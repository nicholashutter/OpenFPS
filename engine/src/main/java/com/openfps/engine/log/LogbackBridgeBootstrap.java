/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.slf4j.LoggerFactory;

/**
 * Wires the engine's log bus to logback at runtime, so every existing
 * SLF4J call in the engine flows into the bus without the logback
 * config needing to know the bus exists.
 *
 * <p>The bootstrap is a single class with two static methods. A caller
 * (the engine's bootstrap, the tools' main entry points) calls
 * {@link #install()} once, then every subsequent SLF4J log line is
 * also published to the bus. {@link #uninstall()} reverses the
 * change for tests that need a clean logback context.</p>
 *
 * <h2>Why this lives in the engine rather than logback config</h2>
 *
 * <p>Logback's XML can declare an appender, but the appender needs
 * a bus reference at construction time. The bus is built lazily by
 * {@link LogBusFactory}, so the cleanest seam is a small bootstrap
 * the engine calls after the factory is warm. The XML stays small
 * (the STDOUT appender is the only one logback configures), and
 * the bus side stays in code where the factory is.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #install} takes the slf4j root logger's appender list
 * lock, so two simultaneous install calls are safe. The result of a
 * second call is a no-op &mdash; the bus is already wired.</p>
 */
public final class LogbackBridgeBootstrap
{
    /** The name the install registers the bridge under. */
    public static final String APPENDER_NAME = "ENGINE_BUS_BRIDGE";

    private LogbackBridgeBootstrap()
    {
        // Static utility.
    }

    /**
     * Installs a {@link Slf4jLogBusBridge} on the slf4j root logger
     * if one is not already installed. The bridge publishes to
     * {@link LogBusFactory#main()}.
     *
     * @return the bridge that was installed, or the one already there
     */
    public static Slf4jLogBusBridge install()
    {
        final Logger root = rootLogger();

        if (root == null)
        {
            // logback is not the slf4j binding on this classpath; the
            // bridge has nothing to attach to. The bus still works for
            // direct publishes; only the slf4j -> bus half is missing.
            return null;
        }

        // logback's Logger does not expose an appender iterator; the
        // public API is getAppender(name). That's enough for the bridge
        // because the only name the install ever uses is APPENDER_NAME.
        final Appender<ILoggingEvent> existing = root.getAppender(APPENDER_NAME);

        if (existing instanceof Slf4jLogBusBridge)
        {
            return (Slf4jLogBusBridge) existing;
        }

        final Slf4jLogBusBridge bridge = Slf4jLogBusBridge.toMainBus();

        bridge.setName(APPENDER_NAME);

        bridge.setContext(root.getLoggerContext());

        bridge.start();

        root.addAppender(bridge);

        return bridge;
    }

    /**
     * Removes a previously-installed bridge from the root logger.
     * Idempotent. Used by tests.
     */
    public static void uninstall()
    {
        final Logger root = rootLogger();

        if (root == null)
        {
            return;
        }

        final Appender<?> existing = root.getAppender(APPENDER_NAME);

        if (existing != null)
        {
            existing.stop();

            root.detachAppender(APPENDER_NAME);
        }
    }

    // Returns the slf4j root logger cast to logback's Logger, or null
    // if the slf4j binding is not logback. The cast is safe because
    // every other slf4j binding gives a non-Logger root.
    private static Logger rootLogger()
    {
        final org.slf4j.Logger raw = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        if (!(raw instanceof Logger))
        {
            return null;
        }

        return (Logger) raw;
    }

    /**
     * Returns the logback {@link LoggerContext} for the root logger,
     * or null if logback is not the active binding. Useful for code
     * that wants to set the context on a custom appender.
     *
     * @return the root logger context, or null
     */
    public static LoggerContext rootContext()
    {
        final Logger root = rootLogger();

        if (root == null)
        {
            return null;
        }

        return root.getLoggerContext();
    }
}
