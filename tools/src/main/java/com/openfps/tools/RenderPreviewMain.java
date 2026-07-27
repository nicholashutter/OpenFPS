/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.pool.ThreadPoolFactory;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.hal.adapter.desktop.DesktopTimePort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.Camera;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rasterizer;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.engine.render.adapter.Vec3;
import com.openfps.tools.model.CubeModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders one frame of a {@link ModelFormat} model to a PNG on disk, with no
 * window and no graphics API.
 *
 * Build-time only — this class is never on a runtime classpath.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Three reasons, and the third is the one that makes it more than a
 * convenience:</p>
 *
 * <ol>
 *   <li><b>It is how the renderer is verified.</b> Unit tests assert numbers;
 *       a rasterizer bug is usually something you can only see.</li>
 *   <li><b>It is how a human verifies it</b> without building and running a
 *       windowed client.</li>
 *   <li><b>It makes the whole pipeline testable where there is no display.</b>
 *       Everything from {@link Camera} to {@code Framebuffer} is pure math on
 *       arrays ({@code render/README.md} § 12), so a frame can be produced and
 *       inspected in CI. Only the upload and the quad need a context, and
 *       {@code GdxScreenshot} in {@code :desktop} covers those.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   gradlew :tools:renderPreview -PpreviewOut=C:\tmp\frame.png
 *
 *   --out=&lt;path.png&gt;      where to write; required unless --frames is given
 *   --model=&lt;path.ofm&gt;    what to draw; default is the built-in cube
 *   --writeModel=&lt;path&gt;   also write the built-in cube as a .ofm
 *   --width=N --height=N  frame size; default 800x600
 *   --angle=&lt;degrees&gt;     orbit angle; default 35
 *   --distance=&lt;factor&gt;   camera distance as a multiple of the model radius
 *   --elevation=&lt;factor&gt;  camera height as a multiple of the model radius
 *   --cull=NONE|CLOCKWISE|COUNTER_CLOCKWISE   default: the pipeline's own
 *   --threads=N           worker threads; 0 (default) runs serially
 *   --frames=N            render N frames and report the timing
 * </pre>
 *
 * <p>The output path is deliberately not defaulted into the repository:
 * {@code docs/ASSETS.md} § 6 keeps generated art out of git, and a PNG written
 * next to the source tree is one {@code git add -A} away from being committed.
 * Point it somewhere outside.</p>
 */
public final class RenderPreviewMain
{
    /** Default frame width when {@code --width} is not given. */
    public static final int DEFAULT_WIDTH = 800;

    /** Default frame height when {@code --height} is not given. */
    public static final int DEFAULT_HEIGHT = 600;

    /** Default orbit angle in degrees. */
    public static final float DEFAULT_ANGLE_DEGREES = 35.0f;

    /** Default camera distance, as a multiple of the model's bounding radius. */
    public static final float DEFAULT_DISTANCE = 2.6f;

    /** Exit code for a usage error. */
    public static final int EXIT_USAGE = 2;

    private static final Logger LOG = LoggerFactory.getLogger(RenderPreviewMain.class);

    /** Bus capacity for the standalone worker pool; nothing is published to it. */
    private static final int BUS_CAPACITY = 64;

    /** Default camera height above the model centre, as a multiple of the radius. */
    public static final float DEFAULT_ELEVATION = 0.9f;

    /** Nanoseconds in a millisecond. */
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /** Shift turning a {@code 0xRRGGBBAA} pixel into {@code 0x00RRGGBB}. */
    private static final int ALPHA_BITS = 8;

    /** Opaque alpha for the {@code TYPE_INT_ARGB} image. */
    private static final int OPAQUE_ARGB = 0xFF000000;

    private RenderPreviewMain()
    {
        // entry point holder
    }

    /**
     * Renders and writes one preview.
     *
     * @param args see the class Javadoc
     * @throws IOException if the model cannot be read or the PNG written
     */
    public static void main(final String[] args) throws IOException
    {
        final String out = option(args, "--out=");
        final int frames = intOption(args, "--frames=", 0);
        if (out == null && frames <= 0)
        {
            LOG.error("Nothing to do: give --out=<path.png>, --frames=<n>, or both.");
            System.exit(EXIT_USAGE);
            return;
        }

        final byte[] image = modelImage(option(args, "--model="));
        final String writeModel = option(args, "--writeModel=");
        if (writeModel != null)
        {
            Files.write(Path.of(writeModel), image);
            LOG.info("Wrote model {} ({} bytes)", writeModel, image.length);
        }

        final int width = intOption(args, "--width=", DEFAULT_WIDTH);
        final int height = intOption(args, "--height=", DEFAULT_HEIGHT);
        final int threads = intOption(args, "--threads=", 0);
        final Rasterizer.CullMode cull = cullOption(args);

        final Pool pool = Pool.open(threads);
        try
        {
            render(image, out, width, height, frames, cull, pool.port(),
                floatOption(args, "--angle=", DEFAULT_ANGLE_DEGREES),
                floatOption(args, "--distance=", DEFAULT_DISTANCE),
                floatOption(args, "--elevation=", DEFAULT_ELEVATION));
        }
        finally
        {
            pool.close();
        }
    }

    // Builds the whole pipeline, renders, times, and writes. Kept as one method
    // because every step depends on the previous one's sizing.
    private static void render(final byte[] image, final String out, final int width,
        final int height, final int frames, final Rasterizer.CullMode cull,
        final I_ThreadPoolPort pool, final float angleDegrees, final float distanceFactor,
        final float elevationFactor) throws IOException
    {
        final I_TimePort time = new DesktopTimePort();
        time.init();

        final ModelFormat model = ModelFormat.read(image);
        final SoftwareRenderPort renderer = new SoftwareRenderPort(pool, time, cull);
        renderer.init();
        renderer.resize(width, height);
        renderer.loadModel(model);
        renderer.setCamera(fixedCamera(model, (float) width / (float) height,
            (float) Math.toRadians(angleDegrees), distanceFactor, elevationFactor));

        renderer.renderFrame(0);
        if (frames > 0)
        {
            measure(renderer, frames, width, height, pool);
        }
        if (out != null)
        {
            writePng(renderer, out, width, height);
        }
        renderer.shutdown();
        time.shutdown();
    }

    // Renders repeatedly and reports the best and the mean. Min-of-N is the
    // figure docs/ASSETS.md section 2 quotes, so both are printed rather than
    // one being chosen here.
    private static void measure(final SoftwareRenderPort renderer, final int frames,
        final int width, final int height, final I_ThreadPoolPort pool)
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
        final long covered = coveredPixels(renderer, width, height);
        // MUTABLE local — the worker count, zero meaning the serial path.
        int workers = 0;
        if (pool != null)
        {
            workers = pool.workerCount();
        }
        LOG.info("Frame time at {}x{}: best {} ms, mean {} ms over {} frames, "
            + "{} workers, {} triangles after clipping",
            width, height,
            String.format("%.3f", best / NANOS_PER_MILLI),
            String.format("%.3f", total / (double) frames / NANOS_PER_MILLI),
            frames, workers, renderer.lastFrameTriangles());
        // Two figures, because they answer different questions. Per screen
        // pixel includes the clear and the empty tiles and is what a frame
        // budget cares about; per covered pixel is the span-loop cost
        // docs/ASSETS.md section 2 quotes in ns/px, and needs the coverage
        // fraction alongside it to be comparable at all.
        LOG.info("Coverage: {} of {} pixels ({}%)", covered, (long) pixels,
            String.format("%.1f", 100.0 * covered / pixels));
        LOG.info("Best frame: {} ns per screen pixel, {} ns per covered pixel",
            String.format("%.2f", best / pixels),
            String.format("%.2f", best / Math.max(1.0, (double) covered)));
    }

    // How many pixels the model actually touched, taken from the finished
    // frame rather than estimated: anything not still the clear colour.
    private static long coveredPixels(final SoftwareRenderPort renderer, final int width,
        final int height)
    {
        final int[] frame = new int[width * height];
        if (!renderer.copyColorInto(frame))
        {
            return 0L;
        }
        // MUTABLE local — the running count.
        long covered = 0L;
        for (final int pixel : frame)
        {
            if (pixel != SoftwareRenderPort.DEFAULT_CLEAR_COLOR)
            {
                covered++;
            }
        }
        return covered;
    }

    // Converts the finished RGBA8888 frame into a PNG.
    //
    // Rgba is 0xRRGGBBAA and BufferedImage.TYPE_INT_ARGB is 0xAARRGGBB, so the
    // conversion is one rotate: shift the alpha off the bottom and put opaque
    // back on the top. Doing it wrong here would produce a plausible but
    // channel-swapped image, which is exactly the class of bug this tool is
    // meant to catch, so it is spelled out rather than hidden in a helper.
    private static void writePng(final SoftwareRenderPort renderer, final String out,
        final int width, final int height) throws IOException
    {
        final int[] frame = new int[width * height];
        if (!renderer.copyColorInto(frame))
        {
            throw new IOException("renderer produced no frame");
        }
        final BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                png.setRGB(x, y, (frame[y * width + x] >>> ALPHA_BITS) | OPAQUE_ARGB);
            }
        }
        final Path target = Path.of(out);
        if (target.getParent() != null)
        {
            Files.createDirectories(target.getParent());
        }
        ImageIO.write(png, "png", target.toFile());
        LOG.info("Wrote {} ({}x{}, cull={})", target.toAbsolutePath(), width, height,
            renderer.cullMode());
    }

    // A fixed camera rather than SoftwareRenderPort's tic-driven orbit, so a
    // preview is reproducible and two cull modes can be compared pixel for
    // pixel.
    private static Camera fixedCamera(final ModelFormat model, final float aspect,
        final float angleRadians, final float distanceFactor, final float elevationFactor)
    {
        final float centreX = (model.minX() + model.maxX()) * 0.5f;
        final float centreY = (model.minY() + model.maxY()) * 0.5f;
        final float centreZ = (model.minZ() + model.maxZ()) * 0.5f;
        final float spanX = model.maxX() - model.minX();
        final float spanY = model.maxY() - model.minY();
        final float spanZ = model.maxZ() - model.minZ();
        // MUTABLE local — bounding radius, floored for a degenerate model.
        float radius = 0.5f * (float) Math.sqrt(spanX * spanX + spanY * spanY + spanZ * spanZ);
        if (!(radius > 0.0f))
        {
            radius = 1.0f;
        }
        final float distance = radius * distanceFactor;
        final Vec3 eye = new Vec3(
            centreX + distance * (float) Math.sin(angleRadians),
            centreY + radius * elevationFactor,
            centreZ + distance * (float) Math.cos(angleRadians));
        return Camera.lookingAt(eye, new Vec3(centreX, centreY, centreZ),
            new Vec3(0.0f, 1.0f, 0.0f), SoftwareRenderPort.DEFAULT_FOV_Y, aspect,
            SoftwareRenderPort.DEFAULT_NEAR);
    }

    // The model file, or the built-in cube when none was named.
    private static byte[] modelImage(final String path) throws IOException
    {
        if (path == null)
        {
            LOG.info("No --model given — drawing the built-in cube");
            return CubeModel.build();
        }
        return Files.readAllBytes(Path.of(path));
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

    private static int intOption(final String[] args, final String prefix, final int fallback)
    {
        final String value = option(args, prefix);
        if (value == null || value.isEmpty())
        {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private static float floatOption(final String[] args, final String prefix,
        final float fallback)
    {
        final String value = option(args, prefix);
        if (value == null || value.isEmpty())
        {
            return fallback;
        }
        return Float.parseFloat(value);
    }

    private static Rasterizer.CullMode cullOption(final String[] args)
    {
        final String value = option(args, "--cull=");
        if (value == null || value.isEmpty())
        {
            return SoftwareRenderPort.BACKFACE_CULL_MODE;
        }
        return Rasterizer.CullMode.valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * A standalone {@code WorkerPool}, or none.
     *
     * The pool is the engine's own — {@code AGENTS.md} rule 1 — which means it
     * needs an event bus and a subsystem registry even though this tool
     * publishes nothing to either. They exist only so {@code submitParallel}
     * has somewhere to live.
     */
    private static final class Pool
    {
        /** The bus the pool drains, or null in the serial case. */
        private final I_EventBusPort bus;

        /** The pool, or null in the serial case. */
        private final I_ThreadPoolPort pool;

        private Pool(final I_EventBusPort eventBus, final I_ThreadPoolPort threadPool)
        {
            this.bus = eventBus;
            this.pool = threadPool;
        }

        static Pool open(final int threads)
        {
            if (threads <= 0)
            {
                return new Pool(null, null);
            }
            final I_EventBusPort eventBus = EventBusFactory.createShared();
            eventBus.init(BUS_CAPACITY);
            final I_ThreadPoolPort threadPool =
                ThreadPoolFactory.createFixed(eventBus, new SubsystemRegistry());
            threadPool.init(threads);
            threadPool.start();
            return new Pool(eventBus, threadPool);
        }

        I_ThreadPoolPort port()
        {
            return pool;
        }

        void close()
        {
            if (pool != null)
            {
                pool.shutdown();
            }
            if (bus != null)
            {
                bus.shutdown();
            }
        }
    }
}
