/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the capture harness's file naming.
 *
 * <p>Everything else in {@link GdxScreenshot} needs a GL context and a window and
 * so belongs to the run script rather than to a unit test. The naming does not,
 * and it is the part that can quietly ruin a measurement: a burst that overwrote
 * one file would answer "how many pixels per frame" with the last frame's figure
 * six times, which is the sort of wrong answer that looks like data.</p>
 */
@DisplayName("GdxScreenshot")
final class GdxScreenshotTest
{
    @Test
    @DisplayName("a single capture keeps exactly the name it was given")
    void oneFrameIsUnchanged()
    {
        // Every existing caller asks for one frame. None of them may see a
        // different file appear because a count was added.
        assertThat(GdxScreenshot.fileFor("C:\\tmp\\frame.png", 1, 1))
            .isEqualTo("C:\\tmp\\frame.png");
        assertThat(GdxScreenshot.fileFor("C:\\tmp\\frame.png", 0, 1))
            .isEqualTo("C:\\tmp\\frame.png");
    }

    @Test
    @DisplayName("a burst numbers before the extension, zero padded so it sorts")
    void aBurstIsNumbered()
    {
        assertThat(GdxScreenshot.fileFor("C:\\tmp\\frame.png", 12, 1))
            .isEqualTo("C:\\tmp\\frame-0001.png");
        assertThat(GdxScreenshot.fileFor("C:\\tmp\\frame.png", 12, 12))
            .isEqualTo("C:\\tmp\\frame-0012.png");
        // Padded, so a plain lexical sort of a directory listing is capture
        // order. Without it frame-10 sorts before frame-2 and a "sequence" is
        // silently shuffled.
        assertThat(GdxScreenshot.fileFor("/tmp/f.png", 12, 2).compareTo(
            GdxScreenshot.fileFor("/tmp/f.png", 12, 10))).isNegative();
    }

    @Test
    @DisplayName("a dot in a directory name is not mistaken for an extension")
    void directoryDotsAreNotExtensions()
    {
        // Scratch directories with dots in them are common enough, and appending
        // the index to a directory component would write outside the target
        // directory entirely.
        assertThat(GdxScreenshot.fileFor("C:\\my.shots\\frame", 4, 3))
            .isEqualTo("C:\\my.shots\\frame-0003");
        assertThat(GdxScreenshot.fileFor("/var/tmp.d/frame", 4, 3))
            .isEqualTo("/var/tmp.d/frame-0003");
    }

    @Test
    @DisplayName("capture is off entirely without a path")
    void noPathNoCapture()
    {
        assertThat(new GdxScreenshot(null, 1, 4, true).isEnabled()).isFalse();
        assertThat(new GdxScreenshot("", 1, 4, true).isEnabled()).isFalse();
        assertThat(new GdxScreenshot("frame.png", 1, 4, true).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("a count below one is treated as one rather than rejected")
    void aBadCountIsHarmless()
    {
        // A diagnostic switch must not be able to fail a run.
        assertThat(new GdxScreenshot("frame.png", 1, -3, false).capturesWritten()).isZero();
        assertThat(GdxScreenshot.fileFor("frame.png", -3, 1)).isEqualTo("frame.png");
    }
}
