package gears.async

import gears.async.SendableChannel
import gears.async.Async
import gears.async.ReadableChannel
import gears.async.BufferedChannel
import gears.async.Future
import scala.annotation.targetName

object Stream:
  opaque type Source[+Out] = SendableChannel[Out] => List[Async ?=> Unit]
  type HotSource[+Out] = ReadableChannel[Out]

  extension [Out](src: Source[Out])
    @targetName("throughFlow")
    def through[NewOut](bufferSize: Int)(flow: Flow[Out, NewOut]): Source[NewOut] =
      val ch = BufferedChannel[Out](bufferSize)
      val srcTasks = src(ch)
      { send =>
        val flowTasks = flow(ch, send)
        flowTasks ::: srcTasks
      }

    def through[NewOut](bufferSize: Int)(
        flow: (ReadableChannel[Out], SendableChannel[NewOut]) => Async ?=> Unit
    ): Source[NewOut] =
      val ch = BufferedChannel[Out](bufferSize)
      val srcTasks = src(ch)
      { send =>
        val flowTask: Async ?=> Unit = flow(ch, send)
        flowTask :: srcTasks
      }

    def start(bufferSize: Int)(using async: Async, spawnable: Async.Spawn & async.type): HotSource[Out] =
      val ch = BufferedChannel[Out](bufferSize)
      val tasks = src(ch)
      tasks.foreach(Future(_))
      ch

    def run[T](bufferSize: Int)(handler: ReadableChannel[Out] => Async ?=> T)(using Async): T =
      val ch = BufferedChannel[Out](bufferSize)
      val tasks = src(ch)
      Async.group:
        tasks.foreach(Future(_))
        handler(ch)

  /** A (cold) source is constructed in three stages:
    *   - The [[Source]] instance is created given a curried function of a channel (to write to) and an async capability
    *   - The source is attached to a channel (by applying the function) generating a set of async tasks
    *   - The async tasks are started, each as a separate Future. Now, those tasks actually generate data and write to
    *     the channels.
    *
    * This three-step process is embodied in the type of the [[apply]] function.
    */
  object Source:
    def apply[Out](fn: SendableChannel[Out] => Async ?=> Unit): Source[Out] = fn.andThen(List(_))

  opaque type Flow[-In, +Out] = (ReadableChannel[In], SendableChannel[Out]) => List[Async ?=> Unit]

  object Flow:
    def apply[In, Out](fn: (ReadableChannel[In], SendableChannel[Out]) => Async ?=> Unit): Flow[In, Out] = {
      (ch1, ch2) => List(fn(ch1, ch2))
    }

  extension [In, Out](flow0: Flow[In, Out])
    @targetName("throughFlow")
    def through[NewOut](bufferSize: Int)(flow: Flow[Out, NewOut]): Flow[In, NewOut] =
      val ch = BufferedChannel[Out](bufferSize)
      { (in, out) =>
        val flow0Tasks = flow0(in, ch)
        val flowTasks = flow(ch, out)
        flowTasks ::: flow0Tasks
      }

    def through[NewOut](bufferSize: Int)(
        flow: (ReadableChannel[Out], SendableChannel[NewOut]) => Async ?=> Unit
    ): Flow[In, NewOut] =
      val ch = BufferedChannel[Out](bufferSize)
      { (in, out) =>
        val flow0Tasks = flow0(in, ch)
        val flowTasks: Async ?=> Unit = flow(ch, out)
        flowTasks :: flow0Tasks
      }
