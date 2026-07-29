# WebAssembly — can OpenFPS run in a browser?

> Feasibility assessment. What a browser target would cost, which of the
> existing ports survive it, which two things in the architecture it breaks,
> and whether it is worth doing yet.
>
> **Nothing here has been built.** There is no `wasm`, `TeaVM` or `GWT` string
> anywhere in this repository, and no work has started. This document exists so
> the question is answered once, with numbers, rather than re-litigated.
>
> **Verdict, up front: viable, but not yet, and not as a peer.** A browser build
> of the *single-player* demo is a genuine two-to-three week job on top of one
> unbuilt renderer feature. A browser peer that stays in lockstep with a desktop
> peer is blocked on two separate things — floating-point transcendentals and
> the absence of UDP — and neither is adapter work. § 8 is the recommendation.

---

## 1. What a browser target actually has to satisfy

The three-layer split is the reason this question is answerable at all.
`:engine` is pure JDK 17 with no platform dependency; `:gdxshared` depends on
libGDX **core** and no backend; `:desktop` and `:android` each own exactly one
backend ([`gdxshared/README.md`](../gdxshared/README.md), "Why this module
exists"). A browser target is a fourth backend, and on paper it is the same
shape as the third.

Two things the architecture already did for a browser, before anyone asked:

**The platform owns the loop.** `I_WindowPort.runFrameLoop(I_FrameCallback)`
hands the calling thread to the platform and the engine supplies the callback.
[`hal/README.md`](../engine/src/main/java/com/openfps/engine/hal/README.md)
argues that inversion from Android — "there the framework owns the loop and
calls the app when a frame is due — there is nothing to pump" — and the argument
transfers to `requestAnimationFrame` verbatim, without a word changed.

**`start()` already returns.** `EngineSession.start()` brings the engine up and
hands back a session without blocking; blocking is isolated in
`awaitPlatformLoop()`, and `frameCallback()` exposes the callback directly so
Android can register it and return from `onCreate`
(`engine/src/main/java/com/openfps/engine/core/EngineSession.java`, and
[`android/README.md`](../android/README.md) § "`onCreate` starts the session and
returns"). A browser build takes the Android path and never calls
`awaitPlatformLoop()`. That split was written to avoid an ANR; it happens to be
the only shape a browser can host.

**And one thing it did not.** The engine's tic clock is a dedicated thread —
`new Thread(loop, "openfps-gameloop")` in
`engine/src/main/java/com/openfps/engine/core/EngineMain.java` — feeding a
`LinkedBlockingQueue` whose `take()` blocks
(`core/eventbus/SharedEventBus.java`), and `GameLoop` burns the tail of each
frame's wait in a `Thread.onSpinWait()` loop to hit its deadline exactly
(`core/GameLoop.java`). A browser gives you one thread of control and two ways
to be called back into it, and **nothing in either may block or spin**. § 3
deals with this; it is the first of the two real architectural costs.

---

## 2. The routes, and what each one is not

There are four live options and one that is not. None of them is "run the JAR".

### 2.1 TeaVM — the only realistic candidate

TeaVM is an ahead-of-time compiler from Java bytecode to JavaScript or
WebAssembly. Since 0.11 it emits **WasmGC** — the WebAssembly variant with
first-class garbage-collected reference types — so it delegates object lifetime
to the browser's own collector rather than shipping a GC inside the module.
WasmGC reached baseline browser support (Chrome 119+, Firefox 120+, Safari
18.2+) at the end of 2024, so the target is no longer speculative.

TeaVM requires Java 17+ to *run*, and consumes Java 17 bytecode, which matches
`sourceCompatibility = JavaVersion.VERSION_17` in every module's
`build.gradle.kts`. There is no `java { toolchain { … } }` block anywhere in the
build, so nothing pins a JDK the compiler would have to match.

**What TeaVM is not**: a JVM. It ships *its own partial reimplementation of the
Java class library*, not the JDK's. Anything the engine calls that TeaVM has not
reimplemented simply fails to link. Three consequences land directly on this
codebase:

- **`java.nio.channels.DatagramChannel` does not exist and cannot.** There is no
  UDP in a browser at any layer. `DesktopDatagramPort` is unportable — not
  "hard", *absent*. § 6.
- **`StrictMath` is not fdlibm.** TeaVM's `StrictMath` delegates every method to
  its `Math`, whose `sin`, `cos`, `tan`, `sqrt` and `pow` are `@Import(module =
  "teavmMath", …)` native declarations bound to the host's `Math`. This quietly
  destroys the property the whole determinism guard exists to protect. § 5, and
  it is the most important paragraph in this document.
- **Logback and sqlite-jdbc are both out.** `engine/build.gradle.kts` declares
  `ch.qos.logback:logback-classic:1.5.12` and
  `org.xerial:sqlite-jdbc:3.46.1.0`. The second is not pure Java —
  [`PLAN.md`](../PLAN.md) § 6 records that it bundles natives for ~20 platform
  triplets and is most of the ~15 MB desktop distribution. **`:android` already
  excludes both and substitutes `slf4j-android` and Room**
  ([`android/README.md`](../android/README.md) § "Two dependency decisions that
  are not preferences"). A browser target repeats that substitution exactly, and
  the fact that the exclusion machinery already exists in a shipping module is
  the strongest single evidence that the dependency surface is portable.

**libGDX on TeaVM already exists.** `gdx-teavm` (Apache-2.0) is an actively
maintained backend set whose current releases track libGDX 1.14.2 — the same
version this repo pins in `PLAN.md` § 6 — on TeaVM 0.15.0, and it emits both
JavaScript and Wasm. Since `:gdxshared` is libGDX core and no backend, the
presenter, the block welcome screen, the UI state machine and `GdxAudioPort`
are, on paper, already compatible. This is the reason the route is TeaVM and not
anything else.

### 2.2 CheerpJ — the interesting wrong shape

CheerpJ is a real OpenJDK compiled to WebAssembly: the JVM core (class loading,
reflection, interpreter) is C++ built to Wasm, and its JIT emits JavaScript that
the browser's own JIT then compiles. It runs **unmodified** Java bytecode, and
4.1 onward supports Java 17 (4.3 supports 8, 11 and 17). On the compatibility
axis it is far ahead of TeaVM — a real `java.lang`, a real `StrictMath`, real
reflection.

It is still the wrong tool here, for three compounding reasons:

1. **Networking is the same wall.** CheerpJ's own documentation is explicit that
   browser security prevents UDP and TCP outright and that anything beyond
   same-origin HTTP needs a hosted proxy; its supported answer for `Socket` is a
   Tailscale-based WireGuard tunnel. That is § 6's problem with a VPN bolted on,
   and it contradicts [`net/README.md`](../engine/src/main/java/com/openfps/engine/net/README.md)
   § 9's "v1 is LAN-only, stated plainly" in a way no adapter can paper over.
2. **It is a JVM, so it pays JVM startup.** CheerpJ's own release notes discuss
   getting large-application boot times down from thirty seconds to twenty. A
   game that takes ten seconds to reach a menu has lost the only argument for
   being in a browser at all.
3. **Licence.** CheerpJ is commercial software, free for open-source and
   personal use only. This repository is MIT and
   [`docs/ASSETS.md`](ASSETS.md) § 3 sets a deliberately conservative bar for
   anything redistributed alongside the binary. A runtime whose terms depend on
   who is shipping it is a policy question, not a technical one, and it has not
   been asked.

CheerpJ is the right answer for putting an existing unmodifiable Swing
application on the web. It is the wrong answer for a game engine whose whole
performance model is a hot inner loop.

### 2.3 GWT / libGDX's HTML backend — rejected on threads and `java.nio`

libGDX's traditional HTML5 backend compiles Java to JavaScript through GWT. Its
documented limitations are decisive here and were decisive before WasmGC
existed:

- **No threads at all.** libGDX's own wiki: "JavaScript is inherently
  single-threaded, and as such, one of the limitations of the HTML 5 backend is
  that threading is not possible."
- **No reflection**, beyond a hand-declared allow-list — which `:gdxshared`'s
  Scene2D menu would have to be audited against.
- **No `java.net`**, so sockets do not work at all.
- GWT is Java 8-era in its language support. `:engine` is Java 17 throughout.

That last point alone ends it. This route is listed for completeness, not as an
option.

### 2.4 JWebAssembly — not a candidate

JWebAssembly compiles Java bytecode to WebAssembly directly. Its own roadmap
still lists exception handling, threads, garbage collection and reflection as
*desired future features*. A codebase built on `LinkedBlockingQueue`,
`ReentrantLock`, `AtomicLong` and a 723-line worker pool is not a plausible
first customer. Recorded so the evaluation is not repeated.

### 2.5 Compiling to WasmGC "directly"

There is no route that skips a Java-to-Wasm compiler. WasmGC gives a compiler a
place to put objects; it does not read class files. In practice "the WasmGC
approach for Java" *is* TeaVM (§ 2.1) — or Kotlin/Wasm, which would mean
rewriting the engine in a different language, which is not a port.

### 2.6 Summary

| Route | JDK 17 language + libs | Threads | Sockets | `StrictMath` reproducible | Verdict |
|---|---|---|---|---|---|
| **TeaVM (WasmGC)** | 17 bytecode in; **partial** class library | Coroutines only — no parallelism | None; WebRTC/WebSocket via JS interop | **No** — delegates to host `Math` | **The route** |
| CheerpJ | Real OpenJDK 17 | Real JVM threads over a Wasm JVM | Only via proxy/Tailscale | Yes (real `StrictMath`) | Wrong shape; licence unresolved |
| GWT / libGDX HTML | Java 8 era | None | None (`java.net` absent) | n/a | Dead on Java 17 |
| JWebAssembly | Incomplete | Roadmap item | Roadmap item | n/a | Not a candidate |

---

## 3. Threads — and why the determinism invariant is the good news

### 3.1 There is no parallelism, on any route

Browser Wasm threading means `SharedArrayBuffer` plus Web Workers, which
requires the page to be cross-origin isolated with COOP and COEP headers — the
`SharedArrayBuffer` constructor is hidden on the global object otherwise. That
is a deployment constraint, not a code constraint, and it is survivable.

The constraint that is not survivable is upstream of it: **WasmGC has no
threads**. The `shared-everything-threads` proposal exists precisely because
there is no way to share reference values across threads in WasmGC today, and it
is still a proposal. TeaVM's release notes say so directly — at 0.12, "only one
feature of the JavaScript backend is not supported in Wasm GC: threading", and
what 0.13 subsequently added was *coroutines*: `Thread.start`, `Thread.sleep`,
`synchronized`, `wait`/`notify` implemented as suspendable green threads on one
OS thread. TeaVM's own documentation states it plainly: TeaVM does not support
true parallelism.

So the browser build is **single-threaded**, and no amount of header
configuration changes that.

### 3.2 What that does to the rasterizer — much less than it should

[`render/README.md`](../engine/src/main/java/com/openfps/engine/render/README.md)
§ 7 makes exclusive per-tile ownership the invariant the whole parallel design
rests on, and the repository asserts bit-identity between the pooled and serial
results. That assertion turns out to be the single most valuable property this
codebase has for a browser port, for a reason that is easy to miss:

**The serial path is not a degraded mode. It is the reference implementation,
and it is already the one under test.** `SoftwareRenderPort`'s constructor
accepts a `null` `I_ThreadPoolPort` — its Javadoc calls that "the serial
reference path, used by the build-time preview tool and by tests" — and its
`dispatch` runs the identical index sequence inline when the pool is null
(`engine/src/main/java/com/openfps/engine/render/adapter/SoftwareRenderPort.java`;
the same fork exists in `Rasterizer.dispatch`). Three test classes pin the
equality: `RasterizerTest` ("multi-threaded output is byte-identical to
single-threaded", parameterized over worker counts 1, 2, 3, 4, 8, plus a
non-tile-multiple resolution case), `SoftwareRenderPortTest` ("a pooled tagged
frame is bit-identical to a serial one, at every worker count"), and
`OutlinePassTest` (colour *and* entity-id buffers).

A browser backend therefore passes `null` and gets a renderer that is not merely
correct but **provably identical to the one the desktop ships**, with no new
code path and no new risk. Nothing about tiling, binning, the fill rule or the
stride padding has to be reconsidered. The 64-pixel tile size and the 16-pixel
stride alignment (`Framebuffer.STRIDE_ALIGNMENT`) become pointless rather than
wrong — they exist to prevent false sharing between workers, and there are no
workers — and `Framebuffer.copyColorTo`'s de-padding is still needed regardless,
because it de-pads for *presentation*, not for threading.

This is worth stating as a general lesson, because it was not the reason the
invariant was adopted: an architecture that can prove its parallel result equals
its serial result can be moved to a platform with no parallelism for free.

### 3.3 What it does to the frame budget — this is the actual problem

[`docs/ASSETS.md`](ASSETS.md) § 2's worker sweep, measured on an Intel Core Ultra
7 155H at 1280×720 on the 295-instance demo room:

| workers | 0 (serial) | 1 | 2 | 4 | 8 | 16 |
|---|---|---|---|---|---|---|
| p50 frame | **26.1 ms** | 25.1 | 16.3 | 8.5 | 5.2 | 4.0 |

**26.1 ms is the number a browser starts from, before any Wasm penalty.** That
is 38 fps on a fast laptop CPU under HotSpot with a warm JIT. It misses 60 Hz
outright and it is the *optimistic* end, because § 2 also observes that a
low-core machine at 2 workers (16.3 ms) already misses 60 Hz and calls that "the
portability risk". The browser is worse than the 2-worker column, not better.

Three levers exist, and the arithmetic is worth doing rather than asserting:

1. **Resolution.** § 2 measures per-pixel cost *rising* 23% from 720p to 1440p
   as the working set leaves L3 — colour plus depth plus entity ids is 12 bytes
   per pixel here, so the effect runs the other way too. 640×360 is a quarter of
   720p's pixels and comfortably cache-resident, so ~6–7 ms serial native is a
   defensible estimate rather than a scaled guess.
2. **Bilinear filtering.** § 2's one big lever: nearest-neighbour instead of
   bilinear is **2.9× faster**, because bilinear costs 2.9× the entire rest of
   the inner loop. It is currently unconditional — `render/README.md`'s Status
   block lists the quality toggle as **not built**, with no configuration field
   for it anywhere.
3. **Tic rate.** `FrameRate` is a closed enum with `FPS_30` in it
   ([`PLAN.md`](../PLAN.md) § 5), so 30 Hz costs nothing to select.

Combining them: 640×360, nearest-neighbour, 30 Hz gives roughly 2–3 ms of
serial native fill against a 33 ms budget — enough headroom to absorb a
substantial Wasm penalty and still hold. **The browser target is what makes the
bilinear toggle load-bearing rather than polish**, and it should be built first
regardless of whether the port ever happens, because it is also the cheapest
path to 1080p60 on the desktop (`render/README.md` § "Next step").

The size of the Wasm penalty is **unmeasured and I will not guess it**. TeaVM's
own maintainer characterises WasmGC as better than the JS backend but not
significantly, at roughly 5× the binary size. What that means for a bounds-check
and array-store-heavy inner loop over three primitive arrays is exactly the sort
of thing this repository has twice discovered by measuring rather than reasoning
(`docs/ASSETS.md` § 2's benchmark correction; `render/README.md` § 7's 15×
timer-period defect). Measure it; do not model it.

### 3.4 The loop, which is the part that needs a design decision

The engine's clock cannot survive contact with a browser unchanged. `GameLoop`
spins on `Thread.onSpinWait()` to hit its deadline and sleeps for the coarse
part; the bus blocks in `take()` when empty; the pool's join spins, yields and
then parks. Under TeaVM's coroutines every one of those is either a suspend
point that round-robins or — in the spin case — an unyielding loop that hangs
the tab.

The fix is not to make waiting cheaper. It is to stop waiting:

> The drift-correction math survives unchanged. What has to go is the *waiting*,
> not the *timing*.

`PLAN.md` § 5 computes each deadline absolutely from a fixed origin
(`startNanos + tic * nanosPerTic`) rather than by accumulation, precisely so two
machines agree. That formula works identically as an accumulator: a host
callback asks "how many tics are now due?", publishes exactly that many, and
returns. No thread, no queue, no park.

But it collides head-on with a rule `hal/README.md` states in bold: "**`onFrame`
draws; it never advances.** … Platform frame rate is whatever the display
decides, and it must never drive a tic." The rule is right, and lockstep depends
on it. The browser-compatible reading is that the *simulation pump* and the
*draw* are two host callbacks — a timer for tics, `requestAnimationFrame` for
frames — which preserves the separation the rule protects while removing the
thread it currently relies on. That is a change to `core`, not to an adapter,
and it is the honest cost of this port.

---

## 4. The HAL is the asset

Every port and its browser equivalent. Difficulty is relative effort for a
working implementation, not for a good one.

| Port | Browser equivalent | Difficulty | Notes |
|---|---|---|---|
| `I_SystemInfoPort` | `navigator.hardwareConcurrency`, constants | **Trivial** | Report 1 logical processor and the pool sizes itself to `MINIMUM_WORKERS`; only `EngineMain` reads it |
| `I_TimePort` | `performance.now()` | **Trivial** | Monotonic sub-millisecond. `epochMillis()` is `Date.now()`. Note browsers coarsen timer resolution when not cross-origin isolated |
| `I_AudioPort` | Web Audio, via libGDX core | **Trivial** | `GdxAudioPort` already lives in `:gdxshared` and the sounds are synthesised in code (`audio/synth/BlasterSound.java`, `WavAudio.java`), so there is no audio file to load and no decoder to port |
| `I_FrameCallback` | — | **None** | Engine-side. Already exactly right |
| `I_WindowPort` | Canvas + `requestAnimationFrame` | **Easy** | `runFrameLoop` registers and returns, exactly as `AndroidWindowPort` does. `isRealWindow()` is true |
| `I_InputPort` | Pointer Lock, `KeyboardEvent`, Touch | **Easy** | `InputAccumulator` in `:gdxshared` imports nothing and already solves accumulate-and-latch between two unrelated rates. `WebBindings` joins `DesktopBindings` and `AndroidBindings` |
| `ModelSource` (not a HAL port) | `fetch` into an in-memory map | **Easy** | § 7. The abstraction survives; only the ordering changes |
| `I_FilePort` | Not implemented | **Easy** | Nothing in the running engine uses it — `hal/README.md` records `NullFilePort` as the only backend and notes desktop needed nothing more. Ship the null one |
| `I_UserProfilePort` | `localStorage`, or IndexedDB behind a prefetch | **Moderate** | § 6.2 |
| `I_DatagramPort` | **Nothing.** WebRTC data channel, and only with a signalling server | **Hard — architectural** | § 6.1 |
| *(not a port)* Determinism | Requires a pure-Java fdlibm in `common/` | **Hard — and load-bearing** | § 5 |

Read down that column: **nine of eleven rows are trivial or easy, and every hard
row is hard for a reason that has nothing to do with the HAL.** The port set
does what
[`hal/README.md`](../engine/src/main/java/com/openfps/engine/hal/README.md)
claims for it — "a port interface lives here, an implementation that needs a
device does not" — and the two rows that resist are the two places where the
browser removes a *capability* rather than changing an *API*. That is the
correct outcome for a hexagonal architecture meeting a genuinely poorer
platform, and it is a real vindication of the port set rather than a polite one.

---

## 5. Determinism — the finding that decides the multiplayer question

This is the part that would be easiest to get confidently wrong, so it is stated
with its evidence.

[`PLAN.md`](../PLAN.md) § 4 records the deviation: `PlayerController` holds
simulation state in `float`, which is safe because JEP 306 makes all Java 17
floating-point arithmetic FP-strict IEEE 754, so `+ − × ÷` and `sqrt` are
bit-reproducible on every conforming JVM. The real hazard is the
transcendentals, which `Math` is permitted 1–2 ulp of error on and is explicitly
not required to reproduce between implementations. `StrictMath` is fdlibm-defined
and is. So every trig call in the simulation path is `StrictMath`, and
`PlayerControllerTest`'s nested `Determinism` class enforces it by reading the
compiled class's constant pool as ISO-8859-1 and asserting `java/lang/Math`
never appears, with a matching positive pin on `java/lang/StrictMath`, `sin` and
`cos`. `HitscanTest` carries the same guard.

**TeaVM breaks this, and the guard cannot see it break.**

TeaVM's class library reimplements `StrictMath` as a pure delegation layer:
every method body is `return TMath.<same method>(a)`. `TMath.sin`, `cos`, `tan`,
`sqrt` and `pow` are in turn `@Import(module = "teavmMath", name = …)` native
declarations — bound to the host environment's `Math`. So under TeaVM,
`StrictMath.sin` **is** JavaScript `Math.sin`. ECMA-262 § 21.3.2 only
*recommends* fdlibm for the transcendentals and does not require it; V8 uses
fdlibm, and other engines have historically not.

Three consequences, in increasing order of how expensive they are to discover:

1. `StrictMath.sqrt` is still safe. IEEE-754 requires square root to be
   correctly rounded and both Java and Wasm honour that, so the `sqrt` calls in
   `PlayerController`, `Bot`, `BotPattern` and `Match` are fine.
2. `StrictMath.sin`, `cos` and `pow` are not. `PlayerController` calls `sin` and
   `cos` on the yaw/pitch path, and `TicCmdEncoder` uses `StrictMath` on the
   float-to-wire quantisation — which means the *wire format itself* could
   disagree between a browser peer and a desktop peer.
3. **The constant-pool test still passes.** It proves the *source* names
   `StrictMath`; it cannot prove the *runtime* is fdlibm, because it reads a
   class file compiled by `javac` and never sees what TeaVM substitutes
   downstream. This is precisely the failure mode `PlayerControllerTest`'s own
   comment warns about — "sub-micron per step, invisible for minutes, and
   impossible to reproduce in a single-process test" — arriving through a door
   the guard was not built to watch. It is a green test that has stopped
   testing anything.

### The fix, and it is not a workaround

Port fdlibm's `sin`, `cos` and `pow` into `common/` as pure Java over `+ − × ÷`
only, and have simulation code call *that* rather than `StrictMath`. Those four
operators are exactly the ones JEP 306 guarantees on the JVM and that Wasm's
`f64` instructions guarantee in a browser, so a hand-rolled fdlibm is
bit-identical on all three platforms by construction. It is a few hundred lines
of well-specified, heavily-tested numerical code, and it is testable the
ordinary way: the desktop JVM's own `StrictMath` is the oracle.

It also **improves the desktop and Android builds**, which is what makes it
worth doing on its own merits rather than as a browser tax: today the fdlibm
guarantee is inherited from the JDK, and `PLAN.md` § 4's argument silently
assumes every peer runs a conforming JVM. Owning the implementation removes that
assumption.

One legal note, because [`docs/ASSETS.md`](ASSETS.md) § 8 makes this exact class
of mistake a named trap: **do not copy OpenJDK's `java.lang.FdLibm`.** OpenJDK is
GPL-with-classpath-exception and this repository is MIT. The original Sun fdlibm
C sources carry a permissive notice, and § 8's own distinction applies — reading
a published specification or a permissively-licensed reference is fine; vendoring
GPL source is not. **Verify the fdlibm notice before writing a line of it**;
this document asserts the distinction, not the conclusion.

### Can a browser peer stay in lockstep with a desktop peer?

**No, twice over.** The arithmetic fails until the paragraph above is done, and
even then the transport fails independently (§ 6.1): a browser cannot send a UDP
datagram to a desktop peer under any circumstances, so the two cannot share a
wire without a relay that
[`net/README.md`](../engine/src/main/java/com/openfps/engine/net/README.md) § 9
classifies as tier T2 — "**Servers, ops, money**" — and that contradicts the
opening line of that file. A browser build's honest scope is **single-player, or
browser-to-browser over WebRTC**, and browser-to-browser still needs signalling.

---

## 6. The two hard ports

### 6.1 Networking

`I_DatagramPort` is `send(byte[], String address)` / `byte[] receive()` /
`bind(int)` / `close()` / `processTic(int)`, and the only real implementation is
`DesktopDatagramPort` — a non-blocking `DatagramChannel` with one preallocated
direct `ByteBuffer` per direction. `NetSession` opens it, and
`DesktopLauncher` constructs both. **None of this exists in a browser.**

The available substitutes, against the constraints
[`net/README.md`](../engine/src/main/java/com/openfps/engine/net/README.md) § 2
already records:

| Substitute | Delivery model | Cost |
|---|---|---|
| WebSocket | Reliable, ordered — TCP | Reintroduces the head-of-line blocking § 3 of that document rules out. **Wrong** |
| `RTCDataChannel`, `{ordered: false, maxRetransmits: 0}` | **Unordered, unreliable messages** — genuinely UDP-shaped, DTLS-encrypted by default | Needs a signalling server and usually STUN |

The data channel is the right delivery model, and two details make it fit better
than expected. The redundant-redelivery design needs no ordering and no
retransmission (§ 4 of that document: "redundancy, not retransmission"), which
is exactly what `maxRetransmits: 0` provides. And `I_DatagramPort.receive()`
already drops the source address — the packet's own 20-byte header carries
`playerId` as field 0 — so the fact that a data channel is *per peer* rather
than one demultiplexed socket costs nothing. `bind(int port)` becomes
meaningless and returns silently; everything above the port is unchanged.

What it costs is the premise. `net/README.md` § 9 grades NAT traversal T0 (LAN
broadcast, zero deps, ships in Phase 3), T1 (STUN, ~30 LOC, fails on symmetric
NAT), T2 (relay — servers and money). **A browser peer cannot do T0 at all**:
there is no UDP broadcast, no LAN discovery, and no way to learn a peer exists
without a server that already knows about both. So a browser target starts at
T1-plus-signalling and cannot fall back. That is a product decision with an
operational budget, exactly as § 9 says of T2, and it should be recorded as such
rather than discovered during implementation.

### 6.2 Persistence

`I_UserProfilePort` is nine synchronous methods — `Optional<UserProfile>
findById`, `List<UserProfile> findAll`, `void save`, `delete`, `count`,
`generateNewId`, plus `init`/`shutdown`/`state()`. There is no SQLite in a
browser and no Room, so both existing backends are gone.

The port's synchrony is the whole difficulty, because IndexedDB is
asynchronous and cannot be made otherwise. Two honest answers:

- **`localStorage`.** Synchronous by specification, so the port signature is
  satisfied with no changes at all. It is origin-scoped and capped near 5 MB —
  a `UserProfile` is a handful of fields, so the cap is irrelevant here.
  **This is the right first answer**, and it is unusual for the boring option to
  also be the correct one.
- **IndexedDB behind the state machine.** The port already has an `init()` that
  the engine calls before the session begins, and `EngineMain` loads or creates
  the profile at boot and saves at shutdown
  ([`hal/README.md`](../engine/src/main/java/com/openfps/engine/hal/README.md),
  "the two ports the engine queries outside the tic"). So an implementation can
  read every profile into memory during an async pre-boot step and write back
  fire-and-forget — which is `MemoryUserProfilePort` plus two hooks, and
  `MemoryUserProfilePort` already exists and is tested.

Both preserve the port. Neither requires a signature change. This row is marked
"Moderate" only because the second option's shutdown path — a page being closed
mid-write — has no clean answer, and the desktop build has never had to have one.

---

## 7. Assets and size

`ModelSource` is already the right abstraction and it exists for exactly this
reason. Its Javadoc says so: "It was a `Path` until the game had to run on a
phone, and on a phone there is no such file … The demo's model set is the one
thing the engine loads from outside itself, so it is the one place that had to
stop assuming a directory." Four methods — `has`, `read`, `describe`,
`describeRoot` — with two implementations, `DirectoryModelSource` and
`ApkModelSource`.

**The interface survives; only the ordering changes.** `read` returns `byte[]`
synchronously, which a browser cannot do — but the same Javadoc pins the
constraint that makes this a non-issue: "Called once at startup, from one
thread, never per frame." So a browser implementation fetches every `.ofm` into
an in-memory map *before* the engine boots, and `read` becomes a map lookup.
That is precisely what libGDX's GWT preloader does, and it needs no change to
`DemoModels`, which already takes a `ModelSource` rather than a `Path`.

### Size, which is the real constraint

Measured from the staged tree, and it confirms
[`docs/DEMO_ASSETS.md`](DEMO_ASSETS.md) § 4 exactly:

| | Bytes |
|---|---|
| The nine demo models | **12,626,532** |
| …of which duplicated 512² atlas + mip pyramid | ~12.6 MB, ~1.4 MB per model |
| `generated-room.ofm` (the no-art fallback) | 47,448 |
| The whole `assets/models` tree, 28 files | **56,944,803** |

`docs/DEMO_ASSETS.md` § 4 records why: "`ModelFormat` has no notion of a texture
shared between models, so a pack built around one atlas per kit — the exact
property `docs/ASSETS.md` § 3 praises Kenney for — pays for that atlas once per
model."

On Android this is survivable because an APK is one compressed archive: ~36 MB
staged becomes ~4 MB added to the APK, because `.ofm` texture data deflates
extremely well ([`android/README.md`](../android/README.md)). **A browser has no
APK.** Each `.ofm` is a separate URL; HTTP compression applies per response and
never dedupes across responses, and the browser cache keys on URL. So nine
identical atlases are fetched, decompressed and held in memory nine times.

This promotes `docs/ASSETS.md` § 9's open item — "**One atlas per model, not per
pack**" — from a recorded inefficiency to **the single largest lever on browser
download size**, worth more than the choice of compiler. With a shared-texture
section in `ModelFormat`, the nine demo models collapse to roughly one atlas
(~1.4 MB) plus nine sets of geometry measured in kilobytes: call it under 2 MB
before compression, against 12.6 MB today. That is a 6× reduction from a change
that `docs/ASSETS.md` already wants for its own reasons.

The staging pipeline itself needs little: `:tools:regenerateDemoAssets` already
emits a flat directory of `/`-separated relative paths, which is exactly what a
web server serves. What it would have to gain is a **manifest** — the list of
paths plus their byte lengths, so the preloader knows what to fetch and can show
progress — and that is a few lines in `DemoAssetsMain`, not a redesign. Android's
`stageModelAssets` is the precedent for a per-platform staging step.

Runtime download size is **unmeasured**. TeaVM's WasmGC output is reported at
roughly 5× its JavaScript output for the same source, and `:engine` plus
`:gdxshared` plus libGDX core is a substantial input. Anyone quoting a number
for this before building it is guessing.

---

## 8. Effort, and the recommendation

### Per-port estimate

Ranges, not commitments. They assume one person already fluent in this codebase,
and they exclude the learning curve on TeaVM itself.

| Item | Estimate | Confidence |
|---|---|---|
| Gradle wiring, `gdx-teavm`, dependency exclusions for logback + sqlite-jdbc | 2–3 days | High — `:android` is the template |
| `WebWindowPort` (canvas + `requestAnimationFrame`) | 2 days | High |
| `WebInputPort` + `WebBindings` (Pointer Lock, keyboard) | 2 days | High — `InputAccumulator` is done and headlessly tested |
| `WebTimePort`, `WebSystemInfoPort` | 0.5 days | High |
| `FetchModelSource` + a staging manifest | 2 days | High |
| `WebUserProfilePort` over `localStorage` | 1 day | High |
| Collapsing `GameLoop` onto a host callback without breaking drift correction or the "onFrame never advances" rule | **4–8 days** | **Low** — this is a `core` change with a test suite to keep green |
| Chasing whatever TeaVM's partial class library does not have | **3–10 days** | **Very low** — unknowable without trying |
| Bilinear/nearest quality toggle (already a planned Phase 5 item) | 2 days | High |
| **Subtotal — playable single-player demo** | **~3–5 weeks** | |
| Pure-Java fdlibm `sin`/`cos`/`pow` in `common/` + oracle tests | 1–2 weeks | Moderate |
| `WebRtcDatagramPort` behind `I_DatagramPort` | 1 week | Moderate |
| Signalling server, STUN, and the lobby that implies | **Unbounded** | It is a service, not a task |
| **Multiplayer, browser-to-browser** | **+3 weeks and an operational commitment** | |

### Verdict

**Viable with specific caveats — and the caveats are load-bearing, not
footnotes.**

The architecture is genuinely ready for this in the ways that are usually hard.
The HAL port set holds: nine of eleven rows are easy, and the two that are not
are hard because the browser lacks a *capability*, which no interface design
could have prevented. The renderer is ready in a way that is close to
remarkable — the serial path is the tested reference, not a fallback, so the
single-threaded platform inherits a provably-identical rasterizer for free.
`ModelSource`, `EngineSession.start()`/`frameCallback()`, and
`I_WindowPort.runFrameLoop` were all shaped by Android and all fit a browser
without modification.

What is not ready is smaller in code and larger in consequence. The tic clock is
a blocking thread and has to stop being one, which is a `core` change against a
1524-test suite. `StrictMath` stops meaning what it means, silently, past a
guard that keeps passing. And UDP is simply gone, which ends browser-to-desktop
multiplayer regardless of anything else.

**Not worth starting today**, for one reason that has nothing to do with any of
the above: the project's own next steps are more valuable per unit of effort.
`net/README.md` states that remote players are not yet simulated into visible
bodies, and `README.md` says the same. Shipping a browser build of a game whose
multiplayer does not draw the other player is optimising the wrong axis. The
right sequence is: finish the remote-player simulation, build the bilinear
toggle (wanted anyway), and *then* reconsider — at which point most of § 8's
"playable demo" subtotal is unchanged and the rest is better understood.

### The smallest meaningful proof of concept

Not a port. **A measurement**, and it should take about three days:

> Compile `:engine`'s `SoftwareRenderPort` with a `null` pool and
> `ProceduralRoom`'s 60-triangle generated room through TeaVM to WasmGC. Render
> 300 frames at 640×360 into a canvas via `Framebuffer.copyColorTo` and a
> `putImageData`. Report the p50.

It is the smallest thing that answers the only question this document cannot:
**how much slower is the inner loop in a browser?** Every other risk here is
known and bounded — the clock rewrite is understood, fdlibm is understood, the
absence of UDP is understood. The Wasm penalty on a bounds-checked loop over
three primitive arrays is not, and it decides everything downstream: if it is
1.5×, a 720p30 browser build is real; if it is 5×, the target is 480×270 and the
conversation changes.

It also needs almost nothing from the rest of the port. No window port, no
input, no persistence, no networking, no `GameLoop` — the generated fallback
room is 47 KB and needs no asset pipeline at all. It deliberately measures the
one thing that cannot be reasoned about, which is the same discipline
`docs/ASSETS.md` § 2 applied when its estimates turned out to be optimistic by
2–3× and `render/README.md` § 7 applied when a timed park cost 15×.

---

## 9. What this document could not verify

Stated so nobody treats an inference as a measurement.

- **The Wasm performance penalty on this rasterizer.** Unmeasured, and no
  published benchmark is close enough to this workload to substitute. § 8's PoC
  exists to fix this.
- **Whether `gdx-teavm` actually carries `:gdxshared`.** The module depends on
  libGDX core only, which is the precondition, but Scene2D, `Pixmap`,
  `SpriteBatch` and `Texture` have not been tried under it.
- **TeaVM version and date specifics.** The threading statements here come from
  TeaVM's own 0.12/0.13 release notes and documentation; the current version and
  its release date were not independently confirmed and should be re-checked
  before any work starts.
- **TeaVM's exact `StrictMath` behaviour at whichever version is current.** The
  delegation chain (`TStrictMath` → `TMath` → `@Import("teavmMath")`) was read
  from the class library sources. It is a design decision rather than an
  oversight and is unlikely to change, but § 5's conclusion is important enough
  to be re-read against the version actually used.
- **The fdlibm licence.** § 5 asserts that the original Sun sources are
  permissive and that OpenJDK's Java port is not usable here. The first half was
  not verified against the notice text.
- **`localStorage` behaviour under private browsing and storage pressure.**
  Browsers evict, and the desktop build has never had a profile disappear.

## 10. Revisit triggers

- **`shared-everything-threads` ships in two browser engines.** That would
  restore the tile-parallel model and change § 3.3's conclusion outright — the
  renderer needs no changes to take advantage, because the pooled path is the
  one that already exists.
- **`ModelFormat` gains a shared-texture section** (`docs/ASSETS.md` § 9). The
  download budget improves ~6× and § 7 stops being a constraint.
- **Remote players are simulated into visible bodies** (`net/README.md`). The
  sequencing argument in § 8 expires the moment this is true.
- **A browser build is wanted for reach rather than for play** — a demo link in
  a README, not a platform. Single-player at 640×360 satisfies that today and is
  a three-to-five week job, with none of § 5 or § 6.1 on the critical path.
