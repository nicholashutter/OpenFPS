# WebAssembly — can OpenFPS run in a browser?

> Feasibility assessment. What a browser target would cost, which of the
> existing ports survive it, which three things in the architecture it breaks,
> and whether it is worth doing yet.
>
> **Nothing here has been built.** There is no `wasm`, `teavm`, `gwt`, `j2cl` or
> `cheerpj` string anywhere in the source or the build — the only hits repo-wide
> are two rows in [`net/README.md`](../engine/src/main/java/com/openfps/engine/net/README.md)
> (`:627`, `:642`) rejecting WebRTC as a *desktop* transport. This document
> exists so the question is answered once, with numbers, rather than
> re-litigated.

## Status

| Field | Value |
|---|---|
| **State** | ASSESSMENT ONLY — no code, no build wiring, no spike |
| **Assessed against** | `main` @ `3041af0`, 2026-07-30 |
| **Supersedes** | the 2026-07-29 assessment written at `cfca483`; **47 commits and 33,858 inserted lines out of date**. § 1 lists every conclusion that moved |
| **Measured on this pass** | asset payload (raw + gzip), bytecode size, JDK import surface, test counts, guard coverage |
| **Not measured** | the Wasm penalty on the span loop. § 12 |

**Verdict, up front: single-player is now a genuinely small job and
browser-to-desktop multiplayer is still impossible.** Three things landed since
the last assessment that each removed a named blocker — `RenderMode` made the
resolution lever real and defaulted it to 480p, gzip measurement showed the
asset payload is 422 KB rather than 12.6 MB, and `GamepadSource` turned the
controller into a one-interface platform seam. Two blockers did not move: the
tic clock is still a blocking thread, and `StrictMath` still stops meaning
fdlibm the moment it crosses a Java-to-Wasm compiler. § 11 is the
recommendation.

---

## 1. What changed since the last assessment

The previous version of this file was written at `cfca483` on 2026-07-29.
Forty-seven commits landed after it. Every row below is a conclusion in that
document that is now wrong, or a fact it could not have known.

| Then (2026-07-29) | Now (2026-07-30) | Where |
|---|---|---|
| "Resolution is a lever" — an argument, with no code | **`RenderMode` exists and defaults to 480p on every platform.** `P480` / `P720` / `NATIVE`, short edge as a *ceiling*, GPU-blitted back up through the existing fullscreen quad | `gdxshared/src/main/java/com/openfps/gdx/RenderMode.java:101-124`, `:130`; `RenderSettings.java:46` |
| "The nine demo models are 12,626,532 bytes… the single largest lever on browser download size" | **Wrong on both counts.** The runtime set is fifteen models, 21,045,428 bytes raw — and **422,158 bytes gzipped, 49.9×.** Download size is a non-issue; *heap* is the constraint | measured, § 9 |
| `StrictMath.sin`, `cos` **and `pow`** are load-bearing | **There is no `StrictMath.pow` in the engine.** The fdlibm surface is exactly `sin`, `cos`, `atan2` — three functions | grep of `engine/src/main`, § 6 |
| "`TicCmdEncoder` uses `StrictMath` on the float-to-wire quantisation — the wire format itself could disagree" | **`TicCmdEncoder` touches `StrictMath.PI` only** (`:72`, `:86`), a constant, not a call. The wire format is safe | `engine/src/main/java/com/openfps/engine/net/TicCmdEncoder.java:72,86` |
| Two constant-pool guards (`PlayerControllerTest`, `HitscanTest`) | **Six guard classes covering twelve production classes**, including the new `PhysicsWorld` and `BotRng` | § 6 |
| "Remote players are not yet simulated into visible bodies… the sequencing argument expires the moment this is true" | **It is true.** `RemotePlayers` runs one `PlayerController` per peer against the shared `PhysicsWorld` and publishes seven bodies plus carbines. **The sequencing argument in the old § 8 has expired** | `engine/src/main/java/com/openfps/engine/demo/RemotePlayers.java:82`, `:206-260`, `:305-324`, `:459-491` |
| No physics | **`PhysicsWorld` landed** — 524 lines, flat `float[]` of AABBs, swept per-axis slide, **no `Math` and no `StrictMath` at all**, and under the strictest constant-pool guard in the repo | `engine/src/main/java/com/openfps/engine/gameplay/PhysicsWorld.java`, `PhysicsWorldTest.java:718-730` |
| No audio | **Five sounds, four synth classes, zero audio assets on disk.** 22050 Hz / 16-bit / mono PCM, hand-written 44-byte RIFF header | `engine/src/main/java/com/openfps/engine/audio/synth/`, `WavAudio.java:47-56` |
| No controller | **`GamepadSource` — a seven-method platform interface in `:desktop`** with `GlfwGamepad` behind it, plus radial dead-zone maths shared in `:gdxshared` | `desktop/src/main/java/com/openfps/desktop/GamepadSource.java:34`; `gdxshared/src/main/java/com/openfps/gdx/AnalogStick.java:64` |
| "1524-test suite" | **2,206 `@Test` methods + 26 parameterized across 126 test classes; 2,339 executed** | measured; `README.md:8` |
| Nothing said about Android's datagram port | **`AndroidAdapterFactory` delegates to `HalBackend.NULL`, so Android already ships `NullDatagramPort`.** A platform with no UDP is not a new shape for this engine — it is the shipping one | `android/src/main/java/com/openfps/android/AndroidAdapterFactory.java:244-246`; `engine/.../nulladapter/NullDatagramPort.java:18` |
| `WorkerPool` "723 lines" | 722, and structurally unchanged. The claim survives | `engine/src/main/java/com/openfps/engine/core/pool/WorkerPool.java` |

**What did not change, at all:** the tic clock is still `new Thread(loop,
"openfps-gameloop")` (`EngineMain.java:408`); `GameLoop` still burns the tail of
each wait in `Thread.onSpinWait()` (`GameLoop.java:110`); the bus still blocks
in `take()` (`SharedEventBus.java:123`); bilinear filtering is still
unconditional with no toggle (`TextureSampler.java:135`, `:156`; and
`render/README.md:23-27` still lists it as not built); and `DesktopDatagramPort`
is still the only real socket.

**Two READMEs are themselves stale and should not be trusted over the code.**
`engine/src/main/java/com/openfps/engine/hal/README.md:27-34` still says "there
is no `mobile/` adapter family… nothing has been written for [Android]" — the
whole `:android` module contradicts it. `render/README.md` (Verified
2026-07-28) still says an internal render-resolution cap "was written for it but
never landed"; `RenderMode` landed.

---

## 2. What a browser target actually has to satisfy

The three-layer split is why this question is answerable. `:engine` is pure JDK
17 with no platform dependency; `:gdxshared` depends on libGDX **core** and no
backend; `:desktop` and `:android` each own exactly one backend
([`gdxshared/README.md`](../gdxshared/README.md), "Why this module exists"). A
browser target is a fourth backend, and on paper it is the same shape as the
third.

### 2.1 The JDK surface, enumerated

This is the input to every toolchain question in § 3, so it is measured rather
than characterised. Every `java.*` import in `engine/src/main` — 149 files,
40,104 lines — is one of these thirty-nine classes:

| Package | Classes | Where it lands |
|---|---|---|
| `java.util` | `ArrayList`, `Arrays`, `EnumMap`, `HashMap`, `LinkedHashMap`, `List`, `Locale`, `Map`, `Objects`, `Optional`, `UUID` | Everywhere. Every toolchain has these |
| `java.util.concurrent.atomic` | `AtomicBoolean`, `AtomicInteger`, `AtomicLong`, `AtomicReference` | `WorkerPool` (all four), `EventFactory`, `NullAudioPort` |
| `java.util.concurrent.locks` | `Condition`, `LockSupport`, `ReentrantLock` | `WorkerPool`; `ReentrantLock` also in `SoftwareRenderPort:9` and `DemoGameplayPort:8` |
| `java.util.concurrent` | `LinkedBlockingQueue`, `TimeUnit` | `SharedEventBus:56`, `WorkerPool` |
| `java.io` | `File`, `FileInputStream`, `FileNotFoundException`, `IOException` | `NullFilePort`, `WadFilePort`, `DirectoryModelSource` |
| `java.nio` | `ByteBuffer`, `ByteOrder` | `ModelFormat:462` (little-endian model parse), `DesktopDatagramPort:79-80` |
| `java.nio.channels` | **`DatagramChannel`** | `DesktopDatagramPort:83` — **the one class with no browser equivalent** |
| `java.net` | `InetSocketAddress`, `SocketAddress` | `DesktopDatagramPort` only |
| `java.nio.file` | `Files`, `Path`, `InvalidPathException` | `DemoModels`, `DirectoryModelSource`, `WadReader` |
| `java.nio.charset` | `StandardCharsets` | constant-pool reads and logging |
| `java.sql` | `Connection`, `DriverManager`, `PreparedStatement`, `ResultSet`, `SQLException`, `Statement` | **`SqliteUserProfilePort` only** — already excluded on Android |

That is a small, boring surface. There is no `java.lang.reflect`, no
`java.time`, no streams API in the engine, no `ExecutorService`, no `Selector`,
no `javax.sound`. Three lines of it are load-bearing for portability:
`DatagramChannel`, `java.sql`, and the eight `java.util.concurrent` types.

### 2.2 Three things the architecture already did for a browser

**The platform owns the loop.** `I_WindowPort.runFrameLoop(I_FrameCallback)`
(`engine/src/main/java/com/openfps/engine/hal/port/I_WindowPort.java:67`) hands
the calling thread to the platform and the engine supplies the callback. The
argument in [`hal/README.md`](../engine/src/main/java/com/openfps/engine/hal/README.md)
transfers to `requestAnimationFrame` without a word changed.

**`start()` already returns, and Android proves it.**
`AndroidWindowPort.runFrameLoop` (`android/src/main/java/com/openfps/android/AndroidWindowPort.java:113`)
calls `application.initialize(...)` at `:127` and **returns immediately** —
blocking inside `onCreate` would trip the ANR watchdog. `EngineSession`
isolates the blocking in `awaitPlatformLoop()`
(`engine/src/main/java/com/openfps/engine/core/EngineSession.java:135-149`). A
browser build takes the Android path verbatim.

**A no-network platform already ships.** `AndroidAdapterFactory` delegates to
`HalBackend.NULL` (`android/.../AndroidAdapterFactory.java:244-246`), which
hands out `NullDatagramPort` — `receive()` returns `null`, `send()` logs and
drops (`engine/.../nulladapter/NullDatagramPort.java:25`, `:31`). The engine
runs single-player on a platform with no socket today, on a device in someone's
hand. § 7's conclusion is therefore about *multiplayer*, not about booting.

### 2.3 And two things it did not

**The tic clock is a blocking thread.** `new Thread(loop, "openfps-gameloop")`
(`EngineMain.java:408`), feeding a `LinkedBlockingQueue` whose `take()` blocks
(`SharedEventBus.java:123`), with `GameLoop` burning the sub-millisecond tail of
each wait in `Thread.onSpinWait()` (`GameLoop.java:108-111`). A browser gives
one thread of control and two ways to be called back into it, and **nothing in
either may block or spin**. § 5.

**`StrictMath` is a source-level promise the toolchain gets to break.** § 6.

---

## 3. The routes, and what each one is not

Five live options and one that is not. None of them is "run the JAR".

### 3.1 TeaVM — the only realistic candidate

TeaVM is an ahead-of-time compiler from Java bytecode to JavaScript or
WebAssembly. Since 0.11 it emits **WasmGC** — the WebAssembly variant with
first-class garbage-collected reference types — so object lifetime is delegated
to the browser's own collector rather than shipping a GC inside the module.
0.15.0 is the current release; it raised TeaVM's own minimum to Java 17, and
0.13 added Java 25 language support. That matches
`sourceCompatibility = JavaVersion.VERSION_17` in every module's
`build.gradle.kts` (`engine:41`, `gdxshared:77`, `desktop:46`, `android:145`),
and no `java { toolchain { … } }` block exists anywhere in the build to conflict
with it.

**What TeaVM is not**: a JVM. It ships *its own partial reimplementation of the
Java class library*. Anything the engine calls that TeaVM has not reimplemented
fails to link. Checked against § 2.1's inventory:

- **`java.nio.channels.DatagramChannel` does not exist and cannot.** There is no
  UDP in a browser at any layer. `DesktopDatagramPort` is unportable — not
  "hard", *absent*. § 7.
- **`java.sql` is out**, so `SqliteUserProfilePort` is out. This costs nothing:
  `:android` already excludes `org.xerial:sqlite-jdbc` from both `:engine` and
  `:gdxshared` (`android/build.gradle.kts:260-261`, `:271-272`) and substitutes
  Room. Logback is excluded on the same two lines and replaced by
  `uk.uuid.slf4j:slf4j-android` (`:281`). **The exclusion machinery already
  exists in a shipping module**, which is the strongest single piece of evidence
  that the dependency surface is portable.
- **`java.util.concurrent` links but does not parallelise.** § 5.
- **`StrictMath` is not fdlibm.** § 6, and it is the most important subsection in
  this document.
- **`java.nio.ByteBuffer` is supported**, which matters more than it looks:
  `ModelFormat.java:462` parses every `.ofm` through
  `ByteBuffer.wrap(data).order(LITTLE_ENDIAN)` and `:586`
  `buffer.asIntBuffer().get(out)`. The renderer's hot path uses none of it —
  `Framebuffer` is plain `int[]`/`float[]` and says so at `Framebuffer.java:837-840`.

**libGDX on TeaVM already exists.** `gdx-teavm` (Apache-2.0) is actively
maintained — its most recent component releases are dated May 2026 — and its
current configuration examples target libGDX **1.14.2**, the exact version
`gdxshared/build.gradle.kts:49` pins. It emits both JavaScript and Wasm. Since
`:gdxshared` is libGDX core and no backend, `FramebufferPresenter`, the block
welcome screen, the UI state machine, `GdxAudioPort`, `InputAccumulator`,
`RenderMode` and `AnalogStick` are on paper already compatible. This is the
reason the route is TeaVM and not anything else.

### 3.2 CheerpJ — the interesting wrong shape

CheerpJ is a real OpenJDK compiled to WebAssembly: the JVM core is C++ built to
Wasm, and its JIT emits JavaScript the browser then compiles. It runs
**unmodified** bytecode, and 4.3 ships Java 8, 11 **and 17** — so unlike every
other route in this table it would take `:engine` as-is, with a real
`java.lang`, a real `StrictMath` and real threads.

It is still the wrong tool here, for three compounding reasons:

1. **Networking is the same wall, plus a VPN.** Browser security prevents UDP
   and TCP outright; CheerpJ's answer for general networking is a
   Tailscale-based WireGuard tunnel over WebSockets. That is § 7's problem with
   an operational dependency bolted on, and it contradicts
   [`net/README.md`](../engine/src/main/java/com/openfps/engine/net/README.md)`:698`
   — "**Verdict: v1 is LAN-only, stated plainly**" — in a way no adapter can
   paper over.
2. **It is a JVM, so it pays JVM startup.** Leaning Technologies' own framing of
   the 5.0 roadmap is that enterprise customers would rather have "a large
   application starting in twenty seconds instead of thirty" than newer language
   versions. A game that takes ten seconds to reach a menu has lost the only
   argument for being in a browser.
3. **Licence.** CheerpJ is commercial software — free for FOSS projects,
   personal projects and one-person companies; everyone else needs a licence.
   This repository is MIT and [`docs/ASSETS.md`](ASSETS.md) § 3 sets a
   deliberately conservative bar for anything redistributed alongside the
   binary. A runtime whose terms depend on who is shipping it is a policy
   question, and it has not been asked.

CheerpJ is the right answer for putting an existing unmodifiable Swing
application on the web. It is the wrong answer for a game engine whose whole
performance model is a hot inner loop.

### 3.3 GWT / libGDX's HTML backend — dead on Java 17

libGDX's traditional HTML5 backend compiles Java to JavaScript through GWT. Its
documented limitations were decisive before WasmGC existed: no threads at all,
no reflection beyond a hand-declared allow-list, no `java.net`. GWT is Java
8-era in its language support and `:engine` is Java 17 throughout. That last
point alone ends it. Listed for completeness, not as an option.

### 3.4 J2CL — rejected on one line of its own documentation

J2CL is Google's Java-to-Closure-JavaScript transpiler. It emulates a
substantial JRE subset, deliberately omits `java.net.*`, and does not emulate a
VM, so threading is unavailable. Any of those would be survivable. This is not:

> "J2CL doesn't emulate 32 bit floating point arithmetic for performance
> reasons. Instead JavaScript `number` is used."

**Every simulation value in this engine is a `float`.** `PlayerController` holds
position and velocity in `float`; `PhysicsWorld` is a flat `float[]` of AABBs
compared with `<` and `>` (`PhysicsWorld.java:145`, `:383`); `InputState`
carries `float` axes; `TicCmd` quantises `float` to 16-bit wire fields. Widening
all of it to `double` changes every rounding decision in the sim, so a J2CL peer
and a desktop peer would diverge on tic one — and unlike § 6's problem, this one
is not confined to transcendentals. J2CL is not a candidate for a lockstep
engine that computes in `float`.

### 3.5 JWebAssembly — not a candidate

JWebAssembly compiles Java bytecode to WebAssembly directly. Its own roadmap
still lists exception handling, threads, garbage collection and reflection as
desired future features. A codebase built on `LinkedBlockingQueue`,
`ReentrantLock`, `LockSupport`, four `Atomic*` types and a 722-line worker pool
is not a plausible first customer. Recorded so the evaluation is not repeated.

### 3.6 "Rewrite in another language"

There is no route that skips a Java-to-Wasm compiler; WasmGC gives a compiler a
place to put objects, it does not read class files. The honest alternative is a
rewrite — Kotlin/Wasm, Rust, C++ — and it should be priced as one: **40,104
lines of `:engine`, 2,206 test methods, and twelve classes whose compiled
constant pools are asserted on.** The tests are the part that does not port.
Every determinism guard in § 6 is a JVM-bytecode assertion; in another language
they would have to be re-derived from scratch against a different toolchain's
guarantees, and until they were, nothing would be watching the property the
whole lockstep design rests on. A rewrite is not a port and is out of scope for
this document.

### 3.7 Summary

| Route | JDK 17 language + libs | Parallelism | Sockets | `float` is `float` | `StrictMath` reproducible | Verdict |
|---|---|---|---|---|---|---|
| **TeaVM (WasmGC)** | 17 bytecode in; **partial** class library | Coroutines only | None; WebRTC/WebTransport via JS interop | Yes | **No** — delegates to host `Math` | **The route** |
| CheerpJ 4.3 | Real OpenJDK 8/11/**17** | Real threads over a Wasm JVM | Only via Tailscale tunnel | Yes | Yes | Wrong shape; licence unresolved |
| GWT / libGDX HTML | Java 8 era | None | None | Yes | n/a | Dead on Java 17 |
| J2CL | Modern Java, no `java.net` | None | None | **No — `float` becomes `double`** | n/a | **Dead on determinism** |
| JWebAssembly | Incomplete | Roadmap item | Roadmap item | n/a | n/a | Not a candidate |
| Rewrite | n/a | Whatever you pick | Whatever you pick | Your problem | Your problem | Not a port. § 3.6 |

---

## 4. The HAL is the asset

Every port and its browser equivalent. Difficulty is relative effort for a
working implementation, not for a good one. Port list verified against
`engine/src/main/java/com/openfps/engine/hal/port/` at HEAD.

| Port | Browser equivalent | Difficulty | Notes |
|---|---|---|---|
| `I_SystemInfoPort` | `navigator.hardwareConcurrency`, constants | **Trivial** | Report 1 logical processor; `ThreadPoolFactory.java:137-138` computes `max(1, logical − 1)` and `MINIMUM_WORKERS = 1` at `:91` already handles it |
| `I_TimePort` | `performance.now()` | **Trivial** | Three methods (`millis`, `nanos`, `epochMillis`). Note browsers coarsen timer resolution when not cross-origin isolated |
| `I_AudioPort` | Web Audio, via libGDX core | **Trivial** | § 8. `GdxAudioPort` already lives in `:gdxshared` and every sound is arithmetic |
| `I_FrameCallback` | — | **None** | Engine-side. Six methods, already exactly the `requestAnimationFrame` + visibility-change shape |
| `I_WindowPort` | Canvas + `requestAnimationFrame` | **Easy** | `runFrameLoop` registers and returns, exactly as `AndroidWindowPort.java:113` does. `isRealWindow()` is true |
| `I_InputPort` | Pointer Lock, `KeyboardEvent`, Gamepad API, Touch | **Easy** | § 9. Five methods; `InputAccumulator` does the hard part and imports nothing platform-specific |
| `GamepadSource` (not a HAL port) | Gamepad API | **Easy** | § 9. Ten methods, poll-shaped — and the Gamepad API is poll-shaped |
| `ModelSource` (not a HAL port) | `fetch` into an in-memory map | **Easy** | § 10. `ApkModelSource` is the precedent |
| `I_FilePort` | Ship `NullFilePort` | **None** | It has **zero consumers**. Every factory hands out the same instance and nothing in gameplay, render or resource calls it — W_ goes through `I_WadPort` and Android reads assets through `ApkModelSource` |
| `I_WadPort` | Ship `NullWadPort` | **None** | No WAD is loaded by the demo |
| `I_UserProfilePort` | `localStorage` | **Easy** | § 4.1 |
| `I_DatagramPort` | **Nothing.** WebRTC/WebTransport, and only with a server | **Hard — architectural** | § 7 |
| *(not a port)* Tic clock | Host timer callback | **Hard — a `core` change** | § 5.4 |
| *(not a port)* Determinism | Pure-Java fdlibm `sin`/`cos`/`atan2` | **Hard — and load-bearing** | § 6 |

Read down that column: **ten of thirteen rows are trivial, none or easy, and
every hard row is hard for a reason that has nothing to do with the HAL.** The
port set does what [`hal/README.md`](../engine/src/main/java/com/openfps/engine/hal/README.md)
claims for it. The rows that resist are the two places where the browser removes
a *capability* rather than changing an *API*, plus one place where the engine's
own threading model is the obstacle. That is the correct outcome for a hexagonal
architecture meeting a poorer platform.

### 4.1 Persistence, which got easier

`I_UserProfilePort` is nine methods —
`engine/src/main/java/com/openfps/engine/hal/port/I_UserProfilePort.java:45-93`
— and there are now **three** backends, not two: `SqliteUserProfilePort`
(`java.sql`), `RoomUserProfilePort` (AndroidX Room), and `MemoryUserProfilePort`
(a `LinkedHashMap`, `engine/.../nulladapter/MemoryUserProfilePort.java:29`).
Neither database survives a browser, but the third one does not need to.

The port's synchrony is the only difficulty, because IndexedDB is asynchronous.
**`localStorage` is synchronous by specification, so the port signature is
satisfied with no changes at all** — origin-scoped, capped near 5 MB, and a
`UserProfile` is a handful of fields. Alternatively, `MemoryUserProfilePort`
plus an async prefetch in `init()` and a fire-and-forget write in `shutdown()`
is the whole implementation, and `MemoryUserProfilePort` already exists and is
tested. Both preserve the port. Neither requires a signature change.

Worth noting what does *not* need persisting: neither `RenderSettings` nor
`AccessibilitySettings` nor `DebugSettings` is stored today, by explicit
decision (`RenderMode.java:85-97`).

---

## 5. Threading

### 5.1 There is no parallelism, on any route

Browser Wasm threading means `SharedArrayBuffer` plus Web Workers, which
requires the page to be cross-origin isolated with `Cross-Origin-Opener-Policy:
same-origin` and `Cross-Origin-Embedder-Policy: require-corp` — the
`SharedArrayBuffer` constructor is hidden on the global object otherwise. That
is a deployment constraint, not a code constraint, and it is survivable (it also
restores fine-grained `performance.now()`, which the browser otherwise coarsens).

The constraint that is not survivable is upstream of it: **WasmGC has no
threads.** The `shared-everything-threads` proposal exists precisely because
there is no way to share reference values across threads in WasmGC, and as of
mid-2026 it remains a draft — the repository is still iterating on shared
annotations, thread-local globals and managed waiter queues, with no shipped
browser implementation. TeaVM 0.13's answer was *coroutines*: `Thread.start`,
`Thread.sleep` and simple synchronisation primitives implemented as suspendable
green threads on one OS thread. Cooperative multitasking, not parallelism.

So the browser build is **single-threaded**, and no header configuration changes
that.

### 5.2 What `WorkerPool` actually does, and why none of it survives

`engine/src/main/java/com/openfps/engine/core/pool/WorkerPool.java`, 722 lines.
It is not a thin wrapper — it is the thing a browser cannot host:

- **Threads.** `new Thread(w, "openfps-worker-" + i)` at `:186`, daemon at
  `:187`, started at `:189`. `ThreadPoolFactory.java:137-138` sizes it
  `max(1, logical − 1)`.
- **Claiming is a CAS on a packed `AtomicLong`**, not an index counter:
  `claimState` packs generation in the high 32 bits and next-unclaimed index in
  the low 32, and `runAvailable()` CASes `snapshot + 1L` at `:621`. The
  generation exists to defeat ABA (`:561-571`).
- **The join is spin → yield → park.** `Thread.onSpinWait()` for
  `JOIN_SPIN_LIMIT = 256` (`:78`, `:670`), `Thread.yield()` for
  `JOIN_YIELD_LIMIT = 4096` (`:100`, `:675`), then
  `LockSupport.parkNanos(50_000L)` (`:103`, `:685`).
- **Idle workers block**: `idleCondition.await(IDLE_PARK_MILLIS, …)` at `:544`,
  and at most one worker sits in `bus.take()` at `:504` under a
  `leaderPresent` flag (`:124`).
- **`Thread.sleep(10)`** in `awaitTermination` at `:251`.

Under TeaVM coroutines every one of those is either a suspend point that
round-robins or — in the `onSpinWait` and `yield` cases — an unyielding loop
that hangs the tab. `JOIN_YIELD_LIMIT`'s Javadoc at `:80-99` is itself a
post-mortem on a 15.6 ms Windows timer period; the browser version of that
mistake is a frozen page.

### 5.3 What that costs the rasterizer — much less than it should

The invariant does the work. **The serial path is not a degraded mode; it is the
reference implementation, and it is already the one under test.**

- `SoftwareRenderPort`'s constructor accepts a null `I_ThreadPoolPort`
  (`SoftwareRenderPort.java:777-780`, Javadoc at `:770-772`: "the serial
  reference path").
- `dispatch` runs the identical index sequence inline when the pool is null
  (`SoftwareRenderPort.java:2197-2204`). The same fork exists in
  `Rasterizer.dispatch:1090` and `OutlinePass.draw:516-524`.
- `chunkCountFor` returns 1 for a null pool (`SoftwareRenderPort.java:820-827`).
- `activePool()` (`:2216-2224`) *already* degrades a live pool to null whenever
  the pool is not `RUNNING`, so the serial path runs in production on every
  teardown frame today.

Bit-identity is pinned by four test classes across the worker ladder
`{1, 2, 3, 4, 8}` plus the machine's resolved count:

| Test | File:line | Counts | Covers |
|---|---|---|---|
| `shouldMatchSingleThreadedOutputAtEveryWorkerCount` | `RasterizerTest.java:592` | 1,2,3,4,8,resolved | colour **and** depth |
| `shouldMatchSingleThreadedOutputAtARaggedResolution` | `RasterizerTest.java:608` | same | 70×50, `stride != width` |
| `shouldRenderTaggedScenesIdenticallyAtEveryWorkerCount` | `SoftwareRenderPortTest.java:886` | 1,2,3,4,8 | frame **and entity-id buffer** |
| `shouldMatchTheSerialFrameAtEveryWorkerCount` | `OutlinePassTest.java:886` | 1,2,3,4,8,resolved | outline pass |
| `shouldNotShiftTheFrame…` | `SoftwareRenderPortCullTest.java:226` | 1,2,3,8 | frustum culling |

A browser backend passes `null` and gets a renderer that is not merely correct
but **provably identical to the one desktop ships**, with no new code path.
`Framebuffer.STRIDE_ALIGNMENT = 16` (`:114`) and `DEFAULT_TILE_SIZE = 64`
(`:122`) become pointless rather than wrong — they exist to prevent false
sharing between workers, and there are no workers.

**One real gap.** `SoftwareRenderPortTranslucentTest` never constructs a pool —
every translucency test is serial-only, so the extra `renderPass` per coverage
run (`SoftwareRenderPort.java:1843-1872`) has no pooled-equals-serial
assertion. That is a hole in the desktop invariant, not in the browser story
(the browser is the serial side), but it should be closed before anyone leans on
"bit-identical across every worker count" as an unqualified claim.

**The general lesson, worth stating because it was not the reason the invariant
was adopted:** an architecture that can prove its parallel result equals its
serial result can be moved to a platform with no parallelism for free.

### 5.4 The frame budget — and this is where `RenderMode` changed the answer

[`docs/ASSETS.md`](ASSETS.md) § 2's worker sweep, Intel Core Ultra 7 155H, p50
over 270 timed frames at 1280×720 on the 295-instance demo room
(`docs/ASSETS.md:128-131`):

| workers | 0 (serial) | 1 | 2 | 4 | 8 | 16 |
|---|---|---|---|---|---|---|
| p50 frame | **26.1 ms** | 25.1 | 16.3 | 8.5 | 5.2 | 4.0 |

The old assessment started from 26.1 ms and argued that 640×360 *would* be a
lever if someone built one. Someone did.

**`RenderMode.DEFAULT` is `P480` on every platform** (`RenderMode.java:130`),
and it is a ceiling on the short edge, applied to the framebuffer while the GPU
blit still fills the surface (`RenderMode.java:29-33`). On the desktop's 1280×720
window that is 853×480 — 0.41 Mpx against 0.92, **44.5% of the pixels**. Scaling
the serial column by pixel count gives **~11.6 ms**, and that is a ceiling
rather than an estimate, because `docs/ASSETS.md:133-137` measures per-pixel cost
*rising* with resolution as the working set leaves cache (4.33 ns/px at 720p,
5.31 at 1440p), so the effect runs the other way at 480p.

Against that, the two budgets:

| Budget | Headroom over ~11.6 ms serial fill | Wasm penalty it can absorb |
|---|---|---|
| 60 Hz (16.7 ms) | 5.1 ms, before simulation or presentation | **None** |
| 30 Hz (33.3 ms) | 21.7 ms | ~1.8× on fill alone |

`FrameRate` is a closed enum with `FPS_30` in it
(`engine/src/main/java/com/openfps/engine/core/FrameRate.java:78`), so 30 Hz
costs nothing to select.

**The remaining lever is still unbuilt, and it is still the biggest one.**
`render/README.md:1153-1154`: "**Bilinear filtering is 2.9× the entire rest of
the inner loop** (21.4 → 7.3 ns/px with nearest)." `TextureSampler` exposes only
bilinear entry points (`:135`, `:156`) and `SpanRenderer.java:613` calls
`sampleLevel` with no branch. Nearest at 480p would put serial fill near **4 ms**
and make 60 Hz genuinely reachable with a 2–3× Wasm penalty. **The browser
target is what makes the bilinear toggle load-bearing rather than polish**, and
`render/README.md`'s own "Next step" already wants it for 1080p60 on the desktop.

The size of the Wasm penalty is **unmeasured and this document will not guess
it**. TeaVM's maintainer characterises WasmGC output as better than the JS
backend but not dramatically so, at roughly 5× the binary size. What that means
for a bounds-check-and-array-store-heavy loop over three primitive arrays is
exactly the sort of thing this repository has twice discovered by measuring
rather than reasoning (`docs/ASSETS.md:62-66`'s benchmark correction;
`render/README.md:761-763`'s 15.6 ms timer defect). Measure it; do not model it.

### 5.5 The loop, which is the part that needs a design decision

The engine's clock cannot survive contact with a browser unchanged. `GameLoop`
spins on `Thread.onSpinWait()` to hit its deadline (`GameLoop.java:110`) and
sleeps for the coarse part (`:192`); the bus blocks in `take()`
(`SharedEventBus.java:123`); the pool's join spins, yields and then parks.

The fix is not to make waiting cheaper. It is to stop waiting:

> The drift-correction math survives unchanged. What has to go is the *waiting*,
> not the *timing*.

`GameLoop.java:100` computes each deadline absolutely from a fixed origin —
`startNanos + (tic * nanosPerTic)` — rather than by accumulation, precisely so
two machines agree. That formula works identically as an accumulator: a host
callback asks "how many tics are now due?", publishes exactly that many, and
returns. No thread, no queue, no park.

But it collides head-on with a rule `hal/README.md` states in bold: "**`onFrame`
draws; it never advances.** … Platform frame rate is whatever the display
decides, and it must never drive a tic." The rule is right, and lockstep depends
on it. The browser-compatible reading is that the *simulation pump* and the
*draw* are two host callbacks — `setInterval`/`setTimeout` for tics,
`requestAnimationFrame` for frames — which preserves the separation the rule
protects while removing the thread it currently relies on.

Two complications the old assessment did not name. First, `GameLoop.java:130-135`
publishes `TickEvent` **and** `RenderFrameEvent` from the same loop iteration,
deliberately unthrottled — so splitting the pump also means deciding which of
the two callbacks owns the render event. Second, background tabs throttle timers
to roughly one callback per second, so the accumulator must be clamped or a
returning tab will try to publish several hundred tics in one turn.

That is a change to `core`, not to an adapter, and against 1,529 engine tests.
It is the honest cost of this port.

---

## 6. Determinism — the finding that decides the multiplayer question

This is the part that would be easiest to get confidently wrong, so it is stated
with its evidence.

[`PLAN.md`](../PLAN.md) § 4 records the deviation: simulation state is `float`,
which is safe because JEP 306 makes all Java 17 floating-point arithmetic
FP-strict IEEE 754, so `+ − × ÷` and `sqrt` are bit-reproducible on every
conforming JVM. The hazard is the transcendentals, which `Math` is permitted 1–2
ulp of error on and is explicitly not required to reproduce between
implementations. `StrictMath` is fdlibm-defined and is.

### 6.1 The fdlibm surface is three functions, not five

Measured, not assumed. Grepping `StrictMath.` across `engine/src/main`:

| Method | Call sites | Reproducible under Wasm without help? |
|---|---|---|
| `sin` | `PlayerController.java:683,833,834,847`; `Match.java:1214,1215`; `BotPattern.java:79,81,102` | **No** |
| `cos` | `PlayerController.java:684,832,835,848`; `Match.java:1213,1216`; `BotPattern.java:107` | **No** |
| `atan2` | `Bot.java:419`; `Match.java:1265` | **No** |
| `sqrt` | `PlayerController.java:249,678`; `Match.java:1232` | Yes — IEEE-754 requires correct rounding; Java and Wasm both honour it |
| `floor` | `PlayerController.java:704` (yaw wrap) | Yes — exact |
| `abs` | `Hitscan.java:272` | Yes — exact. Used via `StrictMath` only to satisfy the flat no-`java/lang/Math` rule; the comment at `:270` says so |
| `hypot` | `RemotePlayers.java:389` | Composite of exact ops in fdlibm, but not required to be — treat as unsafe |
| **`pow`** | **none** | — |
| **`tan`, `exp`, `log`** | **none** | — |

**There is no `StrictMath.pow` in the engine.** The previous assessment listed
it as load-bearing; it is not, and neither is `tan`. The real exposure is
**`sin`, `cos`, `atan2`** — three fdlibm functions, in five classes.

`PhysicsWorld` — the newest simulation code — uses **neither** `Math` nor
`StrictMath`: only `<`, `>`, `+`, `-` on `float` (`PhysicsWorld.java:258`,
`:315`, `:383`). Collision is portable by construction. `BotRng` is a stateless
SplitMix64 finaliser over `(seed, tic, entityId, channel)`
(`BotRng.java:283-292`) — integer arithmetic, no clock, no `java.util.Random`,
and pinned as order-independent by `BotRngTest.java:79`. Randomness is portable
too.

### 6.2 The guard is now six classes and twelve production types

Each reads the compiled `.class` resource as ISO-8859-1 and asserts on the
constant pool:

| Guard | File | Guards |
|---|---|---|
| `PlayerControllerTest.Determinism` | `gameplay/PlayerControllerTest.java:1518,1538,1551` | `PlayerController`, `I_PlayerInput` |
| `HitscanTest` | `gameplay/HitscanTest.java:750,769,779,786` | `Hitscan`, `Target`, `HitResult` — plus a `HashMap`/`HashSet` ban |
| `BotTest.Determinism` | `gameplay/BotTest.java:741,765` | `Bot`, `BotPattern` |
| `BotRngTest.Guards` | `gameplay/BotRngTest.java:353,367,383,399` | `BotRng`, `Match`, `BotSkill` — plus `Random`, `nanoTime`, `currentTimeMillis` bans |
| `PhysicsWorldTest` | `gameplay/PhysicsWorldTest.java:718` | `PhysicsWorld` — absolute: no `sqrt`, no `abs`, no `min` |
| `TicCmdEncoderTest.Determinism` | `net/TicCmdEncoderTest.java:303` | `TicCmdEncoder` |

**Unguarded and worth knowing:** `InputState` calls `(float) Math.sqrt(...)` at
`hal/port/InputState.java:127` to normalise the forward/strafe pair, and
`InputState` is what `PlayerController.update` consumes. It is safe in practice —
`Math.sqrt` is JLS-mandated correctly rounded, the same argument
`Vec3.java:132` makes — but no test asserts anything about its compiled class.
That is the one hole in an otherwise flat rule, and it is a hole on the *input
side of the simulation*.

### 6.3 TeaVM breaks this, and the guard cannot see it break

TeaVM's class library reimplements `StrictMath` as a pure delegation layer:
`TStrictMath.sin` is `return TMath.sin(a);`, and so are `cos`, `tan`, `pow`,
`sqrt` and `atan2`. `TMath.sin` in turn is:

```java
@GeneratedBy(MathNativeGenerator.class)
@Import(module = "teavmMath", name = "sin")
@Unmanaged
public static native double sin(double a);
```

— bound to the host environment's `Math`. So under TeaVM, `StrictMath.sin`
**is** JavaScript `Math.sin`. ECMA-262 only *recommends* fdlibm for the
transcendentals and does not require it.

Three consequences, in increasing order of how expensive they are to discover:

1. `StrictMath.sqrt`, `floor` and `abs` remain safe. The `sqrt` calls in
   `PlayerController`, `Match` and `Bot` are fine, and `PhysicsWorld` calls
   nothing.
2. `sin`, `cos` and `atan2` are not, in five classes.
3. **The constant-pool tests still pass.** They prove the *source* names
   `StrictMath`; they cannot prove the *runtime* is fdlibm, because they read a
   class file compiled by `javac` and never see what TeaVM substitutes
   downstream. Six green guard classes, none of which is testing anything on
   this platform. That is precisely the failure mode `PlayerControllerTest`'s own
   comment warns about — sub-micron per step, invisible for minutes, impossible
   to reproduce in a single-process test — arriving through a door the guard was
   not built to watch.

### 6.4 The fix, and it is not a workaround

Port fdlibm's `sin`, `cos` and `atan2` into `common/` as pure Java over
`+ − × ÷` only, and have simulation code call *that* rather than `StrictMath`.
Those four operators are exactly the ones JEP 306 guarantees on the JVM and that
Wasm's `f64` instructions guarantee in a browser, so a hand-rolled fdlibm is
bit-identical on all three platforms by construction. It is a few hundred lines
of well-specified numerical code, and it is testable the ordinary way: the
desktop JVM's own `StrictMath` is the oracle, over the full domain.

Three functions rather than the five the previous assessment scoped, and the
guard machinery to enforce the new rule already exists — the six classes in
§ 6.2 change from banning `java/lang/Math` to banning `java/lang/StrictMath`
too, which is a one-line edit per assertion.

It also **improves the desktop and Android builds**: today the fdlibm guarantee
is inherited from the JDK, and `PLAN.md` § 4's argument silently assumes every
peer runs a conforming JVM. Owning the implementation removes that assumption.

One legal note, because [`docs/ASSETS.md`](ASSETS.md) § 8 makes this exact class
of mistake a named trap: **do not copy OpenJDK's `java.lang.FdLibm`.** OpenJDK is
GPL-with-classpath-exception and this repository is MIT. The original Sun fdlibm
C sources carry a permissive notice, and § 8's own distinction applies — reading
a published specification or a permissively-licensed reference is fine;
vendoring GPL source is not. **Verify the fdlibm notice before writing a line of
it**; this document asserts the distinction, not the conclusion.

### 6.5 Can a browser peer stay in lockstep with a desktop peer?

**No, twice over.** The arithmetic fails until § 6.4 is done, and even then the
transport fails independently (§ 7). A browser cannot send a UDP datagram to a
desktop peer under any circumstances.

---

## 7. Networking

### 7.1 What exists

`I_DatagramPort`
(`engine/src/main/java/com/openfps/engine/hal/port/I_DatagramPort.java:21`) is
seven methods: `send(byte[], String):29`, `receive():37`, `bind(int):44`,
`close():49`, `processTic(int):56`, `init():61`, `shutdown():66`. The only real
implementation is `DesktopDatagramPort` — a non-blocking `DatagramChannel`
(`:83-84`) with one preallocated direct `ByteBuffer` per direction (`:79-80`).
`processTic` is empty (`:174-178`): the socket is non-blocking and `NetSession`
drains it until `receive()` returns null (`NetSession.java:424-434`).

**The net package owns no thread and imports no `java.util.concurrent` at all.**
`NetSession` "belongs to the game loop thread" (`NetSession.java:63-64`), as do
`PeerConnection:40`, `AckWindow:47` and `TicCmdBuffer:39`. The whole packet path
is driven synchronously from the tic via `DemoGameplayPort.exchangeNetwork`
(`:837-851`). For a single-threaded target this is close to ideal.

The wire format is hand-packed big-endian through one package-private class,
`NetBytes` (`net/NetBytes.java:18`, "the one place the net package converts
between primitives and bytes, so the byte order cannot drift"). A packet is a
20-byte header — `playerId`, `latestTic`, `ackTic`, `ackBitfield`
(`RedundantSender.java:66-69`) — followed by ascending 12-byte `TicCmd`s
(`TicCmd.java:45`), capped at `MAX_DATAGRAM_BYTES = 1200`
(`RedundantSender.java:64`) to stay under Ethernet MTU. **No `ByteBuffer`
anywhere in the format**; `ByteBuffer` appears only as socket scratch inside
`DesktopDatagramPort`.

### 7.2 What a browser can put underneath it

| Substitute | Delivery model | Cost |
|---|---|---|
| WebSocket | Reliable, ordered — TCP | Reintroduces exactly the head-of-line blocking `net/README.md:417` rules out ("Linux `TCP_RTO_MIN` is 200 ms… **12+ frozen tics at 60 Hz**"). **Wrong** |
| `RTCDataChannel`, `{ordered: false, maxRetransmits: 0}` | **Unordered, unreliable messages** — genuinely UDP-shaped, DTLS by default | Needs signalling and usually STUN. Peer-to-peer |
| **WebTransport datagrams** | **Unreliable, unordered, over HTTP/3** | Reached Baseline in March 2026 (Safari 26.4 completing Chrome/Edge/Firefox). **Client-to-server only** — it is not peer-to-peer |

Two details make the delivery model fit better than expected. The
redundant-redelivery design needs no ordering and no retransmission
(`net/README.md:439`: "**The resolution is redundancy, not retransmission**"),
which is exactly what `maxRetransmits: 0` provides. And `receive()` already
drops the source address — the packet's own header carries `playerId` at offset
0 (`RedundantSender.java:66`) — so a per-peer channel rather than one
demultiplexed socket costs nothing. `bind(int)` becomes meaningless and returns
silently; everything above the port is unchanged. **`WebRtcDatagramPort` is a
genuinely small class.**

### 7.3 What it costs is the premise, not the code

`net/README.md:678-698` grades NAT traversal T0 (LAN UDP broadcast, zero deps),
T1 (STUN + hole punching, ~30 LOC, "**fails on symmetric NAT and CGNAT**"), T2
(TURN relay — "**Servers, ops, money**"). **A browser peer cannot do T0 at all**:
there is no UDP broadcast, no LAN discovery, and no way to learn a peer exists
without a server that already knows about both. So a browser target starts at
T1-plus-signalling and cannot fall back.

WebTransport does not rescue this. Being client-to-server, it *is* T2 by
construction — it does not traverse NAT, it removes the need to by putting a
server in the middle. Either substitute contradicts `net/README.md:690`'s note
that T2 "**contradicts the opening line of this file**".

Two further facts from the code, neither of which the old assessment recorded:

- **Discovery does not exist yet in any form.** `net/README.md:735-736` lists it
  as unwritten; peers come from a `--peer=` argument on the command line
  (`desktop/src/main/java/com/openfps/desktop/NetArgs.java:37`). So there is no
  T0 implementation for a browser to fail to inherit.
- **`PeerConnection.recordRttSample(long)` (`:146`) is never called from
  production code** — only from tests. There is no timestamp field in the 20-byte
  header, so RTT is not measurable with the shipped format, and
  `redundancyWindow` (`:246-256`) is driven entirely by the ack gap. Anyone
  adding a browser transport with a very different RTT profile should know that
  the window will not adapt to it.

**Honest scope for a browser build: single-player, or browser-to-browser over
WebRTC with a signalling service.** Browser-to-desktop is impossible.

---

## 8. Audio — the port that is already done

This is the most favourable finding in the document, and it is favourable for a
structural reason rather than a lucky one.

**Nothing is decoded, because nothing is a file.** There is no `.wav`, `.ogg`,
`.mp3`, `.m4a` or `.flac` anywhere in the repository. Five `SoundId` values
(`audio/port/SoundId.java:53`) are produced by four synthesis classes:

| `SoundId` | Synth | Shape |
|---|---|---|
| `WEAPON_FIRE` | `BlasterSound.java:192` | 900→120 Hz exponential sweep, 180 ms, fundamental + 3rd harmonic |
| `BOT_WEAPON_FIRE` | `CarbineSound.java:249` | LCG white noise (seed `0x1BADB002`, `:162-168`) through a one-pole low-pass, plus a 96 Hz body sine |
| `SUPER_WEAPON_FIRE` | `SuperBlasterSound.java:223` | the blaster's own constants halved (`:92-95`), three partials |
| `SUPER_BLASTER_READY` / `_SPENT` | `PowerChimeSound.java:224`, `:234` | two 90 ms notes at 660 Hz and 660×1.5, rising and falling |

Output is 22050 Hz, 16-bit, mono, wrapped in a hand-written 44-byte RIFF header
(`WavAudio.java:47-56`). The synthesis code's entire JDK dependency is
`java.util.Locale` and one `AtomicLong` in the null adapter — **no
`javax.sound`, no `AudioFormat`, no `SourceDataLine`, no `ByteBuffer`.** Just
`Math.sin`/`pow`/`exp` over `short[]`, deliberately *not* `StrictMath`, because
audio is not lockstep (`BlasterSound.java:56`).

Why that matters for a browser, in order of weight:

1. **No decoder to port and no codec licensing question.** The single hardest
   part of audio on a new platform is usually the decode path.
2. **No bytes to download.** § 10's payload contains no audio at all.
3. **The port already tolerates a missing device.** `GdxAudioPort` degrades when
   `Gdx.audio == null` (`gdxshared/.../GdxAudioPort.java:272`) and deliberately
   does not latch the failed attempt — which is exactly the shape of a browser's
   autoplay policy, where the audio context is suspended until the first user
   gesture. **A browser build gets "silent until the player clicks" for free.**
4. **`preload()` already exists and already has an Android caller**
   (`AndroidLauncher.java:606`), added because `SoundPool.load` is async and
   dropped the first three shots. A browser wants the same hook for the same
   reason.

The only browser-specific work is the `Audio.newSound(FileHandle)` seam:
`GdxAudioPort.stage` (`:315`) writes the baked WAV to a temp file because libGDX
core takes a `FileHandle`. In a browser that becomes a `Blob`/data URL or a
direct `AudioBuffer` — a change inside one method of one `:gdxshared` class.

**Verified favourable. This row is genuinely trivial.**

---

## 9. Input

Every browser input API maps onto something that already exists, and one of them
maps onto an interface written last week for an unrelated reason.

| Browser API | Maps to | Evidence |
|---|---|---|
| Pointer Lock + `mousemove` | `InputAccumulator.addYawPixels`/`addPitchPixels` | Mouse deltas are *integral*: summed into `AtomicInteger` (`InputAccumulator.java:190`, `:198`) and drained by `getAndSet(0)` at `latch()` (`:614-615`). `movementX`/`movementY` from a locked pointer are exactly that quantity |
| `KeyboardEvent` | `DesktopBindings`-shaped table | `InputBinding.key(int)` (`InputBinding.java:118`) carries an **opaque** code the engine never interprets. `WebBindings` joins `DesktopBindings` and `AndroidBindings` as a third table |
| **Gamepad API** | **`GamepadSource`** | `desktop/src/main/java/com/openfps/desktop/GamepadSource.java:34` — `poll()`, `isConnected()`, `name()`, `isButtonDown(int)`, `didButtonGoDown(int)`, `isAxisPressed(int)`, `leftStickX/Y`, `rightStickX/Y`. **The Gamepad API is poll-shaped and so is this interface.** A `WebGamepad` reading `navigator.getGamepads()[0]` implements it directly |
| Touch events | `AndroidInputPort`'s per-pointer model | `TouchLayout.java:76` holds all geometry in dp with regions `REGION_MOVE_STICK`/`LOOK`/`FIRE`/`JUMP`/`LEAVE` (`:82-94`); `AndroidInputPort` binds each finger to the region it landed on (`:419`, `:437`, `:467`). Reusable, but it lives in `:android` and would have to be lifted to `:gdxshared` first |

Three properties make this easy rather than merely possible:

**`InputAccumulator` imports nothing platform-specific** and lives in
`:gdxshared` (`gdxshared/src/main/java/com/openfps/gdx/InputAccumulator.java:117`).
It already solves accumulate-and-latch across two unrelated rates: setters run on
the render thread, `latch()` on the game loop thread, with no lock — every field
is individually atomic or volatile (`:108-113`). On a single-threaded browser
those two rates become two callbacks on one thread, which is strictly easier and
strictly safe.

**A gamepad is already a second channel, not a second mode.** Pad readings are
stored beside keyboard/touch in their own fields (`padForwardAxis:254`,
`padYawAxis:266`, `padFireHeld:280`) and *summed* in `latch()` (`:646-653`), and
stick deflection is converted to radians once per tic by
`padYawAxis × GAMEPAD_LOOK_RADIANS_PER_SECOND × ticSeconds` (`:625-626`). Nothing
about that arithmetic is platform-specific. `AnalogStick`
(`gdxshared/.../AnalogStick.java:64`) owns the radial `DEAD_ZONE = 0.20f` (`:79`)
and the response curve, applied once inside the accumulator "so that no backend
can forget them".

**The engine already ships no key defaults.** `GameAction`
(`hal/port/GameAction.java:51`) is nine actions; `InputBinding.Source` (`:41`)
distinguishes key, mouse button, touch region, gamepad button and gamepad axis;
`ActionBindings` (`hal/adapter/ActionBindings.java:54`) allows four bindings per
action and can report `firstUnbound()` (`:196`). A browser table is data, not
code.

**Two small frictions.** `GlfwGamepad` imports `org.lwjgl.glfw.*` directly
(`:8-10`) while `:desktop` declares no `org.lwjgl` coordinate — LWJGL arrives
transitively through `gdx-backend-lwjgl3`. That is fine for desktop and simply
means the browser writes its own `GamepadSource`. And `TouchLayout`/`TouchOverlay`
sit in `:android` despite having no `android.*` imports; a browser touch build
wants them moved to `:gdxshared` first, which is a file move, not a rewrite.

---

## 10. Assets and load time

### 10.1 The abstraction already exists

`ModelSource` has four methods — `has`, `read`, `describe`, `describeRoot` —
with two implementations, `DirectoryModelSource` and `ApkModelSource`
(`android/src/main/java/com/openfps/android/ApkModelSource.java:58`, over
`AssetManager`). `DemoModels.load(ModelSource)`
(`engine/src/main/java/com/openfps/engine/demo/DemoModels.java`) takes the
source rather than a `Path`, and its Javadoc pins the constraint that makes a
browser implementation trivial: loading is "Called once at startup, from one
thread, never per frame."

So a browser implementation `fetch`es every `.ofm` into an in-memory map
*before* the engine boots, and `read` becomes a map lookup. That is what libGDX's
own preloader does. `DemoModels` needs no change.

### 10.2 The numbers, measured

Measured from the staged tree on this pass. **The runtime set is fifteen models,
not nine** — the previous assessment predates the seven character models and the
bot carbine.

| | Files | Raw bytes | gzip (per file, `Optimal`) |
|---|---|---|---|
| **What `DemoModels` actually loads** — 7 kit + 1 weapon + 7 characters | 15 | **21,045,428** | **422,158** |
| Whole `assets/models` `.ofm` set | 28 | 37,920,852 | 762,552 |
| `generated-room.ofm` (no-art fallback) | 1 | 47,448 | 1,347 |
| Whole `assets/models` tree | 524 | 56,944,803 | — |

Per-file gzip is **49.9×** on the runtime set. That is not an artefact: a
`.ofm` is 35% zero bytes (measured on `level/wall.ofm`), and every model carries
a private copy of the same 512² Kenney atlas plus its mip pyramid — flat colour
regions that deflate to almost nothing.

**This overturns the previous assessment's § 7 entirely.** It named
`docs/ASSETS.md` § 9's "one atlas per model, not per pack" as "**the single
largest lever on browser download size**, worth more than the choice of
compiler". It is not. HTTP already applies `Content-Encoding: gzip` per response,
and the payload after it is **422 KB** — smaller than the compiled bytecode.
`DemoModels.java`'s own comment that seven characters cost "9.8 MB" is talking
about bytes on disk and in heap, and is right about both.

The lever that survives is **heap, not bandwidth**: 21 MB of decoded model data
sits in the tab for the whole session, and `docs/MEMORY.md:198-201` records
`21,203,936 bytes` of `int[]` mip chains alive at runtime — plus the
framebuffer's 12 bytes per padded pixel (`Framebuffer.java:935-939`), which at
480p is about 5 MB rather than NATIVE's 10.4. A shared-texture section in
`ModelFormat` would collapse fifteen atlases to one and take ~20 MB out of the
tab; that is worth doing, but for memory pressure on low-end mobile browsers,
not for download time.

### 10.3 What the browser actually downloads

| Component | Bytes | Note |
|---|---|---|
| `:engine` compiled classes | 574,651 | 175 `.class` files, last build |
| `:gdxshared` compiled classes | 118,755 | 26 `.class` files |
| libGDX core jar | 2,236,597 | `gdx-1.14.2.jar`; heavily tree-shaken by TeaVM |
| Model payload, gzipped | 422,158 | § 10.2 |
| Audio payload | **0** | § 8 |

Runtime Wasm module size is **unmeasured**. WasmGC output is reported at roughly
5× the equivalent JavaScript for the same source, and 2.9 MB of bytecode is a
substantial input even after tree-shaking. Anyone quoting a number before
building it is guessing. What can be said is that **the assets are no longer the
long pole** — the compiler output is.

The staging pipeline needs little: `:tools:regenerateDemoAssets` already emits a
flat directory of `/`-separated relative paths, which is exactly what a web
server serves. What it would gain is a **manifest** — paths plus byte lengths,
so the preloader can show progress — and that is a few lines in `DemoAssetsMain`.
Android's `stageModelAssets` is the precedent.

---

## 11. Effort, and the recommendation

### 11.1 Per-item estimate

Ranges, not commitments, in person-days. They assume one person already fluent
in this codebase and exclude the learning curve on TeaVM itself.

| Item | Estimate | Confidence | Why |
|---|---|---|---|
| Gradle wiring, `gdx-teavm`, exclusions for logback + sqlite-jdbc | 2–3 d | **High** | `android/build.gradle.kts:259-272` is the template, line for line |
| `WebWindowPort` (canvas + `requestAnimationFrame`) | 2 d | **High** | `AndroidWindowPort.java:113` is the shape |
| `WebInputPort` + `WebBindings` + `WebGamepad` | 2–3 d | **High** | `InputAccumulator` and `AnalogStick` are done and headlessly tested; `GamepadSource` is ten methods and no decisions |
| `WebTimePort`, `WebSystemInfoPort` | 0.5 d | **High** | Three and eight methods respectively |
| `FetchModelSource` + a staging manifest | 2 d | **High** | `ApkModelSource` is the precedent |
| `WebUserProfilePort` over `localStorage` | 1 d | **High** | Signature already synchronous |
| Audio: replace the `FileHandle` staging seam | 1 d | **High** | One method, `GdxAudioPort.stage:315` |
| Collapsing `GameLoop` onto host callbacks without breaking drift correction or the "onFrame never advances" rule | **5–10 d** | **Low** | A `core` change against 1,529 engine tests, plus the two complications in § 5.5 |
| Chasing whatever TeaVM's partial class library does not have | **3–10 d** | **Very low** | Unknowable without trying. § 2.1 bounds it: 39 classes, and only `java.sql` and `DatagramChannel` are known-absent |
| Bilinear/nearest quality toggle | 2 d | **High** | Already a planned item (`render/README.md` Next step); needed for 60 Hz |
| **Subtotal — playable single-player demo** | **~4–7 weeks** | | |
| Pure-Java fdlibm `sin`/`cos`/`atan2` in `common/` + oracle tests + guard updates | 5–10 d | **Moderate** | Three functions, not five. Desktop `StrictMath` is the oracle |
| `WebRtcDatagramPort` behind `I_DatagramPort` | 4–6 d | **Moderate** | Seven methods; `bind` and `processTic` are no-ops |
| Signalling server, STUN, and the lobby that implies | **Unbounded** | | It is a service, not a task |
| **Multiplayer, browser-to-browser** | **+3 weeks and an operational commitment** | | |
| **Multiplayer, browser-to-desktop** | **Impossible** | | § 7 |

### 11.2 Verdict

**Viable for single-player, worth doing sooner than it was, and permanently
capped below the desktop build.**

Three of the previous assessment's caveats dissolved on their own. `RenderMode`
turned "resolution is a lever" into shipped code with 480p as the default on
every platform, so the browser inherits the frame budget rather than arguing for
it. Measuring the assets showed the payload is 422 KB gzipped, not 12.6 MB, so
the download argument was wrong by 30×. And `GamepadSource` — written for GLFW,
with no thought of a browser — happens to be exactly the shape of the Gamepad
API. The HAL keeps paying out: ten of thirteen rows in § 4 are easy or free, and
two of the three that are not are hard because the browser lacks a *capability*.

Two caveats did not dissolve and are not going to.

The tic clock is still a blocking thread with a spin-wait in it, and unpicking
that is a `core` change, not an adapter — § 5.5 now names two complications the
last pass missed. And `StrictMath` still stops meaning fdlibm the moment it
crosses TeaVM, silently, past six green guard classes that cannot see it. The
second is smaller than it was — three functions, not five, and `PhysicsWorld`
and `BotRng` are portable by construction — but it is still the thing that
decides whether a browser peer can ever share a wire with anything.

**Worth starting now, and the sequencing argument that blocked it has
expired.** The last assessment said "not worth starting today" because remote
players were not simulated into visible bodies. `RemotePlayers` shipped on
2026-07-30 (`01ec3ee`, "The other player finally has a body"). The reason to
wait is gone.

What replaces it is a narrower recommendation: **build the bilinear toggle
first** — it is wanted anyway for desktop 1080p60, it is two days, and it is what
decides whether the browser target is 480p30 or 480p60. Then run the spike in
§ 11.3.

### 11.3 The smallest meaningful proof of concept

Not a port. **A measurement**, and it should take about three days:

> Compile `SoftwareRenderPort` with a `null` pool and `generated-room.ofm`'s
> geometry through TeaVM to WasmGC. Render 300 frames at 853×480 — `RenderMode.P480`
> on a 16:9 surface, the actual default — into a canvas via
> `Framebuffer.copyColorTo` and a `putImageData`. Report the p50 against the
> 11.6 ms serial-native figure in § 5.4.

It is the smallest thing that answers the only question this document cannot:
**how much slower is the inner loop in a browser?** Every other risk here is
known and bounded — the clock rewrite is understood, fdlibm is understood, the
absence of UDP is understood. The Wasm penalty on a bounds-checked loop over
three primitive arrays is not, and it decides everything downstream: if it is
1.5×, 480p60 is real; if it is 3×, the target is 480p30; if it is 5×, the
conversation changes.

It needs almost nothing from the rest of the port — no window port, no input, no
persistence, no networking, no `GameLoop`. The generated fallback room is 47 KB
raw, **1.3 KB gzipped**, and needs no asset pipeline at all. It deliberately
measures the one thing that cannot be reasoned about, which is the same
discipline `docs/ASSETS.md:62-66` applied when its estimates turned out
optimistic by 2–3× and `render/README.md:761-763` applied when a timed park cost
15×.

---

## 12. What this document could not verify

Stated so nobody treats an inference as a measurement.

- **The Wasm performance penalty on this rasterizer.** Unmeasured, and no
  published benchmark is close enough to this workload to substitute. § 11.3
  exists to fix this.
- **Runtime Wasm module size.** § 10.3 gives the bytecode input, not the output.
- **Whether `gdx-teavm` actually carries `:gdxshared`.** The module depends on
  libGDX core only, which is the precondition, but `Pixmap`, `Texture`,
  `SpriteBatch`, `BitmapFont` and Scene2D have not been tried under it.
- **TeaVM release dates.** 0.15.0 is the current release and 0.13 introduced
  WasmGC coroutines; the dates returned by automated fetch were internally
  inconsistent and are not reproduced here. Re-check before any work starts.
- **TeaVM's exact `StrictMath` behaviour at whichever version is used.** The
  delegation chain (`TStrictMath` → `TMath` → `@Import("teavmMath")`) was read
  from the class library sources on `master` and quoted verbatim in § 6.3. It is
  a design decision rather than an oversight, but § 6's conclusion is important
  enough to be re-read against the version actually used.
- **The fdlibm licence.** § 6.4 asserts that the original Sun sources are
  permissive and that OpenJDK's Java port is not usable here. The first half was
  not verified against the notice text.
- **`localStorage` behaviour under private browsing and storage pressure.**
  Browsers evict; the desktop build has never had a profile disappear.
- **Whether the demo is playable at all on a touch-only browser.**
  `TouchLayout`/`TouchOverlay` are reusable in principle but have never run
  outside `:android`.

---

## 13. Revisit triggers

- **`shared-everything-threads` ships in two browser engines.** That would
  restore the tile-parallel model and change § 5.4 outright — the renderer needs
  no changes to take advantage, because the pooled path is the one that already
  exists.
- **The bilinear/nearest toggle lands.** § 5.4's 60 Hz row changes from "no
  headroom" to "2–3× of headroom", which is the difference between a demo and a
  target.
- **`GameLoop` is refactored onto an accumulator for any other reason.** The
  single largest low-confidence line item in § 11.1 disappears.
- **`ModelFormat` gains a shared-texture section** (`docs/ASSETS.md` § 9). Not a
  download win any more — a ~20 MB heap win, which is what a mobile browser tab
  actually runs out of.
- **A signalling service becomes acceptable** (`net/README.md:690`'s T2 line is
  reopened). Browser-to-browser multiplayer becomes a three-week job rather than
  an unbounded one. Browser-to-desktop stays impossible.
- **A browser build is wanted for reach rather than for play** — a demo link in
  a README, not a platform. Single-player at 480p satisfies that today, and none
  of § 6 or § 7 is on the critical path for it.
