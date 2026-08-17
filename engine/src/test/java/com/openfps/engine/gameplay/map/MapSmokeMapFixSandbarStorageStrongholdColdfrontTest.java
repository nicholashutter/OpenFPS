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
 * Headless smoke test for the four maps this fix pass is
 * responsible for: sandbar (DESERT_RAVINE x DOM), storage
 * (INDUSTRIAL_COMPLEX x CTF), stronghold (DESERT_RAVINE x CTF),
 * and coldfront (ARCTIC_STATION x CTF). Each map runs for 120
 * tics and the final state must be: no bots dead, player still
 * positive health, match still in progress.
 *
 * <p>The four maps all had spawn/waypoint positions that put
 * bodies outside the kit's playable area, inside a kit column,
 * or floating above the floor (sandbar's butte-top waypoints
 * at y=32). A bot death at tic 120 in a no-input, no-shoot
 * scenario is the failure mode these tests target.</p>
 */
@DisplayName("Map smoke test for sandbar / storage / stronghold / coldfront")
class MapSmokeMapFixSandbarStorageStrongholdColdfrontTest
{
    /** The engine's headless cap at 60 Hz, replicated so the test
     * is independent of GameConfig. */
    private static final int MAX_TICS = 120;

    @BeforeEach
    void setUp()
    {
        MapLibrary.registerDefaults();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbar", "storage", "stronghold", "coldfront"})
    @DisplayName("after a 120-tic run, every bot is alive and the match is in progress")
    void shouldKeepAllBotsAliveAndMatchInProgress(final String mapId)
    {
        final MapSpec spec = MapLibrary.get(mapId);

        final I_GameplayPort port = MapSmokeGameplayPort.create(null, mapId);

        final Match match = extractMatch(port);

        for (int tic = 0; tic < MAX_TICS; tic++)
        {
            port.tick(tic);
        }

        // A bot that died in 120 tics did so without the player
        // ever firing. That only happens when a bot starts in a
        // wall, on a column, or floating above the floor — the
        // three failure modes the fix targets.
        assertThat(match.livingBots())
            .as("bots alive at tic %d for map %s (player never fired,"
                + " a death here is a spawn-in-wall, in-column, or"
                + " float-above-floor bug)", MAX_TICS, mapId)
            .isEqualTo(match.botCount());

        assertThat(match.playerHealth())
            .as("player health at tic %d for map %s (the bots fire;"
                + " some damage is expected, but a kill in 2s is wrong)",
                MAX_TICS, mapId)
            .isPositive();

        assertThat(match.state())
            .as("match state at tic %d for map %s", MAX_TICS, mapId)
            .isEqualTo(MatchState.IN_PROGRESS);
    }

    /**
     * Extracts the {@link Match} out of a {@link MapSmokeGameplayPort}
     * by reflection. The smoke-test port is a headless path with
     * no other way to get at its state, and the alternative —
     * adding a public accessor purely for tests — would broaden
     * the surface for no production reason.
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
                final String actual;

                if (value == null)
                {
                    actual = "null";
                }
                else
                {
                    actual = value.getClass().getName();
                }

                throw new IllegalStateException(
                    "MapSmokeGameplayPort.match was not a Match, got " + actual);
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
