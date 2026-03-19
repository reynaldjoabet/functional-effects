package zio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext

import zio.ZIO.Async
import zio.ZIO.Fork
import zio.ZIO.Succeed
import zio.ZIO.SucceedNow

// Declarative encoding CHECK
// Stack safety CHECK
// Concurrency safety CHECK
// Custom ExecutionContext CHECK
// Interruption
// Error handling CHECK
// Environment

trait Fiber[+E, +A] {

  def join: ZIO[Any, E, A]

  def interrupt: ZIO[Any, Nothing, Unit]

}

// type IO[+E, +A] = ZIO[Any, E, A]

final private case class FiberContext[E, A](
  startZIO: ZIO[Any, E, A],
  startExecutor: ExecutionContext
) extends Fiber[E, A] {

  sealed trait FiberState

  case class Running(callbacks: List[Exit[E, A] => Any]) extends FiberState
  case class Done(result: Exit[E, A])                    extends FiberState

  val state: AtomicReference[FiberState] =
    new AtomicReference(Running(List.empty))

  // Has someone sent us the signal to stop executing?
  val interrupted: AtomicBoolean =
    new AtomicBoolean(false)

  // Are we in the process of finalizing ourselves
  val isInterrupting: AtomicBoolean =
    new AtomicBoolean(false)

  // Are we in a region where we are subject to being interrupted?
  val isInterruptible: AtomicBoolean =
    new AtomicBoolean(true)

  def shouldInterrupt(): Boolean =
    interrupted.get() && isInterruptible.get() && !isInterrupting.get()

  def interrupt: ZIO[Any, Nothing, Unit] =
    ZIO.succeed(interrupted.set(true))

  def complete(result: Exit[E, A]): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get
      oldState match {
        case Running(callbacks) =>
          if (state.compareAndSet(oldState, Done(result))) {
            callbacks.foreach(cb => cb(result))
            loop = false
          }
        case Done(result) =>
          throw new Exception("Internal defect: Fiber being completed multiple times")
      }
    }
  }

  def await(callback: Exit[E, A] => Any): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get
      oldState match {
        case Running(callbacks) =>
          val newState = Running(callback :: callbacks)
          loop = !state.compareAndSet(oldState, newState)
        case Done(result) =>
          callback(result)
          loop = false
      }
    }
  }

  override def join: ZIO[Any, E, A] =
    ZIO
      .async[Exit[E, A]] { callback =>
        await(callback)
      }
      .flatMap(ZIO.done)

  type Erased         = ZIO[Any, Any, Any]
  type ErasedCallback = Any => Any
  type Cont           = Any => Erased

  def erase[R, E, A](zio: ZIO[R, E, A]): Erased =
    zio.asInstanceOf[Erased]

  def eraseCallback[A](cb: A => Unit): ErasedCallback =
    cb.asInstanceOf[ErasedCallback]

  val stack = new scala.collection.mutable.Stack[Cont]()

  val envStack = new scala.collection.mutable.Stack[Any]()

  var currentZIO      = erase(startZIO)
  var currentExecutor = startExecutor

  var loop = true

  def resume(): Unit = {
    loop = true
    run()
  }

  def continue(value: Any): Unit =
    if (stack.isEmpty) {
      loop = false
      complete(Exit.Success(value.asInstanceOf[A]))
    } else {
      val cont = stack.pop()
      currentZIO = cont(value)
    }

  def findNextErrorHandler(): ZIO.Fold[Any, Any, Any, Any, Any] = {
    var loop                                            = true
    var errorHandler: ZIO.Fold[Any, Any, Any, Any, Any] = null
    while (loop)
      if (stack.isEmpty) loop = false
      else {
        val cont = stack.pop()
        if (cont.isInstanceOf[ZIO.Fold[?, ?, ?, ?, ?]]) {
          errorHandler = cont.asInstanceOf[ZIO.Fold[Any, Any, Any, Any, Any]]
          loop = false
        }
      }
    errorHandler
  }

  // currentZIO = Fold(_ => ZIO.succeed("close the file"), _ => ZIO.succeed("close the file"))

  def run(): Unit =
    while (loop) {
      if (shouldInterrupt()) {
        isInterrupting.set(true)
        stack.push(_ => currentZIO)
        currentZIO = ZIO.failCause(Cause.Interrupt)
      } else {
        try {
          currentZIO match {
            case ZIO.SucceedNow(value) =>
              continue(value)

            case ZIO.Succeed(thunk) =>
              continue(thunk())

            case ZIO.FlatMap(zio, cont) =>
              stack.push(cont.asInstanceOf[Cont])
              currentZIO = zio

            case ZIO.Async(register) =>
              if (stack.isEmpty) {
                loop = false
                register(a => complete(Exit.Success(a.asInstanceOf[A])))
              } else {
                loop = false
                register { a =>
                  currentZIO = ZIO.succeedNow(a)
                  resume()
                }
              }

            case ZIO.Fork(zio) =>
              val fiber = FiberContext(zio, currentExecutor)
              continue(fiber)

            case ZIO.Shift(executor) =>
              currentExecutor = executor
              continue(())

            // zio1 *> zio2.uninterruptible *> zio3

            case ZIO.SetInterruptStatus(zio, interruptStatus) =>
              val oldIsInterruptible = isInterruptible.get()
              isInterruptible.set(interruptStatus.toBoolean)
              currentZIO = zio.ensuring(ZIO.succeed(isInterruptible.set(oldIsInterruptible)))

            case ZIO.Fail(e) =>
              val errorHandler = findNextErrorHandler()
              if (errorHandler eq null) {
                complete(Exit.Failure(Cause.Fail(e().asInstanceOf[E])))
              } else {
                currentZIO = errorHandler.failure(e())
              }

            case fold @ ZIO.Fold(zio, failure, success) =>
              stack.push(fold.asInstanceOf[Cont])
              currentZIO = zio

            case ZIO.Provide(zio, env) =>
              envStack.push(env)
              currentZIO = zio.ensuring(ZIO.succeed(envStack.pop())).asInstanceOf[Erased]

            case ZIO.Access(f) =>
              val currentEnvironment = envStack.head
              currentZIO = f(currentEnvironment)
          }
        } catch {
          case t: Throwable => currentZIO = ZIO.die(t)
        }
      }
    }

  currentExecutor.execute(() => run())

}

// val zio: ZIO[Nothing, Unit] = ZIO.succeed { throw new Exception("Fiber interrupted") }

// R => Either[E, A]
// R => Either[Cause[E], A]

// 1. Introduce type parameter
// 2. Break all the things
// 3. Fix all the compilation errors (yay! pair coding with Kit)
// 4. Compiling but not actually using type parameters
// 5. Fundamental operators - INTRODUCE: access / service 🤝 — ELIMINATE: provide 🔫🤡
// 6. Introduce the operator
// 7. Implement in terms of new primitives
// 8. Implement those primitives

sealed trait ZIO[-R, +E, +A] { self =>

  def fork: ZIO[R, Nothing, Fiber[E, A]] =
    ZIO.Fork(self)

  def as[B](value: => B): ZIO[R, E, B] =
    self.map(_ => value)

  def catchAll[R1 <: R, E2, A1 >: A](f: E => ZIO[R1, E2, A1]): ZIO[R1, E2, A1] =
    foldZIO(e => f(e), a => ZIO.succeedNow(a))

  def ensuring[R1 <: R](finalizer: ZIO[R1, Nothing, Any]): ZIO[R1, E, A] =
    foldCauseZIO(cause => finalizer *> ZIO.failCause(cause), a => finalizer *> ZIO.succeedNow(a))

  def flatMap[R1 <: R, E1 >: E, B](f: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    ZIO.FlatMap(self, f)

  def fold[B](failure: E => B, success: A => B): ZIO[R, E, B] =
    foldZIO(e => ZIO.succeedNow(failure(e)), a => ZIO.succeedNow(success(a)))

  def foldZIO[R1 <: R, E2, B](
    failure: E => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  ): ZIO[R1, E2, B] =
    foldCauseZIO(
      {
        case Cause.Fail(e)        => failure(e)
        case Cause.Die(throwable) => ZIO.failCause(Cause.Die(throwable))
        case Cause.Interrupt      => ZIO.failCause(Cause.Interrupt)
      },
      success
    )

  def foldCauseZIO[R1 <: R, E2, B](
    failure: Cause[E] => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  ): ZIO[R1, E2, B] =
    ZIO.Fold(self, failure, success)

  def forever: ZIO[R, E, Nothing] =
    self *> self.forever

  def map[B](f: A => B): ZIO[R, E, B] =
    flatMap(f.andThen(ZIO.succeedNow))

  // 🔫🤡 ELIMINATES THE ENVIRONMENT
  def provide(r: R): ZIO[Any, E, A] =
    ZIO.Provide(self, r)

  def repeat(n: Int): ZIO[R, E, Unit] =
    if (n <= 0) ZIO.succeedNow(())
    else self *> repeat(n - 1)

  def setInterruptStatus(interruptStatus: InterruptStatus): ZIO[R, E, A] =
    ZIO.SetInterruptStatus(self, interruptStatus)

  def interruptible: ZIO[R, E, A] =
    setInterruptStatus(InterruptStatus.Interruptible)

  def uninterruptible: ZIO[R, E, A] =
    setInterruptStatus(InterruptStatus.Uninterruptible)

  def shift(executor: ExecutionContext): ZIO[R, Nothing, Unit] =
    ZIO.Shift(executor)

  def zipPar[R1 <: R, E1 >: E, B](that: ZIO[R1, E1, B]): ZIO[R1, E1, (A, B)] =
    for {
      f1 <- self.fork
      b  <- that
      a  <- f1.join
    } yield (a, b)

  def zip[R1 <: R, E1 >: E, B](that: ZIO[R1, E1, B]): ZIO[R1, E1, (A, B)] =
    zipWith(that)(_ -> _)

  def *>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    self.zipRight(that)

  def zipRight[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    zipWith(that)((_, b) => b)

  def zipWith[R1 <: R, E1 >: E, B, C](that: => ZIO[R1, E1, B])(f: (A, B) => C): ZIO[R1, E1, C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  final private def unsafeRunFiber(implicit ev: Any <:< R): Fiber[E, A] =
    FiberContext(self.asInstanceOf[ZIO[Any, E, A]], ZIO.defaultExecutor)

  final def unsafeRunSync(implicit ev: Any <:< R): Exit[E, A] = {
    val latch              = new java.util.concurrent.CountDownLatch(1)
    var result: Exit[E, A] = null.asInstanceOf[Exit[E, A]]
    val zio = self.foldCauseZIO(
      cause =>
        ZIO.succeed {
          result = Exit.Failure(cause)
          latch.countDown()
        },
      a =>
        ZIO.succeed {
          result = Exit.succeed(a)
          latch.countDown()
        }
    )
    zio.unsafeRunFiber
    latch.await()
    result
  }

}

object ZIO {

  import scala.reflect.ClassTag

  def environment[R]: ZIO[R, Nothing, R] =
    accessZIO(env => ZIO.succeed(env))

  // 🤝 INTRODUCES THE ENVIRONMENT
  def accessZIO[R, E, A](f: R => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.Access(f)

  def async[A](register: (A => Any) => Any): ZIO[Any, Nothing, A] =
    ZIO.Async(register)

  def die(throwable: Throwable): ZIO[Any, Nothing, Nothing] =
    ZIO.failCause(Cause.Die(throwable))

  def fail[E](e: => E): ZIO[Any, E, Nothing] =
    failCause(Cause.Fail(e))

  def failCause[E](cause: => Cause[E]): ZIO[Any, E, Nothing] =
    Fail(() => cause)

  def fromEither[E, A](either: Either[E, A]): ZIO[Any, E, A] =
    either.fold(e => fail(e), a => succeedNow(a))

  def done[E, A](exit: Exit[E, A]): ZIO[Any, E, A] =
    exit match {
      case Exit.Success(a) => succeedNow(a)
      case Exit.Failure(e) => failCause(e)
    }

  def succeed[A](value: => A): ZIO[Any, Nothing, A] =
    ZIO.Succeed(() => value)

  def succeedNow[A](value: A): ZIO[Any, Nothing, A] = ZIO.SucceedNow(value)

  case class SucceedNow[A](value: A) extends ZIO[Any, Nothing, A]

  case class Succeed[A](f: () => A) extends ZIO[Any, Nothing, A]

  case class FlatMap[R, E, A, B](zio: ZIO[R, E, A], f: A => ZIO[R, E, B]) extends ZIO[R, E, B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[Any, Nothing, A]

  case class Fork[R, E, A](zio: ZIO[R, E, A]) extends ZIO[R, Nothing, Fiber[E, A]]

  case class SetInterruptStatus[R, E, A](self: ZIO[R, E, A], interruptStatus: InterruptStatus)
      extends ZIO[R, E, A]

  case class Shift(executor: ExecutionContext) extends ZIO[Any, Nothing, Unit]

  case class Fail[E](e: () => Cause[E]) extends ZIO[Any, E, Nothing]

  case class Fold[R, E, E2, A, B](
    zio: ZIO[R, E, A],
    failure: Cause[E] => ZIO[R, E2, B],
    success: A => ZIO[R, E2, B]
  ) extends ZIO[R, E2, B]
      with (A => ZIO[R, E2, B]) {
    def apply(a: A): ZIO[R, E2, B] = success(a)
  }

  case class Provide[R, E, A](zio: ZIO[R, E, A], environment: R) extends ZIO[Any, E, A]

  case class Access[R, E, A](f: R => ZIO[R, E, A]) extends ZIO[R, E, A]

  private val defaultExecutor = ExecutionContext.global

}

object Example extends ZIOApp {

  import ZIO._

  val intStringZIO = for {
    int <- ZIO.succeed(42)
    str <- ZIO.succeed("The answer is:")

  } yield (int, str)

  def run = intStringZIO

}

sealed trait Cause[+E]

object Cause {

  // Expected errors
  // Errors you would potentially want to recover from
  final case class Fail[+E](error: E) extends Cause[E]

  // Unexpected errors
  // Errors you can't recover from in a sensible way (because you didn't expect them to happen)
  final case class Die(throwable: Throwable) extends Cause[Nothing]

  // We are being interrupted and need to execute our finalizers and then immediately terminate
  case object Interrupt extends Cause[Nothing]

}

sealed trait Exit[+E, +A]

object Exit {

  final case class Success[+A](a: A)              extends Exit[Nothing, A]
  final case class Failure[+E](failure: Cause[E]) extends Exit[E, Nothing]

  def succeed[A](value: A): Exit[Nothing, A]            = Success(value)
  def fail[E](error: E): Exit[E, Nothing]               = Failure(Cause.Fail(error))
  def die(throwable: Throwable): Exit[Nothing, Nothing] = Failure(Cause.Die(throwable))

}

sealed trait InterruptStatus { self =>

  def toBoolean: Boolean =
    self match {
      case InterruptStatus.Interruptible   => true
      case InterruptStatus.Uninterruptible => false
    }

}

object InterruptStatus {

  case object Interruptible   extends InterruptStatus
  case object Uninterruptible extends InterruptStatus

}

trait ZIOApp {

  def run: ZIO[Any, Any, Any]

  def main(args: Array[String]): Unit = {
    val result = run.unsafeRunSync
    println(s"🤡${scala.Console.RED}TEE HEE HEE!${scala.Console.RESET}👉 $result")
  }

}
