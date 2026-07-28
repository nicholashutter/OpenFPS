/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.openfps.engine.render.adapter.Rgba;

/**
 * Build-time entry point: writes {@link WindowIcon}'s design out as a Windows
 * {@code .ico} file.
 *
 * <p><b>Never called at runtime.</b> The game window gets its icon from
 * {@link WindowIcon#apply()}, which needs no file at all. This exists for one
 * thing: {@code jpackage} builds a native launcher, and {@code --icon} on
 * Windows takes an {@code .ico} path and nothing else. So the icon has to
 * become a file exactly once, at build time, and the {@code writeWindowsIcon}
 * Gradle task is the only caller.</p>
 *
 * <p>Keeping it in a separate class from {@link WindowIcon} is not tidiness. It
 * is what stops {@code java.awt} and {@code javax.imageio} appearing on the
 * path a running game touches: {@code WindowIcon} imports neither, so the AWT
 * subsystem is never initialised in a process that only ever draws a
 * window.</p>
 *
 * <h2>The ICO container, and why the payloads are PNG</h2>
 *
 * <p>An {@code .ico} is a six-byte header, one sixteen-byte directory entry per
 * image, then the images. Each image may be a BMP with a doubled height and an
 * AND mask, or — since Windows Vista — a PNG stored verbatim. PNG is chosen
 * here because the BMP form requires writing a bottom-up DIB with a padded
 * scanline stride and a second one-bit mask plane, all of which is a great deal
 * of code to get subtly wrong, and because {@code ImageIO} already writes
 * correct PNGs.</p>
 *
 * <p>The one non-obvious rule: a directory entry stores <b>0</b> for a
 * dimension of 256. Every size here is far below that, so the field is written
 * directly, but the rule is why the field is a single byte and why anyone
 * adding a 256-pixel entry has to know about it.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class IconFileMain
{
    /** Bytes in the ICONDIR header. */
    private static final int HEADER_BYTES = 6;

    /** Bytes in one ICONDIRENTRY. */
    private static final int ENTRY_BYTES = 16;

    /** ICONDIR image type 1 is an icon; 2 would be a cursor. */
    private static final short TYPE_ICON = 1;

    /** Colour planes. Ignored for PNG payloads, but conventionally 1. */
    private static final short PLANES = 1;

    /** Bits per pixel — 32, since the payloads carry an alpha channel. */
    private static final short BIT_DEPTH = 32;

    /** Shift placing red in an {@code TYPE_INT_ARGB} pixel. */
    private static final int RED_SHIFT = 16;

    /** Shift placing green in an {@code TYPE_INT_ARGB} pixel. */
    private static final int GREEN_SHIFT = 8;

    /** Shift placing alpha in an {@code TYPE_INT_ARGB} pixel. */
    private static final int ALPHA_SHIFT = 24;

    private IconFileMain()
    {
        // build-time entry point
    }

    /**
     * Writes the icon file.
     *
     * @param args one argument: the {@code .ico} path to write
     * @throws IOException if the file cannot be written
     */
    public static void main(final String[] args) throws IOException
    {
        if (args == null || args.length != 1)
        {
            throw new IllegalArgumentException(
                "usage: IconFileMain <output.ico>");
        }
        final Path target = Path.of(args[0]);
        final Path parent = target.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target))
        {
            out.write(buildIco(WindowIcon.SIZES));
        }
        System.out.println("Wrote " + target.toAbsolutePath()
            + " (" + Files.size(target) + " bytes, "
            + WindowIcon.SIZES.length + " sizes)");
    }

    /**
     * Builds a complete {@code .ico} holding one PNG per requested size.
     *
     * <p>Package-visible so a test can parse the header back without touching
     * the filesystem — the container layout is exactly the sort of thing that is
     * wrong by four bytes and produces a file Windows silently ignores.</p>
     *
     * @param sizes the icon edge lengths to include; must not be null or empty,
     *     and each must be between 1 and 255
     * @return the file's bytes
     * @throws IOException if PNG encoding fails
     */
    static byte[] buildIco(final int[] sizes) throws IOException
    {
        if (sizes == null || sizes.length == 0)
        {
            throw new IllegalArgumentException("an icon needs at least one size");
        }
        final List<byte[]> payloads = new ArrayList<>();
        for (final int size : sizes)
        {
            if (size < 1 || size > 255)
            {
                throw new IllegalArgumentException(
                    "icon size must be 1..255 — 256 is encoded as 0 and is not supported here,"
                        + " got " + size);
            }
            payloads.add(encodePng(size));
        }

        int total = HEADER_BYTES + ENTRY_BYTES * sizes.length;
        for (final byte[] payload : payloads)
        {
            total = total + payload.length;
        }

        // Little-endian throughout: the ICO format is a Windows structure dump.
        final ByteBuffer file = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        file.putShort((short) 0);
        file.putShort(TYPE_ICON);
        file.putShort((short) sizes.length);

        int offset = HEADER_BYTES + ENTRY_BYTES * sizes.length;
        for (int index = 0; index < sizes.length; index++)
        {
            final byte[] payload = payloads.get(index);
            file.put((byte) sizes[index]);
            file.put((byte) sizes[index]);
            file.put((byte) 0);
            file.put((byte) 0);
            file.putShort(PLANES);
            file.putShort(BIT_DEPTH);
            file.putInt(payload.length);
            file.putInt(offset);
            offset = offset + payload.length;
        }
        for (final byte[] payload : payloads)
        {
            file.put(payload);
        }
        return file.array();
    }

    // One size of the icon, PNG-encoded.
    private static byte[] encodePng(final int size) throws IOException
    {
        final int[] packed = WindowIcon.pixels(size);
        final BufferedImage image =
            new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                image.setRGB(x, y, toArgb(packed[y * size + x]));
            }
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes))
        {
            throw new IOException("no PNG writer available in this JVM");
        }
        return bytes.toByteArray();
    }

    // The engine's 0xRRGGBBAA packing to AWT's 0xAARRGGBB. Two conventions for
    // the same four bytes; converting in one place is the whole reason this
    // method exists.
    private static int toArgb(final int rgba)
    {
        return (Rgba.alpha(rgba) << ALPHA_SHIFT)
            | (Rgba.red(rgba) << RED_SHIFT)
            | (Rgba.green(rgba) << GREEN_SHIFT)
            | Rgba.blue(rgba);
    }
}
