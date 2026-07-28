/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.InputState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the headless input port stays perfectly still.
 *
 * These matter more than they look: every headless run and almost every test
 * in the suite gets this port, so "the player does nothing, on every tic,
 * forever" is an assumption the rest of the suite is built on.
 */
class NullInputPortTest
{
    @Test
    @DisplayName("reports the neutral snapshot before anything is sampled")
    void shouldStartNeutral()
    {
        final NullInputPort port = new NullInputPort();
        assertThat(port.currentInput()).isSameAs(InputState.NEUTRAL);
        assertThat(port.currentInput().isNeutral()).isTrue();
    }

    @Test
    @DisplayName("stays neutral no matter how many tics are sampled")
    void shouldStayNeutralAcrossTics()
    {
        final NullInputPort port = new NullInputPort();
        port.init();
        for (int tic = 0; tic < 8; tic++)
        {
            port.sampleInput(tic);
            assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
        }
        port.shutdown();
    }

    @Test
    @DisplayName("does not request shutdown until asked")
    void shouldNotRequestShutdownByDefault()
    {
        final NullInputPort port = new NullInputPort();
        port.init();
        assertThat(port.isShutdownRequested()).isFalse();
        port.requestShutdown();
        assertThat(port.isShutdownRequested()).isTrue();
        port.init();
        assertThat(port.isShutdownRequested()).isFalse();
    }
}
