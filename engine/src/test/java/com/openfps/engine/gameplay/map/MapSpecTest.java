/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.gameplay.MatchMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link MapSpec}.
 *
 * <p>Coverage focuses on the validation rules — the inputs the constructor
 * refuses, and the marker/mode match it enforces. The happy path is
 * exercised by {@link Maps#cornerstone()} and its tests; the unhappy paths
 * live here.</p>
 */
@DisplayName("MapSpec")
class MapSpecTest
{
    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("accepts a fully-populated valid spec")
        void shouldAcceptValidSpec()
        {
            final MapSpec spec = new MapSpec("id", "Name", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, new MapDimensions(100.0f, 100.0f, 100.0f),
                threeLanes(), List.of(spawn()), List.of(), MapMarkers.TeamDeathmatch.INSTANCE,
                new MapAssets("a/level.ofm", "a/weapon.ofm", null));

            assertThat(spec.id()).isEqualTo("id");

            assertThat(spec.mode()).isEqualTo(MatchMode.TDM);

            assertThat(spec.markers()).isInstanceOf(MapMarkers.TeamDeathmatch.class);
        }

        @Test
        @DisplayName("rejects a null id")
        void shouldRejectNullId()
        {
            assertThatThrownBy(() -> new MapSpec(null, "Name", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, dim(), threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE, assets()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        }

        @Test
        @DisplayName("rejects a blank id")
        void shouldRejectBlankId()
        {
            assertThatThrownBy(() -> new MapSpec("", "Name", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, dim(), threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE, assets()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a non-three-lane list")
        void shouldRejectWrongLaneCount()
        {
            assertThatThrownBy(() -> new MapSpec("id", "Name", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, dim(), List.of(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE, assets()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three lanes");
        }

        @Test
        @DisplayName("rejects an empty spawn list")
        void shouldRejectEmptySpawns()
        {
            assertThatThrownBy(() -> new MapSpec("id", "Name", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, dim(), threeLanes(), List.of(), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE, assets()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spawnPoints");
        }

        @Test
        @DisplayName("rejects markers that do not match the declared mode")
        void shouldRejectMismatchedMarkers()
        {
            assertThatThrownBy(() -> new MapSpec("id", "Name", MapSetting.URBAN_WARZONE,
                MatchMode.HARDPOINT, dim(), threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE, assets()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match mode");
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality
    {
        @Test
        @DisplayName("two specs with the same id are equal, regardless of other fields")
        void shouldEqualById()
        {
            // Both TDM so the markers/mode check accepts. Equality is
            // measured on displayName / setting / dimensions, not on the
            // markers / mode — which is the point of using id as the
            // key.
            final MapSpec a = new MapSpec("cornerstone", "First", MapSetting.URBAN_WARZONE,
                MatchMode.TDM, dim(), threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE, assets());

            final MapSpec b = new MapSpec("cornerstone", "Second", MapSetting.INDUSTRIAL_COMPLEX,
                MatchMode.TDM, new MapDimensions(50.0f, 50.0f, 50.0f),
                threeLanes(), List.of(spawn()), List.of(), MapMarkers.TeamDeathmatch.INSTANCE,
                assets());

            assertThat(a).isEqualTo(b);

            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("two specs with different ids are not equal")
        void shouldNotEqualAcrossIds()
        {
            final MapSpec a = new MapSpec("a", "X", MapSetting.URBAN_WARZONE, MatchMode.TDM,
                dim(), threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE, assets());

            final MapSpec b = new MapSpec("b", "X", MapSetting.URBAN_WARZONE, MatchMode.TDM,
                dim(), threeLanes(), List.of(spawn()), List.of(),
                MapMarkers.TeamDeathmatch.INSTANCE, assets());

            assertThat(a).isNotEqualTo(b);
        }
    }

    private static MapDimensions dim()
    {
        return new MapDimensions(100.0f, 100.0f, 100.0f);
    }

    private static MapAssets assets()
    {
        return new MapAssets("a/level.ofm", "a/weapon.ofm", null);
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
