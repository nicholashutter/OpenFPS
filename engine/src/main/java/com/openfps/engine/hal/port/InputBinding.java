/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * One physical control, as a device kind plus an opaque platform code.
 *
 * <p>Immutable, two fields, no behaviour. It exists so a binding table can say
 * "{@link GameAction#FIRE} is mouse button 0" rather than just "0" — because
 * {@code 0} alone is ambiguous in a way that matters: on the desktop backend
 * mouse button 0 is the left button and key 0 is {@code ANY_KEY}, and the two
 * are read with different platform calls. Losing the distinction would bind fire
 * to every key on the keyboard.</p>
 *
 * <h2>The code is deliberately opaque</h2>
 *
 * <p>Nothing in {@code :engine} interprets {@link #code()}. It is whatever
 * number the platform that created the binding uses for that control — a libGDX
 * {@code Input.Keys} constant on desktop and Android, a touch-region index on a
 * phone, a button index on a gamepad. Only the adapter that supplied a code ever
 * reads it back, which is what lets the binding table be shared, saved and shown
 * without the engine knowing what a keyboard is.</p>
 *
 * <p>The consequence, stated plainly because it is a real limitation: a bindings
 * file written by the desktop build is <b>not</b> portable to a platform whose
 * codes differ. {@link Source} is what a loader checks first to notice.</p>
 */
public final class InputBinding
{
    /**
     * What kind of device a code belongs to.
     *
     * <p>The set is small on purpose. A new entry is warranted only when a
     * platform needs a genuinely different <i>query</i> — reading a key and
     * reading a mouse button are different calls, so they are different sources;
     * two brands of keyboard are not.</p>
     */
    public enum Source
    {
        /** A keyboard key. The code is a platform key constant. */
        KEY,

        /** A mouse button. The code is a platform button index. */
        MOUSE_BUTTON,

        /**
         * A region of a touchscreen, or an on-screen control drawn over the
         * game. The code identifies which region, and the platform owns the
         * geometry.
         */
        TOUCH_REGION,

        /** A gamepad button. The code is a platform button index. */
        GAMEPAD_BUTTON
    }

    /** Which kind of device. Never null. */
    private final Source source;

    /** The platform's number for the control. Meaningless outside the platform. */
    private final int code;

    /**
     * Creates a binding.
     *
     * @param deviceSource which kind of device the code belongs to; must not be
     *     null
     * @param platformCode the platform's opaque identifier for the control
     * @throws IllegalArgumentException if {@code deviceSource} is null
     */
    public InputBinding(final Source deviceSource, final int platformCode)
    {
        if (deviceSource == null)
        {
            throw new IllegalArgumentException("source must not be null");
        }
        this.source = deviceSource;
        this.code = platformCode;
    }

    /**
     * Creates a keyboard binding.
     *
     * @param keyCode the platform key constant
     * @return a binding on {@link Source#KEY}
     */
    public static InputBinding key(final int keyCode)
    {
        return new InputBinding(Source.KEY, keyCode);
    }

    /**
     * Creates a mouse-button binding.
     *
     * @param buttonIndex the platform button index
     * @return a binding on {@link Source#MOUSE_BUTTON}
     */
    public static InputBinding mouseButton(final int buttonIndex)
    {
        return new InputBinding(Source.MOUSE_BUTTON, buttonIndex);
    }

    /**
     * Creates a touch-region binding.
     *
     * @param regionId the platform's identifier for the region
     * @return a binding on {@link Source#TOUCH_REGION}
     */
    public static InputBinding touchRegion(final int regionId)
    {
        return new InputBinding(Source.TOUCH_REGION, regionId);
    }

    /** Returns which kind of device this binding names. Never null. */
    public Source source()
    {
        return source;
    }

    /** Returns the platform's opaque code for the control. */
    public int code()
    {
        return code;
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof InputBinding))
        {
            return false;
        }
        final InputBinding that = (InputBinding) other;
        return code == that.code && source == that.source;
    }

    @Override
    public int hashCode()
    {
        return 31 * source.hashCode() + code;
    }

    /** Returns a debug rendering of the source and code. */
    @Override
    public String toString()
    {
        return source + "(" + code + ")";
    }
}
