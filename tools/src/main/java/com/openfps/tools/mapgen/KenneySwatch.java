/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import java.nio.file.Path;

import com.openfps.tools.model.KenneyTexture;

/**
 * One Kenney swatch the config can name: a name the config uses, the atlas
 * coordinates it samples, and the loader that turns the swatch into a
 * 64x64 RGBA tile.
 *
 * <p>The class is the seam between the config schema and the
 * {@link KenneyTexture} helper: every swatch a config can name is bound to
 * one of the seven fixed swatch positions in the Kenney Prototype Kit's
 * colormap, and the binding is the whole API. Adding a swatch is adding a
 * {@link #registerBuiltins(PrimitiveFactory)} call; a config that names an
 * unknown swatch fails the build at parse time.</p>
 */
public final class KenneySwatch
{
    /** Conventional name for the floor swatch (a Kenney Prototype Kit swatch). */
    public static final String NAME_FLOOR = "floor";

    /** Conventional name for the wall swatch. */
    public static final String NAME_WALL = "wall";

    /** Conventional name for the crate swatch. */
    public static final String NAME_CRATE = "crate";

    /** Conventional name for the column swatch. */
    public static final String NAME_COLUMN = "column";

    /** Conventional name for the first accent swatch. */
    public static final String NAME_ACCENT = "accent";

    /** Conventional name for the second accent swatch (red). */
    public static final String NAME_ACCENT_RED = "accentRed";

    /** Conventional name for the third accent swatch (orange). */
    public static final String NAME_ACCENT_ORANGE = "accentOrange";

    private final String name;
    private final int swatchCol;
    private final int swatchRow;

    /**
     * Creates a swatch binding.
     *
     * @param name the conventional name the config uses; must be non-blank
     * @param swatchCol the swatch's column in the Kenney atlas, 0..15
     * @param swatchRow the swatch's row in the Kenney atlas, 0..15
     */
    public KenneySwatch(final String name, final int swatchCol, final int swatchRow)
    {
        this.name = name;
        this.swatchCol = swatchCol;
        this.swatchRow = swatchRow;
    }

    /**
     * Registers every built-in swatch with the given factory.
     *
     * <p>Called from {@link PrimitiveFactory#createDefault()}, so a factory
     * built the default way is ready to validate swatch names out of the
     * box.</p>
     *
     * @param factory the factory to register with
     */
    public static void registerBuiltins(final PrimitiveFactory factory)
    {
        factory.registerSwatch(NAME_FLOOR, new KenneySwatch(NAME_FLOOR,
            KenneyTexture.FLOOR_SWATCH_COL, KenneyTexture.FLOOR_SWATCH_ROW));
        factory.registerSwatch(NAME_WALL, new KenneySwatch(NAME_WALL,
            KenneyTexture.WALL_SWATCH_COL, KenneyTexture.WALL_SWATCH_ROW));
        factory.registerSwatch(NAME_CRATE, new KenneySwatch(NAME_CRATE,
            KenneyTexture.CRATE_SWATCH_COL, KenneyTexture.CRATE_SWATCH_ROW));
        factory.registerSwatch(NAME_COLUMN, new KenneySwatch(NAME_COLUMN,
            KenneyTexture.COLUMN_SWATCH_COL, KenneyTexture.COLUMN_SWATCH_ROW));
        factory.registerSwatch(NAME_ACCENT, new KenneySwatch(NAME_ACCENT,
            KenneyTexture.ACCENT_SWATCH_COL, KenneyTexture.ACCENT_SWATCH_ROW));
        factory.registerSwatch(NAME_ACCENT_RED, new KenneySwatch(NAME_ACCENT_RED,
            KenneyTexture.ACCENT2_SWATCH_COL, KenneyTexture.ACCENT2_SWATCH_ROW));
        factory.registerSwatch(NAME_ACCENT_ORANGE, new KenneySwatch(NAME_ACCENT_ORANGE,
            KenneyTexture.ACCENT3_SWATCH_COL, KenneyTexture.ACCENT3_SWATCH_ROW));
    }

    /** Returns the conventional name of the swatch. */
    public String name()
    {
        return name;
    }

    /** Returns the swatch's column in the Kenney atlas, 0..15. */
    public int swatchCol()
    {
        return swatchCol;
    }

    /** Returns the swatch's row in the Kenney atlas, 0..15. */
    public int swatchRow()
    {
        return swatchRow;
    }

    /**
     * Loads this swatch from the named atlas as a 64x64 RGBA tile.
     *
     * <p>Delegates to {@link KenneyTexture#readSwatch(Path, int, int)}; the
     * force-opaque step the existing {@code CornerstoneMapBuilder} runs is
     * applied by the generator after this call, not here, so this method
     * stays a thin pass-through.</p>
     *
     * @param atlasPath the path to the Kenney colormap.png; may be null,
     *     in which case null is returned and the caller is expected to
     *     fall back to a procedural texture
     * @return the tile as packed RGBA8888 texels, or null if no atlas was given
     */
    public int[] load(final Path atlasPath)
    {
        if (atlasPath == null)
        {
            return null;
        }
        return KenneyTexture.readSwatch(atlasPath, swatchCol, swatchRow);
    }
}
