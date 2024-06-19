package gears.async.stream

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import gears.async.Async
import gears.async.Channel
import gears.async.SourceUtil
import gears.async.stream.StreamResult.StreamResult
import gears.async.Listener
import gears.async.Listener.ListenerLock
import gears.async.Future
import gears.async.ChannelClosedException
import gears.async.CancellationException

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

  def take(count: Int): PullReaderStream[T] =
    new PullLayers.TakeLayer.ReaderMixer[T]
      with PullLayers.TakeLayer.TakeLayer(count)
      with PullLayers.FromReaderLayer(this)

  /** Transform this pull stream into a push stream by introducing an active component that pulls items, possibly
    * transforms them, and pushes them downstream.
    *
    * @param parallelism
    *   The number of task instances that are spawned. The actual number may be limited by sender/reader capabilities.
    * @param task
    *   The task to operate on the reader (to pull from this stream) and the sender which consumes the created stream's
    *   output. It may throw a [[StreamResult.StreamTerminatedException]] to stop parallel executions of the same task
    *   without further side effects. Any other exception will cancel (parallel) execution and bubble up.
    * @return
    *   a new push stream where elements emitted by the task will be sent to
    */
  def pushedBy[V](parallelism: Int)(task: (StreamReader[T], StreamSender[V]) => Async ?=> Unit): PushSenderStream[V] =
    require(parallelism > 0)
    new PushSenderStream[V]:
      override def runToSender(senders: PushDestination[StreamSender, V])(using Async): Unit =
        runWithReader(parallelism): readers =>
          try
            if parallelism == 1 then
              val r = handleMaybeIt(readers)(identity)(_.next)
              val s = handleMaybeIt(senders)(identity)(_.next)
              task(r, s)
            else
              val ri = handleMaybeIt(readers)(Iterator.continually)(identity)
              val si = handleMaybeIt(senders)(Iterator.continually)(identity)
              Async.group:
                (ri zip si).take(parallelism).map { (r, s) => Future(task(r, s)) }.foreach(_.await)
          catch case _: StreamResult.StreamTerminatedException => {} // main goal: to cancel Async.group

  def pushed(parallelism: Int): PushSenderStream[T] = pushedBy(parallelism): (reader, sender) =>
    val handle = reader.pull(it => { sender.send(it); true })
    var result: Option[StreamResult.Done] = None

    // The Cancellation Alphabet
    // case (1) upstream returns termination / (2) downstream throws
    // case (A) semi-terminal (closed) / (B) terminal (terminated or with Throwable cause)

    try
      while result.isEmpty do result = handle()
      sender.terminate(result.get) // in case (1) --> notify downstream

      result.get match // case (1B) --> interrupt stream operation
        case t: Throwable               => throw StreamResult.StreamTerminatedException(t)
        case _: StreamResult.Terminated => throw StreamResult.StreamTerminatedException()
        case _                          => {} // case (1A) --> just stop this single task silently
    catch case _: ChannelClosedException => {} // case (2A) --> just stop silently, but case (2B) goes up to interrupt

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

  override def take(count: Int): PullChannelStream[T] =
    new PullLayers.TakeLayer.ChannelMixer[T]
      with PullLayers.TakeLayer.TakeLayer(count)
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
      upstream.runWithReader(parallelism) { inReader => body(mapMaybeIt(inReader)(transform)) }

  trait ChannelMixer[T, V] extends PullChannelStream[V]:
    self: FromChannelLayer[T] =>
    def transform(channel: ReadableStreamChannel[T]): ReadableStreamChannel[V]
    override def runWithChannel[A](parallelism: Int)(body: PullSource[ReadableStreamChannel, V] => Async ?=> A)(using
        Async
    ): A =
      upstream.runWithChannel(parallelism) { inChannel => body(mapMaybeIt(inChannel)(transform)) }

  object MapLayer:
    trait MapLayer[T, V](val mapper: T => V)

    trait ReaderLayer[T, V] extends StreamReader[V]:
      self: FromReader[T] with MapLayer[T, V] =>
      override def readStream()(using Async): StreamResult[V] = upstream.readStream().map(mapper)
      override def pull(onItem: V => (Async) ?=> Boolean): StreamPull = upstream.pull(onItem.compose(mapper))

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
      override def pull(onItem: T => (Async) ?=> Boolean): StreamPull =
        upstream.pull(item => filter(item) && onItem(item))

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

  object TakeLayer:
    trait TakeLayer(val count: Int)

    // can never know whether readStream() item result is already consumed (happens after return) -> do not terminate
    abstract class ReaderLayer[T](remaining: AtomicInteger) extends StreamReader[T]:
      self: FromReader[T] =>

      override def pull(onItem: T => (Async) ?=> Boolean): StreamPull =
        val handle = upstream.pull: item =>
          if remaining.getAndDecrement() > 0 then onItem(item)
          else
            remaining.set(0)
            true

        () => if remaining.get() > 0 then handle() else Some(StreamResult.Closed)

      override def readStream()(using Async): StreamResult[T] =
        if remaining.get() > 0 then
          val result = upstream.readStream()
          if result.isRight then
            if remaining.getAndDecrement() > 0 then result
            else
              remaining.set(0)
              Left(StreamResult.Closed)
          else result
        else Left(StreamResult.Closed)
    end ReaderLayer

    class ChannelCounter(var remaining: Int)

    abstract class ChannelLayer[T](counter: ChannelCounter, lock: Lock) extends ReadableStreamChannel[T]:
      self: FromChannel[T] =>
      override val readStreamSource: Async.Source[StreamResult[T]] =
        new SourceUtil.ExternalLockedSource(upstream.readStreamSource, lock):
          override def lockedCheck(k: Listener[StreamResult[T]]): Boolean =
            if counter.remaining > 0 then true
            else
              lock.unlock()
              k.complete(Left(StreamResult.Closed), this)
              false

          override def complete(
              k: Listener.ForwardingListener[StreamResult[T]],
              data: StreamResult[T],
              source: Async.Source[StreamResult[T]]
          ): Unit =
            counter.remaining -= 1
            super.complete(k, data, source)

      override def pull(onItem: T => (Async) ?=> Boolean): StreamPull =
        val handle = upstream.pull: item =>
          lock.lock()
          val doPass = if counter.remaining > 0 then
            counter.remaining -= 1
            true
          else false
          lock.unlock()

          if doPass then onItem(item) else true

        () => if counter.remaining > 0 then handle() else Some(StreamResult.Closed)

      override def readStream()(using Async): StreamResult[T] =
        if counter.remaining > 0 then
          val result = upstream.readStream()
          if result.isRight then
            lock.lock()
            val doReturn = if counter.remaining > 0 then
              counter.remaining -= 1
              true
            else false
            lock.unlock()

            if doReturn then result else Left(StreamResult.Closed)
          else result
        else Left(StreamResult.Closed)

    trait ReaderMixer[T] extends PullReaderStream[T]:
      self: FromReaderLayer[T] with TakeLayer =>
      override def runWithReader[A](parallelism: Int)(body: PullSource[StreamReader, T] => (Async) ?=> A)(using
          Async
      ): A =
        val remaining = AtomicInteger(count)
        upstream.runWithReader(parallelism)(
          body.compose(mapMaybeIt(_)(reader => new ReaderLayer[T](remaining) with FromReader(reader)))
        )

    trait ChannelMixer[T] extends PullChannelStream[T]:
      self: FromChannelLayer[T] with TakeLayer =>
      override def runWithChannel[A](parallelism: Int)(body: PullSource[ReadableStreamChannel, T] => (Async) ?=> A)(
          using Async
      ): A =
        val counter = ChannelCounter(count)
        val lock = ReentrantLock()
        upstream.runWithChannel(parallelism)(
          body.compose(mapMaybeIt(_)(ch => new ChannelLayer[T](counter, lock) with FromChannel(ch)))
        )
  end TakeLayer
end PullLayers
