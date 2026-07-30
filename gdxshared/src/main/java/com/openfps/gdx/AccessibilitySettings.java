/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.function.Consumer;

/**
 * The switches that make the game readable, held apart from the ones that make
 * it inspectable.
 *
 * <h2>Why this is not a second field on {@link DebugSettings}</h2>
 *
 * <p>Today it holds one switch: whether the opponent under the point of aim is
 * drawn with an outline round it. That switch used to live on
 * {@link DebugSettings} beside the frame counter, and putting it there was a
 * category error with two consequences, one visible and one not.</p>
 *
 * <p><b>The visible one:</b> the two things wanted opposite defaults. The
 * counter is a diagnostic and starts off; the outline is a visual aid and starts
 * on. One boolean cannot be both, so at startup the button said {@code OFF} while
 * the outline was being drawn — a settings screen lying about the state of the
 * game, on the first frame, every time. That is exactly the failure the "a button
 * says what the setting is" rule in {@link SettingsScreen} exists to prevent, and
 * it happened anyway because the label and the behaviour had been welded to
 * different truths.</p>
 *
 * <p><b>The one nobody sees:</b> a player who wants the outline had to turn on
 * the frame counter to get it, and a player who wanted the frame counter got the
 * outline whether they wanted it or not. A visual aid is not a diagnostic. It is
 * a standard feature that some players need in order to play at all, and gating
 * it behind a developer tool tells them it is not meant for them.</p>
 *
 * <p>So this class exists to be the accessibility group rather than to hold one
 * boolean, and it is deliberately shaped like {@link DebugSettings} and
 * {@link RenderSettings} — volatile value, one observer, no fire on attach — so
 * that the next aid to be added is a copy of an established pattern rather than
 * an argument.</p>
 *
 * <h2>Defaults ON, and the composition root asserts it</h2>
 *
 * <p>{@link #onChange} deliberately does not fire on attach, which is right: an
 * observer that fired would have this class overriding a renderer default it
 * knows nothing about. The consequence is that <b>whoever wires the two together
 * has to push the initial value across once</b>, or the screen's label and the
 * renderer's behaviour are only in agreement by coincidence of matching
 * defaults. Both launchers do that, and it is the whole fix for the disagreement
 * described above: after it, the label is true because it was made true, not
 * because two constants happened to be the same.</p>
 *
 * <h2>This is not persisted, and that is the same decision {@link DebugSettings}
 * records</h2>
 *
 * <p>With one difference worth writing down: a diagnostic's natural lifetime is
 * "while I am looking at this problem", and an accessibility setting's natural
 * lifetime is <b>forever</b>. A player who needs the outline needs it in every
 * session, and one who cannot bear it will turn it off in every session. So this
 * is the switch with a real claim on {@code I_UserProfilePort}, and it is the one
 * that should ride the migration first when a setting worth persisting arrives.
 * Defaulting it ON is what keeps that from mattering yet: the players who need it
 * never have to touch it.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #isTargetOutlineVisible()} is safe from any thread — the field is
 * volatile and the value is a primitive. The mutators are called from the
 * platform's render thread only, from a Scene2D button callback.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class AccessibilitySettings
{
    /**
     * System property that starts the target outline switched off. On unless set.
     *
     * <p>Note which way round that is. Every other property of this shape in the
     * project turns a thing <i>on</i>, because every other thing is off by
     * default; this one exists to turn a standard feature off, which is the only
     * way an automated capture can photograph the game without it. Written as a
     * plain boolean rather than as a "disable" flag so it reads the same way as
     * the getter:</p>
     *
     * <pre>
     *   gradlew :desktop:run "--args=--start-in-game" -Dopenfps.targetOutline=false
     * </pre>
     */
    public static final String TARGET_OUTLINE_PROPERTY = "openfps.targetOutline";

    /**
     * Whether the opponent under the point of aim is outlined.
     *
     * <p>MUTABLE: flipped from the settings screen. Volatile because it is
     * written on the render thread and may be read from anywhere.</p>
     *
     * <p><b>Default true</b>, because this is a standard feature and not a
     * developer tool. See the class Javadoc.</p>
     */
    private volatile boolean targetOutlineVisible =
        Boolean.parseBoolean(System.getProperty(TARGET_OUTLINE_PROPERTY, "true"));

    /**
     * Told whenever the switch moves, or null when nobody is listening.
     *
     * <p>MUTABLE: set once by {@link #onChange} during composition, before any
     * frame runs.</p>
     */
    private volatile Consumer<Boolean> observer;

    /**
     * Creates the settings with the target outline on — or off, if
     * {@link #TARGET_OUTLINE_PROPERTY} says so.
     */
    public AccessibilitySettings()
    {
        // The initial state is the field initialiser; nothing else to do.
    }

    /**
     * Returns whether the opponent being aimed at should be outlined. Safe from
     * any thread.
     *
     * @return true when the aid is on
     */
    public boolean isTargetOutlineVisible()
    {
        return targetOutlineVisible;
    }

    /**
     * Sets the switch, telling the observer if it moved.
     *
     * @param visible true to outline the opponent under the point of aim
     */
    public void setTargetOutlineVisible(final boolean visible)
    {
        if (visible == targetOutlineVisible)
        {
            // Not an error — a caller may reassert the current value, and the
            // composition root does exactly that at startup — but the observer is
            // told about CHANGES. Firing on a no-op would have the renderer
            // reasserting its outline mode for nothing.
            return;
        }
        this.targetOutlineVisible = visible;
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
    public boolean toggleTargetOutline()
    {
        setTargetOutlineVisible(!targetOutlineVisible);
        return targetOutlineVisible;
    }

    /**
     * Names something to be told when the switch moves.
     *
     * <p>This is how the toggle drives
     * {@code SoftwareRenderPort.setOutlineEnabled} without this class importing
     * the renderer, or the renderer learning that a settings screen exists. The
     * launcher is the composition root, already holds both, and supplies the
     * one-line lambda.</p>
     *
     * <p><b>Attaching does not fire</b>, exactly as on {@link DebugSettings}, and
     * for the same reason: an observer fired on attach would have this class
     * silently overriding a default it knows nothing about. The price is that the
     * composition root owes the renderer one explicit push of the initial value —
     * see the class Javadoc, and see what happened when nobody paid it.</p>
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
    public String targetOutlineLabel()
    {
        if (targetOutlineVisible)
        {
            return "ON";
        }
        return "OFF";
    }

    /** Returns a debug rendering of the switches. */
    @Override
    public String toString()
    {
        return "AccessibilitySettings{targetOutline=" + targetOutlineLabel() + "}";
    }
}
