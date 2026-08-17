/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.core.FrameRate;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.map.MapLibrary;
import com.openfps.engine.gameplay.map.MapRuntime;
import com.openfps.engine.gameplay.map.Team;
import com.openfps.engine.gameplay.port.DelegatingGameplayPort;
import com.openfps.engine.hal.adapter.nulladapter.NullInputPort;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DesktopLauncher#createMatchGate} — the closure that
 * wires the window's match gate against the map runtime.
 *
 * <p>The bug these tests pin: when the user picks a map from the menu,
 * the launcher builds the map, re-attaches the match gate, and the
 * gate fires immediately with the listener's {@code isPlaying()}. On a
 * fresh pick that is {@code false} (the user is on the LOADING screen,
 * not PLAYING yet). The original wiring treated that initial
 * {@code false} as a return-to-menu and tore the freshly loaded map
 * down at 0 tics, so the user clicked a map, the map built, the map
 * was torn down, and the user landed back at the menu. The visible
 * symptom was "the game crashes when I click a map" — really "the map
 * never reaches PLAYING".</p>
 *
 * <p>The fix tracks the previous {@code playing} value across the
 * closure calls; the tear-down only fires on a real {@code true ->
 * false} transition. The tests below exercise both halves of the
 * contract: the initial {@code false} does not tear down, and a real
 * {@code true -> false} does.</p>
 */
@DisplayName("DesktopLauncher match gate wiring")
class DesktopLauncherMatchGateTest
{
    /** Viewport size the renderer reports; small, because nothing looks at it. */
    private static final int SURFACE = 64;

    @BeforeEach
    void setUp()
    {
        MapLibrary.registerDefaults();
    }

    @Nested
    @DisplayName("on a freshly wired runtime with a map loaded")
    class FreshLoad
    {
        @Test
        @DisplayName("the initial 'not playing' fire does not tear the map down")
        void shouldNotTearDownOnInitialFalseFire()
        {
            // The exact bug: the user picked a map, the launcher
            // attached the gate, the gate fired once with isPlaying()
            // == false (the user is on the loading screen, not
            // PLAYING). The pre-fix wiring tore the map down here,
            // so the user never saw the map. With the fix, the
            // initial fire is observed and remembered, not acted on.
            final MapRuntime runtime = newRuntime();

            runtime.loadMap("cornerstone");

            final AtomicInteger teardowns = new AtomicInteger();

            final Consumer<Boolean> gate = DesktopLauncher.createMatchGate(runtime, returnToMenu(runtime, teardowns));

            // The first fire: this is the bug case. isPlaying() is
            // false because the listener is in LOADING (or MENU, if
            // the loading screen's startGame has not yet advanced
            // the state to PLAYING). Pre-fix, this tore the map
            // down. Post-fix, it is observed without action.
            gate.accept(Boolean.FALSE);

            assertThat(runtime.hasMap())
                .as("the initial 'false' fire must not tear down a freshly loaded map")
                .isTrue();

            assertThat(teardowns.get())
                .as("the initial 'false' fire must not call the return-to-menu callback")
                .isZero();
        }

        @Test
        @DisplayName("a true -> false transition (return to menu) does tear the map down")
        void shouldTearDownOnTrueToFalseTransition()
        {
            // The happy path: the user started the match, played
            // for a while, then went back to the menu. The
            // true -> false transition is exactly when the map
            // should be released. The pre-fix wiring tore down
            // regardless; the post-fix wiring tears down only here.
            final MapRuntime runtime = newRuntime();

            runtime.loadMap("cornerstone");

            final AtomicInteger teardowns = new AtomicInteger();

            final Consumer<Boolean> gate = DesktopLauncher.createMatchGate(runtime, returnToMenu(runtime, teardowns));

            // LOADING -> PLAYING (startGame fired).
            gate.accept(Boolean.TRUE);

            assertThat(runtime.hasMap())
                .as("PLAYING must leave the map loaded")
                .isTrue();

            assertThat(teardowns.get())
                .as("PLAYING must not call the return-to-menu callback")
                .isZero();

            // PLAYING -> MENU (return to menu fired).
            gate.accept(Boolean.FALSE);

            assertThat(runtime.hasMap())
                .as("a true -> false transition must release the map")
                .isFalse();

            assertThat(teardowns.get())
                .as("a true -> false transition must call the return-to-menu callback exactly once")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a false -> false burst (still on the loading screen) does not tear down")
        void shouldNotTearDownOnRepeatedFalseFires()
        {
            // A render-frame race the real wiring sees occasionally:
            // syncUiState() fires the gate on the transition from
            // MAP_SELECT -> LOADING, and the gate is also fired once
            // on attach (with the listener's isPlaying() == false).
            // Both can land as 'false' back-to-back. The fix must
            // not act on either one.
            final MapRuntime runtime = newRuntime();

            runtime.loadMap("cornerstone");

            final AtomicInteger teardowns = new AtomicInteger();

            final Consumer<Boolean> gate = DesktopLauncher.createMatchGate(runtime, returnToMenu(runtime, teardowns));

            gate.accept(Boolean.FALSE);

            gate.accept(Boolean.FALSE);

            gate.accept(Boolean.FALSE);

            assertThat(runtime.hasMap())
                .as("a burst of 'false' fires must leave the map loaded")
                .isTrue();

            assertThat(teardowns.get())
                .as("a burst of 'false' fires must not call the return-to-menu callback")
                .isZero();

            // A subsequent PLAYING -> MENU still works correctly
            // after the burst, so the gate is not in a wedged state.
            gate.accept(Boolean.TRUE);

            gate.accept(Boolean.FALSE);

            assertThat(runtime.hasMap())
                .as("a true -> false transition after the burst must still release the map")
                .isFalse();

            assertThat(teardowns.get())
                .as("only the transition fires the callback, not the burst")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a false -> true -> true burst (still in PLAYING) does not tear down")
        void shouldNotTearDownOnRepeatedTrueFires()
        {
            // The same race on the PLAYING side: render frames can
            // land the gate on 'true' more than once. The fix
            // must not tear down on a true -> true burst.
            final MapRuntime runtime = newRuntime();

            runtime.loadMap("cornerstone");

            final AtomicInteger teardowns = new AtomicInteger();

            final Consumer<Boolean> gate = DesktopLauncher.createMatchGate(runtime, returnToMenu(runtime, teardowns));

            gate.accept(Boolean.TRUE);

            gate.accept(Boolean.TRUE);

            assertThat(runtime.hasMap())
                .as("a true -> true burst must leave the map loaded")
                .isTrue();

            assertThat(teardowns.get())
                .as("a true -> true burst must not call the return-to-menu callback")
                .isZero();
        }
    }

    @Nested
    @DisplayName("on a runtime that has no map loaded")
    class NoMap
    {
        @Test
        @DisplayName("firing the gate is a no-op (no port to freeze, no map to tear down)")
        void shouldBeNoOpWithoutAMap()
        {
            // The boot path (no --map=) reaches bindAndAttachMap
            // without a map loaded, which is a warn-and-return.
            // If a caller did wire the gate anyway, every fire
            // must be a no-op: no port to call setMatchLive on,
            // no map to release.
            final MapRuntime runtime = newRuntime();

            final AtomicInteger teardowns = new AtomicInteger();

            final Consumer<Boolean> gate = DesktopLauncher.createMatchGate(runtime,
                teardowns::incrementAndGet);

            gate.accept(Boolean.FALSE);

            gate.accept(Boolean.TRUE);

            gate.accept(Boolean.FALSE);

            assertThat(teardowns.get())
                .as("with no map loaded, the callback must never fire")
                .isZero();
        }
    }

    // ---- harness ---------------------------------------------------------

    /**
     * The "return to menu" callback the launcher's wiring installs in
     * production: it logs, calls {@code runtime.unload()}, and detaches
     * the window's match hooks. The tests below use a stub for the
     * window side (the counter) but reuse the real {@code unload} so
     * the assertions on {@code hasMap} reflect what the user actually
     * sees.
     */
    private static Runnable returnToMenu(final MapRuntime runtime, final AtomicInteger counter)
    {
        return () ->
        {
            counter.incrementAndGet();

            runtime.unload();
        };
    }

    private static MapRuntime newRuntime()
    {
        return new MapRuntime(renderer(), scriptedInput(), config(), Team.RED, 0,
            new DelegatingGameplayPort());
    }

    private static GameConfig config()
    {
        return GameConfig.unbounded(FrameRate.FPS_60);
    }

    private static SoftwareRenderPort renderer()
    {
        final I_TimePort time = new NullTimePort();

        time.init();

        final SoftwareRenderPort port = new SoftwareRenderPort(null, time);

        port.init();

        port.resize(SURFACE, SURFACE);

        return port;
    }

    private static I_InputPort scriptedInput()
    {
        return new NullInputPort();
    }
}
