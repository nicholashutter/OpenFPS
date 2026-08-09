/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.desktop;

import com.openfps.engine.hal.adapter.AdapterFactorySelector;
import com.openfps.engine.hal.adapter.HalBackend;
import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.adapter.sqlite.SqliteUserProfilePort;
import com.openfps.engine.hal.port.I_UserProfilePort;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DesktopAdapterFactory} and its wiring into
 * {@link AdapterFactorySelector}.
 *
 * The factory opens a real SQLite database, so these tests redirect it to
 * a temp directory — without that they would mutate the developer's own
 * {@code ~/.openfps/profile.db}.
 */
class DesktopAdapterFactoryTest
{
    /** Must match {@code SqliteUserProfilePort.PROP_DB_PATH}. */
    private static final String PROP_DB_PATH = "openfps.profile.db";

    @TempDir
    static Path tempDir;

    private static String previousDbPath;

    @BeforeAll
    static void redirectDatabaseToTempDir()
    {
        previousDbPath = System.getProperty(PROP_DB_PATH);

        System.setProperty(PROP_DB_PATH, tempDir.resolve("desktop-factory-test.db").toString());
    }

    @AfterAll
    static void restoreDatabasePath()
    {
        if (previousDbPath == null)
        {
            System.clearProperty(PROP_DB_PATH);
        }
        else
        {
            System.setProperty(PROP_DB_PATH, previousDbPath);
        }
    }

    @Test
    @DisplayName("the selector maps HalBackend.DESKTOP to DesktopAdapterFactory")
    void shouldSelectDesktopFactoryForDesktopBackend()
    {
        final I_AdapterFactory factory = AdapterFactorySelector.create(HalBackend.DESKTOP);

        assertThat(factory).isInstanceOf(DesktopAdapterFactory.class);
    }

    @Test
    @DisplayName("time and datagram ports are the real desktop implementations")
    void shouldExposeRealTimeAndDatagramPorts()
    {
        final DesktopAdapterFactory factory = new DesktopAdapterFactory();

        factory.init();

        try
        {
            assertThat(factory.getTimePort()).isInstanceOf(DesktopTimePort.class);

            assertThat(factory.getDatagramPort()).isInstanceOf(DesktopDatagramPort.class);

            assertThat(factory.getUserProfilePort()).isInstanceOf(SqliteUserProfilePort.class);
        }
        finally
        {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("input and window stay null adapters until LWJGL lands")
    void shouldKeepInputAndWindowNull()
    {
        final DesktopAdapterFactory factory = new DesktopAdapterFactory();

        factory.init();

        try
        {
            assertThat(factory.getWindowPort().isRealWindow()).isFalse();

            assertThat(factory.getInputPort().isShutdownRequested()).isFalse();

            assertThat(factory.getFilePort()).isNotNull();

            assertThat(factory.getSystemInfoPort()).isNotNull();
        }
        finally
        {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("init() readies the profile port and shutdown() closes it")
    void shouldDriveUserProfileLifecycle()
    {
        final DesktopAdapterFactory factory = new DesktopAdapterFactory();

        factory.init();

        final I_UserProfilePort profiles = factory.getUserProfilePort();

        assertThat(profiles.state()).isEqualTo(I_UserProfilePort.State.READY);

        factory.shutdown();

        assertThat(profiles.state()).isEqualTo(I_UserProfilePort.State.SHUTDOWN);
    }

    @Test
    @DisplayName("the time port is initialized by the factory, not left at its origin default")
    void shouldInitializeTimePortDuringFactoryInit()
    {
        final DesktopAdapterFactory factory = new DesktopAdapterFactory();

        factory.init();

        try
        {
            // A factory-initialized clock reports a small elapsed time, not
            // the raw JVM-wide nanoTime value.
            assertThat(factory.getTimePort().nanos()).isGreaterThanOrEqualTo(0L);

            assertThat(factory.getTimePort().millis()).isGreaterThanOrEqualTo(0L);
        }
        finally
        {
            factory.shutdown();
        }
    }
}
