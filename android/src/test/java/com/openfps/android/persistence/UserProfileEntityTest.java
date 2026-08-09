/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android.persistence;

import com.openfps.engine.common.UserProfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests for {@link UserProfileEntity}, the conversion between the engine's
 * profile and its storage shape.
 *
 * This is the whole reason the entity exists as a separate type — the engine
 * model stays free of Room annotations and the mapping is done at the
 * boundary — so the mapping is plain Java and is covered completely. Nothing
 * here opens a database; what Room does with the annotations is a different
 * question and needs a device.
 */
class UserProfileEntityTest
{
    /** Double comparison tolerance. */
    private static final double EPSILON = 1.0e-9;

    /** Builds a profile with every field distinguishable from every other. */
    private static UserProfile sampleProfile()
    {
        return new UserProfile(
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            "Tester",
            0.42,
            2.5,
            103,
            "#1A2B3C",
            1_700_000_001L,
            4242L,
            1_600_000_002L,
            1_800_000_003L);
    }

    @Nested
    @DisplayName("engine profile to row")
    class ToRow
    {
        @Test
        @DisplayName("no field is dropped on the way into storage")
        void shouldCopyEveryField()
        {
            final UserProfile profile = sampleProfile();

            final UserProfileEntity row = UserProfileEntity.fromDomain(profile);

            assertThat(row.id).isEqualTo(profile.id());

            assertThat(row.displayName).isEqualTo(profile.displayName());

            assertThat(row.audioVolume).isCloseTo(profile.audioVolume(), offset(EPSILON));

            assertThat(row.mouseSensitivity)
                .isCloseTo(profile.mouseSensitivity(), offset(EPSILON));

            assertThat(row.fieldOfView).isEqualTo(profile.fieldOfView());

            assertThat(row.preferredColor).isEqualTo(profile.preferredColor());

            assertThat(row.lastLoginAtEpochMs).isEqualTo(profile.lastLoginAtEpochMs());

            assertThat(row.totalPlaytimeSeconds).isEqualTo(profile.totalPlaytimeSeconds());

            assertThat(row.createdAtEpochMs).isEqualTo(profile.createdAtEpochMs());

            assertThat(row.updatedAtEpochMs).isEqualTo(profile.updatedAtEpochMs());
        }
    }

    @Nested
    @DisplayName("row to engine profile")
    class ToProfile
    {
        @Test
        @DisplayName("a saved profile comes back out of storage unchanged")
        void shouldRoundTrip()
        {
            final UserProfile original = sampleProfile();

            final UserProfile restored = UserProfileEntity.fromDomain(original).toDomain();

            assertThat(restored.id()).isEqualTo(original.id());

            assertThat(restored.displayName()).isEqualTo(original.displayName());

            assertThat(restored.audioVolume()).isCloseTo(original.audioVolume(), offset(EPSILON));

            assertThat(restored.mouseSensitivity())
                .isCloseTo(original.mouseSensitivity(), offset(EPSILON));

            assertThat(restored.fieldOfView()).isEqualTo(original.fieldOfView());

            assertThat(restored.preferredColor()).isEqualTo(original.preferredColor());

            assertThat(restored.lastLoginAtEpochMs()).isEqualTo(original.lastLoginAtEpochMs());

            assertThat(restored.totalPlaytimeSeconds())
                .isEqualTo(original.totalPlaytimeSeconds());

            assertThat(restored.createdAtEpochMs()).isEqualTo(original.createdAtEpochMs());

            assertThat(restored.updatedAtEpochMs()).isEqualTo(original.updatedAtEpochMs());

            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("a default profile survives storage too, not just a hand-built one")
        void shouldRoundTripADefaultProfile()
        {
            final UserProfile original = UserProfile.newDefault("id-1", 1_700_000_000L);

            assertThat(UserProfileEntity.fromDomain(original).toDomain()).isEqualTo(original);
        }

        @Test
        @DisplayName("a row corrupted on disk fails at the boundary, not inside the simulation")
        void shouldRejectAnOutOfRangeRow()
        {
            final UserProfileEntity row = UserProfileEntity.fromDomain(sampleProfile());

            row.fieldOfView = 400;

            assertThatThrownBy(row::toDomain)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldOfView");
        }

        @Test
        @DisplayName("a half-written row is refused rather than becoming a blank profile")
        void shouldRejectAnEmptyRow()
        {
            // Room populates fields one by one; a row that never received its
            // values must not read back as a nameless, colourless profile.
            assertThatThrownBy(new UserProfileEntity()::toDomain)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
