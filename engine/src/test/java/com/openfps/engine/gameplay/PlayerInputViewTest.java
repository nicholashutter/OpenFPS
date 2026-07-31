/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import com.openfps.engine.hal.port.InputState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PlayerInputView}, the seam between the HAL's
 * {@link InputState} and the gameplay {@code I_PlayerInput} contract.
 *
 * The two types were built by separate lanes against separate specs, so the
 * value of these tests is not that the delegation compiles — it is that the
 * four channels are wired to the <b>right</b> accessors. A transposed
 * yaw/pitch or forward/strafe pair compiles perfectly and produces a game that
 * strafes when you walk.
 */
class PlayerInputViewTest
{
    @Test
    @DisplayName("each channel maps to its counterpart, with no transposition")
    void shouldForwardEveryChannelToTheMatchingAccessor()
    {
        // Four distinct values, so a swapped pair cannot pass by coincidence.
        final InputState state = InputState.of(0.25f, -0.5f, 0.75f, -0.125f, false, false, false);
        final PlayerInputView view = new PlayerInputView();
        view.wrap(state);

        assertThat(view.forwardAxis()).isEqualTo(0.25f);
        assertThat(view.strafeAxis()).isEqualTo(-0.5f);
        assertThat(view.yawDelta()).isEqualTo(0.75f);
        assertThat(view.pitchDelta()).isEqualTo(-0.125f);
    }

    @Test
    @DisplayName("jump and sprint are forwarded, unlike fire")
    void shouldForwardJumpAndSprintButNotFire()
    {
        final InputState state = InputState.of(0.0f, 0.0f, 0.0f, 0.0f, true, true, true);
        final PlayerInputView view = new PlayerInputView();
        view.wrap(state);

        assertThat(view.jump()).isTrue();
        assertThat(view.sprint()).isTrue();
    }

    @Test
    @DisplayName("starts neutral so a controller can be updated before any input arrives")
    void shouldStartNeutral()
    {
        final PlayerInputView view = new PlayerInputView();

        assertThat(view.source()).isSameAs(InputState.NEUTRAL);
        assertThat(view.forwardAxis()).isZero();
        assertThat(view.strafeAxis()).isZero();
        assertThat(view.yawDelta()).isZero();
        assertThat(view.pitchDelta()).isZero();
    }

    @Test
    @DisplayName("re-pointing allocates nothing and reflects the new snapshot")
    void shouldReflectTheLatestSnapshotWithoutReallocating()
    {
        // The reason this class is mutable: one object for the whole session
        // rather than one per tic in the simulation path.
        final PlayerInputView view = new PlayerInputView();
        final InputState first = InputState.of(1.0f, 0.0f, 0.0f, 0.0f, false, false, false);
        final InputState second = InputState.of(0.0f, 1.0f, 0.0f, 0.0f, false, false, false);

        view.wrap(first);
        assertThat(view.forwardAxis()).isEqualTo(1.0f);

        view.wrap(second);
        assertThat(view.forwardAxis()).isZero();
        assertThat(view.strafeAxis()).isEqualTo(1.0f);
        assertThat(view.source()).isSameAs(second);
    }

    @Test
    @DisplayName("a null snapshot is rejected rather than silently zeroing the player")
    void shouldRejectNull()
    {
        final PlayerInputView view = new PlayerInputView();

        assertThatThrownBy(() -> view.wrap(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("state");
    }

    @Test
    @DisplayName("drives the controller identically to a hand-written input")
    void shouldDriveTheControllerLikeADirectImplementation()
    {
        // The end-to-end point of the seam: an InputState routed through the
        // view must move the player exactly as the controller's own contract
        // says it should.
        final PlayerInputView view = new PlayerInputView();
        view.wrap(InputState.of(1.0f, 0.0f, 0.0f, 0.0f, false, false, false));

        final PlayerController controller = new PlayerController();
        final float startZ = controller.positionZ();
        controller.update(view, 1.0f);

        assertThat(controller.positionZ())
            .as("yaw 0 faces +z, so full forward input advances along +z")
            .isGreaterThan(startZ);
        assertThat(controller.positionX())
            .as("no strafe input means no lateral movement")
            .isZero();
    }
}
