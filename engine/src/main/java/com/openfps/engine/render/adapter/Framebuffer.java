/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R_ Colour buffer, depth buffer and tile geometry for the software
 * rasterizer.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * <b>This class is storage plus a tile map. It draws nothing.</b>
 * {@code render/README.md} section 1 assigns the drawing to
 * {@code Rasterizer} and {@code SpanRenderer}; overlapping that ownership is
 * how a rasterizer turns into an unmaintainable blob. Do not add a
 * {@code drawTriangle} here.
 *
 * <b>Colour buffer — {@code int[]}, RGBA8888, packed {@code 0xRRGGBBAA}.</b>
 * The format is pinned from outside: it must match what the presentation path
 * uploads, and that is libGDX {@code Pixmap.Format.RGBA8888}, so a finished
 * frame presents as a single texture upload with no per-pixel conversion
 * (README section 3 and section 12). {@code docs/ASSETS.md} section 4 makes
 * the same layout the texture format end to end, so
 * {@code TextureSampler} never swizzles.
 *
 * <b>Depth buffer — a separate {@code float[]} holding 1/w.</b> Cleared to
 * {@link #DEPTH_CLEAR} (= 0.0f, "infinitely far"), tested <i>greater is
 * nearer</i> (README section 8). Kept separate from colour because the two
 * have different element semantics and different clear values, and because an
 * all-zero clear value is the memset the JIT turns into the fastest path
 * available.
 *
 * <b>Entity-id buffer — a third {@code int[]}, same length, same stride, same
 * tiles.</b> Holds {@link Scene#UNTAGGED} or the id of whichever tagged
 * instance last won the depth test at that pixel. {@link OutlinePass} is its
 * only reader; it is <b>not</b> a hit-detection oracle, and the reason is not
 * taste — peers render at different resolutions and worker counts, so two
 * clients sampling it would disagree about who was hit, and under the lockstep
 * model that is a desync rather than a rounding difference.
 *
 * <b>The id buffer is cleared explicitly, not as part of {@link #clearDepth()}.
 * </b> {@link #clear(int)} clears all three because it is the frame-start
 * clear and a stale id there is a ghost outline. {@link #clearDepth()} clears
 * depth alone, because its one caller is the viewmodel pass separator: the
 * outline has already been drawn by then and view instances never write ids,
 * so clearing them again would be a second 3.7 MB memset at 720p for no
 * observable difference. {@link #clearEntityIds()} exists for anyone who wants
 * the third clear on its own.
 *
 * <b>Row stride is padded — {@code stride != width} in general.</b> Indexing
 * is {@code y * strideInPixels() + x}, never {@code y * width() + x}. The
 * stride is rounded up to a multiple of {@link #STRIDE_ALIGNMENT} pixels, and
 * {@link #tileSize()} is required to be a multiple of the same figure. Sixteen
 * {@code int}s are 64 bytes — one cache line — so every tile row begins at an
 * element index that is a multiple of 16, which is the false-sharing
 * mitigation README section 7 calls for. Two workers rasterizing horizontally
 * adjacent tiles would otherwise write the same cache line on every scanline
 * and lose most of the parallel win.
 *
 * <b>Honest limit of that mitigation.</b> The alignment this class can
 * guarantee is <i>relative</i>: every tile row starts a whole number of cache
 * lines from the array's first element. Whether that first element itself sits
 * on a 64-byte boundary is up to the JVM's object layout and allocator, and
 * Java offers no way to request it or observe it. If the base happens to be
 * line-aligned, no line is ever shared; if it is not, adjacent tile rows share
 * exactly one line at their boundary instead of a variable number. README
 * section 7 states the stronger "no line is ever shared" — that is achievable
 * in C with an aligned allocator, not in pure Java. The padding is still the
 * right thing to do: it makes the sharing minimal and deterministic instead of
 * dependent on the window width.
 *
 * <b>The padded columns belong to no tile.</b> Tiles cover the visible
 * {@code width x height} rectangle only, so pixels in {@code [width, stride)}
 * are never written by the raster pass. {@link #copyColorTo(int[])} is the
 * de-padding step the presentation path needs: the adapter must not hand the
 * raw {@code int[]} to a {@code width x height} texture upload.
 *
 * <b>Allocation.</b> This class allocates its two arrays directly rather than
 * through {@code I_MemoryPort}. That is the sanctioned, narrow exception
 * decided in README section 11(a) and recorded in {@code STYLE.md}
 * section 13.4: long-lived, engine-owned primitive buffers whose element type
 * is not {@code byte}, allocated once at initialisation and released at
 * shutdown, named sites only. A hot loop is <b>not</b> on its own a
 * qualifying reason. {@link #init(int, int)} and {@link #resize(int, int)} are
 * the only two allocation sites in this class; in particular {@link #clear()}
 * runs every frame and allocates nothing. The entity-id buffer is allocated
 * unconditionally rather than on demand: it is one more {@code int} per pixel
 * against the {@code int} of colour and the {@code float} of depth, and a
 * lazily allocated one would have to be published safely to every raster
 * worker mid-frame.
 *
 * <b>Threading.</b> This class is not synchronised and does not need to be.
 * The raster pass is safe because a pixel belongs to exactly one tile and a
 * tile is drawn by exactly one worker, so two workers never write the same
 * address (README section 7). Lifecycle calls — {@link #init(int, int)},
 * {@link #resize(int, int)}, {@link #shutdown()} — must not run concurrently
 * with a raster pass.
 */
public final class Framebuffer
{
    /**
     * Row stride alignment in pixels. Sixteen {@code int}s are exactly one
     * 64-byte cache line, which is what makes the tile-row alignment in
     * README section 7 work. Must stay a power of two:
     * {@link #alignUp(int, int)} relies on it.
     */
    public static final int STRIDE_ALIGNMENT = 16;

    /**
     * Default tile edge length in pixels. README section 7 names 64x64 as the
     * starting point and section 11(c) is explicit that it has never been
     * measured — it is a tuning parameter, which is why
     * {@link #Framebuffer(int)} accepts another value.
     */
    public static final int DEFAULT_TILE_SIZE = 64;

    /**
     * Depth clear value. 1/w = 0 is infinitely far, its bit pattern is all
     * zeros, and the depth test is <i>greater passes</i> (README section 8).
     */
    public static final float DEPTH_CLEAR = 0.0f;

    /** Opaque black in RGBA8888 — the default colour clear value. */
    public static final int CLEAR_COLOR_OPAQUE_BLACK = 0x000000FF;

    /** Required length of the array passed to {@link #tileBounds(int, int[])}. */
    public static final int TILE_BOUNDS_LENGTH = 4;

    /** Index of the inclusive minimum x in a {@link #tileBounds(int, int[])} result. */
    public static final int TILE_BOUNDS_MIN_X = 0;

    /** Index of the inclusive minimum y in a {@link #tileBounds(int, int[])} result. */
    public static final int TILE_BOUNDS_MIN_Y = 1;

    /** Index of the inclusive maximum x in a {@link #tileBounds(int, int[])} result. */
    public static final int TILE_BOUNDS_MAX_X = 2;

    /** Index of the inclusive maximum y in a {@link #tileBounds(int, int[])} result. */
    public static final int TILE_BOUNDS_MAX_Y = 3;

    /** Bit width of one RGBA8888 channel. */
    private static final int CHANNEL_BITS = 8;

    /** Mask of one RGBA8888 channel. */
    private static final int CHANNEL_MASK = 0xFF;

    /** Bit position of the red channel in {@code 0xRRGGBBAA}. */
    private static final int RED_SHIFT = 24;

    /** Bit position of the green channel in {@code 0xRRGGBBAA}. */
    private static final int GREEN_SHIFT = 16;

    /** Bit position of the blue channel in {@code 0xRRGGBBAA}. */
    private static final int BLUE_SHIFT = 8;

    private static final Logger LOG = LoggerFactory.getLogger(Framebuffer.class);

    /** Tile edge length in pixels; a multiple of {@link #STRIDE_ALIGNMENT}. */
    private final int tileSize;

    /**
     * Colour buffer, RGBA8888, {@code strideInPixels * height} elements.
     * MUTABLE: replaced on init and resize, written every frame.
     */
    private int[] color;

    /**
     * Depth buffer, 1/w, {@code strideInPixels * height} elements.
     * MUTABLE: replaced on init and resize, written every frame.
     */
    private float[] depth;

    /**
     * Entity-id buffer, {@code strideInPixels * height} elements.
     * MUTABLE: replaced on init and resize; written by the raster pass only
     * while a tagged scene is bound.
     */
    private int[] entityIds;

    /** Visible width in pixels. MUTABLE: set on init and resize. */
    private int width;

    /** Visible height in pixels. MUTABLE: set on init and resize. */
    private int height;

    /** Row stride in pixels, padded. MUTABLE: set on init and resize. */
    private int strideInPixels;

    /** Tile columns. MUTABLE: derived from width on init and resize. */
    private int tilesX;

    /** Tile rows. MUTABLE: derived from height on init and resize. */
    private int tilesY;

    /** Lifecycle state. MUTABLE: advanced by init and shutdown. */
    private State state;

    /**
     * Creates a framebuffer with the default {@value #DEFAULT_TILE_SIZE}-pixel
     * tile size. No memory is allocated until {@link #init(int, int)}.
     */
    public Framebuffer()
    {
        this(DEFAULT_TILE_SIZE);
    }

    /**
     * Creates a framebuffer with an explicit tile size. No memory is allocated
     * until {@link #init(int, int)}.
     *
     * @param tileSize tile edge length in pixels; must be positive and a
     *     multiple of {@link #STRIDE_ALIGNMENT}, because a tile row that does
     *     not start on a cache-line boundary reintroduces the false sharing
     *     the padded stride exists to remove
     * @throws IllegalArgumentException if the tile size is not a positive
     *     multiple of {@link #STRIDE_ALIGNMENT}
     */
    public Framebuffer(final int tileSize)
    {
        if (tileSize <= 0)
        {
            throw new IllegalArgumentException("tileSize must be > 0, got " + tileSize);
        }
        if (tileSize % STRIDE_ALIGNMENT != 0)
        {
            throw new IllegalArgumentException("tileSize must be a multiple of "
                + STRIDE_ALIGNMENT + " pixels (one cache line of int), got " + tileSize);
        }
        this.tileSize = tileSize;
        this.state = State.UNINITIALIZED;
    }

    // ---- lifecycle ----

    /**
     * Allocates the colour and depth buffers at the given surface size. Call
     * once, from {@code I_FrameCallback.onSurfaceReady}.
     *
     * @param newWidth visible width in pixels; must be positive
     * @param newHeight visible height in pixels; must be positive
     * @throws IllegalStateException if called from any state but
     *     {@code UNINITIALIZED}
     * @throws IllegalArgumentException if either dimension is not positive
     */
    public void init(final int newWidth, final int newHeight)
    {
        if (state != State.UNINITIALIZED)
        {
            throw new IllegalStateException("init() called from state " + state
                + " — only valid from UNINITIALIZED");
        }
        allocate(newWidth, newHeight);
        state = State.READY;
        LOG.info("Framebuffer initialized: {}x{} px, stride={} px, tiles={}x{} of {} px",
            width, height, strideInPixels, tilesX, tilesY, tileSize);
    }

    /**
     * Reallocates the buffers at a new surface size. Call from
     * {@code I_FrameCallback.onResize}. A resize to the current dimensions is
     * a no-op and allocates nothing; any other resize reallocates, so that
     * {@code colorBuffer().length} always equals {@link #pixelCount()}.
     * Together with {@link #init(int, int)} this is the only allocation site
     * in the class.
     *
     * <p>Buffer contents are not preserved: the next frame clears anyway.</p>
     *
     * @param newWidth visible width in pixels; must be positive
     * @param newHeight visible height in pixels; must be positive
     * @throws IllegalStateException if the framebuffer is not READY
     * @throws IllegalArgumentException if either dimension is not positive
     */
    public void resize(final int newWidth, final int newHeight)
    {
        requireReady("resize()");
        if (newWidth == width && newHeight == height)
        {
            return;
        }
        allocate(newWidth, newHeight);
        LOG.info("Framebuffer resized: {}x{} px, stride={} px, tiles={}x{} of {} px",
            width, height, strideInPixels, tilesX, tilesY, tileSize);
    }

    /**
     * Releases both buffers. Terminal: a shut-down framebuffer cannot be
     * re-initialised, matching the state machines on the engine's other ports.
     * A platform that loses and regains its surface constructs a new
     * framebuffer.
     *
     * @throws IllegalStateException if already SHUTDOWN
     */
    public void shutdown()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("shutdown() called from state SHUTDOWN "
                + "— already terminal");
        }
        color = null;
        depth = null;
        entityIds = null;
        width = 0;
        height = 0;
        strideInPixels = 0;
        tilesX = 0;
        tilesY = 0;
        state = State.SHUTDOWN;
        LOG.info("Framebuffer shut down");
    }

    /** Returns the current lifecycle state. */
    public State state()
    {
        return state;
    }

    // ---- dimensions ----

    /** Returns the visible width in pixels. */
    public int width()
    {
        return width;
    }

    /** Returns the visible height in pixels. */
    public int height()
    {
        return height;
    }

    /**
     * Returns the row stride in pixels. This is the width rounded up to a
     * multiple of {@link #STRIDE_ALIGNMENT} and is <b>not</b> necessarily
     * {@link #width()}. Index with {@code y * strideInPixels() + x}.
     *
     * @return row stride in pixels
     */
    public int strideInPixels()
    {
        return strideInPixels;
    }

    /**
     * Returns the length of both buffers, {@code strideInPixels * height}.
     * This counts the padding columns, so it is greater than
     * {@code width * height} whenever the width is not already aligned.
     *
     * @return number of elements in each buffer
     */
    public int pixelCount()
    {
        return strideInPixels * height;
    }

    /**
     * Returns the element index of a pixel: {@code y * strideInPixels + x}.
     * Unchecked — this is the address arithmetic the inner loop uses.
     *
     * @param x column, in {@code [0, width)}
     * @param y row, in {@code [0, height)}
     * @return index into {@link #colorBuffer()} and {@link #depthBuffer()}
     */
    public int index(final int x, final int y)
    {
        return y * strideInPixels + x;
    }

    // ---- buffers ----

    /**
     * Returns the colour buffer itself, RGBA8888 packed {@code 0xRRGGBBAA}.
     * Handed out raw and by reference because per-pixel accessor calls are a
     * non-starter in the raster inner loop. Callers must respect
     * {@link #strideInPixels()}.
     *
     * @return the live colour buffer
     */
    public int[] colorBuffer()
    {
        return color;
    }

    /**
     * Returns the depth buffer itself, holding 1/w. Greater is nearer.
     * Handed out raw for the same reason as {@link #colorBuffer()}.
     *
     * @return the live depth buffer
     */
    public float[] depthBuffer()
    {
        return depth;
    }

    /**
     * Returns the entity-id buffer itself, holding {@link Scene#UNTAGGED} or
     * the id of the tagged instance that owns each pixel. Handed out raw for
     * the same reason as {@link #colorBuffer()}.
     *
     * <p>{@link OutlinePass} is its only intended reader. See the class
     * Javadoc for why sampling it for hit detection is a desync.</p>
     *
     * @return the live entity-id buffer
     */
    public int[] entityIdBuffer()
    {
        return entityIds;
    }

    // ---- clearing ----

    /**
     * Clears colour to opaque black, depth to {@link #DEPTH_CLEAR} and entity
     * ids to {@link Scene#UNTAGGED}. Runs every frame and allocates nothing.
     */
    public void clear()
    {
        clear(CLEAR_COLOR_OPAQUE_BLACK);
    }

    /**
     * Clears colour to the given RGBA8888 value, depth to
     * {@link #DEPTH_CLEAR} and entity ids to {@link Scene#UNTAGGED}.
     * Allocates nothing.
     *
     * <p>All three, because this is the frame-start clear: an id surviving
     * from the previous frame draws an outline around where an entity
     * <i>was</i>.</p>
     *
     * @param rgba packed {@code 0xRRGGBBAA} clear colour
     */
    public void clear(final int rgba)
    {
        clearColor(rgba);
        clearDepth();
        clearEntityIds();
    }

    /**
     * Clears the colour buffer only. Allocates nothing.
     *
     * @param rgba packed {@code 0xRRGGBBAA} clear colour
     */
    public void clearColor(final int rgba)
    {
        requireReady("clearColor()");
        // Fills the padding columns too: one contiguous fill beats two
        // strided ones, and nothing ever reads the padding.
        Arrays.fill(color, rgba);
    }

    /**
     * Clears the depth buffer to {@link #DEPTH_CLEAR}, and nothing else.
     * Allocates nothing.
     *
     * <p>Deliberately <b>not</b> an id clear as well. Its one production
     * caller is the viewmodel pass separator, which runs after
     * {@link OutlinePass} has already consumed the ids and before a pass whose
     * instances are all {@link Scene#UNTAGGED}. Clearing them there would be a
     * second full-frame memset — 3.7 MB at 720p — that no later read could
     * distinguish from not doing it. Callers that want the third buffer
     * cleared say {@link #clearEntityIds()}.</p>
     */
    public void clearDepth()
    {
        requireReady("clearDepth()");
        Arrays.fill(depth, DEPTH_CLEAR);
    }

    /**
     * Clears the entity-id buffer to {@link Scene#UNTAGGED}. Allocates
     * nothing.
     *
     * <p>Whole-buffer rather than per-tile. Measured at 1280x720 the fill is
     * far below the cost of the frame it precedes, and a per-tile clear folded
     * into the raster pass would be wrong in the one case that matters: a pass
     * that culls every triangle dispatches no tiles, so the previous frame's
     * ids would survive and outline an entity that is no longer there.</p>
     */
    public void clearEntityIds()
    {
        requireReady("clearEntityIds()");
        Arrays.fill(entityIds, Scene.UNTAGGED);
    }

    // ---- single-pixel access (debug, tests, presentation checks) ----

    /**
     * Writes one colour pixel. Bounds-checked; not for the inner loop.
     *
     * @param x column, in {@code [0, width)}
     * @param y row, in {@code [0, height)}
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @throws IllegalArgumentException if the coordinate is outside the
     *     visible rectangle
     */
    public void setPixel(final int x, final int y, final int rgba)
    {
        checkPixel(x, y);
        color[index(x, y)] = rgba;
    }

    /**
     * Reads one colour pixel. Bounds-checked; not for the inner loop.
     *
     * @param x column, in {@code [0, width)}
     * @param y row, in {@code [0, height)}
     * @return packed {@code 0xRRGGBBAA} colour
     * @throws IllegalArgumentException if the coordinate is outside the
     *     visible rectangle
     */
    public int pixel(final int x, final int y)
    {
        checkPixel(x, y);
        return color[index(x, y)];
    }

    /**
     * Writes one depth value (1/w). Bounds-checked; not for the inner loop.
     *
     * @param x column, in {@code [0, width)}
     * @param y row, in {@code [0, height)}
     * @param invW the 1/w value to store
     * @throws IllegalArgumentException if the coordinate is outside the
     *     visible rectangle
     */
    public void setDepth(final int x, final int y, final float invW)
    {
        checkPixel(x, y);
        depth[index(x, y)] = invW;
    }

    /**
     * Reads one depth value (1/w). Bounds-checked; not for the inner loop.
     *
     * @param x column, in {@code [0, width)}
     * @param y row, in {@code [0, height)}
     * @return the stored 1/w; greater is nearer
     * @throws IllegalArgumentException if the coordinate is outside the
     *     visible rectangle
     */
    public float depthAt(final int x, final int y)
    {
        checkPixel(x, y);
        return depth[index(x, y)];
    }

    /**
     * Writes one entity id. Bounds-checked; not for the inner loop.
     *
     * @param x column, in {@code [0, width)}
     * @param y row, in {@code [0, height)}
     * @param entityId the id to store, or {@link Scene#UNTAGGED}
     * @throws IllegalArgumentException if the coordinate is outside the
     *     visible rectangle
     */
    public void setEntityId(final int x, final int y, final int entityId)
    {
        checkPixel(x, y);
        entityIds[index(x, y)] = entityId;
    }

    /**
     * Reads one entity id. Bounds-checked; not for the inner loop.
     *
     * @param x column, in {@code [0, width)}
     * @param y row, in {@code [0, height)}
     * @return the stored id, or {@link Scene#UNTAGGED}
     * @throws IllegalArgumentException if the coordinate is outside the
     *     visible rectangle
     */
    public int entityIdAt(final int x, final int y)
    {
        checkPixel(x, y);
        return entityIds[index(x, y)];
    }

    // ---- tile geometry ----

    /** Returns the tile edge length in pixels. */
    public int tileSize()
    {
        return tileSize;
    }

    /** Returns the number of tile columns, {@code ceil(width / tileSize)}. */
    public int tilesX()
    {
        return tilesX;
    }

    /** Returns the number of tile rows, {@code ceil(height / tileSize)}. */
    public int tilesY()
    {
        return tilesY;
    }

    /** Returns the total number of tiles, {@code tilesX * tilesY}. */
    public int tileCount()
    {
        return tilesX * tilesY;
    }

    /**
     * Returns the inclusive left edge of a tile in pixels.
     *
     * @param tileIndex tile index in {@code [0, tileCount())}, row-major
     * @return inclusive minimum x
     * @throws IllegalArgumentException if the tile index is out of range
     */
    public int tileMinX(final int tileIndex)
    {
        checkTile(tileIndex);
        return (tileIndex % tilesX) * tileSize;
    }

    /**
     * Returns the inclusive top edge of a tile in pixels.
     *
     * @param tileIndex tile index in {@code [0, tileCount())}, row-major
     * @return inclusive minimum y
     * @throws IllegalArgumentException if the tile index is out of range
     */
    public int tileMinY(final int tileIndex)
    {
        checkTile(tileIndex);
        return (tileIndex / tilesX) * tileSize;
    }

    /**
     * Returns the <b>inclusive</b> right edge of a tile in pixels, clamped to
     * {@code width - 1}. The last tile column is partial whenever the width is
     * not a multiple of the tile size.
     *
     * @param tileIndex tile index in {@code [0, tileCount())}, row-major
     * @return inclusive maximum x
     * @throws IllegalArgumentException if the tile index is out of range
     */
    public int tileMaxX(final int tileIndex)
    {
        return Math.min(tileMinX(tileIndex) + tileSize - 1, width - 1);
    }

    /**
     * Returns the <b>inclusive</b> bottom edge of a tile in pixels, clamped to
     * {@code height - 1}. The last tile row is partial whenever the height is
     * not a multiple of the tile size.
     *
     * @param tileIndex tile index in {@code [0, tileCount())}, row-major
     * @return inclusive maximum y
     * @throws IllegalArgumentException if the tile index is out of range
     */
    public int tileMaxY(final int tileIndex)
    {
        return Math.min(tileMinY(tileIndex) + tileSize - 1, height - 1);
    }

    /**
     * Returns the width of a tile in pixels — {@link #tileSize()} except in
     * the last column, where it is the partial remainder.
     *
     * @param tileIndex tile index in {@code [0, tileCount())}, row-major
     * @return tile width in pixels, always positive
     * @throws IllegalArgumentException if the tile index is out of range
     */
    public int tileWidth(final int tileIndex)
    {
        return tileMaxX(tileIndex) - tileMinX(tileIndex) + 1;
    }

    /**
     * Returns the height of a tile in pixels — {@link #tileSize()} except in
     * the last row, where it is the partial remainder.
     *
     * @param tileIndex tile index in {@code [0, tileCount())}, row-major
     * @return tile height in pixels, always positive
     * @throws IllegalArgumentException if the tile index is out of range
     */
    public int tileHeight(final int tileIndex)
    {
        return tileMaxY(tileIndex) - tileMinY(tileIndex) + 1;
    }

    /**
     * Writes a tile's pixel rectangle into a caller-supplied array, so the
     * rasterizer can take all four bounds without four calls and without
     * allocating. <b>Both maxima are inclusive.</b>
     *
     * @param tileIndex tile index in {@code [0, tileCount())}, row-major
     * @param out array of at least {@link #TILE_BOUNDS_LENGTH} elements,
     *     filled at {@link #TILE_BOUNDS_MIN_X}, {@link #TILE_BOUNDS_MIN_Y},
     *     {@link #TILE_BOUNDS_MAX_X}, {@link #TILE_BOUNDS_MAX_Y}
     * @throws IllegalArgumentException if the tile index is out of range or
     *     the array is too short
     */
    public void tileBounds(final int tileIndex, final int[] out)
    {
        checkTile(tileIndex);
        if (out == null || out.length < TILE_BOUNDS_LENGTH)
        {
            throw new IllegalArgumentException("tileBounds() needs an int["
                + TILE_BOUNDS_LENGTH + "]");
        }
        final int minX = (tileIndex % tilesX) * tileSize;
        final int minY = (tileIndex / tilesX) * tileSize;
        out[TILE_BOUNDS_MIN_X] = minX;
        out[TILE_BOUNDS_MIN_Y] = minY;
        out[TILE_BOUNDS_MAX_X] = Math.min(minX + tileSize - 1, width - 1);
        out[TILE_BOUNDS_MAX_Y] = Math.min(minY + tileSize - 1, height - 1);
    }

    /**
     * Returns the index of the tile owning a pixel. Every visible pixel
     * belongs to exactly one tile — that is the invariant the lock-free raster
     * pass rests on (README section 7).
     *
     * @param x column, in {@code [0, width)}
     * @param y row, in {@code [0, height)}
     * @return tile index in {@code [0, tileCount())}
     * @throws IllegalArgumentException if the coordinate is outside the
     *     visible rectangle
     */
    public int tileIndexAt(final int x, final int y)
    {
        checkPixel(x, y);
        return (y / tileSize) * tilesX + (x / tileSize);
    }

    // ---- presentation handoff ----

    /**
     * Copies the visible {@code width x height} rectangle into a contiguous,
     * unpadded destination — the de-padding step the presentation path needs
     * (README section 12). The adapter uploads {@code destination}, not
     * {@link #colorBuffer()}, because the latter carries the stride padding
     * and is longer than the image.
     *
     * <p>Allocates nothing; the destination is the caller's, allocated once
     * alongside the framebuffer.</p>
     *
     * @param destination array of at least {@code width * height} elements,
     *     filled row by row with RGBA8888 pixels
     * @throws IllegalStateException if the framebuffer is not READY
     * @throws IllegalArgumentException if the destination is null or too short
     */
    public void copyColorTo(final int[] destination)
    {
        requireReady("copyColorTo()");
        final int required = width * height;
        if (destination == null || destination.length < required)
        {
            throw new IllegalArgumentException("copyColorTo() needs an int[" + required
                + "] for a " + width + "x" + height + " frame");
        }
        for (int y = 0; y < height; y++)
        {
            System.arraycopy(color, y * strideInPixels, destination, y * width, width);
        }
    }

    // ---- RGBA8888 packing — the single definition of the layout ----

    /**
     * Packs one RGBA8888 pixel as {@code 0xRRGGBBAA}. Every channel is masked
     * to 8 bits, so out-of-range inputs wrap rather than corrupting a
     * neighbouring channel.
     *
     * @param r red, 0-255
     * @param g green, 0-255
     * @param b blue, 0-255
     * @param a alpha, 0-255
     * @return packed colour
     */
    public static int packRgba(final int r, final int g, final int b, final int a)
    {
        return Rgba.pack(r, g, b, a);
    }

    /**
     * Extracts the red channel of a packed RGBA8888 pixel.
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @return red, 0-255
     */
    public static int red(final int rgba)
    {
        return Rgba.red(rgba);
    }

    /**
     * Extracts the green channel of a packed RGBA8888 pixel.
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @return green, 0-255
     */
    public static int green(final int rgba)
    {
        return Rgba.green(rgba);
    }

    /**
     * Extracts the blue channel of a packed RGBA8888 pixel.
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @return blue, 0-255
     */
    public static int blue(final int rgba)
    {
        return Rgba.blue(rgba);
    }

    /**
     * Extracts the alpha channel of a packed RGBA8888 pixel.
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @return alpha, 0-255
     */
    public static int alpha(final int rgba)
    {
        return Rgba.alpha(rgba);
    }

    /**
     * Writes one packed RGBA8888 pixel into a byte array in R, G, B, A order —
     * the byte layout {@code Pixmap.Format.RGBA8888} and OpenGL
     * {@code GL_RGBA / GL_UNSIGNED_BYTE} both expect.
     *
     * <p>This exists so that the mapping from {@code int} to bytes is written
     * down in exactly one place and is testable without a GPU. The alternative
     * — letting each call site reach for {@code IntBuffer.put} and inherit
     * whichever {@code ByteOrder} that buffer happens to carry — is precisely
     * the byte-order step {@code docs/ASSETS.md} section 4 warns about. On a
     * little-endian machine an {@code IntBuffer} view in native order emits
     * A, B, G, R instead.</p>
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @param out destination byte array
     * @param offset index of the red byte; the next three bytes are written too
     * @throws IllegalArgumentException if the destination cannot hold four
     *     bytes at the offset
     */
    public static void writeRgbaBytes(final int rgba, final byte[] out, final int offset)
    {
        if (out == null || offset < 0 || offset + Integer.BYTES > out.length)
        {
            throw new IllegalArgumentException("writeRgbaBytes() needs 4 bytes at offset "
                + offset);
        }
        out[offset] = (byte) red(rgba);
        out[offset + 1] = (byte) green(rgba);
        out[offset + 2] = (byte) blue(rgba);
        out[offset + 3] = (byte) alpha(rgba);
    }

    /**
     * Reads four bytes in R, G, B, A order back into a packed RGBA8888
     * {@code int}. Inverse of {@link #writeRgbaBytes(int, byte[], int)}.
     *
     * @param in source byte array
     * @param offset index of the red byte
     * @return packed {@code 0xRRGGBBAA} colour
     * @throws IllegalArgumentException if the source cannot supply four bytes
     *     at the offset
     */
    public static int readRgbaBytes(final byte[] in, final int offset)
    {
        if (in == null || offset < 0 || offset + Integer.BYTES > in.length)
        {
            throw new IllegalArgumentException("readRgbaBytes() needs 4 bytes at offset "
                + offset);
        }
        return packRgba(in[offset], in[offset + 1], in[offset + 2], in[offset + 3]);
    }

    /**
     * Rounds a value up to the next multiple of a power-of-two alignment.
     * Public because the presentation path and any future buffer that has to
     * agree with {@link #strideInPixels()} should compute it the same way
     * rather than restating the arithmetic.
     *
     * @param value value to round up; must not be negative
     * @param alignment alignment; must be a positive power of two
     * @return the smallest multiple of {@code alignment} that is at least
     *     {@code value}
     */
    public static int alignUp(final int value, final int alignment)
    {
        return (value + alignment - 1) & -alignment;
    }

    /** Lifecycle states, mirroring the engine's other resource-owning ports. */
    public enum State
    {
        /** Constructed; no buffers exist yet. */
        UNINITIALIZED,

        /** Buffers allocated and usable. */
        READY,

        /** Buffers released; terminal. */
        SHUTDOWN
    }

    // ---- internals ----

    // The one allocation site. Both callers (init, resize) route through here
    // so the stride and tile-grid derivation cannot drift between them.
    private void allocate(final int newWidth, final int newHeight)
    {
        if (newWidth <= 0 || newHeight <= 0)
        {
            throw new IllegalArgumentException("Framebuffer dimensions must be > 0, got "
                + newWidth + "x" + newHeight);
        }

        // Stride, not width. Padding every row up to a whole number of cache
        // lines is what puts each tile row on a line boundary — see the class
        // Javadoc and README section 7.
        final int newStride = alignUp(newWidth, STRIDE_ALIGNMENT);
        final long elements = (long) newStride * (long) newHeight;
        if (elements > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("Framebuffer " + newWidth + "x" + newHeight
                + " needs " + elements + " elements, more than an array can hold");
        }

        // Sanctioned allocation — STYLE.md § 13.4, README § 11(a).
        this.color = new int[(int) elements];
        this.depth = new float[(int) elements];
        // Java zeroes a fresh int[], and Scene.UNTAGGED is zero, so a
        // just-allocated id buffer is already cleared.
        this.entityIds = new int[(int) elements];
        this.width = newWidth;
        this.height = newHeight;
        this.strideInPixels = newStride;
        this.tilesX = ceilDiv(newWidth, tileSize);
        this.tilesY = ceilDiv(newHeight, tileSize);
    }

    // Ceiling division for positive operands.
    private static int ceilDiv(final int value, final int divisor)
    {
        return (value + divisor - 1) / divisor;
    }

    // Rejects use of a framebuffer that has no buffers.
    private void requireReady(final String operation)
    {
        if (state != State.READY)
        {
            throw new IllegalStateException(operation + " called from state " + state
                + " — only valid from READY");
        }
    }

    // Rejects a coordinate outside the visible rectangle. The padding columns
    // are deliberately not addressable: nothing may draw into them.
    private void checkPixel(final int x, final int y)
    {
        requireReady("pixel access");
        if (x < 0 || x >= width || y < 0 || y >= height)
        {
            throw new IllegalArgumentException("Pixel (" + x + ", " + y
                + ") outside " + width + "x" + height);
        }
    }

    // Rejects a tile index outside the grid.
    private void checkTile(final int tileIndex)
    {
        requireReady("tile access");
        if (tileIndex < 0 || tileIndex >= tilesX * tilesY)
        {
            throw new IllegalArgumentException("Tile index " + tileIndex + " outside [0, "
                + (tilesX * tilesY) + ")");
        }
    }
}
