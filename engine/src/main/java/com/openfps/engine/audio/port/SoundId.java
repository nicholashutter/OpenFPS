/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.port;

/**
 * S_ The closed set of sounds the engine knows how to ask for.
 *
 * <p>An enum rather than a {@code String} name, and that is the whole design
 * decision in this file. A string key means a catalogue, a catalogue means a
 * lookup that can miss, and a lookup that can miss means every caller has to
 * decide what a missing sound does — at which point the engine is carrying an
 * asset-management question it has no assets to justify. The set of sounds this
 * engine can make is currently one, it is generated in code
 * ({@code audio/synth}), and an enum says so honestly: an adapter can bake
 * every value at startup and a typo cannot compile.</p>
 *
 * <p>This is the same reason {@code InputState} lives in {@code hal/port/}
 * rather than beside an adapter — it is the value that crosses the port, and it
 * has no platform in it.</p>
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>It is not a handle table, and it carries no position, no volume and no
 * pitch. Those belong to a mixer with 3D sources in it, and there is no such
 * thing here: {@code audio/README.md} keeps the distance-attenuation and
 * panning maths as a documented future extension rather than as unbuilt code
 * with an interface already shaped around it. When a real sound bank arrives —
 * many sounds, loaded from a resource package — the honest move is to add a
 * second port method taking a handle, not to widen this enum to hundreds of
 * constants.</p>
 */
public enum SoundId
{
    /**
     * The player's blaster firing.
     *
     * <p>Played once per shot that is actually taken — after the rate-of-fire
     * cooldown, and regardless of whether the hitscan connected. A miss makes
     * exactly as much noise as a hit, for the same reason the tracer is spawned
     * unconditionally: a sound that only played on a hit would tell the player
     * something the simulation has not told them yet.</p>
     */
    WEAPON_FIRE
}
