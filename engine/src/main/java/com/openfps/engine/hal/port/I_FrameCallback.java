/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — what the platform calls back into when it owns the
 * frame loop.
 *
 * <b>Why this exists.</b> An earlier {@code I_WindowPort} exposed
 * {@code pumpEvents()} and {@code present()} for the engine to call in its
 * own loop. That is a GLFW shape and it does not survive contact with
 * Android, where the OS owns the loop: the framework drives a
 * {@code GLSurfaceView} thread and calls {@code onDrawFrame} when it is
 * ready. There is nothing to pump and no loop to write.
 *
 * So control is inverted. The platform runs the loop; the engine supplies
 * this callback. Desktop implements it with a while-loop, Android with the
 * Activity lifecycle, and neither leaks its shape into the other.
 *
 * <b>What this is NOT.</b> This is presentation, not simulation. The
 * engine's {@code GameLoop} remains the simulation clock on its own thread,
 * ticking at a fixed 30/60/120 Hz with absolute deadlines, because lockstep
 * determinism requires two peers at the same tic to be at the same point in
 * the simulation. Platform frame rate is whatever the display and the OS
 * decide, and it must never drive a tic. {@link #onFrame} draws the latest
 * state; it does not advance it.
 *
 * <b>Threading.</b> Every method here is called on the platform's render
 * thread, which is NOT the game loop thread. On desktop that happens to be
 * the main thread; on Android it is the GL thread the framework created.
 * Do not assume either. Anything shared with the simulation must be
 * published safely.
 */
public interface I_FrameCallback
{
    /**
     * Called once when the drawing surface and graphics context exist.
     * Create GPU resources here, not in the constructor — on Android there
     * is no context until the surface is ready.
     *
     * @param width surface width in pixels
     * @param height surface height in pixels
     */
    void onSurfaceReady(int width, int height);

    /**
     * Called once per platform frame. Draw the current state; do not
     * advance the simulation.
     *
     * @param deltaSeconds wall time since the previous frame, for
     *     interpolation and UI animation only — never for physics
     */
    void onFrame(float deltaSeconds);

    /**
     * Called when the surface changes size: a desktop window resize, or an
     * Android rotation.
     *
     * @param width new surface width in pixels
     * @param height new surface height in pixels
     */
    void onResize(int width, int height);

    /**
     * Called when the app stops being visible. On Android this fires every
     * time the user switches away and is the last chance to persist state —
     * the process may be killed without further warning. On desktop it
     * fires on minimize, if at all.
     */
    void onPause();

    /** Called when the app becomes visible again. */
    void onResume();

    /**
     * Called when the surface is going away for good. Release GPU
     * resources. On Android the graphics context can be destroyed and
     * later recreated while the process lives on, so this is not
     * necessarily followed by process death.
     */
    void onSurfaceLost();
}
