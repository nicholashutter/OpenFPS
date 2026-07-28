/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Match}.
 *
 * <p>The interesting behaviour is not the scorekeeping — it is that <b>both</b>
 * directions of fire are resolved by the same {@link Hitscan} against the same
 * kind of {@link Target}, so a bot standing in the line of fire really does stop
 * a shot, and a player really can use one bot as cover from another. Most of
 * what follows sets up a geometry where that either happens or does not, and
 * asserts which.</p>
 *
 * <h2>The geometry these tests use</h2>
 *
 * <pre>
 *   player feet (0, 0, 0), eye at y = 41, looking along +z
 *   a bot at (0, 0, 200) is 200 units away, well inside BOT_RANGE_UNITS
 *   its box spans x,z in +-16 of its feet and y in [0, 56]
 * </pre>
 *
 * <p>So a shot along +z from the player's eye enters the bot's box at z = 184,
 * and the bot's shot back enters the player's box at z = 16. Both rays sit at
 * y = 41, which is inside both boxes.</p>
 */
@DisplayName("Match")
class MatchTest
{
    /** Player feet, world x. */
    private static final float PLAYER_X = 0.0f;

    /** Player feet, world y — the floor. */
    private static final float PLAYER_Y = 0.0f;

    /** Player feet, world z. */
    private static final float PLAYER_Z = 0.0f;

    /** The player's eye, where their shots leave from. */
    private static final float PLAYER_EYE_Y = PlayerController.EYE_HEIGHT_UNITS;

    // A stationary bot at a given distance straight down +z, firing every
    // fireInterval tics starting at tic 0.
    private static Bot sentryAt(final int id, final float distance, final int fireInterval)
    {
        return new Bot(id, 0.0f, 0.0f, distance, BotPattern.SENTRY,
            0.0f, 60, 0, fireInterval, 0);
    }

    // A stationary bot that never fires within any tic count these tests reach.
    //
    // The offset is load-bearing: a huge interval alone still fires on tic 0,
    // because tic 0 is the start of the very first cycle. Offsetting by one puts
    // the first shot a hundred thousand tics away instead.
    private static Bot silentSentryAt(final int id, final float distance)
    {
        return new Bot(id, 0.0f, 0.0f, distance, BotPattern.SENTRY,
            0.0f, 60, 0, 100_000, 1);
    }

    // Advances the match one tic with the player standing at the origin.
    private static int tick(final Match match, final int ticIndex)
    {
        return match.tick(ticIndex, PLAYER_X, PLAYER_Y, PLAYER_Z);
    }

    // The player shoots straight ahead along +z.
    private static int shootAhead(final Match match)
    {
        return match.firePlayerShot(PLAYER_X, PLAYER_EYE_Y, PLAYER_Z, 0.0f, 0.0f, 1.0f);
    }

    @Nested
    @DisplayName("the player shooting")
    class PlayerFire
    {
        @Test
        @DisplayName("hits the bot the shot is aimed at")
        void shouldHitTheBotAheadWhenShootingAlongPositiveZ()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 600) });

            assertThat(shootAhead(match)).isEqualTo(2);
            assertThat(match.playerShotsHit()).isEqualTo(1);
        }

        @Test
        @DisplayName("takes three shots to kill, and counts the kill once")
        void shouldTakeThreeShotsToKillWhenDamageIs34()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 600) });

            shootAhead(match);
            shootAhead(match);
            assertThat(match.botsKilled()).as("still standing after two").isEqualTo(0);
            shootAhead(match);

            assertThat(match.botsKilled()).isEqualTo(1);
            assertThat(match.byId(2).isAlive()).isFalse();
        }

        @Test
        @DisplayName("hits the NEAREST bot when two line up")
        void shouldHitTheNearerBotWhenTwoAreInLine()
        {
            final Match match = new Match(new Bot[]
            {
                sentryAt(2, 400.0f, 600),
                sentryAt(3, 150.0f, 600),
            });

            assertThat(shootAhead(match)).isEqualTo(3);
        }

        @Test
        @DisplayName("misses when nothing is in the way")
        void shouldMissWhenAimedAtEmptySpace()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 600) });

            // Straight up. Nothing is above the player.
            final int struck =
                match.firePlayerShot(PLAYER_X, PLAYER_EYE_Y, PLAYER_Z, 0.0f, 1.0f, 0.0f);

            assertThat(struck).isEqualTo(Match.NO_HIT);
            assertThat(match.playerShotsFired()).isEqualTo(1);
            assertThat(match.playerShotsHit()).isEqualTo(0);
        }

        @Test
        @DisplayName("a killed bot stops blocking shots at the one behind it")
        void shouldShootThroughACorpseWhenTheNearerBotIsDead()
        {
            final Match match = new Match(new Bot[]
            {
                sentryAt(2, 400.0f, 600),
                sentryAt(3, 150.0f, 600),
            });

            shootAhead(match);
            shootAhead(match);
            shootAhead(match);
            assertThat(match.byId(3).isAlive()).isFalse();

            // A corpse that still blocked would give the player cover they
            // cannot see and make the room get harder as they cleared it.
            assertThat(shootAhead(match)).isEqualTo(2);
        }

        @Test
        @DisplayName("reports a miss rather than throwing when every bot is dead")
        void shouldReportAMissWhenNoBotsAreLeft()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 600) });
            shootAhead(match);
            shootAhead(match);
            shootAhead(match);

            assertThat(shootAhead(match)).isEqualTo(Match.NO_HIT);
        }
    }

    @Nested
    @DisplayName("bots shooting back")
    class ReturnFire
    {
        @Test
        @DisplayName("a bot in range and in the clear lands its shot")
        void shouldDamageThePlayerWhenABotHasAClearLine()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 1) });

            final int damage = tick(match, 0);

            assertThat(damage).isEqualTo(Match.BOT_SHOT_DAMAGE);
            assertThat(match.playerHealth())
                .isEqualTo(Match.PLAYER_MAX_HEALTH - Match.BOT_SHOT_DAMAGE);
            assertThat(match.botShotsLanded()).isEqualTo(1);
        }

        @Test
        @DisplayName("a bot beyond BOT_RANGE_UNITS cannot reach the player")
        void shouldNotDamageThePlayerWhenTheBotIsOutOfRange()
        {
            final Match match =
                new Match(new Bot[] { sentryAt(2, Match.BOT_RANGE_UNITS + 100.0f, 1) });

            assertThat(tick(match, 0)).isEqualTo(0);
            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("another bot in the line of fire blocks the shot")
        void shouldBlockTheShotWhenAnotherBotStandsInTheWay()
        {
            // The shooter is at z = 300 and the player at the origin; a third
            // body at z = 150 sits squarely between them.
            final Match match = new Match(new Bot[]
            {
                sentryAt(2, 300.0f, 1),
                silentSentryAt(3, 150.0f),
            });

            assertThat(tick(match, 0)).as("the shot must not reach the player").isEqualTo(0);
            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("the blocking bot takes no damage — there is no friendly fire")
        void shouldNotHurtTheBlockerWhenABotShootsThroughIt()
        {
            final Match match = new Match(new Bot[]
            {
                sentryAt(2, 300.0f, 1),
                silentSentryAt(3, 150.0f),
            });

            for (int tic = 0; tic < 200; tic++)
            {
                tick(match, tic);
            }

            // With friendly fire the room would clear itself while the player
            // watched, and the blocker would be the first to go.
            assertThat(match.byId(3).health()).isEqualTo(Bot.MAX_HEALTH);
            assertThat(match.livingBots()).isEqualTo(2);
        }

        @Test
        @DisplayName("a bot does not shoot itself, though its own box is at distance zero")
        void shouldNotShootItselfWhenResolvingItsOwnFire()
        {
            // Hitscan treats a ray origin inside a box as a hit at distance
            // zero, so a shooter listed among its own targets would kill itself
            // on the first tic. The exclusion is what stops that.
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 1) });

            for (int tic = 0; tic < 100; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.byId(2).health()).isEqualTo(Bot.MAX_HEALTH);
        }

        @Test
        @DisplayName("a dead bot stops shooting")
        void shouldStopFiringWhenKilled()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 1) });
            shootAhead(match);
            shootAhead(match);
            shootAhead(match);
            final int healthAfterTheKill = match.playerHealth();

            for (int tic = 0; tic < 100; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.playerHealth()).isEqualTo(healthAfterTheKill);
        }

        @Test
        @DisplayName("bots move before they shoot, so fire comes from where the body is")
        void shouldShootFromThePostMoveePositionWhenABotIsPatrolling()
        {
            // A bot pacing along x, half a period in, is 200 units to the side
            // of its home point — from which the player is out of range even
            // though the home point is not. If the shot were resolved before the
            // move it would come from the home point and land.
            final float reach = Match.BOT_RANGE_UNITS - 50.0f;
            final Bot pacer = new Bot(2, 0.0f, 0.0f, reach, BotPattern.PACE_X,
                400.0f, 4, 0, 1, 0);
            final Match match = new Match(new Bot[] { pacer });

            // At its home point — where the constructor placed it, and where it
            // still is when tick() is entered — the player is 462 units away and
            // comfortably in range. A quarter period later it is 400 units to
            // the side and 611 units away, which is out of range.
            assertThat(pacer.positionX()).isEqualTo(0.0f);

            tick(match, 1);

            assertThat(pacer.positionX()).isGreaterThan(300.0f);
            // So a shot resolved from the PRE-move position would have landed.
            // Return fire coming from where a body used to be is exactly the
            // sort of discrepancy a player notices and cannot explain.
            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }
    }

    @Nested
    @DisplayName("how a match ends")
    class Ending
    {
        @Test
        @DisplayName("starts in progress with everyone alive")
        void shouldStartInProgressWhenConstructed()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 600) });

            assertThat(match.state()).isEqualTo(MatchState.IN_PROGRESS);
            assertThat(match.state().isOver()).isFalse();
            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("is won when the last bot goes down")
        void shouldBeWonWhenEveryBotIsDead()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 100_000) });

            shootAhead(match);
            shootAhead(match);
            assertThat(match.state()).isEqualTo(MatchState.IN_PROGRESS);
            shootAhead(match);

            assertThat(match.state()).isEqualTo(MatchState.WON);
            assertThat(match.state().isOver()).isTrue();
        }

        @Test
        @DisplayName("is lost when the player runs out of health")
        void shouldBeLostWhenThePlayerDies()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 1) });

            for (int tic = 0; tic < 500; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.playerHealth()).isLessThanOrEqualTo(0);
            assertThat(match.state()).isEqualTo(MatchState.LOST);
        }

        @Test
        @DisplayName("takes exactly PLAYER_MAX_HEALTH / BOT_SHOT_DAMAGE hits to kill the player")
        void shouldSurviveTheExpectedNumberOfHitsWhenUnderFire()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 1) });
            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;

            for (int tic = 0; tic < hitsToKill - 1; tic++)
            {
                tick(match, tic);
            }
            assertThat(match.state()).as("one hit short").isEqualTo(MatchState.IN_PROGRESS);
            tick(match, hitsToKill - 1);

            assertThat(match.state()).isEqualTo(MatchState.LOST);
        }

        @Test
        @DisplayName("a dead player loses, even having cleared the room on the same tic")
        void shouldReportLostWhenThePlayerAndTheLastBotBothDie()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 1) });
            for (int tic = 0; tic < 500 && match.playerHealth() > 0; tic++)
            {
                tick(match, tic);
            }

            // Now clear the room from beyond the grave.
            shootAhead(match);
            shootAhead(match);
            shootAhead(match);

            assertThat(match.livingBots()).isEqualTo(0);
            // Reporting a win to a corpse is the kind of thing that is funny
            // once. LOST is checked first for exactly this case.
            assertThat(match.state()).isEqualTo(MatchState.LOST);
        }

        @Test
        @DisplayName("stops simulating once it is over")
        void shouldStopTickingWhenTheMatchIsDecided()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f, 1) });
            shootAhead(match);
            shootAhead(match);
            shootAhead(match);
            assertThat(match.state()).isEqualTo(MatchState.WON);

            assertThat(tick(match, 0)).isEqualTo(0);
            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("an empty room is won immediately")
        void shouldBeWonImmediatelyWhenThereAreNoBots()
        {
            // What a scene with no character art staged produces. Winning is the
            // right answer for a room with nobody in it, and is a great deal
            // better than a match that can never end.
            assertThat(new Match(new Bot[0]).state()).isEqualTo(MatchState.WON);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("rejects a bot holding the reserved player id")
        void shouldRejectABotOnThePlayerIdWhenConstructed()
        {
            assertThatThrownBy(() -> new Match(new Bot[]
            {
                sentryAt(Match.PLAYER_ENTITY_ID, 100.0f, 600),
            }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved player id");
        }

        @Test
        @DisplayName("rejects two bots sharing an entity id")
        void shouldRejectDuplicateEntityIdsWhenConstructed()
        {
            // A collision here is invisible and vicious: the outline pass would
            // merge two bodies, and damage aimed at one would land on whichever
            // the lookup found first.
            assertThatThrownBy(() -> new Match(new Bot[]
            {
                sentryAt(5, 100.0f, 600),
                sentryAt(5, 200.0f, 600),
            }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share entity id");
        }

        @Test
        @DisplayName("rejects a null array and a null entry")
        void shouldRejectNullsWhenConstructed()
        {
            assertThatThrownBy(() -> new Match(null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Match(new Bot[] { null }))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("copies the caller's array, so the roster cannot be swapped afterwards")
        void shouldCopyTheRosterWhenConstructed()
        {
            final Bot[] roster = { sentryAt(2, 200.0f, 600) };
            final Match match = new Match(roster);

            roster[0] = sentryAt(9, 50.0f, 600);

            assertThat(match.byId(2)).isNotNull();
            assertThat(match.byId(9)).isNull();
            assertThat(match.bots()).hasSize(1);
        }

        @Test
        @DisplayName("the player id is reserved below the first bot id")
        void shouldReserveThePlayerIdBelowTheBotRange()
        {
            assertThat(Match.FIRST_BOT_ENTITY_ID).isGreaterThan(Match.PLAYER_ENTITY_ID);
            assertThat(Match.PLAYER_ENTITY_ID).isGreaterThanOrEqualTo(Target.MIN_ENTITY_ID);
            // NO_HIT must not collide with Scene.UNTAGGED (0) or any real id.
            assertThat(Match.NO_HIT).isLessThan(Target.MIN_ENTITY_ID);
        }
    }

    @Nested
    @DisplayName("the default roster size")
    class Roster
    {
        @Test
        @DisplayName("is seven, and the whole roster is beatable in a bounded number of shots")
        void shouldClearSevenBotsInTwentyOneShots()
        {
            assertThat(Match.DEFAULT_BOT_COUNT).isEqualTo(7);

            final Bot[] roster = new Bot[Match.DEFAULT_BOT_COUNT];
            for (int index = 0; index < roster.length; index++)
            {
                // Stacked along +z so a single bearing reaches all of them in
                // turn as each one falls.
                roster[index] = sentryAt(Match.FIRST_BOT_ENTITY_ID + index,
                    100.0f + index * 50.0f, 100_000);
            }
            final Match match = new Match(roster);

            final int shotsToKill =
                (Bot.MAX_HEALTH + Match.PLAYER_SHOT_DAMAGE - 1) / Match.PLAYER_SHOT_DAMAGE;
            for (int shot = 0; shot < shotsToKill * Match.DEFAULT_BOT_COUNT; shot++)
            {
                shootAhead(match);
            }

            assertThat(match.state()).isEqualTo(MatchState.WON);
            assertThat(match.botsKilled()).isEqualTo(Match.DEFAULT_BOT_COUNT);
            assertThat(match.playerShotsFired()).isEqualTo(21);
        }
    }
}
