/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The 16-map library: a thread-safe registry of {@link MapSpec}s indexed by
 * id, with the shipped maps registered at class-load time.
 *
 * <p>The 16 maps the user is building toward are a 4-by-4 grid: four settings
 * (Urban Warzone, Industrial Complex, Desert Ravine, Arctic Station) crossed
 * with four modes (TDM, Hardpoint, Domination, CTF). This class is the
 * container for them; the first map, {@code cornerstone}, is registered here
 * in Pass 1 and the rest join in Passes 2 through 4.</p>
 *
 * <h2>How a map is added</h2>
 *
 * <p>A map is added by calling {@link #register(MapSpec)} with a constructed
 * {@code MapSpec}. {@link #registerDefaults()} runs at class-load time and
 * registers the shipped maps; tests call {@code register} directly to add
 * fixtures and then {@link #unregisterAll()} to tear down. There is no file
 * I/O — the data is in Java source because {@code AGENTS.md} says "match the
 * project's conventions" and the project does not currently ship a parser
 * for any data format. A future {@code MapLoader} that reads .map text files
 * is the path off this decision; until then {@code register(MapSpec)} is
 * the entire API surface.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Registration is synchronized; lookups are not. The library is meant to
 * be populated at class-load time and read from many threads thereafter —
 * every {@code Match} it spawns looks up the same handful of maps. Lookup
 * is a single read on a volatile field followed by an immutable-map
 * read, which is safe to do without a lock.</p>
 */
public final class MapLibrary
{
    private static final Logger LOG = LoggerFactory.getLogger(MapLibrary.class);

    /**
     * The currently-registered maps, by id. The field is volatile so the
     * reference to the map is published safely across threads; the maps
     * themselves are immutable. MUTABLE: replaced wholesale on
     * {@link #register} / {@link #unregisterAll} rather than mutated in
     * place, so a reader never sees a half-updated registry.
     */
    private static volatile Map<String, MapSpec> registry = Collections.emptyMap();

    private MapLibrary()
    {
        // utility class
    }

    /**
     * Registers a map. If a map with the same id is already registered, the
     * new one replaces it — which is the right answer for a re-registration
     * and the wrong answer for a name collision, and the second is the
     * reason {@code registerDefaults} uses distinct ids.
     *
     * @param spec the map to register; must not be null
     * @throws IllegalArgumentException if {@code spec} is null
     */
    public static void register(final MapSpec spec)
    {
        if (spec == null)
        {
            throw new IllegalArgumentException("spec must not be null");
        }

        synchronized (MapLibrary.class)
        {
            final Map<String, MapSpec> next = new LinkedHashMap<>(registry);

            final MapSpec previous = next.put(spec.id(), spec);

            registry = Collections.unmodifiableMap(next);

            if (previous == null)
            {
                LOG.info("MapLibrary: registered {} ({} x {}, {}, {})", spec.id(),
                    spec.dimensions().width(), spec.dimensions().depth(), spec.setting(),
                    spec.mode());
            }
            else
            {
                LOG.info("MapLibrary: replaced {} (was {} tics of {}, now {})", spec.id(),
                    previous.mode(), previous.setting(), spec.mode());
            }
        }
    }

    /**
     * Returns the map with the given id, or null if no such map is registered.
     *
     * @param id the id to look up; must not be null
     * @return the map, or null
     * @throws IllegalArgumentException if {@code id} is null
     */
    public static MapSpec get(final String id)
    {
        if (id == null)
        {
            throw new IllegalArgumentException("id must not be null");
        }

        return registry.get(id);
    }

    /**
     * Returns whether a map with the given id is registered.
     *
     * @param id the id to look up; must not be null
     * @return true if the map is registered, false otherwise
     */
    public static boolean has(final String id)
    {
        if (id == null)
        {
            throw new IllegalArgumentException("id must not be null");
        }

        return registry.containsKey(id);
    }

    /**
     * Returns the set of registered map ids.
     *
     * @return an immutable view of the registered ids
     */
    public static Set<String> ids()
    {
        return registry.keySet();
    }

    /**
     * Returns the number of registered maps.
     *
     * @return the count
     */
    public static int size()
    {
        return registry.size();
    }

    /**
     * Registers the shipped maps. Called once at class-load time.
     *
     * <p>Idempotent: a second call re-registers the same maps with no
     * observable change, which is what makes the static initializer
     * order-insensitive against the test fixtures.</p>
     */
    public static void registerDefaults()
    {
        register(Maps.cornerstone());

        register(Maps.overpass());

        register(Maps.tripoint());

        register(Maps.extraction());

        register(Maps.refinery());

        register(Maps.crossroads());

        register(Maps.arcticStation());

        // Pass 5 — three new Hardpoint maps: foundry (Industrial
        // Complex), mesa (Desert Ravine), arctic-hp (Arctic Station).
        register(Maps.foundry());

        register(Maps.mesa());

        register(Maps.arcticHp());

        // Pass 6 — three new Domination maps: pipeline (Industrial
        // Complex), sandbar (Desert Ravine), arctic-dom / Frostline
        // (Arctic Station). The Domination mode logic is shipped from
        // Pass 3; these three maps add the level .ofm and the spec
        // factory methods.
        register(Maps.pipeline());

        register(Maps.sandbar());

        register(Maps.arcticDom());

        // Pass 7 — three new CTF maps: storage (Industrial Complex),
        // stronghold (Desert Ravine), coldfront (Arctic Station).
        // The CTF mode logic is shipped from Pass 4; these three maps
        // add the level .ofm and the spec factory methods. The grid
        // is now complete: 4 settings x 4 modes = 16 maps.
        register(Maps.storage());

        register(Maps.stronghold());

        register(Maps.coldfront());

        // 2026-08: the area-rules sandbox. The seventeenth
        // shipped map, sitting outside the 4x4 setting/mode
        // grid because the mode is not a real rule set, and
        // not paired with three sibling maps because the
        // mode carries no setting-specific structure. One
        // map, registered on its own, is the right shape
        // for the "kill the bots with the pickups the map
        // gives you" mode.
        register(Maps.areaRules());
    }

    /**
     * Unregisters every map. Intended for tests; production code never
     * needs to call this.
     */
    public static void unregisterAll()
    {
        synchronized (MapLibrary.class)
        {
            registry = Collections.emptyMap();
        }
    }

    static
    {
        registerDefaults();
    }
}
