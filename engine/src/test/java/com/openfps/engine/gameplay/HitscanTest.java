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
 * Tests for {@link Hitscan}, {@link Target} and {@link HitResult}.
 *
 * <p>Two kinds of assertion appear here and the difference is deliberate.
 * Geometry that involves an inexact direction — anything normalised by a square
 * root — is asserted with {@link #EPSILON}. Everything the class exists to
 * <i>guarantee</i> is asserted exactly: the tie-break, the distance of a shot
 * that starts inside a box, and the bit patterns of a repeated scenario. A
 * tolerance on any of those would hide the exact bug it is there to catch.</p>
 *
 * <p>The axis-aligned cases use whole numbers throughout, so their expected
 * distances are exactly representable and can be asserted with
 * {@code isEqualTo} rather than a tolerance.</p>
 */
@DisplayName("Hitscan")
class HitscanTest
{
    /**
     * Absolute tolerance for distances along a normalised diagonal. The
     * direction components come from a square root of one half, so they carry
     * about an ulp of error, and distances here are on the order of ten units.
     */
    private static final float EPSILON = 1.0e-4f;

    /** One over the square root of two, as a normalised diagonal component. */
    private static final float DIAGONAL = (float) StrictMath.sqrt(0.5);

    /** Half a unit cube's worth of a player: the radius used by box helpers. */
    private static final float PLAYER_HALF_WIDTH = 16.0f;

    @Nested
    @DisplayName("basic aiming")
    class BasicAiming
    {
        @Test
        @DisplayName("a shot straight down the axis hits the box in front of it")
        void shouldHitWhenTargetIsStraightAhead()
        {
            final Target[] targets = {box(4, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f)};

            final HitResult out = new HitResult();

            final boolean hit = fireForward(targets, out);

            assertThat(hit).isTrue();

            assertThat(out.hit()).isTrue();

            assertThat(out.entityId()).isEqualTo(4);

            assertThat(out.distance()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("a shot past the side of a box misses and leaves a clean miss result")
        void shouldMissWhenTargetIsOffToTheSide()
        {
            final Target[] targets = {box(4, 10.0f, -1.0f, 10.0f, 12.0f, 1.0f, 12.0f)};

            final HitResult out = new HitResult();

            final boolean hit = fireForward(targets, out);

            assertThat(hit).isFalse();

            assertThat(out.hit()).isFalse();

            assertThat(out.entityId()).isEqualTo(HitResult.NO_ENTITY);

            assertThat(out.distance()).isEqualTo(HitResult.NO_DISTANCE);
        }

        @Test
        @DisplayName("a target directly behind the shooter is never hit")
        void shouldMissWhenTargetIsBehindTheShooter()
        {
            // The same box the straight-ahead case hits, mirrored through the
            // origin. If the entry parameter were not clamped at zero this
            // would report a hit at a negative distance, which is the bug.
            final Target[] targets = {box(4, -1.0f, -1.0f, -12.0f, 1.0f, 1.0f, -10.0f)};

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isFalse();
        }

        @Test
        @DisplayName("a box whose far face ends exactly at the origin is touched, not missed")
        void shouldHitAtZeroWhenTheBoxEndsExactlyAtTheOrigin()
        {
            // Exit parameter exactly 0: the box ends at the shooter's own
            // position. Touching counts, so this is a hit at distance 0 —
            // pinned here so the boundary rule is not accidentally reversed.
            final Target[] targets = {box(4, -1.0f, -1.0f, -10.0f, 1.0f, 1.0f, 0.0f)};

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isTrue();

            assertThat(out.distance()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("a shot along a diagonal hits at the geometric distance")
        void shouldHitWhenAimingDownADiagonal()
        {
            final Target[] targets = {box(4, 9.0f, -1.0f, 9.0f, 11.0f, 1.0f, 11.0f)};

            final HitResult out = new HitResult();

            final boolean hit = Hitscan.fire(0.0f, 0.0f, 0.0f, DIAGONAL, 0.0f, DIAGONAL,
                targets, targets.length, out);

            // Enters the x = 9 face at parameter 9 / (1/sqrt2) = 9*sqrt(2).
            assertThat(hit).isTrue();

            assertThat(out.distance())
                .isCloseTo(9.0f * (float) StrictMath.sqrt(2.0), within(EPSILON));
        }

        @Test
        @DisplayName("an empty target list is a miss, not a failure")
        void shouldMissWhenThereAreNoTargets()
        {
            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                new Target[0], 0, out)).isFalse();
        }

        @Test
        @DisplayName("a null slot in the target array is an empty slot, not a target")
        void shouldSkipNullEntriesWhenScanning()
        {
            // Callers keep fixed-size entity arrays with holes; compacting one
            // every tic would allocate, which is exactly what is banned.
            final Target[] targets = new Target[3];

            targets[1] = box(4, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f);

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isTrue();

            assertThat(out.entityId()).isEqualTo(4);
        }

        @Test
        @DisplayName("only the first targetCount entries are considered")
        void shouldIgnoreEntriesBeyondTheLiveCountWhenScanning()
        {
            final Target[] targets =
            {
                box(4, -1.0f, -1.0f, 20.0f, 1.0f, 1.0f, 22.0f),
                box(5, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f),
            };

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, targets, 1, out)).isTrue();

            assertThat(out.entityId()).isEqualTo(4);

            assertThat(out.distance()).isEqualTo(20.0f);
        }
    }

    @Nested
    @DisplayName("nearest hit and tie-breaking")
    class NearestAndTies
    {
        @Test
        @DisplayName("the nearest of several overlapping targets wins")
        void shouldReturnTheNearestWhenSeveralTargetsAreHit()
        {
            final Target[] targets =
            {
                box(9, -2.0f, -2.0f, 20.0f, 2.0f, 2.0f, 24.0f),
                box(3, -2.0f, -2.0f, 5.0f, 2.0f, 2.0f, 9.0f),
                box(7, -2.0f, -2.0f, 12.0f, 2.0f, 2.0f, 16.0f),
            };

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isTrue();

            assertThat(out.entityId()).isEqualTo(3);

            assertThat(out.distance()).isEqualTo(5.0f);
        }

        @Test
        @DisplayName("distance beats id — a nearer high id wins over a farther low id")
        void shouldPreferDistanceOverIdWhenBothDiffer()
        {
            // Guards against a tie-break written so loosely that it takes over
            // the ordinary case and makes the lowest id always win.
            final Target[] targets =
            {
                box(1, -2.0f, -2.0f, 20.0f, 2.0f, 2.0f, 24.0f),
                box(99, -2.0f, -2.0f, 5.0f, 2.0f, 2.0f, 9.0f),
            };

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isTrue();

            assertThat(out.entityId()).isEqualTo(99);
        }

        @Test
        @DisplayName("an exact tie resolves to the lowest entity id whatever order the array is in")
        void shouldBreakExactTiesByLowestIdWhateverTheArrayOrder()
        {
            // Two boxes whose front faces are the same plane: entry distance is
            // bit-identical, so array order is the only other thing that could
            // decide it — and two peers must not have to build their entity
            // lists in the same order.
            final Target low = box(3, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f);

            final Target high = box(7, -2.0f, -2.0f, 10.0f, 2.0f, 2.0f, 20.0f);

            final HitResult lowFirst = new HitResult();

            final HitResult highFirst = new HitResult();

            assertThat(fireForward(new Target[] {low, high}, lowFirst)).isTrue();

            assertThat(fireForward(new Target[] {high, low}, highFirst)).isTrue();

            assertThat(lowFirst.entityId()).isEqualTo(3);

            assertThat(highFirst.entityId()).isEqualTo(3);

            assertThat(lowFirst.distance()).isEqualTo(highFirst.distance());
        }

        @Test
        @DisplayName("a three-way exact tie resolves to the lowest id from every permutation")
        void shouldBreakThreeWayTiesByLowestIdFromEveryPermutation()
        {
            final Target a = box(11, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f);

            final Target b = box(2, -2.0f, -2.0f, 10.0f, 2.0f, 2.0f, 13.0f);

            final Target c = box(40, -3.0f, -3.0f, 10.0f, 3.0f, 3.0f, 14.0f);

            final Target[][] permutations =
            {
                {a, b, c}, {a, c, b}, {b, a, c}, {b, c, a}, {c, a, b}, {c, b, a},
            };

            final HitResult out = new HitResult();

            for (final Target[] permutation : permutations)
            {
                assertThat(fireForward(permutation, out)).isTrue();

                assertThat(out.entityId())
                    .as("permutation starting with id %d", permutation[0].entityId())
                    .isEqualTo(2);

                assertThat(out.distance()).isEqualTo(10.0f);
            }
        }
    }

    @Nested
    @DisplayName("origin inside a box")
    class OriginInside
    {
        @Test
        @DisplayName("a shot whose origin is inside a box hits it at distance zero")
        void shouldHitAtZeroDistanceWhenOriginIsInsideTheBox()
        {
            // The documented decision: standing inside another player is a real
            // position, and it is the one where a shot most obviously ought to
            // connect. Making it a miss would create a dead zone at point
            // blank, which reads as the weapon being broken.
            final Target[] targets = {box(4, -5.0f, -5.0f, -5.0f, 5.0f, 5.0f, 5.0f)};

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isTrue();

            assertThat(out.entityId()).isEqualTo(4);

            assertThat(out.distance()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("a containing box is hit in every direction, including backwards")
        void shouldHitTheContainingBoxWhicheverWayTheShotFaces()
        {
            final Target[] targets = {box(4, -5.0f, -5.0f, -5.0f, 5.0f, 5.0f, 5.0f)};

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f,
                targets, 1, out)).isTrue();

            assertThat(out.distance()).isEqualTo(0.0f);

            assertThat(Hitscan.fire(0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f,
                targets, 1, out)).isTrue();

            assertThat(out.distance()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("a containing box at distance zero beats every farther box")
        void shouldPreferTheContainingBoxWhenOtherTargetsAreAlsoHit()
        {
            final Target[] targets =
            {
                box(2, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f),
                box(50, -5.0f, -5.0f, -5.0f, 5.0f, 5.0f, 5.0f),
            };

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isTrue();

            assertThat(out.entityId()).isEqualTo(50);

            assertThat(out.distance()).isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("zero direction components — the classic NaN")
    class ZeroDirectionComponents
    {
        @Test
        @DisplayName("a ray parallel to a slab whose origin lies exactly on the slab plane still hits")
        void shouldHitWhenParallelRayOriginSitsExactlyOnTheSlabMinimum()
        {
            // THE bug this algorithm is famous for. The usual formulation
            // multiplies by 1/direction; with direction exactly 0 that is
            // infinity, and with the origin exactly on the plane the numerator
            // is exactly 0, so the product is 0 * Infinity = NaN. NaN fails
            // every comparison, so the box silently reports a miss.
            final Target[] targets = {box(4, 0.0f, 0.0f, 10.0f, 2.0f, 2.0f, 12.0f)};

            final HitResult out = new HitResult();

            final boolean hit = Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                targets, 1, out);

            assertThat(hit).isTrue();

            assertThat(Float.isNaN(out.distance())).isFalse();

            assertThat(out.distance()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("the same holds when the origin lies exactly on the slab maximum")
        void shouldHitWhenParallelRayOriginSitsExactlyOnTheSlabMaximum()
        {
            final Target[] targets = {box(4, -2.0f, -2.0f, 10.0f, 0.0f, 0.0f, 12.0f)};

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isTrue();

            assertThat(out.distance()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("a ray parallel to a slab it lies outside of misses")
        void shouldMissWhenParallelRayOriginIsOutsideTheSlab()
        {
            final Target[] targets = {box(4, 0.0f, 0.0f, 10.0f, 2.0f, 2.0f, 12.0f)};

            final HitResult out = new HitResult();

            // Just outside the x slab, everything else identical to the hit above.
            assertThat(Hitscan.fire(-0.001f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                targets, 1, out)).isFalse();
        }

        @Test
        @DisplayName("negative zero in a direction component behaves as zero, not as a tiny negative")
        void shouldTreatNegativeZeroDirectionAsParallel()
        {
            // -0.0f == 0.0f is true, so it takes the containment branch. If it
            // did not, 1 / -0.0f is negative infinity and the slab arithmetic
            // produces the same NaN by the other route.
            final Target[] targets = {box(4, 0.0f, 0.0f, 10.0f, 2.0f, 2.0f, 12.0f)};

            final HitResult out = new HitResult();

            final boolean hit = Hitscan.fire(0.0f, 0.0f, 0.0f, -0.0f, -0.0f, 1.0f,
                targets, 1, out);

            assertThat(hit).isTrue();

            assertThat(out.distance()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("a negative-zero origin on a zero-valued slab plane is inside it")
        void shouldTreatNegativeZeroOriginAsInsideASlabStartingAtZero()
        {
            final Target[] targets = {box(4, 0.0f, 0.0f, 10.0f, 2.0f, 2.0f, 12.0f)};

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(-0.0f, -0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                targets, 1, out)).isTrue();

            assertThat(out.distance()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("two zero components at once do not compound into a NaN")
        void shouldHitWhenTwoDirectionComponentsAreZeroAndBothOriginsAreOnPlanes()
        {
            final Target[] targets = {box(4, 0.0f, 0.0f, 0.0f, 5.0f, 5.0f, 5.0f)};

            final HitResult out = new HitResult();

            // Origin on the x = 0 and y = 0 planes, aimed along +z from behind.
            final boolean hit = Hitscan.fire(0.0f, 0.0f, -3.0f, 0.0f, 0.0f, 1.0f,
                targets, 1, out);

            assertThat(hit).isTrue();

            assertThat(Float.isNaN(out.distance())).isFalse();

            assertThat(out.distance()).isEqualTo(3.0f);
        }
    }

    @Nested
    @DisplayName("grazing and degenerate geometry")
    class GrazingAndDegenerate
    {
        @Test
        @DisplayName("a ray that grazes an edge exactly counts as a hit")
        void shouldHitWhenTheRayGrazesAnEdgeExactly()
        {
            // Passes exactly along the y = 1 top face and the z = 0.5 interior
            // line, so the y slab contributes a boundary-touching containment.
            final Target[] targets = {box(4, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)};

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(-5.0f, 1.0f, 0.5f, 1.0f, 0.0f, 0.0f,
                targets, 1, out)).isTrue();

            assertThat(out.distance()).isEqualTo(5.0f);
        }

        @Test
        @DisplayName("a ray that touches a corner exactly counts as a hit")
        void shouldHitWhenTheRayTouchesACornerExactly()
        {
            // Aimed along the x = 0, y = 0 corner line of the box.
            final Target[] targets = {box(4, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)};

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(0.0f, 0.0f, -5.0f, 0.0f, 0.0f, 1.0f,
                targets, 1, out)).isTrue();

            assertThat(out.distance()).isEqualTo(5.0f);
        }

        @Test
        @DisplayName("a diagonal that enters and leaves at the same parameter still hits")
        void shouldHitWhenEntryAndExitParametersAreExactlyEqual()
        {
            // Enters at (0, 1, 0.5) and leaves there too: the interval collapses
            // to a point, so this is the `enter == exit` boundary. Inclusive
            // means it hits; an exclusive test would drop it.
            final Target[] targets = {box(4, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)};

            final HitResult out = new HitResult();

            final boolean hit = Hitscan.fire(-1.0f, 0.0f, 0.5f, DIAGONAL, DIAGONAL, 0.0f,
                targets, 1, out);

            assertThat(hit).isTrue();

            assertThat(out.distance()).isCloseTo(1.0f / DIAGONAL, within(EPSILON));
        }

        @Test
        @DisplayName("a flat box — a plane — is a legal target and is hit")
        void shouldHitADegenerateBoxThatIsAPlane()
        {
            // A floor plate or a trigger surface: min equals max on one axis.
            final Target[] targets = {box(4, -1.0f, 0.0f, -1.0f, 1.0f, 0.0f, 1.0f)};

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(0.0f, 5.0f, 0.0f, 0.0f, -1.0f, 0.0f,
                targets, 1, out)).isTrue();

            assertThat(out.distance()).isEqualTo(5.0f);
        }

        @Test
        @DisplayName("a degenerate box that is a single point is hit by a ray aimed at it")
        void shouldHitADegenerateBoxThatIsAPoint()
        {
            final Target[] targets = {box(4, 0.0f, 0.0f, 7.0f, 0.0f, 0.0f, 7.0f)};

            final HitResult out = new HitResult();

            assertThat(fireForward(targets, out)).isTrue();

            assertThat(out.distance()).isEqualTo(7.0f);
        }

        @Test
        @DisplayName("a ray parallel to a flat box but offset from its plane misses")
        void shouldMissAFlatBoxWhenTravellingParallelToIt()
        {
            final Target[] targets = {box(4, -1.0f, 0.0f, -1.0f, 1.0f, 0.0f, 1.0f)};

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(0.0f, 0.5f, -5.0f, 0.0f, 0.0f, 1.0f,
                targets, 1, out)).isFalse();
        }
    }

    @Nested
    @DisplayName("input validation")
    class InputValidation
    {
        @Test
        @DisplayName("an unnormalised direction is rejected rather than yielding a wrong distance")
        void shouldRejectADirectionThatIsNotUnitLength()
        {
            final HitResult out = new HitResult();

            assertThatThrownBy(() -> Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.0f,
                new Target[0], 0, out))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit length");
        }

        @Test
        @DisplayName("a zero direction vector is rejected")
        void shouldRejectAZeroDirection()
        {
            final HitResult out = new HitResult();

            assertThatThrownBy(() -> Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                new Target[0], 0, out))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a NaN direction component is rejected")
        void shouldRejectANaNDirection()
        {
            final HitResult out = new HitResult();

            assertThatThrownBy(() -> Hitscan.fire(0.0f, 0.0f, 0.0f, Float.NaN, 0.0f, 1.0f,
                new Target[0], 0, out))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a direction within the stated tolerance of unit length is accepted")
        void shouldAcceptADirectionInsideTheStatedTolerance()
        {
            // Reads the class's own constant rather than restating it, so
            // forking the tolerance breaks this test (STYLE.md § 13.3).
            final float slightlyLong = 1.0f + Hitscan.DIRECTION_LENGTH_TOLERANCE * 0.25f;

            final Target[] targets = {box(4, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f)};

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, slightlyLong,
                targets, 1, out)).isTrue();
        }

        @Test
        @DisplayName("a non-finite origin is rejected")
        void shouldRejectANonFiniteOrigin()
        {
            final HitResult out = new HitResult();

            assertThatThrownBy(() -> Hitscan.fire(Float.POSITIVE_INFINITY, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, new Target[0], 0, out))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        }

        @Test
        @DisplayName("a null target array or result is rejected")
        void shouldRejectNullCollaborators()
        {
            assertThatThrownBy(() -> Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                null, 0, new HitResult()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets");

            assertThatThrownBy(() -> Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                new Target[0], 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out");
        }

        @Test
        @DisplayName("a target count outside the array is rejected")
        void shouldRejectAnOutOfRangeTargetCount()
        {
            final HitResult out = new HitResult();

            assertThatThrownBy(() -> Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                new Target[1], 2, out))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                new Target[1], -1, out))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Target")
    class TargetContract
    {
        @Test
        @DisplayName("an untagged or negative entity id is rejected")
        void shouldRejectAnIdThatCannotNameAnEntity()
        {
            assertThatThrownBy(() -> box(0, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");

            assertThatThrownBy(() -> box(-3, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f))
                .isInstanceOf(IllegalArgumentException.class);

            assertThat(Target.MIN_ENTITY_ID).isEqualTo(1);
        }

        @Test
        @DisplayName("an inverted box is rejected at construction, not silently at every shot")
        void shouldRejectAnInvertedBox()
        {
            assertThatThrownBy(() -> box(1, 5.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
        }

        @Test
        @DisplayName("a non-finite corner is rejected so the firing path never sees a NaN")
        void shouldRejectANonFiniteCorner()
        {
            assertThatThrownBy(() -> box(1, Float.NaN, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        }

        @Test
        @DisplayName("aroundFeet builds the box above the feet, not centred on them")
        void shouldBuildAPlayerBoxAboveTheFeet()
        {
            // Getting this backwards buries the hitbox in the floor and every
            // shot misses low, which is why the derivation lives in one place.
            final Target player = Target.aroundFeet(6, 10.0f, 0.0f, -4.0f,
                PLAYER_HALF_WIDTH, 64.0f);

            assertThat(player.entityId()).isEqualTo(6);

            assertThat(player.minX()).isEqualTo(10.0f - PLAYER_HALF_WIDTH);

            assertThat(player.maxX()).isEqualTo(10.0f + PLAYER_HALF_WIDTH);

            assertThat(player.minY()).isEqualTo(0.0f);

            assertThat(player.maxY()).isEqualTo(64.0f);

            assertThat(player.minZ()).isEqualTo(-4.0f - PLAYER_HALF_WIDTH);

            assertThat(player.maxZ()).isEqualTo(-4.0f + PLAYER_HALF_WIDTH);
        }

        @Test
        @DisplayName("aroundFeet rejects a negative or NaN radius and height")
        void shouldRejectABadPlayerBoxShape()
        {
            assertThatThrownBy(() -> Target.aroundFeet(1, 0.0f, 0.0f, 0.0f, -1.0f, 64.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radius");

            assertThatThrownBy(() -> Target.aroundFeet(1, 0.0f, 0.0f, 0.0f, 16.0f, Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("height");
        }

        @Test
        @DisplayName("a player box built from feet is hit by a level shot at eye height")
        void shouldHitAPlayerBoxBuiltFromFeet()
        {
            final Target[] targets =
            {
                Target.aroundFeet(6, 0.0f, 0.0f, 100.0f, PLAYER_HALF_WIDTH, 64.0f),
            };

            final HitResult out = new HitResult();

            assertThat(Hitscan.fire(0.0f, 41.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                targets, 1, out)).isTrue();

            assertThat(out.entityId()).isEqualTo(6);

            assertThat(out.distance()).isEqualTo(100.0f - PLAYER_HALF_WIDTH);
        }
    }

    @Nested
    @DisplayName("HitResult")
    class HitResultContract
    {
        @Test
        @DisplayName("a fresh result is a miss")
        void shouldStartInTheMissState()
        {
            final HitResult out = new HitResult();

            assertThat(out.hit()).isFalse();

            assertThat(out.entityId()).isEqualTo(HitResult.NO_ENTITY);

            assertThat(out.distance()).isEqualTo(HitResult.NO_DISTANCE);
        }

        @Test
        @DisplayName("a miss overwrites the previous hit rather than leaving it stale")
        void shouldClearAPreviousHitWhenTheNextShotMisses()
        {
            // The reused-result design's one hazard: a caller that reads the
            // result without checking the boolean must still see a miss.
            final HitResult out = new HitResult();

            assertThat(fireForward(
                new Target[] {box(4, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f)}, out)).isTrue();

            assertThat(fireForward(
                new Target[] {box(4, 50.0f, -1.0f, 10.0f, 52.0f, 1.0f, 12.0f)}, out)).isFalse();

            assertThat(out.entityId()).isEqualTo(HitResult.NO_ENTITY);

            assertThat(out.distance()).isEqualTo(HitResult.NO_DISTANCE);
        }

        @Test
        @DisplayName("clear resets a result the caller is holding between shots")
        void shouldResetOnDemand()
        {
            final HitResult out = new HitResult();

            fireForward(new Target[] {box(4, -1.0f, -1.0f, 10.0f, 1.0f, 1.0f, 12.0f)}, out);

            out.clear();

            assertThat(out.hit()).isFalse();

            assertThat(out.toString()).contains("miss");
        }

        @Test
        @DisplayName("the miss sentinels cannot be confused with a real entity or distance")
        void shouldUseSentinelsNoRealHitCanProduce()
        {
            assertThat(HitResult.NO_ENTITY).isLessThan(Target.MIN_ENTITY_ID);

            assertThat(HitResult.NO_DISTANCE).isLessThan(0.0f);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism
    {
        @Test
        @DisplayName("the same scenario run twice gives bit-identical results")
        void shouldReproduceTheSameBitsWhenRunTwice()
        {
            final int[] first = runScenario();

            final int[] second = runScenario();

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("reordering the target array does not change the answer")
        void shouldReturnTheSameAnswerWhateverOrderTheTargetsArriveIn()
        {
            // Peers build their entity lists independently. If array order
            // could move the answer by even one ulp, two peers would eventually
            // disagree about a hit, which under lockstep is a desync.
            final Target near = box(31, -1.0f, -1.0f, 7.0f, 1.0f, 1.0f, 9.0f);

            final Target far = box(5, -3.0f, -3.0f, 25.0f, 3.0f, 3.0f, 30.0f);

            final Target middle = box(18, -2.0f, -2.0f, 12.0f, 2.0f, 2.0f, 16.0f);

            final HitResult forward = new HitResult();

            final HitResult reversed = new HitResult();

            assertThat(fireForward(new Target[] {near, middle, far}, forward)).isTrue();

            assertThat(fireForward(new Target[] {far, middle, near}, reversed)).isTrue();

            assertThat(reversed.entityId()).isEqualTo(forward.entityId());

            assertThat(Float.floatToRawIntBits(reversed.distance()))
                .isEqualTo(Float.floatToRawIntBits(forward.distance()));
        }

        @Test
        @DisplayName("no java/lang/Math reference appears anywhere in the compiled Hitscan")
        void shouldNotReferenceMathWhenCompiled()
        {
            // The same guard PlayerController carries, for the same reason and
            // deliberately in the same shape. java.lang.Math is permitted 1-2
            // ulp of error on its transcendentals and is explicitly not
            // required to agree between JVM implementations, so one of its
            // methods in the hit path desyncs lockstep silently — two peers
            // disagreeing about whether a shot connected, with no way to
            // reproduce it in a single-process test because a single process is
            // self-consistent. Reading the constant pool is the only check that
            // a plausible-looking edit cannot defeat.
            assertThat(constantPoolOf(Hitscan.class))
                .as("Hitscan must not reference java.lang.Math anywhere")
                .doesNotContain("java/lang/Math");
        }

        @Test
        @DisplayName("neither does the target box nor the hit result")
        void shouldKeepTheValueTypesFreeOfMathWhenCompiled()
        {
            // Both are on the firing path, so the rule has to cover them too or
            // the guard just moves the problem one class sideways.
            assertThat(constantPoolOf(Target.class)).doesNotContain("java/lang/Math");

            assertThat(constantPoolOf(HitResult.class)).doesNotContain("java/lang/Math");
        }

        @Test
        @DisplayName("the compiled Hitscan does reference StrictMath")
        void shouldReferenceStrictMathWhenCompiled()
        {
            // The negative test passes trivially if someone deletes the call
            // altogether, so pin the positive too.
            assertThat(constantPoolOf(Hitscan.class)).contains("java/lang/StrictMath");
        }

        @Test
        @DisplayName("no hash-ordered collection is referenced by the firing path")
        void shouldNotReferenceHashOrderedCollectionsWhenCompiled()
        {
            // Iteration order over a HashMap or HashSet depends on hash codes
            // and insertion history, neither of which two peers are required to
            // share. The scan walks an array by index instead.
            final String constantPool = constantPoolOf(Hitscan.class);

            assertThat(constantPool).doesNotContain("java/util/HashMap");

            assertThat(constantPool).doesNotContain("java/util/HashSet");
        }
    }

    // A fixed, varied scenario, deterministic by construction rather than by
    // seeding a generator (STYLE.md § 10). Returns every answer as raw bits.
    private static int[] runScenario()
    {
        final Target[] targets =
        {
            box(12, -2.0f, -2.0f, 30.0f, 2.0f, 2.0f, 34.0f),
            box(3, 1.0f, -1.0f, 8.0f, 3.0f, 1.0f, 10.0f),
            null,
            box(7, -4.0f, -4.0f, 15.0f, 4.0f, 4.0f, 19.0f),
            box(21, -4.0f, -4.0f, 15.0f, 6.0f, 6.0f, 21.0f),
        };

        final HitResult out = new HitResult();

        final int[] bits = new int[64];

        for (int i = 0; i < 32; i++)
        {
            // A fan of directions in the x/z plane, normalised exactly so the
            // unit-length check passes and the parameter is a true distance.
            final float weight = (float) (i - 16) / 64.0f;

            final float length = (float) StrictMath.sqrt(weight * weight + 1.0f);

            final float dirX = weight / length;

            final float dirZ = 1.0f / length;

            final boolean hit = Hitscan.fire(0.0f, 0.0f, 0.0f, dirX, 0.0f, dirZ,
                targets, targets.length, out);

            bits[i * 2] = out.entityId();

            bits[i * 2 + 1] = Float.floatToRawIntBits(out.distance());

            assertThat(out.hit()).isEqualTo(hit);
        }

        return bits;
    }

    // Shorthand for the common case: a shot from the origin down +z.
    private static boolean fireForward(final Target[] targets, final HitResult out)
    {
        return Hitscan.fire(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, targets, targets.length, out);
    }

    // Shorthand for a target box, so the test bodies read as geometry.
    private static Target box(final int entityId,
        final float minX, final float minY, final float minZ,
        final float maxX, final float maxY, final float maxZ)
    {
        return new Target(entityId, minX, minY, minZ, maxX, maxY, maxZ);
    }

    // The class file bytes read as Latin-1, so that every constant-pool UTF8
    // entry is searchable as a plain substring. Same helper, same reasoning, as
    // PlayerControllerTest's.
    private static String constantPoolOf(final Class<?> type)
    {
        final String resource = type.getSimpleName() + ".class";

        try (InputStream in = type.getResourceAsStream(resource))
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
