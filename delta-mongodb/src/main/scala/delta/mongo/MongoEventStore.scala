package delta.mongo

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.annotation.varargs
import scala.jdk.CollectionConverters._
import scala.util._

import org.bson.{ Document, BsonValue }
import org.bson.codecs.{ Codec => BsonCodec, DecoderContext, EncoderContext }
import org.bson.codecs.configuration.{ CodecRegistries, CodecRegistry, CodecConfigurationException }

import com.mongodb._
import com.mongodb.async.SingleResultCallback
import com.mongodb.async.client.MongoCollection
import com.mongodb.async.client.{ MongoClient, MongoClients }
import com.mongodb.connection.ClusterType
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY

import scuff._
import scuff.concurrent.Threads

import delta._

object MongoEventStore {
  @varargs
  def getCollection(
    ns: MongoNamespace, settings: MongoClientSettings,
    codecs: BsonCodec[_]*): MongoCollection[Document] = {
    val client = MongoClients.create(settings)
    if (codecs.isEmpty) getCollection(ns, settings, client, settings.getCodecRegistry)
    else getCollection(ns, settings, client, CodecRegistries.fromCodecs(codecs: _*))
  }
  @varargs
  def getCollection(
    ns: MongoNamespace, settings: MongoClientSettings, client: MongoClient,
    codecs: BsonCodec[_]*): MongoCollection[Document] = {
    if (codecs.isEmpty) getCollection(ns, settings, client, settings.getCodecRegistry)
    else getCollection(ns, settings, client, CodecRegistries.fromCodecs(codecs: _*))
  }
  def getCollection(
    ns: MongoNamespace, settings: MongoClientSettings, client: MongoClient, optRegistry: CodecRegistry): MongoCollection[Document] = {
    val (rc, wc) = settings.getClusterSettings.getRequiredClusterType match {
      case ClusterType.REPLICA_SET => ReadConcern.MAJORITY -> WriteConcern.MAJORITY
      case _ => ReadConcern.DEFAULT -> WriteConcern.JOURNALED
    }
    val registries = Seq(optRegistry, settings.getCodecRegistry, DEFAULT_CODEC_REGISTRY).filter(_ != null)
    val registry = CodecRegistries.fromRegistries(registries.asJava)
    client
      .getDatabase(ns.getDatabaseName)
      .getCollection(ns.getCollectionName)
      .withReadConcern(rc)
      .withWriteConcern(wc)
      .withCodecRegistry(registry)
  }

}

/**
  * Stores events, by default using this format:
  * {{{
  *   {
  *     _id: { // Indexed
  *       stream: 34534, // Stream identifier
  *       rev: 11, // Stream revision
  *     }
  *     tick: 1426320727122, // Clock tick.
  *     channel: "FooBar", // App specific type
  *     events: [{
  *      name: "MyEvent", // Event name
  *      v: 1, // Event version
  *      data: {} // App specific data
  *     }]
  *   }
  * }}}
  */
abstract class MongoEventStore[ID: BsonCodec, EVT](
  docCollection: MongoCollection[Document],
  evtFmt: EventFormat[EVT, BsonValue],
  overrideTransactionCodec: BsonCodec[delta.Transaction[ID, EVT]] = null)(
  initTicker: MongoEventStore[ID, EVT] => Ticker)
extends delta.EventStore[ID, EVT] {

  def ensureIndexes(ensureIndexes: Boolean = true): this.type = {
    if (ensureIndexes) {
      withBlockingCallback[String]() {
        txCollection.createIndex(new Document("_id.stream", 1).append("_id.rev", 1), _)
      }
      withBlockingCallback[String]() {
        txCollection.createIndex(new Document("tick", 1), _)
      }
      withBlockingCallback[String]() {
        txCollection.createIndex(new Document("channel", 1), _)
      }
      withBlockingCallback[String]() {
        txCollection.createIndex(new Document("events.name", 1), _)
      }
    }
    this
  }
  lazy val ticker = initTicker(this)

  def codecRegistry: CodecRegistry = txCollection.getCodecRegistry

  protected val txCollection: MongoCollection[Transaction] = {
    val txCodec = Option(overrideTransactionCodec) getOrElse new DefaultTransactionCodec(
      docCollection.getCodecRegistry.get(classOf[BsonValue])
        .ensuring(_ != null, "No BsonValue codec found in codec registry!"))
    val registry = Try(docCollection.getCodecRegistry.get(classOf[Transaction])) match {
      case Success(_) =>
        docCollection.getCodecRegistry
      case Failure(_: CodecConfigurationException) =>
        CodecRegistries.fromRegistries(
          CodecRegistries.fromCodecs(
            implicitly[BsonCodec[ID]],
            txCodec),
          docCollection.getCodecRegistry)
      case Failure(cause) => throw cause
    }

    docCollection.withCodecRegistry(registry).withDocumentClass(classOf[Transaction])
  }

  private[this] val OrderByRevision = new Document("_id.rev", 1)

  def currRevision(stream: ID): Future[Option[Int]] = {
    withFutureCallback[Document] { callback =>
      txCollection.find(new Document("_id.stream", stream), classOf[Document])
        .projection(new Document("_id.rev", true))
        .sort(new Document("_id.rev", -1))
        .limit(1)
        .first(callback)
    }.map { optDoc =>
      optDoc.map { doc =>
        doc.get("_id", classOf[Document]).getInteger("rev").intValue
      }
    }(Threads.PiggyBack) // map revision on the same thread
  }

  def replayStream[R](stream: ID)(callback: StreamConsumer[Transaction, R]): Unit = {
    queryWith(new Document("_id.stream", stream), callback, OrderByRevision)
  }

  def replayStreamFrom[R](stream: ID, fromRevision: Revision)(callback: StreamConsumer[Transaction, R]): Unit = {
    val filter = new Document("_id.stream", stream)
    if (fromRevision > 0) {
      filter.append("_id.rev", new Document("$gte", fromRevision))
    }
    queryWith(filter, callback, OrderByRevision)
  }
  def replayStreamRange[R](stream: ID, revisionRange: Range)(callback: StreamConsumer[Transaction, R]): Unit = {
    require(revisionRange.step == 1, s"Revision range must step by 1 only, not ${revisionRange.step}")
    val filter = new Document("_id.stream", stream)
    val from = revisionRange.head
    val to = revisionRange.last
    if (from == to) {
      filter.append("_id.rev", from)
    } else if (from == 0) {
      filter.append("_id.rev", new Document("$lte", to))
    } else {
      val range = new Document("$gte", from).append("$lte", to)
      filter.append("_id.rev", range)
    }
    queryWith(filter, callback, OrderByRevision)
  }

  def commit(
    channel: Channel, stream: ID, revision: Revision, tick: Tick,
    events: List[EVT], metadata: Map[String, String]): Future[Transaction] = {
    val tx = Transaction(tick, channel, stream, revision, metadata, events)
    val insertFuture = withFutureCallback[Void] { callback =>
      txCollection.insertOne(tx, callback)
    }.map(_ => tx)(Threads.PiggyBack)
    insertFuture.recoverWith {
      case e: MongoWriteException if e.getError.getCategory == ErrorCategory.DUPLICATE_KEY =>
        withFutureCallback[Transaction] { callback =>
          txCollection.find(new Document("_id.stream", stream).append("_id.rev", revision))
            .limit(1)
            .first(callback)
        }.map(conflicting => throw new DuplicateRevisionException(conflicting.get))(Threads.PiggyBack)
    }(Threads.PiggyBack)
  }

  protected def queryWith[U](
      filter: Document, callback: StreamConsumer[Transaction, U], ordering: Document = null): Unit = {
    val onTx = new Block[Transaction] {
      def apply(tx: Transaction) = callback.onNext(tx)
    }
    val onFinish = new SingleResultCallback[Void] {
      def onResult(result: Void, t: Throwable): Unit = {
        if (t != null) callback.onError(t)
        else callback.onDone()
      }
    }
    txCollection.find(filter).sort(ordering).forEach(onTx, onFinish)
  }

  def maxTick: Future[Option[Long]] = getFirst[Long]("tick", reverse = true)

  private def getFirst[T](name: String, reverse: Boolean): Future[Option[T]] = {
    withFutureCallback[Document] { callback =>
      txCollection.find(new Document, classOf[Document])
        .projection(new Document(name, true).append("_id", false))
        .sort(new Document(name, if (reverse) -1 else 1))
        .limit(1)
        .first(callback)
    }.map { optDoc =>
      optDoc.map(_.get(name).asInstanceOf[T])
    }(Threads.PiggyBack) // map first on same thread
  }

  private def toJList[T](iter: Iterable[T]): java.util.List[T] = {
    val list = new java.util.ArrayList[T](8)
    iter.foreach { e =>
      list add e
    }
    list
  }

  private def toDoc(streamFilter: Selector, docFilter: Document = new Document): Document = {
    streamFilter match {
      case Everything => // Ignore
      case ChannelSelector(channels) =>
        docFilter.append("channel", new Document("$in", toJList(channels)))
      case EventSelector(byChannel) =>
        val matchByChannel = byChannel.toSeq.map {
          case (ch, eventTypes) =>
            val matcher = new Document("channel", ch)
            val evtNames = eventTypes.map(evtFmt.signature(_).name)
            matcher.append("events.name", new Document("$in", toJList(evtNames)))
        }
        if (matchByChannel.size == 1) {
          import scala.jdk.CollectionConverters._
          matchByChannel.head.asScala.foreach { entry =>
            docFilter.append(entry._1, entry._2)
          }
        } else {
          docFilter.append("$or", toJList(matchByChannel))
        }
      case SingleStreamSelector(id, _) =>
        docFilter.append("_id.stream", id)
    }
    docFilter
  }

  def query[U](streamFilter: Selector)(callback: StreamConsumer[Transaction, U]): Unit = {
    queryWith(toDoc(streamFilter), callback)
  }

  def querySince[U](sinceTick: Tick, streamFilter: Selector)(callback: StreamConsumer[Transaction, U]): Unit = {
    val docFilter = new Document("tick", new Document("$gte", sinceTick))
    queryWith(toDoc(streamFilter, docFilter), callback)
  }

  private class DefaultTransactionCodec(bsonCodec: BsonCodec[BsonValue])
      extends BsonCodec[Transaction] {

    import org.bson.{ BsonReader, BsonType, BsonWriter }
    implicit def tag2class[T](tag: ClassTag[T]): Class[T] =
      tag.runtimeClass.asInstanceOf[Class[T]]
    def getEncoderClass = classOf[Transaction]
    private[this] val idCodec = implicitly[BsonCodec[ID]]

    private def writeDocument(writer: BsonWriter, name: String = null)(thunk: => Unit): Unit = {
      if (name != null) writer.writeStartDocument(name) else writer.writeStartDocument()
      thunk
      writer.writeEndDocument()
    }
    private def writeArray(name: String, writer: BsonWriter)(thunk: => Unit): Unit = {
      writer.writeStartArray(name)
      thunk
      writer.writeEndArray()
    }
    def encode(writer: BsonWriter, tx: Transaction, ctx: EncoderContext): Unit = {
      writer.writeStartDocument()
      writeDocument(writer, "_id") {
        writer.writeName("stream"); idCodec.encode(writer, tx.stream, ctx)
        writer.writeInt32("rev", tx.revision)
      }
      writer.writeInt64("tick", tx.tick)
      writer.writeString("channel", tx.channel.toString)
      if (tx.metadata.nonEmpty) {
        writeDocument(writer, "metadata") {
          tx.metadata.foreach {
            case (key, value) =>
              writer.writeString(key, value)
          }
        }
      }
      writeArray("events", writer) {
        tx.events.foreach { evt =>
          writeDocument(writer) {
            val EventFormat.EventSig(name, version) = evtFmt signature evt
            writer.writeString("name", name)
            if (version != evtFmt.NoVersion) {
              writer.writeInt32("v", version.unsigned)
            }
            writer.writeName("data"); bsonCodec.encode(writer, evtFmt.encode(evt), ctx)
          }
        }
      }
      writer.writeEndDocument()
    }
    private def readDocument[R](reader: BsonReader, name: String = null)(thunk: => R): R = {
      if (name != null) reader.readName(name)
      reader.readStartDocument()
      val r = thunk
      reader.readEndDocument()
      r
    }
    private def readArray[R](reader: BsonReader, name: String = null)(thunk: => R): R = {
      if (name != null) reader.readName(name)
      reader.readStartArray()
      val r = thunk
      reader.readEndArray()
      r
    }
    @annotation.tailrec
    private def readMetadata(reader: BsonReader, map: Map[String, String] = Map.empty): Map[String, String] = {
      if (reader.readBsonType() == BsonType.END_OF_DOCUMENT) {
        map
      } else {
        val name = reader.readName
        val value = reader.readString
        readMetadata(reader, map.updated(name, value))
      }
    }

    @annotation.tailrec
    private def readEvents(channel: Channel, metadata: Map[String, String], reader: BsonReader, events: List[EVT] = Nil)(
        implicit ctx: DecoderContext): List[EVT] = {
      if (reader.readBsonType() == BsonType.END_OF_DOCUMENT) {
        events.reverse
      } else {
        val evt = readDocument(reader) {
          val name = reader.readString("name")
          val version = reader.readName() match {
            case "v" =>
              val version = reader.readInt32().toByte
              reader.readName("data")
              version
            case "data" => evtFmt.NoVersion
            case unexpected => sys.error(s"Unexpected name: $unexpected")
          }
          val data = bsonCodec.decode(reader, ctx)
          evtFmt.decode(name, version, data, channel, metadata)
        }
        readEvents(channel, metadata, reader, evt :: events)
      }
    }
    def decode(reader: BsonReader, ctx: DecoderContext): Transaction = {
        implicit def decCtx = ctx
      reader.readStartDocument()
      val (id, rev) = readDocument(reader, "_id") {
        reader.readName("stream")
        idCodec.decode(reader, ctx) -> reader.readInt32("rev")
      }
      val tick = reader.readInt64("tick")
      val channel = Channel(reader.readString("channel"))
      val (metadata, events) = reader.readName match {
        case "metadata" =>
          val metadata = readDocument(reader)(readMetadata(reader))
          metadata -> readArray(reader, "events") {
            readEvents(channel, metadata, reader)
          }
        case "events" =>
          val metadata = Map.empty[String, String]
          metadata -> readArray(reader) {
            readEvents(channel, metadata, reader)
          }
        case other => throw new IllegalStateException(s"Unknown field: $other")
      }
      reader.readEndDocument()
      Transaction(tick, channel, id, rev, metadata, events)
    }

  }

}
