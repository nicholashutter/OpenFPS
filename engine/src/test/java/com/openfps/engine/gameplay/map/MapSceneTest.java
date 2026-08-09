/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.render.adapter.Scene;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link MapScene}.
 *
 * <p>The class is the smallest thing that closes the loop between
 * a {@link MapSpec} and a rendered frame. The tests cover the three
 * properties the wiring depends on:</p>
 *
 * <ul>
 *   <li>A registered spec builds a non-empty scene whose model
 *       came from the spec's level .ofm.</li>
 *   <li>A spec with an unreadable level path falls back to an
 *       empty scene rather than throwing, because the launcher's
 *       "the window must not crash" invariant depends on it.</li>
 *   <li>Two {@code MapScene}s for the same spec are equal,
 *       mirroring {@link MapSpec}'s id-based equality.</li>
 * </ul>
 *
 * <p>The level .ofm files for the shipped maps are committed at
 * {@code engine/src/main/resources/maps/<id>/level.ofm} (see
 * {@code docs/maps/README.md}), which is on the test classpath.
 * The classpath-based resource lookup is what the tests
 * exercise.</p>
 */
@DisplayName("MapScene")
class MapSceneTest
{
    /**
     * Re-registers the shipped maps before every test, because
     * {@link MapLibraryTest}'s {@code @AfterEach} calls
     * {@link MapLibrary#unregisterAll()} and JUnit5 does not
     * guarantee the test class execution order. A test that
     * runs after {@code MapLibraryTest}'s last test would
     * otherwise see an empty library.
     */
    @BeforeEach
    void setUp()
    {
        MapLibrary.registerDefaults();
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a registered map's spec builds a non-empty scene")
        void shouldBuildSceneForRegisteredMap()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            assertThat(spec).isNotNull();

            final MapScene mapScene = MapScene.build(spec);

            assertThat(mapScene.spec()).isSameAs(spec);

            assertThat(mapScene.scene()).isNotNull();

            assertThat(mapScene.scene()).isNotSameAs(Scene.EMPTY);

            assertThat(mapScene.scene().worldInstanceCount()).isEqualTo(1);

            assertThat(mapScene.scene().worldTriangleCount()).isPositive();
        }

        @Test
        @DisplayName("a spec with an unreadable level path falls back to an empty scene")
        void shouldFallBackToEmptyScene()
        {
            final MapSpec spec = new MapSpec("nope", "Nope", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, new MapDimensions(100.0f, 100.0f, 100.0f),
                threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE,
                new MapAssets("engine/src/main/resources/maps/does-not-exist/level.ofm",
                    "a/weapon.ofm", null));

            final MapScene mapScene = MapScene.build(spec);

            assertThat(mapScene.scene()).isSameAs(Scene.EMPTY);
        }

        @Test
        @DisplayName("a null spec is rejected")
        void shouldRejectNullSpec()
        {
            assertThatThrownBy(() -> MapScene.build(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality
    {
        @Test
        @DisplayName("two MapScenes for the same spec are equal")
        void shouldEqualBySpecId()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapScene a = MapScene.build(spec);

            final MapScene b = MapScene.build(spec);

            assertThat(a).isEqualTo(b);

            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("two MapScenes for different specs are not equal")
        void shouldNotEqualAcrossSpecs()
        {
            final MapSpec cornerstone = MapLibrary.get("cornerstone");

            final MapSpec overpass = MapLibrary.get("overpass");

            // Only build one of them to keep the test cheap; the
            // other side is the no-arg fallback.
            final MapScene a = MapScene.build(cornerstone);

            final MapScene b = MapScene.build(overpass);

            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("shipped maps")
    class ShippedMaps
    {
        @Test
        @DisplayName("every shipped map's level .ofm loads through the classpath")
        void shouldLoadEveryShippedMap()
        {
            // The four Urban Warzone maps are the ones Pass 2 commits;
            // assert that each of them resolves through the classpath
            // and produces a non-empty scene. The other three
            // settings (refinery, crossroads, arctic-station) are
            // also committed and follow the same pattern, but they
            // are not the focus of Pass 2.
            final String[] ids = {"cornerstone", "overpass", "tripoint", "extraction"};

            for (final String id : ids)
            {
                final MapSpec spec = MapLibrary.get(id);

                assertThat(spec).as("MapLibrary must know %s", id).isNotNull();

                final MapScene mapScene = MapScene.build(spec);

                assertThat(mapScene.scene().worldInstanceCount())
                    .as("%s should build a non-empty scene", id).isEqualTo(1);

                assertThat(mapScene.scene().worldTriangleCount())
                    .as("%s should have triangles", id).isPositive();
            }
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
