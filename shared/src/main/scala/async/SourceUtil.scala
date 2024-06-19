package gears.async

import java.util.concurrent.locks.Lock

private[async] object SourceUtil:
  /** Create a Source from an upstream Source by transforming attached [[Listener]]s with
    * [[Listener.ForwardingListener]]
    *
    * @param src
    *   the upstream source to which to forward requests
    */
  trait DerivedSource[+T, V](val src: Async.Source[V]) extends Async.Source[T]:
    /** Transform a Listener attached to [[poll]] and [[onComplete]]. It should extend
      * `Listener.ForwardingListener(this, k)`
      *
      * @param k
      *   the Listener attached to this Source
      * @return
      *   the Listener to attach to the upstream Source
      */
    def transform(k: Listener[T]): Listener[V]

    override def poll(k: Listener[T]): Boolean =
      src.poll(transform(k))
    override def onComplete(k: Listener[T]): Unit =
      src.onComplete(transform(k))
    override def dropListener(k: Listener[T]): Unit =
      val listener = Listener.ForwardingListener.empty[V](this, k)
      src.dropListener(listener)

  /** Add a global locking layer to an [[Async.Source]] by transforming every attached listener.
    *
    * @param src
    *   the source to transform
    * @param externalLock
    *   the external lock that secures a global state
    */
  abstract class ExternalLockedSource[T](src: Async.Source[T], externalLock: Lock) extends DerivedSource[T, T](src):

    /** Check to run in [[ListenerLock.acquire]] with k's lock and externalLock held. If it returns false, it is
      * responsible for unlocking the external lock and releasing k.
      *
      * @param k
      *   the original [[Listener]] that was attached to the transformed [[Async.Source]]
      * @return
      *   whether locking succeeds
      */
    def lockedCheck(k: Listener[T]): Boolean

    def complete(k: Listener.ForwardingListener[T], data: T, source: Async.Source[T]): Unit =
      if k.inner.lock == null then k.lock.release() // trigger our custom release process if k does not have a lock
      else externalLock.unlock() //                    otherwise only unlock the externalLock, as k.inner.complete will do the rest
      k.inner.asInstanceOf[Listener[T]].complete(data, ExternalLockedSource.this)

    override def transform(k: Listener[T]) =
      new Listener.ForwardingListener[T](this, k):
        val lock =
          if k.lock != null then
            new Listener.ListenerLock {
              override val selfNumber: Long = k.lock.selfNumber

              override def acquire(): Boolean =
                if !k.lock.acquire() then false
                else
                  externalLock.lock()
                  lockedCheck(k)

              override def release(): Unit =
                externalLock.unlock()
                k.lock.release()
            }
          else
            new Listener.ListenerLock with Listener.NumberedLock {
              override val selfNumber: Long = number

              override def acquire(): Boolean =
                externalLock.lock()
                val check = lockedCheck(k)
                if check then acquireLock() // a wrapping Listener may expect on an exclusive lock
                check

              override def release(): Unit =
                releaseLock()
                externalLock.unlock()
            }
        end lock

        def complete(data: T, source: Async.Source[T]) = ExternalLockedSource.this.complete(this, data, source)
    end transform
