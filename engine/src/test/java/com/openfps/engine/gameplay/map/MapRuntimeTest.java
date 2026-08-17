/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.core.FrameRate;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.adapter.NullGameplayPort;
import com.openfps.engine.gameplay.port.DelegatingGameplayPort;
import com.openfps.engine.hal.adapter.nulladapter.NullInputPort;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MapRuntime} — the swap seam between a map id and a
 * running map-mode game.
 *
 * <p>The claim the launcher depends on: {@link MapRuntime#loadMap} builds
 * the spec, scene and port, binds the scene to the renderer, and
 * atomically swaps the engine's gameplay port. A second
 * {@code loadMap} tears down the first. {@code unload} returns the
 * engine to the "no map, menu is up" state without restarting anything.</p>
 */
@DisplayName("MapRuntime")
class MapRuntimeTest
{
    /** Viewport size the renderer reports; small, because nothing looks at it. */
    private static final int SURFACE = 64;

    @BeforeEach
    void setUp()
    {
        MapLibrary.registerDefaults();
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a null renderer is rejected")
        void shouldRejectNullRenderer()
        {
            assertThatThrownBy(() -> new MapRuntime(null, scriptedInput(), config(),
                Team.RED, 0, new DelegatingGameplayPort()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("renderer");
        }

        @Test
        @DisplayName("a null delegating port is rejected")
        void shouldRejectNullPort()
        {
            assertThatThrownBy(() -> new MapRuntime(renderer(), scriptedInput(), config(),
                Team.RED, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        }

        @Test
        @DisplayName("a freshly built runtime has no map loaded")
        void shouldStartUnloaded()
        {
            final MapRuntime runtime = newRuntime();

            assertThat(runtime.hasMap()).isFalse();

            assertThat(runtime.spec()).isNull();

            assertThat(runtime.scene()).isNull();

            assertThat(runtime.mapPort()).isNull();
        }

        @Test
        @DisplayName("the engine's gameplay port is the delegating port, not a fresh one")
        void shouldExposeDelegatingPort()
        {
            final DelegatingGameplayPort delegating = new DelegatingGameplayPort();

            final MapRuntime runtime = new MapRuntime(renderer(), scriptedInput(), config(),
                Team.RED, 0, delegating);

            assertThat(runtime.enginePort()).isSameAs(delegating);
        }
    }

    @Nested
    @DisplayName("loadMap")
    class Load
    {
        @Test
        @DisplayName("loading cornerstone produces a spec, scene, and port")
        void shouldLoadCornerstone()
        {
            final MapRuntime runtime = newRuntime();

            final MapSpec loaded = runtime.loadMap("cornerstone");

            assertThat(loaded).isNotNull();

            assertThat(loaded.id()).isEqualTo("cornerstone");

            assertThat(runtime.hasMap()).isTrue();

            assertThat(runtime.spec()).isSameAs(loaded);

            assertThat(runtime.scene()).isNotNull();

            assertThat(runtime.mapPort()).isNotNull();
        }

        @Test
        @DisplayName("after loadMap, the engine's port is the map's port, not the null one")
        void shouldSwapEnginePort()
        {
            final DelegatingGameplayPort delegating = new DelegatingGameplayPort();

            final MapRuntime runtime = new MapRuntime(renderer(), scriptedInput(), config(),
                Team.RED, 0, delegating);

            runtime.loadMap("cornerstone");

            // The delegating port was wrapping NullGameplayPort; loadMap
            // swapped in the map port.
            assertThat(delegating.actual()).isSameAs(runtime.mapPort());

            assertThat(delegating.actual()).isNotInstanceOf(NullGameplayPort.class);
        }

        @Test
        @DisplayName("a blank map id is rejected")
        void shouldRejectBlankId()
        {
            final MapRuntime runtime = newRuntime();

            assertThatThrownBy(() -> runtime.loadMap(""))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> runtime.loadMap(null))
                .isInstanceOf(IllegalArgumentException.class);

            assertThat(runtime.hasMap()).isFalse();
        }

        @Test
        @DisplayName("an unknown map id is a no-op, not a thrown exception")
        void shouldIgnoreUnknownId()
        {
            final MapRuntime runtime = newRuntime();

            final MapSpec loaded = runtime.loadMap("not-a-map");

            assertThat(loaded).isNull();

            assertThat(runtime.hasMap()).isFalse();
        }

        @Test
        @DisplayName("after loadMap, the new port is live — the match runs as soon as the engine ticks")
        void shouldMakePortLiveAfterLoadMap()
        {
            // The match-gate hook on the window is the seam that freezes
            // the port while the menu is in front, but it only fires on
            // UI state CHANGES. A loadMap that lands while the UI is
            // already in PLAYING (the --start-in-game case, or a fresh
            // pick that transitions straight to PLAYING) has no state
            // change to observe, so the gate would leave the new port
            // frozen and the player could not shoot or see bots act.
            // MapRuntime.loadMap defends against that by calling
            // setMatchLive(true) itself; this test pins the contract.
            final MapRuntime runtime = newRuntime();

            runtime.loadMap("cornerstone");

            assertThat(runtime.mapPort().isMatchLive())
                .as("a freshly loaded map must be live — the gate hook only fires on transitions, not on initial state")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("loadMap after a previous loadMap")
    class Reload
    {
        @Test
        @DisplayName("loading a different map tears down the previous map's port")
        void shouldReplaceOnSecondLoad()
        {
            final DelegatingGameplayPort delegating = new DelegatingGameplayPort();

            final MapRuntime runtime = new MapRuntime(renderer(), scriptedInput(), config(),
                Team.RED, 0, delegating);

            runtime.loadMap("cornerstone");

            final MapGameplayPort firstPort = runtime.mapPort();

            runtime.loadMap("overpass");

            assertThat(runtime.spec().id()).isEqualTo("overpass");

            // The port was replaced, not mutated — a new MapGameplayPort
            // is a new object with its own Match and bot roster.
            assertThat(runtime.mapPort()).isNotSameAs(firstPort);

            // The first port was shut down as part of the swap.
            assertThat(firstPort).isNotSameAs(runtime.mapPort());

            // The engine is ticking the new port.
            assertThat(delegating.actual()).isSameAs(runtime.mapPort());
        }

        @Test
        @DisplayName("loading the same id twice replaces it with a fresh instance")
        void shouldRebuildOnSameId()
        {
            final MapRuntime runtime = newRuntime();

            runtime.loadMap("cornerstone");

            final MapGameplayPort firstPort = runtime.mapPort();

            runtime.loadMap("cornerstone");

            assertThat(runtime.mapPort()).isNotSameAs(firstPort);

            assertThat(runtime.spec().id()).isEqualTo("cornerstone");
        }
    }

    @Nested
    @DisplayName("unload")
    class Unload
    {
        @Test
        @DisplayName("unloading a loaded runtime returns it to the empty state")
        void shouldClearRuntime()
        {
            final MapRuntime runtime = newRuntime();

            runtime.loadMap("cornerstone");

            runtime.unload();

            assertThat(runtime.hasMap()).isFalse();

            assertThat(runtime.spec()).isNull();

            assertThat(runtime.scene()).isNull();

            assertThat(runtime.mapPort()).isNull();
        }

        @Test
        @DisplayName("after unload, the engine's port is a NullGameplayPort, not the map's port")
        void shouldReturnEnginePortToNull()
        {
            final DelegatingGameplayPort delegating = new DelegatingGameplayPort();

            final MapRuntime runtime = new MapRuntime(renderer(), scriptedInput(), config(),
                Team.RED, 0, delegating);

            runtime.loadMap("cornerstone");

            runtime.unload();

            assertThat(delegating.actual()).isInstanceOf(NullGameplayPort.class);
        }

        @Test
        @DisplayName("unloading an already-unloaded runtime is a no-op")
        void shouldBeIdempotent()
        {
            final MapRuntime runtime = newRuntime();

            runtime.unload();

            runtime.unload();

            assertThat(runtime.hasMap()).isFalse();
        }

        @Test
        @DisplayName("after unload, a fresh loadMap brings the runtime back")
        void shouldAllowReload()
        {
            final MapRuntime runtime = newRuntime();

            runtime.loadMap("cornerstone");

            runtime.unload();

            final MapSpec reloaded = runtime.loadMap("overpass");

            assertThat(reloaded).isNotNull();

            assertThat(reloaded.id()).isEqualTo("overpass");

            assertThat(runtime.hasMap()).isTrue();
        }
    }

    // ---- harness ---------------------------------------------------------

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
