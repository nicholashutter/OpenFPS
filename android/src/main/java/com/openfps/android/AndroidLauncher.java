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
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.demo.DemoAssetException;
import com.openfps.engine.demo.DemoGameplayPort;
import com.openfps.engine.demo.DemoModels;
import com.openfps.engine.demo.DemoScene;
import com.openfps.engine.gameplay.adapter.NullGameplayPort;
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.engine.render.port.I_RenderPort;
import com.openfps.engine.render.port.I_RenderPortFactory;
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

        session = new EngineMain().start(config,
            new AndroidAdapterFactory(windowPort, this, input),
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

        final AndroidUiFrameCallback ui = new AndroidUiFrameCallback(
            new DefaultMenuActions(windowPort), new FramebufferPresenter(renderer), input);
        attachMatchGate(ui, gameplay[0]);

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
            demo.newMatch(), botInstanceIndices(demo));
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
     */
    private static void attachMatchGate(final AndroidUiFrameCallback ui,
        final DemoGameplayPort gameplay)
    {
        if (gameplay == null)
        {
            return;
        }
        ui.attachMatchGate(live -> gameplay.setMatchLive(live.booleanValue()));
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
