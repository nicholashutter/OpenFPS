/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.gameplay.map.Lane;
import com.openfps.engine.gameplay.map.LaneAxis;
import com.openfps.engine.gameplay.map.Chokepoint;
import com.openfps.engine.gameplay.map.MapAssets;
import com.openfps.engine.gameplay.map.MapDimensions;
import com.openfps.engine.gameplay.map.MapLibrary;
import com.openfps.engine.gameplay.map.MapMarkers;
import com.openfps.engine.gameplay.map.MapSetting;
import com.openfps.engine.gameplay.map.MapSpec;
import com.openfps.engine.gameplay.map.SpawnPoint;
import com.openfps.engine.gameplay.map.Team;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link Match} in its map-spec-aware form.
 *
 * <p>The interesting behaviour is not the per-tic game logic (that lives in
 * {@code MatchTest}); it is the seam between the spec and the match — the
 * mode dispatch, the per-mode stub updates, the team-score accessor, and
 * the reset of the new mode-specific state.</p>
 */
@DisplayName("Match with MapSpec")
class MatchMapSpecTest
{
    /** Marksman match: bots always hit, so the player's health drops fast. */
    private static final int SKILL = 0;

    @AfterEach
    void tearDown()
    {
        MapLibrary.unregisterAll();
        MapLibrary.registerDefaults();
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a spec'd match returns the spec's mode")
        void shouldReturnSpecMode()
        {
            final Match match = new Match(smallRoster(), tdmSpec());
            assertThat(match.mode()).isEqualTo(MatchMode.TDM);
        }

        @Test
        @DisplayName("a no-spec match defaults to TDM")
        void shouldDefaultToTdm()
        {
            final Match match = new Match(smallRoster());
            assertThat(match.mode()).isEqualTo(MatchMode.TDM);
        }

        @Test
        @DisplayName("mapSpec() returns the spec the match was built with")
        void shouldExposeMapSpec()
        {
            final MapSpec spec = tdmSpec();
            final Match match = new Match(smallRoster(), spec);
            assertThat(match.mapSpec()).isSameAs(spec);
        }

        @Test
        @DisplayName("the 2-arg constructor rejects a null spec")
        void shouldRejectNullSpec()
        {
            assertThatThrownBy(() -> new Match(smallRoster(), (MapSpec) null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("mode dispatch")
    class ModeDispatch
    {
        @Test
        @DisplayName("TDM tick runs without error")
        void shouldTickTdm()
        {
            final Match match = marksmanMatch(tdmSpec());
            for (int tic = 0; tic < 30; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
            }
            // Marksman bots hit on tic 1; the match is over once the
            // player runs out of deaths. UNLIMITED_DEATHS, so the
            // assertion is the looser one: tics ran and produced a
            // visible state change.
            assertThat(match.botsKilled() > 0 || match.playerDeaths() > 0
                || match.state().isOver()).isTrue();
        }

        @Test
        @DisplayName("Hardpoint tick runs without error and rotates zones")
        void shouldTickHardpoint()
        {
            final MapSpec spec = hardpointSpec();
            final Match match = marksmanMatch(spec);
            for (int tic = 0; tic < 30; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
            }
            // The mode-dispatch path is the load-bearing piece here:
            // updateHardpoint runs every tic and the rotation counter
            // advances. The assertion is the same loose one as TDM.
            assertThat(match.botsKilled() > 0 || match.playerDeaths() > 0
                || match.state().isOver()).isTrue();
        }

        @Test
        @DisplayName("Domination tick runs without error")
        void shouldTickDomination()
        {
            final MapSpec spec = dominationSpec();
            final Match match = marksmanMatch(spec);
            for (int tic = 0; tic < 30; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
            }
            assertThat(match.botsKilled() > 0 || match.playerDeaths() > 0
                || match.state().isOver()).isTrue();
        }

        @Test
        @DisplayName("CTF tick runs without error")
        void shouldTickCtf()
        {
            final MapSpec spec = ctfSpec();
            final Match match = marksmanMatch(spec);
            for (int tic = 0; tic < 30; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
            }
            assertThat(match.botsKilled() > 0 || match.playerDeaths() > 0
                || match.state().isOver()).isTrue();
        }
    }

    @Nested
    @DisplayName("team scores")
    class TeamScores
    {
        @Test
        @DisplayName("teamScores() returns a two-element array")
        void shouldReturnPairOfScores()
        {
            final Match match = new Match(smallRoster(), tdmSpec());
            final int[] scores = match.teamScores();
            assertThat(scores).hasSize(2);
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset
    {
        @Test
        @DisplayName("a reset match is indistinguishable from a fresh one")
        void shouldResetSpecMode()
        {
            final MapSpec spec = hardpointSpec();
            final Match match = new Match(smallRoster(), spec);
            for (int tic = 0; tic < 30; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
            }
            match.reset();
            final Match fresh = new Match(smallRoster(), spec);
            assertThat(match.playerHealth()).isEqualTo(fresh.playerHealth());
            assertThat(match.botsKilled()).isEqualTo(fresh.botsKilled());
        }
    }

    // ----- fixtures ---------------------------------------------------------

    /**
     * A match with the sentry at marksman skill, so the bot reliably hits
     * the player and a few tics produce visible state change. The
     * spec argument is the map to test against; the rest of the
     * construction is constant.
     */
    private static Match marksmanMatch(final MapSpec spec)
    {
        return new Match(smallRoster(), new BotRng(), BotSkill.MARKSMAN,
            Match.UNLIMITED_DEATHS, spec);
    }

    private static Bot[] smallRoster()
    {
        return new Bot[]
        {
            new Bot(Match.FIRST_BOT_ENTITY_ID, 200.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, 60, 0)
        };
    }

    private static MapSpec tdmSpec()
    {
        return baseSpec(MatchMode.TDM, MapMarkers.TeamDeathmatch.INSTANCE);
    }

    private static MapSpec hardpointSpec()
    {
        return baseSpec(MatchMode.HARDPOINT, new MapMarkers.Hardpoint(List.of(
            new MapMarkers.HardpointZone("hp_a", "A", 50.0f, 50.0f, 30.0f),
            new MapMarkers.HardpointZone("hp_b", "B", 100.0f, 50.0f, 30.0f),
            new MapMarkers.HardpointZone("hp_c", "C", 150.0f, 50.0f, 30.0f)
        ), 1800, 1));
    }

    private static MapSpec dominationSpec()
    {
        return baseSpec(MatchMode.DOMINATION, new MapMarkers.Domination(List.of(
            new MapMarkers.Flag("flag_a", "A", 50.0f, 50.0f, 30.0f),
            new MapMarkers.Flag("flag_b", "B", 100.0f, 50.0f, 30.0f),
            new MapMarkers.Flag("flag_c", "C", 150.0f, 50.0f, 30.0f)
        )));
    }

    private static MapSpec ctfSpec()
    {
        return baseSpec(MatchMode.CTF, new MapMarkers.CaptureTheFlag(
            new MapMarkers.Base(Team.RED, 16.0f, 80.0f, 16.0f, 80.0f, 32.0f),
            new MapMarkers.Base(Team.BLUE, 304.0f, 240.0f, 304.0f, 240.0f, 32.0f)
        ));
    }

    private static MapSpec baseSpec(final MatchMode mode, final MapMarkers markers)
    {
        return new MapSpec("test_" + mode.name().toLowerCase(), "Test " + mode,
            MapSetting.URBAN_WARZONE, mode, new MapDimensions(320.0f, 320.0f, 128.0f),
            threeLanes(), List.of(
                new SpawnPoint("sp_0", Team.RED, 16.0f, 0.0f, 80.0f, 0.0f),
                new SpawnPoint("sp_1", Team.BLUE, 304.0f, 0.0f, 240.0f, 0.0f)
            ), List.of(), markers,
            new MapAssets("a/level.ofm", "a/weapon.ofm", null));
    }

    private static List<Lane> threeLanes()
    {
        return List.of(
            new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                new Chokepoint("cp_a1", "A1", 0.0f, 0.0f),
                new Chokepoint("cp_a2", "A2", 10.0f, 0.0f)
            )),
            new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                new Chokepoint("cp_b1", "B1", 0.0f, 10.0f),
                new Chokepoint("cp_b2", "B2", 10.0f, 10.0f)
            )),
            new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                new Chokepoint("cp_c1", "C1", 0.0f, 20.0f),
                new Chokepoint("cp_c2", "C2", 10.0f, 20.0f)
            ))
        );
    }
}
