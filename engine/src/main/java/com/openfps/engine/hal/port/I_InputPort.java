/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — input sampling.
 * Reads keyboard, mouse, and gamepad state each tic and
 * makes it available to the gameplay subsystem.
 *
 * <p><b>Sample and read are two calls, on purpose.</b>
 * {@link #sampleInput(int)} latches the device into one immutable
 * {@link InputState}; {@link #currentInput()} hands out whatever was last
 * latched and changes nothing. A caller that wants the player's input for tic
 * <i>n</i> calls the first once and the second as often as it likes.</p>
 *
 * <p>The split is not ceremony — it is what makes lockstep possible. Under
 * lockstep every peer must simulate tic <i>n</i> from bit-identical input, so
 * a tic has to consume one <i>stable</i> snapshot rather than race a live
 * device. If gameplay read the mouse directly, two reads inside the same tic
 * would disagree, the snapshot could never be serialised into a
 * {@code TicCmd} for the network, and a replay would have nothing fixed to
 * replay. Latching once per tic gives the simulation a value that is frozen
 * for the whole tic, identical on every read, and trivially copyable onto the
 * wire.</p>
 *
 * <p>It also decouples the two clocks. The device is polled by whatever owns
 * the platform loop — vsync-driven, and typically a different and varying rate
 * from the simulation's fixed 30/60/120 Hz. An adapter accumulates relative
 * motion between latches so no mouse movement is dropped when rendering runs
 * slow, and none is counted twice when it runs fast.</p>
 */
public interface I_InputPort
{
    /**
     * Latches the current device state as the snapshot for the given tic.
     *
     * <p>Called exactly once per tic, from the game loop thread, before
     * gameplay runs. Everything relative — mouse motion, and any action that
     * was pressed and released between two tics — is consumed here: after this
     * returns, the adapter's accumulators are empty and begin gathering the
     * <i>next</i> tic's motion. Calling it twice for one tic therefore splits
     * that tic's rotation across two snapshots, and skipping it leaves
     * {@link #currentInput()} returning the previous tic's values.</p>
     *
     * @param ticIndex the tic being processed
     */
    void sampleInput(int ticIndex);

    /**
     * Returns the snapshot latched by the most recent {@link #sampleInput}.
     *
     * <p>Pure: repeated calls between two {@code sampleInput} calls return the
     * same values, and none of them touch the device. Before the first
     * {@code sampleInput}, and for any port with no real device behind it,
     * this is {@link InputState#NEUTRAL}.</p>
     *
     * @return the current immutable snapshot; never null
     */
    InputState currentInput();

    /**
     * Returns whether the engine should shut down (e.g., window closed).
     *
     * @return true if shutdown is requested
     */
    boolean isShutdownRequested();

    /**
     * Initializes the input subsystem. Called once at engine startup.
     */
    void init();

    /**
     * Shuts down the input subsystem. Called once at engine shutdown.
     */
    void shutdown();
}
