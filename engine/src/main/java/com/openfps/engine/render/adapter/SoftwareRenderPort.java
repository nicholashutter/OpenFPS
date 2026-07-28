/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import com.openfps.engine.core.pool.I_ParallelJob;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.port.I_RenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R_ The real software rasterizer: the eight Phase 5 components wired into one
 * pipeline behind {@link I_RenderPort}.
 *
 * Render adapter — the implementation behind {@code render.port.I_RenderPort}.
 * It knows nothing about windows, contexts or graphics APIs; it fills a
 * {@link Framebuffer} and stops ({@code render/README.md} § 12).
 *
 * <h2>Pipeline</h2>
 *
 * Exactly {@code render/README.md} § 5, with the stage boundaries the component
 * table draws:
 *
 * <pre>
 *   ModelFormat  -&gt;  Camera        model to world to clip  per vertex
 *                -&gt;  TriangleClipper  near-plane clip      per triangle
 *                -&gt;  Rasterizer    divide, viewport, cull,
 *                                   edge setup, bin to tiles
 *                -&gt;  SpanRenderer  per tile, per pixel     via TextureSampler
 *                -&gt;  Framebuffer   finished
 * </pre>
 *
 * <p>Four {@code submitParallel} passes make up an instance: geometry
 * (transform and clip), then {@link Rasterizer}'s own setup-and-count and
 * scatter, then the tile raster. Each is separated from the next by the pool's
 * publish/join boundary, so no barrier of ours is needed.</p>
 *
 * <h2>The scene, and the two passes</h2>
 *
 * <p>{@link Scene} holds two instance lists, and a frame renders them in this
 * order:</p>
 *
 * <ol>
 *   <li>Clear colour and depth.</li>
 *   <li><b>World instances</b>, each with its own {@code modelToWorld}
 *       concatenated into the camera's packed transform — once per instance,
 *       so the per-vertex cost is identical to the untransformed case
 *       ({@link Camera#packModelToClip}).</li>
 *   <li><b>Clear depth, not colour.</b></li>
 *   <li><b>View instances</b> — the first-person viewmodel — which are already
 *       in view space and take the projection alone
 *       ({@link Camera#packViewToClip}).</li>
 * </ol>
 *
 * <p>The depth reset in step 3 is the classic FPS solution to the weapon
 * clipping through a wall the player is standing against: the viewmodel is
 * depth-tested only against itself, so nothing in the world can occlude it,
 * and it still composites over the world because colour is left alone. The
 * alternative — squeezing the world into part of the depth range and the
 * viewmodel into the rest — buys nothing here and costs precision everywhere.</p>
 *
 * <p><b>Instances are rendered one at a time</b>, each through the whole
 * pipeline, sharing the framebuffer's depth buffer. That is what makes the
 * result independent of submission order: the depth test resolves two
 * instances against each other exactly as it resolves two triangles.
 * {@link Rasterizer} is <b>reused</b> across instances — one instance for the
 * whole port — but must be <b>reset per instance</b> with
 * {@link Rasterizer#beginFrame}, because its per-triangle records and tile bins
 * are indexed by position within a single submitted stream. It does not clear
 * the framebuffer, so resetting it between instances composites rather than
 * overwrites.</p>
 *
 * <h2>The backface winding convention — SETTLED, EMPIRICALLY</h2>
 *
 * <p><b>{@link #BACKFACE_CULL_MODE} is {@link Rasterizer.CullMode#CLOCKWISE}:
 * a front face is <i>counter-clockwise</i> in screen space, and the clockwise
 * winding is what gets discarded.</b> {@code render/README.md} § 7 left this
 * deliberately unpinned because {@code ModelFormat} did not exist. It does now,
 * so it is pinned here.</p>
 *
 * <p><b>The evidence, and why it is not an argument about handedness.</b>
 * Getting this wrong renders a closed mesh inside-out, which looks like a
 * plausible model rather than like an error, so it is settled by measurement
 * rather than by reasoning:</p>
 *
 * <ul>
 *   <li>A z-buffer resolves a <i>closed</i> mesh correctly with <b>no culling
 *       at all</b> — the nearest surface wins per pixel, whatever order the
 *       triangles arrive in. So {@link Rasterizer.CullMode#NONE} is an
 *       independent oracle for what the image must look like, and it depends on
 *       no winding assumption whatsoever.</li>
 *   <li>{@code SoftwareRenderPortTest} renders a closed cube — six faces, six
 *       distinct textures, wound per the glTF 2.0 rule that a front face is
 *       counter-clockwise seen from outside — three times: once with
 *       {@code NONE}, once with {@code CLOCKWISE}, once with
 *       {@code COUNTER_CLOCKWISE}. It asserts pixel equality against the
 *       {@code NONE} oracle.</li>
 *   <li>{@code CLOCKWISE} reproduces the oracle exactly.
 *       {@code COUNTER_CLOCKWISE} does not: it keeps only the far faces and
 *       shows the cube's interior. The test asserts both directions, so it
 *       fails if either the convention or the oracle drifts.</li>
 * </ul>
 *
 * <p><b>This answer depends on {@link Camera}'s basis order, and it flipped
 * once already.</b> An earlier revision of {@code render/README.md} § 4
 * specified {@code right = normalize(up x forward)}, which is a horizontal
 * mirror; against that camera the oracle chose
 * {@link Rasterizer.CullMode#COUNTER_CLOCKWISE}. Correcting the basis to
 * {@code right = normalize(forward x up)} negates every screen x, hence negates
 * {@code area2}, hence flips this constant — measured again from scratch, not
 * assumed. Anyone changing the camera basis must re-run the oracle rather than
 * reasoning about which way it should go; the two are one decision, and
 * {@link Camera}'s own Javadoc carries the other half of it.</p>
 *
 * <p>The count that {@code render/README.md} § 7 worries about — "two winding
 * flips that may or may not cancel" — comes out at <b>two</b> with the
 * corrected basis: the view transform is orientation-reversing (it maps a
 * right-handed world onto a left-handed view frame, so {@code right x up ==
 * -forward}), and the {@code sy} flip in {@link Rasterizer} reverses it again.
 * They cancel, and glTF's counter-clockwise front face stays counter-clockwise
 * on screen. That agrees with the measurement, which is the only reason it is
 * written down; it is a check, not the justification.</p>
 *
 * <h2>Shading</h2>
 *
 * <p>{@link SpanRenderer.ShadingMode#TEXTURED} with {@link #ATTRIBUTE_COUNT}
 * attributes — u and v. A submesh whose {@code textureIndex} is
 * {@link ModelFormat#NO_TEXTURE} falls back to a flat colour, and the colour
 * used is the baked vertex colour of the triangle's first vertex, so untextured
 * geometry still carries the artist's colour rather than turning white.</p>
 *
 * <h2>Threading and the presentation handoff</h2>
 *
 * <p>{@link #renderFrame} runs on a worker thread — {@code RenderSubsystem} is
 * dispatched from the event bus — and fans out through
 * {@link I_ThreadPoolPort#submitParallel}, whose caller participates. A null
 * pool runs every pass on the calling thread, which is the serial reference the
 * parallel result is compared against and is what the build-time tools use.</p>
 *
 * <p>{@link #copyColorInto} and {@link #renderFrame} are serialised against one
 * another by one lock. The platform's render thread and the render worker are
 * different threads, and the colour buffer is neither double-buffered nor
 * atomically swapped, so without the lock a presented frame could be half of
 * one frame and half of the next. The presenter therefore blocks for at most
 * one frame time, and pays exactly one copy: the de-padding copy
 * {@link Framebuffer#copyColorTo} has to make anyway.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>Nothing is allocated per frame except the {@link Camera}, which
 * {@code render/README.md} § 4 explicitly sanctions ("Build one per frame").
 * Every geometry buffer is sized by {@link #setScene}, and the framebuffer only
 * by {@link #resize}.</p>
 *
 * <p><b>Sized for the largest instance, not for their sum.</b> Instances go
 * through the pipeline one at a time, so the clip-space stream only ever holds
 * one of them. The buffers therefore grow when a scene arrives containing a
 * model bigger than any seen so far, and never shrink — a scene swap that adds
 * instances of models already sized for allocates nothing at all. The
 * per-instance packed transform is one 12-float scratch reused by every
 * instance in the frame.</p>
 */
public final class SoftwareRenderPort implements I_RenderPort
{
    /**
     * Vertex attributes carried from the model through to the span loop: u and
     * v. Matches {@link SpanRenderer#TEXTURED_ATTRIBUTES}.
     */
    public static final int ATTRIBUTE_COUNT = SpanRenderer.TEXTURED_ATTRIBUTES;

    /**
     * The screen-space winding discarded as a back face. See the class Javadoc
     * for the empirical evidence that settled it; do not change it without
     * re-running {@code SoftwareRenderPortTest}'s cull-mode oracle.
     */
    public static final Rasterizer.CullMode BACKFACE_CULL_MODE =
        Rasterizer.CullMode.CLOCKWISE;

    /** Default vertical field of view, in radians. */
    public static final float DEFAULT_FOV_Y = (float) (Math.PI / 3.0);

    /**
     * Default near plane. Small, because the whole reason it exists is that
     * {@code 1/w} is evaluated at {@code w = near}; it is not a culling knob.
     */
    public static final float DEFAULT_NEAR = 0.05f;

    /** Background the colour buffer is cleared to — a dark slate, not black. */
    public static final int DEFAULT_CLEAR_COLOR = Rgba.pack(24, 28, 38, 255);

    /** Radians the default orbit camera advances per tic. */
    public static final float ORBIT_RADIANS_PER_TIC = 0.01f;

    /**
     * Most triangles one input triangle can become after the near-plane clip.
     * Matches {@link TriangleClipper#MAX_OUTPUT_TRIANGLES}; named here because
     * it is the factor every geometry buffer is sized by.
     */
    public static final int CLIP_EXPANSION = TriangleClipper.MAX_OUTPUT_TRIANGLES;

    private static final Logger LOG = LoggerFactory.getLogger(SoftwareRenderPort.class);

    /** How far back the default camera sits, as a multiple of the model radius. */
    private static final float ORBIT_DISTANCE_FACTOR = 2.6f;

    /**
     * How high the default camera sits, as a multiple of the model radius.
     * Above the top of a unit cube's bounding sphere projection, so the orbit
     * shows three faces rather than one — which is what makes an inside-out
     * render obvious at a glance.
     */
    private static final float ORBIT_HEIGHT_FACTOR = 0.9f;

    /** Floats per vertex in the clip-space stream: x, y, w, u, v. */
    private static final int VERTEX_STRIDE =
        TriangleClipper.POSITION_FLOATS + ATTRIBUTE_COUNT;

    /** Floats per triangle in the clip-space stream. */
    private static final int TRIANGLE_FLOATS = TriangleClipper.TRIANGLE_VERTICES * VERTEX_STRIDE;

    /** World up used by the default orbit camera. */
    private static final Vec3 WORLD_UP = new Vec3(0.0f, 1.0f, 0.0f);

    /** World origin, where the fallback camera for a world-less scene sits. */
    private static final Vec3 ORIGIN = new Vec3(0.0f, 0.0f, 0.0f);

    /** The direction that fallback camera looks in; view space is +z forward. */
    private static final Vec3 VIEW_FORWARD = new Vec3(0.0f, 0.0f, 1.0f);

    /** Nanoseconds in a millisecond, for the frame-time log. */
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private final I_ThreadPoolPort pool;
    private final I_TimePort time;
    private final Framebuffer framebuffer;
    private final SpanRenderer spanRenderer;
    private final int chunkCount;
    private final Rasterizer.CullMode cullMode;

    /** Serialises {@link #renderFrame} against {@link #copyColorInto}. */
    private final ReentrantLock frameLock = new ReentrantLock();

    /** The transform-and-clip pass, one index per chunk. Held once; never allocated per frame. */
    private final I_ParallelJob geometryJob = this::runGeometryChunk;

    /**
     * The packed model-to-clip transform of the instance currently being
     * transformed. MUTABLE: rewritten once per instance, read by every
     * geometry chunk of that instance. One scratch serves the whole frame
     * because instances are rendered strictly one after another, and the
     * pool's publish/join boundary is what makes the write visible to the
     * workers.
     */
    private final float[] geometryTransform = new float[Camera.WORLD_TO_CLIP_FLOATS];

    /** The bound scene, or null before one is set. MUTABLE: rebound by {@link #setScene}. */
    private volatile Scene scene;

    /** Derived tables for the scene's world instances. MUTABLE: rebuilt by {@link #setScene}. */
    private volatile Instance[] worldInstances;

    /** Derived tables for the scene's view instances. MUTABLE: rebuilt by {@link #setScene}. */
    private volatile Instance[] viewInstances;

    /** Largest instance the geometry buffers are sized for. MUTABLE: grows, never shrinks. */
    private volatile int sizedForTriangles;

    /** The instance the geometry pass is transforming. MUTABLE: rebound per instance. */
    private volatile Instance geometryInstance;

    /** The near plane the geometry pass clips against. MUTABLE: rebound per frame. */
    private volatile float geometryNear;

    /** Sized for the largest instance. MUTABLE: rebuilt by {@link #setScene}. */
    private volatile Rasterizer rasterizer;

    /** One clipper per chunk — its scratch polygon is instance state. MUTABLE. */
    private volatile TriangleClipper[] clippers;

    /** One input-triangle scratch per chunk. MUTABLE: rebuilt by {@link #setScene}. */
    private volatile float[][] chunkScratch;

    /** Clip-space output stream, worst case {@link #CLIP_EXPANSION} per input triangle. MUTABLE. */
    private volatile float[] clipVertices;

    /** Per-output-triangle material index. MUTABLE. */
    private volatile int[] clipMaterials;

    /** Per-output-triangle flat colour. MUTABLE. */
    private volatile int[] clipColors;

    /** Output triangles each chunk produced this frame. MUTABLE: written per frame. */
    private volatile int[] chunkProduced;

    /** The camera in force, or null to use the default orbit. MUTABLE. */
    private volatile Camera camera;

    /** The camera the last frame actually used. MUTABLE: written per frame. */
    private volatile Camera lastCamera;

    /** Wall time the last frame took, in nanoseconds. MUTABLE: written per frame. */
    private volatile long lastFrameNanos;

    /** Triangles the last frame submitted to the rasterizer. MUTABLE. */
    private volatile int lastFrameTriangles;

    /** Frames completed since construction. MUTABLE. */
    private volatile long framesRendered;

    /**
     * Creates a render port.
     *
     * @param workerPool the engine's worker pool, or null to run every pass on
     *     the calling thread — the serial reference path, used by the
     *     build-time preview tool and by tests
     * @param timePort the engine's monotonic clock; must not be null, because
     *     {@code AGENTS.md} forbids {@code System.nanoTime()} in engine code
     * @throws IllegalArgumentException if {@code timePort} is null
     */
    public SoftwareRenderPort(final I_ThreadPoolPort workerPool, final I_TimePort timePort)
    {
        this(workerPool, timePort, BACKFACE_CULL_MODE);
    }

    /**
     * Creates a render port with an explicit cull mode.
     *
     * <b>Production uses the two-argument form.</b> This one exists because the
     * evidence for {@link #BACKFACE_CULL_MODE} is a comparison against
     * {@link Rasterizer.CullMode#NONE} — a z-buffer draws a closed mesh
     * correctly with no culling at all, which makes {@code NONE} an oracle that
     * assumes nothing about winding. Rendering that oracle needs the whole
     * pipeline, so the pipeline has to accept the mode. It is also what lets
     * the build-time preview tool dump a side-by-side of all three.
     *
     * @param workerPool the engine's worker pool, or null for the serial path
     * @param timePort the engine's monotonic clock; must not be null
     * @param cull which screen-space winding to discard; must not be null
     * @throws IllegalArgumentException if the clock or the cull mode is null
     */
    public SoftwareRenderPort(final I_ThreadPoolPort workerPool, final I_TimePort timePort,
        final Rasterizer.CullMode cull)
    {
        if (timePort == null)
        {
            throw new IllegalArgumentException("timePort must not be null");
        }
        if (cull == null)
        {
            throw new IllegalArgumentException("cull must not be null");
        }
        this.pool = workerPool;
        this.time = timePort;
        this.cullMode = cull;
        this.framebuffer = new Framebuffer();
        this.spanRenderer = new SpanRenderer(SpanRenderer.ShadingMode.TEXTURED, ATTRIBUTE_COUNT);
        this.chunkCount = chunkCountFor(workerPool);
    }

    // One chunk per worker is the figure Rasterizer's Javadoc names. The
    // participating caller is one of them, so a single-worker pool still gets
    // one chunk and the serial path gets exactly one.
    private static int chunkCountFor(final I_ThreadPoolPort workerPool)
    {
        if (workerPool == null)
        {
            return 1;
        }
        return Math.max(1, workerPool.workerCount());
    }

    // ---- lifecycle ----

    @Override
    public void init()
    {
        LOG.info("Software rasterizer ready: {} chunks, cull={}, shading={}",
            chunkCount, cullMode, spanRenderer.mode());
    }

    @Override
    public void shutdown()
    {
        frameLock.lock();
        try
        {
            if (framebuffer.state() == Framebuffer.State.READY)
            {
                framebuffer.shutdown();
            }
            LOG.info("Software rasterizer shut down after {} frames", framesRendered);
        }
        finally
        {
            frameLock.unlock();
        }
    }

    /**
     * Allocates or reallocates the framebuffer at the platform surface size.
     *
     * Call from {@code I_FrameCallback.onSurfaceReady} and
     * {@code I_FrameCallback.onResize}. Until this has been called at least
     * once, {@link #renderFrame} is a no-op — a windowed run publishes
     * {@code RenderFrameEvent}s from the moment the game loop starts, which is
     * before any surface exists.
     *
     * @param newWidth surface width in pixels; must be positive
     * @param newHeight surface height in pixels; must be positive
     */
    public void resize(final int newWidth, final int newHeight)
    {
        frameLock.lock();
        try
        {
            if (framebuffer.state() == Framebuffer.State.UNINITIALIZED)
            {
                framebuffer.init(newWidth, newHeight);
                return;
            }
            if (framebuffer.state() == Framebuffer.State.READY)
            {
                framebuffer.resize(newWidth, newHeight);
            }
        }
        finally
        {
            frameLock.unlock();
        }
    }

    // ---- the scene ----

    /**
     * Loads a model from a {@link ModelFormat} file image and binds it as a
     * one-instance scene.
     *
     * @param fileImage the whole {@code .ofm} file, as the converter wrote it
     * @throws ModelFormatException if the image is not a readable model
     */
    public void loadModel(final byte[] fileImage)
    {
        loadModel(ModelFormat.read(fileImage));
    }

    /**
     * Binds an already-parsed model as a scene of one untransformed world
     * instance.
     *
     * <p>Exactly {@code setScene(Scene.of(newModel))}, and it exists because
     * one model at the origin is what the preview tool and the windowed run
     * want. The single-model behaviour is <b>not</b> a second code path
     * through the renderer — it is the general path with one instance.</p>
     *
     * @param newModel the model to draw; must not be null
     * @throws IllegalArgumentException if the model is null or has no triangles
     */
    public void loadModel(final ModelFormat newModel)
    {
        setScene(Scene.of(newModel));
    }

    /**
     * Binds a scene and sizes every geometry buffer for its largest instance.
     *
     * <p>This is the only allocation site outside {@link #resize}, and it
     * allocates nothing when the new scene's largest model is no bigger than
     * the largest already sized for. Buffers are sized for the worst case —
     * {@link #CLIP_EXPANSION} output triangles per input triangle — so the
     * per-frame path never grows anything.</p>
     *
     * <p>Serialised against a frame in flight, so a scene may be swapped from
     * any thread.</p>
     *
     * @param newScene the scene to draw; must not be null. {@link Scene#EMPTY}
     *     is legal and renders a cleared frame
     * @throws IllegalArgumentException if the scene is null
     */
    public void setScene(final Scene newScene)
    {
        if (newScene == null)
        {
            throw new IllegalArgumentException("scene must not be null");
        }
        frameLock.lock();
        try
        {
            bindScene(newScene);
        }
        finally
        {
            frameLock.unlock();
        }
        LOG.info("Scene bound: {} world instances, {} view instances, largest {} triangles",
            newScene.worldInstanceCount(), newScene.viewInstanceCount(),
            newScene.maxInstanceTriangles());
    }

    /** Returns the bound scene, or null before {@link #setScene} has been called. */
    public Scene scene()
    {
        return scene;
    }

    // Prepares each instance's derived tables and grows the geometry buffers if
    // this scene needs more than the last one did. Called under the lock.
    private void bindScene(final Scene newScene)
    {
        growBuffersFor(newScene.maxInstanceTriangles());
        this.worldInstances = prepareWorld(newScene);
        this.viewInstances = prepareView(newScene);
        this.scene = newScene;
    }

    // Sizes the clip-space stream, the per-chunk scratch and the rasterizer for
    // the largest single instance. Grow-only: a smaller scene keeps the bigger
    // buffers rather than reallocating down and back up on the next swap.
    private void growBuffersFor(final int triangles)
    {
        if (triangles <= sizedForTriangles)
        {
            return;
        }
        final int maxOutput = triangles * CLIP_EXPANSION;

        this.clipVertices = new float[maxOutput * TRIANGLE_FLOATS];
        this.clipMaterials = new int[maxOutput];
        this.clipColors = new int[maxOutput];
        this.chunkProduced = new int[chunkCount];

        final TriangleClipper[] newClippers = new TriangleClipper[chunkCount];
        final float[][] newScratch = new float[chunkCount][];
        for (int chunk = 0; chunk < chunkCount; chunk++)
        {
            newClippers[chunk] = new TriangleClipper(ATTRIBUTE_COUNT);
            newScratch[chunk] = new float[TRIANGLE_FLOATS];
        }
        this.clippers = newClippers;
        this.chunkScratch = newScratch;

        this.rasterizer = new Rasterizer(ATTRIBUTE_COUNT, maxOutput, chunkCount, cullMode);
        this.sizedForTriangles = triangles;
    }

    // One prepared entry per world instance, in submission order.
    private static Instance[] prepareWorld(final Scene source)
    {
        final Instance[] out = new Instance[source.worldInstanceCount()];
        for (int index = 0; index < out.length; index++)
        {
            out[index] = prepare(source.worldModel(index), out, index);
        }
        return out;
    }

    // One prepared entry per view instance, in submission order.
    private static Instance[] prepareView(final Scene source)
    {
        final Instance[] out = new Instance[source.viewInstanceCount()];
        for (int index = 0; index < out.length; index++)
        {
            out[index] = prepare(source.viewModel(index), out, index);
        }
        return out;
    }

    // Flattening the submesh table and building the mip chains costs one pass
    // over the model, so the same model appearing more than once in a pass
    // reuses the first entry rather than repeating it. Reference identity is
    // the right test: ModelFormat is immutable and one parsed file is one
    // object.
    private static Instance prepare(final ModelFormat source, final Instance[] done,
        final int upTo)
    {
        for (int index = 0; index < upTo; index++)
        {
            if (done[index].model == source)
            {
                return done[index];
            }
        }
        final int triangles = source.triangleCount();
        return new Instance(source, buildTriangleMaterials(source, triangles),
            buildTriangleColors(source, triangles), buildTextures(source));
    }

    // Flattens the submesh table into one material index per triangle. A
    // triangle covered by no submesh is untextured, which is what an empty
    // submesh table means.
    private static int[] buildTriangleMaterials(final ModelFormat source, final int triangles)
    {
        final int[] out = new int[triangles];
        Arrays.fill(out, Rasterizer.NO_MATERIAL);
        for (int submesh = 0; submesh < source.submeshCount(); submesh++)
        {
            final int firstTriangle =
                source.submeshFirstIndex(submesh) / ModelFormat.INDICES_PER_TRIANGLE;
            final int span =
                source.submeshIndexCount(submesh) / ModelFormat.INDICES_PER_TRIANGLE;
            final int texture = source.submeshTextureIndex(submesh);
            for (int triangle = firstTriangle; triangle < firstTriangle + span; triangle++)
            {
                out[triangle] = texture;
            }
        }
        return out;
    }

    // The flat colour a triangle falls back to when its submesh has no texture.
    private static int[] buildTriangleColors(final ModelFormat source, final int triangles)
    {
        final int[] out = new int[triangles];
        final int[] indices = source.indices();
        for (int triangle = 0; triangle < triangles; triangle++)
        {
            out[triangle] = source.colour(indices[triangle * ModelFormat.INDICES_PER_TRIANGLE]);
        }
        return out;
    }

    // The texture table the span loop indexes by material.
    private static MipChain[] buildTextures(final ModelFormat source)
    {
        final MipChain[] out = new MipChain[source.textureCount()];
        for (int texture = 0; texture < out.length; texture++)
        {
            out[texture] = source.mipChain(texture);
        }
        return out;
    }

    // ---- the frame ----

    /**
     * Renders one frame into the framebuffer.
     *
     * <p>A no-op until both a surface ({@link #resize}) and a scene
     * ({@link #setScene} or {@link #loadModel}) exist. A scene with no
     * instances in it is not the same thing as no scene: it renders, and
     * produces a cleared frame. Runs on whatever thread the event bus
     * dispatched {@code RenderFrameEvent} on, and fans out from there.</p>
     *
     * @param ticIndex the current tic, which drives the default orbit camera
     */
    @Override
    public void renderFrame(final int ticIndex)
    {
        if (scene == null || framebuffer.state() != Framebuffer.State.READY)
        {
            return;
        }
        frameLock.lock();
        try
        {
            drawFrame(scene, ticIndex);
        }
        catch (final IllegalStateException e)
        {
            rethrowUnlessShuttingDown(e, ticIndex);
        }
        finally
        {
            frameLock.unlock();
        }
    }

    // Teardown drains the bus AFTER the pool has stopped, so the last few
    // RenderFrameEvents are dispatched to a worker whose pool is already
    // SHUTDOWN and submitParallel refuses them. activePool() narrows that
    // window but cannot close it: the pool can stop between the check and the
    // submit. The frame is being rendered during shutdown and will never reach
    // a window, so abandoning it is correct — but only for that one cause.
    // Anything else is a real bug and is rethrown untouched.
    private void rethrowUnlessShuttingDown(final IllegalStateException failure,
        final int ticIndex)
    {
        if (activePool() != null)
        {
            throw failure;
        }
        LOG.debug("Frame {} abandoned: the worker pool stopped mid-frame", ticIndex);
    }

    // One frame: the world pass, the depth reset, the view pass. Called under
    // the lock, with a scene bound and the framebuffer READY.
    private void drawFrame(final Scene current, final int ticIndex)
    {
        final long started = time.nanos();
        final Camera view = cameraFor(current, ticIndex);
        this.lastCamera = view;
        this.geometryNear = view.near();
        final I_ThreadPoolPort workers = activePool();

        framebuffer.clear(DEFAULT_CLEAR_COLOR);

        // MUTABLE local — triangles the whole scene handed the rasterizer.
        int triangles = 0;
        final Instance[] world = worldInstances;
        for (int index = 0; index < world.length; index++)
        {
            view.packModelToClip(current.worldTransform(index), geometryTransform, 0);
            triangles += renderInstance(world[index], workers);
        }

        // The viewmodel is depth-tested only against itself. Colour is NOT
        // cleared: the weapon composites over the world it was just occluded
        // by. See the class Javadoc.
        final Instance[] hand = viewInstances;
        if (hand.length > 0)
        {
            framebuffer.clearDepth();
            for (int index = 0; index < hand.length; index++)
            {
                view.packViewToClip(current.viewTransform(index), geometryTransform, 0);
                triangles += renderInstance(hand[index], workers);
            }
        }

        this.lastFrameTriangles = triangles;
        this.lastFrameNanos = time.nanos() - started;
        this.framesRendered = framesRendered + 1;
    }

    // One instance through the whole pipeline, with its packed transform
    // already in geometryTransform. Returns the triangles it submitted.
    //
    // The rasterizer is reset rather than replaced: beginFrame clears the tile
    // bins and the triangle count, which is required because records and bins
    // are indexed by position within one submitted stream. It does not touch
    // the framebuffer, so the depth buffer carries across instances and
    // resolves them against one another whatever order they arrive in.
    private int renderInstance(final Instance instance, final I_ThreadPoolPort workers)
    {
        this.geometryInstance = instance;
        dispatch(geometryJob, chunkCount, workers);
        final int triangles = compactChunks(instance.model.triangleCount());

        final Rasterizer setup = rasterizer;
        setup.beginFrame(framebuffer);
        setup.setupAndBin(clipVertices, triangles, clipMaterials, clipColors, workers);
        setup.rasterize(spanRenderer, instance.textures, workers);
        return triangles;
    }

    // Transforms and clips one contiguous slice of the current instance's
    // triangles.
    //
    // Chunk c is one submitParallel index, so this body never runs concurrently
    // with itself: its clipper, its scratch and its region of the output stream
    // are all private to it without any thread identity being involved. That is
    // the same argument Rasterizer's binning makes, and it is why a per-worker
    // scheme was never needed.
    private void runGeometryChunk(final int chunk)
    {
        final Instance instance = geometryInstance;
        final ModelFormat current = instance.model;
        final float near = geometryNear;
        final float[] transform = geometryTransform;
        final int[] indices = current.indices();
        final float[] scratch = chunkScratch[chunk];
        final TriangleClipper clipper = clippers[chunk];
        final float[] out = clipVertices;
        final int[] materials = clipMaterials;
        final int[] colors = clipColors;
        final int[] sourceMaterial = instance.triangleMaterial;
        final int[] sourceColor = instance.triangleColor;

        final int from = chunkStart(chunk, current.triangleCount());
        final int to = chunkStart(chunk + 1, current.triangleCount());
        final int outBase = from * CLIP_EXPANSION;

        // MUTABLE local — output triangles this chunk has emitted so far.
        int produced = 0;
        for (int triangle = from; triangle < to; triangle++)
        {
            gatherTriangle(current, indices, triangle, transform, scratch);
            final int emitted = clipper.clipTriangle(near, scratch, 0, out,
                (outBase + produced) * TRIANGLE_FLOATS);
            for (int k = 0; k < emitted; k++)
            {
                materials[outBase + produced + k] = sourceMaterial[triangle];
                colors[outBase + produced + k] = sourceColor[triangle];
            }
            produced += emitted;
        }
        chunkProduced[chunk] = produced;
    }

    // Gathers one model triangle into the clip-space vertex layout the clipper
    // and rasterizer share: [x, y, w, u, v] per vertex. Vertices are
    // transformed per corner rather than per unique vertex; docs/ASSETS.md § 2
    // measures triangle setup at 26-64 ns against 600-900 ns of raster phase,
    // so the redundant transform is far below the noise floor and a separate
    // per-vertex pass would cost an extra parallel barrier to save it.
    //
    // The transform is the instance's packed model-to-clip, concatenated once
    // per instance: model space goes straight to clip space in one three-row
    // multiply, exactly as world space used to.
    private static void gatherTriangle(final ModelFormat source, final int[] indices,
        final int triangle, final float[] transform, final float[] scratch)
    {
        final int base = triangle * ModelFormat.INDICES_PER_TRIANGLE;
        for (int corner = 0; corner < TriangleClipper.TRIANGLE_VERTICES; corner++)
        {
            final int vertex = indices[base + corner];
            final int at = corner * VERTEX_STRIDE;
            Camera.transformToClip(transform, 0, source.positionX(vertex),
                source.positionY(vertex), source.positionZ(vertex), scratch, at);
            scratch[at + TriangleClipper.POSITION_FLOATS] = source.texCoordU(vertex);
            scratch[at + TriangleClipper.POSITION_FLOATS + 1] = source.texCoordV(vertex);
        }
    }

    // Closes the gaps the per-chunk output regions leave, so the rasterizer
    // sees one contiguous stream in which triangle i lives at i * stride.
    //
    // The destination cursor never overtakes the source: a chunk's region
    // starts at CLIP_EXPANSION times its first triangle, and every earlier
    // chunk emitted at most CLIP_EXPANSION per triangle. System.arraycopy is
    // memmove, so the overlapping case is defined regardless.
    private int compactChunks(final int triangles)
    {
        final float[] out = clipVertices;
        final int[] materials = clipMaterials;
        final int[] colors = clipColors;

        // MUTABLE local — the compacted output cursor, in triangles.
        int cursor = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++)
        {
            final int source = chunkStart(chunk, triangles) * CLIP_EXPANSION;
            final int emitted = chunkProduced[chunk];
            if (emitted > 0 && cursor != source)
            {
                System.arraycopy(out, source * TRIANGLE_FLOATS, out, cursor * TRIANGLE_FLOATS,
                    emitted * TRIANGLE_FLOATS);
                System.arraycopy(materials, source, materials, cursor, emitted);
                System.arraycopy(colors, source, colors, cursor, emitted);
            }
            cursor += emitted;
        }
        return cursor;
    }

    // Chunk boundaries as a long product, matching Rasterizer's own.
    private int chunkStart(final int chunk, final int triangles)
    {
        return (int) ((long) triangles * chunk / chunkCount);
    }

    // Runs one indexed pass, serially when there is no pool.
    private static void dispatch(final I_ParallelJob job, final int jobCount,
        final I_ThreadPoolPort workers)
    {
        if (workers == null)
        {
            for (int index = 0; index < jobCount; index++)
            {
                job.runJob(index);
            }
            return;
        }
        workers.submitParallel(job, jobCount);
    }

    // The pool, but only while it can actually take work.
    //
    // Shutdown drains the bus, and a RenderFrameEvent already queued is
    // dispatched to a worker AFTER pool.shutdown() has run — at which point
    // submitParallel throws "called from state SHUTDOWN". That is not an error
    // to propagate: the frame is still perfectly renderable, just serially, and
    // throwing turns an ordinary teardown into two logged stack traces per run.
    // Returning null here routes the last frames down the serial path instead.
    private I_ThreadPoolPort activePool()
    {
        final I_ThreadPoolPort workers = pool;
        if (workers == null || workers.state() != I_ThreadPoolPort.State.RUNNING)
        {
            return null;
        }
        return workers;
    }

    // ---- camera ----

    /**
     * Fixes the camera for every subsequent frame.
     *
     * @param newCamera the camera to render from, or null to return to the
     *     default orbit
     */
    public void setCamera(final Camera newCamera)
    {
        this.camera = newCamera;
    }

    /** Returns the camera the last frame rendered from, or null before the first frame. */
    public Camera lastCamera()
    {
        return lastCamera;
    }

    // An explicit camera wins; otherwise frame the first world instance's model
    // and orbit it, so that a windowed run shows the model is genuinely 3D
    // rather than a flat silhouette.
    //
    // The orbit reads the model's own bounding box and ignores its transform,
    // which is exact for the one-instance scene loadModel builds and is only a
    // default for anything richer. A real scene sets a camera.
    private Camera cameraFor(final Scene current, final int ticIndex)
    {
        final Camera fixed = camera;
        if (fixed != null)
        {
            return fixed;
        }
        if (current.worldInstanceCount() == 0)
        {
            // Nothing to frame — a view-only scene needs a frustum, not a
            // placement, because view instances never use the view matrix.
            return Camera.create(ORIGIN, VIEW_FORWARD, WORLD_UP, DEFAULT_FOV_Y, aspect(),
                DEFAULT_NEAR);
        }
        return orbitCamera(current.worldModel(0), aspect(),
            ticIndex * ORBIT_RADIANS_PER_TIC);
    }

    private float aspect()
    {
        return (float) framebuffer.width() / (float) framebuffer.height();
    }

    /**
     * Builds a camera that frames a model's bounding box from a given angle.
     *
     * Exposed because the build-time preview tool and the tests want the same
     * framing the windowed run uses, and a second copy of it would drift.
     *
     * @param source the model to frame
     * @param viewAspect viewport aspect ratio, width / height
     * @param angleRadians orbit angle about the model's up axis
     * @return a camera looking at the model's centre from outside its bounds
     */
    public static Camera orbitCamera(final ModelFormat source, final float viewAspect,
        final float angleRadians)
    {
        final float centreX = (source.minX() + source.maxX()) * 0.5f;
        final float centreY = (source.minY() + source.maxY()) * 0.5f;
        final float centreZ = (source.minZ() + source.maxZ()) * 0.5f;

        final float spanX = source.maxX() - source.minX();
        final float spanY = source.maxY() - source.minY();
        final float spanZ = source.maxZ() - source.minZ();
        // MUTABLE local — the half-diagonal of the bounding box, floored so a
        // degenerate (flat or empty) model still gets a usable distance.
        float radius = 0.5f * (float) Math.sqrt(spanX * spanX + spanY * spanY + spanZ * spanZ);
        if (!(radius > 0.0f))
        {
            radius = 1.0f;
        }

        final float distance = radius * ORBIT_DISTANCE_FACTOR;
        final Vec3 eye = new Vec3(
            centreX + distance * (float) Math.sin(angleRadians),
            centreY + radius * ORBIT_HEIGHT_FACTOR,
            centreZ + distance * (float) Math.cos(angleRadians));
        final Vec3 target = new Vec3(centreX, centreY, centreZ);
        return Camera.lookingAt(eye, target, WORLD_UP, DEFAULT_FOV_Y, viewAspect, DEFAULT_NEAR);
    }

    // ---- presentation handoff ----

    /**
     * Copies the finished frame into a contiguous, unpadded destination.
     *
     * <p>This is the {@code render/README.md} § 12 handoff and the whole of
     * R_'s side of it: the platform adapter uploads what this writes, and R_
     * never learns that a window exists. The destination is
     * {@code width * height} — <b>not</b> the raw colour buffer, whose stride is
     * padded past the width (§ 7) and which would present sheared.</p>
     *
     * <p>Blocks until any frame in flight has finished, so the copy is never a
     * mixture of two frames.</p>
     *
     * @param destination array of at least {@code surfaceWidth() *
     *     surfaceHeight()} elements
     * @return true if a frame was copied, false if there is no surface yet
     */
    public boolean copyColorInto(final int[] destination)
    {
        frameLock.lock();
        try
        {
            if (framebuffer.state() != Framebuffer.State.READY)
            {
                return false;
            }
            framebuffer.copyColorTo(destination);
            return true;
        }
        finally
        {
            frameLock.unlock();
        }
    }

    /**
     * Returns the framebuffer itself, by reference.
     *
     * <p>Not synchronised against {@link #renderFrame} — this exists for
     * single-threaded tools and tests that want to inspect pixels directly.
     * The platform presentation path uses {@link #copyColorInto} instead.</p>
     *
     * @return the live framebuffer
     */
    public Framebuffer framebuffer()
    {
        return framebuffer;
    }

    /** Returns the surface width in pixels, or zero before the first {@link #resize}. */
    public int surfaceWidth()
    {
        return framebuffer.width();
    }

    /** Returns the surface height in pixels, or zero before the first {@link #resize}. */
    public int surfaceHeight()
    {
        return framebuffer.height();
    }

    /** Returns how long the last frame took, in nanoseconds. */
    public long lastFrameNanos()
    {
        return lastFrameNanos;
    }

    /**
     * Returns how many triangles the last frame handed the rasterizer, after
     * clipping, summed over every instance in both passes.
     *
     * @return the scene total for the last frame
     */
    public int lastFrameTriangles()
    {
        return lastFrameTriangles;
    }

    /** Returns how many frames have completed since construction. */
    public long framesRendered()
    {
        return framesRendered;
    }

    /** Returns how many chunks the geometry and binning passes split the stream into. */
    public int chunkCount()
    {
        return chunkCount;
    }

    /** Returns the screen-space winding this port discards. */
    public Rasterizer.CullMode cullMode()
    {
        return cullMode;
    }

    /** Returns a debug rendering of the port's configuration and last frame. */
    @Override
    public String toString()
    {
        return "SoftwareRenderPort{" + framebuffer.width() + "x" + framebuffer.height()
            + ", chunks=" + chunkCount + ", cull=" + cullMode
            + ", scene=" + scene
            + ", lastFrame=" + (lastFrameNanos / NANOS_PER_MILLI) + " ms"
            + ", triangles=" + lastFrameTriangles + "}";
    }

    // Everything the pipeline needs for one model, derived once when a scene is
    // bound rather than per frame: the submesh table flattened to one material
    // per triangle, the fallback flat colours, and the mip chains the span loop
    // indexes by material. Two instances of the same model share one of these,
    // and the transform is deliberately NOT in here — it belongs to the Scene,
    // and folding it in per frame is a 12-float write, not a rebuild.
    private static final class Instance
    {
        private final ModelFormat model;
        private final int[] triangleMaterial;
        private final int[] triangleColor;
        private final MipChain[] textures;

        Instance(final ModelFormat instanceModel, final int[] materials, final int[] colors,
            final MipChain[] instanceTextures)
        {
            this.model = instanceModel;
            this.triangleMaterial = materials;
            this.triangleColor = colors;
            this.textures = instanceTextures;
        }
    }
}
