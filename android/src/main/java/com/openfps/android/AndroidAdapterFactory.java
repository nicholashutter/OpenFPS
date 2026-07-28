/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.content.Context;
import android.util.Log;

import com.openfps.android.persistence.RoomUserProfilePort;
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
 * with {@code NoClassDefFoundError} at first use. Profile persistence comes
 * from {@link RoomUserProfilePort} instead, overridden below: Room wraps the
 * SQLite already in the OS, so the profile survives process death without
 * shipping a second database engine.
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

    /** Every port except the window and the profile. */
    private final I_AdapterFactory delegate;

    /** The real Android window. */
    private final AndroidWindowPort windowPort;

    /** Room-backed profile persistence. */
    private final I_UserProfilePort userProfilePort;

    /** Touch input, or null to fall back on the null backend's do-nothing port. */
    private final I_InputPort inputPort;

    /**
     * Creates the factory over the null HAL backend with Room persistence and
     * no input.
     *
     * @param windowPort the Android window port; must not be null
     * @param context any context, used to open the Room database; must not
     *     be null
     */
    public AndroidAdapterFactory(final AndroidWindowPort windowPort, final Context context)
    {
        this(windowPort, context, null);
    }

    /**
     * Creates the factory over the null HAL backend with Room persistence and
     * real touch input.
     *
     * @param windowPort the Android window port; must not be null
     * @param context any context, used to open the Room database; must not
     *     be null
     * @param touchInput the port that reads the on-screen controls, or null for
     *     a build with nothing to control
     */
    public AndroidAdapterFactory(final AndroidWindowPort windowPort, final Context context,
                                 final I_InputPort touchInput)
    {
        this(AdapterFactorySelector.create(HalBackend.NULL),
            windowPort,
            new RoomUserProfilePort(context),
            touchInput);
    }

    /**
     * Creates the factory over explicit collaborators. Exists so a test can
     * substitute an in-memory profile port and stay off disk.
     *
     * @param delegate supplies every port except the window and profile;
     *     must not be null
     * @param windowPort the Android window port; must not be null
     * @param userProfilePort profile persistence; must not be null
     */
    public AndroidAdapterFactory(final I_AdapterFactory delegate,
                                 final AndroidWindowPort windowPort,
                                 final I_UserProfilePort userProfilePort)
    {
        this(delegate, windowPort, userProfilePort, null);
    }

    /**
     * Creates the factory over explicit collaborators, including input.
     *
     * @param delegate supplies every port except the window, profile and
     *     input; must not be null
     * @param windowPort the Android window port; must not be null
     * @param userProfilePort profile persistence; must not be null
     * @param touchInput the port that reads the on-screen controls, or null to
     *     use the delegate's
     */
    public AndroidAdapterFactory(final I_AdapterFactory delegate,
                                 final AndroidWindowPort windowPort,
                                 final I_UserProfilePort userProfilePort,
                                 final I_InputPort touchInput)
    {
        if (delegate == null)
        {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (windowPort == null)
        {
            throw new IllegalArgumentException("windowPort must not be null");
        }
        if (userProfilePort == null)
        {
            throw new IllegalArgumentException("userProfilePort must not be null");
        }
        this.delegate = delegate;
        this.windowPort = windowPort;
        this.userProfilePort = userProfilePort;
        this.inputPort = touchInput;
    }

    @Override
    public void init()
    {
        Log.i(TAG, "Initializing Android HAL (libGDX window, Room profile, "
            + (inputPort == null ? "no input" : "touch input") + ")");
        delegate.init();
        userProfilePort.init();
        // The input port is deliberately NOT initialised here. HalSubsystem
        // owns its lifecycle — it calls init() on whatever getInputPort()
        // returns, and shutdown() on the way out — so doing it here as well
        // simply runs both twice. Harmless, because init is idempotent, but it
        // was visible as a duplicated line in logcat and a duplicated line in
        // logcat is how you find out something is happening twice.
        // The window port is initialized and created by AndroidLauncher
        // before the engine starts, because the Activity owns that ordering
        // and initialize() must happen inside onCreate.
    }

    @Override
    public void shutdown()
    {
        // Profile first: EngineSession.stop() saves the profile through this
        // port and only then calls hal.shutdown(), so closing the database
        // any earlier would lose the write it just made.
        userProfilePort.shutdown();
        // Input is HalSubsystem's, on the way down as well as up — see init().
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
        if (inputPort != null)
        {
            return inputPort;
        }
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
        return userProfilePort;
    }

    @Override
    public I_WindowPort getWindowPort()
    {
        return windowPort;
    }
}
