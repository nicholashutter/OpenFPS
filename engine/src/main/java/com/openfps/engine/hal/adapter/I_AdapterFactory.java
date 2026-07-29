/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter;

import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.hal.port.I_DatagramPort;
import com.openfps.engine.hal.port.I_FilePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;
import com.openfps.engine.hal.port.I_WindowPort;

/**
 * A complete set of HAL ports for one platform.
 *
 * The engine asks {@link AdapterFactorySelector} for a factory, calls
 * {@link #init()}, pulls the ports it needs, and calls {@link #shutdown()}
 * at the end. It never instantiates an adapter directly — same rule the
 * memory, event bus, and thread pool factories follow.
 *
 * Every getter must return a non-null port even when the platform has no
 * real implementation for it; use the matching null adapter instead. That
 * way the engine has exactly one code path regardless of backend.
 *
 * <b>Threading:</b> {@link #init()} and {@link #shutdown()} MUST be called
 * from the main thread. GLFW requires window creation and destruction to
 * happen there, so the desktop factory inherits that constraint and the
 * interface states it for everyone.
 */
public interface I_AdapterFactory
{
    /**
     * Initializes every port this factory owns. Main thread only.
     * Called exactly once, before any getter is used.
     */
    void init();

    /**
     * Shuts down every port this factory owns, in reverse dependency
     * order. Main thread only. Called exactly once.
     */
    void shutdown();

    /** Returns the monotonic + wall-clock time source. Never null. */
    I_TimePort getTimePort();

    /** Returns the keyboard/mouse input port. Never null. */
    I_InputPort getInputPort();

    /** Returns the raw UDP datagram port. Never null. */
    I_DatagramPort getDatagramPort();

    /** Returns the filesystem port. Never null. */
    I_FilePort getFilePort();

    /** Returns the host hardware info port. Never null. */
    I_SystemInfoPort getSystemInfoPort();

    /** Returns the user profile persistence port. Never null. */
    I_UserProfilePort getUserProfilePort();

    /**
     * Returns the window port. Never null — headless backends return a
     * no-op window whose {@code isRealWindow()} is false.
     */
    I_WindowPort getWindowPort();

    /**
     * Returns the sound output. Never null — a backend with no audio device
     * returns a {@code NullAudioPort}, whose {@code isAudible()} is false.
     *
     * <b>This factory does NOT init or shut down the port it returns here.</b>
     * {@code AudioSubsystem} owns that lifecycle, exactly as {@code HalSubsystem}
     * owns the input port's, and doing it in both places simply runs both twice.
     * Harmless, since the contract makes them idempotent, but a duplicated line
     * in a log is how you find out something is happening twice, and the input
     * port already taught that lesson once (see {@code AndroidAdapterFactory}).
     *
     * <b>Audio is here rather than in a factory of its own</b> because it is the
     * same kind of thing as the window: a device the platform owns, chosen at the
     * composition root, with a null implementation that keeps CI honest. Giving
     * it a second selection mechanism would mean two answers to "which platform
     * am I on".
     */
    I_AudioPort getAudioPort();
}
