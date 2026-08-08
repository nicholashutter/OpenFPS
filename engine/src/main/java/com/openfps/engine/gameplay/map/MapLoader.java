/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The seam between a map id and a {@link MapSpec}.
 *
 * <p>Pass 1 keeps maps in Java source ({@link Maps}), so {@code MapLoader}
 * delegates to {@link MapLibrary}. A future pass that wants to move map data
 * to .map text files (the more conventional data format, and the only one
 * that lets a player add a map without rebuilding the engine) plugs a
 * different backing store into this class without changing its callers.</p>
 *
 * <p>The class is final and non-instantiable; the entire API is the two
 * static methods.</p>
 */
public final class MapLoader
{
    private static final Logger LOG = LoggerFactory.getLogger(MapLoader.class);

    private MapLoader()
    {
        // utility class
    }

    /**
     * Loads the map with the given id, or returns null if no such map is
     * registered.
     *
     * <p>Returns null rather than throwing because the engine's
     * command-line {@code --map=id} path treats "unknown id" as a startup
     * error and wants a single check (the loader) and a single message
     * (the engine main) rather than a try/catch that has to surface the
     * same fact.</p>
     *
     * @param id the id to look up; must not be null
     * @return the map, or null if not registered
     * @throws IllegalArgumentException if {@code id} is null
     */
    public static MapSpec load(final String id)
    {
        Objects.requireNonNull(id, "id must not be null");
        final MapSpec spec = MapLibrary.get(id);
        if (spec == null)
        {
            LOG.warn("MapLoader: no map registered with id '{}' (known: {})", id,
                MapLibrary.ids());
        }
        return spec;
    }

    /**
     * Loads the map with the given id, falling back to the demo map if
     * the id is unknown. A null result is therefore impossible; the
     * fallback is the shipped {@code cornerstone} map, which is
     * guaranteed to be registered.
     *
     * @param id the id to look up; may be null, in which case the fallback
     *           is returned
     * @return the map, never null
     */
    public static MapSpec loadOrFallback(final String id)
    {
        if (id == null)
        {
            return MapLibrary.get("cornerstone");
        }
        final MapSpec spec = MapLibrary.get(id);
        if (spec == null)
        {
            return MapLibrary.get("cornerstone");
        }
        return spec;
    }
}
