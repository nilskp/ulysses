package delta.read.impl

import scala.concurrent._, duration._

import delta.read._

/**
 * Read model that relies on some externally built store
 * that is continuously updated by ''another thread or process''.
 * The subscription implementation is left out, but can be
 * easily augmented by adding [[delta.read.MessageTransportSupport]]
 * to an instance of this class.
 * @tparam ID The specific identifier type
 * @tparam V The view model type
 * @tparam SID The more general stream identifier
 * @tparam U The update type
 */
abstract class PrebuiltReadModel[ID, V, SID, U](
  protected val name: String,
  protected val defaultReadTimeout: FiniteDuration = DefaultReadTimeout)(
  implicit
  idConv: ID => SID)
extends ReadModel[ID, V]
with SubscriptionSupport[ID, V, U] {

  protected type StreamId = SID
  protected def StreamId(id: ID) = idConv(id)

}

abstract class SimplePrebuiltReadModel[ID, S, SID](
  name: String,
  defaultReadTimeout: FiniteDuration = DefaultReadTimeout)(
  implicit
  idConv: ID => SID)
extends PrebuiltReadModel[ID, S, SID, S](name, defaultReadTimeout) {

  protected def updateState(id: ID, prevState: Option[S], currState: S): Option[S] = Some(currState)

}
