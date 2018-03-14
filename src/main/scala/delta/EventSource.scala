package delta

import scala.concurrent.Future

import scuff.Subscription
import scuff.StreamConsumer
import scala.annotation.varargs

/**
  * Event source.
  */
trait EventSource[ID, EVT, CH] {

  type TXN = Transaction[ID, EVT, CH]

  def currRevision(stream: ID): Future[Option[Int]]
  def maxTick(): Future[Option[Long]]

  protected[delta] type StreamReplayConsumer[E >: EVT, U] = StreamConsumer[Transaction[ID, E, CH], U]

  /** Replay complete stream. */
  def replayStream[E >: EVT, U](stream: ID)(callback: StreamReplayConsumer[E, U]): Unit
  /** Replay stream from provided revision range. */
  def replayStreamRange[E >: EVT, U](stream: ID, revisionRange: Range)(callback: StreamReplayConsumer[E, U]): Unit
  /** Replay stream from provided revision to current. */
  def replayStreamFrom[E >: EVT, U](stream: ID, fromRevision: Int)(callback: StreamReplayConsumer[E, U]): Unit
  /** Replay stream from revision 0 to specific revision (inclusive). */
  def replayStreamTo[E >: EVT, U](stream: ID, toRevision: Int)(callback: StreamReplayConsumer[E, U]): Unit =
    replayStreamRange(stream, 0 to toRevision)(callback)

  /** Query across streams, optionally providing a selector to filter results. */
  def query[U](selector: Selector = Everything)(callback: StreamConsumer[TXN, U]): Unit
  /** Query across streams, optionally providing a selector to filter results. */
  def querySince[U](sinceTick: Long, selector: Selector = Everything)(callback: StreamConsumer[TXN, U]): Unit

  /** Subscribe to published transactions. */
  def subscribe[U](
      selector: CompleteSelector = Everything)(
      callback: TXN => U): Subscription

  type CEVT = Class[_ <: EVT]

  /** General selector. */
  sealed abstract class Selector {
    def include(txn: TXN): Boolean
    def toComplete: EventSource.this.CompleteSelector
  }
  /** Complete, unbroken stream, selector. */
  sealed abstract class CompleteSelector extends Selector {
    /** Subset of channels, if any. */
    def channelSubset: Set[CH]
  }

  /** All streams, all channels, unbroken. */
  case object Everything extends CompleteSelector {
    def channelSubset: Set[CH] = Set.empty
    def include(txn: TXN) = true
    def toComplete: CompleteSelector = this
  }
  @varargs
  def ChannelSelector(one: CH, others: CH*) =
    new ChannelSelector((one +: others).toSet)
  /** All streams in the provided channels, unbroken. */
  case class ChannelSelector private[EventSource] (channels: Set[CH]) extends CompleteSelector {
    //    def this(one: CH, others: CH*) = this((one +: others).toSet)
    require(channels.nonEmpty)
    def channelSubset: Set[CH] = channels
    def include(txn: TXN) = channels.contains(txn.channel)
    def toComplete: CompleteSelector = this
  }
  @varargs
  def EventSelector(chEvt: (CH, Set[CEVT]), more: (CH, Set[CEVT])*) =
    new EventSelector(Map((chEvt :: more.toList): _*))
  def EventSelector(byChannel: java.util.Map[CH, java.util.Set[CEVT]]) = {
    import collection.JavaConverters._
    new EventSelector(byChannel.asScala.toMap.mapValues(_.asScala.toSet))
  }
  /**
    * Only transactions containing the provided events.
    * This means broken, incomplete, streams. Should only
    * be used if necessary, i.e. on data sets so large that
    * it would otherwise take too long to process.
    * NOTE: Steps should be taken to ensure that the stream
    * revision number is still current, since this is no
    * longer guaranteed.
    */
  case class EventSelector private[EventSource] (byChannel: Map[CH, Set[CEVT]])
    extends Selector {
    require(byChannel.nonEmpty)
    require(byChannel.forall(_._2.nonEmpty), s"No events: ${byChannel.filter(_._2.isEmpty).map(_._1).mkString(", ")}")
    def channelSubset: Set[CH] = byChannel.keySet
    def include(txn: TXN) = byChannel.get(txn.channel).exists { set =>
      txn.events.exists { evt =>
        set.exists { cls =>
          cls isAssignableFrom evt.getClass
        }
      }
    }
    def toComplete: CompleteSelector = ChannelSelector(byChannel.keySet)
  }

  def StreamSelector(stream: ID, channel: CH) = new StreamSelector(stream, channel)
  case class StreamSelector private[EventSource] (stream: ID, channel: CH) extends CompleteSelector {
    def channelSubset: Set[CH] = Set(channel)
    def include(txn: TXN) = txn.stream == stream && txn.channel == channel
    def toComplete: CompleteSelector = this
  }

  object Selector {
    def Everything = EventSource.this.Everything
    @varargs
    def apply(ch: CH, one: CEVT, others: CEVT*) =
      new EventSelector(Map(ch -> (one +: others).toSet))
    @varargs
    def apply(one: CH, others: CH*) =
      ChannelSelector(one, others: _*)
    def apply(stream: ID, channel: CH) =
      new StreamSelector(stream, channel)
  }

}
