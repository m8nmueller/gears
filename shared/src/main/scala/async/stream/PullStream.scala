package gears.async.stream

import gears.async.Async
import gears.async.Channel

trait PullReaderStream[+T]:
  def toReader()(using Async): StreamReader[T]

trait PullChannelStream[+T] extends PullReaderStream[T]:
  def toChannel()(using Async): ReadableStreamChannel[T]
  override def toReader()(using Async): StreamReader[T] = toChannel()
