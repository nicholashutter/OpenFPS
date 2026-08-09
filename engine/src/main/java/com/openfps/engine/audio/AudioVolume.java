/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio;

/**
 * S_ The one definition of what a volume is allowed to be.
 *
 * <p>Four lines of arithmetic in a class of its own, because every
 * {@code I_AudioPort} implementation needs exactly this and no two of them may
 * disagree. The null adapter's clamp and the libGDX adapter's clamp being
 * subtly different is the sort of thing that produces a bug report reading
 * "the volume slider works on desktop and mutes the game on my phone".</p>
 *
 * <h2>Why NaN is handled first, and separately</h2>
 *
 * <p>{@code Math.max(0f, Math.min(1f, x))} is the obvious one-liner and it is
 * wrong: NaN fails every comparison, so both {@code Math.min} and
 * {@code Math.max} propagate it, and the "clamped" result is still NaN. What
 * happens next is backend-specific and always bad — OpenAL takes a NaN gain as
 * an invalid value and silences the source, Android's {@code SoundPool} has
 * been observed to play at full scale — so the same NaN is inaudible on one
 * platform and deafening on the other. Mapping it to silence is a choice, and
 * it is the safe direction: nobody's ears are damaged by a bug that makes no
 * noise.</p>
 *
 * <p>NaN is not hypothetical here. A volume derived from a slider position
 * divided by a track width is NaN the frame before that width is laid out, and
 * {@code 0.0f / 0.0f} is exactly how a UI produces one.</p>
 */
public final class AudioVolume
{
    /** Silence — the low end of the range, and where NaN lands. */
    public static final float SILENT = 0.0f;

    /** Unattenuated — the high end of the range. */
    public static final float FULL = 1.0f;

    private AudioVolume()
    {
        // arithmetic holder
    }

    /**
     * Clamps a requested volume into {@code [SILENT, FULL]}.
     *
     * <p>NaN becomes {@link #SILENT}; see the class Javadoc for why that is
     * checked before the range and not folded into it. Both infinities fall out
     * of the ordinary comparisons correctly: {@code -Infinity < SILENT} and
     * {@code +Infinity > FULL}.</p>
     *
     * <p>The low comparison is {@code <=} rather than {@code <} for one reason:
     * <b>negative zero</b>. It is silence by any audible measure, but
     * {@code -0.0f < 0.0f} is false, so a strict comparison hands it straight
     * back — and {@code Float.valueOf(-0.0f).equals(Float.valueOf(0.0f))} is
     * false, so any code that compares volumes by boxed equality (a settings
     * screen asking "is this already muted?") disagrees with itself. Normalising
     * costs nothing and removes the question. {@code -0.0f} is not exotic: it is
     * what {@code -1.0f * 0.0f} and a downward-clamped fade both produce.</p>
     *
     * @param volume the requested volume; any float value, including NaN and
     *     the infinities
     * @return a volume in {@code [0, 1]}, never NaN
     */
    public static float clamp(final float volume)
    {
        if (Float.isNaN(volume))
        {
            return SILENT;
        }

        if (volume <= SILENT)
        {
            return SILENT;
        }

        if (volume > FULL)
        {
            return FULL;
        }

        return volume;
    }
}
