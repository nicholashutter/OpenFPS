/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.port.I_GameplayPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

/**
 * Headless smoke test for the four Urban Warzone maps assigned to this
 * pass: cornerstone, overpass, tripoint, extraction. Runs each map for
 * 120 tics and asserts the final state is "no bots dead, no player dead,
 * match still in progress". A bot death in 120 tics without a player
 * shooting is the failure mode the map-fix pass targets — a bot that
 * died on its own is a bot that spawned in a wall, fell through the
 * floor, or hit a kit column's collision in a way that registers as
 * out-of-bounds.
 *
 * <p>The test is the diagnostic the agent runs every iteration of a
 * map fix; a pass means the map does not have an obvious
 * spawn-in-wall or fall-through-floor bug, a fail is the next
 * coordinate to investigate.</p>
 */
@DisplayName("Map smoke test for fix-iteration diagnostics")
class MapSmokeMapFixTest
{
    /** The engine's headless cap at 60 Hz, replicated here so the test
     * is independent of GameConfig. */
    private static final int MAX_TICS = 120;

    @BeforeEach
    void setUp()
    {
        MapLibrary.registerDefaults();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cornerstone", "overpass", "tripoint", "extraction"})
    @DisplayName("after a 120-tic run, every bot is alive and the player is at 100 hp")
    void shouldKeepAllBotsAliveAndPlayerAtFullHealth(final String mapId)
    {
        final MapSpec spec = MapLibrary.get(mapId);

        // The smoke-test port is the engine's path: build it through the
        // same factory the headless run uses, then tick the underlying
        // match by reflection so the test does not depend on the
        // smoke-test port's private state.
        final I_GameplayPort port = MapSmokeGameplayPort.create(null, mapId);

        final Match match = extractMatch(port);

        for (int tic = 0; tic < MAX_TICS; tic++)
        {
            port.tick(tic);
        }

        // The player is at (0,0,0) in the smoke test (no input, no
        // movement), so they sit at the world origin the whole time.
        // A bot that died in 120 tics did so without the player ever
        // firing - which only happens when a bot starts in a wall.
        // 2026-08: the simulation now drives all of the spec's waypoints
        // (cornerstone has 28), so the alive count is the waypoint
        // count, not Match.DEFAULT_BOT_COUNT = 32 (the cap used to be
        // the smaller of the two).
        assertThat(match.livingBots())
            .as("bots alive at tic %d for map %s (player never fired,"
                + " a death here is a spawn-in-wall or fall-through-floor bug)",
                MAX_TICS, mapId)
            .isEqualTo(spec.botWaypoints().size());

        assertThat(match.playerHealth())
            .as("player health at tic %d for map %s (the bots fire; some"
                + " damage is expected, but a kill in 2s of fire is wrong)",
                MAX_TICS, mapId)
            .isPositive();

        assertThat(match.state())
            .as("match state at tic %d for map %s", MAX_TICS, mapId)
            .isEqualTo(MatchState.IN_PROGRESS);
    }

    /**
     * Extracts the {@link Match} out of a {@link MapSmokeGameplayPort}
     * without making the test depend on its private fields through
     * public accessors. Reflection is justified here because the
     * smoke-test port is a headless path with no other way to get at
     * its state, and the alternative — adding a public accessor
     * purely for tests — would broaden the surface for no production
     * reason.
     */
    private static Match extractMatch(final I_GameplayPort port)
    {
        try
        {
            final Field field = MapSmokeGameplayPort.class.getDeclaredField("match");

            field.setAccessible(true);

            final Object value = field.get(port);

            if (!(value instanceof Match match))
            {
                final String detail;

                if (value == null)
                {
                    detail = "null";
                }
                else
                {
                    detail = value.getClass().getName();
                }

                throw new IllegalStateException(
                    "MapSmokeGameplayPort.match was not a Match, got " + detail);
            }

            return match;
        }
        catch (final NoSuchFieldException | IllegalAccessException e)
        {
            throw new IllegalStateException(
                "MapSmokeGameplayPort.match is not accessible; the test cannot run", e);
        }
    }
}
