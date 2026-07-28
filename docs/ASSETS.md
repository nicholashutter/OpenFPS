# Game Data and Asset Licensing

> Policy document. What game data OpenFPS may legally ship, what technical shape it
> must be in, and how it reaches a developer's machine.
>
> **Every asset entering this repository must satisfy §3 (licensing) and §5 (budget).**
> No exceptions without a recorded decision in this file.

---

## 1. Why this document exists

OpenFPS is MIT-licensed and distributed as a binary on Windows, Linux, and eventually
Android. Anything we ship alongside that binary — models, textures, sound — is
redistributed by us, to everyone, forever. Getting that wrong is expensive to unwind:
by the time art is embedded in levels, referenced by code, and shipped in a release,
removing one badly-licensed texture can mean rebuilding a level.

So the licensing decision is made **before** any asset lands, and it is deliberately
conservative.

---

## 2. Render target — the constraint everything else follows from

The renderer is a **multi-threaded software triangle rasterizer**: z-buffer,
perspective-correct interpolation, mipmapped bilinear sampling, 32-bit colour, glTF
models with baked lighting.

Software rasterization is retained from the original DOOM-inspired design. The 1993
constraints layered on top of it are not — the 8-bit palette, the 320×200 framebuffer,
the visplane/column renderer, and billboard sprites were VGA-era compromises, not
properties of software rendering.

> **This section is the canonical render target.** The implementation
> specification derived from it — pipeline stages, edge functions,
> perspective-correct interpolation, mip selection, tile binning, every citation,
> and the open questions — is
> `engine/src/main/java/com/openfps/engine/render/README.md`. `PLAN.md` § 3.3
> and § 7 Phase 5 track it. Where any of those disagree with this section, this
> section wins and the other document is the bug.

### Per-frame budget — MEASURED

The original estimates in this section were derived from first principles and have
now been **measured**. They were optimistic by roughly 2–3×. The measured numbers
replace them; the estimates are kept alongside so the size of the error stays visible.

Hardware: Intel Core Ultra 7 155H (16 physical / 22 logical), Temurin OpenJDK 17,
1080p at a measured 2.00× overdraw. Span figures are min-of-9 at single-thread turbo.

| Quantity | Estimated | **Measured** | Verdict |
|---|---|---|---|
| Perspective-correct, mipmapped, bilinear span | ~3–8 ns/px | **21.1 ns/px** | Optimistic ~3–7× |
| 1080p @ 2× overdraw, single core | ~20 ms | **87.4 ms** | Optimistic 4.4× |
| 1080p @ 2× overdraw, 8 workers | ~3–4 ms | **14.9 ms** | Optimistic ~4× |
| 1080p @ 1× overdraw (culled), 8 workers | — | **10.3 ms** | |
| Triangle setup | 200–500 ns | **26–64 ns** | Pessimistic 4–8× |
| Per-triangle raster-phase cost | not estimated | **~600–900 ns** | The real per-triangle cost |

> **These rows were corrected once, and how they were wrong is worth keeping.**
> The synthetic benchmark first reported 46–48 ms single-core and 8.2 ms at 8
> workers. Those numbers were **internally inconsistent with its own per-pixel
> figure**: 2,073,600 px × 2 overdraw × ~19 ns is 79 ms, not 46. The real
> renderer, measured end to end on the same CPU, reports 87.4 ms and 21.1 ns/px
> — and 4.147M × 21.1 ns = 87.5 ms, which closes. The per-pixel figure was right
> all along; the frame-time rows were not, and nobody noticed until an actual
> frame was drawn.
>
> The lesson is cheap to state and was expensive to find: **a benchmark's rows
> should be checked against each other, not just against expectations.** These
> figures come from the shipping pipeline drawing a real model, so they are the
> ones to trust.

**The clock is the thing to understand before reading any of this.** The test
machine runs 4.75–5.44 GHz on one thread but averages ~3.1 GHz across all 22
(P-cores ~4.0, E-cores ~2.3). Every per-core figure degrades ~40% the moment the
renderer actually goes wide, and naive speedup ratios are meaningless — an early
run showed "162% efficiency", which is impossible and was pure clock artifact.
**Cycles/pixel is the portable number**: it held stable across runs where ns/pixel
moved 35%. Clock-adjusted, tiled scaling measures at ~100% efficiency, so the
decomposition is sound — the hardware simply does not provide 22 full-speed cores.

### What actually fits in 60 Hz

Allowing a 10 ms renderer budget inside a 16.7 ms frame. **Revised downward
after the end-to-end measurements above replaced the synthetic ones.**

The governing fact is that a first-person view is nearly 100% screen coverage —
you are always looking at a wall, a floor or the sky — so pixel fill, not
triangle count, sets the floor. At 1080p with backface culling that floor is
**10.3 ms at 8 workers**, which consumes the entire 60 Hz renderer budget before
a single additional triangle is considered.

| Target | Verdict |
|---|---|
| **1080p @ 60 Hz** | **Does not fit.** Fill alone is 10.3 ms culled, 14.9 ms at 2× overdraw |
| **1080p @ 30 Hz** | Comfortable — 33 ms frame, fill is a third of it |
| **720p @ 60 Hz** | **The target.** 720p is 44% of 1080p's pixels: ~4.6 ms culled, ~6.6 ms at 2× overdraw, leaving real headroom for geometry |
| **1080p @ 60 Hz, nearest-neighbour** | Plausible — dropping bilinear cuts the span cost 2.9× |
| 4-core minimum spec | 720p only |

**720p at 60 Hz is the default the engine should ship**, with 1080p offered at
30 Hz or with bilinear disabled. That is a materially different conclusion from
the one this document reached an hour earlier, and it comes from measuring the
real pipeline rather than a synthetic harness.

The 720p rows were originally **scaled by pixel count** from the 1080p
measurements rather than measured. They have since been **measured on the real
demo scene** (294 world instances, 1777 triangles, 1280×720): **4.7 ms at 8
workers, 3.9 ms at 16**, against a serial 22.1 ms. So 720p60 holds comfortably,
with roughly 2× headroom inside the 10 ms budget.

### Full resolution and worker sweep — measured 2026-07-28

The demo scene (294 world instances, 1777 triangles after clipping) on an Intel
Core Ultra 7 155H (16 physical / 22 logical). p50 over 270 timed frames at 720p,
170 at the higher resolutions. The default pool is `logicalProcessors / 2` = 11
workers here, which sits between the 8 and 16 columns.

| workers | 0 (serial) | 1 | 2 | 4 | 8 | 16 |
|---|---|---|---|---|---|---|
| **1280x720** | 26.1 ms | 25.1 | 16.3 | 8.5 | 5.2 | 4.0 |
| speed-up | 1.00x | 1.04x | 1.60x | 3.09x | 5.03x | 6.55x |

| resolution | 8 workers | 16 workers | ns per pixel @16 |
|---|---|---|---|
| 1280x720 (0.92 Mpx) | 5.2 ms | 4.0 ms | 4.33 |
| 1920x1080 (2.07 Mpx) | 14.4 ms | 10.2 ms | 4.90 |
| 2560x1440 (3.69 Mpx) | — | 19.6 ms | 5.31 |

**Per-pixel cost rises 23% from 720p to 1440p**, so the renderer scales slightly
*worse* than pixel count (2.25x the pixels costs 2.55x the time; 4x costs 4.91x).
The likely cause is the working set leaving cache: colour + depth is 8 bytes per
pixel, so the framebuffer alone is 7.4 MB at 720p, 16.6 MB at 1080p and 29.5 MB
at 1440p, against 24 MB of L3 on this part. 720p fits, 1440p cannot.

**Two conclusions worth acting on:**

1. **1080p60 is genuinely marginal, not merely tight.** 10.2 ms at 16 workers is
   already the whole 10 ms renderer budget, and p99 was 16.1 ms — inside the
   16.7 ms frame with nothing left for simulation or presentation. 1080p is a
   30 Hz option, or a 60 Hz option only once bilinear can be switched off.
2. **A low-core machine cannot hit 720p60, and this is the portability risk.**
   At 2 workers the frame is 16.3 ms — it misses 60 Hz outright. Under the
   `logicalProcessors / 2` rule that is any 4-thread device. A modern 8-core
   phone gets 4 workers and 8.5 ms, which holds; a 4-core one does not. The
   Android target should measure before assuming.

All frame-time figures in this document are **p50**. Reproduced independently on
2026-07-28 at 8 workers over 270 timed frames: best 3.85 ms, **p50 4.86 ms**,
p90 5.73 ms, p99 7.06 ms, mean 4.97 ms. The 4.7 above and the 4.9 quoted in
`PLAN.md`, `README.md` and `AGENTS.md` are the same statistic from two different
runs on the same machine — treat ~0.2 ms as ordinary run-to-run variance, not as
a discrepancy between documents. Quote p50 and say so; `docs/ASSETS.md` § 2 is
the reason best-of-N is not used anywhere here.

Those figures postdate a fix worth knowing about, because the earlier ones were
measured against a renderer whose parallel path was *slower* than its serial
path. Two faults compounded: the pipeline ran per model instance, crossing 1180
parallel barriers a frame, and the barrier join used a timed park that Windows
rounds up to its 15.6 ms timer period. Batching the pass to 8 barriers and
replacing the park with a yield took the demo from 2.9 fps windowed to a
vsync-limited 60. See `render/README.md` § 5 and § 7.

The old "~50–100k triangles/frame" ceiling was never a 60 Hz 1080p figure and
now looks optimistic even at 720p. **Kenney's kits remain comfortably inside
whatever the real budget turns out to be** — a 368-triangle blaster is the
scale this art direction actually operates at, which is exactly why it was the
right choice.

### The one big lever

| Variant | ns/px | vs full |
|---|---|---|
| Full: perspective + mip + bilinear + z | 21.4 | — |
| **Nearest-neighbour instead of bilinear** | **7.3** | **2.9× faster** |
| Affine instead of perspective-correct | 19.7 | 8% faster |
| Single L2-resident texture | 8.2 | 2.6× faster |
| Material-sorted spans | 17.7 | 17% faster |

**Bilinear filtering costs 2.9× the entire rest of the inner loop.** It is the only
large quality/performance knob, which makes it the natural setting to expose to
players and the obvious first sacrifice on weak hardware.

Perspective correction is nearly free at 8%, so it is never worth trading. The
per-8-pixel divide optimisation buys nothing measurable — the FP divider is not
the bottleneck, memory and the bilinear load/ALU work are. **The headroom is in
memory layout, not arithmetic**: storing textures pre-swizzled in 2×2 blocks so a
bilinear quad lands in one cache line is where the 2.6× single-texture gap points.

**Permanently out of reach**, regardless of optimisation effort: real-time shadow maps,
many per-pixel dynamic lights, post-processing stacks (bloom / SSAO / TAA), and 4K.
Normal mapping is borderline — roughly 3× per-pixel cost; possibly affordable at 720p,
not at 1080p.

> **Caveats the benchmark author flagged, which keep these numbers honest.** The
> harness's front end (setup and binning) ran single-threaded, overstating frame
> time by up to ~7 ms at 100k triangles — the raster-phase figures are the
> trustworthy ones, and the triangle budgets above may improve once that is
> parallel. Its span-extent code did three float divides per scanline where a
> production rasterizer steps edges incrementally, so part of the 600–900 ns
> per-triangle cost is the harness. The 50%-z-reject result showing *no* saving
> used random per-pixel depths, which defeats branch prediction; real occlusion is
> spatially coherent and early-z should do better. Run-to-run spread is ±20–40% at
> high triangle counts.

### What that means for assets

Two consequences drive every recommendation below:

1. **Low-poly is a requirement, not a style preference.** The triangle ceiling is a hard
   number.
2. **PBR map sets are dead weight.** With no per-pixel lighting, normal / roughness /
   metallic / AO maps have nothing to feed. We take **albedo only** and bake lighting
   offline. This also cuts asset size roughly 4× versus a naive PBR import.

---

## 3. Licensing policy

### Accepted

| License | Obligation | Notes |
|---|---|---|
| **CC0 1.0** | None | Preferred. Public-domain equivalent |
| **MIT** | Retain notice | |
| **BSD 2/3-clause** | Retain notice, no endorsement | Discharged by `NOTICE` |
| **Public domain** (verifiable) | None | Provenance must be documented |

### Rejected

| License | Why |
|---|---|
| **CC-BY** | Attribution bookkeeping in perpetuity — every asset, author, and license URL must stay accurate in a shipped credits screen forever. One missed entry is a license violation |
| **CC-BY-SA** | Share-alike attaches to derivatives, and our build-time preprocessing (§4) *creates* a derivative. Contaminates the asset tree |
| **GPL / LGPL assets** | Copyleft obligations conflict with MIT distribution |
| **"Free for commercial use, no redistribution"** | Common on itch.io. Outright incompatible — we redistribute the asset files themselves, which is precisely what this forbids |
| **Unlicensed / unclear** | Absence of a license is not permission |

CC-BY is the notable exclusion — it would roughly triple the available pool. It is
rejected because the obligation is unbounded in time and silently breaks: nothing fails
the build when a credits entry goes stale.

### Sources

All CC0. Verified against each project's stated license at the time of writing.

| Source | Ships | Fit | Use for |
|---|---|---|---|
| **[Kenney](https://kenney.nl/assets/category:3D)** | GLB, OBJ, FBX | **Best** — low-poly, one shared texture atlas per pack | **Primary art direction.** Weapons, characters, level kits, props |
| [Quaternius](https://quaternius.com/) | GLB / glTF | Excellent — low-poly, same philosophy | Characters, creatures, nature, vehicles |
| [KayKit](https://kaylousberg.com/) | GLB / glTF | Excellent — animated low-poly characters | Player and enemy characters with rigs |
| [Poly Haven](https://polyhaven.com/) | glTF, textures, HDRIs | Good, requires downsampling — 8K source | Environment textures; HDRIs for offline light baking |
| [ambientCG](https://ambientcg.com/) | PBR material sets | Good, albedo only | Wall / floor / surface textures |
| [Khronos glTF-Sample-Assets](https://github.com/KhronosGroup/glTF-Sample-Assets) | glTF / GLB | Renderer conformance fixtures | **Mixed licenses — verify per model** |

**Kenney is the spine of the art direction.** The low-poly GLB plus shared-atlas
structure is close to ideal here: low triangle counts and small textures are exactly
what the per-frame budget wants, so the technically correct choice and the aesthetically
coherent one coincide.

FPS-relevant packs: Blaster Kit (40 assets, sci-fi weapons, animated), Prototype Kit
(145 assets, greybox level geometry), Modular Space Kit, Factory Kit, Modular Dungeon
Kit, Blocky Characters.

Standardising on one source family also buys **visual coherence for free** — the thing
most asset-mixed indie projects fail at. Mixing Quaternius and KayKit with Kenney works
because all three target the same low-poly flat-shaded look; mixing in photoscanned
Poly Haven models would not.

---

## 4. Runtime format — preprocess at build time

**glTF is not parsed at runtime.**

glTF is JSON plus binary buffers, and this project has no JSON library. But the stronger
argument is architectural: **a software rasterizer's scarcest resource is per-frame CPU
and its cheapest is build-time CPU.** Everything expensive moves offline.

A Gradle build-time converter turns upstream glTF into a flat binary format the engine
reads with near-zero parsing. Build-time tooling runs on the Gradle buildscript
classpath, so it may freely use a glTF/JSON library **without adding a runtime
dependency or shipping anything** — satisfying the `AGENTS.md` rule against new external
libraries.

Moved offline: triangulation, index and vertex-cache optimisation, mipmap chain
generation, texture decode to the renderer's texel layout (below), normal computation,
bounding-volume precompute, material flattening, and lightmap baking.

The converter is `GltfConverter`; the runtime format it emits is `ModelFormat`. Both
are Phase 5 lanes — see `render/README.md` § 1 and § 10.

### Texture channel order — decided

**One layout end to end: `RGBA8888`, the same as the colour buffer.** This section
previously said BGRA, which was written before the colour buffer format was settled.

RGBA wins for an asymmetric reason rather than a preference. The **colour buffer's**
format is constrained from outside — it has to match what the presentation path
uploads, and that is libGDX `Pixmap.Format.RGBA8888`. The **texture's** format is
entirely ours: it is produced by our own build-time converter, so changing it costs
one line in a tool that has not been written yet. When one side is pinned by an
external contract and the other is free, the free side moves. Any mismatch would
otherwise be paid for as a per-texel swizzle in the hottest loop in the engine, to
buy nothing.

> **Verify this empirically; do not assume it.** A Java `int[]` reaching the GPU as
> bytes goes through a byte-order step, and whether `0xRRGGBBAA` arrives as R,G,B,A
> depends on the upload path and the platform's endianness. The converter, the
> sampler, and the presentation blit must agree on one concrete layout, and the way
> to establish that is a round-trip test — write a known texel, present it, read it
> back, assert the colour — not a reading of this paragraph. Do that once, early, in
> the `Framebuffer` lane; it is a class of bug that otherwise surfaces as
> everything-looks-slightly-wrong much later.

---

## 5. Asset budget

Enforced by the converter. Derived from §2.

| Budget | Cap | Rationale |
|---|---|---|
| Texture resolution | **512²** (256² for Kenney atlases) | Cache locality dominates the inner loop. Poly Haven's 8K source must be downsampled |
| Triangles per model | **~1,500** (was ~5,000) | Tightened by the §2 measurements. A 60 Hz 1080p scene affords ~10–20k triangles *total*, so a 5,000-triangle model meant two to four objects on screen. Kenney's kits sit well under this already — the cap constrains imports from elsewhere, not the primary art direction |
| Texture channels | **Albedo only** | No per-pixel lighting to consume normal/roughness/metallic/AO |
| Mipmaps | **Pre-generated, required** | Un-mipmapped minification is both slow and aliased |
| Total payload | **~20–50 MB** | Byte-identical across platforms; Android ships a trimmed subset |

---

## 6. Distribution

Assets are **not committed to git**. Tens of megabytes that change rarely and
delta-compress poorly would make every clone permanently heavy.

Upstream sources are **mirrored into our own release artifact** rather than fetched from
kenney.nl or polyhaven.com at build time. Third-party download URLs are unpinnable and
couple our build to their uptime. CC0 explicitly permits redistribution, so we publish
the curated, preprocessed payload as a versioned GitHub release on this repository — one
URL we control, one SHA-256, reproducible forever.

This is a concrete, practical dividend of the CC0-only floor that a CC-BY policy would
not have given us cleanly.

Fetch with:

```powershell
.\gradlew.bat fetchAssets
```

The task verifies SHA-256 and **fails the build on mismatch**. It is deliberately *not*
wired into `build` — assets are opt-in, and CI must stay hermetic.

---

## 7. Asset intake checklist

Before anything enters the tree:

- [ ] **Source URL** recorded
- [ ] **Author** recorded
- [ ] **License and license URL** recorded — must be on the §3 accepted list
- [ ] **Retrieval date** recorded
- [ ] **SHA-256** of the original download recorded
- [ ] Fits the §5 triangle and texture budgets, or a downsampling step is defined
- [ ] For per-file-licensed sources (Freesound, Khronos samples): license verified for
      **that specific file**, not the site as a whole

Record provenance **at import time**. Six months later it is effectively unrecoverable,
and an asset with no provenance has to be treated as unlicensed and removed.

---

## 8. Legal traps

Each of these is a plausible mistake for a contributor or an AI agent working in this
repository.

1. **Do not copy code from DOOM, Chocolate Doom, PrBoom+, DSDA-Doom, or SLADE.** All are
   **GPL-2**. Copying into an MIT codebase is a license violation. Reading a GPL
   *implementation* to learn a file format is legally murky; reading a published
   *specification* is not. **Cite specifications, not source repositories.**

2. **"DOOM" is an id Software / Bethesda trademark.** Fine in design docs as
   architectural inspiration. Not fine in a product name, splash screen, or store
   listing.

3. **Khronos glTF-Sample-Assets is not uniformly CC0.** Licenses vary per model. Verify
   each one used.

4. **Build-time GPL tooling is fine.** Running a GPL tool to produce an asset does not
   license the asset under GPL. Do not vendor GPL source into this repository.

5. **Preprocessing creates a derivative work.** Downsampling, re-encoding, and mipmapping
   all produce derivatives — which is why share-alike licenses are rejected in §3.

6. **"Free for commercial use" ≠ redistributable.** Using an asset in a game and
   shipping the asset file itself are different permissions. We need the second.

7. **No IWADs, ever.** The DOOM shareware IWAD is not freely redistributable under our
   terms and the retail IWADs are commercial. This project ships no id Software content.

---

## 9. Open items

- ~~Benchmark the textured-span inner loop~~ — **done.** §2 now carries measured
  numbers, and §5's per-model triangle cap was tightened from 5,000 to 1,500 as a
  direct consequence. The estimates were optimistic by 2–3×; the architecture
  survives, but 60 Hz at 1080p buys ~10–20k triangles rather than 50–100k.
  **Still unmeasured**, because the benchmark did not vary them: the 64×64 tile
  size and the 8/16-pixel span subdivision in `render/README.md` § 7 and § 8. The
  span subdivision now looks unlikely to matter — the per-8-pixel divide
  optimisation measured as no better than a per-pixel divide, since the FP divider
  is not the bottleneck.
- **Sort draws by material.** Measured at 17% off the span loop, essentially free
  to implement, and it compounds with the texture-swizzle idea in §2. Worth doing
  once `Rasterizer` exists.
- ~~Decide the texture channel order~~ — **decided**: `RGBA8888` end to end, §4. What
  remains is the round-trip test that proves the converter, the sampler and the
  presentation blit agree on the concrete byte layout. Do it in the `Framebuffer` lane.
- **Publish the `assets-v1` release.** Until it exists, `fetchAssets` fails with an
  actionable message. See the task's header comment in `build.gradle.kts`.
- ~~Curate the initial pack selection from §3 and record each entry against the §7
  checklist~~ — **done for the first-person demo.** Blaster Kit 2.1 and Prototype
  Kit 1.0, both CC0, nine models converted and verified. The §7 record lives in
  `docs/DEMO_ASSETS.md` rather than here, because a per-asset manifest grows and
  this file is policy. Remaining packs from §3 — Modular Space Kit, Factory Kit,
  Modular Dungeon Kit, Blocky Characters — are still uncurated.
- **One atlas per model, not per pack.** The nine demo models total 12.7 MB, of
  which ~12.6 MB is **nine identical copies of the same 512² atlas and its mip
  pyramid**. `ModelFormat` has no shared-texture concept, so the one-atlas-per-kit
  structure §3 praises Kenney for is paid for once per model. Nine models is
  already a quarter of §5's 20–50 MB payload cap, so this will not survive a real
  level. The fix is a shared-texture section or an atlas-by-reference indirection
  in `ModelFormat`. Measured, not estimated — see `docs/DEMO_ASSETS.md` § 4.

Two further open questions belong to the renderer rather than to asset policy, but
are recorded here because both were surfaced by the §2 render target and one of them
concerns this document's consequences directly:

- **Framebuffer allocation vs. `I_MemoryPort`** — `render/README.md` § 11a.
- **What the WAD subsystem is for now.** §10 records why Freedoom was rejected; the
  consequence is that a built, tested, 101-test WAD subsystem has no art left to read,
  because this document moves all art to preprocessed glTF. Its remaining role —
  map/level geometry container, generic asset container, or a format the project drops
  later — is undecided. Nothing is deleted. `render/README.md` § 11b states the
  options.

---

## 10. History

**2026-07-28 — First art landed: Blaster Kit 2.1 and Prototype Kit 1.0.**
Both CC0, verified on the download page *and* against the `License.txt` inside
each archive. Nine models converted and rendered: one blaster as the viewmodel,
eight Prototype Kit pieces as level geometry. Full §7 provenance — URLs, SHA-256
digests, retrieval date, triangle and texture counts — is in
`docs/DEMO_ASSETS.md`, along with the one command that rebuilds every `.ofm`.

Three things this settled, all measured rather than assumed:

1. **The §5 triangle cap does not bind this art direction.** Across the *entire*
   two packs — 187 models — the worst case is 882 triangles, well under 1,500.
   §2's claim that "Kenney's kits remain comfortably inside whatever the real
   budget turns out to be" holds.
2. **The packs' 512² atlases sit exactly at the §5 texture cap**, power-of-two,
   and mip cleanly to 10 levels. No downsampling step was needed.
3. **Payload size, not triangle count, is the constraint that bites first** —
   see the new §9 item. This was not anticipated by §5, which reasoned about
   texture *resolution* and never about how many times one texture is stored.

Also recorded because it looks like a converter bug and is not: Kenney's GLBs
reference their atlas by relative URI (`Textures/colormap.png`) rather than
embedding it, so the atlas must sit beside each `.glb`. Resolving a relative URI
against the referring document is what the glTF 2.0 specification requires, so
the fix belongs in staging, not in `GltfConverter`.

**2026-07-27 — Freedoom evaluated and rejected.**
[Freedoom](https://freedoom.github.io/) was the leading candidate before the render
target was settled: BSD-3-clause, complete, actively maintained, and already in the
exact WAD format the original design targeted (24 MB for `freedoom1.wad` +
`freedoom2.wad`, 11 MB for the FreeDM deathmatch set). It remains an excellent choice
for a DOOM-architecture engine.

It has no role here. Its content is 128×128 palette-indexed textures and BSP-compiled
2.5D sector maps — the renderer described in §2 consumes neither. Recorded so the
evaluation is not repeated.
