/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link UserProfile} — validation, withXxx, and defaults.
 */
class UserProfileTest
{
    @Test
    @DisplayName("newDefault() returns sensible defaults")
    void shouldProvideDefaults()
    {
        final UserProfile p = UserProfile.newDefault();
        assertThat(p.id()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(p.displayName()).isEqualTo("Player");
        assertThat(p.audioVolume()).isEqualTo(0.8);
        assertThat(p.mouseSensitivity()).isEqualTo(1.0);
        assertThat(p.fieldOfView()).isEqualTo(90);
        assertThat(p.preferredColor()).isEqualTo("#FF6600");
        assertThat(p.totalPlaytimeSeconds()).isEqualTo(0L);
    }

    @Test
    @DisplayName("withXxx methods return a new profile with one field changed")
    void shouldSupportWithMethods()
    {
        final UserProfile original = UserProfile.newDefault();
        final UserProfile renamed = original.withDisplayName("Alice");
        final UserProfile louder = original.withAudioVolume(0.5);
        final UserProfile sharper = original.withMouseSensitivity(2.0);
        final UserProfile wider = original.withFieldOfView(110);
        final UserProfile repainted = original.withPreferredColor("#00FF00");
        final UserProfile longin = original.withLastLogin(1234567890L);
        final UserProfile played = original.withAddedPlaytime(60L);
        final UserProfile updated = original.withUpdatedAt(999L);

        // All withXxx return new instances
        assertThat(renamed).isNotSameAs(original);
        assertThat(louder).isNotSameAs(original);
        assertThat(sharper).isNotSameAs(original);
        assertThat(wider).isNotSameAs(original);
        assertThat(repainted).isNotSameAs(original);
        assertThat(longin).isNotSameAs(original);
        assertThat(played).isNotSameAs(original);
        assertThat(updated).isNotSameAs(original);

        // Each only changes the relevant field
        assertThat(renamed.displayName()).isEqualTo("Alice");
        assertThat(renamed.audioVolume()).isEqualTo(original.audioVolume());

        assertThat(louder.audioVolume()).isEqualTo(0.5);
        assertThat(louder.displayName()).isEqualTo(original.displayName());

        assertThat(sharper.mouseSensitivity()).isEqualTo(2.0);

        assertThat(wider.fieldOfView()).isEqualTo(110);

        assertThat(repainted.preferredColor()).isEqualTo("#00FF00");

        assertThat(longin.lastLoginAtEpochMs()).isEqualTo(1234567890L);

        assertThat(played.totalPlaytimeSeconds()).isEqualTo(60L);

        assertThat(updated.updatedAtEpochMs()).isEqualTo(999L);
    }

    @Test
    @DisplayName("withAddedPlaytime accumulates")
    void shouldAccumulatePlaytime()
    {
        final UserProfile p = UserProfile.newDefault()
            .withAddedPlaytime(30L)
            .withAddedPlaytime(45L);
        assertThat(p.totalPlaytimeSeconds()).isEqualTo(75L);
    }

    @Test
    @DisplayName("withAddedPlaytime rejects negative")
    void shouldRejectNegativePlaytime()
    {
        final UserProfile p = UserProfile.newDefault();
        assertThatThrownBy(() -> p.withAddedPlaytime(-1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects blank ID")
    void shouldRejectBlankId()
    {
        assertThatThrownBy(() -> new UserProfile(
            "", "Player", 0.8, 1.0, 90, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile(
            null, "Player", 0.8, 1.0, 90, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects blank or oversized display name")
    void shouldRejectBadDisplayName()
    {
        assertThatThrownBy(() -> new UserProfile(
            "id", "", 0.8, 1.0, 90, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile(
            "id", "a".repeat(33), 0.8, 1.0, 90, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects audioVolume out of [0, 1]")
    void shouldRejectBadAudioVolume()
    {
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", -0.1, 1.0, 90, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", 1.1, 1.0, 90, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects mouseSensitivity out of [0.1, 5.0]")
    void shouldRejectBadSensitivity()
    {
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", 0.8, 0.05, 90, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", 0.8, 5.5, 90, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects FOV out of [60, 120]")
    void shouldRejectBadFov()
    {
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", 0.8, 1.0, 50, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", 0.8, 1.0, 130, "#FF6600", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects malformed preferredColor")
    void shouldRejectBadColor()
    {
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", 0.8, 1.0, 90, "FF6600", 0, 0, 0, 0))   // missing #
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", 0.8, 1.0, 90, "#FF66", 0, 0, 0, 0))     // too short
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile(
            "id", "P", 0.8, 1.0, 90, "red", 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor accepts lowercase hex color")
    void shouldAcceptLowercaseHex()
    {
        final UserProfile p = new UserProfile(
            "id", "P", 0.8, 1.0, 90, "#ff00aa", 0, 0, 0, 0);
        assertThat(p.preferredColor()).isEqualTo("#ff00aa");
    }

    @Test
    @DisplayName("equals and hashCode work as expected")
    void shouldSupportEqualsHashCode()
    {
        final UserProfile p1 = UserProfile.newDefault();
        final UserProfile p2 = UserProfile.newDefault();
        // different IDs (newDefault generates random UUIDs)
        assertThat(p1).isNotEqualTo(p2);
        assertThat(p1.hashCode()).isNotEqualTo(p2.hashCode());

        // Same ID, same data — should be equal
        final UserProfile a = new UserProfile(
            "id-1", "P", 0.8, 1.0, 90, "#FF6600", 0, 0, 0, 0);
        final UserProfile b = new UserProfile(
            "id-1", "P", 0.8, 1.0, 90, "#FF6600", 0, 0, 0, 0);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
