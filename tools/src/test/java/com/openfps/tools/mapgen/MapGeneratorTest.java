/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.openfps.engine.render.adapter.ModelFormat;

/**
 * Tests the end-to-end map generator: a small config produces a valid
 * {@code .ofm} that the runtime's reader accepts.
 */
final class MapGeneratorTest
{
    @Test
    void generatesSingleBoxConfig()
    {
        final String json = "{"
            + "\"id\":\"test-single\","
            + "\"displayName\":\"Single Box Test\","
            + "\"primitives\":["
            + "  {\"type\":\"box\",\"x\":0,\"y\":0,\"z\":0,"
            + "   \"sx\":1,\"sy\":1,\"sz\":1,\"submesh\":1,\"texture\":\"wall\"}"
            + "]}";
        final byte[] bytes = generate(json);
        final ModelFormat model = ModelFormat.read(bytes);
        assertThat(model.indexCount() / 3).isEqualTo(12);
        // 6 faces x 4 vertices (no sharing) = 24 vertices per box.
        assertThat(model.vertexCount()).isEqualTo(24);
    }

    @Test
    void generatesMultipleBoxes()
    {
        final String json = "{"
            + "\"id\":\"test-multi\","
            + "\"displayName\":\"Multi Box Test\","
            + "\"primitives\":["
            + "  {\"type\":\"box\",\"x\":0,\"y\":0,\"z\":0,\"sx\":1,\"sy\":1,\"sz\":1,"
            + "   \"submesh\":0,\"texture\":\"floor\"},"
            + "  {\"type\":\"box\",\"x\":0,\"y\":0,\"z\":0,\"sx\":1,\"sy\":1,\"sz\":1,"
            + "   \"submesh\":1,\"texture\":\"wall\"},"
            + "  {\"type\":\"box\",\"x\":0,\"y\":0,\"z\":0,\"sx\":1,\"sy\":1,\"sz\":1,"
            + "   \"submesh\":2,\"texture\":\"accent\"}"
            + "]}";
        final byte[] bytes = generate(json);
        final ModelFormat model = ModelFormat.read(bytes);
        // 3 boxes x 12 triangles = 36 triangles
        assertThat(model.indexCount() / 3).isEqualTo(36);
        // 3 submeshes, one per (submesh, texture) pair
        assertThat(model.submeshCount()).isEqualTo(3);
    }

    @Test
    void dedupesRepeatedTexture()
    {
        // Two boxes in submesh 1 with the wall texture should share one
        // texture record, not two.
        final String json = "{"
            + "\"id\":\"test-dedup\","
            + "\"displayName\":\"Texture Dedup Test\","
            + "\"primitives\":["
            + "  {\"type\":\"box\",\"x\":0,\"y\":0,\"z\":0,\"sx\":1,\"sy\":1,\"sz\":1,"
            + "   \"submesh\":1,\"texture\":\"wall\"},"
            + "  {\"type\":\"box\",\"x\":5,\"y\":0,\"z\":0,\"sx\":1,\"sy\":1,\"sz\":1,"
            + "   \"submesh\":1,\"texture\":\"wall\"}"
            + "]}";
        final byte[] bytes = generate(json);
        final ModelFormat model = ModelFormat.read(bytes);
        assertThat(model.submeshCount()).isEqualTo(1);
        assertThat(model.textureCount()).isEqualTo(1);
    }

    @Test
    void generatesSign()
    {
        final String json = "{"
            + "\"id\":\"test-sign\","
            + "\"displayName\":\"Sign Test\","
            + "\"primitives\":["
            + "  {\"type\":\"sign\",\"x\":0,\"y\":32,\"z\":0,\"w\":16,\"h\":8,"
            + "   \"yaw\":0,\"vertical\":true,\"submesh\":1,\"texture\":\"accent\"}"
            + "]}";
        final byte[] bytes = generate(json);
        final ModelFormat model = ModelFormat.read(bytes);
        // 1 sign x 2 triangles = 2 triangles
        assertThat(model.indexCount() / 3).isEqualTo(2);
        assertThat(model.vertexCount()).isEqualTo(4);
    }

    @Test
    void rejectsUnknownPrimitiveType()
    {
        final String json = "{"
            + "\"id\":\"test-bad\","
            + "\"displayName\":\"Bad Type Test\","
            + "\"primitives\":["
            + "  {\"type\":\"unknown\",\"x\":0,\"y\":0,\"z\":0,\"sx\":1,\"sy\":1,\"sz\":1}"
            + "]}";
        assertThatThrownBy(() -> generate(json))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown primitive type");
    }

    @Test
    void rejectsUnknownSwatch()
    {
        final String json = "{"
            + "\"id\":\"test-bad-swatch\","
            + "\"displayName\":\"Bad Swatch Test\","
            + "\"primitives\":["
            + "  {\"type\":\"box\",\"x\":0,\"y\":0,\"z\":0,\"sx\":1,\"sy\":1,\"sz\":1,"
            + "   \"submesh\":1,\"texture\":\"marble\"}"
            + "]}";
        assertThatThrownBy(() -> generate(json))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown swatch");
    }

    @Test
    void rejectsMalformedJson()
    {
        assertThatThrownBy(() -> generate("{not json"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("malformed");
    }

    @Test
    void rejectsMissingId()
    {
        final String json = "{"
            + "\"displayName\":\"No Id Test\","
            + "\"primitives\":["
            + "  {\"type\":\"box\",\"x\":0,\"y\":0,\"z\":0,\"sx\":1,\"sy\":1,\"sz\":1}"
            + "]}";
        assertThatThrownBy(() -> generate(json))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    private static byte[] generate(final String json)
    {
        final PrimitiveFactory factory = PrimitiveFactory.createDefault();
        final JsonConfigParser parser = new JsonConfigParser(factory);
        final MapGenConfig config = parser.parseString(json);
        final MapGenerator generator = new MapGenerator(null, factory);
        return generator.generate(config);
    }
}
