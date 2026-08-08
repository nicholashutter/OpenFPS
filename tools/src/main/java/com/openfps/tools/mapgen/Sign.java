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
 * A flat plane in world space: two triangles, no depth, no thickness.
 *
 * <p>Useful for ceiling lights, ground decals, suspended banners and the
 * one-sided surfaces a hand-built map would model as a stretched rectangle
 * of paper. {@code Box} is too much for these — a box emits 12 triangles
 * where a sign needs 2, and the wasted faces are visible from below the
 * decal or behind the banner.</p>
 *
 * <h2>Orientation</h2>
 *
 * <p>A sign is anchored at its centre, with a {@code width} along the
 * local x axis and a {@code height} along the local y axis. The plane is
 * rotated about the world y axis by {@code facingYawDegrees} and is then
 * either vertical (a wall) or horizontal (a floor or ceiling). Two
 * orientations are supported because the JSON schema is more honest as
 * "vertical with a yaw" than as a generic rotation matrix.</p>
 *
 * <ul>
 *   <li><b>Vertical (default)</b>: the plane sits in the xz plane, normal
 *       along the world z axis rotated by {@code facingYawDegrees}. Used
 *       for wall segments that don't need thickness, banners, signs.</li>
 *   <li><b>Horizontal</b>: the plane sits in the xy plane, normal along
 *       the world +y axis. Used for ground decals, ceiling tiles, suspended
 *       lighting panels.</li>
 * </ul>
 *
 * <h2>Why the "vertical" flag rather than a normal vector</h2>
 *
 * <p>Two real orientations cover everything a map needs. A general normal
 * vector would let callers specify things the renderer would then have to
 * reason about (backface cull, which side is "front") and would invite
 * near-vertical planes that read as flickering slivers in the demo. Two
 * orientations, each with a well-defined normal, sidestep that.</p>
 */
public final class Sign implements Primitive
{
    /** Submesh index for a wall (the natural home for a vertical sign). */
    public static final int SUBMESH_WALL = 1;

    /** Submesh index for an accent (the natural home for a horizontal decal). */
    public static final int SUBMESH_ACCENT = 2;

    private final float centerX;
    private final float centerY;
    private final float centerZ;
    private final float width;
    private final float height;
    private final float facingYawDegrees;
    private final boolean vertical;
    private final int submesh;
    private final String texture;
    private final float worldUnitsPerTile;

    /**
     * Creates a sign from explicit, validated parameters.
     *
     * <p>Prefer {@link #fromJson(JsonObject, float)} for code that reads
     * config files; this constructor is the seam the parser hands a finished
     * value to.</p>
     *
     * @param centerX the centre x
     * @param centerY the centre y
     * @param centerZ the centre z
     * @param width the local x extent; must be positive
     * @param height the local y extent; must be positive
     * @param facingYawDegrees rotation about the world y axis, in degrees
     * @param vertical true for a wall plane, false for a floor/ceiling plane
     * @param submesh the submesh this sign's triangles are appended to
     * @param texture the Kenney swatch name; must be non-blank
     * @param worldUnitsPerTile world-units-per-texture-repeat; must be positive
     */
    public Sign(final float centerX, final float centerY, final float centerZ, final float width,
        final float height, final float facingYawDegrees, final boolean vertical, final int submesh,
        final String texture, final float worldUnitsPerTile)
    {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.width = width;
        this.height = height;
        this.facingYawDegrees = facingYawDegrees;
        this.vertical = vertical;
        this.submesh = submesh;
        this.texture = Objects.requireNonNull(texture, "texture");
        this.worldUnitsPerTile = worldUnitsPerTile;
        if (texture.isBlank())
        {
            throw new IllegalArgumentException("texture must not be blank");
        }
    }

    /**
     * Builds a sign from a JSON object.
     *
     * <p>Reads {@code x}, {@code y}, {@code z} (the centre) and
     * {@code w}, {@code h} (width and height) as required fields. Reads
     * {@code yaw} (default 0), {@code vertical} (default true),
     * {@code submesh} (default {@link #SUBMESH_WALL}) and
     * {@code texture} (default {@code "accent"}).</p>
     *
     * @param obj the JSON object to read from; must not be null
     * @param worldUnitsPerTile the config's world-units-per-texture-repeat
     * @return the sign
     * @throws IllegalArgumentException if a required field is missing or
     *     outside its range
     */
    public static Sign fromJson(final JsonObject obj, final float worldUnitsPerTile)
    {
        final float x = readFloat(obj, "x");
        final float y = readFloat(obj, "y");
        final float z = readFloat(obj, "z");
        final float w = readPositiveFloat(obj, "w");
        final float h = readPositiveFloat(obj, "h");
        final float yaw = readFloatOrDefault(obj, "yaw", 0.0f);
        final boolean vertical = readBooleanOrDefault(obj, "vertical", true);
        final int submesh = readIntOrDefault(obj, "submesh", SUBMESH_WALL);
        final String texture = readStringOrDefault(obj, "texture", "accent");
        return new Sign(x, y, z, w, h, yaw, vertical, submesh, texture, worldUnitsPerTile);
    }

    @Override
    public String type()
    {
        return "sign";
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

    /** Returns the centre x of the sign. */
    public float centerX()
    {
        return centerX;
    }

    /** Returns the centre y of the sign. */
    public float centerY()
    {
        return centerY;
    }

    /** Returns the centre z of the sign. */
    public float centerZ()
    {
        return centerZ;
    }

    /** Returns the local x extent of the sign. */
    public float width()
    {
        return width;
    }

    /** Returns the local y extent of the sign. */
    public float height()
    {
        return height;
    }

    /** Returns the rotation about the world y axis, in degrees. */
    public float facingYawDegrees()
    {
        return facingYawDegrees;
    }

    /** Returns true for a wall plane, false for a floor/ceiling plane. */
    public boolean vertical()
    {
        return vertical;
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
        final float halfW = width * 0.5f;
        final float halfH = height * 0.5f;
        // Build the four corners in the local frame: width along local x, height
        // along local y. The yaw rotates the local x about the world y.
        final float yaw = (float) StrictMath.toRadians(facingYawDegrees);
        final float cosYaw = (float) StrictMath.cos(yaw);
        final float sinYaw = (float) StrictMath.sin(yaw);
        // Local corners (lx, ly)
        final float lAx = -halfW;
        final float lAy = -halfH;
        final float lBx = halfW;
        final float lBy = -halfH;
        final float lCx = halfW;
        final float lCy = halfH;
        final float lDx = -halfW;
        final float lDy = halfH;
        // World-space (wx, wz) = (lx * cos - 0 * sin, lx * sin + 0 * cos) for x
        // components; the y component is the local y. Rotation about world y
        // leaves the y component untouched.
        final float wAx = centerX + lAx * cosYaw;
        final float wAz = centerZ + lAx * sinYaw;
        final float wBx = centerX + lBx * cosYaw;
        final float wBz = centerZ + lBx * sinYaw;
        final float wCx = centerX + lCx * cosYaw;
        final float wCz = centerZ + lCx * sinYaw;
        final float wDx = centerX + lDx * cosYaw;
        final float wDz = centerZ + lDx * sinYaw;
        final float aWorldY;
        final float bWorldY;
        final float cWorldY;
        final float dWorldY;
        if (vertical)
        {
            // Wall: the local y is the world y, so corners are (wx, centerY + ly, wz).
            aWorldY = centerY + lAy;
            bWorldY = centerY + lBy;
            cWorldY = centerY + lCy;
            dWorldY = centerY + lDy;
        }
        else
        {
            // Floor/ceiling: the local y is the world z, so corners are
            // (wx, centerY, wz + ly). Yaw is unused here because horizontal signs
            // lie flat.
            aWorldY = centerY;
            bWorldY = centerY;
            cWorldY = centerY;
            dWorldY = centerY;
        }
        final int a;
        final int b;
        final int c;
        final int d;
        if (vertical)
        {
            // UVs use world (x, z) so adjacent wall signs tile together.
            a = builder.addVertex(wAx, aWorldY, wAz, wAx * uScale, wAz * uScale,
                Rgba.pack(255, 255, 255, 255));
            b = builder.addVertex(wBx, bWorldY, wBz, wBx * uScale, wBz * uScale,
                Rgba.pack(255, 255, 255, 255));
            c = builder.addVertex(wCx, cWorldY, wCz, wCx * uScale, wCz * uScale,
                Rgba.pack(255, 255, 255, 255));
            d = builder.addVertex(wDx, dWorldY, wDz, wDx * uScale, wDz * uScale,
                Rgba.pack(255, 255, 255, 255));
        }
        else
        {
            // UVs use world (x, y) so floor decals tile together.
            a = builder.addVertex(wAx, aWorldY, wAz + lAy, wAx * uScale, (wAz + lAy) * uScale,
                Rgba.pack(255, 255, 255, 255));
            b = builder.addVertex(wBx, bWorldY, wBz + lBy, wBx * uScale, (wBz + lBy) * uScale,
                Rgba.pack(255, 255, 255, 255));
            c = builder.addVertex(wCx, cWorldY, wCz + lCy, wCx * uScale, (wCz + lCy) * uScale,
                Rgba.pack(255, 255, 255, 255));
            d = builder.addVertex(wDx, dWorldY, wDz + lDy, wDx * uScale, (wDz + lDy) * uScale,
                Rgba.pack(255, 255, 255, 255));
        }
        builder.addTriangle(a, b, c);
        builder.addTriangle(a, c, d);
    }

    @Override
    public void validate()
    {
        if (width <= 0.0f)
        {
            throw new IllegalStateException("sign width must be positive: " + width);
        }
        if (height <= 0.0f)
        {
            throw new IllegalStateException("sign height must be positive: " + height);
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

    // --- JSON helpers -----------------------------------------------------

    private static float readFloat(final JsonObject obj, final String field)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            throw new IllegalArgumentException("sign requires field '" + field + "'");
        }
        return obj.get(field).getAsFloat();
    }

    private static float readPositiveFloat(final JsonObject obj, final String field)
    {
        final float value = readFloat(obj, field);
        if (value <= 0.0f)
        {
            throw new IllegalArgumentException("sign field '" + field + "' must be positive: "
                + value);
        }
        return value;
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

    private static boolean readBooleanOrDefault(final JsonObject obj, final String field,
        final boolean def)
    {
        if (!obj.has(field) || obj.get(field).isJsonNull())
        {
            return def;
        }
        return obj.get(field).getAsBoolean();
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
