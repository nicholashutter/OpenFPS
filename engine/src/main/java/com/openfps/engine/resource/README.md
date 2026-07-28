# Resource (W_) — WAD File Loading

> W_ reads resource files and caches them so the same lump isn't read twice.

## Status

| Field | Value |
|---|---|
| **State** | BUILT-UNWIRED |
| **Phase** | `PLAN.md` Phase 2 — partly done (§ 3.6) |
| **Tests** | 101 |
| **Registered** | not registered — no `W_` subsystem wrapper |
| **Verified** | 2026-07-28 |

**Built.** `WadReader`, `LumpCache` (bounded, evict-on-release), `MapLumpParser`,
`LittleEndian` and `WadFilePort` — the container, the cache and the map parser —
under 101 passing tests. It is not a stub and it is not broken.

**Not built.** `ImageDecoder` (deliberately on hold), `BlockmapBuilder`, and the
`W_` subsystem wrapper. `SubsystemId.W_` is declared but nothing registers
`WadFilePort`; it and `MapLumpParser` are referenced only by their own tests, so
nothing can route an event here.

**Blocked on.** The role question below — `docs/ASSETS.md` moved all art to
preprocessed glTF, so the WAD path has no art left to read. Do not implement
`ImageDecoder` until it is resolved.

**Next step.** A decision from the project owner on whether this package has a
future. This is the third time it has been raised.

**Read [Open question: what is this subsystem for now?](#open-question-what-is-this-subsystem-for-now)
before acting on anything below it.** The container, the cache and the map
parser are built and tested. What is *stale* is the assumption running through
the rest of this file that the lumps being read are DOOM patches and flats bound
for a palette-indexed renderer.

## What lives here

- `WadReader` — opens a `.wad` file (or an in-memory image) and reads its lump directory
- `LumpCache` — demand-loaded, reference-counted cache of lump bytes
- `MapLumpParser` — parses the map lumps (THINGS, LINEDEFS, SECTORS, VERTEXES)
- `LittleEndian` — the LE primitive readers everything above shares
- `WadFilePort` — the real `I_WadPort`, wiring the three together
- `ImageDecoder` — decodes DOOM-format patches and flats *(on hold — see the open question)*

This is the one subsystem with no wrapper. The seven registered subsystems are
Audio, Core, Gameplay, Hal, Memory, Net and Render.

## Open question: what is this subsystem for now?

`docs/ASSETS.md` moves **all** art to preprocessed glTF, converted at build time
into a flat binary format, and § 10 of that document records the rejection of
Freedoom — the one complete, well-licensed WAD the project had identified. The
renderer specified in `render/README.md` consumes neither palette-indexed
textures nor BSP-compiled 2.5D sector maps.

**So the WAD path currently has no art left to read.**

Plausible remaining roles, none of them chosen:

- **Map / level geometry container.** WAD is a serviceable generic indexed-lump
  archive, and `MapLumpParser` already reads THINGS / LINEDEFS / SECTORS /
  VERTEXES. Level *layout* and entity placement are not art and are not
  superseded by glTF. This is the strongest candidate.
- **Generic asset container.** Use the lump directory to hold model and texture
  blobs — competing directly with just shipping the preprocessed payload as a
  zip, which `build.gradle.kts` already does.
- **A format the project drops later.** Keep it, stop investing, revisit if the
  first two never materialise.

**This is the user's call and is deliberately left open. Nothing is deleted and
the subsystem is not dead.** The same question is recorded in
`render/README.md` § 11(b), `PLAN.md` § 3.6, and `docs/ASSETS.md` § 9; it is
restated here because this is the file a reader of *this* package opens first.

What it means for the sections below: the WAD **container** format — header,
directory, lump slicing, endianness, the cache contract — is settled and shipped
and is described as fact. The **content** conventions — flats, patches, sprite
and sound namespaces, the palette — are DOOM's, and this project has no DOOM
content and no palette. Treat them as a specification of the format we can read,
not as a description of what we will load. They are kept because deleting them
would throw away the format knowledge the answer to the open question may need.

> **`ImageDecoder` is on hold. Do not implement it until this question is
> resolved.** It decodes patches and flats into palette indices, and the
> renderer has no palette: `render/README.md` § 3 specifies 32-bit colour
> buffers and nothing anywhere loads a `PLAYPAL`. Writing it now produces
> working, tested, unusable code. `render/README.md` § 11(b) and `PLAN.md`
> Phase 2 state the same embargo.

## Subsystem layout

```
resource/
├── WadException.java        runtime failure for every malformed container
├── port/
│   └── I_WadPort.java       interface — read + cache lumps
└── adapter/
    ├── LittleEndian.java    little-endian primitive readers
    ├── WadReader.java       header + directory parse, lump slicing
    ├── LumpCache.java       demand-loaded, ref-counted, bounded
    ├── MapLumpParser.java   THINGS / LINEDEFS / SECTORS / VERTEXES
    ├── WadFilePort.java     real I_WadPort implementation
    └── NullWadPort.java     stub
```

### Endianness

Every WAD field is little-endian. The engine's other byte-packing site,
`memory/adapter/ZoneMemoryPort`, packs its allocation headers **big**-endian
because nothing outside the zone heap reads them. Do not copy that code
here — use `LittleEndian`.

### Reference counting and the port API

`I_WadPort` has no unpin operation, so the three read paths map onto the
cache like this:

| Call | Effect |
|---|---|
| `readLump(...)` | loads the lump, leaves it **unpinned** and evictable |
| `precacheLump(i)` | loads and **pins** it — never chosen as an eviction victim |
| `releaseLump(i)` | unpins; the lump is evicted the moment the last reference goes |
| `flushCache()` | drops everything, pinned or not |

`releaseLump(int)` and `openInMemory(byte[], String)` are additions on
`WadFilePort` beyond the interface. Callers holding the interface type are
unaffected.

### The memory-port tension

`STYLE.md` routes every allocation through `I_MemoryPort`, but that port
hands back opaque int handles and has **no read or write operation** — a
lump behind a handle could never be parsed or handed to the render and
audio subsystems. The compromise: the memory port owns the **budget and
lifecycle** (every resident lump holds a matching
`allocate(size, TAG_CACHE)` handle; eviction calls `free`), and the bytes
come from `WadReader.sliceLump` — the single lump-buffer site in the
subsystem. See the class Javadoc on `LumpCache` for the full argument and
the one honest gap. If `I_MemoryPort` ever grows a read/write API,
`LumpCache` and `WadReader.sliceLump` are the only two places to change.

## WAD file format

WAD is the resource container format from DOOM. The full file:

```
+---------------+  byte 0
| WAD header    |  12 bytes
+---------------+
| lump 0 data   |  variable
+---------------+
| lump 1 data   |
+---------------+
| ...           |
+---------------+
| lump N-1 data |
+---------------+
+---------------+  end of file
| lump directory|  N * 16 bytes
+---------------+
```

### Header (12 bytes)

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | Magic: `"IWAD"` (main WAD) or `"PWAD"` (patch WAD) |
| 4 | 4 | Lump count (int32 LE) |
| 8 | 4 | Directory offset in bytes from start of file (int32 LE) |

### Lump directory entry (16 bytes each)

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | Lump offset from start of file (int32 LE) |
| 4 | 4 | Lump size in bytes (int32 LE) |
| 8 | 8 | Lump name (ASCII, uppercase, null-padded, max 8 chars) |

So a WAD with N lumps has its directory at `header.directoryOffset` and the
directory is `N * 16` bytes.

### Lump name lookup

To find a lump by name, we scan the directory linearly, comparing each name
byte-by-byte. For a typical WAD with 1000–2000 lumps this is fast enough.
For larger WADs we build a `Map<String, Integer>` cache at first lookup.

**Source — Matthew S Fell, "The Unofficial DOOM Specs" v1.666 (1994)**, chapter
[2] "The WAD Format" — the container layout above, field for field:
https://www.gamers.org/dhs/helpdocs/dmsp1666.html

> **On citations.** This file used to cite the SLADE editor's source and a wiki
> mirror. Both are gone. The project cites **specifications and papers, not
> source repositories** — a GPL-2 codebase is not a reference an MIT project can
> read while writing an implementation of the same format, and a wiki is not a
> spec. `render/README.md` stripped its own source-repo citations for the same
> reason.

## Lump name conventions — DOOM's, for reference

Everything in this section describes how *DOOM* organised a WAD. This project
ships no DOOM content (`docs/ASSETS.md` § 8: "No IWADs, ever"), so none of these
namespaces are currently produced or consumed. Whether any of them survive
depends on the [open question](#open-question-what-is-this-subsystem-for-now):
if the answer is "map/level container", the map-data row is the only one that
matters; if it is "generic asset container", the project invents its own
namespaces and this table becomes prior art rather than a target.

DOOM's conventions:

| Prefix | Type |
|---|---|
| `THINGS`, `LINEDEFS`, `SIDEDEFS`, `VERTEXES`, `SEGS`, `SSECTORS`, `NODES`, `SECTORS`, `REJECT`, `BLOCKMAP` | Map data |
| `F_START` … `F_END` | Flats (floor/ceiling textures) |
| `S_START` … `S_END` | Sprite graphics |
| `W1` … `W##` | Wall textures (column-organized) |
| `DS*` (e.g. `DSPISTOL`) | Sound effects |
| `D_*` (e.g. `D_BGND`) | Music lumps |

`F_START` / `F_END` and `S_START` / `S_END` are markers that delimit a range
of lumps. The count between them is variable.

## Image format (patches and flats) — specification only, nothing decodes this

**No code in this repository reads either format, and `ImageDecoder` is on
hold.** Both formats below decode to **palette indices**, and the renderer has
no palette: `render/README.md` § 3 specifies an RGBA8888 `int[]` colour buffer,
and `docs/ASSETS.md` § 4 routes every texture through the build-time glTF
converter instead. Decoding a patch would therefore produce bytes with nowhere
to go, plus a palette lookup table this project does not ship.

The layouts are recorded so that whoever answers the open question does not have
to rediscover them.

### Flat (64 × 64)

A flat is a 64×64 column-major byte array. Each byte is a palette index.
Total size: 4096 bytes.

```
flat[byteOffset] where byteOffset = y * 64 + x
```

### Wall patch (Doom "patch" format)

A wall patch is a column-major sprite-like image. Header:

```
struct PatchHeader {
    uint16 width;       // patch width
    uint16 height;      // patch height
    int16  leftOffset;  // horizontal offset from origin
    int16  topOffset;   // vertical offset from origin
    uint32[height] columnOffsets;  // one offset per column
}
```

For each column, the data is run-length encoded:

```
column:
    uint8 topDelta;
    repeat:
        uint8 length;     // 0 = end of column
        if length == 0: break
        uint8 pad1;        // unused
        uint8[length] pixels;   // palette indices
        uint8 pad2;        // unused
```

**Source — Matthew S Fell, "The Unofficial DOOM Specs" v1.666 (1994)**, chapter
[5] "Graphics" — the patch column format and the 64×64 flat:
https://www.gamers.org/dhs/helpdocs/dmsp1666.html

*(The two citations that stood here — a wiki article and an `img2pic.c` from an
unrelated Prince of Persia port — are removed. Neither was a specification and
the second was not even the right format.)*

## Caching strategy

`LumpCache` as shipped is **bounded and evict-on-release**. An earlier draft of
this section said static lumps are "loaded on first read, kept forever"; that
was never true of the implementation and contradicted the port table above,
which is the accurate description of pinning. Nothing is kept forever.

What the table does not cover is the budget. The cache has a byte ceiling.
Making room evicts the least-recently-used **unpinned** entry first; if every
resident lump is pinned and the ceiling still cannot be met, the load **fails
loudly** rather than silently overcommitting. Every resident lump holds a
matching `allocate(size, TAG_CACHE)` handle, and eviction calls `free`, so the
memory port's accounting sees the cache as if the bytes lived inside it.

The tag policy — `TAG_CACHE` for long-lived resources, `TAG_GAME` for per-map
data freed at map end — is the intended split, but only `TAG_CACHE` is exercised
today because nothing loads a map through this path yet. Streaming audio is
unimplemented; no lump is streamed.

## Files

- `WadException.java`
- `port/I_WadPort.java`
- `adapter/LittleEndian.java`
- `adapter/WadReader.java`
- `adapter/LumpCache.java`
- `adapter/MapLumpParser.java`
- `adapter/WadFilePort.java`
- `adapter/NullWadPort.java`

## TODO (Phase 2)

- [x] `WadReader` — header + directory parse, lump slicing
- [x] `LumpCache` — demand-loaded, ref-counted, bounded
- [x] `MapLumpParser` — read THINGS / LINEDEFS / SECTORS / VERTEXES
- [x] `LittleEndian` + `WadFilePort` — the real `I_WadPort` (101 tests across the package)
- [ ] `ImageDecoder` — patch + flat decode — **on hold**, blocked on the
      [open question](#open-question-what-is-this-subsystem-for-now). Do not start it.
- [ ] `BlockmapBuilder` — pre-compute BLOCKMAP from LINEDEFS. Same blocker if the
      answer turns out not to be "map/level container".
- [ ] A `W_` subsystem registering `WadFilePort` with the `SubsystemRegistry` —
      genuinely still open, and the reason the package has no consumer today
