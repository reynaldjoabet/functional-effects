sealed trait Trampoline[+A] { self =>
	def flatMap[B](f: A => Trampoline[B]): Trampoline[B] = {
		Trampoline.FlatMap(self, f)
	}

	def map[B](f: A => B): Trampoline[B] = {
		flatMap(a => Trampoline.Done(f(a)))
	}
}

object Trampoline {
	final case class Done[A](value: A) extends Trampoline[A]
	final case class Suspend[A](thunk: () => Trampoline[A]) extends Trampoline[A]
	final case class FlatMap[A, B](src: Trampoline[A], f: A => Trampoline[B])
			extends Trampoline[B]

	def done[A](a: A): Trampoline[A] = Done(a)

	// Key: `a` is by-name, so building the next step is deferred
	def suspend[A](a: => Trampoline[A]): Trampoline[A] = Suspend(() => a)

	// Trampolined interpreter: no JVM recursion
	def run[A](t0: Trampoline[A]): A = {
		var cur: Trampoline[Any] = t0.asInstanceOf[Trampoline[Any]]
		var conts: List[Any => Trampoline[Any]] = Nil

		while (true) {
			cur match {
				case Done(v) =>
					conts match {
						case k :: ks =>
							conts = ks
							cur = k(v)
						case Nil =>
							return v.asInstanceOf[A]
					}

				case Suspend(thunk) =>
					cur = thunk().asInstanceOf[Trampoline[Any]]

				case FlatMap(src, f) =>
					conts = f.asInstanceOf[Any => Trampoline[Any]] :: conts
					cur = src.asInstanceOf[Trampoline[Any]]
			}
		}

		throw new IllegalStateException("unreachable")
	}
}

object TrampolineExample {
	def factorial(n: Int): Trampoline[BigInt] = {
		if (n <= 1) Trampoline.done(BigInt(1))
		else Trampoline.suspend(factorial(n - 1).map(_ * n))
	}

	def main(args: Array[String]): Unit = {
		println(Trampoline.run(factorial(10000))) // works without stack overflow
	}
}

object TrampolineExample2 {
	def sum(n: Long): Trampoline[Long] = {
		if (n <= 0) Trampoline.done(0L)
		else Trampoline.suspend(sum(n - 1).map(_ + n))
	}

	def main(args: Array[String]): Unit = {
		println(Trampoline.run(sum(1000000L))) // works without stack overflow
	}
}