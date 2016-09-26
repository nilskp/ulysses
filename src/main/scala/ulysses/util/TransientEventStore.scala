package ulysses.util

import ulysses._
import scuff._
import java.util.Date
import concurrent._
import scala.util._
import java.util.concurrent.TimeUnit
import scala.annotation.implicitNotFound
import language.implicitConversions
import scala.concurrent._
import collection.{ Seq => aSeq, Map => aMap }
import collection.concurrent.TrieMap

/**
  * Non-persistent implementation, probably only useful for testing.
  */
class TransientEventStore[ID, EVT, CH, SF](
    execCtx: ExecutionContext)(implicit codec: EventCodec[EVT, SF])
    extends EventStore[ID, EVT, CH] {

  private def Txn(id: ID, rev: Int, ch: CH, tick: Long, metadata: Map[String, String], events: Seq[EVT]): Txn = {
    val eventsSF = events.map { evt =>
      (codec.name(evt), codec.version(evt), codec.encode(evt))
    }
    new Txn(id, rev, ch, tick, metadata, eventsSF.toVector)
  }
  private class Txn(
      id: ID,
      val rev: Int,
      ch: CH,
      val tick: Long,
      metadata: Map[String, String],
      eventsSF: Vector[(String, Short, SF)]) {
    def toTransaction: TXN = {
      val events = eventsSF.map {
        case (name, version, data) =>
          codec.decode(name, version, data)
      }
      Transaction(tick, ch, id, rev, metadata, events)
    }
  }

  @inline
  implicit private def ec = execCtx

  protected def publishCtx = execCtx

  private[this] val txnMap = new TrieMap[ID, Vector[Txn]]

  def lastTick: Future[Option[Long]] = {
    val ticks = Future(txnMap.values.iterator.flatten.map(_.tick))
    ticks.map { ticks =>
      if (ticks.isEmpty) None
      else Some(ticks.max)
    }
  }
  private def findCurrentRevision(id: ID): Option[Int] = txnMap.get(id).map(_.last.rev)

  def currRevision(stream: ID): Future[Option[Int]] = Future(findCurrentRevision(stream))

  def commit(
    channel: CH, stream: ID, revision: Int, tick: Long,
    events: aSeq[EVT], metadata: aMap[String, String]): Future[TXN] = Future {
    val transactions = txnMap.getOrElse(stream, Vector[Txn]())
    val expectedRev = transactions.size
    if (revision == expectedRev) {
      val txn = Txn(stream, revision, channel, tick, metadata.toMap, events)
      if (revision == 0) {
        txnMap.putIfAbsent(stream, transactions :+ txn).foreach { existing =>
          throw new DuplicateRevisionException(existing(0).toTransaction)
        }
      } else {
        val success = txnMap.replace(stream, transactions, transactions :+ txn)
        if (!success) {
          throw new DuplicateRevisionException(txnMap(stream)(revision).toTransaction)
        }
      }
      txn.toTransaction
    } else if (expectedRev > revision) {
      throw new DuplicateRevisionException(transactions(revision).toTransaction)
    } else {
      throw new IllegalStateException(s"$stream revision $revision too large, expected $expectedRev")
    }
  }

  private def withCallback(callback: StreamCallback[TXN])(thunk: => Unit): Unit =
    Future(thunk).onComplete {
      case Success(_) => callback.onCompleted()
      case Failure(t) => callback.onError(t)
    }

  def replayStream(stream: ID)(callback: StreamCallback[TXN]): Unit = withCallback(callback) {
    val txns = txnMap.getOrElse(stream, Vector.empty)
    txns.map(_.toTransaction).foreach(callback.onNext)
  }
  def replayStreamFrom(stream: ID, fromRevision: Int)(callback: StreamCallback[TXN]): Unit =
    replayStreamRange(stream, fromRevision to Int.MaxValue)(callback)
  def replayStreamRange(stream: ID, revisionRange: collection.immutable.Range)(callback: StreamCallback[TXN]): Unit = withCallback(callback) {
    val txns = txnMap.getOrElse(stream, Vector.empty)
    val sliced = revisionRange.last match {
      case Int.MaxValue => txns.drop(revisionRange.head)
      case last => txns.slice(revisionRange.head, last + 1)
    }
    sliced.map(_.toTransaction).foreach(callback.onNext)
  }
  def replay(filter: StreamFilter[ID, EVT, CH])(callback: StreamCallback[TXN]): Unit = withCallback(callback) {
    txnMap.valuesIterator.flatten.map(_.toTransaction)
      .filter(filter.allowed)
      .toSeq.sortBy(_.tick)
      .foreach(callback.onNext _)
  }
  def replaySince(sinceTick: Long, filter: StreamFilter[ID, EVT, CH])(callback: StreamCallback[TXN]): Unit = withCallback(callback) {
    txnMap.valuesIterator.flatten.map(_.toTransaction)
      .filter(txn => filter.allowed(txn) && txn.tick >= sinceTick)
      .toSeq.sortBy(_.tick).foreach(callback.onNext _)
  }
}
