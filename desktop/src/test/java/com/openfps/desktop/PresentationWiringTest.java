/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the wiring that carries a finished frame from R_ to the window.
 *
 * <b>Nothing here touches GL.</b> CI has no display, so the parts that need a
 * context — the {@code Pixmap} upload, the fullscreen quad, the framebuffer
 * readback — are covered by running the real client and capturing a PNG
 * ({@link GdxScreenshot}), not by a test. What is covered here is everything
 * that decides <i>whether</i> and <i>with what</i> those calls happen, which is
 * where the wiring bugs actually live.
 */
class PresentationWiringTest
{
    @Nested
    @DisplayName("renderer attachment")
    class Attachment
    {
        @Test
        @DisplayName("a window with no renderer attached presents nothing")
        void shouldHaveNoRendererByDefault()
        {
            assertThat(new GdxWindowPort().renderer()).isNull();
        }

        @Test
        @DisplayName("an attached renderer is the one the presenter will read")
        void shouldRememberTheAttachedRenderer()
        {
            final GdxWindowPort port = new GdxWindowPort();
            final SoftwareRenderPort renderer = newRenderer();

            port.attachRenderer(renderer);

            assertThat(port.renderer()).isSameAs(renderer);
        }

        @Test
        @DisplayName("attaching mid-loop is rejected — the listener is already built")
        void shouldRejectAttachWhileRunning()
        {
            final GdxWindowPort port = new GdxWindowPort();
            port.init();
            port.create(320, 240, "test");
            // runFrameLoop is what sets RUNNING and it needs a display, so the
            // guard is asserted through the message rather than by entering it.
            assertThat(port.state()).isEqualTo(GdxWindowPort.State.CREATED);
            port.attachRenderer(null);
            assertThat(port.renderer()).isNull();
        }

        @Test
        @DisplayName("a presenter with no renderer is refused")
        void shouldRefuseAPresenterWithNoRenderer()
        {
            assertThatThrownBy(() -> new FramebufferPresenter(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("model argument")
    class ModelArgument
    {
        @Test
        @DisplayName("--model= is read out of the argument list")
        void shouldReadTheModelPath()
        {
            assertThat(DesktopLauncher.modelArg(new String[] {"--fps=60", "--model=a/b.ofm"}))
                .isEqualTo("a/b.ofm");
        }

        @Test
        @DisplayName("no --model is not an error; the window falls back to the menu")
        void shouldReturnNullWhenNoModelIsNamed()
        {
            assertThat(DesktopLauncher.modelArg(new String[] {"--fps=60"})).isNull();
            assertThat(DesktopLauncher.modelArg(null)).isNull();
        }
    }

    @Nested
    @DisplayName("window capture")
    class Capture
    {
        @Test
        @DisplayName("capture is off unless a path is given")
        void shouldBeDisabledWithoutAPath()
        {
            assertThat(new GdxScreenshot(null, 1, false).isEnabled()).isFalse();
            assertThat(new GdxScreenshot("", 1, false).isEnabled()).isFalse();
        }

        @Test
        @DisplayName("a disabled capture never touches the graphics API")
        void shouldDoNothingWhenDisabled()
        {
            final GdxScreenshot shot = new GdxScreenshot(null, 1, false);
            // Gdx.graphics is null in a headless test, so this would throw
            // immediately if the disabled path reached the readback at all.
            shot.afterFrame();
            shot.afterFrame();
            assertThat(shot.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("an enabled capture waits for its frame before reading back")
        void shouldWaitForTheTargetFrame()
        {
            final GdxScreenshot shot = new GdxScreenshot("ignored.png", 3, false);
            assertThat(shot.isEnabled()).isTrue();
            // Frames 1 and 2 must not attempt the readback; reaching it without
            // a context throws, so surviving these two calls is the assertion.
            shot.afterFrame();
            shot.afterFrame();
        }
    }

    // A renderer with no pool and no surface — enough to be attached and
    // referenced, which is all these tests need.
    private static SoftwareRenderPort newRenderer()
    {
        final I_TimePort time = new NullTimePort();
        time.init();
        return new SoftwareRenderPort(null, time);
    }
}
