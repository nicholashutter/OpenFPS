# Render (R_) — Rendering Pipeline

> R_ takes the current GameState and draws pixels. The actual GPU / window
> binding lives in `hal/adapter/desktop/` (LWJGL3) and `hal/adapter/mobile/`.
> R_ itself is pure math + framebuffer operations on primitive arrays.

## What lives here (planned)

- `BspTraverser` — walks the BSP tree front-to-back, returns visible subsectors
- `WallClipper` — clips wall segments to the player view frustum
- `VisplaneBuilder` — manages screen-space horizontal floor/ceiling bands
- `TextureSampler` — nearest-neighbor texture sampling for column rendering
- `ColumnRenderer` — draws one vertical column of the screen

## Subsystem layout

```
render/
├── port/
│   └── I_RenderPort.java   interface — called by core per tic
└── adapter/
    └── NullRenderPort.java stub
```

## Render math — what's coming

### BSP traversal

The BSP is a binary tree where each node has a partitioning line. Given the
player's (x, y), at each node we test which side of the partition the player
is on, then recurse into the **far** child first, then the **near** child.
This draws things in back-to-front order, so painters' algorithm overdraw
is correct without a z-buffer.

**Pseudocode:**
```
renderBsp(node, clipBox):
    if node is leaf (subsector):
        renderSubsector(node.subsector, clipBox)
        return

    isOnFront = isPointOnFront(player, node)
    near, far = (node.back, node.front) if isOnFront else (node.front, node.back)
    renderBsp(far,  clipBox)        // draw the far side first
    renderBsp(near, intersectBox(clipBox, node.line))   // clip to near side
```

**Source — DOOM source `r_bsp.c`**:
https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/r_bsp.c

**Source — "BSP Tree Rendering" by Daniel Rákos, Atomic Game Engine blog**:
https://www.rastertek.com/dx11tut11.html (similar algorithm)

### Wall clipping (Sutherland-Hodgman)

We clip each wall segment against the player's view frustum (a trapezoid
in screen space, or 4 half-planes in world space). The Sutherland-Hodgman
algorithm clips a polygon against any convex half-plane, one plane at a time,
in O(n) per plane per polygon.

**Pseudocode:**
```
clip(polygon, plane):
    out = []
    for each edge (a, b) in polygon:
        if a is inside plane and b is inside:
            out.add(b)
        elif a is inside and b is outside:
            out.add(intersect(a, b, plane))
        elif a is outside and b is inside:
            out.add(intersect(a, b, plane))
            out.add(b)
    return out
```

**Source — Ivan Sutherland's original paper (1974)**:
https://dl.acm.org/doi/10.1145/360767.360802

**Source — Practical implementation in DOOM source `r_segs.c`**:
https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/r_segs.c

### Perspective projection (column-based)

DOOM doesn't use a 3D matrix transform. It computes per-column screen-space
height of a wall directly:

```
screenHeight = (WALL_HEIGHT * FOCAL_LENGTH) / distanceToWall
```

Where:
- `WALL_HEIGHT` is the height of the wall in world units (typically 128 in DOOM maps)
- `FOCAL_LENGTH` is a constant tuned to the screen resolution (≈ 0.625 × screenHeight)
- `distanceToWall` is the perpendicular distance from the player to the wall

This avoids floating-point math entirely when paired with the 16.16 fixed-point.

**Source — Michael Abrash, *Graphics Programming Black Book*, Chapter 63 ("Building a 3D Engine in a Weekend")**:
http://www.drdobbs.com/parallel/graphics-programming-black-book/184404919

### Texture mapping (affine)

DOOM uses **affine** texture mapping (not perspective-correct). For each column,
the texture V coordinate is computed once and reused for every pixel in that column.
This produces the famous "sliding textures" effect on angled floors/walls but is
much cheaper than perspective division per pixel.

**Source — "Affine Texture Mapping" — Chris Hecker**:
http://www.chrishecker.com/Miscellaneous_Technical_Articles

### Visplanes

A visplane is a contiguous horizontal band of the screen with the same floor
and ceiling texture. DOOM tracks up to 128 visplanes per frame. If a new floor
or ceiling span doesn't match any existing visplane, a new one is created. If
128 are in use, the renderer forces a "visplane overflow" flush that submits
all current visplanes to the framebuffer.

**Source — DOOM source `r_plane.c`**:
https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/r_plane.c

## Color palette

DOOM uses a 256-color palette (8-bit indexed color). Each entry is an
RGB triplet. The renderer's framebuffer is `byte[]` with one byte per pixel
holding the palette index. Pixels are flushed to the GPU at end of frame
by the adapter as a single texture upload.

**Source — "The DOOM Palette"**:
https://doom.fandom.com/wiki/Playpal

## Performance constraints

- **Fixed 320×200** internal framebuffer. The adapter scales up to the window
  size at upload time (nearest-neighbor or integer-multiple).
- **No per-pixel allocations.** All pixel buffers are pre-allocated `byte[]` in
  the zone heap.
- **No `Math.sin`/`Math.cos` per pixel.** Trig is done via lookup table.
- **Subsector cap**: 256 visible subsectors per frame.

## Files

- `port/I_RenderPort.java`
- `adapter/NullRenderPort.java`

## TODO (Phase 5)

- `BspTraverser.walk(rootNode, playerPos, clipBox)`
- `WallClipper.clipSides(seg, clipBox)` — Sutherland-Hodgman
- `VisplaneBuilder.open/close/spans`
- `ColumnRenderer.drawColumn(x, y1, y2, texture, texX)`
- `FrameBuffer.blit(palette)` — for adapter upload
