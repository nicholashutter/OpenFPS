/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses a map generator config from JSON into a {@link MapGenConfig}.
 *
 * <h2>Why Gson</h2>
 *
 * <p>Gson is already a {@code :tools} dependency for the glTF converter, and
 * the build module's {@code build.gradle.kts} notes that the JSON layer is
 * the one part of the toolchain that ought to use a real parser rather than
 * a hand-rolled one. Using it here is consistent with the rest of the
 * build tool and means the config schema can grow (a new field with a
 * default, a new value type) without parsing code in this file having to
 * change.</p>
 *
 * <h2>The schema</h2>
 *
 * <pre>{@code
 * {
 *   "id": "sample-cornerstone",
 *   "displayName": "Sample Cornerstone",
 *   "setting": "URBAN_WARZONE",
 *   "mode": "TDM",
 *   "textureEdge": 64,
 *   "worldUnitsPerTile": 8.0,
 *   "primitives": [
 *     { "type": "box", "x": 0, "y": 0, "z": 0, "sx": 320, "sy": 4, "sz": 320,
 *       "submesh": 0, "texture": "floor" },
 *     { "type": "sign", "x": 0, "y": 64, "z": 0, "w": 32, "h": 16,
 *       "yaw": 90, "vertical": true, "submesh": 1, "texture": "accent" }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code id}, {@code displayName} and the {@code primitives} array are
 * required. Everything else has a default.</p>
 */
public final class JsonConfigParser
{
    private static final int DEFAULT_TEXTURE_EDGE = 64;
    private static final float DEFAULT_WORLD_UNITS_PER_TILE = 8.0f;

    private final PrimitiveFactory factory;

    /**
     * Creates a parser that uses the given factory to turn primitive JSON
     * objects into {@link Primitive} instances.
     *
     * @param factory the factory to look primitive types and swatches up in;
     *     must not be null
     */
    public JsonConfigParser(final PrimitiveFactory factory)
    {
        this.factory = factory;
    }

    /**
     * Parses the config at the given path.
     *
     * @param configPath the path to the config file; must not be null
     * @return the parsed config
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the JSON is malformed, a required
     *     field is missing, or a primitive/swatch name is not registered
     */
    public MapGenConfig parse(final Path configPath) throws IOException
    {
        try (Reader reader = Files.newBufferedReader(configPath))
        {
            return parseReader(reader);
        }
    }

    /**
     * Parses a config from an open {@link Reader}.
     *
     * @param reader the reader to read JSON from
     * @return the parsed config
     * @throws IOException if the reader fails
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public MapGenConfig parseReader(final Reader reader) throws IOException
    {
        final JsonElement root;
        try
        {
            root = JsonParser.parseReader(reader);
        }
        catch (final RuntimeException e)
        {
            throw new IllegalArgumentException("malformed config JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isJsonObject())
        {
            throw new IllegalArgumentException("config root must be a JSON object");
        }
        return fromJson(root.getAsJsonObject());
    }

    /**
     * Parses a config from an already-parsed JSON object.
     *
     * <p>Public so tests can build configs from inline JSON without a
     * temporary file.</p>
     *
     * @param root the parsed JSON object
     * @return the parsed config
     */
    public MapGenConfig fromJson(final JsonObject root)
    {
        final String id = readString(root, "id");
        final String displayName = readString(root, "displayName");
        final String setting = readStringOrNull(root, "setting");
        final String mode = readStringOrNull(root, "mode");
        final int textureEdge = readIntOrDefault(root, "textureEdge", DEFAULT_TEXTURE_EDGE);
        final float worldUnitsPerTile = readFloatOrDefault(root, "worldUnitsPerTile",
            DEFAULT_WORLD_UNITS_PER_TILE);
        final JsonArray primitivesArray = readArray(root, "primitives");
        final List<Primitive> primitives = new ArrayList<>(primitivesArray.size());
        for (final JsonElement element : primitivesArray)
        {
            if (element == null || !element.isJsonObject())
            {
                throw new IllegalArgumentException(
                    "primitives must be a JSON array of objects");
            }
            final Primitive primitive = factory.create(element.getAsJsonObject(),
                worldUnitsPerTile);
            primitives.add(primitive);
        }
        return new MapGenConfig(id, displayName, setting, mode, textureEdge, worldUnitsPerTile,
            primitives);
    }

    /**
     * Convenience: parses a config from a JSON string.
     *
     * @param json the JSON text
     * @return the parsed config
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public MapGenConfig parseString(final String json)
    {
        final JsonElement root;
        try
        {
            root = JsonParser.parseString(json);
        }
        catch (final RuntimeException e)
        {
            throw new IllegalArgumentException("malformed config JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isJsonObject())
        {
            throw new IllegalArgumentException("config root must be a JSON object");
        }
        return fromJson(root.getAsJsonObject());
    }

    // --- JSON helpers -----------------------------------------------------

    private static String readString(final JsonObject obj, final String field)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            throw new IllegalArgumentException("config requires field '" + field + "'");
        }
        return obj.get(field).getAsString();
    }

    private static String readStringOrNull(final JsonObject obj, final String field)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            return null;
        }
        return obj.get(field).getAsString();
    }

    private static int readIntOrDefault(final JsonObject obj, final String field, final int def)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            return def;
        }
        return obj.get(field).getAsInt();
    }

    private static float readFloatOrDefault(final JsonObject obj, final String field,
        final float def)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            return def;
        }
        return obj.get(field).getAsFloat();
    }

    private static JsonArray readArray(final JsonObject obj, final String field)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            throw new IllegalArgumentException("config requires field '" + field + "'");
        }
        final JsonElement element = obj.get(field);
        if (!element.isJsonArray())
        {
            throw new IllegalArgumentException("config field '" + field + "' must be an array");
        }
        return element.getAsJsonArray();
    }
}
