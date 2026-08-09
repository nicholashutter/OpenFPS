/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Bot} and {@link BotPattern}.
 *
 * <p>The properties under test are the ones the design claims and that a
 * screenshot could never show: that a bot's position is a <b>pure function of
 * the tic index</b> rather than an accumulation, that its route closes exactly
 * on its period, and that the simulation path holds no {@code java.lang.Math}
 * call that could put two peers' bots in different places.</p>
 */
@DisplayName("Bot")
class BotTest
{
    /** Tolerance for world-space coordinates. Amplitudes here are 100+ units. */
    private static final float EPSILON = 1.0e-3f;

    /** A route period that divides evenly into the tic counts used below. */
    private static final int PERIOD = 120;

    /** A patrol reach in world units. */
    private static final float AMPLITUDE = 100.0f;

    /**
     * Tics to run a statistical assertion over.
     *
     * <p>Long enough that a random firing rate settles — about forty shots at
     * {@link BotSkill#DUMB}'s mean interval — and short enough that a nest of
     * these runs in milliseconds. Every bound asserted against it is loose on
     * purpose: this is a random process, and an assertion tight enough to pin the
     * exact count would be a test that fails for being correct.</p>
     */
    private static final int LONG_RUN_TICS = 6000;

    private static Bot bot(final BotPattern pattern)
    {
        return new Bot(2, 0.0f, 0.0f, 0.0f, pattern, AMPLITUDE, PERIOD, 0);
    }

    @Nested
    @DisplayName("movement patterns")
    class Patterns
    {
        @Test
        @DisplayName("a sentry never leaves its home point")
        void shouldStayPutWhenThePatternIsSentry()
        {
            final Bot sentry = new Bot(2, 25.0f, 0.0f, -60.0f, BotPattern.SENTRY,
                AMPLITUDE, PERIOD, 0);

            for (int tic = 0; tic < PERIOD * 3; tic++)
            {
                sentry.moveTo(tic);

                assertThat(sentry.positionX()).isEqualTo(25.0f);

                assertThat(sentry.positionZ()).isEqualTo(-60.0f);
            }

            assertThat(BotPattern.SENTRY.moves()).isFalse();
        }

        @Test
        @DisplayName("a pace on one axis leaves the other alone")
        void shouldMoveOnOneAxisOnlyWhenPacing()
        {
            final Bot alongX = bot(BotPattern.PACE_X);

            final Bot alongZ = bot(BotPattern.PACE_Z);

            boolean sawXMove = false;

            boolean sawZMove = false;

            for (int tic = 0; tic < PERIOD; tic++)
            {
                alongX.moveTo(tic);

                alongZ.moveTo(tic);

                assertThat(alongX.positionZ()).as("PACE_X must not move on z").isEqualTo(0.0f);

                assertThat(alongZ.positionX()).as("PACE_Z must not move on x").isEqualTo(0.0f);

                sawXMove = sawXMove || alongX.positionX() != 0.0f;

                sawZMove = sawZMove || alongZ.positionZ() != 0.0f;
            }

            // Guards the vacuous pass: "never moves on z" is trivially true for
            // a bot that never moves at all.
            assertThat(sawXMove).isTrue();

            assertThat(sawZMove).isTrue();
        }

        @Test
        @DisplayName("an orbit traces a circle, not a diagonal line")
        void shouldStayAtConstantRadiusWhenOrbiting()
        {
            final Bot orbiter = bot(BotPattern.ORBIT);

            for (int tic = 0; tic < PERIOD; tic++)
            {
                orbiter.moveTo(tic);

                final float radius = (float) StrictMath.sqrt(
                    orbiter.positionX() * orbiter.positionX()
                        + orbiter.positionZ() * orbiter.positionZ());

                // sin on BOTH axes would give |x| == |z| everywhere — a line
                // through the home point at 45 degrees, and a radius that
                // oscillates between 0 and amplitude*sqrt(2). The cosine on z is
                // what makes this a circle, and this is the assertion that
                // notices if it is ever "simplified" away.
                assertThat(radius).isCloseTo(AMPLITUDE, within(EPSILON));
            }
        }

        @Test
        @DisplayName("a pace reaches its full amplitude in both directions")
        void shouldReachBothExtremesWhenPacing()
        {
            final Bot alongX = bot(BotPattern.PACE_X);

            float lowest = Float.MAX_VALUE;

            float highest = -Float.MAX_VALUE;

            for (int tic = 0; tic < PERIOD; tic++)
            {
                alongX.moveTo(tic);

                lowest = StrictMath.min(lowest, alongX.positionX());

                highest = StrictMath.max(highest, alongX.positionX());
            }

            assertThat(highest).isCloseTo(AMPLITUDE, within(1.0f));

            assertThat(lowest).isCloseTo(-AMPLITUDE, within(1.0f));
        }
    }

    @Nested
    @DisplayName("position as a function of the tic")
    class ClosedForm
    {
        @Test
        @DisplayName("placing the same tic twice gives the same place")
        void shouldBeIdempotentWhenPlacedTwiceAtTheSameTic()
        {
            final Bot walker = bot(BotPattern.ORBIT);

            walker.moveTo(37);

            final float x = walker.positionX();

            final float z = walker.positionZ();

            walker.moveTo(37);

            assertThat(walker.positionX()).isEqualTo(x);

            assertThat(walker.positionZ()).isEqualTo(z);
        }

        @Test
        @DisplayName("the route closes exactly on its period, however long it has run")
        void shouldReturnToTheSamePlaceOneFullPeriodLater()
        {
            final Bot walker = bot(BotPattern.ORBIT);

            walker.moveTo(11);

            final int early = bits(walker);

            // Ten thousand tics later — nearly three minutes at 60 Hz. An
            // INTEGRATED patrol would have accumulated float error by now and
            // drifted off its route; a closed-form one is the same expression
            // and lands on the same bits.
            walker.moveTo(11 + PERIOD * 10_000);

            assertThat(bits(walker)).isEqualTo(early);
        }

        @Test
        @DisplayName("skipping tics loses nothing — position never depends on history")
        void shouldNotDependOnHistoryWhenTicsAreSkipped()
        {
            final Bot stepped = bot(BotPattern.ORBIT);

            final Bot jumped = bot(BotPattern.ORBIT);

            for (int tic = 0; tic <= 200; tic++)
            {
                stepped.moveTo(tic);
            }

            jumped.moveTo(200);

            // The property that lets a peer join a match late, or drop a frame,
            // and still agree with everyone else about where the bots are.
            assertThat(bits(jumped)).isEqualTo(bits(stepped));
        }

        @Test
        @DisplayName("a negative tic index still produces a phase in range")
        void shouldWrapPhaseIntoRangeWhenTheTicIndexIsNegative()
        {
            final Bot walker = bot(BotPattern.ORBIT);

            // Plain % keeps the sign of the dividend, which would give a
            // negative phase here and make the cadence misfire either side of
            // zero.
            assertThat(walker.phaseAt(-1))
                .isGreaterThanOrEqualTo(0.0f)
                .isLessThan((float) (2.0 * StrictMath.PI));

            assertThat(walker.phaseAt(-PERIOD)).isEqualTo(walker.phaseAt(0));
        }

        @Test
        @DisplayName("phase offsets put two bots at different points on the same route")
        void shouldStaggerTwoBotsWhenTheirPhasesDiffer()
        {
            final Bot leading = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.ORBIT,
                AMPLITUDE, PERIOD, 0);

            final Bot trailing = new Bot(3, 0.0f, 0.0f, 0.0f, BotPattern.ORBIT,
                AMPLITUDE, PERIOD, PERIOD / 2);

            leading.moveTo(0);

            trailing.moveTo(0);

            // Half a period apart on a circle is the far side of it.
            assertThat(trailing.positionX()).isCloseTo(-leading.positionX(), within(EPSILON));

            assertThat(trailing.positionZ()).isCloseTo(-leading.positionZ(), within(EPSILON));
        }

        @Test
        @DisplayName("is placed on its route by the constructor, not one tic later")
        void shouldStandOnItsRouteWhenConstructed()
        {
            final Bot orbiter = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.ORBIT,
                AMPLITUDE, PERIOD, PERIOD / 4);

            // A quarter turn in, so a bot left at its home point by the
            // constructor would be at the origin instead and would visibly jump
            // on the first tic.
            assertThat(orbiter.positionX()).isCloseTo(AMPLITUDE, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("facing")
    class Facing
    {
        @Test
        @DisplayName("yaw 0 looks along world +z, matching PlayerController")
        void shouldFaceZeroYawWhenTheTargetIsOnPositiveZ()
        {
            final Bot sentry = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0);

            sentry.faceToward(0.0f, 100.0f);

            assertThat(sentry.yawRadians()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("yaw increases from +z toward +x, not the other way")
        void shouldFaceAQuarterTurnWhenTheTargetIsOnPositiveX()
        {
            final Bot sentry = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0);

            sentry.faceToward(100.0f, 0.0f);

            // atan2(dx, dz), not atan2(dz, dx). The other operand order mirrors
            // every bot in the room, which is very hard to see and impossible to
            // unsee.
            assertThat(sentry.yawRadians())
                .isCloseTo((float) (StrictMath.PI * 0.5), within(EPSILON));
        }

        @Test
        @DisplayName("keeps its heading when the target is exactly underfoot")
        void shouldKeepItsHeadingWhenTheTargetIsAtItsOwnPosition()
        {
            final Bot sentry = new Bot(2, 10.0f, 0.0f, 20.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0);

            sentry.faceToward(10.0f, 120.0f);

            final float before = sentry.yawRadians();

            sentry.faceToward(10.0f, 20.0f);

            assertThat(sentry.yawRadians()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("damage and death")
    class Dying
    {
        @Test
        @DisplayName("reports the kill exactly once, however many more shots land")
        void shouldReportTheKillOnlyOnceWhenShotRepeatedly()
        {
            final Bot victim = bot(BotPattern.SENTRY);

            assertThat(victim.damage(Bot.MAX_HEALTH - 1)).isFalse();

            assertThat(victim.damage(1)).as("the shot that kills").isTrue();

            assertThat(victim.damage(999)).as("and never again").isFalse();

            assertThat(victim.damage(1)).isFalse();
        }

        @Test
        @DisplayName("health floors at zero rather than going negative")
        void shouldFloorHealthAtZeroWhenOverkilled()
        {
            final Bot victim = bot(BotPattern.SENTRY);

            victim.damage(Bot.MAX_HEALTH * 10);

            assertThat(victim.health()).isEqualTo(0);

            assertThat(victim.isAlive()).isFalse();
        }

        @Test
        @DisplayName("a dead bot stops walking its route")
        void shouldStopMovingWhenDead()
        {
            final Bot victim = bot(BotPattern.ORBIT);

            victim.moveTo(10);

            final int whereItFell = bits(victim);

            victim.damage(Bot.MAX_HEALTH);

            victim.moveTo(60);

            // A body that carried on patrolling would be indistinguishable from
            // one that was never hit.
            assertThat(bits(victim)).isEqualTo(whereItFell);
        }

        @Test
        @DisplayName("a dead bot never asks to fire")
        void shouldNeverWantToFireWhenDead()
        {
            final Bot victim = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0);

            final BotRng rng = new BotRng();

            assertThat(victim.wantsToFire(0, rng, BotSkill.MARKSMAN))
                .as("a live marksman fires whenever it is ready")
                .isTrue();

            victim.damage(Bot.MAX_HEALTH);

            for (int tic = 0; tic < 500; tic++)
            {
                assertThat(victim.wantsToFire(tic, rng, BotSkill.MARKSMAN)).isFalse();
            }
        }

        @Test
        @DisplayName("rejects a non-positive damage amount")
        void shouldRejectNonPositiveDamageWhenApplyingIt()
        {
            assertThatThrownBy(() -> bot(BotPattern.SENTRY).damage(0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> bot(BotPattern.SENTRY).damage(-5))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("firing — random, but never faster than the cooldown")
    class Cadence
    {
        @Test
        @DisplayName("never fires twice inside its cooldown")
        void shouldRespectTheCooldownWhenFiringRandomly()
        {
            // The one hard guarantee left now that the cadence is a die roll. A
            // bot that could fire on two adjacent tics would be a machine gun the
            // moment the generator came up twice, and the player would have no
            // way to read it as anything but a bug.
            final Bot shooter = bot(BotPattern.SENTRY);

            final BotRng rng = new BotRng();

            int previous = Integer.MIN_VALUE;

            for (int tic = 0; tic < LONG_RUN_TICS; tic++)
            {
                if (!shooter.wantsToFire(tic, rng, BotSkill.DUMB))
                {
                    continue;
                }

                if (previous != Integer.MIN_VALUE)
                {
                    assertThat(tic - previous)
                        .as("tic %d came only %d tics after the last shot", tic, tic - previous)
                        .isGreaterThanOrEqualTo(BotSkill.DUMB.cooldownTics());
                }

                previous = tic;
            }

            assertThat(previous).as("nothing fired at all over %d tics", LONG_RUN_TICS)
                .isNotEqualTo(Integer.MIN_VALUE);
        }

        @Test
        @DisplayName("fires at roughly the rate the skill profile predicts")
        void shouldFireAtAboutTheMeanIntervalWhenAlive()
        {
            // The profile publishes meanShotIntervalTics() and the whole balance
            // argument for BOT_SHOT_DAMAGE rests on it, so the arithmetic and the
            // behaviour have to be pinned against each other. Loose bounds on
            // purpose: this is a random process, and a tight assertion here would
            // be a test that fails for being correct.
            final Bot shooter = bot(BotPattern.SENTRY);

            final BotRng rng = new BotRng();

            int shots = 0;

            for (int tic = 0; tic < LONG_RUN_TICS; tic++)
            {
                if (shooter.wantsToFire(tic, rng, BotSkill.DUMB))
                {
                    shots++;
                }
            }

            final int expected = LONG_RUN_TICS / BotSkill.DUMB.meanShotIntervalTics();

            assertThat(shots).isBetween(expected / 2, expected * 2);
        }

        @Test
        @DisplayName("seven bots do not volley: no tic has them all firing together")
        void shouldNotSynchroniseWhenSevenBotsShareOneGenerator()
        {
            // What replaced the old fixed offsets. The old cadence needed
            // arithmetic to stagger the room; a per-bot, per-tic draw decorrelates
            // it by construction, and this asserts the property a PLAYER
            // experiences — a broadside rather than pressure.
            final BotRng rng = new BotRng();

            final Bot[] room = new Bot[Match.DEFAULT_BOT_COUNT];

            for (int index = 0; index < room.length; index++)
            {
                room[index] = new Bot(Match.FIRST_BOT_ENTITY_ID + index, 0.0f, 0.0f, 0.0f,
                    BotPattern.SENTRY, 0.0f, PERIOD, 0);
            }

            int busiestTic = 0;

            for (int tic = 0; tic < LONG_RUN_TICS; tic++)
            {
                int firing = 0;

                for (final Bot shooter : room)
                {
                    if (shooter.wantsToFire(tic, rng, BotSkill.DUMB))
                    {
                        firing++;
                    }
                }

                busiestTic = Math.max(busiestTic, firing);
            }

            assertThat(busiestTic)
                .as("%d of seven bots fired on one tic — that is a volley", busiestTic)
                .isLessThan(room.length);
        }

        @Test
        @DisplayName("the same seed and tics give the same shots; a different seed does not")
        void shouldBeReproducibleUnderOneSeedWhenFiring()
        {
            // The lockstep guarantee, stated as the thing it protects: two peers
            // running the same tics must produce the same shots. Math.random()
            // here would fail this test on the second run, which is the point of
            // having it.
            final String underOneSeed = shotPattern(1234L);

            assertThat(shotPattern(1234L))
                .as("the same seed must replay exactly")
                .isEqualTo(underOneSeed);

            assertThat(shotPattern(9876L))
                .as("a different seed must produce a different match")
                .isNotEqualTo(underOneSeed);
        }

        // Which tics one bot fires on under a given seed, as a string so a
        // mismatch prints the divergence rather than "expected false, was true".
        private static String shotPattern(final long seed)
        {
            final Bot shooter = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0);

            final BotRng rng = new BotRng(seed);

            final StringBuilder fired = new StringBuilder();

            for (int tic = 0; tic < LONG_RUN_TICS; tic++)
            {
                if (shooter.wantsToFire(tic, rng, BotSkill.DUMB))
                {
                    fired.append(tic).append(' ');
                }
            }

            return fired.toString();
        }
    }

    @Nested
    @DisplayName("reaction — it shoots at where you were")
    class Reaction
    {
        @Test
        @DisplayName("does not know where the player is until it has been told once")
        void shouldNotHaveSeenThePlayerWhenFreshlyBuilt()
        {
            // Without this a fresh bot remembers the world origin, turns to stare
            // at it, and shoots at it — seven bodies aiming at the middle of the
            // floor for the first half-second of every match.
            final Bot fresh = bot(BotPattern.SENTRY);

            assertThat(fresh.hasSeenPlayer()).isFalse();

            assertThat(fresh.yawRadians()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("aims a whole body-width behind a running player")
        void shouldLagBehindARunningPlayer()
        {
            // The perceptible property, and asserted as a DISTANCE IN WORLD UNITS
            // rather than as an angle or a tic count. That choice is the test:
            // "the memory is stale" can be satisfied by a lag of one micron, which
            // no player could ever exploit. What matters is whether the gap is big
            // enough to run out of, so the assertion is against the player's own
            // hitbox — a bot aiming more than a body-width behind you is a bot you
            // can outrun, and that is what dumb has to mean on screen.
            //
            // A test comparing the remembered angle to the real one would have
            // passed with the memory wired straight through, which is exactly the
            // class of mistake this project has already shipped twice.
            final Bot watcher = bot(BotPattern.SENTRY);

            final float stepPerTic = PlayerController.MOVE_SPEED_UNITS_PER_SECOND / 60.0f;

            float worstLag = 0.0f;

            for (int tic = 0; tic < BotSkill.DUMB.reactionTics() * 4; tic++)
            {
                final float truthX = tic * stepPerTic;

                watcher.observePlayer(tic, truthX, 0.0f, BotSkill.DUMB);

                worstLag = StrictMath.max(worstLag,
                    StrictMath.abs(truthX - watcher.rememberedPlayerX()));
            }

            assertThat(worstLag)
                .as("the bot never aimed more than %f units behind a sprinting player",
                    worstLag)
                .isGreaterThan(Bot.RADIUS_UNITS * 2.0f);
        }

        @Test
        @DisplayName("refreshes eventually, so a bot is not permanently blind")
        void shouldRefreshOnceTheReactionIntervalElapses()
        {
            final Bot watcher = bot(BotPattern.SENTRY);

            for (int tic = 0; tic < BotSkill.DUMB.reactionTics() * 3; tic++)
            {
                watcher.observePlayer(tic, 200.0f, 0.0f, BotSkill.DUMB);
            }

            assertThat(watcher.rememberedPlayerX()).isEqualTo(200.0f);
        }

        @Test
        @DisplayName("faces the remembered position, not the real one")
        void shouldTurnTowardTheMemoryWhenFacing()
        {
            // Independent reference rather than angle-against-angle: the bot sits
            // at the origin, remembers the player at +x, and is then told about a
            // player at -x it is not allowed to notice yet. A body facing the NEW
            // position would have a positive-x forward vector; one facing the
            // memory has a negative one. Sines, not radians.
            final Bot watcher = bot(BotPattern.SENTRY);

            watcher.observePlayer(0, 300.0f, 0.0f, BotSkill.DUMB);

            watcher.faceRemembered();

            final float towardMemory = (float) StrictMath.sin(watcher.yawRadians());

            watcher.observePlayer(1, -300.0f, 0.0f, BotSkill.DUMB);

            watcher.faceRemembered();

            assertThat(towardMemory).as("yaw 0 must face +z, and +x must be a positive sine")
                .isGreaterThan(0.0f);

            assertThat((float) StrictMath.sin(watcher.yawRadians()))
                .as("the bot swung round to the player's real position immediately")
                .isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("a dead bot notices nothing, so a corpse does not swivel")
        void shouldStopObservingWhenDead()
        {
            final Bot victim = bot(BotPattern.SENTRY);

            victim.damage(Bot.MAX_HEALTH);

            victim.observePlayer(0, 300.0f, 0.0f, BotSkill.DUMB);

            assertThat(victim.hasSeenPlayer()).isFalse();
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset
    {
        @Test
        @DisplayName("a reset bot is indistinguishable from a freshly built one")
        void shouldBeIdenticalToAFreshBotAfterReset()
        {
            // The invariant, asserted against a fresh object rather than against a
            // list of fields somebody has to remember to extend. A field added to
            // Bot later and not restored in reset() fails HERE.
            final Bot fresh = bot(BotPattern.ORBIT);

            final Bot used = bot(BotPattern.ORBIT);

            final BotRng rng = new BotRng();

            used.observePlayer(0, 120.0f, 40.0f, BotSkill.DUMB);

            used.faceRemembered();

            used.moveTo(137);

            used.wantsToFire(137, rng, BotSkill.MARKSMAN);

            used.damage(Bot.MAX_HEALTH);

            assertThat(used.isAlive()).isFalse();

            used.reset();

            assertThat(used.health()).isEqualTo(fresh.health());

            assertThat(used.isAlive()).isTrue();

            assertThat(used.positionX()).isEqualTo(fresh.positionX());

            assertThat(used.positionZ()).isEqualTo(fresh.positionZ());

            assertThat(used.yawRadians()).isEqualTo(fresh.yawRadians());

            assertThat(used.readyAtTic()).isEqualTo(fresh.readyAtTic());

            assertThat(used.lastFiredTic()).isEqualTo(fresh.lastFiredTic());

            assertThat(used.hasSeenPlayer()).isEqualTo(fresh.hasSeenPlayer());

            assertThat(used.rememberedPlayerX()).isEqualTo(fresh.rememberedPlayerX());

            assertThat(used.rememberedPlayerZ()).isEqualTo(fresh.rememberedPlayerZ());
        }

        @Test
        @DisplayName("a reset corpse is back on its route, not left where it fell")
        void shouldMoveTheBodyBackWhenResetting()
        {
            // The ordering trap inside reset(): moveTo does nothing to a dead bot,
            // so reviving must happen BEFORE the placement. A reset that ran them
            // the other way round would restart the room with every survivor
            // standing where the last round killed it.
            final Bot pacer = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.PACE_X,
                AMPLITUDE, PERIOD, 0);

            final float atSpawn = pacer.positionX();

            pacer.moveTo(PERIOD / 4);

            assertThat(pacer.positionX()).isNotEqualTo(atSpawn);

            pacer.damage(Bot.MAX_HEALTH);

            pacer.reset();

            assertThat(pacer.positionX()).isEqualTo(atSpawn);
        }
    }

    @Nested
    @DisplayName("hitbox")
    class Hitbox
    {
        @Test
        @DisplayName("is exactly the size of a player's, so bots are no easier to hit")
        void shouldMatchThePlayerBoxWhenBuildingAHitbox()
        {
            final Bot standing = new Bot(4, 10.0f, 0.0f, -20.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0);

            final Target box = standing.hitbox();

            assertThat(box.entityId()).isEqualTo(4);

            assertThat(box.maxY() - box.minY()).isEqualTo(Bot.HEIGHT_UNITS);

            assertThat(box.maxX() - box.minX()).isEqualTo(Bot.RADIUS_UNITS * 2.0f);

            // Around the FEET, not centred on them. Centring buries half the box
            // in the floor and every shot misses low.
            assertThat(box.minY()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("follows the bot along its route")
        void shouldFollowTheBotWhenItMoves()
        {
            final Bot walker = bot(BotPattern.PACE_X);

            walker.moveTo(PERIOD / 4);

            final Target box = walker.hitbox();

            assertThat(box.minX()).isCloseTo(walker.positionX() - Bot.RADIUS_UNITS,
                within(EPSILON));

            assertThat(box.maxX()).isCloseTo(walker.positionX() + Bot.RADIUS_UNITS,
                within(EPSILON));
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("rejects the untagged and negative entity ids")
        void shouldRejectAnIdBelowTheMinimumWhenConstructed()
        {
            assertThatThrownBy(() -> new Bot(0, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a zero route period")
        void shouldRejectNonPositiveCyclesWhenConstructed()
        {
            // The fire interval used to be checked here too. It is no longer a
            // constructor argument at all — the cadence moved into BotSkill when
            // firing became a per-tic roll — and BotSkillTest carries its range
            // checks now.
            assertThatThrownBy(() -> new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
        }

        @Test
        @DisplayName("rejects a NaN amplitude and a NaN home point")
        void shouldRejectNonFiniteGeometryWhenConstructed()
        {
            assertThatThrownBy(() -> new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                Float.NaN, PERIOD, 0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new Bot(2, Float.POSITIVE_INFINITY, 0.0f, 0.0f,
                BotPattern.SENTRY, 0.0f, PERIOD, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a null pattern")
        void shouldRejectANullPatternWhenConstructed()
        {
            assertThatThrownBy(() -> new Bot(2, 0.0f, 0.0f, 0.0f, null,
                0.0f, PERIOD, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism
    {
        @Test
        @DisplayName("no java/lang/Math reference appears in Bot or BotPattern")
        void shouldNotReferenceMathWhenCompiled()
        {
            // The same guard PlayerController carries, and for the same reason:
            // Math.sin and Math.cos are permitted 1-2 ulp of error and are not
            // required to agree between JVMs, so one in a bot's route would put
            // two peers' bots in fractionally different places and eventually
            // disagree about whether a shot connected.
            //
            // The rule is deliberately FLAT — no java/lang/Math at all, not even
            // the integer-exact floorMod and max this class would otherwise use.
            // A pool holding both Math and StrictMath cannot be read to say
            // which of them owns the "sin" entry, so one legitimate exception
            // would defeat the check entirely.
            assertThat(constantPoolOf(Bot.class))
                .as("Bot must not reference java.lang.Math anywhere")
                .doesNotContain("java/lang/Math");

            assertThat(constantPoolOf(BotPattern.class))
                .as("BotPattern must not reference java.lang.Math anywhere")
                .doesNotContain("java/lang/Math");
        }

        @Test
        @DisplayName("both classes do reference StrictMath trigonometry")
        void shouldReferenceStrictMathWhenCompiled()
        {
            // The negative test above passes trivially if the trigonometry is
            // deleted, so pin the positive too.
            assertThat(constantPoolOf(BotPattern.class)).contains("java/lang/StrictMath");

            assertThat(constantPoolOf(BotPattern.class)).contains("sin");

            assertThat(constantPoolOf(BotPattern.class)).contains("cos");

            assertThat(constantPoolOf(Bot.class)).contains("atan2");
        }

        @Test
        @DisplayName("two bots on the same route agree bit for bit")
        void shouldAgreeBitForBitWhenTwoPeersWalkTheSameRoute()
        {
            final Bot peerA = bot(BotPattern.ORBIT);

            final Bot peerB = bot(BotPattern.ORBIT);

            for (int tic = 0; tic < 500; tic++)
            {
                peerA.moveTo(tic);

                peerB.moveTo(tic);

                peerA.faceToward(13.5f, -7.25f);

                peerB.faceToward(13.5f, -7.25f);

                assertThat(bits(peerB)).isEqualTo(bits(peerA));

                assertThat(Float.floatToRawIntBits(peerB.yawRadians()))
                    .isEqualTo(Float.floatToRawIntBits(peerA.yawRadians()));
            }
        }
    }

    // Position as raw bits, so an assertion is exact rather than approximate.
    private static int bits(final Bot subject)
    {
        return 31 * Float.floatToRawIntBits(subject.positionX())
            + Float.floatToRawIntBits(subject.positionZ());
    }

    // The class file bytes read as Latin-1, so every constant-pool UTF8 entry
    // survives as literal characters and can be searched for.
    private static String constantPoolOf(final Class<?> type)
    {
        final String resource = type.getName().replace('.', '/') + ".class";

        try (InputStream in = type.getClassLoader().getResourceAsStream(resource))
        {
            assertThat(in).as("class file for %s must be readable", type).isNotNull();

            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("could not read the class file for " + type, e);
        }
    }
}
