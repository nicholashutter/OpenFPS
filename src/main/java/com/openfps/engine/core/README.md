# Core — Engine Entry, Event Bus, and Worker Pool

> The engine is an **event queue processor**. All subsystem interactions
> go through events. A pool of dedicated worker threads consumes from
> the queue and dispatches to subsystems.

## What lives here

```
core/
├── EngineMain.java           — bootstrap: wire memory, HAL, bus, pool, subsystems
├── GameLoop.java             — D_ event producer (35 Hz tic events + shutdown)
├── EngineState.java          — engine-level state enum
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
│   └── ThreadPoolFactory.java
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
  |  GameLoop  |  (single thread, 35 Hz)
  +-----+------+
        |  produces TickEvent every 28.5ms
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
  +---------------+
  | UNINITIALIZED |  --init()--->  READY  --shutdown()--->  SHUTDOWN (terminal)
  +---------------+                    |
       |                                +-- (init fails) --->  ERROR  --reset()--->  UNINITIALIZED
       +--reset()---> (re-init)
```

The state tracks the subsystem's LIFECYCLE, not per-event state.
Per-event "is the worker busy?" is implicit: the worker thread is
released the moment `onEvent()` returns.

Multiple workers can dispatch events to the SAME subsystem in parallel.
The subsystem's `onEvent()` must be thread-safe.

## Threading

The default thread layout is:
- **N worker threads** (N = `logicalProcessorCount / 2`, min 1) — pre-started, hot
- **1 GameLoop thread** — produces events at 35 Hz
- **1 main thread** — blocks on `loopThread.join()`

Total: 1 + 1 + N threads. For a 16-core machine, N = 8, so 10 threads total.

The pool size comes from `I_SystemInfoPort.logicalProcessorCount() / 2`.
This is the "half as many dedicated threads as the hardware has" rule
the user asked for. A Phase 2 desktop adapter can read the TRUE
physical-core count via oshi/JNA.

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

68 tests across the engine:
- 10 SharedEventBus — publish, take, FIFO, blocking, backpressure, drain
- 7 WorkerPool — hot threads, parallel dispatch, recovery, lifecycle
- 10 SubsystemState — transitions, error recovery, thread-safety
- 6 FixedMath — unchanged from earlier
- 43 MemoryPort — unchanged from earlier (covers both backends)

Run with: `.\gradlew.bat test`
