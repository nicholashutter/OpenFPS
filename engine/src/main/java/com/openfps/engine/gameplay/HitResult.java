/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * The outcome of one {@link Hitscan#fire} call — which entity was hit and how
 * far away it was.
 *
 * <h2>Why this is mutable, and why that is the right call</h2>
 *
 * <p>A shot happens on a simulation tic, and {@code STYLE.md} § 13.4 bans
 * allocation on a per-tic path. That leaves two honest options: return
 * primitives, or reuse one preallocated object. Primitives lose, because a hit
 * is two values of different types — an {@code int} id and a {@code float}
 * distance — and returning both means either packing them into a {@code long}
 * (unreadable, and it hides the distance behind
 * {@code Float.intBitsToFloat}) or a second call to fetch the other half
 * (which is stateful anyway, just with the state hidden somewhere worse).</p>
 *
 * <p>So: the caller owns one of these, allocated once at startup, and passes it
 * in. {@link Hitscan#fire} overwrites it and returns a {@code boolean} saying
 * whether anything was hit. Nothing is allocated inside the shot.</p>
 *
 * <p><b>The consequence, stated plainly: the values here are only valid until
 * the next {@code fire} call that is handed this object.</b> If a caller needs
 * to keep a hit past that point — to queue damage, say — it must copy the two
 * primitives out. Do not stash a reference to a {@code HitResult} and read it
 * later.</p>
 *
 * <p>Not thread-safe, and deliberately so. Like {@code PlayerController}, this
 * belongs to the game loop thread. A parallel hit resolve, if one is ever
 * wanted, gives each worker its own instance rather than sharing this one.</p>
 */
public final class HitResult
{
    /**
     * Entity id of the target hit, or {@link #NO_ENTITY} on a miss. MUTABLE:
     * overwritten by every {@link Hitscan#fire} call given this object.
     */
    private int entityId;

    /**
     * Distance from the ray origin to the hit, in world units, or
     * {@link #NO_DISTANCE} on a miss. MUTABLE: overwritten by every
     * {@link Hitscan#fire} call given this object.
     */
    private float distance;

    /**
     * Whether the last shot connected. MUTABLE: overwritten by every
     * {@link Hitscan#fire} call given this object.
     */
    private boolean hit;

    /**
     * Sentinel entity id meaning "nothing was hit".
     *
     * <p>Zero, matching the scene's untagged sentinel: an id of zero is never a
     * legal {@link Target}, so a caller that ignores the {@code boolean} return
     * and reads {@link #entityId()} anyway still cannot mistake a miss for a
     * real entity.</p>
     */
    public static final int NO_ENTITY = 0;

    /**
     * Sentinel distance meaning "nothing was hit".
     *
     * <p>Negative, because a real hit distance is always at or above zero, so
     * arithmetic done on a miss produces something obviously wrong rather than
     * something plausibly small.</p>
     */
    public static final float NO_DISTANCE = -1.0f;

    /** Creates a result in the miss state, ready to be passed to a shot. */
    public HitResult()
    {
        clear();
    }

    /**
     * Resets this result to the miss state.
     *
     * <p>{@link Hitscan#fire} does this itself before scanning, so a caller
     * never has to. It is public for the one case that matters: a caller
     * reusing a result across an interval where no shot was taken, which wants
     * the stale hit gone rather than lingering.</p>
     */
    public void clear()
    {
        this.entityId = NO_ENTITY;

        this.distance = NO_DISTANCE;

        this.hit = false;
    }

    /**
     * Records a hit. Package-private because {@link Hitscan} is the only thing
     * entitled to decide what was hit — a caller writing its own answer in here
     * would defeat the point of resolving hits in one deterministic place.
     *
     * @param hitEntityId the entity id that was hit
     * @param hitDistance the distance to the hit in world units
     */
    void set(final int hitEntityId, final float hitDistance)
    {
        this.entityId = hitEntityId;

        this.distance = hitDistance;

        this.hit = true;
    }

    /** Returns whether the last shot connected. */
    public boolean hit()
    {
        return hit;
    }

    /**
     * Returns the entity id that was hit, or {@link #NO_ENTITY} on a miss.
     *
     * @return the opaque entity id of the nearest target hit
     */
    public int entityId()
    {
        return entityId;
    }

    /**
     * Returns the distance from the ray origin to the hit in world units, or
     * {@link #NO_DISTANCE} on a miss.
     *
     * <p>Zero is a legitimate hit distance and means the shot started inside
     * the target's box; see {@link Hitscan} for why that counts.</p>
     *
     * @return the hit distance in world units
     */
    public float distance()
    {
        return distance;
    }

    /** Returns a debug rendering of the hit state. */
    @Override
    public String toString()
    {
        if (!hit)
        {
            return "HitResult{miss}";
        }

        return "HitResult{id=" + entityId + ", distance=" + distance + "}";
    }
}
