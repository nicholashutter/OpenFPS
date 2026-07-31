/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import com.openfps.engine.common.Constants;

/**
 * P_ The solid geometry a move is clipped against — walls, columns, crates.
 *
 * <p>This is the {@code PhysicsWorld} {@link PlayerController}'s Javadoc has
 * promised since the controller was written: it "wraps this later and rejects
 * the parts of a move that hit a wall". It takes a desired ground move and
 * returns the permitted one. It holds no player, no clock and no state of any
 * kind once built — every method is a pure function of its arguments and the
 * immutable box table, which is what keeps the controller in front of it
 * deterministic.</p>
 *
 * <h2>The body is a box, not a capsule, and it is DOOM's box</h2>
 *
 * <p>The player collides as an <b>upright axis-aligned box</b> of half-width
 * {@link #PLAYER_HALF_WIDTH_UNITS} — 16 world units, so 32 across — and that
 * number is not chosen here. It is {@link Constants#PLAYER_RADIUS} divided by
 * {@link Constants#MAP_SCALE}, the constant this engine inherited along with the
 * 41-unit eye and the 64-unit map grid, and the whole room is dimensioned
 * against it: {@code DemoScene.KIT_WORLD_SCALE}'s own Javadoc justifies the
 * 64-unit grid partly by "a 32-unit-diameter player in a 64-unit doorway has a
 * radius of clearance either side", and {@code DemoScene.BOT_ROUTE_CENTRES}
 * keeps every patrol inside the wall by the same 16. Inventing a different
 * radius here would put the collision hull at odds with three pieces of
 * geometry that were already sized around that one.</p>
 *
 * <p><b>A box rather than a cylinder</b>, because every solid in the room is
 * itself axis-aligned and a box against a box is four comparisons with no
 * square root and no rounded-corner case. DOOM's player was a 32 x 32 x 56 box
 * for the same reason and called its half-width a "radius", which is where the
 * name of the constant comes from. A cylinder would differ only within 4.7
 * units of a corner — the difference between the circle and its circumscribed
 * square — and would cost a distance test on the hot path to buy it. The
 * footprint does not rotate with the player, which is correct rather than a
 * simplification: movement is yaw-only and a square hull that spun with the
 * view would make a player's clearance depend on which way they were looking.</p>
 *
 * <h2>Sliding — why the move is clipped one axis at a time</h2>
 *
 * <p>{@link #slideX} and {@link #slideZ} are the two halves of the
 * {@code moveWithSlide} that {@code gameplay/README.md} specifies, and calling
 * them in that order is the whole of the sliding behaviour:</p>
 *
 * <pre>
 *   newX = world.slideX(x, z, deltaX);
 *   newZ = world.slideZ(newX, z, deltaZ);
 * </pre>
 *
 * <p>A move straight into a wall loses the component that hits it and keeps the
 * one that does not, so walking into a wall at an angle keeps walking
 * <i>along</i> it. <b>That is the difference between "has collision" and "feels
 * right"</b> — a solver that zeroes the whole move when any part of it is
 * blocked turns every wall into glue, and a player who brushes a corner comes to
 * a dead stop for no reason they can see. It is also exactly what DOOM did
 * ({@code p_map.c}: try the full move, then try each axis alone), and the reason
 * it survives is that it produces a natural feel against arbitrary wall angles
 * for two comparisons instead of a projection.</p>
 *
 * <p><b>The axis order is fixed at x-then-z and must stay fixed.</b> Not
 * "whichever component is larger", not "whichever is blocked less" — those are
 * tempting and they are desyncs, because two peers whose float state has
 * diverged by one ulp would take different branches and then disagree about
 * where a player is standing. A fixed order is bit-identical everywhere. The
 * second call is given the <i>already clipped</i> x, which is what makes an
 * inside corner hold: the x step is refused by one wall, and the z step is then
 * tested from the position the player actually reached rather than from the one
 * they asked for.</p>
 *
 * <h2>Clipped to contact, not merely refused</h2>
 *
 * <p>A blocked move is shortened to the exact surface, not thrown away. The
 * cheaper rule — reject the step entirely — leaves the player up to one step
 * short of the wall, which at 30 Hz is 8.5 units of invisible gap that changes
 * with the tic rate, and it jitters: some steps land, some do not. Clipping to
 * the contact plane makes the stopping position a property of the room rather
 * than of the frame rate, and it is what lets a test assert a coordinate.</p>
 *
 * <p><b>A body already inside a solid is never resolved</b>, only the crossing
 * of a face from outside is. That is the guard against the worst failure this
 * class could have: a player who ends up embedded — spawned badly, or moved by
 * something that does not consult this class — would otherwise be teleported to
 * a face or pinned forever. Here they simply walk out. The cost is that a solid
 * dropped onto a standing player does not push them, which nothing in this demo
 * does.</p>
 *
 * <h2>Tunnelling — why a swept test is not needed</h2>
 *
 * <p>The clip is a swept test along each axis rather than a point sample at the
 * destination, so a fast body cannot step over a thin wall. The margin is large
 * anyway: the thinnest solid in the demo room is a 12.8-unit wall slab, which
 * the 16-unit half-width widens to an effective 44.8 units of blocking, against
 * a longest possible step of {@code 256 / 30} = 8.5 world units at the slowest
 * supported tic rate — over five times the clearance needed. <b>Sprint has
 * since been added</b> ({@code PlayerController.SPRINT_MULTIPLIER}, 1.5x), and
 * the check was re-run rather than assumed: the sprinting step is
 * {@code 256 * 1.5 / 30} ~= 12.8 units, against the same 44.8-unit wall — still
 * 3.5x the clearance needed. The arithmetic stays written down here because
 * this is exactly the kind of check that silently stops being true the next
 * time someone adds a speed multiplier, and it is cheaper to re-derive than to
 * assume.</p>
 *
 * <h2>Cost and determinism</h2>
 *
 * <p>Two calls per tic, each a linear scan of a {@code float[]} that is 16 boxes
 * long for the demo room — 64 floats, one cache line short of nothing. <b>No
 * allocation, no {@code java.lang.Math}, no {@code sqrt}, no wall clock and no
 * randomness</b>: every operation is a comparison, an addition or a subtraction,
 * all of which JEP 306 makes bit-reproducible on every conforming JVM. That is
 * the requirement {@code net/README.md}'s lockstep model imposes, and
 * {@code PhysicsWorldTest} reads this class's constant pool to enforce it, the
 * same guard {@link PlayerController} carries.</p>
 *
 * <p>Boxes are stored flat as {@code minX, minZ, maxX, maxZ} quadruples rather
 * than as objects, because a scan over one primitive array is one cache stream
 * and an array of sixteen small objects is sixteen pointer chases for the same
 * data.</p>
 *
 * <h2>What this class does not do</h2>
 *
 * <p><b>It is two-dimensional.</b> Solids block at every height, and there is no
 * floor height, no step-up and no ceiling. The vertical axis stays where
 * {@link PlayerController}'s gravity leaves it: a flat floor at
 * {@link PlayerController#GROUND_LEVEL_UNITS}. Giving a solid a top would let a
 * player jump over a crate and then land <i>inside</i> it as gravity brought
 * them down through a lid that is not there — trapped in geometry, which is a
 * far worse bug than not being able to climb on the furniture. Standing on
 * things needs a floor-height query, and that is the next piece of work rather
 * than part of this one.</p>
 *
 * <p>It is immutable and therefore safe to share between threads. Build one per
 * map, hand it to whatever moves.</p>
 */
public final class PhysicsWorld
{
    /**
     * Floats per solid in {@link #solids}: {@code minX, minZ, maxX, maxZ}.
     *
     * <p>Public because a caller assembling a table by hand — and every test
     * that reads one back — needs the stride to index it, and a private 4 here
     * plus a literal 4 at every call site is how the two come to disagree.</p>
     */
    public static final int SOLID_STRIDE = 4;

    /**
     * Half the player's collision footprint in world units — <b>16</b>.
     *
     * <p>{@link Constants#PLAYER_RADIUS} in world units rather than 16.16 fixed
     * point. Derived rather than restated so that the collision hull, the bot
     * clearances in {@code DemoScene} and the doorway width argument in
     * {@code DemoScene.KIT_WORLD_SCALE} cannot drift apart — all three are the
     * same 16, and they were the same 16 before this class existed.</p>
     */
    public static final float PLAYER_HALF_WIDTH_UNITS =
        Constants.PLAYER_RADIUS / (float) Constants.MAP_SCALE;

    /**
     * A world with nothing solid in it — every move is permitted in full.
     *
     * <p>The default a {@link PlayerController} is built with, so that a
     * controller constructed without a room behaves exactly as it did before
     * this class existed. That is not a courtesy to old tests: {@code --model=}
     * runs, the preview tool and every unit test that wants to assert a
     * displacement have no room and should not have to invent one.</p>
     */
    public static final PhysicsWorld OPEN = new PhysicsWorld(0.0f, new float[0]);

    /** Half the body's footprint. Expands every solid by this on all four sides. */
    private final float halfWidth;

    /** Solids as flat {@code minX, minZ, maxX, maxZ} quadruples. Never null, never mutated. */
    private final float[] solids;

    /**
     * Creates a world from a flat table of solid boxes.
     *
     * <p>The table is copied, so the caller may keep and reuse its array. That
     * copy is the only allocation this class ever performs.</p>
     *
     * @param bodyHalfWidth half the colliding body's footprint in world units;
     *     must be finite and non-negative. Zero collides as a point
     * @param solidBoxes {@code minX, minZ, maxX, maxZ} per solid, in world
     *     units; must not be null, must be a whole number of quadruples, and
     *     each box must have {@code min < max} on both axes
     * @throws IllegalArgumentException if the radius or any box is unusable
     */
    public PhysicsWorld(final float bodyHalfWidth, final float[] solidBoxes)
    {
        if (solidBoxes == null)
        {
            throw new IllegalArgumentException("solidBoxes must not be null");
        }
        // Negated >= so NaN is rejected here rather than making every
        // comparison in slideX silently false and the world silently permeable.
        if (!(bodyHalfWidth >= 0.0f) || Float.isInfinite(bodyHalfWidth))
        {
            throw new IllegalArgumentException(
                "bodyHalfWidth must be finite and non-negative, got " + bodyHalfWidth);
        }
        if (solidBoxes.length % SOLID_STRIDE != 0)
        {
            throw new IllegalArgumentException("solidBoxes must be a whole number of "
                + SOLID_STRIDE + "-float boxes, got " + solidBoxes.length + " floats");
        }
        for (int base = 0; base < solidBoxes.length; base += SOLID_STRIDE)
        {
            requireOrdered(solidBoxes[base], solidBoxes[base + 2], base, "x");
            requireOrdered(solidBoxes[base + 1], solidBoxes[base + 3], base, "z");
        }
        this.halfWidth = bodyHalfWidth;
        this.solids = solidBoxes.clone();
    }

    // A box whose min is not below its max is inside out: every overlap test
    // would report "outside" and the solid would be invisible to the solver.
    // Caught at construction because a permeable wall is silent at runtime.
    private static void requireOrdered(final float min, final float max, final int base,
        final String axis)
    {
        if (!(min < max))
        {
            throw new IllegalArgumentException("solid at float " + base + " has " + axis
                + " min " + min + " not below max " + max);
        }
    }

    /**
     * Starts building a world for a body of a given half-width.
     *
     * @param bodyHalfWidth half the colliding body's footprint in world units;
     *     must be finite and non-negative
     * @return a fresh builder, never null
     */
    public static Builder builder(final float bodyHalfWidth)
    {
        return new Builder(bodyHalfWidth);
    }

    /**
     * Returns the permitted x after moving {@code deltaX} along x alone.
     *
     * <p>Call this <b>first</b> and feed its result to {@link #slideZ}; see the
     * class Javadoc for why the order is fixed. The z coordinate is the one the
     * body is standing at now, not the one it is about to reach — this call
     * answers "how far along x may it get from here", and the z step is a
     * separate question asked afterwards.</p>
     *
     * @param feetX the body's current world x
     * @param feetZ the body's current world z
     * @param deltaX the desired x displacement, either sign
     * @return the x the body may occupy: {@code feetX + deltaX} when nothing is
     *     in the way, otherwise the exact contact plane of the nearest solid.
     *     Never further from {@code feetX} than {@code deltaX} asked for, and
     *     never on the far side of it
     */
    public float slideX(final float feetX, final float feetZ, final float deltaX)
    {
        if (deltaX == 0.0f)
        {
            // Nothing asked for, so nothing to clip. Returning feetX unchanged
            // rather than feetX + 0.0f also keeps a stationary body bit-for-bit
            // still, which is what makes a repeated no-op update idempotent.
            return feetX;
        }
        float permitted = feetX + deltaX;
        for (int base = 0; base < solids.length; base += SOLID_STRIDE)
        {
            // The solid only matters if the body is beside it on the other
            // axis. A wall the body is level with cannot block travel along it.
            if (!overlaps(feetZ, solids[base + 1], solids[base + 3]))
            {
                continue;
            }
            if (deltaX > 0.0f)
            {
                final float contact = solids[base] - halfWidth;
                // feetX <= contact is the "started outside" guard: a body
                // already past this face is inside the solid, and shoving it
                // back to the face would teleport it. Let it walk out instead.
                if (feetX <= contact && permitted > contact)
                {
                    permitted = contact;
                }
            }
            else
            {
                final float contact = solids[base + 2] + halfWidth;
                if (feetX >= contact && permitted < contact)
                {
                    permitted = contact;
                }
            }
        }
        return permitted;
    }

    /**
     * Returns the permitted z after moving {@code deltaZ} along z alone.
     *
     * <p>Call this <b>second</b>, passing the x that {@link #slideX} permitted
     * rather than the one the body started at. That is not a detail: it is what
     * makes an inside corner hold. Tested from the original x, a body whose x
     * step was refused by one wall would be measured against the other wall from
     * a position it never occupied, and would leak diagonally out of the
     * corner.</p>
     *
     * @param feetX the body's world x <b>after</b> {@link #slideX}
     * @param feetZ the body's current world z
     * @param deltaZ the desired z displacement, either sign
     * @return the z the body may occupy: {@code feetZ + deltaZ} when nothing is
     *     in the way, otherwise the exact contact plane of the nearest solid
     */
    public float slideZ(final float feetX, final float feetZ, final float deltaZ)
    {
        if (deltaZ == 0.0f)
        {
            return feetZ;
        }
        float permitted = feetZ + deltaZ;
        for (int base = 0; base < solids.length; base += SOLID_STRIDE)
        {
            if (!overlaps(feetX, solids[base], solids[base + 2]))
            {
                continue;
            }
            if (deltaZ > 0.0f)
            {
                final float contact = solids[base + 1] - halfWidth;
                if (feetZ <= contact && permitted > contact)
                {
                    permitted = contact;
                }
            }
            else
            {
                final float contact = solids[base + 3] + halfWidth;
                if (feetZ >= contact && permitted < contact)
                {
                    permitted = contact;
                }
            }
        }
        return permitted;
    }

    /**
     * Returns whether the body's footprint would overlap any solid here.
     *
     * <p>Not used by the solver — {@link #slideX} and {@link #slideZ} answer a
     * sharper question — but it is what a test asserts against, and it is the
     * check anything placing a body (a spawn point, a teleport) should make
     * before putting one down.</p>
     *
     * @param feetX the body's world x
     * @param feetZ the body's world z
     * @return true if a body of this world's half-width standing there would be
     *     inside something solid
     */
    public boolean isBlocked(final float feetX, final float feetZ)
    {
        for (int base = 0; base < solids.length; base += SOLID_STRIDE)
        {
            if (overlaps(feetX, solids[base], solids[base + 2])
                && overlaps(feetZ, solids[base + 1], solids[base + 3]))
            {
                return true;
            }
        }
        return false;
    }

    // Whether a body centre at `coordinate` overlaps a solid spanning [min, max]
    // on that axis, once the solid is widened by the body's half-width — the
    // Minkowski expansion that turns a box-versus-box test into a point test.
    //
    // STRICTLY inside, on both ends, and that is load-bearing rather than
    // fastidious. A body clipped to contact by slideZ sits at exactly
    // `min - halfWidth`, computed by the identical float expression; an
    // inclusive test would then call it overlapping and refuse to let it slide
    // ALONG the wall it is resting against. Exclusive, touching is free.
    private boolean overlaps(final float coordinate, final float min, final float max)
    {
        return coordinate > min - halfWidth && coordinate < max + halfWidth;
    }

    /** Returns half the colliding body's footprint in world units. */
    public float halfWidth()
    {
        return halfWidth;
    }

    /** Returns how many solid boxes this world holds. */
    public int solidCount()
    {
        return solids.length / SOLID_STRIDE;
    }

    /**
     * Returns one solid's bounds, as {@code minX, minZ, maxX, maxZ}.
     *
     * <p>A copy, so the table cannot be edited through it. Allocates, and is
     * therefore for tests and diagnostics only — the solver reads the flat array
     * directly and never calls this.</p>
     *
     * @param index which solid, from zero, below {@link #solidCount()}
     * @return the four bounds of that box
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public float[] solid(final int index)
    {
        if (index < 0 || index >= solidCount())
        {
            throw new IndexOutOfBoundsException("no solid " + index + " of " + solidCount());
        }
        final int base = index * SOLID_STRIDE;
        return new float[]
        {
            solids[base], solids[base + 1], solids[base + 2], solids[base + 3],
        };
    }

    /** Returns a debug rendering of the world. */
    @Override
    public String toString()
    {
        return "PhysicsWorld{" + solidCount() + " solids, body half-width " + halfWidth + "}";
    }

    /**
     * Assembles a {@link PhysicsWorld} one box at a time.
     *
     * <p>Exists because the alternative at the call site is a hand-indexed
     * {@code float[]} literal, and a room described as sixty-four unlabelled
     * floats is a room nobody will ever correct. {@code DemoScene} names each
     * wall as it adds it.</p>
     *
     * <p>Mutable and single-threaded by design, like {@code Scene.Builder}: it
     * is used once during map assembly and discarded. The world it produces is
     * immutable and is the thing that is shared.</p>
     */
    public static final class Builder
    {
        /** Boxes to start with — the demo room needs sixteen. */
        private static final int INITIAL_CAPACITY = 16;

        /** Half the body's footprint, carried through to the built world. */
        private final float halfWidth;

        /** The flat box table, grown as needed. MUTABLE: appended to by {@link #addBox}. */
        private float[] boxes = new float[INITIAL_CAPACITY * SOLID_STRIDE];

        /** How many floats of {@link #boxes} are in use. MUTABLE: counts up. */
        private int used;

        // Only PhysicsWorld.builder makes these.
        private Builder(final float bodyHalfWidth)
        {
            this.halfWidth = bodyHalfWidth;
        }

        /**
         * Adds one solid box by its bounds.
         *
         * @param minX the box's low x edge in world units
         * @param minZ the box's low z edge in world units
         * @param maxX the box's high x edge, above {@code minX}
         * @param maxZ the box's high z edge, above {@code minZ}
         * @return this builder, for chaining
         */
        public Builder addBox(final float minX, final float minZ, final float maxX,
            final float maxZ)
        {
            if (used == boxes.length)
            {
                final float[] grown = new float[boxes.length * 2];
                System.arraycopy(boxes, 0, grown, 0, used);
                this.boxes = grown;
            }
            boxes[used] = minX;
            boxes[used + 1] = minZ;
            boxes[used + 2] = maxX;
            boxes[used + 3] = maxZ;
            this.used = used + SOLID_STRIDE;
            return this;
        }

        /**
         * Adds one solid box by its centre and half-extents.
         *
         * <p>The form props are placed in — {@code DemoScene} positions a crate
         * or a column at a centre and knows its size from the model extent — so
         * that the call site does not do the same four additions eleven times
         * and get one of them wrong.</p>
         *
         * @param centreX the box's centre, world x
         * @param centreZ the box's centre, world z
         * @param halfX half its footprint along x; must be positive
         * @param halfZ half its footprint along z; must be positive
         * @return this builder, for chaining
         */
        public Builder addBoxAt(final float centreX, final float centreZ, final float halfX,
            final float halfZ)
        {
            return addBox(centreX - halfX, centreZ - halfZ, centreX + halfX, centreZ + halfZ);
        }

        /**
         * Builds the immutable world.
         *
         * @return a world holding exactly the boxes added, never null
         * @throws IllegalArgumentException if any box is inside out or the
         *     half-width is unusable
         */
        public PhysicsWorld build()
        {
            final float[] exact = new float[used];
            System.arraycopy(boxes, 0, exact, 0, used);
            return new PhysicsWorld(halfWidth, exact);
        }
    }
}
