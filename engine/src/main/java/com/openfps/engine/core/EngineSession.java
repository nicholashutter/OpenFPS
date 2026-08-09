/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.common.UserProfile;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;
import com.openfps.engine.hal.port.I_WindowPort;
import com.openfps.engine.memory.port.I_MemoryPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A running engine — everything {@link EngineMain#start} brought up, and
 * the means to take it back down.
 *
 * <b>Why this exists.</b> {@code run()} used to own the whole session: it
 * booted the subsystems, then blocked its caller until the window closed.
 * That is correct for a desktop {@code main()} and impossible on Android,
 * where the caller is the UI thread inside {@code onCreate} and blocking it
 * is an immediate ANR.
 *
 * The fix is not an Android special case. {@link EngineMain#start} plus
 * {@link #stop()} is now the lifecycle API for <em>every</em> platform, and
 * neither caller has to know or care whether anything blocks:
 *
 * <pre>
 *   desktop   start() -&gt; awaitPlatformLoop() -&gt; stop()   all on main()
 *   android   start() in onCreate, stop() in onDestroy
 * </pre>
 *
 * Desktop's blocking behaviour now belongs to {@link #awaitPlatformLoop()},
 * which is a property of the window, not of the engine lifecycle. That is
 * the distinction the old shape blurred.
 *
 * <b>Threading.</b> {@link #stop()} and {@link #awaitPlatformLoop()} must be
 * called from the same (main) thread that called {@code start}, because
 * {@link I_AdapterFactory#shutdown()} is main-thread only.
 */
public final class EngineSession
{
    private static final Logger LOG = LoggerFactory.getLogger(EngineSession.class);

    /** How long to wait for the worker pool to drain, in milliseconds. */
    private static final long POOL_DRAIN_TIMEOUT_MS = 2000L;

    private final I_MemoryPort memory;
    private final I_AdapterFactory hal;
    private final I_EventBusPort bus;
    private final SubsystemRegistry subsystems;
    private final I_ThreadPoolPort pool;
    private final GameLoop loop;
    private final Thread loopThread;
    private final I_UserProfilePort userProfilePort;
    private final UserProfile profile;
    private final I_TimePort timePort;
    private final I_WindowPort window;
    private final I_FrameCallback frameCallback;

    /** Guards against a double stop(); shutdown of most ports is not idempotent. MUTABLE. */
    private boolean stopped;

    EngineSession(final I_MemoryPort memory,
                  final I_AdapterFactory hal,
                  final I_EventBusPort bus,
                  final SubsystemRegistry subsystems,
                  final I_ThreadPoolPort pool,
                  final GameLoop loop,
                  final Thread loopThread,
                  final I_UserProfilePort userProfilePort,
                  final UserProfile profile,
                  final I_TimePort timePort,
                  final I_WindowPort window)
    {
        this.memory = memory;

        this.hal = hal;

        this.bus = bus;

        this.subsystems = subsystems;

        this.pool = pool;

        this.loop = loop;

        this.loopThread = loopThread;

        this.userProfilePort = userProfilePort;

        this.profile = profile;

        this.timePort = timePort;

        this.window = window;

        this.frameCallback = new EngineFrameCallback(loopThread, window);

        this.stopped = false;
    }

    /**
     * Returns the callback the platform should drive once per frame.
     *
     * Android hands this to {@code I_WindowPort.runFrameLoop} itself, since
     * there the framework owns the loop and {@code onCreate} must return.
     * Desktop gets the same thing via {@link #awaitPlatformLoop()}.
     *
     * @return the engine's frame callback, never null
     */
    public I_FrameCallback frameCallback()
    {
        return frameCallback;
    }

    /**
     * Returns the window port this session booted against.
     *
     * @return the window port, never null — a headless backend returns one
     *     whose {@code isRealWindow()} is false
     */
    public I_WindowPort windowPort()
    {
        return window;
    }

    /**
     * Gives the calling thread to the platform and BLOCKS until the window
     * closes or the game loop ends. Main thread only.
     *
     * This is the desktop path. Android must NOT call it — there the
     * framework owns the loop, so the Activity calls
     * {@code runFrameLoop(session.frameCallback())} and returns.
     *
     * Headless there is nothing to present, so this simply joins the game
     * loop thread rather than spinning on a loop that draws nothing.
     */
    public void awaitPlatformLoop()
    {
        if (!window.isRealWindow())
        {
            joinLoop(0L);

            return;
        }

        window.runFrameLoop(frameCallback);

        // Either the loop ended on its own or the user closed the window.
        // shutdown() is idempotent, so calling it in both cases is fine.
        loop.shutdown();

        joinLoop(EngineMain.LOOP_JOIN_TIMEOUT_MS);
    }

    /**
     * Stops the engine and releases everything {@code start} acquired.
     * Main thread only. Safe to call twice; the second call is a no-op.
     */
    public void stop()
    {
        if (stopped)
        {
            return;
        }

        stopped = true;

        // The loop may still be running when Android tears the Activity
        // down without the window ever closing. Stop it first: order
        // matters, because SharedEventBus.publish() throws once the bus
        // leaves READY, so a live producer would crash the drain below.
        loop.shutdown();

        joinLoop(EngineMain.LOOP_JOIN_TIMEOUT_MS);

        try
        {
            pool.shutdown();

            pool.awaitTermination(POOL_DRAIN_TIMEOUT_MS);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        subsystems.shutdownAll();

        bus.shutdown();

        EngineMain.saveProfile(userProfilePort, profile, timePort);

        hal.shutdown();

        try
        {
            memory.shutdown();
        }
        catch (final RuntimeException ignored)
        {
            // already shut down — fine
        }

        LOG.info("OpenFPS engine shut down cleanly.");
    }

    /**
     * Waits for the game loop thread to exit.
     *
     * @param timeoutMs milliseconds to wait, or 0 to wait indefinitely
     */
    private void joinLoop(final long timeoutMs)
    {
        try
        {
            loopThread.join(timeoutMs);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
