/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.os.Bundle;
import android.util.Log;

import com.badlogic.gdx.backends.android.AndroidApplication;

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
 * <b>Why the engine is not booted here yet.</b> {@code EngineMain.run()}
 * blocks its calling thread for the whole session — it starts the game loop
 * on {@code openfps-gameloop}, then gives the caller's thread to
 * {@code I_WindowPort.runFrameLoop}. On desktop the caller is {@code main}
 * and that is exactly right. On Android the caller would be the UI thread
 * inside {@code onCreate}, and blocking it is an immediate ANR. Booting the
 * engine from Android needs a split entry point in {@code EngineMain} —
 * "start the subsystems and return" separated from "give me your thread" —
 * which belongs to the module that owns that file. Until it exists this
 * Activity drives the window port directly, which is enough to render the
 * menu and is the same port the engine will drive later.
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

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "AndroidLauncher starting");

        windowPort = new AndroidWindowPort(this);
        windowPort.init();
        windowPort.create(NOMINAL_WIDTH, NOMINAL_HEIGHT, TITLE);

        // Does not block — the Android framework owns the loop from here and
        // drives the GLSurfaceView thread. See AndroidWindowPort's Javadoc.
        windowPort.runFrameLoop(new MainMenuFrameCallback(windowPort));
    }

    @Override
    protected void onDestroy()
    {
        // super first: AndroidApplication's onDestroy is what tears the
        // libGDX application down and delivers dispose() -> onSurfaceLost(),
        // so the callback must have released its GL resources before the
        // port is told the window is gone.
        super.onDestroy();
        if (windowPort != null)
        {
            windowPort.shutdown();
            windowPort = null;
        }
        Log.i(TAG, "AndroidLauncher destroyed");
    }
}
