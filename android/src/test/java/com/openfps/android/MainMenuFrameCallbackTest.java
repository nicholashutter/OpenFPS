/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the part of {@link MainMenuFrameCallback} that runs without a GL
 * context: what it does before the surface exists.
 *
 * <b>Almost nothing here is covered, deliberately.</b> Building the skin, the
 * stage and the layout needs a live context and the libGDX natives, and
 * disposing them needs the same — so the menu's appearance and its resource
 * lifetime are verified by running the app, exactly as the desktop module
 * verifies its own presentation path. What is covered is the guard that
 * decides <i>whether</i> any of that runs, because a frame arriving before
 * {@code onSurfaceReady} is the one case where the difference is a crash
 * rather than a pixel.
 */
class MainMenuFrameCallbackTest
{
    @Test
    @DisplayName("a menu with no window has no way to honour Quit, so it is refused")
    void shouldRejectNullWindow()
    {
        assertThatThrownBy(() -> new MainMenuFrameCallback(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("window");
    }

    @Test
    @DisplayName("a frame delivered before the surface exists is skipped, not drawn into nothing")
    void shouldIgnoreFramesBeforeTheSurfaceExists()
    {
        // The composite hands every frame to the menu unconditionally. There
        // is no GL context in this JVM at all, so reaching the clear-and-draw
        // path would fail outright — surviving the call is the assertion.
        final MainMenuFrameCallback menu =
            new MainMenuFrameCallback(new AndroidWindowPort(new FakeAndroidApplication()));

        assertThatCode(() -> menu.onFrame(0.016f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a resize arriving before the surface exists has no viewport to update")
    void shouldIgnoreResizesBeforeTheSurfaceExists()
    {
        final MainMenuFrameCallback menu =
            new MainMenuFrameCallback(new AndroidWindowPort(new FakeAndroidApplication()));

        assertThatCode(() -> menu.onResize(1080, 2340)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pause and resume hold no state, so they are safe at any point in the lifecycle")
    void shouldTolerateSuspensionBeforeTheSurfaceExists()
    {
        // Android can pause an Activity before its surface is ever ready. The
        // menu persists nothing itself — the profile is saved through
        // I_UserProfilePort — so neither call has anything to guard.
        final MainMenuFrameCallback menu =
            new MainMenuFrameCallback(new AndroidWindowPort(new FakeAndroidApplication()));

        assertThatCode(menu::onPause).doesNotThrowAnyException();
        assertThatCode(menu::onResume).doesNotThrowAnyException();
    }
}
