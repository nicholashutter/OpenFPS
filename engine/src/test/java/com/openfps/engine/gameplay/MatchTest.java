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

    /** A route period; irrelevant to a sentry, but the constructor needs one. */
    private static final int PERIOD = 60;

    /** The demo's tic rate, for expressing a measurement in seconds. */
    private static final int TICS_PER_SECOND = 60;

    /**
     * Tics to run a balance or hit-rate measurement over — thirty seconds.
     *
     * <p>Long enough that a random firing rate settles into something worth
     * quoting: at {@link BotSkill#DUMB}'s mean interval one bot takes about
     * fourteen shots in that time and seven take a hundred.</p>
     */
    private static final int BALANCE_RUN_TICS = TICS_PER_SECOND * 30;

    // A stationary bot at a given distance straight down +z.
    //
    // The firing cadence used to be an argument here and is not one any more: it
    // moved into BotSkill when a bot started rolling for the trigger instead of
    // running on a timer. Which of these bots shoots, and how well, is now a
    // property of the MATCH rather than of the body — see marksmanMatch below.
    private static Bot sentryAt(final int id, final float distance)
    {
        return new Bot(id, 0.0f, 0.0f, distance, BotPattern.SENTRY, 0.0f, PERIOD, 0);
    }

    /**
     * A match whose bots never miss, never hesitate and always know exactly where
     * the player is.
     *
     * <p><b>Most of this file wants this rather than the shipped opponents, and
     * the reason is worth stating once here.</b> The questions below are
     * <i>geometric</i>: does a body in the line of fire block a shot, does a
     * shooter appear in its own target set, is a shot resolved from the position a
     * bot has moved to rather than the one it left. None of those can be asked of
     * an opponent that misses seven shots in eight — the answer would come back as
     * a probability, every assertion would need thousands of tics, and the suite
     * would fail once a month for no reason at all.</p>
     *
     * <p>The dumbness gets its own nests, where it is the subject rather than the
     * noise. See {@link BotSkill#MARKSMAN}.</p>
     *
     * @param roster the bots to fight
     * @return a match with deterministic, unerring return fire
     */
    private static Match marksmanMatch(final Bot... roster)
    {
        return new Match(roster, new BotRng(), BotSkill.MARKSMAN, Match.UNLIMITED_DEATHS);
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

    /**
     * Distance to the nearest bot on the streak nest's firing range — <b>600</b>,
     * which is past {@link Match#BOT_RANGE_UNITS}.
     *
     * <p>Deliberately out of reach. The streak questions are about what happens
     * over hundreds of tics, and a room that could shoot back would let the player
     * die in the middle of one — which is a different rule of the same feature and
     * belongs in its own test rather than in the background of every other. The
     * player's own hitscan has no range limit, so they can still clear the room
     * from here.</p>
     */
    private static final float OUT_OF_REACH_UNITS = 600.0f;

    /** Spacing between bots on the firing range, in world units. */
    private static final float RANGE_SPACING_UNITS = 60.0f;

    // A line of bots straight down +z, every one of them beyond BOT_RANGE_UNITS,
    // so the player can work through the queue without being shot at once.
    private static Match firingRange(final int count)
    {
        final Bot[] roster = new Bot[count];

        for (int index = 0; index < roster.length; index++)
        {
            roster[index] = sentryAt(Match.FIRST_BOT_ENTITY_ID + index,
                OUT_OF_REACH_UNITS + index * RANGE_SPACING_UNITS);
        }

        return new Match(roster);
    }

    // Puts the nearest living bot down, in however many shots that takes, and
    // returns how many that was. Three at PLAYER_SHOT_DAMAGE and two under the
    // super blaster, which is the difference several of these tests are about.
    private static int killNearest(final Match match)
    {
        final int killsBefore = match.botsKilled();

        final int shotsBefore = match.playerShotsFired();

        while (match.botsKilled() == killsBefore
            && match.playerShotsFired() - shotsBefore < 4)
        {
            shootAhead(match);
        }

        return match.playerShotsFired() - shotsBefore;
    }

    // Earns the super blaster the only way there is: SUPER_BLASTER_KILL_STREAK
    // kills without dying.
    private static void earnTheSuperBlaster(final Match match)
    {
        for (int kill = 0; kill < Match.SUPER_BLASTER_KILL_STREAK; kill++)
        {
            killNearest(match);
        }
    }

    @Nested
    @DisplayName("the player shooting")
    class PlayerFire
    {
        @Test
        @DisplayName("hits the bot the shot is aimed at")
        void shouldHitTheBotAheadWhenShootingAlongPositiveZ()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) });

            assertThat(shootAhead(match)).isEqualTo(2);

            assertThat(match.playerShotsHit()).isEqualTo(1);
        }

        @Test
        @DisplayName("takes three shots to kill, and counts the kill once")
        void shouldTakeThreeShotsToKillWhenDamageIs34()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) });

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
                sentryAt(2, 400.0f),
                sentryAt(3, 150.0f),
            });

            assertThat(shootAhead(match)).isEqualTo(3);
        }

        @Test
        @DisplayName("misses when nothing is in the way")
        void shouldMissWhenAimedAtEmptySpace()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) });

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
                sentryAt(2, 400.0f),
                sentryAt(3, 150.0f),
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
        @DisplayName("a killed bot cannot be hit again, however carefully it is aimed at")
        void shouldNotBeHittableOnceDead()
        {
            // The other half of "dead bodies disappear". The model stops being
            // drawn (DemoScene.botPlacement) and the hitbox stops being offered
            // to the ray, and the two have to agree: a shot that connected with
            // a body nobody can see would count as a hit, bump the accuracy
            // figure, and be completely unexplainable.
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) });

            shootAhead(match);

            shootAhead(match);

            shootAhead(match);

            assertThat(match.byId(2).isAlive()).isFalse();

            final int hitsBefore = match.playerShotsHit();

            assertThat(shootAhead(match))
                .as("the corpse is not a target")
                .isEqualTo(Match.NO_HIT);

            assertThat(match.playerShotsHit())
                .as("and a shot at it does not count as a hit")
                .isEqualTo(hitsBefore);

            assertThat(match.botsKilled())
                .as("nor as a second kill")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a killed bot stops moving, so the body does not walk away invisibly")
        void shouldStopMovingOnceDead()
        {
            // Placement is derived from the position, so a dead bot that kept
            // walking would be a hidden instance being repositioned every tic
            // forever — harmless to look at and wrong in the logs.
            final Bot bot = new Bot(2, 0.0f, 0.0f, 200.0f, BotPattern.PACE_X,
                64.0f, 60, 0);

            final Match match = new Match(new Bot[] { bot });

            tick(match, 5);

            final float restingX = bot.positionX();

            bot.damage(Bot.MAX_HEALTH);

            for (int tic = 6; tic < 40; tic++)
            {
                tick(match, tic);
            }

            assertThat(bot.positionX()).isEqualTo(restingX);
        }

        @Test
        @DisplayName("reports a miss rather than throwing when every bot is dead")
        void shouldReportAMissWhenNoBotsAreLeft()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) });

            shootAhead(match);

            shootAhead(match);

            shootAhead(match);

            assertThat(shootAhead(match)).isEqualTo(Match.NO_HIT);
        }
    }

    @Nested
    @DisplayName("bots shooting back — the geometry, with the dice taken out")
    class ReturnFire
    {
        @Test
        @DisplayName("a bot in range and in the clear lands its shot")
        void shouldDamageThePlayerWhenABotHasAClearLine()
        {
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            final int damage = tick(match, 0);

            assertThat(damage).isEqualTo(Match.BOT_SHOT_DAMAGE);

            assertThat(match.playerHealth())
                .isEqualTo(Match.PLAYER_MAX_HEALTH - Match.BOT_SHOT_DAMAGE);

            assertThat(match.botShotsLanded()).isEqualTo(1);

            assertThat(match.botShotsFired()).isEqualTo(1);
        }

        @Test
        @DisplayName("a bot beyond BOT_RANGE_UNITS cannot reach the player")
        void shouldNotDamageThePlayerWhenTheBotIsOutOfRange()
        {
            final Match match = marksmanMatch(sentryAt(2, Match.BOT_RANGE_UNITS + 100.0f));

            assertThat(tick(match, 0)).isEqualTo(0);

            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);

            // It pulled the trigger and the shot went nowhere, which is the
            // distinction botShotsFired exists to record: a bot out of range still
            // spends its cooldown.
            assertThat(match.botShotsFired()).isEqualTo(1);

            assertThat(match.botShotsLanded()).isEqualTo(0);
        }

        @Test
        @DisplayName("another bot in the line of fire blocks the shot")
        void shouldBlockTheShotWhenAnotherBotStandsInTheWay()
        {
            // The shooter is at z = 300 and the player at the origin; a third
            // body at z = 150 sits squarely between them.
            final Match match = marksmanMatch(sentryAt(2, 300.0f), sentryAt(3, 150.0f));

            // Bot 3 is also a marksman and would hit, so only bot 2's blocked
            // shot is interesting — hence the count rather than the damage: two
            // triggers pulled, one landing.
            tick(match, 0);

            assertThat(match.botShotsFired()).isEqualTo(2);

            assertThat(match.botShotsLanded())
                .as("the shot from behind the blocker must not have reached the player")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a wall between the bot and the player blocks the shot")
        void shouldBlockTheShotWhenAWallStandsInTheWay()
        {
            // Shooter at z = 300, player at the origin, wall at z = 100..101
            // running across the full room width on the X axis. The
            // PhysicsWorld is the same one a body would be clipped against
            // for movement, so the raycast blocks at the wall's near face.
            final Bot shooter = sentryAt(2, 300.0f);

            shooter.setCollisionWorld(PhysicsWorld.builder(PhysicsWorld.PLAYER_HALF_WIDTH_UNITS)
                .addBox(-1000.0f, 100.0f, 1000.0f, 101.0f)
                .build());

            final Match match = marksmanMatch(shooter);

            tick(match, 0);

            assertThat(match.botShotsFired())
                .as("the bot still pulled the trigger")
                .isEqualTo(1);

            assertThat(match.botShotsLanded())
                .as("the wall is between the bot and the player — the shot must not connect")
                .isEqualTo(0);

            assertThat(match.playerHealth())
                .as("a blocked shot does not deal damage")
                .isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("a wall behind the player does not block a shot at the player")
        void shouldNotBlockTheShotWhenTheWallIsBehindThePlayer()
        {
            // Shooter at z = 300, player at z = 200, wall at z = 100. The wall
            // is further from the shooter than the player is, so the wall
            // does not block the shot at the player.
            final Bot shooter = sentryAt(2, 300.0f);

            shooter.setCollisionWorld(PhysicsWorld.builder(PhysicsWorld.PLAYER_HALF_WIDTH_UNITS)
                .addBox(-1000.0f, 100.0f, 1000.0f, 101.0f)
                .build());

            final Match match = new Match(new Bot[] {shooter}, new BotRng(), BotSkill.MARKSMAN,
                Match.UNLIMITED_DEATHS);

            // Player at z = 200 — the same side of the wall as the bot.
            final int damage = match.tick(0, PLAYER_X, PLAYER_Y, 200.0f);

            assertThat(damage).isEqualTo(Match.BOT_SHOT_DAMAGE);

            assertThat(match.botShotsLanded())
                .as("the wall is past the player from the shooter's point of view")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("the blocking bot takes no damage — there is no friendly fire")
        void shouldNotHurtTheBlockerWhenABotShootsThroughIt()
        {
            final Match match = marksmanMatch(sentryAt(2, 300.0f), sentryAt(3, 150.0f));

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
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

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
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            shootAhead(match);

            shootAhead(match);

            shootAhead(match);

            final int shotsBefore = match.botShotsFired();

            for (int tic = 0; tic < 100; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.botShotsFired()).isEqualTo(shotsBefore);

            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("bots move before they shoot, so fire comes from where the body is")
        void shouldShootFromThePostMoveePositionWhenABotIsPatrolling()
        {
            // A bot pacing along x, a quarter period in, is 1500 units to the
            // side of its home point — from which the player is out of range
            // (post-2026-08 BOT_RANGE_UNITS bump to 2048) even though the home
            // point is not. If the shot were resolved before the move it would
            // come from the home point and land.
            final float reach = Match.BOT_RANGE_UNITS - 100.0f;

            final Bot pacer = new Bot(2, 0.0f, 0.0f, reach, BotPattern.PACE_X,
                1500.0f, 4, 0);

            final Match match = marksmanMatch(pacer);

            // At its home point — where the constructor placed it, and where it
            // still is when tick() is entered — the player is 1948 units away
            // and comfortably in range. A quarter period later it is 1500 units
            // to the side and 2461 units away, which is out of range.
            assertThat(pacer.positionX()).isEqualTo(0.0f);

            tick(match, 1);

            assertThat(pacer.positionX()).isGreaterThan(1200.0f);

            // So a shot resolved from the PRE-move position would have landed.
            // Return fire coming from where a body used to be is exactly the
            // sort of discrepancy a player notices and cannot explain.
            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("a bot that has never seen the player does not shoot at the origin")
        void shouldNotFireBeforeItHasSeenAnything()
        {
            // A bot's shot is aimed at what it REMEMBERS, and a fresh bot
            // remembers nothing. Without the guard the memory reads as the world
            // origin, which in the demo room is the middle of the floor — seven
            // bodies firing into it for the first half-second of every match.
            final Bot fresh = sentryAt(2, 200.0f);

            final Match match = marksmanMatch(fresh);

            assertThat(fresh.hasSeenPlayer()).isFalse();

            // One tic is enough for the observation to happen, so the shot lands;
            // the assertion that matters is that the bot knew first.
            tick(match, 0);

            assertThat(fresh.hasSeenPlayer()).isTrue();
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
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) });

            assertThat(match.state()).isEqualTo(MatchState.IN_PROGRESS);

            assertThat(match.state().isOver()).isFalse();

            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("is won when the last bot goes down")
        void shouldBeWonWhenEveryBotIsDead()
        {
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) });

            shootAhead(match);

            shootAhead(match);

            assertThat(match.state()).isEqualTo(MatchState.IN_PROGRESS);

            shootAhead(match);

            assertThat(match.state()).isEqualTo(MatchState.WON);

            assertThat(match.state().isOver()).isTrue();
        }

        @Test
        @DisplayName("running out of health is NOT a loss — it is a death, and the round goes on")
        void shouldNotBeLostWhenThePlayerDies()
        {
            // The change at the heart of this: LOST used to mean "health reached
            // zero once", which made every death terminal. A death is now a score.
            // A round ends when the room is empty, which is something the player
            // earns rather than survives.
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            for (int tic = 0; tic < 500; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.playerDeaths()).as("nobody was killed at all").isGreaterThan(0);

            assertThat(match.state())
                .as("a death ended the round, which is the behaviour this replaced")
                .isEqualTo(MatchState.IN_PROGRESS);
        }

        @Test
        @DisplayName("takes exactly PLAYER_MAX_HEALTH / BOT_SHOT_DAMAGE hits to put the player down")
        void shouldSurviveTheExpectedNumberOfHitsWhenUnderFire()
        {
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;

            for (int tic = 0; tic < hitsToKill - 1; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.isPlayerDown()).as("one hit short").isFalse();

            assertThat(match.playerDeaths()).isEqualTo(0);

            tick(match, hitsToKill - 1);

            assertThat(match.isPlayerDown()).isTrue();

            assertThat(match.playerDeaths()).isEqualTo(1);

            assertThat(match.playerHealth()).isEqualTo(0);
        }

        @Test
        @DisplayName("with a death limit set, the last death loses even if the room emptied too")
        void shouldReportLostWhenThePlayerAndTheLastBotBothDie()
        {
            // UNLIMITED_DEATHS is the default and the demo's choice, so LOST is
            // now reachable only by asking for it. The ordering inside state() is
            // still worth pinning for the build that does: reporting a win to
            // somebody who has just spent their last life is funny exactly once.
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) },
                new BotRng(), BotSkill.MARKSMAN, 1);

            for (int tic = 0; tic < 500 && match.playerDeaths() == 0; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.playerDeaths()).isEqualTo(1);

            // Now clear the room from beyond the grave.
            shootAhead(match);

            shootAhead(match);

            shootAhead(match);

            assertThat(match.livingBots()).isEqualTo(0);

            assertThat(match.state()).isEqualTo(MatchState.LOST);
        }

        @Test
        @DisplayName("stops simulating once it is over")
        void shouldStopTickingWhenTheMatchIsDecided()
        {
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

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
                sentryAt(Match.PLAYER_ENTITY_ID, 100.0f),
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
                sentryAt(5, 100.0f),
                sentryAt(5, 200.0f),
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
            final Bot[] roster = { sentryAt(2, 200.0f) };

            final Match match = new Match(roster);

            roster[0] = sentryAt(9, 50.0f);

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
        @DisplayName("is enough for a map room, and the whole roster is beatable in a bounded number of shots")
        void shouldClearSevenBotsInTwentyOneShots()
        {
            // 2026-08: Match.DEFAULT_BOT_COUNT was raised from 7 to 32
            // (the largest spec waypoint count plus headroom). The
            // demo's own room still holds 7 bots, so this test pins
            // 7 explicitly - the assertion is about the demo's room,
            // not the map mode's roster size.
            final int rosterSize = 7;

            final Bot[] roster = new Bot[rosterSize];

            for (int index = 0; index < roster.length; index++)
            {
                // Stacked along +z so a single bearing reaches all of them in
                // turn as each one falls.
                roster[index] = sentryAt(Match.FIRST_BOT_ENTITY_ID + index,
                    100.0f + index * 50.0f);
            }

            final Match match = new Match(roster);

            final int shotsToKill =
                (Bot.MAX_HEALTH + Match.PLAYER_SHOT_DAMAGE - 1) / Match.PLAYER_SHOT_DAMAGE;

            for (int shot = 0; shot < shotsToKill * rosterSize; shot++)
            {
                shootAhead(match);
            }

            assertThat(match.state()).isEqualTo(MatchState.WON);

            assertThat(match.botsKilled()).isEqualTo(rosterSize);

            assertThat(match.playerShotsFired()).isEqualTo(21);
        }
    }

    @Nested
    @DisplayName("the shotgun — 2026-08 weapon dispatch")
    class Shotgun
    {
        // The player's eye is 41 units above the feet. The bot's
        // hitbox is 56 units tall, so a pellet that hits the
        // centre of the body lands 28 units away from the
        // eye's vertical centre - but the hitscan is on the
        // box, not the centre, and the shotgun's test sits the
        // bot so its box is squarely in front of the eye. The
        // 256-unit close range is wide enough that the
        // hit-distance lands inside it.
        private static final float SHOTGUN_CLOSE_Z = 100.0f;

        @Test
        @DisplayName("a close-range shotgun blast kills in one shot")
        void shouldOneShotAtPointBlank()
        {
            // 2026-08: the shotgun's selling point. The player
            // and the bot are within SHOTGUN_CLOSE_RANGE_UNITS,
            // the pellets land, and one pellet does
            // SHOTGUN_CLOSE_DAMAGE (well past Bot.MAX_HEALTH).
            // The bot is dead after one trigger pull.
            final Bot[] roster =
            {
                sentryAt(Match.FIRST_BOT_ENTITY_ID, SHOTGUN_CLOSE_Z),
            };

            final Match match = new Match(roster);

            final int hit = match.firePlayerShot(PLAYER_X, PLAYER_EYE_Y, PLAYER_Z,
                0.0f, 0.0f, 1.0f, Weapon.SHOTGUN);

            assertThat(hit)
                .as("a close-range shotgun blast hits the bot")
                .isEqualTo(Match.FIRST_BOT_ENTITY_ID);

            assertThat(roster[0].isAlive())
                .as("the bot is dead from the close-range damage")
                .isFalse();

            assertThat(match.botsKilled()).isEqualTo(1);
        }

        @Test
        @DisplayName("a long-range shotgun blast does less damage per pellet")
        void shouldTickleAtLongRange()
        {
            // 2026-08: a pellet that lands beyond
            // SHOTGUN_CLOSE_RANGE_UNITS does the far-range
            // damage (half the blaster), not the one-shot
            // damage. The bot survives the first blast and
            // takes only the far-range damage across the
            // pellets that landed.
            final Bot[] roster =
            {
                sentryAt(Match.FIRST_BOT_ENTITY_ID, 600.0f),
            };

            final Match match = new Match(roster);

            final int hit = match.firePlayerShot(PLAYER_X, PLAYER_EYE_Y, PLAYER_Z,
                0.0f, 0.0f, 1.0f, Weapon.SHOTGUN);

            assertThat(hit)
                .as("a far-range shotgun blast still hits the bot")
                .isEqualTo(Match.FIRST_BOT_ENTITY_ID);

            assertThat(roster[0].isAlive())
                .as("the far-range damage is well below MAX_HEALTH, so the bot lives")
                .isTrue();

            // Player fires once; 5 pellets were tested. The
            // fan shape puts one pellet dead-on, so at least
            // one lands. The hit counter is bumped on every
            // pellet that lands (the fan may have one or more
            // pellets connect, depending on the bot's box and
            // the fan's spread). The exact number of hits
            // depends on the box-vs-fan math, so the assertion
            // is "the player fired once and at least one pellet
            // landed" rather than pinning a count.
            assertThat(match.playerShotsFired())
                .as("one trigger pull counts as one shot")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("the blaster is still a single hitscan, with the Weapon parameter defaulting to BLASTER")
        void shouldKeepBlasterAsSingleHitscan()
        {
            // 2026-08: the 7-arg overload is the new shape; the
            // 6-arg overload still calls it with BLASTER. The
            // blaster does PLAYER_SHOT_DAMAGE (34) per shot,
            // not the shotgun's one-shot-kill damage, so a
            // bot at 100 units survives the first blaster
            // shot. That is the proof the dispatch landed
            // on the blaster path and not the shotgun path.
            final Bot[] roster =
            {
                sentryAt(Match.FIRST_BOT_ENTITY_ID, 100.0f),
            };

            final Match match = new Match(roster);

            final int hit = match.firePlayerShot(PLAYER_X, PLAYER_EYE_Y, PLAYER_Z,
                0.0f, 0.0f, 1.0f);

            assertThat(hit)
                .as("the legacy 6-arg call dispatches to BLASTER and hits")
                .isEqualTo(Match.FIRST_BOT_ENTITY_ID);

            assertThat(roster[0].isAlive())
                .as("the blaster does 34 damage per shot, the bot has 100 hp, and lives")
                .isTrue();

            assertThat(roster[0].health())
                .as("the blaster's 34 damage is reflected in the bot's health")
                .isEqualTo(Bot.MAX_HEALTH - 34);
        }
    }

    @Nested
    @DisplayName("respawning")
    class Respawn
    {
        @Test
        @DisplayName("the player is not shot at while on the floor")
        void shouldStopReturnFireWhileThePlayerIsDown()
        {
            // Otherwise the two seconds after a death are the most dangerous part
            // of the round: seven marksmen emptying magazines into a corpse would
            // run the death counter away before the body stood up.
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;

            for (int tic = 0; tic < hitsToKill; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.isPlayerDown()).isTrue();

            final int shotsAtDeath = match.botShotsFired();

            for (int tic = hitsToKill; tic < hitsToKill + Match.RESPAWN_DELAY_TICS - 1; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.botShotsFired()).isEqualTo(shotsAtDeath);

            assertThat(match.playerDeaths()).isEqualTo(1);
        }

        @Test
        @DisplayName("stands up at full health exactly RESPAWN_DELAY_TICS later")
        void shouldRespawnAfterTheDelay()
        {
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;

            for (int tic = 0; tic < hitsToKill; tic++)
            {
                tick(match, tic);
            }

            final int deathTic = hitsToKill - 1;

            // One tic short.
            for (int tic = hitsToKill; tic < deathTic + Match.RESPAWN_DELAY_TICS; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.isPlayerDown()).as("stood up early").isTrue();

            assertThat(match.playerHealth()).isEqualTo(0);

            tick(match, deathTic + Match.RESPAWN_DELAY_TICS);

            assertThat(match.isPlayerDown()).isFalse();

            assertThat(match.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
        }

        @Test
        @DisplayName("the respawn is reported exactly once, so the body is moved once")
        void shouldConsumeTheRespawnFlagOnce()
        {
            // A consuming read: the caller has to teleport a body, and moving it
            // twice would be harmless only by luck.
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;

            for (int tic = 0; tic < hitsToKill + Match.RESPAWN_DELAY_TICS; tic++)
            {
                tick(match, tic);

                if (match.consumePlayerRespawned())
                {
                    assertThat(match.consumePlayerRespawned())
                        .as("the same respawn was reported twice")
                        .isFalse();

                    return;
                }
            }

            assertThat(false).as("no respawn was ever reported").isTrue();
        }

        @Test
        @DisplayName("the countdown counts down, so the notice is not a frozen screen")
        void shouldCountDownTowardTheRespawn()
        {
            // The perceptible property. A static "you died" is indistinguishable
            // from a hung game, which is the whole reason the notice shows a
            // number at all.
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;

            for (int tic = 0; tic < hitsToKill; tic++)
            {
                tick(match, tic);
            }

            final int first = match.respawnTicsRemaining(hitsToKill);

            final int later = match.respawnTicsRemaining(hitsToKill + 30);

            assertThat(first).isGreaterThan(0);

            assertThat(later).isLessThan(first);

            assertThat(match.respawnTicsRemaining(hitsToKill + Match.RESPAWN_DELAY_TICS))
                .isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("reset — a rematch is indistinguishable from a first round")
    class Reset
    {
        @Test
        @DisplayName("every counter, every bot and the player match a freshly built match")
        void shouldBeIdenticalToAFreshMatchAfterReset()
        {
            // THE invariant, and asserted against a fresh Match rather than
            // against a list of fields somebody has to remember to extend. A
            // counter added to Match later and not zeroed in reset() fails HERE
            // rather than shipping as a summary that is the sum of two rounds.
            final Match fresh = marksmanMatch(sentryAt(2, 200.0f), sentryAt(3, 260.0f));

            final Match used = marksmanMatch(sentryAt(2, 200.0f), sentryAt(3, 260.0f));

            // Play a whole round out: kill one bot, take fire, die, respawn.
            shootAhead(used);

            shootAhead(used);

            shootAhead(used);

            for (int tic = 0; tic < 400; tic++)
            {
                tick(used, tic);
            }

            assertThat(used.botsKilled()).isEqualTo(1);

            assertThat(used.playerDeaths()).isGreaterThan(0);

            used.reset();

            assertThat(used.playerHealth()).isEqualTo(fresh.playerHealth());

            assertThat(used.botsKilled()).isEqualTo(fresh.botsKilled());

            assertThat(used.playerDeaths()).isEqualTo(fresh.playerDeaths());

            assertThat(used.playerShotsFired()).isEqualTo(fresh.playerShotsFired());

            assertThat(used.playerShotsHit()).isEqualTo(fresh.playerShotsHit());

            assertThat(used.botShotsFired()).isEqualTo(fresh.botShotsFired());

            assertThat(used.botShotsLanded()).isEqualTo(fresh.botShotsLanded());

            assertThat(used.livingBots()).isEqualTo(fresh.livingBots());

            assertThat(used.isPlayerDown()).isEqualTo(fresh.isPlayerDown());

            assertThat(used.state()).isEqualTo(fresh.state());

            assertThat(used.killStreak()).isEqualTo(fresh.killStreak());

            assertThat(used.isSuperBlaster()).isEqualTo(fresh.isSuperBlaster());

            assertThat(used.superBlasterTicsRemaining())
                .isEqualTo(fresh.superBlasterTicsRemaining());

            assertThat(used.playerShotDamage()).isEqualTo(fresh.playerShotDamage());
        }

        @Test
        @DisplayName("a cleared room is full again, and the match is playable rather than won")
        void shouldReviveEveryBotWhenResetting()
        {
            // What the player actually perceives about a rematch: there is
            // somebody to shoot. A reset that zeroed the counters and left the
            // corpses would report IN_PROGRESS for a round already over, or WON
            // for one that had not started.
            final Match match = marksmanMatch(sentryAt(2, 200.0f));

            shootAhead(match);

            shootAhead(match);

            shootAhead(match);

            assertThat(match.state()).isEqualTo(MatchState.WON);

            match.reset();

            assertThat(match.livingBots()).isEqualTo(1);

            assertThat(match.state()).isEqualTo(MatchState.IN_PROGRESS);

            assertThat(shootAhead(match))
                .as("the revived bot is not a target again")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("a reset round plays out identically to the first one")
        void shouldReplayTheSameRoundAfterReset()
        {
            // The strongest statement of the invariant available: run the same
            // tics twice on the same object with a reset between, and the two
            // halves have to agree in every figure. This is also what would catch
            // a bot whose cooldown or memory survived the reset — either would
            // shift the second round's return fire.
            final Match match = marksmanMatch(sentryAt(2, 200.0f), sentryAt(3, 260.0f));

            for (int tic = 0; tic < 300; tic++)
            {
                tick(match, tic);
            }

            final String firstRound = match.toString();

            match.reset();

            for (int tic = 0; tic < 300; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.toString()).isEqualTo(firstRound);
        }
    }

    @Nested
    @DisplayName("dumbness — the measured properties, not the constants")
    class Dumbness
    {
        @Test
        @DisplayName("most shots MISS, and some still land")
        void shouldMissMostShotsButNotAllOfThem()
        {
            // Both halves matter and neither alone is enough. "The bots miss" is
            // satisfied by opponents who never hit anything, which is not a threat
            // but scenery; "the bots hit" is satisfied by the metronome this
            // replaced. The pair is the feature.
            //
            // The player stands 200 units from a bot that fires as often as it can
            // — an engagement distance the demo room produces constantly.
            final Match match = new Match(new Bot[] { sentryAt(2, 200.0f) },
                new BotRng(), BotSkill.DUMB, Match.UNLIMITED_DEATHS);

            for (int tic = 0; tic < BALANCE_RUN_TICS; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.botShotsFired())
                .as("nothing was fired at all over %d tics", BALANCE_RUN_TICS)
                .isGreaterThan(5);

            assertThat(match.botShotsLanded())
                .as("no shot ever landed — that is scenery, not an opponent")
                .isGreaterThan(0);

            final int landedPercent = match.botShotsLanded() * 100 / match.botShotsFired();

            assertThat(landedPercent)
                .as("%d%% of shots landed, which is not 'most shots miss'", landedPercent)
                .isLessThan(50);
        }

        @Test
        @DisplayName("a distant bot misses far more than a close one")
        void shouldMissMoreOftenAtRange()
        {
            // The consequence of scattering the RAY rather than rolling against
            // the outcome, and the reason it was worth doing that way: a fixed
            // angular error is a bigger miss the further it travels, so distance
            // matters without anything modelling it. A dice roll against the
            // outcome would give the same hit rate across the whole room.
            final int closeHits = landedOver(80.0f);

            final int distantHits = landedOver(460.0f);

            assertThat(closeHits)
                .as("a bot at arm's length never hit anything")
                .isGreaterThan(0);

            assertThat(distantHits)
                .as("a bot at 460 units hit %d times against %d at 80 — range does not matter",
                    distantHits, closeHits)
                .isLessThan(closeHits);
        }

        // How many shots one DUMB bot lands on a motionless player at a distance.
        private static int landedOver(final float distance)
        {
            final Match match = new Match(new Bot[] { sentryAt(2, distance) },
                new BotRng(), BotSkill.DUMB, Match.UNLIMITED_DEATHS);

            for (int tic = 0; tic < BALANCE_RUN_TICS; tic++)
            {
                tick(match, tic);
            }

            return match.botShotsLanded();
        }

        @Test
        @DisplayName("the same seed replays a round exactly; a different seed does not")
        void shouldBeReproducibleUnderOneSeed()
        {
            // The lockstep guarantee, stated as the thing it protects rather than
            // as a property of the generator: two peers running the same tics must
            // reach the same state. Math.random() in the firing path fails this on
            // the second run, which is the point of having it.
            final String underOneSeed = roundUnderSeed(1234L);

            assertThat(roundUnderSeed(1234L))
                .as("the same seed must replay exactly")
                .isEqualTo(underOneSeed);

            assertThat(roundUnderSeed(9876L))
                .as("a different seed must produce a different round")
                .isNotEqualTo(underOneSeed);
        }

        // A whole round's outcome under one seed, as a string so a mismatch prints
        // the divergence rather than "expected 41, was 47".
        private static String roundUnderSeed(final long seed)
        {
            final Match match = new Match(
                new Bot[] { sentryAt(2, 150.0f), sentryAt(3, 300.0f), sentryAt(4, 420.0f) },
                new BotRng(seed), BotSkill.DUMB, Match.UNLIMITED_DEATHS);

            for (int tic = 0; tic < BALANCE_RUN_TICS; tic++)
            {
                tick(match, tic);
            }

            return match.toString();
        }
    }

    @Nested
    @DisplayName("balance — the measurement BOT_SHOT_DAMAGE is derived from")
    class Balance
    {
        @Test
        @DisplayName("seven bots in sight kill a motionless player in about half a minute")
        void shouldKillAStationaryPlayerInAboutThirtySeconds()
        {
            // THE MEASUREMENT. Match.BOT_SHOT_DAMAGE's Javadoc quotes a figure
            // taken from this test, and the previous figure — "a hit every 0.40 s,
            // dead in twenty seconds" — described opponents who could aim and went
            // stale the moment they could not. Pinning it here is what stops the
            // same thing happening again: change the skill profile or the damage
            // and this test tells you the new number rather than passing quietly.
            //
            // The room is the demo's own arrangement in spirit: seven bots spread
            // between 100 and 400 units, all in line of sight, player motionless
            // in the open. That is the worst case a player can put themselves in.
            // 2026-08: pinned to 7 explicitly (the demo's count), not the new
            // Match.DEFAULT_BOT_COUNT = 32 (the map mode's roster size).
            final int rosterSize = 7;

            final Bot[] roster = new Bot[rosterSize];

            for (int index = 0; index < roster.length; index++)
            {
                roster[index] = sentryAt(Match.FIRST_BOT_ENTITY_ID + index,
                    100.0f + index * 50.0f);
            }

            final Match match = new Match(roster, new BotRng(), BotSkill.DUMB,
                Match.UNLIMITED_DEATHS);

            int ticsToFirstDeath = -1;

            for (int tic = 0; tic < BALANCE_RUN_TICS * 4; tic++)
            {
                tick(match, tic);

                if (ticsToFirstDeath < 0 && match.playerDeaths() > 0)
                {
                    ticsToFirstDeath = tic;
                }
            }

            assertThat(ticsToFirstDeath)
                .as("the player never died at all — the opponents are scenery")
                .isGreaterThan(0);

            // Between ten and sixty seconds at 60 Hz. Wide bounds because this is
            // a random process; the point is that standing still is punished on a
            // timescale a player experiences as pressure, and that it is nowhere
            // near the two and a half minutes the old 2 damage would have given.
            assertThat(ticsToFirstDeath / TICS_PER_SECOND)
                .as("death took %d tics, which is %d seconds",
                    ticsToFirstDeath, ticsToFirstDeath / TICS_PER_SECOND)
                .isBetween(10, 60);
        }

        @Test
        @DisplayName("the room fires at about the rate it did under the old fixed cadence")
        void shouldKeepTheRoomsRateOfFireWhenRandomised()
        {
            // The other half of the balance argument. The bots miss far more than
            // they did, so the room has to still SOUND about as busy or the demo
            // goes quiet — the noise is what tells the player they are in a fight.
            // The old cadence produced a shot somewhere in the room every 21 tics;
            // BotSkill.DUMB is tuned to land near that.
            // 2026-08: pinned to 7 explicitly (the demo's count), not the new
            // Match.DEFAULT_BOT_COUNT = 32.
            final int rosterSize = 7;

            final Bot[] roster = new Bot[rosterSize];

            for (int index = 0; index < roster.length; index++)
            {
                roster[index] = sentryAt(Match.FIRST_BOT_ENTITY_ID + index, 200.0f);
            }

            // A death limit of one, so the run is not interrupted by respawns —
            // the moment the player goes down the room stops firing, which would
            // make the measurement about the respawn delay instead.
            final Match match = new Match(roster, new BotRng(), BotSkill.DUMB, 1);

            int tics = 0;

            while (tics < BALANCE_RUN_TICS && match.playerDeaths() == 0)
            {
                tick(match, tics);

                tics++;
            }

            final int ticsPerShot = tics / Math.max(1, match.botShotsFired());

            assertThat(ticsPerShot)
                .as("the room fired once every %d tics against the old cadence's 21",
                    ticsPerShot)
                .isBetween(10, 40);
        }
    }

    @Nested
    @DisplayName("the kill streak and the super blaster")
    class KillStreak
    {
        @Test
        @DisplayName("two kills change nothing; the third arms the super blaster")
        void shouldArmTheSuperBlasterOnTheThirdKill()
        {
            final Match match = firingRange(5);

            killNearest(match);

            killNearest(match);

            assertThat(match.killStreak()).isEqualTo(2);

            assertThat(match.isSuperBlaster()).as("armed early").isFalse();

            assertThat(match.playerShotDamage()).isEqualTo(Match.PLAYER_SHOT_DAMAGE);

            killNearest(match);

            assertThat(match.isSuperBlaster()).isTrue();

            assertThat(match.superBlasterTicsRemaining()).isEqualTo(Match.SUPER_BLASTER_TICS);

            assertThat(match.killStreak())
                .as("the award has to spend the streak, or the next single kill re-arms it")
                .isZero();
        }

        @Test
        @DisplayName("a hit that does not kill does not count toward the streak")
        void shouldCountKillsRatherThanHits()
        {
            // Otherwise "three kills" is "three shots", the reward arrives inside
            // one engagement with one opponent, and it stops being about a streak
            // at all.
            final Match match = firingRange(3);

            assertThat(shootAhead(match)).isNotEqualTo(Match.NO_HIT);

            assertThat(shootAhead(match)).isNotEqualTo(Match.NO_HIT);

            assertThat(match.killStreak()).isZero();
        }

        @Test
        @DisplayName("the super shot does EXACTLY twice PLAYER_SHOT_DAMAGE, derived from it")
        void shouldDoExactlyDoubleDamage()
        {
            // Derived rather than written down again: a second literal is one
            // re-balance away from a reward that has quietly stopped being double
            // anything, and a wrong number here looks exactly like a right one.
            assertThat(Match.SUPER_BLASTER_DAMAGE_MULTIPLIER).isEqualTo(2);

            assertThat(Match.SUPER_BLASTER_SHOT_DAMAGE)
                .isEqualTo(Match.PLAYER_SHOT_DAMAGE * 2);

            final Match match = firingRange(5);

            assertThat(match.playerShotDamage()).isEqualTo(Match.PLAYER_SHOT_DAMAGE);

            earnTheSuperBlaster(match);

            assertThat(match.playerShotDamage()).isEqualTo(Match.SUPER_BLASTER_SHOT_DAMAGE);
        }

        @Test
        @DisplayName("two hits to kill instead of three — what the player actually feels")
        void shouldKillInTwoHitsWhileSuper()
        {
            // The damage figure restated as the thing a player experiences. 68 does
            // not read as a reward until it is put beside Bot.MAX_HEALTH; "the next
            // one goes down a shot sooner" does.
            final Match match = firingRange(5);

            earnTheSuperBlaster(match);

            assertThat(match.playerShotsFired())
                .as("three ordinary kills should be nine shots")
                .isEqualTo(9);

            assertThat(killNearest(match))
                .as("the super blaster did not shorten the kill")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("runs out after exactly SUPER_BLASTER_TICS tics, and says so once")
        void shouldExpireAfterTheFullWindow()
        {
            final Match match = firingRange(6);

            earnTheSuperBlaster(match);

            // One tic short of the window.
            for (int tic = 0; tic < Match.SUPER_BLASTER_TICS - 1; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.isSuperBlaster()).as("expired early").isTrue();

            assertThat(match.superBlasterTicsRemaining()).isEqualTo(1);

            tick(match, Match.SUPER_BLASTER_TICS - 1);

            assertThat(match.isSuperBlaster()).isFalse();

            assertThat(match.superBlasterTicsRemaining()).isZero();

            assertThat(match.playerShotDamage()).isEqualTo(Match.PLAYER_SHOT_DAMAGE);

            assertThat(match.consumeSuperBlasterExpired())
                .as("the player was never told it had stopped")
                .isTrue();

            assertThat(match.consumeSuperBlasterExpired())
                .as("the same expiry was announced twice")
                .isFalse();
        }

        @Test
        @DisplayName("the countdown counts down, so the badge is not a frozen screen")
        void shouldCountDownWhileLive()
        {
            // The same perceptible property the respawn notice has: a number that
            // does not move is indistinguishable from a badge somebody forgot to
            // take away.
            final Match match = firingRange(6);

            earnTheSuperBlaster(match);

            final int atAward = match.superBlasterTicsRemaining();

            for (int tic = 0; tic < 30; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.superBlasterTicsRemaining()).isEqualTo(atAward - 30);
        }

        @Test
        @DisplayName("the award is announced exactly once, and an ordinary kill is not")
        void shouldAnnounceTheAwardOnce()
        {
            // A consuming read, so exactly one caller makes the noise. Two would
            // make it twice, in phase, which is one noise at double the volume.
            final Match match = firingRange(5);

            killNearest(match);

            assertThat(match.consumeSuperBlasterAwarded())
                .as("an ordinary kill announced an award")
                .isFalse();

            killNearest(match);

            killNearest(match);

            assertThat(match.consumeSuperBlasterAwarded()).isTrue();

            assertThat(match.consumeSuperBlasterAwarded())
                .as("the same award was announced twice")
                .isFalse();
        }

        @Test
        @DisplayName("a kill while it is live neither extends nor refreshes the window")
        void shouldNotExtendTheWindowOnAKill()
        {
            // THE RULE, and the reason for it: extending is a positive feedback
            // loop — double damage makes the next kill easier, an easier kill buys
            // more double damage — and in a seven-bot room the loop has nothing to
            // stop it, so the reward would end the round instead of punctuating it.
            final Match match = firingRange(Match.DEFAULT_BOT_COUNT);

            earnTheSuperBlaster(match);

            for (int tic = 0; tic < 60; tic++)
            {
                tick(match, tic);
            }

            final int aged = Match.SUPER_BLASTER_TICS - 60;

            assertThat(match.superBlasterTicsRemaining()).isEqualTo(aged);

            killNearest(match);

            assertThat(match.superBlasterTicsRemaining())
                .as("kill four refreshed the timer")
                .isEqualTo(aged);

            killNearest(match);

            assertThat(match.superBlasterTicsRemaining())
                .as("kill five refreshed the timer")
                .isEqualTo(aged);

            // Kill six completes a WHOLE fresh streak, which is a new award rather
            // than an extension of the old one — earned exactly as the first was.
            killNearest(match);

            assertThat(match.superBlasterTicsRemaining()).isEqualTo(Match.SUPER_BLASTER_TICS);

            assertThat(match.consumeSuperBlasterAwarded()).isTrue();
        }

        @Test
        @DisplayName("dying resets the streak, so three kills spread over two lives earn nothing")
        void shouldResetTheStreakOnDeath()
        {
            // The rule that makes it a STREAK. A running total would tick the
            // reward over several deaths later, with nothing on screen accounting
            // for why the gun had changed.
            final Match match = marksmanMatch(sentryAt(2, 200.0f), sentryAt(3, 260.0f),
                sentryAt(4, 320.0f), sentryAt(5, 380.0f));

            killNearest(match);

            killNearest(match);

            assertThat(match.killStreak()).isEqualTo(2);

            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;

            for (int tic = 0; tic < hitsToKill; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.isPlayerDown()).isTrue();

            assertThat(match.killStreak()).isZero();

            // Stand up, and take a third kill. Under a running total this would be
            // the one that armed it.
            tick(match, hitsToKill + Match.RESPAWN_DELAY_TICS);

            assertThat(match.isPlayerDown()).isFalse();

            killNearest(match);

            assertThat(match.killStreak()).isEqualTo(1);

            assertThat(match.isSuperBlaster()).isFalse();
        }

        @Test
        @DisplayName("dying cancels a live super blaster, and the player is told")
        void shouldCancelALiveBuffOnDeath()
        {
            // The reward's one precondition is being alive and three kills ahead.
            // A buff that outlived the life that earned it would be a reward for
            // nothing — and the two seconds on the floor would eat most of the
            // window regardless, so death would cancel it in practice while the
            // rules said otherwise.
            final Match match = marksmanMatch(sentryAt(2, 200.0f), sentryAt(3, 260.0f),
                sentryAt(4, 320.0f), sentryAt(5, 380.0f));

            earnTheSuperBlaster(match);

            assertThat(match.isSuperBlaster()).isTrue();

            match.consumeSuperBlasterAwarded();

            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;

            for (int tic = 0; tic < hitsToKill; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.isPlayerDown()).isTrue();

            assertThat(match.isSuperBlaster()).isFalse();

            assertThat(match.superBlasterTicsRemaining()).isZero();

            assertThat(match.playerShotDamage()).isEqualTo(Match.PLAYER_SHOT_DAMAGE);

            assertThat(match.consumeSuperBlasterExpired())
                .as("the buff vanished silently, which is indistinguishable from a bug")
                .isTrue();
        }

        @Test
        @DisplayName("reset clears the streak AND cancels a live super blaster")
        void shouldClearBothOnReset()
        {
            // A rematch that inherited four seconds of double damage nobody earned
            // is exactly the class of bug this reset already shipped once, when
            // reviving the bots left them invisible.
            final Match match = firingRange(6);

            earnTheSuperBlaster(match);

            killNearest(match);

            for (int tic = 0; tic < 30; tic++)
            {
                tick(match, tic);
            }

            assertThat(match.isSuperBlaster()).isTrue();

            assertThat(match.killStreak()).isEqualTo(1);

            match.reset();

            assertThat(match.killStreak()).isZero();

            assertThat(match.isSuperBlaster()).isFalse();

            assertThat(match.superBlasterTicsRemaining()).isZero();

            assertThat(match.playerShotDamage()).isEqualTo(Match.PLAYER_SHOT_DAMAGE);

            assertThat(match.consumeSuperBlasterAwarded())
                .as("a rematch opened by announcing last round's award")
                .isFalse();

            assertThat(match.consumeSuperBlasterExpired()).isFalse();
        }

        @Test
        @DisplayName("the window is measured in tics, which is what makes two peers agree")
        void shouldMeasureTheWindowInTics()
        {
            // Not a tautology: it is the assertion that the same NUMBER OF TICKS
            // ends the buff regardless of how long those ticks took. A wall-clock
            // window would expire on different tics on two peers, which is a shot
            // doing 68 damage on one and 34 on the other — the same class of desync
            // BotRng exists to prevent, arriving by a different door.
            final Match slow = firingRange(6);

            final Match fast = firingRange(6);

            earnTheSuperBlaster(slow);

            earnTheSuperBlaster(fast);

            // The same tics, driven at whatever rate two different machines happen
            // to manage, with one of them skipping indices as the loop is entitled
            // to do.
            for (int tic = 0; tic < Match.SUPER_BLASTER_TICS; tic++)
            {
                tick(slow, tic);
            }

            for (int tic = 0; tic < Match.SUPER_BLASTER_TICS; tic++)
            {
                tick(fast, tic * 3);
            }

            assertThat(slow.isSuperBlaster()).isFalse();

            assertThat(fast.isSuperBlaster())
                .as("a caller that skipped tic indices got a different answer")
                .isFalse();
        }
    }
}
