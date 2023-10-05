package gears.async
import scala.collection.mutable
import Future.Promise
import scala.util.Success

/** A group of cancellable objects that are completed together.
 *  Cancelling the group means cancelling all its uncompleted members.
 *  @param  handleCompletion  a function that gets applied to every member
 *                            when it is completed or cancelled
 */
class CompletionGroup(val handleCompletion: Cancellable => Async ?=> Unit = _ => {}) extends Cancellable:
  private val members: mutable.Set[Cancellable] = mutable.Set()
  private var cancelWait: Option[Promise[Unit]] = None

  /** Cancel all members and clear the members set */
  def cancel(): Unit =
    synchronized(members.toArray).foreach(_.cancel())

  private[async] def waitCompletion()(using Async): Unit =
    synchronized:
      if members.nonEmpty && cancelWait.isEmpty then
        cancelWait = Some(Promise())
    cancelWait.foreach(cWait => Async.await(cWait.future))
    signalCompletion()

  /** Add given member to the members set */
  def add(member: Cancellable): Unit = synchronized:
    members += member

  /** Remove given member from the members set if it is an element */
  def drop(member: Cancellable): Unit = synchronized:
    members -= member
    if members.isEmpty && cancelWait.isDefined then
      cancelWait.get.complete(Success(()))

object CompletionGroup:

  /** A sentinel group of cancellables that are in fact not linked
   *  to any real group. `cancel`, `add`, and `drop` do nothing when
   *  called on this group.
   */
  object Unlinked extends CompletionGroup:
    override def cancel(): Unit = ()
    override def add(member: Cancellable): Unit = ()
    override def drop(member: Cancellable): Unit = ()
  end Unlinked

end CompletionGroup