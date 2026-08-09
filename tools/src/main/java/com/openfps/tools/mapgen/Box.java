/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import java.util.Objects;

import com.openfps.engine.render.adapter.Rgba;
import com.openfps.tools.model.ModelBuilder;

import com.google.gson.JsonObject;

/**
 * A closed axis-aligned box: a rectangular solid that emits twelve triangles
 * across six faces, two per face.
 *
 * <p>The single most common primitive in any COD-style map. A floor slab, a
 * perimeter wall, a landmark building, a crate — they are all boxes with a
 * texture and a placement, and the only thing that changes between them is
 * which swatch they sample. The class reflects that: every box carries its
 * extent, its position and the swatch it samples, and nothing else.</p>
 *
 * <h2>UVs</h2>
 *
 * <p>Each face's UVs are world position divided by
 * {@link #worldUnitsPerTile()} so the texture tiles seamlessly across pieces
 * of different sizes. The transform is the same one the existing
 * {@code CornerstoneMapBuilder} uses, so a config-driven box reads as
 * visually consistent with a hand-written one.</p>
 *
 * <h2>Orientation</h2>
 *
 * <p>Six faces, each wound counter-clockwise as seen from outside — the
 * backface cull in the renderer rejects the rest. UVs grow with the world
 * position rather than with the face-local position, so the texture is
 * continuous across shared edges of two boxes that meet, and a 320-unit
 * floor reads as one big tiled surface rather than as a 320-row mosaic of
 * 64-pixel squares.</p>
 */
public final class Box implements Primitive
{
    /** Submesh index for the floor. */
    public static final int SUBMESH_FLOOR = 0;

    /** Submesh index for a wall. */
    public static final int SUBMESH_WALL = 1;

    /** Submesh index for an accent (streetlight, trim, landmark). */
    public static final int SUBMESH_ACCENT = 2;

    private final float minX;
    private final float minY;
    private final float minZ;
    private final float sizeX;
    private final float sizeY;
    private final float sizeZ;
    private final int submesh;
    private final String texture;
    private final float worldUnitsPerTile;

    /**
     * Creates a box from explicit, validated parameters.
     *
     * <p>Prefer {@link #fromJson(JsonObject, float)} for code that reads
     * config files; this constructor is the seam the parser hands a finished
     * value to.</p>
     *
     * @param minX the minimum x (face) of the box
     * @param minY the minimum y (face) of the box
     * @param minZ the minimum z (face) of the box
     * @param sizeX the box's extent in x, must be positive
     * @param sizeY the box's extent in y, must be positive
     * @param sizeZ the box's extent in z, must be positive
     * @param submesh the submesh this box's triangles are appended to
     * @param texture the Kenney swatch name; must be non-blank
     * @param worldUnitsPerTile world-units-per-texture-repeat; must be positive
     */
    public Box(final float minX, final float minY, final float minZ, final float sizeX,
        final float sizeY, final float sizeZ, final int submesh, final String texture,
        final float worldUnitsPerTile)
    {
        this.minX = minX;

        this.minY = minY;

        this.minZ = minZ;

        this.sizeX = sizeX;

        this.sizeY = sizeY;

        this.sizeZ = sizeZ;

        this.submesh = submesh;

        this.texture = Objects.requireNonNull(texture, "texture");

        this.worldUnitsPerTile = worldUnitsPerTile;

        if (texture.isBlank())
        {
            throw new IllegalArgumentException("texture must not be blank");
        }
    }

    /**
     * Builds a box from a JSON object.
     *
     * <p>Reads {@code x}, {@code y}, {@code z} (the minimum corner) and
     * {@code sx}, {@code sy}, {@code sz} (the size in each axis) as required
     * fields. Reads {@code submesh} (default {@link #SUBMESH_WALL}) and
     * {@code texture} (default {@code "wall"}). The {@code texture} field
     * names a Kenney swatch; the {@code submesh} field names a draw group.</p>
     *
     * @param obj the JSON object to read from; must not be null
     * @param worldUnitsPerTile the config's world-units-per-texture-repeat;
     *     applied to this box's UVs
     * @return the box
     * @throws IllegalArgumentException if a required field is missing or
     *     outside its range
     */
    public static Box fromJson(final JsonObject obj, final float worldUnitsPerTile)
    {
        final float x = readFloat(obj, "x");

        final float y = readFloat(obj, "y");

        final float z = readFloat(obj, "z");

        final float sx = readPositiveFloat(obj, "sx");

        final float sy = readPositiveFloat(obj, "sy");

        final float sz = readPositiveFloat(obj, "sz");

        final int submesh = readIntOrDefault(obj, "submesh", SUBMESH_WALL);

        final String texture = readStringOrDefault(obj, "texture", "wall");

        return new Box(x, y, z, sx, sy, sz, submesh, texture, worldUnitsPerTile);
    }

    @Override
    public String type()
    {
        return "box";
    }

    @Override
    public int submesh()
    {
        return submesh;
    }

    @Override
    public String texture()
    {
        return texture;
    }

    /** Returns the minimum x corner of the box. */
    public float minX()
    {
        return minX;
    }

    /** Returns the minimum y corner of the box. */
    public float minY()
    {
        return minY;
    }

    /** Returns the minimum z corner of the box. */
    public float minZ()
    {
        return minZ;
    }

    /** Returns the box's extent along the x axis. */
    public float sizeX()
    {
        return sizeX;
    }

    /** Returns the box's extent along the y axis. */
    public float sizeY()
    {
        return sizeY;
    }

    /** Returns the box's extent along the z axis. */
    public float sizeZ()
    {
        return sizeZ;
    }

    /** Returns the world-units-per-texture-repeat used for UVs. */
    public float worldUnitsPerTile()
    {
        return worldUnitsPerTile;
    }

    @Override
    public void addTo(final ModelBuilder builder, final int textureIndex)
    {
        if (textureIndex < 0)
        {
            throw new IllegalArgumentException("textureIndex must be non-negative: "
                + textureIndex);
        }

        final float uScale = 1.0f / worldUnitsPerTile;

        final float ax = minX;

        final float ay = minY;

        final float az = minZ;

        final float bx = minX + sizeX;

        final float by = minY + sizeY;

        final float bz = minZ + sizeZ;

        // +x face
        addFace(builder, bx, ay, bz, bx, by, bz, bx, by, az, bx, ay, az, uScale);

        // -x face
        addFace(builder, ax, ay, az, ax, by, az, ax, by, bz, ax, ay, bz, uScale);

        // +y face
        addFace(builder, ax, by, bz, bx, by, bz, bx, by, az, ax, by, az, uScale);

        // -y face
        addFace(builder, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz, uScale);

        // +z face
        addFace(builder, ax, ay, bz, ax, by, bz, bx, by, bz, bx, ay, bz, uScale);

        // -z face
        addFace(builder, bx, ay, az, bx, by, az, ax, by, az, ax, ay, az, uScale);
    }

    @Override
    public void validate()
    {
        if (sizeX <= 0.0f || sizeY <= 0.0f || sizeZ <= 0.0f)
        {
            throw new IllegalStateException("box sizes must be positive: "
                + sizeX + ", " + sizeY + ", " + sizeZ);
        }

        if (submesh < 0)
        {
            throw new IllegalStateException("submesh must be non-negative: " + submesh);
        }

        if (worldUnitsPerTile <= 0.0f)
        {
            throw new IllegalStateException("worldUnitsPerTile must be positive: "
                + worldUnitsPerTile);
        }
    }

    // Two triangles (a, b, c) and (a, c, d), UVs scaled to the tile.
    private static void addFace(final ModelBuilder builder, final float ax, final float ay,
        final float az, final float bx, final float by, final float bz, final float cx,
        final float cy, final float cz, final float dx, final float dy, final float dz,
        final float uScale)
    {
        final int a = builder.addVertex(ax, ay, az, ax * uScale, az * uScale,
            Rgba.pack(255, 255, 255, 255));

        final int b = builder.addVertex(bx, by, bz, bx * uScale, bz * uScale,
            Rgba.pack(255, 255, 255, 255));

        final int c = builder.addVertex(cx, cy, cz, cx * uScale, cz * uScale,
            Rgba.pack(255, 255, 255, 255));

        final int d = builder.addVertex(dx, dy, dz, dx * uScale, dz * uScale,
            Rgba.pack(255, 255, 255, 255));

        builder.addTriangle(a, b, c);

        builder.addTriangle(a, c, d);
    }

    // --- JSON helpers -----------------------------------------------------

    private static float readFloat(final JsonObject obj, final String field)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            throw new IllegalArgumentException("box requires field '" + field + "'");
        }

        return obj.get(field).getAsFloat();
    }

    private static float readPositiveFloat(final JsonObject obj, final String field)
    {
        final float value = readFloat(obj, field);

        if (value <= 0.0f)
        {
            throw new IllegalArgumentException("box field '" + field + "' must be positive: "
                + value);
        }

        return value;
    }

    private static int readIntOrDefault(final JsonObject obj, final String field, final int def)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            return def;
        }

        return obj.get(field).getAsInt();
    }

    private static String readStringOrDefault(final JsonObject obj, final String field,
        final String def)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            return def;
        }

        return obj.get(field).getAsString();
    }
}
