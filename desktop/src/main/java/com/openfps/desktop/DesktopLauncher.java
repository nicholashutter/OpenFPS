/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.openfps.engine.core.EngineMain;
import com.openfps.engine.core.EngineSession;
import com.openfps.engine.core.FrameRate;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.demo.DemoAssetException;
import com.openfps.engine.demo.DemoGameplayPort;
import com.openfps.engine.demo.DemoModels;
import com.openfps.engine.demo.DemoScene;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchSummary;
import com.openfps.engine.gameplay.adapter.NullGameplayPort;
import com.openfps.engine.gameplay.map.MapLibrary;
import com.openfps.engine.gameplay.map.MapRuntime;
import com.openfps.engine.gameplay.map.MapSpec;
import com.openfps.engine.gameplay.map.Team;
import com.openfps.engine.gameplay.port.DelegatingGameplayPort;
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.gdx.AccessibilitySettings;
import com.openfps.gdx.DebugSettings;
import com.openfps.gdx.MapSelection;
import com.openfps.gdx.MapSelectionScreen;
import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.adapter.AdapterFactorySelector;
import com.openfps.engine.hal.adapter.desktop.DesktopDatagramPort;
import com.openfps.engine.net.NetSession;
import com.openfps.engine.hal.adapter.HalBackend;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.PlayerSettings;
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

    /**
     * CLI prefix naming the map id to load, as {@code --map=<id>}.
     *
     * <p>When a map id is given, the launcher builds a {@link
     * com.openfps.engine.gameplay.map.MapScene} from the spec and
     * uses that as the rendered world. The legacy demo scene is
     * bypassed — the per-tic gameplay port still runs (so the
     * mode-specific rule logic ticks), but it is the map scene
     * the renderer draws.</p>
     */
    public static final String MAP_ARG = "--map=";

    /** CLI prefix naming the model root the demo scene loads from. */
    public static final String ASSETS_ARG = "--assets=";

    /**
     * CLI prefix naming the player settings file. The launcher reads the file
     * at startup, parses it with {@link PlayerSettings#fromSpec}, and hands
     * the result to the input port. The file's grammar is documented on
     * {@link PlayerSettings#toSpec}.
     */
    public static final String SETTINGS_ARG = "--settings=";

    /**
     * Default location of the player settings file, relative to the user's
     * home directory. Used when {@code --settings=} is not given AND the
     * file exists. Missing file is not an error — the launcher falls back to
     * {@link PlayerSettings#defaults()} so a fresh install still runs.
     */
    public static final String DEFAULT_SETTINGS_FILENAME = ".openfps" + java.io.File.separator + "settings.txt";

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
        // Install the SLF4J-to-log-bus bridge before the first log call.
        // Every existing LOG.info / LOG.warn site stays as-is, but its
        // event is now also published to the engine's main bus for any
        // consumer subscribed there (a file writer, a debug overlay,
        // anything else). Idempotent: a re-install is a no-op, so the
        // engine subsystem and the desktop launcher can both call it.
        com.openfps.engine.log.LogbackBridgeBootstrap.install();

        com.openfps.engine.log.LogBusFactory.startDrainTask();

        // Install the on-disk log sink so every engine log line
        // also lands in logs/openfps-<timestamp>.log next to
        // settings.gradle.kts. The install is a no-op when
        // -Dopenfps.log.file=off or OPENFPS_LOG_FILE=off is set;
        // see LogSinkPaths for the resolution rules.
        com.openfps.engine.log.LogBusFactory.installDefaultFileSink();

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

        final String bootMapId = mapArg(args);

        // The demo is no longer the default for a no-args run. The
        // previous version keyed off "no --map=" — which is also
        // the menu-driven run's signature — and built the demo as a
        // side effect, hardcoding the demo as the default for every
        // no-args run. The menu flow then picked a real map, but
        // the demo was still what the renderer was bound to, and
        // the user saw the demo instead of the picked map. The
        // demo is now opt-in: it is built only when --model= is
        // explicitly given. A no-args run is a menu-driven run,
        // and the menu is the first thing the player sees; a
        // --map= run boots directly into that map (with or
        // without --start-in-game); a --model= run draws that
        // single model.
        final boolean hasExplicitModel = explicitModel != null && !explicitModel.isEmpty();

        final boolean hasBootMap = bootMapId != null && !bootMapId.isEmpty();

        if (hasExplicitModel && hasBootMap)
        {
            LOG.warn("--map={} and --model={} are both given; --model= takes precedence"
                + " (draws that one model) — drop --model= for menu-driven map loading",
                bootMapId, explicitModel);
        }

        if (hasBootMap)
        {
            LOG.info("--map={} given — the map is the world; the demo is not built", bootMapId);
        }
        else if (hasExplicitModel)
        {
            LOG.info("--model={} given — drawing that one model; the menu is the first thing", explicitModel);
        }
        else
        {
            LOG.info("No --map= and no --model= — the menu is the first thing the player sees");
        }

        // Built BEFORE the window opens, on purpose: missing assets
        // should report and exit at a console, not behind a black
        // GLFW window the user then has to close to read the
        // reason. Only built when the user actually wants the demo
        // room; otherwise the renderer is left scene-less and the
        // menu is the first thing.
        final DemoScene demo;

        try
        {
            demo = buildDemoIfRequested(explicitModel, assetsArg(args));
        }
        catch (final DemoAssetException e)
        {
            LOG.error("Cannot start the first-person demo: {}", e.getMessage());

            System.exit(EXIT_NO_ASSETS);

            return;
        }

        // The map runtime's populated scene path (MapScene.build(spec, models))
        // needs the kit, the bot characters, the bot carbine and the player's
        // viewmodel. Without these, a --map= boot or any menu pick lands the
        // player on the level .ofm with no walls, no arms, no viewmodel and
        // no bots - the level-only scene has 1 world instance and 0 view
        // instances, which is exactly the broken state the user reports.
        // The 7-arg MapRuntime constructor takes a non-null DemoModels; the
        // 6-arg constructor is the headless-test path that the smoke tests
        // use and is not the right one for a windowed run.
        final String assetRoot = assetsArg(args);

        final com.openfps.engine.demo.DemoModels mapModels;

        try
        {
            mapModels = com.openfps.engine.demo.DemoModels.load(java.nio.file.Path.of(assetRoot));
        }
        catch (final com.openfps.engine.demo.DemoAssetException e)
        {
            LOG.error("Cannot start the map-mode runtime: {}", e.getMessage());

            System.exit(EXIT_NO_ASSETS);

            return;
        }

        if (startInGameArg(args))
        {
            System.setProperty(GdxFrameLoopListener.START_IN_GAME_PROPERTY, "true");

            LOG.info("{}: opening straight into the world, no menu", START_IN_GAME_ARG);
        }

        final GdxWindowPort window = new GdxWindowPort();

        // Built here rather than inside the window, because the launcher is the
        // only object that can see both the settings screen's switch and the
        // renderer it also drives. That is the composition root's job.
        final DebugSettings debug = new DebugSettings();

        // Separate from the debug switch, and separately defaulted — the outline
        // is a standard feature for players who need it and starts on, while the
        // frame counter is a diagnostic and starts off. One boolean could not be
        // both, which is how the toggle came to disagree with the game.
        final AccessibilitySettings access = new AccessibilitySettings();

        final GdxAdapterFactory hal = new GdxAdapterFactory(
            AdapterFactorySelector.create(HalBackend.DESKTOP), window);

        final RendererHolder holder = new RendererHolder();

        final GameConfig config = GameConfig.unbounded(rate);

        // A gamepad's look stick reports a RATE — "keep turning at this speed" —
        // and a rate is not an angle until something supplies the duration. The
        // launcher is the only object that knows the configured frame rate, so
        // the launcher is who says. Without this the pad would still work, at
        // 60 Hz sensitivity regardless of the real rate: at --fps=120 every turn
        // would be half as fast as intended and at 30 twice. Nothing else in the
        // input path cares, because a mouse delta is a displacement that has
        // already happened.
        hal.inputPort().setTicRate(rate.fps());

        // Player settings — the rebind table, mouse sensitivity, and
        // invert-Y flag — live in a text file the launcher reads at
        // startup. The file's grammar is documented on
        // PlayerSettings.toSpec; a missing file falls back to defaults
        // rather than aborting the run, because a fresh install has no
        // file to read and the player's first run should not be a
        // crash.
        final PlayerSettings settings = loadPlayerSettings(args);

        hal.inputPort().bindSettings(settings);

        // Hand the rebind screen the same object the input port
        // now reads from, and a sink that writes the same file
        // loadPlayerSettings would have read. The screen mutates
        // the settings in place on every rebind; the input port
        // and the file see the change on Save. Without this wire
        // the menu's "Controls" entry would draw a screen that
        // has no idea what the engine's bindings are.
        window.attachPlayerSettings(settings, savePlayerSettingsSink(args));

        // The player's team on a multiplayer map is derived from the net
        // id (one peer per team, alternating), so the player on a 1-arg
        // --net= with no peers lands on RED. Single-player runs leave
        // this at NEUTRAL.
        final com.openfps.engine.gameplay.map.Team playerTeam =
            teamForPlayer(netArgs.localSpawnId());

        // MUTABLE: assigned once on this thread by the factory below, before
        // the frame loop that reads it starts. There is no race — the engine
        // bootstrap has returned by then.
        final DemoGameplayPort[] gameplay = new DemoGameplayPort[1];

        // The swappable port the engine ticks. The same instance is
        // returned by the factory every time, so the engine's
        // GameplaySubsystem ticks one port for the life of the run;
        // the runtime swaps the port that delegates to.
        final DelegatingGameplayPort delegatingPort = new DelegatingGameplayPort();

        // The runtime that owns the current map (if any). A menu
        // pick calls mapRuntime.loadMap; a return-to-menu calls
        // mapRuntime.unload. The runtime holds the renderer and the
        // swappable port, which is the only place either of them
        // gets touched from outside the engine.
        final MapRuntime mapRuntime;

        final EngineSession session = new EngineMain()
            .start(config, hal, holder, input ->
            {
                // The factory returns the swappable port. The
                // engine's GameplaySubsystem wraps it once and ticks
                // it for the life of the run; the actual port the
                // engine ticks is whatever the runtime has set
                // (NullGameplayPort by default, a MapGameplayPort
                // after a loadMap).
                if (demo != null)
                {
                    final I_GameplayPort port = gameplayPort(input, holder, demo, config,
                        netArgs.localSpawnId());

                    if (port instanceof DemoGameplayPort)
                    {
                        gameplay[0] = (DemoGameplayPort) port;
                    }

                    return delegatingPort;
                }

                return delegatingPort;
            });

        final SoftwareRenderPort renderer = holder.renderer();

        // Build the map runtime only when a map is in play at boot
        // (the --map=ID path) or any menu pick can drive it (the no
        // --map= path). The runtime is bound to the renderer, the
        // engine's input port, the running config, and the swappable
        // port the engine ticks — the only place the four come
        // together.
        mapRuntime = new MapRuntime(renderer, hal.getInputPort(), config, playerTeam,
            mapSpawnIndexFor(netArgs.localSpawnId(), playerTeam), delegatingPort, mapModels);

        window.attachRenderer(renderer);

        if (demo != null)
        {
            // Demo mode keeps its old wiring: the demo scene is bound
            // once, the demo-specific UI hooks (local body, viewmodel)
            // are attached, the match gate is the demo port's. The
            // map runtime is not used.
            bindWorld(renderer, demo, explicitModel);

            attachMatchGate(window, gameplay[0]);

            attachAudio(hal, gameplay[0]);

            attachLocalBody(demo, gameplay[0]);

            attachMatchResult(window, gameplay[0]);

            attachMatchRestart(window, gameplay[0]);

            attachMatchStatus(window, gameplay[0], rate);
        }
        else if (hasBootMap)
        {
            // Boot with --map=ID: build the map now, then run the
            // same wiring the menu path will use. After bootstrap,
            // --start-in-game (already set above) will move the
            // state machine to PLAYING and the match gate will
            // un-freeze the port.
            mapRuntime.loadMap(bootMapId);

            bindAndAttachMap(window, hal, mapRuntime, rate);
        }
        else if (hasExplicitModel)
        {
            // --model= given: draw that one model. The renderer is
            // bound directly to the .ofm; the menu is the first
            // thing the user sees, the model is what the menu is
            // over (same shape as the original --model= path). The
            // map runtime is empty; a menu pick swaps to a map.
            bindWorld(renderer, null, explicitModel);

            bindAndAttachMap(window, hal, mapRuntime, rate);

            window.setLoadMapCallback(loadMapCallback(window, hal, mapRuntime, rate));
        }
        else
        {
            // No --map= and no --model=. The runtime starts empty,
            // the menu is the first thing, and no scene is bound
            // to the renderer. A menu pick builds the map; a
            // return-to-menu tears it down.
            bindAndAttachMap(window, hal, mapRuntime, rate);

            // The picker's hook: when the menu fires, this is the
            // function that builds the map and re-attaches the UI
            // hooks (match gate, result, status, restart) against
            // the new port. Done before openLoading, so by the
            // time the loading screen's onReady fires startGame,
            // the gate is in place and the new port is live. The
            // contract — good id re-attaches the match gate, bad id
            // leaves the window alone — is pinned by
            // DesktopLauncherLoadMapCallbackTest.
            window.setLoadMapCallback(loadMapCallback(window, hal, mapRuntime, rate));
        }

        attachDebugSettings(window, debug);

        attachAccessibilitySettings(window, access, renderer);

        attachMapSelection(window, args);

        final NetSession netSession;

        try
        {
            // Networking drives the demo's port in demo mode and the
            // map runtime's current port in map mode. The map port
            // is read through the runtime, not captured, so a swap
            // re-targets whatever the runtime now holds. A future
            // pass tears down and re-opens the net session on swap;
            // for now the net session is locked to the boot port.
            if (demo != null)
            {
                netSession = openNetwork(netArgs, gameplay[0], config, demo);
            }
            else
            {
                final com.openfps.engine.gameplay.map.MapGameplayPort currentMapPort =
                    mapRuntime.mapPort();

                if (currentMapPort != null)
                {
                    netSession = openNetworkMap(netArgs, currentMapPort, config,
                        mapRuntime.scene());
                }
                else
                {
                    // Boot with no --map= and no demo: the net
                    // session would have nothing to drive. The
                    // menu-driven flow will pick a map later, but
                    // a single-process net session cannot be
                    // pre-opened for it. Refuse the net args.
                    if (netArgs.isRequested())
                    {
                        LOG.error("Networking was requested (--net=...) but no map is loaded;"
                            + " open the menu, pick a map, then start a session.");

                        session.stop();

                        System.exit(EXIT_BAD_NETWORK);

                        return;
                    }

                    netSession = null;
                }
            }
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

            // The other half of the summary, and the half that says whether the
            // match was playable rather than merely connected: a session can report
            // perfect traffic while every peer body sat still, which is precisely
            // the failure that went unnoticed for as long as it did.
            // Demo mode reports the local player's final placement beside the
            // remote body summary; map mode does the same, minus the
            // remote-body summary this first pass does not yet simulate.
            if (demo != null && gameplay[0] != null && gameplay[0].remoteBodies() != null)
            {
                // The local player's own final placement, printed beside the peer
                // bodies so the two can be compared directly. This is the whole
                // lockstep claim in one line: whatever this peer says its own
                // position is, the OTHER peer's log must show the same numbers for
                // this player's body. A divergence here is a desync, and there is
                // currently nothing else that would report one.
                LOG.info("Local player {} finished at ({}, {}, {}) yaw {}",
                    netArgs.playerId(), gameplay[0].controller().positionX(),
                    gameplay[0].controller().positionY(),
                    gameplay[0].controller().positionZ(),
                    gameplay[0].controller().yawRadians());

                LOG.info("Remote body summary: {}", gameplay[0].remoteBodies());
            }
            else if (mapRuntime.hasMap() && mapRuntime.mapPort() != null)
            {
                // Map mode: print the local player's final placement. The
                // peer's body is not yet simulated into the map scene, so
                // the second line on the demo path has no map analogue
                // yet — the lockstep claim is the two peers' position
                // values being equal, which the OTHER peer's log will
                // show for this player.
                final com.openfps.engine.gameplay.map.MapGameplayPort port =
                    mapRuntime.mapPort();

                LOG.info("Local player {} finished at ({}, {}, {}) yaw {} (map={})",
                    netArgs.playerId(), port.controller().positionX(),
                    port.controller().positionY(), port.controller().positionZ(),
                    port.controller().yawRadians(), mapRuntime.spec().id());
            }

            netSession.close();
        }

        session.stop();

        LOG.info("OpenFPS desktop launcher exited");
    }

    /**
     * Builds the load-map consumer the menu's pick screen drives.
     *
     * <p>The window's match gate and the map-selection screen are
     * wired separately; the menu pick fires this consumer with the
     * chosen id. A good id causes the runtime to swap to that map
     * and the match hooks to be re-attached. A bad id is logged and
     * left alone — no hooks are re-attached, and the menu state
     * machine carries the user to a "could not load" path the menu
     * knows how to render. The runtime's previous map is released by
     * the time the bad id is reported, because
     * {@link MapRuntime#loadMap} unloads before it tries to build.</p>
     *
     * <p>Extracted to a factory so a unit test can pin the
     * "a good id re-attaches the match gate, a bad id leaves the
     * window alone" contract. The August 2026 bug was the
     * {@code --map=} boot path forgetting to call
     * {@code setLoadMapCallback} at all; pinning this contract is
     * what stops that regression from sneaking back in.</p>
     *
     * @param window the window whose match hooks the wiring touches;
     *     must not be null
     * @param hal the platform HAL the audio port comes from; may be
     *     null, because the bad-id branch returns before
     *     {@code hal} is dereferenced
     * @param runtime the map runtime to drive; must not be null
     * @param rate the configured frame rate; must not be null
     * @return a consumer the menu pick screen drives; never null
     */
    public static Consumer<String> loadMapCallback(final GdxWindowPort window,
        final GdxAdapterFactory hal, final MapRuntime runtime, final FrameRate rate)
    {
        if (window == null)
        {
            throw new IllegalArgumentException("window must not be null");
        }

        if (runtime == null)
        {
            throw new IllegalArgumentException("runtime must not be null");
        }

        if (rate == null)
        {
            throw new IllegalArgumentException("rate must not be null");
        }

        return id ->
        {
            if (id == null)
            {
                throw new IllegalArgumentException("id must not be null");
            }

            final MapSpec loaded = runtime.loadMap(id);

            if (loaded == null)
            {
                LOG.error("Map pick '{}' did not load — staying in the menu", id);

                return;
            }

            bindAndAttachMap(window, hal, runtime, rate);
        };
    }

    /**
     * Builds the match-gate consumer the window's listener drives.
     *
     * <p>The window's match-gate hook fires every time the UI's
     * {@code isPlaying()} state changes &mdash; <b>and once on attach</b>
     * with whatever {@code isPlaying()} reports at that instant. The bug
     * the original wiring had: on a fresh menu pick the user is on the
     * LOADING screen, {@code isPlaying()} is {@code false}, and the
     * pre-fix lambda treated that initial {@code false} as a
     * return-to-menu and tore the freshly loaded map down at 0 tics.
     * The user clicked a map, the map built, the map was torn down, the
     * user landed back at the menu &mdash; the visible symptom was "the
     * game crashes when I click a map" but really "the map never
     * reaches PLAYING".</p>
     *
     * <p>The fix is the {@code wasPlaying} closure variable: the
     * teardown only fires on a real {@code true -> false} transition,
     * never on the initial {@code false} nor on a {@code false -> false}
     * burst (which the render loop produces when the listener sits on
     * LOADING for a few frames) nor on a {@code true -> true} burst
     * (which the render loop produces during PLAYING). The contract:</p>
     *
     * <ul>
     *   <li>no map loaded &rarr; every fire is a no-op (no port to
     *       freeze, no map to tear down);</li>
     *   <li>initial {@code false} on a fresh load &rarr; observed,
     *       remembered, no teardown;</li>
     *   <li>{@code false -> true} &rarr; freeze released, no teardown;</li>
     *   <li>{@code true -> false} &rarr; freeze re-applied AND the
     *       {@code onReturnToMenu} callback fires (the production
     *       callback is the log + {@code runtime.unload()} +
     *       {@code window.detachMatchHooks()} triple);</li>
     *   <li>{@code true -> true} or {@code false -> false} &rarr; no
     *       teardown, no callback.</li>
     * </ul>
     *
     * <p>The port is read through the runtime on every fire, so a later
     * {@code runtime.loadMap} that swaps the port is picked up by the
     * next gate fire without re-wiring. That is the same shape
     * {@link #attachMatchGateMap} uses for the map side.</p>
     *
     * @param runtime the map runtime to drive; must not be null
     * @param onReturnToMenu the action to take on a real
     *     {@code true -> false} transition with a map loaded; must not
     *     be null. Production wires this to the log + unload + detach
     *     triple.
     * @return a consumer the window's match-gate fires; never null
     */
    public static Consumer<Boolean> createMatchGate(final MapRuntime runtime,
        final Runnable onReturnToMenu)
    {
        if (runtime == null)
        {
            throw new IllegalArgumentException("runtime must not be null");
        }

        if (onReturnToMenu == null)
        {
            throw new IllegalArgumentException("onReturnToMenu must not be null");
        }

        // MUTABLE: tracked across gate fires. The bug was that the
        // initial fire (with `false` while the listener sat on the
        // loading screen) was treated as a transition. Initial state
        // is `false` because the listener is not yet playing, so the
        // first observed `false` is an observation, not a transition.
        final boolean[] wasPlaying = {false};

        return playing ->
        {
            if (playing == null)
            {
                throw new IllegalArgumentException("playing must not be null");
            }

            final com.openfps.engine.gameplay.map.MapGameplayPort livePort = runtime.mapPort();

            if (livePort != null)
            {
                livePort.setMatchLive(playing.booleanValue());
            }

            // A real return-to-menu: we were playing, we are no longer
            // playing, and a map is still loaded. Anything else is an
            // observation we should remember but not act on.
            if (!playing.booleanValue() && wasPlaying[0] && runtime.hasMap())
            {
                onReturnToMenu.run();

                wasPlaying[0] = false;

                return;
            }

            if (playing.booleanValue())
            {
                wasPlaying[0] = true;
            }
        };
    }

    /**
     * Wires every match-related UI hook the window has against the
     * runtime's current port. Called both at boot (after a {@code --map=}
     * build) and on every menu pick (after {@code MapRuntime.loadMap}),
     * so the wiring path is identical in both cases.
     *
     * <p>The match gate reads the runtime's port through the closure
     * rather than capturing a port reference, so a later
     * {@code mapRuntime.loadMap} that swaps the port is picked up by
     * the next gate fire. The {@code false} branch additionally calls
     * {@code mapRuntime.unload} and {@code window.detachMatchHooks}, so
     * a return-to-menu tears the map down cleanly. The match-gate
     * teardown rule is owned by {@link #createMatchGate} so a unit
     * test can pin the {@code wasPlaying} contract that the old
     * inline lambda got wrong.</p>
     *
     * @param window the window that owns the match hooks
     * @param hal the platform HAL the audio port comes from
     * @param runtime the map runtime; must not be null
     * @param rate the configured frame rate; must not be null
     */
    private static void bindAndAttachMap(final GdxWindowPort window, final GdxAdapterFactory hal,
        final MapRuntime runtime, final FrameRate rate)
    {
        final com.openfps.engine.gameplay.map.MapGameplayPort currentPort = runtime.mapPort();

        if (currentPort == null)
        {
            LOG.warn("bindAndAttachMap: no map loaded; skipping UI hook attach");

            return;
        }

        // The match gate is the seam that freezes/unfreezes the match
        // when the menu is in front, and the seam that tears the map
        // down on a return-to-menu. The factory owns the wasPlaying
        // contract; the inline callback here owns the production side
        // effects (log, runtime.unload, window.detachMatchHooks).
        window.attachMatchGate(createMatchGate(runtime, () ->
        {
            if (runtime.hasMap())
            {
                LOG.info("Returning to menu: tearing down map '{}'", runtime.spec().id());
            }

            runtime.unload();

            window.detachMatchHooks();
        }));

        attachAudioMap(hal, currentPort);

        attachMatchResultMap(window, currentPort);

        attachMatchRestartMap(window, currentPort);

        attachMatchStatusMap(window, currentPort, rate);

        LOG.info("bindAndAttachMap: wired UI hooks against map '{}' (port live={})",
            runtime.spec().id(), currentPort.isMatchLive());
    }

    /**
     * Returns the demo world when the launcher was asked to build
     * one, or null otherwise.
     *
     * <p>The previous version of the launcher built the demo room
     * by default for any no-{@code --map=} run, which made the
     * demo the implicit "first thing" the user saw and broke the
     * menu-driven map load (the user picked a map; the demo was
     * still what the renderer was bound to). The demo room is
     * now opt-in: this method returns null unless the caller
     * explicitly asks for it. A future "Try the demo" menu
     * option can call {@code DemoScene.build} directly without
     * going through this method, and the demo room is still
     * available as a {@link DemoScene} for tests and one-off
     * tooling.</p>
     *
     * @param explicitModel the {@code --model=} argument, or null
     * @param assetRoot where the demo's models live; currently
     *     unused but kept on the signature for the future
     *     "Try the demo" menu option that will need it
     * @return null; the demo room is no longer the default
     * @throws DemoAssetException never thrown by this method;
     *     kept on the signature so a future caller can build
     *     the demo room and surface the same failure mode
     */
    static DemoScene buildDemoIfRequested(final String explicitModel, final String assetRoot)
    {
        if (explicitModel != null && !explicitModel.isEmpty())
        {
            LOG.info("--model={} given — drawing that one model; the demo room is not built",
                explicitModel);
        }
        else
        {
            LOG.info("No --map= and no --model= — the menu is the first thing; the demo room is not built");
        }

        return null;
    }

    /**
     * Builds the demo room from the assets directory. Kept for
     * tests and the future "Try the demo" menu option; the
     * launcher's main path no longer calls it.
     *
     * @param assetRoot where the demo's models live
     * @return the assembled demo, never null
     * @throws DemoAssetException if the demo has no geometry to stand on
     */
    private static DemoScene buildDemoRoom(final String assetRoot)
    {
        return DemoScene.build(DemoModels.load(Path.of(assetRoot)));
    }

    // The demo's per-tic loop, or the do-nothing port when a single model was
    // named. The renderer is read from the holder rather than passed in: the
    // bootstrap builds the render port before it calls this, which is the
    // ordering contract I_GameplayPortFactory documents.
    private static I_GameplayPort gameplayPort(final I_InputPort input,
        final RendererHolder holder, final DemoScene demo, final GameConfig config,
        final int localSpawnId)
    {
        if (demo == null)
        {
            return new NullGameplayPort();
        }

        // The match holds the OTHER bodies. The local player is deliberately
        // absent from it: Hitscan treats a ray origin inside a box as a hit at
        // distance zero, so a shooter listed among its own targets would shoot
        // itself on every trigger pull.
        // Standing on the spawn this player's id owns, not on the canonical one.
        // Two peers on one spawn are each at the other's eye, where the near plane
        // clips the body away — so a two-peer match would open with both players
        // apparently alone. A single-player run passes 0 and is placed exactly
        // where it always was.
        return new DemoGameplayPort(input, holder.renderer(),
            demo.spawnControllerFor(localSpawnId), config,
            demo.newMatch(), botInstanceIndices(demo), demo.effects(),
            botWeaponInstanceIndices(demo));
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
     * @param demo the assembled world, whose pre-placed peer bodies the session
     *     drives, or null when there is no demo scene
     * @return the open session, or null when networking was not requested
     */
    private static NetSession openNetwork(final NetArgs netArgs,
        final DemoGameplayPort gameplay, final GameConfig config, final DemoScene demo)
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

        // The bodies, without which the session is a perfectly working transport
        // between two empty rooms. Attached only on the networked path: a
        // single-player run has nobody to put in them, and the pool costs nothing
        // while it stays hidden.
        if (demo != null)
        {
            gameplay.attachRemoteBodies(demo.remotePlayers());
        }

        LOG.info("Multiplayer: {} — inputs both ways, and each peer is replayed"
            + " into a body of its own", netArgs);

        return netSession;
    }

    /**
     * Opens the network session for a map-mode run, and attaches it to the
     * map port.
     *
     * <p>The map port has the same network attach shape as the demo port
     * (the same {@link NetSession#recordLocalCommand} / {@code tick}
     * path) but it does not yet have visible peer bodies in the map scene
     * — the peer's commands are on the wire, the local player's inputs
     * are sent, and the lockstep claim is exercised end-to-end. The
     * remote-body visual layer is the next pass; this method's
     * responsibilities stop at the wire.</p>
     *
     * @param netArgs the parsed command line
     * @param port the map port to attach the session to; null returns null
     * @param config the running configuration
     * @param mapScene the map scene, for logging
     * @return the open session, or null when networking was not requested
     */
    private static NetSession openNetworkMap(final NetArgs netArgs,
        final com.openfps.engine.gameplay.map.MapGameplayPort port,
        final GameConfig config,
        final com.openfps.engine.gameplay.map.MapScene mapScene)
    {
        if (!netArgs.isRequested() || port == null)
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

        port.attachNetwork(netSession);

        LOG.info("Map multiplayer: {} on map={} — inputs both ways, each peer runs the"
            + " same spec match. Peer bodies in the map scene are a follow-up; the"
            + " lockstep claim is on the wire.", netArgs, mapScene.spec().id());

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

    /**
     * Lets the UI find out that the round has been decided.
     *
     * <p>The other direction of {@link #attachMatchGate}. Until this existed the
     * match simply stopped: {@code Match} decided the round on some tic,
     * {@code DemoGameplayPort} logged one line, the bots went still, and the
     * player was left standing in a room that had quietly finished without
     * telling them. Winning and dying looked identical from inside the
     * window.</p>
     *
     * @param window the window whose UI shows the result; must not be null
     * @param gameplay the match to read, or null when there is no match — the
     *     {@code --model=} path has none
     */
    private static void attachMatchResult(final GdxWindowPort window,
        final DemoGameplayPort gameplay)
    {
        if (gameplay == null)
        {
            return;
        }

        window.attachMatchResult(() -> finishedMatch(gameplay));
    }

    /**
     * Lets the end screen's Play Again button put the world back.
     *
     * <p>The third leg beside {@link #attachMatchGate} and
     * {@link #attachMatchResult}, and the one that turns a summary into a
     * rematch. Until it existed the only way out of a finished round was the
     * menu, and pressing Single Player from there led back into the room the
     * player had already cleared — which is why {@code UiState} refused a
     * {@code GAME_OVER -> PLAYING} edge at all.</p>
     *
     * <p>{@code restartMatch} takes the gameplay port's tic lock, which is what
     * makes it safe to call from the render thread's Scene2D click handler while
     * the game loop thread is mid-tic.</p>
     *
     * @param window the window whose end screen offers the rematch; must not be
     *     null
     * @param gameplay the match to restore, or null when there is no match — the
     *     {@code --model=} path has none, and the button is then not offered
     */
    private static void attachMatchRestart(final GdxWindowPort window,
        final DemoGameplayPort gameplay)
    {
        if (gameplay == null)
        {
            return;
        }

        window.attachMatchRestart(gameplay::restartMatch);
    }

    /**
     * Lets the on-screen score and the death notice read the live figures.
     *
     * <p>The tic rate is passed through because the respawn delay is counted in
     * <b>tics</b> — see {@code Match.RESPAWN_DELAY_TICS} — and a countdown shown
     * in seconds has to divide by something. The configuration is the only thing
     * that knows which rate this run is using, and it is right here.</p>
     *
     * @param window the window that draws the score; must not be null
     * @param gameplay the match to read, or null when there is no match
     * @param rate the simulation rate this run was started at; must not be null
     */
    private static void attachMatchStatus(final GdxWindowPort window,
        final DemoGameplayPort gameplay, final FrameRate rate)
    {
        if (gameplay == null)
        {
            return;
        }

        window.attachMatchStatus(gameplay::status, rate.fps());
    }

    /**
     * Returns the frozen result of a decided round, or null while it runs.
     *
     * <p>Called from the render thread, on a {@code Match} the game loop thread
     * owns. That is a race in form only, and the shape of this method is what
     * makes it one: nothing is read until {@code state().isOver()} is true, and
     * once it is, {@code Match.tick} returns early and the trigger is gated on
     * the same check — so every figure {@link MatchSummary#of} copies has
     * stopped moving before it is first observed. A stale read costs one
     * frame's delay and nothing else.</p>
     *
     * @param gameplay the port holding the round; must not be null
     * @return the summary, or null when there is no match or it is still running
     */
    private static MatchSummary finishedMatch(final DemoGameplayPort gameplay)
    {
        final Match round = gameplay.match();

        if (round == null || !round.state().isOver())
        {
            return null;
        }

        return MatchSummary.of(round);
    }

    /**
     * Shares the debug switch with the window.
     *
     * <p>Nothing but the frame counter is behind it any more. <b>It used to also
     * drive the renderer's outline pass</b>, which made a visual aid a
     * side-effect of a diagnostic and left the toggle's label describing one of
     * the two things it did — see
     * {@link #attachAccessibilitySettings} and {@code AccessibilitySettings}.</p>
     *
     * @param window the window whose settings screen flips the switch
     * @param debug the switch to share; must not be null
     */
    private static void attachDebugSettings(final GdxWindowPort window,
        final DebugSettings debug)
    {
        window.attachDebugSettings(debug);
    }

    /**
     * Shares the accessibility switches with the window, and puts the renderer's
     * outline pass behind them.
     *
     * <p><b>Loosely, on purpose.</b> {@code AccessibilitySettings} does not
     * import the renderer and the renderer has never heard of a settings screen;
     * the launcher, which already holds both, supplies the one-line observer.</p>
     *
     * <p><b>The initial value is pushed across explicitly, and that line is the
     * whole of a bug fix.</b> {@code onChange} deliberately does not fire on
     * attach, so before this the renderer kept whatever default it was compiled
     * with and the settings screen reported whatever default the switch was
     * compiled with — two constants that agreed only by luck, and for a while
     * did not: the outline defaulted on, its toggle defaulted off, and the
     * screen said {@code OFF} over a game that was drawing outlines. Asserting
     * the switch's value once here makes the label true because it was made
     * true. It is a no-op when the two already agree, because
     * {@code setOutlineEnabled} takes a value rather than a nudge.</p>
     *
     * @param window the window whose settings screen flips the switches
     * @param access the switches to share; must not be null
     * @param renderer the renderer whose outline pass follows them, or null when
     *     there is none
     */
    private static void attachAccessibilitySettings(final GdxWindowPort window,
        final AccessibilitySettings access, final SoftwareRenderPort renderer)
    {
        window.attachAccessibilitySettings(access);

        if (renderer == null)
        {
            return;
        }

        renderer.setOutlineEnabled(access.isTargetOutlineVisible());

        access.onChange(on -> renderer.setOutlineEnabled(on.booleanValue()));
    }

    /**
     * Shares the map selection with the window's picker, and gives the picker
     * the list of registered maps to show.
     *
     * <p>The launcher is the composition root, so it is the only object that
     * can see both {@link MapLibrary} (engine code) and {@link MapSelection}
     * (platform code). It builds the {@code (id, displayName)} rows the
     * screen needs and seeds the selection so the launcher's
     * {@code --map=<id>} argument takes effect for this run as well as the
     * next one.</p>
     *
     * <p>An empty registry produces an empty entries list. The picker's
     * button still transitions, but the screen is never built and the
     * player is silently returned to the menu — the same "no picker wired"
     * shape every windowless test already passes.</p>
     *
     * @param window the window whose picker reads and writes the selection
     * @param args the CLI arguments; {@code --map=} is honoured as the
     *     initial selection when present
     */
    private static void attachMatchGateMap(final GdxWindowPort window,
        final com.openfps.engine.gameplay.map.MapGameplayPort port)
    {
        if (port == null)
        {
            return;
        }

        window.attachMatchGate(live -> port.setMatchLive(live.booleanValue()));
    }

    /**
     * Lets the UI find out that the round has been decided. Mirror of
     * {@link #attachMatchResult} for the map port: the spec's match
     * produces a {@link MatchSummary} the same way the demo's does, and
     * the window reads it the same way.
     */
    private static void attachMatchResultMap(final GdxWindowPort window,
        final com.openfps.engine.gameplay.map.MapGameplayPort port)
    {
        if (port == null)
        {
            return;
        }

        window.attachMatchResult(() ->
        {
            final com.openfps.engine.gameplay.Match round = port.match();

            if (round == null || !round.state().isOver())
            {
                return null;
            }

            return MatchSummary.of(round);
        });
    }

    /**
     * Lets the end screen's Play Again button put the map world back.
     *
     * <p>The map port's {@code restartMatch} is a follow-up — there is
     * no equivalent of the demo's rematch yet. The window therefore does
     * not get a restart callback in map mode, and the end screen renders
     * a Back-to-menu button only. Stating so here because a player who
     * looks at the end of a map-mode match and does not see a Play Again
     * would otherwise have no way to know whether it was intended.</p>
     */
    private static void attachMatchRestartMap(final GdxWindowPort window,
        final com.openfps.engine.gameplay.map.MapGameplayPort port)
    {
        if (port == null)
        {
            return;
        }
        // Intentionally no-op: map mode has no restart path yet.
    }

    /**
     * Lets the on-screen score read the live match figures for a map
     * port. Same wiring as the demo path; the match status API is the
     * same {@link com.openfps.engine.gameplay.MatchStatus} either way.
     */
    private static void attachMatchStatusMap(final GdxWindowPort window,
        final com.openfps.engine.gameplay.map.MapGameplayPort port, final FrameRate rate)
    {
        if (port == null)
        {
            return;
        }

        // The match status is read every frame; the map port's accessor
        // builds a new snapshot on demand, which is the right shape for
        // the platform's per-frame copy.
        window.attachMatchStatus(port::status, rate.fps());
    }

    /**
     * Wires the map port's weapon noise. The audio port comes from the
     * HAL rather than being constructed here, for the same reason the
     * demo path's audio attach uses the HAL. {@code MapGameplayPort} does
     * not have an {@code attachAudio} method yet — it reads the
     * NullAudioPort default — so the wiring is a no-op until that ships.
     * Stating the no-op here rather than silently omitting it so the
     * shape of the launcher's attach path is uniform across modes.
     */
    private static void attachAudioMap(final GdxAdapterFactory hal,
        final com.openfps.engine.gameplay.map.MapGameplayPort port)
    {
        if (port == null)
        {
            return;
        }
        // Map port has no attachAudio on this pass; the field defaults
        // to NullAudioPort. The seam is documented so the next pass can
        // add the setter without having to re-derive what was intended.
    }

    private static void attachMapSelection(final GdxWindowPort window, final String[] args)
    {
        final MapSelection selection = new MapSelection();

        final String initial = mapArg(args);

        if (initial != null && !initial.isEmpty() && MapLibrary.has(initial))
        {
            selection.setCurrentMapId(initial);
        }

        final List<MapSelectionScreen.Entry> entries = new ArrayList<>(MapLibrary.size());

        for (final String id : MapLibrary.ids())
        {
            final MapSpec spec = MapLibrary.get(id);

            // The thumbnail lives next to the .ofm in the same map directory,
            // and the engine module's resources are on the classpath, so the
            // classpath-relative path is what the screen needs to load it.
            final String thumbnailPath = "maps/" + spec.id() + "/thumbnail.png";

            entries.add(new MapSelectionScreen.Entry(spec.id(), spec.displayName(),
                thumbnailPath, spec.mode().name()));
        }

        window.attachMapSelection(selection);

        window.attachMapEntries(entries);

        LOG.info("Map picker wired: {} map(s) registered, current selection = {}",
            entries.size(), selection.currentMapId());
    }

    /**
     * Gives the match somewhere to play the weapon sound.
     *
     * <p>Attached after {@code start} rather than passed into it, because the
     * gameplay port is built inside the bootstrap by a factory that takes only
     * the input port — see {@code DemoGameplayPort.attachAudio}. Nothing is
     * opened by this call: the port bakes its sound on the first shot, since
     * there is no libGDX audio device until {@code runFrameLoop} below has
     * started the application.</p>
     *
     * @param hal the HAL that owns the platform's sound output; must not be null
     * @param gameplay the match to give a voice to, or null when there is no
     *     match — the {@code --model=} path has none
     */
    private static void attachAudio(final GdxAdapterFactory hal,
        final DemoGameplayPort gameplay)
    {
        if (gameplay == null)
        {
            return;
        }

        gameplay.attachAudio(hal.getAudioPort());
    }

    // Wires the local player's first-person body into the gameplay port, so
    // the arms are published every tic. The body is part of the DemoScene
    // and so cannot be passed at construction — the same shape
    // attachRemoteBodies and attachAudio already document.
    private static void attachLocalBody(final DemoScene demo, final DemoGameplayPort gameplay)
    {
        if (gameplay == null || demo == null)
        {
            return;
        }

        gameplay.attachLocalBody(demo.localBody());
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

    // The same, for the blaster each bot is holding. A second table rather than
    // an interleaved one, because the port publishes the two in the same loop and
    // the arithmetic to unpack a stride is exactly the kind of thing that ends up
    // moving a weapon to a body's position.
    private static int[] botWeaponInstanceIndices(final DemoScene demo)
    {
        final int[] indices = new int[demo.botCount()];

        for (int index = 0; index < indices.length; index++)
        {
            indices[index] = demo.botWeaponInstanceIndex(index);
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
     * Picks the team a player is on from their net id, the same
     * deterministic assignment two peers on the same machine use to land
     * on opposite sides of the map.
     *
     * <p>Net id 1 lands on {@link Team#RED}, 2 on {@link Team#BLUE}, 3 on
     * RED again (the third peer takes the next slot of the team's
     * spawn list), and so on. A net id of 0 (no networking) is
     * {@link Team#NEUTRAL} — the single-player case — which the port
     * then resolves to the first spec spawn of any team.</p>
     *
     * @param netSpawnId the net id, or 0 for a local run
     * @return the team the player starts on
     */
    private static Team teamForPlayer(final int netSpawnId)
    {
        if (netSpawnId <= 0)
        {
            return Team.NEUTRAL;
        }

        if ((netSpawnId % 2) == 1)
        {
            return Team.RED;
        }

        return Team.BLUE;
    }

    /**
     * Picks which spawn within the team the player starts on, again
     * deterministically from the net id. Two peers with the same team
     * and different ids land on different spawns, so neither starts on
     * top of the other.
     *
     * @param netSpawnId the net id
     * @param team the team the player is on
     * @return the spawn index within the team, or -1 to fall back to the
     *     first spec spawn of any team
     */
    private static int mapSpawnIndexFor(final int netSpawnId, final Team team)
    {
        if (netSpawnId <= 0)
        {
            return -1;
        }

        // For two peers on the same team (3rd, 4th, 5th...), the spawn
        // index advances; for the normal 1v1 case, the index is 0 — the
        // first RED or BLUE spawn, which is what a two-peer match wants.
        if (team == Team.RED || team == Team.BLUE)
        {
            return (netSpawnId - 1) / 2;
        }

        return 0;
    }

    /**
     * Hands the renderer the map scene the spec was built from. The
     * map scene bypasses the demo entirely: no bots, no held weapon,
     * no UI hooks — the per-tic simulation runs the map's mode
     * logic but the rendered frame is just the level geometry.
     *
     * <p>The visual smoke test documented in {@code docs/pass2-report.md}
     * is whether this is enough to put a map on screen. A future
     * pass can replace this with a full map-driven demo (bots,
     * weapon, UI), at which point this method and the
     * {@code bindWorld} above converge.</p>
     *
     * @param renderer the renderer to bind the map scene into
     * @param mapScene the map scene to draw
     */
    private static void bindMapWorld(final SoftwareRenderPort renderer,
        final com.openfps.engine.gameplay.map.MapScene mapScene)
    {
        renderer.setScene(mapScene.scene());

        renderer.setCrosshairEnabled(true);

        LOG.info("Map world ready: {} — viewmodel and bots are bypassed in map mode,"
            + " only the level geometry is rendered. The per-tic mode logic still ticks"
            + " (the smoke gameplay port runs against the spec).", mapScene);
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

    /**
     * Returns the {@code --map=} argument, or null if none was given.
     *
     * <p>When non-null, the launcher builds a {@link
     * com.openfps.engine.gameplay.map.MapScene} for the id and uses
     * that as the rendered world. The legacy demo scene is bypassed
     * — see {@link MapRuntime#loadMap} and {@link #bindMapWorld}.</p>
     *
     * @param args the CLI arguments, may be null
     * @return the map id, or null
     */
    public static String mapArg(final String[] args)
    {
        return valueOf(args, MAP_ARG);
    }

    /**
     * Returns the {@code --settings=} argument, or null if none was given.
     *
     * @param args the CLI arguments, may be null
     * @return the explicit settings path, or null when the launcher should
     *     fall back to the default location
     */
    public static String settingsArg(final String[] args)
    {
        return valueOf(args, SETTINGS_ARG);
    }

    /**
     * Loads the player's settings from disk and applies them to the input
     * port. The lookup order is: explicit {@code --settings=}, then the
     * default at {@code ~/.openfps/settings.txt}; either may be absent,
     * in which case the launcher uses {@link PlayerSettings#defaults()}.
     *
     * <p>A malformed settings file is a hard error — the player typed it,
     * the launcher has no way to know what they meant, and silently
     * ignoring a typo is the kind of UI a player curses at.</p>
     *
     * @param args the CLI arguments, may be null
     * @return the loaded settings, never null
     */
    static PlayerSettings loadPlayerSettings(final String[] args)
    {
        final ActionBindings platformDefaults = DesktopBindings.defaults();

        final String explicit = settingsArg(args);

        if (explicit != null)
        {
            final Path path = Path.of(explicit);

            if (Files.exists(path))
            {
                return readSettingsFrom(path);
            }

            LOG.warn("Settings file {} does not exist - using platform defaults", path);

            return PlayerSettings.defaults(platformDefaults);
        }

        final String home = System.getProperty("user.home");

        if (home == null || home.isEmpty())
        {
            return PlayerSettings.defaults(platformDefaults);
        }

        final Path defaultPath = Path.of(home, DEFAULT_SETTINGS_FILENAME);

        if (!Files.exists(defaultPath))
        {
            return PlayerSettings.defaults(platformDefaults);
        }

        return readSettingsFrom(defaultPath);
    }

    private static PlayerSettings readSettingsFrom(final Path path)
    {
        try
        {
            final String text = Files.readString(path);

            return PlayerSettings.fromSpec(text);
        }
        catch (final IOException ex)
        {
            // Hard fail: the file exists, the player meant something, and a
            // silent default would erase the symptom rather than the cause.
            throw new IllegalStateException("Could not read settings file " + path, ex);
        }
    }

    /**
     * Returns the path the rebind screen's Save button should write
     * to.
     *
     * <p>Symmetric to {@link #loadPlayerSettings(String[])}: the
     * explicit {@code --settings=} argument wins, otherwise the
     * default at {@code ~/.openfps/settings.txt}. A launcher that
     * was started with no file path will write to the default
     * location, which is what a player on a fresh install wants —
     * the file their next launch should read from.</p>
     *
     * @param args the CLI arguments, may be null
     * @return the path to write to, never null
     */
    public static Path settingsSavePath(final String[] args)
    {
        final String explicit = settingsArg(args);

        if (explicit != null)
        {
            return Path.of(explicit);
        }

        final String home = System.getProperty("user.home");

        if (home == null || home.isEmpty())
        {
            return Path.of(DEFAULT_SETTINGS_FILENAME);
        }

        return Path.of(home, DEFAULT_SETTINGS_FILENAME);
    }

    /**
     * Returns a sink the rebind screen calls on Save. The sink
     * resolves the save path from the CLI arguments and writes
     * the settings file there. Errors are logged and swallowed:
     * a player who hits Save and the file fails to write is back
     * where they started, not staring at a crash dialog.
     *
     * @param args the CLI arguments the launcher was started with
     * @return a sink that writes the settings file when called
     */
    static Consumer<PlayerSettings> savePlayerSettingsSink(final String[] args)
    {
        return updated ->
        {
            final Path path = settingsSavePath(args);

            try
            {
                final Path parent = path.getParent();

                if (parent != null)
                {
                    Files.createDirectories(parent);
                }

                Files.writeString(path, updated.toSpec());

                LOG.info("Settings saved to {}", path);
            }
            catch (final IOException ex)
            {
                LOG.error("Could not write settings file {}: {}", path, ex.getMessage());
            }
        };
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
