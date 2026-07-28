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
import com.openfps.engine.demo.DemoAssetException;
import com.openfps.engine.demo.DemoGameplayPort;
import com.openfps.engine.demo.DemoModels;
import com.openfps.engine.demo.DemoScene;
import com.openfps.engine.gameplay.adapter.NullGameplayPort;
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.engine.hal.adapter.AdapterFactorySelector;
import com.openfps.engine.hal.adapter.HalBackend;
import com.openfps.engine.hal.port.I_InputPort;
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
 * <b>What it draws.</b> By default, the first-person demo: a room assembled by
 * {@link DemoScene} from the models under {@code --assets=<dir>} (default
 * {@code assets/models}), with a blaster held in view space and the camera
 * driven per tic by {@link DemoGameplayPort}. If those models are absent the
 * launcher says so and exits {@link #EXIT_NO_ASSETS} rather than opening a
 * window onto nothing.
 *
 * {@code --model=<path>} overrides that with a single {@code .ofm} file on the
 * default orbit camera — the pre-demo behaviour, kept because it is the
 * quickest way to look at one converted asset in a window.
 *
 * <b>Threading:</b> {@code main} must stay on the process main thread.
 * {@code EngineSession.awaitPlatformLoop} hands it to
 * {@code I_WindowPort.runFrameLoop}, and GLFW requires window calls there.
 */
public final class DesktopLauncher
{
    /** CLI prefix naming the model file to draw. */
    public static final String MODEL_ARG = "--model=";

    /** CLI prefix naming the model root the demo scene loads from. */
    public static final String ASSETS_ARG = "--assets=";

    /** Where the demo looks for its models when {@code --assets=} is not given. */
    public static final String DEFAULT_ASSET_ROOT = "assets/models";

    /** Exit status used when the demo has no geometry to stand on. */
    public static final int EXIT_NO_ASSETS = 3;

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

        // Built BEFORE the window opens, on purpose: missing assets should
        // report and exit at a console, not behind a black GLFW window the
        // user then has to close to read the reason.
        final String explicitModel = modelArg(args);
        final DemoScene demo;
        try
        {
            demo = buildDemo(explicitModel, assetsArg(args));
        }
        catch (final DemoAssetException e)
        {
            LOG.error("Cannot start the first-person demo: {}", e.getMessage());
            System.exit(EXIT_NO_ASSETS);
            return;
        }

        final GdxWindowPort window = new GdxWindowPort();
        final GdxAdapterFactory hal = new GdxAdapterFactory(
            AdapterFactorySelector.create(HalBackend.DESKTOP), window);
        final RendererHolder holder = new RendererHolder();
        final GameConfig config = GameConfig.unbounded(rate);

        final EngineSession session = new EngineMain()
            .start(config, hal, holder, input -> gameplayPort(input, holder, demo, config));
        final SoftwareRenderPort renderer = holder.renderer();
        bindWorld(renderer, demo, explicitModel);
        window.attachRenderer(renderer);

        session.awaitPlatformLoop();
        session.stop();
        LOG.info("OpenFPS desktop launcher exited");
    }

    /**
     * Loads and assembles the demo world, or nothing when a single model was
     * named explicitly.
     *
     * @param explicitModel the {@code --model=} argument, or null
     * @param assetRoot where the demo's models live
     * @return the assembled demo, or null when {@code --model=} was given
     * @throws DemoAssetException if the demo has no geometry to stand on
     */
    private static DemoScene buildDemo(final String explicitModel, final String assetRoot)
    {
        if (explicitModel != null && !explicitModel.isEmpty())
        {
            LOG.info("--model given — drawing that one model instead of the demo room");
            return null;
        }
        return DemoScene.build(DemoModels.load(Path.of(assetRoot)));
    }

    // The demo's per-tic loop, or the do-nothing port when a single model was
    // named. The renderer is read from the holder rather than passed in: the
    // bootstrap builds the render port before it calls this, which is the
    // ordering contract I_GameplayPortFactory documents.
    private static I_GameplayPort gameplayPort(final I_InputPort input,
        final RendererHolder holder, final DemoScene demo, final GameConfig config)
    {
        if (demo == null)
        {
            return new NullGameplayPort();
        }
        return new DemoGameplayPort(input, holder.renderer(), demo.spawnController(), config);
    }

    // Hands the renderer the scene it will draw for the rest of the run. Built
    // once here, never per frame — Scene is immutable and nothing in this demo
    // moves except the camera.
    private static void bindWorld(final SoftwareRenderPort renderer, final DemoScene demo,
        final String explicitModel)
    {
        if (demo == null)
        {
            loadModel(renderer, explicitModel);
            return;
        }
        renderer.setScene(demo.scene());
        LOG.info("First-person demo ready: {} — click the window to capture the mouse,"
            + " WASD to walk, Escape to release", demo);
    }

    /**
     * Returns the {@code --assets=} argument, or the default model root.
     *
     * @param args the CLI arguments, may be null
     * @return the directory the demo loads its models from, never null
     */
    public static String assetsArg(final String[] args)
    {
        final String value = valueOf(args, ASSETS_ARG);
        if (value == null || value.isEmpty())
        {
            return DEFAULT_ASSET_ROOT;
        }
        return value;
    }

    /**
     * Returns the {@code --model=} argument, or null if none was given.
     *
     * @param args the CLI arguments, may be null
     * @return the model path, or null
     */
    public static String modelArg(final String[] args)
    {
        return valueOf(args, MODEL_ARG);
    }

    // The value of the first argument carrying a given prefix, or null.
    private static String valueOf(final String[] args, final String prefix)
    {
        if (args == null)
        {
            return null;
        }
        for (final String arg : args)
        {
            if (arg != null && arg.startsWith(prefix))
            {
                return arg.substring(prefix.length());
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
            LOG.warn("No --model=<path> given — the window will show the menu only.");
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
     *
     * <p><b>The pool is passed through unchanged, and a note for whoever
     * profiles this next.</b> An earlier revision of this class handed the
     * renderer {@code null} instead, on the strength of a headless measurement:
     * {@code :tools:demoPreview --frames=300} at 1280x720 renders this scene in
     * 19.6 ms serially and <b>297 ms with 8 workers</b>, because
     * {@code SoftwareRenderPort} renders instances one at a time and each
     * crosses four {@code submitParallel} barriers — about 1,180 of them per
     * frame for a 295-instance room. That finding is real and reproducible.</p>
     *
     * <p>It is also <b>not</b> what limits the window, which is why the change
     * was reverted rather than kept. Measured windowed, both settings present
     * at roughly 2 frames per second: 2.5 with the pool, 2.0 without. The
     * limiter is elsewhere — {@code GameLoop} publishes a {@code
     * RenderFrameEvent} every tic with no coalescing, so the rasterizer spends
     * every cycle on frames nobody will see, and {@code FramebufferPresenter}
     * then starves against a non-fair {@code frameLock} that the render workers
     * keep reacquiring. Both belong to D_ and R_, not to a launcher, and
     * neither is fixed by choosing a pool here.</p>
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
