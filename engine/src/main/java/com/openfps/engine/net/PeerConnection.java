/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import com.openfps.engine.common.Constants;

/**
 * G_ Per-peer state: address, smoothed RTT, ack window, loss stats.
 *
 * <b>This class holds no socket.</b> One shared non-blocking
 * {@code DatagramChannel} lives in the HAL adapter and is demultiplexed by
 * source address (net/README.md sections 1 and 11). Keeping the socket out of
 * here is what makes the whole netcode core unit-testable with no I/O.
 *
 * ====================================================================
 *  RTT SMOOTHING — EWMA
 * ====================================================================
 * <pre>
 *  smoothed = 0.7 * smoothed + 0.3 * sample
 * </pre>
 *  One long of state, no ring buffer, and what Quake 3 does. It is evaluated
 *  in integer arithmetic as {@code (7 * smoothed + 3 * sample) / 10} so the
 *  result is bit-identical on x86 and ARM. That matters even though RTT never
 *  feeds the simulation directly: it sizes the redundancy window, and two peers
 *  disagreeing about window size is a bug that only shows up under loss.
 *
 * ====================================================================
 *  TWO DIRECTIONS OF ACK
 * ====================================================================
 *  {@link #ackWindow()} tracks tics received <i>from</i> this peer. It is what
 *  we put in the packets we send them, and what loss stats are measured over.
 *
 *  {@link #remoteAckedTic()} is what they told us they have received <i>from
 *  us</i>. It is what bounds our redundancy window when sending to them. The
 *  two are unrelated numbers and conflating them re-sends the wrong range.
 *
 *  This class is not thread-safe; it is owned by the game loop thread.
 */
public final class PeerConnection
{
    /**
     * Extra tics added to the RTT-derived redundancy window, per
     * net/README.md section 4: {@code W = ceil(RTT / ticDuration) + 2}. Two
     * covers the half-tic quantisation at each end of the round trip.
     */
    public static final int WINDOW_SAFETY_TICS = 2;

    /** Returned by {@link #smoothedRttNanos()} before any sample is recorded. */
    public static final long NO_RTT = 0L;

    private static final long EWMA_OLD_WEIGHT = 7L;
    private static final long EWMA_NEW_WEIGHT = 3L;
    private static final long EWMA_DIVISOR = 10L;

    private final int peerId;
    private final String address;
    private final AckWindow ackWindow;

    /** Smoothed round-trip time in nanoseconds. MUTABLE: updated per received packet. */
    private long smoothedRttNanos;

    /** Whether any RTT sample has landed yet. MUTABLE: set once, on the first sample. */
    private boolean hasRttSample;

    /** Newest tic this peer has acked of ours. MUTABLE: updated per received packet. */
    private int remoteAckedTic;

    /** Bitfield this peer last sent us. MUTABLE: updated per received packet. */
    private long remoteAckBitfield;

    /** Packets received from this peer. MUTABLE: counter. */
    private long packetsReceived;

    /**
     * Creates a peer starting at tic 0.
     *
     * @param peerId this peer's slot, 0..{@link Constants#MAX_PLAYERS} - 1
     * @param address the peer's transport address as "host:port"
     */
    public PeerConnection(final int peerId, final String address)
    {
        this(peerId, address, 0);
    }

    /**
     * Creates a peer joining at a given tic.
     *
     * @param peerId this peer's slot, 0..{@link Constants#MAX_PLAYERS} - 1
     * @param address the peer's transport address as "host:port"
     * @param baseTic the first tic this peer is expected to send
     */
    public PeerConnection(final int peerId, final String address, final int baseTic)
    {
        if (peerId < 0 || peerId >= Constants.MAX_PLAYERS)
        {
            throw new IllegalArgumentException("peerId out of range: " + peerId);
        }
        if (address == null || address.isEmpty())
        {
            throw new IllegalArgumentException("address must not be null or empty");
        }
        this.peerId = peerId;
        this.address = address;
        this.ackWindow = new AckWindow(baseTic);
        this.smoothedRttNanos = NO_RTT;
        this.hasRttSample = false;
        this.remoteAckedTic = baseTic - 1;
        this.remoteAckBitfield = 0L;
        this.packetsReceived = 0L;
    }

    /** Returns this peer's slot. */
    public int peerId()
    {
        return peerId;
    }

    /** Returns the peer's transport address as "host:port". */
    public String address()
    {
        return address;
    }

    /** Returns the window of tics received from this peer. */
    public AckWindow ackWindow()
    {
        return ackWindow;
    }

    /** Returns the number of packets received from this peer. */
    public long packetsReceived()
    {
        return packetsReceived;
    }

    /**
     * Folds one round-trip measurement into the smoothed value. The first
     * sample is taken verbatim; smoothing from a zero seed would spend a dozen
     * packets climbing to the true value and undersize the window meanwhile.
     *
     * @param rttNanos the measured round trip in nanoseconds; must not be negative
     */
    public void recordRttSample(final long rttNanos)
    {
        if (rttNanos < 0L)
        {
            throw new IllegalArgumentException("rttNanos must not be negative: " + rttNanos);
        }
        if (!hasRttSample)
        {
            smoothedRttNanos = rttNanos;
            hasRttSample = true;
            return;
        }
        smoothedRttNanos =
            (EWMA_OLD_WEIGHT * smoothedRttNanos + EWMA_NEW_WEIGHT * rttNanos) / EWMA_DIVISOR;
    }

    /** Returns the smoothed round-trip time in nanoseconds, or {@link #NO_RTT} if unmeasured. */
    public long smoothedRttNanos()
    {
        return smoothedRttNanos;
    }

    /** Returns true once at least one RTT sample has been recorded. */
    public boolean hasRttSample()
    {
        return hasRttSample;
    }

    /**
     * Records a tic received from this peer. One packet carries several tics,
     * so packet accounting is separate — see {@link #recordReceivedPacket()}.
     *
     * @param ticNumber the tic that arrived
     * @return true if the window changed, false for a duplicate
     */
    public boolean recordReceivedTic(final int ticNumber)
    {
        return ackWindow.record(ticNumber);
    }

    /**
     * Counts one received packet, for loss and liveness accounting.
     */
    public void recordReceivedPacket()
    {
        packetsReceived++;
    }

    /**
     * Records what this peer says it has received from us.
     *
     * <p>Monotonic on purpose: acks arrive out of order over UDP, and letting an
     * older ack overwrite a newer one would widen the redundancy window on
     * every reordered packet.
     *
     * @param ackTic the peer's highest contiguous tic received from us
     * @param ackBitfield the peer's bitfield for the 64 tics below its newest
     * @return true if this advanced our view of the peer's ack
     */
    public boolean recordRemoteAck(final int ackTic, final long ackBitfield)
    {
        if (ackTic <= remoteAckedTic)
        {
            return false;
        }
        remoteAckedTic = ackTic;
        remoteAckBitfield = ackBitfield;
        return true;
    }

    /** Returns the highest tic this peer has acked of ours. */
    public int remoteAckedTic()
    {
        return remoteAckedTic;
    }

    /** Returns the bitfield this peer last reported. */
    public long remoteAckBitfield()
    {
        return remoteAckBitfield;
    }

    /** Returns packet loss from this peer over the ack window, 0..100. */
    public int lossPercent()
    {
        return ackWindow.lossPercent();
    }

    /**
     * Returns how many commands to pack when sending to this peer.
     *
     * <p>Two lower bounds apply and the wider one wins: everything the peer has
     * not acked must be re-sent, and net/README.md section 4 wants at least
     * {@code ceil(RTT / ticDuration) + 2} tics in flight so a loss is covered
     * before the ack for it could possibly have arrived.
     *
     * @param latestTic the newest tic we hold
     * @param nanosPerTic the tic duration in nanoseconds; must be positive
     * @return the command count, 1..{@link AckWindow#WIDTH}
     */
    public int redundancyWindow(final int latestTic, final long nanosPerTic)
    {
        if (nanosPerTic <= 0L)
        {
            throw new IllegalArgumentException("nanosPerTic must be positive: " + nanosPerTic);
        }
        final long rttTics = (smoothedRttNanos + nanosPerTic - 1L) / nanosPerTic;
        final long fromRtt = rttTics + WINDOW_SAFETY_TICS;
        final int fromAck = latestTic - remoteAckedTic;
        return AckWindow.clampWindow((int) Math.max(fromRtt, (long) fromAck));
    }

    /**
     * Reports whether this peer is far enough behind that the simulation must
     * block. That is lockstep working as designed, not a fault to paper over.
     *
     * @param localTic the tic the local simulation wants to run
     * @return true if the peer is more than {@link Constants#MAX_LATENCY_TICS} behind
     */
    public boolean isStalling(final int localTic)
    {
        return localTic - ackWindow.highestContiguousTic() > Constants.MAX_LATENCY_TICS;
    }

    @Override
    public String toString()
    {
        return "PeerConnection{id=" + peerId
            + ", address=" + address
            + ", rttNanos=" + smoothedRttNanos
            + ", contiguousTic=" + ackWindow.highestContiguousTic()
            + ", lossPercent=" + ackWindow.lossPercent()
            + "}";
    }
}
