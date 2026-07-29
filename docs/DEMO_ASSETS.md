# Demo Asset Manifest

> Provenance record for the first-person demo's model set: one weapon and eight
> level pieces, plus a generated fallback room.
>
> **The binaries are not in git** — `docs/ASSETS.md` § 6, enforced by
> `.gitignore` (`assets/gltf/`, `assets/models/`, `*.ofm`, `*.glb`). This file
> is. It is the only durable record of where each model came from, and
> `docs/ASSETS.md` § 7 is explicit that an asset with no provenance has to be
> treated as unlicensed and removed.

---

## 1. Regenerating everything

```powershell
# 1. Download the two CC0 packs by hand (see § 2 for URLs and digests) and
#    unzip each into its own subdirectory:
#
#      <raw>\blaster-kit\Models\GLB format\...
#      <raw>\prototype-kit\Models\GLB format\...
#
# 2. Stage, convert, and verify in one command:
.\gradlew.bat :tools:regenerateDemoAssets -PkenneyRaw=<raw>
```

That writes `assets/models/weapon/*.ofm` and `assets/models/level/*.ofm`, then
reads every one back through the runtime's own `ModelFormat` and reports it
against the `docs/ASSETS.md` § 5 budget. It fails the build if any model does
not parse, holds no geometry, or is over budget.

Useful variants:

| Command | Effect |
|---|---|
| `.\gradlew.bat :tools:regenerateDemoAssets` | No `-PkenneyRaw`: staging warns and skips, and the **generated fallback room** is emitted instead (§ 5) |
| `.\gradlew.bat :tools:regenerateDemoAssets -PkenneyRaw=<raw> -PforceFallback` | Both: the Kenney set *and* the generated room |
| `.\gradlew.bat :tools:verifyModels` | Re-check an existing `assets/models` without reconverting |
| `.\gradlew.bat :tools:renderPreview "--args=--model=<path>.ofm --out=<path>.png"` | Render one model headlessly, to look at it |

**The download is deliberately manual.** `docs/ASSETS.md` § 6 forbids
build-time third-party fetches: the URLs are unpinnable, the uptime is not
ours, and CI must stay hermetic. `stageDemoAssets` only ever reads from a local
directory you point it at.

### Why staging exists at all

Kenney's GLBs reference their texture atlas by **relative URI**
(`Textures/colormap.png`) rather than embedding it in the GLB's binary chunk.
The glTF 2.0 specification resolves a relative URI against the referring
document, so the atlas must sit *beside* each `.glb` — copying a `.glb` out on
its own produces a conversion failure that reads like a converter bug and is
not one.

`stageDemoAssets` therefore copies each pack's `Textures/colormap.png` into
every staged directory alongside the models that reference it. This is the one
sharp edge in the pipeline and it is handled in exactly one place.

Source — Khronos Group, glTF 2.0 Specification, § 3.6.1.1 (buffers and images,
URI resolution) — https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html

---

## 2. Sources

Both packs satisfy `docs/ASSETS.md` § 3: **CC0 1.0**, verified twice — on the
download page *and* in the `License.txt` shipped inside the archive. Neither
carries a per-file exception.

| | Blaster Kit | Prototype Kit |
|---|---|---|
| **Version** | 2.1 | 1.0 |
| **Author** | Kenney (kenney.nl) | Kenney (kenney.nl) |
| **Source page** | https://kenney.nl/assets/blaster-kit | https://kenney.nl/assets/prototype-kit |
| **Archive URL** | `https://kenney.nl/media/pages/assets/blaster-kit/261d80a716-1753959510/kenney_blaster-kit_2.1.zip` | `https://kenney.nl/media/pages/assets/prototype-kit/4d3b7073ed-1724832076/kenney_prototype-kit.zip` |
| **Licence** | CC0 1.0 | CC0 1.0 |
| **Licence URL** | http://creativecommons.org/publicdomain/zero/1.0/ | http://creativecommons.org/publicdomain/zero/1.0/ |
| **Retrieved** | 2026-07-28 | 2026-07-28 |
| **Archive bytes** | 1,724,676 | 2,961,396 |
| **Archive SHA-256** | `91e3093e95427d59625e7e2ce2d0399b861600160fd0b4ada7714796b67cea8c` | `213b522fb12bcc9b9ac66c4f7581f7c74623293272212e40a70c39936ad3da95` |
| **Atlas** | `Models/GLB format/Textures/colormap.png`, 512×512 PNG | `Models/GLB format/Textures/colormap.png`, 512×512 PNG |

The in-archive `License.txt` of both reads, verbatim:

> License: (Creative Commons Zero, CC0)
> http://creativecommons.org/publicdomain/zero/1.0/
> You can use this content for personal, educational, and commercial purposes.
> Support by crediting 'Kenney' or 'www.kenney.nl' (this is not a requirement)

Crediting is explicitly **not** a requirement, which is what keeps this on the
§ 3 accepted list rather than in the CC-BY rejection.

> The archive URLs embed a content hash and a timestamp. Kenney re-issues them
> when a pack is revised, so a URL that 404s means the pack moved on — go via
> the source page, and record the new digest here rather than assuming the old
> one still applies.

---

## 3. What was converted

Every model below is **real Kenney art**, not generated. Measured by
`:tools:verifyModels` after conversion, reading each `.ofm` back through
`ModelFormat`.

Budget: **≤1,500 triangles**, **≤512² textures**, power-of-two, mipmaps
required, albedo only (`docs/ASSETS.md` § 5).

### Weapon — `assets/models/weapon/`

| Model | Source file | Triangles | % of budget | Verts | Texture | Extent (x·y·z) |
|---|---|---|---|---|---|---|
| `blaster-b.ofm` | Blaster Kit `blaster-b.glb` | **368** | 24.5% | 632 | 512×512, 10 mips | 0.16 · 0.31 · 0.42 |
| `blaster-p.ofm` | Blaster Kit `blaster-p.glb` | **882** | 58.8% | 1,506 | 512×512, 10 mips | 0.16 · 0.37 · 0.86 |

**Two blasters, and they must stay visibly different from each other.**
`blaster-b` is the player's viewmodel — a compact orange pistol, 0.42 units
long. `blaster-p` is what the **bots** carry: a green two-handed carbine at
0.86, twice the length and a different colour, so the silhouette is
distinguishable across the room. An opponent holding what looks like your own
gun tells the player nothing.

Chosen off the pack's own preview renders rather than by taking the next letter:
several of the eighteen blasters are near-duplicates of `blaster-b` with a
different grip, and any of those would have been a change nobody could see.
`blaster-p` is also the **largest model in either pack** at 882 triangles — 59%
of the per-model budget, and seven of them in the room is 6,174 triangles beside
a scene that already submits tens of thousands.

### Level — `assets/models/level/`

| Model | Source file | Triangles | % of budget | Verts | Texture | Extent (x·y·z) |
|---|---|---|---|---|---|---|
| `floor-square.ofm` | Prototype Kit `floor-square.glb` | **8** | 0.5% | 14 | 512×512, 10 mips | 1.00 · 0.00 · 1.00 |
| `wall.ofm` | Prototype Kit `wall.glb` | **12** | 0.8% | 24 | 512×512, 10 mips | 0.20 · 1.00 · 1.00 |
| `wall-corner.ofm` | Prototype Kit `wall-corner.glb` | **20** | 1.3% | 36 | 512×512, 10 mips | 1.00 · 1.00 · 1.00 |
| `wall-doorway.ofm` | Prototype Kit `wall-doorway.glb` | **152** | 10.1% | 240 | 512×512, 10 mips | 0.30 · 1.00 · 1.00 |
| `shape-slope.ofm` | Prototype Kit `shape-slope.glb` | **8** | 0.5% | 18 | 512×512, 10 mips | 1.00 · 1.00 · 1.00 |
| `stairs.ofm` | Prototype Kit `stairs.glb` | **36** | 2.4% | 60 | 512×512, 10 mips | 1.00 · 1.00 · 1.00 |
| `column.ofm` | Prototype Kit `column.glb` | **12** | 0.8% | 24 | 512×512, 10 mips | 0.20 · 1.00 · 0.20 |
| `crate.ofm` | Prototype Kit `crate.glb` | **204** | 13.6% | 312 | 512×512, 10 mips | 0.50 · 0.50 · 0.50 |

**Nothing is over the cap, though the bots' carbine is now the piece that comes
closest to it.** `blaster-p` is 59% of the per-model budget, and the whole level
kit together is 452 triangles — under a third of what one model is allowed.
`docs/ASSETS.md` § 2's remark that "Kenney's kits remain comfortably inside
whatever the real budget turns out to be" is confirmed: across the *entire*
Blaster Kit and Prototype Kit, all 187 models, the worst case is `blaster-p.glb`
at 882 triangles — which the demo now ships, so that figure is measured rather
than surveyed. **No piece in either pack exceeds the 1,500 cap**, so the cap
constrains imports from elsewhere and not this art direction.

### Scale — read this before placing anything

The Prototype Kit is authored on a **1-unit grid**: a floor tile is 1×1, a wall
is 1 unit wide and 1 unit tall. The blaster is 0.42 units long. A 1-unit wall
is therefore about knee height next to a human-scaled weapon, so **the two kits
need a relative scale chosen at placement time** — they are not authored to a
common unit. This is a property of the source art, not of the conversion.

---

## 4. Scene cost against the frame budget

`docs/ASSETS.md` § 2 measures the target as **720p at 60 Hz**, affording roughly
**10–20k triangles per frame** once fill is paid for.

| Scene | Triangles | Share of 10k |
|---|---|---|
| One of every model placed at once | **820** | 8% |
| A worked room: 10×10 floor tiles, 40 wall segments, 4 corners, 2 doorways, 1 ramp, 1 staircase, 4 columns, 8 crates, 1 held weapon | **3,756** | 38% |

The worked room breaks down as 800 + 480 + 80 + 304 + 8 + 36 + 48 + 1,632 +
368. The crates dominate at 43% of it, which is the one counter-intuitive
number here: a 204-triangle prop costs more than the entire 100-tile floor,
because the floor is 8 triangles a tile. **Geometry is not the constraint at
this scale — fill is**, exactly as § 2 concluded.

### The payload finding, which is the real cost

The nine converted models total **12.7 MB**, and roughly 12.6 MB of that is
**nine identical copies of the same 512×512 atlas plus its mip pyramid** —
about 1.4 MB each. `ModelFormat` has no notion of a texture shared between
models, so a pack built around one atlas per kit — the exact property
`docs/ASSETS.md` § 3 praises Kenney for — pays for that atlas once per model.

Nine models is already a quarter of § 5's 20–50 MB total payload cap. This is
fine for a demo and will not be fine for a level. Recorded rather than fixed:
the fix is a shared-texture section or an atlas-by-reference indirection in
`ModelFormat`, which is an engine change and not this lane's to make.

---

## 5. The generated fallback

`assets/models/generated-room.ofm` is **generated geometry, not third-party
art.** It has no upstream source, no author but this repository, and no licence
question — which is the entire point of it.

| | |
|---|---|
| **Source** | `com.openfps.tools.model.ProceduralRoom`, this repository |
| **Licence** | MIT, same as the rest of the codebase |
| **Triangles** | 60 (4.0% of budget) |
| **Vertices** | 120 |
| **Textures** | 2 × 64×64, 7 mips each — generated grid, floor and wall |
| **Extent** | 17.00 · 4.50 · 17.00 — a 16×16 interior with 4-high walls |

It is emitted **only** when no glTF has been staged, or when `-PforceFallback`
asks for it, and `DemoAssetsMain` logs at `WARN` when it does. A clone with no
packs downloaded still gets a floor to stand on; nobody gets a generated room
recorded against a Kenney URL.

Two design notes worth keeping:

- **Every piece is a closed box**, wound counter-clockwise as seen from
  outside. A room made of inward-facing single-sided quads would be invisible
  under the same backface culling that renders a converted Kenney model
  correctly, and a test scene needing different render state from the real art
  tests the wrong thing. A wall with thickness has its room-facing side as a
  genuine outward face.
- **UVs are world position over a tile size**, not per-face `0..1`, so the grid
  holds constant texel density across pieces of different sizes and tiles
  seamlessly where they meet.

`ProceduralRoomTest` asserts the winding by **signed volume** — the divergence
theorem over the triangle soup gives +260 for the correct winding and −260 for
the inverted one. Vertex counts, triangle counts, bounds and file size are
identical between the two, so nothing else in the file distinguishes them.

---

## 6. Intake checklist — `docs/ASSETS.md` § 7

| Requirement | Blaster Kit | Prototype Kit | Generated room |
|---|---|---|---|
| Source URL recorded | ✅ § 2 | ✅ § 2 | n/a — this repository |
| Author recorded | ✅ Kenney | ✅ Kenney | ✅ this project |
| Licence + URL recorded, on the § 3 accepted list | ✅ CC0 1.0 | ✅ CC0 1.0 | ✅ MIT |
| Retrieval date recorded | ✅ 2026-07-28 | ✅ 2026-07-28 | n/a |
| SHA-256 of the original download recorded | ✅ § 2 | ✅ § 2 | n/a |
| Fits the § 5 triangle and texture budgets | ✅ § 3 | ✅ § 3 | ✅ § 5 |
| Per-file licence verified where the source is per-file licensed | n/a — pack-wide CC0 | n/a — pack-wide CC0 | n/a |
