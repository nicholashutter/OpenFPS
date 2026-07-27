/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.util.Log;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.engine.hal.port.I_WindowPort;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android implementation of I_WindowPort — the Activity, its GLSurfaceView,
 * and the framework-owned frame loop behind them.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * <b>Why {@link #runFrameLoop} does not block here.</b> The desktop adapter
 * satisfies the port with a {@code while (!closeRequested)} loop that drains
 * OS events, calls the callback and swaps buffers; it owns the thread until
 * the window closes. Android has no such loop to write. The framework starts
 * a {@code GLSurfaceView} render thread and calls the application when a
 * frame is due, and {@link #runFrameLoop} runs on the UI thread inside
 * {@code onCreate} — blocking it would trip the ANR watchdog within seconds
 * and no frame would ever be drawn, because the very thread that must return
 * to the looper to schedule them would be parked.
 *
 * So this implementation <em>registers</em> rather than loops: it wraps the
 * engine's {@link I_FrameCallback} in a {@link GdxLifecycleBridge} and hands
 * it to {@code AndroidApplication.initialize}, which installs the content
 * view and returns immediately. From that point the platform loop is running
 * on the GL thread and the UI thread is free. The port contract's "returns
 * when the app is destroyed" is honoured by the Activity lifecycle instead:
 * {@code onDestroy} is the end of the loop, and {@link #shutdown()} is what
 * the launcher calls there.
 *
 * <b>Threading.</b> {@link #init}, {@link #create}, {@link #runFrameLoop} and
 * {@link #shutdown} must all be called from the Android main (UI) thread —
 * the same constraint GLFW imposes on desktop, for a different reason.
 * {@link #requestClose()} and {@link #isCloseRequested()} are safe from any
 * thread, which matters because the menu's Quit button fires on the GL
 * thread.
 */
public final class AndroidWindowPort implements I_WindowPort
{
    /** Logcat tag. Android has no SLF4J binding, so platform code logs here. */
    private static final String TAG = "OpenFPS";

    /** Framebuffer bit depth. 8888 rather than libGDX's RGB565 default,
     *  which bands visibly on flat UI fills. Alpha stays 0 so the surface
     *  is opaque and the compositor can skip blending it. */
    private static final int COLOR_BITS = 8;

    /** Alpha bits in the framebuffer. Zero — an opaque surface. */
    private static final int ALPHA_BITS = 0;

    /** Depth buffer bits. 16 is libGDX's default and is enough for the
     *  Phase 5 rasterizer's blit; the UI itself needs none. */
    private static final int DEPTH_BITS = 16;

    /** The Activity. Owns the surface; this port only drives it. */
    private final AndroidApplication application;

    /** Close flag. Set from any thread, read by the launcher and the engine. */
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);

    /** Guards against a second {@code runFrameLoop} on one Activity. */
    private final AtomicBoolean loopStarted = new AtomicBoolean(false);

    /**
     * Creates the port for one Activity.
     *
     * @param application the hosting {@code AndroidApplication}; never null
     */
    public AndroidWindowPort(final AndroidApplication application)
    {
        if (application == null)
        {
            throw new IllegalArgumentException("application must not be null");
        }
        this.application = application;
    }

    @Override
    public void init()
    {
        // Nothing to initialize: the windowing system is the Android
        // framework and it is already up by the time an Activity exists.
        // The flags are reset so a relaunched Activity starts clean.
        closeRequested.set(false);
        loopStarted.set(false);
    }

    @Override
    public void create(final int width, final int height, final String title)
    {
        // Android decides both. The Activity is full-screen at whatever the
        // panel and the current split-screen state say, and the app label
        // comes from the manifest, not from here. The requested values are
        // logged rather than silently dropped so a mismatch between the
        // desktop default and the actual surface is visible in logcat.
        Log.i(TAG, "Window requested " + width + "x" + height + " '" + title
            + "' — Android supplies the real surface size at onSurfaceReady");
    }

    @Override
    public void runFrameLoop(final I_FrameCallback callback)
    {
        if (callback == null)
        {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (!loopStarted.compareAndSet(false, true))
        {
            throw new IllegalStateException("runFrameLoop already started for this Activity");
        }

        // initialize() installs the GLSurfaceView as the content view and
        // returns. It does NOT block — see the class Javadoc for why it
        // must not.
        application.initialize(new GdxLifecycleBridge(callback), buildConfig());
        Log.i(TAG, "Platform frame loop started (GLSurfaceView thread owns it)");
    }

    @Override
    public boolean isCloseRequested()
    {
        return closeRequested.get();
    }

    @Override
    public void requestClose()
    {
        // compareAndSet, not set: finish() must be posted exactly once even
        // if the GL thread and the UI thread both ask on the same frame.
        if (closeRequested.compareAndSet(false, true))
        {
            Log.i(TAG, "Close requested — finishing the Activity");
            application.runOnUiThread(application::finish);
        }
    }

    @Override
    public boolean isRealWindow()
    {
        return true;
    }

    @Override
    public void shutdown()
    {
        // The Activity owns the surface and the framework tears it down; the
        // callback has already had onSurfaceLost by the time this runs. All
        // that is left is to drop the flags so the port is reusable.
        Log.i(TAG, "Window port shut down");
        closeRequested.set(false);
        loopStarted.set(false);
    }

    // Builds the libGDX Android configuration. Sensors are off because the
    // engine reads input through I_InputPort, and every sensor left enabled
    // is a wake-lock-free but still real battery cost.
    private static AndroidApplicationConfiguration buildConfig()
    {
        final AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        // The manifest declares GLES 3.0 as required, so ask for it here too
        // rather than silently running the 2.0 path on a device that has 3.0.
        config.useGL30 = true;
        config.useImmersiveMode = true;
        config.r = COLOR_BITS;
        config.g = COLOR_BITS;
        config.b = COLOR_BITS;
        config.a = ALPHA_BITS;
        config.depth = DEPTH_BITS;
        config.stencil = 0;
        config.numSamples = 0;
        return config;
    }
}
