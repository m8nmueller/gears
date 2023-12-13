This is a proof of concept for a base library for asynchronous computing in direct style. The library needs either fibers or  virtual threads as a basis. It is at present highly experimental, incomplete and provisional. It is not yet extensively tested and not optimized at all.

The concepts and code here should be regarded as a strawman, in the sense of "meant to be knocked down".

Here is a [slidedeck](./scalar-slides.pdf) of a talk given at Scalar 2023
covering some aspects of the library. A general rationale and introduction follows.

---

# Towards A New Base Library for Asynchronous Computing

Based on:
Martin Odersky
16 Feb 2023

## Why a New Library?

We are seeing increasing adoption of continuations, coroutines, or green threads in modern runtimes. Examples are goroutines in golang, coroutines in C++, or virtual threads in project Loom. Complementary to this, we see a maturing of techniques to implement continuations by code generation. Examples range from more local solutions such as async/await in C#, Python, or Scala to more sweeping implementations such as Kotlin coroutines or dotty-cps-async, and
the stack capture techniques pioneered by Krishnamurti et al. and Brachthäuser. This means that we can realistically expect support for continuations or coroutines in most runtimes in the near future.

This will lead to a fundamental paradigm shift in reactive programming since we can now assume a lightweight and universal `await` construct that can be called anywhere. Previously, most reactive code was required to be cps-transformed into (something resembling) a monad, so that suspension could be implemented in a library.

As an example, here is some code using new, direct style futures:
```scala
  val sum = Future:
    val f1 = Future(c1.read)
    val f2 = Future(c2.read)
    f1.value + f2.value
```
We set up two futures that each read from a connection (which might take a while). We return the
sum of the read values in a new future. The `value` method returns the result value of a future once it is available, or throws an exception if the future returns a `Failure`.

By contrast, with current, monadic style futures, we'd need a composition with `flatMap` to achieve the same effect:
```scala
  val sum =
    val f1 = Future(c1.read)
    val f2 = Future(c2.read)
    for
      x <- f1
      y <- f2
    yield x + y
```
The proposed direct style futures also support structured concurrency with cancellation. If the `sum`
future in the direct style is cancelled, the two nested futures reading the connections are cancelled as well. Or, if one of the nested futures
finishes with an exception, the exception is propagated and the other future is cancelled.

Lightweight blocking thus gives us a fundamentally new tool to design concurrent systems. Paired with the principles of structured concurrency this allows for direct-style systems that are both very lightweight and very expressive. In the following I describe the outline of such a system. I start with the public APIs and then discuss some internal data structures and implementation details.

## Disclaimer

The following is an exploration of what might be possible and desirable. It is backed by a complete implementation, but the implementation is neither thoroughly tested nor optimized in any way.
The current implementation only serves as a prototype to explore general feasibility of the presented concepts.

## Outline

The library is built around four core abstractions:

 - **Future** Futures are the primary active elements of the framework. A future starts a computation that delivers a result at some point in the future. The result can be a computed value or a failure value that contains an exception. One can wait for the result of a future. Futures can suspend when waiting for other futures to complete and when reading from channels.

 - **Channel** Channels are the primary passive elements of the framework.
 A channel provides a way to send data from producers to consumers (which can both be futures). There are several versions of channels. **Rendevouz channels** block both pending receivers and senders until a communication happens. **Buffered channels** allow a sender to continue immediately, buffering the sent data until it is received.

 - **Async Source** Futures and Channels are both described in terms of a new fundamental abstraction of an _asynchronous source_. Async sources can be polled or awaited  by suspending a computation. They can be composed by mapping or filtering their results, or by combining several sources in a race where the first arriving result wins.

 - **Async Context** An async context is a capability that allows a computation to suspend while waiting for the result of an async source. This capability is encapsulated in the `Async` trait. Code that has access to a (usually implicit) parameter of type `Async` is said to be in an async context. The bodies of futures are in such a context, so they can suspend.

The library supports **structured concurrency** with combinators on futures such as `alt`, which returns the first succeeding future and `zip`, which combines all success results or otherwise returns with the first failing future. These combinators are supported by a cancellation mechanism that discards futures whose outcome is no longer relevant.

**Cancellation** is scoped and hierarchical. Futures created in the scope of some other future are registered as children of that future. If a parent is cancelled, all its children are cancelled as well.

## Futures

The `Future` trait is defined as follows:
```scala
trait Future[+T] extends Async.Source[Try[T]], Cancellable:
  def result(using async: Async): Try[T]
  def value(using async: Async): T = result.get
```
Futures represent a computation that is completed concurrently. The computation yields a result value or a exception encapsulated in a `Try` result. The `value` method produces the future's value if it completed successfully or re-throws the exception contained in the `Failure` alternative of the `Try` otherwise.

The `result` method can be defined like this:
```scala
  def result(using async: Async): T = async.await(this)
```
Here, `async` is a capability that allows to suspend in an `await` method. The `Async` trait is defined as follows:
```scala
trait Async(using val support: AsyncSupport, val scheduler: support.Scheduler):
  def await[T](src: Async.Source[T]): T

  def group: CompletionGroup
  def withGroup(group: CompletionGroup): Async
```
The most important abstraction here is the `await` method.
Code with the `Async` capability can _await_ an _asynchronous source_ of type `Async.Source`. This implies that the code will suspend if the
result of the async source is not yet ready. Futures are async sources of type `Try[T]`.

## Async Sources

We have seen that futures are a particular kind of an async source. We will see other implementations related to channels later. Async sources are the primary means of communication between asynchronous computations and they can be composed in powerful ways.

In particular, we have an extension method on async sources of type `Source[T]` that transforms elements of that source:
```scala
  def map[U](f: T => U): Source[U]
```

Furthermore, there is a `race` method that passes on the first of several sources:
```scala
  def race[T](sources: Source[T]*): Source[T]
```

These methods are building blocks for higher-level operations. For instance, `Async` also defines an `either` combinator over two sources `src1: Source[T1]` and `src2: Source[T2]` that returns an `Either[T1, T2]` with the result of `src1` if it finishes first and with the result of `src2` otherwise. It is defined as follows:
```scala
  def either[T1, T2](src1: Source[T1], src2: Source[T2]): Source[Either[T, U]] =
    race(src1.map(Left(_)), src2.map(Right(_)))
```
We distinguish between _original_ async sources such as futures or channels and _derived_ sources such as the results of `map` or `race`.

Async sources need to define three abstract methods in trait `Async.Source[T]`:
```scala
  trait Source[+T]:
    def poll(k: Listener[T]): Boolean
    def onComplete(k: Listener[T]): Unit
    def dropListener(k: Listener[T]): Unit
```

The `poll` method of an async source allows to poll whether data is present. If that's the case, the listener `k` is applied to the data. The result of `poll` is `true` if the source has data available (independent of the listener's availability, see below) and `false` otherwise. There is also a first-order variant of `poll` that returns data in an `Option`. It is defined
as follows:
```scala
  def poll(): Option[T] =
    var resultOpt: Option[T] = None
    poll(Listener.acceptingListener { (x, _) => resultOpt = Some(x) })
    resultOpt
```
The `onComplete` method of an async source calls the listener `k` once data is present. This could either be immediately, in which case the effect is the same as `poll`, or it could be in the future in which case the listener is installed in waiting lists in the original sources on which it depends so that it can be called when the data is ready. Note that there could be several such original sources, since the listener could have been passed to a `race` source, which itself depends on several other sources.

The `dropListener` method drops the listener `k` from the waiting lists of all original sources on which it depends. This an optimization that is necessary in practice to support races efficiently. Once a race is decided, all losing listeners will never pass data (i.e. they always return `false`), so we do not want them to clutter the waiting lists of their original sources anymore. In general, we recommend listeners that get somehow completed externally, to be dropped from their sources for the same reason.

A typical way to implement `onComplete` for original sources is to poll first and install a listener only if no data is present. This behavior is encapsulated in the `OriginalSource` abstraction:
```scala
  abstract class OriginalSource[+T] extends Source[T]:

    /** Add `k` to the waiting list of this source */
    protected def addListener(k: Listener[T]): Unit

    def onComplete(k: Listener[T]): Unit = synchronized:
      if !poll(k) then addListener(k)
```
So original sources are defined in terms if `poll`, `addListener`, and `dropListener`.


## Listeners

All three methods of a async source take a `Listener` argument which is defined as follows:
```scala
trait Listener[-T]:
  def complete(data: T, source: Async.Source[T]): Unit
  val lock: Listener.ListenerLock | Null

  def lockCompletely(source: Async.Source[T]): Locked.type | Gone.type
  def releaseLock(to: Listener.LockMarker): Unit

  def completeNow(data: T, source: Async.Source[T]): Boolean
```
The `T` argument is the value obtained from an async source. The basic operation of a listener is the `complete` operation which is passed the value from the async source and the source instance itself. A listener may also employ locking, which is especially important for not taking elements away from channels if they would be lost in a race. If it does not, `lock` is null and complete can always be called. If it does, complete may only be called with the lock held and `lock` is set to an instance with these abstract members:

```scala
  trait ListenerLock:
    val selfNumber: Long
    def lockSelf(source: Async.Source[?]): LockResult
    def release(to: Listener.LockMarker): ListenerLock | Null
```

The `lockSelf` operation locks the listener's lock and checks whether the listener can still take an element from the given source. The result can be `Locked` (completable), `Gone` (not completable), or a `PartialLock`. A `PartialLock` represents an intermediate step in locking where the listener would consider itself completable but the final decision depends on a downstream listener (e.g., on the original listener passed to a race source). The selfNumber is a unique number for native locks that can be obtained by deriving `NumberedLock`. It is used for deadlock prevention when locking two listeners at the same time. This can be done using the following utility method:
```scala
def lockBoth[T, U](st: Async.Source[T], su: Async.Source[U])(lt: Listener[T], lu: Listener[U]): lt.type | lu.type | Locked.type
```
It takes two listeners with the corresponding sources to pass to `lockSelf`. It returns `Locked` if both listeners can be locked, or the listener instance that rejected first.

Sources will usually use `completeNow` to lock and complete a listener immediately. It handles locking and returns true if the locking succeeded and the listener was completed, false otherwise. If a source needs to complete two listeners atomically (as a rendezvous channel does), it can use `lockBoth` and `complete`. If a lock is acquired but the listener is not completed, it should be released with `releaseLock(Locked)`. The parameter can also be a `PartialLock` if locking failed or is cancelled in an intermediate step.

## Creating Futures

A simple future can be created by calling the `apply` method of the `Future` object. We have seen an example in the introduction:

```scala
  val sum = Future:
    val f1 = Future(c1.read)
    val f2 = Future(c2.read)
    f1.value + f2.value
```
The `Future.apply` method has the following signature:
```scala
  def apply[T](body: Async ?=> T)(using Async): Future[T]
```
`apply` wraps an `Async` capability with cancellation handling (tied to the returned `Future`) and passes it to its `body` argument.

Futures also have a set of useful combinators that support what is usually called _structured concurrency_. In particular, there is the `zip` operator, which takes two futures and if they both complete successfully returns their results in a pair. If one or both of the operand futures fail, the first failure is returned as failure result of the zip. Dually, there is the `alt` operator, which returns the result of the first succeeding future and fails only if both operand futures fail.

`zip` and `alt` can be implemented as extension methods on futures as follows:

```scala
  extension [T](f1: Future[T])

    def zip[U](f2: Future[U])(using Async): Future[(T, U)] = Future:
      Async.either(f1, f2).awaitResult match
        case Left(Success(x1))    => (x1, f2.value)
        case Right(Success(x2))   => (f1.value, x2)
        case Left(Failure(ex))    => throw ex
        case Right(Failure(ex))   => throw ex

    def alt(f2: Future[T])(using Async): Future[T] = Future:
      Async.either(f1, f2).awaitResult match
        case Left(Success(x1))    => x1
        case Right(Success(x2))   => x2
        case Left(_: Failure[?])  => f2.value
        case Right(_: Failure[?]) => f1.value
```
The `zip` implementation calls `await` over a source which results from an `either`. We have seen that `either` is in turn implemented by a combination of `map` and `race`. It distinguishes four cases reflecting which of the argument futures finished first, and whether that was with a success or a failure.

The `alt` implementation starts in the same way, calling `await` over `either`. If the first result was a success, it returns it. If not, it waits for the second result.

In some cases an operand future is no longer needed for the result of a `zip` or an `alt`. For `zip` this is the case if one of the operands fails, since then the result is always a failure, and for `alt` this is the case if one of the operands succeeds, since then the result is that success value.

## Cancellation

Futures that are no longer needed can be cancelled. `Future` extends the `Cancellable` trait, which is defined as follows:
```scala
  trait Cancellable:
    def cancel(): Unit
    def link(group: CompletionGroup): this.type
    ...
```
A cancel request is transmitted via the `cancel` method. It interrupts running `await`s and sets the `cancelRequest` flag of the future to `true`. The flag is also tested before each `await`. If a test returns `true` or the cancellation was requested while awaiting, a `CancellationException` is thrown, which usually terminates the running future.

## Completion Groups

A cancellable object such as a future belongs to a `CompletionGroup`. Completion groups are themselves cancellable objects. Cancelling a completion group means cancelling all its members.
```scala
class CompletionGroup(val handleCompletion: Cancellable => Async ?=> Unit) extends Cancellable.Tracking:
  def cancel(): Unit
  def add(member: Cancellable): Unit
  def drop(member: Cancellable): Unit
```

Cancellation is persistent, such that a member that is added after a call to `cancel` is cancelled immediately. Cancelled objects drop themselves from the group once they finished their cleanup. This is used internally to await termination of a cancelled group.

One can include a cancellable object in a completion group using the object's `link` method. An object can belong only to one completion group, so linking an already linked cancellable object will unlink it from its previous completion group. The `link` method is defined as follows:
```scala
def link(group: CompletionGroup): this.type =
    this.group.drop(this)
    this.group = group
    this.group.add(this)
    this
```
There are also two variants of `link` in `Cancellable`, defined as follows:

```scala
trait Cancellable:
  ...
  def link()(using async: Async): this.type =
    link(async.group)
  def unlink(): this.type =
    link(CompletionGroup.Unlinked)
```
The second variant of `link` links a cancellable object to the group of the current `Async` context. The `unlink` method drops a cancellable object from its group. This is achieved by "linking" the object to the special `Unlinked` completion group, which ignores all cancel requests as well as all add/drop member requests.
```scala
object CompletionGroup
  object Unlinked extends CompletionGroup:
    override def cancel() = ()
    override def add(member: Cancellable): Unit = ()
    override def drop(member: Cancellable): Unit = ()
  end Unlinked
```

## Structured Concurrency

As we have seen in the `sum` example, futures can be nested.
```scala
  val sum = Future:
    val f1 = Future(c1.read)
    val f2 = Future(c2.read)
    f1.value + f2.value
```
Our library follows the _structured concurrency_ principle which says that
the lifetime of nested computations is contained within the lifetime of enclosing computations. In the previous example, `f1` and `f2` will be guaranteed to terminate when the `sum` future terminates. This is already implied by the program logic if both futures terminate successfully. But what if `f1` fails with an exception? In that case `f2` will be canceled before the `sum` future is completed.

The mechanism which achieves this is as follows: When defining a future,
the body of the future is run in the scope of an `Async.group` wrapper, which is defined like this:
```scala
  def group[T](body: Async ?=> T)(using async: Async): T =
    val newGroup = CompletionGroup().link()
    try body(using async.withGroup(newGroup))
    finally
      newGroup.cancel()
      newGroup.waitCompletion()(using async.withGroup(CompletionGroup.Unlinked))
```
The `group` wrapper sets up a new completion group, runs the given `body` in an `Async` context with that group, and finally cancels the group and awaits its termination once `body` has finished.

## Channels

Channels are a means for futures and related asynchronous computations to synchronize and exchange messages.
There are two broad categories of channels: _asynchronous_ or _synchronous_. Synchronous channels block the sender of a message until it is received, whereas asynchronous channels don't do this as a general rule (but they might still block a sender by some back-pressure mechanism or if a bounded buffer gets full).

The general interface of a channel is as follows:
```scala
trait Channel[T]:
  def read()(using Async): T
  def send(x: T)(using Async): Unit
```
Channels provide

 - a `read` method, which might suspend while waiting for a message to arrive,
 - a `send` method, which also might suspend in case this is a sync channel or there is some other mechanism that forces a sender to wait,

### Async Channels

An asynchronous channel implements both the `Async.Source` and `Channel` interfaces. This means inputs from an asynchronous channel can be mapped, filtered or combined with other sources in races.

```scala
class AsyncChannel[T] extends Async.OriginalSource[T], Channel[T]
```

### Synchronous Channels

A sync channel pairs a read request with a send request in a _rendezvous_. Readers and/or senders are blocked until a rendezvous between them is established which causes a message to be sent and received. A sync channel provides two separate async sources
for reading a message and sending one. The `canRead` source
provides messages to readers of the channel. The `canSend` source
provides message listeners to writers that send messages to the channel.
```scala
trait SyncChannel[T] extends Channel[T]:

  val canRead: Async.Source[T]
  val canSend: Async.Source[Listener[T]]

  def send(x: T)(using Async): Unit = await(canSend)(x)
  def read()(using Async): T = await(canRead)
```

## Tasks

One criticism leveled against futures is that they "lack referential transparency". What this means is that a future starts running when it is defined, so passing a reference to a future is not the same as passing the referenced expression itself. Example:
```scala
val x = Future { println("started") }
f(x)
```
is not the same as
```scala
val x = Future { println("started") }
f(Future { println("started") })
```
In the first case the program prints "started" once whereas in the second case it prints "started" twice. In a sense that's exactly what's intended. After all, the whole point of futures is to get parallelism. So a future should start well before its result is requested and the simplest way to achieve that is to start the future when it is defined. _Aside_: I believe the criticism of the existing `scala.concurrent.Future` design in Scala 2.13 is understandable, since
these futures are usually composed monad-style using for expressions, which informally suggests referential transparency. Direct-style futures like the ones presented here don't have that problem.

On the other hand, the early start of futures _does_ makes it harder to assemble parallel computations as first class values in data structures and to launch them according to user-defined execution rules. Of course one can still achieve all that by working with
functions producing futures instead of futures directly. A function of type `() => Future[T]` will start executing its embedded future only once it is called.

Tasks make the definition of such delayed futures a bit easier. The `Task` class is defined
as follows:
```scala
class Task[+T](val body: Async ?=> T):
  def run(using Async) = Future(body)
```
A `Task` takes the body of a future as an argument. Its `run` method converts that body to a `Future`, which means starting its execution.

Example:
```scala
  val chan: Channel[Int]
  val allTasks = List(
      Task:
        println("task1")
        chan.read(),
      Task:
        println("task2")
        chan.read()
    )

  def start() = Future:
    allTasks.map(_.run.value).sum
```

Tasks have two advantages over simple lambdas when it comes to delaying futures:

 - The intent is made clear: This is a delayed computation intended to be executed concurrently in a future once it is started.
 - The `Async` context is implicitly provided, since `Task.apply` takes a context function over `Async` as argument.

## Promises

Sometimes we want to define future's value externally instead of executing a specific body of code. This can be done using a promise.
The design and implementation of promises is simply this:
```scala
class Promise[T]:
  private val myFuture = CoreFuture[T]()

  val future: Future[T] = myFuture

  def complete(result: Try[T]): Unit =
    myFuture.complete(result)
```
A promise provides a `future` and a way to define the result of that
future in its `complete` method.

## Going Further

The library is expressive enough so that higher-order abstractions over channels can be built with ease. In the following, I outline some of the possible extensions and explain how they could be defined and implemented.

### Streams

A stream represents a sequence of values that are computed one-by-one in a separate concurrent computation. Conceptually, streams are simply nested futures, where each future produces one element:
```scala
  type Stream[+T] = Future[StreamResult[T]]

  enum StreamResult[+T]:
    case More(elem: T, rest: Stream[T])
    case End extends StreamResult[Nothing]
```
One can see a stream as a static representation of the values that are transmitted over a channel. This poses the question of termination -- when do we know that a channel receives no further values, so the stream can be terminated
with an `StreamResult.End` value? The following implementation shows one
possibility: Here we map a channel of `Try` results to a stream, mapping
failures with a special `ChannelClosedException` to `StreamResult.End`.
```scala
  extension [T](c: Channel[Try[T]])
    def toStream(using Async): Stream[T] = Future:
      c.read() match
        case Success(x) => StreamResult.More(x, toStream)
        case Failure(ex: ChannelClosedException) => StreamResult.End
        case Failure(ex) => throw ex
```

### Coroutines or Fibers

A coroutine or fiber is simply a `Future[Unit]`. This might seem surprising at first. Why should we return something from a coroutine or fiber? Well, we certainly do want to observe that a coroutine has terminated, and we also need to handle any exceptions that are thrown
from it. A result of type `Try[Unit]` has exactly the information we need for this. We typically want to add some supervisor framework that
waits for coroutines to terminate and handles failures. A possible setup would be to send terminated coroutines to a channel that is serviced by a supervisor future.

### Actors

Similarly, we can model an actor by a `Future[Unit]` paired with a channel which serves as the actor's inbox.

## Implementation Details

## Internals of Async Contexts

An async context provides three elements:

 - an `await` method that allows a caller to suspend while waiting for the result of an async source to arrive,
 - a `scheduler` value that refers to execution context on which tasks are scheduled,
 - a `group` value that contains a completion group which determines the default linkage of all cancellable objects that are created in an async context.

## Implementing Await

The most interesting part of an async context is its implementation of the `await` method. These implementations need to be based on a lower-level mechanism of suspensions or green threads.

### Using Delimited Continuations

We first describe
the implementation if support for full delimited continuations is available. We assume in this case a trait
```scala
trait Suspension[-T, +R]:
  def resume(arg: T): R = ???
```
and a method
```scala
def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T = ???
```
A call of `suspend(body)` captures the continuation up to an enclosing boundary in a `Suspension` object and passes it to `body`. The continuation can be resumed by calling the suspension's `resume` method. The enclosing boundary
is the one which created the implicit `Label` argument.

Using this infrastructure, `await` can be implemented like this:
```scala
  def await[T](src: Async.Source[T]): T =
    checkCancellation()
    src.poll().getOrElse:
      try
        suspend[T, Unit]: k =>
          src.onComplete: x =>
            scheduler.schedule: () =>
              k.resume(x)
            true // signals to `src` that result `x` was consumed
      finally checkCancellation()
```
Notes:

 - The main body of `await` is enclosed by two `checkCancellation` calls that abort
 the computation with a `CancellationException` in case of a cancel request.
 - Await first polls the async source and returns the result if one is present.
 - If no result is present, it suspends the computation and adds a listener to the source via its `onComplete` method. The listener is generated via a SAM conversion from the closure following `x =>`.
 - If the listener is invoked with a result, it resumes the suspension with that result argument in a newly scheduled task. The listener returns `true` to indicate that the result value was consumed.

An Async context with this version of `await` is used in the following
implementation of `async`, the wrapper for the body of a future:
```scala
private def async(body: Async ?=> Unit): Unit =
  class FutureAsync ... extends Async:
    def await[T](src: Async.Source[T]): T = ...
    ...

  boundary [Unit]:
    body(using FutureAsync(...))
```

### Using Fibers

On a runtime that only provides fibers (_aka_ green threads), the implementation of `await` is a bit more complicated, since we cannot suspend awaiting an argument value. We can work around this restriction by re-formulating the body of `await` as follows:
```scala
  def await[T](src: Async.Source[T]): T =
    checkCancellation()
    src.poll().getOrElse:
      try
        var result: Option[T] = None
        src.onComplete: x =>
          synchronized:
            result = Some(x)
            notify()
          true
        synchronized:
          while result.isEmpty do wait()
          result.get
      finally checkCancellation()
```
Only the body of the `try` is different from the previous implementation. Here we now create a variable holding an optional result value. The computation `wait`s until the result value is defined. The variable becomes is set to a defined value when the listener is invoked, followed by a call to `notify()` to wake up the waiting fiber.

Since the whole fiber suspends, we don't need a `boundary` anymore to delineate the limit of a continuation, so the `async` can be defined as follows:
```scala
private def async(body: Async ?=> Unit): Unit =
  class FutureAsync(...) extends Async:
    def await[T](src: Async.Source[T]): T = ...
    ...

  body(using FutureAsync(...))
```

### Implementation in Gears

Gears contains an abstraction over those two implementations. It is defined as follows:

```scala
trait Suspension[-T, +R]:
    def resume(arg: T): R

trait SuspendSupport:
    type Label[R]
    type Suspension[-T, +R] <: gears.async.Suspension[T, R]

    def boundary[R](body: Label[R] ?=> R): R
    def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T

trait AsyncSupport extends SuspendSupport:
    type Scheduler <: gears.async.Scheduler

    private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using s: Scheduler): Unit =
        s.execute(() => suspension.resume(arg))

    private[async] def scheduleBoundary(body: Label[Unit] ?=> Unit)(using s: Scheduler): Unit =
        s.execute(() => boundary(body))

trait Scheduler:
    def execute(body: Runnable): Unit
    def schedule(delay: FiniteDuration, body: Runnable): Cancellable
```

While the `SuspendSupport` defines the interface of the delimited continuations introduced above, the `AsyncSupport` extends it providing merged operations of continuations and scheduling. This allows to plug in a green fiber implementation while avoiding most of the overhead for the common Future operations.

Using JVM’s virtual threads, a scheduler can be implemented like this:
```scala
object VThreadScheduler extends Scheduler:
  override def execute(body: Runnable): Unit = Thread.startVirtualThread(body)

  override def schedule(delay: FiniteDuration, body: Runnable): Cancellable =
    val th = Thread.startVirtualThread: () =>
      Thread.sleep(delay.toMillis)
      body.run()
    () => th.interrupt()
```

The suspension is implemented similarly as described in the fiber approach above using virtual thread-compatible locks and condition variables. The data returned by the body parameter of `suspend` is stored in the label while the data given to `resume` is stored in the suspension instance.
```scala
  class VThreadSuspension[-T, +R](using val label: Label[R] @uncheckedVariance) extends Suspension[T, R]:
    private var nextInput: Option[T] = None
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    def setInput(data: T) =
      lock.lock()
      try
        nextInput = Some(data)
        cond.signalAll()
      finally
        lock.unlock()

    def waitInput(): T @uncheckedVariance =
      lock.lock()
      try
        while nextInput.isEmpty do
          cond.await()
        nextInput.get
      finally
        lock.unlock()

    override def resume(data: T): R =
      label.clearResult()
      setInput(data)
      label.waitResult()
```

`setInput` and `waitInput` constitute a basic blocking channel. This is used in `resume` to continue the suspended operation (that awaits the input) while parking the operation that called `resume` until the suspended (now running) operation suspends again or finishes. Suspend is then implemented as follows:
```scala
  override def suspend[T, R](body: Suspension[T, R] => R)(using label: Label[R]): T =
    val suspension = new VThreadSuspension[T, R]()
    val result = body(suspension)
    label.setResult(result)
    suspension.waitInput()
```

Here, the outer context (creator of the boundary/label or caller of `resume`) is waked up in `setResult` while the operation that called suspend now waits for the next input.

The boundary must be detached from the calling operation (because it can suspend at any time). It is therefore spawned as a virtual thread (fiber).

```scala
override def boundary[R](body: (Label[R]) ?=> R): R =
    val label = VThreadLabel[R]()
    Thread.startVirtualThread: () =>
      val result = body(using label)
      label.setResult(result)

    label.waitResult()
```
On the caller’s side, the label is set up, a new thread is started, and the label is awaited. It may either be completed by a `suspend` from within the body or by the final result.

For the common use case of scheduling a boundary (for starting a future) or scheduling the `resume` of a suspension (for resuming a waiting Future), a virtual thread is started by the scheduling. This thread is only used for starting another thread (implementation of a `boundary`) or for setting a variable (implementation of `resume`) and blocking afterwards (in both cases). Instead the following conjoint methods are used:
```scala
  def scheduleBoundary(body: (Label[Unit]) ?=> Unit)(using Scheduler): Unit =
    Thread.startVirtualThread: () =>
      val label = VThreadLabel[Unit]()
      body(using label)

  def resumeAsync[T, R](suspension: Suspension[T, R])(data: T)(using Scheduler): Unit =
      suspension.label.clearResult()
      suspension.setInput(data)
```
The difference to `boundary` and `resume` as presented above is the missing `waitResult` and `waitInput`, respectively. These operations would make the operations blocking. When they are omitted, no more thread needs to be spawned.
