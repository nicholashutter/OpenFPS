/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, validated representation of one map generator config file.
 *
 * <p>Build-time only. A config file is the entire source of truth for a map's
 * geometry: the generator has no other inputs, and two configs with the same
 * bytes produce byte-identical {@code .ofm} output. That property is what
 * makes "write a JSON, run a Gradle task" the right workflow — the file is
 * checked in, diff-friendly, and reviewable in a normal pull request.</p>
 *
 * <h2>Schema</h2>
 *
 * <p>The JSON shape is {@link JsonConfigParser#parse} contract. Briefly:</p>
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
 *     { "type": "box", ... },
 *     { "type": "sign", ... }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code id} is the stable identifier the rest of the engine uses
 * ({@code --map=id}). The other fields are documentation — the generator
 * itself only consumes {@code id} and the primitives list; the rest are
 * available for the broader library tooling (menus, settings, mode tagging)
 * that this pass does not change.</p>
 *
 * <h2>Primitives are flat</h2>
 *
 * <p>The primitive list is the only "language" the config speaks. There is no
 * sugar for lanes or cut-throughs: every box, every sign is named explicitly.
 * That is deliberate for the first pass — sugar is a multiplier on the
 * primitive vocabulary and the vocabulary has to exist before it is worth
 * multiplying. {@link Box} and {@link Sign} are the only types in the first
 * pass; both are documented on their own classes and the factory
 * ({@link PrimitiveFactory}) is the single place a new type has to be
 * registered.</p>
 */
public final class MapGenConfig
{
    private final String id;
    private final String displayName;
    private final String setting;
    private final String mode;
    private final int textureEdge;
    private final float worldUnitsPerTile;
    private final List<Primitive> primitives;

    /**
     * Creates a config from already-validated parts.
     *
     * <p>Public so {@link JsonConfigParser} can hand back a finished value;
     * callers that build a config by hand should prefer the parser, which is
     * the one place the JSON field names and types are defined.</p>
     *
     * @param id the stable map id; must be non-blank
     * @param displayName the menu name; must be non-blank
     * @param setting the visual setting; may be null
     * @param mode the multiplayer mode tag; may be null
     * @param textureEdge the Kenney tile edge; must be a positive power of two
     * @param worldUnitsPerTile the texture's world-units-per-repeat; must be
     *     positive
     * @param primitives the primitive list; must be non-null and non-empty
     */
    public MapGenConfig(final String id, final String displayName, final String setting,
        final String mode, final int textureEdge, final float worldUnitsPerTile,
        final List<Primitive> primitives)
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("id must not be blank");
        }

        if (displayName == null || displayName.isBlank())
        {
            throw new IllegalArgumentException("displayName must not be blank");
        }

        if (textureEdge <= 0 || (textureEdge & (textureEdge - 1)) != 0)
        {
            throw new IllegalArgumentException("textureEdge must be a positive power of two: "
                + textureEdge);
        }

        if (worldUnitsPerTile <= 0.0f)
        {
            throw new IllegalArgumentException("worldUnitsPerTile must be positive: "
                + worldUnitsPerTile);
        }

        this.id = Objects.requireNonNull(id, "id").intern();

        this.displayName = Objects.requireNonNull(displayName, "displayName").intern();

        if (setting == null || setting.isBlank())
        {
            this.setting = null;
        }
        else
        {
            this.setting = setting.intern();
        }

        if (mode == null || mode.isBlank())
        {
            this.mode = null;
        }
        else
        {
            this.mode = mode.intern();
        }

        this.textureEdge = textureEdge;

        this.worldUnitsPerTile = worldUnitsPerTile;

        this.primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));

        if (this.primitives.isEmpty())
        {
            throw new IllegalArgumentException("primitives must not be empty");
        }
    }

    /** Returns the stable map id. */
    public String id()
    {
        return id;
    }

    /** Returns the human-readable map name. */
    public String displayName()
    {
        return displayName;
    }

    /** Returns the visual setting tag, or null if not specified. */
    public String setting()
    {
        return setting;
    }

    /** Returns the multiplayer mode tag, or null if not specified. */
    public String mode()
    {
        return mode;
    }

    /** Returns the Kenney tile edge in texels (a power of two). */
    public int textureEdge()
    {
        return textureEdge;
    }

    /** Returns the texture's world-units-per-repeat. */
    public float worldUnitsPerTile()
    {
        return worldUnitsPerTile;
    }

    /** Returns the immutable primitive list. */
    public List<Primitive> primitives()
    {
        return primitives;
    }
}
