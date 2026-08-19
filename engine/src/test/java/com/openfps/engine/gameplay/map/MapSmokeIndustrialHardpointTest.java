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
 * Headless smoke test for the four maps this pass owns: refinery
 * (Industrial x TDM), foundry (Industrial x Hardpoint), mesa
 * (Desert x Hardpoint), and arctic-hp (Arctic x Hardpoint). Runs
 * each map for 120 tics and asserts no bot died and the match is
 * still in progress.
 *
 * <p>Mirrors {@code MapSmokeMapFixTest}, which owns the four
 * Urban Warzone maps. The two tests are siblings, not a single
 * suite, so each map-fix agent's test file is independent of the
 * other 12 maps and can land as its own PR.</p>
 */
@DisplayName("Map smoke test for refinery / foundry / mesa / arctic-hp")
class MapSmokeIndustrialHardpointTest
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
    @ValueSource(strings = {"refinery", "foundry", "mesa", "arctic-hp"})
    @DisplayName("after a 120-tic run, every bot is alive and the player is still in")
    void shouldKeepAllBotsAliveAndMatchInProgress(final String mapId)
    {
        final MapSpec spec = MapLibrary.get(mapId);

        final I_GameplayPort port = MapSmokeGameplayPort.create(null, mapId);

        final Match match = extractMatch(port);

        for (int tic = 0; tic < MAX_TICS; tic++)
        {
            port.tick(tic);
        }

        assertThat(match.livingBots())
            .as("bots alive at tic %d for map %s (a death here is a"
                + " spawn-in-wall or fall-through-floor bug)",
                MAX_TICS, mapId)
            .isEqualTo(spec.botWaypoints().size());

        assertThat(match.state())
            .as("match state at tic %d for map %s", MAX_TICS, mapId)
            .isEqualTo(MatchState.IN_PROGRESS);
    }

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
