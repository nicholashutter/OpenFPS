/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * Deterministic hitscan — resolves an instant-hit shot against a set of
 * {@link Target} boxes and reports the nearest one struck.
 *
 * <h2>Precision: this is ray versus axis-aligned box, and nothing more</h2>
 *
 * <p><b>Say it plainly rather than let the API imply otherwise: a hit here
 * means the ray entered an entity's axis-aligned bounding box.</b> It does not
 * mean the ray touched the entity's mesh. A shot that passes between an
 * outstretched arm and the torso registers as a hit, a shot through the gap
 * under a raised arm registers as a hit, and there is no notion of a head, a
 * limb, or a damage multiplier for either. The box is a rectangular prism
 * around the whole entity and that is the entire fidelity of this first cut.</p>
 *
 * <p>That is a deliberate first cut, not an oversight. It is cheap, it is
 * trivially reproducible, and it is enough to make shooting work end to end.
 * <b>The upgrade path is per-triangle:</b> keep this box test as the broad
 * phase, and when it reports a candidate, walk that entity's triangles and
 * intersect each one properly (Moller-Trumbore is the usual choice, and is
 * pure {@code + - * /}, so it inherits the determinism argument below
 * unchanged). The nearest-hit selection and tie-break here stay exactly as they
 * are; only {@link #entryDistance} is replaced. Until that lands, do not build
 * anything on top of this that needs to know <i>where</i> on an entity a shot
 * landed, because this cannot tell you.</p>
 *
 * <h2>Why this lives in the simulation and not in the renderer</h2>
 *
 * <p>The renderer carries a per-pixel entity-id buffer, and sampling it at the
 * centre pixel would give pixel-exact hit detection for free. <b>That is the
 * wrong answer and must never be done.</b> Peers render at different
 * resolutions and different worker counts; two clients sampling their own
 * framebuffers would disagree about who was hit, and under the lockstep model
 * in {@code net/README.md} a disagreement about a hit is a desync, not a
 * rounding difference. The id buffer exists for the outline pass and for
 * nothing else. Hits are computed here, from geometry, on inputs both peers
 * already agree on.</p>
 *
 * <h2>Determinism, in four specific commitments</h2>
 *
 * <ol>
 *   <li><b>{@code StrictMath} only, never {@code java.lang.Math}.</b>
 *       {@code Math}'s transcendentals are permitted 1-2 ulp of error and are
 *       explicitly not required to agree between implementations. This class
 *       needs no transcendentals at all — the slab test is {@code + - * /} and
 *       comparisons, which JEP 306 makes bit-identical on every conforming JVM
 *       since Java 17 — but it holds to the same flat rule as
 *       {@code PlayerController} so there is no exception to remember, and
 *       {@code HitscanTest} reads this class's own constant pool and fails if
 *       {@code java/lang/Math} appears in it.</li>
 *   <li><b>No hash-ordered iteration.</b> Targets arrive as an array and are
 *       walked by index. There is no {@code HashMap}, no {@code HashSet}, and
 *       nothing whose order depends on a hash code or on insertion history.</li>
 *   <li><b>No reliance on float associativity.</b> Each candidate's distance is
 *       computed independently from the same inputs; nothing is accumulated
 *       across candidates. The selection compares only, it never sums, so the
 *       answer cannot depend on the order the candidates were visited.</li>
 *   <li><b>Ties break by lowest entity id.</b> Two targets at exactly the same
 *       distance — which happens the moment two boxes share a face, or two
 *       players stand in identical cover — resolve to the smaller id, never to
 *       whichever came first in the array. Array order is a property of how the
 *       caller built its entity list, and two peers must not be required to
 *       have built it the same way.</li>
 * </ol>
 *
 * <h2>The intersection test, and the NaN that lives in it</h2>
 *
 * <p>The standard slab method: for each axis, the ray enters that axis's slab
 * at one parameter and leaves at another; the box is hit over the intersection
 * of the three intervals. The entry parameter starts at zero rather than at
 * negative infinity, which does two jobs at once — <b>a target entirely behind
 * the shooter is not hit</b> (its exit parameter is negative, so the interval
 * is empty), and <b>a shot whose origin is inside the box hits at distance
 * zero</b>.</p>
 *
 * <p>Origin-inside-the-box being a hit is a decision, so here is the reason:
 * standing inside another player is a real position in a game with no
 * inter-player collision yet, and it is the position where a shot most
 * obviously ought to connect. Making it a miss would create an invisible dead
 * zone in which the closest possible target is the one you cannot shoot, which
 * reads as the weapon being broken. <b>The direct consequence for callers: the
 * shooter must not appear in its own target array</b>, because the shooter's
 * eye is inside the shooter's own box and it would hit itself at distance
 * zero every time.</p>
 *
 * <p><b>The classic bug in this algorithm is a NaN, and it is worth naming.</b>
 * The usual formulation multiplies by the reciprocal of the direction
 * component. When that component is exactly zero the reciprocal is an infinity,
 * and if the ray origin also lies exactly on the slab boundary the numerator is
 * exactly zero — so the product is {@code 0 * Infinity}, which is NaN. NaN
 * fails every comparison, so it silently propagates and the box is reported as
 * a miss. It is the case that shows up as "shots along the axes sometimes pass
 * through people", it only triggers on exact values, and it is exactly the kind
 * of thing that differs between two peers standing in different places.</p>
 *
 * <p>This class does not paper over that with NaN-tolerant min/max tricks. A
 * zero direction component means the ray is <b>parallel</b> to that pair of
 * slab planes, and a parallel ray either lies between them forever or never
 * touches them — so it is handled as what it is, a containment test, and the
 * division is never performed. Combined with {@link Target} rejecting
 * non-finite corners and {@link #fire} rejecting a non-finite origin or a
 * direction that is not unit length, <b>no NaN can be produced anywhere in this
 * class</b>, and the comparisons downstream need no NaN handling.</p>
 *
 * <p>Boundaries are inclusive throughout. A ray that grazes a face, an edge or
 * a corner exactly counts as a hit, and so does a ray parallel to a slab whose
 * origin sits exactly on one of its planes. Inclusive is the choice that makes
 * the parallel case and the general case agree; the alternative gives a
 * hairline of misses along exact coordinates, which is the same class of bug as
 * the NaN and just as hard to reproduce.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>{@link #fire} allocates nothing. Targets are built by the caller ahead of
 * time and the answer is written into a caller-owned {@link HitResult}; see
 * that class for why a reused result beat returning primitives. The only object
 * this class can create is an {@code IllegalArgumentException}, on a
 * programmer error that has already invalidated the tic.</p>
 *
 * <p>All methods are static and this class holds no state, so it is safe to
 * call from any thread. The mutable state in the shot is the caller's
 * {@link HitResult}, and two threads sharing one of those is the caller's bug,
 * not this class's.</p>
 */
public final class Hitscan
{
    /**
     * How far the squared length of a direction vector may stray from 1 before
     * {@link #fire} rejects it.
     *
     * <p>The returned distance <i>is</i> the ray parameter, which is only a
     * distance in world units if the direction is unit length — so a caller
     * that passes an unnormalised direction gets a silently wrong distance
     * rather than an error, which is why this is checked rather than assumed.
     * The tolerance is on the squared length so no square root is needed. It is
     * loose enough for any honestly normalised vector: a direction built from
     * {@code PlayerController.forwardVector()} is within a few ulp of unit, and
     * even a badly conditioned normalise lands orders of magnitude inside this,
     * while a vector someone forgot to normalise at all is not close.</p>
     */
    public static final float DIRECTION_LENGTH_TOLERANCE = 1.0e-3f;

    /**
     * Ray parameter returned by {@link #entryDistance} for a miss. Negative,
     * which no real entry distance can be, since the entry parameter is clamped
     * at zero.
     */
    private static final float NO_HIT = -1.0f;

    /** Not instantiable — this is a pure function over its arguments. */
    private Hitscan()
    {
    }

    /**
     * Fires a ray and reports the nearest target it enters.
     *
     * <p>The nearest hit wins; an exact tie goes to the lowest entity id. A
     * target behind the origin is never hit. A target whose box contains the
     * origin is hit at distance zero — so <b>do not put the shooter in its own
     * target array</b>. See the class Javadoc for the reasoning behind each of
     * those, and for what a "hit" does and does not mean at this fidelity.</p>
     *
     * <p>Nothing is allocated. The answer is written into {@code out}, which the
     * caller owns and reuses, and which is reset to the miss state before the
     * scan begins — so {@code out} is meaningful after a miss as well as after
     * a hit.</p>
     *
     * @param originX ray origin, world x; must be finite
     * @param originY ray origin, world y; must be finite. For a player shot this
     *     is the eye, not the feet — {@code PlayerController.eyePosition()}
     * @param originZ ray origin, world z; must be finite
     * @param directionX ray direction, world x
     * @param directionY ray direction, world y
     * @param directionZ ray direction, world z. The direction must be unit
     *     length to within {@link #DIRECTION_LENGTH_TOLERANCE}, because the
     *     reported distance is the ray parameter
     * @param targets the candidate boxes, walked by index from 0. A null entry
     *     is an empty slot and is skipped, so a caller may keep a fixed-size
     *     array with holes rather than compacting it every tic
     * @param targetCount how many leading entries of {@code targets} are live;
     *     must be between 0 and {@code targets.length}
     * @param out receives the answer; reset to the miss state first. Its
     *     contents are valid only until the next {@code fire} call given it
     * @return true if a target was hit, in which case {@code out} carries the
     *     entity id and the distance; false if nothing was hit
     * @throws IllegalArgumentException if {@code targets} or {@code out} is
     *     null, if {@code targetCount} is out of range, if an origin component
     *     is not finite, or if the direction is not unit length
     */
    public static boolean fire(
        final float originX, final float originY, final float originZ,
        final float directionX, final float directionY, final float directionZ,
        final Target[] targets, final int targetCount, final HitResult out)
    {
        validateRay(originX, originY, originZ, directionX, directionY, directionZ);
        validateTargets(targets, targetCount, out);

        out.clear();

        // MUTABLE locals: the running best over the scan. `found` is carried
        // rather than using a sentinel distance, because zero is a legitimate
        // hit distance and would be indistinguishable from "nothing yet".
        boolean found = false;
        int bestEntityId = HitResult.NO_ENTITY;
        float bestDistance = 0.0f;

        for (int i = 0; i < targetCount; i++)
        {
            final Target target = targets[i];
            if (target == null)
            {
                continue;
            }

            final float distance = entryDistance(target,
                originX, originY, originZ, directionX, directionY, directionZ);
            if (distance < 0.0f)
            {
                continue;
            }

            if (!found)
            {
                found = true;
                bestEntityId = target.entityId();
                bestDistance = distance;
            }
            else if (distance < bestDistance)
            {
                bestEntityId = target.entityId();
                bestDistance = distance;
            }
            else if (distance == bestDistance && target.entityId() < bestEntityId)
            {
                // The tie-break. Array order must not decide this: see the
                // class Javadoc, commitment 4.
                bestEntityId = target.entityId();
            }
        }

        if (found)
        {
            out.set(bestEntityId, bestDistance);
        }
        return found;
    }

    // Rejects a ray that would make the answer meaningless. NaN fails every
    // comparison, so each check is written so that NaN takes the throw branch.
    private static void validateRay(
        final float originX, final float originY, final float originZ,
        final float directionX, final float directionY, final float directionZ)
    {
        if (!Float.isFinite(originX) || !Float.isFinite(originY) || !Float.isFinite(originZ))
        {
            throw new IllegalArgumentException("ray origin must be finite, got ("
                + originX + ", " + originY + ", " + originZ + ")");
        }

        final float lengthSquared = directionX * directionX
            + directionY * directionY
            + directionZ * directionZ;
        // StrictMath.abs rather than Math.abs so the "no java/lang/Math here"
        // guard stays a single flat rule with no exceptions to remember.
        if (!(StrictMath.abs(lengthSquared - 1.0f) <= DIRECTION_LENGTH_TOLERANCE))
        {
            throw new IllegalArgumentException("ray direction must be unit length, got ("
                + directionX + ", " + directionY + ", " + directionZ
                + ") with squared length " + lengthSquared);
        }
    }

    // Rejects a target list or result the scan could not honour.
    private static void validateTargets(
        final Target[] targets, final int targetCount, final HitResult out)
    {
        if (targets == null)
        {
            throw new IllegalArgumentException("targets must not be null");
        }
        if (out == null)
        {
            throw new IllegalArgumentException("out must not be null");
        }
        if (targetCount < 0 || targetCount > targets.length)
        {
            throw new IllegalArgumentException("targetCount must be in [0, " + targets.length
                + "], got " + targetCount);
        }
    }

    // The slab test. Returns the entry distance along the ray, or NO_HIT.
    //
    // `enter` starts at 0, not at -infinity: that clamp is what makes a target
    // behind the shooter a miss and a target containing the origin a hit at
    // distance 0, without either needing its own branch.
    private static float entryDistance(final Target target,
        final float originX, final float originY, final float originZ,
        final float directionX, final float directionY, final float directionZ)
    {
        // MUTABLE locals: the running intersection of the three slab intervals.
        float enter = 0.0f;
        float exit = Float.POSITIVE_INFINITY;

        if (directionX == 0.0f)
        {
            // Parallel to the x slab planes — containment, never division.
            // This also catches -0.0f, for which == 0.0f is true.
            if (originX < target.minX() || originX > target.maxX())
            {
                return NO_HIT;
            }
        }
        else
        {
            enter = larger(enter, slabEnter(target.minX(), target.maxX(), originX, directionX));
            exit = smaller(exit, slabExit(target.minX(), target.maxX(), originX, directionX));
        }

        if (directionY == 0.0f)
        {
            if (originY < target.minY() || originY > target.maxY())
            {
                return NO_HIT;
            }
        }
        else
        {
            enter = larger(enter, slabEnter(target.minY(), target.maxY(), originY, directionY));
            exit = smaller(exit, slabExit(target.minY(), target.maxY(), originY, directionY));
        }

        if (directionZ == 0.0f)
        {
            if (originZ < target.minZ() || originZ > target.maxZ())
            {
                return NO_HIT;
            }
        }
        else
        {
            enter = larger(enter, slabEnter(target.minZ(), target.maxZ(), originZ, directionZ));
            exit = smaller(exit, slabExit(target.minZ(), target.maxZ(), originZ, directionZ));
        }

        // Inclusive: enter == exit is a graze along an edge or corner and counts.
        if (enter > exit)
        {
            return NO_HIT;
        }
        return enter;
    }

    // Ray parameter at which the ray enters one slab. The caller guarantees a
    // nonzero direction, so neither quotient can be 0/0, and the corners are
    // finite because Target validated them — no NaN is reachable here.
    //
    // Division, not multiplication by a precomputed reciprocal: both are
    // correctly rounded and so equally reproducible, and dividing is the more
    // accurate of the two. Three extra divisions per box per shot is not a cost
    // worth trading accuracy for on a path that runs once per trigger pull.
    private static float slabEnter(final float slabMin, final float slabMax,
        final float origin, final float direction)
    {
        return smaller((slabMin - origin) / direction, (slabMax - origin) / direction);
    }

    // Ray parameter at which the ray leaves one slab. Same guarantees.
    private static float slabExit(final float slabMin, final float slabMax,
        final float origin, final float direction)
    {
        return larger((slabMin - origin) / direction, (slabMax - origin) / direction);
    }

    // Written out rather than delegated to Math.min/Math.max, which this class
    // may not reference. No NaN can reach either of these; if one ever could,
    // it would fall through to the second argument, and the argument that no
    // NaN is reachable is the class Javadoc's, not a comparison's.
    private static float smaller(final float a, final float b)
    {
        if (a < b)
        {
            return a;
        }
        return b;
    }

    private static float larger(final float a, final float b)
    {
        if (a > b)
        {
            return a;
        }
        return b;
    }
}
