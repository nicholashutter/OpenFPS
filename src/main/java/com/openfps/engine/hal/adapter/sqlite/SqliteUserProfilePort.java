/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.sqlite;

import com.openfps.engine.common.UserProfile;
import com.openfps.engine.hal.port.I_UserProfilePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed implementation of {@link I_UserProfilePort}, using the
 * Xerial SQLite JDBC driver.
 *
 * The database file lives at {@code <userHome>/.openfps/profile.db}
 * by default. Override the path via the
 * {@code OPENFPS_PROFILE_DB} environment variable.
 *
 * Schema (created on init if missing):
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS user_profile (
 *     id                       TEXT    PRIMARY KEY NOT NULL,
 *     display_name             TEXT    NOT NULL,
 *     audio_volume             REAL    NOT NULL,
 *     mouse_sensitivity        REAL    NOT NULL,
 *     field_of_view            INTEGER NOT NULL,
 *     preferred_color          TEXT    NOT NULL,
 *     last_login_at_epoch_ms   INTEGER NOT NULL,
 *     total_playtime_seconds   INTEGER NOT NULL,
 *     created_at_epoch_ms      INTEGER NOT NULL,
 *     updated_at_epoch_ms      INTEGER NOT NULL
 * );
 * </pre>
 *
 * Concurrency: SQLite is configured in WAL mode with NORMAL
 * synchronization. One connection per write; reads can share. Worker
 * threads can call this port concurrently.
 *
 * References:
 *  - Xerial SQLite JDBC: https://github.com/xerial/sqlite-jdbc
 *  - SQLite WAL mode: https://www.sqlite.org/wal.html
 *  - Room (Android) is the future Android-side implementation of the
 *    same port interface — see {@code hal.adapter.mobile} (Phase 3+).
 */
public final class SqliteUserProfilePort implements I_UserProfilePort
{
    private static final Logger LOG = LoggerFactory.getLogger(SqliteUserProfilePort.class);

    private static final String DEFAULT_DB_DIR = ".openfps";
    private static final String DEFAULT_DB_FILE = "profile.db";
    private static final String ENV_DB_PATH = "OPENFPS_PROFILE_DB";

    private static final String SCHEMA_SQL = """
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
        );
        """;

    private static final String PRAGMAS = """
        PRAGMA journal_mode = WAL;
        PRAGMA synchronous = NORMAL;
        PRAGMA foreign_keys = ON;
        """;

    private Connection connection;
    private String dbPath;
    private volatile State state;

    public SqliteUserProfilePort()
    {
        this.state = State.UNINITIALIZED;
    }

    @Override
    public void init()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("init() called from state SHUTDOWN");
        }
        if (state == State.READY)
        {
            throw new IllegalStateException("init() called from state READY — already initialized");
        }

        this.dbPath = resolveDbPath();
        try
        {
            ensureDirectory(dbPath);
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (final Statement stmt = connection.createStatement())
            {
                stmt.executeUpdate(PRAGMAS);
                stmt.executeUpdate(SCHEMA_SQL);
            }
            state = State.READY;
            LOG.info("SqliteUserProfilePort initialized: db={}", dbPath);
        }
        catch (final SQLException e)
        {
            throw new IllegalStateException("Failed to open SQLite database at " + dbPath, e);
        }
    }

    @Override
    public void shutdown()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("shutdown() called from state SHUTDOWN");
        }
        if (connection != null)
        {
            try
            {
                connection.close();
            }
            catch (final SQLException e)
            {
                LOG.warn("Error closing SQLite connection", e);
            }
            connection = null;
        }
        state = State.SHUTDOWN;
        LOG.info("SqliteUserProfilePort shut down");
    }

    @Override
    public State state()
    {
        return state;
    }

    // ===============================================================
    //  CRUD
    // ===============================================================

    @Override
    public Optional<UserProfile> findById(final String id)
    {
        requireReady();
        if (id == null || id.isBlank())
        {
            return Optional.empty();
        }
        final String sql = "SELECT * FROM user_profile WHERE id = ?";
        try (final PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setString(1, id);
            try (final ResultSet rs = ps.executeQuery())
            {
                if (rs.next())
                {
                    return Optional.of(fromResultSet(rs));
                }
                return Optional.empty();
            }
        }
        catch (final SQLException e)
        {
            throw new IllegalStateException("findById failed for id=" + id, e);
        }
    }

    @Override
    public List<UserProfile> findAll()
    {
        requireReady();
        final String sql = "SELECT * FROM user_profile ORDER BY created_at_epoch_ms ASC";
        final List<UserProfile> result = new ArrayList<>();
        try (final PreparedStatement ps = connection.prepareStatement(sql);
             final ResultSet rs = ps.executeQuery())
        {
            while (rs.next())
            {
                result.add(fromResultSet(rs));
            }
            return result;
        }
        catch (final SQLException e)
        {
            throw new IllegalStateException("findAll failed", e);
        }
    }

    @Override
    public void save(final UserProfile profile)
    {
        requireReady();
        if (profile == null)
        {
            throw new IllegalArgumentException("profile must not be null");
        }

        // SQLite UPSERT (INSERT OR REPLACE) keyed on the primary key
        final String sql = """
            INSERT OR REPLACE INTO user_profile (
                id, display_name, audio_volume, mouse_sensitivity, field_of_view,
                preferred_color, last_login_at_epoch_ms, total_playtime_seconds,
                created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (final PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setString(1,  profile.id());
            ps.setString(2,  profile.displayName());
            ps.setDouble(3,  profile.audioVolume());
            ps.setDouble(4,  profile.mouseSensitivity());
            ps.setInt   (5,  profile.fieldOfView());
            ps.setString(6,  profile.preferredColor());
            ps.setLong  (7,  profile.lastLoginAtEpochMs());
            ps.setLong  (8,  profile.totalPlaytimeSeconds());
            ps.setLong  (9,  profile.createdAtEpochMs());
            ps.setLong  (10, profile.updatedAtEpochMs());
            ps.executeUpdate();
        }
        catch (final SQLException e)
        {
            throw new IllegalStateException("save failed for id=" + profile.id(), e);
        }
    }

    @Override
    public void delete(final String id)
    {
        requireReady();
        if (id == null || id.isBlank())
        {
            return;
        }
        final String sql = "DELETE FROM user_profile WHERE id = ?";
        try (final PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        catch (final SQLException e)
        {
            throw new IllegalStateException("delete failed for id=" + id, e);
        }
    }

    @Override
    public int count()
    {
        requireReady();
        final String sql = "SELECT COUNT(*) FROM user_profile";
        try (final PreparedStatement ps = connection.prepareStatement(sql);
             final ResultSet rs = ps.executeQuery())
        {
            if (rs.next())
            {
                return rs.getInt(1);
            }
            return 0;
        }
        catch (final SQLException e)
        {
            throw new IllegalStateException("count failed", e);
        }
    }

    @Override
    public String generateNewId()
    {
        return UUID.randomUUID().toString();
    }

    // ===============================================================
    //  Internals
    // ===============================================================

    private void requireReady()
    {
        if (state != State.READY)
        {
            throw new IllegalStateException("operation called from state " + state
                + " — only valid from READY");
        }
    }

    private static UserProfile fromResultSet(final ResultSet rs) throws SQLException
    {
        return new UserProfile(
            rs.getString("id"),
            rs.getString("display_name"),
            rs.getDouble("audio_volume"),
            rs.getDouble("mouse_sensitivity"),
            rs.getInt("field_of_view"),
            rs.getString("preferred_color"),
            rs.getLong("last_login_at_epoch_ms"),
            rs.getLong("total_playtime_seconds"),
            rs.getLong("created_at_epoch_ms"),
            rs.getLong("updated_at_epoch_ms")
        );
    }

    private static String resolveDbPath()
    {
        final String override = System.getenv(ENV_DB_PATH);
        if (override != null && !override.isBlank())
        {
            return override;
        }
        final String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank())
        {
            throw new IllegalStateException("user.home system property is not set");
        }
        return userHome + "/" + DEFAULT_DB_DIR + "/" + DEFAULT_DB_FILE;
    }

    private static void ensureDirectory(final String dbPath)
    {
        final int lastSlash = dbPath.lastIndexOf('/');
        if (lastSlash <= 0)
        {
            return;
        }
        final String dirPath = dbPath.substring(0, lastSlash);
        final java.io.File dir = new java.io.File(dirPath);
        if (!dir.exists() && !dir.mkdirs())
        {
            throw new IllegalStateException("Failed to create directory: " + dirPath);
        }
    }

    /** For tests: pass an in-memory SQLite db (":memory:") to bypass file I/O. */
    void initWithInMemoryDb() throws SQLException
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("init() called from state SHUTDOWN");
        }
        if (state == State.READY)
        {
            throw new IllegalStateException("init() called from state READY — already initialized");
        }
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (final Statement stmt = connection.createStatement())
        {
            stmt.executeUpdate(PRAGMAS);
            stmt.executeUpdate(SCHEMA_SQL);
        }
        dbPath = ":memory:";
        state = State.READY;
    }
}
