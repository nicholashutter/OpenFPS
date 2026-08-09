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
 * Tests the {@link Sign} primitive: JSON parsing, defaults, the
 * vertical/horizontal branch, and yaw rotation.
 */
final class SignTest
{
    @Test
    void buildsFromJsonWithExplicitFields()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"type\":\"sign\",\"x\":1.0,\"y\":2.0,\"z\":3.0,"
            + "\"w\":10.0,\"h\":20.0,\"yaw\":90.0,\"vertical\":true,"
            + "\"submesh\":1,\"texture\":\"accent\"}");

        final Sign sign = Sign.fromJson(obj, 8.0f);

        assertThat(sign.type()).isEqualTo("sign");

        assertThat(sign.submesh()).isEqualTo(1);

        assertThat(sign.texture()).isEqualTo("accent");

        assertThat(sign.centerX()).isEqualTo(1.0f);

        assertThat(sign.centerY()).isEqualTo(2.0f);

        assertThat(sign.centerZ()).isEqualTo(3.0f);

        assertThat(sign.width()).isEqualTo(10.0f);

        assertThat(sign.height()).isEqualTo(20.0f);

        assertThat(sign.facingYawDegrees()).isEqualTo(90.0f);

        assertThat(sign.vertical()).isTrue();
    }

    @Test
    void buildsFromJsonWithDefaults()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"x\":0.0,\"y\":0.0,\"z\":0.0,\"w\":1.0,\"h\":1.0}");

        final Sign sign = Sign.fromJson(obj, 8.0f);

        assertThat(sign.facingYawDegrees()).isEqualTo(0.0f);

        assertThat(sign.vertical()).isTrue();

        assertThat(sign.submesh()).isEqualTo(Sign.SUBMESH_WALL);

        assertThat(sign.texture()).isEqualTo("accent");
    }

    @Test
    void horizontalSign()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"x\":0.0,\"y\":0.0,\"z\":0.0,\"w\":10.0,\"h\":20.0,\"vertical\":false}");

        final Sign sign = Sign.fromJson(obj, 8.0f);

        assertThat(sign.vertical()).isFalse();
    }

    @Test
    void rejectsMissingSize()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"x\":0.0,\"y\":0.0,\"z\":0.0,\"h\":1.0}");

        assertThatThrownBy(() -> Sign.fromJson(obj, 8.0f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("w");
    }

    @Test
    void rejectsNonPositiveSize()
    {
        final JsonObject obj = (JsonObject) JsonParser.parseString(
            "{\"x\":0.0,\"y\":0.0,\"z\":0.0,\"w\":0.0,\"h\":1.0}");

        assertThatThrownBy(() -> Sign.fromJson(obj, 8.0f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("w");
    }

    @Test
    void validateAcceptsValidSign()
    {
        final Sign sign = new Sign(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, true,
            Sign.SUBMESH_WALL, "accent", 8.0f);

        sign.validate();
    }

    @Test
    void rejectsBlankTexture()
    {
        assertThatThrownBy(() -> new Sign(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, true,
            Sign.SUBMESH_WALL, " ", 8.0f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("texture");
    }
}
