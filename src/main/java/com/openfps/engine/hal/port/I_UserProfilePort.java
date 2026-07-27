/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

import com.openfps.engine.common.UserProfile;

import java.util.List;
import java.util.Optional;

/**
 * I_ Port interface — user profile persistence.
 *
 * Persists {@link UserProfile} records. The default desktop
 * implementation uses Xerial SQLite JDBC (in
 * {@code hal.adapter.sqlite}). The Android implementation will use
 * Room (in {@code hal.adapter.mobile}, Phase 3+). The in-memory
 * implementation lives in {@code hal.adapter.nulladapter} for tests.
 *
 * State machine: UNINITIALIZED → READY → SHUTDOWN.
 *
 * Threading: implementations must be safe to call from multiple
 * worker threads concurrently.
 */
public interface I_UserProfilePort
{
    // ===============================================================
    //  State machine
    // ===============================================================

    /** Port lifecycle states. */
    enum State
    {
        /** Default state at construction. Must call init() to advance. */
        UNINITIALIZED,
        /** Database open; CRUD operations are valid. */
        READY,
        /** Terminal state. Database closed; all operations throw. */
        SHUTDOWN
    }

    /** UNINITIALIZED → READY. Opens the underlying database. */
    void init();

    /** READY → SHUTDOWN. Closes the database. After this, all operations throw. */
    void shutdown();

    State state();

    // ===============================================================
    //  CRUD
    // ===============================================================

    /**
     * Finds a profile by its ID.
     *
     * @param id the profile's UUID
     * @return the profile, or {@code Optional.empty()} if not found
     * @throws IllegalStateException if not READY
     */
    Optional<UserProfile> findById(String id);

    /**
     * Returns all profiles, ordered by {@code createdAtEpochMs} ascending.
     * Used by the lobby to list users.
     */
    List<UserProfile> findAll();

    /**
     * Upserts a profile. If a profile with the same ID exists, it is
     * replaced. The {@code updatedAtEpochMs} is NOT automatically
     * updated — the caller should set it explicitly via
     * {@code withUpdatedAt(now)} before saving.
     */
    void save(UserProfile profile);

    /**
     * Deletes a profile by ID. No-op if the ID is not present.
     */
    void delete(String id);

    /**
     * Returns the number of profiles in the database.
     */
    int count();

    /**
     * Generates a new random user ID. The same ID format used by
     * {@code UserProfile.newDefault()}.
     */
    String generateNewId();
}
