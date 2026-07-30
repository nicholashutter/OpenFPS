/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.audio.synth.CarbineSound;
import com.openfps.engine.gameplay.BotSkill;

/**
 * How many of the room's shots are allowed to make a noise.
 *
 * <h2>Why a cap is required rather than prudent</h2>
 *
 * <p>The player's weapon needs no gate because it already has one:
 * {@code DemoGameplayPort.FIRE_INTERVAL_TICS} bounds it at five shots a second,
 * and the {@code play} call deliberately sits <b>after</b> the cooldown for
 * exactly this reason — before it, a held trigger would make sixty sounds a second
 * and the blaster would be a buzz whose pitch depended on {@code --fps}.</p>
 *
 * <p>Return fire has no such bound. Seven bots roll for the trigger independently
 * on every tic their weapon is ready, so two things happen that a single shooter
 * cannot do:</p>
 *
 * <ul>
 *   <li><b>Several fire on the same tic.</b> Not often, but it is a coincidence
 *       with seven chances a tic, and seven copies of one 120 ms buffer started on
 *       the same millisecond are not seven shots. They are <i>the same waveform
 *       added to itself</i> — identical samples, perfectly in phase, so the mix is
 *       seven times the amplitude of one and clips hard. It does not sound like a
 *       volley; it sounds like a fault.</li>
 *   <li><b>The long-run rate is unbounded by anything audible.</b> Measured, the
 *       room fires once every 18 tics between them — three and a third a second,
 *       which is fine. But that is a <i>mean</i>: a stretch where several bots come
 *       off cooldown together is several times that, and a stream of cracks closer
 *       together than the ear resolves stops being gunfire and becomes texture,
 *       which is the opposite of a warning.</li>
 * </ul>
 *
 * <h2>Why it lives here and not behind {@code I_AudioPort}</h2>
 *
 * <p>Because it is a rule about <b>this game's opponents</b>, not about sound. The
 * port's contract is that overlapping plays overlap in the mix, and it says so
 * deliberately: "firing again before the last shot has decayed must not cut it
 * off. That is what a weapon sounds like." A mixer that silently dropped plays
 * would be a mixer nobody could reason about, and both shipped backends do overlap
 * by default. So the decision about how much of a room's fire is worth hearing
 * belongs to the thing that knows how many opponents there are.</p>
 *
 * <h2>Determinism</h2>
 *
 * <p>The gate is a pure function of the tic index and the last tic it allowed.
 * <b>No wall clock is consulted anywhere</b> — not {@code System.nanoTime()}, not
 * {@code currentTimeMillis()} — for the reason {@code BotRng} gives at length: a
 * gate that opened on different tics on two peers would be a divergence, and it
 * would be one in the audio layer where nobody would look for it. Nothing in the
 * simulation reads this back, so a peer that gated differently would not desync;
 * the point is that it will not gate differently, and that is cheaper to guarantee
 * than to reason about.</p>
 *
 * <p>Mutable and not thread-safe, like the port that owns it. It lives inside
 * {@code DemoGameplayPort}'s tic lock.</p>
 */
public final class BotFireVoices
{
    /**
     * Bot shots allowed to make a noise on any one tic — <b>1</b>.
     *
     * <p>One, and the second is not a smaller contribution — it is a doubling of
     * the first. Two bots firing on the same tic play the same generated buffer
     * from the same offset, so their samples are identical and in phase and the sum
     * is exactly {@code 2x} the amplitude, not the {@code 1.4x} that uncorrelated
     * sources would give. At seven that is {@code 7x}, which clips a signal already
     * peaking at {@link CarbineSound#PEAK}.</p>
     *
     * <p>Nothing audible is lost by dropping the others. Two identical waveforms
     * summed in phase are indistinguishable from one played louder — there is no
     * second shot in there to hear. What a player would actually notice is the
     * clipping.</p>
     */
    public static final int MAX_VOICES_PER_TIC = 1;

    /**
     * Fewest tics between two bot-fire sounds — <b>6</b>, so ten a second at most.
     *
     * <h2>Derived from the sound's own length</h2>
     *
     * <p>{@link CarbineSound#DURATION_MS} is 120 ms, which at 60 Hz is <b>7.2
     * tics</b>. A minimum interval of 6 therefore admits at most
     * {@code ceil(7.2 / 6) = 2} overlapping voices, ever — which is the number the
     * whole thing is solved for. Two is what makes "more than one of them is
     * shooting at me" audible as a texture rather than as a single shot; and two
     * copies at {@link CarbineSound#PEAK} of 0.45 reach 0.90, which is inside full
     * scale, so <b>the one overlap this permits cannot clip</b>. An interval of 3
     * would allow three (1.35, clipped); an interval of 8 would allow none, and the
     * room would sound like one opponent however many were firing.</p>
     *
     * <h2>Checked against what the room actually does</h2>
     *
     * <p>{@link BotSkill#DUMB} produces a shot every <b>18</b> tics across the
     * seven of them, so in the ordinary case this gate never closes: 6 is a third
     * of the mean interval. It exists entirely for the bursts. That is the right
     * shape for a limiter — one that shaped the average would be changing the
     * demo's balance from the audio layer.</p>
     *
     * <p>Ten a second is also twice the player's own five, deliberately: the room
     * is allowed to sound busier than the player, because it is.</p>
     */
    public static final int MIN_INTERVAL_TICS = 6;

    /** Sentinel meaning no bot-fire sound has been played yet. */
    public static final int NEVER = Integer.MIN_VALUE;

    /**
     * Tic of the last sound allowed through, or {@link #NEVER}. MUTABLE.
     *
     * <p>{@link #NEVER} rather than zero, and it matters for the same reason
     * {@code DemoGameplayPort.lastFireTic} does not start at
     * {@code Long.MIN_VALUE}: the test is a subtraction, and this one is safe only
     * because it is written to be. See {@link #allow}.</p>
     */
    private int lastPlayedTic = NEVER;

    /** The tic {@link #voicesThisTic} counts for. MUTABLE. */
    private int currentTic = NEVER;

    /** Sounds already allowed on {@link #currentTic}. MUTABLE. */
    private int voicesThisTic;

    /** Sounds allowed since construction or the last reset. MUTABLE: for tests and logs. */
    private int allowed;

    /** Sounds refused since construction or the last reset. MUTABLE. */
    private int suppressed;

    /**
     * Asks whether one bot's shot on a given tic may make a noise.
     *
     * <p>Call once per shot, and act on the answer: this is a <b>consuming</b>
     * question, because a caller that asked twice and played once would have spent
     * a voice it did not use.</p>
     *
     * <p><b>The interval test is written as {@code ticIndex - last} and is safe
     * only because {@link #NEVER} is handled first.</b> With {@link Integer#MIN_VALUE}
     * left in the subtraction it overflows on the very first shot, wraps to a large
     * negative number, and reads as "not ready yet" — and since the field is only
     * assigned after the test passes, it never passes and nothing ever makes a
     * sound. That is not a hypothetical: it is the bug
     * {@code DemoGameplayPort.lastFireTic} records, which silenced the player's own
     * weapon for entire runs on every platform.</p>
     *
     * @param ticIndex the tic the shot happened on; a tic index may be negative and
     *     may repeat, and neither breaks this
     * @return true if this shot should be played
     */
    public boolean allow(final int ticIndex)
    {
        if (ticIndex != currentTic)
        {
            this.currentTic = ticIndex;
            this.voicesThisTic = 0;
        }
        if (voicesThisTic >= MAX_VOICES_PER_TIC)
        {
            this.suppressed = suppressed + 1;
            return false;
        }
        if (lastPlayedTic != NEVER && ticIndex - lastPlayedTic < MIN_INTERVAL_TICS)
        {
            this.suppressed = suppressed + 1;
            return false;
        }
        this.voicesThisTic = voicesThisTic + 1;
        this.lastPlayedTic = ticIndex;
        this.allowed = allowed + 1;
        return true;
    }

    /**
     * Forgets everything, so a fresh round starts with a clear gate.
     *
     * <p>Called from {@code DemoGameplayPort.restartMatch}, beside
     * {@code DemoEffects.clear()} and for the same reason: a rematch that inherited
     * the last round's final shot would refuse the first few tics of the new one,
     * and "the first bot to shoot at me was silent" is a bug nobody would ever
     * trace to a rematch.</p>
     */
    public void clear()
    {
        this.lastPlayedTic = NEVER;
        this.currentTic = NEVER;
        this.voicesThisTic = 0;
        this.allowed = 0;
        this.suppressed = 0;
    }

    /**
     * Returns how many concurrent voices this gate can produce, worst case.
     *
     * <p>Derived from {@link #MIN_INTERVAL_TICS} and the sound's own length rather
     * than asserted, so a change to either is caught by the test that reads this
     * instead of by somebody listening. See {@link #MIN_INTERVAL_TICS} for the
     * arithmetic and why the answer has to be 2.</p>
     *
     * @param ticsPerSecond the simulation rate; a non-positive value returns 1,
     *     since a rate that cannot elapse cannot overlap anything
     * @return the largest number of bot-fire voices that can sound at once
     */
    public static int maxConcurrentVoices(final int ticsPerSecond)
    {
        if (ticsPerSecond <= 0)
        {
            return 1;
        }
        final int soundTics =
            (CarbineSound.DURATION_MS * ticsPerSecond + 999) / 1000;
        return Math.max(1, (soundTics + MIN_INTERVAL_TICS - 1) / MIN_INTERVAL_TICS);
    }

    /** Returns the tic of the last sound allowed through, or {@link #NEVER}. */
    public int lastPlayedTic()
    {
        return lastPlayedTic;
    }

    /** Returns how many bot-fire sounds have been allowed. */
    public int allowedCount()
    {
        return allowed;
    }

    /**
     * Returns how many have been refused.
     *
     * <p>Worth counting rather than discarding silently: "the cap is not costing
     * anything" is a claim, and without the denominator there is nothing to check
     * it against — the same reason {@code Match.botShotsFired} exists beside
     * {@code botShotsLanded}.</p>
     *
     * @return the suppressed count
     */
    public int suppressedCount()
    {
        return suppressed;
    }

    /** Returns a debug rendering of the gate's state. */
    @Override
    public String toString()
    {
        return "BotFireVoices{allowed=" + allowed + ", suppressed=" + suppressed
            + ", last=" + lastPlayedTic + ", minInterval=" + MIN_INTERVAL_TICS + " tics}";
    }
}
