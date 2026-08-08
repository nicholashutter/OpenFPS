/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.gameplay.map.Chokepoint;
import com.openfps.engine.gameplay.map.Lane;
import com.openfps.engine.gameplay.map.LaneAxis;
import com.openfps.engine.gameplay.map.MapAssets;
import com.openfps.engine.gameplay.map.MapDimensions;
import com.openfps.engine.gameplay.map.MapMarkers;
import com.openfps.engine.gameplay.map.MapSetting;
import com.openfps.engine.gameplay.map.MapSpec;
import com.openfps.engine.gameplay.map.SpawnPoint;
import com.openfps.engine.gameplay.map.Team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for the Domination mode in {@link Match}.
 *
 * <p>Domination is the second of the three mode-specific rules to land
 * (Pass 3). The class pins the two rules it implements: <b>resolve</b>
 * each flag's owner from the bodies in its capture radius, and
 * <b>score</b> each flag that has an owner. The contested rule (both
 * teams present means the owner is unchanged) and the empty rule (no
 * team present means the owner is unchanged) are tested explicitly,
 * because they are the difference between a working Domination and a
 * Domination that flips a flag the moment a defender steps on it.</p>
 */
@DisplayName("Match Domination mode")
class MatchDominationTest
{
    /** Three small flags covering an 80-unit box centred on the world origin. */
    private static final List<MapMarkers.Flag> FLAGS = List.of(
        new MapMarkers.Flag("flag_a", "A", 0.0f, 0.0f, 8.0f),
        new MapMarkers.Flag("flag_b", "B", 40.0f, 0.0f, 8.0f),
        new MapMarkers.Flag("flag_c", "C", 80.0f, 0.0f, 8.0f)
    );

    @Nested
    @DisplayName("flag resolution")
    class FlagResolution
    {
        @Test
        @DisplayName("all flags start NEUTRAL")
        void shouldStartNeutral()
        {
            final Match match = dominationMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.NEUTRAL);
            assertThat(match.dominationFlagOwner(1)).isEqualTo(Team.NEUTRAL);
            assertThat(match.dominationFlagOwner(2)).isEqualTo(Team.NEUTRAL);
        }

        @Test
        @DisplayName("a RED bot in flag A's radius: flag A becomes RED")
        void shouldCaptureForRedBot()
        {
            final Match match = dominationMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(1000.0f, 1000.0f, 1)
            ));
            match.setPlayerTeam(Team.BLUE);
            match.tick(0, 1000.0f, 0.0f, 0.0f);
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.RED);
            assertThat(match.dominationFlagOwner(1)).isEqualTo(Team.NEUTRAL);
        }

        @Test
        @DisplayName("a BLUE bot in flag B's radius: flag B becomes BLUE")
        void shouldCaptureForBlueBot()
        {
            final Match match = dominationMatch(List.of(
                blueBotAt(40.0f, 0.0f, 0),
                redBotAt(1000.0f, 1000.0f, 1)
            ));
            match.setPlayerTeam(Team.RED);
            match.tick(0, 1000.0f, 0.0f, 0.0f);
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.NEUTRAL);
            assertThat(match.dominationFlagOwner(1)).isEqualTo(Team.BLUE);
        }

        @Test
        @DisplayName("RED and BLUE bodies both in a flag's radius: contested, owner unchanged")
        void shouldBeContestedWhenBothIn()
        {
            final Match match = dominationMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                blueBotAt(0.0f, 0.0f, 1)
            ));
            match.setPlayerTeam(Team.NEUTRAL);
            // Tick once: contested, owner is still NEUTRAL.
            match.tick(0, 1000.0f, 0.0f, 0.0f);
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.NEUTRAL);
        }

        @Test
        @DisplayName("an empty flag: owner stays at its current value (NEUTRAL)")
        void shouldStayNeutralWhenEmpty()
        {
            final Match match = dominationMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));
            match.setPlayerTeam(Team.RED);
            // No body near flag A on tic 0 — stays NEUTRAL.
            match.tick(0, 1000.0f, 0.0f, 0.0f);
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.NEUTRAL);
        }

        @Test
        @DisplayName("a captured flag stays captured when both bodies leave")
        void shouldStayCapturedWhenEmpty()
        {
            // RED bot in flag A, far away from any flag.
            final Match match = dominationMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(1000.0f, 1000.0f, 1)
            ));
            match.setPlayerTeam(Team.NEUTRAL);
            // Tick 1: RED bot in flag A's radius — captured.
            match.tick(0, 1000.0f, 0.0f, 0.0f);
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.RED);
            // Tick 2: same state, still RED.
            match.tick(1, 1000.0f, 0.0f, 0.0f);
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.RED);
        }

        @Test
        @DisplayName("a NEUTRAL bot in a flag's radius does not claim it")
        void shouldNotCountNeutralBot()
        {
            // A NEUTRAL bot in flag A's radius, plus a far-away RED
            // bot. The NEUTRAL bot does not claim the flag for any
            // side, so the flag stays NEUTRAL.
            final Match match = dominationMatch(List.of(
                neutralBotAt(0.0f, 0.0f, 0),
                redBotAt(1000.0f, 1000.0f, 1)
            ));
            match.setPlayerTeam(Team.RED);
            match.tick(0, 1000.0f, 0.0f, 0.0f);
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.NEUTRAL);
        }
    }

    @Nested
    @DisplayName("scoring")
    class Scoring
    {
        @Test
        @DisplayName("a held flag scores one point per tic")
        void shouldScorePerTick()
        {
            // RED bot in flag A's radius; player is far away.
            final Match match = dominationMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(1000.0f, 1000.0f, 1)
            ));
            match.setPlayerTeam(Team.NEUTRAL);
            for (int tic = 0; tic < 10; tic++)
            {
                match.tick(tic, 1000.0f, 0.0f, 0.0f);
            }
            // Flag A held for 10 tics → red +10; flag B and C
            // never claimed → blue stays 0.
            assertThat(match.teamScores()).containsExactly(10, 0);
        }

        @Test
        @DisplayName("each held flag scores independently")
        void shouldScorePerFlag()
        {
            // RED bot holds flag A (and lives far enough to keep
            // holding it). No bot in flag B or C's radius.
            final Match match = dominationMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(1000.0f, 1000.0f, 1)
            ));
            match.setPlayerTeam(Team.NEUTRAL);
            for (int tic = 0; tic < 5; tic++)
            {
                match.tick(tic, 1000.0f, 0.0f, 0.0f);
            }
            // Only flag A is held, so only red scores 5.
            assertThat(match.teamScores()).containsExactly(5, 0);
        }

        @Test
        @DisplayName("a contested flag does not score")
        void shouldNotScoreContested()
        {
            // RED and BLUE both in flag A. The flag is contested
            // (NEUTRAL) — neither team scores.
            final Match match = dominationMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                blueBotAt(0.0f, 0.0f, 1)
            ));
            match.setPlayerTeam(Team.NEUTRAL);
            for (int tic = 0; tic < 10; tic++)
            {
                match.tick(tic, 1000.0f, 0.0f, 0.0f);
            }
            assertThat(match.teamScores()).containsExactly(0, 0);
        }
    }

    @Nested
    @DisplayName("team scores accessor")
    class TeamScoresAccessor
    {
        @Test
        @DisplayName("a Domination spec returns the per-team scores")
        void shouldReturnPerTeamScores()
        {
            final Match match = dominationMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(1000.0f, 1000.0f, 1)
            ));
            match.setPlayerTeam(Team.NEUTRAL);
            for (int tic = 0; tic < 7; tic++)
            {
                match.tick(tic, 1000.0f, 0.0f, 0.0f);
            }
            final int[] scores = match.teamScores();
            assertThat(scores).hasSize(2);
            assertThat(scores[0]).isEqualTo(7);
            assertThat(scores[1]).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset
    {
        @Test
        @DisplayName("reset clears the Domination scores and the flag owners")
        void shouldResetDominationState()
        {
            final Match match = dominationMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(1000.0f, 1000.0f, 1)
            ));
            match.setPlayerTeam(Team.RED);
            for (int tic = 0; tic < 30; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
            }
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.RED);
            assertThat(match.teamScores()[0]).isGreaterThan(0);
            match.reset();
            // After reset: every flag is NEUTRAL, scores are 0. The
            // player team is preserved.
            assertThat(match.dominationFlagOwner(0)).isEqualTo(Team.NEUTRAL);
            assertThat(match.dominationFlagOwner(1)).isEqualTo(Team.NEUTRAL);
            assertThat(match.dominationFlagOwner(2)).isEqualTo(Team.NEUTRAL);
            assertThat(match.teamScores()).containsExactly(0, 0);
            assertThat(match.playerTeam()).isEqualTo(Team.RED);
        }
    }

    @Nested
    @DisplayName("flag index bounds")
    class FlagIndexBounds
    {
        @Test
        @DisplayName("dominationFlagOwner rejects an out-of-range index")
        void shouldRejectBadIndex()
        {
            final Match match = dominationMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));
            assertThatThrownBy(() -> match.dominationFlagOwner(-1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> match.dominationFlagOwner(3))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ----- fixtures ---------------------------------------------------------

    private static Bot redBotAt(final float x, final float z, final int entitySlot)
    {
        return new Bot(Match.FIRST_BOT_ENTITY_ID + entitySlot, x, 0.0f, z, BotPattern.SENTRY,
            0.0f, 60, 0, Team.RED);
    }

    private static Bot blueBotAt(final float x, final float z, final int entitySlot)
    {
        return new Bot(Match.FIRST_BOT_ENTITY_ID + entitySlot, x, 0.0f, z, BotPattern.SENTRY,
            0.0f, 60, 0, Team.BLUE);
    }

    private static Bot neutralBotAt(final float x, final float z, final int entitySlot)
    {
        return new Bot(Match.FIRST_BOT_ENTITY_ID + entitySlot, x, 0.0f, z, BotPattern.SENTRY,
            0.0f, 60, 0, Team.NEUTRAL);
    }

    private static Match dominationMatch(final List<Bot> roster)
    {
        final MapSpec spec = new MapSpec("test_dom", "Test DOM", MapSetting.DESERT_RAVINE,
            MatchMode.DOMINATION, new MapDimensions(160.0f, 160.0f, 128.0f),
            threeLanes(), threeSpawns(), List.of(), new MapMarkers.Domination(FLAGS),
            new MapAssets("a/level.ofm", "a/weapon.ofm", null));
        return new Match(roster.toArray(new Bot[0]), new BotRng(), BotSkill.MARKSMAN,
            Match.UNLIMITED_DEATHS, spec);
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

    private static List<SpawnPoint> threeSpawns()
    {
        return List.of(
            new SpawnPoint("sp_0", Team.RED, 0.0f, 0.0f, 0.0f, 0.0f),
            new SpawnPoint("sp_1", Team.BLUE, 100.0f, 0.0f, 100.0f, 0.0f)
        );
    }
}
