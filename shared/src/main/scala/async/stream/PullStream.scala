package gears.async.stream

import gears.async.Async
import gears.async.Channel
import gears.async.SourceUtil
import gears.async.stream.StreamResult.StreamResult
import gears.async.Listener
import gears.async.Listener.ListenerLock
import gears.async.SourceUtil

/** A source should provide means for parallel execution. As the execution is driven by the consumer, the consumer tells
  * the producer the degree of parallelism (see [[PullReaderStream.runWithReader]]) and the producer decides whether its
  * readers are thread-safe. If they are, it may pass a single reader. Otherwise, it passes a factory for sources that
  * will only be employed on one thread.
  */
type PullSource[+S[+_], +T] = S[T] | Iterator[S[T]]

trait PullReaderStream[+T]:
  /** Run the reader stream by creating one/multiple readers and passing it to the receiving body. Computation is
    * terminated once the body returns.
    *
    * @param parallelism
    *   the number of reader instances that the receiver requests for concurrent access
    * @param body
    *   the receiver body that runs on the readers
    * @return
    *   the result of the body
    */
  def runWithReader[A](parallelism: Int)(body: PullSource[StreamReader, T] => Async ?=> A)(using Async): A

  def map[V](mapper: T => V): PullReaderStream[V] =
    new PullLayers.MapLayer.ReaderMixer[T, V]
      with PullLayers.MapLayer.MapLayer(mapper)
      with PullLayers.FromReaderLayer(this)

  def filter(test: T => Boolean): PullReaderStream[T] =
    new PullLayers.FilterLayer.ReaderMixer[T]
      with PullLayers.FilterLayer.FilterLayer(test)
      with PullLayers.FromReaderLayer(this)

trait PullChannelStream[+T] extends PullReaderStream[T]:
  /** @see
    *   PullReaderStream.runWithReader
    */
  def runWithChannel[A](parallelism: Int)(body: PullSource[ReadableStreamChannel, T] => Async ?=> A)(using Async): A
  override def runWithReader[A](parallelism: Int)(body: PullSource[StreamReader, T] => Async ?=> A)(using Async): A =
    runWithChannel(parallelism)(body)

  override def map[V](mapper: T => V): PullChannelStream[V] =
    new PullLayers.MapLayer.ChannelMixer[T, V]
      with PullLayers.MapLayer.MapLayer(mapper)
      with PullLayers.FromChannelLayer(this)

  override def filter(test: T => Boolean): PullChannelStream[T] =
    new PullLayers.FilterLayer.ChannelMixer[T]
      with PullLayers.FilterLayer.FilterLayer(test)
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

  trait ReaderMixer[T, V] extends PullReaderStream[V]:
    self: FromReaderLayer[T] =>
    def transform(reader: StreamReader[T]): StreamReader[V]
    override def runWithReader[A](parallelism: Int)(body: PullSource[StreamReader, V] => Async ?=> A)(using Async): A =
      upstream.runWithReader(parallelism): inReader =>
        val reader =
          if inReader.isInstanceOf[StreamReader[?]] then transform(inReader.asInstanceOf[StreamReader[T]])
          else inReader.asInstanceOf[Iterator[StreamReader[T]]].map(transform)
        body(reader)

  trait ChannelMixer[T, V] extends PullChannelStream[V]:
    self: FromChannelLayer[T] =>
    def transform(channel: ReadableStreamChannel[T]): ReadableStreamChannel[V]
    override def runWithChannel[A](parallelism: Int)(body: PullSource[ReadableStreamChannel, V] => Async ?=> A)(using
        Async
    ): A =
      upstream.runWithChannel(parallelism): inChannel =>
        val channel =
          if inChannel.isInstanceOf[ReadableStreamChannel[?]] then
            transform(inChannel.asInstanceOf[ReadableStreamChannel[T]])
          else inChannel.asInstanceOf[Iterator[ReadableStreamChannel[T]]].map(transform)
        body(channel)

  object MapLayer:
    trait MapLayer[T, V](val mapper: T => V)

    trait ReaderLayer[T, V] extends StreamReader[V]:
      self: FromReader[T] with MapLayer[T, V] =>
      override def readStream()(using Async): StreamResult[V] = upstream.readStream().map(mapper)
      override def pull(
          onItem: V => (Async) ?=> Boolean,
          onTermination: StreamResult.Terminated => (Async) ?=> Unit
      ): StreamPull = upstream.pull(onItem.compose(mapper), onTermination)

    trait ChannelLayer[T, V] extends ReadableStreamChannel[V]:
      self: FromChannel[T] with MapLayer[T, V] =>
      override val readStreamSource: Async.Source[StreamResult[V]] =
        upstream.readStreamSource.transformValuesWith(_.map(mapper))

    trait ReaderMixer[T, V] extends PullLayers.ReaderMixer[T, V]:
      self: FromReaderLayer[T] with MapLayer[T, V] =>
      override def transform(reader: StreamReader[T]): StreamReader[V] =
        new ReaderLayer[T, V] with FromReader(reader) with MapLayer(mapper)

    trait ChannelMixer[T, V] extends PullLayers.ChannelMixer[T, V]:
      self: FromChannelLayer[T] with MapLayer[T, V] =>
      override def transform(channel: ReadableStreamChannel[T]): ReadableStreamChannel[V] =
        new ChannelLayer[T, V] with ReaderLayer[T, V] with FromChannel[T](channel) with MapLayer[T, V](mapper)
  end MapLayer

  object FilterLayer:
    trait FilterLayer[T](val filter: T => Boolean)

    trait ReaderLayer[T] extends StreamReader[T]:
      self: FromReader[T] with FilterLayer[T] =>
      override def readStream()(using Async): StreamResult[T] =
        var data = upstream.readStream()
        // only continue if is right (item) and that item does not match the filter
        while data.exists(item => !filter(item)) do data = upstream.readStream()
        data
      override def pull(
          onItem: T => (Async) ?=> Boolean,
          onTermination: StreamResult.Terminated => (Async) ?=> Unit
      ): StreamPull = upstream.pull(item => filter(item) && onItem(item), onTermination)

    trait ChannelLayer[T] extends ReadableStreamChannel[T]:
      self: FromChannel[T] with FilterLayer[T] =>
      override val readStreamSource: Async.Source[StreamResult[T]] =
        new SourceUtil.DerivedSource[StreamResult[T], StreamResult[T]](upstream.readStreamSource):
          selfSrc =>
          override def transform(k: Listener[StreamResult[T]]): Listener[StreamResult[T]] =
            new Listener.ForwardingListener[StreamResult[T]](this, k):
              override val lock: ListenerLock | Null = k.lock
              override def complete(data: StreamResult[T], source: Async.Source[StreamResult[T]]): Unit =
                if data.exists(item => !filter(item)) then
                  // it is an item element that does not match the filter -> abort this completion and re-register
                  k.releaseLock()
                  selfSrc.src.onComplete(this) // this is racy *unless* the Source drops Listeners after completing
                else k.complete(data, selfSrc)
          end transform

          override def poll(k: Listener[StreamResult[T]]): Boolean =
            var found = false
            val kk = new Listener[StreamResult[T]]:
              override val lock: ListenerLock | Null = k.lock
              override def complete(data: StreamResult[T], source: Async.Source[StreamResult[T]]): Unit =
                if data.exists(item => !filter(item)) then k.releaseLock()
                else
                  found = true
                  k.complete(data, selfSrc)
            while !found && src.poll(kk) do ()
            found
          end poll

          override def poll(): Option[StreamResult[T]] =
            var res = src.poll()
            while res.exists(_.exists(item => !filter(item))) do res = src.poll()
            res
    end ChannelLayer

    trait ReaderMixer[T] extends PullLayers.ReaderMixer[T, T]:
      self: FromReaderLayer[T] with FilterLayer[T] =>
      override def transform(reader: StreamReader[T]): StreamReader[T] =
        new ReaderLayer[T] with FromReader(reader) with FilterLayer(filter)

    trait ChannelMixer[T] extends PullLayers.ChannelMixer[T, T]:
      self: FromChannelLayer[T] with FilterLayer[T] =>
      override def transform(channel: ReadableStreamChannel[T]): ReadableStreamChannel[T] =
        new ChannelLayer[T] with ReaderLayer[T] with PullLayers.FromChannel[T](channel) with FilterLayer[T](filter)
  end FilterLayer
end PullLayers
