# HAL (I_) — Hardware Abstraction Layer

> Every platform dependency the engine has, named as an interface here and
> satisfied somewhere else. Nothing in `:engine` outside `hal/adapter/` may
> import a platform API — and `hal/adapter/` itself is limited to the JDK, so
> anything needing a device or a native library lives in a platform module.

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING |
| **Phase** | 1.4, 1.5 complete |
| **Tests** | 91 |
| **Registered** | I_ via `HalSubsystem` |
| **Verified** | 2026-07-28 |

**Built.** All nine ports, and three adapter families behind them: `nulladapter`
(every test and `--headless`), `sqlite` (Xerial `SqliteUserProfilePort`) and
`desktop` (`DesktopTimePort`, `DesktopDatagramPort`), selected by `HalBackend` /
`AdapterFactorySelector`. Window and input are built too — `GdxWindowPort` and
`GdxInputPort` in the `:desktop` module. `DesktopAdapterFactory` returning
`NullWindowPort` and `NullInputPort` is the design, not a gap: `:engine` stays
platform-free and `:desktop` decorates it (§ "Where the window and input actually
live").

**Not built.** Nothing outstanding for these phases. There is no `mobile/`
adapter family; `I_UserProfilePort` names `hal.adapter.mobile` as where a Room
implementation will go, not as somewhere that exists.

**Blocked on.** Nothing.

**Next step.** Android — the second platform is what proves the port set, and it
is the only named backend with nothing written for it (`PLAN.md` § 6).

## Layout

```
hal/
├── port/                interfaces + one value type — core depends on these
│   ├── I_TimePort.java          monotonic + wall clock
│   ├── I_InputPort.java         latch input once per tic
│   ├── InputState.java          the latched snapshot (immutable value)
│   ├── I_DatagramPort.java      raw UDP
│   ├── I_FilePort.java          open / read / size / close
│   ├── I_SystemInfoPort.java    cores, memory, OS strings
│   ├── I_UserProfilePort.java   profile persistence
│   ├── I_WindowPort.java        the platform window and its frame loop
│   └── I_FrameCallback.java     what the platform calls back into
└── adapter/
    ├── I_AdapterFactory.java    one complete port set for one platform
    ├── HalBackend.java          the closed set of backends: NULL, SQLITE, DESKTOP
    ├── AdapterFactorySelector.java  backend enum → factory; the only construction site
    ├── nulladapter/     everything null / in-memory — every test, and --headless
    ├── sqlite/          real Xerial SQLite user profile on disk
    └── desktop/         real JDK-backed time + UDP; window and input stay null (see below)
```

`InputState` is the one non-interface in `port/` on purpose: it is the value the
input port hands back, and it has no platform in it, so it belongs with the
contract rather than with any adapter.

There is no `mobile/` directory. Android is a stated target (`PLAN.md` § 6) but
nothing has been written for it — `I_UserProfilePort` names `hal.adapter.mobile`
as where its Room implementation will go, not as somewhere that exists.

## Where the window and input actually live

**`hal/adapter/desktop/` contains no window and no input.** It holds
`DesktopAdapterFactory`, `DesktopDatagramPort` and `DesktopTimePort` — a real
`java.nio.channels.DatagramChannel` and a `System.nanoTime()` clock, both pure
JDK 17. `DesktopAdapterFactory` returns `NullWindowPort` and `NullInputPort`,
which is why `HalBackend.DESKTOP` still needs no display and still runs on CI.

The real LWJGL3 window and mouse/keyboard input are `GdxWindowPort` and
`GdxInputPort` in the **separate `:desktop` module** (package
`com.openfps.desktop`), together with `GdxAdapterFactory`, which delegates every
other getter to `DesktopAdapterFactory` and overrides only those two.

That split is the architectural point of this package, not an accident of
layout. `:engine` must stay platform-free — it has to compile and test with no
libGDX, no GLFW and no display — so it cannot depend on `:desktop`. The
dependency therefore runs one way only, and `AdapterFactorySelector` can never
name `GdxAdapterFactory`: the windowed HAL is assembled at the composition root
in `:desktop`, by decorating the headless one.

The rule that falls out of it: **a port interface lives here, an implementation
that needs a device does not.**

## What each port does

| Port | Role | Backends today |
|---|---|---|
| `I_TimePort` | Monotonic nanosecond counter + wall clock | `DesktopTimePort` (`System.nanoTime()`, rebased at `init`), `NullTimePort` (also `System.nanoTime()`; tests that need a controlled clock supply their own fake) |
| `I_InputPort` | Latch keyboard / mouse / gamepad once per tic into an `InputState` | `GdxInputPort` (`:desktop`), `NullInputPort` |
| `I_DatagramPort` | Raw UDP send/receive + per-tic tick | `DesktopDatagramPort` (`java.nio.channels.DatagramChannel`), `NullDatagramPort` |
| `I_FilePort` | Open + read + size + close files | `NullFilePort` — which already reads the real filesystem, so desktop needs nothing more |
| `I_SystemInfoPort` | Logical / physical cores, total + free memory, OS and JVM strings | `NullSystemInfoPort` (answers from `Runtime`) |
| `I_UserProfilePort` | Persist `UserProfile` records; `UNINITIALIZED → READY → SHUTDOWN` | `SqliteUserProfilePort` (Xerial JDBC), `MemoryUserProfilePort` |
| `I_WindowPort` | Create the window, own the frame loop, report close requests | `GdxWindowPort` (`:desktop`), `NullWindowPort` |
| `I_FrameCallback` | What the platform calls back into per frame — **engine** side, not platform side | `EngineFrameCallback` in `core`, handed to the window by `EngineSession` |

`I_FrameCallback` is the one entry here that is not a thing a platform provides:
the engine implements it and the platform calls it. It lives in `port/` because
it is half of the `I_WindowPort` contract, not because an adapter supplies it.

`I_SystemInfoPort` and `I_UserProfilePort` are the two ports the engine queries
outside the tic: `EngineMain` reads `logicalProcessorCount()` to size the worker
pool at startup (`ThreadPoolFactory`), and loads or creates the `UserProfile`
before the session begins.

## The platform owns the loop

`I_WindowPort` and `I_FrameCallback` are one mechanism split across two files,
and they are the whole presentation hook the renderer hangs off.

An earlier `I_WindowPort` exposed `pumpEvents()` and `present()` for the engine
to call in its own while-loop. That is a GLFW shape, and Android cannot satisfy
it: there the framework owns the loop and calls the app when a frame is due —
there is nothing to pump. Faking a pump in the Android adapter would invert
control twice. So `runFrameLoop(I_FrameCallback)` hands the calling thread to
the platform and blocks until the window closes, and the engine supplies the
callback. Desktop implements the loop with a while-loop; Android will implement
it with the Activity lifecycle; neither shape leaks into the other.

Two consequences worth stating plainly:

- **The window is not behind `I_RenderPort`.** R_ is pure math on primitive
  arrays (`render/README.md`), and `RenderSubsystem.onEvent` runs on worker
  threads while a GL context is current on exactly one. Keeping the window on a
  HAL port that only the composition root drives makes the threading correct by
  construction rather than by discipline.
- **`onFrame` draws; it never advances.** The simulation clock stays the
  engine's own `GameLoop` on its own thread at a fixed 30/60/120 Hz, because
  lockstep requires two peers at the same tic to be at the same point in the
  simulation. Platform frame rate is whatever the display decides, and it must
  never drive a tic.

`init`, `create`, `runFrameLoop` and `shutdown` are main-thread only — GLFW
requires it (strictly, on macOS) and Android requires it for the Activity
lifecycle. `I_AdapterFactory.init()` and `shutdown()` inherit the same
constraint and say so.

## Input: sample, then read

`I_InputPort` is two calls on purpose. `sampleInput(tic)` **latches** the
device into one immutable `InputState`; `currentInput()` hands back whatever
was latched and changes nothing. A tic consumes exactly one stable snapshot,
which is what makes it serialisable into a `TicCmd`, replayable, and identical
on every peer under lockstep — none of which is true if gameplay races a live
device.

The two rates involved are unrelated: the platform polls the mouse at vsync,
the simulation latches at a fixed 30/60/120 Hz. A real adapter therefore
**accumulates** relative motion between latches rather than sampling it
instantaneously, so no rotation is dropped when rendering runs slow and none is
counted twice when it runs fast. `GdxInputPort` in `:desktop` splits that into a
platform half (reads `Gdx.input` on the render thread) and an
`InputAccumulator` (no platform imports, latches on the loop thread) so the
bookkeeping is testable headlessly.

`InputState` is what the latch produces: seven device-neutral values —
`forwardAxis`, `strafeAxis`, `yawDelta`, `pitchDelta`, `fire`, `jump`,
`sprint` — so a keyboard, a gamepad and a replay file are indistinguishable
downstream. The axes are a *level* ("the stick is pushed this far"); the look
deltas are an *integral* ("the view turned this much since the last snapshot").
A level can be re-read harmlessly, a delta must be consumed exactly once — which
is the reason `sampleInput` and `currentInput` are two calls and not one.

## How to write a new adapter

1. Pick the right `adapter/<platform>/` directory. Create it if needed — but
   first decide whether it belongs here at all: if it needs a device, a native
   library or a display, it belongs in a platform module, not in `:engine`.
2. `implements I_<Capability>Port` from the matching `port/` interface.
3. Use **only** JDK 17 stdlib APIs (no LWJGL / Android in the engine module).
4. If you need a platform-specific dependency, put it in its own module with its
   own `build.gradle.kts` — as `:desktop` does for libGDX — so the Android
   profile doesn't pull in desktop natives and CI can still build headlessly.
5. Expose it from a factory. Inside `:engine` that means `NullAdapterFactory`
   or `DesktopAdapterFactory`, plus a `HalBackend` value and a branch in
   `AdapterFactorySelector` if it is a whole new backend. Outside `:engine` it
   means decorating an existing factory, the way `GdxAdapterFactory` does.
   Every getter must return non-null — fall back to the matching null adapter —
   so the engine has exactly one code path regardless of backend.
6. Add tests in `src/test/java/com/openfps/engine/hal/adapter/<yourplatform>/`,
   or in the platform module's own test source set.

## Why port-first?

Adapter/port (a.k.a. hexagonal) architecture lets the engine core run:
- On a server with no graphics (`HalBackend.NULL`, or `SQLITE` for a real profile)
- On a desktop with a real window (`:desktop`, `GdxAdapterFactory`)
- On a phone with Android (planned; nothing written yet)
- In a test JVM with fakes (null adapter, plus per-test stubs of any port)

…all from the same compiled core JAR. Adapters are the only things that change.

The 91 tests in this package are largely a check on that claim: they run every
port through its state machine with no display and no peer, which is only
possible because none of those things are named in `port/`.

**Reference:** Alistair Cockburn, "Hexagonal Architecture" (2005):
https://alistair.cockburn.us/hexagonal-architecture/
