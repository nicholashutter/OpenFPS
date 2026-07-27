/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.engine.hal.adapter.nulladapter.NullWindowPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link GdxFrameLoopListener}'s forwarding to the engine
 * callback.
 *
 * Only the callbacks that need no GL context are covered:
 * {@code pause/resume/resize/dispose} touch nothing but the engine
 * callback when the menu has not been built. {@code create()} and
 * {@code render()} read {@code Gdx.graphics}, which does not exist without
 * a display, so they are left to the manual windowed run.
 */
class GdxFrameLoopListenerTest
{
    private static GdxFrameLoopListener listenerFor(final RecordingFrameCallback callback)
    {
        return new GdxFrameLoopListener(callback, new DefaultMenuActions(new NullWindowPort()));
    }

    @Test
    @DisplayName("a null callback is rejected")
    void shouldRejectNullCallback()
    {
        assertThatThrownBy(() -> new GdxFrameLoopListener(null, new DefaultMenuActions(new NullWindowPort())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callback");
    }

    @Test
    @DisplayName("null menu actions are rejected")
    void shouldRejectNullActions()
    {
        assertThatThrownBy(() -> new GdxFrameLoopListener(new RecordingFrameCallback(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actions");
    }

    @Test
    @DisplayName("pause forwards to onPause")
    void shouldForwardPause()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        listenerFor(callback).pause();
        assertThat(callback.pauseCount()).isEqualTo(1);
        assertThat(callback.resumeCount()).isZero();
    }

    @Test
    @DisplayName("resume forwards to onResume")
    void shouldForwardResume()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        listenerFor(callback).resume();
        assertThat(callback.resumeCount()).isEqualTo(1);
        assertThat(callback.pauseCount()).isZero();
    }

    @Test
    @DisplayName("resize forwards to onResize even before the menu exists")
    void shouldForwardResize()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        listenerFor(callback).resize(1024, 768);
        assertThat(callback.resizeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dispose forwards to onSurfaceLost")
    void shouldForwardDispose()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        listenerFor(callback).dispose();
        assertThat(callback.surfaceLostCount()).isEqualTo(1);
        assertThat(callback.surfaceReadyCount()).isZero();
        assertThat(callback.frameCount()).isZero();
    }
}
