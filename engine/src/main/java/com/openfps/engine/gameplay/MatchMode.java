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
    MULTIPLAYER;

    /**
     * Returns whether this mode fills the room with locally simulated bots.
     *
     * @return true only for {@link #SINGLE_PLAYER}
     */
    public boolean spawnsBots()
    {
        return this == SINGLE_PLAYER;
    }

    /**
     * Returns whether this mode needs a socket.
     *
     * @return true only for {@link #MULTIPLAYER}
     */
    public boolean needsNetwork()
    {
        return this == MULTIPLAYER;
    }
}
