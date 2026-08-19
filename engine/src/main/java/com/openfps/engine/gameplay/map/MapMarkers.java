/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import java.util.List;

/**
 * Mode-specific positions a map needs beyond its lanes, spawns and waypoints.
 *
 * <p>A {@link MapSpec} carries one of these depending on its mode. TDM has
 * no extra markers; Hardpoint declares its three zones; Domination declares
 * its three flags; CTF declares its two bases. The four cases are modelled
 * as a sealed interface so the match layer can pattern-match on the marker
 * type with no downcast and the compiler can prove every case is handled.</p>
 *
 * <p>Sealed, and therefore closed. A future mode (Search and Destroy, Free
 * For All) adds a new permitted subtype; a future map does not get to
 * invent a fifth marker type without breaking the seal.</p>
 */
public sealed interface MapMarkers
    permits MapMarkers.TeamDeathmatch, MapMarkers.Hardpoint, MapMarkers.Domination,
        MapMarkers.CaptureTheFlag, MapMarkers.AreaRules
{
    /**
     * A TDM map carries no mode-specific markers. The marker exists so a
     * {@link MapSpec} for any mode has a non-null {@code markers} field and
     * the match layer never has to null-check it.
     */
    final class TeamDeathmatch implements MapMarkers
    {
        /** The one and only instance; TDM markers are mode-wide identical. */
        public static final TeamDeathmatch INSTANCE = new TeamDeathmatch();

        private TeamDeathmatch()
        {
        }
    }

    /**
     * The three capture zones of a Hardpoint map, plus the rotation
     * period and the score-per-second.
     *
     * <p>One zone is active at a time. The active zone rotates every
     * {@code rotationTics} tics; a team standing in the active zone
     * earns {@code scorePerTick} points per tic. {@code rotationTics} is
     * the period in tics (never milliseconds) so two peers running at
     * the same {@link com.openfps.engine.core.FrameRate} see the rotation
     * on the same tic.</p>
     *
     * @param zones          the three zones, in activation order; must not
     *                       be null and must have exactly three entries
     * @param rotationTics   tics between zone rotations; must be positive
     * @param scorePerTick   score awarded per tic while a team holds the
     *                       active zone; must be positive
     */
    record Hardpoint(List<HardpointZone> zones, int rotationTics, int scorePerTick)
        implements MapMarkers
    {
        /**
         * Creates a {@code Hardpoint} after validating the inputs.
         *
         * @throws IllegalArgumentException if the inputs are out of range
         */
        public Hardpoint
        {
            if (zones == null)
            {
                throw new IllegalArgumentException("zones must not be null");
            }

            if (zones.size() != 3)
            {
                throw new IllegalArgumentException(
                    "Hardpoint requires exactly three zones, got " + zones.size());
            }

            if (rotationTics <= 0)
            {
                throw new IllegalArgumentException(
                    "rotationTics must be positive, got " + rotationTics);
            }

            if (scorePerTick <= 0)
            {
                throw new IllegalArgumentException(
                    "scorePerTick must be positive, got " + scorePerTick);
            }

            zones = List.copyOf(zones);
        }
    }

    /**
     * The three flags of a Domination map, with the team currently holding
     * each. The match layer updates {@code owner} as flags are captured; the
     * map spec holds the neutral initial state.
     *
     * @param flags the three flags, named A, B, C; must not be null and must
     *              have exactly three entries
     */
    record Domination(List<Flag> flags) implements MapMarkers
    {
        /**
         * Creates a {@code Domination} after validating the inputs.
         *
         * @throws IllegalArgumentException if the inputs are out of range
         */
        public Domination
        {
            if (flags == null)
            {
                throw new IllegalArgumentException("flags must not be null");
            }

            if (flags.size() != 3)
            {
                throw new IllegalArgumentException(
                    "Domination requires exactly three flags, got " + flags.size());
            }

            flags = List.copyOf(flags);
        }
    }

    /**
     * The two bases and their flag routes for a Capture The Flag map.
     *
     * <p>Each base declares where the flag sits, where it returns when
     * dropped, and which team defends it. A capture by a player of their
     * own flag at the enemy base scores a point for the capturing team.</p>
     *
     * @param redBase  the red team's base; must not be null
     * @param blueBase the blue team's base; must not be null
     */
    record CaptureTheFlag(Base redBase, Base blueBase) implements MapMarkers
    {
        /**
         * Creates a {@code CaptureTheFlag} after validating the inputs.
         *
         * @throws IllegalArgumentException if either base is null
         */
        public CaptureTheFlag
        {
            if (redBase == null)
            {
                throw new IllegalArgumentException("redBase must not be null");
            }

            if (blueBase == null)
            {
                throw new IllegalArgumentException("blueBase must not be null");
            }
        }
    }

    /**
     * One Hardpoint capture zone, with its world position and radius.
     *
     * @param id     a stable identifier unique within the map; must not be
     *               null or blank
     * @param callout the human-readable name; must not be null or blank
     * @param x      world x
     * @param z      world z
     * @param radius capture radius, in world units; must be positive
     */
    record HardpointZone(String id, String callout, float x, float z, float radius)
    {
        /**
         * Creates a {@code HardpointZone} after validating the inputs.
         *
         * @throws IllegalArgumentException if any rule above is broken
         */
        public HardpointZone
        {
            if (id == null || id.isBlank())
            {
                throw new IllegalArgumentException("id must not be null or blank");
            }

            if (callout == null || callout.isBlank())
            {
                throw new IllegalArgumentException("callout must not be null or blank");
            }

            if (!(radius > 0.0f))
            {
                throw new IllegalArgumentException("radius must be positive, got " + radius);
            }

            id = id.intern();

            callout = callout.intern();
        }
    }

    /**
     * One Domination flag, with its world position and capture radius.
     *
     * @param id     a stable identifier unique within the map (conventionally
     *               {@code "flag_a"}, {@code "flag_b"}, {@code "flag_c"});
     *               must not be null or blank
     * @param callout the human-readable name; must not be null or blank
     * @param x      world x
     * @param z      world z
     * @param radius capture radius, in world units; must be positive
     */
    record Flag(String id, String callout, float x, float z, float radius)
    {
        /**
         * Creates a {@code Flag} after validating the inputs.
         *
         * @throws IllegalArgumentException if any rule above is broken
         */
        public Flag
        {
            if (id == null || id.isBlank())
            {
                throw new IllegalArgumentException("id must not be null or blank");
            }

            if (callout == null || callout.isBlank())
            {
                throw new IllegalArgumentException("callout must not be null or blank");
            }

            if (!(radius > 0.0f))
            {
                throw new IllegalArgumentException("radius must be positive, got " + radius);
            }

            id = id.intern();

            callout = callout.intern();
        }
    }

    /**
     * One CTF base — the flag's home position and the capture point for the
     * enemy flag.
     *
     * <p>The {@code flagX}/{@code flagZ} pair is where the team's flag sits
     * at spawn and where it returns after being dropped. The
     * {@code captureX}/{@code captureZ} pair is the spot a player carrying
     * the enemy flag must touch to score — usually in front of their own
     * flag, but the spec lets a map place them independently.</p>
     *
     * @param team       which team defends this base; must not be null
     * @param flagX      world x of the flag
     * @param flagZ      world z of the flag
     * @param captureX   world x of the capture point
     * @param captureZ   world z of the capture point
     * @param radius     radius at which a player triggers a capture or pickup;
     *                   must be positive
     */
    record Base(Team team, float flagX, float flagZ, float captureX, float captureZ, float radius)
    {
        /**
         * Creates a {@code Base} after validating the inputs.
         *
         * @throws IllegalArgumentException if {@code team} is null or
         *     {@code radius} is not positive
         */
        public Base
        {
            if (team == null)
            {
                throw new IllegalArgumentException("team must not be null");
            }

            if (!(radius > 0.0f))
            {
                throw new IllegalArgumentException("radius must be positive, got " + radius);
            }
        }
    }

    /**
     * 2026-08: the markers for an area-rules map. Area Rules
     * carries no zone, flag, or base - the mode is the
     * container for the pickup rules, and the markers are
     * the "no extra structure" singleton (the same shape
     * TDM uses). The spec carries the pickup positions in
     * its {@code pickups()} list, and the match layer
     * reads those for the weapon-spawn lifecycle.
     *
     * <p>The class is the marker type for the sealed
     * interface rather than a record because the singleton
     * pattern (one canonical instance per JVM) is the
     * same shape the TDM marker uses, and reusing the
     * shape keeps the match layer's pattern-match clean.</p>
     */
    final class AreaRules implements MapMarkers
    {
        /** The one and only instance; area-rules markers are mode-wide identical. */
        public static final AreaRules INSTANCE = new AreaRules();

        private AreaRules()
        {
        }
    }
}
