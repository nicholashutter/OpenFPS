# Render (R_) — Multi-threaded Software Triangle Rasterizer

> R_ takes the current game state and produces a finished framebuffer. It is
> pure math over primitive arrays — no window, no graphics API, no GPU. The
> platform adapter uploads the finished buffer; R_ never presents.

**This document is the Phase 5 specification.** `docs/ASSETS.md` § 2 is the
canonical statement of the render target; this file is its implementation
spec. Where the two disagree, `docs/ASSETS.md` wins and this file is the bug.

---

## 0. What this replaces

An earlier draft of this file specified a 1993-era 2.5D renderer: a 320×200
framebuffer, an 8-bit palette, BSP front-to-back traversal for visibility,
visplanes, a per-column renderer, and affine texture mapping.

**That design is retired.** It was a set of VGA-era compromises, not properties
of software rendering, and it cannot consume the art the project has actually
committed to — a visplane and a column renderer cannot draw a Kenney GLB model.
`docs/ASSETS.md` § 2 and § 10 record the reasoning and the Freedoom evaluation
that followed from it.

What survives, and in what form:

| Retired thing | Status |
|---|---|
| BSP for renderer visibility | Retired. The z-buffer does this job now. **BSP itself is not deleted** — see § 10 |
| Sutherland-Hodgman | **Survives, repurposed** — near-plane clipping in homogeneous space, not 2D frustum clipping of wall segments. See § 6 |
| `TextureSampler` | **Survives, rewritten** — mipmapped bilinear, not nearest-neighbour column sampling |
| Visplanes, `ColumnRenderer`, `WallClipper`, affine mapping, 8-bit palette, fixed 320×200 | Gone |

---

## 1. Components

These are the Phase 5 implementation lanes. Each owns one thing; the "does not
own" column is as load-bearing as the "owns" column, because overlapping
ownership is how a rasterizer turns into an unmaintainable blob.

| Component | Owns | Does not own |
|---|---|---|
| `Scene` | The immutable draw list: world instances (`model` + `modelToWorld`) and view instances (`model` + `modelToView`). Built once, never per frame | Drawing, culling, ordering. It is data |
| `Framebuffer` | Colour buffer, depth buffer, dimensions, tile geometry, `clear()` | Any drawing. It is storage plus a tile map |
| `Camera` (+ transform math) | View matrix, projection matrix, world → clip-space transform, frustum parameters | Clipping, rasterizing. It produces clip-space vertices and stops |
| `TriangleClipper` | Near-plane clipping in homogeneous clip space (Sutherland-Hodgman), attribute interpolation along clipped edges, fan re-triangulation | The perspective divide, the viewport transform |
| `Rasterizer` | Perspective divide, viewport transform, backface cull, edge-function setup, screen-space bounding box, **binning triangles to tiles** | The per-pixel inner loop |
| `SpanRenderer` | The inner loop: per-pixel or per-segment perspective-correct interpolation, depth test, depth write, colour write | Which triangles it draws, and where. It is handed a triangle and a tile rectangle |
| `TextureSampler` | Bilinear filtering, mip level selection from UV derivatives, texel fetch and unpack | UV computation. It receives finished `(u, v, lod)` |
| `ModelFormat` | The flat binary runtime format: layout, versioning, reader. Near-zero parsing at load | Producing that format |
| `GltfConverter` | **Build time only.** glTF/GLB → `ModelFormat`. Triangulation, mip generation, texture decode, budget enforcement | Anything at runtime. It is never on the runtime classpath |

`GltfConverter` runs on the Gradle **buildscript** classpath, so it may freely
use a glTF/JSON library without adding a runtime dependency or shipping
anything — see `docs/ASSETS.md` § 4. Everything expensive moves offline,
because a software rasterizer's scarcest resource is per-frame CPU and its
cheapest is build-time CPU.

### Subsystem layout

```
render/
├── port/
│   └── I_RenderPort.java     interface — called by core per tic
└── adapter/
    ├── NullRenderPort.java   stub
    ├── Rgba.java             the one definition of the 0xRRGGBBAA format
    ├── Framebuffer.java      colour + depth buffers, tile geometry
    ├── Vec3.java, Mat4.java  minimal transform math
    ├── Camera.java           world → clip space
    ├── TriangleClipper.java  homogeneous near-plane clipping
    ├── MipChain.java         a texture and its pre-generated mip levels
    ├── TextureSampler.java   bilinear + mip selection
    ├── Rasterizer.java       setup, binning, tile dispatch
    ├── SpanRenderer.java     the per-pixel inner loop
    ├── Scene.java            world + view instance draw list
    └── SoftwareRenderPort.java  the real I_RenderPort
```

**Landed:** every component in § 1 — `Scene`, `Framebuffer`, `Camera`,
`TriangleClipper`, `Rasterizer`, `SpanRenderer`, `TextureSampler`,
`ModelFormat`, `GltfConverter` — plus `SoftwareRenderPort` tying them together
and the supporting `Rgba`, `Vec3`, `Mat4`, `MipChain`, and the `WorkerPool`
prerequisite in § 7.

**Binning is per *chunk*, not per worker.** § 7 originally said per-worker; the
implementation improved on it. A chunk is one `submitParallel` index, so it runs
exactly once and never concurrently with itself — which makes its bin region
private without needing any thread identity at all. That matters because the
participating caller has no worker id, so a per-worker scheme could not have
been expressed. Counting per (chunk, tile) → serial prefix sum → scatter, with
the prefix sum walking tiles outermost so a tile's entries form one contiguous
run in ascending triangle order.

**All eight components have landed, and the pipeline is wired end to end.**
`SoftwareRenderPort` implements `I_RenderPort`, `GameLoop` drives it, and
`:desktop` presents the result. A real Kenney model (Blaster Kit, 368 triangles,
textured) renders correctly both to a window and to a PNG.

**What integration found that unit tests could not.** This section previously
said to expect exactly this, and it is worth recording what actually turned up,
because both bugs were invisible to a passing 748-test suite:

1. **`RenderFrameEvent` had no producer.** The event class, `EventFactory`
   method, `SubsystemId.R_` target and `RenderSubsystem` branch all existed and
   were tested. Nothing ever published one, so R_ had never been invoked at all.
   `GameLoop` now publishes one per tic, and `GameLoopRenderEventTest` asserts it.
2. **The world was mirrored** — see the § 4 correction. A wrong basis order that
   a unit test had *locked in*.

3. **A pool defect the renderer surfaced but did not cause — now fixed.**
   `pool.shutdown()` drained the bus and *immediately* declared the pool
   terminal. But draining **delivers** the queued events rather than discarding
   them, so trailing `RenderFrameEvent`s were dispatched to a pool that had
   already refused further work, and every clean exit threw twice. The fix was a
   missing state, not a renderer workaround: `WorkerPool` now has `DRAINING`
   between `RUNNING` and `SHUTDOWN`, and `submitParallel` is legal there. Any
   fan-out subsystem would have hit this; the renderer was simply the first.

`GltfConverter` lives in the separate `:tools` module, never on the runtime
classpath; `verifyToolsIsolation` proves it mechanically on every build.

`Rgba` deserves a note, since it is not in the § 1 component table. `Framebuffer`
and `TextureSampler` were built in parallel and each grew its own identical copy
of pack/unpack. Two definitions of one pixel format is exactly what `AGENTS.md`
rule 1 forbids, and had they drifted the symptom would have been a channel swap
that reads as slightly-wrong colour rather than an error — in the one place every
pixel passes through both. Use `Rgba`; do not add a third copy.

---

## 2. Numeric policy — the renderer uses `float`

**The renderer uses `float`. Gameplay and networking stay 16.16 fixed-point
(`PLAN.md` § 4 is unchanged).**

This looks like an inconsistency and it is not. Record the reasoning here,
because a future contributor will otherwise "fix" it in one direction or the
other and break something.

**1. Fixed-point exists for simulation determinism, not for speed.** Peer-to-peer
lockstep requires that two peers at the same tic hold bit-identical simulation
state. Fixed-point integer arithmetic guarantees that trivially. That is the
entire reason `FixedMath` exists. It is not there because floats are slow — on
any CPU this project targets, `float` multiply is at least as fast as the
shift-and-`long`-multiply that 16.16 requires.

**2. Rendering never feeds simulation state.** R_ is a pure function from game
state to pixels. Nothing it computes is ever written back, sent over the wire,
or read on the next tic. **A fully non-reproducible renderer cannot desync
lockstep**, so the renderer has no reason to pay fixed-point's cost — neither
its precision cost (16.16 has ~5 decimal digits, hopeless for a 1/w that spans
several orders of magnitude across a frame) nor its range cost.

**3. Java 17 floating point is always-strict anyway.** Since **JEP 306**,
delivered in Java 17, all floating-point expressions are FP-strict IEEE 754;
`strictfp` became a no-op keyword. So `+ - * /` and `Math.sqrt` are
bit-reproducible across every conforming JVM and every CPU. This is stronger
than most people assume and worth knowing even though § 2 above means the
renderer does not depend on it.

The exception, and it is a real one: **`Math.sin`, `Math.cos`, `Math.tan`,
`Math.pow`, `Math.exp` and `Math.log` are permitted 1–2 ulp of error and are
explicitly NOT required to be reproducible between implementations.**
`StrictMath.*` is defined by fdlibm and is reproducible. The renderer calls
`Math.tan` once per frame when building the projection matrix (§ 4) — that is
fine precisely because of § 2. **If a transcendental is ever needed in
simulation code, it must be `StrictMath`.**

- JEP 306, Restore Always-Strict Floating-Point Semantics — https://openjdk.org/jeps/306
- JLS 17 § 15.4, FP-strict expressions — https://docs.oracle.com/javase/specs/jls/se17/html/jls-15.html#jls-15.4
- `java.lang.Math` javadoc, on the 1–2 ulp allowance and `StrictMath` — https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Math.html

---

## 3. Buffers

### Colour buffer — `int[]`, RGBA8888

One `int` per pixel, packed `0xRRGGBBAA`. The format is chosen to match libGDX
`Pixmap.Format.RGBA8888` so that a finished frame presents as **a single
texture upload** with no per-pixel conversion.

Indexing is `y * strideInPixels + x`. The stride is **not** necessarily the
width — see § 7 on false sharing.

### Depth buffer — separate primitive array

A separate `float[]`, one element per pixel, holding **1/w** (see § 8). Not
interleaved with colour. Two reasons:

- The two have different element semantics and different clear values, and
  keeping them separate lets each tile row be cache-line aligned independently.
- The depth clear value is `0.0f`, whose bit pattern is all zeros, so clearing
  the depth buffer is a memset the JIT turns into the fastest path available.
  (1/w = 0 means "infinitely far"; the test is *greater passes* — § 8.)

### Resolution

Not fixed. The framebuffer is allocated at the surface size reported by
`I_FrameCallback.onSurfaceReady(width, height)` and reallocated on
`onResize(width, height)`. `docs/ASSETS.md` § 2 budgets 1080p at 2× overdraw;
rendering at a lower internal resolution and letting the adapter scale is a
legitimate quality knob, not a fixed constraint.

> **Open question — how the framebuffer gets allocated. See § 11(a).**

---

## 4. Camera and transforms

### View space convention

This spec uses a view space with **+x right, +y up, +z forward**. Forward is
positive z, which means `w_clip` is positive in front of the camera and the
near-plane test in § 6 is simply `w > near`. Left-handed, and deliberately so —
it removes a sign flip from the hottest branch in the pipeline.

The view matrix is the inverse of the camera's world transform. For a rigid
camera (rotation `R`, position `eye`) that inverse is exact and cheap:
`V = Rᵀ · T(−eye)`. Do not call a general matrix inverse.

**Basis derivation — the operand order is normative:**

```text
right = normalize(forward × up)      // NOT up × forward
up    = right × forward
```

> **Corrected twice, and the second correction was found by looking at a
> rendered image rather than by reasoning.** An early draft left the order
> unpinned. It was then pinned — to `up × forward`, which is **the mirror**, and
> a test was written that locked the wrong answer in. It survived every unit
> test and was caught the first time a real model was drawn.
>
> Why `up × forward` is wrong, concretely. Camera at +z looking at the origin
> gives `forward = (0,0,−1)`, `up = (0,1,0)`:
>
> ```text
> up × forward = (1*(−1) − 0*0,  0*0 − 0*(−1),  0*0 − 1*0) = (−1, 0, 0)
> ```
>
> `right` is world **−X**, so world +x maps to camera-*left* and every model
> renders horizontally mirrored. With `forward × up` you get `(+1, 0, 0)`.
>
> A second case worth checking, because it catches a sign error the first
> permits: looking down +x, `forward × up = (0,0,+1)`. Physically — right-handed
> world, +x east, +y up, so +z is south — face east and your right hand points
> south. `right = +z` is what a person standing there would say.
>
> **This also delivers the left-handedness the section claims.** With the
> corrected order `right × up == −forward`, so `(right, up, forward)` is a
> left-handed triple in a right-handed world, which is exactly what "+x right,
> +y up, +z into the screen" means. The old order produced a *right*-handed
> triple while claiming left — the mirror was the claim failing, not a separate
> bug.
>
> **A mirrored render is nearly invisible.** Most game art is close to
> symmetric; the blaster that exposed this looked entirely correct until an
> asymmetric detail *within a single face* was checked. If this order is ever
> changed again, verify with an asymmetric marker, not with reasoning.

**`near` must be positive.** `1/w` is evaluated at `w = near`, so a zero or
negative near plane is a division by zero or a sign inversion, not merely a bad
view. Enforced in `Camera.create`.

### Projection

With vertical field of view `fovY` and `aspect = width / height`, let
`f = 1 / tan(fovY / 2)`:

```text
x_clip = x_view * f / aspect
y_clip = y_view * f
w_clip = z_view
```

**There is no third row.** A GPU needs `z_clip` because its fixed-function depth
unit consumes a window-space z in [0, 1]. We own the depth unit, we store 1/w,
and 1/w comes from `w_clip` alone — so the z row of the projection matrix is
dead weight and is simply not computed. This is a real simplification available
to a software rasterizer that a GPU pipeline cannot take.

**Consequence: "projection matrix" is a misnomer here, and § 1 keeps the term
only because it is what a reader searches for.** Once the z row is gone, what
remains is two scale factors. `Camera` therefore exposes `projectionScaleX()`
and `projectionScaleY()` and there is deliberately **no `projectionMatrix()`** —
handing back a `Mat4` with a zeroed row is an invitation to fill it back in.

**Clip space has three components: `x`, `y`, `w`. There is no `z`.** The valid
range is `-w ≤ x ≤ w` and `-w ≤ y ≤ w`; § 4's viewport formula only implies it,
so it is stated here. `Camera.CLIP_FLOATS == 3`, and the vertex layout the
clipper and rasterizer share is a flat `float[]` with stride `3 + attributeCount`
laid out `[x, y, w, attr...]`.

`Math.tan` is called once per frame here. See § 2 for why that is fine.

### Perspective divide and viewport transform

Applied by `Rasterizer`, **after** `TriangleClipper` (§ 6):

```text
invW = 1 / w_clip
sx   = (x_clip * invW * 0.5 + 0.5) * width
sy   = (0.5 - y_clip * invW * 0.5) * height
```

`sy` is flipped because the framebuffer's y grows downward while clip space's
grows upward. Doing the flip here, once per vertex, is why no later stage has
to think about it.

---

## 5. Pipeline order

A frame is **two passes over the same buffers**: world geometry, then a depth
clear, then view-space geometry. The second pass is what a first-person weapon
is drawn in.

**A pass batches every instance into one geometry stream.** This is not an
optimisation detail — it is the difference between threading helping and
threading hurting. An earlier revision ran the whole pipeline *per instance*,
so a 295-instance room crossed **1180 parallel barriers per frame** to
distribute a few dozen triangles each, and more workers made it slower. Now
transform and clip run across all instances into one shared stream, and setup,
binning and rasterization run once over the whole stream: **8 barriers per
frame, independent of instance count.** Each instance still gets its own packed
model→clip transform, which was always once-per-instance and cheap.

The buffers are therefore sized to the **scene's pass total**, not to the
largest single instance, and grow only when a bigger scene appears.

Ordering is preserved exactly: instances enter the stream in submission order
and tiles are binned in ascending stream index, so every pixel sees the same
triangles in the same order the per-instance path produced. That is why the
batched output is byte-identical to what it replaced — verified across
{0,1,2,4,8,16} workers on four scenes, 48 images, all matching.

```text
  Scene
    ├── world instances  (ModelFormat + modelToWorld)
    └── view  instances  (ModelFormat + modelToView)

  clear colour + depth
        │
        ▼
  ── WORLD PASS ── for each world instance ──────────────────
        │
        ▼
  Camera.packModelToClip(modelToWorld)         (once per INSTANCE)
        │                                       48 multiplies, not per vertex
        ▼
  model triangles (ModelFormat)
        │
        ▼
  Camera:          model → clip space          (per vertex, 12 mul + 9 add)
        │
        ▼
  TriangleClipper: near-plane clip             (per triangle → 0, 1 or 2 triangles)
        │
        ▼
  Rasterizer:      divide, viewport, backface cull,
                   edge setup, bounding box, BIN TO TILES
        │
        ▼   ── parallel, one worker per tile, exclusive ownership ──
        │
  SpanRenderer:    per tile, per binned triangle, per covered pixel:
                   interpolate, depth test, sample, write
        │
        ▼
  ── CLEAR DEPTH ONLY ───────────────────────────────────────
        │            colour is NOT cleared: the world stays
        │
        ▼
  ── VIEW PASS ── for each view instance ────────────────────
        │            Camera.packViewToClip — projection only,
        │            no view matrix: these are already in view space
        ▼
        (same clip → raster → span path)
        │
        ▼
  Framebuffer (finished)  →  adapter uploads  →  screen
```

**Why the depth clear rather than a compressed depth range.** A first-person
weapon is bolted to the camera, so it is naturally expressed in view space —
which makes its transform a fixed constant and skips the view matrix entirely.
But it sits centimetres from the eye and the world does not, so with a shared
depth buffer it punches through walls the moment the player stands near one.
Clearing depth between the passes makes the weapon unconditionally nearest,
costs one `Arrays.fill` of a buffer that is about to be written anyway, and is
what shipped first-person renderers have always done. A compressed depth range
would work too and is strictly more complex for no benefit here.

The view pass is skipped entirely when a scene has no view instances, so a
scene that is only world geometry pays nothing for this.

---

## 6. Near-plane clipping (Sutherland-Hodgman, homogeneous)

### Why only the near plane

The perspective divide is `1 / w_clip`. A vertex behind the eye has `w ≤ 0`, and
dividing by it produces garbage — geometry mirrored through the origin, or an
infinity. So a triangle crossing the near plane **must** be geometrically clipped
before the divide. There is no way to fix it up afterwards.

The other five frustum planes need no geometric clip at all. Once a triangle is
in screen space, left/right/top/bottom rejection is just intersecting its
bounding box with the screen rectangle — which the tile binning in § 7 does
anyway, for free. And there is no far plane: with 1/w depth, distant geometry
converges toward 1/w = 0 rather than overflowing.

So: **one clip plane, applied in homogeneous clip space.** This is why
Sutherland-Hodgman survives the retirement of the 2.5D design. It was always the
right algorithm; it was pointed at the wrong plane.

### The algorithm

Sutherland-Hodgman clips a polygon against one half-space in O(n) per plane,
walking edges and emitting kept vertices plus intersections. Against the plane
`w = near`, with vertex `a` inside iff `a.w > near`:

```text
clipNear(polygon, near):
    out = []
    for each edge (a, b) in polygon:          // b follows a, wrapping
        aIn = a.w > near
        bIn = b.w > near
        if aIn and bIn:
            out.append(b)
        else if aIn and not bIn:
            out.append(lerpVertex(a, b, (near - a.w) / (b.w - a.w)))
        else if not aIn and bIn:
            out.append(lerpVertex(a, b, (near - a.w) / (b.w - a.w)))
            out.append(b)
        // both outside: emit nothing
    return out
```

`lerpVertex(a, b, t)` interpolates **every** vertex attribute linearly by `t`:
`x, y, w` of the clip-space position, and `u, v`, and any baked colour.

**Linear interpolation is correct here and would not be correct after the
divide.** Clip-space position and all vertex attributes are affine functions of
the parameter along the edge in object space; the divide is what destroys that
linearity. Clipping before the divide is what lets one `lerp` handle position
and UVs identically. This is the whole reason the operation lives in homogeneous
space, and it is the point of the Blinn & Newell paper below.

### Output cases

Clipping a triangle against one plane yields 0, 3, or 4 vertices:

| Vertices inside | Output |
|---|---|
| 0 | 0 vertices — triangle rejected entirely |
| 1 | 3 vertices — 1 triangle |
| 2 | 4 vertices — fan-triangulate to 2 triangles: (0,1,2) and (0,2,3) |
| 3 | unchanged — 1 triangle, and the fast path. Take it with an early-out on `min(w) > near` |

The output is bounded at 4 vertices, so the clipper needs **no allocation** — a
fixed 4-vertex scratch buffer per worker is sufficient.

**The boundary rule is strict: a vertex is inside iff `w > near`.** A vertex
lying *exactly* on the plane is outside.

**A case the table above does not cover.** Because the rule is strict, "3 inside"
is not the only way to reach an unclipped triangle, and more importantly the
*2-inside* row is not the only way to reach 4 vertices: a triangle with two
vertices strictly inside and one exactly **on** the plane also emits 4, two of
which coincide. Fan-triangulating that gives one good triangle and one
**degenerate** one. This is harmless — § 7 mandates an `area2 == 0` reject, which
discards it — but it will look like a clipper bug to whoever hits it first, so it
is recorded rather than left to be rediscovered. Do not add a de-duplication
pass: that spends comparisons on every triangle to tidy a measure-zero case the
rasterizer already handles.

**Trivial reject.** § 6 gave the `min(w) > near` accept fast path; the symmetric
`max(w) ≤ near` reject costs the same and discards everything behind the camera
without touching the scratch buffer at all. Implement both.

**Snap the intersection vertex's `w` to exactly `near`.** The lerp produces
`near` give or take a few ulp, and the sign of that error decides whether a
freshly clipped vertex satisfies the very predicate that just admitted it.
Assigning `near` directly is algebraically identical and makes
`w ≥ near > 0` a guarantee the rasterizer can rely on, so `1/w` is always finite.

**Sources:**
- Sutherland & Hodgman, "Reentrant Polygon Clipping", *CACM* 17(1), 1974 — https://dl.acm.org/doi/10.1145/360767.360802
- Blinn & Newell, "Clipping using homogeneous coordinates", *SIGGRAPH '78* — https://dl.acm.org/doi/10.1145/800248.807398

---

## 7. Rasterization and tiling

### Edge functions

For a screen-space triangle with vertices `(x0,y0)`, `(x1,y1)`, `(x2,y2)`, the
edge function of edge `01` evaluated at a point is:

```text
E01(x, y) = (x1 - x0) * (y - y0) - (y1 - y0) * (x - x0)
```

Its sign says which side of the line `01` the point is on. A point is inside the
triangle when all three edge functions share a sign. This is Pineda's
formulation, and the reason it is the right one for this renderer is that it is
**linear in x and y**, so it steps incrementally:

```text
∂E01/∂x = -(y1 - y0)        // add this when moving one pixel right
∂E01/∂y =  (x1 - x0)        // add this when moving one pixel down
```

> **Corrected.** An earlier draft wrote this as
> `E01 = (x - x0)(y1 - y0) - (y - y0)(x1 - x0)`, which is the **negation** of the
> form above, and the `Rasterizer` implementation caught the contradiction. That
> version is inconsistent with the `area2` immediately below: evaluated at vertex
> 2 it yields `−area2`, so `e0 + e1 + e2 = −area2` and the barycentrics would sum
> to `−1` rather than `1`. Both statements could not be true at once. The form
> above is the orientation determinant, agrees with `area2`, and makes the λ
> assignment below correct as written. Verify with the unit triangle
> `(0,0), (1,0), (0,1)`: `area2 = 1`, `E01 = y`, `E12 = 1 − x − y`, `E20 = x`,
> summing to exactly `area2`.

Setup computes `E` once at the top-left corner of the bounding box and then the
whole traversal is adds. It also parallelizes trivially: `E` can be evaluated at
any pixel directly, with no dependence on neighbours, which is exactly what lets
a tile be rasterized independently of every other tile.

**Signed area and barycentrics.** Twice the signed triangle area is `E01`
evaluated at vertex 2:

```text
area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
```

`area2` is the backface cull test — one sign check, before any per-pixel work —
and a degenerate triangle has `area2 == 0` and must be rejected before it
divides by zero.

**Decided by measurement: `CullMode.CLOCKWISE`.** `Rasterizer` still takes a
required `CullMode` with no default, whose values name the **screen-space**
winding (`CLOCKWISE` = positive signed area with y growing downward) rather than
asserting which one is a back face; `SoftwareRenderPort.BACKFACE_CULL_MODE`
holds the engine's choice.

**How it was settled, because the method matters more than the answer.** A
z-buffer draws a *closed* mesh correctly with **no culling at all**, so
`CullMode.NONE` is an oracle that assumes nothing about winding. Render a
six-coloured cube three ways and the mode whose output is *pixel-identical* to
`NONE` is correct. The other mode does not error — it renders the cube's
**interior**, which looks like a perfectly plausible open box. That is why this
cannot be eyeballed on arbitrary geometry.

> **This value is NOT independent of § 4's basis order.** It was originally
> measured as `COUNTER_CLOCKWISE`, and flipped to `CLOCKWISE` when the mirrored
> basis was corrected — because a handedness change reverses screen-space
> winding. **If `Camera`'s basis ever changes, re-run the oracle. Do not edit
> the expectation to match.** Both the constant's Javadoc and the pinning test
> carry this warning.
>
> The flip count now resolves to two, cancelling: the view transform is
> orientation-reversing and the `sy` flip reverses again, so glTF's
> CCW-from-outside front face stays CCW on screen. That is recorded as a *check*
> on the measurement, not as its justification. With `e0 = E12(p)`, `e1 = E20(p)`, `e2 = E01(p)`, the
barycentric weights are:

```text
λ0 = e0 / area2      λ1 = e1 / area2      λ2 = e2 / area2      (λ0 + λ1 + λ2 = 1)
```

Compute `1 / area2` once at setup and multiply.

**Fill rule.** Adjacent triangles share edges. Without a rule, a pixel exactly on
a shared edge is drawn twice (visible with blending, and wasted work regardless)
or zero times (a seam of background pixels). Adopt the standard **top-left rule**:
a pixel on an edge is inside only if that edge is a top edge or a left edge.
This is a decision made once at setup by biasing each edge's constant term, so
the inner loop stays a plain sign test.

**The bias is not −1.** An earlier draft said to bias by −1, which is the
*fixed-point* subpixel idiom — in 16.16 or 4.8 subpixel coordinates, −1 is one
subpixel step and excludes exactly the on-edge case. This is a `float`
rasterizer (§ 2), where −1 is an entire pixel. Use a per-edge bias of `0.0f`
when the edge is owned and `Float.MIN_VALUE` when it is not; the test `e >= bias`
is then *exactly* `e >= 0` or `e > 0`, with no epsilon and no tolerance.

Exactness here has a prerequisite that is easy to miss: the two triangles
sharing an edge must produce edge functions that are **bitwise negations** of one
another, or the rule stops deciding the pixel and a seam reappears. Two
consequences follow, both load-bearing:

- Compute the edge constant in the cross-product form `px*qy − qx*py`, because
  IEEE subtraction is exactly antisymmetric. The algebraically identical
  `−(dx*px + dy*py)` is not, once rounding enters.
- **Evaluate** `dx*x + rowConst` per pixel rather than accumulating `e += dx`
  along the span. Accumulation drifts the two triangles' values apart by a few
  ulp, which is all it takes.

Assert this directly: a test should check that the shared edge function is
exactly `0.0f` at a pixel on the edge. Without that assertion, fill-rule tests
silently degrade into coverage tests and stop testing the rule at all.

### Bounding box

```text
minX = max(0, floor(min(x0, x1, x2)))
maxX = min(width  - 1, ceil (max(x0, x1, x2)))
minY = max(0, floor(min(y0, y1, y2)))
maxY = min(height - 1, ceil (max(y0, y1, y2)))
```

Clamping to the screen here is what makes the four side frustum planes free
(§ 6). An empty box after clamping means the triangle is off-screen — reject.

### Tile binning

The screen is divided into fixed-size tiles (**64×64 pixels is the starting
point**; it is a tuning parameter, and § 11 flags that none of these numbers are
measured yet). The tile grid is `ceil(width / TILE) × ceil(height / TILE)`.

Setup converts a triangle's clamped bounding box into a tile range and appends
the triangle's index to each covered tile's bin:

```text
tx0 = minX / TILE      tx1 = maxX / TILE
ty0 = minY / TILE      ty1 = maxY / TILE
for ty in ty0..ty1:
    for tx in tx0..tx1:
        bin[ty * tilesX + tx].append(triangleIndex)
```

Bounding-box binning over-includes: a thin diagonal triangle is binned into
corner tiles it does not actually touch. Those tiles reject it in one edge-
function test. Testing the triangle's edges against tile corners at bin time is
a known refinement — do not do it until the profile says to.

### The parallel model — tiled, exclusive ownership

**This is the design decision that makes the renderer lock-free.**

A pixel belongs to exactly one tile. A tile is drawn by exactly one worker. So
**two workers never write the same colour or depth address, ever.** From that
one invariant:

- No lock, no atomic, and no compare-and-swap on the depth buffer. The
  read-modify-write of the depth test — which is where a naive parallel
  rasterizer either serializes or races — is a plain array access, because it is
  provably uncontended.
- No barrier between triangles within a tile.
- The only synchronisation in the whole raster pass is one join at the end of
  the frame.

Exclusivity is a property **of the tile, not of the assignment policy**. Any
policy that hands each tile to exactly one worker preserves it.

**The policy is a shared atomic claim counter. This is forced, not preferred.**
An earlier draft of this section offered a static interleave (`worker w takes
tiles where index % W == w`) as the simpler starting point, and that was wrong:
a static interleave assigns tile sets by *worker id*, and **the participating
caller has no worker id** — nor is there any guarantee that the worker whose id
owns a given tile ever shows up. It is directly incompatible with the
caller-participation requirement below, which is a correctness constraint rather
than a tuning one. The claim counter is the only policy that satisfies both, and
it load-balances better anyway when tile costs are uneven, which they will be.

**Binning must not become the shared-write hazard the raster pass avoided.**
Setup is itself parallel (`docs/ASSETS.md` § 2 budgets 200–500 ns per triangle
against a 50–100k triangle ceiling — serial setup alone would consume the whole
frame). So each worker bins its slice of the triangle stream into its **own**
per-worker bin arrays, and the raster pass reads tile T by walking the W
per-worker bins for T in worker order. Lock-free on both sides.

**Determinism.** Bin lists are appended in a fixed triangle order and read in a
fixed worker order, so a tile's output does not depend on thread scheduling.
This matters because coplanar triangles at exactly equal depth would otherwise
resolve differently run to run. It is not required for lockstep — the renderer
cannot desync anything (§ 2) — but a renderer that flickers between runs is
miserable to debug.

**False sharing.** Tiles are rectangles in a linearly addressed buffer, so the
end of one tile's row and the start of its right-hand neighbour's row land in
the same 64-byte cache line — two workers writing the same line, which is the
classic false-sharing stall and would quietly eat most of the parallel win. The
cheap fix: make the **tile width a multiple of 16 pixels** (16 ints = 64 bytes)
and pad the framebuffer's **row stride** to a multiple of 16 pixels. This is why
§ 3 says stride is not necessarily width.

> **Correction — how much this actually guarantees.** An earlier draft of this
> section claimed "no line is ever shared". That is not achievable in Java and
> the implementation of `Framebuffer` found it. Padding buys *relative*
> alignment: every tile row starts a whole number of cache lines from the array's
> **first element**. Whether that element itself sits on a 64-byte boundary is
> the JVM's object layout, which Java offers no way to request or even observe.
>
> So: if the array base happens to be line-aligned, no line is shared. If it is
> not, adjacent tile rows share exactly **one** line at their boundary — instead
> of a number that varies with the window width. The padding is still worth
> doing, because it converts an unpredictable width-dependent amount of false
> sharing into a bounded, deterministic one. A hard guarantee needs an aligned
> allocator, which means native memory. Do not claim more than this in code
> comments.
>
> Second consequence, easy to miss: **the padded columns belong to no tile**, so
> the presentation adapter must not upload the raw `int[]` to a `width × height`
> texture — it would shear the image. `Framebuffer.copyColorTo` de-pads for that
> reason.

The alternative — per-tile contiguous (swizzled) buffers, de-swizzled once at
present — has better locality still, but it costs a full-frame shuffle before
upload and complicates every debug tool that wants to look at a pixel. Not worth
it at this stage.

**Threading service.** Parallel work goes through the existing `WorkerPool`
(`AGENTS.md` rule 1 — use the services we have). **Never `new Thread`.**

> **A fine-grained join must not use a timed park. This cost 15× and was found
> by measurement, not review.**
>
> `submitParallel`'s join is a polling loop — nothing unparks a joining thread —
> so a `LockSupport.parkNanos` there is a **timed** park, and a timed park is
> rounded up to the system timer period. On Windows that is **15.6 ms by
> default**. A join that needed 50 microseconds slept for fifteen milliseconds.
>
> The symptom was that enabling worker threads made the demo scene *slower*:
> best frame 4 ms, median 31 ms on eight workers. The tell was in the
> distribution rather than the mean — frame times sat on exact multiples of
> 15.6 ms, because a frame's several joins each caught a timer tick.
>
> `Thread.yield()` has no such floor; it is a reschedule, not a sleep. The join
> now spins, then yields, and parks only as a backstop for a genuinely
> descheduled worker, where burning a core is the worse trade.
>
> **Two lessons worth keeping.** Report percentiles, not best-of-N — best-of-N
> hid this completely, because the best frame was always the one that missed a
> tick. And a benchmark can be *unable to see* a fault: the single-instance
> scene scaled 5.9× and looked healthy purely because it crossed four barriers
> per frame instead of 1180, so it rarely caught a tick at all. That healthy
> number is what made the pool look innocent.

> **Prerequisite — SATISFIED.** `I_ThreadPoolPort` / `WorkerPool` was exclusively
> an event-bus drainer: workers looped on `bus.take()` and dispatched to a
> `SubsystemRegistry`, with no "submit N jobs and await completion" operation.
> It now has one — `submitParallel(I_ParallelJob, int jobCount)`, index-based so
> nothing is allocated per tile per frame. Extending the existing pool was the
> right move; a second thread pool is a `STYLE.md` § 13.4 anti-pattern.
>
> **Caller participation was necessary but not sufficient.** The requirement
> below stops the submitting thread from deadlocking, and it is what makes
> correctness independent of worker count. It does not, on its own, produce any
> **parallelism**: at frame time the bus is normally idle, so every other worker
> is blocked inside `bus.take()` and cannot be reached at all. A caller that
> merely participates would run all 
> the tiles itself, correctly and serially.
>
> Waking those workers by interrupting them is not acceptable — a worker may be
> inside subsystem code, and any blocking call in a handler would start seeing
> `InterruptedException`, which changes dispatch behaviour for every subsystem.
> `WorkerPool` uses **leader/follower** instead: at most one worker sits in
> `take()` (the leader) and the rest wait on a pool-owned condition that
> `submitParallel` can signal. The leader hands leadership on *before* it
> dispatches, so concurrent dispatch is unaffected — and `take()` was already
> serialised on the queue's own lock, so nothing was lost.
>
> **The submitting thread must participate in the work.** This is a correctness
> requirement, not a tuning choice. `RenderSubsystem` is dispatched *from* the
> bus, so the thread that submits the tile jobs **is itself a pool worker**. If
> it submits W jobs and then blocks waiting for them, it has removed a worker
> from the pool that has to execute them. At `workerCount == 1` that is an
> immediate, total deadlock; above 1 it is a latent one that appears the moment
> two subsystems fan out in the same frame. This is the same hazard `AGENTS.md`
> already documents for the game loop ("it cannot run on the pool it feeds —
> deadlock at `workerCount == 1`"), arriving from the other direction.
>
> The fix is the standard fork-join one: the caller **runs tiles itself** until
> the queue is empty, then waits only for tiles other workers already claimed.
> Progress is then guaranteed by the calling thread alone, so correctness no
> longer depends on how many workers exist. Tile exclusivity is untouched — the
> caller claims tiles through the same policy as everyone else. Any submit/await
> added to `I_ThreadPoolPort` must have this property, and it must be tested at
> `workerCount == 1`.

**Sources:**
- Pineda, "A Parallel Algorithm for Polygon Rasterization", *SIGGRAPH '88*, Computer Graphics 22(4) — https://dl.acm.org/doi/10.1145/378456.378457
- Fabian Giesen, "A trip through the Graphics Pipeline 2011" — binning, tiling, and the rasterizer's place in the pipeline — https://fgiesen.wordpress.com/2011/07/09/a-trip-through-the-graphics-pipeline-2011-index/
- Fabian Giesen, "Optimizing the basic rasterizer" — incremental edge functions, fill rules, block traversal — https://fgiesen.wordpress.com/2013/02/10/optimizing-the-basic-rasterizer/

---

## 8. Perspective-correct interpolation and the depth test

### The rule

Screen-space linear interpolation of a texture coordinate is **wrong** — that is
affine mapping, and it is what produced the sliding-texture artefact the retired
design lived with. The correct statement is:

> `u/w`, `v/w`, and `1/w` **are** linear in screen space. `u` and `v` are not.

So interpolate the *divided* quantities with the barycentric weights from § 7,
then undo the division per pixel:

```text
invW   = λ0 * (1/w0)     + λ1 * (1/w1)     + λ2 * (1/w2)
uOverW = λ0 * (u0/w0)    + λ1 * (u1/w1)    + λ2 * (u2/w2)
vOverW = λ0 * (v0/w0)    + λ1 * (v1/w1)    + λ2 * (v2/w2)

w = 1 / invW
u = uOverW * w
v = vOverW * w
```

`1/w0`, `u0/w0`, `v0/w0` and their siblings are computed **once per vertex at
setup**, never in the loop. All three interpolated quantities are linear in
screen space, so like the edge functions they step incrementally along a span:
setup computes `∂/∂x` for each and the inner loop adds.

### The divide, and how to afford it

`w = 1 / invW` is a floating-point reciprocal per pixel. That is the single most
expensive operation in the inner loop and it is on the critical path of every
textured pixel.

Two correct strategies:

1. **Per pixel.** Exact. This is the reference implementation — write it first,
   keep it as the correctness oracle for the fast path, and use it in tests.
2. **Per span segment (recommended default).** Divide exactly at the two ends of
   an N-pixel segment (**N = 8 or 16**), and interpolate `u` and `v` linearly
   between those exact endpoints. The error is bounded by the curvature of the
   hyperbola over N pixels and is imperceptible at N = 16 for anything but
   extreme grazing angles. This is the classic Quake span technique and it is
   what makes the ~3–8 ns/pixel estimate in `docs/ASSETS.md` § 2 plausible at
   all.

Strategy 2 also gives mip selection its natural cadence — see § 9.

### Depth: store 1/w, test greater

`invW` is already being interpolated, so **using it as the depth value is free**
— no extra interpolant, no extra setup. Convention:

```text
clear   depth to 0.0f            // 1/w = 0 is infinitely far; all-zero bit pattern
test    invW > depth[index]      // greater is nearer
write   depth[index] = invW      // only when the test passes
```

1/w also distributes precision hyperbolically, concentrating it near the camera
where it is wanted — the same property that makes GPU depth buffers nonlinear,
here for free rather than as a side effect.

Do the **depth test before texture sampling**. Sampling is far more expensive
than a compare, and at 2× overdraw roughly half of it is thrown away.

**Sources:**
- Heckbert & Moreton, "Interpolation for Polygon Texture Mapping and Shading" — the derivation of why `u/w` and `1/w` are the screen-linear quantities
- Paul Heckbert, "Fundamentals of Texture Mapping and Image Warping", UCB/CSD 89/516, 1989 — https://www2.eecs.berkeley.edu/Pubs/TechRpts/1989/CSD-89-516.pdf
- Jim Blinn, "Hyperbolic Interpolation", *IEEE CG&A* 12(4), 1992 — https://doi.org/10.1109/38.144827
- Chris Hecker, "Perspective Texture Mapping" series, *Game Developer Magazine* 1995–96 — the practical span-subdivision treatment — https://chrishecker.com/Miscellaneous_Technical_Articles
- Michael Abrash, *Graphics Programming Black Book*, Part V (the Quake chapters) — span-based rendering and subdivided perspective correction — https://www.jagregory.com/abrash-black-book/

---

## 9. Texture sampling

`docs/ASSETS.md` § 2 specifies **mipmapped bilinear** — bilinear filtering
*within* one mip level. Trilinear (blending two levels) doubles the sample cost
and is not in the per-frame budget. Mip chains are pre-generated by
`GltfConverter` and are **required**, not optional: unmipmapped minification is
both aliased and slow, because it destroys texture cache locality exactly when
the texture is being sampled sparsely.

### Bilinear filtering

With `u, v` in [0, 1] and a level of size `w × h`:

```text
tx = u * w - 0.5          ty = v * h - 0.5
x0 = floor(tx)            y0 = floor(ty)
fx = tx - x0              fy = ty - y0

result = lerp( lerp(texel(x0,   y0),   texel(x0+1, y0),   fx),
               lerp(texel(x0,   y0+1), texel(x0+1, y0+1), fx),
               fy )
```

The `- 0.5` is not decorative: it places the sample point at the texel *centre*,
and omitting it shifts the whole texture by half a texel — a bug that looks like
a blurry-but-plausible image and survives review for months.

Filtering runs per channel on the unpacked RGBA8888 components. Power-of-two
level dimensions make the wrap/clamp of `x0+1` and `y0+1` a mask rather than a
branch; `GltfConverter` should enforce power-of-two textures for that reason
alone.

### Mip level selection

The level is chosen from how fast UV changes per screen pixel. With `u, v`
expressed **in texels of level 0**, the scale factor and level of detail are:

```text
ρ = max( sqrt((∂u/∂x)² + (∂v/∂x)²),
         sqrt((∂u/∂y)² + (∂v/∂y)²) )

λ = log2(ρ)

level = clamp(floor(λ + 0.5), 0, maxLevel)
```

This is the OpenGL specification's definition, and matching it means the output
matches what a GPU would produce, which makes visual comparison against a
reference renderer meaningful.

**Computing the derivatives cheaply.** Do *not* evaluate `∂u/∂x` per pixel.

An earlier draft got these by differencing the two exact endpoints of an N-pixel
segment — `∂u/∂x ≈ (uEnd − uStart) / N`. **That recipe is obsolete**, because
§ 11(c) measured the segment path as not worth writing, and the recipe assumed
segments exist. Use the **quotient rule** instead, which is cheaper, exact, and
independent of any segmentation:

```text
∂u/∂x = (udx − u * wdx) * w          where w = 1 / w_clip
```

Every term is already in hand: `udx` and `wdx` are the triangle's setup
gradients for the `u/w` and `1/w` planes, and `u` and `w` are the values the loop
just computed for this pixel. The same form gives `∂u/∂y`, `∂v/∂x` and `∂v/∂y`.

Selection still runs **per segment rather than per pixel** — roughly every 16
pixels — because `log2` and the square root genuinely do not belong in the
per-pixel path, even though the perspective divide turned out to be affordable
there. `log2` reduces to extracting the float's exponent field, so no `Math.log`
call is needed in the loop.

Cheap-and-conservative variants (using only the x derivative, or `max(|∂u|,|∂v|)`
in place of the square root) trade a little over- or under-blurring for speed and
are legitimate — but implement the specification form first so there is something
to compare against.

**Sources:**
- Lance Williams, "Pyramidal Parametrics", *SIGGRAPH '83* — the original mipmap paper — https://dl.acm.org/doi/10.1145/800059.801126
- OpenGL 4.6 Core Profile Specification, § 8.14 "Texture Minification" — the normative ρ and λ definitions — https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf
- Heckbert, "Fundamentals of Texture Mapping and Image Warping" (above) — filtering theory

---

## 10. Model format, and what happened to BSP

### `ModelFormat` — the flat binary runtime format

glTF is **not parsed at runtime** (`docs/ASSETS.md` § 4). `GltfConverter`
produces a flat binary file that `ModelFormat` reads with near-zero parsing:
fixed-size header, explicit section offsets, arrays laid out exactly as the
renderer wants to consume them, no per-element decoding.

Shape (indicative, to be pinned down in Phase 5):

- Header: magic, format version, section offsets and counts
- Interleaved vertex array: position, UV, baked vertex colour
- Index array
- Submesh/material table
- Texture blobs, **already decoded**, with **pre-generated mip chains**

`ModelFormat` owns the layout and its version field. Refusing to load an
unrecognised version with a clear error is cheaper than debugging a
mis-parsed vertex array.

> **Channel order — decided: `RGBA8888` end to end.** `docs/ASSETS.md` § 4 used
> to say the converter decodes to raw BGRA, which predated the colour buffer
> decision. RGBA wins because the colour buffer's format is pinned from outside
> (it must match what the presentation path uploads, libGDX
> `Pixmap.Format.RGBA8888`) while the texture's format is ours, produced by a
> build-time converter that does not exist yet. The free side moves. A mismatch
> would cost a swizzle per texel fetched, in the hottest loop, for nothing.
>
> `TextureSampler` therefore never swizzles. But **prove the byte layout, do not
> assume it** — an `int[]` reaching the GPU passes through a byte-order step, and
> whether `0xRRGGBBAA` arrives as R,G,B,A depends on the upload path and platform
> endianness. Round-trip test in the `Framebuffer` lane: write a known texel,
> present, read back, assert.

### BSP — retired as a *renderer* algorithm, not deleted

Be precise about this, because "we dropped BSP" is a half-truth that will
mislead someone.

**Retired:** BSP as the renderer's *visibility* algorithm. The old design walked
the tree front-to-back to get a correct draw order without a depth buffer. **The
z-buffer does that job now**, per-pixel and per-triangle, correctly for arbitrary
geometry including the interpenetrating meshes a painter's-algorithm order cannot
resolve at all. R_ does not traverse a BSP tree.

**Kept:** BSP as a *spatial structure for gameplay and collision*. `PLAN.md`
Phase 4 lists `BspTraverser` for leaf lookup, and `gameplay/README.md` uses it as
the broad-phase quick-reject for collision. That is a completely different use of
the same structure and it is unaffected by anything in this document.

So: **BSP is not gone from the project. It is gone from the renderer.**

---

## 11. Open questions — decide these before implementing

These are unresolved. They are recorded rather than resolved because both are
genuine architectural conflicts with a real cost on each side.

### (a) Framebuffer allocation vs. the memory port

**The conflict.** `AGENTS.md` and `STYLE.md` § 13.4 forbid `new byte[]` outside a
memory-port adapter: every allocation goes through `I_MemoryPort`. But
`I_MemoryPort` hands out opaque `int` handles over a `byte[]` backing store and
has **no read or write operation**. A software rasterizer needs raw typed-array
access in its inner loop, and the buffers are `int[]` and `float[]`, not `byte[]`.
Per-pixel handle indirection is not a performance concern to be measured — it is
a non-starter, several times the cost of the pixel work itself.

The resource subsystem hit the same wall and documented it (`resource/README.md`,
"The memory-port tension"), resolving it by letting the memory port own the
*budget and lifecycle* while the bytes come from one sanctioned site. That
precedent is relevant but not identical: `LumpCache` touches its bytes rarely,
and the framebuffer is touched millions of times per frame.

**Option 1 — sanctioned exception.** `Framebuffer` allocates its `int[]` and
`float[]` directly, once at init and again only on resize, and is named in
`STYLE.md` § 13.4 as an explicit exception alongside the memory-port adapters
themselves.
*Cost:* the rule acquires a second exception, and every future "my hot loop is
special too" argument now has a precedent to point at. The exception must be
narrow and written down, not just tolerated.

**Option 2 — `I_MemoryPort` grows a typed-slab capability.** Add an operation
that allocates a typed slab and returns the **array itself** (plus an offset and
length), tracked by the port for budget and lifecycle, with the caller free to
index it directly. `MemoryPortFactory.createSlab(int, int)` already exists as a
Phase 2+ placeholder, so the concept is anticipated.
*Cost:* real design and test work on the engine's most foundational port, before
a single pixel is drawn. It also weakens the port's central invariant — that the
engine never dereferences memory it did not get a handle for — and both backends
plus their 35 tests must absorb it.

**Decided: option 1, narrowly scoped.**

The memory port earns its keep by tracking and bulk-freeing *many, small,
short-lived* allocations — that is what tags and `freeByTag` are for. The
framebuffer is the exact opposite: two or three arrays, allocated once at init,
freed at shutdown, resized only when the window is. It gets none of the port's
benefits and pays its whole cost. Growing a typed-slab capability on the engine's
most foundational port, plus absorbing it into both backends and their 35 tests,
would be real design work done *before the first pixel is drawn*, to satisfy a
rule that is not buying anything here.

The exception is **narrow, and the narrowness is the point** — it must not become
the precedent for every future "my loop is hot too" argument:

> Long-lived, engine-owned primitive buffers whose element type is not `byte`,
> allocated once at initialisation and released at shutdown, may be allocated
> directly. Named sites only, listed in `STYLE.md` § 13.4. A hot loop is **not**
> on its own a qualifying reason.

That wording admits `Framebuffer` and the audio mixing buffers, and excludes
per-frame and per-entity allocation, which is what the rule actually exists to
prevent.

Revisit if a third and fourth candidate appear — at that point the pattern is
real and option 2 becomes the better shape. Record the exception in `STYLE.md`
§ 13.4 as part of the `Framebuffer` lane, not afterwards; an undocumented
exception is indistinguishable from a violation.

### (b) The WAD subsystem has no art left to read

**The situation.** `engine/.../resource/` is **built and working**: `WadReader`,
`LumpCache`, `MapLumpParser`, `LittleEndian`, `WadFilePort`, backed by 101
passing tests. It is not a stub and it is not broken.

But `docs/ASSETS.md` moves all art to preprocessed glTF, and § 10 records the
rejection of Freedoom — the one complete, well-licensed WAD the project had
identified. The renderer specified in this document consumes neither
palette-indexed textures nor BSP-compiled 2.5D sector maps. **So the subsystem
currently has no art to read.**

Plausible remaining roles, none of them chosen:

- **Map/level geometry container.** WAD is a serviceable generic indexed-lump
  archive, and `MapLumpParser` already reads THINGS / LINEDEFS / SECTORS /
  VERTEXES. Level *layout* and entity placement are not art and are not
  superseded by glTF. This is the strongest candidate.
- **Generic asset container.** Use the lump directory to hold `ModelFormat`
  blobs and textures — competing directly with just shipping the preprocessed
  payload as a zip, which `build.gradle.kts` already does.
- **A format the project drops later.** Keep it, stop investing, revisit if the
  first two never materialise.

**This is the user's call and is deliberately left open.** Nothing is deleted,
nothing is declared dead. What *is* stale is the assumption baked into
`resource/README.md` and `I_WadPort` that the lumps being read are DOOM patches
and flats destined for a palette-indexed renderer — the planned `ImageDecoder`
(`PLAN.md` Phase 2) decodes into a pixel format § 3 no longer uses. Do not
implement `ImageDecoder` until (b) is resolved.

### (c) The performance numbers — now measured, and they moved

**The benchmark has been run.** `docs/ASSETS.md` § 2 carries the full results and
is canonical. What matters for the lanes still unwritten:

- **The span loop costs 17–21 ns/pixel, not 3–8.** The architecture survives, but
  60 Hz at 1080p affords **~10–20k triangles**, not 50–100k. 50–100k is a 30 Hz
  1080p figure or a 60 Hz 720p one.
- **Bilinear filtering is 2.9× the entire rest of the inner loop** (21.4 → 7.3
  ns/px with nearest). It is the only large lever, and therefore the quality
  setting to expose and the first thing to drop on weak hardware. § 9's sampler is
  correct as specified — this is a runtime choice, not a spec change.
- **Perspective correction costs 8%.** Never worth trading away. § 8 is vindicated.
- **`SpanRenderer`'s segment optimisation is probably not worth writing.** § 8
  proposes a per-8-or-16-pixel divide over a per-pixel one; measured, the two are
  indistinguishable, because the FP divider is not the bottleneck — memory and the
  bilinear load/ALU work are. **Build the reference per-pixel path and measure
  before writing the segment path.** The § 14 ordering already says reference
  first; this is the evidence for why that ordering matters.
- **Tiled scaling is ~100% efficient once clock-adjusted**, so § 7's decomposition
  is sound. Raw speedup looks worse than it is because the test CPU runs 4.75+ GHz
  on one thread and ~3.1 GHz across all 22. **Report cycles/pixel, not ns/pixel**,
  in any future measurement — it held stable across runs where ns/pixel moved 35%.
- **Still unmeasured: the 64×64 tile size.** The benchmark did not vary it.
- Two cheap wins the numbers point at, neither of them spec changes: sorting draws
  by material (17%), and storing textures pre-swizzled in 2×2 blocks so a bilinear
  quad lands in one cache line (the single-texture case measured 2.6× faster).

---

## 12. Presentation — R_ does not present

The engine produces a finished framebuffer. **The platform adapter uploads it.**
There is no new window port and none is needed: `I_WindowPort` and
`I_FrameCallback` already are the hook, and their Javadoc explains why the window
lives in the HAL rather than behind `I_RenderPort`.

```text
R_ (worker threads)                 platform adapter (render thread)
─────────────────────               ────────────────────────────────
render into Framebuffer
      │
      ├─ de-pad into BACK buffer
      │  (R_ owns it outright)
      │
      ├─ swap two references ◄──┐  short presentLock
      │                          │
      └──── FRONT int[] ─────────┴►  one arraycopy under the same lock
                                    copy into Pixmap (RGBA8888)
                                    upload as one texture
                                    draw fullscreen, swap
```

**Double-buffered, not lock-arbitrated.** A single shared buffer meant the
presenting thread competed for a non-fair lock that the render workers kept
reacquiring, and it rarely won: R_ finished **35 frames a second and the window
displayed 2.9 of them.** Fairness settings would have arbitrated that contention;
double buffering removes it. Neither side ever waits on the other's *work* — only
on a two-reference swap. The cost is one extra full-frame copy, about 0.2 ms at
720p.

**Render frames are coalesced, and that belongs to the consumer.** `GameLoop`
publishes a `RenderFrameEvent` every tic. If frames are already in flight,
`RenderSubsystem` renders the newest and drops the rest — a stale frame is pure
waste, because the camera has already moved. The throttle deliberately does
**not** live in `GameLoop`: it is the simulation clock, it cannot know frame
cost, and slowing it to the frame rate would couple simulation speed to
rendering speed and desync lockstep. Only the consumer knows a frame is in
flight.

The two load-bearing facts, both already documented on `I_WindowPort`:

1. **R_ must not know what a window is.** Giving it `swapBuffers()` would put
   platform knowledge in a subsystem that is otherwise pure math on arrays.
2. **A graphics context is current on exactly one thread.** `RenderSubsystem`
   runs on *worker* threads; the context lives on the platform's render thread.
   Routing graphics calls through `I_RenderPort` would be a crash waiting to
   happen.

`I_FrameCallback.onFrame(float deltaSeconds)` is presentation, not simulation.
Platform frame rate is whatever the display and OS decide; the 30/60/120 Hz
`GameLoop` remains the simulation clock on its own thread. `onFrame` draws the
latest state — it never advances it.

The colour buffer format in § 3 exists to make this handoff one bulk copy. The
adapter is responsible for confirming byte order at the `Pixmap` boundary; that
check belongs in the adapter's tests, not in R_.

---

## 13. Files

`engine/.../render/`:

- `port/I_RenderPort.java`, `port/I_RenderPortFactory.java`
- `adapter/NullRenderPort.java` — headless stub
- `adapter/SoftwareRenderPort.java` — the real port; composes the pipeline in § 5 order over four `submitParallel` passes
- `adapter/Rgba.java`, `Framebuffer.java`, `Vec3.java`, `Mat4.java`, `Camera.java`, `TriangleClipper.java`, `Rasterizer.java`, `SpanRenderer.java`, `MipChain.java`, `TextureSampler.java`, `ModelFormat.java`, `ModelFormatException.java`

`desktop/.../`:

- `FramebufferPresenter.java` — uploads `copyColorTo`'s de-padded copy as an RGBA8888 `Pixmap` (§ 12)
- `GdxScreenshot.java`

`tools/.../` — build-time only, never on the runtime classpath:

- `gltf/GltfConverter.java` and support, `model/ModelBuilder.java`, `model/MipGenerator.java`, `GltfConverterMain.java`, `RenderPreviewMain.java` (renders one frame to a PNG — the headless verification path)

## 14. TODO (Phase 5)

Ordered. Each item is a lane from § 1; the ordering is by dependency.

- [x] **Benchmark the textured-span inner loop first** — done; results in `docs/ASSETS.md` § 2 and § 11(c) above
- [x] **Resolve open question § 11(a)** — framebuffer allocation vs. `I_MemoryPort`
- [x] **Extend `WorkerPool`** with submit-and-await for tile jobs (§ 7 prerequisite)
- [x] `Framebuffer` — `int[]` colour (RGBA8888), `float[]` depth (1/w), tile geometry, padded stride, `clear()`
- [x] `Camera` — view matrix, projection without a z row, world → clip space
- [x] `TriangleClipper` — homogeneous near-plane Sutherland-Hodgman, 4-vertex scratch, fan re-triangulation
- [x] `Rasterizer` — divide, viewport transform, backface cull, edge setup with top-left fill rule, bounding box, per-chunk tile binning
- [x] `SpanRenderer` — reference per-pixel path; segment path deliberately NOT written (§ 11c measured it as no better)
- [x] `TextureSampler` — bilinear with the −0.5 texel-centre offset, per-segment mip selection
- [x] `ModelFormat` — flat binary reader, versioned header
- [x] `GltfConverter` — buildscript classpath only; triangulation, mip chains, budget enforcement per `docs/ASSETS.md` § 5
- [x] **Integration** — `SoftwareRenderPort`, `GameLoop` publishes `RenderFrameEvent`, `:desktop` presents. A real Kenney model renders to window and PNG
- [x] **Backface winding** — measured, `CullMode.CLOCKWISE` (§ 7)
- [x] `Scene` — world + view instances with per-instance `modelToWorld`; view pass after a depth clear (§ 5)
- [x] Fix the drain-window defect — `WorkerPool` gained `DRAINING`; `submitParallel` is legal there. Clean exits no longer throw
- [ ] Measure the 64×64 tile size — still the one unmeasured constant (§ 11c)
- [ ] Default to 720p60; expose bilinear as a quality toggle (`docs/ASSETS.md` § 2)

---

## 15. A note on citations

`docs/ASSETS.md` § 8 rule 1: **cite specifications and papers, not GPL source
repositories.** DOOM, Chocolate Doom, PrBoom+, DSDA-Doom and SLADE are all
GPL-2; copying from them into this MIT codebase is a license violation, and
reading a GPL *implementation* to learn an algorithm is legally murky in a way
that reading a published *specification* is not.

The previous version of this file cited `id-Software/DOOM` source files
directly. Those citations have been removed. Every algorithm above is sourced to
a paper, a standard, or an author's own published prose.

---

## 16. Style note for anyone copying from this document

Every code block above is **pseudocode and non-normative** — it is written for
clarity of the math, not as Java to paste. Real code in this repository is bound
by `STYLE.md`: Allman braces, `final` on every parameter, no ternary `?:`, no
nested lambdas, primitives over boxed types, and no magic numbers outside
`Constants`.
