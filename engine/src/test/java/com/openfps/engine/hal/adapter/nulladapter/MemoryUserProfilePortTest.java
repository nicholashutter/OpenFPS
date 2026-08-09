/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.common.UserProfile;
import com.openfps.engine.hal.port.I_UserProfilePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link MemoryUserProfilePort} — the in-memory user profile port.
 */
class MemoryUserProfilePortTest
{
    private MemoryUserProfilePort port;

    @BeforeEach
    void setUp()
    {
        port = new MemoryUserProfilePort();

        port.init();
    }

    @Test
    @DisplayName("init() moves to READY; initial count is 0")
    void shouldStartEmpty()
    {
        assertThat(port.state()).isEqualTo(I_UserProfilePort.State.READY);

        assertThat(port.count()).isEqualTo(0);

        assertThat(port.findAll()).isEmpty();
    }

    @Test
    @DisplayName("save then findById returns the profile")
    void shouldSaveAndFind()
    {
        final UserProfile profile = UserProfile.newDefault();

        port.save(profile);

        final Optional<UserProfile> found = port.findById(profile.id());

        assertThat(found).isPresent();

        assertThat(found.get()).isEqualTo(profile);
    }

    @Test
    @DisplayName("findById returns empty for unknown ID")
    void shouldReturnEmptyForUnknownId()
    {
        assertThat(port.findById("not-a-uuid")).isEmpty();

        assertThat(port.findById(null)).isEmpty();

        assertThat(port.findById("")).isEmpty();
    }

    @Test
    @DisplayName("save is upsert: re-saving with same ID replaces the profile")
    void shouldUpsertOnSameId()
    {
        final UserProfile original = UserProfile.newDefault();

        port.save(original);

        final UserProfile updated = original.withDisplayName("UpdatedName");

        port.save(updated);

        assertThat(port.count()).isEqualTo(1);

        assertThat(port.findById(original.id()).get().displayName())
            .isEqualTo("UpdatedName");
    }

    @Test
    @DisplayName("findAll returns all saved profiles")
    void shouldFindAll()
    {
        final UserProfile a = UserProfile.newDefault();

        final UserProfile b = UserProfile.newDefault();

        final UserProfile c = UserProfile.newDefault();

        port.save(a);

        port.save(b);

        port.save(c);

        final List<UserProfile> all = port.findAll();

        assertThat(all).hasSize(3);

        assertThat(all).extracting(UserProfile::id)
            .containsExactly(a.id(), b.id(), c.id());
    }

    @Test
    @DisplayName("delete removes a profile")
    void shouldDelete()
    {
        final UserProfile a = UserProfile.newDefault();

        final UserProfile b = UserProfile.newDefault();

        port.save(a);

        port.save(b);

        port.delete(a.id());

        assertThat(port.count()).isEqualTo(1);

        assertThat(port.findById(a.id())).isEmpty();

        assertThat(port.findById(b.id())).isPresent();
    }

    @Test
    @DisplayName("delete of unknown ID is a no-op")
    void shouldIgnoreUnknownDelete()
    {
        port.delete("not-a-uuid");

        port.delete(null);

        port.delete("");

        assertThat(port.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("count returns the number of profiles")
    void shouldCount()
    {
        assertThat(port.count()).isEqualTo(0);

        port.save(UserProfile.newDefault());

        assertThat(port.count()).isEqualTo(1);

        port.save(UserProfile.newDefault());

        assertThat(port.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("generateNewId returns valid UUID format")
    void shouldGenerateUuids()
    {
        final String id1 = port.generateNewId();

        final String id2 = port.generateNewId();

        assertThat(id1).isNotEqualTo(id2);

        assertThat(id1).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    @DisplayName("state machine — operations throw before init")
    void shouldThrowBeforeInit()
    {
        final MemoryUserProfilePort fresh = new MemoryUserProfilePort();

        assertThat(fresh.state()).isEqualTo(I_UserProfilePort.State.UNINITIALIZED);

        assertThatThrownBy(() -> fresh.findById("anything"))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> fresh.save(UserProfile.newDefault()))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> fresh.findAll())
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> fresh.delete("x"))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> fresh.count())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("state machine — init twice throws")
    void shouldThrowOnDoubleInit()
    {
        assertThatThrownBy(() -> port.init())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("state machine — shutdown twice throws")
    void shouldThrowOnDoubleShutdown()
    {
        port.shutdown();

        assertThatThrownBy(() -> port.shutdown())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("state machine — operations throw after shutdown")
    void shouldThrowAfterShutdown()
    {
        port.shutdown();

        assertThatThrownBy(() -> port.save(UserProfile.newDefault()))
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
}
