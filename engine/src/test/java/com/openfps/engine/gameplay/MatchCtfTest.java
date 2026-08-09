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
 * Tests for the CTF mode in {@link Match}.
 *
 * <p>CTF is the third of the three mode-specific rules to land (Pass 4).
 * The class pins the four rules it implements: <b>pickup</b> on touch,
 * <b>return on touch</b> at the player's home base, <b>capture on
 * touch</b> at the player's home capture point, and <b>drop on
 * death</b>. The standard COD rules: a dead carrier is a save, not a
 * recovery; only the local player carries (bots patrol as defenders);
 * the match ends on 5 captures or the time limit.</p>
 *
 * <p><b>Test-fixture note:</b> the spec places RED's base at the
 * world origin (flag at (0, 0), capture at (0, 0), radius 32) and
 * BLUE's base at (200, 0, 0) — far enough apart that the player
 * cannot be in both base radii at once. The pickup tic positions the
 * player at (200, 0, 0); the capture tic positions the player at
 * (0, 0, 0). The carrier check (which is "carrying the enemy flag
 * AND standing on the home base") only fires on the capture tic.</p>
 *
 * <p><b>Damage note:</b> most tests use a single RED bot at
 * (1000, 1000) — a far-away sentry out of the bot's
 * {@link Match#BOT_RANGE_UNITS}. The shot fires but does not connect,
 * so the player survives every test. The drop-on-death test needs the
 * player to actually die, so it uses a closer bot at (0, 0, 300) and
 * drives enough tics for the player to lose 100 HP.</p>
 */
@DisplayName("Match CTF mode")
class MatchCtfTest
{
    /** RED base — the flag and capture point sit at the world origin. */
    private static final MapMarkers.Base RED_BASE = new MapMarkers.Base(Team.RED,
        0.0f, 0.0f, 0.0f, 0.0f, 32.0f);

    /** BLUE base — the flag and capture point sit 200 units east of RED. */
    private static final MapMarkers.Base BLUE_BASE = new MapMarkers.Base(Team.BLUE,
        200.0f, 0.0f, 200.0f, 0.0f, 32.0f);

    @Nested
    @DisplayName("initial state")
    class InitialState
    {
        @Test
        @DisplayName("both flags start at home, no captures, no carrier")
        void shouldStartWithFlagsAtHome()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            assertThat(match.ctfRedFlagCarrier()).isNull();

            assertThat(match.ctfBlueFlagCarrier()).isNull();

            assertThat(match.ctfRedCaptures()).isZero();

            assertThat(match.ctfBlueCaptures()).isZero();

            assertThat(match.teamScores()).containsExactly(0, 0);
        }
    }

    @Nested
    @DisplayName("pickup")
    class Pickup
    {
        @Test
        @DisplayName("a RED player on BLUE's flag picks it up")
        void shouldPickUpForRed()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            // Player at BLUE's flag (200, 0, 0); flag is at home.
            match.tick(0, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);

            assertThat(match.ctfRedFlagCarrier()).isNull();
        }

        @Test
        @DisplayName("a BLUE player on RED's flag picks it up")
        void shouldPickUpForBlue()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.BLUE);

            // Player at RED's flag (0, 0, 0); RED's flag is at home.
            match.tick(0, 0.0f, 0.0f, 0.0f);

            assertThat(match.ctfRedFlagCarrier()).isEqualTo(Team.BLUE);

            assertThat(match.ctfBlueFlagCarrier()).isNull();
        }

        @Test
        @DisplayName("a NEUTRAL player does not pick up either flag")
        void shouldNotPickUpForNeutral()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            // playerTeam defaults to NEUTRAL.
            // Player at BLUE's flag; nothing should happen.
            match.tick(0, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isNull();

            assertThat(match.ctfRedFlagCarrier()).isNull();
        }

        @Test
        @DisplayName("a player on their own flag does not pick it up")
        void shouldNotPickUpOwnFlag()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            // Player at RED's own flag (0, 0, 0). RED's flag is at home.
            // The pickup check looks at the ENEMY flag only, so this is
            // a no-op even though the player is inside the home base
            // radius.
            match.tick(0, 0.0f, 0.0f, 0.0f);

            assertThat(match.ctfRedFlagCarrier()).isNull();

            assertThat(match.ctfBlueFlagCarrier()).isNull();
        }

        @Test
        @DisplayName("a carrier touching the enemy base again does not double-pickup")
        void shouldNotDoublePickup()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            // Tic 0: pickup.
            match.tick(0, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);

            // Tic 1: still on BLUE's flag, already carrying. The pickup
            // check sees the slot is non-null and skips.
            match.tick(1, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);
        }
    }

    @Nested
    @DisplayName("return and capture")
    class ReturnAndCapture
    {
        @Test
        @DisplayName("a carrier on the home flag (not the capture point) returns both flags")
        void shouldReturnOnHomeFlag()
        {
            // RED player carrying BLUE's flag, touching RED's home
            // flag. The home flag is at the same coordinates as the
            // capture point, so we move the capture point off to a
            // different spot to test the save-only path.
            final MapMarkers.Base redBaseNoCapture = new MapMarkers.Base(Team.RED,
                0.0f, 0.0f, 500.0f, 500.0f, 32.0f);

            final MapMarkers.Base blueBase = new MapMarkers.Base(Team.BLUE,
                200.0f, 0.0f, 200.0f, 0.0f, 32.0f);

            final MatchSpecHolder holder = ctfMatchWithBases(redBaseNoCapture, blueBase,
                List.of(redBotAt(1000.0f, 1000.0f, 0)));

            final Match match = holder.match;

            match.setPlayerTeam(Team.RED);

            // Tic 0: pickup.
            match.tick(0, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);

            // Tic 1: on RED's home flag, NOT on the capture point
            // (which is at (500, 0)). The save fires: both flags
            // return home, no score.
            match.tick(1, 0.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isNull();

            assertThat(match.ctfRedFlagCarrier()).isNull();

            assertThat(match.teamScores()).containsExactly(0, 0);
        }

        @Test
        @DisplayName("a carrier on the capture point scores a capture and returns the flag")
        void shouldCaptureOnCapturePoint()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            // Tic 0: pickup.
            match.tick(0, 200.0f, 0.0f, 0.0f);

            // Tic 1: on RED's capture point (which is at the home
            // flag). Capture fires: RED +1, both flags return.
            match.tick(1, 0.0f, 0.0f, 0.0f);

            assertThat(match.ctfRedFlagCarrier()).isNull();

            assertThat(match.ctfBlueFlagCarrier()).isNull();

            assertThat(match.teamScores()).containsExactly(1, 0);
        }

        @Test
        @DisplayName("a carrier on neither the home flag nor the capture point does nothing")
        void shouldDoNothingWhenFarFromHome()
        {
            // RED base at origin, BLUE base at (200, 0, 0), but
            // the carrier is at (100, 0, 0) — in neither base
            // radius.
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            // Tic 0: pickup at (200, 0, 0).
            match.tick(0, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);

            // Tic 1: at (100, 0, 0). Not in RED's flag radius (100
            // units > 32) and not in RED's capture radius (also
            // 100 > 32). No save, no capture.
            match.tick(1, 100.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);

            assertThat(match.teamScores()).containsExactly(0, 0);
        }
    }

    @Nested
    @DisplayName("drop on death")
    class DropOnDeath
    {
        @Test
        @DisplayName("a carrier who dies returns the carried flag to its base")
        void shouldDropOnDeath()
        {
            // 1 in-range RED bot at (0, 0, 300) — within
            // BOT_RANGE_UNITS (512), so its shots connect. The
            // player loses 20 HP per tic.
            final Match match = ctfMatchWithInRangeBot(
                List.of(redBotAt(0.0f, 300.0f, 0)));

            match.setPlayerTeam(Team.RED);

            // Tic 0: pickup at BLUE's flag. Player takes 20 damage
            // (HP = 80).
            match.tick(0, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);

            // Tics 1-3: still at (200, 0, 0). The carrier is in
            // BLUE's base radius, but the pickup check sees the
            // slot is non-null. The carrier check sees the player
            // is NOT in RED's base radius. Nothing happens in CTF.
            // Each tic takes 20 damage.
            for (int tic = 1; tic <= 3; tic++)
            {
                match.tick(tic, 200.0f, 0.0f, 0.0f);
            }

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);

            assertThat(match.playerHealth()).isEqualTo(20);

            // Tic 4: the bot's shot drops the player to 0 HP, the
            // player is killed, then updateCtf runs and sees
            // playerDown = true — both flags return home.
            match.tick(4, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isNull();

            assertThat(match.ctfRedFlagCarrier()).isNull();
        }
    }

    @Nested
    @DisplayName("scoring")
    class Scoring
    {
        @Test
        @DisplayName("three captures accumulate on the per-team count")
        void shouldAccumulateCaptures()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            for (int tic = 0; tic < 6; tic++)
            {
                if (tic % 2 == 0)
                {
                    // Pickup tics: player at (200, 0, 0).
                    match.tick(tic, 200.0f, 0.0f, 0.0f);
                }
                else
                {
                    // Capture tics: player at (0, 0, 0).
                    match.tick(tic, 0.0f, 0.0f, 0.0f);
                }
            }

            assertThat(match.teamScores()).containsExactly(3, 0);
        }

        @Test
        @DisplayName("RED and BLUE captures accumulate independently")
        void shouldScorePerTeam()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            // Two RED captures, then switch sides and one BLUE
            // capture. The match's playerTeam is the player's
            // team, so we change it between sequences.
            match.setPlayerTeam(Team.RED);

            // 2 RED captures (4 tics: pickup, capture, pickup, capture).
            for (int tic = 0; tic < 4; tic++)
            {
                if (tic % 2 == 0)
                {
                    match.tick(tic, 200.0f, 0.0f, 0.0f);
                }
                else
                {
                    match.tick(tic, 0.0f, 0.0f, 0.0f);
                }
            }

            assertThat(match.teamScores()).containsExactly(2, 0);

            // Switch to BLUE and capture once.
            match.setPlayerTeam(Team.BLUE);

            match.tick(4, 0.0f, 0.0f, 0.0f);   // pickup RED's flag

            match.tick(5, 200.0f, 0.0f, 0.0f); // capture at BLUE's base

            assertThat(match.teamScores()).containsExactly(2, 1);
        }
    }

    @Nested
    @DisplayName("team scores accessor")
    class TeamScoresAccessor
    {
        @Test
        @DisplayName("a CTF spec returns the per-team capture counts")
        void shouldReturnPerTeamCaptures()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            for (int tic = 0; tic < 4; tic++)
            {
                if (tic % 2 == 0)
                {
                    match.tick(tic, 200.0f, 0.0f, 0.0f);
                }
                else
                {
                    match.tick(tic, 0.0f, 0.0f, 0.0f);
                }
            }

            final int[] scores = match.teamScores();

            assertThat(scores).hasSize(2);

            assertThat(scores[0]).isEqualTo(2);

            assertThat(scores[1]).isZero();
        }
    }

    @Nested
    @DisplayName("capture limit")
    class CaptureLimit
    {
        @Test
        @DisplayName("four captures: match is still IN_PROGRESS")
        void shouldStillBeInProgressAtFourCaptures()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            for (int tic = 0; tic < 8; tic++)
            {
                if (tic % 2 == 0)
                {
                    match.tick(tic, 200.0f, 0.0f, 0.0f);
                }
                else
                {
                    match.tick(tic, 0.0f, 0.0f, 0.0f);
                }
            }

            assertThat(match.ctfRedCaptures()).isEqualTo(4);

            assertThat(match.state()).isEqualTo(MatchState.IN_PROGRESS);
        }

        @Test
        @DisplayName("five captures: match is WON")
        void shouldBeWonAtFiveCaptures()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            for (int tic = 0; tic < 10; tic++)
            {
                if (tic % 2 == 0)
                {
                    match.tick(tic, 200.0f, 0.0f, 0.0f);
                }
                else
                {
                    match.tick(tic, 0.0f, 0.0f, 0.0f);
                }
            }

            assertThat(match.ctfRedCaptures()).isEqualTo(5);

            assertThat(match.state()).isEqualTo(MatchState.WON);
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset
    {
        @Test
        @DisplayName("reset clears the CTF carriers, scores, and elapsed tics")
        void shouldResetCtfState()
        {
            final Match match = ctfMatch(List.of(redBotAt(1000.0f, 1000.0f, 0)));

            match.setPlayerTeam(Team.RED);

            // Drive 2 captures.
            for (int tic = 0; tic < 4; tic++)
            {
                if (tic % 2 == 0)
                {
                    match.tick(tic, 200.0f, 0.0f, 0.0f);
                }
                else
                {
                    match.tick(tic, 0.0f, 0.0f, 0.0f);
                }
            }

            assertThat(match.ctfRedCaptures()).isEqualTo(2);

            assertThat(match.ctfBlueFlagCarrier()).isNull();

            // Now drive a pickup, then reset, then verify the pickup
            // is gone (the rematch opens with both flags at home).
            match.tick(4, 200.0f, 0.0f, 0.0f);

            assertThat(match.ctfBlueFlagCarrier()).isEqualTo(Team.RED);

            match.reset();

            assertThat(match.ctfRedFlagCarrier()).isNull();

            assertThat(match.ctfBlueFlagCarrier()).isNull();

            assertThat(match.ctfRedCaptures()).isZero();

            assertThat(match.ctfBlueCaptures()).isZero();

            // The player's team is a rematch input, not output.
            assertThat(match.playerTeam()).isEqualTo(Team.RED);
        }
    }

    // ----- fixtures ---------------------------------------------------------

    /**
     * Holder for a match plus its spec, returned by the bases-aware
     * factory so a test that needs a custom spec can read it back.
     */
    private static final class MatchSpecHolder
    {
        final Match match;
        @SuppressWarnings("unused")
        final MapSpec spec;
        MatchSpecHolder(final Match match, final MapSpec spec)
        {
            this.match = match;

            this.spec = spec;
        }
    }

    private static Bot redBotAt(final float x, final float z, final int entitySlot)
    {
        return new Bot(Match.FIRST_BOT_ENTITY_ID + entitySlot, x, 0.0f, z, BotPattern.SENTRY,
            0.0f, 60, 0, Team.RED);
    }

    /**
     * The default CTF match: RED's base at the origin, BLUE's base at
     * (200, 0, 0), one far-away RED bot. The far-away bot is past
     * {@link Match#BOT_RANGE_UNITS} so its shots do not connect and
     * the player survives every test.
     */
    private static Match ctfMatch(final List<Bot> roster)
    {
        return ctfMatchWithBases(RED_BASE, BLUE_BASE, roster).match;
    }

    private static MatchSpecHolder ctfMatchWithBases(final MapMarkers.Base redBase,
        final MapMarkers.Base blueBase, final List<Bot> roster)
    {
        final MapSpec spec = new MapSpec("test_ctf", "Test CTF", MapSetting.ARCTIC_STATION,
            MatchMode.CTF, new MapDimensions(160.0f, 160.0f, 128.0f),
            threeLanes(), threeSpawns(), List.of(),
            new MapMarkers.CaptureTheFlag(redBase, blueBase),
            new MapAssets("a/level.ofm", "a/weapon.ofm", null));

        final Match match = new Match(roster.toArray(new Bot[0]), new BotRng(), BotSkill.MARKSMAN,
            Match.UNLIMITED_DEATHS, spec);

        return new MatchSpecHolder(match, spec);
    }

    /**
     * The drop-on-death variant: same bases, but the bot is at
     * (0, 0, 300) — within {@link Match#BOT_RANGE_UNITS} so its
     * shots connect. The match ends when the player dies after
     * enough tics; tests that need this have to stop driving
     * before the player respawns.
     */
    private static Match ctfMatchWithInRangeBot(final List<Bot> roster)
    {
        return ctfMatch(roster);
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
