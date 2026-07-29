/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.openfps.engine.audio.adapter.NullAudioPort;
import com.openfps.engine.audio.port.SoundId;
import com.openfps.engine.core.FrameRate;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.BotPattern;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.InputState;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that the weapon makes a noise, and makes it at the right moments.
 *
 * <p>The point of this file is that it needs no sound card and no assets. The
 * engine calls an {@code I_AudioPort} and nothing else — never a libGDX type —
 * so the whole trigger-to-sound path is assertable in a plain JVM by counting
 * calls on the null adapter. What is <b>not</b> covered here is whether a
 * speaker actually moves; that is {@code GdxAudioPort}'s job and no test can
 * check it on CI.</p>
 *
 * <p>Separate from {@code DemoGameplayPortTest} rather than folded into it: that
 * file builds a whole {@code DemoScene} from staged model fixtures because its
 * subject is the bot-to-model handoff, and none of that is needed to assert when
 * a sound is played. A {@code Match} with one bot and a renderer with no scene
 * is enough, and it runs in milliseconds.</p>
 */
@DisplayName("DemoGameplayPort — the weapon's sound")
final class DemoGameplayPortAudioTest
{
    /**
     * Entity id for the single target.
     *
     * <p>{@code FIRST_BOT_ENTITY_ID} rather than a literal: id 1 is reserved for
     * the player, and {@code Match} rejects a roster that claims it.</p>
     */
    private static final int TARGET_ID = Match.FIRST_BOT_ENTITY_ID;

    /** A configuration at the default rate. */
    private static GameConfig config()
    {
        return GameConfig.unbounded(FrameRate.FPS_60);
    }

    /** A render port with no worker pool and no scene — nothing here draws. */
    private static SoftwareRenderPort renderer()
    {
        final I_TimePort time = new NullTimePort();
        time.init();
        final SoftwareRenderPort port = new SoftwareRenderPort(null, time);
        port.init();
        return port;
    }

    /**
     * How far above the player's eye the lone target stands, in world units.
     *
     * <p><b>Unreachable on purpose, and the tests here depend on it.</b> A
     * {@code Match} ends the moment its last bot dies, and a bot in front of the
     * player dies in three shots — so a target the player could hit would end
     * the round a quarter of the way through the cadence test, and the weapon
     * would stop firing for a reason that has nothing to do with cadence. That
     * is exactly how the first draft of this file failed: five sounds expected,
     * three heard, and the arithmetic under test was never the problem.</p>
     *
     * <p>Straight up rather than far away, because distance alone is not enough
     * — a hitscan ray has no range limit, so a bot on the ray is hit at any
     * distance. The default player pitch is level, so nothing directly overhead
     * can ever be on it.</p>
     */
    private static final float OUT_OF_REACH_HEIGHT = 2000.0f;

    /** One stationary opponent, standing where the player cannot shoot it. */
    private static Match match()
    {
        return new Match(new Bot[] {new Bot(TARGET_ID, 0.0f, OUT_OF_REACH_HEIGHT, 0.0f,
            BotPattern.SENTRY, 0.0f, 120, 0)});
    }

    /** An input port that replays one snapshot forever. */
    private static I_InputPort input(final boolean triggerDown)
    {
        final InputState state =
            InputState.of(0.0f, 0.0f, 0.0f, 0.0f, triggerDown, false, false);
        return new I_InputPort()
        {
            @Override
            public void init()
            {
                // nothing to open
            }

            @Override
            public void shutdown()
            {
                // nothing to close
            }

            @Override
            public void sampleInput(final int ticIndex)
            {
                // the snapshot never changes
            }

            @Override
            public InputState currentInput()
            {
                return state;
            }

            @Override
            public boolean isShutdownRequested()
            {
                return false;
            }
        };
    }

    /** A port over the fixtures above, with the match already live. */
    private static DemoGameplayPort livePort(final boolean triggerDown, final Match round)
    {
        final DemoGameplayPort port = new DemoGameplayPort(input(triggerDown), renderer(),
            new PlayerController(), config(), round, new int[] {0});
        port.setMatchLive(true);
        return port;
    }

    @Nested
    @DisplayName("attaching")
    final class Attaching
    {
        @Test
        @DisplayName("a fresh port is silent but never null")
        void shouldDefaultToSilence()
        {
            // A NullAudioPort default rather than a null field, so the trigger
            // has one code path. This class has already had one bug that made
            // the weapon useless for a whole run, and it lived on exactly that
            // branch.
            final DemoGameplayPort port = livePort(false, match());

            assertThat(port.audio()).isNotNull();
            assertThat(port.audio().isAudible()).isFalse();
        }

        @Test
        @DisplayName("attaching null restores silence rather than throwing")
        void shouldTreatNullAsSilence()
        {
            // "This build has no audio" is a configuration, not a mistake.
            final DemoGameplayPort port = livePort(true, match());

            assertThatCode(() -> port.attachAudio(null)).doesNotThrowAnyException();
            assertThat(port.audio()).isNotNull();
            assertThatCode(() -> port.tick(0)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the attached port is the one that gets played")
        void shouldPlayThroughTheAttachedPort()
        {
            final NullAudioPort audio = new NullAudioPort();
            final DemoGameplayPort port = livePort(true, match());
            port.attachAudio(audio);

            port.tick(0);

            assertThat(audio.playCount()).isEqualTo(1L);
            assertThat(audio.lastSound()).isEqualTo(SoundId.WEAPON_FIRE);
        }
    }

    @Nested
    @DisplayName("when it fires")
    final class WhenItFires
    {
        @Test
        @DisplayName("the very first pull of the trigger makes a noise")
        void shouldPlayOnTheFirstShot()
        {
            // The audio half of the lastFireTic regression. If the cooldown
            // arithmetic ever breaks again the weapon goes silent as well as
            // harmless, and this asserts the silence directly rather than
            // inferring it from a shot count.
            final NullAudioPort audio = new NullAudioPort();
            final Match round = match();
            final DemoGameplayPort port = livePort(true, round);
            port.attachAudio(audio);

            port.tick(0);

            assertThat(round.playerShotsFired()).isEqualTo(1);
            assertThat(audio.playCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("one sound per SHOT, not one per tic")
        void shouldFollowTheWeaponsCadence()
        {
            // The reason the play call sits AFTER the cooldown test. Before it,
            // a held trigger would make sixty sounds a second at FPS_60 and a
            // hundred and twenty at FPS_120 — a blaster whose pitch depends on
            // --fps, which is the exact coupling FIRE_INTERVAL_TICS exists to
            // break. It would also sound like a buzz rather than like a weapon.
            final NullAudioPort audio = new NullAudioPort();
            final Match round = match();
            final DemoGameplayPort port = livePort(true, round);
            port.attachAudio(audio);

            final int tics = 60;
            for (int tic = 0; tic < tics; tic++)
            {
                port.tick(tic);
            }

            final long expected = 1L + (tics - 1) / DemoGameplayPort.FIRE_INTERVAL_TICS;
            assertThat(audio.playCount()).isEqualTo(expected);
            assertThat(audio.playCount()).isEqualTo((long) round.playerShotsFired());
        }

        @Test
        @DisplayName("a miss sounds exactly like a hit")
        void shouldSoundTheSameOnAMiss()
        {
            // The player is aimed at nothing here — the single bot sits far
            // overhead. The sound still plays, and it must: a noise that only
            // happened when you connected would tell the player the outcome
            // before the game does. Same rule as the tracer.
            final NullAudioPort audio = new NullAudioPort();
            final Match round = match();
            final DemoGameplayPort port = livePort(true, round);
            port.attachAudio(audio);

            port.tick(0);

            assertThat(round.playerShotsHit()).as("the fixture was meant to miss").isZero();
            assertThat(audio.playCount())
                .as("the shot was silent because it missed")
                .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("when it stays quiet")
    final class WhenItStaysQuiet
    {
        @Test
        @DisplayName("an untouched trigger makes no noise")
        void shouldBeSilentWithoutATrigger()
        {
            final NullAudioPort audio = new NullAudioPort();
            final DemoGameplayPort port = livePort(false, match());
            port.attachAudio(audio);

            for (int tic = 0; tic < 60; tic++)
            {
                port.tick(tic);
            }

            assertThat(audio.playCount()).isZero();
        }

        @Test
        @DisplayName("a match frozen behind a menu is silent")
        void shouldBeSilentBehindTheMenu()
        {
            // The gate that stopped bots shooting a player who was still reading
            // the title screen. The weapon is gated by the same flag, so a
            // player mashing the mouse on the menu must not hear their blaster
            // through it.
            final NullAudioPort audio = new NullAudioPort();
            final DemoGameplayPort port = new DemoGameplayPort(input(true), renderer(),
                new PlayerController(), config(), match(), new int[] {0});
            port.attachAudio(audio);

            assertThat(port.isMatchLive()).isFalse();
            for (int tic = 0; tic < 60; tic++)
            {
                port.tick(tic);
            }

            assertThat(audio.playCount()).isZero();

            // And it comes back when the world does.
            port.setMatchLive(true);
            port.tick(60);
            assertThat(audio.playCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a camera-only demo has no weapon to fire")
        void shouldBeSilentWithoutAMatch()
        {
            // The --model= path: one converted model on an orbit camera, no
            // match, no opponents. Firing into it must be silent rather than
            // throwing on the null match.
            final NullAudioPort audio = new NullAudioPort();
            final DemoGameplayPort port = new DemoGameplayPort(input(true), renderer(),
                new PlayerController(), config());
            port.attachAudio(audio);
            port.setMatchLive(true);

            for (int tic = 0; tic < 60; tic++)
            {
                port.tick(tic);
            }

            assertThat(audio.playCount()).isZero();
        }
    }
}
