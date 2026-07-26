# Resource (W_) — WAD File Loading

> W_ reads resource files (textures, maps, sprites, sounds, etc.) and caches
> them so the same lump isn't read twice.

## What lives here (planned)

- `WadReader` — opens a `.wad` file and reads its lump directory
- `LumpCache` — demand-loaded, reference-counted cache of lump bytes
- `MapLumpParser` — parses the map lumps (THINGS, LINEDEFS, SECTORS, …)
- `ImageDecoder` — decodes DOOM-format patches and flats

## Subsystem layout

```
resource/
├── port/
│   └── I_WadPort.java   interface — read + cache lumps
└── adapter/
    └── NullWadPort.java stub
```

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

- `port/I_WadPort.java`
- `adapter/NullWadPort.java`

## TODO (Phase 2)

- `WadReader` — header + directory parse
- `LumpCache` — demand-loaded, ref-counted
- `MapLumpParser` — read THINGS / LINEDEFS / SECTORS
- `ImageDecoder` — patch + flat decode
- `BlockmapBuilder` — pre-compute BLOCKMAP from LINEDEFS
