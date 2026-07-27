# Resource (W_) — WAD File Loading

> W_ reads resource files (textures, maps, sprites, sounds, etc.) and caches
> them so the same lump isn't read twice.

## What lives here

- `WadReader` — opens a `.wad` file (or an in-memory image) and reads its lump directory
- `LumpCache` — demand-loaded, reference-counted cache of lump bytes
- `MapLumpParser` — parses the map lumps (THINGS, LINEDEFS, SECTORS, VERTEXES)
- `LittleEndian` — the LE primitive readers everything above shares
- `WadFilePort` — the real `I_WadPort`, wiring the three together
- `ImageDecoder` — decodes DOOM-format patches and flats *(still planned)*

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

**Source — DOOM WAD format spec (unofficial)**:
http://doom.wikia.com/wiki/WAD

**Source — slade (open-source WAD editor) source for reference parsing**:
https://github.com/sirjuddington/SLADE

## Lump name conventions

DOOM uses these name conventions:

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

## Image format (patches and flats)

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

**Source — "DOOM Picture Format" — Encyclopedia**:
https://doom.fandom.com/wiki/Picture_format

**Source — Ethan Lee's "img2pic" source code**:
https://github.com/flibitijibibo/SDLPoP/blob/master/img2pic.c

## Caching strategy

- **Static lumps** (textures, sprites): loaded on first read, kept forever.
  Tag: `I_MemoryPort.TAG_CACHE`.
- **Map lumps**: loaded on map start, freed on map end. Tag: `I_MemoryPort.TAG_GAME`.
- **Audio lumps**: streamed, never fully resident. The audio adapter holds its
  own pointers.

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

- [x] `WadReader` — header + directory parse
- [x] `LumpCache` — demand-loaded, ref-counted
- [x] `MapLumpParser` — read THINGS / LINEDEFS / SECTORS / VERTEXES
- [ ] `ImageDecoder` — patch + flat decode
- [ ] `BlockmapBuilder` — pre-compute BLOCKMAP from LINEDEFS
- [ ] A `W_` subsystem registering `WadFilePort` with the `SubsystemRegistry`
