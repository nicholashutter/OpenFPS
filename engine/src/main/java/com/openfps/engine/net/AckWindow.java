/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import com.openfps.engine.common.Constants;

/**
 * G_ Receive-side acknowledgement state for one peer: the highest tic seen,
 * a 64-bit bitfield covering the 64 tics before it, and the highest
 * contiguous tic received.
 *
 * ====================================================================
 *  WHAT GOES ON THE WIRE
 * ====================================================================
 *  net/README.md section 4 specifies the ack as "highest contiguous tic
 *  received, plus a 64-bit bitfield covering the previous 64 tics", where the
 *  bitfield width is exactly {@link Constants#TIC_BUFFER_SIZE}.
 *
 *  Those two are anchored differently, and it matters:
 * <pre>
 *   highestTic            the newest tic number seen from this peer
 *   bitfield bit i        set when tic (highestTic - 1 - i) was seen, i = 0..63
 *   highestContiguousTic  the highest T with every tic up to T seen
 * </pre>
 *  The bitfield has to hang off {@code highestTic}, not off the contiguous tic:
 *  anchored on the contiguous tic every bit would be set by definition and the
 *  field would carry no information at all. Anchored on the newest tic, the
 *  holes in it are exactly the losses the sender has to cover.
 *
 *  {@code highestContiguousTic} is what bounds the redundancy window, because
 *  it is the point below which the peer provably needs nothing.
 *
 * ====================================================================
 *  WINDOW FLOOR
 * ====================================================================
 *  A tic that falls more than {@link Constants#TIC_BUFFER_SIZE} behind
 *  {@code highestTic} can never be acked again — its bit has shifted out. If
 *  the contiguous tic were allowed to stay pinned below that floor it would
 *  never advance and the redundancy window would grow without bound. So the
 *  contiguous tic is floored at {@code highestTic - TIC_BUFFER_SIZE}. In
 *  practice lockstep stalls at {@link Constants#MAX_LATENCY_TICS} long before
 *  a peer gets 64 tics behind, so the floor is a safety net, not a policy.
 *
 *  This class is not thread-safe; it is owned by the game loop thread.
 */
public final class AckWindow
{
    /**
     * Bitfield width in tics. Load-bearing, and exactly
     * {@link Constants#TIC_BUFFER_SIZE} so a tic that is still in the input
     * ring is still ackable.
     */
    public static final int WIDTH = Constants.TIC_BUFFER_SIZE;

    /** Value returned when no tic has been received yet. */
    public static final int NO_TIC = -1;

    private static final int PERCENT = 100;

    private final int baseTic;

    /** Newest tic seen. MUTABLE: advances as packets arrive. */
    private int highestTic;

    /** Bit i covers tic (highestTic - 1 - i). MUTABLE: shifts as packets arrive. */
    private long bitfield;

    /** Highest tic with no gap below it. MUTABLE: advances as gaps fill. */
    private int highestContiguousTic;

    /**
     * Creates a window for a peer joining at tic 0.
     */
    public AckWindow()
    {
        this(0);
    }

    /**
     * Creates a window for a peer that joins mid-match, treating everything
     * below {@code baseTic} as already accounted for. Without this a late
     * joiner would report a contiguous tic of -1 forever and ask every peer to
     * resend the whole match.
     *
     * @param baseTic the first tic this peer is expected to send; must not be negative
     */
    public AckWindow(final int baseTic)
    {
        if (baseTic < 0)
        {
            throw new IllegalArgumentException("baseTic must not be negative: " + baseTic);
        }

        this.baseTic = baseTic;

        this.highestTic = NO_TIC;

        this.bitfield = 0L;

        this.highestContiguousTic = baseTic - 1;
    }

    /**
     * Records a received tic. Idempotent: re-delivering a tic that is already
     * recorded changes nothing, which is what makes redundant redelivery free
     * of dedup bookkeeping.
     *
     * @param ticNumber the tic that arrived; must not be negative
     * @return true if this call changed the window, false for a duplicate or a
     *         tic that has already fallen out of the window
     */
    public boolean record(final int ticNumber)
    {
        if (ticNumber < 0)
        {
            throw new IllegalArgumentException("ticNumber must not be negative: " + ticNumber);
        }

        if (isAcked(ticNumber))
        {
            return false;
        }

        if (ticNumber > highestTic)
        {
            advanceHighest(ticNumber);

            advanceContiguous();

            return true;
        }

        final int back = highestTic - ticNumber;

        if (back > WIDTH)
        {
            // Its bit shifted out of the window; too old to be useful.
            return false;
        }

        bitfield |= 1L << (back - 1);

        advanceContiguous();

        return true;
    }

    /**
     * Reports whether a tic is covered by this window.
     *
     * @param ticNumber the tic to test
     * @return true if that tic has been received and is still inside the window
     */
    public boolean isAcked(final int ticNumber)
    {
        if (ticNumber < 0 || highestTic == NO_TIC || ticNumber > highestTic)
        {
            return false;
        }

        if (ticNumber == highestTic)
        {
            return true;
        }

        if (ticNumber <= highestContiguousTic)
        {
            return true;
        }

        final int back = highestTic - ticNumber;

        if (back > WIDTH)
        {
            return false;
        }

        return (bitfield & (1L << (back - 1))) != 0L;
    }

    /** Returns the newest tic received, or {@link #NO_TIC} if none. */
    public int highestTic()
    {
        return highestTic;
    }

    /**
     * Returns the highest tic with no gap below it, or {@code baseTic - 1} if
     * nothing contiguous has arrived. This is the value that goes on the wire
     * and bounds the peer's redundancy window.
     */
    public int highestContiguousTic()
    {
        return highestContiguousTic;
    }

    /** Returns the raw bitfield; bit i covers tic {@code highestTic - 1 - i}. */
    public long bitfield()
    {
        return bitfield;
    }

    /**
     * Returns how many bitfield positions currently correspond to real tics.
     * Below 64 received tics the window is not yet full and loss must be
     * measured against this, not against 64.
     */
    public int windowBits()
    {
        if (highestTic == NO_TIC)
        {
            return 0;
        }

        return Math.min(WIDTH, highestTic - baseTic);
    }

    /** Returns how many of the {@link #windowBits()} tics below the newest arrived. */
    public int receivedInWindow()
    {
        final int bits = windowBits();

        if (bits == 0)
        {
            return 0;
        }

        return Long.bitCount(bitfield & mask(bits));
    }

    /**
     * Returns packet loss over the window as an integer percentage, 0..100.
     * Integer arithmetic on purpose: this feeds diagnostics and adaptive
     * throttling, and net/README.md bans float accumulation anywhere near the
     * simulation.
     */
    public int lossPercent()
    {
        final int bits = windowBits();

        if (bits == 0)
        {
            return 0;
        }

        return (bits - receivedInWindow()) * PERCENT / bits;
    }

    /**
     * Returns how many commands a sender must include to cover everything this
     * peer has not acked, ending at {@code latestTic}.
     *
     * <p>Always at least 1, because the current tic always ships, and never more
     * than {@link #WIDTH}, because older input is no longer in the input ring.
     *
     * @param latestTic the newest tic the sender holds
     * @return the redundancy window in commands, 1..{@link #WIDTH}
     */
    public int redundancyWindow(final int latestTic)
    {
        return clampWindow(latestTic - highestContiguousTic);
    }

    /**
     * Clamps a raw command count into the legal redundancy window.
     *
     * @param rawCount the desired number of commands
     * @return the count clamped to 1..{@link #WIDTH}
     */
    public static int clampWindow(final int rawCount)
    {
        if (rawCount < 1)
        {
            return 1;
        }

        if (rawCount > WIDTH)
        {
            return WIDTH;
        }

        return rawCount;
    }

    /**
     * Clears the window back to its initial state, for use when a peer drops.
     */
    public void reset()
    {
        highestTic = NO_TIC;

        bitfield = 0L;

        highestContiguousTic = baseTic - 1;
    }

    // Shifts the bitfield up by the gap and marks the tic that was newest.
    private void advanceHighest(final int ticNumber)
    {
        if (highestTic == NO_TIC)
        {
            highestTic = ticNumber;

            return;
        }

        final int delta = ticNumber - highestTic;

        if (delta >= WIDTH)
        {
            // Everything the bitfield described has shifted out. A long shift
            // is undefined for long (it is taken modulo 64), so clear instead.
            bitfield = 0L;
        }
        else
        {
            bitfield = (bitfield << delta) | (1L << (delta - 1));
        }

        highestTic = ticNumber;
    }

    // Walks the contiguous run forward, then applies the window floor.
    private void advanceContiguous()
    {
        while (isAcked(highestContiguousTic + 1))
        {
            highestContiguousTic++;
        }

        final int floor = highestTic - WIDTH;

        if (highestContiguousTic < floor)
        {
            highestContiguousTic = floor;
        }
    }

    // A long shift of 64 is a no-op rather than a clear, so special-case it.
    private static long mask(final int bits)
    {
        if (bits >= Long.SIZE)
        {
            return -1L;
        }

        return (1L << bits) - 1L;
    }
}
