package gears.async.stream

import gears.async.Async
import gears.async.Channel
import gears.async.stream.StreamResult.StreamResult

trait PullReaderStream[+T]:
  def toReader()(using Async): StreamReader[T]

  def map[V](mapper: T => V): PullReaderStream[V] =
    new PullLayers.MapLayer.ReaderMixer[T, V]
      with PullLayers.MapLayer.MapLayer(mapper)
      with PullLayers.FromReaderLayer(this)

trait PullChannelStream[+T] extends PullReaderStream[T]:
  def toChannel()(using Async): ReadableStreamChannel[T]
  override def toReader()(using Async): StreamReader[T] = toChannel()
  override def map[V](mapper: T => V): PullChannelStream[V] =
    new PullLayers.MapLayer.ChannelMixer[T, V]
      with PullLayers.MapLayer.MapLayer(mapper)
      with PullLayers.FromChannelLayer(this)

private object PullLayers:
  // helpers for generating the layer ("mixer") traits (for derived streams)
  trait FromAnyReaderLayer[+S[+_] <: PullReaderStream[_], +V](val upstream: S[V])
  type FromChannelLayer[V] = FromAnyReaderLayer[PullChannelStream, V]
  type FromReaderLayer[V] = FromAnyReaderLayer[PullReaderStream, V]

  // helpers for the derived channels
  trait FromAnyReader[+S[+_] <: StreamReader[_], +V](val upstream: S[V])
  type FromReader[V] = FromAnyReader[StreamReader, V]
  type FromChannel[V] = FromAnyReader[ReadableStreamChannel, V]

  object MapLayer:
    trait MapLayer[T, V](val mapper: T => V)

    trait ReaderLayer[T, V] extends StreamReader[V]:
      self: PullLayers.FromReader[T] with MapLayer[T, V] =>
      override def readStream()(using Async): StreamResult[V] = upstream.readStream().map(mapper)

    trait ChannelLayer[T, V] extends ReadableStreamChannel[V]:
      self: PullLayers.FromChannel[T] with MapLayer[T, V] =>
      val readStreamSource: Async.Source[StreamResult[V]] = upstream.readStreamSource.transformValuesWith(_.map(mapper))

    trait ReaderMixer[T, V] extends PullReaderStream[V]:
      self: PullLayers.FromReaderLayer[T] with MapLayer[T, V] =>
      def toReader()(using Async): StreamReader[V] =
        new ReaderLayer[T, V] with PullLayers.FromReader(upstream.toReader()) with MapLayer(mapper)

    trait ChannelMixer[T, V] extends PullChannelStream[V]:
      self: PullLayers.FromChannelLayer[T] with MapLayer[T, V] =>
      def toChannel()(using Async): ReadableStreamChannel[V] = new ChannelLayer[T, V]
        with ReaderLayer[T, V]
        with PullLayers.FromChannel[T](upstream.toChannel())
        with MapLayer[T, V](mapper)
  end MapLayer
end PullLayers
