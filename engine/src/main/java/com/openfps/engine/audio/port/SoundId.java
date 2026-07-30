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
 * engine can make is small, every one of them is generated in code
 * ({@code audio/synth}), and an enum says so honestly: an adapter can bake
 * every value at startup and a typo cannot compile.</p>
 *
 * <h2>What the second value proved, and what it broke</h2>
 *
 * <p>One sound could not show whether this shape was right, and the second one
 * found a real fault the moment it existed — <b>not here, but one layer
 * out</b>. The enum did its job: {@code GdxAudioPort.preload} already looped over
 * {@code values()}, so a new id was warmed without anybody touching it, and
 * {@code fileNameFor} already had a {@code switch} so each id got its own staged
 * file. What did <i>not</i> exist was any way for the engine to say <b>what a
 * given id sounds like</b>. The adapter reached straight into
 * {@code BlasterSound} for every id it was handed, so {@link #BOT_WEAPON_FIRE}
 * would have loaded correctly, warmed correctly, played on cue — and played the
 * player's own weapon. Working audio and no way to tell.</p>
 *
 * <p>{@code SoundBank} is the seam that was missing. The engine now answers "what
 * does this id sound like" and an adapter answers only "how does this platform
 * play bytes", which is the division {@code I_AudioPort} claims to draw and
 * did not.</p>
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
    WEAPON_FIRE,

    /**
     * A bot's carbine firing — <b>incoming</b> fire.
     *
     * <p>Deliberately a different noise from {@link #WEAPON_FIRE} rather than the
     * same one at a lower volume, and for the same reason the bots carry a
     * different model and their tracers are a different colour: "something is
     * shooting at me" has to be identifiable <b>by ear alone</b>, without looking
     * away from whatever the player is already looking at. The player's blaster is
     * a bright falling tone; this is a dull noise crack. See {@code CarbineSound}
     * for how far apart they are made and why.</p>
     *
     * <p><b>Voice-limited by the caller, not by the port.</b> Seven bots on seven
     * independent cadences will ask for this far more often than one player can ask
     * for {@link #WEAPON_FIRE}, and an unrestrained stack of copies of one 120 ms
     * sample is not seven shots — it is one shot several times too loud.
     * {@code BotFireVoices} owns that gate, on the tic index, above the port. The
     * port stays a port.</p>
     */
    BOT_WEAPON_FIRE
}
