/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * One weapon on the map, available for the player to pick up.
 *
 * <p>The 2026-08 pickup system. A map declares a list of pickups
 * in its {@code MapSpec}; each pickup is a {@link Weapon} at a
 * fixed world position. The player walks into the pickup's
 * footprint and the pickup is consumed: the weapon goes into
 * the player's {@link Inventory}, the pickup's world instance
 * is hidden, and the player can fire the new weapon as soon
 * as the inventory's current-weapon pointer is on it.</p>
 *
 * <p>The pickup's Y is the world Y at which the weapon model
 * floats; the player's pickup check uses the XZ distance and
 * the Y distance separately, so a pickup on a gantry (Y = 64)
 * is not picked up by a player on the ground (Y = 0) just
 * because they are within XZ range.</p>
 *
 * <p>Pickup is an immutable record, so it is safe to share
 * between threads and to store in a {@code List<Pickup>} on a
 * {@code MapSpec} that is itself immutable.</p>
 *
 * @param weapon the weapon this pickup grants
 * @param x world x at which the weapon floats, in world units
 * @param y world y at which the weapon floats, in world units
 * @param z world z at which the weapon floats, in world units
 */
public record Pickup(Weapon weapon, float x, float y, float z)
{
    /**
     * Radius of the XZ-distance check, in world units, around the
     * pickup's centre. The player is "on" the pickup when the
     * horizontal distance is at most this value. Set to one Kenney
     * grid unit (KIT_TILE_UNITS = 64 world units, 4 m) so the
     * player can pick up a weapon by walking into the tile it
     * sits on, but cannot pick it up from across the room.
     */
    public static final float PICKUP_RADIUS_UNITS = 64.0f;

    /**
     * Maximum vertical separation, in world units, between the
     * player's feet and the pickup's Y for the pickup to count
     * as reachable. A pickup on a 64-unit gantry needs the
     * player to be on the gantry; the 96-unit headroom absorbs
     * the player's eye height plus a margin so the eye does
     * not miss a pickup by being slightly above the centre of
     * the tile.
     */
    public static final float PICKUP_VERTICAL_REACH_UNITS = 96.0f;

    /**
     * Returns whether the player at the given world position is
     * "on" this pickup, meaning the XZ distance is at most
     * {@link #PICKUP_RADIUS_UNITS} and the Y separation is at
     * most {@link #PICKUP_VERTICAL_REACH_UNITS}.
     *
     * <p>Both checks are squared to avoid the {@code sqrt} on
     * the hot path; a player on the same tile as the pickup
     * is well inside both bounds, and a player one tile over
     * is well outside them, so the unsquared form was
     * unnecessary.</p>
     *
     * @param playerX player feet x, in world units
     * @param playerY player feet y, in world units
     * @param playerZ player feet z, in world units
     * @return true if the player is close enough to pick up
     */
    public boolean isAt(final float playerX, final float playerY, final float playerZ)
    {
        final float dx = playerX - x;
        final float dz = playerZ - z;

        if (dx * dx + dz * dz > PICKUP_RADIUS_UNITS * PICKUP_RADIUS_UNITS)
        {
            return false;
        }

        final float dy = playerY - y;

        return dy * dy <= PICKUP_VERTICAL_REACH_UNITS * PICKUP_VERTICAL_REACH_UNITS;
    }
}
