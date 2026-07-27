/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.util.Log;

import com.openfps.engine.hal.adapter.AdapterFactorySelector;
import com.openfps.engine.hal.adapter.HalBackend;
import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.port.I_DatagramPort;
import com.openfps.engine.hal.port.I_FilePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;
import com.openfps.engine.hal.port.I_WindowPort;

/**
 * The Android HAL: every null-backend port, with the real Android window
 * substituted.
 *
 * <b>Why there is no {@code HalBackend.ANDROID}.</b> Adding one was
 * considered and rejected for the same reason the desktop track rejected it:
 * {@link AdapterFactorySelector} lives in {@code :engine}, and {@code :engine}
 * cannot depend on {@code :android} — that is a module cycle, and it would
 * drag the Android SDK into the module CI builds on a machine with no SDK.
 * The selector could therefore never construct the value, so the enum
 * constant would be unreachable. Instead this factory is handed directly to
 * {@code EngineMain.start(config, hal)}, which exists precisely so a platform
 * can supply its own HAL without the engine naming it.
 *
 * <b>Why the NULL backend underneath, not SQLITE.</b> {@code sqlite-jdbc} is
 * excluded from this module — it ships ~20 desktop native triplets Android
 * will never load — so selecting {@code HalBackend.SQLITE} here would fail
 * with {@code NoClassDefFoundError} at first use. The profile is therefore
 * in-memory and does NOT survive the app being killed. That is a real
 * limitation, not an oversight: persistence needs a Room-backed
 * {@link I_UserProfilePort}, which is its own piece of work.
 *
 * <b>Threading:</b> {@link #init()} and {@link #shutdown()} are main-thread
 * only, inherited from {@link I_AdapterFactory} and required by the Activity
 * lifecycle.
 *
 * Platform adapter — must not import from core engine packages beyond the
 * HAL ports it implements against.
 */
public final class AndroidAdapterFactory implements I_AdapterFactory
{
    /** Logcat tag. Android has no SLF4J binding, so platform code logs here. */
    private static final String TAG = "OpenFPS";

    /** Every port except the window. */
    private final I_AdapterFactory delegate;

    /** The real Android window. */
    private final AndroidWindowPort windowPort;

    /**
     * Creates the factory over the null HAL backend.
     *
     * @param windowPort the Android window port; must not be null
     */
    public AndroidAdapterFactory(final AndroidWindowPort windowPort)
    {
        this(AdapterFactorySelector.create(HalBackend.NULL), windowPort);
    }

    /**
     * Creates the factory over an explicit delegate. Exists so a test can
     * substitute a different backend.
     *
     * @param delegate supplies every port except the window; must not be null
     * @param windowPort the Android window port; must not be null
     */
    public AndroidAdapterFactory(final I_AdapterFactory delegate,
                                 final AndroidWindowPort windowPort)
    {
        if (delegate == null)
        {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (windowPort == null)
        {
            throw new IllegalArgumentException("windowPort must not be null");
        }
        this.delegate = delegate;
        this.windowPort = windowPort;
    }

    @Override
    public void init()
    {
        Log.i(TAG, "Initializing Android HAL (libGDX AndroidApplication window)");
        delegate.init();
        // The window port is initialized and created by AndroidLauncher
        // before the engine starts, because the Activity owns that ordering
        // and initialize() must happen inside onCreate.
    }

    @Override
    public void shutdown()
    {
        // The Activity owns the window's teardown in onDestroy — doing it
        // here too would be a double shutdown.
        delegate.shutdown();
        Log.i(TAG, "Android HAL shut down");
    }

    @Override
    public I_TimePort getTimePort()
    {
        return delegate.getTimePort();
    }

    @Override
    public I_InputPort getInputPort()
    {
        return delegate.getInputPort();
    }

    @Override
    public I_DatagramPort getDatagramPort()
    {
        return delegate.getDatagramPort();
    }

    @Override
    public I_FilePort getFilePort()
    {
        return delegate.getFilePort();
    }

    @Override
    public I_SystemInfoPort getSystemInfoPort()
    {
        return delegate.getSystemInfoPort();
    }

    @Override
    public I_UserProfilePort getUserProfilePort()
    {
        return delegate.getUserProfilePort();
    }

    @Override
    public I_WindowPort getWindowPort()
    {
        return windowPort;
    }
}
