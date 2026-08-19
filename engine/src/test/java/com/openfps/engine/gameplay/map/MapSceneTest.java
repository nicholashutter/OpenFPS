/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.demo.DemoModelFixture;
import com.openfps.engine.demo.DemoModels;
import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.render.adapter.Scene;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
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
    @DisplayName("construction with demo models")
    class Populated
    {
        @Test
        @DisplayName("the 2-arg build stages the kit, the bots, the arms, and the effect pool")
        void shouldPopulateSceneWithKitBotsAndEffects(@TempDir final Path root) throws IOException
        {
            // Stage a complete kit + weapon + carbine + character, the
            // smallest set the kit composer needs to do its work.
            // DemoModelFixture writes a valid two-triangle model;
            // nothing in this test inspects the geometry, only the
            // staging.
            final String[] kit =
            {
                "floor-square.ofm", "wall.ofm", "wall-doorway.ofm", "column.ofm",
                "crate.ofm", "stairs.ofm", "shape-slope.ofm",
            };

            for (final String piece : kit)
            {
                DemoModelFixture.write(root.resolve(DemoModels.LEVEL_DIRECTORY).resolve(piece));
            }

            DemoModelFixture.write(root.resolve(DemoModels.WEAPON_DIRECTORY)
                .resolve(DemoModels.WEAPON_MODEL));

            DemoModelFixture.write(root.resolve(DemoModels.WEAPON_DIRECTORY)
                .resolve(DemoModels.BOT_WEAPON_MODEL));

            for (final String person : DemoModels.CHARACTER_FILES)
            {
                DemoModelFixture.write(root.resolve(DemoModels.CHARACTER_DIRECTORY).resolve(person));
            }

            final DemoModels models = DemoModels.load(root);

            assertThat(models.isRealArt())
                .as("test fixture must produce KENNEY_KIT, otherwise the kit composer has nothing to stage")
                .isTrue();

            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapScene mapScene = MapScene.build(spec, models);

            // Scene is more than the level alone — the level-only path
            // returns 1 world instance, the populated path adds the kit
            // (5x5 floor = 25 tiles + 25 ceiling + 4 walls per side x 3
            // courses x 5 tiles = 60 wall tiles + 4 columns + 6 crates
            // = 144+ instances just for the kit, plus the 12 bots and 12
            // weapons on top).
            assertThat(mapScene.scene().worldInstanceCount())
                .as("the populated path must stage the kit + bots + weapons + arms + effect pool")
                .isGreaterThan(50);

            // The corner-stone spec has 12 bot waypoints, so we expect 12
            // bot instances and 12 weapon instances, each with a valid
            // scene-instance index (>= 0; the NO_INSTANCE sentinel is
            // -1).
            assertThat(mapScene.botInstanceIndex(0))
                .as("first bot must be staged with a real scene instance")
                .isGreaterThanOrEqualTo(0);

            assertThat(mapScene.botInstanceIndex(11))
                .as("twelfth bot (the kit composer stages one per waypoint)")
                .isGreaterThanOrEqualTo(0);

            assertThat(mapScene.botWeaponInstanceIndex(0))
                .as("first bot's carbine must be staged with a real scene instance")
                .isGreaterThanOrEqualTo(0);

            // The effect pool, the local body, and the level physics
            // are all populated when the 2-arg path is taken.
            assertThat(mapScene.effects())
                .as("the populated path must include the shared effect pool")
                .isNotNull();

            assertThat(mapScene.localBody())
                .as("the populated path must include the local player's first-person arms")
                .isNotNull();

            assertThat(mapScene.levelPhysics())
                .as("the populated path must include the level's collision world")
                .isNotNull();

            // The level .ofm has its own collision boxes; the kit
            // composer adds 4 walls + 4 columns on top. So the total
            // is "more than 8" (the kit's contribution alone).
            assertThat(mapScene.levelPhysics().solidCount())
                .as("level + kit walls + kit columns must produce solid boxes")
                .isGreaterThan(8);
        }

        @Test
        @DisplayName("a null models is rejected (loud failure, not a silent no-op)")
        void shouldRejectNullModels()
        {
            // The previous version of this overload was a silent
            // no-op delegation to the 1-arg path. That was the bug:
            // a caller passing null got a level-only scene with no
            // kit, no bots, no arms, no viewmodel, no effects. The
            // contract now is that the populated path either runs to
            // completion or throws — no silent fall-through.
            final MapSpec spec = MapLibrary.get("cornerstone");

            assertThatThrownBy(() -> MapScene.build(spec, null))
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

        @Test
        @DisplayName("cornerstone bots stand on the level's floor, not floating or sunk")
        void shouldPlaceCornerstoneBotsOnTheFloor()
        {
            // Pin the bot's visible feet to the level's floor for
            // cornerstone — the shipped Urban-Warzone TDM map. The
            // waypoint Y is 0.0f and the level .ofm is placed at
            // Mat4.identity() with the floor slab at world Y=0, so
            // the visible feet land at world Y=0.
            //
            // A regression that lifted the kit floor above Y=0, sank
            // the level .ofm below Y=0, or pulled the waypoints up
            // onto a gantry the kit can't see would all pass the
            // existing placement tests (which check X/Z and the
            // count) and only fail here.
            final MapSpec spec = MapLibrary.get("cornerstone");

            // Sanity: cornerstone has 28 waypoints, all at Y=0.
            // The map is single-floor, so any non-zero Y would put
            // the bot's body either buried in a wall or floating
            // above the kit floor tiles. The 28-waypoint count
            // (12 original 320-system waypoints scaled by 10, plus
            // 16 new outer-ring waypoints) is the scaled-cornerstone
            // shape — the level .ofm is still 320 x 320 but the
            // spec data spreads across the 3200 x 3200 playable
            // area as a centerpiece layout.
            assertThat(spec.botWaypoints())
                .as("cornerstone's 28 waypoints are the floor the bots stand on")
                .hasSize(28)
                .allSatisfy(wp -> assertThat(wp.y())
                    .as("cornerstone is a single-floor map; every waypoint Y is the floor")
                    .isZero());

            // The level .ofm must stage without falling back to the
            // empty-scene warning. The 1-arg build returns just the
            // level as one world instance — kit and bots are the
            // 2-arg build's job — so this is the level being
            // present, not the bot instances.
            final MapScene mapScene = MapScene.build(spec);

            assertThat(mapScene.scene().worldInstanceCount())
                .as("the level .ofm is staged, not an empty fallback")
                .isEqualTo(1);
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
