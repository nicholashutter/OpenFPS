/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * One weapon the player can carry and fire.
 *
 * <p>The 2026-08 pickup system. The shipped demo's player always fires
 * a blaster, and the simulation has a single hitscan path tuned to
 * the blaster's range, damage, and cadence. The area rules mode
 * adds two more weapons (shotgun, rocket launcher) that fire on
 * different patterns, and a player who walks over a pickup swaps
 * into them. A {@link Weapon} is the tag that drives both the
 * viewmodel and the fire path, so the rest of the engine does not
 * need to know which weapon a shot came from to render the bolt,
 * but the fire path does need to know whether to fire one ray or
 * a cone, and whether to spawn a projectile or a tracer.</p>
 *
 * <p>The constants are public so a test or a map builder can
 * reference them by name rather than by string id. The {@link #id}
 * is the wire form (what a spec would store) and is the
 * string the rest of the system logs.</p>
 *
 * <h2>What lives here, and what lives on a subclass</h2>
 *
 * <p>The blaster is a single hitscan: one ray from the player's eye
 * in the aim direction, damage falls off with range via the
 * hitscan's distance attenuation, and one bolt per trigger pull.
 * The shotgun is a cone of rays with infinite damage at close range
 * (so a single pellets-spam is a kill), and the rocket launcher is
 * a single projectile that explodes on impact (splash damage). The
 * cone and the projectile are not single hitscan calls, and they
 * are the reason a {@link Weapon} is not just a damage constant.</p>
 *
 * <p>For now the shipped behavior is "every weapon fires like the
 * blaster", and the shotgun's cone and the rocket's splash land
 * in subsequent passes. The seam is in place: a future
 * {@link Match#firePlayerShot} can dispatch on
 * {@code weapon.fireMode()} rather than re-reading damage numbers.</p>
 */
public final class Weapon
{
    /** The player's default weapon, infinite ammo, single hitscan. */
    public static final Weapon BLASTER = new Weapon(
        "blaster", "Blaster", FireMode.HITSCAN, 20, -1);

    /**
     * Hidden area-rules pickup. Cone of rays, infinite damage at
     * very close range, two shots before the next pickup. Spawns
     * in two to three locations per area-rules map, chosen to be
     * off the line of sight from the player's spawn.
     */
    public static final Weapon SHOTGUN = new Weapon(
        "shotgun", "Shotgun", FireMode.CONE, 0, 2);

    /**
     * One per area-rules map. Single projectile, splash damage on
     * impact, one shot before the next pickup (the rocket
     * launcher is a deliberate "use it or lose it" weapon).
     */
    public static final Weapon ROCKET_LAUNCHER = new Weapon(
        "rocket", "Rocket Launcher", FireMode.PROJECTILE, 200, 1);

    private final String id;
    private final String displayName;
    private final FireMode fireMode;
    private final int damage;
    private final int ammoMax;

    private Weapon(final String id, final String displayName, final FireMode fireMode,
        final int damage, final int ammoMax)
    {
        this.id = id;
        this.displayName = displayName;
        this.fireMode = fireMode;
        this.damage = damage;
        this.ammoMax = ammoMax;
    }

    /**
     * Returns the wire id (e.g. {@code "blaster"}, {@code "shotgun"}).
     * Stable across runs; what a {@code MapSpec} would store.
     */
    public String id()
    {
        return id;
    }

    /**
     * Returns the human-readable name (e.g. "Rocket Launcher").
     * What menus and HUD overlays would show.
     */
    public String displayName()
    {
        return displayName;
    }

    /**
     * Returns how this weapon fires. Drives the dispatch in
     * {@code Match.firePlayerShot} once the shotgun and rocket fire
     * paths land. {@link FireMode#HITSCAN} is the shipped default.
     */
    public FireMode fireMode()
    {
        return fireMode;
    }

    /**
     * Returns the damage one shot deals at the weapon's effective
     * range. The blaster uses this directly; the shotgun multiplies
     * by the per-pellet attenuation; the rocket applies this to
     * the center of the blast and less to the ring of the splash.
     */
    public int damage()
    {
        return damage;
    }

    /**
     * Returns the maximum ammo count for one pickup, or
     * {@code -1} for an infinite-ammo weapon. The blaster is
     * infinite; the shotgun is two; the rocket is one.
     */
    public int ammoMax()
    {
        return ammoMax;
    }

    /** Whether this weapon's ammo pool is refilled by a pickup. */
    public boolean hasLimitedAmmo()
    {
        return ammoMax > 0;
    }

    @Override
    public String toString()
    {
        return "Weapon{" + id + "}";
    }

    @Override
    public int hashCode()
    {
        return id.hashCode();
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof Weapon w))
        {
            return false;
        }

        return id.equals(w.id);
    }

    /**
     * How a {@link Weapon} fires. The shipped blaster is
     * {@link #HITSCAN}. The shotgun is {@link #CONE} and the
     * rocket launcher is {@link #PROJECTILE}, both of which the
     * 2026-08 follow-up commits add.
     */
    public enum FireMode
    {
        /**
         * A single ray from the eye in the aim direction, one
         * damage value, one bolt. The shipped blaster.
         */
        HITSCAN,

        /**
         * A cone of N rays, each with its own attenuation. The
         * shotgun, 2026-08 follow-up.
         */
        CONE,

        /**
         * A single projectile that flies until it hits, then
         * damages everything in a radius. The rocket launcher,
         * 2026-08 follow-up.
         */
        PROJECTILE,
    }
}
