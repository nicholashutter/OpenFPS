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

/**
 * Android entry point — the Activity declared as LAUNCHER in the manifest.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * This is the Android composition root, and it is deliberately thin. It
 * builds the {@link AndroidWindowPort}, hands it the menu callback, and gets
 * out of the way; every decision about what a frame does lives in
 * {@link MainMenuFrameCallback}, and every decision about how the platform
 * loop is driven lives in the window port.
 *
 * <b>How the engine boots without blocking.</b> {@code EngineMain.start()}
 * brings up memory, HAL, bus, subsystems, pool and the game loop thread and
 * then RETURNS, handing back an {@link EngineSession}. That matters here:
 * the old {@code run()} blocked its caller for the whole session, which is
 * correct for a desktop {@code main} and an immediate ANR on the UI thread.
 * So {@code onCreate} starts the session and returns, and {@code onDestroy}
 * stops it — the same pair desktop uses, just without
 * {@code awaitPlatformLoop()} in between, since the Android framework owns
 * the loop.
 *
 * The per-frame callback is a {@link CompositeFrameCallback}: the engine's
 * own callback plus the menu's, because the window port takes exactly one.
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

        // start() returns immediately — it never takes this thread. The game
        // loop runs on openfps-gameloop at a fixed rate; the frame callback
        // below only draws. unbounded() because a phone session ends when the
        // user leaves, not after a tic count.
        session = new EngineMain().start(
            GameConfig.unbounded(EngineMain.parseFpsArg(null)),
            new AndroidAdapterFactory(windowPort, this));

        // The port takes one callback, and two things need the frame: the
        // engine (which watches for the loop ending) and the menu (which
        // draws). Engine first — see CompositeFrameCallback on why order
        // matters on the way down.
        windowPort.runFrameLoop(new CompositeFrameCallback(
            session.frameCallback(),
            new MainMenuFrameCallback(windowPort)));
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
}
