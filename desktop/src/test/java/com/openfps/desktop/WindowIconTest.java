/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.openfps.engine.render.adapter.Crosshair;
import com.openfps.engine.render.adapter.Rgba;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WindowIcon} and {@link IconFileMain}.
 *
 * <p>The {@code glfwSetWindowIcon} call needs a window and cannot be checked
 * here. Everything up to it can: the pixels are pure arithmetic, and the ICO
 * container is a byte layout that is either right or produces a file Windows
 * silently ignores — which is the worst possible failure, because it looks
 * exactly like not having set an icon at all.</p>
 */
@DisplayName("WindowIcon")
class WindowIconTest
{
    /** ICO files start with a two-byte zero, then the type. */
    private static final int HEADER_BYTES = 6;

    /** One ICONDIRENTRY. */
    private static final int ENTRY_BYTES = 16;

    /** The eight-byte PNG signature, which each payload must begin with. */
    private static final byte[] PNG_MAGIC =
    {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
    };

    @Nested
    @DisplayName("the drawn icon")
    class Design
    {
        @Test
        @DisplayName("fills exactly size x size pixels at every declared size")
        void shouldFillTheWholeSquareAtEverySize()
        {
            for (final int size : WindowIcon.SIZES)
            {
                assertThat(WindowIcon.pixels(size)).hasSize(size * size);
            }
        }

        @Test
        @DisplayName("is fully opaque, so no taskbar shows through it")
        void shouldBeOpaqueEverywhere()
        {
            for (final int size : WindowIcon.SIZES)
            {
                for (final int pixel : WindowIcon.pixels(size))
                {
                    assertThat(Rgba.alpha(pixel))
                        .as("a transparent pixel at size %d", size)
                        .isEqualTo(255);
                }
            }
        }

        @Test
        @DisplayName("uses the game's own reticle colour, not a copy of it")
        void shouldDrawTheReticleInTheGamesGreen()
        {
            // Sharing the constant is what stops the icon becoming a picture of
            // a previous version of the crosshair. If the reticle is ever
            // recoloured, the icon follows.
            assertThat(WindowIcon.pixels(32)).contains(Crosshair.CORE_COLOR);
        }

        @Test
        @DisplayName("has a border all the way round, so it reads as a tile")
        void shouldBorderEveryEdge()
        {
            final int size = 32;
            final int[] pixels = WindowIcon.pixels(size);

            for (int index = 0; index < size; index++)
            {
                assertThat(pixels[index]).as("top edge").isEqualTo(WindowIcon.BORDER_COLOR);
                assertThat(pixels[(size - 1) * size + index]).as("bottom edge")
                    .isEqualTo(WindowIcon.BORDER_COLOR);
                assertThat(pixels[index * size]).as("left edge")
                    .isEqualTo(WindowIcon.BORDER_COLOR);
                assertThat(pixels[index * size + size - 1]).as("right edge")
                    .isEqualTo(WindowIcon.BORDER_COLOR);
            }
        }

        @Test
        @DisplayName("leaves the centre open — a reticle has a hole in it")
        void shouldLeaveTheCentreClear()
        {
            // Without the gap the icon is a plus sign rather than a crosshair,
            // and at 16 pixels that is the difference between "a game" and "a
            // medical app".
            final int size = 32;
            final int[] pixels = WindowIcon.pixels(size);

            assertThat(pixels[(size / 2) * size + size / 2])
                .isEqualTo(WindowIcon.BACKGROUND_COLOR);
        }

        @Test
        @DisplayName("still draws a visible reticle at the smallest size")
        void shouldSurviveTheSixteenPixelSize()
        {
            // Every feature is derived from the size by division, so at 16 the
            // arms and the gap would both round to zero or one without the
            // minimum. That is the size a title bar uses, so it is the one that
            // must not degrade.
            final int[] pixels = WindowIcon.pixels(16);

            int green = 0;
            for (final int pixel : pixels)
            {
                if (pixel == Crosshair.CORE_COLOR)
                {
                    green++;
                }
            }
            assertThat(green).as("the reticle vanished at 16 pixels").isGreaterThan(16);
        }

        @Test
        @DisplayName("is symmetric about both axes, at every size")
        void shouldBeSymmetricWhenDrawn()
        {
            // A crosshair a pixel off centre is subtly wrong in a way that
            // survives review and cannot be unseen afterwards. The first version
            // of this class measured from size / 2, which for an even width
            // lands between two pixels and biases every arm one way; this test
            // is what found it.
            for (final int size : WindowIcon.SIZES)
            {
                final int[] pixels = WindowIcon.pixels(size);
                for (int y = 0; y < size; y++)
                {
                    for (int x = 0; x < size; x++)
                    {
                        assertThat(pixels[y * size + x])
                            .as("horizontal mirror at (%d, %d), size %d", x, y, size)
                            .isEqualTo(pixels[y * size + (size - 1 - x)]);
                        assertThat(pixels[y * size + x])
                            .as("vertical mirror at (%d, %d), size %d", x, y, size)
                            .isEqualTo(pixels[(size - 1 - y) * size + x]);
                    }
                }
            }
        }

        @Test
        @DisplayName("rejects a non-positive size")
        void shouldRejectANonPositiveSize()
        {
            assertThatThrownBy(() -> WindowIcon.pixels(0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> WindowIcon.pixels(-8))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("offers a title-bar, a taskbar and an Alt-Tab size")
        void shouldOfferThreeSizes()
        {
            // Windows scales whatever it is given. Supplying all three is what
            // stops the icon being soft in exactly one place.
            assertThat(WindowIcon.SIZES).containsExactly(16, 32, 48);
        }
    }

    @Nested
    @DisplayName("the .ico container")
    class IcoFile
    {
        @Test
        @DisplayName("has a well-formed header and one directory entry per size")
        void shouldWriteAValidHeaderWhenBuildingTheIco() throws IOException
        {
            final byte[] ico = IconFileMain.buildIco(WindowIcon.SIZES);
            final ByteBuffer buffer = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);

            assertThat(buffer.getShort()).as("reserved").isZero();
            assertThat(buffer.getShort()).as("type 1 is an icon").isEqualTo((short) 1);
            assertThat(buffer.getShort()).as("image count")
                .isEqualTo((short) WindowIcon.SIZES.length);
        }

        @Test
        @DisplayName("every entry points at a real PNG inside the file")
        void shouldPointEveryEntryAtItsPayload() throws IOException
        {
            // The offsets are the part that is wrong by four bytes and produces
            // a file Windows ignores without a word. Following each one and
            // finding a PNG signature is the check that cannot pass by accident.
            final byte[] ico = IconFileMain.buildIco(WindowIcon.SIZES);
            final ByteBuffer buffer = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);

            for (int index = 0; index < WindowIcon.SIZES.length; index++)
            {
                buffer.position(HEADER_BYTES + index * ENTRY_BYTES);
                assertThat(buffer.get() & 0xFF).as("width").isEqualTo(WindowIcon.SIZES[index]);
                assertThat(buffer.get() & 0xFF).as("height").isEqualTo(WindowIcon.SIZES[index]);
                buffer.get();
                buffer.get();
                buffer.getShort();
                buffer.getShort();
                final int length = buffer.getInt();
                final int offset = buffer.getInt();

                assertThat(offset + length)
                    .as("entry %d runs past the end of the file", index)
                    .isLessThanOrEqualTo(ico.length);
                for (int magic = 0; magic < PNG_MAGIC.length; magic++)
                {
                    assertThat(ico[offset + magic])
                        .as("entry %d does not begin with a PNG signature", index)
                        .isEqualTo(PNG_MAGIC[magic]);
                }
            }
        }

        @Test
        @DisplayName("payloads do not overlap and follow the directory")
        void shouldLayPayloadsOutAfterTheDirectory() throws IOException
        {
            final byte[] ico = IconFileMain.buildIco(WindowIcon.SIZES);
            final ByteBuffer buffer = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
            final int directoryEnd = HEADER_BYTES + ENTRY_BYTES * WindowIcon.SIZES.length;

            int previousEnd = directoryEnd;
            for (int index = 0; index < WindowIcon.SIZES.length; index++)
            {
                buffer.position(HEADER_BYTES + index * ENTRY_BYTES + 8);
                final int length = buffer.getInt();
                final int offset = buffer.getInt();

                assertThat(offset).as("entry %d overlaps what came before", index)
                    .isGreaterThanOrEqualTo(previousEnd);
                previousEnd = offset + length;
            }
            assertThat(previousEnd).as("trailing bytes nothing points at")
                .isEqualTo(ico.length);
        }

        @Test
        @DisplayName("refuses a size the single-byte dimension field cannot hold")
        void shouldRefuseAnOversizedEntry()
        {
            // 256 is encoded as 0 in an ICONDIRENTRY, so writing it directly
            // produces a zero-dimension entry rather than an error. Refusing is
            // better than silently emitting one.
            assertThatThrownBy(() -> IconFileMain.buildIco(new int[] { 256 }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
        }

        @Test
        @DisplayName("refuses an empty size list")
        void shouldRefuseAnEmptyIcon()
        {
            assertThatThrownBy(() -> IconFileMain.buildIco(new int[0]))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> IconFileMain.buildIco(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
