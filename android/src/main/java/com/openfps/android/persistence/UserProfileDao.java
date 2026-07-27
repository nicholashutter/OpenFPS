/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android.persistence;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Room DAO for {@link UserProfileEntity}.
 *
 * Every method is SYNCHRONOUS, deliberately. {@code I_UserProfilePort} is a
 * synchronous contract shared with the desktop SQLite adapter, and making
 * the Android side async would force the port — and the engine bootstrap
 * that calls it — to become async for one platform's benefit. See
 * {@code RoomUserProfilePort} for how the main-thread constraint is handled.
 */
@Dao
public interface UserProfileDao
{
    /**
     * Finds one profile by ID.
     *
     * @param id the profile UUID
     * @return the row, or null if absent
     */
    @Query("SELECT * FROM user_profile WHERE id = :id LIMIT 1")
    UserProfileEntity findById(String id);

    /**
     * Returns every profile, oldest first.
     *
     * Ordering is part of the port contract, not a convenience: the engine
     * bootstrap picks a profile from this list, so an unordered result would
     * make which profile loads depend on storage layout.
     *
     * @return all rows ordered by creation time ascending
     */
    @Query("SELECT * FROM user_profile ORDER BY created_at_epoch_ms ASC")
    List<UserProfileEntity> findAll();

    /**
     * Inserts or replaces a profile.
     *
     * @param profile the row to write
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(UserProfileEntity profile);

    /**
     * Deletes a profile by ID. No-op when absent.
     *
     * @param id the profile UUID
     */
    @Query("DELETE FROM user_profile WHERE id = :id")
    void deleteById(String id);

    /**
     * Counts stored profiles.
     *
     * @return the row count
     */
    @Query("SELECT COUNT(*) FROM user_profile")
    int count();
}
