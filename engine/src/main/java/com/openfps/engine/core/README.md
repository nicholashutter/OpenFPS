# Core — Engine Entry, Event Bus, and Worker Pool

> The engine is an **event queue processor**. All subsystem interactions
> go through events. A pool of dedicated worker threads consumes from
> the queue and dispatches to subsystems.

## What lives here

```
core/
├── EngineMain.java           — bootstrap: wire memory, HAL, bus, pool, subsystems
├── GameLoop.java             — D_ event producer (30/60/120 Hz tic events + shutdown)
├── event/                    — I_EngineEvent + concrete events + factory
│   ├── I_EngineEvent.java
│   ├── TickEvent.java
│   ├── RenderFrameEvent.java
│   ├── ShutdownEvent.java
│   ├── MapLoadEvent.java
│   ├── InputSampledEvent.java
│   ├── NetworkPacketEvent.java
│   └── EventFactory.java
├── eventbus/                 — single shared queue with blocking backpressure
│   ├── I_EventBusPort.java
│   ├── SharedEventBus.java
│   └── EventBusFactory.java
├── pool/                     — fixed worker thread pool
│   ├── I_ThreadPoolPort.java
│   ├── WorkerPool.java
│   ├── ThreadPoolFactory.java
│   ├── I_ParallelJob.java        — one indexed unit of fan-out work
│   └── ParallelJobException.java — job failure, rethrown to the submitter
└── subsystem/                — per-subsystem state machine + registry
    ├── ISubsystem.java
    ├── Subsystem.java         (base class with state machine)
    ├── SubsystemId.java
    ├── SubsystemState.java
    ├── SubsystemException.java
    ├── SubsystemRegistry.java
    └── impl/                  (concrete subsystems)
        ├── GameplaySubsystem.java
        ├── RenderSubsystem.java
        ├── AudioSubsystem.java
        ├── NetSubsystem.java
        ├── HalSubsystem.java
        └── MemorySubsystem.java
```

## How it works — the event flow

```
  +------------+
  |  GameLoop  |  (single thread, 30/60/120 Hz)
  +-----+------+
        |  produces TickEvent every 33.3/16.7/8.3ms
        v
  +-----+------+      +---------------+      +-------------------+
  |            |  ──> |               | ───> |                   |
  | EventBus   |      | WorkerPool    |      | SubsystemRegistry|
  | (shared    |      | (N=cores/2    |      |  + GameplaySubsys |
  |  queue)    |      |  hot threads) |      |  + RenderSubsys   |
  |            |      |               |      |  + ...            |
  +-----+------+      +-------+-------+      +-------------------+
        ^                      |                       |
        |                      | dispatches            |
        +----------------------+<──────────────────────+
```

1. **Producers** (GameLoop, input, network) call `bus.publish(event)`.
2. The bus's queue is shared between all producers and consumers. If full,
   the producer **blocks** (backpressure).
3. **Workers** in the pool call `bus.take()`. They block until an event arrives.
4. Workers call `registry.dispatch(event)`, which routes to the right subsystem.
5. The subsystem's `processEvent()` invokes `onEvent()` (concrete handler).
6. The thread loops back to `bus.take()` — implicitly released.

## Subsystem state machine

Each subsystem has a strict lifecycle state machine:

```
  +---------------+                 +-------+                 +----------+
  | UNINITIALIZED | --init()------> | READY | --shutdown()--> | SHUTDOWN |
  +---------------+                 +-------+                 +----------+
          |                             |                       (terminal)
          | init() throws               | shutdown() throws          ^
          v                             v                            |
       +-------+                     +-------+                       |
       | ERROR | ----------------------------  --shutdown()----------+
       +-------+
```

There is no `reset()` — once a subsystem reaches SHUTDOWN it is done, and
ERROR can only be shut down, not revived. The state tracks the subsystem's
LIFECYCLE, not per-event state: there is no BUSY, because several workers
can be inside `onEvent` for the same subsystem simultaneously.
Per-event "is the worker busy?" is implicit: the worker thread is
released the moment `onEvent()` returns.

Multiple workers can dispatch events to the SAME subsystem in parallel.
The subsystem's `onEvent()` must be thread-safe.

## Threading

The default thread layout is:
- **N worker threads** (N = `logicalProcessorCount / 2`, min 1) — pre-started, hot
- **1 GameLoop thread** — produces events at the configured rate (30/60/120 Hz)
- **1 main thread** — blocks on `loopThread.join()`

Total: 1 + 1 + N threads. For a 16-core machine, N = 8, so 10 threads total.

The pool size comes from `I_SystemInfoPort.logicalProcessorCount() / 2`.
This is the "half as many dedicated threads as the hardware has" rule
the user asked for. A Phase 2 desktop adapter can read the TRUE
physical-core count via oshi/JNA.

## Parallel fan-out — `submitParallel`

Event draining is one-event-per-worker. The renderer needs the other shape:
split the screen into tiles, run them all, join once at end of frame
(`render/README.md` § 7). That is `I_ThreadPoolPort.submitParallel(job, jobCount)`
— run indices `0 .. jobCount-1` and return when every one has completed.

It is deliberately not a general executor. Indices, not tasks; one reusable
`I_ParallelJob` callback, not a task object per tile. Nothing is allocated per
job or per submission, because this runs every frame.

### The caller participates — this is the correctness property

Subsystems are dispatched *from the bus*, so **the thread that submits is
normally itself a pool worker**. A submit-and-block implementation would take a
worker out of the very pool that has to execute the batch: at
`workerCount == 1` that is an immediate total deadlock, and above 1 it is a
latent one that shows up the first time two subsystems fan out in one frame.

So the submitting thread claims and runs jobs itself until the range is
exhausted, and only then waits on the jobs other threads already claimed.
Progress is guaranteed by the calling thread alone — correctness no longer
depends on how many workers exist. This is the standard fork-join "caller
helps" pattern.

### How idle workers are reached — leader / follower

A worker blocked in `bus.take()` can only be woken by an interrupt, and this
pool will not interrupt a thread that might be inside subsystem code. So at
most **one** worker is in `take()` at a time — the leader — and every other
idle worker waits on a pool-owned condition that `submitParallel` can signal.
When the leader gets an event it hands leadership over and *then* dispatches,
so up to N dispatches still run concurrently and bus behaviour is unchanged.

At `workerCount == 1` the sole worker is usually the leader and cannot help;
the caller runs the whole range. That is the intended degradation, not a bug.

### Failure, reentrancy, ordering

- **Failure**: a throwing job does not abort the batch and cannot hang the
  join — `Throwable` is caught and the completion counter is bumped in a
  `finally`. The first failure is rethrown to the submitter as a
  `ParallelJobException` after the join; later ones are logged.
- **Reentrancy**: supported. A job may submit its own batch. Slots are
  pre-allocated (8); when they are all in flight a submission runs its whole
  range inline on its caller — same guarantee, no parallelism.
- **Ordering**: indices are claimed in ascending order, complete in no
  particular order, and are not tied to any thread.

## State machine — engine level

The engine itself has a simple state:

```
  BOOTING → BUSY → SHUTTING_DOWN → SHUTDOWN
```

Tracked implicitly by the order of operations in `EngineMain.runHeadless()`.
No dedicated `EngineStateMachine` class — the bootstrap code is the
state machine.

## Why event-driven?

- **Testability**: events are easy to inject in tests. We don't need to
  set up real network/input to test gameplay logic.
- **Decoupling**: subsystems don't call each other. They emit events
  and consume events. Adding a new subsystem doesn't ripple.
- **Observability**: every interaction is an event, so the event log
  IS the engine's activity log.
- **Future distributed**: same events could flow across a network
  boundary (Phase 4 P2P).

## What lives in each subsystem package vs core

- `core/`: engine entry, event bus, pool, subsystem state machine, GameLoop
- `gameplay/`, `render/`, `audio/`, `net/`: subsystem PORT interfaces
- `core/subsystem/impl/`: concrete subsystems that WRAP the ports and
  implement the event-handler logic

The ports stay minimal (`init/shutdown/port-specific-methods`).
The `Subsystem` wrapper adds the state machine and event dispatch.

## Tests

Core-package test coverage:
- 9 FrameRate — per-rate math, parser, rejection of unsupported rates
- 10 GameConfig — factory methods, drift correction over 1000 tics
- 10 SharedEventBus — publish, take, FIFO, blocking, backpressure, drain
- 7 WorkerPool — hot threads, parallel dispatch, recovery, lifecycle
- 12 WorkerPool parallel fan-out — caller participation at `workerCount == 1`,
  exactly-once execution, failure propagation, reentrancy, bus coexistence
- 10 SubsystemState — transitions, error recovery, thread-safety
- 6 FixedMath — unchanged from earlier
- 43 MemoryPort — unchanged from earlier (covers both backends)

Run with: `.\gradlew.bat test`

## Running at different rates

The engine supports three frame rates: **30 Hz**, **60 Hz**, **120 Hz**. The
rate is set at startup via the `--fps` CLI flag. Default is 60.

```powershell
.\gradlew.bat run --args="--fps=30"
.\gradlew.bat run --args="--fps=60"   # default
.\gradlew.bat run --args="--fps=120"
```

Anything else is rejected with a friendly error. See
`core/FrameRate.java` for the rationale and the math.

### Why these three rates only?

- **30 Hz** — console target, low-power / laptop mode
- **60 Hz** — standard PC display, default for headless tests
- **120 Hz** — high-refresh gaming displays
- 144/240 Hz are niche esports, excluded by design

### Drift correction (the math)

The frame budget in nanoseconds is computed once at construction:
`nanosPerFrame = 1,000,000,000 / fps`. Since 10⁹ doesn't divide evenly by
any of our rates, there's a sub-nanosecond rounding error per frame:

| FPS | nanos/frame | drift/frame |
|---|---|---|
| 30  | 33,333,333  | 0.33 ns |
| 60  | 16,666,666  | 0.67 ns |
| 120 | 8,333,333   | 0.33 ns |

Naive additive wait (`nextDeadline += budget`) accumulates this error. We
don't do that. Instead, every iteration computes the deadline
**absolutely** from a fixed origin:

```java
final long startNanos = timePort.nanos();
for (int tic = 0; running; tic++) {
    long deadlineNanos = startNanos + ((long) tic * nanosPerTic);
    long waitNanos = deadlineNanos - timePort.nanos();
    if (waitNanos > 0) waitNanos(waitNanos);
    publishTickEvent(tic, nanosPerTic);
}
```

Two machines running this code at the same time reach the same deadline
at the same tic — required for P2P lockstep determinism. See
`core/FrameRate.java` for the full derivation and overflow analysis.

Reference: Glenn Fiedler, "Fix Your Timestep" — https://gafferongames.com/post/fix_your_timestep/
