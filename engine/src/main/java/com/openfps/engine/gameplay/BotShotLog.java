/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * Which bots fired on this tic, and along exactly which rays.
 *
 * <h2>What this is for</h2>
 *
 * <p>Return fire was invisible. {@link Match} decided it, resolved it, and took
 * the player's health down by twenty, and the only trace of any of it was a log
 * line — so a player being shot at could not tell where it came from, or that it
 * was happening at all, or do anything about it. Making it visible needs the
 * <b>ray</b>, not the outcome: the shot is scattered by {@link BotRng} before it
 * is traced, and a tracer drawn down the unscattered heading would show a bolt
 * arriving dead-on while the damage said the shot went wide. A near-miss the
 * player can see is the whole point, and a near-miss is a property of the
 * scattered ray.</p>
 *
 * <h2>Why a per-tic buffer rather than an immutable snapshot</h2>
 *
 * <p>{@link MatchStatus} and {@link MatchSummary} are immutable copies because
 * they <b>cross a thread</b>: the HUD is drawn by the platform's render thread
 * while the game loop thread ticks the match, so reading eight mutable fields
 * across that boundary would give a frame that disagreed with itself. Nothing
 * here crosses anything. {@code DemoGameplayPort.tick} calls {@code Match.tick}
 * and then spawns the effects a few lines later, in the same method, on the same
 * thread, inside the same tic lock. The consumer is the caller.</p>
 *
 * <p>So the copy an immutable snapshot would buy is a copy nobody needs, and it
 * would be paid seven times a shot on the tic path — which {@code STYLE.md}
 * § 13.4 forbids. This allocates <b>once</b>, when the match is built, and every
 * tic afterwards writes into the same primitive arrays and resets a count. A tic
 * on which nobody fires costs one integer store.</p>
 *
 * <p><b>The window is one tic wide and that is deliberate.</b> {@link #clear()}
 * runs at the top of every {@link Match#tick}, so a caller that does not read the
 * log before the next tic loses it. That is the correct lifetime for a per-tic
 * event and it is the property that keeps this from becoming a queue with a
 * backlog policy nobody wants to own: an effect that was not spawned on the tic
 * its shot happened is an effect in the wrong place.</p>
 *
 * <h2>Mutability and threading</h2>
 *
 * <p>Mutable, not thread-safe, and simulation-adjacent rather than simulation:
 * nothing reads it back, so what a peer does with it cannot desync anything. It
 * belongs to whichever thread owns the {@link Match}.</p>
 */
public final class BotShotLog
{
    /** Coordinates per position or direction triple. */
    private static final int AXES = 3;

    /** The most shots one tic can hold. */
    private final int capacity;

    /** Which bot fired each shot. MUTABLE: written up to {@link #count}. */
    private final int[] shooterId;

    /** Where each shot started, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] origin;

    /** Which way each shot went, unit length, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] direction;

    /** How far down the ray the shot was aimed, per slot. MUTABLE. */
    private final float[] range;

    /** Shots recorded this tic. MUTABLE: reset by {@link #clear()}. */
    private int count;

    /**
     * Creates a log that can hold one shot per bot.
     *
     * <p>One per bot is not a guess, it is the arithmetic:
     * {@link Bot#wantsToFire} is called exactly once per bot per tic and books a
     * cooldown when it comes up, so a bot cannot fire twice on one tic and the
     * whole room cannot produce more entries than it has members.
     * {@link #record} therefore has no failure path — see its Javadoc for what
     * happens if that ever stops being true.</p>
     *
     * @param botCount how many bots the match holds; must not be negative
     * @throws IllegalArgumentException if {@code botCount} is negative
     */
    public BotShotLog(final int botCount)
    {
        if (botCount < 0)
        {
            throw new IllegalArgumentException("botCount must not be negative, got " + botCount);
        }
        this.capacity = botCount;
        this.shooterId = new int[botCount];
        this.origin = new float[botCount * AXES];
        this.direction = new float[botCount * AXES];
        this.range = new float[botCount];
    }

    /**
     * Forgets every shot recorded, so the log describes one tic and no more.
     *
     * <p>Called at the top of {@link Match#tick}. The arrays are not wiped — only
     * the count moves — because everything above the count is unreadable by
     * contract and clearing it would be sixty pointless array fills a second.</p>
     */
    public void clear()
    {
        this.count = 0;
    }

    /**
     * Records one shot: who fired, from where, which way, and how far off the
     * thing it was aimed at was.
     *
     * <p>The direction is the ray {@link Hitscan} was actually given — after
     * {@link BotRng} has scattered it — because that is the only direction a
     * tracer may be drawn along without lying about what happened.</p>
     *
     * <p><b>{@code rangeUnits} is here so a tracer can start at the muzzle
     * without leaving the ray.</b> The simulation fires from a bot's eye, at the
     * centre of its body; the visible bolt has to leave the end of a barrel, which
     * is some fourteen world units away from that. Aim the bolt along the same
     * direction from the offset origin and it runs <i>parallel</i> to the shot,
     * fourteen units to one side, for as far as it flies — which at the player's
     * distance is most of a player radius and would make a hit look like a miss.
     * Given the range, the effect layer can aim from the muzzle at the point the
     * real ray reaches, so the two converge exactly where it matters and the
     * bolt still comes out of the gun.</p>
     *
     * <p>Silently drops a shot beyond {@link #capacity()} rather than throwing.
     * That is unreachable — see the constructor — and the choice of what to do
     * about the unreachable case is deliberate: this is cosmetic data on the tic
     * path, and an exception here would take a running match down over a missing
     * tracer. A dropped bolt nobody can be looking for is the cheaper failure.</p>
     *
     * @param entityId the bot that fired
     * @param originX the ray origin, world x — the shooter's eye
     * @param originY the ray origin, world y
     * @param originZ the ray origin, world z
     * @param dirX the scattered ray direction, world x; unit length
     * @param dirY the scattered ray direction, world y
     * @param dirZ the scattered ray direction, world z
     * @param rangeUnits how far along the ray the shot was aimed; must be
     *     positive to be useful, and is stored as given either way
     */
    public void record(final int entityId, final float originX, final float originY,
        final float originZ, final float dirX, final float dirY, final float dirZ,
        final float rangeUnits)
    {
        if (count >= capacity)
        {
            return;
        }
        final int at = count * AXES;
        shooterId[count] = entityId;
        origin[at] = originX;
        origin[at + 1] = originY;
        origin[at + 2] = originZ;
        direction[at] = dirX;
        direction[at + 1] = dirY;
        direction[at + 2] = dirZ;
        range[count] = rangeUnits;
        this.count = count + 1;
    }

    /** Returns how many shots were recorded this tic. */
    public int count()
    {
        return count;
    }

    /** Returns the most shots one tic can hold — one per bot. */
    public int capacity()
    {
        return capacity;
    }

    /**
     * Returns which bot fired one of this tic's shots.
     *
     * @param slot the shot, in {@code [0, count())}
     * @return the shooter's entity id
     */
    public int shooterId(final int slot)
    {
        return shooterId[check(slot)];
    }

    /**
     * Returns one shot's ray origin, world x.
     *
     * @param slot the shot, in {@code [0, count())}
     * @return the origin's x
     */
    public float originX(final int slot)
    {
        return origin[check(slot) * AXES];
    }

    /**
     * Returns one shot's ray origin, world y.
     *
     * @param slot the shot, in {@code [0, count())}
     * @return the origin's y
     */
    public float originY(final int slot)
    {
        return origin[check(slot) * AXES + 1];
    }

    /**
     * Returns one shot's ray origin, world z.
     *
     * @param slot the shot, in {@code [0, count())}
     * @return the origin's z
     */
    public float originZ(final int slot)
    {
        return origin[check(slot) * AXES + 2];
    }

    /**
     * Returns one shot's scattered direction, world x.
     *
     * @param slot the shot, in {@code [0, count())}
     * @return the direction's x
     */
    public float directionX(final int slot)
    {
        return direction[check(slot) * AXES];
    }

    /**
     * Returns one shot's scattered direction, world y.
     *
     * @param slot the shot, in {@code [0, count())}
     * @return the direction's y
     */
    public float directionY(final int slot)
    {
        return direction[check(slot) * AXES + 1];
    }

    /**
     * Returns one shot's scattered direction, world z.
     *
     * @param slot the shot, in {@code [0, count())}
     * @return the direction's z
     */
    public float directionZ(final int slot)
    {
        return direction[check(slot) * AXES + 2];
    }

    /**
     * Returns how far along its ray one shot was aimed, in world units.
     *
     * @param slot the shot, in {@code [0, count())}
     * @return the range the shot was taken at
     */
    public float rangeUnits(final int slot)
    {
        return range[check(slot)];
    }

    // Rejects a read above the count. Throwing here, unlike in record, because
    // this one is a caller bug rather than a capacity condition: everything above
    // the count is last tic's data, and quietly returning it would put a tracer
    // at a stale muzzle with nothing to show that anything had gone wrong.
    private int check(final int slot)
    {
        if (slot < 0 || slot >= count)
        {
            throw new IndexOutOfBoundsException(
                "shot " + slot + " of " + count + " recorded this tic");
        }
        return slot;
    }

    /** Returns a debug rendering of this tic's shots. */
    @Override
    public String toString()
    {
        return "BotShotLog{" + count + "/" + capacity + " shots this tic}";
    }
}
