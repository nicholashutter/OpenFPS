/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The windowed desktop HAL: everything {@code HalBackend.DESKTOP} already
 * provides, with the null window swapped for a real
 * {@link GdxWindowPort}.
 *
 * <b>Why a decorator and not a new backend.</b> {@code :engine} cannot
 * depend on {@code :desktop} — that is a module cycle, and it would drag
 * libGDX into the module CI builds headlessly. So
 * {@link AdapterFactorySelector} can never name this class. Rather than
 * duplicate the time, datagram, file, system-info and SQLite-profile wiring
 * that {@code DesktopAdapterFactory} already gets right, this delegates all
 * of it and overrides exactly one getter. Adding a port to
 * {@code I_AdapterFactory} therefore needs no change here beyond the
 * forwarding method the interface forces.
 *
 * {@link #init()} also performs the {@code create()} call the engine's
 * bootstrap does not make: the composition root has no opinion about window
 * geometry, and this is the layer that does.
 *
 * <b>Threading:</b> {@link #init()} and {@link #shutdown()} are main-thread
 * only, inherited from {@code I_AdapterFactory} and required by GLFW.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class GdxAdapterFactory implements I_AdapterFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(GdxAdapterFactory.class);

    /** Every port except the window. */
    private final I_AdapterFactory delegate;

    /** The real GLFW-backed window. */
    private final GdxWindowPort windowPort;

    /** Creates the factory over the standard desktop HAL backend. */
    public GdxAdapterFactory()
    {
        this(AdapterFactorySelector.create(HalBackend.DESKTOP), new GdxWindowPort());
    }

    /**
     * Creates the factory over an explicit delegate. Exists so tests can
     * substitute the null backend and stay off disk.
     *
     * @param delegate supplies every port except the window; must not be null
     * @param windowPort the real window port; must not be null
     */
    public GdxAdapterFactory(final I_AdapterFactory delegate, final GdxWindowPort windowPort)
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
        LOG.info("Initializing windowed desktop HAL (libGDX LWJGL3 window)");
        delegate.init();
        windowPort.init();
        windowPort.create(GdxWindowPort.DEFAULT_WIDTH,
            GdxWindowPort.DEFAULT_HEIGHT,
            GdxWindowPort.DEFAULT_TITLE);
    }

    @Override
    public void shutdown()
    {
        windowPort.shutdown();
        delegate.shutdown();
        LOG.info("Windowed desktop HAL shut down");
    }

    /** Returns the delegate's real JDK-backed time port. */
    @Override
    public I_TimePort getTimePort()
    {
        return delegate.getTimePort();
    }

    /** Returns the delegate's input port. */
    @Override
    public I_InputPort getInputPort()
    {
        return delegate.getInputPort();
    }

    /** Returns the delegate's UDP datagram port. */
    @Override
    public I_DatagramPort getDatagramPort()
    {
        return delegate.getDatagramPort();
    }

    /** Returns the delegate's filesystem port. */
    @Override
    public I_FilePort getFilePort()
    {
        return delegate.getFilePort();
    }

    /** Returns the delegate's host hardware info port. */
    @Override
    public I_SystemInfoPort getSystemInfoPort()
    {
        return delegate.getSystemInfoPort();
    }

    /** Returns the delegate's user profile persistence port. */
    @Override
    public I_UserProfilePort getUserProfilePort()
    {
        return delegate.getUserProfilePort();
    }

    /**
     * Returns the real GLFW-backed window — the one getter this factory
     * does not delegate, and the reason it exists.
     */
    @Override
    public I_WindowPort getWindowPort()
    {
        return windowPort;
    }
}
