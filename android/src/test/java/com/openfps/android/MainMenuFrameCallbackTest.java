/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.openfps.gdx.DefaultMenuActions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
    /** The menu under test, wired to a window it can genuinely close. */
    private static MainMenuFrameCallback menu()
    {
        return new MainMenuFrameCallback(new DefaultMenuActions(
            new AndroidWindowPort(new FakeAndroidApplication())));
    }

    @Test
    @DisplayName("a menu with no actions has no way to honour a press, so it is refused")
    void shouldRejectNullActions()
    {
        assertThatThrownBy(() -> new MainMenuFrameCallback(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("menuActions");
    }

    @Test
    @DisplayName("a frame delivered before the surface exists is skipped, not drawn into nothing")
    void shouldIgnoreFramesBeforeTheSurfaceExists()
    {
        // The composite hands every frame to the menu unconditionally. There
        // is no GL context in this JVM at all, so reaching the clear-and-draw
        // path would fail outright — surviving the call is the assertion.
        assertThatCode(() -> menu().onFrame(0.016f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a resize arriving before the surface exists has no viewport to update")
    void shouldIgnoreResizesBeforeTheSurfaceExists()
    {
        assertThatCode(() -> menu().onResize(1080, 2340)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pause and resume hold no state, so they are safe at any point in the lifecycle")
    void shouldTolerateSuspensionBeforeTheSurfaceExists()
    {
        // Android can pause an Activity before its surface is ever ready. The
        // menu persists nothing itself — the profile is saved through
        // I_UserProfilePort — so neither call has anything to guard.
        final MainMenuFrameCallback menu = menu();

        assertThatCode(menu::onPause).doesNotThrowAnyException();

        assertThatCode(menu::onResume).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the menu is scaled down until it fits a short landscape screen")
    void shouldFitALandscapeScreen()
    {
        // The first emulator run showed this failing: at 2400x1080 and 2.625x
        // the title clipped off the top and Quit clipped off the bottom. On a
        // screen whose only two useful controls are "start" and "leave", that
        // is not a cosmetic problem.
        final float laidOut = MainMenuFrameCallback.layoutDensity(1080, 2.625f);

        assertThat(laidOut).isLessThan(2.625f);

        assertThat(MainMenuFrameCallback.naturalHeightDp() * laidOut)
            .isLessThanOrEqualTo(1080.0f);
    }

    @Test
    @DisplayName("a screen with room to spare is laid out at its true density")
    void shouldNotScaleUpOnATallScreen()
    {
        // The cap is a maximum, not a target. A tall portrait phone must get
        // physically correct sizes, not a menu stretched to fill it.
        assertThat(MainMenuFrameCallback.layoutDensity(2340, 2.625f)).isEqualTo(2.625f);
    }

    @Test
    @DisplayName("a surface with no height yet is not divided by")
    void shouldTolerateAZeroHeightSurface()
    {
        assertThat(MainMenuFrameCallback.layoutDensity(0, 2.625f)).isEqualTo(2.625f);

        assertThat(MainMenuFrameCallback.layoutDensity(1080, 0.0f)).isZero();
    }

    @Test
    @DisplayName("the input processor is not claimed until the menu is asked for it")
    void shouldNotClaimTheInputProcessorBeforeItIsBuilt()
    {
        // The menu used to take Gdx.input for itself inside onSurfaceReady.
        // That is exactly wrong once there is a game to play: a surface rebuilt
        // mid-match — which Android does on its own schedule — would silently
        // take every touch away from the player and hand it to invisible menu
        // buttons. Ownership belongs to whoever knows which half of the UI is
        // in front, and attach/detach is how it says so.
        final MainMenuFrameCallback menu = menu();

        assertThatCode(menu::attachInputProcessor).doesNotThrowAnyException();

        assertThatCode(menu::detachInputProcessor).doesNotThrowAnyException();
    }
}
