/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.openfps.engine.core.EngineMain;
import com.openfps.engine.core.EngineSession;
import com.openfps.engine.core.FrameRate;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.hal.adapter.AdapterFactorySelector;
import com.openfps.engine.hal.adapter.HalBackend;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.engine.render.port.I_RenderPort;
import com.openfps.engine.render.port.I_RenderPortFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windowed desktop entry point — {@code gradlew :desktop:run}.
 *
 * The difference from {@code EngineMain.main}, which stays the headless
 * entry point, is three decisions and nothing else:
 *
 *  1. The HAL is {@link GdxAdapterFactory}, so the window port is real and
 *     the engine gives the main thread to LWJGL3 instead of joining the
 *     game loop.
 *  2. The run is {@link GameConfig#unbounded}, because a windowed session
 *     ends when the user closes the window or picks Quit — not after a
 *     fixed tic count.
 *  3. The renderer is the real {@link SoftwareRenderPort} rather than the null
 *     one, and the window is told to present its frames.
 *
 * Everything after that is the standard bootstrap: {@code EngineMain} owns
 * memory, the event bus, subsystems, the worker pool, the game loop and
 * shutdown ordering. This class deliberately reimplements none of it.
 *
 * <b>Why {@code start} / {@code awaitPlatformLoop} / {@code stop} rather than
 * {@code run}.</b> The renderer has to be attached to the window between the
 * bootstrap and the frame loop: it does not exist before {@code start} (it
 * needs the worker pool) and it is too late once {@code runFrameLoop} has
 * built its listener. {@code EngineSession} already documents those three
 * calls as the lifecycle API for every platform; this is the first caller that
 * needs the seam between them.
 *
 * <b>Model.</b> {@code --model=<path>} names a {@code .ofm} file produced by
 * {@code gradlew :tools:convertModels} or by {@code RenderPreviewMain}. Without
 * one the rasterizer has nothing to draw and the window falls back to the menu,
 * which is exactly what it did before Phase 5 was wired.
 *
 * <b>Threading:</b> {@code main} must stay on the process main thread.
 * {@code EngineSession.awaitPlatformLoop} hands it to
 * {@code I_WindowPort.runFrameLoop}, and GLFW requires window calls there.
 */
public final class DesktopLauncher
{
    /** CLI prefix naming the model file to draw. */
    public static final String MODEL_ARG = "--model=";

    private static final Logger LOG = LoggerFactory.getLogger(DesktopLauncher.class);

    private DesktopLauncher()
    {
        // entry point holder
    }

    /**
     * Boots the engine with a real window and blocks until it closes.
     *
     * @param args CLI arguments; {@code --fps=30|60|120} selects the
     *     simulation rate, defaulting to 60, and {@code --model=<path>} names
     *     the {@code .ofm} model to draw
     */
    public static void main(final String[] args)
    {
        final FrameRate rate;
        try
        {
            rate = EngineMain.parseFpsArg(args);
        }
        catch (final IllegalArgumentException e)
        {
            LOG.error("Failed to parse arguments: {}", e.getMessage());
            return;
        }
        LOG.info("OpenFPS desktop launcher: rate={} Hz, java={}",
            rate.fps(), System.getProperty("java.version"));

        final GdxWindowPort window = new GdxWindowPort();
        final GdxAdapterFactory hal = new GdxAdapterFactory(
            AdapterFactorySelector.create(HalBackend.DESKTOP), window);
        final RendererHolder holder = new RendererHolder();

        final EngineSession session = new EngineMain()
            .start(GameConfig.unbounded(rate), hal, holder);
        final SoftwareRenderPort renderer = holder.renderer();
        loadModel(renderer, modelArg(args));
        window.attachRenderer(renderer);

        session.awaitPlatformLoop();
        session.stop();
        LOG.info("OpenFPS desktop launcher exited");
    }

    /**
     * Returns the {@code --model=} argument, or null if none was given.
     *
     * @param args the CLI arguments, may be null
     * @return the model path, or null
     */
    public static String modelArg(final String[] args)
    {
        if (args == null)
        {
            return null;
        }
        for (final String arg : args)
        {
            if (arg != null && arg.startsWith(MODEL_ARG))
            {
                return arg.substring(MODEL_ARG.length());
            }
        }
        return null;
    }

    // Reads the model into the renderer. A missing or malformed file is logged
    // and the run continues with an empty world: a bad path should not cost
    // the user their window.
    private static void loadModel(final SoftwareRenderPort renderer, final String path)
    {
        if (path == null || path.isEmpty())
        {
            LOG.warn("No --model=<path> given — the window will show the menu only. "
                + "Produce one with: gradlew :tools:renderPreview");
            return;
        }
        try
        {
            renderer.loadModel(Files.readAllBytes(Path.of(path)));
            LOG.info("Drawing model {}", path);
        }
        catch (final IOException | RuntimeException e)
        {
            LOG.error("Could not load model {}: {}", path, e.getMessage());
        }
    }

    /**
     * Builds the render port and remembers it.
     *
     * The port cannot be constructed before {@code EngineMain.start} — it needs
     * the worker pool — and the window needs a typed reference to it
     * afterwards. A four-line holder gives both without a downcast and without
     * a mutable field racing the game loop thread.
     */
    private static final class RendererHolder implements I_RenderPortFactory
    {
        /** What the last call built. MUTABLE: assigned once, on the bootstrap thread. */
        private SoftwareRenderPort built;

        @Override
        public I_RenderPort createRenderPort(final I_ThreadPoolPort pool, final I_TimePort time)
        {
            built = new SoftwareRenderPort(pool, time);
            return built;
        }

        /** Returns the port this factory built. */
        SoftwareRenderPort renderer()
        {
            return built;
        }
    }
}
