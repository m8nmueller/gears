package simpleFutures

import scala.collection.mutable.ListBuffer
import runtime.{suspend, boundary, Label}

object Scheduler:
  def schedule(task: Runnable): Unit = Thread.startVirtualThread(task)

trait Async:
  def await[T](f: Future[T]): T

class Future[+T](body: Async ?=> T):
  private var result: Option[T] = None
  private var waiting: ListBuffer[T => Unit] = ListBuffer()
  private def addWaiting(k: T => Unit): Unit =
    synchronized:
      result match
        case None => waiting += k
        case Some(value) => k(value)

  def await(using a: Async): T = a.await(this)

  private def complete(): Unit =
    Future.async:
      val value = body
      synchronized:
        result = Some(value)
        for k <- waiting do
          Scheduler.schedule(() => k(value))
        waiting.clear()

  Scheduler.schedule(() => complete())

object Future:

  // a handler for Async
  def async(body: Async ?=> Unit): Unit =
    boundary [Unit]:
      given Async with
        def await[T](f: Future[T]): T = f.result match
          case Some(x) => x
          case None => suspend[T, Unit](s => f.addWaiting(s.resume))
      body

end Future

def Test(x: Future[Int], xs: List[Future[Int]]) =
  Future:
    x.await + xs.map(_.await).sum








