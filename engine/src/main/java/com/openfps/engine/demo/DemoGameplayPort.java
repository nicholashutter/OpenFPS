/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import java.util.concurrent.locks.ReentrantLock;

import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.gameplay.PlayerInputView;
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P_ The demo's per-tic loop: latch input, move the player, aim the camera.
 *
 * <p>This is the join that makes the first-person demo a demo rather than a
 * pile of working parts. Every tic, in this order:</p>
 *
 * <pre>
 *   inputPort.sampleInput(tic)      latch the accumulated mouse and keys
 *   view.wrap(currentInput())       present that snapshot as I_PlayerInput
 *   controller.update(view, dt)     turn, then move
 *   renderer.setCamera(camera)      aim the next frame
 * </pre>
 *
 * <p>The scene is <b>not</b> touched here. It is immutable, it is built once
 * before the loop starts, and nothing in this demo moves except the camera —
 * so rebuilding it per tic would allocate for no reason at all.</p>
 *
 * <h2>Why the delta is a constant</h2>
 *
 * <p>{@code deltaSeconds} comes from {@link GameConfig#nanosPerTic()}, not from
 * a measured frame time, and that is the correct source rather than a
 * convenience. {@code GameLoop} is a <b>fixed-timestep</b> clock: it computes
 * an absolute deadline per tic and corrects drift against it, so a tic
 * <i>is</i> {@code 1 / rate} seconds by construction. The render side is
 * vsync-driven and runs at whatever the display does — feeding
 * {@code Gdx.graphics.getDeltaTime()} into the controller would make movement
 * speed depend on the monitor, which is the exact bug the fixed-rate loop
 * exists to prevent.</p>
 *
 * <h2>Threading — this is why there is a lock</h2>
 *
 * <p>{@code Subsystem}'s Javadoc is explicit that <b>multiple workers may be
 * inside {@code onEvent} for the same subsystem at once</b>, so two adjacent
 * {@code TickEvent}s really can land on two worker threads concurrently.
 * {@link PlayerController} is documented as mutable and not thread-safe, and it
 * is: an unguarded concurrent update would interleave the yaw write with the
 * movement read that depends on it, and the player would slide sideways
 * relative to where they are looking.</p>
 *
 * <p>One lock makes each tic atomic, which is what correctness needs. It does
 * <b>not</b> impose an order on two concurrent tics, and that is deliberate
 * rather than overlooked: the quantities involved — a yaw increment and a
 * displacement — commute over a single 16 ms window, so paying for a sequence
 * number to reject an out-of-order tic would buy a guarantee nothing can
 * observe. If lockstep networking later needs strict tic ordering, it needs it
 * at the bus, not here.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>The {@link PlayerInputView} is created once and re-pointed with
 * {@code wrap} — that is the entire reason it is mutable. The only per-tic
 * allocation is the {@code Camera}, which {@code render/README.md} § 4
 * explicitly sanctions ("Build one per frame") because it is immutable and is
 * the value that crosses to the render workers.</p>
 *
 * <p><b>On importing {@link SoftwareRenderPort}.</b> {@code STYLE.md} § 1.1
 * permits a composition root to name concrete implementations, and this package
 * is the demo's composition root. The import is unavoidable in any case:
 * {@code I_RenderPort} has no {@code setCamera}, and {@code render/README.md}
 * § 12 keeps it that way on purpose — the port renders and stops.</p>
 */
public final class DemoGameplayPort implements I_GameplayPort
{
    private static final Logger LOG = LoggerFactory.getLogger(DemoGameplayPort.class);

    /** Nanoseconds in a second. */
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** Where the latched input comes from. */
    private final I_InputPort inputPort;

    /** Where the camera goes. */
    private final SoftwareRenderPort renderer;

    /** The player's position and heading. Mutated under {@link #tickLock}. */
    private final PlayerController controller;

    /** One reused adapter from {@code InputState} to {@code I_PlayerInput}. */
    private final PlayerInputView inputView = new PlayerInputView();

    /** The fixed tic duration in seconds. See the class Javadoc. */
    private final float deltaSeconds;

    /** Makes one tic atomic against a concurrently dispatched neighbour. */
    private final ReentrantLock tickLock = new ReentrantLock();

    /** Tics applied so far. MUTABLE: bumped under the lock, read for logging. */
    private volatile long ticsApplied;

    /**
     * Creates the demo's gameplay port.
     *
     * @param input the HAL input port to latch each tic; must not be null
     * @param renderPort the renderer to aim; must not be null
     * @param playerController the player to move; must not be null
     * @param config the running configuration, which fixes the tic duration;
     *     must not be null
     */
    public DemoGameplayPort(final I_InputPort input, final SoftwareRenderPort renderPort,
        final PlayerController playerController, final GameConfig config)
    {
        if (input == null)
        {
            throw new IllegalArgumentException("input must not be null");
        }
        if (renderPort == null)
        {
            throw new IllegalArgumentException("renderPort must not be null");
        }
        if (playerController == null)
        {
            throw new IllegalArgumentException("playerController must not be null");
        }
        if (config == null)
        {
            throw new IllegalArgumentException("config must not be null");
        }
        this.inputPort = input;
        this.renderer = renderPort;
        this.controller = playerController;
        this.deltaSeconds = (float) (config.nanosPerTic() / NANOS_PER_SECOND);
    }

    @Override
    public void init()
    {
        LOG.info("Demo gameplay ready: {} s per tic, spawn {}", deltaSeconds, controller);
    }

    @Override
    public void shutdown()
    {
        LOG.info("Demo gameplay stopped after {} tics at {}", ticsApplied, controller);
    }

    /**
     * Advances the player by one tic and points the camera at the result.
     *
     * @param ticIndex the tic being processed
     */
    @Override
    public void tick(final int ticIndex)
    {
        tickLock.lock();
        try
        {
            // Latching is a consuming read — the look deltas are an integral
            // since the previous call, so exactly one caller per tic may do it.
            inputPort.sampleInput(ticIndex);
            inputView.wrap(inputPort.currentInput());
            controller.update(inputView, deltaSeconds);
            aimCamera();
            this.ticsApplied = ticsApplied + 1;
        }
        finally
        {
            tickLock.unlock();
        }
    }

    // Points the renderer at the player's eye.
    //
    // Skipped entirely until a surface exists. The game loop publishes tics
    // from the moment it starts, which on desktop is before the GLFW window has
    // reported its size, and an aspect ratio of 0/0 is a NaN that Camera.create
    // rejects outright. renderFrame is a no-op in that same window, so there is
    // nothing to aim at yet either.
    private void aimCamera()
    {
        final int width = renderer.surfaceWidth();
        final int height = renderer.surfaceHeight();
        if (width <= 0 || height <= 0)
        {
            return;
        }
        renderer.setCamera(controller.camera((float) width / (float) height));
    }

    /**
     * Returns false — the demo loads no maps.
     *
     * <p>The room is a {@code Scene} assembled by {@link DemoScene} before the
     * loop starts, not a map file, so there is nothing here to load by name.
     * Returning false rather than a silent true keeps
     * {@code GameplaySubsystem}'s warning honest if a {@code MapLoadEvent} ever
     * reaches this port.</p>
     *
     * @param mapName ignored
     * @return false, always
     */
    @Override
    public boolean loadMap(final String mapName)
    {
        return false;
    }

    /**
     * Returns -1 — the demo has no entity system.
     *
     * @param entityType ignored
     * @param x ignored
     * @param y ignored
     * @param z ignored
     * @return -1, always
     */
    @Override
    public int spawnEntity(final int entityType, final int x, final int y, final int z)
    {
        return -1;
    }

    /**
     * Does nothing — the demo has no entity system.
     *
     * @param entityId ignored
     */
    @Override
    public void removeEntity(final int entityId)
    {
        // no entities to remove
    }

    /** Returns the player this port moves. Never null. */
    public PlayerController controller()
    {
        return controller;
    }

    /** Returns the fixed tic duration in seconds. */
    public float deltaSeconds()
    {
        return deltaSeconds;
    }

    /** Returns how many tics have been applied since construction. */
    public long ticsApplied()
    {
        return ticsApplied;
    }

    /** Returns a debug rendering of the port's state. */
    @Override
    public String toString()
    {
        return "DemoGameplayPort{dt=" + deltaSeconds + "s, tics=" + ticsApplied
            + ", " + controller + "}";
    }
}
