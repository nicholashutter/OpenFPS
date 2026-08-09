/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.function.Consumer;

/**
 * The one switch behind the Debug entry on the settings screen.
 *
 * <p>Held here rather than as a boolean on whichever screen happens to draw the
 * counter, because three unrelated things read it: the settings screen that
 * flips it, the {@link DebugOverlay} that appears because of it, and — through
 * {@link #onChange} — the software renderer's outline pass. A boolean living in
 * one of the three would have the other two reaching into it.</p>
 *
 * <h2>This is not persisted, and that is a decision rather than an oversight</h2>
 *
 * <p>The engine has a real profile store: {@code I_UserProfilePort}, backed by
 * SQLite on desktop and Room on Android, holding {@code UserProfile}. Putting a
 * debug flag in it costs a field on {@code UserProfile}, a column and a
 * migration on the Room entity and DAO, matching changes in
 * {@code SqliteUserProfilePort} and {@code MemoryUserProfilePort}, and the
 * validation and {@code withXxx} copy that every field on that immutable class
 * carries. That is a schema migration across two databases, for a diagnostic
 * overlay whose natural lifetime is "while I am looking at this problem".</p>
 *
 * <p>So the choice survives for the run and no longer. If a settings screen ever
 * carries something a player would be annoyed to re-enter — sensitivity, field
 * of view, volume, all of which {@code UserProfile} already stores — the
 * migration becomes worth doing and this flag rides along with it. Until then
 * the honest thing is to say so here rather than to leave the reader wondering
 * why their toggle forgot.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #isOverlayVisible()} is safe from any thread — the field is
 * volatile and the value is a primitive. The mutators are called from the
 * platform's render thread only, from a Scene2D button callback. There is no
 * compare-and-set because two render threads do not exist; the volatile is for
 * publication to whoever reads it, not for exclusion.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class DebugSettings
{
    /**
     * System property that starts the counter switched on. Off unless set.
     *
     * <p>Opt-in, on the {@link FramebufferPresenter#FPS_LOG_PROPERTY} pattern
     * and for a version of the same reason: <b>the counter is otherwise
     * unreachable without a human.</b> Getting to it needs a mouse to press
     * Settings and then the toggle, so an automated capture can only ever
     * photograph a window that does not have it — which makes the one part of
     * this feature that has to be looked at the one part nothing can look
     * at.</p>
     *
     * <p>It seeds the initial value and nothing more. The toggle still owns the
     * switch from the first press, so this is a starting position rather than a
     * lock.</p>
     *
     * <pre>
     *   gradlew :desktop:run "--args=--start-in-game" -Dopenfps.debugOverlay=true
     * </pre>
     */
    public static final String OVERLAY_PROPERTY = "openfps.debugOverlay";

    /**
     * Whether the frame counter is on screen.
     * MUTABLE: flipped from the settings screen. Volatile because it is written
     * on the render thread and may be read from anywhere.
     */
    private volatile boolean overlayVisible =
        Boolean.parseBoolean(System.getProperty(OVERLAY_PROPERTY, "false"));

    /**
     * Told whenever the switch moves, or null when nobody is listening.
     *
     * <p>MUTABLE: set once by {@link #onChange} during composition, before any
     * frame runs.</p>
     */
    private volatile Consumer<Boolean> observer;

    /**
     * Creates the settings with debug off — or on, if
     * {@link #OVERLAY_PROPERTY} says so.
     */
    public DebugSettings()
    {
        // The initial state is the field initialiser; nothing else to do.
    }

    /** Returns whether the frame counter should be drawn. Safe from any thread. */
    public boolean isOverlayVisible()
    {
        return overlayVisible;
    }

    /**
     * Sets the switch, telling the observer if it moved.
     *
     * @param visible true to show the frame counter
     */
    public void setOverlayVisible(final boolean visible)
    {
        if (visible == overlayVisible)
        {
            // Not an error — a caller may reassert the current value — but the
            // observer is told about CHANGES. Firing on a no-op would have the
            // renderer reasserting its outline mode for nothing.
            return;
        }

        this.overlayVisible = visible;

        final Consumer<Boolean> told = observer;

        if (told != null)
        {
            told.accept(Boolean.valueOf(visible));
        }
    }

    /**
     * Flips the switch.
     *
     * @return the new value, so a caller that is about to relabel a button does
     *     not have to read the field back
     */
    public boolean toggleOverlay()
    {
        setOverlayVisible(!overlayVisible);

        return overlayVisible;
    }

    /**
     * Names something to be told when the switch moves.
     *
     * <p>This is how the debug toggle also drives
     * {@code SoftwareRenderPort.setOutlineEnabled} without this class importing
     * the renderer, or the renderer learning that a settings screen exists. The
     * launcher is the composition root, already holds both, and supplies the
     * one-line lambda.</p>
     *
     * <p><b>Attaching does not fire.</b> That is deliberate and it is the whole
     * of the loose coupling: whatever default the renderer was built with stays
     * in force until the player actually touches the toggle. An observer fired
     * on attach would have this class silently overriding a default it knows
     * nothing about, on every startup — and outline defaults are exactly the
     * kind of thing that gets tuned elsewhere.</p>
     *
     * @param told receives the new value on every change, or null to detach
     */
    public void onChange(final Consumer<Boolean> told)
    {
        this.observer = told;
    }

    /**
     * Returns the label the settings screen should show for this switch.
     *
     * <p>Here rather than in the screen because both platforms draw this and the
     * words should not drift, and because it is the one part of the settings
     * screen a headless test can assert.</p>
     *
     * @return {@code "ON"} or {@code "OFF"}
     */
    public String overlayLabel()
    {
        if (overlayVisible)
        {
            return "ON";
        }

        return "OFF";
    }

    /** Returns a debug rendering of the switch. */
    @Override
    public String toString()
    {
        return "DebugSettings{overlay=" + overlayLabel() + "}";
    }
}
