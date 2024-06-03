/** Package listeners provide some auxilliary methods to work with listeners. */
package gears.async.listeners

import gears.async._
import Listener.ListenerLock
import scala.annotation.tailrec

/** Two listeners being locked at the same time, while having the same [[Listener.ListenerLock.selfNumber lock number]].
  */
case class ConflictingLocksException(
    listeners: (Listener[?], Listener[?])
) extends Exception

/** Attempt to lock both listeners belonging to possibly different sources at the same time. Lock orders are respected
  * by comparing numbers on every step.
  *
  * Returns `null` on success, or the listener that fails first.
  *
  * @throws ConflictingLocksException
  *   In the case that two locks sharing the same number is encountered, this exception is thrown with the conflicting
  *   listeners.
  */
def lockBoth[T, U](
    lt: Listener[T],
    lu: Listener[U]
): lt.type | lu.type | Null =
  val lockT = if lt.lock == null then return (if lu.acquireLock() then null else lu) else lt.lock
  val lockU = if lu.lock == null then return (if lt.acquireLock() then null else lt) else lu.lock

  inline def doLock[T, U](lt: Listener[T], lu: Listener[U])(
      lockT: ListenerLock,
      lockU: ListenerLock
  ): lt.type | lu.type | Null =
    // assert(lockT.number > lockU.number)
    if !lockT.acquire() then lt
    else if !lockU.acquire() then
      lockT.release()
      lu
    else null

  if lockT.selfNumber == lockU.selfNumber then throw ConflictingLocksException((lt, lu))
  else if lockT.selfNumber > lockU.selfNumber then doLock(lt, lu)(lockT, lockU)
  else doLock(lu, lt)(lockU, lockT)
end lockBoth
