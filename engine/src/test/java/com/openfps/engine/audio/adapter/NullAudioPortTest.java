/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.openfps.engine.audio.AudioVolume;
import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.audio.port.SoundId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link I_AudioPort}, exercised against the null adapter.
 *
 * <p>Deliberately written against the interface type rather than the concrete
 * class wherever the assertion is about the contract, because these are the
 * rules every implementation owes — including the libGDX one, which cannot be
 * tested here for the reason this class exists: CI has no sound card.</p>
 *
 * <p>The rule that matters most is the negative one. <b>Nothing throws.</b> Not
 * on a machine with no audio device, not in a headless JVM, not when called out
 * of lifecycle order. A game that dies because the speakers are missing is
 * worse than a silent one, and the whole engine reaches this port on every
 * headless run.</p>
 */
@DisplayName("NullAudioPort")
final class NullAudioPortTest
{
    @Nested
    @DisplayName("the contract")
    final class Contract
    {
        @Test
        @DisplayName("never throws, whatever order it is called in")
        void shouldNeverThrow()
        {
            final I_AudioPort port = new NullAudioPort();

            // Shutdown before init, play before init, double init, double
            // shutdown, play after shutdown. Every one of these is reachable:
            // Android's onDestroy can arrive with the loop still running, and
            // the game loop publishes tics before and after the subsystems
            // start and stop.
            assertThatCode(() ->
            {
                port.shutdown();
                port.play(SoundId.WEAPON_FIRE);
                port.init();
                port.init();
                port.play(SoundId.WEAPON_FIRE);
                port.stopAll();
                port.shutdown();
                port.shutdown();
                port.play(SoundId.WEAPON_FIRE);
                port.stopAll();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("preloading nothing is a no-op that does not count as a play")
        void shouldIgnorePreload()
        {
            // preload is a hint about a real device's load latency and this port
            // has no device. It must stay free, and — the part worth pinning —
            // it must not move the play count, or every test that asserts "one
            // shot fired one sound" starts reading two.
            final NullAudioPort port = new NullAudioPort();

            assertThatCode(() ->
            {
                port.preload();
                port.init();
                port.preload();
                port.preload();
                port.shutdown();
                port.preload();
            }).doesNotThrowAnyException();
            assertThat(port.playCount()).isZero();
            assertThat(port.lastSound()).isNull();
            assertThat(port.isAudible()).isFalse();
        }

        @Test
        @DisplayName("ignores a null sound rather than rejecting it")
        void shouldIgnoreANullSound()
        {
            // A caller cannot usefully handle "the speakers do not know that
            // noise", so the port swallows it. The count proves it was swallowed
            // rather than played.
            final NullAudioPort port = new NullAudioPort();
            port.init();

            assertThatCode(() -> port.play(null)).doesNotThrowAnyException();
            assertThat(port.playCount()).isZero();
            assertThat(port.lastSound()).isNull();
        }

        @Test
        @DisplayName("reports itself inaudible, which is the whole point of it")
        void shouldReportInaudible()
        {
            final I_AudioPort port = new NullAudioPort();
            port.init();

            assertThat(port.isAudible()).isFalse();
        }
    }

    @Nested
    @DisplayName("volume")
    final class Volume
    {
        @Test
        @DisplayName("starts at full scale")
        void shouldStartAtFullScale()
        {
            assertThat(new NullAudioPort().masterVolume()).isEqualTo(AudioVolume.FULL);
        }

        @Test
        @DisplayName("reads back the CLAMPED value, not the requested one")
        void shouldReadBackTheClampedValue()
        {
            // The port tells you what it will actually do, not what you asked
            // for. A settings screen that echoed 4.0 back to the user after
            // asking for 4.0 would be lying about a volume it is going to play
            // at 1.0.
            final I_AudioPort port = new NullAudioPort();

            port.setMasterVolume(4.0f);
            assertThat(port.masterVolume()).isEqualTo(AudioVolume.FULL);

            port.setMasterVolume(-4.0f);
            assertThat(port.masterVolume()).isEqualTo(AudioVolume.SILENT);

            port.setMasterVolume(Float.NaN);
            assertThat(port.masterVolume()).isEqualTo(AudioVolume.SILENT);

            port.setMasterVolume(0.25f);
            assertThat(port.masterVolume()).isEqualTo(0.25f);
        }

        @Test
        @DisplayName("survives being set out of lifecycle")
        void shouldAcceptVolumeOutsideTheLifecycle()
        {
            // A UI slider does not know or care whether the subsystem is up.
            final I_AudioPort port = new NullAudioPort();
            port.setMasterVolume(0.5f);
            port.init();
            assertThat(port.masterVolume()).isEqualTo(0.5f);

            port.shutdown();
            port.setMasterVolume(0.75f);
            assertThat(port.masterVolume()).isEqualTo(0.75f);
        }
    }

    @Nested
    @DisplayName("what it records")
    final class Recording
    {
        @Test
        @DisplayName("counts one play per sound asked for")
        void shouldCountPlays()
        {
            final NullAudioPort port = new NullAudioPort();
            port.init();

            assertThat(port.playCount()).isZero();
            port.play(SoundId.WEAPON_FIRE);
            port.play(SoundId.WEAPON_FIRE);
            port.play(SoundId.WEAPON_FIRE);

            assertThat(port.playCount()).isEqualTo(3L);
            assertThat(port.lastSound()).isEqualTo(SoundId.WEAPON_FIRE);
        }

        @Test
        @DisplayName("keeps the count across shutdown")
        void shouldSurviveTeardown()
        {
            // A test that asserts what a whole session played needs the counter
            // to outlive the session's own teardown.
            final NullAudioPort port = new NullAudioPort();
            port.init();
            port.play(SoundId.WEAPON_FIRE);
            port.shutdown();

            assertThat(port.playCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("counts correctly when several threads fire at once")
        void shouldCountUnderConcurrency() throws InterruptedException
        {
            // I_AudioPort#play is documented as callable from any thread, and
            // the engine really does call it off the game loop thread while a
            // launcher may be attaching from the platform's main thread. A
            // non-atomic counter would lose increments here, and a lost
            // increment is a flaky cadence test rather than an obvious bug.
            final NullAudioPort port = new NullAudioPort();
            port.init();

            final int threads = 4;
            final int playsEach = 500;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            for (int index = 0; index < threads; index++)
            {
                final Thread worker = new Thread(() ->
                {
                    try
                    {
                        start.await();
                        for (int play = 0; play < playsEach; play++)
                        {
                            port.play(SoundId.WEAPON_FIRE);
                        }
                    }
                    catch (final InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    finally
                    {
                        done.countDown();
                    }
                }, "null-audio-test-" + index);
                worker.start();
            }
            start.countDown();

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(port.playCount()).isEqualTo((long) threads * playsEach);
        }
    }
}
