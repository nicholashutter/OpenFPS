/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * The only source of randomness the simulation is allowed to have: a seeded,
 * <b>stateless</b>, counter-based PRNG addressed by (tic, entity, channel).
 *
 * <h2>READ THIS BEFORE REACHING FOR {@code Math.random()}</h2>
 *
 * <p>{@code engine/net/README.md} commits this engine to <b>deterministic
 * lockstep</b>: peers exchange inputs and nothing else, every peer simulates
 * every tic, and two peers at the same tic must hold identical state. A single
 * random number drawn from anything the peers do not share desyncs them
 * instantly and silently — a bot fires on one machine and not on the other, the
 * player's health diverges, and by the time anyone notices, the two simulations
 * have been telling different stories for minutes.</p>
 *
 * <p><b>Therefore, inside a tic, all of the following are forbidden:</b></p>
 *
 * <ul>
 *   <li>{@code Math.random()} — seeded from the system clock.</li>
 *   <li>{@code new Random()} — the same, via {@code System.nanoTime()}.</li>
 *   <li>{@code System.nanoTime()} and {@code currentTimeMillis()} in any
 *       arithmetic that reaches simulation state.</li>
 *   <li>{@code ThreadLocalRandom}, or anything else whose value depends on
 *       which thread got there first.</li>
 *   <li>{@code java.util.Random} <b>even seeded</b>, if it is advanced by
 *       drawing from it — see the next section for why that is not enough.</li>
 * </ul>
 *
 * <h2>Why stateless, and not a seeded generator held in a field</h2>
 *
 * <p>A seeded generator is reproducible only if it is drawn from in exactly the
 * same order, the same number of times, on every peer. That is a far stronger
 * obligation than it looks: it means a bot that is out of range and skips its
 * accuracy roll must still consume the same numbers as one that does not, and
 * it means adding a single {@code if} anywhere in the firing path shifts every
 * later draw by one and desyncs a build against itself.</p>
 *
 * <p>This generator has no cursor to get out of step. Every value is a pure
 * function of {@code (seed, ticIndex, entityId, channel)}, so a caller may skip
 * a roll, take the same roll twice, or evaluate rolls in any order, and every
 * peer still computes the identical number for the identical question. That is
 * what makes {@link Match}'s early-outs safe to add and to remove.</p>
 *
 * <h2>Why the arithmetic is bit-exact</h2>
 *
 * <p>Everything here is 64-bit integer multiply, xor and unsigned shift. Those
 * are exact in the JLS on every conforming JVM — no rounding, no platform
 * intrinsic with an error budget, no dependence on the optimiser. The mixing
 * function is SplitMix64's finaliser, chosen because it is short enough to
 * read, holds no state, and avalanches well: one flipped input bit changes
 * about half the output bits.</p>
 *
 * <p>{@link #unitFloat} converts to {@code float} by taking 24 bits and scaling
 * by a power of two, which is exact in IEEE 754 and therefore reproducible too.
 * Dividing by a non-power-of-two would still be correctly rounded under JEP 306,
 * but the power of two makes it obviously so.</p>
 *
 * <p><b>No {@code java.lang.Math} appears in this class</b>, deliberately, for
 * the reason {@link Bot} records: the guard that keeps {@code Math.sin} out of
 * the simulation is a flat "no {@code java/lang/Math} in the constant pool"
 * check, and it works precisely because it has no exceptions. {@code BotRngTest}
 * carries the same guard.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Immutable and therefore safe to share. It holds nothing but the seed.</p>
 */
public final class BotRng
{
    /**
     * The seed a match uses when nobody names one — an arbitrary constant.
     *
     * <p>Arbitrary but <b>fixed</b>, which is the whole point: every peer in a
     * local match uses this, so every peer's bots make the same decisions. A
     * networked match must agree a seed the way it already agrees a tic rate; a
     * seed derived per-process from the clock would be exactly the desync this
     * class exists to prevent, wearing a respectable name.</p>
     */
    public static final long DEFAULT_SEED = 0x0FE5C0DEBA5EL;

    /**
     * Channel: does this bot pull the trigger on this tic?
     *
     * <p>Channels exist so two independent questions asked about the same bot on
     * the same tic get independent answers. Without them "do I fire?" and "how
     * far off is my aim?" would be one number, and a bot would always miss by
     * an amount fixed by the decision to shoot.</p>
     */
    public static final int CHANNEL_FIRE = 1;

    /** Channel: how long until this bot's weapon is ready again. */
    public static final int CHANNEL_COOLDOWN = 2;

    /** Channel: how far off the aim is, across the shot. */
    public static final int CHANNEL_AIM_YAW = 3;

    /** Channel: how far off the aim is, vertically. */
    public static final int CHANNEL_AIM_PITCH = 4;

    /** Channel: is this one of the shots taken with nothing acquired at all? */
    public static final int CHANNEL_WILD = 5;

    /** One thousand — the denominator {@link #chance} reads its argument against. */
    public static final int PER_MILLE = 1000;

    /** Bits of a {@code long} kept when a value is folded down to a float. */
    private static final int FLOAT_MANTISSA_BITS = 24;

    /** {@code 2^-24}, the exact scale mapping 24 random bits onto {@code [0, 1)}. */
    private static final float FLOAT_SCALE = 1.0f / (1 << FLOAT_MANTISSA_BITS);

    /** Shift leaving {@link #FLOAT_MANTISSA_BITS} of the best-mixed high bits. */
    private static final int FLOAT_SHIFT = Long.SIZE - FLOAT_MANTISSA_BITS;

    /** The golden-ratio odd constant SplitMix64 strides its counter by. */
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    /** First avalanche multiplier of the SplitMix64 finaliser. */
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;

    /** Second avalanche multiplier of the SplitMix64 finaliser. */
    private static final long MIX_B = 0x94D049BB133111EBL;

    /** First xorshift distance of the SplitMix64 finaliser. */
    private static final int SHIFT_A = 30;

    /** Second xorshift distance. */
    private static final int SHIFT_B = 27;

    /** Third xorshift distance. */
    private static final int SHIFT_C = 31;

    /** Shift that drops the sign bit before a value is reduced to a bound. */
    private static final int UNSIGNED_SHIFT = 1;

    /** Half, for turning a unit draw into a symmetric one. */
    private static final float HALF_RANGE = 2.0f;

    /** The seed every draw is derived from. Immutable. */
    private final long seed;

    /**
     * Creates a generator on the default seed.
     *
     * <p>This is what a single-player match gets. See {@link #DEFAULT_SEED}.</p>
     */
    public BotRng()
    {
        this(DEFAULT_SEED);
    }

    /**
     * Creates a generator on an explicit seed.
     *
     * <p><b>Injectable so a test can pin it</b>, which is the other half of
     * determinism: "the same seed gives the same match" is only a claim until
     * something asserts it, and asserting it needs a second seed to compare
     * against. It is also the seam a networked match would use to agree one
     * seed across peers — see the class Javadoc.</p>
     *
     * @param randomSeed any value; there are no bad seeds, because the seed is
     *     mixed rather than used directly
     */
    public BotRng(final long randomSeed)
    {
        this.seed = randomSeed;
    }

    /** Returns the seed every draw is derived from. */
    public long seed()
    {
        return seed;
    }

    /**
     * Returns a uniform {@code float} in {@code [0, 1)} for one question.
     *
     * <p>Pure: the same three arguments always give the same value, on every
     * machine, in any order, however many times it is asked.</p>
     *
     * @param ticIndex the tic the question is asked on; any value, negative
     *     included
     * @param entityId who is asking — a bot's entity id, so two bots on the same
     *     tic get independent answers
     * @param channel which question, one of the {@code CHANNEL_} constants
     * @return a value in {@code [0, 1)}
     */
    public float unitFloat(final int ticIndex, final int entityId, final int channel)
    {
        // The top bits are the best mixed, so the mantissa is taken from there.
        // Unsigned shift, so the sign bit cannot make this negative.
        return (draw(ticIndex, entityId, channel) >>> FLOAT_SHIFT) * FLOAT_SCALE;
    }

    /**
     * Returns a uniform {@code int} in {@code [0, bound)} for one question.
     *
     * <p>A remainder rather than a rejection loop. The bias is bounded by
     * {@code bound / 2^63}, far below anything observable at the tic counts this
     * engine deals in, and a loop would make the number of mixes depend on the
     * value drawn — which is fine until somebody profiles it and "optimises" the
     * determinism away.</p>
     *
     * @param ticIndex the tic the question is asked on
     * @param entityId who is asking
     * @param channel which question, one of the {@code CHANNEL_} constants
     * @param bound one past the largest value returned; must be positive
     * @return a value in {@code [0, bound)}
     * @throws IllegalArgumentException if {@code bound} is not positive
     */
    public int boundedInt(final int ticIndex, final int entityId, final int channel,
        final int bound)
    {
        if (bound <= 0)
        {
            throw new IllegalArgumentException("bound must be positive, got " + bound);
        }
        // Shifted right by one first, so the value is non-negative before the
        // remainder: %, unlike floorMod, keeps the sign of the dividend, and a
        // negative result here would be a very confusing crash a long way away.
        return (int) ((draw(ticIndex, entityId, channel) >>> UNSIGNED_SHIFT) % bound);
    }

    /**
     * Returns whether a question comes up true with a given probability.
     *
     * <p>Expressed in <b>parts per thousand</b> rather than as a float, so a
     * probability written as a constant is exactly the probability drawn. A
     * float threshold compares two rounded quantities and puts the edge case at
     * the mercy of a decimal literal.</p>
     *
     * @param ticIndex the tic the question is asked on
     * @param entityId who is asking
     * @param channel which question, one of the {@code CHANNEL_} constants
     * @param permille the chance in parts per thousand; 0 never, 1000 always
     * @return true with probability {@code permille / 1000}
     */
    public boolean chance(final int ticIndex, final int entityId, final int channel,
        final int permille)
    {
        return boundedInt(ticIndex, entityId, channel, PER_MILLE) < permille;
    }

    /**
     * Returns a symmetric {@code float} in {@code (-magnitude, +magnitude)}.
     *
     * <p>The shape the aim error wants: an offset either side of a perfect shot,
     * with zero in the middle. Derived from {@link #unitFloat} rather than drawn
     * separately, so there is one definition of a draw.</p>
     *
     * @param ticIndex the tic the question is asked on
     * @param entityId who is asking
     * @param channel which question, one of the {@code CHANNEL_} constants
     * @param magnitude the half-width of the range; must be finite
     * @return a value in {@code (-magnitude, +magnitude)}
     */
    public float symmetric(final int ticIndex, final int entityId, final int channel,
        final float magnitude)
    {
        return (unitFloat(ticIndex, entityId, channel) * HALF_RANGE - 1.0f) * magnitude;
    }

    // The whole generator: fold the seed and the three coordinates into a
    // counter, then avalanche it. Each coordinate gets its own stride AND its
    // own mixing round, so that (tic 2, bot 3) and (tic 3, bot 2) are not the
    // same point. A plain sum would collide those and give different bots
    // correlated behaviour on adjacent tics — precisely the "they all volley
    // together" look this randomness exists to break.
    private long draw(final int ticIndex, final int entityId, final int channel)
    {
        long counter = seed + GOLDEN_GAMMA * ticIndex;
        counter = mix(counter) + GOLDEN_GAMMA * entityId;
        counter = mix(counter) + GOLDEN_GAMMA * channel;
        return mix(counter);
    }

    // SplitMix64's finaliser. Three xor-shift-multiply rounds, which is enough
    // avalanche that one changed input bit flips about half the output bits.
    // Every operation is exact 64-bit integer arithmetic — see the class
    // Javadoc on why that is the point rather than an implementation detail.
    private static long mix(final long value)
    {
        long folded = value;
        folded = (folded ^ (folded >>> SHIFT_A)) * MIX_A;
        folded = (folded ^ (folded >>> SHIFT_B)) * MIX_B;
        return folded ^ (folded >>> SHIFT_C);
    }

    /** Returns a debug rendering naming the seed, which is the whole of the state. */
    @Override
    public String toString()
    {
        return "BotRng{seed=" + seed + "}";
    }
}
