# `:tools` — build-time asset tooling

> **Nothing in this module is ever shipped.** It converts art offline, verifies
> it against the budget, and renders preview frames without a display. No class
> here reaches a runtime classpath, and the build fails if one ever does.

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING |
| **Phase** | 5 (§ 7) — `GltfConverter` is a Phase 5 lane; the preview harnesses grew out of it |
| **Tests** | 73 |
| **Registered** | not registered — build-time only; nothing depends on `:tools` |
| **Verified** | 2026-07-28 |

**Built.** `GltfConverter` (glTF 2.0 / GLB in, `ModelFormat` out) with
`GltfAsset`, `GltfAccessor` and `GltfException` behind it; `ModelBuilder`,
`MipGenerator`, `ProceduralRoom` and `CubeModel` producing `.ofm` bytes;
`AssetBudgetException` failing the build on an over-budget asset; `FramePng` and
`ToolPool` shared by the two preview harnesses; and four entry points —
`GltfConverterMain`, `DemoAssetsMain`, `RenderPreviewMain`, `DemoPreviewMain`.

**Not built.** Nothing outstanding.

**Blocked on.** Nothing.

**Next step.** Nothing outstanding. This module moves when the renderer or the
asset budget does.

## Why an unshipped module exists at all

`docs/ASSETS.md` § 4: a software rasterizer's scarcest resource is per-frame CPU
and its cheapest is build-time CPU. So triangulation, scene flattening, material
flattening, texture decode, mip generation and budget enforcement all happen
here, once, offline — and the runtime reads a flat binary with near-zero
parsing. glTF is never parsed at runtime.

That also buys a licensing result. Gson (Apache-2.0) parses the JSON chunk,
which `docs/ASSETS.md` § 4 sanctions **because** build-time tooling ships
nothing. If any of this were on the runtime classpath that argument would
collapse.

## The one-way dependency edge

```
:tools  ──────►  :engine          (and nothing points back)
```

`:tools` depends on `:engine` so `ModelBuilder` can write against
`ModelFormat`'s own layout constants, `FramePng` against `Rgba`'s packing, and
the preview harnesses against `Camera` / `Framebuffer` / `SoftwareRenderPort`.
A second copy of the vertex stride or the RGBA byte order is exactly the
divergence `AGENTS.md` rule 1 exists to prevent — `ModelFormat` owns the layout
and the reader but deliberately not the writer, and this module is the other
half of that split.

Nothing depends on `:tools`. That is what keeps it, and Gson, off every shipped
classpath, and it is **enforced rather than trusted**: the root project's
`verifyToolsIsolation` task resolves the shipped runtime classpath and throws if
any artifact name starts with `tools-` or `gson-`. It is wired into `:tools`'s
own `check`, so a plain `gradlew build` fails the moment someone adds
`implementation(project(":tools"))` to `:engine`, `:desktop` or `:android`.

(`:tools` does pull `logback-classic` at `runtimeOnly`, so the converter's
diagnostics appear when Gradle runs it. Same reasoning: this module's classpath
is never a shipped one.)

## Gradle tasks

None of these is wired into `build`. They write files, and `docs/ASSETS.md` § 6
keeps generated art out of git while CI stays hermetic.

| Task | Required property | What it does |
|---|---|---|
| `regenerateDemoAssets` | `-PkenneyRaw=<dir>` (else it warns and falls back) | stages the curated CC0 selection, converts it, then reads every `.ofm` back and checks the budget |
| `verifyModels` | — | the read-back-and-check half alone, for a payload already converted |
| `convertModels` | — (`-PmodelsIn` / `-PmodelsOut` optional) | converts every `.gltf` / `.glb` under the input tree |
| `renderPreview` | `--args="--out=<png>"` | orbits one model, writes a PNG, no window and no GL |
| `demoPreview` | **`-PdemoOut=<dir>`, mandatory** | renders the whole demo — room, props, viewmodel — to PNGs |

`-PkenneyRaw` names a directory holding each pack unzipped into a subdirectory
of its own (`<kenneyRaw>/prototype-kit/Models/GLB format/...`). The download
stays a manual step: `docs/ASSETS.md` § 6 forbids build-time third-party
fetches, because those URLs are unpinnable and their uptime is not ours.
Without it, `DemoAssetsMain` falls through to `ProceduralRoom` and says so at
`WARN` — a generated greybox room is a floor to stand on, but presenting one as
Kenney art would falsify the provenance record § 7 requires.

`-PdemoOut` is mandatory **and must point outside the repository**. There is no
default on purpose; a defaulted output path is how generated art ends up
committed.

Conversion and verification are one step in `regenerateDemoAssets` because a
model that converts without throwing is not a model that works: the converter's
budget checks run on in-memory totals, which proves the geometry was in budget,
not that the bytes on disk parse back into the same geometry. Reading every
output through the runtime's own `ModelFormat` in the same process closes that
gap and means nobody can regenerate and skip the check.

## `demoPreview` reports percentiles, and that is why it found the bug

`measure` prints p50, p90 and p99 rather than best-of-N, and that choice is
load-bearing. Adding workers used to make the demo scene *slower*:

```
workers      1        2        4        8       16
before    20.9 ms  30.3 ms  31.1 ms  30.6 ms  31.1 ms
after     19.8 ms  11.0 ms   6.3 ms   4.7 ms   3.9 ms
```

Two faults, neither visible in a minimum. `SoftwareRenderPort` ran its
four-stage pipeline once per instance — roughly 1,180 `submitParallel`
publish/join boundaries a frame for a 295-instance room; it batches a whole pass
now and pays eight. And `WorkerPool`'s batch join fell back to a *timed* park,
which on Windows cannot resolve faster than the 15.6 ms platform timer period.
**The tell was in the distribution and nowhere else**: frame times sat on exact
multiples of 15.6 ms while the best frame was 4 ms. A best-of-N number would
have looked healthy throughout. (`DemoPreviewMain`'s Javadoc calls this a 7x
error against its own table; `DesktopLauncher`'s `RendererHolder` records the
windowed cost as "fifteen times slower".)

`--threads` still defaults to 0 — the serial path is the reference the parallel
result is compared against — but it has not been the fastest setting since.

## Files

- `gltf/` — `GltfConverter` (43 tests, with `GltfFixtures`), `GltfAsset` (GLB and
  `.gltf` containers, buffer and URI resolution), `GltfAccessor` (accessor → flat
  arrays), `GltfException` (malformed or unsupported input)
- `model/` — `ModelBuilder` writes the `.ofm` image (14 tests), `MipGenerator`
  builds 2x2 box-filtered pyramids (11 tests), `ProceduralRoom` is the greybox
  room of last resort (5 tests), `CubeModel` is a closed textured cube for
  proving backface culling
- `AssetBudgetException.java` — fails the build instead of silently fixing the asset
- `FramePng.java` — `0xRRGGBBAA` → `TYPE_INT_ARGB`, in one place so the two tools agree
- `ToolPool.java` — a standalone `WorkerPool`, or none when `threads <= 0`
- `GltfConverterMain` / `DemoAssetsMain` / `RenderPreviewMain` /
  `DemoPreviewMain` — the four entry points

`AGENTS.md` forbids `public static void main` outside core packages. That rule
guards `:engine` against entry points competing with `EngineMain`; it has no
purchase on an unshipped module whose reason to exist is being invoked by
Gradle. Each `main` sits in its own class so the libraries stay libraries.

**73 tests in this module.** Run with `.\gradlew.bat :tools:test`.
