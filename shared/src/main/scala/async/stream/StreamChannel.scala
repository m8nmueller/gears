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
import scala.collection.mutable
import gears.async.listeners.lockBoth

enum StreamResult[+T]:
  case Closed
  case Failed(val exception: Throwable)
  case Data(val data: T)

object StreamResult:
  type Terminated = StreamResult.Closed.type | StreamResult.Failed[?]

  extension (t: Terminated)
    /** Convert the termination message to a [[Try]]. It matches [[StreamResult.Closed]] to [[Success]] and
      * [[StreamResult.Failed]] to [[Failure]] containing the given throwable.
      *
      * @return
      *   a try representation of this termination state
      */
    def toTerminationTry(): Try[Unit] =
      t match
        case Closed            => Success(())
        case Failed(exception) => Failure(exception)

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
  def terminate(value: StreamResult.Terminated): Boolean

  /** Close the channel now. Does nothing if the channel is already terminated.
    */
  override def close(): Unit = terminate(StreamResult.Closed)

  /** Close the channel now with a failure. Does nothing if the channel is already terminated.
    *
    * @param exception
    *   the exception that will constitute the termination value
    */
  def fail(exception: Throwable): Unit = terminate(StreamResult.Failed(exception))

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
                  handler(StreamResult.Data(x))
                  k.complete(Right(()), this)
                else k.complete(Left(Channel.Closed), this)
            true

          override def onComplete(k: Listener[Res[Unit]]): Unit = poll(k)

          override def dropListener(k: Listener[Res[Unit]]): Unit = ()

      override def terminate(value: StreamResult.Terminated): Boolean =
        synchronized:
          if open then
            open = false
            handler(value.asInstanceOf[StreamResult[T]])
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
      { element => channel.sendSource(StreamResult.Data(element)) },
      { termination =>
        channel
          .sendSource(termination.asInstanceOf[StreamResult[T]])
          .onComplete(Listener.acceptingListener { (_, _) => })
      }
    )

end SendableStreamChannel

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
    // acquire will succeed if either:
    //  - finalResult is null, therefore terminate will wait for the writeLock until our release/complete, or
    //  - we are sending the termination message right now (only done once if justTerminated in terminate).
    // If we try to lock a non-termination-sender after termination, the lock attempt by src is rejected
    //    and the downstream listener k is completed with a Left(Closed).
    SourceUtil.addExternalLock(channel.sendSource(result), closeLock.readLock()) { (k, selfSrc) =>
      if finalResult != null && result.isInstanceOf[StreamResult.Data[?]] then
        closeLock.readLock().unlock()
        k.complete(Left(Channel.Closed), selfSrc) // k.lock is already acquired or not existent (checked in SourceUtil)
        false
      else true
    }
    // note: a send-attached Listener may learn about a successful send possibly after the send-end has been closed

  override def sendSource(x: T): Async.Source[Res[Unit]] = sendSource0(StreamResult.Data(x))

  override val readStreamSource: Async.Source[StreamResult[T]] =
    channel.readSource.transformValuesWith {
      case Left(_) => finalResult
      case Right(value) =>
        if !value.isInstanceOf[StreamResult.Data[?]] then channel.close()
        value
    }

  override def terminate(value: StreamResult.Terminated): Boolean =
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

  private[async] abstract class ImplBase[T] extends StreamChannel[T]:
    protected type ReadResult = StreamResult[T]
    protected type SendResult = Res[Unit]
    protected type Reader = Listener[ReadResult]
    protected type Sender = Listener[SendResult]

    var finalResult: StreamResult[T] = null
    val cells = CellBuf()
    // Poll a reader, returning false if it should be put into queue
    def pollRead(r: Reader): Boolean
    // Poll a reader, returning false if it should be put into queue
    def pollSend(src: CanSend, s: Sender): Boolean

    protected final def checkSendClosed(src: Async.Source[Res[Unit]], l: Sender): Boolean =
      if finalResult != null then
        l.completeNow(Left(Channel.Closed), src)
        true
      else false

    protected final def checkReadClosed(l: Reader): Boolean =
      if finalResult != null then
        l.completeNow(finalResult, readStreamSource)
        true
      else false

    override val readStreamSource: Async.Source[ReadResult] = new Async.Source {
      override def poll(k: Reader): Boolean = pollRead(k)
      override def onComplete(k: Reader): Unit = ImplBase.this.synchronized:
        if !pollRead(k) then cells.addReader(k)
      override def dropListener(k: Reader): Unit = ImplBase.this.synchronized:
        cells.dropReader(k)
    }
    override final def sendSource(x: T): Async.Source[SendResult] = CanSend(x)

    override def terminate(value: StreamResult.Terminated): Boolean = ImplBase.this.synchronized:
      if finalResult == null then
        finalResult = value.asInstanceOf[StreamResult[T]]
        cells.cancelSender()
        true
      else false

    /** Complete a pair of locked sender and reader. */
    protected final def complete(src: CanSend, reader: Listener[ReadResult], sender: Listener[SendResult]) =
      reader.complete(StreamResult.Data(src.item), readStreamSource)
      sender.complete(Right(()), src)

    // Not a case class because equality should be referential, as otherwise
    // dependent on a (possibly odd) equality of T. Users do not expect that
    // cancelling a send of a given item might in fact cancel that of an equal one.
    protected final class CanSend(val item: T) extends Async.Source[SendResult] {
      override def poll(k: Listener[SendResult]): Boolean = pollSend(this, k)
      override def onComplete(k: Listener[SendResult]): Unit = ImplBase.this.synchronized:
        if !pollSend(this, k) then cells.addSender(this, k)
      override def dropListener(k: Listener[SendResult]): Unit = ImplBase.this.synchronized:
        cells.dropSender(this, k)
    }

    /** CellBuf is a queue of cells, which consists of a sleeping sender or reader. The queue always guarantees that
      * there are *only* all readers or all senders. It must be externally synchronized.
      */
    private[async] class CellBuf():
      type Cell = Reader | (CanSend, Sender)
      // reader == 0 || sender == 0 always
      private var reader = 0
      private var sender = 0

      private val pending = mutable.Queue[Cell]()

      /* Boring push/pop methods */

      def hasReader = reader > 0
      def hasSender = sender > 0
      def nextReader =
        require(reader > 0)
        pending.head.asInstanceOf[Reader]
      def nextSender =
        require(sender > 0)
        pending.head.asInstanceOf[(CanSend, Sender)]
      def dequeue() =
        pending.dequeue()
        if reader > 0 then reader -= 1 else sender -= 1
      def addReader(r: Reader): this.type =
        require(sender == 0)
        reader += 1
        pending.enqueue(r)
        this
      def addSender(src: CanSend, s: Sender): this.type =
        require(reader == 0)
        sender += 1
        pending.enqueue((src, s))
        this
      def dropReader(r: Reader): this.type =
        if reader > 0 then if pending.removeFirst(_ == r).isDefined then reader -= 1
        this
      def dropSender(src: CanSend, s: Sender): this.type =
        if sender > 0 then if pending.removeFirst(_ == (src, s)).isDefined then sender -= 1
        this

      /** Match a possible reader to a queue of senders: try to go through the queue with lock pairing, stopping when
        * finding a good pair.
        */
      def matchReader(r: Reader): Boolean =
        while hasSender do
          val (src, s) = nextSender
          tryComplete(src, s)(r) match
            case ()                        => return true
            case listener if listener == r => return true
            case _                         => dequeue() // drop gone sender from queue
        false

      /** Match a possible sender to a queue of readers: try to go through the queue with lock pairing, stopping when
        * finding a good pair.
        */
      def matchSender(src: CanSend, s: Sender): Boolean =
        while hasReader do
          val r = nextReader
          tryComplete(src, s)(r) match
            case ()                        => return true
            case listener if listener == s => return true
            case _                         => dequeue() // drop gone reader from queue
        false

      private inline def tryComplete(src: CanSend, s: Sender)(r: Reader): s.type | r.type | Unit =
        lockBoth(r, s) match
          case true =>
            ImplBase.this.complete(src, r, s)
            dequeue() // drop completed reader/sender from queue
            ()
          case listener: (r.type | s.type) => listener

      private inline def cancel[U](count: Int)(inline f: Cell => U): Unit =
        if count > 0 then
          pending.foreach(f)
          pending.clear()
          reader = 0
          sender = 0

      def cancelSender() =
        cancel(sender) {
          case (src, s) => s.completeNow(Left(Channel.Closed), src)
          case _        => ()
        }

      def cancelReader() =
        cancel(reader) {
          case r: Reader => r.completeNow(ImplBase.this.finalResult, readStreamSource)
          case _         => ()
        }
    end CellBuf
  end ImplBase

  // notable differences to BufferedChannel.Impl:
  //  - finalResults for check and to replace Left(Closed) on read --> new checkSendClosed method
  //  - in pollRead, close checking moved from 'before buffer checking' to 'after buffer checking'
  private class Impl[T](size: Int) extends ImplBase[T]:
    require(size > 0, "Buffered channels must have a buffer size greater than 0")
    val buf = new mutable.Queue[T](size)

    // Match a reader -> check space in buf -> fail
    override def pollSend(src: CanSend, s: Sender): Boolean = synchronized:
      checkSendClosed(src, s) || cells.matchSender(src, s) || senderToBuf(src, s)

    // Check space in buf -> fail
    // If we can pop from buf -> try to feed a sender
    override def pollRead(r: Reader): Boolean = synchronized:
      if !buf.isEmpty then
        if r.completeNow(StreamResult.Data(buf.head), readStreamSource) then
          buf.dequeue()
          if cells.hasSender then
            val (src, s) = cells.nextSender
            cells.dequeue() // buf always has space available after dequeue
            senderToBuf(src, s)
          else if buf.isEmpty && finalResult != null then cells.cancelReader()
        true
      else checkReadClosed(r)

    // Try to add a sender to the buffer
    def senderToBuf(src: CanSend, s: Sender): Boolean =
      if buf.size < size then
        if s.completeNow(Right(()), src) then buf += src.item
        true
      else false

    override def terminate(value: StreamResult.Terminated): Boolean = synchronized:
      val terminated = super.terminate(value)
      if terminated && buf.isEmpty then cells.cancelReader()
      terminated
  end Impl
end BufferedStreamChannel
