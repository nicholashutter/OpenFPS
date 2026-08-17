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

import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DesktopLauncher#loadMapCallback} — the consumer
 * the menu fires when the user picks a map.
 *
 * <p>The contract the launcher depends on:</p>
 *
 * <ol>
 *   <li>A good id causes the runtime to swap to that map and the
 *       match hooks to be re-attached (which is what
 *       {@code bindAndAttachMap} does).</li>
 *   <li>A bad id is logged and left alone — no hooks are
 *       re-attached, and the menu state machine carries the user to
 *       a "could not load" path the menu knows how to render. The
 *       runtime's previous map is released by the time the bad id
 *       is reported, because {@link MapRuntime#loadMap} unloads
 *       before it tries to build.</li>
 * </ol>
 *
 * <p>Why this is a separate test from
 * {@code DesktopLauncherMatchGateTest}: the August 2026 bug was the
 * {@code --map=} boot path forgetting to call
 * {@code setLoadMapCallback} at all. Extracting the wiring to a
 * static method and pinning its contract here is what stops that
 * regression from sneaking back in.</p>
 */
@DisplayName("DesktopLauncher load map callback")
class DesktopLauncherLoadMapCallbackTest
{
    /** Viewport size the renderer reports; small, because nothing looks at it. */
    private static final int SURFACE = 64;

    @BeforeEach
    void setUp()
    {
        MapLibrary.registerDefaults();
    }

    @Test
    @DisplayName("a good id swaps the runtime to the new map")
    void shouldLoadMapAndReattachHooks()
    {
        final MapRuntime runtime = newRuntime();

        runtime.loadMap("cornerstone");

        // A real GdxWindowPort + GdxAdapterFactory. The match hooks
        // are field setters on a window that has not been run, so
        // the side effects are limited to bindAndAttachMap's other
        // attachers (audio / result / restart / status), which all
        // also just store their argument until runFrameLoop.
        final GdxWindowPort window = new GdxWindowPort();

        window.init();

        final GdxAdapterFactory hal = new GdxAdapterFactory();

        final Consumer<String> callback = DesktopLauncher.loadMapCallback(window, hal, runtime,
            FrameRate.FPS_60);

        callback.accept("foundry");

        assertThat(runtime.hasMap())
            .as("a good id must keep the runtime in the loaded state")
            .isTrue();

        assertThat(runtime.spec().id())
            .as("a good id must swap the runtime to the new map")
            .isEqualTo("foundry");

        // The match gate the wiring installs must be present on the
        // window - that is the point of calling bindAndAttachMap
        // from this path. Without the gate, a return-to-menu would
        // not tear the new map down cleanly.
        assertThat(window.matchGate())
            .as("a good id must re-attach the match gate so a later return-to-menu tears the map down")
            .isNotNull();
    }

    @Test
    @DisplayName("a bad id is logged and the wiring returns without touching the hooks")
    void shouldLeaveRuntimeAloneOnBadId()
    {
        final MapRuntime runtime = newRuntime();

        runtime.loadMap("cornerstone");

        final GdxWindowPort window = new GdxWindowPort();

        window.init();

        // hal is null on purpose: the bad-id branch returns before
        // bindAndAttachMap runs, so hal is never dereferenced.
        final Consumer<String> callback = DesktopLauncher.loadMapCallback(window, null, runtime,
            FrameRate.FPS_60);

        callback.accept("this-id-does-not-exist");

        // MapRuntime.loadMap unloads the current map before it
        // tries to resolve the new id; a bad id therefore leaves
        // the runtime empty. The menu state machine sees a
        // LOADING screen with no map, which is the "could not
        // load" path it knows how to render.
        assertThat(runtime.hasMap())
            .as("a bad id leaves the runtime empty (loadMap unloads before it tries the new id)")
            .isFalse();
    }

    // ---- harness ---------------------------------------------------------

    private static MapRuntime newRuntime()
    {
        // The 6-arg constructor delegates to the 7-arg with models=null.
        // The 7-arg constructor does not validate non-null on models
        // because the demo path (the only other caller) genuinely
        // needs null to mean "no kit, level-only path". The match-gate
        // tests only exercise the gate, not the populated scene, so
        // null is the right value here.
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
