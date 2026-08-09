/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android.persistence;

import android.content.Context;

import androidx.room.Room;

import com.openfps.engine.common.UserProfile;
import com.openfps.engine.hal.port.I_UserProfilePort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Android implementation of {@link I_UserProfilePort}, backed by Room.
 *
 * This is the counterpart to {@code SqliteUserProfilePort} on desktop, and
 * the reason {@code sqlite-jdbc} is excluded from this module: that jar
 * carries ~20 desktop native triplets the JVM loads via JNI, none of which
 * Android can use. Room wraps the SQLite that already ships with the OS.
 * The engine sees neither — it only ever holds {@link I_UserProfilePort}.
 *
 * <b>On allowMainThreadQueries.</b> Room blocks synchronous queries on the
 * main thread by default, and that default is normally right. It is
 * deliberately disabled here, for reasons that are worth stating rather than
 * hiding:
 *
 *  1. {@link I_UserProfilePort} is a SYNCHRONOUS contract shared with the
 *     desktop adapter. Making it async for Android's sake would push
 *     callbacks or futures into the engine bootstrap on every platform.
 *  2. The engine touches this port exactly twice per session — one
 *     {@code findAll()} during {@code onCreate} and one {@code save()}
 *     during {@code onDestroy} — over a table holding a handful of
 *     single-row profiles. This is not a query that grows with play.
 *
 * If profile access ever moves into the frame path, this decision must be
 * revisited rather than inherited: a blocking query per frame would drop
 * frames, and the main-thread guard exists precisely to catch that.
 *
 * <b>State machine</b> mirrors every other port: UNINITIALIZED → READY →
 * SHUTDOWN, with operations outside READY throwing rather than failing
 * quietly.
 *
 * Platform adapter — must not import from core engine packages beyond the
 * port it implements and the model it stores.
 */
public final class RoomUserProfilePort implements I_UserProfilePort
{
    /** Application context, used to open the database. */
    private final Context context;

    /** Lifecycle state. MUTABLE: advanced by init() and shutdown(). */
    private State state = State.UNINITIALIZED;

    /** The open database. MUTABLE: created in init(), closed in shutdown(). */
    private OpenFpsDatabase database;

    /** The DAO. MUTABLE: obtained in init(), released in shutdown(). */
    private UserProfileDao dao;

    /**
     * Creates the port.
     *
     * @param context any context; the application context is taken from it
     *     so the port cannot outlive-leak an Activity
     */
    public RoomUserProfilePort(final Context context)
    {
        if (context == null)
        {
            throw new IllegalArgumentException("context must not be null");
        }

        this.context = context.getApplicationContext();
    }

    @Override
    public void init()
    {
        if (state != State.UNINITIALIZED)
        {
            throw new IllegalStateException(
                "init() called from state " + state + " — only valid from UNINITIALIZED");
        }

        database = Room.databaseBuilder(context, OpenFpsDatabase.class, OpenFpsDatabase.DB_NAME)
            .allowMainThreadQueries()
            .build();

        dao = database.userProfileDao();

        state = State.READY;
    }

    @Override
    public void shutdown()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("shutdown() called from state SHUTDOWN — already terminal");
        }

        if (database != null)
        {
            database.close();

            database = null;
        }

        dao = null;

        state = State.SHUTDOWN;
    }

    @Override
    public State state()
    {
        return state;
    }

    @Override
    public Optional<UserProfile> findById(final String id)
    {
        requireReady("findById");

        if (id == null || id.isBlank())
        {
            return Optional.empty();
        }

        final UserProfileEntity row = dao.findById(id);

        if (row == null)
        {
            return Optional.empty();
        }

        return Optional.of(row.toDomain());
    }

    @Override
    public List<UserProfile> findAll()
    {
        requireReady("findAll");

        final List<UserProfileEntity> rows = dao.findAll();

        final List<UserProfile> out = new ArrayList<>(rows.size());

        for (final UserProfileEntity row : rows)
        {
            out.add(row.toDomain());
        }

        return out;
    }

    @Override
    public void save(final UserProfile profile)
    {
        requireReady("save");

        if (profile == null)
        {
            throw new IllegalArgumentException("profile must not be null");
        }

        dao.upsert(UserProfileEntity.fromDomain(profile));
    }

    @Override
    public void delete(final String id)
    {
        requireReady("delete");

        if (id == null || id.isBlank())
        {
            return;
        }

        dao.deleteById(id);
    }

    @Override
    public int count()
    {
        requireReady("count");

        return dao.count();
    }

    @Override
    public String generateNewId()
    {
        return UUID.randomUUID().toString();
    }

    /**
     * Throws unless the port is READY.
     *
     * @param operation the caller's name, for the message
     */
    private void requireReady(final String operation)
    {
        if (state != State.READY)
        {
            throw new IllegalStateException(
                operation + "() called from state " + state + " — only valid from READY");
        }
    }
}
