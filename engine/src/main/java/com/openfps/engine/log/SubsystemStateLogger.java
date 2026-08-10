/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import com.openfps.engine.core.subsystem.I_SubsystemObserver;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.core.subsystem.SubsystemState;
import com.openfps.engine.core.subsystem.SubsystemStateChangeEvent;

/**
 * The bridge from the engine's subsystem state machine to the
 * engine's main log bus.
 *
 * <p>Every {@link SubsystemStateChangeEvent} is published to the
 * log bus on the appropriate subsystem channel (the same channel
 * the existing SLF4J bridge routes the subsystem's own
 * {@code LOG.info} calls to), and the message is shaped to match
 * the bridge's format so a downstream consumer cannot tell which
 * path an event took. The {@code engine.core} channel is the
 * default for subsystems whose id does not have a named channel
 * &mdash; there is no log bus named for the resource subsystem,
 * for example, so its events land on {@code engine.core}.</p>
 *
 * <p>Register one of these with the {@code SubsystemRegistry} at
 * engine bootstrap, and every state transition of every
 * subsystem is observable through the log bus. A debug overlay,
 * a file writer, or any other consumer on the main bus sees them
 * in arrival order, in the same shape as every other engine log
 * line.</p>
 *
 * <p>Threading: events arrive on whatever thread the transition
 * fired on. The log bus's {@code publish} is non-blocking and
 * thread-safe; the observer does not need its own synchronisation
 * for the publish call. Observer exceptions are caught by the
 * registry, not here &mdash; this class is itself a passive
 * observer; the registry is the one that catches and logs.</p>
 */
public final class SubsystemStateLogger implements I_SubsystemObserver
{
    /**
     * The default channel for subsystems whose id has no
     * dedicated log bus. {@code engine.core} is the engine
     * bootstrap channel and is the natural place for
     * uncategorised events. The resource subsystem lands here
     * today; the mapping is one-line if a dedicated channel is
     * wanted later.
     */
    private static final String DEFAULT_CHANNEL = "engine.core";

    /**
     * Maps a {@link SubsystemId} to the log-bus channel name
     * the event should be published to. The map is kept here
     * rather than on the enum itself because the engine
     * subsystem package has no dependency on the log package;
     * the direction of the dependency is the other way, and
     * adding {@code core.subsystem -> log} would invert it.
     */
    private static final String CHANNEL_BY_ID = buildChannelById();

    private static String buildChannelById()
    {
        // SubsystemId -> log bus channel. The resource subsystem
        // (W_) currently has no dedicated channel and lands on
        // engine.core by way of the default branch in
        // channelFor. The remaining 7 ids are listed for the
        // reader's benefit; a future dedicated channel for W_
        // would land here.
        return null;
    }

    /**
     * Returns the log-bus channel for a given subsystem id.
     */
    private static String channelFor(final SubsystemId id)
    {
        switch (id)
        {
            case CORE: return "engine.core";
            case I_:   return "engine.hal";
            case Z_:   return "engine.memory";
            case P_:   return "engine.gameplay";
            case G_:   return "engine.net";
            case S_:   return "engine.audio";
            case R_:   return "engine.render";
            case W_:   return DEFAULT_CHANNEL;
            default:   return DEFAULT_CHANNEL;
        }
    }

    /**
     * Builds a logger ready to be registered with the
     * subsystem registry. The returned observer is stateless
     * and safe to share across registries.
     */
    public static SubsystemStateLogger install()
    {
        return new SubsystemStateLogger();
    }

    @Override
    public void onStateChange(final SubsystemStateChangeEvent event)
    {
        if (event == null)
        {
            return;
        }

        final String channel = channelFor(event.subsystemId());

        final I_LogBus bus = LogBusFactory.subsystem(channel);

        final String message = formatMessage(event);

        final LogLevel level;

        if (event.isErrorTransition())
        {
            level = LogLevel.WARN;
        }
        else
        {
            level = LogLevel.INFO;
        }

        bus.publish(new LogEvent(System.currentTimeMillis(), channel,
            "SubsystemStateLogger", level, message, event.cause()));
    }

    /**
     * Shapes the message the way a human reading a log file
     * would expect to see it: subsystem id, the transition
     * itself, an optional cause for error transitions.
     */
    private static String formatMessage(final SubsystemStateChangeEvent event)
    {
        final SubsystemState from = event.fromState();

        final SubsystemState to = event.toState();

        if (event.isErrorTransition() && event.cause() != null)
        {
            return "subsystem " + event.subsystemId() + " " + from + " -> " + to
                + " (" + event.cause().getClass().getSimpleName() + ": "
                + event.cause().getMessage() + ")";
        }

        return "subsystem " + event.subsystemId() + " " + from + " -> " + to;
    }
}
