/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.render.adapter.Rgba;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Tests for {@link KenneyTexture}.
 *
 * <p>The class samples a 32x32 swatch from the Kenney Prototype Kit's
 * 512x512 colormap.png and upscales it to a 64x64 tile. The tests
 * cover the load-bearing properties: the upsampled tile has the
 * expected size, every pixel in the tile is the same colour (because
 * each swatch is a solid block), and the input validation rejects
 * out-of-range swatch coordinates.</p>
 *
 * <p>Tests construct a tiny in-memory {@link BufferedImage} rather
 * than reading the real {@code colormap.png} from disk, so the
 * tests do not depend on the pack being staged. The contract being
 * tested is "given a 512x512 PNG and a swatch coordinate, return a
 * 64x64 tile of that swatch's colour"; the actual colour values
 * are not the subject of the assertions.</p>
 */
@DisplayName("KenneyTexture")
class KenneyTextureTest
{
    @Test
    @DisplayName("an out-of-range swatch column is rejected")
    void shouldRejectOutOfRangeColumn()
    {
        final BufferedImage atlas = newAtlas();
        final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try
        {
            ImageIO.write(atlas, "png", baos);
        }
        catch (final IOException e)
        {
            throw new RuntimeException(e);
        }
        final java.nio.file.Path tempPath = writeTemp(baos.toByteArray());
        assertThatThrownBy(() -> KenneyTexture.readSwatch(tempPath, -1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KenneyTexture.readSwatch(tempPath,
            KenneyTexture.SWATCHES_PER_ROW, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an out-of-range swatch row is rejected")
    void shouldRejectOutOfRangeRow()
    {
        final BufferedImage atlas = newAtlas();
        final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try
        {
            ImageIO.write(atlas, "png", baos);
        }
        catch (final IOException e)
        {
            throw new RuntimeException(e);
        }
        final java.nio.file.Path tempPath = writeTemp(baos.toByteArray());
        assertThatThrownBy(() -> KenneyTexture.readSwatch(tempPath, 0, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KenneyTexture.readSwatch(tempPath, 0,
            KenneyTexture.SWATCHES_PER_ROW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a 32x32 swatch upscales to a 64x64 tile of one solid colour")
    void shouldUpsampleSwatchToSolidTile()
    {
        // Build a 512x512 atlas where one swatch is bright red and
        // everything else is opaque grey. The contract is that
        // readSwatch returns 64x64 of the named swatch's colour,
        // regardless of what the rest of the atlas contains.
        final int swatchColour = Rgba.pack(220, 32, 32, 255);
        final int otherColour = Rgba.pack(96, 96, 96, 255);
        final BufferedImage atlas = new BufferedImage(KenneyTexture.ATLAS_EDGE,
            KenneyTexture.ATLAS_EDGE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < KenneyTexture.ATLAS_EDGE; y++)
        {
            for (int x = 0; x < KenneyTexture.ATLAS_EDGE; x++)
            {
                final int col = x / KenneyTexture.SWATCH_EDGE;
                final int row = y / KenneyTexture.SWATCH_EDGE;
                final int colour;
                if (col == 5 && row == 7)
                {
                    colour = swatchColour;
                }
                else
                {
                    colour = otherColour;
                }
                atlas.setRGB(x, y, colour);
            }
        }
        final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try
        {
            ImageIO.write(atlas, "png", baos);
        }
        catch (final IOException e)
        {
            throw new RuntimeException(e);
        }
        final java.nio.file.Path tempPath = writeTemp(baos.toByteArray());
        final int[] tile = KenneyTexture.readSwatch(tempPath, 5, 7);
        assertThat(tile).hasSize(KenneyTexture.TILE_EDGE * KenneyTexture.TILE_EDGE);
        // The swatch colour is one solid block, so every pixel of
        // the upsampled tile is the same RGB. The alpha may be 0
        // (TYPE_INT_ARGB) or 0xFF depending on the format; the
        // swatch is opaque, so we test RGB only.
        final int argb = tile[0];
        for (int index = 1; index < tile.length; index++)
        {
            final int rgb0 = argb & 0x00FFFFFF;
            final int rgbN = tile[index] & 0x00FFFFFF;
            assertThat(rgbN).as("tile pixel %d", index).isEqualTo(rgb0);
        }
        assertThat(argb & 0x00FFFFFF).isEqualTo(swatchColour & 0x00FFFFFF);
    }

    @Test
    @DisplayName("forceOpaque forces the alpha channel to 0xFF on every texel")
    void shouldForceOpaque()
    {
        // Build an array with several pixels whose alpha is 0, then
        // call forceOpaque and check every alpha is now 0xFF. The
        // Rgba convention is 0xRRGGBBAA, so the alpha channel sits
        // in the bottom 8 bits — not the top 8 bits.
        final int[] texels = new int[KenneyTexture.TILE_EDGE * KenneyTexture.TILE_EDGE];
        for (int index = 0; index < texels.length; index++)
        {
            texels[index] = Rgba.pack(100, 100, 100, 0);
        }
        KenneyTexture.forceOpaque(texels);
        for (final int texel : texels)
        {
            assertThat(texel & 0xFF).isEqualTo(0xFF);
        }
    }

    @Test
    @DisplayName("forceOpaque leaves already-opaque texels alone")
    void shouldLeaveOpaqueTexelsAlone()
    {
        final int[] texels = new int[KenneyTexture.TILE_EDGE * KenneyTexture.TILE_EDGE];
        for (int index = 0; index < texels.length; index++)
        {
            texels[index] = Rgba.pack(100, 100, 100, 255);
        }
        KenneyTexture.forceOpaque(texels);
        for (final int texel : texels)
        {
            assertThat(texel).isEqualTo(Rgba.pack(100, 100, 100, 255));
        }
    }

    // A 512x512 atlas of one neutral grey, with every pixel
    // explicitly opaque. The tests above use this as a fallback
    // when a custom atlas is not what they need to test.
    private static BufferedImage newAtlas()
    {
        final BufferedImage atlas = new BufferedImage(KenneyTexture.ATLAS_EDGE,
            KenneyTexture.ATLAS_EDGE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < KenneyTexture.ATLAS_EDGE; y++)
        {
            for (int x = 0; x < KenneyTexture.ATLAS_EDGE; x++)
            {
                atlas.setRGB(x, y, Rgba.pack(128, 128, 128, 255));
            }
        }
        return atlas;
    }

    // Writes the PNG bytes to a temp file and returns the path.
    // The file is left for the test JVM to clean up.
    private static java.nio.file.Path writeTemp(final byte[] bytes)
    {
        try
        {
            final java.nio.file.Path temp = java.nio.file.Files.createTempFile("kenney-atlas-",
                ".png");
            java.nio.file.Files.write(temp, bytes);
            return temp;
        }
        catch (final IOException e)
        {
            throw new RuntimeException(e);
        }
    }
}
