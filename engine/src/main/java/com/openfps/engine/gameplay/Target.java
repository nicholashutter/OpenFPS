/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * A shootable entity, described to {@link Hitscan} as an axis-aligned bounding
 * box in world space plus the entity id that owns it.
 *
 * <p>This is the <b>only</b> shape the hitscan understands. It is a coarse
 * stand-in for the entity's real geometry — see {@link Hitscan} for what that
 * costs and for the per-triangle upgrade path.</p>
 *
 * <h2>Immutable, and allocated off the firing path</h2>
 *
 * <p>Every field is {@code final} and every value is a primitive, so a target
 * can be built once when an entity spawns and shared freely across threads.
 * That is the whole reason this type exists rather than the box being passed as
 * six loose floats: {@code STYLE.md} § 13.4 bans allocation on a per-tic path,
 * and {@link Hitscan#fire} allocates nothing, so the boxes have to be built
 * <i>somewhere else</i>. Build them at spawn, or rebuild one when an entity
 * moves, but never inside the shot.</p>
 *
 * <p>Bounds are validated at construction rather than at every shot. A box with
 * a NaN or infinite corner would poison the slab arithmetic in a way that is
 * very hard to trace back from a "shots sometimes miss" bug report, and the
 * whole point of doing this check once is that the firing path can then assume
 * its inputs are finite and never has to test for NaN.</p>
 *
 * <h2>Entity ids</h2>
 *
 * <p>Ids are opaque and strictly positive. Zero is the scene's "untagged"
 * sentinel — untagged geometry is world architecture, not a shootable entity —
 * so it is rejected here rather than silently producing hits that name nobody.
 * Nothing may assume ids are dense, ordered, or usable as array indices; the
 * only ordering this class participates in is {@link Hitscan}'s tie-break,
 * which prefers the lowest id.</p>
 */
public final class Target
{
    /**
     * The lowest legal entity id. Zero is the untagged sentinel, and negative
     * ids are reserved by {@code Constants.NULL_ENTITY} as "no entity".
     */
    public static final int MIN_ENTITY_ID = 1;

    /** Opaque, strictly positive entity id. Never an array index. */
    private final int entityId;

    /** Lower corner, world x. */
    private final float minX;

    /** Lower corner, world y. */
    private final float minY;

    /** Lower corner, world z. */
    private final float minZ;

    /** Upper corner, world x. */
    private final float maxX;

    /** Upper corner, world y. */
    private final float maxY;

    /** Upper corner, world z. */
    private final float maxZ;

    /**
     * Creates a target from an explicit box.
     *
     * <p>A degenerate box — one where a min equals its max, making the box a
     * plane, a line or a point — is legal on purpose. It is the natural way to
     * express a flat trigger surface, and the intersection test handles it
     * without a special case.</p>
     *
     * @param entityId the owning entity's id; must be at least
     *     {@link #MIN_ENTITY_ID}
     * @param boxMinX lower corner, world x
     * @param boxMinY lower corner, world y
     * @param boxMinZ lower corner, world z
     * @param boxMaxX upper corner, world x; must be at least {@code boxMinX}
     * @param boxMaxY upper corner, world y; must be at least {@code boxMinY}
     * @param boxMaxZ upper corner, world z; must be at least {@code boxMinZ}
     * @throws IllegalArgumentException if the id is out of range, if any corner
     *     is not finite, or if any min exceeds its max
     */
    public Target(final int entityId,
        final float boxMinX, final float boxMinY, final float boxMinZ,
        final float boxMaxX, final float boxMaxY, final float boxMaxZ)
    {
        if (entityId < MIN_ENTITY_ID)
        {
            throw new IllegalArgumentException(
                "entityId must be at least " + MIN_ENTITY_ID + ", got " + entityId);
        }

        requireFinite("boxMinX", boxMinX);

        requireFinite("boxMinY", boxMinY);

        requireFinite("boxMinZ", boxMinZ);

        requireFinite("boxMaxX", boxMaxX);

        requireFinite("boxMaxY", boxMaxY);

        requireFinite("boxMaxZ", boxMaxZ);

        requireOrdered("x", boxMinX, boxMaxX);

        requireOrdered("y", boxMinY, boxMaxY);

        requireOrdered("z", boxMinZ, boxMaxZ);

        this.entityId = entityId;

        this.minX = boxMinX;

        this.minY = boxMinY;

        this.minZ = boxMinZ;

        this.maxX = boxMaxX;

        this.maxY = boxMaxY;

        this.maxZ = boxMaxZ;
    }

    /**
     * Builds the box a standing player occupies, from the placement a
     * {@code PlayerController} actually holds.
     *
     * <p>That controller stores the player's <b>feet</b>, not the centre, and
     * the difference is exactly one body height — getting it wrong puts the
     * hitbox in the floor and every shot misses low. Deriving it here means one
     * place to get it right instead of one per caller.</p>
     *
     * @param entityId the owning entity's id; must be at least
     *     {@link #MIN_ENTITY_ID}
     * @param feetX feet position, world x
     * @param feetY feet position, world y — the floor the player stands on
     * @param feetZ feet position, world z
     * @param radius half-width of the box on both horizontal axes, in world
     *     units; must be finite and non-negative
     * @param height extent of the box above the feet, in world units; must be
     *     finite and non-negative
     * @return an immutable target box centred horizontally on the feet and
     *     rising {@code height} above them
     * @throws IllegalArgumentException if the id, radius or height is out of
     *     range, or if any resulting corner is not finite
     */
    public static Target aroundFeet(final int entityId,
        final float feetX, final float feetY, final float feetZ,
        final float radius, final float height)
    {
        // Written as negated >= so NaN, which fails every comparison, is
        // rejected here rather than reaching the box.
        if (!(radius >= 0.0f))
        {
            throw new IllegalArgumentException(
                "radius must be non-negative and a number, got " + radius);
        }

        if (!(height >= 0.0f))
        {
            throw new IllegalArgumentException(
                "height must be non-negative and a number, got " + height);
        }

        return new Target(entityId,
            feetX - radius, feetY, feetZ - radius,
            feetX + radius, feetY + height, feetZ + radius);
    }

    // Rejects NaN and both infinities, so the firing path never has to.
    private static void requireFinite(final String name, final float value)
    {
        if (!Float.isFinite(value))
        {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }

    // Rejects an inverted slab, which would otherwise read as an empty box that
    // silently swallows every shot aimed at it.
    private static void requireOrdered(final String axis, final float low, final float high)
    {
        if (low > high)
        {
            throw new IllegalArgumentException(
                "box min must not exceed box max on " + axis + ", got " + low + " > " + high);
        }
    }

    /** Returns the opaque, strictly positive entity id this box belongs to. */
    public int entityId()
    {
        return entityId;
    }

    /** Returns the lower corner, world x. */
    public float minX()
    {
        return minX;
    }

    /** Returns the lower corner, world y. */
    public float minY()
    {
        return minY;
    }

    /** Returns the lower corner, world z. */
    public float minZ()
    {
        return minZ;
    }

    /** Returns the upper corner, world x. */
    public float maxX()
    {
        return maxX;
    }

    /** Returns the upper corner, world y. */
    public float maxY()
    {
        return maxY;
    }

    /** Returns the upper corner, world z. */
    public float maxZ()
    {
        return maxZ;
    }

    /** Returns a debug rendering of the id and both corners. */
    @Override
    public String toString()
    {
        return "Target{id=" + entityId
            + ", min=(" + minX + ", " + minY + ", " + minZ + ")"
            + ", max=(" + maxX + ", " + maxY + ", " + maxZ + ")}";
    }
}
