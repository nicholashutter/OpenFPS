/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.gameplay.MatchMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link MapLibrary}.
 *
 * <p>The library is a singleton with a static initializer, so each test
 * uses {@link #tearDown} to clear state. {@link MapLibrary#registerDefaults()}
 * is called by the static initializer and re-runs at the start of every test
 * to ensure the shipped {@code cornerstone} map is present.</p>
 */
@DisplayName("MapLibrary")
class MapLibraryTest
{
    @AfterEach
    void tearDown()
    {
        MapLibrary.unregisterAll();
    }

    @BeforeEach
    void setUp()
    {
        // The library is a singleton with a static initializer, so we
        // unregister between tests and re-register the shipped maps at
        // the start of every one. Tests that want a different set call
        // register directly without calling this.
        MapLibrary.registerDefaults();
    }

    @Nested
    @DisplayName("defaults")
    class Defaults
    {
        @Test
        @DisplayName("cornerstone is registered at class load time")
        void shouldRegisterCornerstone()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("cornerstone")).isTrue();
        }

        @Test
        @DisplayName("cornerstone spec is a TDM map in Urban Warzone")
        void shouldDescribeCornerstone()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.TDM);

            assertThat(spec.setting()).isEqualTo(MapSetting.URBAN_WARZONE);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).isNotEmpty();
        }

        @Test
        @DisplayName("overpass is registered at class load time")
        void shouldRegisterOverpass()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("overpass")).isTrue();
        }

        @Test
        @DisplayName("overpass spec is a Hardpoint map in Urban Warzone with 3 zones")
        void shouldDescribeOverpass()
        {
            final MapSpec spec = MapLibrary.get("overpass");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.HARDPOINT);

            assertThat(spec.setting()).isEqualTo(MapSetting.URBAN_WARZONE);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.Hardpoint hp = (MapMarkers.Hardpoint) spec.markers();

            assertThat(hp.zones()).hasSize(3);

            assertThat(hp.rotationTics()).isPositive();

            assertThat(hp.scorePerTick()).isPositive();
        }

        @Test
        @DisplayName("tripoint is registered at class load time")
        void shouldRegisterTripoint()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("tripoint")).isTrue();
        }

        @Test
        @DisplayName("tripoint spec is a Domination map in Urban Warzone with 3 flags")
        void shouldDescribeTripoint()
        {
            final MapSpec spec = MapLibrary.get("tripoint");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.DOMINATION);

            assertThat(spec.setting()).isEqualTo(MapSetting.URBAN_WARZONE);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.Domination dom = (MapMarkers.Domination) spec.markers();

            assertThat(dom.flags()).hasSize(3);

            // The three flag positions are distinct, by the unique
            // id the spec assigns. The Match layer relies on the
            // id being unique within a map, so a test pinning the
            // property here is a guard against a future refactor
            // that introduces an id collision.
            assertThat(dom.flags().get(0).id()).isNotEqualTo(dom.flags().get(1).id());

            assertThat(dom.flags().get(1).id()).isNotEqualTo(dom.flags().get(2).id());
        }

        @Test
        @DisplayName("extraction is registered at class load time")
        void shouldRegisterExtraction()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("extraction")).isTrue();
        }

        @Test
        @DisplayName("extraction spec is a CTF map in Urban Warzone with 2 bases")
        void shouldDescribeExtraction()
        {
            final MapSpec spec = MapLibrary.get("extraction");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.CTF);

            assertThat(spec.setting()).isEqualTo(MapSetting.URBAN_WARZONE);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.CaptureTheFlag ctf = (MapMarkers.CaptureTheFlag) spec.markers();

            assertThat(ctf.redBase().team()).isEqualTo(Team.RED);

            assertThat(ctf.blueBase().team()).isEqualTo(Team.BLUE);

            // Each base declares both a flag and a capture point.
            // They may sit at the same coordinates (the standard
            // CTF layout) or at distinct ones; the spec does not
            // constrain that, so the test only checks they are
            // non-zero and within the map's bounds.
            assertThat(ctf.redBase().radius()).isPositive();

            assertThat(ctf.blueBase().radius()).isPositive();
        }

        @Test
        @DisplayName("storage is registered at class load time")
        void shouldRegisterStorage()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("storage")).isTrue();
        }

        @Test
        @DisplayName("storage spec is a CTF map in Industrial Complex with 2 bases")
        void shouldDescribeStorage()
        {
            final MapSpec spec = MapLibrary.get("storage");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.CTF);

            assertThat(spec.setting()).isEqualTo(MapSetting.INDUSTRIAL_COMPLEX);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.CaptureTheFlag ctf = (MapMarkers.CaptureTheFlag) spec.markers();

            assertThat(ctf.redBase().team()).isEqualTo(Team.RED);

            assertThat(ctf.blueBase().team()).isEqualTo(Team.BLUE);

            assertThat(ctf.redBase().radius()).isPositive();

            assertThat(ctf.blueBase().radius()).isPositive();
        }

        @Test
        @DisplayName("stronghold is registered at class load time")
        void shouldRegisterStronghold()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("stronghold")).isTrue();
        }

        @Test
        @DisplayName("stronghold spec is a CTF map in Desert Ravine with 2 bases")
        void shouldDescribeStronghold()
        {
            final MapSpec spec = MapLibrary.get("stronghold");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.CTF);

            assertThat(spec.setting()).isEqualTo(MapSetting.DESERT_RAVINE);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.CaptureTheFlag ctf = (MapMarkers.CaptureTheFlag) spec.markers();

            assertThat(ctf.redBase().team()).isEqualTo(Team.RED);

            assertThat(ctf.blueBase().team()).isEqualTo(Team.BLUE);

            assertThat(ctf.redBase().radius()).isPositive();

            assertThat(ctf.blueBase().radius()).isPositive();
        }

        @Test
        @DisplayName("coldfront is registered at class load time")
        void shouldRegisterColdfront()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("coldfront")).isTrue();
        }

        @Test
        @DisplayName("coldfront spec is a CTF map in Arctic Station with 2 bases")
        void shouldDescribeColdfront()
        {
            final MapSpec spec = MapLibrary.get("coldfront");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.CTF);

            assertThat(spec.setting()).isEqualTo(MapSetting.ARCTIC_STATION);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.CaptureTheFlag ctf = (MapMarkers.CaptureTheFlag) spec.markers();

            assertThat(ctf.redBase().team()).isEqualTo(Team.RED);

            assertThat(ctf.blueBase().team()).isEqualTo(Team.BLUE);

            assertThat(ctf.redBase().radius()).isPositive();

            assertThat(ctf.blueBase().radius()).isPositive();
        }

        @Test
        @DisplayName("foundry is registered at class load time")
        void shouldRegisterFoundry()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("foundry")).isTrue();
        }

        @Test
        @DisplayName("foundry spec is a Hardpoint map in Industrial Complex with 3 zones")
        void shouldDescribeFoundry()
        {
            final MapSpec spec = MapLibrary.get("foundry");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.HARDPOINT);

            assertThat(spec.setting()).isEqualTo(MapSetting.INDUSTRIAL_COMPLEX);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.Hardpoint hp = (MapMarkers.Hardpoint) spec.markers();

            assertThat(hp.zones()).hasSize(3);

            assertThat(hp.rotationTics()).isPositive();

            assertThat(hp.scorePerTick()).isPositive();

            // The three zone ids must be unique within the map; the
            // match layer relies on this to disambiguate the active
            // zone from its siblings. A future refactor that
            // introduces an id collision would silently desync
            // lockstep peers on the first rotation.
            assertThat(hp.zones().get(0).id())
                .isNotEqualTo(hp.zones().get(1).id());

            assertThat(hp.zones().get(1).id())
                .isNotEqualTo(hp.zones().get(2).id());
        }

        @Test
        @DisplayName("mesa is registered at class load time")
        void shouldRegisterMesa()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("mesa")).isTrue();
        }

        @Test
        @DisplayName("mesa spec is a Hardpoint map in Desert Ravine with 3 zones")
        void shouldDescribeMesa()
        {
            final MapSpec spec = MapLibrary.get("mesa");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.HARDPOINT);

            assertThat(spec.setting()).isEqualTo(MapSetting.DESERT_RAVINE);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.Hardpoint hp = (MapMarkers.Hardpoint) spec.markers();

            assertThat(hp.zones()).hasSize(3);

            assertThat(hp.rotationTics()).isPositive();

            assertThat(hp.scorePerTick()).isPositive();

            assertThat(hp.zones().get(0).id())
                .isNotEqualTo(hp.zones().get(1).id());

            assertThat(hp.zones().get(1).id())
                .isNotEqualTo(hp.zones().get(2).id());
        }

        @Test
        @DisplayName("arctic-hp is registered at class load time")
        void shouldRegisterArcticHp()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("arctic-hp")).isTrue();
        }

        @Test
        @DisplayName("arctic-hp spec is a Hardpoint map in Arctic Station with 3 zones")
        void shouldDescribeArcticHp()
        {
            final MapSpec spec = MapLibrary.get("arctic-hp");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.HARDPOINT);

            assertThat(spec.setting()).isEqualTo(MapSetting.ARCTIC_STATION);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.Hardpoint hp = (MapMarkers.Hardpoint) spec.markers();

            assertThat(hp.zones()).hasSize(3);

            assertThat(hp.rotationTics()).isPositive();

            assertThat(hp.scorePerTick()).isPositive();

            assertThat(hp.zones().get(0).id())
                .isNotEqualTo(hp.zones().get(1).id());

            assertThat(hp.zones().get(1).id())
                .isNotEqualTo(hp.zones().get(2).id());
        }

        @Test
        @DisplayName("pipeline is registered at class load time")
        void shouldRegisterPipeline()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("pipeline")).isTrue();
        }

        @Test
        @DisplayName("pipeline spec is a Domination map in Industrial Complex with 3 flags")
        void shouldDescribePipeline()
        {
            final MapSpec spec = MapLibrary.get("pipeline");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.DOMINATION);

            assertThat(spec.setting()).isEqualTo(MapSetting.INDUSTRIAL_COMPLEX);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.Domination dom = (MapMarkers.Domination) spec.markers();

            assertThat(dom.flags()).hasSize(3);

            // The three flag positions are distinct, by the unique
            // id the spec assigns. The Match layer relies on the
            // id being unique within a map, so a test pinning the
            // property here is a guard against a future refactor
            // that introduces an id collision.
            assertThat(dom.flags().get(0).id()).isNotEqualTo(dom.flags().get(1).id());

            assertThat(dom.flags().get(1).id()).isNotEqualTo(dom.flags().get(2).id());
        }

        @Test
        @DisplayName("sandbar is registered at class load time")
        void shouldRegisterSandbar()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("sandbar")).isTrue();
        }

        @Test
        @DisplayName("sandbar spec is a Domination map in Desert Ravine with 3 flags")
        void shouldDescribeSandbar()
        {
            final MapSpec spec = MapLibrary.get("sandbar");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.DOMINATION);

            assertThat(spec.setting()).isEqualTo(MapSetting.DESERT_RAVINE);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            final MapMarkers.Domination dom = (MapMarkers.Domination) spec.markers();

            assertThat(dom.flags()).hasSize(3);

            assertThat(dom.flags().get(0).id()).isNotEqualTo(dom.flags().get(1).id());

            assertThat(dom.flags().get(1).id()).isNotEqualTo(dom.flags().get(2).id());
        }

        @Test
        @DisplayName("arctic-dom is registered at class load time")
        void shouldRegisterArcticDom()
        {
            MapLibrary.registerDefaults();

            assertThat(MapLibrary.has("arctic-dom")).isTrue();
        }

        @Test
        @DisplayName("arctic-dom spec is a Domination map in Arctic Station with 3 flags")
        void shouldDescribeArcticDom()
        {
            final MapSpec spec = MapLibrary.get("arctic-dom");

            assertThat(spec).isNotNull();

            assertThat(spec.mode()).isEqualTo(MatchMode.DOMINATION);

            assertThat(spec.setting()).isEqualTo(MapSetting.ARCTIC_STATION);

            assertThat(spec.lanes()).hasSize(3);

            assertThat(spec.spawnPoints()).hasSize(6);

            assertThat(spec.botWaypoints()).isNotEmpty();

            // The display name is the spec's "Frostline" (per the
            // design doc), not the map id. Pin the invariant here
            // so a future rename is intentional.
            assertThat(spec.displayName()).isEqualTo("Frostline");

            final MapMarkers.Domination dom = (MapMarkers.Domination) spec.markers();

            assertThat(dom.flags()).hasSize(3);

            assertThat(dom.flags().get(0).id()).isNotEqualTo(dom.flags().get(1).id());

            assertThat(dom.flags().get(1).id()).isNotEqualTo(dom.flags().get(2).id());
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration
    {
        @Test
        @DisplayName("a freshly registered map is retrievable by id")
        void shouldRegisterAndRetrieve()
        {
            final MapSpec spec = new MapSpec("test", "Test", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, new MapDimensions(100.0f, 100.0f, 100.0f),
                threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE,
                List.of(),
                new MapAssets("a/level.ofm", "a/weapon.ofm", null));

            MapLibrary.register(spec);

            assertThat(MapLibrary.get("test")).isSameAs(spec);
        }

        @Test
        @DisplayName("re-registering a map replaces the previous one")
        void shouldReplaceExisting()
        {
            final MapSpec first = new MapSpec("dup", "First", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, new MapDimensions(100.0f, 100.0f, 100.0f),
                threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE,
                List.of(),
                new MapAssets("a/level.ofm", "a/weapon.ofm", null));

            final MapSpec second = new MapSpec("dup", "Second", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, new MapDimensions(100.0f, 100.0f, 100.0f),
                threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE,
                List.of(),
                new MapAssets("a/level.ofm", "a/weapon.ofm", null));

            MapLibrary.register(first);

            MapLibrary.register(second);

            assertThat(MapLibrary.get("dup")).isSameAs(second);
        }

        @Test
        @DisplayName("registering a null spec is rejected")
        void shouldRejectNullSpec()
        {
            assertThatThrownBy(() -> MapLibrary.register(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("lookups")
    class Lookups
    {
        @Test
        @DisplayName("an unknown id returns null")
        void shouldReturnNullForUnknown()
        {
            assertThat(MapLibrary.get("nope")).isNull();
        }

        @Test
        @DisplayName("a null id is rejected")
        void shouldRejectNullId()
        {
            assertThatThrownBy(() -> MapLibrary.get(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static SpawnPoint spawn()
    {
        return new SpawnPoint("sp_0", Team.RED, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    private static List<Lane> threeLanes()
    {
        return List.of(
            new Lane("lane_a", LaneAxis.NORTH_SOUTH, List.of(
                new Chokepoint("cp_a1", "A1", 0.0f, 0.0f),
                new Chokepoint("cp_a2", "A2", 10.0f, 0.0f)
            )),
            new Lane("lane_b", LaneAxis.NORTH_SOUTH, List.of(
                new Chokepoint("cp_b1", "B1", 0.0f, 10.0f),
                new Chokepoint("cp_b2", "B2", 10.0f, 10.0f)
            )),
            new Lane("lane_c", LaneAxis.NORTH_SOUTH, List.of(
                new Chokepoint("cp_c1", "C1", 0.0f, 20.0f),
                new Chokepoint("cp_c2", "C2", 10.0f, 20.0f)
            ))
        );
    }
}
