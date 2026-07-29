# Memory: what OpenFPS reserves, what it churns

> Measurement document. How many bytes the engine pre-allocates, how many it
> allocates per frame and per tic, which of the Javadoc's allocation-freedom
> claims survive contact with a running process, and what that means for frame
> time.
>
> **Every number here was measured or derived from a quoted constant.** Where a
> figure could not be measured it is in [§ 10](#10-what-could-not-be-measured)
> and labelled as such. [docs/ASSETS.md](ASSETS.md) § 2 is the model for this:
> its frame-time table replaced estimates that were wrong by 2–3×, and the same
> thing happens below to two claims that had been in the source for months.

---

## 1. Why this document exists

The renderer is software. A GPU game's colour buffer, depth buffer and staging
texture live in VRAM and never appear in a heap profile; here all three are Java
arrays. And the engine is a 60/120 Hz loop, so **a garbage collection pause is
not a slowdown, it is a dropped frame**: at 120 Hz the whole budget is 8.33 ms
and a G1 young pause on this machine measures 6.4–6.9 ms.

The source knows this and says so, repeatedly. `TriangleClipper` promises
"Nothing is allocated after construction". `SpanRenderer` says it "allocates
nothing". `DemoGameplayPort` says "The only per-tic allocation is the `Camera`".
`net/README.md` says "Steady-state per-tic allocation is zero". Those sentences
are load-bearing and **none of them had ever been checked against a running
process.** Three of them are false.

---

## 2. Method — three instruments, and why each

Reading the code finds allocation sites. It cannot weigh them, and it cannot
find the ones that are not in this repository at all — the largest single
allocator below is a JDK class, reached from one line of `WorkerPool`.

All runs are the shipping demo scene (`DemoScene`: **340 world instances, 36 of
them translucent, 1 view instance, largest model 368 triangles,
`maxPassTriangles = 7844`** — logged at startup), on the same machine as
[docs/ASSETS.md](ASSETS.md) § 2: Intel Core Ultra 7 155H, 16 physical / 22
logical, so the auto-sized pool is `22 − 1 = 21` workers. Windowed 1280×720,
`--fps=60` unless stated.

**Runs bypass Gradle.** `desktop/build.gradle.kts` forwards named
`openfps.*` system properties into the forked JVM precisely because a `-D` on
the Gradle line lands on the daemon — but it forwards no *JVM* arguments, and
`-Xlog:gc` is a JVM argument. Rather than add forwarding, every measurement
below launches `com.openfps.desktop.DesktopLauncher` directly against
`desktop/build/install/desktop/lib/*.jar` with `--start-in-game` and
`-Dopenfps.screenshotFrame=N -Dopenfps.screenshotExit=true`, which makes the run
an exact number of frames long.

| Instrument | What it gives | Why it was needed |
|---|---|---|
| **`-XX:+UseEpsilonGC`** with `-Xms1g -Xmx1g -XX:EpsilonPrintHeapSteps=4096` | Total bytes allocated, exactly. Epsilon never collects, so heap-used is a monotone running total; the log emits a line every 256 KiB. A least-squares fit of used-bytes against uptime over the steady-state window **is** the allocation rate | The only way to get an absolute figure with no sampling error. Ordinary GC logs bound the answer but do not give it |
| **JFR `jdk.ThreadAllocationStatistics`** at `period=1000ms` | Per-thread cumulative allocated bytes, once a second | This is `ThreadMXBean.getThreadAllocatedBytes` sampled by the VM — **the probe the brief asked for, without touching a line of engine code.** It splits the total across `main`, `openfps-gameloop` and the 21 workers |
| **JFR `jdk.ObjectAllocationInNewTLAB` / `OutsideTLAB`** with `-XX:TLABSize=4k -XX:-ResizeTLAB` | ~900 stack traces per 16 s window, attributed to a class and a line | `jdk.ObjectAllocationSample` is throttled to ~150/s and yielded **7** usable samples in a steady-state window. Shrinking the TLAB to 4 KiB turns every refill into an event and raises that by two orders of magnitude |
| **`jcmd GC.class_histogram`** mid-run, after the implied full GC | Live bytes per array type | Verifies the pre-allocation arithmetic against the actual heap rather than against itself |

**The two independent rate measurements agree to 0.3%** — Epsilon 332,497 B/s
against JFR-per-thread 333,555 B/s on the same configuration. That agreement is
the reason the figures below are quoted as measurements rather than estimates.

One correction to make before reading § 5: **TLAB-refill attribution charges a
whole 4 KiB refill to whichever stack tripped it**, so its *shares* are sound
and its *absolute* per-site bytes are coarse. Its total (4,501 B/frame) lands
inside the Epsilon range (4,414–6,204 B/frame) rather than on a point in it.

---

## 3. Pre-allocated pools

### 3.1 The six full-frame buffers — the arithmetic nobody had added up

A frame at `w × h` costs **six** arrays sized by the surface, not the three the
Android manifest counts:

| # | Field | Declared at | Type | Length |
|---|---|---|---|---|
| 1 | `Framebuffer.color` | [`Framebuffer.java:935`](../engine/src/main/java/com/openfps/engine/render/adapter/Framebuffer.java) | `int[]` | `stride × h` |
| 2 | `Framebuffer.depth` | `Framebuffer.java:936` | `float[]` | `stride × h` |
| 3 | `Framebuffer.entityIds` | `Framebuffer.java:939` | `int[]` | `stride × h` |
| 4 | `SoftwareRenderPort.frontColor` | [`SoftwareRenderPort.java:791`](../engine/src/main/java/com/openfps/engine/render/adapter/SoftwareRenderPort.java) | `int[]` | `w × h` |
| 5 | `SoftwareRenderPort.backColor` | `SoftwareRenderPort.java:792` | `int[]` | `w × h` |
| 6 | `FramebufferPresenter.scratch` | [`FramebufferPresenter.java:415`](../gdxshared/src/main/java/com/openfps/gdx/FramebufferPresenter.java) | `int[]` | `w × h` |

`stride = alignUp(w, STRIDE_ALIGNMENT)` with `STRIDE_ALIGNMENT = 16`
(`Framebuffer.java:114`) — sixteen `int`s are one 64-byte cache line, which is
the false-sharing mitigation `render/README.md` § 7 asks for. **The padding is
charged three times**, because buffers 1–3 are indexed by stride and buffers 4–6
are the de-padded copies the presentation path uploads
(`Framebuffer.copyColorTo`, `Framebuffer.java:753`).

So the per-pixel cost is:

```
12 bytes per PADDED pixel   (colour int + depth float + entity-id int)
12 bytes per VISIBLE pixel  (frontColor + backColor + presenter scratch)
```

**24 bytes per pixel** once the width is already 16-aligned. `docs/ASSETS.md`
§ 2 reasons about the cache working set as "8 bytes per pixel, so the
framebuffer alone is 7.4 MB at 720p" — correct for the two buffers the *inner
loop* touches, and a third of what the *heap* carries.

Why three copies of the colour and not one: `frontColor`/`backColor` are a swap
pair so the de-padding copy happens outside `presentLock`
(`SoftwareRenderPort.java:639–644`), and the presenter's `scratch` exists
because `copyColorInto` needs a destination the render port does not own. Each
is individually justified; nothing had ever summed them.

**Verified against the heap, not against itself.** Two `GC.class_histogram`
snapshots of the same build, 480P and NATIVE, on the same 1280×720 window:

| | `[I` live bytes | `[F` live bytes |
|---|---|---|
| 480P (853×480, stride 864) | 29,714,504 | 4,480,168 |
| NATIVE (1280×720, stride 1280) | 39,947,720 | 6,507,752 |
| **delta** | **10,233,216** | **2,027,584** |

Predicted `int[]` delta: `2 × (921,600 − 414,720)` padded pixels for colour and
entity-ids, `3 × (921,600 − 409,440)` visible pixels for the three de-padded
buffers, and `3 × 21 × (240 − 112)` for the tile bins — `1,013,760 + 1,536,480 +
8,064 = 2,558,304` elements = **10,233,216 bytes. Exact.** Predicted `float[]`
delta is the depth buffer alone, `506,880 × 4 = 2,027,520` bytes, measured
2,027,584 — 64 bytes over, one unrelated array.

### 3.2 Geometry pools — linear in `Scene.maxPassTriangles()`

`SoftwareRenderPort.growBuffersFor` (`SoftwareRenderPort.java:1088–1114`) sizes
these from `T = maxPassTriangles()` and `maxOutput = T × CLIP_EXPANSION` where
`CLIP_EXPANSION = TriangleClipper.MAX_OUTPUT_TRIANGLES = 2`. They are grow-only.
For the demo's `T = 7844`:

| Pool | Length | Bytes |
|---|---|---|
| `Rasterizer.records` `float[2T × 25]` (`Rasterizer.java:403`) | 392,200 | 1,568,816 |
| `clipVertices` `float[2T × 15]` (`:1096`) | 235,320 | 941,296 |
| `clipMaterials` / `clipColors` / `clipEntityIds` `int[2T]` (`:1097–1099`) | 15,688 each | 62,768 each |
| `binEntries` `int[2T]`, grows to the (triangle, tile) high-water mark (`Rasterizer.java:407`, `:955`) | ≥15,688 | ≥62,768 |
| **subtotal** | | **2,761,184** |

`records` dominates because the record stride is `RECORD_HEADER_FLOATS +
PLANE_FLOATS × attributeCount = 19 + 3×2 = 25` floats — 100 bytes of setup per
clipped triangle. **352 bytes per pass triangle** all told, which is the number
to carry into any conversation about scene size: a 50,000-triangle level would
want 17.6 MB of geometry scratch before a pixel is drawn.

### 3.3 Tile bins — the only pool that depends on both resolution and worker count

`Rasterizer.beginFrame` sizes three `int[chunkCount × tileCount]`
(`Rasterizer.java:456–458`), where `chunkCount = max(1, pool.workerCount())`
and `tileCount = ceil(w/64) × ceil(h/64)` with `DEFAULT_TILE_SIZE = 64`
(`Framebuffer.java:122`).

**Cost = 12 × workers bytes per tile.** At 21 workers that is 252 B/tile: 28,272
B at 480P (112 tiles), 60,528 B at 720P (240 tiles), 54,312 B at a phone's
NATIVE 2400×1080 with 7 workers (646 tiles). The retirement of
`logicalProcessors / 2` in favour of `logicalProcessors − 1`
([docs/ASSETS.md](ASSETS.md) § 2) roughly doubled this pool on a 22-thread part —
from 11 to 21 chunks — which is 30 KB at 720P and not worth a second thought.
It also roughly doubled something that *is* worth a thought; see § 5.2.

### 3.4 Fixed pools — everything that scales with neither

| Pool | Constants | Bytes |
|---|---|---|
| Lockstep input ring: `TicCmdBuffer.slotTics` / `axes` / `look`, `int[TIC_BUFFER_SIZE × MAX_PLAYERS]` ([`TicCmdBuffer.java:69–71`](../engine/src/main/java/com/openfps/engine/net/TicCmdBuffer.java)) | `64 × 8 = 512` slots; `Constants.TIC_BUFFER_SIZE = 64`, `MAX_PLAYERS = 8` | 6,192 |
| `TicCmdBuffer.latestTics` `int[8]` | | 48 |
| `NetSession.sendBuffer` `byte[1200]` ([`NetSession.java:101`](../engine/src/main/java/com/openfps/engine/net/NetSession.java)) | `RedundantSender.MAX_DATAGRAM_BYTES = 1200` | 1,216 |
| 7 × `PeerConnection` + `AckWindow` | `MAX_PEERS = MAX_PLAYERS − 1` | 664 |
| `DemoEffects` tracer + smoke pools, 12 arrays ([`DemoEffects.java:516–526`](../engine/src/main/java/com/openfps/engine/demo/DemoEffects.java)) | `MAX_TRACERS = 3`, `MAX_PUFFS = 3`, `PUFF_STAGES = 4`, `PUFF_LOBES = 3` → 39 scene instances | 624 |
| `Match.bots` `Bot[7]` + 7 `Bot` + one reused `HitResult` | `DEFAULT_BOT_COUNT = 7` | 576 |
| `PhysicsWorld.solids` `float[16 × 4]` | 16 boxes, `SOLID_STRIDE = 4` | 272 |
| `SoftwareRenderPort.blendedRenderers` `SpanRenderer[256]` (`:1023`) | `Scene.OPAQUE + 1` | 1,040 |
| `WorkerPool.batches` `ParallelBatch[8]` (`WorkerPool.java:121`, `:150`) | `MAX_CONCURRENT_BATCHES = 8` | 304 |
| Blaster sound: `short[3969]` PCM + `byte[7982]` WAV ([`BlasterSound.java`](../engine/src/main/java/com/openfps/engine/audio/synth/BlasterSound.java), `SAMPLE_RATE = 22050`, `DURATION_MS = 180`) | `22050 × 180 / 1000 = 3969` samples; `44 + 2 × 3969` | 15,960 |
| **total** | | **26,896 (26.3 KiB)** |

**The input ring is 6,144 bytes of payload and the whole of `net` is 8.1 KB.**
That is the most striking thing in this table: the subsystem with the most
documentation about memory discipline is four orders of magnitude smaller than
the one array the presenter needs. Two caveats on it, both in
[net/README.md](../engine/src/main/java/com/openfps/engine/net/README.md) and both
wrong — see § 6.

`GdxAudioPort` stages nothing per-frame. The blaster WAV is generated once,
lazily, on first play; `AudioVolume` is stateless, `NullAudioPort` holds one
`AtomicLong`. The claim at `BlasterSound.java:185–188` that it "allocates a
fresh array each call — about 8 KB" and is "not on any per-tic or per-frame
path" is accurate: 7,938 + 7,982 bytes, once per process.

### 3.5 Totals per render mode

Render-owned pre-allocation. Decoded model textures are the measured
`21,203,936 bytes` of `int[]` mip chains left over once § 3.1's arithmetic is
subtracted from the 480P histogram — which matches
[docs/DEMO_ASSETS.md](DEMO_ASSETS.md) § 4's on-disk 12.7 MB expanding to the
"about 21 MB" the Android manifest guesses at.

| Surface | Mode | Render size | Stride | Tiles | Frame buffers | Geometry + bins | **Render total** | + textures |
|---|---|---|---|---|---|---|---|---|
| 1280×720 window, 21 workers | 480P | 853×480 | 864 | 112 | 9,890,016 | 2,793,968 | **12,683,984** (12.10 MiB) | 33.3 MiB |
| | 720P / NATIVE | 1280×720 | 1280 | 240 | 22,118,496 | 2,826,224 | **24,944,720** (23.79 MiB) | 45.0 MiB |
| 2400×1080 phone, 7 workers | 480P | 1067×480 | 1072 | 136 | 12,320,736 | 2,774,120 | **15,094,856** (14.40 MiB) | 35.6 MiB |
| | 720P | 1600×720 | 1600 | 300 | 27,648,096 | 2,787,896 | **30,435,992** (29.03 MiB) | 50.3 MiB |
| | NATIVE | 2400×1080 | 2400 | 646 | 62,208,096 | 2,816,960 | **65,025,056** (62.01 MiB) | 83.2 MiB |

Measured live heap after a full GC, 1280×720 window, JDK 17, default G1:
**55.4 MiB at 480P, 69.7 MiB at NATIVE.** Both are the table's "+ textures"
column plus the JVM's own baseline, minus whatever the demo has not touched yet.

---

## 4. Android: the manifest is right about the symptom and wrong by half

[`android/src/main/AndroidManifest.xml:27–47`](../android/src/main/AndroidManifest.xml)
is the best piece of memory documentation in the repository — it is measured,
not precautionary, and it records a real failure. It is also short by 31 MB.

**Its one hard number checks out exactly.** The presenter's scratch array on a
2400×1080 panel at NATIVE is `int[2,592,000]`; Android ART's array header is 12
bytes, so the allocation request is `12 + 2,592,000 × 4 =` **10,368,012 bytes**
— the figure in the two `OutOfMemoryError`s. That the arithmetic closes on ART's
12-byte header rather than HotSpot's 16 is itself the proof it was copied off a
device and not computed.

Where it is wrong: it counts "a 10.4 MB colour `int[]`, a 10.4 MB depth buffer,
and another 10.4 MB `int[]` the presenter uploads from — 31 MB". § 3.1 shows
**six** such arrays, not three. Missing are `Framebuffer.entityIds`,
`SoftwareRenderPort.frontColor` and `SoftwareRenderPort.backColor`, and each is
another 10,368,012 bytes. **The real figure is 62.2 MB, not 31 MB**, and with
~21 MB of decoded textures the app wants ~83 MB of arrays plus its baseline
before it draws anything.

That makes the manifest's own conclusion *more* right than it argues. Against
`dalvik.vm.heapgrowthlimit` at 96–128 MB on mid-range phones, 83 MB of arrays is
not "close to the edge", it is over it for anything but the emulator's 192 MB.
And the corollary the manifest does not draw:

> **480P is not only a frame-time default, it is the reason the Android build
> fits in memory at all.** It costs 15.1 MB of render-owned arrays against
> NATIVE's 65.0 MB — a 49.9 MB saving, four times the entire `largeHeap`
> headroom of a 128 MB device.

[`RenderMode`](../gdxshared/src/main/java/com/openfps/gdx/RenderMode.java) argues
for 480P entirely from the 98 ms frame it measured on the emulator, and never
mentions memory. It should: the frame-time argument makes NATIVE unplayable, the
memory argument makes it unbootable.

One thing that does *not* land on the Java heap and is easy to double-count:
libGDX's `Pixmap` (`FramebufferPresenter.java:416`) is another `w × h × 4` bytes,
but it is a native `gdx2d` allocation. It counts against the process, not against
`heapgrowthlimit`.

---

## 5. Dynamic allocation — measured

### 5.1 The headline

Steady-state, JDK 17, 480P, 21 workers, five Epsilon runs, windows chosen after
warm-up and before the demo's match is lost:

| Run | Mode | B per rendered frame |
|---|---|---|
| `j17_60` | 480P | 4,651 |
| `j17_60b` | 480P | 5,039 |
| `j17_720` | 720P | 4,414 |
| `j17_720b` | 720P | 6,204 |
| `j17_nat` | NATIVE 1280×720 | 5,491 |

**≈5 KB per tic, spread 4.4–6.2 KB.** Three findings follow immediately, and the
first two are good news:

1. **Allocation does not scale with render resolution.** 720P allocates no more
   than 480P above the run-to-run spread, across 2.25× the pixels. That is
   exactly what a genuinely allocation-free raster path looks like.
2. **Presentation allocates almost nothing per platform frame.** Solving
   `rate = A × platformFps + B × renderedFps` across `--fps=30/60/120` runs (all
   vsync-locked to 60 platform fps, rendering at 30/60/120) puts `B` at
   ~5–6 KB per rendered frame and `A` at or below zero — i.e. within noise of
   nothing. Rendering is tic-driven via `RenderFrameEvent`, so **per-frame and
   per-tic are the same number in this engine**, and the extra 30 platform
   frames a second at `--fps=30` cost nothing measurable.
3. **It does scale with worker count, up to a point.** Same build, same scene,
   480P, JDK 17, `-Dopenfps.workers=N`:

   | workers | 2 | 4 | 8 | 16 | 21 |
   |---|---|---|---|---|---|
   | B/frame | 3,307 | 3,929 | 5,405 | 4,903 | 4,651 / 5,039 |

   It rises by ~60% from 2 to 8 workers and then flattens. § 5.2 explains why,
   and why the ceiling is set by dispatch count rather than by thread count.

### 5.2 Where it comes from

Per-thread, from `jdk.ThreadAllocationStatistics` over a 17 s window — the
`getThreadAllocatedBytes` split, and independently reproduced by the TLAB
attribution:

| Thread group | B/s | B/frame @60 | share |
|---|---|---|---|
| `openfps-worker-*` (21 threads) | 266,258 | 4,438 | 79.8% |
| `main` (LWJGL3 loop + present) | 56,720 | 945 | 17.0% |
| `openfps-gameloop` | 9,835 | 164 | 2.9% |
| JVM / libGDX housekeeping | 741 | 12 | 0.2% |
| **total** | **333,555** | **5,559** | |

And by site, from 921 TLAB-refill stacks on JDK 17 (shares reliable, absolutes
coarse — see § 2):

| Share | B/frame | Allocating site | Object |
|---|---|---|---|
| **40.7%** | 1,833 | [`WorkerPool$WorkerThread.awaitWork:544`](../engine/src/main/java/com/openfps/engine/core/pool/WorkerPool.java) | `AbstractQueuedSynchronizer$ConditionNode` |
| **16.3%** | 733 | [`Mat4.ofRowMajor:80`](../engine/src/main/java/com/openfps/engine/render/adapter/Mat4.java) | `float[16]` |
| **13.9%** | 624 | [`DemoScene.placement:960`](../engine/src/main/java/com/openfps/engine/demo/DemoScene.java) | `float[16]` |
| 3.8% | 171 | `Mat4.ofRowMajor:82` | `Mat4` |
| 2.9% | 132 | [`GdxInputPort.isHeld:730`](../desktop/src/main/java/com/openfps/desktop/GdxInputPort.java) | capturing lambda |
| 2.0% | 89 | [`Camera.<init>:150`](../engine/src/main/java/com/openfps/engine/render/adapter/Camera.java) | `float[12]` |
| 1.7% | 78 | [`Vec3.cross:123`](../engine/src/main/java/com/openfps/engine/render/adapter/Vec3.java) | `Vec3` |
| 1.6% | 73 | [`SharedEventBus.publish:107`](../engine/src/main/java/com/openfps/engine/core/eventbus/SharedEventBus.java) | `LinkedBlockingQueue$Node` |
| 1.5% | 69 | `Camera.create:204` | `Camera` |
| 1.4% | 63 | `GdxInputPort.isJustPressed:740` | capturing lambda |
| 1.2% | 54 | `WorkerPool$WorkerThread.awaitWork:537` | `AQS$ExclusiveNode` |
| 1.1% | 49 | `SharedEventBus.take:123` | `ConditionNode` |
| 1.1% | 49 | `Vec3.normalized:158` | `Vec3` |
| 0.9% | 39 | [`FramebufferPresenter.present:482`](../gdxshared/src/main/java/com/openfps/gdx/FramebufferPresenter.java) | `DirectIntBufferS` |
| 0.9% | 39 | [`Match.bots:340`](../engine/src/main/java/com/openfps/engine/gameplay/Match.java) | `Bot[7]` clone |
| 0.9% | 39 | [`EventFactory.newRenderFrame:55`](../engine/src/main/java/com/openfps/engine/core/event/EventFactory.java) | `RenderFrameEvent` |
| 0.8% | 34 | `EventFactory.newTick:50` | `TickEvent` |
| 0.8% | 34 | [`InputState.of:130`](../engine/src/main/java/com/openfps/engine/hal/port/InputState.java) | `InputState` |
| 0.5% | 25 | [`Target.aroundFeet:156`](../engine/src/main/java/com/openfps/engine/gameplay/Target.java) | `Target` |
| 0.5% | 24 | [`PlayerController.eyePosition:730`](../engine/src/main/java/com/openfps/engine/gameplay/PlayerController.java) | `Vec3` |
| 0.4% | 20 | `PlayerController.forwardVector:765` | `Vec3` |

**The largest allocator in OpenFPS is not the renderer and not the simulation.
It is 21 hot worker threads parking on one shared `Condition`.**
`WorkerPool.awaitWork` (`WorkerPool.java:535–555`) takes `idleLock` and calls
`idleCondition.await(IDLE_PARK_MILLIS, MILLISECONDS)`. Every `await` on an
`AbstractQueuedSynchronizer$ConditionObject` allocates one `ConditionNode` to
put the thread on the wait queue; the contended `idleLock.lock()` on the line
above adds an `ExclusiveNode`. Together that is **42% of everything the engine
allocates**, and there is not a `new` anywhere near it.

It is dispatch-driven rather than thread-driven, which is why § 5.1's worker
sweep flattens: `SoftwareRenderPort` reports **56 parallel passes per frame**
(`lastFrameParallelPasses`, `SoftwareRenderPort.java:1452`), each of which wakes
the pool and lets it re-park. 1,882 B/frame at ~40 bytes a node is ~47 nodes per
frame — one per barrier, not one per worker per barrier. `docs/ASSETS.md` § 2
records batching this pass "to 8 barriers" as the fix that took the demo from
2.9 fps to vsync-limited; translucency has since taken it to 56, and the cost of
that is now visible here rather than only in frame time.

**Second: 32% of allocation is `float[16]`, and all of it is one seam.**
`DemoGameplayPort.publishBotPlacements` (`DemoGameplayPort.java:696–713`) calls
`DemoScene.botPlacement` per living bot per tic; `DemoScene.placement:960` builds
a `float[16]` literal and `Mat4.ofRowMajor:80` **copies it defensively into a
second `float[16]`** before `Mat4.ofRowMajor:82` wraps it. Three objects, 176
bytes, per bot per tic — 1,232 B/tic for seven bots. `Mat4`'s immutability is
what makes it safe to hand to 21 raster workers, and the defensive copy is what
makes it immutable; the cost is paid 420 times a second.

**Third: the raster path is clean.** Across 921 sampled allocations there is not
one attributed to `Rasterizer`, `SpanRenderer`, `TextureSampler`,
`TriangleClipper`, `OutlinePass`, `Crosshair`, `Rgba`, `MipChain` or
`Framebuffer`. Per-triangle, per-tile, per-span and per-pixel allocation is
**zero, measured** — and `Framebuffer.clear`'s "runs every frame and allocates
nothing" (`Framebuffer.java:421`) and `Crosshair`'s `Arrays.fill`-per-row claim
(`Crosshair.java:219–220`) both hold. The three months of Javadoc discipline
around the inner loop worked exactly as advertised. The leaks are all at the
seams.

### 5.3 `StrictMath` allocates on JDK 21, and the project is built for 17

The JDK-21 TLAB profile showed **15.1% of all allocation as `double[]` from
`java.lang.FdLibm$Sin.compute` and `FdLibm$Cos.compute`** — 1,019 B/frame,
reached from `DemoScene.placement:958/:959`, `BotPattern.offsetX:81`,
`BotPattern.offsetZ:107` and `PlayerController.forwardVector:763`. On JDK 17
that class does not appear at all.

JDK 21 ported fdlibm from C to Java (JDK-8302027). The Java port's argument
reduction allocates a `double[]` per call. Measured directly with
`getThreadAllocatedBytes` over 10⁶ calls each, after warm-up:

| | JDK 17.0.19 | JDK 21.0.11 | JDK 25.0.3 |
|---|---|---|---|
| `StrictMath.sin`, arg > π/4 | **0.00 B/call** | 37.26 | 32.00 |
| `StrictMath.cos`, arg > π/4 | **0.00** | 32.00 | 32.00 |
| `StrictMath.sin(0.3)`, no reduction | **0.00** | 2.62 | 2.62 |
| `StrictMath.atan2` | 0.00 | ~0.00 | ~0.00 |
| `StrictMath.sqrt` | 0.00 | 0.00 | 0.00 |
| `Math.sin` (contrast, intrinsic) | 0.00 | 0.00 | 0.00 |

End to end, the same 480P configuration: **JDK 17 4,651 B/frame, JDK 21
5,964 B/frame.**

This matters more than 1 KB a frame, because the two rules collide.
`README.md` records that "`PlayerController` is checked at the constant-pool
level to prove it never references `java/lang/Math`, only `StrictMath`" — lockstep
P2P needs transcendentals to agree bit-for-bit. `STYLE.md` § 13.4 bans allocation
on a per-tic path. **On JDK 21+ those two rules cannot both be satisfied by
`StrictMath.sin`.** Nothing is broken today: `gradle.properties` pins
`org.openfps.java.version=17` and both modules set `sourceCompatibility = 17`.
But `gradlew` runs on whatever JDK is on `JAVA_HOME`, which on this machine is
21, so **the JVM that runs the game is already the one that allocates.**

The fix, if the project ever needs one, is not to abandon determinism — it is a
hand-rolled fixed-point or table-driven sine that is deterministic *by
construction*, which `common/FixedMath` already has the machinery for. Recording
the collision is the useful part; § 9 prices it.

### 5.4 Per-shot and per-tic, against what the source claims

Everything below was found by reading and is consistent with the profile:

- **Per tic, always:** 1 `InputState` (32 B), 8 objects / 264 B for the camera
  (`aimCamera` → `PlayerController.camera` → 2 `Vec3` from `eyePosition`/
  `forwardVector`, 4 more inside `Camera.create`, a `float[12]`, a `Camera`),
  22 objects / 1,280 B for bot placements, up to 36 objects / 2,112 B for
  effects. **~52 objects / ~2,808 B/tic** with seven living bots and effects in
  flight.
- **Per shot** (≤5/s, `FIRE_INTERVAL_TICS = 12`): 2 `Vec3` + `Target[7]` +
  7 `Target` ≈ 376 B. Bot shots add 328 B at 2.8/s.
- **Per rendered frame on `main`:** 2 capturing lambdas × ~4.5 polls
  (`GdxInputPort.isHeld:730`, `isJustPressed:740`), and one `DirectIntBufferS`
  from `pixels.asIntBuffer()` at `FramebufferPresenter.java:482`.
- **Per tic on the bus:** a `TickEvent`, a `RenderFrameEvent` and a
  `LinkedBlockingQueue$Node` each — the event-driven architecture's own floor,
  ~146 B/frame.
- **Networked (not exercised here):** `NetSession.trimmed` (`NetSession.java:494`)
  allocates a `byte[20 + 12W]` **per peer per tic** — at 7 peers, `W = 8`,
  60 Hz that is 420 arrays/s ≈ 57 KB/s, plus one `byte[]` per received datagram
  from `socket.receive()`. That would roughly double the current rate.

The `Vec3` accounting specifically, since the source draws attention to it:
`forwardVector()`/`eyePosition()` are called **twice per tic** from
`aimCamera` and **twice per shot** from `fireIfRequested`, and the per-tic call
drags four more `Vec3` out of `Camera.create`. **Six `Vec3` per tic, two per
shot.** The render path itself allocates **zero** `Vec3` in the game, because
`DemoGameplayPort` sets an explicit camera and `SoftwareRenderPort.cameraFor`
(`:2010–2013`) returns it untouched; the 7-per-frame orbit-camera path at
`:2061/:2065` is reached only by `--model=` mode and the offline preview tools.

---

## 6. The claims audit

### 6.1 Claims that hold

Verified by reading and confirmed by the profile's silence:

| Claim | Where |
|---|---|
| "Nothing is allocated after construction… one fixed scratch polygon sized at construction is sufficient forever" | `TriangleClipper.java:76–83` |
| "One instance serves every tile of every frame, which is also why it allocates nothing" | `SpanRenderer.java:112–114` |
| "None per frame. The tile job is a field… the inner loop holds only primitives" | `OutlinePass.java:156–159` |
| "It owns no state, allocates nothing" / "`Arrays.fill` per row… no allocation of any kind" | `Crosshair.java:17–19`, `:219–220` |
| "Nothing here allocates" | `TextureSampler.java:23–29` (the *justification* "takes primitives" is wrong — five methods take a `MipChain` — but the conclusion is right) |
| "Runs every frame and allocates nothing" | `Framebuffer.java:421`, and `copyColorTo` at `:745` |
| "pre-allocated so the per-frame sort allocates nothing" — the insertion sort over `translucentOrder`/`translucentDepth` | `SoftwareRenderPort.java:504–506`, sort at `:1669–1700` |
| "Three `int[]` rings are allocated once… `64 × 8 × 12 B = 6 KB`" — arithmetic verified exactly | `TicCmdBuffer.java:30–37` |
| "`fire` allocates nothing… the answer is written into a caller-owned `HitResult`" | `Hitscan.java:118–124` |
| "the update path allocates nothing, touches no clock" — `PlayerController.update` | `PlayerController.java:28–30` |
| "one view is created and re-pointed with `wrap`, so the whole adapter costs a single object for the life of the session" | `PlayerInputView.java:25–31` |
| "A tic where no trigger is pulled allocates nothing at all" — true of `Match.tick` itself | `Match.java:30–38` |
| "hiding costs one reference store and no allocation at all" | `DemoEffects.java:289–291` |
| "allocates a fresh array each call — about 8 KB… not on any per-tic or per-frame path" | `BlasterSound.java:185–188` |
| "`String.format` allocates, and this draws every frame while it is on" | `DebugOverlay.java:54` — the only claim in the repository that volunteers a cost rather than denying one, and it is honest: the overlay measured within noise of free |

### 6.2 Claims that are FALSE

**F1 — `DemoGameplayPort.java:80–83`.** "**The only per-tic allocation is the
`Camera`**, which `render/README.md` § 4 explicitly sanctions."

Measured: ~52 objects / ~2,808 B per tic. The `Camera` is 8 of those objects and
264 of those bytes — and `DemoScene.placement`/`Mat4.ofRowMajor`, reached from
the same `tick`, are **32% of the entire process's allocation**. Off by 52× on
object count. This is the most consequential false claim in the repository
because it is the one a reader would trust to skip the tick path.

**F2 — `DemoGameplayPort.java:689–695`.** "**it is one reference store per bot
per tic.** The Scene itself is untouched."

`publishBotPlacements` calls `match.bots()` → `bots.clone()` (`Match.java:340`),
then per living bot `DemoScene.placement:960` + `Mat4.ofRowMajor:80` +
`Mat4.ofRowMajor:82`. **22 objects / 1,280 B per tic**, and the profile puts all
three lines in its top four.

**F3 — `DemoEffects.java:79–85`.** "**A tic allocates one `Mat4` per *visible*
effect and nothing else.**"

Wrong twice. One "`Mat4`" is three objects / 176 B, because
`Mat4.ofRowMajor:80` copies the caller's `float[16]`. And a visible puff
publishes `PUFF_LOBES = 3` instances (`DemoEffects.java:778–782`), so it is
three `Mat4` = 9 objects / 528 B. Worst case 36 objects / 2,112 B per tic against
a claimed 6. The same file states the honest version at `:124` ("one more `Mat4`
per tic per visible **puff**" — per *lobe*), so the two contradict each other.

**F4 — `SoftwareRenderPort.java:237–239`.** "**a scene swap that stays within the
high-water mark allocates nothing at all.**"

Only `growBuffersFor` is gated on the high-water mark (`:1090–1093`). `bindScene`
(`:947`) then allocates unconditionally on every swap: `new float[opaque.length ×
12]` (`:964`), `new Mat4[worldInstanceCount()]` (`:972`), `new float[hand.length ×
12]` (`:973`), two `int[instances.length + 1]` (`:1123`), `new MipChain[total]`
(`:1177`), `new Instance[…]` (`:1222`, `:1233`), plus two `int[triangles]` per
distinct model (`:1266`, `:1286`) and seven arrays in `bindTranslucent`
(`:997–1011`). For the demo's 340 instances that is hundreds of arrays.
The same Javadoc says the opposite 200 lines earlier at `:839–843` ("the
per-instance tables… are rebuilt every time"). The defensible claim is "does not
re-grow the **geometry** buffers".

**F5 — `SoftwareRenderPort.java:836–838`.** "**This is the only allocation site
outside `resize`.**"

Two allocation sites are inside the per-frame path. `Rasterizer.java:955`
(`binEntries = new int[running]`) is reached from `renderPass` → `setupAndBin` →
`buildBinOffsets` on any frame that bins more (triangle, tile) pairs than any
before it. `Rasterizer.java:456–458` (three `int[chunkCount × tiles]`) is reached
from `renderPass` → `beginFrame`, and because `growBuffersFor` constructs a
*fresh* `Rasterizer` with `tileCount == 0` (`:1112`), it fires **on a frame**
after every buffer grow — 60,528 bytes at 720P/21 workers. `Rasterizer`'s own
Javadoc admits both at `:161–167` while its headline sentence, "Nothing is
allocated per frame", is contradicted by its own third clause.

**F6 — `net/README.md:550–551`.** "Six kilobytes for the entire input ring,
allocated once at init **from `I_MemoryPort`**."

`TicCmdBuffer.java:69–72` uses plain `new int[…]`. Nothing in `engine/net`
imports `I_MemoryPort`. The 6 KB and the "steady-state per-tic allocation is
zero" halves are both correct; the provenance is invented. Worth fixing because
`README.md` advertises "Every allocation in the engine goes through
`I_MemoryPort`" with exactly one sanctioned exception, and this is a second one
nobody declared.

**F7 — `net/README.md:474–476` and `RedundantSender.java:51–52`.** "**neither the
send nor the receive path allocates.**"

True of `RedundantSender` in isolation, false of the shipped path:
`NetSession.trimmed` (`:494`) allocates per packet per peer per tic and
`socket.receive()` (`:427`) returns a fresh `byte[]` per datagram.
`NetSession.java:487–491` admits the copy; the README generalises past it. The
options table at `net/README.md:566` compounds it with "Alloc/packet 0 (reused
direct buffer)" — true of `DatagramChannel`, false of the `byte[]`-based
`I_DatagramPort` contract layered over it.

**F8 — `net/README.md:100–116`.** "Nothing in the running engine constructs a
`PeerConnection`, a `TicCmdBuffer` or a `RedundantSender`… **No socket is
opened.**"

Stale, and contradicted by line 13 of the same file. `NetSession` constructs a
`TicCmdBuffer` at `:89` and a `PeerConnection` at `:205`, calls
`RedundantSender.packWindow` at `:479`, and binds a socket at `:170–176`.

**F9 — narrower absolutes that are wrong as written**, all off the hot path but
all stated without qualification: `TicCmdBuffer.java:63–65` "This is the only
allocation the class ever performs" (`get` allocates a `TicCmd` at `:339`, and
the method's own Javadoc at `:325` says so); `PhysicsWorld.java:179–180` "That
copy is the only allocation this class ever performs" (`solid(int)` at `:418`,
`Builder` at `:238`/`:477`/`:518`); `TicCmd.java:196–199` "the only method here
that allocates".

**F10 — `SoftwareRenderPort.java:228–230`.** "`render/README.md` § 4 explicitly
sanctions ("Build one per frame")."

The quoted string is not in `render/README.md`. It is in `Camera.java:91`. The
substance is defensible; the citation is fabricated — which matters in a
repository whose contribution rules require Javadoc to cite sources.

**F11 — `OutlinePass.java:150–154`.** "`SoftwareRenderPort` does not run the pass
at all unless `Scene#hasTaggedEntities()`."

It gates on `worldEntityIds != null && outlineEnabled && aimedEntityId !=
Scene.UNTAGGED` (`:1403`), and `SoftwareRenderPort.java:1143–1148` argues at
length that answering from `hasTaggedEntities()` would be *wrong*. Not an
allocation claim, but the named condition is not the one in the code.

### 6.3 Misleading rather than false

**`DemoGameplayPort.java:573–576`** — "**Two Vec3 allocations per SHOT, not per
tic.**" Literally true of lines 578–579. Twelve lines below, `aimCamera` calls
the *same two accessors* every tic and drags four more `Vec3` out of
`Camera.create`. **The per-tic camera costs three times as many `Vec3` as the
per-shot path the file goes out of its way to defend.**

**`net/TicCmdEncoder.java:52–55`** — the "flat 'no `java.lang.Math`' guard… so
that the check needs no exceptions to remember". The guard is per-class, over six
classes. Live `java.lang.Math` calls exist in `AckWindow:201`,
`RedundantSender:84`, `PeerConnection:255`, `DemoEffects:804`, `Vec3:140`,
`InputState:127`. None is a determinism defect — integer ops, correctly-rounded
`sqrt`, non-lockstep audio — but "flat rule, no exceptions" does not describe the
repository.

---

## 7. GC observations on a real run

JDK 17, **default G1 and default heap** — what a player's JVM actually does.
`MaxHeapSize` resolved to 4,217,372,672 B (¼ of 16 GB RAM). Two 3,600-frame runs
(~62 s, ~3,680 tics each):

| Run | GC | Uptime | Cause | Heap | Pause |
|---|---|---|---|---|---|
| 480P | GC(0) | 0.711s | Young (Concurrent Start), G1 Humongous Allocation | 133M→33M | 6.351 ms |
| | GC(2) | 8.586s | Young (Normal), G1 Evacuation Pause | 81M→50M | 6.593 ms |
| | — | 8.6s → 62s | **nothing** | | |
| NATIVE | GC(0) | 0.807s | Young (Concurrent Start), G1 Humongous Allocation | 133M→32M | 6.899 ms |
| | GC(2) | 8.862s | Young (Normal), G1 Evacuation Pause | 92M→62M | 6.904 ms |
| | — | 8.9s → 62s | **nothing** | | |

**Two collections, both inside the first nine seconds, then roughly 3,100
rendered frames with no pause at all.** Corroborated by the very first
measurement taken for this document — Serial GC, `-Xmx2g`, 300 frames: two young
GCs, at 0.596 s and 1.350 s, and none thereafter.

Both early collections are startup, not gameplay. GC(0)'s cause is *humongous
allocation* — a G1 region here is 2 MiB and every buffer in § 3.1 is larger than
half of one, so each full-frame array is allocated straight into humongous
regions. GC(2) at ~8.6 s reclaims the ~30 MB of model-loading garbage
(`Files.readAllBytes` per `.ofm`, `ModelFormat.sliceLevels`).

**The frame-time conclusion:**

- At ~300 kB/s and G1's adaptive young gen (30 MB reclaimed at GC(2)), the
  engine earns a young collection roughly **once every 100 seconds — about every
  6,000 frames**.
- The pause is **6.4–6.9 ms**. Against `docs/ASSETS.md` § 2's p99 of 7.06 ms at
  720p/8 workers, a pause landing on the worst frame gives ~14 ms — **inside a
  16.7 ms budget. 60 Hz survives it.**
- At **120 Hz the budget is 8.33 ms and a 6.6 ms pause does not fit.** One
  dropped frame every ~12,000 at 120 Hz is not a defect worth engineering
  around, but it is the honest description, and it is the reason the § 9 list is
  worth doing at all rather than a reason it is urgent.
- The 600-frame harness in the brief will normally see **zero** steady-state
  collections. That is a fine smoke test and a useless GC benchmark; use
  Epsilon and read the slope.

---

## 8. Steady-state footprint against the platforms

| Configuration | Pre-allocated (render) | + textures | Measured live heap | Verdict |
|---|---|---|---|---|
| Desktop 480P | 12.68 MB | 33.3 MiB | **55.4 MiB** | Trivial against a 4 GiB default max |
| Desktop 720P/NATIVE | 24.94 MB | 45.0 MiB | **69.7 MiB** | Trivial |
| Phone 480P | 15.09 MB | 35.6 MiB | not measured | Fits a 96 MB growth limit with room |
| Phone 720P | 30.44 MB | 50.3 MiB | not measured | Fits, tight on a 96 MB device |
| Phone NATIVE | **65.03 MB** | **83.2 MiB** | not measured | **Does not fit a 96–128 MB growth limit.** `largeHeap` is load-bearing, and the manifest under-counts the reason by 31 MB |

---

## 9. What would actually be worth fixing

Ordered by measured benefit per unit of risk. Together, items 1–4 remove ~62% of
all steady-state allocation and would take the engine to roughly 1.9 KB/tic.

| # | Fix | Wins | Cost |
|---|---|---|---|
| **1** | **`WorkerPool.awaitWork`: replace `idleLock` + `idleCondition.await(50ms)` with a per-worker `LockSupport.parkNanos`.** `AQS$ConditionObject.await` allocates a `ConditionNode` per park and the contended `lock()` adds an `ExclusiveNode` | **~1,900 B/frame, 42%** — the single largest allocator in the process. Also removes 21-way contention on one lock | One class, ~30 lines. Needs care: the existing three-condition recheck under the lock (`:540`) is what makes a signal unmissable, and a park/unpark rewrite has to keep that. Highest value and the only item on this list with real concurrency risk |
| **2** | **Give `I_RenderPort` a `setWorldTransform(int, float[])` that copies from a caller-owned `float[16]`**, so `DemoScene.placement` and `DemoEffects` can fill reusable scratch instead of building a literal that `Mat4.ofRowMajor` then copies | **~1,450 B/frame, 32%.** Removes 2 of the 3 objects per placement and all of the defensive copies | A port-contract addition. `Mat4`'s immutability is deliberate — it is what makes a transform safe to hand to 21 workers — so the overload must copy into port-owned storage, not alias the caller's array. Medium |
| **3** | **`GdxInputPort.isHeld` / `isJustPressed`: replace `binding -> isDown(input, binding)` with a plain loop or a non-capturing predicate** | ~195 B/frame, 4.3% | Two methods, both private, no contract change. The cheapest real win here |
| **4** | **`FramebufferPresenter`: hoist `pixmap.getPixels().asIntBuffer()` into a field, refreshed on resize** | 39 B/frame per *platform* frame | One line. `asIntBuffer()` allocates a view object every frame for no reason |
| **5** | **`Match.bots()`: stop cloning.** `DemoGameplayPort` calls it every tic purely to iterate | 39 B/frame | Add a non-copying accessor, or have `DemoGameplayPort` hold the roster it already receives. Trivially safe; the clone is defending against a caller that does not exist |
| **6** | **Correct F1–F11 in § 6.2.** Zero runtime effect | The reason this audit was needed. A false "allocates nothing" is worse than no comment: it tells the next reader not to look | Comment edits. Do this one first — it costs nothing and it is what makes items 1–5 discoverable |
| **7** | **Drop `Framebuffer.entityIds`, or fold it into the depth buffer's spare bits, or allocate it only for tagged scenes.** Memory, not rate | **10.4 MB at phone NATIVE, 3.7 MB at 720P** | The Javadoc at `Framebuffer.java:94–97` argues it is allocated unconditionally so it need not be published safely to workers mid-frame — a good reason. But it is a third of what pushed Android into `OutOfMemoryError`, and § 4's real 62 MB makes the trade worth re-opening |
| **8** | **Record the `StrictMath` collision** (§ 5.3) in `STYLE.md` § 13.4 and `net/README.md` | 1,019 B/frame *if* the project moves off JDK 17 | A paragraph now. A deterministic fixed-point sine later, if it ever has to be paid — `common/FixedMath` already has the shape for it. Do not silently switch to `Math.sin`: that trades 1 KB/frame for a lockstep desync |

Explicitly **not** worth fixing: the event bus's `TickEvent` +
`RenderFrameEvent` + two `LinkedBlockingQueue$Node` per tic (~146 B/frame, 3%).
Pooling them means either mutable events or a ring buffer, and both trade the
architecture's clearest property — an event is an immutable value on a shared
queue — for 3% of a number that is already small enough not to trigger a
collection inside 100 seconds.

---

## 10. What could not be measured

Stated plainly so nothing above reads as more certain than it is.

1. **Nothing was measured on Android.** Every phone figure in § 3.5, § 4 and
   § 8 is *computed* from the same constants that were verified exactly against
   a desktop heap histogram (§ 3.1), and the one Android data point —
   10,368,012 bytes — is quoted from the manifest, not re-observed. The
   arithmetic closes on ART's 12-byte array header, which is good evidence the
   model is right, but **the growth-limit conclusion in § 8 is a prediction.**
   Running the `OpenFPS_API36` emulator with `adb shell dumpsys meminfo` at each
   `RenderMode` would settle it and was out of scope here.
2. **Native memory is not counted anywhere.** libGDX's `Pixmap`
   (`FramebufferPresenter.java:416`) is another `w × h × 4` bytes off-heap —
   10.4 MB at phone NATIVE — and LWJGL, GLFW, the GL driver and the uploaded
   texture are all outside the Java heap. `-XX:NativeMemoryTracking=summary`
   plus `jcmd VM.native_memory` would give the JVM's share; the driver's would
   need platform tooling. Every "footprint" figure here is **Java heap only**.
3. **Per-frame and per-tic could not be separated,** because they are the same
   event. Rendering is driven by `RenderFrameEvent` from `GameLoop`, so varying
   `--fps` moves both together. What *was* separated is per-*platform*-frame
   (vsync, 60 Hz, `main`) from per-*rendered*-frame, and the platform half
   measured within noise of zero. A build that decoupled them would be needed to
   do better, and that build does not exist.
4. **TLAB attribution is coarse in absolute bytes.** A 4 KiB refill is charged
   entirely to the stack that tripped it, so § 5.2's per-site bytes are shares
   scaled by a total, not direct measurements. The *shares* are corroborated by
   the independent per-thread split; the *absolutes* should be read to one
   significant figure.
5. **Run-to-run spread is ±20–30%** and is not fully explained. Five JDK 17
   runs of the same configuration gave 4,414–6,204 B/frame. The candidates are
   `binEntries` high-water growth (`Rasterizer.java:955`) creeping as the camera
   finds new (triangle, tile) maxima, a varying number of live effects, and the
   number of idle worker parks tracking scheduler luck. **Any single measured
   figure in § 5.1 is worth ±25%, and the resolution-independence conclusion is
   drawn from the whole set, not from any pair.**
6. **The networked path is unexercised.** § 5.4's 57 KB/s for
   `NetSession.trimmed` is computed from `RedundantSender.packetBytes(W)` at
   7 peers and 60 Hz, not observed — no run here attached a `NetSession`. It
   would roughly double the total, which makes it the largest *unmeasured* risk
   in this document.
7. **The demo's match is lost after ~25 s at 60 Hz** (the bots shoot back and
   nothing shoots back at them in an unattended run), and `GameOverScreen`
   constructs a `SpriteBatch`. Every regression window above ends before that
   transition. **No figure here describes a match longer than about 25 seconds
   of play**, and long-run behaviour — texture churn on scene swaps, a slowly
   creeping `binEntries` — is therefore unmeasured.
8. **Tile size and worker-count interaction on the bin pools was not swept.**
   `render/README.md` § 11(c) already flags 64×64 as never measured for *speed*;
   it is equally unmeasured for memory, and § 3.3's `12 × workers` bytes per
   tile is arithmetic from the source rather than a measurement of alternatives.

---

## 11. Reproducing this

```powershell
# Absolute allocation rate. Read the slope of heap-used against uptime,
# in the window after warm-up and before the match is lost.
java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms1g -Xmx1g `
     -XX:EpsilonPrintHeapSteps=4096 -Xlog:gc:file=alloc.log:uptime,time `
     -Dopenfps.screenshotFrame=1300 -Dopenfps.screenshotExit=true `
     -Dopenfps.screenshot=C:\tmp\x.png -Dopenfps.renderMode=480p `
     -cp "desktop\build\install\desktop\lib\*" `
     com.openfps.desktop.DesktopLauncher --start-in-game --fps=60

# Attribution. Shrinking the TLAB is what makes the sample density usable.
java "-XX:StartFlightRecording:filename=a.jfr,jdk.ObjectAllocationInNewTLAB#enabled=true,jdk.ObjectAllocationInNewTLAB#stackTrace=true,jdk.ThreadAllocationStatistics#enabled=true,jdk.ThreadAllocationStatistics#period=1000ms" `
     -XX:FlightRecorderOptions=stackdepth=128 -XX:TLABSize=4k -XX:-ResizeTLAB `
     -cp "desktop\build\install\desktop\lib\*" com.openfps.desktop.DesktopLauncher --start-in-game
jfr print --json --events jdk.ThreadAllocationStatistics a.jfr

# Pre-allocation, verified rather than computed. Take two, at two render modes;
# the delta must equal 12 B per padded pixel + 12 B per visible pixel.
jcmd <pid> GC.class_histogram
```

`gradlew :desktop:run` cannot do any of this: `run` is a `JavaExec` and
`desktop/build.gradle.kts:105–115` forwards `openfps.*` system properties only,
so a `-Xlog` or `-XX:` flag on the Gradle line lands on the daemon. Use
`:desktop:installDist` and launch the JVM yourself, or add `jvmArgs` forwarding
alongside the existing `systemProperty` loop.
