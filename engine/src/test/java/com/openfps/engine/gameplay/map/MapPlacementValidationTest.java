/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every shipped map's bot waypoints and spawn
 * placements sit in positions a Kenney-kit-equipped build can
 * actually draw — inside the playable area, clear of the four
 * kit columns at the quarter positions, and clear of the six
 * perimeter crates.
 *
 * <p>The kit's playable area for a 320x320 spec is x in
 * [-153.6, +153.6], z in [-153.6, +153.6] (the perimeter walls'
 * inner face is at half-room minus wall-half-thickness =
 * 160 - 6.4). The kit's four columns are at (±80, 0, ±80) and
 * extend 64x64 (model 1x1, scale 64), so each column occupies a
 * 64x64 footprint centred on its position. The kit's six crates
 * sit on the inside of each wall, centred on (±80, ±134.4) and
 * (±134.4, ±80) with the same 64x64 footprint.</p>
 *
 * <p>A waypoint or spawn inside any of those rectangles renders a
 * body half-buried in a column or crate. A waypoint outside the
 * playable area renders a body that is invisible (behind a
 * wall) or off the map. The previous shipped spec data did
 * exactly both, and this test is the regression net for the
 * "fix the data" half of the map-fix pass.</p>
 *
 * <p>Multi-floor maps (mesa) keep their elevation — a waypoint
 * at y=30 on a level.ofm whose mesa top sits at y=30 is on the
 * mesa, and the validation is per-(x, z) only.</p>
 */
@DisplayName("Map placement validation (waypoints + spawns in the kit's playable area)")
class MapPlacementValidationTest
{
    /** Per-axis half-extent of a Kenney kit column, in world units.
     * The column is 1x1 in model space; the kit's scale is 64. */
    private static final float COLUMN_HALF = 32.0f;

    /** The kit's column centres on the x axis. */
    private static final float COLUMN_X = 80.0f;

    /** The kit's column centres on the z axis. */
    private static final float COLUMN_Z = 80.0f;

    /** Per-axis half-extent of a kit crate, in world units. */
    private static final float CRATE_HALF = 32.0f;

    /** Kit crate's offset from the wall inner face. The crate's
     * centre is at +/- (half-room - 25.6) on the axis that runs
     * along the wall (so +/- 80 on the cross axis, 134.4 on the
     * long axis) — see MapScene.addLevelKit for the math. */
    private static final float CRATE_OFFSET = 134.4f;

    /** Buffer from the kit's perimeter wall's inner face, to keep
     * spawns/waypoints comfortably off the wall. The wall's
     * inner face is at half-room minus 6.4, which is 153.6 on a
     * 320x320 spec. The buffer (4 units) keeps the test
     * generous — a 320x320 spec with a 1-unit-tall body would
     * still be inside the playable area. */
    private static final float PLAYABLE_BUFFER = 4.0f;

    @BeforeEach
    void setUp()
    {
        MapLibrary.registerDefaults();
    }

    @Test
    @DisplayName("every sandbar / storage / stronghold / coldfront waypoint is in the playable area")
    void shouldKeepAssignedMapsWaypointsPlayable()
    {
        // This fix pass is responsible for sandbar, storage,
        // stronghold, and coldfront. The other shipped maps
        // (cornerstone, overpass, tripoint, extraction,
        // refinery, crossroads, arctic-station, foundry,
        // mesa, arctic-hp, pipeline, arctic-dom) are owned by
        // parallel agents and are expected to fix their own
        // maps in their own pass.
        for (final String id : new String[] {"sandbar", "storage", "stronghold", "coldfront"})
        {
            final MapSpec spec = MapLibrary.get(id);

            final float halfX = spec.dimensions().width() * 0.5f;

            final float halfZ = spec.dimensions().depth() * 0.5f;

            final float inner = Math.min(halfX, halfZ) - 6.4f - PLAYABLE_BUFFER;

            for (final Waypoint wp : spec.botWaypoints())
            {
                assertThat(wp.x())
                    .as("waypoint %s in %s is on the playable area's x axis", wp.id(), id)
                    .isBetween(-inner, inner);

                assertThat(wp.z())
                    .as("waypoint %s in %s is on the playable area's z axis", wp.id(), id)
                    .isBetween(-inner, inner);

                assertThat(insideColumn(wp.x(), wp.z()))
                    .as("waypoint %s in %s is not inside a kit column", wp.id(), id)
                    .isFalse();

                assertThat(insideCrate(wp.x(), wp.z()))
                    .as("waypoint %s in %s is not inside a kit crate", wp.id(), id)
                    .isFalse();
            }
        }
    }

    @Test
    @DisplayName("every sandbar / storage / stronghold / coldfront spawn is in the playable area")
    void shouldKeepAssignedMapsSpawnsPlayable()
    {
        for (final String id : new String[] {"sandbar", "storage", "stronghold", "coldfront"})
        {
            final MapSpec spec = MapLibrary.get(id);

            final float halfX = spec.dimensions().width() * 0.5f;

            final float halfZ = spec.dimensions().depth() * 0.5f;

            final float inner = Math.min(halfX, halfZ) - 6.4f - PLAYABLE_BUFFER;

            for (final SpawnPoint sp : spec.spawnPoints())
            {
                assertThat(sp.x())
                    .as("spawn %s in %s is on the playable area's x axis", sp.id(), id)
                    .isBetween(-inner, inner);

                assertThat(sp.z())
                    .as("spawn %s in %s is on the playable area's z axis", sp.id(), id)
                    .isBetween(-inner, inner);

                assertThat(insideColumn(sp.x(), sp.z()))
                    .as("spawn %s in %s is not inside a kit column", sp.id(), id)
                    .isFalse();

                assertThat(insideCrate(sp.x(), sp.z()))
                    .as("spawn %s in %s is not inside a kit crate", sp.id(), id)
                    .isFalse();
            }
        }
    }

    /**
     * Returns true if (x, z) is inside any of the kit's four
     * columns at the quarter positions.
     */
    private static boolean insideColumn(final float x, final float z)
    {
        return inRect(x, z, COLUMN_X, COLUMN_Z, COLUMN_HALF)
            || inRect(x, z, -COLUMN_X, COLUMN_Z, COLUMN_HALF)
            || inRect(x, z, COLUMN_X, -COLUMN_Z, COLUMN_HALF)
            || inRect(x, z, -COLUMN_X, -COLUMN_Z, COLUMN_HALF);
    }

    /**
     * Returns true if (x, z) is inside any of the six perimeter
     * kit crates. The crates are centred on (±80, ±134.4) and
     * (±134.4, ±80) with half-extent CRATE_HALF.
     */
    private static boolean insideCrate(final float x, final float z)
    {
        return inRect(x, z, 80.0f, CRATE_OFFSET, CRATE_HALF)
            || inRect(x, z, -80.0f, CRATE_OFFSET, CRATE_HALF)
            || inRect(x, z, 80.0f, -CRATE_OFFSET, CRATE_HALF)
            || inRect(x, z, -80.0f, -CRATE_OFFSET, CRATE_HALF)
            || inRect(x, z, CRATE_OFFSET, 80.0f, CRATE_HALF)
            || inRect(x, z, -CRATE_OFFSET, 80.0f, CRATE_HALF)
            || inRect(x, z, CRATE_OFFSET, -80.0f, CRATE_HALF)
            || inRect(x, z, -CRATE_OFFSET, -80.0f, CRATE_HALF);
    }

    private static boolean inRect(final float x, final float z, final float cx, final float cz,
        final float half)
    {
        return x >= cx - half && x <= cx + half && z >= cz - half && z <= cz + half;
    }
}
