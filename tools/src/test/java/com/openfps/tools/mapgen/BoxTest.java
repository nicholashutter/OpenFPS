/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the {@link Box} primitive: JSON parsing, defaults, validation, and
 * the addTo path that emits twelve triangles.
 */
final class BoxTest
{
    @Test
    void buildsFromJsonWithExplicitFields()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"type\":\"box\",\"x\":1.0,\"y\":2.0,\"z\":3.0,"
            + "\"sx\":10.0,\"sy\":20.0,\"sz\":30.0,"
            + "\"submesh\":1,\"texture\":\"wall\"}");
        final Box box = Box.fromJson(obj, 8.0f);
        assertThat(box.type()).isEqualTo("box");
        assertThat(box.submesh()).isEqualTo(1);
        assertThat(box.texture()).isEqualTo("wall");
        assertThat(box.minX()).isEqualTo(1.0f);
        assertThat(box.minY()).isEqualTo(2.0f);
        assertThat(box.minZ()).isEqualTo(3.0f);
        assertThat(box.sizeX()).isEqualTo(10.0f);
        assertThat(box.sizeY()).isEqualTo(20.0f);
        assertThat(box.sizeZ()).isEqualTo(30.0f);
    }

    @Test
    void buildsFromJsonWithDefaults()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"x\":0.0,\"y\":0.0,\"z\":0.0,\"sx\":1.0,\"sy\":1.0,\"sz\":1.0}");
        final Box box = Box.fromJson(obj, 8.0f);
        assertThat(box.submesh()).isEqualTo(Box.SUBMESH_WALL);
        assertThat(box.texture()).isEqualTo("wall");
    }

    @Test
    void rejectsMissingPosition()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"sx\":1.0,\"sy\":1.0,\"sz\":1.0}");
        assertThatThrownBy(() -> Box.fromJson(obj, 8.0f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("x");
    }

    @Test
    void rejectsMissingSize()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"x\":0.0,\"y\":0.0,\"z\":0.0,\"sy\":1.0,\"sz\":1.0}");
        assertThatThrownBy(() -> Box.fromJson(obj, 8.0f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sx");
    }

    @Test
    void rejectsNonPositiveSize()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"x\":0.0,\"y\":0.0,\"z\":0.0,\"sx\":0.0,\"sy\":1.0,\"sz\":1.0}");
        assertThatThrownBy(() -> Box.fromJson(obj, 8.0f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sx");
    }

    @Test
    void rejectsBlankTexture()
    {
        assertThatThrownBy(() -> new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "", 8.0f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("texture");
    }

    @Test
    void validateAcceptsValidBox()
    {
        final Box box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        box.validate();
    }

    @Test
    void addToRejectsNegativeTextureIndex()
    {
        final Box box = new Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f,
            Box.SUBMESH_WALL, "wall", 8.0f);
        assertThatThrownBy(() -> box.addTo(new com.openfps.tools.model.ModelBuilder("test"), -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("textureIndex");
    }
}
