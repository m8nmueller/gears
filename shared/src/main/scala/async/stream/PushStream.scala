package gears.async.stream

import gears.async.Async
import gears.async.Channel
import gears.async.Listener

trait PushSenderStream[+T] extends PushChannelStream[T]:
  def runToSender(sender: StreamSender[T])(using Async): Unit

  def runToChannel(channel: SendableStreamChannel[T])(using Async): Unit = runToSender(channel)

  override def map[V](mapper: T => V): PushSenderStream[V] =
    new MapLayer.SenderMixer[T, V] with MapLayer.MapLayer(mapper) with PushLayers.FromSenderLayer(this)

  override def filter(test: T => Boolean): PushSenderStream[T] =
    new FilterLayer.SenderMixer[T] with FilterLayer.FilterLayer(test) with PushLayers.FromSenderLayer(this)

trait PushChannelStream[+T]:
  def runToChannel(channel: SendableStreamChannel[T])(using Async): Unit

  def map[V](mapper: T => V): PushChannelStream[V] =
    new MapLayer.ChannelMixer[T, V] with MapLayer.MapLayer(mapper) with PushLayers.FromChannelLayer(this)

  def filter(test: T => Boolean): PushChannelStream[T] =
    new FilterLayer.ChannelMixer[T] with FilterLayer.FilterLayer(test) with PushLayers.FromChannelLayer(this)

private object PushLayers:
  // helpers for generating the layer ("mixer") traits (for derived streams)
  trait FromAnySenderLayer[+S[+_] <: PushChannelStream[_], +V](val upstream: S[V])
  type FromChannelLayer[V] = FromAnySenderLayer[PushChannelStream, V]
  type FromSenderLayer[V] = FromAnySenderLayer[PushSenderStream, V]

  // helpers for the derived channels
  trait ToAnySender[+S[-_] <: StreamSender[_], -V](val downstream: S[V])
  type ToSender[V] = ToAnySender[StreamSender, V]
  type ToChannel[V] = ToAnySender[SendableStreamChannel, V]

  trait ForwardTerminate[T] extends StreamSender[T]:
    self: PushLayers.ToSender[?] =>
    def terminate(value: StreamResult.Terminated): Boolean = downstream.terminate(value)
end PushLayers

private object MapLayer:
  trait MapLayer[T, V](val mapper: T => V)

  trait SenderLayer[T, V] extends PushLayers.ForwardTerminate[T]:
    self: PushLayers.ToSender[V] with MapLayer[T, V] =>
    override def send(x: T)(using Async): Unit = downstream.send(mapper(x))

  trait ChannelLayer[T, V] extends SendableStreamChannel[T]:
    self: PushLayers.ToChannel[V] with MapLayer[T, V] =>
    def sendSource(x: T): Async.Source[Channel.Res[Unit]] = downstream.sendSource(mapper(x))

  trait SenderMixer[T, V] extends PushSenderStream[V]:
    self: PushLayers.FromSenderLayer[T] with MapLayer[T, V] =>
    def runToSender(sender: StreamSender[V])(using Async): Unit =
      upstream.runToSender(new SenderLayer[T, V] with PushLayers.ToSender(sender) with MapLayer(mapper))

  trait ChannelMixer[T, V] extends PushChannelStream[V]:
    self: PushLayers.FromChannelLayer[T] with MapLayer[T, V] =>
    def runToChannel(channel: SendableStreamChannel[V])(using Async): Unit = upstream.runToChannel(
      new ChannelLayer[T, V] with SenderLayer[T, V] with PushLayers.ToChannel[V](channel) with MapLayer[T, V](mapper)
    )
end MapLayer

private object FilterLayer:
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

  trait SenderLayer[T] extends PushLayers.ForwardTerminate[T]:
    self: PushLayers.ToSender[T] with FilterLayer[T] =>
    override def send(x: T)(using Async): Unit = if filter(x) then downstream.send(x)

  trait ChannelLayer[T] extends SendableStreamChannel[T]:
    self: PushLayers.ToChannel[T] with FilterLayer[T] =>
    def sendSource(x: T): Async.Source[Channel.Res[Unit]] =
      if filter(x) then downstream.sendSource(x)
      else NoopSource

  trait SenderMixer[T] extends PushSenderStream[T]:
    self: PushLayers.FromSenderLayer[T] with FilterLayer[T] =>
    def runToSender(sender: StreamSender[T])(using Async): Unit =
      upstream.runToSender(new SenderLayer[T] with FilterLayer(filter) with PushLayers.ToSender(sender))

  trait ChannelMixer[T] extends PushChannelStream[T]:
    self: PushLayers.FromChannelLayer[T] with FilterLayer[T] =>
    def runToChannel(channel: SendableStreamChannel[T])(using Async): Unit = upstream.runToChannel(
      new ChannelLayer[T] with SenderLayer[T] with FilterLayer(filter) with PushLayers.ToChannel(channel)
    )
end FilterLayer
