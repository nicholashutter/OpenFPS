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

    /**
     * Distance to the nearest bot on the reward fixture's firing range — 3000, well
     * past {@code Match.BOT_RANGE_UNITS}.
     *
     * <p>The reward takes three kills and hundreds of tics to reach, and a room
     * that could shoot back would let the player die in the middle of that — which
     * cancels the buff, and would make every assertion below conditional on the
     * dice. The player's own hitscan has no range limit, so the queue is still
     * shootable from here.</p>
     */
    private static final float OUT_OF_REACH_DISTANCE = 3000.0f;

    /** A queue of bots straight down +z, none of which can shoot back. */
    private static Match firingRange(final int count)
    {
        final Bot[] roster = new Bot[count];

        for (int index = 0; index < count; index++)
        {
            roster[index] = new Bot(Match.FIRST_BOT_ENTITY_ID + index, 0.0f, 0.0f,
                OUT_OF_REACH_DISTANCE + index * 60.0f, BotPattern.SENTRY, 0.0f, 120, 0);
        }

        return new Match(roster);
    }

    /** Scene indices for a roster of any size; nothing here draws. */
    private static int[] instancesFor(final Match round)
    {
        return new int[round.botCount()];
    }

    /**
     * An input port that holds the trigger for a while and then lets go.
     *
     * <p>Needed because the expiry sound has to be observed <b>on the tic it
     * happens</b>, and {@code NullAudioPort} remembers only the last sound played.
     * A trigger still held on that tic fires in the same call, after the match has
     * ticked, and overwrites the answer.</p>
     *
     * @param tics how many tics the trigger is held for
     * @return a port that goes neutral afterwards
     */
    private static I_InputPort inputHeldFor(final int tics)
    {
        final InputState down = InputState.of(0.0f, 0.0f, 0.0f, 0.0f, true, false, false);

        return new I_InputPort()
        {
            /** MUTABLE: swapped for neutral once the hold is over. */
            private InputState current = down;

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
                if (ticIndex >= tics)
                {
                    this.current = InputState.NEUTRAL;
                }
            }

            @Override
            public InputState currentInput()
            {
                return current;
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
            new PlayerController(), config(), round, instancesFor(round));

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
    @DisplayName("the kill streak, by ear")
    final class SuperBlaster
    {
        @Test
        @DisplayName("an ordinary shot is the ordinary blaster")
        void shouldFireTheOrdinaryBlasterFirst()
        {
            final NullAudioPort audio = new NullAudioPort();

            final DemoGameplayPort port = livePort(true, firingRange(6));

            port.attachAudio(audio);

            port.tick(0);

            assertThat(audio.lastSound()).isEqualTo(SoundId.WEAPON_FIRE);
        }

        @Test
        @DisplayName("the third kill announces itself, on the tic it happens")
        void shouldPlayTheAwardChime()
        {
            // The killing shot plays the ordinary weapon and THEN the chime, in that
            // order, because the award is a consequence of the shot rather than a
            // property of it. So the last sound on the arming tic is the chime — and
            // if it is the weapon instead, the award was announced after a return.
            final NullAudioPort audio = new NullAudioPort();

            final Match round = firingRange(6);

            final DemoGameplayPort port = livePort(true, round);

            port.attachAudio(audio);

            final int armedAt = runUntilArmed(port, round);

            assertThat(armedAt).as("the reward was never earned at all").isPositive();

            assertThat(round.botsKilled()).isEqualTo(Match.SUPER_BLASTER_KILL_STREAK);

            assertThat(audio.lastSound()).isEqualTo(SoundId.SUPER_BLASTER_READY);
        }

        @Test
        @DisplayName("the weapon itself sounds different while the reward is live")
        void shouldFireTheSuperBlasterWhileLive()
        {
            // The reward would otherwise be inaudible during exactly the activity it
            // modifies: the player hears their own trigger five times a second, so a
            // buff that changed the damage and not the noise could only be seen.
            final NullAudioPort audio = new NullAudioPort();

            final Match round = firingRange(6);

            final DemoGameplayPort port = livePort(true, round);

            port.attachAudio(audio);

            final int armedAt = runUntilArmed(port, round);

            for (int tic = armedAt + 1; tic <= armedAt + DemoGameplayPort.FIRE_INTERVAL_TICS;
                tic++)
            {
                port.tick(tic);
            }

            assertThat(round.isSuperBlaster()).isTrue();

            assertThat(audio.lastSound()).isEqualTo(SoundId.SUPER_WEAPON_FIRE);
        }

        @Test
        @DisplayName("running out announces itself too, so the gun does not change silently")
        void shouldPlayTheExpiryChime()
        {
            // A reward that ended silently would leave the player firing an ordinary
            // weapon at a target they picked because they thought they had a better
            // one. The trigger is released before the window closes so that the
            // expiry is the last thing played rather than the next shot.
            final NullAudioPort audio = new NullAudioPort();

            final Match round = firingRange(6);

            final DemoGameplayPort port = new DemoGameplayPort(inputHeldFor(150), renderer(),
                new PlayerController(), config(), round, instancesFor(round));

            port.setMatchLive(true);

            port.attachAudio(audio);

            // 150 tics of held trigger is thirteen shots at the twelve-tic cadence,
            // which is four kills' worth — comfortably past the three the reward
            // costs. Then the window is run out with nobody firing.
            for (int tic = 0; tic < 150 + Match.SUPER_BLASTER_TICS + 2; tic++)
            {
                port.tick(tic);
            }

            assertThat(round.isSuperBlaster()).isFalse();

            assertThat(audio.lastSound()).isEqualTo(SoundId.SUPER_BLASTER_SPENT);
        }

        // Ticks the port until the reward is armed, and returns the tic it happened
        // on. Returns -1 if it never did, so the assertion says so rather than
        // passing on a fixture that never got there.
        private static int runUntilArmed(final DemoGameplayPort port, final Match round)
        {
            for (int tic = 0; tic < 600; tic++)
            {
                port.tick(tic);

                if (round.isSuperBlaster())
                {
                    return tic;
                }
            }

            return -1;
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
