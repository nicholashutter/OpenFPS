/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.openfps.engine.core.FrameRate;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.InputState;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.engine.render.adapter.ModelFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the demo's per-tic loop — the join between input, the player and
 * the camera.
 *
 * <p>The interesting assertion is the last one: that the camera the renderer
 * actually renders from is the one the controller produced. Every other part of
 * this chain has its own tests, and the chain still failed to exist for several
 * phases because nothing connected them.</p>
 */
@DisplayName("DemoGameplayPort")
final class DemoGameplayPortTest
{
    /** Viewport used where a surface is needed; small, because nothing looks at it. */
    private static final int SURFACE = 64;

    /** Floating-point slack for position comparisons, in world units. */
    private static final float EPSILON = 1.0e-3f;

    /** A configuration at the default rate. */
    private static GameConfig config()
    {
        return GameConfig.unbounded(FrameRate.FPS_60);
    }

    /** A render port with no worker pool — the serial reference path. */
    private static SoftwareRenderPort renderer()
    {
        final I_TimePort time = new NullTimePort();
        time.init();
        final SoftwareRenderPort port = new SoftwareRenderPort(null, time);
        port.init();
        return port;
    }

    /**
     * An input port that replays one snapshot and counts how often it is
     * latched.
     *
     * <p>Counting matters: the look deltas are an integral since the previous
     * latch, so sampling twice in a tic would double a turn and sampling not at
     * all would freeze the view. Both are silent.</p>
     */
    private static final class ScriptedInput implements I_InputPort
    {
        /** What every latch produces. */
        private final InputState state;

        /** How many times {@link #sampleInput} has been called. MUTABLE. */
        private int samples;

        /** What {@link #currentInput} returns. MUTABLE: set by sampleInput. */
        private InputState latched = InputState.NEUTRAL;

        ScriptedInput(final InputState scripted)
        {
            this.state = scripted;
        }

        @Override
        public void init()
        {
            // nothing to open
        }

        @Override
        public void shutdown()
        {
            // nothing to close
        }

        @Override
        public void sampleInput(final int ticIndex)
        {
            samples++;
            latched = state;
        }

        @Override
        public InputState currentInput()
        {
            return latched;
        }

        @Override
        public boolean isShutdownRequested()
        {
            return false;
        }

        int samples()
        {
            return samples;
        }
    }

    @Nested
    @DisplayName("construction")
    final class Construction
    {
        @Test
        @DisplayName("derives the tic duration from the configured rate")
        void deltaComesFromTheConfig()
        {
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), renderer(), new PlayerController(),
                GameConfig.unbounded(FrameRate.FPS_60));

            // The fixed-timestep loop makes a tic exactly 1/rate seconds; a
            // measured render delta would make movement speed depend on the
            // monitor.
            assertThat(port.deltaSeconds()).isCloseTo(1.0f / 60.0f, within(1.0e-6f));
        }

        @Test
        @DisplayName("follows the rate when it changes")
        void deltaTracksTheRate()
        {
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), renderer(), new PlayerController(),
                GameConfig.unbounded(FrameRate.FPS_120));

            assertThat(port.deltaSeconds()).isCloseTo(1.0f / 120.0f, within(1.0e-6f));
        }

        @Test
        @DisplayName("rejects every null dependency")
        void rejectsNulls()
        {
            final I_InputPort input = new ScriptedInput(InputState.NEUTRAL);
            final SoftwareRenderPort render = renderer();
            final PlayerController player = new PlayerController();

            assertThatThrownBy(() -> new DemoGameplayPort(null, render, player, config()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DemoGameplayPort(input, null, player, config()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DemoGameplayPort(input, render, null, config()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DemoGameplayPort(input, render, player, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("per tic")
    final class PerTic
    {
        @Test
        @DisplayName("latches the input exactly once")
        void latchesOncePerTic()
        {
            final ScriptedInput input = new ScriptedInput(InputState.NEUTRAL);
            final DemoGameplayPort port = new DemoGameplayPort(input, renderer(),
                new PlayerController(), config());

            port.tick(0);
            port.tick(1);
            port.tick(2);

            assertThat(input.samples()).isEqualTo(3);
            assertThat(port.ticsApplied()).isEqualTo(3L);
        }

        @Test
        @DisplayName("walks the player forward when the forward axis is held")
        void movesThePlayer()
        {
            final ScriptedInput input = new ScriptedInput(
                InputState.of(1.0f, 0.0f, 0.0f, 0.0f, false, false, false));
            final PlayerController player = new PlayerController();
            final DemoGameplayPort port = new DemoGameplayPort(input, renderer(), player,
                config());

            for (int tic = 0; tic < 60; tic++)
            {
                port.tick(tic);
            }

            // Yaw 0 faces world +z, and a second of walking is one
            // MOVE_SPEED_UNITS_PER_SECOND.
            assertThat(player.positionZ())
                .isCloseTo(PlayerController.MOVE_SPEED_UNITS_PER_SECOND, within(0.5f));
            assertThat(player.positionX()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("turns the player when the look delta is non-zero")
        void turnsThePlayer()
        {
            final float perTic = 0.01f;
            final ScriptedInput input = new ScriptedInput(
                InputState.of(0.0f, 0.0f, perTic, 0.0f, false, false, false));
            final PlayerController player = new PlayerController();
            final DemoGameplayPort port = new DemoGameplayPort(input, renderer(), player,
                config());

            for (int tic = 0; tic < 50; tic++)
            {
                port.tick(tic);
            }

            assertThat(player.yawRadians()).isCloseTo(50 * perTic, within(EPSILON));
        }

        @Test
        @DisplayName("does not aim a camera before there is a surface")
        void survivesTicsBeforeTheWindowExists()
        {
            // The game loop publishes tics from the moment it starts, which on
            // desktop is before GLFW has reported a size. An aspect of 0/0 is a
            // NaN that Camera.create rejects, so this must be skipped, not
            // attempted.
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), renderer(), new PlayerController(),
                config());

            assertThatCode(() -> port.tick(0)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the camera handoff")
    final class CameraHandoff
    {
        @Test
        @DisplayName("the frame is rendered from the controller's own eye")
        void cameraReachesTheRenderer()
        {
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(Scene.of(ModelFormat.read(DemoModelFixture.quad())));

            final PlayerController player = new PlayerController(10.0f, 0.0f, -20.0f,
                0.0f, 0.0f);
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), render, player, config());

            port.tick(0);
            render.renderFrame(0);

            // This is the whole join, asserted end to end: the camera the
            // rasterizer actually used is the one the player produced.
            assertThat(render.lastCamera()).isNotNull();
            assertThat(render.lastCamera().eye().x()).isCloseTo(10.0f, within(EPSILON));
            assertThat(render.lastCamera().eye().y())
                .isCloseTo(PlayerController.EYE_HEIGHT_UNITS, within(EPSILON));
            assertThat(render.lastCamera().eye().z()).isCloseTo(-20.0f, within(EPSILON));
        }

        @Test
        @DisplayName("the camera follows the player as input moves them")
        void cameraTracksMovement()
        {
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(Scene.of(ModelFormat.read(DemoModelFixture.quad())));

            final PlayerController player = new PlayerController();
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.of(1.0f, 0.0f, 0.0f, 0.0f, false, false, false)),
                render, player, config());

            port.tick(0);
            render.renderFrame(0);
            final float first = render.lastCamera().eye().z();

            for (int tic = 1; tic <= 30; tic++)
            {
                port.tick(tic);
            }
            render.renderFrame(1);

            assertThat(render.lastCamera().eye().z()).isGreaterThan(first);
        }
    }
}
