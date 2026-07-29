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
import com.openfps.engine.hal.adapter.desktop.DesktopDatagramPort;
import com.openfps.engine.net.NetSession;
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

    /**
     * CLI flag that skips the main menu and drops straight into the world.
     *
     * A flag rather than a system property because {@code -Dopenfps.x} on a
     * Gradle command line lands on the daemon and never reaches the forked
     * application, whereas {@code --args} is passed through verbatim. This
     * translates one into the other. See
     * {@link GdxFrameLoopListener#START_IN_GAME_PROPERTY}.
     */
    public static final String START_IN_GAME_ARG = "--start-in-game";

    /**
     * CLI prefix naming this peer's identity and local UDP port, as
     * {@code --net=<playerId>:<port>}.
     *
     * <p>Both halves are needed and neither has a safe default. The id is what
     * every packet is matched on, so two peers sharing one would each drop the
     * other's traffic as coming from themselves; and the port cannot default
     * for two instances on one machine, which is exactly the case anyone
     * testing this will hit first. {@code 0} as a port asks the OS for a free
     * one.</p>
     */
    public static final String NET_ARG = "--net=";

    /**
     * CLI prefix naming a peer to connect to, as
     * {@code --peer=<playerId>@<host>:<port>}. May be given more than once.
     */
    public static final String PEER_ARG = "--peer=";

    /** Exit status used when the demo has no geometry to stand on. */
    public static final int EXIT_NO_ASSETS = 3;

    /** Exit status used when the network arguments cannot be honoured. */
    public static final int EXIT_BAD_NETWORK = 4;

    private static final Logger LOG = LoggerFactory.getLogger(DesktopLauncher.class);

    private DesktopLauncher()
    {
        // entry point holder
    }

    /**
     * Boots the engine with a real window and blocks until it closes.
     *
     * @param args CLI arguments; {@code --fps=30|60|120} selects the
     *     simulation rate, defaulting to 60, {@code --model=<path>} names
     *     the {@code .ofm} model to draw, and {@code --start-in-game} opens
     *     past the menu
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

        // Parsed BEFORE anything is opened, for the same reason the assets are
        // loaded early: a typo in an address should be a console message, not a
        // failure behind a window the user has to close to read.
        final NetArgs netArgs;
        try
        {
            netArgs = NetArgs.parse(args);
        }
        catch (final IllegalArgumentException e)
        {
            LOG.error("Bad network arguments: {}", e.getMessage());
            System.exit(EXIT_BAD_NETWORK);
            return;
        }

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

        if (startInGameArg(args))
        {
            System.setProperty(GdxFrameLoopListener.START_IN_GAME_PROPERTY, "true");
            LOG.info("{}: opening straight into the world, no menu", START_IN_GAME_ARG);
        }

        final GdxWindowPort window = new GdxWindowPort();
        final GdxAdapterFactory hal = new GdxAdapterFactory(
            AdapterFactorySelector.create(HalBackend.DESKTOP), window);
        final RendererHolder holder = new RendererHolder();
        final GameConfig config = GameConfig.unbounded(rate);
        // MUTABLE: assigned once on this thread by the factory below, before
        // the frame loop that reads it starts. There is no race — the engine
        // bootstrap has returned by then.
        final DemoGameplayPort[] gameplay = new DemoGameplayPort[1];

        final EngineSession session = new EngineMain()
            .start(config, hal, holder, input ->
            {
                final I_GameplayPort port = gameplayPort(input, holder, demo, config);
                if (port instanceof DemoGameplayPort)
                {
                    gameplay[0] = (DemoGameplayPort) port;
                }
                return port;
            });
        final SoftwareRenderPort renderer = holder.renderer();
        bindWorld(renderer, demo, explicitModel);
        window.attachRenderer(renderer);
        attachMatchGate(window, gameplay[0]);

        final NetSession netSession;
        try
        {
            netSession = openNetwork(netArgs, gameplay[0], config);
        }
        catch (final RuntimeException e)
        {
            LOG.error("Cannot open the network session: {}", e.getMessage());
            session.stop();
            System.exit(EXIT_BAD_NETWORK);
            return;
        }

        session.awaitPlatformLoop();
        if (netSession != null)
        {
            LOG.info("Network summary: {}", netSession);
            netSession.close();
        }
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
        // The match holds the OTHER bodies. The local player is deliberately
        // absent from it: Hitscan treats a ray origin inside a box as a hit at
        // distance zero, so a shooter listed among its own targets would shoot
        // itself on every trigger pull.
        return new DemoGameplayPort(input, holder.renderer(), demo.spawnController(), config,
            demo.newMatch(), botInstanceIndices(demo), demo.effects());
    }

    /**
     * Opens the network session, if one was asked for, and hands it to the
     * match.
     *
     * <p>Its own socket rather than the HAL's shared one: {@code EngineMain}
     * builds an {@code I_DatagramPort} for the networking subsystem, and two
     * owners of one socket would race over {@code receive()} — each draining
     * packets the other needed. A second {@link DesktopDatagramPort} costs one
     * file descriptor and removes the question entirely.</p>
     *
     * @param netArgs the parsed command line
     * @param gameplay the match to attach the session to, or null when there is
     *     no match to network
     * @param config the running configuration, whose tic duration sizes the
     *     redundancy window
     * @return the open session, or null when networking was not requested
     */
    private static NetSession openNetwork(final NetArgs netArgs,
        final DemoGameplayPort gameplay, final GameConfig config)
    {
        if (!netArgs.isRequested() || gameplay == null)
        {
            return null;
        }
        final NetSession netSession = new NetSession(new DesktopDatagramPort(),
            netArgs.playerId(), config.nanosPerTic());
        netSession.open(netArgs.port());
        for (final NetArgs.Peer peer : netArgs.peers())
        {
            netSession.addPeer(peer.id(), peer.address());
        }
        gameplay.attachNetwork(netSession);
        LOG.info("Multiplayer: {} — the transport carries inputs both ways;"
            + " remote bodies are not simulated into the world yet", netArgs);
        return netSession;
    }

    /**
     * Connects the UI's notion of "in the world" to the match's notion of
     * "running".
     *
     * <p>The game loop starts with the process and ticks whatever is on screen,
     * which is right — the simulation clock must not depend on a menu. But the
     * <b>match</b> must, and until this seam existed it did not: the bots
     * patrolled and fired from the moment the window opened, so a player who
     * read the title screen for ten seconds started already down a fifth of
     * their health. That was visible in the first run of the packaged build as
     * "took 2 damage" scrolling past under the menu.</p>
     *
     * @param window the window whose UI drives the gate; must not be null
     * @param gameplay the match to freeze and unfreeze, or null when there is no
     *     match — the {@code --model=} path has none
     */
    private static void attachMatchGate(final GdxWindowPort window,
        final DemoGameplayPort gameplay)
    {
        if (gameplay == null)
        {
            return;
        }
        window.attachMatchGate(live -> gameplay.setMatchLive(live.booleanValue()));
    }

    // Where each bot's model sits among the scene's world instances, so the
    // gameplay port can move it as the bot walks its patrol.
    private static int[] botInstanceIndices(final DemoScene demo)
    {
        final int[] indices = new int[demo.botCount()];
        for (int index = 0; index < indices.length; index++)
        {
            indices[index] = demo.botInstanceIndex(index);
        }
        return indices;
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
        // Furniture of a first-person game, so the first-person game asks for
        // it. Off by default so :tools:renderPreview can inspect a model and
        // the render tests can assert exact pixels without a reticle through
        // the middle of every frame.
        renderer.setCrosshairEnabled(true);
        LOG.info("First-person demo ready: {} — pick Start Game to enter it, WASD to walk,"
            + " mouse to look, left click to fire, Escape to return to the menu", demo);
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
     * Returns whether {@link #START_IN_GAME_ARG} was given.
     *
     * @param args the CLI arguments, may be null
     * @return true if the run should skip the menu
     */
    public static boolean startInGameArg(final String[] args)
    {
        if (args == null)
        {
            return false;
        }
        for (final String arg : args)
        {
            if (START_IN_GAME_ARG.equals(arg))
            {
                return true;
            }
        }
        return false;
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
     * <p><b>The pool is passed through, and it is now worth passing.</b> An
     * earlier revision handed the renderer {@code null} instead, because the
     * pool genuinely made this scene fifteen times slower. Three faults were
     * responsible, none of them in this class, and all three are fixed:</p>
     *
     * <ul>
     *   <li>{@code SoftwareRenderPort} ran its pipeline once per instance —
     *       about 1,180 {@code submitParallel} barriers a frame for a
     *       295-instance room. It batches a whole pass now, and pays eight.</li>
     *   <li>{@code WorkerPool}'s batch join used a timed park, which on Windows
     *       cannot resolve faster than the 15.6 ms timer period.</li>
     *   <li>{@code RenderSubsystem} rendered every {@code RenderFrameEvent}
     *       {@code GameLoop} published instead of coalescing to the newest, and
     *       {@code FramebufferPresenter} starved against a frame lock the
     *       render workers kept reacquiring. Measured windowed at 1280x720, R_
     *       finished 35 frames a second and the window presented <b>2.9</b>;
     *       it now presents 60, vsync-limited, at 4.3 ms a frame.</li>
     * </ul>
     *
     * <p>Run {@code gradlew :desktop:run -Dopenfps.fpsLog=2} to see all three
     * rates for yourself; {@code FramebufferPresenter} documents them.</p>
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
