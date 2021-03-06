package delta.process

abstract class UpdateCodec[S, U] {

  type Snapshot = delta.Snapshot[S]
  type Update = delta.process.Update[U]

  /**
    * Derive update from new state (and optionally old state).
    */
  def asUpdate(prevState: Option[S], currState: S): U

  /**
    * Apply update to old state to produce new state,
    * or return `None` if not possible.
    */
  def asSnapshot(prevState: Option[S], update: U): Option[S]

  final def asUpdate(
      prevSnapshot: Option[Snapshot], currSnapshot: Snapshot, contentUpdated: Boolean)
      : Update = {

    val change = if (contentUpdated) Option {
      asUpdate(prevSnapshot.map(_.state), currSnapshot.state)
    } else None

    new Update(change, currSnapshot.revision, currSnapshot.tick)

  }

  final def asSnapshot(
      prevSnapshot: Option[Snapshot], update: Update)
      : Option[Snapshot] = {

    update.changed match {
      case None =>
        prevSnapshot.map {
          _.copy(revision = update.revision, tick = update.tick)
        }
      case Some(updateContent) =>
        asSnapshot(prevSnapshot.map(_.state), updateContent)
          .map(new Snapshot(_, update.revision, update.tick))
    }

  }

}

object UpdateCodec {
  private val none = new UpdateCodec[Any, Null] {
    def asUpdate(prevState: Option[Any], currState: Any): Null = null
    def asSnapshot(prevState: Option[Any], update: Null): Option[Any] = scala.None
  }
  def None[S, U] = none.asInstanceOf[UpdateCodec[S, U]]

}
