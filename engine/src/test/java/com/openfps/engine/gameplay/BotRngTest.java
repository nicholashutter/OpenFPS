/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BotRng}.
 *
 * <p>The properties under test are the ones lockstep depends on, and none of them
 * is "the numbers look random". A generator that produced a poor distribution
 * would make the bots feel odd; one that produced a <b>different</b> distribution
 * on two machines, or on two runs, would make two peers disagree about whether a
 * shot connected — and would do it silently, for minutes, before anybody noticed.
 * So most of what follows is about reproducibility and independence rather than
 * about statistics.</p>
 */
@DisplayName("BotRng")
class BotRngTest
{
    /** Draws to take when a test needs a distribution rather than a value. */
    private static final int SAMPLES = 20_000;

    @Nested
    @DisplayName("reproducibility — the whole reason this class exists")
    class Reproducibility
    {
        @Test
        @DisplayName("the same question always gives the same answer")
        void shouldBePureForOneSeed()
        {
            final BotRng rng = new BotRng(42L);

            for (int tic = 0; tic < 100; tic++)
            {
                final float first = rng.unitFloat(tic, 2, BotRng.CHANNEL_FIRE);
                assertThat(rng.unitFloat(tic, 2, BotRng.CHANNEL_FIRE))
                    .as("asking twice gave two answers at tic %d", tic)
                    .isEqualTo(first);
            }
        }

        @Test
        @DisplayName("two generators on one seed agree everywhere — two peers, two processes")
        void shouldAgreeBetweenTwoInstances()
        {
            // The lockstep statement in its plainest form. These two objects stand
            // in for two machines: they have exchanged nothing but a seed, and they
            // have to reach the same answer to every question.
            final BotRng peerOne = new BotRng(0xC0FFEEL);
            final BotRng peerTwo = new BotRng(0xC0FFEEL);

            for (int tic = -50; tic < 200; tic++)
            {
                for (int entity = 2; entity < 9; entity++)
                {
                    assertThat(peerTwo.unitFloat(tic, entity, BotRng.CHANNEL_AIM_YAW))
                        .as("peers disagreed at tic %d for entity %d", tic, entity)
                        .isEqualTo(peerOne.unitFloat(tic, entity, BotRng.CHANNEL_AIM_YAW));
                }
            }
        }

        @Test
        @DisplayName("ORDER does not matter, which is what makes an early-out safe")
        void shouldNotDependOnTheOrderOfDraws()
        {
            // The property that a stateful generator cannot have, and the reason
            // this one is counter-based. A seeded java.util.Random is reproducible
            // only if it is drawn from in the same order the same number of times —
            // so a bot that skipped its accuracy roll because it was out of range
            // would shift every later draw, and adding one `if` to the firing path
            // would desync a build against its own previous version.
            final BotRng forwards = new BotRng(7L);
            final BotRng backwards = new BotRng(7L);

            final float[] ascending = new float[50];
            for (int tic = 0; tic < ascending.length; tic++)
            {
                ascending[tic] = forwards.unitFloat(tic, 3, BotRng.CHANNEL_FIRE);
            }
            // Same questions, asked from the other end, with half of them skipped
            // on the way past.
            for (int tic = ascending.length - 1; tic >= 0; tic -= 2)
            {
                assertThat(backwards.unitFloat(tic, 3, BotRng.CHANNEL_FIRE))
                    .as("the answer at tic %d depended on what had been asked before", tic)
                    .isEqualTo(ascending[tic]);
            }
        }

        @Test
        @DisplayName("a different seed gives a different sequence")
        void shouldDivergeUnderADifferentSeed()
        {
            // The negative half. Without it, a generator that ignored its seed
            // entirely would pass every test above.
            final BotRng one = new BotRng(1L);
            final BotRng two = new BotRng(2L);

            int agreements = 0;
            for (int tic = 0; tic < 200; tic++)
            {
                if (one.unitFloat(tic, 2, BotRng.CHANNEL_FIRE)
                    == two.unitFloat(tic, 2, BotRng.CHANNEL_FIRE))
                {
                    agreements++;
                }
            }

            assertThat(agreements).as("two seeds produced the same sequence").isZero();
        }
    }

    @Nested
    @DisplayName("independence — what stops seven bots behaving as one")
    class Independence
    {
        @Test
        @DisplayName("two entities on one tic get different answers")
        void shouldDecorrelateEntitiesOnTheSameTic()
        {
            // Directly the "no volley" property, one level below where BotTest
            // asserts it. If the entity id did not reach the mixer, every bot in
            // the room would pull the trigger on the same tic forever.
            final BotRng rng = new BotRng();

            int collisions = 0;
            for (int tic = 0; tic < 500; tic++)
            {
                if (rng.unitFloat(tic, 2, BotRng.CHANNEL_FIRE)
                    == rng.unitFloat(tic, 3, BotRng.CHANNEL_FIRE))
                {
                    collisions++;
                }
            }

            assertThat(collisions).as("two bots drew identically %d times", collisions).isZero();
        }

        @Test
        @DisplayName("(tic 2, bot 3) is not the same point as (tic 3, bot 2)")
        void shouldNotCollideOnTransposedCoordinates()
        {
            // The specific hazard a plain sum of the coordinates would have. It
            // would give adjacent bots correlated behaviour on adjacent tics, which
            // is a subtle version of exactly the lockstep-of-appearance this
            // randomness exists to break — and it would look like a coincidence
            // rather than like a bug.
            final BotRng rng = new BotRng();

            assertThat(rng.unitFloat(2, 3, BotRng.CHANNEL_FIRE))
                .isNotEqualTo(rng.unitFloat(3, 2, BotRng.CHANNEL_FIRE));
        }

        @Test
        @DisplayName("two channels on one tic and one bot get different answers")
        void shouldDecorrelateChannels()
        {
            // Without this, "do I fire?" and "how far off is my aim?" are the same
            // number, and a bot would always miss by an amount fixed by its
            // decision to shoot — a correlation nobody could explain from the
            // outside.
            final BotRng rng = new BotRng();

            int collisions = 0;
            for (int tic = 0; tic < 500; tic++)
            {
                if (rng.unitFloat(tic, 2, BotRng.CHANNEL_FIRE)
                    == rng.unitFloat(tic, 2, BotRng.CHANNEL_AIM_YAW))
                {
                    collisions++;
                }
            }

            assertThat(collisions).isZero();
        }
    }

    @Nested
    @DisplayName("the ranges it promises")
    class Ranges
    {
        @Test
        @DisplayName("unitFloat stays inside [0, 1), including for negative tics")
        void shouldKeepUnitFloatInRange()
        {
            // Negative tics are not hypothetical: Match is driven by an index the
            // caller supplies, and an unsigned shift is what stops the sign bit
            // producing a negative "probability" that would make chance() always
            // true.
            final BotRng rng = new BotRng();

            for (int tic = -SAMPLES / 2; tic < SAMPLES / 2; tic++)
            {
                final float drawn = rng.unitFloat(tic, 2, BotRng.CHANNEL_FIRE);
                assertThat(drawn).as("tic %d drew %f", tic, drawn)
                    .isGreaterThanOrEqualTo(0.0f).isLessThan(1.0f);
            }
        }

        @Test
        @DisplayName("boundedInt stays inside [0, bound), including for negative tics")
        void shouldKeepBoundedIntInRange()
        {
            final BotRng rng = new BotRng();
            final int bound = 90;

            for (int tic = -SAMPLES / 2; tic < SAMPLES / 2; tic++)
            {
                final int drawn = rng.boundedInt(tic, 2, BotRng.CHANNEL_COOLDOWN, bound);
                assertThat(drawn).as("tic %d drew %d", tic, drawn)
                    .isGreaterThanOrEqualTo(0).isLessThan(bound);
            }
        }

        @Test
        @DisplayName("symmetric straddles zero and stays within its magnitude")
        void shouldKeepSymmetricInRange()
        {
            // The shape the aim error needs: a bot must be able to miss to EITHER
            // side. A one-sided error would make every bot in the room pull its
            // shots the same way, which a player would learn to stand in front of.
            final BotRng rng = new BotRng();
            final float magnitude = 0.244f;
            int negatives = 0;
            int positives = 0;

            for (int tic = 0; tic < SAMPLES; tic++)
            {
                final float drawn =
                    rng.symmetric(tic, 2, BotRng.CHANNEL_AIM_YAW, magnitude);
                assertThat(drawn).isGreaterThanOrEqualTo(-magnitude).isLessThan(magnitude);
                if (drawn < 0.0f)
                {
                    negatives++;
                }
                else
                {
                    positives++;
                }
            }

            // Roughly even, loosely asserted — the point is that both signs happen
            // in quantity, not that they are balanced to a percent.
            assertThat(negatives).isGreaterThan(SAMPLES / 3);
            assertThat(positives).isGreaterThan(SAMPLES / 3);
        }

        @Test
        @DisplayName("chance comes up true at about the rate it is asked for")
        void shouldHonourAProbability()
        {
            // BotSkill's fire chance is expressed in parts per thousand and the
            // whole balance argument rests on it meaning what it says.
            final BotRng rng = new BotRng();
            final int permille = 250;
            int hits = 0;

            for (int tic = 0; tic < SAMPLES; tic++)
            {
                if (rng.chance(tic, 2, BotRng.CHANNEL_FIRE, permille))
                {
                    hits++;
                }
            }

            final int measured = hits * BotRng.PER_MILLE / SAMPLES;
            assertThat(measured).as("asked for %d/1000 and got %d/1000", permille, measured)
                .isBetween(permille - 30, permille + 30);
        }

        @Test
        @DisplayName("a chance of zero never happens and a chance of a thousand always does")
        void shouldHonourTheExtremes()
        {
            // BotSkill.MARKSMAN is built on both ends of this: it fires on every
            // ready tic (1000) and never takes a wild shot (0). A generator that
            // was off by one at either end would make the geometry tests
            // intermittent.
            final BotRng rng = new BotRng();

            for (int tic = 0; tic < 1000; tic++)
            {
                assertThat(rng.chance(tic, 2, BotRng.CHANNEL_WILD, 0)).isFalse();
                assertThat(rng.chance(tic, 2, BotRng.CHANNEL_FIRE, BotRng.PER_MILLE)).isTrue();
            }
        }

        @Test
        @DisplayName("a non-positive bound is rejected rather than dividing by zero")
        void shouldRejectANonPositiveBound()
        {
            final BotRng rng = new BotRng();

            assertThatThrownBy(() -> rng.boundedInt(0, 2, BotRng.CHANNEL_COOLDOWN, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound");
            assertThatThrownBy(() -> rng.boundedInt(0, 2, BotRng.CHANNEL_COOLDOWN, -5))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("distribution — enough to know it is not degenerate")
    class Distribution
    {
        @Test
        @DisplayName("unitFloat fills its range rather than clustering")
        void shouldSpreadAcrossTheUnitRange()
        {
            // Not a statistical certification — SplitMix64's finaliser has one of
            // those already. This is the check that would catch a mixer wired up
            // wrongly: one that returned the top bits of a counter, say, would
            // pass every reproducibility test above and put every draw in one
            // tenth of the range.
            final BotRng rng = new BotRng();
            final int buckets = 10;
            final int[] counts = new int[buckets];

            for (int tic = 0; tic < SAMPLES; tic++)
            {
                counts[(int) (rng.unitFloat(tic, 2, BotRng.CHANNEL_FIRE) * buckets)]++;
            }

            final int expected = SAMPLES / buckets;
            for (int bucket = 0; bucket < buckets; bucket++)
            {
                assertThat(counts[bucket]).as("bucket %d held %d of an expected %d",
                    bucket, counts[bucket], expected)
                    .isBetween(expected / 2, expected * 2);
            }
        }
    }

    @Nested
    @DisplayName("determinism guards")
    class Guards
    {
        @Test
        @DisplayName("no java/lang/Math reference appears in the compiled class")
        void shouldNotReferenceMathWhenCompiled()
        {
            // The same guard PlayerController and Bot carry, and this class is
            // where it matters most: it IS the randomness. The rule is deliberately
            // FLAT — no java/lang/Math at all — because a constant pool holding
            // both Math and StrictMath cannot be read to say which of them owns an
            // entry, so one legitimate exception defeats the check entirely.
            assertThat(constantPoolOf(BotRng.class))
                .as("BotRng must not reference java.lang.Math anywhere")
                .doesNotContain("java/lang/Math");
        }

        @Test
        @DisplayName("nothing wall-clock or thread-scoped is referenced either")
        void shouldNotReferenceAnyClockOrThreadRandomWhenCompiled()
        {
            // The list from the class Javadoc, enforced. Every one of these would
            // desync two peers on the first shot, and none of them would fail a
            // single-process test — a single process is self-consistent, which is
            // exactly why this has to be read out of the bytecode.
            final String pool = constantPoolOf(BotRng.class);

            assertThat(pool).as("a seeded-from-the-clock generator").doesNotContain("java/util/Random");
            assertThat(pool).as("thread-scoped randomness").doesNotContain("ThreadLocalRandom");
            assertThat(pool).as("wall-clock time").doesNotContain("nanoTime");
            assertThat(pool).as("wall-clock time").doesNotContain("currentTimeMillis");
        }

        @Test
        @DisplayName("Match's firing path is free of the same references")
        void shouldKeepMatchFreeOfNonDeterminism()
        {
            // Match is where a future contributor will actually reach for
            // Math.random(), because it is where the shot is resolved. The guard
            // therefore covers it and BotSkill as well as the generator itself.
            assertThat(constantPoolOf(Match.class))
                .as("Match must not reference java.lang.Math anywhere")
                .doesNotContain("java/lang/Math");
            assertThat(constantPoolOf(Match.class)).doesNotContain("java/util/Random");
            assertThat(constantPoolOf(Match.class)).doesNotContain("nanoTime");
            assertThat(constantPoolOf(Match.class)).doesNotContain("currentTimeMillis");
            assertThat(constantPoolOf(BotSkill.class)).doesNotContain("java/util/Random");
        }

        @Test
        @DisplayName("Match does use StrictMath, so the trigonometry is reproducible")
        void shouldReferenceStrictMathWhenCompiled()
        {
            // The negative test above passes trivially if somebody deletes the
            // trigonometry, so pin the positive too. Match needs sin, cos and atan2
            // to build a scattered shot direction, and all three are permitted 1-2
            // ulp of error under java.lang.Math.
            final String pool = constantPoolOf(Match.class);

            assertThat(pool).contains("java/lang/StrictMath");
            assertThat(pool).contains("atan2");
            assertThat(pool).contains("sin");
            assertThat(pool).contains("cos");
        }
    }

    // The class file bytes read as Latin-1, so that every constant-pool UTF8
    // entry is searchable as a plain substring.
    private static String constantPoolOf(final Class<?> type)
    {
        final String resource = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resource))
        {
            if (in == null)
            {
                throw new IllegalStateException("no class file for " + type);
            }
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            int read = in.read(chunk);
            while (read > 0)
            {
                bytes.write(chunk, 0, read);
                read = in.read(chunk);
            }
            return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("cannot read the class file for " + type, e);
        }
    }
}
