/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import java.util.HashMap;
import java.util.Map;

/**
 * The player's weapon collection: which weapons they carry, how
 * much ammo each has, and which one is in the player's hand.
 *
 * <p>The 2026-08 pickup system. The player starts with the
 * {@link Weapon#BLASTER} at infinite ammo; picking up a
 * {@link Weapon#SHOTGUN} or {@link Weapon#ROCKET_LAUNCHER}
 * adds that weapon to the inventory with its
 * {@link Weapon#ammoMax ammo max}, and the player can switch
 * the held weapon to any of the ones they carry.</p>
 *
 * <p>The current weapon is what {@code Match.firePlayerShot}
 * reads when it dispatches. Switching the current weapon does
 * not consume ammo - only firing does. The inventory is also
 * what the viewmodel render reads to know which model to draw
 * at the camera.</p>
 *
 * <p>State is plain mutable maps rather than a record so the
 * inventory can grow as the player picks up new weapons
 * without rebuilding itself. The instance is owned by the
 * {@code PlayerController} and is single-threaded; no
 * synchronisation is needed on the hot path.</p>
 */
public final class Inventory
{
    /**
     * The blaster: every player has this from the start, with
     * infinite ammo. The inventory constructor seeds it; nothing
     * in the engine path ever drops the blaster.
     */
    private static final Weapon STARTER_WEAPON = Weapon.BLASTER;

    /**
     * Weapon -> ammo count. The blaster is always present with
     * ammo = -1; every other weapon is absent until a pickup
     * adds it. Lookups via {@link #ammo} return the live value
     * (or -1 for the blaster, or 0 for a not-yet-picked-up
     * shotgun). A {@link HashMap} rather than an
     * {@link EnumMap} because {@link Weapon} is not an enum -
     * it is a class with public static final instances, the
     * shape the rest of the engine expects.
     */
    private final Map<Weapon, Integer> ammo = new HashMap<>();

    /**
     * The currently held weapon. Always one of the keys in
     * {@link #ammo}. Set by {@link #setCurrent}; the renderer
     * and the fire path read it.
     */
    private Weapon current = STARTER_WEAPON;

    /**
     * Creates an inventory that holds the blaster at infinite
     * ammo and nothing else. The default is what every player
     * starts with.
     */
    public Inventory()
    {
        ammo.put(STARTER_WEAPON, STARTER_WEAPON.ammoMax());
    }

    /**
     * Returns the weapon currently in the player's hand. The
     * value is one of the weapons the inventory holds, never
     * null.
     */
    public Weapon current()
    {
        return current;
    }

    /**
     * Switches the held weapon. The new weapon must be in the
     * inventory; calling with a weapon the player does not
     * carry is a programming error and the call is a no-op
     * rather than an exception, because the input layer does
     * not always know what the player has when a key is
     * pressed (a key event from before a pickup landed, for
     * instance).
     *
     * @param weapon the weapon to switch to; must not be null
     * @return true if the switch happened, false if the weapon
     *     is not in the inventory
     */
    public boolean setCurrent(final Weapon weapon)
    {
        if (!ammo.containsKey(weapon))
        {
            return false;
        }

        this.current = weapon;

        return true;
    }

    /**
     * Returns the live ammo count for the given weapon, or 0
     * if the weapon is not in the inventory, or -1 for the
     * infinite-ammo blaster.
     *
     * @param weapon the weapon to look up; must not be null
     * @return the ammo count, 0 if absent, -1 if infinite
     */
    public int ammo(final Weapon weapon)
    {
        final Integer value = ammo.get(weapon);

        if (value == null)
        {
            return 0;
        }

        return value;
    }

    /**
     * Returns whether the inventory holds the given weapon. The
     * blaster is always present; the shotgun and rocket
     * launcher are present only after a pickup.
     *
     * @param weapon the weapon to test; must not be null
     * @return true if the player has the weapon
     */
    public boolean has(final Weapon weapon)
    {
        return ammo.containsKey(weapon);
    }

    /**
     * Adds one pickup of the given weapon to the inventory. The
     * new ammo count is the weapon's {@link Weapon#ammoMax} for
     * a fresh pickup, or the current count + ammoMax when the
     * player already has the weapon (so a second shotgun pickup
     * gives four total, not two). The current weapon is not
     * changed.
     *
     * @param weapon the weapon to add; must not be null
     */
    public void add(final Weapon weapon)
    {
        final int existing = ammo.getOrDefault(weapon, 0);

        ammo.put(weapon, existing + weapon.ammoMax());
    }

    /**
     * Consumes one round of the given weapon from the inventory.
     * No-op for the blaster (infinite ammo) and for weapons the
     * player does not carry. The ammo count is clamped to 0.
     *
     * @param weapon the weapon that fired; must not be null
     */
    public void consume(final Weapon weapon)
    {
        if (weapon.ammoMax() < 0)
        {
            return;
        }

        final int existing = ammo.getOrDefault(weapon, 0);

        if (existing <= 0)
        {
            return;
        }

        ammo.put(weapon, existing - 1);
    }
}
