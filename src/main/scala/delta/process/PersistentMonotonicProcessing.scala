package delta.process

import scala.concurrent._
import scuff.concurrent._
import scala.reflect.ClassTag
import scala.collection.concurrent.{ Map => CMap, TrieMap }

/**
 * Default monotonic implementation of [[delta.process.EventSourceProcessing]].
 * Can be started and stopped on demand, without loss of
 * transactions.
 * @note Since replay processing is generally
 * not done in tick order, a tick received
 * through incomplete replay processing
 * is NOT reliable as a high-water mark. In other words,
 * replay processing should not use a persistent
 * [[delta.process.StreamProcessStore]] until replay
 * processing has completed.
 * Any tick received in live processing can be considered a
 * high-water mark (subject to the tick window).
 * @tparam SID The stream id type
 * @tparam EVT The process specific event type
 * @tparam Work The processing (work) state representation
 * @tparam U The update type (often the same as `Work`)
 * @param ec General execution context for light work
 */
abstract class PersistentMonotonicProcessing[SID, EVT: ClassTag, Work >: Null, U]
extends EventSourceProcessing[SID, EVT]
with TransactionProcessor[SID, EVT, Work] {

  override protected type Transaction = delta.Transaction[SID, _ >: EVT]
  protected type Snapshot = delta.Snapshot[Work]
  protected type Update = delta.process.Update[U]

  protected def processStore: StreamProcessStore[SID, Work, U]
  def name = processStore.name

  protected def reportFailure(th: Throwable): Unit

  /**
    * Callback on snapshot updates from live processing.
    * @param id The stream id
    * @param update The snapshot update
    */
  protected def onUpdate(id: SID, update: Update): Unit

  protected def tickWatermark: Option[Tick] = processStore.tickWatermark

  private lazy val defaultThreadGroup =
    Threads.newThreadGroup(
      name = s"${getClass.getSimpleName}-processing",
      daemon = false, reportFailure = this.reportFailure)

  protected def threadGroup = defaultThreadGroup

  /** Partitions on stream id. Defaults to `availableProcessors - 1`. */
  protected def newPartitionedExecutionContext(replay: Boolean): PartitionedExecutionContext = {
    val numThreads = 1 max (Runtime.getRuntime.availableProcessors - 1)
    val suffix = if (replay) "replay" else "live"
    val tg = threadGroup
    val tf = Threads.factory(s"${tg.getName}-$suffix", tg)
    PartitionedExecutionContext(numThreads, tg, tf, _.hashCode)
  }

  /**
    * Instantiate new concurrent map used to hold state during
    * replay processing. This can be overridden to provide a
    * different implementation that e.g. stores to local disk,
    * if data set is too large for in-memory handling.
    */
  protected def newReplayMap: CMap[SID, ConcurrentMapStore.State[Work]] =
    new TrieMap[SID, ConcurrentMapStore.State[Work]]

  protected type LiveResult = Work

  protected class ReplayConsumer(
    config: ReplayProcessConfig,
    protected val executionContext: ExecutionContext)
  extends PersistentMonotonicReplayProcessor[SID, EVT, Work, U](
      processStore,
      config,
      newPartitionedExecutionContext(replay = true), newReplayMap) {
    override protected def process(tx: Transaction, state: Option[Work]): Future[Work] =
      PersistentMonotonicProcessing.this.process(tx, state)

  }

  protected class LiveConsumer(es: EventSource, config: LiveProcessConfig)
  extends PersistentMonotonicProcessor[SID, EVT, Work, U](
      es, config,
      newPartitionedExecutionContext(replay = false)) {

    protected def processStore = PersistentMonotonicProcessing.this.processStore
    protected def onUpdate(id: SID, update: Update) =
      PersistentMonotonicProcessing.this.onUpdate(id, update)
    override protected def process(tx: Transaction, state: Option[Work]): Future[Work] =
      PersistentMonotonicProcessing.this.process(tx, state)

  }

  protected def adHocContext: ExecutionContext
  protected def replayProcessor(es: EventSource, config: ReplayProcessConfig): ReplayProcessor =
    new ReplayConsumer(config, adHocContext)

  protected def liveProcessor(es: EventSource, config: LiveProcessConfig): LiveProcessor =
    new LiveConsumer(es, config)

}

/**
  * Recommended super class for implementing [[delta.EventSource]]
  * consumption.
  * @see [[delta.process.PersistentMonotonicProcessing]] for details.
  */
abstract class PersistentMonotonicConsumer[SID, EVT: ClassTag, Work >: Null, U]
extends PersistentMonotonicProcessing[SID, EVT, Work, U]
with EventSourceConsumer[SID, EVT]
