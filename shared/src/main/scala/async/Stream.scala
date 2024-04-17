package gears.async

import gears.async.SendableChannel
import gears.async.Async
import gears.async.ReadableChannel
import gears.async.BufferedChannel
import gears.async.Future
import scala.annotation.targetName

opaque type Stream[+Out] = SendableChannel[Out] => List[Async ?=> Unit]

/** A (cold) stream is constructed in three stages:
  *   - The [[Stream]] instance is created given a curried function of a channel (to write to) and an async capability
  *   - The stream is attached to a channel (by applying the function) generating a set of async tasks
  *   - The async tasks are started, each as a separate Future. Now, those tasks actually generate data and write to the
  *     channels.
  *
  * This three-step process is embodied in the type of the [[apply]] function.
  */
object Stream:
  opaque type Flow[-In, +Out] = (ReadableChannel[In], SendableChannel[Out]) => List[Async ?=> Unit]
  opaque type ChannelFactory = [T] => () => Channel[T]

  inline def apply[Out](inline fn: SendableChannel[Out] => Async ?=> Unit): Stream[Out] = { ch => List(fn(ch)) }

  extension [Out](src: Stream[Out])
    @targetName("throughFlow")
    def through[NewOut](flow: Flow[Out, NewOut])(using fac: ChannelFactory): Stream[NewOut] =
      val ch = fac[Out]()
      val srcTasks = src(ch)
      { send =>
        val flowTasks = flow(ch, send)
        flowTasks ::: srcTasks
      }

    def through[NewOut](
        flow: (ReadableChannel[Out], SendableChannel[NewOut]) => Async ?=> Unit
    )(using fac: ChannelFactory): Stream[NewOut] =
      val ch = fac[Out]()
      val srcTasks = src(ch)
      { send =>
        val flowTask: Async ?=> Unit = flow(ch, send)
        flowTask :: srcTasks
      }

    def run[T](handler: ReadableChannel[Out] => Async ?=> T)(using fac: ChannelFactory)(using Async): T =
      val ch = fac[Out]()
      val tasks = src(ch)
      Async.group:
        tasks.foreach(Future(_))
        handler(ch)

  object ChannelFactory {
    given default: ChannelFactory = { [T] => () => BufferedChannel[T](10) }
    inline def apply(inline fac: [T] => () => Channel[T]): ChannelFactory = fac
  }

  object Flow:
    inline def apply[In, Out](
        inline fn: (ReadableChannel[In], SendableChannel[Out]) => Async ?=> Unit
    ): Flow[In, Out] = { (ch1, ch2) =>
      List(fn(ch1, ch2))
    }

  extension [In, Out](flow0: Flow[In, Out])
    @targetName("throughFlow")
    def through[NewOut](flow: Flow[Out, NewOut])(using fac: ChannelFactory): Flow[In, NewOut] =
      val ch = fac[Out]()
      { (in, out) =>
        val flow0Tasks = flow0(in, ch)
        val flowTasks = flow(ch, out)
        flowTasks ::: flow0Tasks
      }

    def through[NewOut](
        flow: (ReadableChannel[Out], SendableChannel[NewOut]) => Async ?=> Unit
    )(using fac: ChannelFactory): Flow[In, NewOut] =
      val ch = fac[Out]()
      { (in, out) =>
        val flow0Tasks = flow0(in, ch)
        val flowTasks: Async ?=> Unit = flow(ch, out)
        flowTasks :: flow0Tasks
      }
