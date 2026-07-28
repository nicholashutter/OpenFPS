/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android.persistence;

import android.content.ContextWrapper;

import com.openfps.engine.common.UserProfile;
import com.openfps.engine.hal.port.I_UserProfilePort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the halves of {@link RoomUserProfilePort}'s state machine that do
 * not need a database: UNINITIALIZED and SHUTDOWN.
 *
 * <b>The READY leg is not covered here.</b> Reaching it means
 * {@code Room.databaseBuilder(...).build()} against a real {@code Context},
 * which needs the framework and the platform's SQLite — that is an
 * instrumented test, not a unit test. What is covered is the contract the
 * engine depends on either side of it: that a port which is not open refuses
 * work loudly instead of returning an empty result, and that the terminal
 * state stays terminal.
 */
class RoomUserProfilePortTest
{
    /** A profile the port would store, if it were open. */
    private static final UserProfile PROFILE = UserProfile.newDefault("id-1", 1_700_000_000L);

    /**
     * A context that is never actually used. The port takes the application
     * context from it and hands that to Room at init(), which these tests
     * never reach.
     */
    private static ContextWrapper newContext()
    {
        return new ContextWrapper(null);
    }

    @Test
    @DisplayName("a port with no context has nothing to open a database against, so it is refused")
    void shouldRejectNullContext()
    {
        assertThatThrownBy(() -> new RoomUserProfilePort(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("context");
    }

    @Test
    @DisplayName("a fresh port is closed, matching every other HAL port's state machine")
    void shouldStartUninitialized()
    {
        final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
        assertThat(port).isInstanceOf(I_UserProfilePort.class);
        assertThat(port.state()).isEqualTo(I_UserProfilePort.State.UNINITIALIZED);
    }

    @Nested
    @DisplayName("before the database is open")
    class BeforeInit
    {
        @Test
        @DisplayName("a lookup on a closed port fails loudly rather than reporting no profile")
        void shouldRejectFindById()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            assertThatThrownBy(() -> port.findById("id-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("findById")
                .hasMessageContaining("UNINITIALIZED");
        }

        @Test
        @DisplayName("listing profiles on a closed port fails rather than reporting none")
        void shouldRejectFindAll()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            assertThatThrownBy(port::findAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("findAll")
                .hasMessageContaining("UNINITIALIZED");
        }

        @Test
        @DisplayName("a save on a closed port fails rather than being silently discarded")
        void shouldRejectSave()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            assertThatThrownBy(() -> port.save(PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("save")
                .hasMessageContaining("UNINITIALIZED");
        }

        @Test
        @DisplayName("a delete on a closed port fails rather than pretending to have deleted")
        void shouldRejectDelete()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            assertThatThrownBy(() -> port.delete("id-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delete")
                .hasMessageContaining("UNINITIALIZED");
        }

        @Test
        @DisplayName("a count on a closed port fails rather than answering zero")
        void shouldRejectCount()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            assertThatThrownBy(port::count)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("count")
                .hasMessageContaining("UNINITIALIZED");
        }
    }

    @Nested
    @DisplayName("after shutdown")
    class AfterShutdown
    {
        @Test
        @DisplayName("closing a port that was never opened is allowed and reaches the terminal state")
        void shouldAllowShutdownFromUninitialized()
        {
            // Android can destroy an Activity before the engine ever reached
            // hal.init(), so teardown must not require a matching open.
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());

            port.shutdown();

            assertThat(port.state()).isEqualTo(I_UserProfilePort.State.SHUTDOWN);
        }

        @Test
        @DisplayName("work asked of a closed port names the terminal state, not the opening one")
        void shouldRejectQueriesNamingShutdown()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            port.shutdown();

            assertThatThrownBy(port::findAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHUTDOWN");
            assertThatThrownBy(() -> port.save(PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHUTDOWN");
        }

        @Test
        @DisplayName("a second shutdown is an error, so a double teardown cannot pass unnoticed")
        void shouldRejectASecondShutdown()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            port.shutdown();

            assertThatThrownBy(port::shutdown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHUTDOWN");
        }

        @Test
        @DisplayName("a closed port cannot be reopened — a new Activity gets a new port")
        void shouldRejectReinitialisation()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            port.shutdown();

            assertThatThrownBy(port::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHUTDOWN");
        }
    }

    @Nested
    @DisplayName("identifier minting")
    class Identifiers
    {
        @Test
        @DisplayName("an ID can be minted before the database opens, so first boot has one to save")
        void shouldGenerateIdsWithoutBeingReady()
        {
            // Deliberately not guarded by the READY check that fronts every
            // other operation: the engine mints an ID for a brand-new profile
            // and only then has something to persist.
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());
            assertThat(port.state()).isEqualTo(I_UserProfilePort.State.UNINITIALIZED);

            assertThat(UUID.fromString(port.generateNewId())).isNotNull();
        }

        @Test
        @DisplayName("two profiles never collide on their primary key")
        void shouldGenerateDistinctIds()
        {
            final RoomUserProfilePort port = new RoomUserProfilePort(newContext());

            assertThat(port.generateNewId()).isNotEqualTo(port.generateNewId());
        }
    }
}
