/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.nio.ByteBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;

import com.openfps.engine.render.adapter.Crosshair;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.gdx.MainMenuScreen;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The window and taskbar icon: the game's own reticle, drawn in code.
 *
 * <h2>Generated, not loaded</h2>
 *
 * <p>There is no icon file anywhere in this repository, and that is the same
 * decision {@link MainMenuScreen} makes for the same reason: the asset pipeline
 * deliberately fails until the payload release exists, and a window that cannot
 * find its own icon on a fresh checkout would either crash or fall back to the
 * Java coffee cup. A few dozen lines of arithmetic always work.</p>
 *
 * <p>It also keeps the icon <i>correct</i>. The colours come from
 * {@link Crosshair}, so the icon is the reticle the game actually draws — if
 * that reticle ever changes colour, the icon follows rather than quietly
 * becoming a picture of a previous version.</p>
 *
 * <h2>Why GLFW directly rather than libGDX</h2>
 *
 * <p>{@code Lwjgl3ApplicationConfiguration.setWindowIcon} takes <b>file
 * paths</b> and nothing else — there is no overload for pixels already in
 * memory. Since the whole point here is that there is no file,
 * {@code glfwSetWindowIcon} is the only route. LWJGL is already on the desktop
 * classpath (it is what the libGDX backend is built on), so this adds no
 * dependency.</p>
 *
 * <h2>Three sizes, because Windows picks per context</h2>
 *
 * <p>GLFW hands the whole set to the platform, which chooses: 16 for the title
 * bar, 32 for the taskbar, 48 for Alt-Tab and the large-icon views. Supplying
 * one size and letting Windows scale it is what makes an icon look soft in
 * exactly one of those places and sharp in the others. Each size here is drawn
 * at its own resolution, so every one is crisp.</p>
 *
 * <p><b>What this does not do is change the process name.</b> The running
 * process is the JVM's — {@code java.exe} — and no amount of window decoration
 * changes what Task Manager lists. See {@code BUILD.md} and the
 * {@code packageWindows} task for the part that does.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class WindowIcon
{
    /**
     * The icon sizes handed to the platform, in pixels.
     *
     * <p>16 for a title bar, 32 for the taskbar, 48 for Alt-Tab. Windows scales
     * whatever it is given, so the point of supplying all three is that none of
     * them has to be scaled.</p>
     */
    public static final int[] SIZES = { 16, 32, 48 };

    /** The icon's background, a shade darker than the menu backdrop. */
    public static final int BACKGROUND_COLOR = Rgba.pack(18, 23, 38, 255);

    /** The border around the icon, so it reads as a tile on a light taskbar. */
    public static final int BORDER_COLOR = Rgba.pack(58, 74, 120, 255);

    /** Bytes per pixel in the RGBA buffer GLFW expects. */
    private static final int BYTES_PER_PIXEL = 4;

    /** Denominator giving the reticle's arm thickness from the icon size. */
    private static final int THICKNESS_DIVISOR = 8;

    /** Denominator giving the reticle's centre gap from the icon size. */
    private static final int GAP_DIVISOR = 6;

    /** Denominator giving the margin between the reticle and the border. */
    private static final int MARGIN_DIVISOR = 8;

    /** Smallest feature size in pixels, so nothing vanishes at 16x16. */
    private static final int MIN_FEATURE = 2;

    private static final Logger LOG = LoggerFactory.getLogger(WindowIcon.class);

    private WindowIcon()
    {
        // icon generator
    }

    /**
     * Renders the icon at a given size.
     *
     * <p>Row-major, top row first, one {@code 0xRRGGBBAA} value per pixel — the
     * same packing {@link Rgba} uses everywhere else in this codebase, so the
     * colours can be shared with the renderer without a conversion nobody would
     * remember to keep in step.</p>
     *
     * @param size the icon's width and height in pixels; must be positive
     * @return {@code size * size} packed pixels
     * @throws IllegalArgumentException if {@code size} is not positive
     */
    public static int[] pixels(final int size)
    {
        if (size <= 0)
        {
            throw new IllegalArgumentException("icon size must be positive, got " + size);
        }

        final int[] out = new int[size * size];

        // Every threshold is DOUBLED, and so is every distance. See colourAt.
        final int thickness = atLeast(size / THICKNESS_DIVISOR) * 2;

        final int gap = atLeast(size / GAP_DIVISOR) * 2;

        final int reach = size - 1 - atLeast(size / MARGIN_DIVISOR) * 2;

        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                out[y * size + x] = colourAt(x, y, size, thickness, gap, reach);
            }
        }

        return out;
    }

    /**
     * Returns the colour of one icon pixel.
     *
     * <h2>Everything here is in doubled pixels, and that is not a micro-
     * optimisation</h2>
     *
     * <p>An icon of even width has <b>no centre pixel</b>: for 32 columns the
     * middle falls between 15 and 16. Measuring from {@code size / 2} therefore
     * puts one more pixel on one side of every arm than the other, and the
     * reticle is a pixel off centre — subtle enough to survive review, and the
     * kind of thing that is impossible to unsee once noticed. It was in the
     * first version of this class and a symmetry test found it.</p>
     *
     * <p>Doubling removes the half: {@code |2x - (size - 1)|} is an exact
     * integer distance from the true centre for even and odd sizes alike, and
     * it is symmetric by construction because {@code x} and {@code size - 1 - x}
     * give the same value. The thresholds are doubled to match, which is why
     * they arrive already multiplied.</p>
     *
     * @param x pixel column
     * @param y pixel row, 0 at the top
     * @param size the icon's edge length
     * @param thickness reticle arm thickness, in doubled pixels
     * @param gap the reticle's centre gap, in doubled pixels
     * @param reach how far an arm extends from the centre, in doubled pixels
     * @return the packed RGBA colour
     */
    static int colourAt(final int x, final int y, final int size, final int thickness,
        final int gap, final int reach)
    {
        if (x == 0 || y == 0 || x == size - 1 || y == size - 1)
        {
            return BORDER_COLOR;
        }

        final int fromCentreX = StrictMath.abs(2 * x - (size - 1));

        final int fromCentreY = StrictMath.abs(2 * y - (size - 1));

        // Horizontal arm: thin in y, long in x, with a hole in the middle.
        final boolean acrossArm = fromCentreY < thickness
            && fromCentreX >= gap && fromCentreX <= reach;

        // Vertical arm: the same, transposed.
        final boolean downArm = fromCentreX < thickness
            && fromCentreY >= gap && fromCentreY <= reach;

        if (acrossArm || downArm)
        {
            return Crosshair.CORE_COLOR;
        }

        return BACKGROUND_COLOR;
    }

    /**
     * Gives the current window this icon.
     *
     * <p>Call once, from the frame loop's {@code create()}, on the thread that
     * owns the window — GLFW requires window calls there. Does nothing when
     * there is no LWJGL3 window, which is every headless test and the null
     * backend, so callers need no guard of their own.</p>
     */
    public static void apply()
    {
        final long handle = windowHandle();

        if (handle == 0L)
        {
            LOG.debug("No LWJGL3 window — skipping the icon");

            return;
        }

        // The buffers must stay alive across the glfwSetWindowIcon call, so the
        // whole set is built inside one stack frame. GLFW copies the pixels
        // before returning, which is what makes freeing them here safe.
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            final GLFWImage.Buffer icons = GLFWImage.malloc(SIZES.length, stack);

            for (int index = 0; index < SIZES.length; index++)
            {
                final int size = SIZES[index];

                icons.position(index)
                    .width(size)
                    .height(size)
                    .pixels(toRgbaBuffer(pixels(size), stack));
            }

            icons.position(0);

            GLFW.glfwSetWindowIcon(handle, icons);

            LOG.info("Window icon set at {}, {} and {} pixels", SIZES[0], SIZES[1], SIZES[2]);
        }
    }

    // The GLFW handle of the current window, or 0 when there is no window.
    //
    // Guarded on the concrete backend type rather than assumed: Gdx.graphics is
    // an Android or headless implementation in every other build, and casting
    // blindly would turn "no icon" into a ClassCastException on a platform that
    // has no window bar at all.
    private static long windowHandle()
    {
        if (!(Gdx.graphics instanceof Lwjgl3Graphics))
        {
            return 0L;
        }

        return ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
    }

    // Packed RGBA ints to the byte order GLFW reads: R, G, B, A per pixel.
    private static ByteBuffer toRgbaBuffer(final int[] packed, final MemoryStack stack)
    {
        final ByteBuffer buffer = stack.malloc(packed.length * BYTES_PER_PIXEL);

        for (final int pixel : packed)
        {
            buffer.put((byte) Rgba.red(pixel));

            buffer.put((byte) Rgba.green(pixel));

            buffer.put((byte) Rgba.blue(pixel));

            buffer.put((byte) Rgba.alpha(pixel));
        }

        buffer.flip();

        return buffer;
    }

    // Keeps a derived feature from collapsing to nothing at the smallest size.
    private static int atLeast(final int value)
    {
        if (value < MIN_FEATURE)
        {
            return MIN_FEATURE;
        }

        return value;
    }
}
