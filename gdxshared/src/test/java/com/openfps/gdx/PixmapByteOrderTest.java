/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.GdxNativesLoader;

import com.openfps.engine.render.adapter.Framebuffer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The presentation-side half of the colour round trip.
 *
 * {@code render/README.md} section 12 puts this check in the adapter's tests
 * rather than in R_, because R_ must not know what a window is.
 * {@code docs/ASSETS.md} section 4 asks that the RGBA8888 layout be proved
 * rather than read off a document: an {@code int[]} reaching the GPU passes
 * through a byte-order step, and whether {@code 0xRRGGBBAA} arrives as
 * R, G, B, A depends on the upload path and platform endianness.
 *
 * What this proves: a {@link Framebuffer} colour value written into a
 * {@code Pixmap.Format.RGBA8888} reads back as the same colour, both through
 * libGDX's own accessors and through the raw pixel bytes libGDX would hand
 * OpenGL.
 *
 * What this still does not prove: the {@code glTexImage2D} upload and the
 * sampled result on screen. That needs a GL context and a framebuffer read
 * back from the driver, which no headless test has. The remaining gap is one
 * step — {@code Pixmap} hands OpenGL exactly the bytes asserted here, tagged
 * {@code GL_RGBA / GL_UNSIGNED_BYTE}.
 *
 * Requires the gdx native library. If it cannot be loaded the test is skipped
 * rather than failed, so a machine without the natives still builds.
 */
@DisplayName("Pixmap byte order")
final class PixmapByteOrderTest
{
    private static final Logger LOG = LoggerFactory.getLogger(PixmapByteOrderTest.class);

    /** A colour with four distinct channels, so any swizzle is visible. */
    private static final int DISTINCT = Framebuffer.packRgba(0x12, 0x34, 0x56, 0x78);

    /** Whether the gdx natives loaded; the tests are skipped when they did not. */
    private static boolean nativesReady;

    @BeforeAll
    static void loadNatives()
    {
        try
        {
            GdxNativesLoader.load();

            nativesReady = true;
        }
        catch (final RuntimeException | UnsatisfiedLinkError e)
        {
            // Not a failure: a machine without the desktop natives should
            // still build. Recorded loudly so a skip is never silent.
            nativesReady = false;

            LOG.warn("gdx natives unavailable — Pixmap byte-order tests skipped", e);
        }
    }

    @Test
    @DisplayName("a Framebuffer colour survives a Pixmap round trip unchanged")
    void shouldRoundTripThroughPixmap()
    {
        assumeThat(nativesReady).isTrue();

        final Pixmap pixmap = new Pixmap(2, 2, Pixmap.Format.RGBA8888);

        try
        {
            pixmap.drawPixel(1, 1, DISTINCT);

            assertThat(pixmap.getPixel(1, 1)).isEqualTo(DISTINCT);
        }
        finally
        {
            pixmap.dispose();
        }
    }

    @Test
    @DisplayName("the bytes Pixmap hands OpenGL are R, G, B, A in that order")
    void shouldStoreRgbaInThatByteOrder()
    {
        assumeThat(nativesReady).isTrue();

        final Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);

        try
        {
            pixmap.drawPixel(0, 0, DISTINCT);

            final ByteBuffer pixels = pixmap.getPixels();

            final byte[] actual = new byte[Integer.BYTES];

            pixels.duplicate().get(actual);

            final byte[] expected = new byte[Integer.BYTES];

            Framebuffer.writeRgbaBytes(DISTINCT, expected, 0);

            // This is the assertion that matters: the engine's packing and
            // libGDX's storage agree byte for byte, so a frame presents with
            // no per-pixel conversion.
            assertThat(actual).isEqualTo(expected);

            assertThat(Framebuffer.readRgbaBytes(actual, 0)).isEqualTo(DISTINCT);
        }
        finally
        {
            pixmap.dispose();
        }
    }

    @Test
    @DisplayName("a big-endian IntBuffer view is the correct bulk upload path")
    void shouldBulkCopyThroughABigEndianIntBufferView()
    {
        assumeThat(nativesReady).isTrue();

        // The presentation path in README section 12 is one bulk copy of the
        // finished int[] into the Pixmap. Which ByteOrder that copy uses is
        // the whole question, so it is asserted rather than assumed.
        final Framebuffer fb = new Framebuffer();

        fb.init(4, 4);

        fb.clear(Framebuffer.packRgba(0, 0, 0, 0xFF));

        fb.setPixel(3, 3, DISTINCT);

        final int[] contiguous = new int[fb.width() * fb.height()];

        fb.copyColorTo(contiguous);

        final Pixmap pixmap = new Pixmap(fb.width(), fb.height(), Pixmap.Format.RGBA8888);

        try
        {
            final ByteBuffer pixels = pixmap.getPixels();

            assertThat(pixels.order())
                .as("libGDX hands out a big-endian pixel buffer")
                .isEqualTo(ByteOrder.BIG_ENDIAN);

            pixels.asIntBuffer().put(contiguous);

            assertThat(pixmap.getPixel(3, 3)).isEqualTo(DISTINCT);

            assertThat(pixmap.getPixel(0, 0))
                .isEqualTo(Framebuffer.packRgba(0, 0, 0, 0xFF));
        }
        finally
        {
            pixmap.dispose();

            fb.shutdown();
        }
    }
}
