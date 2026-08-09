/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import com.openfps.engine.render.adapter.Rgba;

/**
 * Reads a 64x64 RGBA tile from the Kenney Prototype Kit's
 * {@code colormap.png}.
 *
 * <p>Build-time only — this class is never on a runtime classpath.
 * {@code colormap.png} is a 512x512 swatch atlas: a 16x16 grid of 32x32
 * solid-colour blocks. Every model in the kit references one of those
 * blocks by UV. {@link #MipGenerator} generates the mip chain the runtime
 * needs, so the tile we hand back here is the only texture content the
 * build tool needs to produce.</p>
 *
 * <p><b>Swatch coordinates.</b> The atlas is laid out as 16 columns
 * (x = 0..15) by 16 rows (y = 0..15) of 32x32 swatches. The colour of
 * each swatch is fixed by the pack; this class samples it. The expected
 * colours at the named swatches are documented at the field declarations
 * and were eyeballed from the atlas at import time.</p>
 *
 * <h2>Why a class rather than a constants file</h2>
 *
 * <p>The pack ships a single image and the swatch coordinates are the
 * entire API. Encoding the swatch positions in named constants makes a
 * future atlas revision (a different colour scheme, say) a one-line
 * change, and the build tool stays a build tool: the swatch positions
 * travel with the code that reads them.</p>
 */
public final class KenneyTexture
{
    /** Width and height of the Kenney Prototype Kit colormap.png. */
    public static final int ATLAS_EDGE = 512;

    /** Edge of one swatch inside the atlas. 16 swatches per row, 16 per column. */
    public static final int SWATCH_EDGE = 32;

    /** Number of swatches per row in the atlas. */
    public static final int SWATCHES_PER_ROW = 16;

    /** Output tile edge (a power of two, per the sampler's wrap). */
    public static final int TILE_EDGE = 64;

    /**
     * Swatch (col, row) sampled for the floor texture. Row 0 column 0 is the
     * light grey that the kit's floor-square piece uses.
     */
    public static final int FLOOR_SWATCH_COL = 0;

    /** The row of the floor swatch in the kit's colormap. */
    public static final int FLOOR_SWATCH_ROW = 0;

    /**
     * Swatch (col, row) sampled for the wall texture. Row 2 column 0 is the
     * dark grey that the kit's wall and wall-corner pieces use.
     */
    public static final int WALL_SWATCH_COL = 0;

    /** The row of the wall swatch in the kit's colormap. */
    public static final int WALL_SWATCH_ROW = 2;

    /**
     * Swatch (col, row) sampled for the crate texture. Row 2 column 4 is
     * the medium navy that the kit's crate piece uses.
     */
    public static final int CRATE_SWATCH_COL = 4;

    /** The row of the crate swatch in the kit's colormap. */
    public static final int CRATE_SWATCH_ROW = 2;

    /**
     * Swatch (col, row) sampled for the column texture. Row 2 column 2 is
     * the deep blue that the kit's column piece uses.
     */
    public static final int COLUMN_SWATCH_COL = 2;

    /** The row of the column swatch in the kit's colormap. */
    public static final int COLUMN_SWATCH_ROW = 2;

    /**
     * Swatch (col, row) sampled for accent / trim geometry. Row 1 column 0
     * is the saturated pink that the kit uses for "marker" geometry in its
     * preview renders.
     */
    public static final int ACCENT_SWATCH_COL = 0;

    /** The row of the accent swatch in the kit's colormap. */
    public static final int ACCENT_SWATCH_ROW = 1;

    /**
     * Swatch (col, row) sampled for a second accent / trim colour. Row 1
     * column 7 is the saturated red used by several of the kit's pieces.
     */
    public static final int ACCENT2_SWATCH_COL = 7;

    /** The row of the second-accent swatch in the kit's colormap. */
    public static final int ACCENT2_SWATCH_ROW = 1;

    /**
     * Swatch (col, row) sampled for a third accent / trim colour. Row 1
     * column 4 is the saturated orange that pairs with the pink and red
     * above.
     */
    public static final int ACCENT3_SWATCH_COL = 4;

    /** The row of the third-accent swatch in the kit's colormap. */
    public static final int ACCENT3_SWATCH_ROW = 1;

    private KenneyTexture()
    {
        // entry point holder
    }

    /**
     * Reads a {@link #TILE_EDGE}x{@link #TILE_EDGE} RGBA tile from the
     * Kenney Prototype Kit's colormap.png. The tile is built by nearest-
     * neighbour upsampling of the named 32x32 swatch to 64x64, so the
     * output holds the same solid colour as the original swatch, just at
     * the engine's preferred texture resolution.
     *
     * <p>The atlas path is taken on trust. A missing atlas fails the
     * build with a clear message rather than producing a model that
     * silently has a black or white texture in place of the kit's
     * colours.</p>
     *
     * @param atlasPath the path to the colormap.png
     * @param swatchCol the swatch's column, 0..{@link #SWATCHES_PER_ROW}-1
     * @param swatchRow the swatch's row, 0..{@link #SWATCHES_PER_ROW}-1
     * @return an {@code int[TILE_EDGE * TILE_EDGE]} of packed RGBA texels
     */
    public static int[] readSwatch(final Path atlasPath, final int swatchCol, final int swatchRow)
    {
        if (swatchCol < 0 || swatchCol >= SWATCHES_PER_ROW)
        {
            throw new IllegalArgumentException(
                "swatchCol out of range: " + swatchCol);
        }

        if (swatchRow < 0 || swatchRow >= SWATCHES_PER_ROW)
        {
            throw new IllegalArgumentException(
                "swatchRow out of range: " + swatchRow);
        }

        final BufferedImage atlas;

        try
        {
            atlas = ImageIO.read(Files.newInputStream(atlasPath));
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("could not read Kenney atlas: " + atlasPath, e);
        }

        if (atlas == null)
        {
            throw new IllegalStateException("Kenney atlas did not decode: " + atlasPath);
        }

        if (atlas.getWidth() != ATLAS_EDGE || atlas.getHeight() != ATLAS_EDGE)
        {
            throw new IllegalStateException("Kenney atlas is not " + ATLAS_EDGE + "x" + ATLAS_EDGE
                + " (got " + atlas.getWidth() + "x" + atlas.getHeight() + "): " + atlasPath);
        }

        final int swatchX = swatchCol * SWATCH_EDGE;

        final int swatchY = swatchRow * SWATCH_EDGE;

        // Nearest-neighbour upsample: 32x32 -> 64x64. The whole swatch is
        // a single solid colour, so the upsample preserves the colour
        // exactly; doing it this way keeps the engine's TILE_EDGE
        // contract without committing to a smoothing filter that would
        // pretend the swatch is more than it is.
        final int[] tile = new int[TILE_EDGE * TILE_EDGE];

        for (int y = 0; y < TILE_EDGE; y++)
        {
            final int srcY = swatchY + (y * SWATCH_EDGE / TILE_EDGE);

            for (int x = 0; x < TILE_EDGE; x++)
            {
                final int srcX = swatchX + (x * SWATCH_EDGE / TILE_EDGE);

                tile[y * TILE_EDGE + x] = atlas.getRGB(srcX, srcY);
            }
        }

        return tile;
    }

    /**
     * Reads a 64x64 floor tile from the named atlas.
     *
     * @param atlasPath the path to the colormap.png
     * @return the floor tile
     */
    public static int[] floor(final Path atlasPath)
    {
        return readSwatch(atlasPath, FLOOR_SWATCH_COL, FLOOR_SWATCH_ROW);
    }

    /**
     * Reads a 64x64 wall tile from the named atlas.
     *
     * @param atlasPath the path to the colormap.png
     * @return the wall tile
     */
    public static int[] wall(final Path atlasPath)
    {
        return readSwatch(atlasPath, WALL_SWATCH_COL, WALL_SWATCH_ROW);
    }

    /**
     * Reads a 64x64 crate tile from the named atlas.
     *
     * @param atlasPath the path to the colormap.png
     * @return the crate tile
     */
    public static int[] crate(final Path atlasPath)
    {
        return readSwatch(atlasPath, CRATE_SWATCH_COL, CRATE_SWATCH_ROW);
    }

    /**
     * Reads a 64x64 column tile from the named atlas.
     *
     * @param atlasPath the path to the colormap.png
     * @return the column tile
     */
    public static int[] column(final Path atlasPath)
    {
        return readSwatch(atlasPath, COLUMN_SWATCH_COL, COLUMN_SWATCH_ROW);
    }

    /**
     * Reads a 64x64 accent (trim) tile from the named atlas.
     *
     * @param atlasPath the path to the colormap.png
     * @return the accent tile
     */
    public static int[] accent(final Path atlasPath)
    {
        return readSwatch(atlasPath, ACCENT_SWATCH_COL, ACCENT_SWATCH_ROW);
    }

    /**
     * Reads a 64x64 second-accent (red trim) tile from the named atlas.
     *
     * @param atlasPath the path to the colormap.png
     * @return the second-accent tile
     */
    public static int[] accentRed(final Path atlasPath)
    {
        return readSwatch(atlasPath, ACCENT2_SWATCH_COL, ACCENT2_SWATCH_ROW);
    }

    /**
     * Reads a 64x64 third-accent (orange trim) tile from the named atlas.
     *
     * @param atlasPath the path to the colormap.png
     * @return the third-accent tile
     */
    public static int[] accentOrange(final Path atlasPath)
    {
        return readSwatch(atlasPath, ACCENT3_SWATCH_COL, ACCENT3_SWATCH_ROW);
    }

    /**
     * Returns a single opaque RGBA sample of the named swatch, useful
     * when a model needs a solid colour (e.g. a vertex tint) rather than
     * a full 64x64 texture.
     *
     * @param atlasPath the path to the colormap.png
     * @param swatchCol the swatch's column, 0..{@link #SWATCHES_PER_ROW}-1
     * @param swatchRow the swatch's row, 0..{@link #SWATCHES_PER_ROW}-1
     * @return the packed {@code 0xRRGGBBAA} colour
     */
    public static int sample(final Path atlasPath, final int swatchCol, final int swatchRow)
    {
        return readSwatch(atlasPath, swatchCol, swatchRow)[0] | 0xFF000000;
    }

    /**
     * The pack's neutral wall colour as a packed RGBA, regardless of
     * whether the swatch's alpha is 0xFF.
     *
     * @param atlasPath the path to the colormap.png
     * @return the packed wall colour
     */
    public static int wallColor(final Path atlasPath)
    {
        return sample(atlasPath, WALL_SWATCH_COL, WALL_SWATCH_ROW);
    }

    /**
     * The pack's neutral floor colour as a packed RGBA.
     *
     * @param atlasPath the path to the colormap.png
     * @return the packed floor colour
     */
    public static int floorColor(final Path atlasPath)
    {
        return sample(atlasPath, FLOOR_SWATCH_COL, FLOOR_SWATCH_ROW);
    }

    /**
     * Discards the alpha channel of a packed RGBA texel array. The
     * Kenney colormap is fully opaque; the build tool reuses the same
     * array shape the runtime expects (where the alpha channel is
     * significant), so this is a safety pass rather than a
     * transformation.
     *
     * @param texels the texel array
     * @return the same array (mutated in place)
     */
    public static int[] forceOpaque(final int[] texels)
    {
        for (int index = 0; index < texels.length; index++)
        {
            // The Kenney atlas is opaque everywhere, but the
            // engine's Rgba.pack convention is 0xRRGGBBAA — the
            // alpha channel is the bottom 8 bits, not the top
            // 8 (which is the BufferedImage's TYPE_INT_ARGB
            // convention, 0xAARRGGBB). Force the engine's alpha
            // to 0xFF on every texel that came in transparent.
            if ((texels[index] & 0x000000FF) == 0)
            {
                texels[index] = texels[index] | 0x000000FF;
            }
        }

        return texels;
    }

    /**
     * Holds the Rgba channel order used by the build tools.
     *
     * <p>The {@link BufferedImage} returned by {@code ImageIO.read} is
     * {@code TYPE_INT_ARGB}, which is {@code 0xAARRGGBB}; the engine's
     * {@link Rgba#pack} is {@code 0xRRGGBBAA}. This constant exists so
     * future readers do not have to re-derive the conversion.</p>
     */
    public static final int CHANNEL_ROTATION_BITS = 24;
}
