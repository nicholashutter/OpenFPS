/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Which map the player has picked from the launcher's
 * {@link MapSelectionScreen}, held apart from the engine's
 * {@code MapLibrary} so the menu and the renderer can read it without either
 * one importing the other.
 *
 * <h2>The shape this class has, and why</h2>
 *
 * <p>The class deliberately matches {@link AccessibilitySettings} and
 * {@link DebugSettings}: a volatile value, a single observer that does not
 * fire on attach, no persistence. The same reasons apply, and the same
 * composition-root pattern (the launcher wires the observer; the screen
 * moves the value) keeps every actor in the right place. Adding a
 * {@code MapSelectionScreen} is then a copy of the {@link SettingsScreen}
 * recipe rather than a new architecture.</p>
 *
 * <h2>Not persisted, and not the same call as {@code DebugSettings} on
 * that</h2>
 *
 * <p>Persisting the selection would change the boot sequence: the launcher
 * would have to read the profile before parsing the map id, and a saved
 * selection would shadow the {@code --map=} command-line argument in a way
 * nobody had asked for. The selection is in-process, by design, and the
 * next launch reads {@code --map=...} or falls back to this class's
 * current value if the engine's {@code MapLibrary} cannot honour a stale
 * id. {@link AccessibilitySettings} has the persistence case already
 * documented; the case for {@code MapSelection} lands when a player has
 * actually used the picker enough times for a saved value to matter.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #currentMapId()} is safe from any thread — the field is
 * volatile and the value is a primitive reference. The mutators are called
 * from the platform's render thread only, from a Scene2D click
 * callback.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class MapSelection
{
    /**
     * System property that seeds the initial selection when no other
     * source has spoken. The launcher reads {@code --map=} first and only
     * falls back to this if that argument is missing, so the property is
     * the third source in priority order.
     */
    public static final String MAP_PROPERTY = "openfps.selectedMap";

    /**
     * The default selection when no other source has spoken. {@code cornerstone}
     * is the shipped map every fresh checkout can run, and is the value a
     * picker that has not been touched yet reports as "the current map".
     */
    public static final String DEFAULT_MAP_ID = "cornerstone";

    /**
     * The map id the launcher should use, or null when nothing has been picked
     * and the engine has no default. Volatile because the picker is on the
     * render thread and the launcher is on the engine's bootstrap thread.
     */
    private volatile String currentMapId;

    /**
     * Told whenever the selection changes, or null when nobody is listening.
     * MUTABLE: set once by {@link #onChange} during composition, before any
     * frame runs.
     */
    private volatile Consumer<String> observer;

    /**
     * Creates the selection with the default map id, or the value of
     * {@link #MAP_PROPERTY} if that property is set and non-blank.
     */
    public MapSelection()
    {
        this.currentMapId = initialMapId();
    }

    /**
     * Returns the initial map id, in priority order: {@link #MAP_PROPERTY},
     * then {@link #DEFAULT_MAP_ID}.
     *
     * @return a non-null, non-blank map id
     */
    private static String initialMapId()
    {
        final String fromProperty = System.getProperty(MAP_PROPERTY);
        if (fromProperty != null && !fromProperty.isBlank())
        {
            return fromProperty;
        }
        return DEFAULT_MAP_ID;
    }

    /**
     * Returns the currently selected map id, never null and never blank.
     *
     * <p>Safe from any thread.</p>
     *
     * @return the map id the launcher should hand to the engine
     */
    public String currentMapId()
    {
        return currentMapId;
    }

    /**
     * Sets the selected map id, telling the observer if it moved.
     *
     * <p>A blank or null id is refused: a blank value would make the
     * engine's {@code MapLibrary} fall through to its own default, which
     * is a different code path than the one this class promises. The
     * picker should never offer a blank id, and tests asserting the
     * state should never write one.</p>
     *
     * @param mapId the new selection; must be non-blank
     * @throws IllegalArgumentException if {@code mapId} is null or blank
     */
    public void setCurrentMapId(final String mapId)
    {
        if (mapId == null || mapId.isBlank())
        {
            throw new IllegalArgumentException("mapId must not be null or blank");
        }
        if (mapId.equals(currentMapId))
        {
            // A picker that reasserts the current value is a no-op; firing
            // the observer would have the listener relabel its own button
            // for nothing. See AccessibilitySettings.setTargetOutlineVisible
            // for the same call.
            return;
        }
        this.currentMapId = mapId;
        final Consumer<String> told = observer;
        if (told != null)
        {
            told.accept(mapId);
        }
    }

    /**
     * Names something to be told when the selection changes.
     *
     * <p>This is how the launcher's main-menu label follows the picker's
     * choice without the screen importing the launcher or vice versa.
     * The launcher wires the observer at composition; the screen calls
     * {@link #setCurrentMapId} and forgets.</p>
     *
     * <p><b>Attaching does not fire</b>, exactly as on
     * {@link AccessibilitySettings}, and for the same reason: an observer
     * fired on attach would have this class silently overriding a default
     * the launcher had already set from {@code --map=}. The price is that
     * whoever wires the observer owes the menu one explicit push of the
     * initial value — see {@link GdxFrameLoopListener} for the seam.</p>
     *
     * @param told receives the new value on every change, or null to detach
     */
    public void onChange(final Consumer<String> told)
    {
        this.observer = told;
    }

    /**
     * Returns a debug rendering of the selection.
     *
     * @return a one-line string naming the current selection
     */
    @Override
    public String toString()
    {
        return Objects.toString(currentMapId, "(unset)");
    }
}
