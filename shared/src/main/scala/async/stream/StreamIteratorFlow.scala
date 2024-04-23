package gears.async.stream

import gears.async.Async
import java.{util => ju}

object StreamIteratorFlow:
  /** Create a [[Stream.Flow]] that allows to apply the [[Iterator]] API. The iterator transformation and its steps will
    * run as one task.
    *
    * The mapper is run when the stream is started. It is given access to the upstream [[Stream]] by the Iterator
    * argument. The results, i.e., the values that the returned Iterator provides, constitute the transformed
    * [[Stream]]. Remarks on the mapper:
    *   - The input iterator is initially unbounded, which means that computations should be lazy. It will only end when
    *     the upstream terminated its [[SendableStreamChannel]].
    *   - Methods that immediately access elements (such as [[Iterator.next]] or even [[Iterator.hasNext]]) can suspend
    *     the operation.
    *   - The transformed [[Stream]] will only start emitting items after the [[mapper]] has returned.
    *
    * @param mapper
    *   a function to create a transforming iterator. See the notes above.
    * @return
    *   a flow that transforms elements that flow through an iterator
    */
  def apply[In, Out](mapper: Iterator[In] => Async ?=> Iterator[Out]): Stream.Flow[In, Out] = { (chanIn, chanOut) =>
    var termination: StreamResult.Terminated = null

    val unsafeIt = new Iterator[In]:
      // we always have to read one element in advance because channels don't have 'hasNext'

      var buf: In = _
      var bufDef = false

      // expects that !bufDef
      def loadNext(): Boolean =
        bufDef = chanIn.readStream() match // uses 'global' Async capability provided by Flow caller
          case StreamResult.Data(data) =>
            buf = data
            true
          case other =>
            termination = other.asInstanceOf[StreamResult.Terminated]
            false
        bufDef

      override def hasNext: Boolean = bufDef || loadNext()

      override def next(): In =
        if hasNext then
          bufDef = false
          buf
        else throw ju.NoSuchElementException()
    end unsafeIt

    val resultIt = mapper(unsafeIt)

    while (resultIt.hasNext)
      chanOut.send(resultIt.next())
    chanOut.terminate(termination)
  }

  extension [T](stream: Stream[T])
    /** Create a derived [[Stream]] by applying a [[StreamIteratorFlow]].
      *
      * @see
      *   StreamIteratorFlow.apply
      * @see
      *   Stream.through
      */
    inline def throughIterator[U](inline mapper: Iterator[T] => Async ?=> Iterator[U])(using
        Stream.ChannelFactory
    ): Stream[U] =
      stream.through(StreamIteratorFlow(mapper))

    inline def map[U](inline mapper: T => U)(using Stream.ChannelFactory): Stream[U] =
      stream.throughIterator(_.map(mapper))
    inline def filter(inline filter: T => Boolean)(using Stream.ChannelFactory): Stream[T] =
      stream.throughIterator(_.filter(filter))
    inline def mapMulti[U](inline mapper: T => IterableOnce[U])(using Stream.ChannelFactory): Stream[U] =
      stream.throughIterator(_.flatMap(mapper))
