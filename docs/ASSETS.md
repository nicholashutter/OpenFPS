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

### Per-frame budget

Estimates, from first principles. **Not yet measured — see §9.**

| Quantity | Estimate |
|---|---|
| Perspective-correct, mipmapped, bilinear textured span | ~3–8 ns/pixel |
| 1080p @ 2× overdraw (~4.1M px) | ~20 ms single core, **~3–4 ms across 8 workers** |
| Triangle setup | ~200–500 ns each |
| **Practical ceiling** | **~50–100k triangles/frame** |

That lands near Quake 3 / early-2000s fidelity at 1080p.

**Permanently out of reach**, regardless of optimisation effort: real-time shadow maps,
many per-pixel dynamic lights, post-processing stacks (bloom / SSAO / TAA), and 4K.
Normal mapping is borderline — roughly 3× per-pixel cost; possibly affordable at 720p,
not at 1080p.

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
generation, texture decode to raw BGRA, normal computation, bounding-volume precompute,
material flattening, and lightmap baking.

---

## 5. Asset budget

Enforced by the converter. Derived from §2.

| Budget | Cap | Rationale |
|---|---|---|
| Texture resolution | **512²** (256² for Kenney atlases) | Cache locality dominates the inner loop. Poly Haven's 8K source must be downsampled |
| Triangles per model | **~5,000** | Keeps scene totals inside the 50–100k/frame ceiling |
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

- **Benchmark the textured-span inner loop.** Every number in §2 and §5 is an estimate
  derived from first principles, not a measurement. A throwaway Java benchmark
  confirming the ~3–8 ns/pixel figure on real hardware is a few hours of work and
  de-risks the entire render architecture. **Do this before Phase 5 commits.**
- **Publish the `assets-v1` release.** Until it exists, `fetchAssets` fails with an
  actionable message. See the task's header comment in `build.gradle.kts`.
- **Curate the initial pack selection** from §3 and record each entry against the §7
  checklist in this file.

---

## 10. History

**2026-07-27 — Freedoom evaluated and rejected.**
[Freedoom](https://freedoom.github.io/) was the leading candidate before the render
target was settled: BSD-3-clause, complete, actively maintained, and already in the
exact WAD format the original design targeted (24 MB for `freedoom1.wad` +
`freedoom2.wad`, 11 MB for the FreeDM deathmatch set). It remains an excellent choice
for a DOOM-architecture engine.

It has no role here. Its content is 128×128 palette-indexed textures and BSP-compiled
2.5D sector maps — the renderer described in §2 consumes neither. Recorded so the
evaluation is not repeated.
