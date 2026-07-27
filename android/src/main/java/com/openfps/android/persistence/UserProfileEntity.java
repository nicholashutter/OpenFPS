/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android.persistence;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.openfps.engine.common.UserProfile;

/**
 * Room row for a {@link UserProfile}.
 *
 * <b>Why this exists rather than annotating UserProfile.</b> {@code UserProfile}
 * lives in {@code :engine}, which is pure Java 17 with no Android dependency —
 * annotating it with {@code @Entity} would drag Room into a module that has to
 * compile and test on a machine with no Android SDK. So the engine type stays
 * clean and this is the storage shape, converted at the boundary.
 *
 * Column names match the desktop SQLite schema in
 * {@code SqliteUserProfilePort} so the two platforms describe the same data
 * the same way, which matters the day a profile is synced or exported.
 */
@Entity(tableName = "user_profile")
public final class UserProfileEntity
{
    /** Profile UUID. */
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id = "";

    /** Display name. */
    @ColumnInfo(name = "display_name")
    public String displayName = "";

    /** Audio volume, 0.0 to 1.0. */
    @ColumnInfo(name = "audio_volume")
    public double audioVolume;

    /** Mouse sensitivity, 0.1 to 5.0. */
    @ColumnInfo(name = "mouse_sensitivity")
    public double mouseSensitivity;

    /** Field of view in degrees, 60 to 120. */
    @ColumnInfo(name = "field_of_view")
    public int fieldOfView;

    /** Preferred colour as a hex string. */
    @ColumnInfo(name = "preferred_color")
    public String preferredColor = "";

    /** Wall-clock time of last login. */
    @ColumnInfo(name = "last_login_at_epoch_ms")
    public long lastLoginAtEpochMs;

    /** Accumulated playtime in seconds. */
    @ColumnInfo(name = "total_playtime_seconds")
    public long totalPlaytimeSeconds;

    /** Wall-clock time the profile was created. */
    @ColumnInfo(name = "created_at_epoch_ms")
    public long createdAtEpochMs;

    /** Wall-clock time the profile was last written. */
    @ColumnInfo(name = "updated_at_epoch_ms")
    public long updatedAtEpochMs;

    /** Room requires a no-arg constructor. */
    public UserProfileEntity()
    {
        // fields default above
    }

    /**
     * Converts an engine profile into a storable row.
     *
     * @param profile the engine profile; must not be null
     * @return the row form
     */
    public static UserProfileEntity fromDomain(final UserProfile profile)
    {
        final UserProfileEntity row = new UserProfileEntity();
        row.id = profile.id();
        row.displayName = profile.displayName();
        row.audioVolume = profile.audioVolume();
        row.mouseSensitivity = profile.mouseSensitivity();
        row.fieldOfView = profile.fieldOfView();
        row.preferredColor = profile.preferredColor();
        row.lastLoginAtEpochMs = profile.lastLoginAtEpochMs();
        row.totalPlaytimeSeconds = profile.totalPlaytimeSeconds();
        row.createdAtEpochMs = profile.createdAtEpochMs();
        row.updatedAtEpochMs = profile.updatedAtEpochMs();
        return row;
    }

    /**
     * Converts this row back into an engine profile.
     *
     * The {@code UserProfile} constructor validates every field, so a row
     * corrupted on disk fails loudly here rather than propagating a bad
     * value into the simulation.
     *
     * @return the engine profile
     */
    public UserProfile toDomain()
    {
        return new UserProfile(
            id,
            displayName,
            audioVolume,
            mouseSensitivity,
            fieldOfView,
            preferredColor,
            lastLoginAtEpochMs,
            totalPlaytimeSeconds,
            createdAtEpochMs,
            updatedAtEpochMs);
    }
}
