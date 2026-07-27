/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import com.openfps.engine.common.Constants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the acknowledgement window: highest contiguous tic plus a 64-bit
 * bitfield of the tics below the newest one seen.
 */
@DisplayName("AckWindow — highest contiguous tic plus a 64-bit loss bitfield")
class AckWindowTest
{
    private AckWindow window;

    @BeforeEach
    void setUp()
    {
        window = new AckWindow();
    }

    @Test
    @DisplayName("is exactly as wide as the input ring, so anything still replayable is still ackable")
    void widthMatchesTheInputRingDepth()
    {
        assertThat(AckWindow.WIDTH).isEqualTo(Constants.TIC_BUFFER_SIZE);
    }

    @Test
    @DisplayName("starts with nothing received and nothing contiguous")
    void startsEmpty()
    {
        assertThat(window.highestTic()).isEqualTo(AckWindow.NO_TIC);
        assertThat(window.highestContiguousTic()).isEqualTo(-1);
        assertThat(window.bitfield()).isZero();
        assertThat(window.isAcked(0)).isFalse();
        assertThat(window.lossPercent()).isZero();
    }

    @Test
    @DisplayName("advances the contiguous tic as an unbroken run of tics arrives")
    void advancesOnAnUnbrokenRun()
    {
        for (int tic = 0; tic <= 9; tic++)
        {
            assertThat(window.record(tic)).isTrue();
            assertThat(window.highestContiguousTic()).isEqualTo(tic);
        }
        assertThat(window.highestTic()).isEqualTo(9);
        assertThat(window.lossPercent()).isZero();
    }

    @Test
    @DisplayName("holds the contiguous tic at the gap when a single tic is lost, then jumps past it "
        + "once the redundant copy lands")
    void recoversFromASingleLoss()
    {
        for (int tic = 0; tic <= 9; tic++)
        {
            if (tic != 5)
            {
                window.record(tic);
            }
        }

        assertThat(window.highestTic()).isEqualTo(9);
        assertThat(window.highestContiguousTic()).isEqualTo(4);
        assertThat(window.isAcked(5)).isFalse();
        assertThat(window.isAcked(6)).isTrue();

        assertThat(window.record(5)).isTrue();

        assertThat(window.highestContiguousTic()).isEqualTo(9);
    }

    @Test
    @DisplayName("recovers from three consecutive lost tics in a single later delivery")
    void recoversFromThreeConsecutiveLosses()
    {
        for (int tic = 0; tic <= 11; tic++)
        {
            if (tic < 4 || tic > 6)
            {
                window.record(tic);
            }
        }

        assertThat(window.highestContiguousTic()).isEqualTo(3);
        assertThat(window.isAcked(4)).isFalse();
        assertThat(window.isAcked(5)).isFalse();
        assertThat(window.isAcked(6)).isFalse();

        window.record(4);
        window.record(5);
        assertThat(window.highestContiguousTic()).isEqualTo(5);

        window.record(6);
        assertThat(window.highestContiguousTic()).isEqualTo(11);
    }

    @Test
    @DisplayName("recovers from seven consecutive lost tics, the edge of the recommended window")
    void recoversFromSevenConsecutiveLosses()
    {
        final int lossStart = 10;
        final int lossCount = 7;
        for (int tic = 0; tic <= 25; tic++)
        {
            if (tic < lossStart || tic >= lossStart + lossCount)
            {
                window.record(tic);
            }
        }

        assertThat(window.highestContiguousTic()).isEqualTo(lossStart - 1);
        for (int tic = lossStart; tic < lossStart + lossCount; tic++)
        {
            assertThat(window.isAcked(tic)).isFalse();
        }

        for (int tic = lossStart; tic < lossStart + lossCount; tic++)
        {
            window.record(tic);
        }

        assertThat(window.highestContiguousTic()).isEqualTo(25);
        assertThat(window.lossPercent()).isZero();
    }

    @Test
    @DisplayName("files out-of-order arrivals under their own tic numbers")
    void handlesOutOfOrderArrival()
    {
        window.record(3);
        window.record(1);
        window.record(0);
        window.record(4);
        window.record(2);

        assertThat(window.highestTic()).isEqualTo(4);
        assertThat(window.highestContiguousTic()).isEqualTo(4);
    }

    @Test
    @DisplayName("treats a re-delivered tic as a no-op rather than double counting it")
    void treatsDuplicatesAsNoOps()
    {
        assertThat(window.record(0)).isTrue();
        assertThat(window.record(0)).isFalse();

        window.record(1);
        window.record(2);

        assertThat(window.record(1)).isFalse();
        assertThat(window.record(2)).isFalse();
        assertThat(window.highestContiguousTic()).isEqualTo(2);
    }

    @Test
    @DisplayName("covers exactly the 64 tics below the newest one and no further")
    void coversExactlySixtyFourTicsBelowTheNewest()
    {
        final int width = AckWindow.WIDTH;
        for (int tic = 0; tic <= width; tic++)
        {
            window.record(tic);
        }

        assertThat(window.highestTic()).isEqualTo(width);
        assertThat(window.bitfield()).isEqualTo(-1L);
        assertThat(window.isAcked(0)).isTrue();

        window.record(width + 1);

        assertThat(window.isAcked(1)).isTrue();
        assertThat(window.windowBits()).isEqualTo(width);
    }

    @Test
    @DisplayName("shifts the bitfield rather than wrapping it when the newest tic advances")
    void shiftsTheBitfieldAsTheNewestTicAdvances()
    {
        window.record(0);
        window.record(1);
        window.record(3);

        // bit 0 covers tic 2 (missing), bit 1 covers tic 1, bit 2 covers tic 0
        assertThat(window.bitfield()).isEqualTo(0b110L);

        window.record(4);

        // everything shifts up one: bit 0 now covers tic 3
        assertThat(window.bitfield()).isEqualTo(0b1101L);
    }

    @Test
    @DisplayName("clears the bitfield when the newest tic jumps a full window ahead, because every "
        // A long shift of 64 or more is taken modulo 64 in Java and would rotate.
        + "bit it described has shifted out")
    void clearsTheBitfieldOnAFullWindowJump()
    {
        for (int tic = 0; tic <= 5; tic++)
        {
            window.record(tic);
        }
        final int jump = 5 + AckWindow.WIDTH;

        window.record(jump);

        assertThat(window.bitfield()).isZero();
        assertThat(window.isAcked(jump)).isTrue();
        assertThat(window.isAcked(4)).isTrue();
        assertThat(window.isAcked(jump - 1)).isFalse();
    }

    @Test
    @DisplayName("floors the contiguous tic one window back so an unrecoverable gap cannot pin it "
        + "forever, then lets it catch up once the gap has aged out")
    void floorsTheContiguousTicOneWindowBack()
    {
        final int width = AckWindow.WIDTH;
        window.record(0);
        // tic 1 is never delivered, by any packet, ever
        for (int tic = 2; tic <= width + 1; tic++)
        {
            window.record(tic);
        }

        // The gap at tic 1 still pins the run, so only the floor moves it.
        assertThat(window.highestTic()).isEqualTo(width + 1);
        assertThat(window.highestContiguousTic()).isEqualTo(1);
        assertThat(window.isAcked(1)).isTrue();

        // One more tic and the gap has fallen out of the bitfield entirely, so
        // the unbroken run above it can finally be recognised.
        window.record(width + 2);

        assertThat(window.highestContiguousTic()).isEqualTo(width + 2);
    }

    @Test
    @DisplayName("refuses a tic that has already fallen out of the window")
    void refusesATicBelowTheWindow()
    {
        for (int tic = 0; tic <= AckWindow.WIDTH + 10; tic++)
        {
            window.record(tic);
        }

        assertThat(window.record(0)).isFalse();
        assertThat(window.record(1)).isFalse();
    }

    @Test
    @DisplayName("reports loss as a whole percentage over the window, using integer arithmetic only")
    void reportsLossAsAWholePercentage()
    {
        for (int tic = 0; tic < 10; tic++)
        {
            window.record(tic);
        }
        assertThat(window.windowBits()).isEqualTo(9);
        assertThat(window.receivedInWindow()).isEqualTo(9);
        assertThat(window.lossPercent()).isZero();

        final AckWindow lossy = new AckWindow();
        for (int tic = 0; tic <= 10; tic++)
        {
            if (tic % 2 == 0)
            {
                lossy.record(tic);
            }
        }
        // Newest is 10; bits cover tics 9..0, of which the five even ones arrived.
        assertThat(lossy.windowBits()).isEqualTo(10);
        assertThat(lossy.receivedInWindow()).isEqualTo(5);
        assertThat(lossy.lossPercent()).isEqualTo(50);
    }

    @Test
    @DisplayName("asks for one command when the peer is fully caught up")
    void asksForOneCommandWhenCaughtUp()
    {
        for (int tic = 0; tic <= 5; tic++)
        {
            window.record(tic);
        }

        assertThat(window.redundancyWindow(5)).isEqualTo(1);
    }

    @Test
    @DisplayName("widens the redundancy window to cover every tic the peer has not acked")
    void widensTheWindowToCoverUnackedTics()
    {
        window.record(0);
        window.record(1);
        // tic 2 lost; the sender must keep resending from tic 2 onward
        window.record(3);

        assertThat(window.redundancyWindow(6)).isEqualTo(5);
    }

    @Test
    @DisplayName("never asks for fewer than one or more than one full ring depth of commands")
    void clampsTheRedundancyWindow()
    {
        assertThat(AckWindow.clampWindow(0)).isEqualTo(1);
        assertThat(AckWindow.clampWindow(-5)).isEqualTo(1);
        assertThat(AckWindow.clampWindow(1)).isEqualTo(1);
        assertThat(AckWindow.clampWindow(AckWindow.WIDTH)).isEqualTo(AckWindow.WIDTH);
        assertThat(AckWindow.clampWindow(AckWindow.WIDTH + 1)).isEqualTo(AckWindow.WIDTH);
        assertThat(window.redundancyWindow(10_000)).isEqualTo(AckWindow.WIDTH);
    }

    @Test
    @DisplayName("lets a peer joining mid-match start from its own base tic instead of demanding "
        + "the whole match be resent")
    void supportsALateJoiner()
    {
        final AckWindow late = new AckWindow(1000);

        assertThat(late.highestContiguousTic()).isEqualTo(999);
        assertThat(late.redundancyWindow(1000)).isEqualTo(1);

        late.record(1000);
        late.record(1001);

        assertThat(late.highestContiguousTic()).isEqualTo(1001);
        assertThat(late.windowBits()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns to its initial state on reset, for when a peer drops")
    void resetsToTheInitialState()
    {
        window.record(0);
        window.record(1);

        window.reset();

        assertThat(window.highestTic()).isEqualTo(AckWindow.NO_TIC);
        assertThat(window.highestContiguousTic()).isEqualTo(-1);
        assertThat(window.bitfield()).isZero();
    }

    @Test
    @DisplayName("rejects a negative tic number and a negative base tic")
    void rejectsNegativeInputs()
    {
        assertThatThrownBy(() -> window.record(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AckWindow(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(window.isAcked(-1)).isFalse();
    }
}
