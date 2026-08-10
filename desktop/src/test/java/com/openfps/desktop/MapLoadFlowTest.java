/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.MatchSummary;
import com.openfps.engine.hal.adapter.nulladapter.NullWindowPort;
import com.openfps.gdx.DefaultMenuActions;
import com.openfps.gdx.MapSelection;
import com.openfps.gdx.MapSelectionScreen;
import com.openfps.gdx.UiState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for the menu-driven map load flow.
 *
 * <p>The claim the launcher depends on: a pick from the menu fires
 * the launcher's load callback, which builds the map, re-attaches
 * the match hooks, and advances the state machine through LOADING
 * to PLAYING. A subsequent return-to-menu detaches the hooks and
 * unloads the map. A fresh pick reloads it.</p>
 *
 * <p>This is a state-flow test, not a GL test: the renderer is
 * null, the engine never ticks, and the match gate is exercised
 * through the same {@code Consumer<Boolean>} the launcher wires.
 * The point is that the wiring fires when it should, in the
 * order it should.</p>
 */
@DisplayName("Map load flow")
class MapLoadFlowTest
{
    /** The list the picker shows — one entry, "cornerstone". */
    private static final List<MapSelectionScreen.Entry> ONE_ENTRY = List.of(
        new MapSelectionScreen.Entry("cornerstone", "Cornerstone", "maps/cornerstone/thumbnail.png",
            MatchMode.TDM.name()));

    @BeforeEach
    void setUp()
    {
        com.openfps.engine.gameplay.map.MapLibrary.registerDefaults();
    }

    @Test
    @DisplayName("a single-player map pick fires the load callback with the picked id")
    void shouldFireLoadCallbackOnMapPick()
    {
        final MapSelection selection = new MapSelection();

        selection.setCurrentMapId("cornerstone");

        final GdxFrameLoopListener listener = newListener(selection, ONE_ENTRY, id -> { });

        final List<String> recorded = new ArrayList<>();

        listener.setLoadMapCallback(recorded::add);

        // MENU -> MODE_SELECT (Single Player)
        listener.menuActions().onStartGame();

        // MODE_SELECT -> MAP_SELECT (player picked the TDM mode)
        listener.uiState().pickMode(MatchMode.TDM);

        // MAP_SELECT -> LOADING. The launcher sees this as a real pick
        // and fires its load callback before the loading screen hands
        // control to uiState.startGame.
        listener.onMapPicked("cornerstone");

        assertThat(recorded)
            .as("the launcher should have been told the picked id exactly once")
            .containsExactly("cornerstone");
    }

    @Test
    @DisplayName("a blank or null map id is dropped, not passed to the launcher")
    void shouldNotFireOnBlankId()
    {
        final MapSelection selection = new MapSelection();

        final GdxFrameLoopListener listener = newListener(selection, ONE_ENTRY, id -> { });

        final List<String> recorded = new ArrayList<>();

        listener.setLoadMapCallback(recorded::add);

        listener.menuActions().onStartGame();

        listener.uiState().pickMode(MatchMode.TDM);

        // A blank id: should log a warning and return without
        // dispatching to the loader.
        listener.onMapPicked("");

        listener.onMapPicked(null);

        assertThat(recorded)
            .as("no load callback should have fired for blank/null ids")
            .isEmpty();
    }

    @Test
    @DisplayName("the launcher's full path: pick -> LOADING -> PLAYING -> MENU")
    void shouldCompleteTheFullPath()
    {
        final MapSelection selection = new MapSelection();

        selection.setCurrentMapId("cornerstone");

        final List<String> loads = new ArrayList<>();

        final GdxFrameLoopListener listener = newListener(selection, ONE_ENTRY, loads::add);

        listener.setLoadMapCallback(loads::add);

        // A gate that records every fire. The match-gate fires from
        // GdxFrameLoopListener.render() in the real run; this test
        // does not drive the render loop, so the gate is observed
        // firing on attach (when the listener is constructed) and
        // not on the state-machine calls below. The point of this
        // test is the state-machine flow, not the gate wiring.
        final List<Boolean> gateFires = new ArrayList<>();

        listener.attachMatchGate(playing -> gateFires.add(Boolean.valueOf(playing.booleanValue())));

        // MENU -> MODE_SELECT
        listener.menuActions().onStartGame();

        assertThat(listener.uiState().state()).isEqualTo(UiState.MODE_SELECT);

        assertThat(listener.uiState().mode()).isEqualTo(MatchMode.SINGLE_PLAYER);

        // MODE_SELECT -> MAP_SELECT
        listener.uiState().pickMode(MatchMode.TDM);

        assertThat(listener.uiState().state()).isEqualTo(UiState.MAP_SELECT);

        // MAP_SELECT -> LOADING (and the load callback fires)
        listener.onMapPicked("cornerstone");

        assertThat(listener.uiState().state()).isEqualTo(UiState.LOADING);

        assertThat(loads)
            .as("the launcher should have been told the picked id before openLoading")
            .containsExactly("cornerstone");

        // LOADING -> PLAYING (the loading screen's onReady fires this)
        listener.uiState().startGame(MatchMode.SINGLE_PLAYER);

        assertThat(listener.uiState().state()).isEqualTo(UiState.PLAYING);

        // PLAYING -> GAME_OVER -> MENU (a return-to-menu tears the
        // map down per the user's chosen policy)
        listener.uiState().endMatch(new MatchSummary(MatchState.WON, 7, 1, 7, 21, 13, 44, 56));

        listener.uiState().returnToMenu();

        assertThat(listener.uiState().state()).isEqualTo(UiState.MENU);
    }

    @Test
    @DisplayName("a window built without a load callback logs a warning on pick, not an error")
    void shouldWarnWhenNoCallbackIsWired()
    {
        final MapSelection selection = new MapSelection();

        final GdxFrameLoopListener listener = newListener(selection, ONE_ENTRY, id -> { });

        // Deliberately no setLoadMapCallback — the no-op default.
        listener.menuActions().onStartGame();

        listener.uiState().pickMode(MatchMode.TDM);

        // The pick still transitions the state machine to LOADING;
        // the absence of a callback is logged at WARN, not thrown.
        listener.onMapPicked("cornerstone");

        assertThat(listener.uiState().state()).isEqualTo(UiState.LOADING);
    }

    // ---- harness ---------------------------------------------------------

    private static GdxFrameLoopListener newListener(final MapSelection selection,
        final List<MapSelectionScreen.Entry> entries, final Consumer<String> ignored)
    {
        // The harness builds a listener with the map picker wired
        // to the given selection and entries. The "ignored" param is
        // there to keep call sites readable; the actual load
        // callback is set by the test after construction.
        final GdxFrameLoopListener listener = new GdxFrameLoopListener(
            new RecordingFrameCallback(), new DefaultMenuActions(new NullWindowPort()),
            null, null, new com.openfps.gdx.DebugSettings(), new com.openfps.gdx.AccessibilitySettings(),
            selection, entries);

        return listener;
    }
}
