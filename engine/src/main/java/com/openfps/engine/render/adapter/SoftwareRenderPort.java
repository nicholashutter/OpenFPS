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
 * <p>Four {@code submitParallel} passes make up a <b>pass</b>: geometry
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
 *   <li><b>{@link OutlinePass}</b>, if and only if the scene has tagged
 *       entities. It reads the finished world-pass id buffer and paints
 *       silhouettes into colour.</li>
 *   <li><b>Translucent world instances</b>, if the scene has any, sorted
 *       back-to-front by view depth and composited over the opaque frame. They
 *       test the world's depth and write none of their own, which is why they
 *       have to be drawn after it and why their order has to be decided here
 *       rather than by the depth buffer — {@link #drawTranslucent}.</li>
 *   <li><b>Clear depth, not colour.</b></li>
 *   <li><b>View instances</b> — the first-person viewmodel — which are already
 *       in view space and take the projection alone
 *       ({@link Camera#packViewToClip}).</li>
 * </ol>
 *
 * <p>The outline sits between the two passes deliberately: the id buffer is
 * only complete once the world pass has joined, and the held weapon must draw
 * over outlines rather than under them.</p>
 *
 * <h2>Entity ids cost an untagged scene nothing</h2>
 *
 * <p>{@link Scene#hasTaggedEntities()} is decided when the scene is built, and
 * this class turns it into a single null-or-not array reference,
 * {@code worldEntityIds}. When it is null — every scene with no players in it,
 * which includes the whole demo room — the frame skips
 * {@link Framebuffer#clearEntityIds()}, hands {@link Rasterizer} no id table so
 * the span loop never stores one, does not compact the id stream, and does not
 * dispatch {@link OutlinePass}. What remains is one reference compare per
 * frame and one per tile. That is why the feature does not appear in the
 * measured frame time of the demo scene.</p>
 *
 * <p>The depth reset in step 3 is the classic FPS solution to the weapon
 * clipping through a wall the player is standing against: the viewmodel is
 * depth-tested only against itself, so nothing in the world can occlude it,
 * and it still composites over the world because colour is left alone. The
 * alternative — squeezing the world into part of the depth range and the
 * viewmodel into the rest — buys nothing here and costs precision everywhere.</p>
 *
 * <h2>A pass is ONE batch, not one batch per instance</h2>
 *
 * <p><b>Every instance in a pass is transformed and clipped into a single
 * shared geometry stream, and that whole stream takes one setup, one bin and
 * one tile raster.</b> A frame therefore costs <b>eight</b> parallel passes —
 * four for the world, four for the viewmodel — whatever the instance count.
 * The alternative, running the four-stage pipeline once per instance, is what
 * this class used to do, and it is why adding workers used to make the demo
 * room slower: at 295 instances it paid about 1,180 publish/join boundaries per
 * frame to distribute a few dozen triangles each, and barrier cost swamped the
 * work. Measured at 1280x720, that scene went from 20 ms serial and 217 ms on
 * eight workers to 20 ms serial and 5 ms on eight workers when the pass was
 * batched. The parallel machinery was never the problem; the granularity
 * was.</p>
 *
 * <h2>An instance the camera cannot see is not transformed at all</h2>
 *
 * <p>Batching fixed the barrier count but left something else untouched:
 * <b>every instance in the scene was transformed and clipped every frame
 * regardless of where the camera looked</b>. A room is a box the player stands
 * inside, so much of it is behind the eye at any moment, and the pipeline
 * discovered that only after transforming every vertex of it — the near clip
 * emitted nothing, or the rasterizer's screen bounding box missed the
 * viewport.</p>
 *
 * <p>{@link #packVisibleWorld} now asks {@link InstanceCull} the same question
 * one instance earlier, from the packed transform it has just built and the
 * model's own bounding box, and appends only the survivors to the stream.
 * {@link #sortBackToFront} does the same for the translucent phase, where it
 * additionally collapses the run count — see that method.</p>
 *
 * <p><b>The measured effect, and it is smaller than it first looked.</b> The
 * demo room, 340 world instances, all four {@code :tools:demoPreview} poses at
 * 640x360 on eight workers, with the culling and non-culling builds compiled and
 * run alternately in one session:</p>
 *
 * <pre>
 *   pose                triangles   dispatches   best     p50
 *   01 down the room  7400 -&gt; 6556   57 -&gt; 57    -4.0%   -1.4%
 *   02 corner         1425 -&gt;  617   20 -&gt;  8    -9.5%  -13.7%
 *   03 weapon at wall  963 -&gt;  448   20 -&gt;  8    -0.3%   -6.3%
 *   04 after movement  788 -&gt;  416   20 -&gt;  8    -7.0%   -5.4%
 * </pre>
 *
 * <p>The triangle and dispatch counts are exact. <b>The frame-time gain is real
 * but modest — about 5% averaged over the poses, consistently signed but only a
 * small multiple of the run-to-run noise.</b> Do not quote it as more than that,
 * and do not quote pose 02 or 04 alone: the first table this Javadoc carried did
 * exactly that and overstated the change threefold.</p>
 *
 * <p><b>Pose 01 is the honest case and it is the least impressive.</b> Looking
 * down the length of an open room is a normal thing to do, and there the cull
 * removes 11% of the triangles and not one dispatch. A single open room is close
 * to the worst case for frustum culling; the gain grows with occlusion and with
 * scenes larger than one room, and neither exists yet.</p>
 *
 * <p>Why the gain is small, which is more useful than the number: the geometry
 * removed was <b>already spread over eight workers</b>, so several thousand
 * triangles of transform-and-clip is a fraction of a millisecond of wall time.
 * Whatever dominates the resolution-independent cost, it is mostly not this.</p>
 *
 * <p>Pose 01's 57 dispatches are largely an artefact of the preview tool, which
 * never publishes {@code DemoEffects}: all 36 smoke lobes therefore sit at the
 * origin at unit scale and are genuinely visible. A running game has one puff
 * stage live at a time, whose three lobes share a coverage and so form one
 * run.</p>
 *
 * <p><b>The image does not change, and that is checked rather than argued.</b>
 * {@link InstanceCull}'s Javadoc carries the proof that a culled instance could
 * not have produced a pixel; the four {@code :tools:demoPreview} reference
 * frames are byte-identical across the change; and the pooled-equals-serial
 * bit-identity tests are unaffected because culling happens once, on the frame
 * thread, before any worker starts.</p>
 *
 * <p><b>Nothing the simulation can see depends on this.</b> Hitscan is resolved
 * from geometry rather than from the id buffer
 * ({@code DemoGameplayPort.fireIfRequested}), precisely so that hit detection
 * cannot depend on resolution or worker count. Culling is one more thing it must
 * not depend on, and it does not: the cull is read by the geometry pass and by
 * nothing else.</p>
 *
 * <p><b>The result is bit-for-bit what rendering instances one at a time
 * produced</b>, and that is a property of the ordering rather than a hope.
 * Instances are laid into the stream in submission order, {@link Rasterizer}
 * bins a tile's triangles in ascending stream index, and the depth test rejects
 * ties — so each pixel sees exactly the sequence of triangles it saw before,
 * in exactly the same order. It is asserted against a stored image in
 * {@code SoftwareRenderPortTest} rather than argued.</p>
 *
 * <p>Two things had to become scene-wide for this to work, and both are built
 * once by {@link #setScene} rather than per frame:</p>
 *
 * <ul>
 *   <li><b>The texture table.</b> One raster pass takes one table, so a
 *       triangle's material index has to mean something without knowing which
 *       instance it came from. Every distinct instance's textures are
 *       concatenated into one scene-wide table and its per-triangle material
 *       indices are rebased into it. {@link Rasterizer#NO_MATERIAL} stays
 *       {@link Rasterizer#NO_MATERIAL}.</li>
 *   <li><b>The packed transforms.</b> The geometry pass now spans instances, so
 *       it cannot read a single shared scratch transform. Each instance gets
 *       its own {@link Camera#WORLD_TO_CLIP_FLOATS}-float slice of one array,
 *       written once per frame — the same once-per-instance concatenation as
 *       before, into a slot rather than over the top of the last one.</li>
 * </ul>
 *
 * <p>{@link Rasterizer} is <b>reused</b> across passes — one instance for the
 * whole port — but must be <b>reset per pass</b> with
 * {@link Rasterizer#beginFrame}, because its per-triangle records and tile bins
 * are indexed by position within a single submitted stream. It does not clear
 * the framebuffer, so resetting it between the two passes composites rather
 * than overwrites.</p>
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
 * <p><b>The presentation handoff is double-buffered, not locked.</b> The
 * renderer finishes a frame, de-pads it into a back buffer it owns outright,
 * and then takes {@code presentLock} for exactly long enough to swap two
 * references. {@link #copyColorInto} takes the same lock and copies the front
 * buffer out. Neither side ever waits on the other's work: the lock is held for
 * a pointer swap on one side and one {@code arraycopy} on the other, and never
 * for the 5-20 ms a frame takes.</p>
 *
 * <p>That replaces an earlier design in which the presenter and
 * {@link #renderFrame} shared one non-fair lock. It was correct and it was
 * unusable: the render workers reacquired that lock every frame and the
 * presenting thread — a different thread, running the window — mostly lost the
 * race, so finished frames were rendered and never shown. Arbitrating the lock
 * more fairly would have made the presenter wait a whole frame instead of
 * starving; removing the contention makes it wait for a swap. The cost is one
 * extra full-frame copy, about 0.2 ms at 720p, which is the right trade at any
 * frame rate worth having.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>Nothing is allocated per frame except the {@link Camera}, which
 * {@code render/README.md} § 4 explicitly sanctions ("Build one per frame").
 * Every geometry buffer is sized by {@link #setScene}, and the framebuffer and
 * the two present buffers only by {@link #resize}.</p>
 *
 * <p><b>Sized for the larger whole pass, not for the largest instance.</b> A
 * pass is transformed and clipped as one batch, so the clip-space stream holds
 * every triangle in it at once — {@link Scene#maxPassTriangles}, times
 * {@link #CLIP_EXPANSION} for the worst-case clip. The buffers therefore grow
 * when a scene arrives whose larger pass is bigger than any seen so far, and
 * never shrink; a scene swap that stays within the high-water mark allocates
 * nothing at all.</p>
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

    /**
     * Draws entity silhouettes between the two passes. Constructed once and
     * only ever dispatched for a scene that has tagged entities.
     */
    private final OutlinePass outlinePass = new OutlinePass();

    /**
     * Whether to draw the aiming reticle over each finished frame.
     *
     * <p><b>Off by default, and that default is the important part.</b> This
     * class is a general render port, not a game: {@code :tools:renderPreview}
     * uses it to inspect one model, {@code :tools:demoPreview} to produce
     * reference frames, and the render tests to assert exact pixel content. A
     * reticle stamped unconditionally through the middle of every frame is
     * wrong for all three — it is furniture belonging to a first-person game,
     * so the first-person game asks for it.</p>
     *
     * <p>MUTABLE: set once at wiring time before the first frame, read on the
     * render thread. Volatile because those are different threads.</p>
     */
    private volatile boolean crosshairEnabled;

    /**
     * Whether tagged entities get a wireframe edge highlight drawn over them.
     *
     * <p><b>On by default, and the default has been both ways round.</b> It
     * shipped on, was reported as making every opponent look permanently
     * damaged, was turned off, and was then reported missing — because the
     * highlight is genuinely wanted: it is what tells a player where the
     * simulation believes each body is, in a demo whose bodies are flat-shaded
     * boxes against a flat-shaded room.</p>
     *
     * <p>Both reports were right, and what was wrong was the mark rather than
     * the switch. {@link OutlinePass} now draws a one-pixel wireframe over the
     * silhouette and the interior creases instead of a three-pixel filled band
     * round the outside, which reads as geometry rather than as a status
     * effect. That is the change that let this go back to true.</p>
     *
     * <p><b>The platform side of it is an accessibility option and not a
     * diagnostic</b> — {@code com.openfps.gdx.AccessibilitySettings}, presented
     * under its own heading on the settings screen and defaulted on. It used to
     * hang off the debug switch beside the frame counter, which meant this
     * default and that switch's default disagreed and the toggle's label was
     * wrong from the first frame. This class was never party to that: it takes a
     * boolean from whoever composed it, and the fix was for the composition root
     * to push its initial value in rather than assume the two constants
     * matched.</p>
     *
     * <p><b>It is a master switch and not a scope.</b> What it turns on is a
     * wireframe around the <i>one</i> entity under the point of aim
     * ({@link #aimedEntityId}), because a mark on all seven opponents at once
     * still says something about the opponents rather than about the aim — see
     * {@link OutlinePass}. Turning this off removes the mark entirely, which is
     * what {@code :tools} and the pixel-exact render tests want.</p>
     *
     * <p>Tagging and outlining stay independent for a reason. Hitscan is
     * resolved from geometry and never reads the id buffer (see
     * {@code DemoGameplayPort.fireIfRequested}), so turning the highlight off
     * costs no hit detection at all — which is what keeps
     * {@link #setOutlineEnabled} usable by {@code :tools} and by the render
     * tests that assert exact pixel content.</p>
     *
     * <p>MUTABLE: toggled at runtime by the switch, read on the render thread.
     * Volatile because those are different threads.</p>
     */
    private volatile boolean outlineEnabled = true;

    private final int chunkCount;
    private final Rasterizer.CullMode cullMode;

    /** Serialises {@link #renderFrame}, {@link #setScene} and {@link #resize}. */
    private final ReentrantLock frameLock = new ReentrantLock();

    /**
     * Guards the front/back present-buffer swap and nothing else.
     *
     * <p>Held for a two-reference swap by the renderer and for one
     * {@code arraycopy} by the presenter. Deliberately <b>not</b> the lock a
     * frame is rendered under — see the class Javadoc.</p>
     */
    private final ReentrantLock presentLock = new ReentrantLock();

    /** The transform-and-clip pass, one index per chunk. Held once; never allocated per frame. */
    private final I_ParallelJob geometryJob = this::runGeometryChunk;

    /** The bound scene, or null before one is set. MUTABLE: rebound by {@link #setScene}. */
    private volatile Scene scene;

    /**
     * Derived tables for the scene's <b>opaque</b> world instances, in scene
     * order. MUTABLE: rebuilt by {@link #setScene}.
     *
     * <p>Translucent instances are absent from this list and drawn afterwards
     * by {@link #drawTranslucent}. For a scene with none — every scene that
     * existed before translucency did — this is the whole world list and the
     * pass is unchanged.</p>
     */
    private volatile Instance[] worldInstances;

    /**
     * The {@link Scene} index of each entry in {@link #worldInstances}.
     * MUTABLE: rebuilt by {@link #setScene}.
     *
     * <p>Needed because {@link #worldOverrides} is addressed by <i>scene</i>
     * index — that is the handle {@link #setWorldTransform} was given and
     * callers recorded — while the opaque pass is addressed by position within
     * itself. The two stopped being the same number when the translucent
     * instances were lifted out. Identity for an entirely opaque scene.</p>
     */
    private volatile int[] worldSceneIndex;

    /** Derived tables for the scene's view instances. MUTABLE: rebuilt by {@link #setScene}. */
    private volatile Instance[] viewInstances;

    /**
     * Every texture in the scene, in one table the whole frame indexes by
     * material. MUTABLE: rebuilt by {@link #setScene}.
     */
    private volatile MipChain[] sceneTextures;

    /** The view pass's stream offsets. MUTABLE: rebuilt by {@link #setScene}. */
    private volatile int[] viewStarts;

    /**
     * One entity id per world instance, or <b>null when the scene has none</b>.
     * MUTABLE: rebuilt by {@link #setScene}.
     *
     * <p>Null is the gate for the entire outline feature, not merely a missing
     * table — see the class Javadoc. There is no view-pass counterpart because
     * {@link Scene} refuses to tag a view instance.</p>
     */
    private volatile int[] worldEntityIds;

    /**
     * Per-instance placement overrides, or null when the scene has none.
     *
     * <p>MUTABLE: the array is allocated by {@link #setScene}, and individual
     * slots are replaced by {@link #setWorldTransform} from the game loop
     * thread while the render workers read them. A slot holds null until
     * something moves that instance, and {@link #packVisibleWorld} falls back to the
     * {@link Scene}'s own transform for every null.</p>
     *
     * <p><b>This is how anything moves.</b> {@link Scene} is immutable and that
     * is load-bearing — it is what makes rendering one allocate nothing and what
     * lets it be shared across workers without a lock. But a bot walks a patrol,
     * so <i>something</i> has to change per tic, and the two honest options were
     * to rebuild the whole scene sixty times a second or to let a caller replace
     * one instance's placement. Rebuilding costs a full {@code bindScene}:
     * texture table, stream offsets, entity ids, buffer sizing, all of it, for a
     * 295-instance room in which four bodies moved.</p>
     *
     * <p><b>The race is real and is deliberately tolerated.</b> A reference store
     * into an array slot cannot tear, so a worker reads either the previous
     * placement or the new one, never a half-written matrix. Which of the two it
     * gets is a one-frame difference in where a bot is drawn — the same
     * granularity the camera already has, since {@code setCamera} publishes a
     * new immutable {@code Camera} on exactly the same terms. Taking
     * {@code frameLock} to close it would serialise the game loop against
     * rendering, which is a far worse trade for a frame of latency on a
     * patrolling body.</p>
     */
    private volatile Mat4[] worldOverrides;

    /**
     * The opaque world instances the frustum cull kept this frame, in scene
     * order, filling the leading {@code visibleWorldCount} slots. MUTABLE:
     * sized by {@link #setScene}, refilled every frame.
     *
     * <p>Scene order is preserved rather than merely "some order", and that is
     * the whole of the bit-identity argument for culling: removing entries from
     * a sequence does not reorder the ones that remain, so every surviving
     * triangle still reaches the rasterizer in the position it always did and
     * every pixel still sees the same triangles in the same sequence.</p>
     */
    private volatile Instance[] visibleWorld;

    /**
     * Where each kept instance's triangles begin in the batched stream, with a
     * terminator holding the pass total.
     *
     * <p>Per <b>frame</b> rather than per scene, which is the one structural
     * consequence of culling: the stream is now the visible instances rather
     * than all of them, so the offsets into it change as the camera turns.
     * MUTABLE: sized by {@link #setScene}, refilled every frame.</p>
     */
    private volatile int[] visibleWorldStarts;

    /**
     * One packed model-to-clip transform per kept instance,
     * {@link Camera#WORLD_TO_CLIP_FLOATS} floats each.
     *
     * <p>MUTABLE: refilled every frame, before the geometry pass reads any of
     * it. An instance is packed into the slot it <i>would</i> occupy and then
     * tested; a cull simply leaves the slot to be overwritten by the next
     * survivor, so the cull needs no scratch buffer of its own.</p>
     */
    private volatile float[] visibleWorldTransforms;

    /**
     * Entity ids for {@link #visibleWorld}, or null when the opaque partition
     * tags nothing. MUTABLE: refilled per frame.
     */
    private volatile int[] visibleWorldIds;

    /**
     * One packed transform, reused by the translucent cull.
     *
     * <p>The opaque pass packs straight into the slot the instance will occupy
     * if it survives, so it needs no scratch. The translucent phase cannot: it
     * decides visibility before it knows the sorted order, and therefore before
     * it knows which slot anything lands in. MUTABLE: overwritten per instance,
     * on the frame thread only.</p>
     */
    private final float[] cullTransform = new float[Camera.WORLD_TO_CLIP_FLOATS];

    /** The same for the view pass, packed view-to-clip. MUTABLE: rewritten per frame. */
    private volatile float[] viewTransforms;

    /**
     * Prepared tables for the scene's translucent world instances, in scene
     * order, or <b>null when the scene has none</b>. MUTABLE: rebuilt by
     * {@link #setScene}.
     *
     * <p>Null is the gate for the entire translucent feature, exactly as
     * {@link #worldEntityIds} is for the outline: a scene with nothing
     * translucent pays no sort, no second set of packed transforms, no blended
     * renderer and no extra parallel pass.</p>
     */
    private volatile Instance[] translucentInstances;

    /** The {@link Scene} index of each translucent instance. MUTABLE: rebuilt by setScene. */
    private volatile int[] translucentScene;

    /** The coverage each translucent instance composites at. MUTABLE: rebuilt by setScene. */
    private volatile int[] translucentCoverage;

    /**
     * Translucent instance slots in back-to-front order, rewritten every frame.
     * MUTABLE, and pre-allocated so the per-frame sort allocates nothing.
     */
    private volatile int[] translucentOrder;

    /** View depth of each translucent instance, the sort key. MUTABLE: per frame. */
    private volatile float[] translucentDepth;

    /** One sorted run's instances, handed to {@link #renderPass}. MUTABLE: per run. */
    private volatile Instance[] translucentPassInstances;

    /** That run's stream offsets. MUTABLE: per run. */
    private volatile int[] translucentPassStarts;

    /** That run's packed model-to-clip transforms. MUTABLE: per run. */
    private volatile float[] translucentPassTransforms;

    /**
     * A blended {@link SpanRenderer} per coverage the scene actually uses,
     * indexed by coverage. MUTABLE: rebuilt by {@link #setScene}.
     *
     * <p>Indexed rather than searched because the lookup is per run per frame
     * and the table is 256 references. Only the coverages present are populated;
     * every other slot stays null and is never read.</p>
     */
    private volatile SpanRenderer[] blendedRenderers;

    /** Largest pass the geometry buffers are sized for. MUTABLE: grows, never shrinks. */
    private volatile int sizedForTriangles;

    /** The instances the geometry pass is transforming. MUTABLE: rebound per pass. */
    private volatile Instance[] geometryInstances;

    /**
     * How many leading entries of {@link #geometryInstances} the pass covers.
     * MUTABLE: rebound per pass.
     *
     * <p>Not simply the array's length, because a translucent run reuses one
     * scratch array sized for every translucent instance and fills only the
     * leading part of it. Passing the count keeps that array off the
     * per-frame allocation path.</p>
     */
    private volatile int geometryInstanceCount;

    /** The stream offsets of those instances. MUTABLE: rebound per pass. */
    private volatile int[] geometryStarts;

    /** Their packed transforms. MUTABLE: rebound per pass. */
    private volatile float[] geometryTransforms;

    /**
     * One entity id per instance in the pass being transformed, or null when
     * the pass carries none — which the view pass always does. MUTABLE:
     * rebound per pass.
     */
    private volatile int[] geometryEntityIds;

    /** Triangles in the pass being transformed. MUTABLE: rebound per pass. */
    private volatile int geometryTriangles;

    /** The near plane the geometry pass clips against. MUTABLE: rebound per frame. */
    private volatile float geometryNear;

    /** Sized for the larger pass. MUTABLE: rebuilt by {@link #setScene}. */
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

    /**
     * Per-output-triangle entity id. MUTABLE. Written and compacted only while
     * a tagged scene is bound; otherwise it is never read.
     */
    private volatile int[] clipEntityIds;

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

    /** Indexed passes the last frame dispatched. MUTABLE: written per frame. */
    private volatile int lastFrameParallelPasses;

    /** Indexed passes this class has dispatched. MUTABLE: bumped per dispatch. */
    private volatile long parallelPasses;

    /**
     * The tagged entity under the point of aim on the last frame, or
     * {@link Scene#UNTAGGED}.
     *
     * <p>MUTABLE: written once per frame under {@link #frameLock}, read on
     * whatever thread asks. Volatile because those differ.</p>
     *
     * <p><b>Cosmetic, and that word is doing work.</b> This is a screen-space
     * read: it depends on the resolution, on where the frame happened to land
     * relative to a body's edge, and on nothing the simulation knows about. It
     * drives the reticle's colour and which body gets a wireframe, and it must
     * never drive anything a peer could disagree about — hitscan is resolved
     * from geometry for exactly that reason ({@code DemoGameplayPort
     * .fireIfRequested}). Reading it and shooting at it are different
     * questions, and this answers only the first.</p>
     */
    private volatile int aimedEntityId = Scene.UNTAGGED;

    /**
     * The finished frame the presenter reads, de-padded to
     * {@code width * height}. MUTABLE: swapped under {@link #presentLock}.
     */
    private int[] frontColor;

    /**
     * The frame being written, de-padded. MUTABLE: owned outright by the
     * renderer between swaps, which is what lets the de-padding copy happen
     * outside {@link #presentLock}.
     */
    private int[] backColor;

    /** Pixels in each present buffer. MUTABLE: set by {@link #resize}. */
    private int presentPixels;

    /** Whether a finished frame has been published. MUTABLE: cleared by {@link #resize}. */
    private boolean framePublished;

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
                resizePresentBuffers();
                return;
            }
            if (framebuffer.state() == Framebuffer.State.READY)
            {
                framebuffer.resize(newWidth, newHeight);
                resizePresentBuffers();
            }
        }
        finally
        {
            frameLock.unlock();
        }
    }

    // Sizes the two present buffers to the framebuffer's visible rectangle and
    // drops whatever was in them.
    //
    // Dropping is deliberate: a frame captured at the old size is not a frame
    // at the new one, and handing it to a presenter that has already resized
    // its texture would shear it. One platform frame falls back to the menu
    // instead, which is what happens before the first frame anyway.
    private void resizePresentBuffers()
    {
        final int pixels = framebuffer.width() * framebuffer.height();
        presentLock.lock();
        try
        {
            if (frontColor == null || frontColor.length != pixels)
            {
                this.frontColor = new int[pixels];
                this.backColor = new int[pixels];
            }
            this.presentPixels = pixels;
            this.framePublished = false;
        }
        finally
        {
            presentLock.unlock();
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
     * Binds a scene and sizes every geometry buffer for its larger pass.
     *
     * <p>This is the only allocation site outside {@link #resize}. The
     * clip-space stream and the rasterizer are grow-only and cost nothing when
     * the new scene's larger pass fits inside the high-water mark; the
     * per-instance tables — stream offsets, packed transforms and the
     * scene-wide texture table — are rebuilt every time, because they are
     * per-scene by definition. Buffers are sized for the worst case,
     * {@link #CLIP_EXPANSION} output triangles per input triangle, so the
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
        LOG.info("Scene bound: {} world instances ({} triangles, {} translucent),"
            + " {} view instances ({}), {} textures, buffers sized for {}",
            newScene.worldInstanceCount(), newScene.worldTriangleCount(),
            newScene.translucentInstanceCount(), newScene.viewInstanceCount(),
            newScene.viewTriangleCount(), sceneTextures.length, sizedForTriangles);
    }

    /** Returns the bound scene, or null before {@link #setScene} has been called. */
    public Scene scene()
    {
        return scene;
    }

    /**
     * Moves one world instance, without rebuilding the scene.
     *
     * <p>The seam that lets a body walk. {@link Scene} is immutable —
     * deliberately, because that is what makes rendering one allocate nothing
     * and share safely across workers — so a moving entity has to express itself
     * somewhere else. This is that somewhere: an override slot the frame's
     * transform packing consults instead of the scene's own placement.</p>
     *
     * <p><b>Cheap on purpose.</b> One reference store. Nothing is re-derived: the
     * geometry, the texture table, the stream offsets and the entity id all
     * belong to the instance rather than to its position, and none of them
     * change when it moves. Compare {@link #setScene}, which rebuilds all of
     * them and is the right call when the <i>set</i> of instances changes rather
     * than where one of them is.</p>
     *
     * <p><b>Threading:</b> safe to call from the game loop thread while the
     * render workers are mid-frame. A reference store cannot tear, so a worker
     * sees either the old placement or the new one — never a partially written
     * matrix — and the worst case is that one instance is drawn a frame behind.
     * That is the same guarantee {@link #setCamera} already gives, and for the
     * same reason: taking the frame lock here would serialise the simulation
     * against rendering to remove a frame of latency on a walking body.</p>
     *
     * @param instanceIndex which world instance to move, in the order
     *     {@link Scene} holds them
     * @param modelToWorld its new placement, or null to return it to the
     *     placement the scene was built with
     * @throws IllegalStateException if no scene is bound
     * @throws IndexOutOfBoundsException if the index is not a world instance
     */
    public void setWorldTransform(final int instanceIndex, final Mat4 modelToWorld)
    {
        final Mat4[] slots = worldOverrides;
        if (slots == null)
        {
            throw new IllegalStateException("setWorldTransform() before setScene()");
        }
        if (instanceIndex < 0 || instanceIndex >= slots.length)
        {
            throw new IndexOutOfBoundsException("world instance " + instanceIndex
                + " is outside 0.." + (slots.length - 1));
        }
        slots[instanceIndex] = modelToWorld;
    }

    /**
     * Returns the override placing a world instance, or null when it still sits
     * where the scene put it.
     *
     * @param instanceIndex which world instance to read
     * @return the override transform, or null if none has been set
     * @throws IllegalStateException if no scene is bound
     */
    public Mat4 worldTransformOverride(final int instanceIndex)
    {
        final Mat4[] slots = worldOverrides;
        if (slots == null)
        {
            throw new IllegalStateException("worldTransformOverride() before setScene()");
        }
        return slots[instanceIndex];
    }

    // Prepares each instance's derived tables and grows the geometry buffers if
    // this scene needs more than the last one did. Called under the lock.
    private void bindScene(final Scene newScene)
    {
        growBuffersFor(newScene.maxPassTriangles());
        final Instance[] world = prepareWorld(newScene);
        final Instance[] hand = prepareView(newScene);
        // Built over BOTH partitions: a translucent instance's textures still
        // occupy the scene-wide table even though the translucent phase binds
        // no table, because the rebase walks every instance exactly once and a
        // partial rebase would shift the opaque indices onto the wrong slots.
        this.sceneTextures = buildSceneTextures(world, hand);

        final int[] solid = sceneIndices(newScene, false);
        final Instance[] opaque = subset(world, solid);
        this.worldSceneIndex = solid;
        this.worldInstances = opaque;
        this.worldEntityIds = entityIdsFor(newScene, solid);
        bindVisibleWorld(opaque.length, worldEntityIds != null);

        bindTranslucent(newScene, world, sceneIndices(newScene, true));

        this.viewStarts = streamOffsets(hand);
        // One slot per instance, all null: a scene starts entirely static and
        // pays nothing for the override path until something is actually moved.
        // Indexed by SCENE index, not by pass position — see worldSceneIndex.
        this.worldOverrides = new Mat4[newScene.worldInstanceCount()];
        this.viewTransforms = new float[hand.length * Camera.WORLD_TO_CLIP_FLOATS];
        this.viewInstances = hand;
        this.scene = newScene;
    }

    // Sizes the opaque pass's per-frame tables for the worst case — nothing
    // culled — so that the cull itself never allocates however the camera moves.
    //
    // The id table is allocated only when the opaque partition tags something,
    // because null there is the switch for the whole outline feature and a
    // freshly allocated array of zeroes would silently turn it on.
    private void bindVisibleWorld(final int count, final boolean tagged)
    {
        this.visibleWorld = new Instance[count];
        this.visibleWorldStarts = new int[count + 1];
        this.visibleWorldTransforms = new float[count * Camera.WORLD_TO_CLIP_FLOATS];
        if (tagged)
        {
            this.visibleWorldIds = new int[count];
            return;
        }
        this.visibleWorldIds = null;
    }

    // Prepares the translucent phase's tables, or clears them all to null when
    // the scene has nothing translucent in it. Null is the gate the per-frame
    // path tests — see the field.
    private void bindTranslucent(final Scene newScene, final Instance[] world,
        final int[] indices)
    {
        if (indices.length == 0)
        {
            this.translucentInstances = null;
            this.translucentScene = null;
            this.translucentCoverage = null;
            this.translucentOrder = null;
            this.translucentDepth = null;
            this.translucentPassInstances = null;
            this.translucentPassStarts = null;
            this.translucentPassTransforms = null;
            this.blendedRenderers = null;
            return;
        }
        final int[] coverage = new int[indices.length];
        for (int slot = 0; slot < indices.length; slot++)
        {
            coverage[slot] = newScene.worldCoverage(indices[slot]);
        }
        this.translucentScene = indices;
        this.translucentInstances = subset(world, indices);
        this.translucentCoverage = coverage;
        this.translucentOrder = new int[indices.length];
        this.translucentDepth = new float[indices.length];
        this.translucentPassInstances = new Instance[indices.length];
        this.translucentPassStarts = new int[indices.length + 1];
        this.translucentPassTransforms =
            new float[indices.length * Camera.WORLD_TO_CLIP_FLOATS];
        this.blendedRenderers = blendedRenderersFor(coverage);
    }

    // One blended span renderer per coverage the scene actually uses, indexed
    // by coverage so the per-run lookup is an array read.
    //
    // TEXTURED with ATTRIBUTE_COUNT attributes like the opaque renderer, and
    // that is required rather than cosmetic: the whole port shares one
    // Rasterizer, whose records carry exactly two attribute planes. A renderer
    // built for a different attribute count would read past them.
    private static SpanRenderer[] blendedRenderersFor(final int[] coverage)
    {
        final SpanRenderer[] table = new SpanRenderer[Scene.OPAQUE + 1];
        for (final int level : coverage)
        {
            if (table[level] == null)
            {
                table[level] = new SpanRenderer(SpanRenderer.ShadingMode.TEXTURED,
                    ATTRIBUTE_COUNT, level);
            }
        }
        return table;
    }

    // The scene indices of one partition of the world list, in SCENE order.
    //
    // Scene order is preserved rather than regrouped because it is what makes
    // the batched stream bit-identical to the per-instance render it replaced:
    // instances are laid into the stream in submission order and the depth test
    // rejects ties, so each pixel sees the same triangle sequence it always did.
    // Partitioning reorders nothing within a pass — it only removes from the
    // opaque pass instances that could not have been drawn in it anyway.
    private static int[] sceneIndices(final Scene source, final boolean translucent)
    {
        // MUTABLE local — how many instances fall in this partition.
        int found = 0;
        for (int index = 0; index < source.worldInstanceCount(); index++)
        {
            if (source.isWorldTranslucent(index) == translucent)
            {
                found++;
            }
        }
        final int[] out = new int[found];
        // MUTABLE local — the write cursor into the partition.
        int at = 0;
        for (int index = 0; index < source.worldInstanceCount(); index++)
        {
            if (source.isWorldTranslucent(index) == translucent)
            {
                out[at] = index;
                at++;
            }
        }
        return out;
    }

    // The prepared entries named by a partition, in the partition's order.
    // Returns the source array untouched when the partition is the whole of it,
    // so an entirely opaque scene allocates nothing and shares one array.
    private static Instance[] subset(final Instance[] all, final int[] indices)
    {
        if (indices.length == all.length)
        {
            return all;
        }
        final Instance[] out = new Instance[indices.length];
        for (int slot = 0; slot < indices.length; slot++)
        {
            out[slot] = all[indices[slot]];
        }
        return out;
    }

    // Sizes the clip-space stream, the per-chunk scratch and the rasterizer for
    // a whole pass. Grow-only: a smaller scene keeps the bigger buffers rather
    // than reallocating down and back up on the next swap.
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
        this.clipEntityIds = new int[maxOutput];
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

    // Where each instance's triangles begin in the batched stream, plus a
    // terminator holding the pass total. Every entry is strictly greater than
    // the one before it, because Scene refuses an instance with no triangles —
    // which is what lets the geometry chunk walk instances with a single
    // increment rather than a search per triangle.
    private static int[] streamOffsets(final Instance[] instances)
    {
        final int[] starts = new int[instances.length + 1];
        for (int index = 0; index < instances.length; index++)
        {
            starts[index + 1] = starts[index] + instances[index].model.triangleCount();
        }
        return starts;
    }

    // One entity id per world instance, or NULL when the scene tags nothing.
    //
    // Null rather than an array of zeroes on purpose: it is the single switch
    // the whole per-frame path tests, so a scene with no players in it cannot
    // accidentally pay for the id clear, the per-pixel id store, the stream
    // compaction or the outline dispatch. See the class Javadoc.
    //
    // The id belongs to the Scene instance, not to the prepared Instance:
    // prepare() shares one prepared entry between duplicate ModelFormats, so
    // two players built from the same model would otherwise collapse onto one
    // id and stop being outlined apart from each other.
    // The ids are read for the OPAQUE partition only, and the "is anything
    // tagged" question is asked of that partition rather than of the scene: a
    // translucent instance writes no id at any pixel, so a scene whose only
    // tagged instance is translucent must still take the cheap path. Answering
    // it from Scene.hasTaggedEntities() would have such a scene clear the id
    // buffer, store an id per covered pixel and dispatch an outline pass that
    // could not possibly find an edge.
    private static int[] entityIdsFor(final Scene source, final int[] indices)
    {
        final int[] out = new int[indices.length];
        // MUTABLE local — whether any instance in this partition is tagged.
        boolean any = false;
        for (int slot = 0; slot < indices.length; slot++)
        {
            out[slot] = source.worldEntityId(indices[slot]);
            any = any || out[slot] != Scene.UNTAGGED;
        }
        if (!any)
        {
            return null;
        }
        return out;
    }

    // Concatenates every distinct instance's textures into one scene-wide table
    // and rebases its per-triangle material indices into it.
    //
    // One raster pass takes one texture table, so a material index has to be
    // meaningful without knowing which instance produced the triangle. The
    // rebase happens exactly once per distinct instance — instances are shared
    // by reference between duplicate models, and a second pass over one would
    // add its base twice.
    private static MipChain[] buildSceneTextures(final Instance[] world, final Instance[] hand)
    {
        final int total = rebase(hand, rebase(world, 0));
        final MipChain[] table = new MipChain[total];
        copyTextures(world, table);
        copyTextures(hand, table);
        return table;
    }

    // Assigns each distinct instance its slice of the scene-wide table and
    // shifts its material indices into it. Returns the next free slot.
    private static int rebase(final Instance[] instances, final int firstBase)
    {
        // MUTABLE local — the next free slot in the scene-wide texture table.
        int base = firstBase;
        for (final Instance instance : instances)
        {
            if (instance.textureBase != Instance.UNASSIGNED)
            {
                continue;
            }
            instance.textureBase = base;
            final int[] materials = instance.triangleMaterial;
            for (int triangle = 0; triangle < materials.length; triangle++)
            {
                if (materials[triangle] != Rasterizer.NO_MATERIAL)
                {
                    materials[triangle] += base;
                }
            }
            base += instance.textures.length;
        }
        return base;
    }

    // Copies each distinct instance's textures into the slice it was assigned.
    private static void copyTextures(final Instance[] instances, final MipChain[] table)
    {
        for (final Instance instance : instances)
        {
            System.arraycopy(instance.textures, 0, table, instance.textureBase,
                instance.textures.length);
        }
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
            buildTriangleColors(source, triangles), buildTextures(source),
            buildBounds(source));
    }

    // The model-space bounding box the frustum cull tests, measured HERE from
    // the vertex block rather than read from ModelFormat's bounds accessors.
    //
    // That is not redundancy for its own sake. ModelFormat's box is a header
    // field written offline by GltfConverter and never checked against the
    // vertices on load — which was harmless while the only consumer was the
    // orbit camera, where a wrong box frames a model badly and nothing worse.
    // A cull is a different contract: a box that understates the geometry
    // deletes part of the world, silently, and only from some angles. So the
    // box the cull trusts is derived from the same array the geometry pass
    // transforms, once per distinct model per setScene, and cannot disagree
    // with it.
    private static float[] buildBounds(final ModelFormat source)
    {
        final float[] box = new float[InstanceCull.BOX_FLOATS];
        final int vertices = source.vertexCount();
        if (vertices == 0)
        {
            return box;
        }
        // MUTABLE locals — the running box over the vertex block.
        float minX = source.positionX(0);
        float minY = source.positionY(0);
        float minZ = source.positionZ(0);
        float maxX = minX;
        float maxY = minY;
        float maxZ = minZ;
        for (int vertex = 1; vertex < vertices; vertex++)
        {
            final float x = source.positionX(vertex);
            final float y = source.positionY(vertex);
            final float z = source.positionZ(vertex);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        box[0] = minX;
        box[1] = minY;
        box[2] = minZ;
        box[3] = maxX;
        box[4] = maxY;
        box[5] = maxZ;
        return box;
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
        final long passesBefore = parallelPasses + rasterizerPasses();
        final Camera view = cameraFor(current, ticIndex);
        this.lastCamera = view;
        this.geometryNear = view.near();
        final I_ThreadPoolPort workers = activePool();

        // Null unless the scene tags something, in which case it is the whole
        // outline feature's switch.
        final int[] tagged = worldEntityIds;

        // Spelled out rather than framebuffer.clear(), which would clear the
        // id buffer unconditionally: that is a third full-frame memset — 3.7 MB
        // at 720p — and an untagged scene must not pay it.
        framebuffer.clearColor(DEFAULT_CLEAR_COLOR);
        framebuffer.clearDepth();
        if (tagged != null)
        {
            framebuffer.clearEntityIds();
        }

        // Packs and culls in one walk: an instance the frustum cannot see is
        // never transformed, never clipped, and never reaches the stream. When
        // the whole opaque partition is culled the pass costs no parallel
        // dispatches at all, because renderPass sees a zero triangle total.
        final int visible = packVisibleWorld(current, view, tagged);
        // MUTABLE local — triangles the whole scene handed the rasterizer.
        int triangles = renderPass(visibleWorld, visible, visibleWorldStarts,
            visibleWorldTransforms, visibleWorldIds, spanRenderer, sceneTextures, workers);

        // Who the player is pointing at, sampled once, from the finished world
        // pass. Both the outline and the reticle are driven from this single
        // read — they are two renderings of one fact, so taking it twice would
        // be two chances for them to disagree with each other.
        this.aimedEntityId = sampleAimedEntity(tagged);

        // After the world pass, so the id buffer is complete; before the
        // viewmodel, so the weapon draws over the outlines rather than under
        // them. OutlinePass's Javadoc explains why fusing it into the raster
        // pass would break the worker-count invariant.
        //
        // Skipped outright when the crosshair is on nothing, which is most of
        // the time: the pass reads every visible pixel's id, and there is no
        // point scanning the frame to mark an entity that is not there.
        if (tagged != null && outlineEnabled && aimedEntityId != Scene.UNTAGGED)
        {
            this.parallelPasses = parallelPasses + 1L;
            outlinePass.draw(framebuffer, workers, aimedEntityId);
        }

        // Translucent instances, back to front, over the finished opaque world.
        //
        // AFTER the outline rather than before it, so smoke drifting across a
        // body dims that body's silhouette along with the body. The outline
        // reads the id buffer, which this phase never writes, so the two cannot
        // be reordered for any reason except how they should look — and a
        // silhouette painted on top of the smoke in front of it would look like
        // a bug.
        //
        // BEFORE the viewmodel's depth clear, because it depth-tests against
        // the world and that depth is about to be thrown away.
        triangles += drawTranslucent(current, view, workers);

        // The viewmodel is depth-tested only against itself. Colour is NOT
        // cleared: the weapon composites over the world it was just occluded
        // by. See the class Javadoc. Ids are not cleared either: view
        // instances are always UNTAGGED, so the pass writes none, and the
        // outline has already been drawn.
        final Instance[] hand = viewInstances;
        if (hand.length > 0)
        {
            framebuffer.clearDepth();
            packView(current, view, hand.length);
            triangles += renderPass(hand, hand.length, viewStarts, viewTransforms, null,
                spanRenderer, sceneTextures, workers);
        }

        // The reticle is the topmost thing on screen and is not in the world:
        // after the viewmodel, and never depth-tested. It must also be before
        // publishFrame(), which de-pads the colour buffer into the present
        // buffer — anything drawn after that call never reaches a window.
        //
        // Runs on the calling thread with every parallel pass already joined.
        // That is required, not incidental: the crosshair spans tile
        // boundaries, so drawing it while the raster pass is live would write
        // into tiles other workers still own.
        if (crosshairEnabled)
        {
            Crosshair.draw(framebuffer, aimedEntityId != Scene.UNTAGGED);
        }

        publishFrame();
        this.lastFrameTriangles = triangles;
        this.lastFrameParallelPasses =
            (int) (parallelPasses + rasterizerPasses() - passesBefore);
        this.lastFrameNanos = time.nanos() - started;
        this.framesRendered = framesRendered + 1;
    }

    /**
     * Returns the tagged entity under the point of aim as of the last frame,
     * or {@link Scene#UNTAGGED} if the crosshair was on nothing.
     *
     * <p>What turns the reticle red and what the wireframe is drawn around.
     * Read it for a HUD or a test; do <b>not</b> read it to decide what a shot
     * hit — see the field for why that is not a style preference.</p>
     *
     * @return the aimed entity id, or {@link Scene#UNTAGGED}
     */
    public int aimedEntityId()
    {
        return aimedEntityId;
    }

    // Which tagged entity, if any, owns the pixel at the point of aim.
    //
    // ONE pixel, at (width/2, height/2). A 1280x720 frame has no centre pixel —
    // its geometric centre is the corner between columns 639 and 640 — so this
    // picks the upper of the two candidates on each axis and says so rather
    // than pretending the choice does not exist. Nothing rests on which: the
    // reticle's gap is at least 2 x (outline + 1) pixels wide (Crosshair
    // .centreGap), so anything close enough to the point of aim to be worth
    // calling "aimed at" covers both columns and both rows.
    //
    // Averaging a neighbourhood was the alternative and is worse: it would put
    // the reticle in a third, in-between state on every silhouette edge, and a
    // highlight that flickers as you graze a shoulder is less informative than
    // one that is simply on or off.
    //
    // Null `tagged` means the scene has no tagged opaque instance at all, so
    // the id buffer was never cleared this frame and holds whatever the last
    // tagged scene left in it. Answering UNTAGGED without reading is both
    // correct and the reason an untagged scene still pays nothing.
    private int sampleAimedEntity(final int[] tagged)
    {
        if (tagged == null)
        {
            return Scene.UNTAGGED;
        }
        return framebuffer.entityIdAt(framebuffer.width() / 2, framebuffer.height() / 2);
    }

    // Concatenates every opaque world instance's placement into the camera's
    // packed transform, and drops the ones the frustum cannot see. Returns how
    // many were kept.
    //
    // Pack-then-test rather than test-then-pack: the cull is expressed in the
    // packed transform's own coefficients (InstanceCull pulls each clip-space
    // frustum plane back through it into model space), so the packing is the
    // input to the decision rather than a cost the decision could avoid. It is
    // 48 multiplies against a saving of the whole instance.
    //
    // The write cursor is what makes the result bit-identical: survivors are
    // appended in scene order, so the stream is the old stream with entries
    // deleted, never rearranged.
    private int packVisibleWorld(final Scene current, final Camera view, final int[] tagged)
    {
        final Instance[] all = worldInstances;
        final Instance[] kept = visibleWorld;
        final int[] starts = visibleWorldStarts;
        final float[] slots = visibleWorldTransforms;
        final int[] keptIds = visibleWorldIds;
        final Mat4[] moved = worldOverrides;
        // The pass position and the scene index are the same number only for an
        // entirely opaque scene; the override table is addressed by the latter.
        final int[] sceneOf = worldSceneIndex;
        final float near = view.near();

        starts[0] = 0;
        // MUTABLE local — the write cursor, and therefore the kept count.
        int count = 0;
        for (int index = 0; index < all.length; index++)
        {
            final int at = count * Camera.WORLD_TO_CLIP_FLOATS;
            view.packModelToClip(placementOf(current, moved, sceneOf[index]), slots, at);
            final Instance instance = all[index];
            if (InstanceCull.isOutsideFrustum(slots, at, near, instance.bounds, 0))
            {
                continue;
            }
            kept[count] = instance;
            starts[count + 1] = starts[count] + instance.model.triangleCount();
            if (keptIds != null)
            {
                keptIds[count] = tagged[index];
            }
            count++;
        }
        return count;
    }

    // Where one world instance actually is this frame: its override if something
    // has moved it, otherwise the placement the immutable Scene was built with.
    private static Mat4 placementOf(final Scene current, final Mat4[] moved, final int index)
    {
        if (moved != null && moved[index] != null)
        {
            return moved[index];
        }
        return current.worldTransform(index);
    }

    // The same for the viewmodel, which is already in view space and therefore
    // takes the projection alone.
    private void packView(final Scene current, final Camera view, final int count)
    {
        final float[] slots = viewTransforms;
        for (int index = 0; index < count; index++)
        {
            view.packViewToClip(current.viewTransform(index), slots,
                index * Camera.WORLD_TO_CLIP_FLOATS);
        }
    }

    // One whole pass — every instance in it — through the whole pipeline.
    // Returns the triangles it submitted.
    //
    // Four parallel passes, whatever the instance count: that is the entire
    // point of batching. The rasterizer is reset rather than replaced:
    // beginFrame clears the tile bins and the triangle count, which is required
    // because records and bins are indexed by position within one submitted
    // stream. It does not touch the framebuffer, so the depth buffer carries
    // across the two passes.
    private int renderPass(final Instance[] instances, final int count, final int[] starts,
        final float[] transforms, final int[] instanceEntityIds, final SpanRenderer renderer,
        final MipChain[] textures, final I_ThreadPoolPort workers)
    {
        final int total = starts[count];
        if (total == 0)
        {
            return 0;
        }
        this.geometryInstances = instances;
        this.geometryInstanceCount = count;
        this.geometryStarts = starts;
        this.geometryTransforms = transforms;
        this.geometryEntityIds = instanceEntityIds;
        this.geometryTriangles = total;
        dispatch(geometryJob, chunkCount, workers);
        final int triangles = compactChunks(total);
        if (triangles == 0)
        {
            return 0;
        }

        final Rasterizer setup = rasterizer;
        setup.beginFrame(framebuffer);
        setup.setupAndBin(clipVertices, triangles, clipMaterials, clipColors,
            idStreamFor(instanceEntityIds), workers);
        setup.rasterize(renderer, textures, workers);
        return triangles;
    }

    // ---- the translucent phase ----

    /**
     * Draws every translucent instance, back to front, composited over the
     * finished opaque frame.
     *
     * <h2>Back-to-front is the whole of the correctness argument</h2>
     *
     * <p>A translucent fragment tests depth but does not write it, so nothing
     * in this phase occludes anything else in it and the depth buffer cannot
     * resolve the order for us. {@link Rgba#srcOver} is not commutative:
     * compositing two puffs the wrong way round gives different pixels. So the
     * instances are sorted by view depth every frame — <b>farthest first</b> —
     * and drawn in that order. The sort is per instance rather than per
     * triangle, which is the usual approximation and is exact whenever
     * translucent instances do not interpenetrate; the effects this exists for
     * are small, convex and separate, so they do not.</p>
     *
     * <h2>Runs, not one pass and not one pass per instance</h2>
     *
     * <p>A {@link SpanRenderer}'s coverage is fixed when it is built, so one
     * batched pass can only draw one coverage. The sorted list is therefore cut
     * into <b>maximal runs of equal coverage</b> and each run is one batched
     * pass. Cutting at coverage <i>changes</i> rather than grouping by coverage
     * is what preserves the sorted order exactly — a run is contiguous in it,
     * so concatenating the runs reproduces it. Grouping would have been fewer
     * passes and the wrong picture.</p>
     *
     * <p>A scene whose translucent instances all share one coverage is therefore
     * exactly one extra batched pass, four parallel dispatches, however many
     * instances are in it. The demo's smoke costs one pass per <i>puff</i>
     * rather than per instance: all five lobes of a puff sit on the same rung of
     * {@code DemoEffects.PUFF_COVERAGE}, so they are one run, and a held trigger
     * keeps three puffs of three different ages in the air — three rungs, three
     * runs, three passes.</p>
     *
     * <p><b>No texture table is bound</b>, so every triangle falls to the flat
     * path and takes the baked colour of its first vertex. See
     * {@link Scene.Builder#addTranslucentWorldInstance} for why that is the
     * contract rather than a limitation to be fixed later.</p>
     *
     * @param current the scene being drawn
     * @param view the camera this frame is rendered from
     * @param workers the pool, or null for the serial path
     * @return the triangles this phase submitted to the rasterizer
     */
    private int drawTranslucent(final Scene current, final Camera view,
        final I_ThreadPoolPort workers)
    {
        final int[] indices = translucentScene;
        if (indices == null)
        {
            return 0;
        }
        final int visible = sortBackToFront(current, view, indices);

        final int[] order = translucentOrder;
        final int[] coverage = translucentCoverage;
        // MUTABLE locals — triangles submitted so far, and the cursor walking
        // the sorted list one equal-coverage run at a time.
        int triangles = 0;
        int from = 0;
        while (from < visible)
        {
            final int level = coverage[order[from]];
            // MUTABLE local — the end of the run starting at `from`.
            int to = from;
            while (to < visible && coverage[order[to]] == level)
            {
                to++;
            }
            triangles += drawTranslucentRun(current, view, from, to, level, workers);
            from = to;
        }
        return triangles;
    }

    // Fills the sort key of every VISIBLE translucent instance and orders those
    // slots farthest first. Returns how many there are — the leading part of
    // `order` that means anything.
    //
    // Culling here does something the opaque pass's cull does not: it collapses
    // the RUN COUNT. Runs are cut at coverage changes in the sorted order, so a
    // puff of a different coverage sitting between two others splits them into
    // three passes — twelve parallel dispatches — even when it is behind the
    // camera and draws nothing. Dropping it first merges its neighbours into
    // one run and one pass. That is the largest single barrier saving in the
    // demo scene, and it is a consequence of the cull rather than a separate
    // mechanism.
    //
    // Removing entries cannot change the relative order of the ones that remain,
    // so the surviving puffs still composite in exactly the sequence they did.
    //
    // The key is the instance origin's distance along the camera's forward
    // axis — its view-space z. Taken from the placement's translation column
    // rather than by transforming a bound, because a bound would need the whole
    // model walked per frame to say something the origin already says for the
    // small, roughly centred geometry this phase draws.
    //
    // Insertion sort, and not apologetically: the list is a handful of entries,
    // it is nearly sorted from one frame to the next because the instances move
    // a little rather than teleport, and it allocates nothing. The comparison
    // is strict, so equal depths keep scene order — which is what makes the
    // frame reproducible when two puffs sit at the same distance.
    private int sortBackToFront(final Scene current, final Camera view, final int[] indices)
    {
        final Mat4[] moved = worldOverrides;
        final Vec3 eye = view.eye();
        final Vec3 forward = view.forward();
        final float[] depths = translucentDepth;
        final int[] order = translucentOrder;
        final Instance[] all = translucentInstances;
        final float[] scratch = cullTransform;
        final float near = view.near();

        // MUTABLE local — the write cursor into the visible prefix of `order`.
        int visible = 0;
        for (int slot = 0; slot < indices.length; slot++)
        {
            final Mat4 placement = placementOf(current, moved, indices[slot]);
            view.packModelToClip(placement, scratch, 0);
            if (InstanceCull.isOutsideFrustum(scratch, 0, near, all[slot].bounds, 0))
            {
                continue;
            }
            final float dx = placement.get(0, Mat4.ORDER - 1) - eye.x();
            final float dy = placement.get(1, Mat4.ORDER - 1) - eye.y();
            final float dz = placement.get(2, Mat4.ORDER - 1) - eye.z();
            depths[slot] = dx * forward.x() + dy * forward.y() + dz * forward.z();
            order[visible] = slot;
            visible++;
        }

        for (int slot = 1; slot < visible; slot++)
        {
            final int moving = order[slot];
            final float depth = depths[moving];
            // MUTABLE local — the gap the moving entry is sifted down through.
            int gap = slot - 1;
            while (gap >= 0 && depths[order[gap]] < depth)
            {
                order[gap + 1] = order[gap];
                gap--;
            }
            order[gap + 1] = moving;
        }
        return visible;
    }

    // One maximal run of equal coverage, in the order the sort put it.
    //
    // The run's instances, stream offsets and packed transforms are gathered
    // into scratch sized for every translucent instance at bind time, so a run
    // costs no allocation whatever its length. Entity ids are null: a
    // translucent pixel writes none, by construction.
    private int drawTranslucentRun(final Scene current, final Camera view, final int from,
        final int to, final int coverage, final I_ThreadPoolPort workers)
    {
        final Instance[] pass = translucentPassInstances;
        final int[] starts = translucentPassStarts;
        final float[] slots = translucentPassTransforms;
        final Instance[] all = translucentInstances;
        final int[] indices = translucentScene;
        final int[] order = translucentOrder;
        final Mat4[] moved = worldOverrides;

        final int count = to - from;
        starts[0] = 0;
        for (int slot = 0; slot < count; slot++)
        {
            final int which = order[from + slot];
            pass[slot] = all[which];
            starts[slot + 1] = starts[slot] + pass[slot].model.triangleCount();
            view.packModelToClip(placementOf(current, moved, indices[which]), slots,
                slot * Camera.WORLD_TO_CLIP_FLOATS);
        }
        return renderPass(pass, count, starts, slots, null, blendedRenderers[coverage],
            null, workers);
    }

    // The per-output-triangle id stream, or null when this pass carries no
    // ids. Null propagates all the way down to SpanRenderer, where it is the
    // difference between one store per covered pixel and none.
    private int[] idStreamFor(final int[] instanceEntityIds)
    {
        if (instanceEntityIds == null)
        {
            return null;
        }
        return clipEntityIds;
    }

    // Transforms and clips one contiguous slice of the pass's flattened
    // triangle range, crossing instance boundaries as it goes.
    //
    // Chunk c is one submitParallel index, so this body never runs concurrently
    // with itself: its clipper, its scratch and its region of the output stream
    // are all private to it without any thread identity being involved. That is
    // the same argument Rasterizer's binning makes, and it is why a per-worker
    // scheme was never needed.
    //
    // The slice is a range of the WHOLE pass, so a chunk may start part way
    // through one instance and end part way through another. The instance is
    // found once, by a scan of the offset table, and then advanced by one at
    // each boundary — an instance always holds at least one triangle, so the
    // advance always makes progress.
    private void runGeometryChunk(final int chunk)
    {
        final Instance[] instances = geometryInstances;
        final int[] starts = geometryStarts;
        final int from = chunkStart(chunk, geometryTriangles);
        final int to = chunkStart(chunk + 1, geometryTriangles);
        final int outBase = from * CLIP_EXPANSION;

        // MUTABLE locals — the flat triangle reached, the instance it belongs
        // to, and the output triangles emitted so far.
        int flat = from;
        int index = instanceContaining(starts, geometryInstanceCount, from);
        int produced = 0;
        while (flat < to)
        {
            final int instanceEnd = starts[index + 1];
            final int runEnd = Math.min(to, instanceEnd);
            produced += clipRun(chunk, instances[index], index, flat - starts[index],
                runEnd - starts[index], outBase + produced);
            flat = runEnd;
            index++;
        }
        chunkProduced[chunk] = produced;
    }

    // The instance owning a flat triangle index. Linear, because it runs once
    // per chunk per pass — a few hundred comparisons a frame against a few
    // hundred thousand triangle transforms.
    private static int instanceContaining(final int[] starts, final int count, final int flat)
    {
        for (int index = 0; index < count; index++)
        {
            if (flat < starts[index + 1])
            {
                return index;
            }
        }
        return Math.max(0, count - 1);
    }

    // Transforms and clips triangles [localFrom, localTo) of one instance into
    // the stream, starting at output triangle outBase. Returns how many output
    // triangles the clip produced.
    private int clipRun(final int chunk, final Instance instance, final int instanceIndex,
        final int localFrom, final int localTo, final int outBase)
    {
        final ModelFormat current = instance.model;
        final float near = geometryNear;
        final float[] transforms = geometryTransforms;
        final int transformAt = instanceIndex * Camera.WORLD_TO_CLIP_FLOATS;
        final int[] indices = current.indices();
        final float[] scratch = chunkScratch[chunk];
        final TriangleClipper clipper = clippers[chunk];
        final float[] out = clipVertices;
        final int[] materials = clipMaterials;
        final int[] colors = clipColors;
        final int[] sourceMaterial = instance.triangleMaterial;
        final int[] sourceColor = instance.triangleColor;

        // Null for the viewmodel pass and for every untagged scene, in which
        // case not one id is written. The id is per SCENE instance, so it is
        // read here from the instance index rather than from `instance`, whose
        // prepared entry is shared between duplicate models.
        final int[] entityOut = passEntityOut();
        final int entityId = passEntityId(instanceIndex);

        // MUTABLE local — output triangles this run has emitted so far.
        int produced = 0;
        for (int triangle = localFrom; triangle < localTo; triangle++)
        {
            gatherTriangle(current, indices, triangle, transforms, transformAt, scratch);
            final int emitted = clipper.clipTriangle(near, scratch, 0, out,
                (outBase + produced) * TRIANGLE_FLOATS);
            for (int k = 0; k < emitted; k++)
            {
                materials[outBase + produced + k] = sourceMaterial[triangle];
                colors[outBase + produced + k] = sourceColor[triangle];
            }
            if (entityOut != null)
            {
                writeEntityIds(entityOut, outBase + produced, emitted, entityId);
            }
            produced += emitted;
        }
        return produced;
    }

    // The id stream this pass writes into, or null when it writes none.
    private int[] passEntityOut()
    {
        if (geometryEntityIds == null)
        {
            return null;
        }
        return clipEntityIds;
    }

    // The entity id of one instance of the pass being transformed.
    private int passEntityId(final int instanceIndex)
    {
        final int[] ids = geometryEntityIds;
        if (ids == null)
        {
            return Scene.UNTAGGED;
        }
        return ids[instanceIndex];
    }

    // One input triangle's id, copied to each of the output triangles the clip
    // produced from it. A clipped triangle is still the same entity.
    private static void writeEntityIds(final int[] out, final int at, final int count,
        final int entityId)
    {
        for (int k = 0; k < count; k++)
        {
            out[at + k] = entityId;
        }
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
        final int triangle, final float[] transform, final int transformAt,
        final float[] scratch)
    {
        final int base = triangle * ModelFormat.INDICES_PER_TRIANGLE;
        for (int corner = 0; corner < TriangleClipper.TRIANGLE_VERTICES; corner++)
        {
            final int vertex = indices[base + corner];
            final int at = corner * VERTEX_STRIDE;
            Camera.transformToClip(transform, transformAt, source.positionX(vertex),
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
        // Null when this pass wrote no ids, in which case there is nothing to
        // close the gaps in.
        final int[] entityOut = passEntityOut();

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
                if (entityOut != null)
                {
                    System.arraycopy(entityOut, source, entityOut, cursor, emitted);
                }
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
    private void dispatch(final I_ParallelJob job, final int jobCount,
        final I_ThreadPoolPort workers)
    {
        this.parallelPasses = parallelPasses + 1L;
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
     * <p>Reads the front buffer of the double-buffered handoff, so it never
     * blocks on a frame in flight and never sees half of one frame and half of
     * the next. The only contention is against the reference swap at the end of
     * a frame, which is two field writes.</p>
     *
     * @param destination array of at least {@code surfaceWidth() *
     *     surfaceHeight()} elements
     * @return true if a frame was copied, false before the first finished frame
     *     or after a resize discarded it
     * @throws IllegalArgumentException if the destination is too small
     */
    public boolean copyColorInto(final int[] destination)
    {
        presentLock.lock();
        try
        {
            if (!framePublished)
            {
                return false;
            }
            if (destination == null || destination.length < presentPixels)
            {
                throw new IllegalArgumentException("copyColorInto() needs an int["
                    + presentPixels + "] for a " + framebuffer.width() + "x"
                    + framebuffer.height() + " frame");
            }
            System.arraycopy(frontColor, 0, destination, 0, presentPixels);
            return true;
        }
        finally
        {
            presentLock.unlock();
        }
    }

    // De-pads the finished frame into the back buffer and swaps it to the
    // front. Called at the end of every frame, under frameLock.
    //
    // The copy is outside presentLock on purpose: the back buffer belongs to
    // the renderer alone between swaps, because the only way it can become the
    // front buffer is through the swap below, and the presenter only ever reads
    // the front buffer while holding the lock the swap needs. So the lock
    // covers two reference writes and nothing else — see the class Javadoc for
    // why that mattered enough to add a second buffer.
    private void publishFrame()
    {
        final int[] target = backColor;
        if (target == null || target.length != presentPixels)
        {
            return;
        }
        framebuffer.copyColorTo(target);
        presentLock.lock();
        try
        {
            this.backColor = frontColor;
            this.frontColor = target;
            this.framePublished = true;
        }
        finally
        {
            presentLock.unlock();
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
     * Turns the aiming reticle on or off. Off until asked.
     *
     * <p>Set this once at wiring time, from the composition root that knows it
     * is building a first-person game — {@code DesktopLauncher} does, the
     * preview tools do not. See the field for why the default is off.</p>
     *
     * @param enabled whether finished frames should carry a crosshair
     */
    public void setCrosshairEnabled(final boolean enabled)
    {
        this.crosshairEnabled = enabled;
    }

    /**
     * Turns the entity wireframe highlight on or off. On by default.
     *
     * <p>Safe to flip between frames: it is one volatile read on the render
     * thread, and the id buffer is produced either way, so nothing else in the
     * frame changes shape when it moves. This remains the switch — a tool that
     * wants a clean frame turns it off here.</p>
     *
     * @param enabled whether tagged entities should get an edge highlight
     */
    public void setOutlineEnabled(final boolean enabled)
    {
        this.outlineEnabled = enabled;
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

    /**
     * Returns how many indexed passes the last frame dispatched.
     *
     * <p>With a worker pool each one is a {@code submitParallel} publish/join
     * boundary — a barrier every participating thread pays — so this is the
     * figure that decides whether adding workers helps or hurts. A batched
     * frame costs <b>eight</b>: four for the world pass and four for the
     * viewmodel, independent of the instance count. The per-instance pipeline
     * this replaced cost four per instance, which is where the demo room's
     * 1,180 came from.</p>
     *
     * <p>A scene with tagged entities costs one more — {@link OutlinePass} —
     * and a scene without them costs exactly the same eight it always did. A
     * scene with translucent instances costs four more per <b>run</b> of equal
     * coverage in the back-to-front order, which for effects that share one
     * coverage is four however many of them are on screen.</p>
     *
     * <p><b>Eight is now the ceiling rather than the figure.</b> A pass with
     * nothing visible in it dispatches nothing, so the count falls as the camera
     * turns away: the demo scene measures 20 with its smoke in view and 8 with
     * the smoke culled. Do not read a fixed number out of this.</p>
     *
     * <h2>How much a barrier actually costs — MEASURED, and it is not much</h2>
     *
     * <p>This number was the headline suspect for the resolution-independent
     * cost, on the reasonable theory that a publish/join boundary is pure
     * overhead every worker pays. <b>It was measured, and on a 22-thread desktop
     * host the boundaries are cheap enough that removing them is not worth
     * doing.</b> Recorded here so the experiment is not repeated:</p>
     *
     * <ul>
     *   <li>A frame is full of small passes — the viewmodel is one model, a
     *       smoke run is three lobes — and each pays four boundaries to spread a
     *       few hundred triangles. Running the three <i>geometry-bound</i>
     *       dispatches of a small pass on the calling thread instead removes
     *       three boundaries per small pass and is <b>bit-identical by the
     *       pooled-equals-serial invariant</b>, so it was safe to try.</li>
     *   <li>At a 2048-triangle threshold, over three interleaved A/B rounds at
     *       640x360 on eight workers, the result was <b>inconsistent in sign and
     *       inside the run-to-run noise</b>: one pose slightly better, the other
     *       slightly worse, and under load the viewmodel-heavy pose was clearly
     *       worse at 1280x720 because the serial work cost more than the
     *       boundaries saved.</li>
     *   <li>That last point is the useful one, and it is a direct measurement
     *       rather than an inference: <b>a boundary here costs less than the
     *       serial execution of the few hundred triangles it was distributing.</b>
     *       So the remaining barrier count is not where a frame's fixed cost is
     *       going on this hardware, and the invasive options — folding the
     *       count-then-scatter pair in {@link Rasterizer#setupAndBin} into one
     *       pass, which needs the serial prefix sum between them gone — are not
     *       justified by anything measured.</li>
     * </ul>
     *
     * <p><b>This conclusion is hardware-specific and should be re-measured on a
     * phone before it is trusted there.</b> The original 5.4x-pixels-for-2.9x-time
     * observation came from an Android emulator, whose scheduler and core count
     * are nothing like a desktop's, and no barrier measurement has been taken on
     * one.</p>
     *
     * @return the last frame's parallel pass count, or zero before the first
     *     frame
     */
    public int lastFrameParallelPasses()
    {
        return lastFrameParallelPasses;
    }

    // The rasterizer's own running dispatch count, or zero before a scene has
    // sized one.
    private long rasterizerPasses()
    {
        final Rasterizer current = rasterizer;
        if (current == null)
        {
            return 0L;
        }
        return current.parallelPasses();
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
        /** {@link #textureBase} before the scene-wide texture table is built. */
        private static final int UNASSIGNED = -1;

        private final ModelFormat model;

        /**
         * One material index per triangle, rebased into the scene-wide texture
         * table. MUTABLE: built model-local, then shifted by
         * {@link #textureBase} exactly once, when the scene is bound. It is
         * shifted in place rather than copied because the array is freshly
         * built for this scene and nothing else can see it yet.
         */
        private final int[] triangleMaterial;

        private final int[] triangleColor;
        private final MipChain[] textures;

        /**
         * The model-space bounding box the per-instance frustum cull tests,
         * {@link InstanceCull#BOX_FLOATS} floats. Measured from the vertex
         * block by {@link #buildBounds}, not read from the file header.
         */
        private final float[] bounds;

        /**
         * Where this instance's textures start in the scene-wide table.
         * MUTABLE: assigned once by {@link #buildSceneTextures}, and the guard
         * that stops a shared instance being rebased twice.
         */
        private int textureBase = UNASSIGNED;

        Instance(final ModelFormat instanceModel, final int[] materials, final int[] colors,
            final MipChain[] instanceTextures, final float[] modelBounds)
        {
            this.model = instanceModel;
            this.triangleMaterial = materials;
            this.triangleColor = colors;
            this.textures = instanceTextures;
            this.bounds = modelBounds;
        }
    }
}
