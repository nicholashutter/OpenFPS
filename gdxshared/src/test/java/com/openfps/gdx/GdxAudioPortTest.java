/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.nio.file.Path;

import com.badlogic.gdx.Gdx;

import com.openfps.engine.audio.AudioVolume;
import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.audio.port.SoundId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the libGDX audio adapter — specifically, for what it does when
 * there is no libGDX.
 *
 * <p>That is the whole scope of what is testable here, and it is the half that
 * matters most. In a plain JUnit JVM {@code Gdx.audio} is null, exactly as it is
 * on a real desktop run before {@code runFrameLoop} constructs the application,
 * and exactly as it is on a build machine with no sound card. <b>Every one of
 * these tests asserts that nothing throws and the game keeps running.</b></p>
 *
 * <p>Whether a speaker actually moves is not assertable without a device and is
 * not attempted. The audible path is covered by the arithmetic tests in
 * {@code :engine} — which check the samples and the container the adapter hands
 * over — plus running the game.</p>
 */
@DisplayName("GdxAudioPort")
final class GdxAudioPortTest
{
    /** The static libGDX audio hook these tests rely on being absent. */
    private static boolean headless()
    {
        return Gdx.audio == null;
    }

    @Nested
    @DisplayName("with no libGDX application")
    final class Headless
    {
        @Test
        @DisplayName("the test JVM really has no audio, or these tests prove nothing")
        void shouldConfirmThePremise()
        {
            // Pins the assumption every other test here rests on. If a future
            // change starts an application in this source set, this fails first
            // and says why, rather than the rest quietly asserting nothing.
            assertThat(headless())
                .as("Gdx.audio is set, so the 'no device' path is no longer under test")
                .isTrue();
        }

        @Test
        @DisplayName("survives a full lifecycle without a device")
        void shouldSurviveTheLifecycle(@TempDir final Path cache)
        {
            final I_AudioPort port = new GdxAudioPort(cache.toFile());

            assertThatCode(() ->
            {
                port.init();

                port.play(SoundId.WEAPON_FIRE);

                port.stopAll();

                port.shutdown();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("reports itself inaudible instead of pretending")
        void shouldReportInaudible(@TempDir final Path cache)
        {
            // The distinction this method exists for: "audio works" versus
            // "audio is silently doing nothing". A launcher logs one line off
            // this, and without it a muted mixer and a broken backend look
            // identical.
            final I_AudioPort port = new GdxAudioPort(cache.toFile());

            port.init();

            port.play(SoundId.WEAPON_FIRE);

            assertThat(port.isAudible()).isFalse();

            port.shutdown();
        }

        @Test
        @DisplayName("keeps trying rather than latching itself off")
        void shouldNotLatchOffPermanently(@TempDir final Path cache)
        {
            // "Gdx.audio is null" means the frame loop has not started, which is
            // temporary — on desktop it is null for the whole of the engine
            // bootstrap. Treating the first failure as final would mute the game
            // for its entire run, which is the same permanent-silent-failure bug
            // the lazy load exists to avoid. Repeated plays must stay harmless.
            final I_AudioPort port = new GdxAudioPort(cache.toFile());

            port.init();

            assertThatCode(() ->
            {
                for (int shot = 0; shot < 100; shot++)
                {
                    port.play(SoundId.WEAPON_FIRE);
                }
            }).doesNotThrowAnyException();

            port.shutdown();
        }

        @Test
        @DisplayName("plays before init and after shutdown without complaint")
        void shouldToleratePlayOutsideTheLifecycle(@TempDir final Path cache)
        {
            // The game loop publishes tics before the subsystems are up and
            // after they have gone down, and on Android onDestroy can arrive
            // with the loop still running.
            final I_AudioPort port = new GdxAudioPort(cache.toFile());

            assertThatCode(() ->
            {
                port.play(SoundId.WEAPON_FIRE);

                port.init();

                port.shutdown();

                port.play(SoundId.WEAPON_FIRE);

                port.shutdown();
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("preload")
    final class Preload
    {
        @Test
        @DisplayName("is harmless with no device, and does not latch the port off")
        void shouldSurvivePreloadWithoutADevice(@TempDir final Path cache)
        {
            // The Android launcher calls this as soon as there is a surface —
            // while the menu is still up, and deliberately NOT on entering a
            // match, because that edge is also what unfreezes the bots and the
            // first bot shot then beat the asynchronous SoundPool.load to the
            // speaker ("not READY"). It is still called on the match edge too, as
            // an idempotent backstop, so it has to be as survivable as play() is
            // and must leave the port willing to try again.
            final I_AudioPort port = new GdxAudioPort(cache.toFile());

            port.init();

            assertThatCode(() ->
            {
                port.preload();

                port.preload();

                port.play(SoundId.WEAPON_FIRE);
            }).doesNotThrowAnyException();

            assertThat(port.isAudible()).isFalse();

            port.shutdown();
        }

        @Test
        @DisplayName("outside the lifecycle it does nothing rather than opening a device")
        void shouldIgnorePreloadOutsideTheLifecycle(@TempDir final Path cache)
        {
            // shutdown() has already released whatever was loaded. A preload
            // after it must not quietly reopen the port behind the subsystem's
            // back — the same rule play() follows.
            final I_AudioPort port = new GdxAudioPort(cache.toFile());

            assertThatCode(() ->
            {
                port.preload();

                port.init();

                port.shutdown();

                port.preload();
            }).doesNotThrowAnyException();

            assertThat(port.isAudible()).isFalse();
        }

        @Test
        @DisplayName("a null cache directory costs the warm-up, not the run")
        void shouldSurvivePreloadWithNoCacheDirectory()
        {
            final I_AudioPort port = new GdxAudioPort(null);

            port.init();

            assertThatCode(port::preload).doesNotThrowAnyException();

            port.shutdown();
        }
    }

    @Nested
    @DisplayName("with a broken cache directory")
    final class BrokenCache
    {
        @Test
        @DisplayName("a null directory costs the sound, not the run")
        void shouldSurviveANullCacheDirectory()
        {
            // Reachable on a locked-down system where java.io.tmpdir is unset.
            final I_AudioPort port = new GdxAudioPort(null);

            assertThatCode(() ->
            {
                port.init();

                port.play(SoundId.WEAPON_FIRE);

                port.shutdown();
            }).doesNotThrowAnyException();

            assertThat(port.isAudible()).isFalse();
        }

        @Test
        @DisplayName("a directory that cannot exist costs the sound, not the run")
        void shouldSurviveAnUnusableCacheDirectory(@TempDir final Path cache)
        {
            // A file where the directory should be: mkdirs fails, the stream
            // cannot open, and the port has to swallow it.
            final File notADirectory = cache.resolve("occupied").toFile();

            final I_AudioPort port = new GdxAudioPort(new File(notADirectory, "sub"));

            assertThatCode(() ->
            {
                port.init();

                port.play(SoundId.WEAPON_FIRE);

                port.shutdown();
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("volume")
    final class Volume
    {
        @Test
        @DisplayName("clamps through the same arithmetic as every other port")
        void shouldClampVolume(@TempDir final Path cache)
        {
            // The clamp lives in AudioVolume precisely so this and NullAudioPort
            // cannot disagree. A backend with its own copy is how "the slider
            // works on desktop and mutes the game on my phone" happens.
            final I_AudioPort port = new GdxAudioPort(cache.toFile());

            assertThat(port.masterVolume()).isEqualTo(AudioVolume.FULL);

            port.setMasterVolume(2.0f);

            assertThat(port.masterVolume()).isEqualTo(AudioVolume.FULL);

            port.setMasterVolume(-1.0f);

            assertThat(port.masterVolume()).isEqualTo(AudioVolume.SILENT);

            port.setMasterVolume(Float.NaN);

            assertThat(port.masterVolume()).isEqualTo(AudioVolume.SILENT);

            port.setMasterVolume(0.3f);

            assertThat(port.masterVolume()).isEqualTo(0.3f);
        }
    }
}
