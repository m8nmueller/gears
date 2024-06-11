package gears.async.stream

import gears.async.Async
import gears.async.Channel
import gears.async.Listener
import gears.async.Future

/** A destination can either be a single Sender or a factory. If it is a factory, the producer should create a new
  * instance for every parallel execution. A termination/intermediary step might decide it's sender logic is not
  * thread-safe and thus pass a factory upstream. If it passes a single sender, it is considered thread-safe by the
  * sender.
  */
type PushDestination[+S[-_], -T] = S[T] | Iterator[S[T]]

trait PushSenderStream[+T] extends PushChannelStream[T]:
  def runToSender(sender: PushDestination[StreamSender, T])(using Async): Unit

  override def runToChannel(channel: PushDestination[SendableStreamChannel, T])(using Async): Unit =
    runToSender(channel)

  override def map[V](mapper: T => V): PushSenderStream[V] =
    new PushLayers.MapLayer.SenderMixer[T, V]
      with PushLayers.MapLayer.MapLayer(mapper)
      with PushLayers.FromSenderLayer(this)

  override def filter(test: T => Boolean): PushSenderStream[T] =
    new PushLayers.FilterLayer.SenderMixer[T]
      with PushLayers.FilterLayer.FilterLayer(test)
      with PushLayers.FromSenderLayer(this)

trait PushChannelStream[+T]:
  def runToChannel(channel: PushDestination[SendableStreamChannel, T])(using Async): Unit

  def map[V](mapper: T => V): PushChannelStream[V] =
    new PushLayers.MapLayer.ChannelMixer[T, V]
      with PushLayers.MapLayer.MapLayer(mapper)
      with PushLayers.FromChannelLayer(this)

  def filter(test: T => Boolean): PushChannelStream[T] =
    new PushLayers.FilterLayer.ChannelMixer[T]
      with PushLayers.FilterLayer.FilterLayer(test)
      with PushLayers.FromChannelLayer(this)

  def pulledThrough(bufferSize: Int): PullChannelStream[T] = new PullChannelStream[T]:
    override def runWithChannel[A](parallelism: Int)(body: PullSource[ReadableStreamChannel, T] => Async ?=> A)(using
        Async
    ): A =
      // the channel is thread-safe -> the consumer may use it from multiple threads
      val channel = BufferedStreamChannel[T](bufferSize)
      Async.group:
        Future { runToChannel(channel) }
        body(channel)

private object PushLayers:
  // helpers for generating the layer ("mixer") traits (for derived streams)
  trait FromAnySenderLayer[+S[+_] <: PushChannelStream[_], +V](val upstream: S[V])
  type FromChannelLayer[V] = FromAnySenderLayer[PushChannelStream, V]
  type FromSenderLayer[V] = FromAnySenderLayer[PushSenderStream, V]

  trait SenderMixer[-T, +V] extends PushSenderStream[V]:
    self: FromSenderLayer[T] =>
    def transform(sender: StreamSender[V]): StreamSender[T]

    override def runToSender(sender: PushDestination[StreamSender, V])(using Async): Unit =
      upstream.runToSender(mapMaybeIt(sender)(transform))

  trait ChannelMixer[-T, +V] extends PushChannelStream[V]:
    self: FromChannelLayer[T] =>
    def transform(channel: SendableStreamChannel[V]): SendableStreamChannel[T]

    override def runToChannel(channel: PushDestination[SendableStreamChannel, V])(using Async): Unit =
      upstream.runToChannel(mapMaybeIt(channel)(transform))

  // helpers for the derived channels
  trait ToAnySender[+S[-_] <: StreamSender[_], -V](val downstream: S[V])
  type ToSender[V] = ToAnySender[StreamSender, V]
  type ToChannel[V] = ToAnySender[SendableStreamChannel, V]

  trait ForwardTerminate[T] extends StreamSender[T]:
    self: ToAnySender[?, ?] =>
    override def terminate(value: StreamResult.Terminated): Boolean = downstream.terminate(value)

  object MapLayer:
    trait MapLayer[T, V](val mapper: T => V)

    trait SenderLayer[T, V] extends ForwardTerminate[T]:
      self: ToSender[V] with MapLayer[T, V] =>
      override def send(x: T)(using Async): Unit = downstream.send(mapper(x))

    trait ChannelLayer[T, V] extends SendableStreamChannel[T]:
      self: ToChannel[V] with MapLayer[T, V] =>
      override def sendSource(x: T): Async.Source[Channel.Res[Unit]] = downstream.sendSource(mapper(x))

    trait SenderMixer[T, V] extends PushLayers.SenderMixer[T, V]:
      self: FromSenderLayer[T] with MapLayer[T, V] =>
      override def transform(sender: StreamSender[V]): StreamSender[T] =
        new SenderLayer[T, V] with ToSender(sender) with MapLayer(mapper)

    trait ChannelMixer[T, V] extends PushLayers.ChannelMixer[T, V]:
      self: FromChannelLayer[T] with MapLayer[T, V] =>
      override def transform(channel: SendableStreamChannel[V]): SendableStreamChannel[T] =
        new ChannelLayer[T, V] with SenderLayer[T, V] with ToChannel[V](channel) with MapLayer[T, V](mapper)
  end MapLayer

  object FilterLayer:
    val NoopRes: Channel.Res[Unit] = Right(())
    val NoopOption: Option[Channel.Res[Unit]] = Some(NoopRes)
    val NoopSource = new Async.Source[Channel.Res[Unit]]:
      override def poll(): Option[Channel.Res[Unit]] = NoopOption
      override def poll(k: Listener[Channel.Res[Unit]]): Boolean =
        k.completeNow(NoopRes, this)
        true

      override def onComplete(k: Listener[Channel.Res[Unit]]): Unit = poll(k)

      override def dropListener(k: Listener[Channel.Res[Unit]]): Unit = ()
    end NoopSource

    trait FilterLayer[T](val filter: T => Boolean)

    trait SenderLayer[T] extends ForwardTerminate[T]:
      self: ToSender[T] with FilterLayer[T] =>
      override def send(x: T)(using Async): Unit = if filter(x) then downstream.send(x)

    trait ChannelLayer[T] extends SendableStreamChannel[T]:
      self: ToChannel[T] with FilterLayer[T] =>
      override def sendSource(x: T): Async.Source[Channel.Res[Unit]] =
        if filter(x) then downstream.sendSource(x)
        else NoopSource

    trait SenderMixer[T] extends PushLayers.SenderMixer[T, T]:
      self: FromSenderLayer[T] with FilterLayer[T] =>
      override def transform(sender: StreamSender[T]): StreamSender[T] =
        new SenderLayer[T] with FilterLayer(filter) with ToSender(sender)

    trait ChannelMixer[T] extends PushLayers.ChannelMixer[T, T]:
      self: FromChannelLayer[T] with FilterLayer[T] =>
      override def transform(channel: SendableStreamChannel[T]): SendableStreamChannel[T] =
        new ChannelLayer[T] with SenderLayer[T] with FilterLayer(filter) with ToChannel(channel)
  end FilterLayer
end PushLayers
