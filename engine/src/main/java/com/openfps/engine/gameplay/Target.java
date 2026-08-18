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
 * <h2>Mutability, and the firing path</h2>
 *
 * <p>The fields are mutable on purpose. {@code STYLE.md} § 13.4 bans
 * allocation on a per-tic path, and {@link Hitscan#fire} allocates nothing,
 * so the boxes have to be <i>reused</i> rather than recreated. The intended
 * pattern is: a caller — typically {@link Match} — pre-allocates one
 * {@code Target} slot per entity that can move, and a {@code Target[]} big
 * enough to hold every target in the scene; both are filled in place by
 * {@link #write(int, float, float, float, float, float, float)} (or the
 * derived {@link #aroundFeetInto(Target, int, float, float, float, float,
 * float)}) each tic. The validation is the same as the constructor, so a
 * NaN corner still fails loudly rather than poisoning the slab arithmetic
 * in a way that is very hard to trace back from a "shots sometimes miss"
 * bug report.</p>
 *
 * <p>Why mutable, not a pool: a pool would need to know which slot is
 * "free" between calls, and a recycled slot is the same object every shot
 * — the per-entity position is what changes, not the slot identity. A
 * mutable slot is the smaller surface; a pool would only be a smaller
 * surface if the slot count varied, and the engine's per-tic path knows
 * how many targets it has before the loop starts.</p>
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

    /**
     * Opaque, strictly positive entity id. Never an array index.
     *
     * <p>MUTABLE: rewritten by {@link #write} when the slot is reused.
     * Immutable-by-default does not survive the firing path's need to
     * rebuild a box in place every tic; the field is the seam, and the
     * constructor's validation is the only safety net.</p>
     */
    private int entityId;

    /**
     * Lower corner, world x. MUTABLE: see {@link #entityId} for why.
     */
    private float minX;

    /** Lower corner, world y. MUTABLE. */
    private float minY;

    /** Lower corner, world z. MUTABLE. */
    private float minZ;

    /** Upper corner, world x. MUTABLE. */
    private float maxX;

    /** Upper corner, world y. MUTABLE. */
    private float maxY;

    /** Upper corner, world z. MUTABLE. */
    private float maxZ;

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

    /**
     * Writes a new bounding box into an existing slot, re-validating every
     * field as the constructor would. The hot-path companion to
     * {@link #aroundFeet(int, float, float, float, float, float)} and the
     * reason the fields are mutable.
     *
     * <p>Bounds are validated at the rewrite rather than at every read so
     * that a NaN corner fails immediately at the call site that produced
     * it, rather than three frames later inside a slab test that has no
     * idea where the box came from. The cost of six {@code requireFinite}
     * calls and three ordered comparisons is one float per slot per tic,
     * which is cheaper than a {@code new Target} and the GC pressure that
     * comes with it.</p>
     *
     * @param newEntityId the owning entity's id; must be at least
     *     {@link #MIN_ENTITY_ID}
     * @param newMinX lower corner, world x
     * @param newMinY lower corner, world y
     * @param newMinZ lower corner, world z
     * @param newMaxX upper corner, world x; must be at least {@code newMinX}
     * @param newMaxY upper corner, world y; must be at least {@code newMinY}
     * @param newMaxZ upper corner, world z; must be at least {@code newMinZ}
     * @throws IllegalArgumentException if the id, any corner, or any
     *     min/max relationship is out of range
     */
    void write(final int newEntityId,
        final float newMinX, final float newMinY, final float newMinZ,
        final float newMaxX, final float newMaxY, final float newMaxZ)
    {
        if (newEntityId < MIN_ENTITY_ID)
        {
            throw new IllegalArgumentException(
                "entityId must be at least " + MIN_ENTITY_ID + ", got " + newEntityId);
        }

        requireFinite("boxMinX", newMinX);

        requireFinite("boxMinY", newMinY);

        requireFinite("boxMinZ", newMinZ);

        requireFinite("boxMaxX", newMaxX);

        requireFinite("boxMaxY", newMaxY);

        requireFinite("boxMaxZ", newMaxZ);

        requireOrdered("x", newMinX, newMaxX);

        requireOrdered("y", newMinY, newMaxY);

        requireOrdered("z", newMinZ, newMaxZ);

        this.entityId = newEntityId;

        this.minX = newMinX;

        this.minY = newMinY;

        this.minZ = newMinZ;

        this.maxX = newMaxX;

        this.maxY = newMaxY;

        this.maxZ = newMaxZ;
    }

    /**
     * Writes the standing-body box around the given feet into an existing
     * slot. Hot-path companion to {@link #aroundFeet(int, float, float,
     * float, float, float)}, paired with the same radius/height parameters.
     *
     * <p>The {@code slot} is mutated in place. No allocation. Re-validation
     * follows the same rules as the constructor — see {@link #write(int,
     * float, float, float, float, float, float)}.</p>
     *
     * @param slot the target to write into; must not be null
     * @param entityId the owning entity's id; must be at least
     *     {@link #MIN_ENTITY_ID}
     * @param feetX feet position, world x
     * @param feetY feet position, world y
     * @param feetZ feet position, world z
     * @param radius half-width of the box on both horizontal axes; must be
     *     finite and non-negative
     * @param height extent of the box above the feet; must be finite and
     *     non-negative
     */
    public static void aroundFeetInto(final Target slot, final int entityId,
        final float feetX, final float feetY, final float feetZ,
        final float radius, final float height)
    {
        if (slot == null)
        {
            throw new IllegalArgumentException("slot must not be null");
        }

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

        slot.write(entityId,
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
