/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.os.Bundle;
import android.util.Log;

import com.badlogic.gdx.backends.android.AndroidApplication;

import com.openfps.engine.core.EngineMain;
import com.openfps.engine.core.EngineSession;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.demo.DemoAssetException;
import com.openfps.engine.demo.DemoGameplayPort;
import com.openfps.engine.demo.DemoModels;
import com.openfps.engine.demo.DemoScene;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchSummary;
import com.openfps.engine.gameplay.adapter.NullGameplayPort;
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.engine.render.port.I_RenderPort;
import com.openfps.engine.render.port.I_RenderPortFactory;
import com.openfps.gdx.DebugSettings;
import com.openfps.gdx.DefaultMenuActions;
import com.openfps.gdx.FramebufferPresenter;

/**
 * Android entry point — the Activity declared as LAUNCHER in the manifest.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * This is the Android composition root, and it makes exactly the same three
 * decisions {@code DesktopLauncher} makes: a real window port, an unbounded
 * run, and the real {@link SoftwareRenderPort} with the window told to present
 * its frames. Everything after that is the standard bootstrap —
 * {@code EngineMain} owns memory, the event bus, subsystems, the worker pool,
 * the game loop and shutdown ordering, and this class reimplements none of it.
 *
 * <b>How the engine boots without blocking.</b> {@code EngineMain.start()}
 * brings up memory, HAL, bus, subsystems, pool and the game loop thread and
 * then RETURNS, handing back an {@link EngineSession}. That matters here:
 * a {@code run()} that blocked its caller is correct for a desktop
 * {@code main} and an immediate ANR on the UI thread. So {@code onCreate}
 * starts the session and returns, and {@code onDestroy} stops it — the same
 * pair desktop uses, just without {@code awaitPlatformLoop()} in between,
 * since the Android framework owns the loop.
 *
 * <h2>The ordering, which is the only genuinely Android-specific part</h2>
 *
 * <ol>
 *   <li><b>The world is built first, from {@code getAssets()}.</b> Not from
 *       {@code Gdx.files}: that only exists once {@code AndroidApplication
 *       .initialize()} has run, and {@code initialize()} is the same call that
 *       starts the frame loop — which needs the gameplay port, which needs the
 *       world. {@link ApkModelSource} takes the platform's own
 *       {@code AssetManager} and breaks the circle.</li>
 *   <li><b>Then the input port</b>, because the HAL has to hand the engine the
 *       same instance the UI will feed touches into.</li>
 *   <li><b>Then the engine</b>, which builds the renderer and the gameplay
 *       port during {@code start()}.</li>
 *   <li><b>Then the UI and the frame loop</b>, which is the point at which a
 *       surface exists and anything may draw.</li>
 * </ol>
 *
 * <b>A missing model set is not fatal.</b> {@code assets/models} is gitignored,
 * so an APK built from a fresh clone genuinely has no world in it. That is
 * reported at {@code ERROR} in logcat and the app runs as a menu — which is
 * what the Android build was before this, and is a better outcome than an app
 * that dies on launch on a colleague's machine.
 *
 * <b>Threading.</b> {@code onCreate} and {@code onDestroy} run on the Android
 * main (UI) thread, which is what {@code I_WindowPort} requires of
 * {@code init} / {@code create} / {@code runFrameLoop} / {@code shutdown}.
 */
public final class AndroidLauncher extends AndroidApplication
{
    /** Logcat tag. Android has no SLF4J binding, so platform code logs here. */
    private static final String TAG = "OpenFPS";

    /**
     * Nominal surface width passed to the port. Android ignores it — the
     * Activity is full-screen — but the port contract asks for one, and the
     * desktop default is the honest thing to ask for.
     */
    private static final int NOMINAL_WIDTH = 1280;

    /** Nominal surface height passed to the port. Also ignored by Android. */
    private static final int NOMINAL_HEIGHT = 720;

    /** Window title. Android takes the visible label from the manifest. */
    private static final String TITLE = "OpenFPS";

    /** The window port. MUTABLE: created in onCreate, released in onDestroy. */
    private AndroidWindowPort windowPort;

    /** The running engine. MUTABLE: started in onCreate, stopped in onDestroy. */
    private EngineSession session;

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "AndroidLauncher starting");

        windowPort = new AndroidWindowPort(this);
        windowPort.init();
        windowPort.create(NOMINAL_WIDTH, NOMINAL_HEIGHT, TITLE);

        final DemoScene demo = buildDemo();
        // The platform's own density, not Gdx.graphics.getDensity(): there is
        // no libGDX graphics object until initialize() runs, and the control
        // layout has to exist before the engine is handed the input port.
        final AndroidInputPort input =
            new AndroidInputPort(getResources().getDisplayMetrics().density);
        final RendererHolder holder = new RendererHolder();
        final GameConfig config = GameConfig.unbounded(EngineMain.parseFpsArg(null));
        // MUTABLE: assigned once on this thread by the factory below, before
        // the frame loop that reads it starts. There is no race — the engine
        // bootstrap has returned by then.
        final DemoGameplayPort[] gameplay = new DemoGameplayPort[1];

        // Hoisted into a local because the audio port is read back off it below.
        // The factory owns the platform's devices; the launcher only wires them.
        final AndroidAdapterFactory hal = new AndroidAdapterFactory(windowPort, this, input);

        session = new EngineMain().start(config,
            hal,
            holder,
            inputPort ->
            {
                final I_GameplayPort port = gameplayPort(inputPort, holder, demo, config);
                if (port instanceof DemoGameplayPort)
                {
                    gameplay[0] = (DemoGameplayPort) port;
                }
                return port;
            });

        final SoftwareRenderPort renderer = holder.renderer();
        bindWorld(renderer, demo);

        // Built here rather than inside the UI, because this Activity is the
        // only object that can see both the settings screen's switch and the
        // renderer it also drives. That is the composition root's job.
        final DebugSettings debug = new DebugSettings();
        final AndroidUiFrameCallback ui = new AndroidUiFrameCallback(
            new DefaultMenuActions(windowPort), new FramebufferPresenter(renderer), input,
            debug);
        attachMatchGate(ui, gameplay[0], hal.getAudioPort());
        attachMatchResult(ui, gameplay[0]);
        attachMatchRestart(ui, gameplay[0]);
        attachMatchStatus(ui, gameplay[0], config);
        // Loosely, on purpose: DebugSettings does not import the renderer and
        // the renderer has never heard of a settings screen. Attaching does not
        // fire, so whatever outline default the renderer was built with survives
        // startup and only a player pressing the toggle moves it.
        debug.onChange(on -> renderer.setOutlineEnabled(on.booleanValue()));
        // The weapon's noise. Nothing is opened yet — there is no Gdx.audio
        // until initialize() has run, which runFrameLoop below is what does, so
        // the port bakes its sound on the first shot instead.
        attachAudio(hal, gameplay[0]);

        // The port takes one callback, and two things need the frame: the
        // engine (which watches for the loop ending) and the UI (which draws).
        // Engine first — see CompositeFrameCallback on why order matters on the
        // way down.
        windowPort.runFrameLoop(new CompositeFrameCallback(session.frameCallback(), ui));
    }

    @Override
    protected void onDestroy()
    {
        // super first: AndroidApplication's onDestroy is what tears the
        // libGDX application down and delivers dispose() -> onSurfaceLost(),
        // so the callback must have released its GL resources before the
        // port is told the window is gone.
        super.onDestroy();

        // Then the engine: stop() halts the game loop, joins it, drains the
        // bus and saves the profile. It is idempotent, and it deliberately
        // does not assume the window ever closed gracefully — onDestroy can
        // arrive with the loop still running.
        if (session != null)
        {
            session.stop();
            session = null;
        }
        if (windowPort != null)
        {
            windowPort.shutdown();
            windowPort = null;
        }
        Log.i(TAG, "AndroidLauncher destroyed");
    }

    // The demo world from the APK's assets, or null when none was packaged.
    private DemoScene buildDemo()
    {
        try
        {
            final DemoScene demo =
                DemoScene.build(DemoModels.load(new ApkModelSource(getAssets())));
            Log.i(TAG, "First-person demo ready: " + demo);
            return demo;
        }
        catch (final DemoAssetException e)
        {
            Log.e(TAG, "No world in this APK: " + e.getMessage()
                + " — the menu will come up with nothing behind it");
            return null;
        }
    }

    // The demo's per-tic loop, or the do-nothing port when no world was
    // packaged. The renderer is read from the holder rather than passed in:
    // the bootstrap builds the render port before it calls this, which is the
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
            demo.newMatch(), botInstanceIndices(demo), demo.effects(),
            botWeaponInstanceIndices(demo));
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

    // The same, for the blaster each bot is holding.
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
    // moves except the camera and the bots, and those move through
    // setWorldTransform rather than by rebuilding.
    private static void bindWorld(final SoftwareRenderPort renderer, final DemoScene demo)
    {
        if (demo == null)
        {
            return;
        }
        renderer.setScene(demo.scene());
        // Furniture of a first-person game, so the first-person game asks for
        // it. Off by default so the render tests can assert exact pixels
        // without a reticle through the middle of every frame.
        renderer.setCrosshairEnabled(true);
    }

    /**
     * Connects the UI's notion of "in the world" to the match's notion of
     * "running".
     *
     * <p>The game loop starts with the process and ticks whatever is on screen,
     * which is right — the simulation clock must not depend on a menu. But the
     * <b>match</b> must. Without this the bots patrol and fire from the moment
     * the Activity launches, so a player who reads the title screen for ten
     * seconds starts already down a fifth of their health. That was visible in
     * the first run of the packaged desktop build and there is no reason a
     * phone would be different.</p>
     *
     * <p><b>The weapon's sound is loaded on the same edge</b>, and this is the
     * only seam in the app where it can be. It needs a live {@code Gdx.audio},
     * which does not exist until the frame loop is running, and it has to
     * happen before the trigger rather than on it — {@code SoundPool.load} is
     * asynchronous, so a sound created by the first shot is not ready to play
     * until several shots later. Entering a match satisfies both: the surface
     * is up, and the player is still finding their thumbs.</p>
     *
     * @param ui the UI whose transitions drive the gate; must not be null
     * @param gameplay the match to freeze and unfreeze, or null when no world
     *     was packaged
     * @param audio the sound output to warm up; must not be null
     */
    private static void attachMatchGate(final AndroidUiFrameCallback ui,
        final DemoGameplayPort gameplay, final I_AudioPort audio)
    {
        if (gameplay == null)
        {
            return;
        }
        ui.attachMatchGate(live ->
        {
            gameplay.setMatchLive(live.booleanValue());
            if (live.booleanValue())
            {
                // Idempotent, so a second match costs one map lookup. Attaching
                // the gate fires it once with the CURRENT state — the menu — so
                // this cannot run before there is a device to run against.
                audio.preload();
            }
        });
    }

    /**
     * Lets the UI find out that the round has been decided.
     *
     * <p>The other direction of {@link #attachMatchGate}. Without it a match
     * simply stops: the bots go still, the log gets one line, and the player is
     * left holding a phone showing a room that quietly finished without telling
     * them — winning and dying look identical.</p>
     *
     * @param ui the UI that shows the result; must not be null
     * @param gameplay the match to read, or null when no world was packaged
     */
    private static void attachMatchResult(final AndroidUiFrameCallback ui,
        final DemoGameplayPort gameplay)
    {
        if (gameplay == null)
        {
            return;
        }
        ui.attachMatchResult(() -> finishedMatch(gameplay));
    }

    /**
     * Lets the end screen's Play Again button put the world back.
     *
     * <p>The Android half of the rematch. {@code restartMatch} takes the gameplay
     * port's tic lock, which is what makes it safe to call from the GL thread's
     * Scene2D click handler while the game loop thread is mid-tic.</p>
     *
     * @param ui the UI whose end screen offers the rematch; must not be null
     * @param gameplay the match to restore, or null when no world was packaged —
     *     the button is then not offered at all
     */
    private static void attachMatchRestart(final AndroidUiFrameCallback ui,
        final DemoGameplayPort gameplay)
    {
        if (gameplay == null)
        {
            return;
        }
        ui.attachMatchRestart(gameplay::restartMatch);
    }

    /**
     * Lets the on-screen score and the death notice read the live figures.
     *
     * <p>The tic rate is passed because the respawn delay is counted in
     * <b>tics</b> — see {@code Match.RESPAWN_DELAY_TICS} — and a countdown shown
     * in seconds has to divide by something.</p>
     *
     * @param ui the UI that draws the score; must not be null
     * @param gameplay the match to read, or null when no world was packaged
     * @param config the running configuration, which fixes the tic rate; must not
     *     be null
     */
    private static void attachMatchStatus(final AndroidUiFrameCallback ui,
        final DemoGameplayPort gameplay, final GameConfig config)
    {
        if (gameplay == null)
        {
            return;
        }
        ui.attachMatchStatus(gameplay::status, config.rate().fps());
    }

    /**
     * Returns the frozen result of a decided round, or null while it runs.
     *
     * <p>Called from the render thread on a {@code Match} the game loop thread
     * owns, which is a race in form only: nothing is read until
     * {@code state().isOver()} is true, and once it is, {@code Match.tick}
     * returns early and the trigger is gated on the same check, so every figure
     * copied has stopped moving. A stale read costs one frame and nothing
     * else.</p>
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
     * Gives the match somewhere to play the weapon sound.
     *
     * <p>Attached after {@code start} rather than passed into it, because the
     * gameplay port is built inside the bootstrap by a factory that takes only
     * the input port — see {@code DemoGameplayPort.attachAudio}. The desktop
     * launcher carries the same method for the same reason.</p>
     *
     * @param hal the HAL that owns the platform's sound output
     * @param gameplay the match to give a voice to, or null when no world was
     *     packaged
     */
    private static void attachAudio(final AndroidAdapterFactory hal,
        final DemoGameplayPort gameplay)
    {
        if (gameplay == null)
        {
            return;
        }
        gameplay.attachAudio(hal.getAudioPort());
    }

    /**
     * Builds the render port and remembers it.
     *
     * <p>The port cannot be constructed before {@code EngineMain.start} — it
     * needs the worker pool — and the UI needs a typed reference to it
     * afterwards. A small holder gives both without a downcast and without a
     * mutable field racing the game loop thread. The desktop launcher carries
     * the same class for the same reason.</p>
     */
    private static final class RendererHolder implements I_RenderPortFactory
    {
        /** What the last call built. MUTABLE: assigned once, on the UI thread. */
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
