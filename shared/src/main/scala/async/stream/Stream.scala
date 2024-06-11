package gears.async.stream

private[stream] inline def handleMaybeIt[S[_], T, V](
    source: S[T] | Iterator[S[T]]
)(inline single: S[T] => V)(inline iterator: Iterator[S[T]] => V): V =
  if source.isInstanceOf[S[T]] then single(source.asInstanceOf[S[T]])
  else iterator(source.asInstanceOf[Iterator[S[T]]])

private[stream] inline def mapMaybeIt[S[_], T, V](source: S[T] | Iterator[S[T]])(single: S[T] => S[V]) =
  handleMaybeIt(source)(single)(_.map(single))
