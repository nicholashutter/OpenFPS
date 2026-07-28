/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openfps.engine.core.pool.I_ParallelJob;
import com.openfps.engine.core.pool.I_ThreadPoolPort;

/**
 * R_ Perspective divide, viewport transform, backface cull, edge-function
 * setup and tile binning for the software rasterizer.
 *
 * Render adapter — the implementation behind {@code render.port.I_RenderPort}.
 *
 * <p><b>This class decides <i>which</i> pixels a triangle can touch and
 * <i>where</i>; it never touches one.</b> The per-pixel loop belongs to
 * {@link SpanRenderer} ({@code render/README.md} § 1). The two share one thing
 * — the per-triangle record laid out by this class and consumed by that one —
 * and that shared layout is documented here because this class writes it.</p>
 *
 * <h2>Input</h2>
 *
 * <p>Clip-space triangles, exactly as {@link TriangleClipper} emits them: a
 * flat {@code float[]} of {@code 3 * stride} floats per triangle, stride
 * {@code 3 + attributeCount}, each vertex laid out {@code [x, y, w, attr...]}.
 * There is no {@code z} — see {@link Camera}. Every {@code w} is assumed
 * positive, which the clipper guarantees, so {@code 1/w} is finite.</p>
 *
 * <h2>The per-triangle record</h2>
 *
 * <p>One contiguous run of {@link #recordStride()} floats per triangle, not a
 * set of parallel arrays. {@code docs/ASSETS.md} § 2 measures the raster phase
 * at 600-900 ns per triangle against 26-64 ns of setup, and names scattered
 * cache misses on triangle data as a large part of the difference — so
 * everything the inner loop needs for one triangle is laid out to be fetched
 * together:</p>
 *
 * <pre>
 *   [ 0.. 2]  edge 0 (v1-&gt;v2): dE/dx, dE/dy, constant
 *   [ 3.. 5]  edge 1 (v2-&gt;v0)
 *   [ 6.. 8]  edge 2 (v0-&gt;v1)
 *   [ 9..11]  per-edge fill-rule bias
 *   [12..14]  the 1/w plane: d/dx, d/dy, constant
 *   [15..18]  screen bounding box: minX, minY, maxX, maxY (whole numbers)
 *   [19..  ]  one plane per vertex attribute, each holding attr/w
 * </pre>
 *
 * <p>Everything is a plane equation of the form {@code f(x, y) = dx * x + dy *
 * y + c}, evaluated at integer pixel coordinates: the half-pixel offset that
 * puts the sample at the pixel <i>centre</i> is folded into {@code c} once, at
 * setup. Edge functions and interpolants therefore share one evaluation form,
 * and no stage after this one has to remember where a sample point is.</p>
 *
 * <p>Material and flat colour are deliberately <b>not</b> in the record. They
 * are {@code int}s, and round-tripping an {@code int} through a {@code float}
 * array by bit pattern is unsound: a plausible RGBA8888 value such as
 * {@code 0xFF800001} is a <i>signalling</i> NaN, which a JVM is permitted to
 * quieten in transit. They stay in the caller's {@code int[]} arrays, which
 * cost one extra sequential fetch per triangle and cannot corrupt a colour.</p>
 *
 * <h2>Edge functions and the sign convention</h2>
 *
 * <p>The edge function is the standard orientation determinant, which for an
 * edge {@code p -> q} evaluated at {@code r} is</p>
 *
 * <pre>
 *   E(r) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
 * </pre>
 *
 * <p>and the signed area is {@code area2 = E_v0v1(v2)}, matching
 * {@code render/README.md} § 7's {@code area2} formula and satisfying
 * {@code e0 + e1 + e2 == area2}, so that {@code lambda_i = e_i / area2} sums to
 * one. <b>README § 7's separate {@code E01(x, y)} formula is the negation of
 * this one</b> and is inconsistent with its own {@code area2}; the two cannot
 * both hold. This class uses the form above throughout.</p>
 *
 * <p>Setup normalises the sign: when {@code area2} is negative every edge
 * coefficient is negated, so the inner loop always tests "inside means not
 * less than the bias" with no per-triangle sign to carry.</p>
 *
 * <h2>The top-left fill rule, and why it is exact</h2>
 *
 * <p>Two triangles sharing an edge must paint each pixel on it exactly once.
 * With the sign convention above and screen y growing downward, an edge owns
 * its boundary pixels when {@code dE/dx > 0} (a left edge) or when
 * {@code dE/dx == 0 && dE/dy > 0} (a top edge). The neighbouring triangle
 * traverses the same edge backwards, so its coefficients are the exact
 * negations of these, and exactly one of the pair passes the test.</p>
 *
 * <p>The rule is applied as a per-edge <b>bias</b> rather than README § 7's
 * "bias the constant by -1", because -1 is a subpixel unit in a fixed-point
 * rasterizer and this one is {@code float} (§ 2). The bias is
 * {@link #TOP_LEFT_BIAS} for an owned edge and {@link #EXCLUSIVE_BIAS} for the
 * others, and the test {@code e >= bias} is then exactly {@code e >= 0} or
 * {@code e > 0} respectively — {@link Float#MIN_VALUE} being the smallest
 * positive float, no positive value can fall below it. No epsilon, no fudge.</p>
 *
 * <p>Exactness also requires that the two triangles' edge functions be bitwise
 * negations of one another. That is why the edge constant is computed as the
 * cross product {@code px*qy - qx*py} rather than the algebraically identical
 * {@code -(dx*px + dy*py)}: swapping {@code p} and {@code q} swaps the two
 * products, and IEEE subtraction is exactly antisymmetric, whereas the second
 * form rounds two different expressions. {@link SpanRenderer} preserves the
 * property by evaluating {@code dx * x + (dy * y + c)} directly rather than
 * accumulating incrementally, which would drift apart between the two.</p>
 *
 * <h2>Binning, and why it is per chunk rather than per worker</h2>
 *
 * <p>README § 7 requires per-worker private bins, concatenated in worker
 * order, so that binning does not reintroduce the shared write the tile pass
 * exists to avoid. It also — correctly — forbids assigning work by worker id,
 * because {@code submitParallel}'s participating caller has no worker id. Both
 * are satisfied by binning per <b>chunk</b>: the triangle stream is split into
 * {@link #chunkCount()} contiguous ranges, chunk {@code c} is one job index and
 * therefore runs exactly once and never concurrently with itself, so its bin
 * region is private without any thread identity being involved.</p>
 *
 * <p>Bins are built as a counting sort, in three steps: count per (chunk, tile)
 * while writing records; prefix-sum serially; scatter triangle indices. The
 * prefix sum walks tiles outermost, so one tile's entries end up
 * <b>contiguous</b> and in ascending triangle order across all chunks, and the
 * tile pass reads them as a single flat run.</p>
 *
 * <p><b>Determinism.</b> Because a chunk is a contiguous range of the stream
 * and chunks are laid out in index order, a tile's triangles are always visited
 * in submission order, whatever the thread count. Coplanar surfaces at equal
 * depth therefore resolve the same way every run. Nothing in the renderer can
 * desync lockstep (§ 2), but a renderer that flickers between runs is miserable
 * to debug.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Parallel work goes through {@link I_ThreadPoolPort#submitParallel} —
 * {@code AGENTS.md} rule 1, and the pool's claim counter is exactly the shared
 * atomic tile claim README § 7 requires. The pool port is imported here for the
 * same reason {@code resource.adapter.LumpCache} imports {@code I_MemoryPort}:
 * it is a shared engine service, not platform code. The single-argument
 * overloads run every pass on the calling thread and exist so that the
 * multi-threaded result can be compared against a serial one.</p>
 *
 * <p><b>One instance renders one frame at a time.</b> Records and bins are
 * instance state. {@link SpanRenderer}, by contrast, is stateless and may be
 * shared by every tile.</p>
 *
 * <p><b>Visibility.</b> Each pass is separated from the next by a
 * {@code submitParallel} boundary, which publishes on the way in and joins on
 * the way out, so the records one pass writes are visible to the next without
 * any barrier of ours. The per-frame configuration fields are nonetheless
 * declared {@code volatile}: they are rebound between frames rather than
 * between passes, and their correctness should not rest on a reader knowing
 * how the pool publishes. They are hoisted into locals at the top of the hot
 * methods so a pass pays for that once, not per triangle.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>Nothing is allocated per frame. The record array is sized once from
 * {@code maxTriangles}; the bin index arrays are sized from the tile grid and
 * reallocated only when the framebuffer's tile count changes, which happens on
 * a window resize; the bin entry array grows only when a frame bins more
 * (triangle, tile) pairs than any previous frame, and never shrinks.</p>
 *
 * <h2>Sources</h2>
 * <ul>
 *   <li>Pineda, "A Parallel Algorithm for Polygon Rasterization", SIGGRAPH '88
 *       — https://dl.acm.org/doi/10.1145/378456.378457</li>
 *   <li>Fabian Giesen, "Optimizing the basic rasterizer" — incremental edge
 *       functions, fill rules, block traversal —
 *       https://fgiesen.wordpress.com/2013/02/10/optimizing-the-basic-rasterizer/</li>
 * </ul>
 */
public final class Rasterizer
{
    /** Record offset of edge 0's x coefficient. Edge 0 runs from v1 to v2. */
    public static final int EDGE0_DX = 0;

    /** Record offset of edge 0's y coefficient. */
    public static final int EDGE0_DY = 1;

    /** Record offset of edge 0's constant, pixel-centre adjusted. */
    public static final int EDGE0_CONST = 2;

    /** Record offset of edge 1's x coefficient. Edge 1 runs from v2 to v0. */
    public static final int EDGE1_DX = 3;

    /** Record offset of edge 1's y coefficient. */
    public static final int EDGE1_DY = 4;

    /** Record offset of edge 1's constant, pixel-centre adjusted. */
    public static final int EDGE1_CONST = 5;

    /** Record offset of edge 2's x coefficient. Edge 2 runs from v0 to v1. */
    public static final int EDGE2_DX = 6;

    /** Record offset of edge 2's y coefficient. */
    public static final int EDGE2_DY = 7;

    /** Record offset of edge 2's constant, pixel-centre adjusted. */
    public static final int EDGE2_CONST = 8;

    /** Record offset of edge 0's fill-rule bias. */
    public static final int EDGE0_BIAS = 9;

    /** Record offset of edge 1's fill-rule bias. */
    public static final int EDGE1_BIAS = 10;

    /** Record offset of edge 2's fill-rule bias. */
    public static final int EDGE2_BIAS = 11;

    /** Record offset of the 1/w plane's x coefficient. */
    public static final int INV_W_DX = 12;

    /** Record offset of the 1/w plane's y coefficient. */
    public static final int INV_W_DY = 13;

    /** Record offset of the 1/w plane's constant, pixel-centre adjusted. */
    public static final int INV_W_CONST = 14;

    /** Record offset of the inclusive minimum screen x, as a whole number. */
    public static final int BOUND_MIN_X = 15;

    /** Record offset of the inclusive minimum screen y, as a whole number. */
    public static final int BOUND_MIN_Y = 16;

    /**
     * Record offset of the inclusive maximum screen x. A value below
     * {@link #BOUND_MIN_X} marks a triangle that was culled or fell off screen.
     */
    public static final int BOUND_MAX_X = 17;

    /** Record offset of the inclusive maximum screen y, as a whole number. */
    public static final int BOUND_MAX_Y = 18;

    /** Floats in a record before the attribute planes begin. */
    public static final int RECORD_HEADER_FLOATS = 19;

    /** Floats in one plane equation: x coefficient, y coefficient, constant. */
    public static final int PLANE_FLOATS = 3;

    /**
     * Fill-rule bias for an edge that owns the pixels lying exactly on it.
     * {@code e >= 0.0f} accepts them.
     */
    public static final float TOP_LEFT_BIAS = 0.0f;

    /**
     * Fill-rule bias for an edge that does not own its boundary pixels. No
     * positive float is smaller than {@link Float#MIN_VALUE}, so
     * {@code e >= Float.MIN_VALUE} is exactly {@code e > 0.0f}.
     */
    public static final float EXCLUSIVE_BIAS = Float.MIN_VALUE;

    /** Material index meaning "this triangle has no texture". */
    public static final int NO_MATERIAL = -1;

    /** Flat colour used when the caller supplies no per-triangle colours. */
    public static final int DEFAULT_FLAT_COLOR = 0xFFFFFFFF;

    private static final Logger LOG = LoggerFactory.getLogger(Rasterizer.class);

    /** Half a pixel — the offset from a pixel's corner to its centre. */
    private static final float HALF = 0.5f;

    private final int attributeCount;
    private final int vertexStride;
    private final int recordStride;
    private final int maxTriangles;
    private final int chunkCount;
    private final CullMode cullMode;

    /** Per-triangle setup records, {@code maxTriangles * recordStride} floats. */
    private final float[] records;

    /** Setup-and-count pass, one index per chunk. Held once; never allocated per frame. */
    private final I_ParallelJob setupJob = this::runSetupChunk;

    /** Bin scatter pass, one index per chunk. */
    private final I_ParallelJob scatterJob = this::runScatterChunk;

    /** Raster pass, one index per tile. */
    private final I_ParallelJob tileJob = this::runTile;

    /** Entries per (chunk, tile). MUTABLE: counted every frame, resized on tile-grid change. */
    private int[] binCount;

    /** Start of each (chunk, tile) run in {@link #binEntries}. MUTABLE: rebuilt every frame. */
    private int[] binStart;

    /** Append cursor per (chunk, tile); after scatter it is the run end. MUTABLE. */
    private int[] binCursor;

    /** Triangle indices, grouped by tile then chunk. MUTABLE: grows to the high-water mark. */
    private int[] binEntries;

    /** The frame's target. MUTABLE: rebound by beginFrame. */
    private volatile Framebuffer framebuffer;

    /** Clip-space vertex stream for the current frame. MUTABLE: rebound per frame. */
    private volatile float[] clipVertices;

    /** Per-triangle material indices, or null. MUTABLE: rebound per frame. */
    private volatile int[] materials;

    /** Per-triangle flat colours, or null. MUTABLE: rebound per frame. */
    private volatile int[] flatColors;

    /**
     * Per-triangle entity ids, or null when this pass writes none. MUTABLE:
     * rebound per frame.
     *
     * <p>Null is the switch for the whole feature: it means the raster pass
     * never touches {@link Framebuffer#entityIdBuffer()}, which is what makes a
     * scene with nothing tagged cost nothing.</p>
     */
    private volatile int[] entityIds;

    /**
     * The id buffer the raster pass writes, or null. MUTABLE: derived from
     * {@link #entityIds} once per rasterize call so the per-pixel loop is
     * handed an array reference rather than a framebuffer to interrogate.
     */
    private volatile int[] entityIdTarget;

    /** The span renderer for the raster pass. MUTABLE: rebound per rasterize call. */
    private volatile SpanRenderer spanRenderer;

    /** Texture table indexed by material, or null. MUTABLE: rebound per rasterize call. */
    private volatile MipChain[] textures;

    /** Triangles submitted this frame. MUTABLE: set per frame. */
    private volatile int triangleCount;

    /** Cached framebuffer geometry. MUTABLE: refreshed by beginFrame. */
    private volatile int width;

    /** Cached framebuffer geometry. MUTABLE: refreshed by beginFrame. */
    private volatile int height;

    /** Cached framebuffer geometry. MUTABLE: refreshed by beginFrame. */
    private volatile int tileSize;

    /** Cached framebuffer geometry. MUTABLE: refreshed by beginFrame. */
    private volatile int tilesX;

    /** Cached framebuffer geometry. MUTABLE: refreshed by beginFrame. */
    private volatile int tileCount;

    /**
     * Indexed passes dispatched since construction. MUTABLE: bumped once per
     * {@link #dispatch}, only ever by the submitting thread.
     */
    private volatile long parallelPasses;

    /**
     * Creates a rasterizer and allocates everything that does not depend on the
     * framebuffer.
     *
     * @param attributeCount floats per vertex beyond the three position floats,
     *     matching the {@link TriangleClipper} that produced the input — 2 for
     *     UV, 3 for a baked RGB, 0 for position-only geometry
     * @param maxTriangles most triangles a single frame may submit; one record
     *     is reserved per triangle, culled or not, so that a triangle's record
     *     offset is simply its index
     * @param chunkCount how many contiguous slices the triangle stream is split
     *     into for the parallel setup and binning passes; around one per worker
     *     is the intended figure. Output does not depend on it
     * @param cullMode which screen-space winding to discard
     * @throws IllegalArgumentException if any count is out of range or the cull
     *     mode is null
     */
    public Rasterizer(final int attributeCount, final int maxTriangles, final int chunkCount,
        final CullMode cullMode)
    {
        if (attributeCount < 0)
        {
            throw new IllegalArgumentException(
                "attributeCount must not be negative, got " + attributeCount);
        }
        if (maxTriangles <= 0)
        {
            throw new IllegalArgumentException("maxTriangles must be > 0, got " + maxTriangles);
        }
        if (chunkCount <= 0)
        {
            throw new IllegalArgumentException("chunkCount must be > 0, got " + chunkCount);
        }
        if (cullMode == null)
        {
            throw new IllegalArgumentException("cullMode must not be null");
        }
        this.attributeCount = attributeCount;
        this.vertexStride = TriangleClipper.POSITION_FLOATS + attributeCount;
        this.recordStride = RECORD_HEADER_FLOATS + PLANE_FLOATS * attributeCount;
        this.maxTriangles = maxTriangles;
        this.chunkCount = chunkCount;
        this.cullMode = cullMode;
        this.records = new float[maxTriangles * recordStride];
        this.binCount = new int[0];
        this.binStart = new int[0];
        this.binCursor = new int[0];
        this.binEntries = new int[maxTriangles];
    }

    /**
     * Returns the record offset of one attribute's plane equation.
     *
     * @param attributeIndex attribute number, counting from the first float
     *     after {@code w} in the vertex layout
     * @return offset within a record of that attribute's {@code attr/w} plane
     */
    public static int attributePlaneOffset(final int attributeIndex)
    {
        return RECORD_HEADER_FLOATS + PLANE_FLOATS * attributeIndex;
    }

    // ---- frame lifecycle ----

    /**
     * Binds the framebuffer for a frame and clears the previous frame's bins.
     *
     * <p>Allocates only when the tile grid has changed size since the last
     * call, which happens on a window resize.</p>
     *
     * @param target the framebuffer to rasterize into; must be READY
     * @throws IllegalArgumentException if the framebuffer is null
     * @throws IllegalStateException if the framebuffer has no buffers
     */
    public void beginFrame(final Framebuffer target)
    {
        if (target == null)
        {
            throw new IllegalArgumentException("framebuffer must not be null");
        }
        if (target.state() != Framebuffer.State.READY)
        {
            throw new IllegalStateException("Framebuffer is " + target.state() + ", not READY");
        }
        this.framebuffer = target;
        this.width = target.width();
        this.height = target.height();
        this.tileSize = target.tileSize();
        this.tilesX = target.tilesX();
        this.triangleCount = 0;

        final int tiles = target.tileCount();
        if (tiles != tileCount)
        {
            this.tileCount = tiles;
            final int slots = chunkCount * tiles;
            this.binCount = new int[slots];
            this.binStart = new int[slots];
            this.binCursor = new int[slots];
            return;
        }
        Arrays.fill(binCount, 0);
    }

    /**
     * Runs setup and binning on the calling thread.
     *
     * @param vertices clip-space vertices, three per triangle
     * @param triangles number of triangles in {@code vertices}
     * @param materialIndices per-triangle material index, or null for none
     * @param triangleColors per-triangle flat colour, or null for the default
     */
    public void setupAndBin(final float[] vertices, final int triangles,
        final int[] materialIndices, final int[] triangleColors)
    {
        setupAndBin(vertices, triangles, materialIndices, triangleColors, null, null);
    }

    /**
     * Transforms, culls and bins a frame's triangles, in parallel across chunks.
     *
     * <p>Every input triangle gets a record slot whether or not it survives, so
     * a triangle's record offset is always {@code index * recordStride()}. A
     * culled triangle is marked by an empty bounding box and is binned into no
     * tile at all, so the raster pass never sees it.</p>
     *
     * @param vertices clip-space vertices, {@code 3 * triangles * } {@link
     *     #vertexStride()} floats, each vertex {@code [x, y, w, attr...]} with
     *     {@code w} positive
     * @param triangles number of triangles in {@code vertices}
     * @param materialIndices per-triangle index into the texture table passed to
     *     {@link #rasterize}, {@link #NO_MATERIAL} for untextured; null means
     *     every triangle is untextured
     * @param triangleColors per-triangle RGBA8888 flat colour; null means
     *     {@link #DEFAULT_FLAT_COLOR} throughout
     * @param pool the worker pool, or null to run on the calling thread
     * @throws IllegalStateException if {@link #beginFrame} has not been called
     * @throws IllegalArgumentException if the counts or buffer sizes disagree
     */
    public void setupAndBin(final float[] vertices, final int triangles,
        final int[] materialIndices, final int[] triangleColors, final I_ThreadPoolPort pool)
    {
        setupAndBin(vertices, triangles, materialIndices, triangleColors, null, pool);
    }

    /**
     * Transforms, culls and bins a frame's triangles, carrying an entity id per
     * triangle.
     *
     * <p>Everything the five-argument overload says still holds. The one
     * addition is {@code triangleEntityIds}: <b>null means this pass writes no
     * entity ids at all</b> and {@link Framebuffer#entityIdBuffer()} is never
     * touched, which is the state every scene with nothing tagged renders in.
     * Non-null means every pixel that passes the depth test records its
     * triangle's id, {@link Scene#UNTAGGED} included — see
     * {@link SpanRenderer}'s class Javadoc for why skipping the zeroes is a
     * bug rather than an optimisation.</p>
     *
     * @param vertices clip-space vertices, three per triangle
     * @param triangles number of triangles in {@code vertices}
     * @param materialIndices per-triangle material index, or null for none
     * @param triangleColors per-triangle flat colour, or null for the default
     * @param triangleEntityIds per-triangle entity id, or null to write none
     * @param pool the worker pool, or null to run on the calling thread
     * @throws IllegalStateException if {@link #beginFrame} has not been called
     * @throws IllegalArgumentException if the counts or buffer sizes disagree
     */
    public void setupAndBin(final float[] vertices, final int triangles,
        final int[] materialIndices, final int[] triangleColors,
        final int[] triangleEntityIds, final I_ThreadPoolPort pool)
    {
        requireFrame();
        if (triangles < 0 || triangles > maxTriangles)
        {
            throw new IllegalArgumentException("triangles must be in [0, " + maxTriangles
                + "], got " + triangles);
        }
        final int neededFloats = triangles * TriangleClipper.TRIANGLE_VERTICES * vertexStride;
        if (vertices == null || vertices.length < neededFloats)
        {
            throw new IllegalArgumentException("vertices must hold " + neededFloats + " floats");
        }
        requireTable(materialIndices, triangles, "materialIndices");
        requireTable(triangleColors, triangles, "triangleColors");
        requireTable(triangleEntityIds, triangles, "triangleEntityIds");

        this.clipVertices = vertices;
        this.materials = materialIndices;
        this.flatColors = triangleColors;
        this.entityIds = triangleEntityIds;
        this.triangleCount = triangles;

        dispatch(setupJob, chunkCount, pool);
        buildBinOffsets();
        dispatch(scatterJob, chunkCount, pool);
    }

    /**
     * Rasterizes every tile on the calling thread.
     *
     * @param renderer the per-pixel loop to run
     * @param textureTable textures indexed by material, or null
     */
    public void rasterize(final SpanRenderer renderer, final MipChain[] textureTable)
    {
        rasterize(renderer, textureTable, null);
    }

    /**
     * Rasterizes every tile, one worker per tile, exclusive ownership.
     *
     * <p>Tiles are claimed from {@code submitParallel}'s shared atomic counter,
     * so no tile is tied to a worker identity and the participating caller takes
     * its share like anyone else. A pixel belongs to exactly one tile and a tile
     * is drawn by exactly one thread, which is what makes the depth test a plain
     * array read-modify-write with no atomic (README § 7).</p>
     *
     * @param renderer the per-pixel loop; a single instance serves every tile
     *     because {@link SpanRenderer} holds no mutable state
     * @param textureTable textures indexed by the material indices given to
     *     {@link #setupAndBin}, or null when nothing is textured
     * @param pool the worker pool, or null to run every tile on the calling
     *     thread
     * @throws IllegalStateException if {@link #beginFrame} has not been called
     * @throws IllegalArgumentException if the renderer is null or disagrees with
     *     this rasterizer's attribute count
     */
    public void rasterize(final SpanRenderer renderer, final MipChain[] textureTable,
        final I_ThreadPoolPort pool)
    {
        requireFrame();
        if (renderer == null)
        {
            throw new IllegalArgumentException("renderer must not be null");
        }
        if (renderer.attributeCount() != attributeCount)
        {
            throw new IllegalArgumentException("SpanRenderer expects " + renderer.attributeCount()
                + " attributes, this Rasterizer produces " + attributeCount);
        }
        this.spanRenderer = renderer;
        this.textures = textureTable;
        this.entityIdTarget = idTargetFor(framebuffer);
        dispatch(tileJob, tileCount, pool);
    }

    // ---- accessors ----

    /** Returns the floats per vertex in the clip-space input: 3 plus attributes. */
    public int vertexStride()
    {
        return vertexStride;
    }

    /** Returns the floats in one per-triangle setup record. */
    public int recordStride()
    {
        return recordStride;
    }

    /** Returns the attribute count this rasterizer was built for. */
    public int attributeCount()
    {
        return attributeCount;
    }

    /** Returns how many contiguous slices the triangle stream is split into. */
    public int chunkCount()
    {
        return chunkCount;
    }

    /** Returns the screen-space winding this rasterizer discards. */
    public CullMode cullMode()
    {
        return cullMode;
    }

    /** Returns the number of triangles submitted for the current frame. */
    public int triangleCount()
    {
        return triangleCount;
    }

    /**
     * Returns how many indexed passes this rasterizer has dispatched since it
     * was constructed.
     *
     * <p>Three per frame — setup-and-count, scatter, tile raster. With a pool
     * each is a {@code submitParallel} publish/join boundary, which is what
     * makes this number the barrier count the caller pays; without one they are
     * plain loops on the calling thread. Sampled either side of a frame by
     * {@link SoftwareRenderPort#lastFrameParallelPasses()}, which is the whole
     * reason it is exposed.</p>
     *
     * @return the running dispatch count
     */
    public long parallelPasses()
    {
        return parallelPasses;
    }

    /**
     * Returns the setup records themselves, by reference. Handed out raw
     * because {@link SpanRenderer} indexes them in its inner loop; also the
     * hook tests use to inspect setup without rasterizing.
     *
     * @return the live record array
     */
    public float[] records()
    {
        return records;
    }

    /**
     * Reports whether a triangle survived setup.
     *
     * @param triangleIndex index into the submitted stream
     * @return true if the triangle was neither culled nor entirely off screen
     */
    public boolean isLive(final int triangleIndex)
    {
        final int base = triangleIndex * recordStride;
        return records[base + BOUND_MAX_X] >= records[base + BOUND_MIN_X];
    }

    /**
     * Returns how many triangles were binned into one tile. Valid after
     * {@link #setupAndBin}.
     *
     * @param tileIndex tile index in {@code [0, tileCount)}
     * @return number of (triangle, tile) entries for that tile
     */
    public int binnedTriangleCount(final int tileIndex)
    {
        return binEnd(tileIndex) - binStart[tileIndex];
    }

    /**
     * Which screen-space winding {@link Rasterizer} discards.
     *
     * <p><b>The project's answer is
     * {@link SoftwareRenderPort#BACKFACE_CULL_MODE}.</b> This enum stays
     * open — a rasterizer is not the place to hard-code an art-pipeline
     * convention — but the pipeline that consumes {@code ModelFormat} has one,
     * it is {@link #CLOCKWISE}, and the empirical evidence for it is recorded
     * on that field. Do not re-derive it from handedness: it depends on
     * {@link Camera}'s basis order as much as on the model data, and it has
     * already flipped once when that order was corrected.</p>
     */
    public enum CullMode
    {
        /** Draw both windings; only zero-area triangles are rejected. */
        NONE,

        /**
         * Discard triangles wound clockwise on screen — those whose signed area
         * is positive, screen y growing downward.
         */
        CLOCKWISE,

        /** Discard triangles wound counter-clockwise on screen. */
        COUNTER_CLOCKWISE
    }

    // ---- pass 1: setup and count ----

    // Transforms, culls and records one chunk's triangles, counting the tiles
    // each covers. Chunk c is one job index, so this body never runs
    // concurrently with itself and its slice of binCount is private to it.
    private void runSetupChunk(final int chunk)
    {
        final int from = chunkStart(chunk);
        final int to = chunkStart(chunk + 1);
        final int countBase = chunk * tileCount;
        for (int triangle = from; triangle < to; triangle++)
        {
            if (setupTriangle(triangle))
            {
                countTiles(triangle, countBase);
            }
        }
    }

    // Writes one triangle's record. Returns false if it was culled, degenerate
    // or entirely off screen, in which case the record carries an empty box.
    private boolean setupTriangle(final int triangle)
    {
        final int base = triangle * recordStride;
        final float[] src = clipVertices;
        final int a = triangle * TriangleClipper.TRIANGLE_VERTICES * vertexStride;
        final int b = a + vertexStride;
        final int c = b + vertexStride;

        // The frame geometry is volatile so that a pass sees what beginFrame
        // published; read it once here rather than twelve times below.
        final int viewWidth = width;
        final int viewHeight = height;

        final float invW0 = 1.0f / src[a + TriangleClipper.OFFSET_W];
        final float invW1 = 1.0f / src[b + TriangleClipper.OFFSET_W];
        final float invW2 = 1.0f / src[c + TriangleClipper.OFFSET_W];

        final float sx0 = screenX(src[a + TriangleClipper.OFFSET_X], invW0, viewWidth);
        final float sy0 = screenY(src[a + TriangleClipper.OFFSET_Y], invW0, viewHeight);
        final float sx1 = screenX(src[b + TriangleClipper.OFFSET_X], invW1, viewWidth);
        final float sy1 = screenY(src[b + TriangleClipper.OFFSET_Y], invW1, viewHeight);
        final float sx2 = screenX(src[c + TriangleClipper.OFFSET_X], invW2, viewWidth);
        final float sy2 = screenY(src[c + TriangleClipper.OFFSET_Y], invW2, viewHeight);

        // One sign check culls the back face and rejects the degenerate
        // triangle before anything divides by its area. A NaN area lands here
        // too: every comparison against NaN is false.
        final float area2 = (sx1 - sx0) * (sy2 - sy0) - (sy1 - sy0) * (sx2 - sx0);
        final float sign = cullSign(area2);
        if (sign == 0.0f || !writeBounds(base, sx0, sy0, sx1, sy1, sx2, sy2))
        {
            markRejected(base);
            return false;
        }

        writeEdge(base, EDGE0_DX, EDGE0_BIAS, sx1, sy1, sx2, sy2, sign);
        writeEdge(base, EDGE1_DX, EDGE1_BIAS, sx2, sy2, sx0, sy0, sign);
        writeEdge(base, EDGE2_DX, EDGE2_BIAS, sx0, sy0, sx1, sy1, sign);

        // 1/area2 once, then multiply — README § 7. Both operands carry the
        // normalised sign, so the quotient is positive.
        final float invArea2 = 1.0f / (area2 * sign);
        writePlane(base, INV_W_DX, invW0, invW1, invW2, invArea2);
        for (int k = 0; k < attributeCount; k++)
        {
            final int offset = TriangleClipper.POSITION_FLOATS + k;
            // attr/w is the screen-linear quantity, not attr — README § 8.
            writePlane(base, attributePlaneOffset(k), src[a + offset] * invW0,
                src[b + offset] * invW1, src[c + offset] * invW2, invArea2);
        }
        return true;
    }

    // Perspective divide and viewport transform — README § 4.
    private static float screenX(final float clipX, final float invW, final int viewWidth)
    {
        return (clipX * invW * HALF + HALF) * viewWidth;
    }

    // The y flip lives here, once per vertex, so no later stage thinks about it.
    private static float screenY(final float clipY, final float invW, final int viewHeight)
    {
        return (HALF - clipY * invW * HALF) * viewHeight;
    }

    // Returns +1 to keep as-is, -1 to negate the edges, 0 to reject.
    private float cullSign(final float area2)
    {
        if (area2 > 0.0f)
        {
            if (cullMode == CullMode.CLOCKWISE)
            {
                return 0.0f;
            }
            return 1.0f;
        }
        if (area2 < 0.0f)
        {
            if (cullMode == CullMode.COUNTER_CLOCKWISE)
            {
                return 0.0f;
            }
            return -1.0f;
        }
        return 0.0f;
    }

    // Clamps the screen-space bounding box to the viewport, which is what makes
    // the four side frustum planes free (README § 6). Returns false when the
    // triangle misses the viewport entirely, or when any coordinate is NaN.
    private boolean writeBounds(final int base, final float sx0, final float sy0,
        final float sx1, final float sy1, final float sx2, final float sy2)
    {
        final float minXf = Math.min(sx0, Math.min(sx1, sx2));
        final float maxXf = Math.max(sx0, Math.max(sx1, sx2));
        final float minYf = Math.min(sy0, Math.min(sy1, sy2));
        final float maxYf = Math.max(sy0, Math.max(sy1, sy2));
        if (!(maxXf >= 0.0f && minXf < width && maxYf >= 0.0f && minYf < height))
        {
            return false;
        }
        records[base + BOUND_MIN_X] = floorClamp(minXf, width - 1);
        records[base + BOUND_MAX_X] = ceilClamp(maxXf, width - 1);
        records[base + BOUND_MIN_Y] = floorClamp(minYf, height - 1);
        records[base + BOUND_MAX_Y] = ceilClamp(maxYf, height - 1);
        return true;
    }

    // Writes one edge's plane and its fill-rule bias.
    private void writeEdge(final int base, final int edgeOffset, final int biasOffset,
        final float px, final float py, final float qx, final float qy, final float sign)
    {
        final float dx = (py - qy) * sign;
        final float dy = (qx - px) * sign;
        // Cross-product form, deliberately. See the class Javadoc: the adjacent
        // triangle's mirrored edge must produce the exact negation of this
        // constant or the top-left rule stops being exact on a shared edge.
        final float constant = (px * qy - qx * py) * sign;
        final float[] r = records;
        r[base + edgeOffset] = dx;
        r[base + edgeOffset + 1] = dy;
        // Fold the half-pixel sample offset in once, so the inner loop can
        // evaluate at integer pixel coordinates.
        r[base + edgeOffset + 2] = constant + HALF * (dx + dy);
        // Left edge, or top edge. The neighbour sharing this edge sees both
        // coefficients exactly negated, so exactly one of the pair owns it.
        if (dx > 0.0f || (dx == 0.0f && dy > 0.0f))
        {
            r[base + biasOffset] = TOP_LEFT_BIAS;
            return;
        }
        r[base + biasOffset] = EXCLUSIVE_BIAS;
    }

    // Turns three per-vertex values into the screen-space plane that
    // interpolates them, using the already-written edge planes as the
    // barycentric weights. The edge constants are pixel-centre adjusted, so the
    // result is too.
    private void writePlane(final int base, final int planeOffset, final float q0,
        final float q1, final float q2, final float invArea2)
    {
        final float[] r = records;
        r[base + planeOffset] = (r[base + EDGE0_DX] * q0 + r[base + EDGE1_DX] * q1
            + r[base + EDGE2_DX] * q2) * invArea2;
        r[base + planeOffset + 1] = (r[base + EDGE0_DY] * q0 + r[base + EDGE1_DY] * q1
            + r[base + EDGE2_DY] * q2) * invArea2;
        r[base + planeOffset + 2] = (r[base + EDGE0_CONST] * q0 + r[base + EDGE1_CONST] * q1
            + r[base + EDGE2_CONST] * q2) * invArea2;
    }

    // An empty box: every later pass skips this triangle without a flag field.
    private void markRejected(final int base)
    {
        records[base + BOUND_MIN_X] = 0.0f;
        records[base + BOUND_MIN_Y] = 0.0f;
        records[base + BOUND_MAX_X] = -1.0f;
        records[base + BOUND_MAX_Y] = -1.0f;
    }

    // Bounding-box binning over-includes — a thin diagonal is counted into
    // corner tiles it never touches, which cost one edge test each. Testing
    // edges against tile corners here is a known refinement; README § 7 says
    // not to do it until the profile asks.
    private void countTiles(final int triangle, final int countBase)
    {
        final int base = triangle * recordStride;
        final int minX = (int) records[base + BOUND_MIN_X];
        final int maxX = (int) records[base + BOUND_MAX_X];
        final int minY = (int) records[base + BOUND_MIN_Y];
        final int maxY = (int) records[base + BOUND_MAX_Y];
        final int edge = tileSize;
        final int columns = tilesX;
        final int firstTileX = minX / edge;
        final int lastTileX = maxX / edge;
        final int lastTileY = maxY / edge;
        for (int ty = minY / edge; ty <= lastTileY; ty++)
        {
            final int rowBase = countBase + ty * columns;
            for (int tx = firstTileX; tx <= lastTileX; tx++)
            {
                binCount[rowBase + tx]++;
            }
        }
    }

    // ---- between the passes: the prefix sum ----

    // Tiles outermost so that one tile's entries end up contiguous and, because
    // chunks are contiguous ranges of the stream visited in index order, in
    // ascending triangle order. That is where the raster pass's determinism
    // comes from.
    private void buildBinOffsets()
    {
        // MUTABLE local — the running offset into binEntries.
        int running = 0;
        for (int tile = 0; tile < tileCount; tile++)
        {
            for (int chunk = 0; chunk < chunkCount; chunk++)
            {
                final int slot = chunk * tileCount + tile;
                binStart[slot] = running;
                binCursor[slot] = running;
                running += binCount[slot];
            }
        }
        if (binEntries.length < running)
        {
            LOG.debug("Rasterizer bin capacity {} -> {} entries", binEntries.length, running);
            binEntries = new int[running];
        }
    }

    // ---- pass 2: scatter ----

    private void runScatterChunk(final int chunk)
    {
        final int from = chunkStart(chunk);
        final int to = chunkStart(chunk + 1);
        final int cursorBase = chunk * tileCount;
        for (int triangle = from; triangle < to; triangle++)
        {
            final int base = triangle * recordStride;
            final int minX = (int) records[base + BOUND_MIN_X];
            final int maxX = (int) records[base + BOUND_MAX_X];
            if (maxX < minX)
            {
                continue;
            }
            scatterTiles(triangle, cursorBase, minX, maxX);
        }
    }

    private void scatterTiles(final int triangle, final int cursorBase, final int minX,
        final int maxX)
    {
        final int base = triangle * recordStride;
        final int minY = (int) records[base + BOUND_MIN_Y];
        final int maxY = (int) records[base + BOUND_MAX_Y];
        final int edge = tileSize;
        final int columns = tilesX;
        final int[] entries = binEntries;
        final int[] cursor = binCursor;
        final int firstTileX = minX / edge;
        final int lastTileX = maxX / edge;
        final int lastTileY = maxY / edge;
        for (int ty = minY / edge; ty <= lastTileY; ty++)
        {
            final int rowBase = cursorBase + ty * columns;
            for (int tx = firstTileX; tx <= lastTileX; tx++)
            {
                final int slot = rowBase + tx;
                entries[cursor[slot]] = triangle;
                cursor[slot]++;
            }
        }
    }

    // ---- pass 3: raster ----

    // One tile, claimed by exactly one thread. Everything this reads is
    // per-frame immutable except the framebuffer's own pixels, and those belong
    // to this tile alone.
    private void runTile(final int tile)
    {
        final Framebuffer target = framebuffer;
        final SpanRenderer renderer = spanRenderer;
        final MipChain[] textureTable = textures;
        // Null unless this pass carries entity ids. Hoisted here, once per
        // tile, so the per-pixel test is against a local rather than a
        // volatile read.
        final int[] idTarget = entityIdTarget;
        final int minX = target.tileMinX(tile);
        final int minY = target.tileMinY(tile);
        final int maxX = target.tileMaxX(tile);
        final int maxY = target.tileMaxY(tile);
        final int end = binEnd(tile);
        for (int entry = binStart[tile]; entry < end; entry++)
        {
            final int triangle = binEntries[entry];
            renderer.renderTriangle(target, records, triangle * recordStride,
                materialOf(triangle), flatColorOf(triangle), textureTable,
                idTarget, entityIdOf(triangle), minX, minY, maxX, maxY);
        }
    }

    // The id buffer this pass writes, or null when it writes none. A pass with
    // no per-triangle id table never touches the third buffer at all, and that
    // is the whole cost model: an untagged scene pays one null compare per
    // tile, not one store per pixel.
    private int[] idTargetFor(final Framebuffer target)
    {
        if (entityIds == null)
        {
            return null;
        }
        return target.entityIdBuffer();
    }

    private int entityIdOf(final int triangle)
    {
        final int[] table = entityIds;
        if (table == null)
        {
            return Scene.UNTAGGED;
        }
        return table[triangle];
    }

    // The last chunk's cursor is the end of the tile's single contiguous run.
    private int binEnd(final int tile)
    {
        return binCursor[(chunkCount - 1) * tileCount + tile];
    }

    private int materialOf(final int triangle)
    {
        final int[] table = materials;
        if (table == null)
        {
            return NO_MATERIAL;
        }
        return table[triangle];
    }

    private int flatColorOf(final int triangle)
    {
        final int[] table = flatColors;
        if (table == null)
        {
            return DEFAULT_FLAT_COLOR;
        }
        return table[triangle];
    }

    // ---- plumbing ----

    // Runs one indexed pass. A null pool means "this thread does all of it",
    // which is the serial reference the multi-threaded output is compared
    // against; otherwise the pool's claim counter hands out the indices and the
    // submitting thread participates (I_ThreadPoolPort.submitParallel).
    private void dispatch(final I_ParallelJob job, final int jobCount,
        final I_ThreadPoolPort pool)
    {
        this.parallelPasses = parallelPasses + 1L;
        if (pool == null)
        {
            for (int index = 0; index < jobCount; index++)
            {
                job.runJob(index);
            }
            return;
        }
        pool.submitParallel(job, jobCount);
    }

    // Chunk boundaries as a long product so a large triangle count cannot
    // overflow the multiplication.
    private int chunkStart(final int chunk)
    {
        return (int) ((long) triangleCount * chunk / chunkCount);
    }

    private void requireFrame()
    {
        if (framebuffer == null)
        {
            throw new IllegalStateException("beginFrame() has not been called");
        }
    }

    private static void requireTable(final int[] table, final int triangles, final String name)
    {
        if (table != null && table.length < triangles)
        {
            throw new IllegalArgumentException(name + " must hold " + triangles + " entries");
        }
    }

    // Float-to-int casts saturate in Java, so an infinite or hugely out-of-range
    // coordinate clamps rather than wrapping. NaN never reaches here: writeBounds
    // rejects it first.
    private static float floorClamp(final float value, final int limit)
    {
        final int floor = (int) Math.floor(value);
        if (floor < 0)
        {
            return 0.0f;
        }
        if (floor > limit)
        {
            return limit;
        }
        return floor;
    }

    private static float ceilClamp(final float value, final int limit)
    {
        final int ceil = (int) Math.ceil(value);
        if (ceil < 0)
        {
            return 0.0f;
        }
        if (ceil > limit)
        {
            return limit;
        }
        return ceil;
    }

    /** Returns a debug rendering of this rasterizer's configuration. */
    @Override
    public String toString()
    {
        return "Rasterizer{attributeCount=" + attributeCount + ", recordStride=" + recordStride
            + ", maxTriangles=" + maxTriangles + ", chunkCount=" + chunkCount
            + ", cullMode=" + cullMode + "}";
    }
}
