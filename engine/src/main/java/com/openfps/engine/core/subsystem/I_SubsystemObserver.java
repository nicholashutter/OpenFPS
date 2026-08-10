/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

/**
 * Receives every {@link Subsystem} state-machine transition.
 *
 * <p>An observer is the only way to be told that a subsystem moved
 * from {@code UNINITIALIZED} to {@code READY}, or that an event
 * handler pushed it into {@code ERROR}, or that {@code shutdown()}
 * finished. The {@link SubsystemRegistry} holds a list of
 * observers and dispatches every transition to all of them, in
 * registration order. Observers added after a transition has
 * already happened are not retroactively notified; the registry
 * records the current state of every registered subsystem so a
 * late observer can be primed by a separate callback if it needs
 * the current state.</p>
 *
 * <p>Observers run on whichever thread the transition fired on.
 * The bootstrap thread for {@code init()} and {@code shutdown()};
 * a worker thread for transitions driven by event handlers that
 * throw. Observers must be thread-safe; the event itself is
 * immutable and therefore safe to publish.</p>
 *
 * <p>Observer exceptions are caught and logged by the registry;
 * a misbehaving observer cannot prevent other observers from
 * receiving the same event, and cannot fail the subsystem's
 * state machine. This is a logging seam, not a control seam.</p>
 */
@FunctionalInterface
public interface I_SubsystemObserver
{
    /**
     * Called on every {@link Subsystem} state transition. The
     * event is immutable and the observer is responsible for
     * thread safety; the event may arrive on any thread.
     *
     * @param event the transition that just happened; never null
     */
    void onStateChange(SubsystemStateChangeEvent event);
}
