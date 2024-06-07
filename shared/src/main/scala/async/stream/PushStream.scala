package gears.async.stream

import gears.async.Async
import gears.async.Channel

trait PushSenderStream[+T] extends PushChannelStream[T]:
  def runToSender(sender: StreamSender[T])(using Async): Unit

  def runToChannel(channel: SendableStreamChannel[T])(using Async): Unit = runToSender(channel)

  override def map[V](mapper: T => V): PushSenderStream[V] = ???

trait PushChannelStream[+T]:
  def runToChannel(channel: SendableStreamChannel[T])(using Async): Unit

  def map[V](mapper: T => V): PushChannelStream[V] = ???

private object PushLayers:
  trait DowngradePushLayer[T, V]:
    def sendToChannel(x: T, downstream: SendableStreamChannel[V])(using Async): Unit

//    def downgrade(stream: PushSenderStream[T]): PushChannelStream[V]
  end DowngradePushLayer

  trait SenderPushLayer[T, V] extends DowngradePushLayer[T, V]:
    def sendToSender(x: T, downstream: StreamSender[V])(using Async): Unit

    def sendToChannel(x: T, downstream: SendableStreamChannel[V])(using Async): Unit = sendToSender(x, downstream)
  end SenderPushLayer

  trait ChannelPushLayer[T, V] extends DowngradePushLayer[T, V]:
    def sendSource(x: T, downstream: SendableStreamChannel[V]): Async.Source[Channel.Res[Unit]]
  end ChannelPushLayer

  trait FullPushLayer[T, V] extends ChannelPushLayer[T, V] with SenderPushLayer[T, V]
end PushLayers

private class MapLayer[T, V](mapper: T => V) extends PushLayers.FullPushLayer[T, V]:

  override def sendToSender(x: T, downstream: StreamSender[V])(using Async): Unit = downstream.send(mapper(x))

  override def sendSource(x: T, downstream: SendableStreamChannel[V]): Async.Source[Channel.Res[Unit]] =
    downstream.sendSource(mapper(x))

end MapLayer
