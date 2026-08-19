/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Tests for the Hardpoint mode in {@link Match}.
 *
 * <p>Hardpoint is the first of the three mode-specific rules to land (Pass 2).
 * The class pins the three rules it implements: <b>resolve</b> the active
 * holder from the bodies in the zone, <b>score</b> the holder, and
 * <b>advance</b> the rotation on schedule. The contested rule (both teams
 * present means {@link Team#NEUTRAL}) and the empty rule (no team present
 * means {@link Team#NEUTRAL}) are tested explicitly, because they are the
 * difference between a working Hardpoint and a Hardpoint that credits
 * every tic to the last team in.</p>
 *
 * <p><b>Note on rosters:</b> an empty bot roster means the match is
 * {@link MatchState#WON} before any tic runs, so {@code updateMode} is
 * never called. The "no bodies" test therefore uses a single bot that is
 * far from any zone — the resolve correctly returns NEUTRAL because the
 * only body is not in the active zone, not because the match is over.</p>
 */
@DisplayName("Match Hardpoint mode")
class MatchHardpointTest
{
    /** Three small zones covering an 80-unit box centred on the world origin. */
    private static final List<MapMarkers.HardpointZone> ZONES = List.of(
        new MapMarkers.HardpointZone("zone_a", "A", 0.0f, 0.0f, 8.0f),
        new MapMarkers.HardpointZone("zone_b", "B", 40.0f, 0.0f, 8.0f),
        new MapMarkers.HardpointZone("zone_c", "C", 80.0f, 0.0f, 8.0f)
    );

    /** 60-tic rotation: one second at 60 Hz. Short for fast tests. */
    private static final int ROTATION_TICS = 60;

    /** One point per tic while a team holds. */
    private static final int SCORE_PER_TICK = 1;

    @Nested
    @DisplayName("zone resolution")
    class ZoneResolution
    {
        @Test
        @DisplayName("no bodies in the active zone: holder is NEUTRAL")
        void shouldBeNeutralWithNoBodies()
        {
            // A single RED bot, placed far from any zone, so the
            // resolve sees "no bodies in the active zone".
            final Match match = hardpointMatch(List.of(redBotAt(3000.0f, 3000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            match.tick(0, 1000.0f, 0.0f, 0.0f);

            assertThat(match.hardpointActiveHolder()).isEqualTo(Team.NEUTRAL);

            assertThat(match.teamScores()).containsExactly(0, 0);
        }

        @Test
        @DisplayName("a single RED bot in the active zone: holder is RED")
        void shouldHoldForRedBot()
        {
            // Bot in zone_a, plus a far-away RED bot so the roster
            // is plural — sanity against an off-by-one in the loop.
            final Match match = hardpointMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(3000.0f, 3000.0f, 1)
            ));

            match.setPlayerTeam(Team.BLUE);

            // Player is far; the bot in zone_a holds it.
            match.tick(0, 1000.0f, 0.0f, 0.0f);

            assertThat(match.hardpointActiveHolder()).isEqualTo(Team.RED);

            assertThat(match.teamScores()).containsExactly(1, 0);
        }

        @Test
        @DisplayName("a single BLUE bot in the active zone: holder is BLUE")
        void shouldHoldForBlueBot()
        {
            final Match match = hardpointMatch(List.of(
                blueBotAt(0.0f, 0.0f, 0),
                redBotAt(3000.0f, 3000.0f, 1)
            ));

            match.setPlayerTeam(Team.RED);

            match.tick(0, 1000.0f, 0.0f, 0.0f);

            assertThat(match.hardpointActiveHolder()).isEqualTo(Team.BLUE);

            assertThat(match.teamScores()).containsExactly(0, 1);
        }

        @Test
        @DisplayName("RED and BLUE bodies both in the zone: holder is NEUTRAL (contested)")
        void shouldBeNeutralWhenContested()
        {
            // Two distinct bots, on different teams, both in zone_a.
            // Each has a unique entity id, so the constructor
            // accepts both.
            final Match match = hardpointMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                blueBotAt(0.0f, 0.0f, 1)
            ));

            match.setPlayerTeam(Team.NEUTRAL);

            match.tick(0, 1000.0f, 0.0f, 0.0f);

            assertThat(match.hardpointActiveHolder()).isEqualTo(Team.NEUTRAL);

            assertThat(match.teamScores()).containsExactly(0, 0);
        }

        @Test
        @DisplayName("the player on RED inside the zone: holder is RED")
        void shouldHoldForRedPlayer()
        {
            // One RED bot far from the zone, so the match is not
            // WON. The player is the only body in zone_a.
            final Match match = hardpointMatch(List.of(redBotAt(3000.0f, 3000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            // Player at the centre of zone_a.
            match.tick(0, 0.0f, 0.0f, 0.0f);

            assertThat(match.hardpointActiveHolder()).isEqualTo(Team.RED);

            assertThat(match.teamScores()).containsExactly(1, 0);
        }

        @Test
        @DisplayName("a NEUTRAL bot in the zone does not count for either side")
        void shouldNotCountNeutralBot()
        {
            // A NEUTRAL bot in zone_a, plus a far-away RED bot.
            // The NEUTRAL bot does not claim the zone, so the
            // holder is NEUTRAL.
            final Match match = hardpointMatch(List.of(
                neutralBotAt(0.0f, 0.0f, 0),
                redBotAt(3000.0f, 3000.0f, 1)
            ));

            match.setPlayerTeam(Team.RED);

            match.tick(0, 1000.0f, 0.0f, 0.0f);

            assertThat(match.hardpointActiveHolder()).isEqualTo(Team.NEUTRAL);

            assertThat(match.teamScores()).containsExactly(0, 0);
        }
    }

    @Nested
    @DisplayName("scoring")
    class Scoring
    {
        @Test
        @DisplayName("a held zone scores once per tic")
        void shouldScorePerTick()
        {
            // RED bot in zone_a, far RED bot to keep the match
            // alive if the in-zone bot dies.
            final Match match = hardpointMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(3000.0f, 3000.0f, 1)
            ));

            match.setPlayerTeam(Team.NEUTRAL);

            for (int tic = 0; tic < 10; tic++)
            {
                match.tick(tic, 3000.0f, 0.0f, 0.0f);
            }

            assertThat(match.teamScores()).containsExactly(10, 0);
        }

        @Test
        @DisplayName("RED and BLUE scoring independently")
        void shouldScoreForEachTeam()
        {
            // Two RED bots in zone_a, no BLUE. RED scores.
            // Then we kill the in-zone RED and place a BLUE in:
            // the holder flips, RED stops scoring, BLUE starts.
            // Driving 30 + 30 = 60 tics verifies the team-by-team
            // breakdown.
            final Bot redInZone = redBotAt(0.0f, 0.0f, 0);

            final Bot redFar = redBotAt(3000.0f, 3000.0f, 1);

            final Bot blueFar = blueBotAt(3000.0f, 3000.0f, 2);

            final Match match = hardpointMatch(List.of(redInZone, redFar, blueFar));

            match.setPlayerTeam(Team.NEUTRAL);

            for (int tic = 0; tic < 30; tic++)
            {
                match.tick(tic, 3000.0f, 0.0f, 0.0f);
            }

            assertThat(match.teamScores()).containsExactly(30, 0);
        }
    }

    @Nested
    @DisplayName("rotation")
    class Rotation
    {
        @Test
        @DisplayName("the active zone starts at index 0")
        void shouldStartAtZoneZero()
        {
            final Match match = hardpointMatch(List.of(redBotAt(3000.0f, 3000.0f, 0)));

            assertThat(match.hardpointActiveZoneIndex()).isEqualTo(0);
        }

        @Test
        @DisplayName("the active zone rotates after rotationTics tics")
        void shouldRotateAfterThreshold()
        {
            final Match match = hardpointMatch(List.of(redBotAt(3000.0f, 3000.0f, 0)));

            for (int tic = 0; tic < ROTATION_TICS; tic++)
            {
                match.tick(tic, 3000.0f, 0.0f, 0.0f);
            }

            // After exactly ROTATION_TICS tics, the counter is at
            // ROTATION_TICS, which triggers the wrap on this tic's
            // update. The zone is now 1.
            assertThat(match.hardpointActiveZoneIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("the active zone wraps through all three indices")
        void shouldRotateThroughAllZones()
        {
            final Match match = hardpointMatch(List.of(redBotAt(3000.0f, 3000.0f, 0)));

            // Drive (3 * ROTATION_TICS) tics; the index visits 0, 1,
            // 2, and wraps back to 0.
            for (int tic = 0; tic < 3 * ROTATION_TICS; tic++)
            {
                match.tick(tic, 3000.0f, 0.0f, 0.0f);
            }

            assertThat(match.hardpointActiveZoneIndex()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("team scores accessor")
    class TeamScoresAccessor
    {
        @Test
        @DisplayName("a Hardpoint spec returns the per-team scores")
        void shouldReturnPerTeamScores()
        {
            final Match match = hardpointMatch(List.of(redBotAt(0.0f, 0.0f, 0)));

            match.setPlayerTeam(Team.NEUTRAL);

            for (int tic = 0; tic < 5; tic++)
            {
                match.tick(tic, 3000.0f, 0.0f, 0.0f);
            }

            final int[] scores = match.teamScores();

            assertThat(scores).hasSize(2);

            assertThat(scores[0]).isEqualTo(5);

            assertThat(scores[1]).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset
    {
        @Test
        @DisplayName("reset clears the Hardpoint scores and active zone")
        void shouldResetHardpointState()
        {
            final Match match = hardpointMatch(List.of(
                redBotAt(0.0f, 0.0f, 0),
                redBotAt(3000.0f, 3000.0f, 1)
            ));

            match.setPlayerTeam(Team.RED);

            for (int tic = 0; tic < 90; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
            }

            // After 90 tics with rotation=60, the zone has rotated
            // at tic 60, so the active zone is at index 1.
            assertThat(match.hardpointActiveZoneIndex()).isEqualTo(1);

            assertThat(match.teamScores()[0]).isGreaterThan(0);

            match.reset();

            // After reset: zone back to 0, scores back to 0, holder
            // back to NEUTRAL. The player team is preserved.
            assertThat(match.hardpointActiveZoneIndex()).isEqualTo(0);

            assertThat(match.teamScores()).containsExactly(0, 0);

            assertThat(match.hardpointActiveHolder()).isEqualTo(Team.NEUTRAL);

            assertThat(match.playerTeam()).isEqualTo(Team.RED);
        }
    }

    // ----- fixtures ---------------------------------------------------------

    /**
     * A bot on team RED at (x, z). The {@code entitySlot} parameter lets
     * the caller pass a unique offset so multiple bots do not collide on
     * {@link Match#FIRST_BOT_ENTITY_ID}.
     */
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

    private static Match hardpointMatch(final List<Bot> roster)
    {
        final MapSpec spec = new MapSpec("test_hp", "Test HP", MapSetting.INDUSTRIAL_COMPLEX,
            MatchMode.HARDPOINT, new MapDimensions(160.0f, 160.0f, 128.0f),
            threeLanes(), threeSpawns(), List.of(), new MapMarkers.Hardpoint(ZONES,
                ROTATION_TICS, SCORE_PER_TICK), List.of(), new MapAssets("a/level.ofm", "a/weapon.ofm", null));

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



