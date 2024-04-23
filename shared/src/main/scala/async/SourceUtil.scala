package gears.async

import java.util.concurrent.locks.Lock

private[async] object SourceUtil:
  /** Add a global locking layer to an [[Async.Source]] by transforming every attached listener.
    *
    * @param src
    *   the source to transform
    * @param externalLock
    *   the external lock that secures a global state
    * @param lockedCheck
    *   the check to run in [[ListenerLock.acquire]] with externalLock held. It receives the original [[Listener]] that
    *   was attached to the transformed [[Async.Source]] and the transformed Source itself. If it returns true, locking
    *   succeeds. If it returns false, it is responsible for unlocking the external lock.
    * @return
    *   a new source that wraps [[src]] but adds a layer of locking
    */
  def addExternalLock[T](src: Async.Source[T], externalLock: Lock)(
      lockedCheck: (Listener[T], Async.Source[T]) => Boolean
  ): Async.Source[T] =
    new Async.Source[T]:
      selfSrc =>
      def transform(k: Listener[T]) =
        new Listener.ForwardingListener[T](selfSrc, k):
          val lock =
            if k.lock != null then
              new Listener.ListenerLock {
                override val selfNumber: Long = k.lock.selfNumber

                override def acquire(): Boolean =
                  if !k.lock.acquire() then false
                  else
                    externalLock.lock()
                    lockedCheck(k, selfSrc)

                override def release(): Unit =
                  externalLock.unlock()
                  k.lock.release()
              }
            else
              new Listener.ListenerLock with Listener.NumberedLock {
                override val selfNumber: Long = number

                override def acquire(): Boolean =
                  externalLock.lock()
                  val check = lockedCheck(k, selfSrc)
                  if check then acquireLock() // a wrapping Listener may expect on an exclusive lock
                  check

                override def release(): Unit =
                  releaseLock()
                  externalLock.unlock()
              }
          end lock

          def complete(data: T, source: Async.Source[T]) =
            if k.lock == null then lock.release() // trigger our custom release process if k does not have a lock
            else externalLock.unlock() //            otherwise only unlock the externalLock, as k.complete will do the rest
            k.complete(data, selfSrc)
        end new
      end transform

      override def poll(k: Listener[T]): Boolean =
        src.poll(transform(k))
      override def onComplete(k: Listener[T]): Unit =
        src.onComplete(transform(k))
      override def dropListener(k: Listener[T]): Unit =
        val listener = Listener.ForwardingListener.empty[T](selfSrc, k)
        src.dropListener(listener)
