package gears.async.stream

import gears.async.Channel
import gears.async.SendableChannel
import gears.async.ReadableChannel
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import gears.async.Listener
import gears.async.Channel.Res
import gears.async.Async
import gears.async.SourceUtil
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import gears.async.ChannelSender
import StreamResult.StreamResult

object StreamResult:
  object Closed
  type Closed = Closed.type

  type Terminated = Closed | Throwable
  type StreamResult[+T] = Either[Terminated, T]

trait StreamSender[-T] extends ChannelSender[T], java.io.Closeable:
  /** Terminate the channel with a given termination value. No more send operations (using [[sendSource]] or [[send]])
    * will be allowed afterwards. If the stream channel was terminated before, this does nothing. Especially, it does
    * not replace the termination value.
    *
    * @param value
    *   [[StreamResult.Closed]] to signal completion, or [[StreamResult.Failed]] to signal a failure
    * @return
    *   true iff this channel was terminated by that call with the given value
    */
  def terminate(value: StreamResult.Terminated): Boolean

  /** Close the channel now. Does nothing if the channel is already terminated.
    */
  override def close(): Unit = terminate(StreamResult.Closed)

  /** Close the channel now with a failure. Does nothing if the channel is already terminated.
    *
    * @param exception
    *   the exception that will constitute the termination value
    */
  def fail(exception: Throwable): Unit = terminate(exception)

  /** @see
    *   [[SendableChannel.send]]
    */
  def send(x: T)(using Async): Unit

trait SendableStreamChannel[-T] extends SendableChannel[T], StreamSender[T]

object SendableStreamChannel:
  /** Create a send-only channel that will accept any element immediately and pass it to a given handler. It
    * synchronizes access to the handler so that the handler will never be called multiple times in parallel. When the
    * [[SendableStreamChannel]] is terminated, the handler receives the termination value once. It will not be called
    * again afterwards.
    *
    * The handler is run synchronously on the sender's thread.
    *
    * @param handler
    *   a function to run when an element is sent on the channel or the channel is terminated
    * @return
    *   a new sending end of a stream channel that redirects the data to the given callback
    */
  def fromCallback[T](handler: StreamResult[T] => Unit): SendableStreamChannel[T] =
    new SendableStreamChannel[T]:
      var open = true

      override def sendSource(x: T): Async.Source[Res[Unit]] =
        new Async.Source[Res[Unit]]:
          override def poll(k: Listener[Res[Unit]]): Boolean =
            if k.acquireLock() then
              synchronized:
                if open then
                  handler(Right(x))
                  k.complete(Right(()), this)
                else k.complete(Left(Channel.Closed), this)
            true

          override def onComplete(k: Listener[Res[Unit]]): Unit = poll(k)

          override def dropListener(k: Listener[Res[Unit]]): Unit = ()

      override def terminate(value: StreamResult.Terminated): Boolean =
        synchronized:
          if open then
            open = false
            handler(Left(value))
            true
          else false
  end fromCallback

  private def fromSendableChannel[T](
      sendSrc: T => Async.Source[Res[Unit]],
      onTerminate: StreamResult.Terminated => Unit
  ): SendableStreamChannel[T] =
    new SendableStreamChannel[T]:
      private val closeLock: ReadWriteLock = new ReentrantReadWriteLock()
      private var closed = false

      override def sendSource(x: T): Async.Source[Res[Unit]] =
        SourceUtil.addExternalLock(sendSrc(x), closeLock.readLock()) { (k, selfSrc) =>
          if closed then
            closeLock.readLock().unlock()
            k.complete(Left(Channel.Closed), selfSrc)
            false
          else true
        }

      override def terminate(value: StreamResult.Terminated): Boolean =
        closeLock.writeLock().lock()
        val justTerminated = if closed then
          closeLock.writeLock().unlock()
          false
        else
          closed = true
          closeLock.writeLock().unlock()
          true

        if justTerminated then onTerminate(value)
        justTerminated
  end fromSendableChannel

  /** Create a [[SendableStreamChannel]] from a [[SendableChannel]] by forwarding all elements. Termination messages are
    * not sent through the channel but must be handled externally.
    *
    * @param channel
    *   the channel where to send the data elements
    * @param onTerminate
    *   the callback to invoke when the stream channel is closed
    * @return
    *   a new stream channel wrapper for the given channel
    */
  def fromChannel[T](channel: SendableChannel[T])(onTerminate: StreamResult.Terminated => Unit) =
    fromSendableChannel(channel.sendSource, onTerminate)

  /** Create a [[SendableStreamChannel]] from a [[SendableChannel]] by forwarding all data results. The termination
    * message is sent exactly once when the stream channel is terminated.
    *
    * @param channel
    *   the channel where to send the data
    * @return
    *   a new stream channel wrapper for the given channel
    */
  def fromResultChannel[T](channel: SendableChannel[StreamResult[T]]): SendableStreamChannel[T] =
    fromSendableChannel(
      { element => channel.sendSource(Right(element)) },
      { termination =>
        channel
          .sendSource(Left(termination))
          .onComplete(Listener.acceptingListener { (_, _) => })
      }
    )

end SendableStreamChannel

/** A handle to pull elements from a [[StreamReader]]. It is created once using [[StreamReader.pull]] and can be used
  * repeatedly to request more elements.
  *
  * When pulling, the producer blocks until a result is available and feeds it to the handler passed to
  * [[StreamReader.pull]] until the item handler returns true or termination is reached.
  */
type StreamPull = () => Async ?=> Unit

/** Trait to mixin to a partial [[StreamReader]] implementation providing [[StreamReader.pull]]
  */
trait GenReadStream[+T] extends StreamReader[T]:
  override def readStream()(using Async): StreamResult[T] =
    var res: StreamResult[T] = null
    pull(x => { res = Right(x); true }, x => res = Left(x))()
    res

/** Trait to mixin to a partial [[StreamReader]] implementation providing [[StreamReader.readStream]]
  */
trait GenPull[+T] extends StreamReader[T]:
  override def pull(
      onItem: T => (Async) ?=> Boolean,
      onTermination: StreamResult.Terminated => (Async) ?=> Unit
  ): StreamPull = () =>
    var continue = true
    while continue do
      readStream() match
        case Left(terminated) =>
          onTermination(terminated)
          continue = false
        case Right(item) => continue = !onItem(item)

trait StreamReader[+T]:
  /** Read an item from the channel, suspending until the item has been received.
    */
  def readStream()(using Async): StreamResult[T]

  /** Create a persistent pull handle to extract data from this reader in an efficient manner. The handlers are never
    * called asynchronously, but only as part of an invocation of the returned [[StreamPull]] (exactly one handle once
    * per call).
    *
    * @param onItem
    *   a handler to be called when an element is available. Return false to request another element.
    * @param onTermination
    *   a handler to be called when the producer is terminated
    * @return
    *   a [[StreamPull]] handle to request data
    */
  def pull(onItem: T => Async ?=> Boolean, onTermination: StreamResult.Terminated => Async ?=> Unit): StreamPull

trait ReadableStreamChannel[+T] extends StreamReader[T]:
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

  override def readStream()(using Async): StreamResult[T] = readStreamSource.awaitResult

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
class GenericStreamChannel[T](private val channel: Channel[StreamResult[T]]) extends StreamChannel[T] with GenPull[T]:
  private var finalResult: StreamResult[T] = null // access is synchronized with [[closeLock]]
  private val closeLock: ReadWriteLock = new ReentrantReadWriteLock()

  private def sendSource0(result: StreamResult[T]): Async.Source[Res[Unit]] =
    // acquire will succeed if either:
    //  - finalResult is null, therefore terminate will wait for the writeLock until our release/complete, or
    //  - we are sending the termination message right now (only done once if justTerminated in terminate).
    // If we try to lock a non-termination-sender after termination, the lock attempt by src is rejected
    //    and the downstream listener k is completed with a Left(Closed).
    SourceUtil.addExternalLock(channel.sendSource(result), closeLock.readLock()) { (k, selfSrc) =>
      if finalResult != null && result.isRight then
        closeLock.readLock().unlock()
        k.complete(Left(Channel.Closed), selfSrc) // k.lock is already acquired or not existent (checked in SourceUtil)
        false
      else true
    }
    // note: a send-attached Listener may learn about a successful send possibly after the send-end has been closed

  override def sendSource(x: T): Async.Source[Res[Unit]] = sendSource0(Right(x))

  override val readStreamSource: Async.Source[StreamResult[T]] =
    channel.readSource.transformValuesWith {
      case Left(_) => finalResult
      case Right(value) =>
        if value.isLeft then channel.close()
        value
    }

  override def terminate(value: StreamResult.Terminated): Boolean =
    val theValue = Left(value) // [[Failed]] contains useless generic

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
  private class Impl[T](size: Int) extends Channel.Impl[T] with StreamChannel[T] with GenPull[T]:
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

    override val readStreamSource: Async.Source[StreamResult[T]] =
      readSource.transformValuesWith(_.orElse(finalResult).asInstanceOf[StreamResult[T]])

    override def terminate(value: StreamResult.Terminated): Boolean = synchronized:
      if finalResult == null then
        finalResult = Left(value)
        cells.cancel()
        true
      else false
  end Impl
end BufferedStreamChannel
