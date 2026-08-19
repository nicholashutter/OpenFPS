/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * What kind of match the player asked for from the menu.
 *
 * <p>The distinction the engine cares about is narrow and worth stating exactly,
 * because it is smaller than it sounds: <b>who supplies the other bodies in the
 * room</b>. Everything else — the map, the weapon, the hitscan, the outline
 * pass, the crosshair — is identical, and deliberately so. A bot and a remote
 * player are both an entity id with a hitbox that moves, and the shooting code
 * cannot tell them apart.</p>
 *
 * <p>That equivalence is the point rather than a happy accident. It means the
 * single-player match is a real test of the multiplayer code path: if a shot
 * connects with a bot it will connect with a peer, because the same
 * {@code Hitscan} resolved both against the same kind of {@link Target}.</p>
 *
 * <h2>Pass 1 — the four real multiplayer modes</h2>
 *
 * <p>Four new entries — {@link #TDM}, {@link #HARDPOINT}, {@link #DOMINATION}
 * and {@link #CTF} — are the rule sets the 16-map library ships. They are
 * <b>siblings</b> of {@link #SINGLE_PLAYER} and {@link #MULTIPLAYER}, not
 * replacements: the original two stay because the existing code references
 * them and removing them would orphan every test and every launcher that
 * distinguishes "is this a net match?" from "is this a real-game-mode
 * match?". The new modes are what a {@link com.openfps.engine.gameplay.map.MapSpec}
 * carries; the original two are the high-level question of "who supplies
 * the bodies".</p>
 */
public enum MatchMode
{
    /**
     * One human against {@link Match#DEFAULT_BOT_COUNT} bots, all simulated
     * locally.
     *
     * <p>No sockets are opened. The match is entirely deterministic from the tic
     * index and the player's inputs, which makes it the mode to reproduce a bug
     * in.</p>
     */
    SINGLE_PLAYER,

    /**
     * Peers on the network supply the other bodies.
     *
     * <p>Bots are not spawned — the room fills with whoever connects. See
     * {@code net/README.md} for the transport and the lockstep model.</p>
     */
    MULTIPLAYER,

    /**
     * Team Deathmatch. Respawn on death; score per kill; the round ends when
     * one team reaches the kill limit or the time limit.
     */
    TDM,

    /**
     * Hardpoint. Capture and hold rotating zones; score per second held; the
     * round ends when one team reaches the score limit or the time limit.
     */
    HARDPOINT,

    /**
     * Domination. Three flags (A, B, C); capture to score; the round ends when
     * one team reaches the score limit or the time limit.
     */
    DOMINATION,

    /**
     * Capture The Flag. Two bases; pick up the enemy flag, return it to your
     * own base; score per capture; the round ends at the capture limit or the
     * time limit.
     */
    CTF,

    /**
     * Area Rules. 2026-08: the pickup-sandbox mode. Players start
     * with the blaster and pick up weapons (shotgun, rocket
     * launcher) by walking over them; the spec declares the
     * pickup positions and the mode carries no further
     * structure. The mode is a container for the rules - the
     * scoring and win condition live in
     * {@link com.openfps.engine.gameplay.map.MapMarkers.AreaRules}.
     */
    AREA_RULES;

    /**
     * Returns whether this is one of the four real multiplayer rule sets.
     *
     * <p>{@link #SINGLE_PLAYER} and {@link #MULTIPLAYER} return false; they
     * are the high-level "who supplies the bodies" question, and the answer
     * "yes" is what {@code Match} would need to dispatch per-tic mode
     * updates. A null result from this method means the existing per-tic
     * path is fine and no mode dispatch is required.</p>
     *
     * @return true for {@link #TDM}, {@link #HARDPOINT}, {@link #DOMINATION},
     *     {@link #CTF}; false for the legacy two
     */
    public boolean isRuleSet()
    {
        return switch (this)
        {
            case TDM, HARDPOINT, DOMINATION, CTF, AREA_RULES -> true;
            case SINGLE_PLAYER, MULTIPLAYER -> false;
        };
    }
}
