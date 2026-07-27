/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter;

import com.openfps.engine.hal.port.I_UserProfilePort;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link I_AdapterFactory} implementations.
 *
 * Every backend must hand back a complete, non-null set of ports so the
 * engine has exactly one code path regardless of which one is selected.
 *
 * DESKTOP is excluded — it needs a display, so it is covered by the
 * {@code displayTest} task instead.
 *
 * The SQLITE backend opens a real database file, so these tests redirect
 * it to a temp directory. Without that they would open (and mutate) the
 * developer's own {@code ~/.openfps/profile.db}.
 */
class AdapterFactoryTest
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
        System.setProperty(PROP_DB_PATH, tempDir.resolve("adapter-factory-test.db").toString());
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

    /** Backends that are safe to construct headlessly. */
    private enum HeadlessBackend
    {
        NULL,
        SQLITE
    }

    private static I_AdapterFactory create(final HeadlessBackend backend)
    {
        return AdapterFactorySelector.create(HalBackend.valueOf(backend.name()));
    }

    @ParameterizedTest
    @EnumSource(HeadlessBackend.class)
    @DisplayName("every port getter returns non-null after init")
    void shouldReturnAllPortsWhenInitialized(final HeadlessBackend backend)
    {
        final I_AdapterFactory factory = create(backend);
        factory.init();
        try
        {
            assertThat(factory.getTimePort()).isNotNull();
            assertThat(factory.getInputPort()).isNotNull();
            assertThat(factory.getDatagramPort()).isNotNull();
            assertThat(factory.getFilePort()).isNotNull();
            assertThat(factory.getSystemInfoPort()).isNotNull();
            assertThat(factory.getUserProfilePort()).isNotNull();
            assertThat(factory.getWindowPort()).isNotNull();
        }
        finally
        {
            factory.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(HeadlessBackend.class)
    @DisplayName("the user profile port is READY after init and SHUTDOWN after shutdown")
    void shouldDriveUserProfileLifecycle(final HeadlessBackend backend)
    {
        final I_AdapterFactory factory = create(backend);
        factory.init();
        final I_UserProfilePort profiles = factory.getUserProfilePort();
        assertThat(profiles.state()).isEqualTo(I_UserProfilePort.State.READY);

        factory.shutdown();
        assertThat(profiles.state()).isEqualTo(I_UserProfilePort.State.SHUTDOWN);
    }

    @ParameterizedTest
    @EnumSource(HeadlessBackend.class)
    @DisplayName("no headless backend claims to own a real window")
    void shouldNotReportRealWindowWhenHeadless(final HeadlessBackend backend)
    {
        final I_AdapterFactory factory = create(backend);
        factory.init();
        try
        {
            assertThat(factory.getWindowPort().isRealWindow()).isFalse();
            assertThat(factory.getWindowPort().isCloseRequested()).isFalse();
        }
        finally
        {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("selector rejects a null backend")
    void shouldRejectNullBackend()
    {
        assertThatThrownBy(() -> AdapterFactorySelector.create(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("DESKTOP backend fails loudly until Phase 1.5 step 4 lands")
    void shouldRejectDesktopBackendUntilImplemented()
    {
        assertThatThrownBy(() -> AdapterFactorySelector.create(HalBackend.DESKTOP))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("not implemented yet");
    }
}
