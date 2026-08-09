/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import com.openfps.engine.gameplay.MatchMode;

import java.util.List;
import java.util.Objects;

/**
 * One map in the 16-map library: everything a {@link com.openfps.engine.gameplay.Match}
 * needs to know to start a round on it.
 *
 * <p>A {@code MapSpec} is the immutable record of a map's identity, layout and
 * mode-specific positions. It carries no rendering state, no live gameplay
 * state, no I/O. What it carries is the design: lanes, chokepoints, spawns,
 * waypoints, mode-specific markers (Hardpoint zones, Domination flags, CTF
 * bases) and the asset paths the renderer needs to build the
 * {@link com.openfps.engine.render.adapter.Scene}. A map is built once
 * (typically by {@link MapLibrary#registerDefaults()} at class-load time) and
 * then handed to {@code MapScene} for instantiation, and to {@code Match} for
 * the rules.</p>
 *
 * <p>The {@code id} is the map's stable identifier — the same string a
 * {@code --map=id} command-line argument carries. The {@code displayName} is
 * what the menu and the loading screen show. The {@code setting} is one of
 * the four {@link MapSetting} values, and {@code mode} is one of the four
 * multiplayer {@link MatchMode} values.</p>
 *
 * <p>Records were considered here and rejected: a record's {@code equals} is
 * field-by-field, but two {@code MapSpec}s with the same id but different
 * other fields are not equal and must not be considered equal — they are
 * different specs that share a name. The explicit {@link #equals} and
 * {@link #hashCode} use the id alone for the same reason, so a registry that
 * de-duplicates by id works as expected.</p>
 *
 * <p>Immutable, and therefore safe to share between threads.</p>
 */
public final class MapSpec
{
    /** Stable identifier; the value a {@code --map=id} CLI argument carries. */
    private final String id;

    /** Human-readable name; what menus and loading screens show. */
    private final String displayName;

    /** Visual setting; which Kenney kit the map's art comes from. */
    private final MapSetting setting;

    /** Multiplayer mode; which rule set the map runs. */
    private final MatchMode mode;

    /** Playable extent, in world units. */
    private final MapDimensions dimensions;

    /** The map's three lanes, in order (A, B, C). */
    private final List<Lane> lanes;

    /** Spawn placements, by team. */
    private final List<SpawnPoint> spawnPoints;

    /** Bot patrol waypoints. */
    private final List<Waypoint> botWaypoints;

    /** Mode-specific positions. Never null; TDM uses the singleton. */
    private final MapMarkers markers;

    /** Asset paths the renderer needs. */
    private final MapAssets assets;

    /**
     * Creates a {@code MapSpec} after validating the inputs.
     *
     * @param id           the stable id; must not be null or blank
     * @param displayName  the menu name; must not be null or blank
     * @param setting      the visual setting; must not be null
     * @param mode         the multiplayer mode; must not be null
     * @param dimensions   the playable extent; must not be null
     * @param lanes        the three lanes in order (A, B, C); must not be
     *                     null and must have exactly three entries
     * @param spawnPoints  the spawn placements; must not be null and must
     *                     contain at least one entry
     * @param botWaypoints the bot waypoints; must not be null (an empty
     *                     list is allowed)
     * @param markers      the mode-specific markers; must not be null
     * @param assets       the asset paths; must not be null
     * @throws IllegalArgumentException if any rule above is broken, or if
     *     {@code markers} is not the right subtype for {@code mode}
     */
    public MapSpec(final String id, final String displayName, final MapSetting setting,
        final MatchMode mode, final MapDimensions dimensions, final List<Lane> lanes,
        final List<SpawnPoint> spawnPoints, final List<Waypoint> botWaypoints,
        final MapMarkers markers, final MapAssets assets)
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("id must not be null or blank");
        }

        if (displayName == null || displayName.isBlank())
        {
            throw new IllegalArgumentException("displayName must not be null or blank");
        }

        if (setting == null)
        {
            throw new IllegalArgumentException("setting must not be null");
        }

        if (mode == null)
        {
            throw new IllegalArgumentException("mode must not be null");
        }

        if (dimensions == null)
        {
            throw new IllegalArgumentException("dimensions must not be null");
        }

        if (lanes == null)
        {
            throw new IllegalArgumentException("lanes must not be null");
        }

        if (lanes.size() != 3)
        {
            throw new IllegalArgumentException(
                "a map must have exactly three lanes, got " + lanes.size());
        }

        if (spawnPoints == null)
        {
            throw new IllegalArgumentException("spawnPoints must not be null");
        }

        if (spawnPoints.isEmpty())
        {
            throw new IllegalArgumentException("spawnPoints must not be empty");
        }

        if (botWaypoints == null)
        {
            throw new IllegalArgumentException("botWaypoints must not be null");
        }

        if (markers == null)
        {
            throw new IllegalArgumentException("markers must not be null");
        }

        if (assets == null)
        {
            throw new IllegalArgumentException("assets must not be null");
        }

        validateMarkersForMode(mode, markers);

        this.id = id.intern();

        this.displayName = displayName.intern();

        this.setting = setting;

        this.mode = mode;

        this.dimensions = dimensions;

        this.lanes = List.copyOf(lanes);

        this.spawnPoints = List.copyOf(spawnPoints);

        this.botWaypoints = List.copyOf(botWaypoints);

        this.markers = markers;

        this.assets = assets;
    }

    /**
     * Returns the map's stable id.
     *
     * @return the id, never null or blank
     */
    public String id()
    {
        return id;
    }

    /**
     * Returns the map's display name.
     *
     * @return the display name, never null or blank
     */
    public String displayName()
    {
        return displayName;
    }

    /**
     * Returns the map's visual setting.
     *
     * @return the setting, never null
     */
    public MapSetting setting()
    {
        return setting;
    }

    /**
     * Returns the map's multiplayer mode.
     *
     * @return the mode, never null
     */
    public MatchMode mode()
    {
        return mode;
    }

    /**
     * Returns the map's playable extent.
     *
     * @return the dimensions, never null
     */
    public MapDimensions dimensions()
    {
        return dimensions;
    }

    /**
     * Returns the map's three lanes in order.
     *
     * @return an immutable list of three lanes, never null
     */
    public List<Lane> lanes()
    {
        return lanes;
    }

    /**
     * Returns the map's spawn placements.
     *
     * @return an immutable list of spawns, never null or empty
     */
    public List<SpawnPoint> spawnPoints()
    {
        return spawnPoints;
    }

    /**
     * Returns the map's bot patrol waypoints.
     *
     * @return an immutable list of waypoints, never null (may be empty)
     */
    public List<Waypoint> botWaypoints()
    {
        return botWaypoints;
    }

    /**
     * Returns the map's mode-specific markers.
     *
     * @return the markers, never null
     */
    public MapMarkers markers()
    {
        return markers;
    }

    /**
     * Returns the map's asset paths.
     *
     * @return the assets, never null
     */
    public MapAssets assets()
    {
        return assets;
    }

    /**
     * Returns a debug rendering of the map.
     *
     * @return a debug string
     */
    @Override
    public String toString()
    {
        return "MapSpec{id=" + id + ", name=" + displayName + ", setting=" + setting
            + ", mode=" + mode + ", dimensions=" + dimensions
            + ", lanes=" + lanes.size() + ", spawns=" + spawnPoints.size()
            + ", waypoints=" + botWaypoints.size()
            + ", markers=" + markers.getClass().getSimpleName() + "}";
    }

    /**
     * Equals by {@code id} alone.
     *
     * <p>Two specs with the same id are the same map, even if other fields
     * differ — which is what a registry wants when it says "this id is
     * already taken".</p>
     *
     * @param other the object to compare to
     * @return true if {@code other} is a {@code MapSpec} with the same id
     */
    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof MapSpec otherSpec))
        {
            return false;
        }

        return id.equals(otherSpec.id);
    }

    /**
     * Hash code by {@code id} alone, for symmetry with {@link #equals}.
     *
     * @return the id's hash code
     */
    @Override
    public int hashCode()
    {
        return Objects.hashCode(id);
    }

    /**
     * Refuses a {@code MapSpec} whose {@code markers} subtype does not match
     * its {@code mode}. The check is the single load-bearing part of the
     * invariant {@code Match} relies on: every Hardpoint map has Hardpoint
     * markers, every CTF map has CTF markers, and so on.
     *
     * @param mode    the declared mode
     * @param markers the declared markers
     * @throws IllegalArgumentException if the markers subtype is wrong for
     *     the mode
     */
    private static void validateMarkersForMode(final MatchMode mode, final MapMarkers markers)
    {
        final boolean ok = switch (mode)
        {
            case TDM -> markers instanceof MapMarkers.TeamDeathmatch;
            case HARDPOINT -> markers instanceof MapMarkers.Hardpoint;
            case DOMINATION -> markers instanceof MapMarkers.Domination;
            case CTF -> markers instanceof MapMarkers.CaptureTheFlag;
            // SINGLE_PLAYER and MULTIPLAYER are not real modes and never
            // appear on a MapSpec; the enum's other entries are reserved
            // for the existing single-player / multiplayer distinction.
            case SINGLE_PLAYER, MULTIPLAYER -> false;
        };

        if (!ok)
        {
            throw new IllegalArgumentException("markers " + markers.getClass().getSimpleName()
                + " does not match mode " + mode);
        }
    }
}
