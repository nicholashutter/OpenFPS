/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.desktop;

import com.openfps.engine.audio.adapter.NullAudioPort;
import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.adapter.nulladapter.NullFilePort;
import com.openfps.engine.hal.adapter.nulladapter.NullInputPort;
import com.openfps.engine.hal.adapter.nulladapter.NullSystemInfoPort;
import com.openfps.engine.hal.adapter.nulladapter.NullWindowPort;
import com.openfps.engine.hal.adapter.sqlite.SqliteUserProfilePort;
import com.openfps.engine.hal.port.I_DatagramPort;
import com.openfps.engine.hal.port.I_FilePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;
import com.openfps.engine.hal.port.I_WindowPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desktop HAL adapter factory — real OS-backed ports built on the JDK
 * alone, with no third-party native dependency.
 *
 * What is real here:
 *
 *  - {@link DesktopTimePort} — monotonic and wall clock from the JVM
 *  - {@link DesktopDatagramPort} — non-blocking UDP on {@code DatagramChannel}
 *  - {@link SqliteUserProfilePort} — on-disk profile persistence (Xerial),
 *    reused verbatim from the SQLITE backend rather than duplicated
 *
 * What is still null, and why: window and input need GLFW, and system
 * info needs a native probe for a true physical core count. LWJGL is not
 * a project dependency, and adding one is an explicit decision rather
 * than a side effect of this adapter. So those three keep their null
 * implementations — {@link NullFilePort} already reads the real
 * filesystem, and {@link NullSystemInfoPort} already answers from
 * {@link Runtime}, so only window and input are genuinely inert.
 *
 * The practical consequence: this backend is headless-safe. It needs no
 * display, which is why the adapter contract tests can cover it.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class DesktopAdapterFactory implements I_AdapterFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(DesktopAdapterFactory.class);

    private final DesktopTimePort timePort = new DesktopTimePort();
    private final NullInputPort inputPort = new NullInputPort();
    private final DesktopDatagramPort datagramPort = new DesktopDatagramPort();
    private final NullFilePort filePort = new NullFilePort();
    private final NullSystemInfoPort systemInfo = new NullSystemInfoPort();
    private final SqliteUserProfilePort userProfile = new SqliteUserProfilePort();
    private final NullWindowPort windowPort = new NullWindowPort();
    private final NullAudioPort audioPort = new NullAudioPort();

    @Override
    public void init()
    {
        LOG.info("Initializing desktop HAL adapter (JDK time + UDP, SQLite profile)");
        timePort.init();
        inputPort.init();
        datagramPort.init();
        systemInfo.init();
        userProfile.init();
        windowPort.init();
    }

    @Override
    public void shutdown()
    {
        LOG.info("Shutting down desktop HAL adapter");
        windowPort.shutdown();
        userProfile.shutdown();
        systemInfo.shutdown();
        datagramPort.shutdown();
        inputPort.shutdown();
        timePort.shutdown();
    }

    /** Returns the real JDK-backed time port. */
    @Override
    public I_TimePort getTimePort()
    {
        return timePort;
    }

    /** Returns the input port (null adapter — needs GLFW, out of scope). */
    @Override
    public I_InputPort getInputPort()
    {
        return inputPort;
    }

    /** Returns the real non-blocking UDP datagram port. */
    @Override
    public I_DatagramPort getDatagramPort()
    {
        return datagramPort;
    }

    /** Returns the file port (null adapter, backed by real filesystem reads). */
    @Override
    public I_FilePort getFilePort()
    {
        return filePort;
    }

    /** Returns the system info port (null adapter, backed by {@code Runtime}). */
    @Override
    public I_SystemInfoPort getSystemInfoPort()
    {
        return systemInfo;
    }

    /** Returns the SQLite-backed user profile port — the real one. */
    @Override
    public I_UserProfilePort getUserProfilePort()
    {
        return userProfile;
    }

    /** Returns the window port (null adapter — needs GLFW, out of scope). */
    @Override
    public I_WindowPort getWindowPort()
    {
        return windowPort;
    }

    /**
     * Returns the audio port (null adapter — needs a device, out of scope).
     *
     * The same call as the window and input ports, and for the same reason:
     * making a noise needs OpenAL, OpenAL is not a dependency of {@code :engine},
     * and this backend's whole value is that it is headless-safe. {@code
     * GdxAdapterFactory} in {@code :desktop} decorates this one with a real
     * {@code GdxAudioPort}, which is where a device may legitimately be opened.
     */
    @Override
    public I_AudioPort getAudioPort()
    {
        return audioPort;
    }
}
