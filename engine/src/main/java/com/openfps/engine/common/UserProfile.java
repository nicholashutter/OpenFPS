/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.common;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable user profile.
 *
 * Persisted to SQLite via {@code I_UserProfilePort}. All fields are
 * primitive or immutable. Use {@code withXxx(...)} methods to produce
 * updated copies.
 *
 * Created via {@link #newDefault()} on first boot, or
 * {@link #newDefault(String)} to give the user a specific ID.
 *
 * Field reference:
 *  - {@code id}                — UUIDv4 (36 chars, hex with hyphens)
 *  - {@code displayName}       — free text, 1-32 chars, validated on input
 *  - {@code audioVolume}       — 0.0 = muted, 1.0 = full volume
 *  - {@code mouseSensitivity}  — multiplier, 0.1..5.0
 *  - {@code fieldOfView}       — degrees, 60..120
 *  - {@code preferredColor}     — hex color string "#RRGGBB"
 *  - {@code lastLoginAtEpochMs} — System.currentTimeMillis() of last boot
 *  - {@code totalPlaytimeSeconds} — cumulative seconds played
 *  - {@code createdAtEpochMs}  — when this profile was first persisted
 *  - {@code updatedAtEpochMs}  — last save time
 */
public final class UserProfile
{
    private final String id;
    private final String displayName;
    private final double audioVolume;
    private final double mouseSensitivity;
    private final int fieldOfView;
    private final String preferredColor;
    private final long lastLoginAtEpochMs;
    private final long totalPlaytimeSeconds;
    private final long createdAtEpochMs;
    private final long updatedAtEpochMs;

    public UserProfile(
        final String id,
        final String displayName,
        final double audioVolume,
        final double mouseSensitivity,
        final int fieldOfView,
        final String preferredColor,
        final long lastLoginAtEpochMs,
        final long totalPlaytimeSeconds,
        final long createdAtEpochMs,
        final long updatedAtEpochMs)
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("id must be non-blank");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 32)
        {
            throw new IllegalArgumentException("displayName must be 1-32 chars, got '"
                + displayName + "'");
        }
        if (audioVolume < 0.0 || audioVolume > 1.0)
        {
            throw new IllegalArgumentException("audioVolume must be in [0, 1], got "
                + audioVolume);
        }
        if (mouseSensitivity < 0.1 || mouseSensitivity > 5.0)
        {
            throw new IllegalArgumentException("mouseSensitivity must be in [0.1, 5.0], got "
                + mouseSensitivity);
        }
        if (fieldOfView < 60 || fieldOfView > 120)
        {
            throw new IllegalArgumentException("fieldOfView must be in [60, 120], got "
                + fieldOfView);
        }
        if (preferredColor == null || !preferredColor.matches("#[0-9A-Fa-f]{6}"))
        {
            throw new IllegalArgumentException("preferredColor must be #RRGGBB, got "
                + preferredColor);
        }
        if (totalPlaytimeSeconds < 0)
        {
            throw new IllegalArgumentException("totalPlaytimeSeconds must be >= 0, got "
                + totalPlaytimeSeconds);
        }
        this.id = id;
        this.displayName = displayName;
        this.audioVolume = audioVolume;
        this.mouseSensitivity = mouseSensitivity;
        this.fieldOfView = fieldOfView;
        this.preferredColor = preferredColor;
        this.lastLoginAtEpochMs = lastLoginAtEpochMs;
        this.totalPlaytimeSeconds = totalPlaytimeSeconds;
        this.createdAtEpochMs = createdAtEpochMs;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    /**
     * Creates a new profile with sensible defaults, a random UUID, and
     * timestamps read from the system wall clock.
     *
     * Convenience form for tests and tooling. Engine code should call
     * {@link #newDefault(String, long)} with
     * {@code I_TimePort.epochMillis()} so the clock stays injectable.
     */
    public static UserProfile newDefault()
    {
        return newDefault(UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    /**
     * Creates a new profile with sensible defaults, a random UUID, and
     * the given wall-clock timestamp.
     *
     * @param nowEpochMs milliseconds since the Unix epoch, from
     *                   {@code I_TimePort.epochMillis()}
     * @return a fresh profile
     */
    public static UserProfile newDefault(final long nowEpochMs)
    {
        return newDefault(UUID.randomUUID().toString(), nowEpochMs);
    }

    /**
     * Creates a new profile with sensible defaults, the given ID, and the
     * given wall-clock timestamp. createdAt, updatedAt, and lastLogin are
     * all set to {@code nowEpochMs}.
     *
     * @param id the profile's UUID
     * @param nowEpochMs milliseconds since the Unix epoch, from
     *                   {@code I_TimePort.epochMillis()}
     * @return a fresh profile
     */
    public static UserProfile newDefault(final String id, final long nowEpochMs)
    {
        final long now = nowEpochMs;
        return new UserProfile(
            id,
            "Player",
            0.8,        // audioVolume
            1.0,        // mouseSensitivity
            90,         // fieldOfView
            "#FF6600",  // preferredColor (openFPS orange)
            now,        // lastLoginAtEpochMs
            0L,         // totalPlaytimeSeconds
            now,        // createdAtEpochMs
            now         // updatedAtEpochMs
        );
    }

    // ---- Accessors ----

    /** Returns the profile's UUID. */
    public String id()
    {
        return id;
    }

    /** Returns the display name (1-32 chars). */
    public String displayName()
    {
        return displayName;
    }

    /** Returns the audio volume, 0.0 (muted) to 1.0 (full). */
    public double audioVolume()
    {
        return audioVolume;
    }

    /** Returns the mouse sensitivity multiplier, 0.1 to 5.0. */
    public double mouseSensitivity()
    {
        return mouseSensitivity;
    }

    /** Returns the field of view in degrees, 60 to 120. */
    public int fieldOfView()
    {
        return fieldOfView;
    }

    /** Returns the preferred color as a "#RRGGBB" string. */
    public String preferredColor()
    {
        return preferredColor;
    }

    /** Returns the wall-clock time of the last login, in epoch millis. */
    public long lastLoginAtEpochMs()
    {
        return lastLoginAtEpochMs;
    }

    /** Returns cumulative seconds played across all sessions. */
    public long totalPlaytimeSeconds()
    {
        return totalPlaytimeSeconds;
    }

    /** Returns when this profile was first persisted, in epoch millis. */
    public long createdAtEpochMs()
    {
        return createdAtEpochMs;
    }

    /** Returns when this profile was last saved, in epoch millis. */
    public long updatedAtEpochMs()
    {
        return updatedAtEpochMs;
    }

    // ---- withXxx — return new profile with one field changed ----

    public UserProfile withDisplayName(final String newName)
    {
        return new UserProfile(id, newName, audioVolume, mouseSensitivity,
            fieldOfView, preferredColor, lastLoginAtEpochMs, totalPlaytimeSeconds,
            createdAtEpochMs, updatedAtEpochMs);
    }

    public UserProfile withAudioVolume(final double newVolume)
    {
        return new UserProfile(id, displayName, newVolume, mouseSensitivity,
            fieldOfView, preferredColor, lastLoginAtEpochMs, totalPlaytimeSeconds,
            createdAtEpochMs, updatedAtEpochMs);
    }

    public UserProfile withMouseSensitivity(final double newSensitivity)
    {
        return new UserProfile(id, displayName, audioVolume, newSensitivity,
            fieldOfView, preferredColor, lastLoginAtEpochMs, totalPlaytimeSeconds,
            createdAtEpochMs, updatedAtEpochMs);
    }

    public UserProfile withFieldOfView(final int newFov)
    {
        return new UserProfile(id, displayName, audioVolume, mouseSensitivity,
            newFov, preferredColor, lastLoginAtEpochMs, totalPlaytimeSeconds,
            createdAtEpochMs, updatedAtEpochMs);
    }

    public UserProfile withPreferredColor(final String newColor)
    {
        return new UserProfile(id, displayName, audioVolume, mouseSensitivity,
            fieldOfView, newColor, lastLoginAtEpochMs, totalPlaytimeSeconds,
            createdAtEpochMs, updatedAtEpochMs);
    }

    public UserProfile withLastLogin(final long newLastLogin)
    {
        return new UserProfile(id, displayName, audioVolume, mouseSensitivity,
            fieldOfView, preferredColor, newLastLogin, totalPlaytimeSeconds,
            createdAtEpochMs, updatedAtEpochMs);
    }

    public UserProfile withAddedPlaytime(final long additionalSeconds)
    {
        if (additionalSeconds < 0)
        {
            throw new IllegalArgumentException("additionalSeconds must be >= 0");
        }
        return new UserProfile(id, displayName, audioVolume, mouseSensitivity,
            fieldOfView, preferredColor, lastLoginAtEpochMs,
            totalPlaytimeSeconds + additionalSeconds,
            createdAtEpochMs, updatedAtEpochMs);
    }

    public UserProfile withUpdatedAt(final long newUpdatedAt)
    {
        return new UserProfile(id, displayName, audioVolume, mouseSensitivity,
            fieldOfView, preferredColor, lastLoginAtEpochMs, totalPlaytimeSeconds,
            createdAtEpochMs, newUpdatedAt);
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o) return true;
        if (!(o instanceof UserProfile that)) return false;
        return Double.compare(that.audioVolume, audioVolume) == 0
            && Double.compare(that.mouseSensitivity, mouseSensitivity) == 0
            && fieldOfView == that.fieldOfView
            && lastLoginAtEpochMs == that.lastLoginAtEpochMs
            && totalPlaytimeSeconds == that.totalPlaytimeSeconds
            && createdAtEpochMs == that.createdAtEpochMs
            && updatedAtEpochMs == that.updatedAtEpochMs
            && Objects.equals(id, that.id)
            && Objects.equals(displayName, that.displayName)
            && Objects.equals(preferredColor, that.preferredColor);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(id, displayName, audioVolume, mouseSensitivity,
            fieldOfView, preferredColor, lastLoginAtEpochMs, totalPlaytimeSeconds,
            createdAtEpochMs, updatedAtEpochMs);
    }

    @Override
    public String toString()
    {
        return "UserProfile{id='" + id + "', name='" + displayName
            + "', vol=" + audioVolume + ", sens=" + mouseSensitivity
            + ", fov=" + fieldOfView + ", playtime=" + totalPlaytimeSeconds + "s}";
    }
}
