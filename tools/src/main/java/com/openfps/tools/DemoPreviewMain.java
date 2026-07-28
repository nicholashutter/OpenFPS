/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.demo.DemoAssetException;
import com.openfps.engine.demo.DemoModels;
import com.openfps.engine.demo.DemoScene;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.gameplay.PlayerInputView;
import com.openfps.engine.hal.adapter.desktop.DesktopTimePort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.InputState;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the whole first-person demo — room, props <b>and viewmodel</b> — to
 * PNGs, with no window and no graphics API.
 *
 * Build-time only — this class is never on a runtime classpath.
 *
 * <h2>Why this is not just {@code RenderPreviewMain} with more models</h2>
 *
 * <p>{@code RenderPreviewMain} orbits a camera around one model's bounding box.
 * That answers "does this asset convert and rasterize", which is a different
 * question from "does the demo work", and it cannot answer the second one at
 * all: it never builds a {@link DemoScene}, never places a view instance, and
 * never runs a {@link PlayerController}. Three things can only be verified by
 * the pipeline below:</p>
 *
 * <ol>
 *   <li><b>The viewmodel is on screen and unclipped.</b> A view instance is
 *       drawn after a depth clear and takes the projection alone; nothing in
 *       the single-model path exercises that.</li>
 *   <li><b>The world scale is right.</b> A room only reads as a room relative
 *       to an eye height, and the eye height comes from the controller.</li>
 *   <li><b>The camera is genuinely driven by input.</b> Which is why every
 *       shot here reaches its pose by <b>feeding {@link InputState} through
 *       {@link PlayerInputView} into {@link PlayerController}</b>, one tic at a
 *       time, rather than by constructing a camera directly. A shot that framed
 *       itself would prove nothing about the wiring; these fail if the input
 *       path is broken.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   gradlew :tools:demoPreview -PdemoOut=C:\tmp\demo
 *
 *   --outDir=&lt;dir&gt;     where the PNGs go; required
 *   --assets=&lt;dir&gt;     model root; default assets/models
 *   --width=N --height=N   frame size; default 1280x720
 *   --threads=N        worker threads; 0 (default) runs serially
 *   --frames=N         after the shots, time N frames of the full scene
 *   --shot=&lt;name&gt;      render one shot only; default is all four
 * </pre>
 *
 * <p>The output directory is deliberately not defaulted into the repository:
 * {@code docs/ASSETS.md} § 6 keeps generated art out of git, and a PNG written
 * next to the source tree is one {@code git add -A} away from being committed.
 * Point it somewhere outside.</p>
 *
 * <h2>What {@code --frames} measured, and why {@code --threads} defaults to 0</h2>
 *
 * <p>At 1280x720 over 300 frames, best-of-N, on a 22-thread host:</p>
 *
 * <pre>
 *   scene                       serial     4 threads   8 threads
 *   demo room, 295 instances    19.6 ms    155 ms      297 ms
 *   fallback room, 1 instance   17.2 ms    --          2.9 ms
 * </pre>
 *
 * <p><b>The parallel path is not broken — it is per-instance.</b> One instance
 * gets a 5.9x speedup from 8 workers, exactly as
 * {@code render/README.md} § 7 intends. But {@code SoftwareRenderPort} renders
 * instances strictly one at a time and each crosses <b>four</b>
 * {@code submitParallel} publish/join boundaries, so a 295-instance room pays
 * roughly 1,180 barriers per frame to distribute a few dozen triangles apiece.
 * The barrier cost swamps the work, and adding workers adds barrier cost.</p>
 *
 * <p>Hence the default of {@code --threads=0}: serial is the fastest setting
 * this tool has for a real scene, which is a statement about the scene shape,
 * not about the rasterizer's inner loops. <b>Recorded, not fixed</b> — the fix
 * is to batch world instances into one geometry stream and one raster pass so a
 * frame pays four barriers rather than four per instance, and that is an
 * {@code R_} change.</p>
 */
public final class DemoPreviewMain
{
    /** Default frame width — the 720p target {@code docs/ASSETS.md} § 2 names. */
    public static final int DEFAULT_WIDTH = 1280;

    /** Default frame height. */
    public static final int DEFAULT_HEIGHT = 720;

    /** Default model root, relative to the repository root. */
    public static final String DEFAULT_ASSETS = "assets/models";

    /** Exit code for a usage error. */
    public static final int EXIT_USAGE = 2;

    /** Exit code for a missing model set. */
    public static final int EXIT_NO_ASSETS = 3;

    private static final Logger LOG = LoggerFactory.getLogger(DemoPreviewMain.class);

    /** Nanoseconds in a millisecond. */
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /** Simulation rate the scripted shots are stepped at, in Hz. */
    private static final float SCRIPT_HZ = 60.0f;

    /** Degrees in half a turn, for the degrees-to-radians conversion. */
    private static final float DEGREES_PER_HALF_TURN = 180.0f;

    /**
     * Tics the standing aim phase is spread over.
     *
     * <p>More than one, deliberately. A whole turn delivered in a single tic
     * would exercise a path no mouse ever produces and would hide an error that
     * only accumulates — {@code PlayerController} wraps yaw every update, and a
     * wrap that drifted would show up over twenty small steps and not over one
     * large one.</p>
     */
    private static final int TURN_TICS = 20;

    /**
     * The shots, each a scripted walk from the demo's spawn point.
     *
     * <p>Between them they cover the four things a human has to look at to
     * believe the demo works: the room has depth, a corner closes, the weapon
     * reads as a weapon and survives being pressed against a wall, and the
     * camera moves when input says so.</p>
     */
    private static final Shot[] SHOTS =
    {
        new Shot("01-down-room", 0.0f, 0.0f, 0, 0.0f, 0.0f, 0.0f,
            "spawn, level, looking down the long axis of the room at the doorway"),
        new Shot("02-corner", -112.0f, -4.0f, 25, 1.0f, 0.0f, 0.0f,
            "turned back over the left shoulder and walked into the near corner —"
                + " check the two walls meet each other and both meet the floor"),
        new Shot("03-weapon-at-wall", 90.0f, -20.0f, 58, 1.0f, 0.0f, 0.0f,
            "walked up to the east wall and looked down it — the weapon must NOT clip"
                + " through it, which is what the view-space depth clear buys"),
        new Shot("04-after-movement", 0.0f, 2.0f, 90, 1.0f, 0.35f, 70.0f,
            "90 tics of forward + strafe while turning 70 degrees, proving the camera"
                + " is driven by input and not by a hard-coded pose"),
    };

    private DemoPreviewMain()
    {
        // entry point holder
    }

    /**
     * Renders the demo's shots and writes them as PNGs.
     *
     * @param args see the class Javadoc
     * @throws IOException if a PNG cannot be written
     */
    public static void main(final String[] args) throws IOException
    {
        final String outDir = option(args, "--outDir=");
        if (outDir == null || outDir.isEmpty())
        {
            LOG.error("Nothing to do: give --outDir=<directory>.");
            System.exit(EXIT_USAGE);
            return;
        }

        final String assets = optionOr(args, "--assets=", DEFAULT_ASSETS);
        final DemoScene demo;
        try
        {
            demo = DemoScene.build(DemoModels.load(Path.of(assets)));
        }
        catch (final DemoAssetException e)
        {
            LOG.error("Cannot build the demo scene: {}", e.getMessage());
            System.exit(EXIT_NO_ASSETS);
            return;
        }

        final int width = intOption(args, "--width=", DEFAULT_WIDTH);
        final int height = intOption(args, "--height=", DEFAULT_HEIGHT);
        final ToolPool pool = ToolPool.open(intOption(args, "--threads=", 0));
        try
        {
            renderAll(demo, Path.of(outDir), width, height, pool,
                intOption(args, "--frames=", 0), option(args, "--shot="));
        }
        finally
        {
            pool.close();
        }
    }

    // Brings up the whole pipeline once, then walks the shot list through it.
    // One renderer and one scene serve every shot: setScene is the expensive
    // call and its cost has nothing to do with where the camera is.
    private static void renderAll(final DemoScene demo, final Path outDir, final int width,
        final int height, final ToolPool pool, final int frames, final String only)
        throws IOException
    {
        final I_TimePort time = new DesktopTimePort();
        time.init();

        final SoftwareRenderPort renderer = new SoftwareRenderPort(pool.port(), time);
        renderer.init();
        renderer.resize(width, height);
        renderer.setScene(demo.scene());
        final float aspect = (float) width / (float) height;

        // MUTABLE local — how many shots actually matched the filter.
        int written = 0;
        for (final Shot shot : SHOTS)
        {
            if (only != null && !only.isEmpty() && !only.equals(shot.name))
            {
                continue;
            }
            renderShot(demo, shot, renderer, outDir, width, height, aspect);
            written++;
        }
        if (written == 0)
        {
            LOG.warn("No shot matched --shot={} — nothing was written.", only);
        }

        if (frames > 0)
        {
            measure(renderer, frames, width, height, pool);
        }
        renderer.shutdown();
        time.shutdown();
    }

    // One shot: script the controller to its pose, aim, render, write.
    private static void renderShot(final DemoScene demo, final Shot shot,
        final SoftwareRenderPort renderer, final Path outDir, final int width, final int height,
        final float aspect) throws IOException
    {
        final PlayerController controller = demo.spawnController();
        drive(controller, shot);
        renderer.setCamera(controller.camera(aspect));
        renderer.renderFrame(0);

        final Path out = outDir.resolve(shot.name + ".png");
        FramePng.write(renderer, out, width, height);
        LOG.info("{} -> {}", shot.name, out.toAbsolutePath());
        LOG.info("    {}", shot.description);
        LOG.info("    eye {}, yaw {} deg, pitch {} deg, {} triangles after clipping",
            controller.eyePosition(), degrees(controller.yawRadians()),
            degrees(controller.pitchRadians()), renderer.lastFrameTriangles());
    }

    // Steps the controller through the shot's scripted tics.
    //
    // This is the production path with the platform removed: InputState is what
    // a real InputPort latches, PlayerInputView is the same adapter the engine
    // uses, and the delta is the same fixed tic duration. Nothing here reaches
    // around the controller to set a position directly, which is the entire
    // reason these images are evidence rather than illustration.
    //
    // Two phases, because one is not enough to aim a shot. Turning and walking
    // at once traces an arc, so a script that did both together could not be
    // pointed at a named corner without solving for the curve. Aiming first and
    // then walking goes where it says it goes; the last shot deliberately keeps
    // the two overlapped, because a camera that turns while it moves is the
    // thing that needs proving.
    private static void drive(final PlayerController controller, final Shot shot)
    {
        final PlayerInputView view = new PlayerInputView();
        step(controller, view, TURN_TICS, 0.0f, 0.0f, shot.aimYawDegrees,
            shot.aimPitchDegrees);
        step(controller, view, shot.walkTics, shot.forwardAxis, shot.strafeAxis,
            shot.walkYawDegrees, 0.0f);
    }

    // One phase: hold the axes for `tics` tics while spreading the rotation
    // evenly across them.
    private static void step(final PlayerController controller, final PlayerInputView view,
        final int tics, final float forwardAxis, final float strafeAxis,
        final float yawDegrees, final float pitchDegrees)
    {
        if (tics <= 0)
        {
            return;
        }
        final float deltaSeconds = 1.0f / SCRIPT_HZ;
        final float yawPerTic = radians(yawDegrees) / tics;
        final float pitchPerTic = radians(pitchDegrees) / tics;
        for (int tic = 0; tic < tics; tic++)
        {
            view.wrap(InputState.of(forwardAxis, strafeAxis, yawPerTic, pitchPerTic,
                false, false, false));
            controller.update(view, deltaSeconds);
        }
    }

    // Renders repeatedly and reports best and mean. Min-of-N is the figure
    // docs/ASSETS.md section 2 quotes, so both are printed rather than one
    // being chosen here.
    private static void measure(final SoftwareRenderPort renderer, final int frames,
        final int width, final int height, final ToolPool pool)
    {
        // MUTABLE locals — the running best and total across the sample.
        long best = Long.MAX_VALUE;
        long total = 0L;
        for (int frame = 0; frame < frames; frame++)
        {
            renderer.renderFrame(frame);
            final long took = renderer.lastFrameNanos();
            total += took;
            if (took < best)
            {
                best = took;
            }
        }
        final double pixels = (double) width * (double) height;
        LOG.info("Full demo scene at {}x{}: best {} ms, mean {} ms over {} frames,"
            + " {} worker threads, {} triangles after clipping",
            width, height, format(best / NANOS_PER_MILLI),
            format(total / (double) frames / NANOS_PER_MILLI), frames, pool.workerCount(),
            renderer.lastFrameTriangles());
        LOG.info("Best frame: {} ns per screen pixel, {} frames per second sustained",
            format(best / pixels), format(1e9 / best));
    }

    // Radians from degrees, and back — reporting only, never simulation state.
    private static float radians(final float degrees)
    {
        return (float) (degrees * StrictMath.PI / DEGREES_PER_HALF_TURN);
    }

    private static String degrees(final float radians)
    {
        return format(radians * DEGREES_PER_HALF_TURN / StrictMath.PI);
    }

    private static String format(final double value)
    {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    // ---- argument parsing ----

    private static String option(final String[] args, final String prefix)
    {
        for (final String arg : args)
        {
            if (arg != null && arg.startsWith(prefix))
            {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static String optionOr(final String[] args, final String prefix,
        final String fallback)
    {
        final String value = option(args, prefix);
        if (value == null || value.isEmpty())
        {
            return fallback;
        }
        return value;
    }

    private static int intOption(final String[] args, final String prefix, final int fallback)
    {
        final String value = option(args, prefix);
        if (value == null || value.isEmpty())
        {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    /**
     * One scripted camera pose, expressed as input rather than as a placement.
     *
     * <p>Immutable and package-private by field, because it is a parameter
     * block for one private method and giving it accessors would be ceremony
     * around four floats.</p>
     */
    private static final class Shot
    {
        /** PNG base name, without the extension. */
        private final String name;

        /** Yaw turned during the standing aim phase, in degrees. */
        private final float aimYawDegrees;

        /** Pitch turned during the standing aim phase, in degrees. */
        private final float aimPitchDegrees;

        /** Tics of movement after aiming. */
        private final int walkTics;

        /** Forward axis held for every walking tic, -1..1. */
        private final float forwardAxis;

        /** Strafe axis held for every walking tic, -1..1. */
        private final float strafeAxis;

        /** Yaw turned <em>while</em> walking, in degrees. */
        private final float walkYawDegrees;

        /** What a human should be checking in this image. */
        private final String description;

        Shot(final String shotName, final float aimYaw, final float aimPitch, final int tics,
            final float forward, final float strafe, final float walkYaw, final String what)
        {
            this.name = shotName;
            this.aimYawDegrees = aimYaw;
            this.aimPitchDegrees = aimPitch;
            this.walkTics = tics;
            this.forwardAxis = forward;
            this.strafeAxis = strafe;
            this.walkYawDegrees = walkYaw;
            this.description = what;
        }
    }
}
