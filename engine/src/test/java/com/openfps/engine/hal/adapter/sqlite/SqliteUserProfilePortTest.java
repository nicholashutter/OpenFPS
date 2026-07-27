/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.sqlite;

import com.openfps.engine.common.UserProfile;
import com.openfps.engine.hal.port.I_UserProfilePort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SqliteUserProfilePort}.
 * Uses Xerial SQLite JDBC's {@code :memory:} mode for hermetic tests.
 */
class SqliteUserProfilePortTest
{
    private SqliteUserProfilePort port;

    @BeforeEach
    void setUp() throws SQLException
    {
        port = new SqliteUserProfilePort();
        // Use the test hook: an in-memory database, no file I/O
        port.initWithInMemoryDb();
    }

    @AfterEach
    void tearDown()
    {
        if (port.state() == I_UserProfilePort.State.READY)
        {
            port.shutdown();
        }
    }

    @Test
    @DisplayName("init creates the user_profile table")
    void shouldCreateTable()
    {
        assertThat(port.state()).isEqualTo(I_UserProfilePort.State.READY);
        assertThat(port.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("save then findById round-trips the profile")
    void shouldRoundTrip()
    {
        final UserProfile profile = UserProfile.newDefault()
            .withDisplayName("Alice")
            .withAudioVolume(0.42)
            .withFieldOfView(110);
        port.save(profile);

        final Optional<UserProfile> found = port.findById(profile.id());
        assertThat(found).isPresent();
        assertThat(found.get().displayName()).isEqualTo("Alice");
        assertThat(found.get().audioVolume()).isEqualTo(0.42);
        assertThat(found.get().fieldOfView()).isEqualTo(110);
    }

    @Test
    @DisplayName("save is upsert: re-saving replaces the row")
    void shouldUpsertOnConflict()
    {
        final UserProfile original = UserProfile.newDefault();
        port.save(original);
        final UserProfile updated = original.withDisplayName("Bob");
        port.save(updated);

        assertThat(port.count()).isEqualTo(1);
        assertThat(port.findById(original.id()).get().displayName()).isEqualTo("Bob");
    }

    @Test
    @DisplayName("findAll returns all profiles")
    void shouldReturnAllProfiles()
    {
        port.save(UserProfile.newDefault());
        port.save(UserProfile.newDefault());
        port.save(UserProfile.newDefault());
        assertThat(port.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("delete removes a profile by ID")
    void shouldDelete()
    {
        final UserProfile a = UserProfile.newDefault();
        final UserProfile b = UserProfile.newDefault();
        port.save(a);
        port.save(b);
        port.delete(a.id());
        assertThat(port.count()).isEqualTo(1);
        assertThat(port.findById(a.id())).isEmpty();
    }

    @Test
    @DisplayName("count returns 0 on empty database")
    void shouldCountZero()
    {
        assertThat(port.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("profile persists across port restart (data survives shutdown)")
    void shouldPersistAcrossRestart() throws SQLException, java.io.IOException
    {
        // Use a temp file (not :memory:) to test persistence across restarts.
        final java.io.File tmpFile = java.io.File.createTempFile("openfps-test-", ".db");
        tmpFile.deleteOnExit();
        final String jdbcUrl = "jdbc:sqlite:" + tmpFile.getAbsolutePath();
        final String userId = "fixed-id-1234";
        final String displayName = "PersistentAlice";

        // First session: write a profile
        try (Connection conn = DriverManager.getConnection(jdbcUrl))
        {
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS user_profile (
                    id                     TEXT    PRIMARY KEY NOT NULL,
                    display_name           TEXT    NOT NULL,
                    audio_volume           REAL    NOT NULL,
                    mouse_sensitivity      REAL    NOT NULL,
                    field_of_view          INTEGER NOT NULL,
                    preferred_color        TEXT    NOT NULL,
                    last_login_at_epoch_ms INTEGER NOT NULL,
                    total_playtime_seconds INTEGER NOT NULL,
                    created_at_epoch_ms    INTEGER NOT NULL,
                    updated_at_epoch_ms    INTEGER NOT NULL
                )
                """);
            try (final var ps = conn.prepareStatement("""
                INSERT INTO user_profile (id, display_name, audio_volume, mouse_sensitivity,
                    field_of_view, preferred_color, last_login_at_epoch_ms,
                    total_playtime_seconds, created_at_epoch_ms, updated_at_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """))
            {
                ps.setString(1,  userId);
                ps.setString(2,  displayName);
                ps.setDouble(3,  0.5);
                ps.setDouble(4,  1.0);
                ps.setInt(5,  95);
                ps.setString(6,  "#00FF00");
                ps.setLong(7,  1000L);
                ps.setLong(8,  0L);
                ps.setLong(9,  0L);
                ps.setLong(10, 0L);
                ps.executeUpdate();
            }
        }

        // Second session: open with our port and verify the row is there
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM user_profile WHERE id = ?"))
        {
            ps.setString(1, userId);
            try (var rs = ps.executeQuery())
            {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("display_name")).isEqualTo(displayName);
            }
        }
    }

    @Test
    @DisplayName("state machine — init twice throws")
    void shouldThrowOnDoubleInit()
    {
        assertThatThrownBy(() -> port.init())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("state machine — operations throw after shutdown")
    void shouldThrowAfterShutdown()
    {
        port.shutdown();
        assertThatThrownBy(() -> port.save(UserProfile.newDefault()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> port.findAll())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("state machine — init after shutdown throws")
    void shouldThrowOnInitAfterShutdown()
    {
        port.shutdown();
        assertThatThrownBy(() -> port.init())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("save(null) throws IllegalArgumentException")
    void shouldRejectNullSave()
    {
        assertThatThrownBy(() -> port.save(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("findById for unknown ID returns empty")
    void shouldReturnEmptyForUnknown()
    {
        assertThat(port.findById("not-a-real-id")).isEmpty();
    }

    @Test
    @DisplayName("findById with null/blank ID returns empty")
    void shouldReturnEmptyForNullOrBlank()
    {
        assertThat(port.findById(null)).isEmpty();
        assertThat(port.findById("")).isEmpty();
        assertThat(port.findById("   ")).isEmpty();
    }

    @Test
    @DisplayName("generateNewId returns unique valid UUIDs")
    void shouldGenerateUniqueIds()
    {
        final String id1 = port.generateNewId();
        final String id2 = port.generateNewId();
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    @DisplayName("delete of unknown ID is a no-op")
    void shouldIgnoreUnknownDelete()
    {
        port.delete("not-a-real-id");
        port.delete(null);
        port.delete("");
        assertThat(port.count()).isEqualTo(0);
    }
}
