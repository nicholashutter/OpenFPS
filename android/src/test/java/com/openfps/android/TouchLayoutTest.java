/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link TouchLayout}.
 *
 * <p><b>This is the part of Android input a machine can check.</b> The class
 * imports nothing, so every one of these runs in a plain JVM — no device, no
 * emulator, no GL context. What is being checked is not decoration: a control
 * scheme fails by being subtly wrong (a button that is drawn but cannot be hit,
 * a stick whose forward is backwards, a dead zone that lets a resting thumb
 * walk you into a wall) and every one of those is invisible in a screenshot and
 * obvious in an assertion.</p>
 */
@DisplayName("TouchLayout")
class TouchLayoutTest
{
    /** A landscape phone at 2.75x — a Pixel-class device, which is what the emulator is. */
    private static final float DENSITY = 2.75f;

    /** Surface width in pixels. */
    private static final int WIDTH = 2340;

    /** Surface height in pixels. */
    private static final int HEIGHT = 1080;

    private static TouchLayout sized()
    {
        final TouchLayout layout = new TouchLayout(DENSITY);
        layout.resize(WIDTH, HEIGHT);
        return layout;
    }

    @Nested
    @DisplayName("construction and sizing")
    class Sizing
    {
        @Test
        @DisplayName("a density that is not a positive finite number is refused")
        void shouldRejectAnImpossibleDensity()
        {
            assertThatThrownBy(() -> new TouchLayout(0.0f))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TouchLayout(-1.0f))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TouchLayout(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TouchLayout(Float.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a zero-sized surface is ignored rather than believed")
        void shouldIgnoreAZeroSizedSurface()
        {
            // Android really does report 0x0 briefly during rotation. A layout
            // that believed it would stack every control on the origin, and the
            // first touch afterwards would hit whichever won the priority
            // order — which is to say, leave the match.
            final TouchLayout layout = sized();

            layout.resize(0, 0);

            assertThat(layout.width()).isEqualTo(WIDTH);
            assertThat(layout.height()).isEqualTo(HEIGHT);
        }

        @Test
        @DisplayName("nothing can be hit before the layout has been sized")
        void shouldHitNothingBeforeSizing()
        {
            assertThat(new TouchLayout(DENSITY).regionAt(100.0f, 100.0f))
                .isEqualTo(TouchLayout.REGION_NONE);
        }

        @Test
        @DisplayName("a dp size becomes pixels through the density and nothing else")
        void shouldScaleByDensity()
        {
            assertThat(sized().pixels(48.0f)).isEqualTo(48.0f * DENSITY);
        }
    }

    @Nested
    @DisplayName("which control a touch lands on")
    class Regions
    {
        @Test
        @DisplayName("the left half is the movement stick")
        void shouldPutTheStickOnTheLeft()
        {
            final TouchLayout layout = sized();

            assertThat(layout.regionAt(10.0f, HEIGHT - 10.0f))
                .isEqualTo(TouchLayout.REGION_MOVE_STICK);
            assertThat(layout.regionAt(WIDTH * 0.25f, HEIGHT * 0.5f))
                .isEqualTo(TouchLayout.REGION_MOVE_STICK);
        }

        @Test
        @DisplayName("the right half is the look area")
        void shouldPutLookOnTheRight()
        {
            assertThat(sized().regionAt(WIDTH * 0.7f, HEIGHT * 0.4f))
                .isEqualTo(TouchLayout.REGION_LOOK);
        }

        @Test
        @DisplayName("each button's centre hits that button")
        void shouldHitEachButtonAtItsCentre()
        {
            final TouchLayout layout = sized();

            assertThat(layout.regionAt(layout.fireCentreX(), layout.fireCentreY()))
                .isEqualTo(TouchLayout.REGION_FIRE);
            assertThat(layout.regionAt(layout.jumpCentreX(), layout.jumpCentreY()))
                .isEqualTo(TouchLayout.REGION_JUMP);
            assertThat(layout.regionAt(layout.leaveCentreX(), layout.leaveCentreY()))
                .isEqualTo(TouchLayout.REGION_LEAVE);
        }

        @Test
        @DisplayName("the buttons beat the look half they sit inside")
        void shouldTestButtonsBeforeHalves()
        {
            // Fire and jump are in the right half, which is also the look
            // region. If halves were tested first the fire button would be
            // unreachable and every tap on it would spin the camera — which
            // looks like a broken renderer, not a broken hit test.
            final TouchLayout layout = sized();

            assertThat(layout.fireCentreX()).isGreaterThan(WIDTH * TouchLayout.MOVE_HALF_FRACTION);
            assertThat(layout.regionAt(layout.fireCentreX(), layout.fireCentreY()))
                .isEqualTo(TouchLayout.REGION_FIRE);
        }

        @Test
        @DisplayName("just outside a button's rim is the area behind it, not the button")
        void shouldNotHitOutsideTheRim()
        {
            final TouchLayout layout = sized();
            final float justOutside = layout.fireCentreX() - layout.fireRadius() - 1.0f;

            assertThat(layout.regionAt(justOutside, layout.fireCentreY()))
                .isEqualTo(TouchLayout.REGION_LOOK);
        }

        @Test
        @DisplayName("no two buttons overlap")
        void shouldKeepTheButtonsApart()
        {
            // An overlap makes one of them unpressable in its shared area, and
            // which one depends on the order of the tests in regionAt rather
            // than on anything a player could see.
            final TouchLayout layout = sized();

            assertThat(distance(layout.fireCentreX(), layout.fireCentreY(),
                layout.jumpCentreX(), layout.jumpCentreY()))
                .isGreaterThan(layout.fireRadius() + layout.jumpRadius());
            assertThat(distance(layout.fireCentreX(), layout.fireCentreY(),
                layout.leaveCentreX(), layout.leaveCentreY()))
                .isGreaterThan(layout.fireRadius() + layout.leaveRadius());
        }

        @Test
        @DisplayName("every button clears Material's 48 dp minimum touch target")
        void shouldMeetTheMinimumTouchTarget()
        {
            final TouchLayout layout = sized();
            final float minimum = layout.pixels(48.0f);

            assertThat(layout.fireRadius() * 2.0f).isGreaterThanOrEqualTo(minimum);
            assertThat(layout.jumpRadius() * 2.0f).isGreaterThanOrEqualTo(minimum);
            assertThat(layout.leaveRadius() * 2.0f).isGreaterThanOrEqualTo(minimum);
        }

        @Test
        @DisplayName("the buttons stay on screen on a small phone")
        void shouldFitASmallScreen()
        {
            // 720x360 dp is about the smallest landscape surface still in use.
            // A layout measured from the right and bottom edges cannot run off
            // them, but it CAN run into the left half and steal the stick, and
            // that would be a phone on which the player cannot walk.
            final TouchLayout layout = new TouchLayout(2.0f);
            layout.resize(1280, 720);

            assertThat(layout.jumpCentreX() - layout.jumpRadius())
                .isGreaterThan(layout.width() * TouchLayout.MOVE_HALF_FRACTION);
        }
    }

    @Nested
    @DisplayName("the movement stick")
    class Stick
    {
        @Test
        @DisplayName("pushing away from you walks forward")
        void shouldWalkForwardWhenPushedUpTheScreen()
        {
            // The single easiest thing to get backwards: screen y grows
            // DOWNWARD, so forward is the negative direction and the
            // subtraction runs the other way round from the strafe axis.
            final TouchLayout layout = sized();
            final float anchorX = 300.0f;
            final float anchorY = 800.0f;

            assertThat(layout.stickForward(anchorX, anchorY, anchorX,
                anchorY - layout.stickRange())).isEqualTo(1.0f);
            assertThat(layout.stickForward(anchorX, anchorY, anchorX,
                anchorY + layout.stickRange())).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("pushing right strafes right")
        void shouldStrafeRightWhenPushedRight()
        {
            final TouchLayout layout = sized();
            final float anchorX = 300.0f;
            final float anchorY = 800.0f;

            assertThat(layout.stickStrafe(anchorX, anchorY,
                anchorX + layout.stickRange(), anchorY)).isEqualTo(1.0f);
            assertThat(layout.stickStrafe(anchorX, anchorY,
                anchorX - layout.stickRange(), anchorY)).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("half the travel is half the deflection")
        void shouldBeLinearInsideItsRange()
        {
            final TouchLayout layout = sized();
            final float anchorY = 800.0f;

            assertThat(layout.stickForward(300.0f, anchorY, 300.0f,
                anchorY - layout.stickRange() / 2.0f)).isCloseTo(0.5f, within(0.001f));
        }

        @Test
        @DisplayName("past full travel is still full deflection, not more")
        void shouldClampBeyondItsRange()
        {
            final TouchLayout layout = sized();

            assertThat(layout.stickForward(300.0f, 800.0f, 300.0f, -5000.0f)).isEqualTo(1.0f);
            assertThat(layout.stickStrafe(300.0f, 800.0f, 99000.0f, 800.0f)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("a resting thumb inside the dead zone is standing still")
        void shouldIgnoreJitterInsideTheDeadZone()
        {
            // Without this a finger resting on a capacitive screen jitters a
            // pixel or two forever: the player is never quite still, aiming
            // drifts, and a networked match sends a nonzero axis every tic.
            final TouchLayout layout = sized();
            final float anchorX = 300.0f;
            final float anchorY = 800.0f;
            final float jitter = layout.stickDeadZone() * 0.5f;

            assertThat(layout.stickForward(anchorX, anchorY, anchorX + jitter, anchorY))
                .isZero();
            assertThat(layout.stickStrafe(anchorX, anchorY, anchorX, anchorY + jitter))
                .isZero();
        }

        @Test
        @DisplayName("the dead zone is measured on the whole displacement, not per axis")
        void shouldMeasureTheDeadZoneRadially()
        {
            // A thumb 5 px right and 5 px up has moved 7 px. Testing each axis
            // alone would call that "inside the dead zone on both" and let a
            // diagonal drift through as movement on neither.
            final TouchLayout layout = sized();
            final float anchorX = 300.0f;
            final float anchorY = 800.0f;
            final float justOverRadially = layout.stickDeadZone() * 0.8f;

            assertThat(layout.stickForward(anchorX, anchorY,
                anchorX + justOverRadially, anchorY - justOverRadially)).isNotZero();
        }

        @Test
        @DisplayName("the drawn knob never leaves the ring, however far the thumb goes")
        void shouldClampTheKnobToTheRing()
        {
            final TouchLayout layout = sized();
            final float anchorX = 300.0f;
            final float anchorY = 800.0f;

            final float offsetX = layout.knobOffset(anchorX, 9000.0f, anchorY, anchorY);
            final float offsetY = layout.knobOffset(anchorY, anchorY, anchorX, 9000.0f);

            assertThat(offsetX).isCloseTo(layout.stickRange(), within(0.01f));
            assertThat(offsetY).isZero();
        }

        @Test
        @DisplayName("inside the ring the knob follows the thumb exactly")
        void shouldFollowTheThumbInsideTheRing()
        {
            final TouchLayout layout = sized();

            assertThat(layout.knobOffset(300.0f, 320.0f, 800.0f, 800.0f)).isEqualTo(20.0f);
        }

        @Test
        @DisplayName("a knob offset at the anchor is zero rather than a division by zero")
        void shouldTolerateAZeroDisplacement()
        {
            assertThat(sized().knobOffset(300.0f, 300.0f, 800.0f, 800.0f)).isZero();
        }
    }

    private static float distance(final float ax, final float ay,
        final float bx, final float by)
    {
        final float dx = ax - bx;
        final float dy = ay - by;
        return (float) Math.sqrt((dx * dx) + (dy * dy));
    }
}
