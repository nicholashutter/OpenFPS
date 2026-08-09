/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

/**
 * Test double for the {@code AndroidApplication} {@link AndroidWindowPort}
 * drives.
 *
 * This is the Android answer to the problem the desktop tests solve by never
 * entering {@code runFrameLoop}: the one line in the port that reaches the
 * framework is {@code application.initialize(listener, config)}, and the real
 * one installs a {@code GLSurfaceView} and starts a render thread. Overriding
 * that single method leaves everything the port actually decides — the
 * lifecycle guard, the bridge it wraps the callback in, the configuration it
 * builds — observable on a plain JVM.
 *
 * <b>What this double cannot show.</b> {@code Activity.runOnUiThread} is
 * {@code final}, so it cannot be overridden and the stubbed version does
 * nothing. {@code requestClose()} therefore never actually reaches
 * {@code finish()} here, and no test may claim it does; only the close flag
 * is observable. Proving the Activity finishes needs a device.
 */
final class FakeAndroidApplication extends AndroidApplication
{
    /** Listener handed to initialize(). MUTABLE: captured per call. */
    private ApplicationListener listener;

    /** Configuration handed to initialize(). MUTABLE: captured per call. */
    private AndroidApplicationConfiguration config;

    /** How many times initialize() was called. MUTABLE: counted per call. */
    private int initializeCount;

    @Override
    public void initialize(final ApplicationListener applicationListener,
                           final AndroidApplicationConfiguration configuration)
    {
        // Deliberately does NOT call super: the real implementation creates a
        // GLSurfaceView and a render thread.
        this.listener = applicationListener;

        this.config = configuration;

        this.initializeCount++;
    }

    /**
     * Returns the listener the port registered with libGDX.
     *
     * @return the listener, or null if the frame loop never started
     */
    ApplicationListener registeredListener()
    {
        return listener;
    }

    /**
     * Returns the configuration the port built for libGDX.
     *
     * @return the configuration, or null if the frame loop never started
     */
    AndroidApplicationConfiguration registeredConfig()
    {
        return config;
    }

    /**
     * Returns how many times the port handed a listener to libGDX.
     *
     * @return the count
     */
    int initializeCount()
    {
        return initializeCount;
    }
}
