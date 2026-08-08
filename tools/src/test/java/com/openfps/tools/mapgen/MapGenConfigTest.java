/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests the immutable {@link MapGenConfig} POJO: required-field rejection,
 * defaults, and the list-copy contract.
 */
final class MapGenConfigTest
{
    @Test
    void buildsFromValidArguments()
    {
        final Primitive box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        final MapGenConfig config = new MapGenConfig("test-id", "Test", "URBAN_WARZONE", "TDM",
            64, 8.0f, List.of(box));
        assertThat(config.id()).isEqualTo("test-id");
        assertThat(config.displayName()).isEqualTo("Test");
        assertThat(config.setting()).isEqualTo("URBAN_WARZONE");
        assertThat(config.mode()).isEqualTo("TDM");
        assertThat(config.textureEdge()).isEqualTo(64);
        assertThat(config.worldUnitsPerTile()).isEqualTo(8.0f);
        assertThat(config.primitives()).hasSize(1);
    }

    @Test
    void nullSettingAndModeAreKeptAsNull()
    {
        final Primitive box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        final MapGenConfig config = new MapGenConfig("test-id", "Test", null, null, 64, 8.0f,
            List.of(box));
        assertThat(config.setting()).isNull();
        assertThat(config.mode()).isNull();
    }

    @Test
    void blankSettingAndModeBecomeNull()
    {
        final Primitive box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        final MapGenConfig config = new MapGenConfig("test-id", "Test", "", " ", 64, 8.0f,
            List.of(box));
        assertThat(config.setting()).isNull();
        assertThat(config.mode()).isNull();
    }

    @Test
    void rejectsBlankId()
    {
        final Primitive box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        assertThatThrownBy(() -> new MapGenConfig("", "Test", null, null, 64, 8.0f, List.of(box)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    @Test
    void rejectsBlankDisplayName()
    {
        final Primitive box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        assertThatThrownBy(() -> new MapGenConfig("id", " ", null, null, 64, 8.0f, List.of(box)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("displayName");
    }

    @Test
    void rejectsNonPowerOfTwoTextureEdge()
    {
        final Primitive box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        assertThatThrownBy(() -> new MapGenConfig("id", "Test", null, null, 63, 8.0f, List.of(box)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("textureEdge");
    }

    @Test
    void rejectsNonPositiveWorldUnitsPerTile()
    {
        final Primitive box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        assertThatThrownBy(() -> new MapGenConfig("id", "Test", null, null, 64, 0.0f, List.of(box)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("worldUnitsPerTile");
    }

    @Test
    void rejectsEmptyPrimitives()
    {
        assertThatThrownBy(() -> new MapGenConfig("id", "Test", null, null, 64, 8.0f,
            List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("primitives");
    }

    @Test
    void primitivesListIsImmutable()
    {
        final Primitive box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        final MapGenConfig config = new MapGenConfig("id", "Test", null, null, 64, 8.0f,
            List.of(box));
        assertThatThrownBy(() -> config.primitives().add(box))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
