/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

import java.util.Objects;

/**
 * Immutable record of one subsystem state-machine transition.
 *
 * <p>Every transition of every {@link Subsystem} emits one of these
 * through the observer list the {@link SubsystemRegistry} holds. The
 * event carries the {@link SubsystemId}, the previous and new
 * {@link SubsystemState}, a wall-clock timestamp, and an optional
 * cause (the exception that drove an error transition; null
 * otherwise).</p>
 *
 * <p>Events are produced on whatever thread the transition runs on
 * &mdash; a subsystem's {@code init()} runs on the engine's bootstrap
 * thread, but a transition driven by an event handler that throws
 * may run on a worker thread. Observers must be thread-safe.</p>
 *
 * <p>Threading: the event is immutable and therefore safe to publish
 * across threads without synchronisation. The observer is the unit
 * that has to be thread-safe; the event itself is a value.</p>
 */
public final class SubsystemStateChangeEvent
{
    private final SubsystemId subsystemId;
    private final SubsystemState fromState;
    private final SubsystemState toState;
    private final long timestampMs;
    private final Throwable cause;

    /**
     * Builds the event.
     *
     * @param subsystemId the subsystem that transitioned; must not be null
     * @param fromState the state before the transition; must not be null
     * @param toState the state after the transition; must not be null
     * @param timestampMs wall-clock time of the transition, in ms
     * @param cause the exception that drove an error transition, or
     *     null for a non-error transition
     */
    public SubsystemStateChangeEvent(final SubsystemId subsystemId, final SubsystemState fromState,
        final SubsystemState toState, final long timestampMs, final Throwable cause)
    {
        if (subsystemId == null)
        {
            throw new IllegalArgumentException("subsystemId must not be null");
        }

        if (fromState == null)
        {
            throw new IllegalArgumentException("fromState must not be null");
        }

        if (toState == null)
        {
            throw new IllegalArgumentException("toState must not be null");
        }

        this.subsystemId = subsystemId;

        this.fromState = fromState;

        this.toState = toState;

        this.timestampMs = timestampMs;

        this.cause = cause;
    }

    /** The subsystem that transitioned. Never null. */
    public SubsystemId subsystemId()
    {
        return subsystemId;
    }

    /** The state the subsystem was in before the transition. Never null. */
    public SubsystemState fromState()
    {
        return fromState;
    }

    /** The state the subsystem is in after the transition. Never null. */
    public SubsystemState toState()
    {
        return toState;
    }

    /** Wall-clock time of the transition, in ms since the epoch. */
    public long timestampMs()
    {
        return timestampMs;
    }

    /**
     * The exception that drove the transition, or null for a
     * non-error transition.
     */
    public Throwable cause()
    {
        return cause;
    }

    /**
     * Returns true when the new state is {@link SubsystemState#ERROR}.
     */
    public boolean isErrorTransition()
    {
        return toState == SubsystemState.ERROR;
    }

    @Override
    public String toString()
    {
        final StringBuilder builder = new StringBuilder("SubsystemStateChange{id=");

        builder.append(subsystemId);

        builder.append(" ").append(fromState).append("->").append(toState);

        if (cause != null)
        {
            builder.append(" cause=").append(cause.getClass().getSimpleName());
        }

        builder.append("}");

        return builder.toString();
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof SubsystemStateChangeEvent))
        {
            return false;
        }

        final SubsystemStateChangeEvent that = (SubsystemStateChangeEvent) other;

        return this.subsystemId == that.subsystemId
            && this.fromState == that.fromState
            && this.toState == that.toState
            && this.timestampMs == that.timestampMs
            && Objects.equals(this.cause, that.cause);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(subsystemId, fromState, toState, timestampMs, cause);
    }
}
