package IO3

import java.util.{Timer, TimerTask}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import IO._
import IO.Yield

// Your original IO data types and combinators
sealed trait IO[+A] {
  self =>

  import IO._

  def as[B](value: => B): IO[B]                             = map(_ => value)
  def map[B](f: A => B): IO[B]                              = flatMap(a => pure(f(a)))
  def flatMap[B](f: A => IO[B]): IO[B]                      = FlatMap(this, f)
  def flatten[B](implicit ev: A <:< IO[B]): IO[B]           = flatMap(a => a)
  def handleErrorWith[B >: A](t: Throwable => IO[B]): IO[B] = HandleErrorWith(self, t)
  def fork: IO[Fiber[A]]                                    = IO.Fork(self)
  def unit: IO[Unit]                                        = map(_ => ())
  def keepRight[B](that: IO[B]): IO[B]                      = self.flatMap(_ => that)
  def *>[B](that: IO[B]): IO[B]                             = keepRight(that)
  def keepLeft[B](that: IO[B]): IO[A]                       = flatMap(that.as(_))
  def <*[B](that: IO[B]): IO[A]                             = keepLeft(that)
  def zip[B](that: IO[B]): IO[(A, B)]                       = self.flatMap(a => that.map(b => (a, b)))
  def forever: IO[A]                                        = self.flatMap(_ => forever)

}

object IO {

  def apply[A](a: => A): IO[A]                      = delay(a)
  def unit: IO[Unit]                                = pure(())
  def none[A]: IO[Option[A]]                        = pure(None)
  def pure[A](a: => A): IO[A]                       = Pure(a)
  def succeed[A](a: => A): IO[A]                    = delay(a)
  def suspend[A](t: => IO[A]): IO[A]                = Suspend(() => t)
  def delay[A](t: => A): IO[A]                      = Delay(() => t)
  def raiseError(throwable: Throwable): IO[Nothing] = RaiseError(throwable)

  def fromEither2[A](either: Either[Throwable, A]): IO[A] = either match {
    case Left(value)  => raiseError(value)
    case Right(value) => pure(value)
  }

  def foreach[A, B](xs: Iterable[A])(f: A => IO[B]): IO[Iterable[B]] =
    xs.foldLeft(IO.succeed(Vector.empty[B]))((acc, curr) =>
      for {
        soFar <- acc
        x     <- f(curr)
      } yield soFar :+ x
    )

  def yield5: IO[Unit] = IO.Yield

  def foreachPar[A, B](xs: Iterable[A])(f: A => IO[B]): IO[Iterable[B]] =
    foreach(xs)(x => f(x).fork).flatMap(fibers => foreach(fibers)(_.join))

  def fromEither[A](either: => Either[Throwable, A]): IO[A] = either.fold(raiseError(_), pure(_))
  def fromTry[A](t: => Try[A]): IO[A]                       = t.fold(raiseError(_), pure(_))

  def fromOption[A](opt: => Option[A])(exception: => Throwable): IO[A] = opt match {
    case Some(value) => pure(value)
    case None        => raiseError(exception)
  }

  def async[A](f: (Try[A] => Unit) => Unit): IO[A] = Async(f)
  def never: IO[Nothing]                           = IO.async(_ => ())

  // CORRECTED fromFuture implementation
  def fromFuture[A](future: => IO[Future[A]])(implicit ec: ExecutionContext): IO[A] =
    future.flatMap { fut =>
      IO.async { cb =>
        fut.onComplete {
          case Failure(exception) => cb(Failure(exception))
          case Success(value)     => cb(Success(value))
        }
      }
    }

  def fromCompletableFuture[A](f: => CompletableFuture[A]): IO[A] = {
    IO.async { callback =>
      f.whenComplete { (result: A, error: Throwable) =>
        if (error == null) callback(Success(result)) else callback(Failure(error))
      }
    }
  }

  final case class Pure[+A](a: A)                                          extends IO[A]
  final case class Delay[+A](t: () => A)                                   extends IO[A]
  final case class FlatMap[A, +B](io: IO[A], f: A => IO[B])                extends IO[B]
  final case class Suspend[+A](t: () => IO[A])                             extends IO[A]
  final case class RaiseError(e: Throwable)                                extends IO[Nothing]
  final case class Async[+A](f: (Try[A] => Unit) => Unit)                  extends IO[A]
  final case class Join[+A](fi: Fiber[A])                                  extends IO[A]
  final case class Fork[+A](tio: IO[A])                                    extends IO[Fiber[A]]
  final case class HandleErrorWith[+A](self: IO[A], f: Throwable => IO[A]) extends IO[A]
  case object Yield                                                        extends IO[Unit]

}

abstract class Fiber[+A] { self =>
  def join: IO[A] = IO.Join(self)
}

// Your IOApp trait
trait IOApp {

  def run: IO[Any]
  final def main(args: Array[String]): Unit = Runtime.run(run).get

}

// Your new Clock object, integrated into the codebase
object Clock {

  // A single, daemon Timer that will not prevent the JVM from exiting
  private val timer = new Timer("IO-Timer", true)

  /**
    * Creates an IO that, when executed, will sleep for the specified duration. This is a
    * non-blocking sleep that uses IO.async and a java.util.Timer. The thread running the IO is free
    * to do other work while the timer is active.
    */
  def sleep(duration: Duration): IO[Unit] = {
    IO.async { onComplete =>
      timer.schedule(
        new TimerTask {
          override def run(): Unit = onComplete(Success(()))
        },
        duration.toMillis
      )
    }
  }

}

object Runtime {

  private def executor(a: => Unit): Unit = {
    Executors
      .newWorkStealingPool
      .submit(
        new Runnable {
          override def run(): Unit = a
        }
      )
  }

  def run[A](t: IO[A]): Try[A] = unsafeRunToFuture(t).value.get

  def runAsync[A](io: IO[A])(cb: (Try[A] => Unit)): RuntimeFiber[A] = new RuntimeFiber(io)
    .register(cb)
    .start()

  def unsafeRunToFuture[A](io: IO[A]): Future[A] = {
    val promise = Promise[A]()
    runAsync(io)((promise.tryComplete _).asInstanceOf[Try[A] => Unit])
    Await.ready(promise.future, Duration.Inf)
    promise.future
  }

  final class RuntimeFiber[A](io: IO[A]) extends Fiber[A] { self =>

    type Callback[B] = Try[B] => Unit

    private val joined: AtomicReference[Set[Callback[A]]] =
      new AtomicReference[Set[Callback[A]]](Set.empty)

    private val result: AtomicReference[Option[Try[A]]] = new AtomicReference[Option[Try[A]]](None)

    def register(cb: Callback[A]): RuntimeFiber[A] = {
      joined.updateAndGet(_ + cb)
      result.get().foreach(cb)
      self
    }

    def fiberDone(a: Try[A]): Unit = {
      result.set(Some(a))
      joined.get.foreach(cb => cb(a))
    }

    def start(): RuntimeFiber[A] = {
      eval(io)(fiberDone)
      self
    }

    // The key stack-safe interpreter function, now rewritten as a tail-recursive function.
    // This removes the faulty iterative loop and correctly handles all IO types.
    // @scala.annotation.tailrec
    private def eval[B](io: IO[B])(cb: (Try[B]) => Unit): Unit = executor {
      io match {
        case Pure(a) =>
          cb(Success(a))

        case Delay(b) =>
          try
            cb(Success(b()))
          catch {
            case e: Throwable => cb(Failure(e))
          }

        case Suspend(t) =>
          eval(t())(cb)

        case FlatMap(src, f) =>
          // When we see a FlatMap, we recursively call eval on the source IO.
          // We provide a new callback that, when invoked, will apply the continuation
          // function 'f' and then re-evaluate the result.
          eval(src) {
            case Success(value) => eval(f(value))(cb)
            case Failure(e)     => cb(Failure(e))
          }

        // eval(src)(
        //   _.fold(
        //     e => cb(Failure(e)),
        //     v => eval(f(v))(cb)
        //   )
        // )

        case HandleErrorWith(self, f) =>
          // Similar to FlatMap, we evaluate the source IO, but with a new callback.
          // This callback will only apply the continuation 'f' if the source fails.
          eval(self) {
            case Success(value) => cb(Success(value))
            case Failure(e)     => eval(f(e))(cb)
          }

        case RaiseError(e) =>
          cb(Failure(e))

        case Async(register) =>
          // For Async, we hand off the callback and then return.
          // The computation will be resumed later when the callback is invoked.
          register(cb)

        // This is a cooperative yield.
        // We hand off the current continuation (cb)
        // back to the executor to be run as a new task.
        // This allows the current thread to be released and potentially
        // pick up another fiber, effectively yielding control.
        case Yield =>
          // For Yield, we simply yield control back to the executor.
          executor {
            cb(Success(()))
          }
        case Join(fi) =>
          // For Join, we hand off the callback to the target fiber.
          // The computation will resume when the target fiber completes.
          fi.asInstanceOf[RuntimeFiber[B]].register(cb)

        case Fork(tio: IO[Any]) =>
          // For Fork, we start a new fiber and immediately return its handle.
          cb(Success(new RuntimeFiber(tio).start().asInstanceOf[B]))
      }
    }

  }

}

// A simple example application to demonstrate stack safety and asynchronous effects.
object MyApp extends IOApp {

  import scala.concurrent.duration._
  import scala.language.postfixOps

  override def run: IO[Any] = {
    // A synchronous, time-consuming task to simulate a blocking computation.
    val blockingTask = IO.delay {
      println("[BlockingTask] Starting a busy-wait loop.")
      var i     = 0
      val start = System.currentTimeMillis()
      while (System.currentTimeMillis() - start < 2000)
        i += 1
      println(s"[BlockingTask] Finished busy-wait after $i iterations.")
    }

    // An asynchronous task that uses our non-blocking sleep.
    val asyncTask = for {
      _ <- IO.delay(println("[AsyncTask] Starting a 8 second delay..."))
      _ <- Clock.sleep(8 seconds)
      _ <- IO.delay(println("[AsyncTask] Finished the 8 second delay."))
    } yield ()

    // The main program that forks the other two tasks.
    for {
      _ <- IO.delay(println("Main program started."))

      // Fork the two tasks into separate fibers.
      // This is non-blocking and the main program continues immediately.
      fiber1 <- blockingTask.fork
      fiber2 <- asyncTask.fork

      _ <- IO.delay(println("Main program has forked both tasks. Waiting for them to finish..."))

      // We can do other work here while the fibers are running.
      _ <- IO.delay(println("Main program is doing other work."))

      // Now, join both fibers to wait for their results.
      // The main fiber will block here until fiber1 and fiber2 are complete.
      _ <- fiber1.join
      _ <- fiber2.join

      _ <- IO.delay(println("Main program finished. All tasks are complete."))
    } yield ()
  }

}

// add thread id to demonstrate yielding

object YieldExample extends IOApp {

  val yieldIO: IO[Unit] = for {
    _ <- IO.delay(println("Before yield"))
    _ <- IO.delay(println(s"Thread before yield: ${Thread.currentThread().getName}"))
    _ <- IO.yield5
    _ <- IO.delay(println(s"Thread after yield: ${Thread.currentThread().getName}"))
    _ <- IO.delay(println("After yield"))
    _ <- IO.yield5
    _ <- IO.delay(println("After second yield"))
    _ <- IO.delay(println(s"Thread after second yield: ${Thread.currentThread().getName}"))
  } yield ()

  def run = yieldIO

}

object NeverExample extends IOApp {

  val never: IO[Unit] = IO.delay(println("Starting never...")) *> IO.never *> IO
    .delay(println("This will never be printed."))

  def run = never

}

object ForeachExample extends IOApp {

  def run = {
    val numbers = (1 to 1000).toList

    val io: IO[Iterable[Int]] = IO.foreach(numbers) { n =>
      IO.delay {
        println(s"Processing number: $n on thread ${Thread.currentThread().getName}")
        n * 2
      }
    }

    io.map { results =>
      println(s"Results: $results")
    }
  }

}

object ForEachParExampleApp extends IOApp {

  def run = {
    val numbers = (1 to 400).toList

    val io: IO[Iterable[Int]] = IO.foreachPar(numbers) { n =>
      IO.delay {
        println(s"Processing number: $n on thread ${Thread.currentThread().getName}")
        n * 2
      }
    }

    io.map { results =>
      println(s"Results: $results")
    }
  }

}

object FromFutureExample extends IOApp {

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Future

  def run = {
    val futureIO: IO[Future[Int]] = IO.delay {
      Future {
        println(s"Starting future computation on thread ${Thread.currentThread().getName}")
        Thread.sleep(2000) // Simulate long computation
        println(s"Completing future computation on thread ${Thread.currentThread().getName}")
        42
      }
    }

    val io: IO[Int] = IO.fromFuture(futureIO)

    io.map { result =>
      println(s"Result from future: $result")
    }
  }

}

object AsyncExample extends IOApp {

  import java.util.{Timer, TimerTask}

  import scala.concurrent.duration._ // For 1.second
  import scala.util.Success

  // This is the IO.async definition from Clock.sleep, simplified slightly
  // to be a direct IO value rather than a method.
  val simpleAsyncIO = IO.async[String] { onComplete =>
    println(s"[${Thread.currentThread().getName}] Async Task: Scheduling delayed result...")
    val timer = new Timer("SingleAsyncTimer", true) // Use a local timer to clean up
    timer.schedule(
      new TimerTask {
        override def run(): Unit = {
          println(
            s"[${Thread.currentThread().getName}] Async Task: Timer fired! Calling onComplete."
          )
          onComplete(Success("Hello from async!"))
          timer.cancel() // Good practice to clean up the timer after use
        }
      },
      1000 // 1 second delay
    )
  }

  // Our program will be:
  val program = simpleAsyncIO
    .map(s => println(s"[${Thread.currentThread().getName}] Main Program Result: $s"))

  override def run: IO[Any] = program

}

object ForkExample extends IOApp {

  def run = {
    val io = for {
      fiber <- IO.delay {
                 println(s"[${Thread.currentThread().getName}] Forking a new fiber...")
               } *> IO.never.fork
      _ <- IO.delay {
             println(s"[${Thread.currentThread().getName}] Main fiber is doing other work...")
           }
      // We won't join the never-ending fiber, just demonstrate forking
      _ <- IO.delay {
             println(s"[${Thread.currentThread().getName}] Main fiber finished its work.")
           }
    } yield ()

    io
  }

}
