package gears.async.stream

import gears.async.Channel
import gears.async.SendableChannel
import gears.async.ReadableChannel
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import gears.async.Listener
import gears.async.Channel.Res
import gears.async.Async

enum StreamResult[+T]:
  case Closed
  case Failed(val exception: Throwable)
  case Data(val data: T)

trait SendableStreamChannel[-T] extends SendableChannel[T], java.io.Closeable:
  /** Terminate the channel with a given termination value. No more send operations (using [[sendSource]] or [[send]])
    * will be allowed afterwards. If the stream channel was terminated before, this does nothing. Especially, it does
    * not replace the termination value.
    *
    * @param value
    *   [[StreamResult.Closed]] to signal completion, or [[StreamResult.Failed]] to signal a failure
    * @return
    *   true iff this channel was terminated by that call with the given value
    */
  def terminate(value: StreamResult.Closed.type | StreamResult.Failed[?]): Boolean

  /** Close the channel now. Does nothing if the channel is already terminated.
    */
  override def close(): Unit = terminate(StreamResult.Closed)

  /** Close the channel now with a failure. Does nothing if the channel is already terminated.
    *
    * @param exception
    *   the exception that will constitute the termination value
    */
  def fail(exception: Throwable): Unit = terminate(StreamResult.Failed(exception))

trait ReadableStreamChannel[+T]:
  /** An [[Async.Source]] corresponding to items being sent over the channel. Note that *each* listener attached to and
    * accepting a [[StreamResult.Data]] value corresponds to one value received over the channel.
    *
    * To create an [[Async.Source]] that reads *exactly one* item regardless of listeners attached, wrap the
    * [[readStream]] operation inside a [[gears.async.Future]].
    * {{{
    * val readOnce = Future(ch.readStream(x))
    * }}}
    */
  val readStreamSource: Async.Source[StreamResult[T]]

  /** Read an item from the channel, suspending until the item has been received.
    */
  def readStream()(using Async): StreamResult[T] = readStreamSource.awaitResult

/** An variant of a channel that provides an error termination (to signal a failure condition by a sender to channel
  * readers). Furthermore, both successful (close) or failure termination are
  *   - sequential with elements, i.e., elements sent before the termination will be supplied to readers before they see
  *     the termination.
  *   - persistent, i.e., any further read attempt after the last element has been consumed, will obtain the termination
  *     value.
  *
  * All [[StreamResult.Data]] messages that accepted, will be delivered. No more messages (neither elements nor
  * termination) will be accepted after the termination.
  * @see
  *   Channel
  */
trait StreamChannel[T] extends SendableStreamChannel[T], ReadableStreamChannel[T]

/** An implementation of [[StreamChannel]] using any [[Channel]] as underlying means of communication. That channel is
  * closed as soon as the first reader obtains a termination value, i.e., a [[StreamResult.Closed]] or a
  * [[StreamResult.Failed]].
  *
  * The channel implementation must observe that, if a listener on a [[Channel.sendSource]](`a`) is completed before a
  * listener on a [[Channel.sendSource]](`b`), then some listener on the [[Channel.readSource]] must be completed with
  * `a` before one is completed with `b`.
  *
  * @param channel
  *   the channel that implements the actual communication. It should not be used directly.
  */
class GenericStreamChannel[T](private val channel: Channel[StreamResult[T]]) extends StreamChannel[T]:
  private var finalResult: StreamResult[T] = null // access is synchronized with [[closeLock]]
  private val closeLock: ReadWriteLock = new ReentrantReadWriteLock()

  private def sendSource0(result: StreamResult[T]): Async.Source[Res[Unit]] =
    val src = channel.sendSource(result)
    new Async.Source[Res[Unit]]:
      selfSrc =>
      def transform(k: Listener[Res[Unit]]) =
        new Listener.ForwardingListener[Res[Unit]](selfSrc, k):
          // In both implementations (wrapping ListenerLock or top-level), acquire will succeed if either:
          //  - finalResult is null, therefore terminate will wait for the writeLock until our release/complete, or
          //  - we are sending the termination message right now (only done once if justTerminated in terminate).
          // If we try to lock a non-termination-sender after termination, the lock attempt by src is rejected
          //    and the downstream listener k is completed with a Left(Closed).
          val lock =
            if k.lock != null then
              new Listener.ListenerLock {
                override val selfNumber: Long = k.lock.selfNumber

                override def acquire(): Boolean =
                  if !k.lock.acquire() then false
                  else
                    closeLock.readLock().lock()
                    if finalResult != null && result.isInstanceOf[StreamResult.Data[?]] then
                      closeLock.readLock().unlock()
                      k.complete(Left(Channel.Closed), selfSrc) // k.lock is already acquired
                      false
                    else true

                override def release(): Unit =
                  closeLock.readLock().unlock()
                  k.lock.release()
              }
            else
              new Listener.ListenerLock with Listener.NumberedLock {
                override val selfNumber: Long = number

                override def acquire(): Boolean =
                  closeLock.readLock().lock()
                  if finalResult != null && result.isInstanceOf[StreamResult.Data[?]] then
                    closeLock.readLock().unlock()
                    k.complete(Left(Channel.Closed), selfSrc) // k.lock is null
                    false
                  else
                    acquireLock() // a wrapping Listener may expect on an exclusive lock
                    true

                override def release(): Unit =
                  releaseLock()
                  closeLock.readLock().unlock()
              }
          end lock

          def complete(data: Res[Unit], source: Async.Source[Res[Unit]]) =
            if k.lock == null then lock.release() // trigger our custom release process if k does not have a lock
            else closeLock.readLock().unlock() //    otherwise only unlock the closeLock, as k.complete will do the rest
            // this order implies that a send-attached Listener may learn about a successful send possibly after the send-end has been closed
            k.complete(data, selfSrc)
        end new
      end transform

      def poll(k: Listener[Res[Unit]]): Boolean =
        src.poll(transform(k))
      def onComplete(k: Listener[Res[Unit]]): Unit =
        src.onComplete(transform(k))
      def dropListener(k: Listener[Res[Unit]]): Unit =
        val listener = Listener.ForwardingListener.empty[Res[Unit]](selfSrc, k)
        src.dropListener(listener)
  end sendSource0

  override def sendSource(x: T): Async.Source[Res[Unit]] = sendSource0(StreamResult.Data(x))

  override val readStreamSource: Async.Source[StreamResult[T]] =
    channel.readSource.transformValuesWith {
      case Left(_) => finalResult
      case Right(value) =>
        if !value.isInstanceOf[StreamResult.Data[?]] then channel.close()
        value
    }

  override def terminate(value: StreamResult.Closed.type | StreamResult.Failed[?]): Boolean =
    val theValue = value.asInstanceOf[StreamResult[T]] // [[Failed]] contains useless generic

    closeLock.writeLock().lock()
    val justTerminated = if finalResult == null then
      finalResult = theValue
      true
    else false
    closeLock.writeLock().unlock()

    if justTerminated then sendSource0(theValue).onComplete(Listener.acceptingListener { (_, _) => })
    justTerminated
  end terminate
end GenericStreamChannel

object BufferedStreamChannel:
  /** Create a [[StreamChannel]] operating on an internal buffer. It works exactly like [[BufferedChannel]] except for
    * the termination behavior of [[StreamChannel]]s.
    *
    * @param size
    *   the capacity of the internal buffer
    * @return
    *   a new stream channel on a new buffer
    */
  def apply[T](size: Int): StreamChannel[T] = Impl(size)

  // notable differences to BufferedChannel.Impl:
  //  - finalResults for check and to replace Left(Closed) on read --> new checkSendClosed method
  //  - in pollRead, close checking moved from 'before buffer checking' to 'after buffer checking'
  private class Impl[T](size: Int) extends Channel.Impl[T] with StreamChannel[T]:
    require(size > 0, "Buffered channels must have a buffer size greater than 0")
    val buf = new scala.collection.mutable.Queue[T](size)
    var finalResult: StreamResult[T] = null

    // Match a reader -> check space in buf -> fail
    override def pollSend(src: CanSend, s: Sender): Boolean = synchronized:
      checkSendClosed(src, s) || cells.matchSender(src, s) || senderToBuf(src, s)

    // Check space in buf -> fail
    // If we can pop from buf -> try to feed a sender
    override def pollRead(r: Reader): Boolean = synchronized:
      if !buf.isEmpty then
        if r.completeNow(Right(buf.head), readSource) then
          buf.dequeue()
          if cells.hasSender then
            val (src, s) = cells.nextSender
            cells.dequeue() // buf always has space available after dequeue
            senderToBuf(src, s)
        true
      else checkSendClosed(readSource, r)

    // if the stream is terminated, complete the listener and indicate listener completion (true)
    def checkSendClosed[V](src: Async.Source[Res[V]], l: Listener[Res[V]]): Boolean =
      if finalResult != null then
        l.completeNow(Left(Channel.Closed), src)
        true
      else false

    // Try to add a sender to the buffer
    def senderToBuf(src: CanSend, s: Sender): Boolean =
      if buf.size < size then
        if s.completeNow(Right(()), src) then buf += src.item
        true
      else false

    override val readStreamSource: Async.Source[StreamResult[T]] = readSource.transformValuesWith {
      case Left(_)      => finalResult
      case Right(value) => StreamResult.Data(value)
    }

    override def terminate(value: StreamResult.Closed.type | StreamResult.Failed[?]): Boolean = synchronized:
      if finalResult == null then
        finalResult = value.asInstanceOf[StreamResult[T]]
        cells.cancel()
        true
      else false
  end Impl
end BufferedStreamChannel
