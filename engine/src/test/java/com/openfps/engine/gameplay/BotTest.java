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

    /** A firing cadence. */
    private static final int FIRE_INTERVAL = 150;

    private static Bot bot(final BotPattern pattern)
    {
        return new Bot(2, 0.0f, 0.0f, 0.0f, pattern, AMPLITUDE, PERIOD, 0, FIRE_INTERVAL, 0);
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
                AMPLITUDE, PERIOD, 0, FIRE_INTERVAL, 0);

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
                AMPLITUDE, PERIOD, 0, FIRE_INTERVAL, 0);
            final Bot trailing = new Bot(3, 0.0f, 0.0f, 0.0f, BotPattern.ORBIT,
                AMPLITUDE, PERIOD, PERIOD / 2, FIRE_INTERVAL, 0);

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
                AMPLITUDE, PERIOD, PERIOD / 4, FIRE_INTERVAL, 0);

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
                0.0f, PERIOD, 0, FIRE_INTERVAL, 0);

            sentry.faceToward(0.0f, 100.0f);

            assertThat(sentry.yawRadians()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("yaw increases from +z toward +x, not the other way")
        void shouldFaceAQuarterTurnWhenTheTargetIsOnPositiveX()
        {
            final Bot sentry = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0, FIRE_INTERVAL, 0);

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
                0.0f, PERIOD, 0, FIRE_INTERVAL, 0);
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
                0.0f, PERIOD, 0, FIRE_INTERVAL, 0);
            assertThat(victim.wantsToFire(0)).isTrue();

            victim.damage(Bot.MAX_HEALTH);

            for (int tic = 0; tic < FIRE_INTERVAL * 3; tic++)
            {
                assertThat(victim.wantsToFire(tic)).isFalse();
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
    @DisplayName("firing cadence")
    class Cadence
    {
        @Test
        @DisplayName("asks to fire once per interval and no more often")
        void shouldFireOncePerIntervalWhenAlive()
        {
            final Bot shooter = bot(BotPattern.SENTRY);
            int shots = 0;
            for (int tic = 0; tic < FIRE_INTERVAL * 4; tic++)
            {
                if (shooter.wantsToFire(tic))
                {
                    shots++;
                }
            }

            assertThat(shots).isEqualTo(4);
        }

        @Test
        @DisplayName("offsets stop two bots volleying on the same tic")
        void shouldStaggerTwoBotsWhenTheirFireOffsetsDiffer()
        {
            final Bot first = new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0, FIRE_INTERVAL, 0);
            final Bot second = new Bot(3, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0, FIRE_INTERVAL, FIRE_INTERVAL / 2);

            for (int tic = 0; tic < FIRE_INTERVAL * 4; tic++)
            {
                assertThat(first.wantsToFire(tic) && second.wantsToFire(tic))
                    .as("tic %d: both bots fired at once", tic)
                    .isFalse();
            }
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
                0.0f, PERIOD, 0, FIRE_INTERVAL, 0);

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
                0.0f, PERIOD, 0, FIRE_INTERVAL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a zero period and a zero fire interval")
        void shouldRejectNonPositiveCyclesWhenConstructed()
        {
            assertThatThrownBy(() -> new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, 0, 0, FIRE_INTERVAL, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
            assertThatThrownBy(() -> new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                0.0f, PERIOD, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fire interval");
        }

        @Test
        @DisplayName("rejects a NaN amplitude and a NaN home point")
        void shouldRejectNonFiniteGeometryWhenConstructed()
        {
            assertThatThrownBy(() -> new Bot(2, 0.0f, 0.0f, 0.0f, BotPattern.SENTRY,
                Float.NaN, PERIOD, 0, FIRE_INTERVAL, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Bot(2, Float.POSITIVE_INFINITY, 0.0f, 0.0f,
                BotPattern.SENTRY, 0.0f, PERIOD, 0, FIRE_INTERVAL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a null pattern")
        void shouldRejectANullPatternWhenConstructed()
        {
            assertThatThrownBy(() -> new Bot(2, 0.0f, 0.0f, 0.0f, null,
                0.0f, PERIOD, 0, FIRE_INTERVAL, 0))
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
