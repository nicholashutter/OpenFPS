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

    /**
     * The splash radius for a rocket impact, in world units.
     * A bot within this radius of the impact point takes
     * splash damage, with linear falloff from the impact
     * point out to the radius. 320 world units is 20 m,
     * which is "the room you are in" rather than "the
     * map" - a rocket into a doorway is a problem for
     * everyone in the corridor, not a problem for the
     * bot on the other side of the wall.
     */
    public static final float ROCKET_SPLASH_RADIUS_UNITS = 320.0f;

    /**
     * The splash damage at the impact point (the centre of
     * the blast). 200 is well past any bot's health
     * (Bot.MAX_HEALTH = 100), so a bot at the impact point
     * dies; a bot halfway out from the centre takes 100,
     * also lethal; a bot at the radius takes 0 (the
     * falloff is linear). The falloff rule is in
     * {@code Match.fireRocket} and is the one that makes
     * "shoot a rocket at a doorway the bot is behind"
     * a good idea and "shoot a rocket at your own feet"
     * a bad one.
     */
    public static final int ROCKET_SPLASH_DAMAGE_CENTER = 200;

    /**
     * How many pellets one shotgun blast spreads across.
     * Five reads as a shotgun (a single hitscan reads as a
     * rifle), and a 5-pellet fan covers a useful cross-section
     * at point-blank without being noisy.
     */
    public static final int SHOTGUN_PELLETS = 5;

    /**
     * Half-width of the shotgun's pellet fan, in radians. A
     * 0.15 rad (~8.6 degrees) total spread is what a sawed-off
     * shotgun has at the muzzle; a tighter choke would make the
     * shotgun feel like a rifle, and a wider choke would make
     * the pellets miss anything but the broad side of a barn.
     */
    public static final float SHOTGUN_SPREAD_RADIANS = 0.15f;

    /**
     * Maximum hit distance, in world units, at which a
     * shotgun pellet does its close-range damage. A pellet
     * within this range is a one-shot kill against a
     * full-health bot; beyond it, the pellet does the
     * far-range damage and may take several shots to drop
     * the same target. 256 world units is 16 m, which is
     * "across a small room" rather than "across the map",
     * so the close-range rule is what a player standing
     * in the same tile as a bot experiences.
     */
    public static final float SHOTGUN_CLOSE_RANGE_UNITS = 256.0f;

    /**
     * The damage a close-range pellet does. Pinned to a
     * number higher than any bot's health, so a single
     * pellet kills in one hit. {@link #Bot#MAX_HEALTH}
     * is 100 and the blaster does {@code PLAYER_SHOT_DAMAGE
     * = 34}, so 1000 is well past both.
     */
    public static final int SHOTGUN_CLOSE_DAMAGE = 1000;

    /**
     * The damage a long-range pellet does. Half the blaster's
     * shot damage, because the long-range pellet is one of
     * seven and the bot's health is the same as the blaster
     * would have hit. A player who lands most of a
     * point-blank blast gets the close-range number; a
     * player who lands one of seven pellets at the back of
     * the room gets this. The damage split is the one that
     * makes "aim with the crosshair at point-blank" a kill
     * and "aim from across the room" a tickle.
     */
    public static final int SHOTGUN_FAR_DAMAGE = 17;

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
