package gears.async.stream

import gears.async.Async
import gears.async.Future
import scala.annotation.targetName
import gears.async.SendableChannel
import scala.util.Success
import gears.async.Channel
import scala.util.Try

trait Stream[+Out]:
  def through[NewOut](
      flow: (ReadableStreamChannel[Out], SendableStreamChannel[NewOut]) => Async ?=> Unit
  )(using fac: Stream.ChannelFactory): Stream[NewOut]

  /** Execute the handler in a scope where the stream is connected to the given channel and running. When the handler
    * returns, the stream is cancelled.
    *
    * @param channel
    *   the channel to connect the stream to
    * @param handler
    *   the handler to run when the stream is started
    * @return
    *   the result of the handler
    */
  def runWithChannel[T](channel: SendableStreamChannel[Out])(handler: Async ?=> T)(using Async): T

object Stream:

  private class StreamImpl[Out](val tasks: SendableStreamChannel[Out] => List[Async ?=> Unit]) extends Stream[Out]:
    override def through[NewOut](flow: (ReadableStreamChannel[Out], SendableStreamChannel[NewOut]) => Async ?=> Unit)(
        using fac: ChannelFactory
    ): Stream[NewOut] =
      val ch = fac[Out]()
      val srcTasks = this.tasks(ch)
      StreamImpl: send =>
        val flowTask: Async ?=> Unit = flow(ch, send)
        flowTask :: srcTasks

    override def runWithChannel[T](channel: SendableStreamChannel[Out])(handler: Async ?=> T)(using Async): T =
      val tasks = this.tasks(channel)
      Async.group:
        tasks.foreach(Future(_))
        handler
  end StreamImpl

  /** A (cold) stream is constructed in three stages:
    *   - The [[Stream]] instance is created given a curried function of a channel (to write to) and an async capability
    *   - The stream is attached to a channel (by applying the function) generating a set of async tasks
    *   - The async tasks are started, each as a separate Future. Now, those tasks actually generate data and write to
    *     the channels.
    *
    * @param fn
    *   the initial task that will constitute the stream's computation. It is stored until the [[Stream]] is run.
    * @return
    *   a newly created unstarted stream
    */
  def apply[Out](fn: SendableStreamChannel[Out] => Async ?=> Unit): Stream[Out] = StreamImpl(ch => List(fn(ch)))

  extension [Out](src: Stream[Out])

    /** Execute the handler in a scope where the stream is connected to a new channel and running. The handler receives
      * the reading end of the same channel. When it returns, the stream is cancelled.
      *
      * @param handler
      *   the handler to run when the stream is started. Receives the read end of a newly created channel.
      * @param fac
      *   the factory used to create the channel
      * @return
      *   the result of the handler
      */
    def run[T](handler: ReadableStreamChannel[Out] => Async ?=> T)(using fac: ChannelFactory)(using Async): T =
      val channel = fac[Out]()
      src.runWithChannel(channel)(handler(channel))

    /** Connect this stream to a channel, start it, and wait until the stream closes the write end channel.
      *
      * @param channel
      *   the channel where this [[Stream]]'s items are emitted to
      */
    def startToStreamChannel(channel: SendableStreamChannel[Out])(using Async): Unit =
      val done = Future.Promise[Unit]()

      // a wrapper that delegates everything to the actual channel, but notices when the channel is completed
      val wrappedChannel = new SendableStreamChannel[Out]:
        override def sendSource(x: Out): Async.Source[Channel.Res[Unit]] = channel.sendSource(x)
        override def terminate(value: StreamResult.Terminated): Boolean =
          val res = channel.terminate(value)
          if res then done.complete(Success(()))
          res

      src.runWithChannel(wrappedChannel)(done.awaitResult)

    /** Connect this stream to a channel, start it, and wait until the stream closes the write end channel.
      *
      * @param channel
      *   the channel where this [[Stream]]'s items are emitted to
      * @return
      *   the termination state sent by the stream, see [[Terminated.toTerminationTry]]
      */
    def startToChannel(channel: SendableChannel[Out])(using Async): Try[Unit] =
      val done = Future.Promise[Unit]()

      val wrappedChannel = SendableStreamChannel.fromChannel(channel): termination =>
        done.complete(termination.toTerminationTry())

      src.runWithChannel(wrappedChannel)(done.awaitResult)

  end extension // Stream

  opaque type ChannelFactory = [T] => () => StreamChannel[T]
  object ChannelFactory {
    given default: ChannelFactory = { [T] => () => BufferedStreamChannel[T](10) }
    inline def apply(inline fac: [T] => () => StreamChannel[T]): ChannelFactory = fac
  }
