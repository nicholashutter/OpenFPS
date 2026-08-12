# Log — Engine Logging Bus and File Sink

> The engine owns its own logging bus. SLF4J is the side door for
> producers; the bus is the spine every consumer (file, debug overlay,
> future telemetry) reads from.

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING (file sink added 2026-08-12) |
| **Phase** | 1.3 bus + Pass 9 file sink |
| **Tests** | 99 — `gradlew :engine:test --tests 'com.openfps.engine.log.*'` |
| **Registered** | none — log bus is a service, not a `Subsystem` |
| **Verified** | 2026-08-12 — 1855 tests pass on `:engine`, checkstyle clean |

**Built.** `I_LogBus` / `RingBufferLogBus` (non-blocking ring, 500 main +
100 per-subsystem), `LogBusFactory` (singleton + drain daemon), per-
subsystem `SubsystemLogBus`, `Slf4jLogBusBridge` (logback → bus),
`LogbackBridgeBootstrap` (auto-wires the bridge at engine start),
`LogEvent`, `LogLevel`, `LogSubscription`, `SubsystemStateLogger` (state-
change observer → bus), **`LogFileSink` + `LogFileFormat` + `LogSinkPaths`
(Pass 9, 2026-08-12)**. Bootstrap wired in `EngineMain.main()` and
`DesktopLauncher.main()`; shutdown wired in `EngineSession.stop()` and
`DesktopLauncher` teardown. Every Gradle run (`gradlew :engine:run`,
`gradlew :desktop:run`, `./run-desktop.ps1`) produces a
`logs/openfps-<timestamp>.log` next to `settings.gradle.kts`
automatically.

**Not built.** Per-subsystem file sinks (one file per channel, e.g.
`logs/engine-render.log`); level-based file routing; rotation
compression; remote log shipping. None of these is queued — the shipped
sink covers the developer-reads-logs-after-the-fact case the player
asked for.

**Blocked on.** Nothing.

**Next step.** Nothing outstanding. The file sink handles the "I want
to read what happened after I closed the window" use case; a future
remote-shipping sink is an additive consumer on the same main bus and
will not need to touch this code.

## Architecture

```
   ┌──────────────────┐  bridge   ┌─────────────┐
   │ SLF4J / logback  │ ─────────▶│  log bus    │
   │ (existing code)  │           │             │   ┌─────────────┐
   └──────────────────┘           │  RingBuffer │──▶│ LogFileSink │
   ┌──────────────────┐ direct    │  LogBus     │   └─────────────┘
   │ Subsystem code   │ ─────────▶│             │──▶ other consumers
   │ (e.g. core.info) │  publish  └─────────────┘
   └──────────────────┘
```

The bus is the join point. SLF4J producers (existing call sites, log
imports, logback `Logger`) flow through `Slf4jLogBusBridge` into the
bus. Native producers (`LogBusFactory.core().publish(event)`) go
straight in. Consumers (`LogFileSink`, debug overlay, future remote
shipping) subscribe to the same bus and see both flows, in the order
they were published.

### Why a custom bus instead of just logback

Logback is the producer-side default; that part is unchanged. The bus
exists for the consumer side: SLF4J has no concept of "every line goes
through one queue that the file sink, the debug overlay, and the
future telemetry sink all subscribe to." A logging path that knows
about its consumers is a logging path that can rate-limit, drop
gracefully under back-pressure, and offer a uniform shape to the file
sink (timestamp, level, source, message, cause) without going through
logback's appender dance.

### Per-subsystem channels

`LogBusFactory` carries a fixed set of named subsystem buses
(`engine.core`, `engine.hal`, `engine.memory`, `engine.gameplay`,
`engine.net`, `engine.audio`, `engine.render`, `engine.demo`,
`engine.map`). `SubsystemLogBus` is a thin wrapper that stamps every
event with its subsystem's `source` string AND immediately forwards
the event to the main bus — that's how a single subscriber on the main
bus sees every line from every subsystem in publish order, without the
drain task having to do anything. **Known issue:** the drain task also
re-reads from `subsystem.recent()` every `DRAIN_INTERVAL_MS`, so a
subsystem publish produces **two** events on the main bus: one from
the immediate forward, one from the drain. The drain task should only
move events that arrived before the immediate-forward path was wired
(a startup window) and is being simplified in a follow-up — see
`LogBusFactory.drainLoop` for the `lastReadIndex` note.

### File sink: async queue, never blocks publish

```
   publish() ─▶ main bus ─▶ LogFileSink.onEvent()
                                │
                                ▼  (non-blocking offer)
                       ┌────────────────┐
                       │ bounded queue  │  Constants.LOG_FILE_QUEUE_CAPACITY (default 4096)
                       └────────────────┘
                                │
                                ▼  (daemon thread)
                          writer.write ─▶ BufferedWriter ─▶ openfps.log
                                                       └─▶ rotate when threshold crossed
```

`LogFileSink` is a bus consumer, not a SLF4J appender. The publish path
is the bus's own non-blocking ring buffer; the sink adds one further
bounded queue and a daemon writer thread. The slowest hot-path cost is
still `RingBufferLogBus.publish`. A burst that overflows the sink's
queue drops events and increments `LogFileSink.droppedCount()` —
silent loss becomes observable.

The daemon thread is owned by the sink itself (AGENTS.md's "no `new
Thread(...)` for event handling; use `WorkerPool`" rule has the same
exception as `LogBusFactory.startDrainTask()`: the file sink must keep
draining its own queue even when the engine is shutting down and the
worker pool is itself draining).

### Rotation

When `currentFileBytes >= Constants.LOG_FILE_ROTATE_BYTES`, the active
file is closed, renamed to `<base>.1.<suffix>`, files `.1` through
`.N-1` are shifted up by one, and a fresh `<base>.<suffix>` is opened.
`Constants.LOG_FILE_KEEP_FILES` controls the history depth. With
`keepFiles == 1`, rotation deletes the active file instead of
preserving history (single-file mode for environments that demand
bounded disk).

### Path resolution

`LogSinkPaths` consults three sources in order; the first non-blank
wins. The literal `"off"` at any layer disables the sink without
removing the bootstrap call.

| Source | Spelling | Example |
|---|---|---|
| System property | `-Dopenfps.log.file=<path>` | `-Dopenfps.log.file=C:/logs/out.log` |
| Environment | `OPENFPS_LOG_FILE` | `OPENFPS_LOG_FILE=/var/log/openfps.log` |
| Default | `<projectRoot>/logs/openfps-<timestamp>.log` | `C:/.../OpenFPS/logs/openfps-20260812-141830.log` |

The default walks up from `user.dir` looking for `settings.gradle.kts`,
then writes into a sibling `logs/` directory there. A Gradle
`JavaExec` task from a subproject lands in the right place
automatically; the pattern matches `BuildAudit.resolveLogDir()`.

## File layout

```
log/
├── I_LogBus.java              — port: publish + subscribe + recent + dropped + close
├── RingBufferLogBus.java      — non-blocking ring with per-bus dropped counter
├── SubsystemLogBus.java       — stamps source, forwards to main bus
├── LogBusFactory.java         — singleton buses, drain task, file-sink install/close
├── LogEvent.java              — immutable record (timestamp, source, logger, level, msg, cause)
├── LogLevel.java              — TRACE / DEBUG / INFO / WARN / ERROR with rank()
├── LogSubscription.java       — closeable handle returned by I_LogBus.subscribe
├── SubsystemStateLogger.java  — observer: SubsystemStateChange → bus event
├── Slf4jLogBusBridge.java     — logback appender: every SLF4J event → bus event
├── LogbackBridgeBootstrap.java — programmatic initContext hook
├── LogSinkPaths.java          — three-source path resolution (sysprop / env / default)
├── LogFileFormat.java         — one LogEvent → "[ts] LEVEL source logger - msg\n..."
└── LogFileSink.java           — async queue + daemon writer + rotation
```

## How to use the file sink

The sink is installed automatically by `EngineMain.main()` and
`DesktopLauncher.main()`. A developer running `gradlew :engine:run`,
`gradlew :desktop:run`, or `./run-desktop.ps1` gets a
`logs/openfps-<timestamp>.log` next to `settings.gradle.kts` with no
extra arguments.

### Disable the file sink

```bash
# One-off via the JVM command line:
gradlew :desktop:run -Dopenfps.log.file=off

# Or via environment for a CI box:
OPENFPS_LOG_FILE=off gradlew :desktop:run
```

The console log is unaffected. Setting the file sink off does not stop
the log bus from running or any other consumer (debug overlay, future
telemetry) from receiving events.

### Pin the file path

```bash
# Absolute path — useful when running outside Gradle:
gradlew :desktop:run -Dopenfps.log.file=C:/logs/openfps.log

# Relative — resolved against user.dir (the subproject's cwd):
gradlew :engine:run -Dopenfps.log.file=build/logs/out.log
```

### Read the file during a long-running debug session

The file is plain UTF-8. `grep '^\[' openfps-<date>.log` matches only
header lines (stack frames are indented); `grep ERROR` pulls every
error. The active file is `openfps-<timestamp>.log`; rotated history
is `openfps.<N>.<timestamp>.log` for `N ∈ [1, LOG_FILE_KEEP_FILES)`.

### Test what your changes log

Run `:engine:test --tests 'com.openfps.engine.log.*'` for the bus
contracts; `LogFileSinkTest` covers the round-trip, rotation,
overflow-drop, and close-drain behaviour directly. To assert on a log
line in your own subsystem's test, use `LogBusFactory.main().recent(N)`
to grab the latest N events without waiting for the file sink.

## Knobs (in `Constants`)

| Constant | Default | What it does |
|---|---|---|
| `LOG_FILE_ROTATE_BYTES` | 5 MB | Active file size at which rotation fires |
| `LOG_FILE_KEEP_FILES` | 3 | History depth; 1 means delete-on-rotate |
| `LOG_FILE_QUEUE_CAPACITY` | 4096 | Async queue depth; overflow drops events |

All three are `Constants` entries per `STYLE.md` § 13.2 — no magic
numbers anywhere in `LogFileSink`.

## Conventions

- **Producers** use SLF4J (`LoggerFactory.getLogger(MyClass.class)`).
  Do not instantiate `LogEvent` directly except for the bridge and the
  state-change observer.
- **Consumers** subscribe to `LogBusFactory.main()`. A future remote
  sink would be one more consumer on the same main bus.
- **Source strings** are the names in `LogBusFactory.SUBSYSTEM_NAMES`
  (`engine.core`, `engine.gameplay`, ...). The bridge derives source
  from the SLF4J logger name (`com.openfps.engine.X.Y` → `engine.X`).
- **Levels** follow SLF4J semantics: TRACE / DEBUG / INFO / WARN /
  ERROR. The file sink defaults to INFO; raise to TRACE via
  `-Dopenfps.log.level=TRACE` on the JVM.
- **No `System.out` / `System.err` in engine code.** Logback → bus →
  sink. The console appender remains so the developer still sees the
  line in their terminal.