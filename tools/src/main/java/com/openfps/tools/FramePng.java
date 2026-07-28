/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import com.openfps.engine.render.adapter.SoftwareRenderPort;

/**
 * Writes a finished software-rasterizer frame to a PNG.
 *
 * Build-time only — this class is never on a runtime classpath.
 *
 * <p><b>The channel order is the whole of this class and it is easy to get
 * wrong.</b> {@code Rgba} packs {@code 0xRRGGBBAA} and
 * {@link BufferedImage#TYPE_INT_ARGB} wants {@code 0xAARRGGBB}, so the
 * conversion is one rotate: shift the alpha off the bottom and put opaque back
 * on the top. Getting it backwards produces a plausible but channel-swapped
 * image — which is exactly the class of bug a preview tool exists to catch, so
 * it is spelled out here rather than hidden behind a helper, and it lives in
 * one place so the two tools cannot disagree about it.</p>
 */
public final class FramePng
{
    /** Shift turning a {@code 0xRRGGBBAA} pixel into {@code 0x00RRGGBB}. */
    private static final int ALPHA_BITS = 8;

    /** Opaque alpha for the {@code TYPE_INT_ARGB} image. */
    private static final int OPAQUE_ARGB = 0xFF000000;

    private FramePng()
    {
        // conversion helper
    }

    /**
     * Copies the renderer's finished frame and writes it as a PNG.
     *
     * <p>Parent directories are created if they do not exist.</p>
     *
     * @param renderer the renderer holding the finished frame
     * @param out where to write the PNG
     * @param width the frame width in pixels
     * @param height the frame height in pixels
     * @throws IOException if no frame is available or the file cannot be written
     */
    public static void write(final SoftwareRenderPort renderer, final Path out,
        final int width, final int height) throws IOException
    {
        final int[] frame = new int[width * height];
        if (!renderer.copyColorInto(frame))
        {
            throw new IOException("renderer produced no frame");
        }
        final BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                png.setRGB(x, y, (frame[y * width + x] >>> ALPHA_BITS) | OPAQUE_ARGB);
            }
        }
        if (out.getParent() != null)
        {
            Files.createDirectories(out.getParent());
        }
        ImageIO.write(png, "png", out.toFile());
    }
}
