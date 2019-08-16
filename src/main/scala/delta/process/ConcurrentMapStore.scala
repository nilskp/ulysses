package delta.process

import collection.Map
import collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal
import scuff.concurrent.Threads

/**
 * [[delta.process.StreamProcessStore]] implementation that stores snapshots in a
 * [[scala.collection.concurrent.Map]].
 * Useful when doing replay processing of past events, as a fast local memory store.
 * If the backing map is either empty or incomplete (this would be expected, to
 * save both memory and load time), provide a fallback lookup mechanism for keys
 * not found.
 * NOTE: This implementation IS NOT a two-way cache. There's no mechanism
 * to write through.
 * @param cmap The concurrent map implementation
 * @param lookupFallback Persistent store fallback
 */
final class ConcurrentMapStore[K, V](
    cmap: collection.concurrent.Map[K, delta.Snapshot[V]],
    val tickWatermark: Option[Long])(
    readFallback: K => Future[Option[delta.Snapshot[V]]])
  extends StreamProcessStore[K, V] with NonBlockingCASWrites[K, V] {

  def this(
      cmap: collection.concurrent.Map[K, delta.Snapshot[V]],
      backingStore: StreamProcessStore[K, V]) =
    this(cmap, backingStore.tickWatermark)(
      backingStore.read)

  def this(
      cmap: java.util.concurrent.ConcurrentMap[K, delta.Snapshot[V]],
      backingStore: StreamProcessStore[K, V]) =
    this(cmap.asScala, backingStore.tickWatermark)(
      backingStore.read)

  def this(
      maxTick: Option[java.lang.Long],
      cmap: java.util.concurrent.ConcurrentMap[K, delta.Snapshot[V]],
      readFallback: K => Future[Option[delta.Snapshot[V]]]) =
    this(cmap.asScala, maxTick.map(_.longValue))(readFallback)

  private[this] val unknownKeys = new collection.concurrent.TrieMap[K, Unit]

  @annotation.tailrec
  private def trySave(key: K, snapshot: Snapshot): Option[Snapshot] = {
    cmap.putIfAbsent(key, snapshot) match {
      case None => // Success
        unknownKeys.remove(key)
        None
      case Some(existing) =>
        if (snapshot.revision > existing.revision || (snapshot.revision == existing.revision && snapshot.tick >= existing.tick)) { // replace with later revision
          if (!cmap.replace(key, existing, snapshot)) {
            trySave(key, snapshot)
          } else None
        } else {
          Some(existing)
        }
    }
  }
  def write(key: K, snapshot: Snapshot) = try {
    trySave(key, snapshot) match {
      case None =>
        StreamProcessStore.UnitFuture
      case Some(existing) =>
        Future failed Exceptions.writeOlder(key, existing, snapshot)
    }
  } catch {
    case NonFatal(th) => Future failed th
  }

  def read(key: K): Future[Option[Snapshot]] = cmap.get(key) match {
    case found @ Some(_) => Future successful found
    case None =>
      if (unknownKeys contains key) StreamProcessStore.NoneFuture
      else readFallback(key).map {
        case fallbackSnapshot @ Some(snapshot) =>
          cmap.putIfAbsent(key, snapshot) orElse fallbackSnapshot
        case None =>
          unknownKeys.update(key, ())
          None
      }(Threads.PiggyBack)
  }

  def readBatch(keys: Iterable[K]): Future[Map[K, Snapshot]] = {
      implicit def ec = Threads.PiggyBack
    val readFutures: Seq[Future[Option[(K, Snapshot)]]] =
      keys.map { key =>
        read(key).map {
          _.map(key -> _)
        }
      }.toSeq
    val futureReads: Future[Seq[Option[(K, Snapshot)]]] = Future.sequence(readFutures)
    futureReads.map {
      _.flatten.toMap
    }
  }

  def writeBatch(map: Map[K, Snapshot]): Future[Unit] = {
    map.foreach {
      case (key, snapshot) => write(key, snapshot)
    }
    StreamProcessStore.UnitFuture
  }
  def refresh(key: K, revision: Int, tick: Long): Future[Unit] = {
    cmap.get(key).foreach { snapshot =>
      trySave(key, snapshot.copy(revision = revision, tick = tick))
    }
    StreamProcessStore.UnitFuture
  }
  def refreshBatch(revisions: Map[K, (Int, Long)]): Future[Unit] = {
    revisions.foreach {
      case (key, (revision, tick)) => refresh(key, revision, tick)
    }
    StreamProcessStore.UnitFuture
  }

  def writeIfAbsent(key: K, snapshot: Snapshot): Future[Option[Snapshot]] = {
    Future successful cmap.putIfAbsent(key, snapshot)
  }
  def writeReplacement(key: K, oldSnapshot: Snapshot, newSnapshot: Snapshot): Future[Option[Snapshot]] = {
    if (cmap.replace(key, oldSnapshot, newSnapshot)) StreamProcessStore.NoneFuture
    else cmap.get(key) match {
      case existing @ Some(_) => Future successful existing
      case _ => Future fromTry Try(sys.error(s"Cannot refresh non-existent key: $key"))
    }
  }

}