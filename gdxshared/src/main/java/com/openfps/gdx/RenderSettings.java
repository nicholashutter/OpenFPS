/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.function.Consumer;

/**
 * The switch behind the Render entry on the settings screen.
 *
 * <p>Deliberately the same shape as {@link DebugSettings}, and for the same
 * reason: three unrelated things touch this value — the settings screen that
 * cycles it, the {@link FramebufferPresenter} that has to re-size a framebuffer
 * because of it, and the {@link DebugOverlay} that reports the size it produced.
 * A field living on any one of the three would have the other two reaching into
 * it.</p>
 *
 * <h2>Held by the presenter, unlike the debug switch</h2>
 *
 * <p>{@link DebugSettings} is built by the composition root because the
 * launcher is the only object that can see both the settings screen and the
 * renderer's outline pass. This one is built by the presenter instead, because
 * <b>the presenter is the only thing that can act on it</b>: applying a mode
 * means re-sizing the render port and rebuilding a Pixmap and a Texture, and
 * those are the presenter's own resources on the presenter's own thread. A
 * launcher holding this would be a launcher forwarding it straight back.</p>
 *
 * <p><b>Not persisted</b>, exactly as the debug toggle is not — see
 * {@link RenderMode} for what persisting it would cost and why that bill is not
 * worth paying yet.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #mode()} is safe from any thread; the field is volatile and the
 * value is an immutable enum constant. {@link #cycle()} and {@link #setMode} are
 * called from the platform's render thread only, out of a Scene2D button
 * callback — which matters, because the observer they fire rebuilds GL
 * resources. There is no compare-and-set because two render threads do not
 * exist; the volatile is for publication to whoever reads it, not for
 * exclusion.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class RenderSettings
{
    /**
     * The mode in force.
     * MUTABLE: cycled from the settings screen. Volatile because it is written
     * on the render thread and may be read from anywhere.
     */
    private volatile RenderMode mode;

    /**
     * Told whenever the mode moves, or null when nobody is listening.
     *
     * <p>MUTABLE: set once during composition, before any frame runs.</p>
     */
    private volatile Consumer<RenderMode> observer;

    /**
     * Creates the settings at {@link RenderMode#DEFAULT}.
     */
    public RenderSettings()
    {
        this(RenderMode.DEFAULT);
    }

    /**
     * Creates the settings at a given mode.
     *
     * @param initial the mode to start in; must not be null
     * @throws IllegalArgumentException if the mode is null
     */
    public RenderSettings(final RenderMode initial)
    {
        if (initial == null)
        {
            throw new IllegalArgumentException("initial must not be null");
        }

        this.mode = initial;
    }

    /** Returns the mode in force. Safe from any thread. */
    public RenderMode mode()
    {
        return mode;
    }

    /**
     * Sets the mode, telling the observer if it moved.
     *
     * @param wanted the mode to adopt; must not be null
     * @throws IllegalArgumentException if the mode is null
     */
    public void setMode(final RenderMode wanted)
    {
        if (wanted == null)
        {
            throw new IllegalArgumentException("wanted must not be null");
        }

        if (wanted == mode)
        {
            // Not an error — a caller may reassert the current value — but the
            // observer is told about CHANGES. Firing on a no-op would have the
            // presenter tearing down and rebuilding a Texture and a Pixmap to
            // arrive at the size it already had.
            return;
        }

        this.mode = wanted;

        final Consumer<RenderMode> told = observer;

        if (told != null)
        {
            told.accept(wanted);
        }
    }

    /**
     * Moves to the next mode in the cycle.
     *
     * @return the new mode, so a caller about to relabel a button does not have
     *     to read the field back
     */
    public RenderMode cycle()
    {
        setMode(mode.next());

        return mode;
    }

    /**
     * Names something to be told when the mode moves.
     *
     * <p><b>Attaching does not fire</b>, on the {@link DebugSettings#onChange}
     * rule and for a sharper version of the same reason: the presenter applies
     * whatever mode it was built with as part of its first
     * {@link FramebufferPresenter#resize}, so an observer fired on attach would
     * re-size a framebuffer that has not been sized once yet.</p>
     *
     * @param told receives the new mode on every change, or null to detach
     */
    public void onChange(final Consumer<RenderMode> told)
    {
        this.observer = told;
    }

    /**
     * Returns the label the settings screen should show for this switch.
     *
     * <p>Here rather than in the screen because both platforms draw it and the
     * words should not drift, and because it is the one part of the render
     * setting a headless test can assert.</p>
     *
     * @return the current mode's label, such as {@code "480P"}
     */
    public String label()
    {
        return mode.label();
    }

    /** Returns a debug rendering of the switch. */
    @Override
    public String toString()
    {
        return "RenderSettings{mode=" + mode + "}";
    }
}
