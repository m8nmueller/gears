package gears.async

/** A Resource wraps allocation to some asynchronously allocatable and releasable resource and grants access to it. It
  * allows both structured access (similar to [[scala.util.Using]]) and unstructured allocation.
  */
trait Resource[+T]:
  self =>

  /** Run a structured action on the resource. It is allocated and released automatically.
    *
    * @param body
    *   the action to run on the resource
    * @return
    *   the result of [[body]]
    */
  def use[V](body: T => Async ?=> V)(using Async): V =
    val (res, close) = allocated
    try body(res)
    finally close

  /** Allocate the resource and leak it. **Use with caution**. The programmer is responsible for closing the resource
    * with the returned handle.
    *
    * @return
    *   the allocated access to the resource data as well as a handle to close it
    */
  def allocated(using Async): (T, Async ?=> Unit)

  /** Create a derived resource that inherits the close operation.
    *
    * @param fn
    *   the function used to transform the resource data. It is only run on allocation/use.
    * @return
    *   the transformed resource used to access the mapped resource data
    */
  def map[U](fn: T => Async ?=> U): Resource[U] = new Resource[U]:
    override def use[V](body: U => (Async) ?=> V)(using Async): V = self.use(t => body(fn(t)))
    override def allocated(using Async): (U, (Async) ?=> Unit) =
      val (res, close) = self.allocated
      var failed = true
      try
        val mapped = fn(res)
        failed = false // don't clean up
        (mapped, close)
      finally if failed then close
    override def map[Q](fn2: U => (Async) ?=> Q): Resource[Q] = self.map(t => fn2(fn(t)))
end Resource

object Resource:
  /** Create a Resource from the allocation and release operation. The returned resource will allocate a new instance,
    * i.e., call [[alloc]], for every call to [[use]] and [[allocated]].
    *
    * @param alloc
    *   the allocation (generating) operation
    * @param close
    *   the release (close) operation
    * @return
    *   a new Resource exposing the allocatable object in a safe way
    */
  inline def apply[T](inline alloc: Async ?=> T, inline close: T => Async ?=> Unit): Resource[T] =
    new Resource[T]:
      def allocated(using Async): (T, (Async) ?=> Unit) =
        val res = alloc
        (res, close(res))

  /** Create a concurrent computation resource from an allocator function. It can use the given capability to spawn
    * [[Future]]s and return a handle to communicate with them. Allocation is only complete after that allocator
    * returns. The resource is only allocated on use.
    *
    * No presumption is made on reusability of the Resource. Thus, if the [[spawnBody]] is re-runnable, so is the
    * Resource created from it.
    *
    * @param spawnBody
    *   the allocator to setup and start asynchronous computation
    * @return
    *   a new resource wrapping access to the spawnBody's results
    */
  inline def spawning[T](inline spawnBody: Async.Spawn ?=> T) = Async.spawning.map(spawn => spawnBody(using spawn))
end Resource
