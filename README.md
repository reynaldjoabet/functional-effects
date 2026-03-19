# Functional-Effects
Functional effects are immutable data structures that describe side effects.
# ZIO as a Virtual CPU
ZIO implements a green-thread virtual processor. Each FiberRuntime is a CPU core, and the ZIO sealed trait is its instruction set.

```scala
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Set => JavaSet}
import scala.annotation.tailrec

final class FiberRuntime[E, A](fiberId: FiberId.Runtime, fiberRefs0: FiberRefs, runtimeFlags0: RuntimeFlags)
    extends Fiber.Runtime.Internal[E, A]
    with FiberRunnable {
  self =>
  type Erased = ZIO.Erased

  import FiberRuntime._
  import ZIO._

  private var _lastTrace      = fiberId.location
  private var _fiberRefs      = fiberRefs0
  private var _runtimeFlags   = runtimeFlags0
  private var _blockingOn     = FiberRuntime.notBlockingOn
  private var _asyncContWith  = null.asInstanceOf[AsyncContWith]
  private val running         = new AtomicBoolean(false)
  private val inbox           = new ConcurrentLinkedQueue[FiberMessage]()
  private var _children       = null.asInstanceOf[JavaSet[Fiber.Runtime[_, _]]]
  private var observers       = Nil: List[Exit[E, A] => Unit]
  private var runningExecutor = null.asInstanceOf[Executor]
  private var _stack          = null.asInstanceOf[Array[Continuation]]
  private var _stackSize      = 0
  private var _isInterrupted  = false

  private var _forksSinceYield = 0
    }
```  
- Program Counter (PC) : `var cur: ZIO[_,_,_]` — the current effect being executed in the runLoop	
- Stack Pointer (SP)	      `_stackSize`: Int — index into the continuation stack	
- Call Stack`_stack`: Array[Continuation] — explicit array of return continuations (initial size 16)	
- Status/Flags Register	`_runtimeFlags`: RuntimeFlags — bit-packed flags (interruptibility, wind-down, cooperative yielding, etc.)	
- Register File / Context: 	`_fiberRefs`: FiberRefs — map of FiberRef → value; holds environment, executor, logger, etc.	
- Interrupt Flag: 	`_isInterrupted`: Boolean — whether an interrupt signal has been received	
Return Register:	`_exitValue: Exit[E, A]` (volatile) — null while running, set to final result on completion  
- Instruction Set (the sealed trait ZIO ADT)
Each ZIO case class is like a CPU opcode

The `runLoop` method (starting at FiberRuntime.scala:1085) is a `while(true)` that mirrors the CPU cycle:

```scala
while (true) {
  // 1. CHECK INTERRUPTS — drain inbox (like polling interrupt line)
  cur = drainQueueWhileRunning(cur)

  // 2. TIMESLICE CHECK — cooperative yield after 10,240 ops
  ops += 1
  if (ops > MaxOperationsBeforeYield) { inbox.add(Resume(cur)); return }

  // 3. FETCH-DECODE-EXECUTE — pattern match on `cur`
  cur match {
    case FlatMap(first, k) => push(k); cur = first    // push return addr, jump
    case Sync(eval)        => value = eval(); pop & apply continuations
    case Async(register)   => register(callback); return  // suspend (I/O wait)
    case Failure(cause)    => unwind stack to FoldZIO     // exception unwinding
    case YieldNow          => re-enqueue; return           // voluntary preempt
    ...
  }
}
```

## Hardware Constants

| CPU Concept                  | ZIO Constant               | Value        |
| ---------------------------- | -------------------------- | ------------ |
| Timeslice / quantum          | MaxOperationsBeforeYield   | 2,048 ops    |
| Max stack depth (trampoline) | MaxDepthBeforeTrampoline   | 300 frames   |
| Initial stack allocation     | InitialStackSize           | 16 entries   |
| Stack GC threshold           | StackIdxGcThreshold        | 128 entries  |
| Fork quantum                 | MaxForksBeforeYield        | 128 forks    |

## OS-Level Concepts

| OS Concept               | ZIO Equivalent                                                   |
| ------------------------ | ---------------------------------------------------------------- |
| Process/Thread           | FiberRuntime instance                                            |
| Scheduler                | Executor.submit(fiber) — the thread pool                         |
| Context switch           | drainQueueLaterOnExecutor — re-submit fiber as Runnable          |
| Interrupt controller     | inbox: ConcurrentLinkedQueue[FiberMessage]                       |
| IPC signals              | FiberMessage.InterruptSignal, Resume, Stateful                   |
| Process table (children) | _children: JavaSet[Fiber.Runtime]                                |
| Completion handlers      | observers: List[Exit => Unit]                                    |
| Address space / memory   | ZEnvironment stored via FiberRef.currentEnvironment              |
| Fork                     | Creates new FiberRuntime, copies _fiberRefs via forkAs(childId)  |

## Cats Effect
The `IOFiber` is a virtual processor — a software CPU that fetches `IO` instructions, decodes their `tag` via a `tableswitch`, executes them against a register file of mutable state, and cooperatively time-shares physical threads via `IORuntime`'s work-stealing thread pool

The `runLoop` has a countdown parameter `autoCedeIterations`. Each time through the loop, it decrements:
```scala
// Simplified from IOFiber.runLoop
def runLoop(_cur0: IO[Any], cancelationIterations: Int, autoCedeIterations: Int): Unit = {
  // ... each iteration:
  val autoCede1 = autoCedeIterations - 1

  if (autoCede1 == 0) {
    // Time's up — yield the thread
    resumeTag = AutoCedeR       // save HOW to resume
    resumeIO  = _cur0           // save WHAT we were about to execute (the "PC")
    rescheduleFiber(currentCtx) // put ourselves at the BACK of the work queue
    return                      // exit the run-loop, freeing the OS thread
  }

  // ... otherwise, keep executing the current IO node
}
```

When another worker thread picks this fiber back up, `run()` sees `resumeTag == AutoCedeR,` restores `resumeIO`, and re-enters `runLoop` exactly where it left off

With `autoYieldThreshold = 1024 `(the default):
```sh

Thread-3 picks up Fiber A (first time):
  autoCedeIterations = 1024
  iteration 1:    1024 → 1023
  iteration 2:    1023 → 1022
  ...
  iteration 1024: 1 → 0  ⏸ auto-cede! Save state, reschedule.

Thread-7 picks up Fiber A (resumed):
  autoCedeIterations = 1024  ← RESET to full value
  iteration 1025: 1024 → 1023
  iteration 1026: 1023 → 1022
  ...
  iteration 2048: 1 → 0  ⏸ auto-cede again!
```    
When the fiber is rescheduled after an auto-cede, it re-enters the `run-loop `with a fresh `autoCedeIterations` value:
```scala
// resumeTag == AutoCedeR
case AutoCedeR =>
  val io = resumeIO
  resumeIO = null
  runLoop(io, cancelationCheckThreshold, autoYieldThreshold)
  //                                     ^^^^^^^^^^^^^^^^^^
  //                                    reset to full 1024
  ```

  ## Tail Call Optimization (TCO)
  Tail Call Optimization (TCO) is a technique used by compilers and interpreters to optimize **any** function call that occurs in the tail position (the last operation in a function) — not just recursive calls. Instead of creating a new stack frame for the callee, TCO allows the current function's stack frame to be reused, effectively turning the call into a jump. Languages like Scheme guarantee full TCO for all tail calls, including calls to *different* functions (mutual recursion, continuation-passing style, etc.).

  Scala's `@tailrec` is more limited: it only performs **Tail Recursion Optimization (TRO)** — the function must call *itself* in tail position. The JVM does not support general TCO, so Scala cannot optimize tail calls to different methods. For mutual recursion on the JVM, you need a trampoline. Despite this limitation, TRO is still very useful for functions that operate on large data sets or require deep recursion, as it prevents stack overflow by reusing the frame.

  ```scala
  //If the recursive call is the last thing the function does, the compiler can reuse the frame:
  @tailrec
def factorial(n: Int, acc: Int = 1): Int =
  if (n <= 1) acc
  else factorial(n - 1, n * acc)  // tail position — nothing to do after return
 ```   
```sh
factorial:
    cmp  edi, 1
    jle  .done
    imul esi, edi       ; acc = acc * n
    dec  edi             ; n = n - 1
    jmp  factorial       ; ← NOT call, just jmp (reuses same frame)
.done:
    mov  eax, esi
    ret
```

Many recursive patterns are NOT in tail position:
```scala
def tree(node: Node): Int =  if (node == null) 0  else tree(node.left) + tree(node.right)  // two calls, neither is tail
```
Or mutual recursion:
```scala
def isEven(n: Int): Boolean =
  if (n == 0) true else isOdd(n - 1)

def isOdd(n: Int): Boolean =
  if (n == 0) false else isEven(n - 1)
```
The JVM doesn't optimize tail calls across different methods. `@tailrec` won't compile here because the recursive call is not in the same method. You'd have to rewrite it to be tail-recursive within a single method, or use a trampoline to achieve TCO for mutual recursion.


## The Trampoline
### Core idea
Don't actually call the recursive function. Instead, return a description of what to call next. A loop (the "trampoline") keeps executing these descriptions until done

```sh
Normal recursion:         Trampoline:

f calls g                 f RETURNS "please call g(args)"
  g calls h               loop sees this, calls g(args)
    h calls f              g RETURNS "please call h(args)"
      ...                  loop sees this, calls h(args)
      (stack grows)        h RETURNS "please call f(args)"
                           loop sees this, calls f(args)
                           ...
                           (stack depth: always 1)
```

The "trampoline" name: the function "bounces" back to the loop each time, like bouncing on a trampoline, instead of diving deeper.

```scala
sealed trait Bounce[A]
case class Done[A](result: A)              extends Bounce[A]  // finished
case class Call[A](thunk: () => Bounce[A])  extends Bounce[A]  // "call this next"
//thunk is a function that takes nothing and returns a Bounce[A]
//A thunk is a function that wraps a computation so it doesn't execute yet:
// The Trampoline loop
@tailrec
def trampoline[A](bounce: Bounce[A]): A = bounce match {
  case Done(result) => result              // unwrap and return
  case Call(thunk)  => trampoline(thunk()) // call thunk, loop (tail position!)
}
```
This is itself tail-recursive → compiles to a `while` loop. Constant stack.

```scala
def isEven(n: Int): Bounce[Boolean] =
  if (n == 0) Done(true)
  else Call(() => isOdd(n - 1))      // don't call isOdd, return description

def isOdd(n: Int): Bounce[Boolean] =
  if (n == 0) Done(false)
  else Call(() => isEven(n - 1))     // don't call isEven, return description

trampoline(isEven(1000000)) // true, no stack overflow
```

```sh
trampoline(isEven(1000000))
  isEven(1000000) → Call(() => isOdd(999999))     return to loop
  
trampoline(Call(() => isOdd(999999)))
  thunk() → isOdd(999999) → Call(() => isEven(999998))   return to loop

trampoline(Call(() => isEven(999998)))
  thunk() → isEven(999998) → Call(() => isOdd(999997))   return to loop

... 999,997 more bounces ...

trampoline(Call(() => isEven(0)))
  thunk() → isEven(0) → Done(true)               return to loop

trampoline(Done(true))
  → true                                          finished
```  
Stack depth at every point: 2 frames (trampoline + the current function). Never deeper.

With trampoline (constant stack)
```sh
; trampoline loop (compiled from @tailrec)
trampoline:
.loop:
    ; bounce object in rdi
    mov  eax, [rdi + TYPE_TAG]    ; check: Done or Call?
    cmp  eax, DONE_TAG
    je   .finished

    ; It's a Call — extract the thunk (closure)
    mov  rdi, [rdi + THUNK_PTR]   ; load function pointer from closure
    call [rdi + CLOSURE_APPLY]    ; call thunk() → returns new Bounce
                                  ;   (stack: just this frame + thunk)
    ; thunk returns, its frame is GONE
    ; rax = new Bounce object
    mov  rdi, rax
    jmp  .loop                    ; ← back to top, same frame

.finished:
    mov  rax, [rdi + RESULT_PTR]  ; extract result from Done
    ret
```    
```sh
Stack during execution:

│ trampoline │ ← always here
│ isEven     │ ← called by trampoline, returns immediately
└────────────┘

│ trampoline │ ← same frame reused
│ isOdd      │ ← called by trampoline, returns immediately  
└────────────┘

(alternates, but never more than 2 frames deep)
```
The key trick in one sentence
`Each recursive function returns to the trampoline instead of calling the next function directly. The trampoline then makes the next call from the same stack depth.`

The trampoline trades stack space for heap allocations. The stack is fixed-size and will overflow; the heap is gigabytes and managed by GC. That's the fundamental win.

```sh
┌──────────────────────────────────────────────────┐
│                    MEMORY                        │
│                                                  │
│  ┌─────────────┐          ┌─────────────────┐    │
│  │   STACK     │          │      HEAP       │    │
│  │             │          │                 │    │
│  │ Fixed size  │          │ Huge (GBs)      │    │
│  │ (~1-8 MB)   │          │                 │    │
│  │             │          │ Managed by GC   │    │
│  │ Auto cleanup│          │ (or malloc/free)│    │
│  │ (pop frame) │          │                 │    │
│  │             │          │Objects, closures│    │
│  │ Fast (push/ │          │ arrays live here│    │
│  │  pop only)  │          │                 │    │
│  │             │          │Slower (allocator│    │
│  │ OVERFLOWS   │          │ must find space)│    │
│  │ if too deep │          │                 │    │
│  └─────────────┘          │Never "overflows"│    │
│                           │ (practically)   │    │
│                           └─────────────────┘    │
└──────────────────────────────────────────────────┘
```
Normal recursion uses the STACK
Each call pushes a frame:
```sh
isEven(4) calls isOdd(3) calls isEven(2) calls isOdd(1) calls isEven(0)

STACK (grows downward, fixed ~1MB limit):
┌────────────────────┐
│ isEven(4) frame    │  ← 64 bytes
├────────────────────┤
│ isOdd(3) frame     │  ← 64 bytes
├────────────────────┤
│ isEven(2) frame    │  ← 64 bytes
├────────────────────┤
│ isOdd(1) frame     │  ← 64 bytes
├────────────────────┤
│ isEven(0) frame    │  ← 64 bytes
├────────────────────┤
│                    │
│  (free stack)      │
└────────────────────┘

All 5 frames exist AT THE SAME TIME.
At n=1,000,000 → 1,000,000 frames → ~64MB → OVERFLOW (limit is ~1MB)
```
The frames must all exist simultaneously because each caller is waiting for its callee to return.

Trampoline uses the HEAP instead
Each bounce allocates a small object on the heap, but the previous frame is already gone:
```sh
Bounce 1: isEven(4) runs, RETURNS Call(() => isOdd(3))
           isEven's stack frame is freed
           Call object lives on heap

Bounce 2: trampoline calls isOdd(3), RETURNS Call(() => isEven(2))
           isOdd's stack frame is freed
           New Call object on heap, old one is garbage

Bounce 3: trampoline calls isEven(2), RETURNS Call(() => isOdd(1))
           ...same...
```
```sh
STACK at any moment:           HEAP over time:
┌────────────────────┐         
│ trampoline frame   │         Bounce 1: Call(() => isOdd(3))    → GC can collect
│ current func frame │         Bounce 2: Call(() => isEven(2))   → GC can collect
├────────────────────┤         Bounce 3: Call(() => isOdd(1))    → GC can collect
│                    │         Bounce 4: Call(() => isEven(0))   → GC can collect
│  (free stack)      │         Bounce 5: Done(true)              → result
│                    │
│                    │         Each object: ~32 bytes
│                    │         Total heap used: 1,000,000 × 32 bytes = ~32MB
│                    │         But GC reclaims old ones continuously
└────────────────────┘         Peak heap in practice: much less

Stack depth: ALWAYS 2 frames (~128 bytes). Never overflows.
```
The trade-off, visually
```sh
                Normal recursion              Trampoline
                ────────────────              ──────────

Stack usage:    ████████████████████████      ██
                (grows with N)                (constant: 2 frames)

Heap usage:     (none)                        ████████████████████████
                                              (grows with N, but GC helps)

Failure mode:   StackOverflowError            OutOfMemoryError
                at ~15,000 calls              at ~billions of objects
                (hard crash, 1MB limit)       (practically never)
```  
Why is this a good trade?
```sh
Stack:  Small (1MB), fixed, crashes hard.     ← SCARCE resource
Heap:   Huge (GBs), expandable, GC'd.         ← ABUNDANT resource
```
## how is a while loop constant stack?
A `while` loop is constant stack because it does not involve any function calls that would add new frames to the call stack. Instead, it uses a single frame for the entire duration of the loop. The loop's body is executed repeatedly, but since it does not call itself or any other function recursively, the stack depth remains constant regardless of how many iterations the loop performs. This is in contrast to recursive functions, which can lead to stack overflow if they exceed the maximum stack depth due to too many nested calls. In a `while` loop, the control flow is managed through iteration rather than recursion, allowing it to run indefinitely without consuming additional stack space.

When function A calls function B, A's frame stays on the stack waiting:

```sh
A calls B:

Stack BEFORE call:        Stack DURING B:          Stack AFTER B returns:
┌──────┐                  ┌──────┐                 ┌──────┐
│  A   │                  │  A   │ ← still here!   │  A   │ ← resumes
└──────┘                  │  B   │ ← on top        └──────┘
                          └──────┘
```
`A`cannot be removed while `B` is running, because `A` has unfinished business — it needs to do something with `B`'s result.

But if `A` returns first, its frame is gone:   
```sh
A returns:
Stack BEFORE return:      Stack AFTER return:
┌──────┐                  ┌──────┐
│  A   │                  │caller│ ← A is gone
└──────┘                  └──────┘
```
That's the entire trick. If `A` returns instead of calling `B`, then `A`'s frame is freed. The caller can then call `B` from that same depth.

```sh
isEven:
    cmp  edi, 0
    je   .done
    dec  edi
    call isOdd        ; ← PUSH return address onto stack
                      ;    jump to isOdd
                      ;    isEven's frame CANNOT be freed
                      ;    because we pushed a return address
                      ;    that says "come back here"
    ret               ; ← only reached AFTER isOdd returns

; The "call" instruction literally does:
;    push RIP          (save where to come back)
;    jmp  isOdd        (go to isOdd)
;
; isEven's frame is TRAPPED under isOdd's frame
```
each function's frame must stay alive because the CPU pushed a return address. The function hasn't finished — it's blocked waiting for the callee

With trampoline: `isEven` RETURNS a description, trampoline CALLS `isOdd`

```scala
def isEven(n: Int): Bounce[Boolean] =
  if (n == 0) Done(true)
  else Call(() => isOdd(n - 1))    // RETURN this object. Don't call isOdd.
```

```sh
Step 1: trampoline calls isEven(4)

Stack:
┌──────────────┐
│ trampoline   │
│ isEven(4)    │ ← running
└──────────────┘

isEven(4) executes: "n is not 0, so..."
  It does NOT call isOdd.
  It creates an object: Call(() => isOdd(3))
  It RETURNS this object.
```  
```sh
Step 2: isEven(4) has RETURNED

Stack:
┌──────────────┐        Heap:
│ trampoline   │ ← has the Call object     [ Call(() => isOdd(3)) ]
└──────────────┘
                         ↑
                    isEven's frame is GONE.
                    Completely freed. Popped off the stack.
```                    
```sh
Step 3: trampoline sees Call, extracts thunk, CALLS isOdd(3)

Stack:
┌──────────────┐
│ trampoline   │ ← same position as before
│ isOdd(3)     │ ← new call, same depth as isEven was
└──────────────┘

isOdd(3) executes: "n is not 0, so..."
  Creates: Call(() => isEven(2))
  RETURNS it.

Step 4: isOdd(3) has RETURNED

Stack:
┌──────────────┐        Heap:
│ trampoline   │        [ Call(() => isEven(2)) ]
└──────────────┘
                    isOdd's frame is GONE.  

Step 5: trampoline calls isEven(2), same depth again

Stack:
┌──────────────┐
│ trampoline   │
│ isEven(2)    │ ← same stack slot that isEven(4) and isOdd(3) used
└──────────────┘                    
```  
The stack NEVER goes beyond depth 2. Each function returns, freeing its frame, before the next one is called.

```sh

trampoline:
.loop:
    ; rdi = current Bounce object (on HEAP)
    mov  eax, [rdi + TYPE]
    cmp  eax, DONE
    je   .finished

    ; It's a Call — extract thunk
    mov  rdi, [rdi + THUNK]
    call [rdi + APPLY]          ; call the thunk
                                ; thunk runs isEven or isOdd
                                ; thunk RETURNS a new Bounce object
                                ; thunk's frame is POPPED right here
                                ; ↓
    mov  rdi, rax               ; rax = new Bounce (return value)
    jmp  .loop                  ; go back to top — SAME stack depth

.finished:
    mov  rax, [rdi + RESULT]
    ret
```    
The critical moment:
```sh
    call [rdi + APPLY]    ; push trampoline's return addr, jump to isEven
                          ;
                          ; inside isEven:
                          ;   allocate Call object on HEAP
                          ;   put it in rax
                          ;   ret              ← POP return addr, back to trampoline
                          ;                      isEven's frame is NOW GONE
                          ;
    mov  rdi, rax         ; ← we're back here, stack is exactly where it was
    jmp  .loop            ; call the NEXT function from the SAME depth
```    
A function frame is freed the moment it returns. The trampoline forces every recursive function to return immediately, so its frame is freed before the next function is called — meaning the next call reuses the same stack space.

`ret` destroys the frame
When a function executes `ret`, two things happen:
```sh
ret   ; 1. Pop return address from stack
      ; 2. Jump to that address
      ; 
      ; The frame (local variables, saved registers) that was
      ; on the stack? It's ABANDONED. The stack pointer moved past it.
      ; That memory is now "free" — the next call will overwrite it.
```      
 In normal recursion, `isEven` needs to keep its data because it has more work to do after `isOdd` returns. But in the trampoline version, `isEven` has no more work to do. All it does is:
 ```scala
 def isEven(n: Int): Bounce[Boolean] =
  if (n == 0) Done(true)
  else Call(() => isOdd(n - 1))   // build object, return it, DONE
  ```
  ```sh
  Normal recursion — isEven HAS work after the call:

  def isEven(n: Int): Boolean =
    if (n == 0) true
    else isOdd(n - 1)     // even here, the frame must stay because
                           // the CALL instruction pushed a return address
                           // into isEven's frame

Trampoline — isEven has NO work after building the object:

  def isEven(n: Int): Bounce[Boolean] =
    Call(() => isOdd(n - 1))    // 1. allocate Call object on HEAP
                                 // 2. return it
                                 // 3. isEven is FINISHED
                                 //    ret executes
                                 //    frame is gone
``` 
But wait — the closure captures `n-1`. Where does that live?
On the heap, inside the closure object:        

```sh
Stack (temporary):                Heap (survives):
┌─────────────────┐               
│ isEven(4) frame │               ┌──────────────────────  ┐
│   n = 4         │──creates──→   │ Call object            │
│   compute n-1=3 │               │   thunk: () => {       │
│                 │               │     isOdd(3) ← 3 is    │
└─────────────────┘               │   }            stored  │
    ↑                             │            HERE        │
    frame GONE after ret          └──────────────────────  ┘
                                       ↑
                                  this survives on the heap
                                  trampoline has a reference to it
```                                                         
```sh
1. isEven(4) is called
   Stack: [trampoline] [isEven: n=4]

2. isEven computes n-1 = 3

3. isEven allocates on HEAP: Call(() => isOdd(3))
   The number 3 is COPIED into the closure object on the heap.
   Stack: [trampoline] [isEven: n=4]
   Heap:  [Call object, captures 3]

4. isEven RETURNS the Call object (address in rax register)
   ret executes. Stack pointer pops isEven's frame.
   
   Stack: [trampoline]              ← isEven GONE
   Heap:  [Call object, captures 3] ← data SURVIVES here

5. Trampoline receives Call object (from rax)
   Extracts thunk
   Calls thunk() → isOdd(3)
   
   Stack: [trampoline] [isOdd: n=3]  ← same depth as step 1
   Heap:  [Call object, captures 3]   ← can be GC'd now
 ```  

 ```sh
 isEven:
    push rbp
    mov  rbp, rsp               ; prologue — frame established
    
    cmp  edi, 0                 ; if n == 0
    je   .done
    
    ; --- allocate Call object on heap ---
    sub  edi, 1                 ; compute n-1 (= 3)
    
    mov  rdi, 24                ; size of Call object
    call malloc                 ; rax = pointer to heap memory
    
    mov  [rax + TYPE], CALL_TAG ; mark as Call
    mov  [rax + CAPTURED_N], 3  ; store n-1 IN THE HEAP OBJECT
    mov  [rax + FUNC_PTR], isOdd; store which function to call
    
    ; rax = pointer to Call object (return value)
    
    pop  rbp                    ; epilogue — FRAME DESTROYED
    ret                         ; ← jump back to trampoline
                                ;    stack pointer moves up
                                ;    isEven's memory is GONE
                                ;    but the Call object is on the HEAP
                                ;    and its address is in rax
                                ;    so trampoline can use it
``` 
Then in the trampoline:
```sh
trampoline:
.loop:
    ; rdi = Call object (on heap, survives across calls)
    call [rdi + FUNC_PTR]       ; call isEven or isOdd
                                ; it returns QUICKLY
                                ; its frame is popped
    mov  rdi, rax               ; ← right here, the callee's frame
                                ;    is ALREADY GONE
                                ;    but rax holds a pointer to
                                ;    a NEW heap object
    jmp  .loop                  ; loop — same stack depth
```    

```sh
        ↑ isEven(4)              ↑ isOdd(3)              ↑ isEven(2)
        │ runs                   │ runs                   │ runs
        │ returns                │ returns                │ returns
        │                        │                        │
════════╧════════════════════════╧════════════════════════╧════════  ← trampoline
        trampoline               trampoline               trampoline
        loop                     loop                     loop
```
Each function bounces off the trampoline loop:
- Goes up (function is called)
- Comes back down (function returns to the trampoline)
- Goes up again (trampoline calls the next function)
- Comes back down again        

```scala
// This executes IMMEDIATELY:
val result = isOdd(3)           // isOdd runs RIGHT NOW

// This does NOT execute yet — it's wrapped in a thunk:
val thunk = () => isOdd(3)      // isOdd does NOT run
                                // thunk is just a "plan to call isOdd(3)"

// Later, you execute it:
thunk()                         // NOW isOdd(3) runs
```
A thunk is delayed computation — a function that wraps a computation so it doesn't execute until you call it. In the trampoline, thunks are used to represent the next step of the computation without actually performing it immediately. This allows the trampoline to manage control flow and stack usage effectively, enabling tail call optimization and preventing stack overflow in recursive functions.

```scala
// When isEven runs:
def isEven(n: Int): Bounce[Boolean] =
  if (n == 0) Done(true)
  else Call(() => isOdd(n - 1))
        │    └─────────────────┘
        │     this is the thunk
        │     it says "call isOdd(n-1)"
        │     but it does NOT call it yet
        │
        └── wraps the thunk in a Call object and RETURNS it
```        
```scala
// When trampoline receives it:
def trampoline[A](bounce: Bounce[A]): A = bounce match {
  case Done(result) => result
  case Call(thunk)  => trampoline(thunk())
                                  │     │
                                  │     └─ () invokes the thunk
                                  │        NOW isOdd(n-1) actually runs
                                  │
                                  └─ the thunk itself (still just a function)
}                                  
```  

```sh
Call object on the heap:
┌─────────────────────────────────┐
│ type: Call                      │
│ thunk: ──→ [closure object]     │
│             ┌──────────────┐    │
│             │ code: isOdd  │    │
│             │ captured: 3  │    │
│             └──────────────┘    │
└─────────────────────────────────┘
```
The `thunk` is a closure — a function pointer (`isOdd`) bundled with captured data `(3)`. When you call `thunk()`, it executes `isOdd(3)`. 

```sh
                        RSP before call = 0x7000 (always the same)
                        │
                        ▼
Call isEven:   ┌────────────────┐
(frame = 32B)  │isEven: 32 bytes│
               └────────────────┘
               RSP = 0x6FE0

ret → RSP = 0x7000  ← back to original position

Call isOdd:    ┌──────────────────────────┐
(frame = 64B)  │    isOdd: 64 bytes       │
               └──────────────────────────┘
               RSP = 0x6FC0

ret → RSP = 0x7000  ← back to original position again

Call bigFunc:  ┌──────────────────────────────────────────┐
(frame = 128B) │         bigFunc: 128 bytes               │
               └──────────────────────────────────────────┘
               RSP = 0x6F80

ret → RSP = 0x7000  ← back to original position again
```

```sh
Without trampoline — frames pile up FROM DIFFERENT starting points:

0x7000 ┌──────────────────┐
       │ isEven(4): 32B   │ starts at 0x7000
0x6FE0 ├──────────────────┤
       │ isOdd(3): 64B    │ starts at 0x6FE0  ← different!
0x6FA0 ├──────────────────┤
       │ isEven(2): 32B   │ starts at 0x6FA0  ← different!
0x6F80 ├──────────────────┤
       │ isOdd(1): 64B    │ starts at 0x6F80  ← different!
0x6F40 └──────────────────┘
       
Each new call starts where the previous one ENDED.
They keep accumulating. None are freed.


With trampoline — every call starts from the SAME point:

0x7000 ┌──────────────────┐
       │ trampoline: 16B  │ ← always here
0x6FF0 ├──────────────────┤
       │ isEven(4): 32B   │ ← starts at 0x6FF0, returns, GONE
0x6FD0 └──────────────────┘

0x7000 ┌──────────────────┐
       │ trampoline: 16B  │ ← same
0x6FF0 ├──────────────────┤
       │ isOdd(3): 64B    │ ← starts at 0x6FF0 (same!), uses MORE space downward
0x6FB0 └──────────────────┘    but who cares — it returns, GONE

0x7000 ┌──────────────────┐
       │ trampoline: 16B  │ ← same
0x6FF0 ├──────────────────┤
       │ isEven(2): 32B   │ ← starts at 0x6FF0 again
0x6FD0 └──────────────────┘
```
The trampoline ensures that every function call starts from the same stack location (e.g., 0x6FF0). Each function returns before the next one is called, so the stack never grows beyond that point. The previous frame is always freed before the next call, allowing for constant stack usage regardless of recursion depth.

The loop. The loop is the trampoline.

The case classes are just the messages bouncing on it.
```sh
Trampoline = the loop       (the surface you bounce on)Done/Call  = the messages    (the things bouncing)
```
ZIO's runtime and Cats Effect's IOFiber are trampolines 
```scala
// This is stack-safe in ZIO — 10 million flatMaps, no overflow:
def loop(n: Int): ZIO[Any, Nothing, Unit] =
  if (n <= 0) ZIO.unit
  else ZIO.succeed(n).flatMap(i => loop(i - 1))

// Because flatMap doesn't CALL loop — it returns FlatMap(Succeed(n), i => loop(i-1))
// The runtime trampoline executes it one step at a time.
```

```scala
sealed trait Trampoline[A]
case class Done[A](result: A)                        extends Trampoline[A]
case class Call[A](thunk: () => Trampoline[A])        extends Trampoline[A]
```
That's enough for tail recursion (each step returns one next step).

To support non-tail recursion (where a step needs the result of another step before continuing), you add a third case:

```scala
case class FlatMap[A, B](sub: Trampoline[A], f: A => Trampoline[B]) extends Trampoline[B]
```
```scala
// Can't be tail-recursive — needs result of left AND right
def treeSum(node: Node): Trampoline[Int] =
  if (node == null) Done(0)
  else FlatMap(treeSum(node.left), (leftSum: Int) =>
       FlatMap(treeSum(node.right), (rightSum: Int) =>
       Done(leftSum + rightSum)))
```

```scala
def run[A](t: Trampoline[A]): A = {
  var current: Trampoline[Any] = t
  var stack: List[Any => Trampoline[Any]] = Nil  // continuations for FlatMap

  while (true) {
    current match {
      case Done(result) =>
        stack match {
          case Nil       => return result.asInstanceOf[A]  // truly done
          case f :: rest => current = f(result); stack = rest  // resume
        }
      case Call(thunk) =>
        current = thunk()
      case FlatMap(sub, f) =>
        current = sub              // run sub first
        stack = f :: stack         // remember to call f with sub's result
    }
  }
}
```
`flatMap` without a trampoline is just recursion

```scala
trait IO[A] {
  def flatMap[B](f: A => IO[B]): IO[B] = f(this.run())  // run me, pass result to f
  def run(): A
}
```
```sh
Stack:
┌──────────────┐
│ flatMap 1    │ waiting for flatMap 2
│ flatMap 2    │ waiting for flatMap 3
│ flatMap 3    │ waiting for flatMap 4
│ flatMap 4    │ running
└──────────────┘

4 flatMaps = 4 stack frames.  
10,000 flatMaps = 10,000 stack frames = stack overflow.
```
Every `flatMap` is stuck waiting for the one it spawned.
None can return. All frames alive. Stack grows.

The fix: make flatMap a case class (data, not execution)
```scala
// BEFORE — flatMap EXECUTES (blows stack):
def flatMap[B](f: A => IO[B]): IO[B] = f(this.run())

// AFTER — flatMap is DATA (safe):
case class FlatMap[A, B](io: IO[A], f: A => IO[B]) extends IO[B]
```

```scala
IO(1).flatMap(a => IO(a + 1))

// BEFORE: immediately calls IO(1).run(), passes 1 to f, calls result.run()
// AFTER:  returns FlatMap(IO(1), a => IO(a + 1))  — nothing executed
```
```scala
IO(println(10000)).flatMap(_ =>
  IO(println(9999)).flatMap(_ =>
    IO(println(9998)).flatMap(_ =>
      IO.unit
    )
  )
)
```

Becomes a linked data structure on the heap:

```scala
FlatMap(
  IO(println(10000)),
  _ => FlatMap(
    IO(println(9999)),
    _ => FlatMap(
      IO(println(9998)),
      _ => Succeed(())
    )
  )
)
```
No function has been called. No stack frames. Just objects on the heap.

sequential computation requires `flatMap`. Every program is a sequence of steps where each step depends on the result of the previous step

```sh
// 3. Associativity: grouping doesn't matter
io.flatMap(f).flatMap(g)  ==  io.flatMap(a => f(a).flatMap(g))
```
```sh
// Grouped left:
(step1.flatMap(a => step2(a))).flatMap(b => step3(b))

// Grouped right:
step1.flatMap(a => step2(a).flatMap(b => step3(b)))
```
```sh
Problem:   Programs are sequential — each step needs the previous result.

Solution:  flatMap — it sequences effects: "run this, feed result to that."

Problem:   Chaining flatMaps = nested function calls = stack overflow.

Solution:  Make flatMap a case class (data) instead of a function call.
           Interpret with a trampoline loop.

Why safe:  Associativity law guarantees the interpreter can reorder/flatten
           the chain without changing the result.
``` 
```sh
interpret(FlatMap(FlatMap(FlatMap(io, f), g), h))
  → need to interpret FlatMap(FlatMap(io, f), g) first
    → need to interpret FlatMap(io, f) first
      → need to interpret io first
      
3 levels of nesting = 3 interpreter frames on the stack
```

Right-nested doesn't have this problem:

```sh
Right-nested structure:

FlatMap(io, a =>
  FlatMap(f(a), b =>
    FlatMap(g(b), h)))

Interpreter:
  → peel off outer FlatMap: run io, push continuation
  → done with this iteration, back to loop
  
Only 1 level deep per iteration. Always safe.
```

When the interpreter sees FlatMap(FlatMap(...), g), it re-associates it on the fly:
```scala

// Interpreter encounters:case FlatMap(FlatMap(inner, f), g) =>  // Re-associate: turn left-nested into right-nested  current = FlatMap(inner, a => FlatMap(f(a), g))  // Now it's right-nested → safe to interpret in the loop
```

The law is about correctness.
Stack safety is about implementation.

Monad Associativity (flatMap)
The core law that makes FiberRuntime work:
```scala
(m.flatMap(f)).flatMap(g)  ==  m.flatMap(x => f(x).flatMap(g))
```

When you write `a.flatMap(f).flatMap(g)`, the ZIO ADT builds a left-leaning tree:
```scala
FlatMap(FlatMap(a, f), g)
```

The `runLoop` transforms this into a right-associated form by pushing `g` onto `_stack`, then `f` onto `_stack`, then executing `a`. When `a` completes, it pops `f`, runs it, then pops `g`, runs it. This rewriting is only semantically valid because flatMap is associative

```scala
ZIO.succeed(1)
  .flatMap(a => ZIO.succeed(a + 1))   // 1
  .flatMap(b => ZIO.succeed(b + 1))   // 2
  .flatMap(c => ZIO.succeed(c + 1))   // 3
  .flatMap(d => ZIO.succeed(d + 1))   // 4
  .flatMap(e => ZIO.succeed(e + 1))   // 5
  .flatMap(f => ZIO.succeed(f + 1))   // 6
  .flatMap(g => ZIO.succeed(g + 1))   // 7
  .flatMap(h => ZIO.succeed(h + 1))   // 8
  .flatMap(i => ZIO.succeed(i + 1))   // 9
  ```
  This builds a left-leaning tree in memory:

  ```scala
  FlatMap(                                          // 9
  FlatMap(                                        // 8
    FlatMap(                                      // 7
      FlatMap(                                    // 6
        FlatMap(                                  // 5
          FlatMap(                                // 4
            FlatMap(                              // 3
              FlatMap(                            // 2
                FlatMap(Succeed(1), f1),          // 1
              f2),
            f3),
          f4),
        f5),
      f6),
    f7),
  f8),
f9)
```

If you interpreted this naively (recursively), each `flatMap` would call into the next, creating 9 JVM stack frames deep. At scale (thousands of `flatMap`s), you'd stack overflow.

Right-associated (how runLoop executes it)
The `runLoop` transforms it at runtime into this equivalent execution via the stack:

```scala
ZIO.succeed(1).flatMap { a =>
  ZIO.succeed(a + 1).flatMap { b =>
    ZIO.succeed(b + 1).flatMap { c =>
      ZIO.succeed(c + 1).flatMap { d =>
        ZIO.succeed(d + 1).flatMap { e =>
          ZIO.succeed(e + 1).flatMap { f =>
            ZIO.succeed(f + 1).flatMap { g =>
              ZIO.succeed(g + 1).flatMap { h =>
                ZIO.succeed(h + 1).flatMap { i =>
                  ZIO.succeed(i + 1)
                }}}}}}}}}
```  
How `runLoop` does it step by step
Starting with the left-associated tree, the `runLoop`'s while(true) processes it in constant JVM stack depth:              

```sh
Step 1: cur = FlatMap(FlatMap(...FlatMap(Succeed(1), f1)..., f8), f9)
        → push f9 onto _stack[0], cur = FlatMap(..., f8)

Step 2: cur = FlatMap(...FlatMap(Succeed(1), f1)..., f8)
        → push f8 onto _stack[1], cur = FlatMap(..., f7)

Step 3: push f7 → _stack[2]
Step 4: push f6 → _stack[3]
Step 5: push f5 → _stack[4]
Step 6: push f4 → _stack[5]
Step 7: push f3 → _stack[6]
Step 8: push f2 → _stack[7]

Step 9: cur = FlatMap(Succeed(1), f1)
        → push f1 onto _stack[8], cur = Succeed(1)

Step 10: cur = Succeed(1) → value = 1
         pop _stack[8] = f1, cur = f1(1) = Succeed(2)

Step 11: cur = Succeed(2) → value = 2
         pop _stack[7] = f2, cur = f2(2) = Succeed(3)

Step 12: pop f3(3) = Succeed(4)
Step 13: pop f4(4) = Succeed(5)
Step 14: pop f5(5) = Succeed(6)
Step 15: pop f6(6) = Succeed(7)
Step 16: pop f7(7) = Succeed(8)
Step 17: pop f8(8) = Succeed(9)

Step 18: pop _stack[0] = f9, cur = f9(9) = Succeed(10)
         stack empty → return Exit.succeed(10)
```

```sh
((((io1 >> io2) >> io3) >> io4) >> io5) >> io6

IO.FlatMap(
  IO.FlatMap(
    IO.FlatMap(
      ...),
    _ => io5),
  _ => io6)
```
The first thing to execute (`io1`) is buried at the bottom. To even find it, you'd have to recurse 5 levels deep — that's the stack overflow problem. 
when you evaluate the top one though, you'll see the `FlatMap` node at the top, and you have to hold onto the right while you evaluate the left… which is itself a `FlatMap` node, and the process repeats. so you actually end up to holding onto `io2` through `io6` while you keep drilling down into the structure before you finally find `io1`
it's kind of like `foldRight` vs `foldLeft`

```scala
io1 >> (io2 >> (io3 >> (io4 >> (io5 >> io6))))

IO.FlatMap(
  io1,
  _ => 
    IO.FlatMap(
      io2,
      _ =>
        IO.FlatMap(
          ...)))
```

so the top one is left-associated, the bottom is right-associated

when you evaluate the bottom one, you're going to start out by seeing the `FlatMap`, which means you hold onto the function on the right while evaluating `io1`. Once that's evaluated, you can invoke the function on the right, and then the same process repeats with `io2`, so your stack usage is only proportional to how deeply you have to recurse to evaluate `io1` (and if we assume that everything is right-associated, then `io1` must just be a `Pure` or soemthing like it)

The first thing to execute (`io1`) is right there at the top. After running it, the continuation hands you the next `FlatMap `with `io2` at the top, and so on. No recursion needed

What `runLoop` does with the left-associated tree
It converts left → right at runtime using `_stack`:

```sh
cur = FlatMap(FlatMap(FlatMap(FlatMap(FlatMap(io1, _=>io2), _=>io3), _=>io4), _=>io5), _=>io6)

Iteration 1: push (_=>io6) → _stack[0],  cur = FlatMap(..., _=>io5)
Iteration 2: push (_=>io5) → _stack[1],  cur = FlatMap(..., _=>io4)
Iteration 3: push (_=>io4) → _stack[2],  cur = FlatMap(..., _=>io3)
Iteration 4: push (_=>io3) → _stack[3],  cur = FlatMap(io1, _=>io2)
Iteration 5: push (_=>io2) → _stack[4],  cur = io1

          ┌─────────────────┐
          │  cur = io1      │  ← now executing
          ├─────────────────┤
_stack[4] │  _ => io2       │
_stack[3] │  _ => io3       │
_stack[2] │  _ => io4       │
_stack[1] │  _ => io5       │
_stack[0] │  _ => io6       │
          └─────────────────┘

io1 completes → pop _stack[4] → cur = io2
io2 completes → pop _stack[3] → cur = io3
io3 completes → pop _stack[2] → cur = io4
io4 completes → pop _stack[1] → cur = io5
io5 completes → pop _stack[0] → cur = io6
io6 completes → stack empty   → return result
```

The left tree is `O(n)` deep to reach `io1`. The `runLoop` peels it in a flat `while` loop — one iteration per `FlatMap` wrapper — using zero JVM recursion. The explicit `_stack` array replaces what would otherwise be JVM call frames.

Each new `.flatMap()` wraps everything so far as its `first`, producing a left-leaning tree

```scala
final case class FlatMap[R, E, A1, A2](
  trace: Trace,
  first: ZIO[R, E, A1],       // ← this field
  successK: A1 => ZIO[R, E, A2]
)
```
In the left tree, `io1` is buried at depth `N`. A naive recursive interpreter would need `N` stack frames just to find the first instruction. With 10,000 chained effects, that's a stack overflow.

In a right-associated tree, `io1` is always at the top — you can start executing immediately. Each continuation lazily produces the next `FlatMap` on demand, so you never need to dive deep

`io1.flatMap(_ => io2).flatMap(_ => io3)`

```scala
FlatMap(
  first = FlatMap(        // ← everything so far is stuffed into `first`
    first = io1,
    successK = _ => io2
  ),
  successK = _ => io3
)
```

`io1.flatMap(_ => io2).flatMap(_ => io3).flatMap(_ => io4)`

```scala
FlatMap(
  first = FlatMap(                    // everything so far
    first = FlatMap(                  // everything before that
      first = io1,                   // the original
      successK = _ => io2
    ),
    successK = _ => io3
  ),
  successK = _ => io4
)
```
The `first` field keeps growing deeper because each `.flatMap()` takes `this` (the whole chain built so far) and assigns it to the new `FlatMap`'s `first`. That's why `io1` — the thing you actually need to execute `first` at runtime — ends up buried `N` levels deep.

Left-associated: `((((((((io1 >> io2) >> io3) >> io4) >> io5) >> io6) >> io7) >> io8) >> io9)`

```scala
FlatMap(
  first = FlatMap(
    first = FlatMap(
      first = FlatMap(
        first = FlatMap(
          first = FlatMap(
            first = FlatMap(
              first = FlatMap(
                first = io1,
                successK = _ => io2
              ),
              successK = _ => io3
            ),
            successK = _ => io4
          ),
          successK = _ => io5
        ),
        successK = _ => io6
      ),
      successK = _ => io7
    ),
    successK = _ => io8
  ),
  successK = _ => io9
)
```
Right-associated: `io1 >> (io2 >> (io3 >> (io4 >> (io5 >> (io6 >> (io7 >> (io8 >> io9)))))))`

```scala
FlatMap(
  first = io1,
  successK = _ => FlatMap(
    first = io2,
    successK = _ => FlatMap(
      first = io3,
      successK = _ => FlatMap(
        first = io4,
        successK = _ => FlatMap(
          first = io5,
          successK = _ => FlatMap(
            first = io6,
            successK = _ => FlatMap(
              first = io7,
              successK = _ => FlatMap(
                first = io8,
                successK = _ => io9
              )
            )
          )
        )
      )
    )
  )
)
```

A right-associated chain has one rule: `first` is always a leaf (a concrete effect), never a `FlatMap`.

```scala
FlatMap(
  first = io1,                    // ← leaf, not a FlatMap
  successK = _ => FlatMap(
    first = io2,                  // ← leaf
    successK = _ => FlatMap(
      first = io3,                // ← leaf
      successK = _ => io4         // ← leaf
    )
  )
)
```
`m.flatMap(f).flatMap(g)  ==  m.flatMap(x => f(x).flatMap(g))`

```scala
LEFT                                RIGHT

FlatMap(                            FlatMap(
  first = FlatMap(                    first = m,
    first = m,                        successK = x =>
    successK = f                        FlatMap(
  ),                                      first = f(x),
  successK = g                            successK = g
)                                       )
                                    )
```                                    
```sh
# left associated flatMaps
m.flatMap(f).flatMap(g).flatMap(h).flatMap(k)

# right associated flatMaps
m.flatMap(x => f(x).flatMap(y => g(y).flatMap(z => h(z).flatMap(k))))
```
In right-associated form:
```scala
FlatMap(
  first = m,                          ← just one leaf
  successK = THE ENTIRE REST          ← everything else lives here
)
```
The `successK` is a function that, when called, returns the entire remaining program. And that remaining program is itself a `FlatMap` whose `successK` contains everything after that, and so on:

```scala
FlatMap(m, _ => ← everything else
  FlatMap(io2, _ => ← everything after io2
    FlatMap(io3, _ => ← everything after io3
      FlatMap(io4, _ => ← everything after io4
        io5))))
```   
Each `successK` is a closure that lazily produces the next step. Nothing after `m` exists in memory until `m` completes and `successK` is called. Then nothing after `io2` exists until `io2` completes, and so on.

That's why right-association is efficient: you only ever have one `FlatMap` in hand at a time, and the rest materializes on demand.     

`m.flatMap(x => f(x).flatMap(y => g(y).flatMap(z => h(z))))`
builds
```scala
FlatMap(
  first = m,
  successK = x => f(x).flatMap(y => g(y).flatMap(z => h(z)))
)
```
Before `m` runs, what exists in memory?
```sh
Heap:
  ┌──────────────────┐
  │ FlatMap          │
  │  first = m       │
  │  successK = λ    │  ← just a function pointer, NOT a FlatMap tree
  └──────────────────┘
  ```
  That `successK` is a function. It's `x => f(x).flatMap(y => ...)`. It's just a closure object sitting there. The `FlatMap(f(x), ...)` inside it doesn't exist yet — it's source code inside a function body that hasn't been called.
  After `m` completes with value `42`, the runLoop calls `successK(42)`:

  ```scala
  successK(42)
// executes: f(42).flatMap(y => g(y).flatMap(z => h(z)))
// which constructs:
FlatMap(
  first = f(42),
  successK = y => g(y).flatMap(z => h(z))   ← again, just a function
)
```
Now the heap has:
```sh
Heap:
  ┌──────────────────┐
  │ FlatMap          │
  │  first = f(42)   │
  │  successK = λ    │  ← just a function, g/h don't exist yet
  └──────────────────┘
  ```
  The previous `FlatMap(m, ...)` is gone — garbage collected. And `g(y).flatMap(z => h(z))` still doesn't exist in memory.
  After `f(42)` completes with value `99`, call `successK(99)`:
  ```sh
Heap:
  ┌──────────────────┐
  │ FlatMap          │
  │  first = g(99)   │
  │  successK = λ    │  ← h doesn't exist yet
  └──────────────────┘
  ```
  After `g(99)` completes with value `7`, call `successK(7)`:
  ```sh
  Heap:
  ┌──────────────────┐
  │ h(7)             │  ← just the final effect, no FlatMap at all
  └──────────────────┘
```
Each `flatMap` body contains all remaining steps. That's why it's right-associated — the nesting goes into `successK` (the right side), not into first (the left side).

```sh
io1.flatMap { a =>
  // ┌─── the ENTIRE rest of the program lives inside this one closure ───┐
  // │                                                                    │
       io2(a).flatMap { b =>
         // ┌─── the ENTIRE rest after io2 lives inside THIS closure ───┐ │
         // │                                                           │ │
              io3(b).flatMap { c =>
                // ┌─── the rest after io3 lives inside HERE ───┐       │ │
                // │                                            │       │ │
                     io4(c).flatMap { d =>
                       // ┌─── rest after io4 ───┐              │       │ │
                       // │                       │             │       │ │
                            io5(d)                //            │       │ │
                       // └───────────────────────┘             │       │ │
                     }                                     //   │       │ │
                // └────────────────────────────────────────────┘       │ │
              }                                                    //   │ │
         // └───────────────────────────────────────────────────────────┘ │
       }                                                              //  │
  // └────────────────────────────────────────────────────────────────────┘
}
```
Right: one big nesting, each step inside the previous. 
Left: each step wrapping around all the previous.

## Async
```scala
final case class Async[R, E, A](
  trace: Trace,
  registerCallback: (ZIO[R, E, A] => Unit) => Either[URIO[R, Any], ZIO[R, E, A]],
  blockingOn: () => FiberId
) extends ZIO[R, E, A]
```
`registerCallback` is a function whose argument is a callback that the user can call to resume the fiber. The runtime calls `registerCallback` (which the user supplied) to start an async operation, and it returns one of three things
The `registerCallback` takes a callback and returns:

- `Left(finalizer)` → "I started an async op. If you need to cancel, run this finalizer."
- `Right(value)` → "Actually, the result is available now." (sync fast-path)
- `null` → "I started an async op. No way to cancel it."

The user constructs an Async node (typically via `ZIO.async` or `ZIO.asyncInterrupt`), providing a registration function:
`registerCallback: (ZIO[R, E, A] => Unit) => Either[URIO[R, Any], ZIO[R, E, A]]`

```scala
ZIO.async[Any, Throwable, String] { callback =>
  // "Register" our callback with an external async API
  httpClient.onComplete(response => callback(ZIO.succeed(response)))
  //         ^^^^^^^^^^
  //         This is the registration — subscribing to a future event
}
```
It's called a "registration function" because its job is to register a callback with some external async system (e.g., an HTTP client, a database driver, a message queue, etc). The callback is how the external system will signal back to the fiber when the async operation completes. The registration function can also return a finalizer (if the async system supports cancellation) or a synchronous result (if the async system can complete immediately).

That lambda gets stored as the `registerCallback` field in the `Async` case class. When the run loop encounters it, it hands it to `initiateAsync`, which calls it — passing in the runtime's Callback as the `callback` parameter.

So the user writes it, ZIO stores it, and the runtime invokes it.
```scala

  /**
   * Initiates an asynchronous operation, by building a callback that will
   * resume execution, and then feeding that callback to the registration
   * function, handling error cases and repeated resumptions appropriately.
   *
   * '''NOTE''': This method must be invoked by the fiber itself.
   */
  private def initiateAsync(
    asyncRegister: (ZIO.Erased => Unit) => Either[ZIO.Erased, ZIO.Erased]
  ): ZIO.Erased = {
    val callback = new AsyncContWith.Callback(self)
    var value    = null.asInstanceOf[Either[ZIO.Erased, ZIO.Erased]]

    try {
      value = asyncRegister(callback)
    } catch {
      case ex if nonFatal(ex) => callback(Exit.Failure(Cause.die(ex)))
      case fatal              => handleFatalError(fatal)
    }

    value match {
      case Left(onInterrupt) =>
        if (isInterruptible()) self._asyncContWith = AsyncContWith(callback, onInterrupt)

      case Right(value) if value ne null =>
        if (callback.compareAndSet(false, true)) {
          // Synchronous resumption
          return value
        }
        log(
          FiberRuntime.syncResumptionErrorMessage,
          Cause.empty,
          ZIO.someError,
          id.location
        )

      case _ =>
        if (isInterruptible()) self._asyncContWith = AsyncContWith(callback)
    }

    null
  }
```
When the fiber interpreter encounters this Async node, it calls initiateAsync in FiberRuntime.scala:696, which:
- Creates a `callback: ZIO[R, E, A] => Unit` (used to resume the fiber later)
- Invokes the user's registration function, passing in that callback
- Inspects what the registration function returns  

```scala
ZIO.asyncInterrupt[Any, Nothing, Int] { cb =>          // ← registerCallback starts here
  val cancel = someHttpClient.get("...") { response =>
    cb(ZIO.succeed(response.status))                    //   user calls the runtime's callback
  }
  Left(ZIO.succeed(cancel()))                           //   returns Left = "still pending, here's cleanup"
}                                                       // ← registerCallback ends here
```
And at runtime, the sequence is:

- Run loop hits the Async node
- `initiateAsync` creates a Callback instance
- `initiateAsync` invokes this lambda, passing in that Callback as `cb`
- The lambda starts the HTTP request and returns `Left(ZIO.succeed(cancel()))`
- `initiateAsync` sees `Left(onInterrupt)` → stores callback + cleanup in `_asyncContWith` → returns null → fiber suspends
- Later, the HTTP response arrives on some other thread → the lambda's inner closure calls `cb(ZIO.succeed(response.status))` → `Callback.completeZIO` fires → fiber resumes

The runtime's only job in `initiateAsync` is:
- Create a callback — `new AsyncContWith.Callback(self)`
- Pass it to the user's function — `asyncRegister(callback)`
- Handle the result

That's it. The Callback is just a thin wrapper: an `AtomicBoolean` + a reference to the fiber. When called, it posts a `Resume` message to the fiber's inbox

Without calling the callback, the fiber stays suspended forever (or until interrupted). The callback is the only way to resume it. So the user must call it at some point — otherwise their fiber will never wake up.

```scala
def never = async[Any, Nothing, Nothing](ZIO.unitFn)
```
It calls `ZIO.async` with `ZIO.unitFn` — a function that ignores the callback entirely and never calls it. The registration function runs, does nothing, and returns. The fiber suspends waiting for a callback that will never come.

The runtime creates the callback and hands it over. From that point, it's the user's responsibility to ensure it gets called — typically by passing it to some external system's completion handler
The runtime never calls it itself. The only exception is `interruption` — if the fiber gets interrupted while suspended, the runtime calls `callback.completeCause(cause)` internally in `processNewInterruptSignal`. But for normal completion, it's always the user's code (or the external system the user registered with).

`fiber.tell(FiberMessage.Resume(effect))`
`tell` posts the message to that specific fiber's inbox and tries to schedule it for execution. Without the fiber reference, the callback would have no way to deliver the result — it wouldn't know where to send it

```scala
final class Callback(fiber: FiberRuntime[?, ?]) extends AtomicBoolean(false) with (ZIO.Erased => Unit) {

  def apply(effect: ZIO.Erased): Unit = completeZIO(effect)

  def completeZIO(effect: ZIO.Erased): Boolean =
    if (compareAndSet(false, true)) {      // try to flip false → true
      fiber.tell(FiberMessage.Resume(effect))
      true
    } else false                           // someone else already resumed

  def completeCause(cause: Cause[Nothing]): Boolean =
    if (compareAndSet(false, true)) {
      fiber.tell(FiberMessage.Resume(Exit.Failure(cause)))
      true
    } else false
}
```
`with (ZIO.Erased => Unit)` means the callback can be called like a function: `callback(effect)` is the same as `callback.apply(effect)`, which calls `completeZIO(effect)`. So when the user calls `cb(ZIO.succeed(response.status))`, it invokes `completeZIO` on the callback.

#### Why the AtomicBoolean matters
The callback extends `AtomicBoolean(false)`. The `compareAndSet(false, true)` ensures exactly-once resumption. This is crucial because the external system might call the callback multiple times (e.g., if its API is buggy, or if it calls the callback on both success and failure, or if it calls the callback on a timeout and then again when the result arrives). The `AtomicBoolean` ensures that only the first call to `completeZIO` or `completeCause` will succeed in resuming the fiber. Subsequent calls will return false and do nothing, preventing multiple resumptions which could lead to race conditions or inconsistent state in the fiber.

The `AtomicBoolean` starts as `false` (not yet resumed). There are three places that race to flip it to true via `compareAndSet(false, true)`:

1. The external system calls back (normal completion)
The user's async code calls `callback(ZIO.succeed(result))`, which hits `completeZIO`. If it wins the CAS, it posts `FiberMessage.Resume` to the fiber's inbox, waking it.
2. Synchronous fast-path (inside initiateAsync)
```scala
case Right(value) if value ne null =>
  if (callback.compareAndSet(false, true)) {
    return value   // no message posting needed, just return directly
  }
```
If the registration function returns `Right(value)`, `initiateAsync` itself tries to claim the boolean. If it wins, the fiber never suspends — it just continues the run loop with value. Note it doesn't call `fiber.tell` here because the fiber is already running on the current thread.  

3. Interruption (inside processNewInterruptSignal)
```scala
// In processNewInterruptSignal:
val callback = k.callback
// ...
callback.completeCause(cause)   // tries compareAndSet(false, true)
```
When an interrupt arrives while the fiber is suspended, `processNewInterruptSignal` grabs the callback from `_asyncContWith `and calls `completeCause`. If it wins the CAS, it resumes the fiber with a failure cause

`compareAndSet(false, true)` is a method on `java.util.concurrent.atomic.AtomicBoolean`. It does this atomically (as a single CPU instruction):
```scala
if (currentValue == false) {
  currentValue = true
  return true    // "I won"
} else {
  return false   // "Someone else already set it"
}
```
It's called CAS (Compare-And-Swap). The key property is that it's atomic at the hardware level — even if multiple threads call it simultaneously, exactly one will get true and the rest get false. There's no window where two threads can both see false and both succeed

`private[zio] type Erased = ZIO[Any, Any, Any]`
It erases the type parameters R, E, A to Any. The runtime uses it because the run loop processes effects generically — it doesn't care about the specific types. Every `ZIO[R, E, A]` gets cast to `ZIO[Any, Any, Any]` inside the interpreter.

This avoids carrying type parameters through all the runtime internals. Compare:
```scala
// Without Erased — runtime would need type params everywhere
class Callback[R, E, A](fiber: FiberRuntime[E, A]) extends (ZIO[R, E, A] => Unit)

// With Erased — one concrete type, no params
class Callback(fiber: FiberRuntime[?, ?]) extends (ZIO.Erased => Unit)
```
By accepting a ZIO instead of just `A` or `Either[E, A]`, the callback can express any outcome — success, typed failure, defects, interruption, or even further effects that need to run before producing the result. A plain value would only handle the success case.

The `registerCallback` returns an `Either`
`registerCallback: (ZIO[R, E, A] => Unit) => Either[URIO[R, Any], ZIO[R, E, A]]`

 It returns an `Either` to signal what happened during registration:

`Left(cleanup)` — "I started an async operation, here's a finalizer to cancel it if the fiber is interrupted"
`Right(value)` — "The result is already available, no need to suspend"
### How Users Create Async Effects
```scala
// Simple: fire-and-forget, no cancellation
ZIO.async[Any, Nothing, Int] { callback =>
  someExternalSystem.onComplete(result => callback(ZIO.succeed(result)))
}

// With cancellation:
ZIO.asyncInterrupt[Any, Nothing, Int] { callback =>
  val handle = someExternalSystem.onComplete(result => callback(ZIO.succeed(result)))
  Left(ZIO.succeed(handle.cancel()))   // ← finalizer if interrupted
}

// Sync fast-path:
ZIO.asyncInterrupt[Any, Nothing, Int] { callback =>
  cache.get(key) match {
    case Some(v) => Right(ZIO.succeed(v))   // ← available immediately
    case None    =>
      db.fetchAsync(key, result => callback(ZIO.succeed(result)))
      Left(ZIO.succeed(db.cancelFetch(key)))
  }
}
```
What Happens When `runLoop` Hits `Async`
```scala
//Step 1: Create a Callback

val callback = new AsyncContWith.Callback(self)
//This Callback extends AtomicBoolean(false). It's a one-shot function:

final class Callback(fiber: FiberRuntime[?, ?]) extends AtomicBoolean(false) {
  def completeZIO(effect: ZIO.Erased): Boolean =
    if (compareAndSet(false, true)) {    // only the first caller wins
      fiber.tell(FiberMessage.Resume(effect))   // send resume to fiber's inbox
      true
    } else false
}

```
The `AtomicBoolean` ensures that even if the callback is invoked multiple times (from different threads), only the first invocation resumes the fiber.

```scala
//Call the User's Registration Function
value = asyncRegister(callback)

//This hands the callback to user code. The user does something like:
httpClient.get(url, response => callback(ZIO.succeed(response)))
```
Now the callback is "out there" in the external system. When the HTTP response arrives, `callback` will be invoked, which sends `FiberMessage.Resume` to the fiber's inbox.

```sh
#Handle the Return Value

registerCallback returns    │ What happens
────────────────────────────┼──────────────────────────────────────
Left(finalizer)             │ Store callback + finalizer in _asyncContWith
                            │ Return null → fiber will suspend
────────────────────────────┼──────────────────────────────────────
Right(value)                │ CAS callback false→true (claim it)
                            │ Return value → runLoop continues immediately
────────────────────────────┼──────────────────────────────────────
null                        │ Store callback only (no finalizer)
                            │ Return null → fiber will suspend
```  
```scala
 //Check for Early Completion 
 if (cur eq null) {
  cur = drainQueueAfterAsync()   // maybe callback already fired during registration
}
```
This is a race condition handler. The external system might have called `callback` during `asyncRegister` (e.g., it completed synchronously). In that case, `FiberMessage.Resume` is already in the inbox. `drainQueueAfterAsync` grabs it.

```scala
//Step 5: Suspend or Continue
if (cur eq null) {
  self._blockingOn = async.blockingOn   // record what we're waiting on
  return null                           // EXIT the runLoop — fiber is suspended
}
```
`return` null exits `runLoop`, which exits `evaluateEffect`, and the fiber's thread is freed. The fiber is now `parked` — no thread is running it.

```sh
Fiber A: io1 >> io2 >> ZIO.async(...) >> io3 >> io4
                              ↑
                          suspends here

io1  ✓ executed
io2  ✓ executed
async  → registers callback, return null, fiber suspends
io3  ✗ waiting — exists as a continuation on _stack
io4  ✗ waiting — exists as a continuation on _stack
```
The fiber's state stays in memory on the heap — no thread needed:
```sh
FiberRuntime (heap object, no thread attached):
  _stack     = [ continuation for io3, continuation for io4 ]  ← intact
  _stackSize = 2
  _fiberRefs = { environment, logger, etc. }                   ← intact
  _runtimeFlags = ...                                           ← intact
  _asyncContWith = Callback + optional finalizer                ← how to resume/cancel
  _blockingOn = FiberId of what we're waiting on
  running = false                                               ← no thread owns this fiber
  ```

  ### Interruption
```scala
ZIO.asyncInterrupt[Any, Nothing, String] { callback =>
  val connection = database.query("SELECT ...", result => {
    callback(ZIO.succeed(result))    // when done, resume fiber
  })

  Left(ZIO.succeed(connection.close()))   // ← if cancelled, close the connection
}
```
When `initiateAsync` runs this, it stores three things:
```sh
_asyncContWith = (callback, ZIO.succeed(connection.close()))
                  ↑                ↑
                  │                └── onInterrupt: the finalizer
                  └── callback: the AtomicBoolean(false) one-shot function
 ```
 The fiber then suspends (`return null`). No thread is attached.     

## The Three Cancellation Scenarios
### Scenario 1: Normal completion (no cancellation)
```sh
Database responds → callback(ZIO.succeed("data"))
  → callback.compareAndSet(false, true)     ✓ wins
  → fiber.tell(Resume(ZIO.succeed("data")))
  → fiber resumes, continues with "data"
  → _asyncContWith is cleared
```
The finalizer is never called. The connection is used normally.

### Scenario 2: Interruption arrives WHILE suspended
Another fiber calls fiberA.interrupt:
```sh
otherFiber.interrupt
  → fiber.tell(InterruptSignal(cause))
    → drainQueueOnCurrentThread
      → evaluateMessageWhileSuspended(InterruptSignal(cause))
        → processNewInterruptSignal(cause)
 ```
 Now processNewInterruptSignal runs. Here's what it does, step by step:
 ```scala
 // 1. Record the interrupt
self.addInterruptedCause(cause)           // _isInterrupted = true
self.sendInterruptSignalToAllChildren()   // propagate to child fibers

// 2. Grab the stored async state
val k = self._asyncContWith              // (callback, finalizer)
self._asyncContWith = AsyncContWith.null  // clear it

val callback = k.callback                // the AtomicBoolean one-shot
```
Then it checks k.onInterrupt — the finalizer:

### Case A: No finalizer (null)
`case null => callback.completeCause(cause)`
Just resume the fiber with the interrupt cause. The fiber wakes up, sees `Exit.Failure(Interrupt(...))`, unwinds the stack.

### Case B: Simple finalizer (Sync, most common)
```scala
case sync: Sync[Any] =>
  if (callback.completeCause(cause)) {   // CAS false→true: claim the callback
    sync.eval()                           // run the finalizer RIGHT HERE
  }
  ```
`callback.completeCause(cause)` does two things atomically:

Claims the `callback` (CAS `false → true`) — so if the database responds now, its call to `callback` will fail the CAS and be ignored
Sends `Resume(Exit.Failure(cause))` to the fiber's inbox
Then it runs the finalizer inline `(sync.eval() = connection.close())`.

The CAS is critical here. It's a race between:
```sh
Thread X (database):          Thread Y (interruptor):
callback(succeed("data"))     callback.completeCause(interruptCause)
  compareAndSet(false,true)     compareAndSet(false,true)
```
Only one wins. If the interruptor wins→ fiber is cancelled, finalizer runs. If the database wins → fiber resumes with data, interrupt is too late (the fiber is no longer suspended)  

### Case C: Complex finalizer (a full ZIO effect)
```scala
case onInterrupt =>
  val f = onInterrupt.foldCauseZIO(
    c => { addInterruptedCause(c); enableInterruptionAfterAsync },
    _ => enableInterruptionAfterAsync
  )

  if (callback.completeZIO(f))
    patchRuntimeFlagsOnly(disableInterruption)  // protect finalizer from being interrupted
 ```
 The finalizer is too complex to run inline, so it:

1. Wraps the finalizer in error handling
2. Resumes the fiber with the finalizer as the next effect to run
3. Disables interruption so the finalizer can't be interrupted mid-execution
4. After the finalizer completes, re-enables interruption (via `enableInterruptionAfterAsync`)
### Scenario 3: Interruption arrives AFTER the callback already fired   
```sh
Thread X (database):           Thread Y (interruptor):
callback(succeed("data"))
  compareAndSet(false,true) ✓   
  fiber.tell(Resume)            
                               processNewInterruptSignal:
                                 callback.completeCause(cause)
                                   compareAndSet(false,true) ✗  ← FAILS
                                   returns false
                                   → finalizer NOT run
                                   → interrupt cause still recorded
```
The callback already won the race. The fiber will resume with the data. But the interrupt cause is still recorded via `addInterruptedCause` — the fiber will notice it at the next `shouldInterrupt()` check.

### The Race Diagram
  ```sh
                    _asyncContWith = (callback[false], finalizer)
                  Fiber is suspended, no thread
                          │
          ┌───────────────┼────────────────┐
          ▼               │                ▼
    Database responds     │         Interrupt arrives
          │               │                │
   callback(result)       │    processNewInterruptSignal
          │               │                │
   CAS(false→true)  ──── RACE ────  CAS(false→true)
          │                                │
     ┌────┴────┐                    ┌──────┴──────┐
     │ WON     │                    │ WON         │
     ▼         ▼                    ▼             ▼
  Resume    (loser:              Resume w/     (loser:
  w/ data   interrupt            Failure +     database
             ignored*)           finalizer     response
                                 runs          ignored)

  * interrupt cause is still recorded, checked later
  ```

  ```scala
  val program = for {
  fiber <- ZIO.asyncInterrupt[Any, Nothing, String] { cb =>
    val timer = new java.util.Timer()
    timer.schedule(new TimerTask {
      def run() = cb(ZIO.succeed("done"))
    }, 5000)  // 5 seconds

    Left(ZIO.succeed(timer.cancel()))   // ← finalizer: cancel the timer
  }.fork

  _ <- ZIO.sleep(1.second)
  _ <- fiber.interrupt                  // interrupt after 1 second
} yield ()
```

What happens:
```sh
t=0ms:  fiber starts, registers timer, suspends
        _asyncContWith = (callback, ZIO.succeed(timer.cancel()))

t=1000ms: fiber.interrupt called
          → processNewInterruptSignal
          → callback.completeCause(interruptCause) → CAS wins ✓
          → timer.cancel() runs                     ← FINALIZER EXECUTES
          → fiber resumes with Exit.Failure(Interrupt)
          → stack unwinds

t=5000ms: timer would have fired, but it's cancelled — nothing happens
```

Step 1. **User provides a cleanup effect**

```scala
ZIO.asyncInterrupt { cb =>
  val cancel = httpClient.get("...") { response =>
    cb(ZIO.succeed(response))
  }
  Left(ZIO.succeed(cancel()))   // ← cleanup effect
}
```

Step 2. **Runtime stores it in _asyncContWith**

Inside `initiateAsync` at `FiberRuntime.scala:710`:

```scala
value match {
  case Left(onInterrupt) =>
    if (isInterruptible()) self._asyncContWith = AsyncContWith(callback, onInterrupt)
    //                                                         ^^^^^^^^  ^^^^^^^^^^^
    //                                                         the CAS   the cleanup ZIO
}    
```
`_asyncContWith `now holds both the `callback` and the `cleanup effect` as a tuple.    

Step 3. **An interrupt arrives**

Some other fiber calls myFiber.interrupt. This sends a message:
```scala
tell(FiberMessage.InterruptSignal(cause))
```
Step 4. **processNewInterruptSignal handles it**

At FiberRuntime.scala:1017:
```scala
private def processNewInterruptSignal(cause: Cause[Nothing]): Unit = {
  self.addInterruptedCause(cause)
  self.sendInterruptSignalToAllChildren(_children)

  val k = self._asyncContWith              // grab the stored callback + cleanup
  self._asyncContWith = AsyncContWith.`null`

  val callback = k.callback

  if (callback eq null) return             // not suspended async, nothing to do

  k.onInterrupt match {
    case null =>                           // no cleanup provided
      callback.completeCause(cause)        // just resume with interruption

    case sync: Sync[Any] =>               // simple synchronous cleanup (fast path)
      if (callback.completeCause(cause)) { // claim the callback first
        updateLastTrace(sync.trace)
        try { sync.eval() }               // run cleanup directly
        catch { ... }
      }

    case onInterrupt =>                    // complex cleanup (needs the run loop)
      val f = onInterrupt.foldCauseZIO(
        c => { addInterruptedCause(c); enableInterruptionAfterAsync },
        _ => enableInterruptionAfterAsync
      )
      if (callback.completeZIO(f))         // resume fiber with cleanup effect
        patchRuntimeFlagsOnly(RuntimeFlags.disableInterruption)  // disable interruption so cleanup can run
  }
}
```

1. `initiateAsync `calls `asyncRegister(callback)`
2. Inside the user's function, the external system calls callback(result)
   → callback posts `FiberMessage.Resume(result)` to the inbox
3. `asyncRegister` returns `Left(cleanup)`
4. `initiateAsync` sees `Left` → stores `_asyncContWith` → returns `null`

The problem: between steps 2 and 4, the callback already fired and deposited a `Resume` message in the inbox. But `initiateAsync` returned `null` (meaning "we're suspended"). The fiber would incorrectly suspend forever even though the result is already sitting in the inbox.

That's why the run loop checks immediately after:
```scala
cur = initiateAsync(async.registerCallback)  // returns null

if (cur eq null) {
  cur = drainQueueAfterAsync()               // ← check: did someone already resume us?
}

if (cur eq null) {
  self._blockingOn = async.blockingOn
  return null                                // truly suspended, no result available
}
```
`drainQueueAfterAsync` drains the inbox, looking for a Resume message:
```scala
private def drainQueueAfterAsync(): ZIO.Erased = {
  var resumption: ZIO.Erased = null

  var message = inbox.poll()

  while (message ne null) {
    message match {
      case FiberMessage.Resume(nextEffect0) =>
        resumption = nextEffect0          // found it! the callback already fired

      case FiberMessage.InterruptSignal(cause) =>
        processNewInterruptSignal(cause)  // handle interrupts too

      case FiberMessage.Stateful(onFiber) =>
        processStatefulMessage(onFiber)   // handle stateful messages too

      case _ => ()
    }
    message = inbox.poll()
  }

  resumption   // non-null if callback already fired, null if still pending
}
```
**The key ordering**
1. `callback.completeCause(cause)` is called first — this claims the AtomicBoolean
2. Only then is cleanup run
This matters because the external system might call `cb(result)` at the same time as the interrupt arrives. The CAS ensures only one wins:
- If interrupt wins → cleanup runs, external callback becomes a no-op
- If external callback wins → fiber resumes with the result, interrupt's `completeCause` returns `false`, cleanup is skipped (not needed anymore — the operation completed)

```scala
Left(ZIO.succeed(cancel()))
// becomes: Left(Sync(() => cancel()))
```
When interruption arrives, `processNewInterruptSignal` pattern-matches on `k.onInterrupt`:
```scala
k.onInterrupt match {
  case sync: Sync[Any] =>               // ← matches here (fast path)
    if (callback.completeCause(cause)) { // claim the AtomicBoolean
      updateLastTrace(sync.trace)
      try {
        sync.eval()                      // ← this calls cancel()
      } catch {
        case ex if nonFatal(ex) => addInterruptedCause(Cause.die(ex))
        case fatal              => handleFatalError(fatal)
      }
    }
}
```    
So `sync.eval()` invokes the thunk `() => cancel()`, which actually executes `cancel()` — directly, inline, on the current thread. No run loop needed. That's why this is the fast path — it avoids scheduling the cleanup as a separate effect.

If the cleanup were more complex (e.g., `ZIO.fromFuture(...)` or a flatMap chain), it wouldn't match Sync and would fall through to the slow path, where the effect is fed back into the run loop for full evaluation

Inbox: `private val inbox = new ConcurrentLinkedQueue[FiberMessage]()`
Tell: fire-and-forget message send
Process loop: `drainQueueOnCurrentThread` pulls messages and handles them

```scala
private[zio] def tell(message: FiberMessage): Unit = {
  inbox.add(message)                                    // enqueue the message
  if (running.compareAndSet(false, true))               // if nobody is processing...
    drainQueueLaterOnExecutor(false)                    //   schedule processing on a thread pool
}
```
And the message types are:
```sh
FiberMessage
  ├── Resume(effect)            — wake up and run this effect
  ├── InterruptSignal(cause)    — you've been interrupted
  ├── Stateful(onFiber => ...)  — run this function on the fiber's thread
  └── YieldNow                  — give up the thread
```
The reason it uses this pattern (instead of just calling methods directly) is thread safety. A fiber's mutable state (`_fiberRefs`, `_stack`, `_runtimeFlags`, etc.) can only be touched by the fiber itself — on its own thread. So when another fiber wants to interrupt this fiber or add a child, it can't directly mutate anything. Instead it posts a message via `tell`, and the fiber processes it when it gets control.

```scala
def completeZIO(effect: ZIO.Erased): Boolean =
  if (compareAndSet(false, true)) {
    fiber.tell(FiberMessage.Resume(effect))   // ← just posts a message
    true
  } else false
```
```scala
private[zio] def tell(message: FiberMessage): Unit = {
  inbox.add(message)                                  // 1. add to queue
  if (running.compareAndSet(false, true))              // 2. if fiber is not running...
    drainQueueLaterOnExecutor(false)                   //    schedule it on the thread pool
}
```
`drainQueueLaterOnExecutor` submits the fiber (which implements `Runnable`) to the executor:
```scala
private def drainQueueLaterOnExecutor(...): Unit = {
  runningExecutor = self.getCurrentExecutor()
  runningExecutor.submitOrThrow(self)(Unsafe)    // submit this fiber as a Runnable
}
```
`The callback itself runs on whatever thread calls it` (the external system's thread). But the actual fiber resumption happens on a thread pool worker. The callback just drops a message and kicks the scheduler — it returns immediately.


```scala
private[zio] sealed trait FiberMessage
private[zio] object FiberMessage {
  final case class InterruptSignal(cause: Cause[Nothing])        extends FiberMessage
  final case class Stateful(onFiber: FiberRuntime[_, _] => Unit) extends FiberMessage
  final case class Resume(effect: ZIO[_, _, _])                  extends FiberMessage

  @deprecated("We no longer use a message to propagate yielding", "2.1.7")
  case object YieldNow extends FiberMessage

  val resumeUnit: FiberMessage = Resume(ZIO.unit)
}
```
The companion object also pre-allocates resumeUnit as a cached `Resume(ZIO.unit)` to avoid allocating a new object on every yield/resume
The mechanism is straightforward object reuse:

Without caching — every `ZIO.YieldNow` would do:
```scala
inbox.add(Resume(ZIO.unit))  // allocates a new Resume + ZIO.unit wrapper each time
```
With caching — declared once at class-load time as a `val`:
```scala
// FiberMessage.scala L36
val resumeUnit: FiberMessage = Resume(ZIO.unit)
```
Then every YieldNow uses the same pre-existing object:
```scala
// FiberRuntime.scala L1302
inbox.add(FiberMessage.resumeUnit)  // no allocation — reuses the singleton
```

## Cooperative Yielding
A single fiber could hog a thread forever. ZIO prevents this with two yield mechanisms:

**Operation counting** — The run loop increments ops on every iteration. After 10,240 operations, it yields:
```scala
ops += 1
if (ops > MaxOperationsBeforeYield && RuntimeFlags.cooperativeYielding(_runtimeFlags)) {
  inbox.add(FiberMessage.Resume(cur))  // re-enqueue current effect
  return null                          // exit run loop
}
```
Both mechanisms depend on the `cooperativeYielding` runtime flag — if disabled, the fiber runs uninterrupted.
**Fork counting** — If a fiber forks 128 times without yielding, `shouldYieldBeforeFork()` returns true:
```scala
private[zio] def shouldYieldBeforeFork(): Boolean =
  if (RuntimeFlags.cooperativeYielding(_runtimeFlags)) {
    _forksSinceYield += 1
    _forksSinceYield >= MaxForksBeforeYield
  } else false
```  

Explicit yield — `ZIO.yieldNow` is handled as a `YieldNow` node:
```scala
case yieldNow: ZIO.YieldNow =>
  inbox.add(FiberMessage.resumeUnit)
  return null
```
After any yield, the counter resets at FiberRuntime.scala:440:` _forksSinceYield = 0 `
## Instruction Set Architecture(ISA)
It is the set of instructions that defines what kinds of operations can be performed in hardware. ISA describes the memory model,supported data types, registers and behavior of machine code. The sequences of zeros and ones that the CPU must execute.  eg Arm and x86 are two different ISAs. They have different instructions, registers, and ways of doing things. A program compiled for one ISA won't run on a CPU with a different ISA without some kind of translation layer (e.g., an emulator).

ZIO's instruction set is the set of all possible `ZIO` effects that users can construct. Each combinator (e.g., `flatMap`, `map`, `async`, `fold`, etc.) corresponds to a different instruction. The `ZIO` data type is the "assembly language" that encodes these instructions as data structures (e.g., `FlatMap`, `Async`, etc.). The run loop is the "CPU" that executes these instructions by pattern-matching on the `ZIO` data structures and performing the corresponding operations. Just like a CPU has an instruction set (e.g., ADD, SUB, LOAD, STORE), ZIO has an instruction set (e.g., FlatMap, Async, Fold). Each instruction has its own semantics and effects on the fiber's state. The combination of these instructions allows users to build complex programs that can do anything a normal Scala program can do, but with powerful features like asynchrony, concurrency, interruption, and resource safety.

## Fork
Calling `.fork` on an effect starts it running in the background. It immediately returns a Fiber (specifically a `Fiber.Runtime`), which is like a lightweight thread handle.

```scala
val backgroundTask: ZIO[Any, Nothing, Fiber[Throwable, String]] = 
  ZIO.succeed("Doing heavy work...").delay(2.seconds).fork
```
The crux of the process is dropping a message into the child fiber's queue and signalling the thread pool executor:
```scala
// In FiberRuntime.scala
private[zio] def startConcurrently(effect: ZIO[_, E, A]): Unit =
  tell(FiberMessage.Resume(effect))
```  
## Join
To wait for an executing fiber to finish and extract its success value (or fail if the fiber failed), you call .join on the Fiber handle.
```scala
for {
  fiber  <- backgroundTask
  // ... do other things concurrently ...
  result <- fiber.join // Suspends the parent fiber until the child finishes
  _      <- ZIO.debug(s"Result was: $result")
} yield ()
```

