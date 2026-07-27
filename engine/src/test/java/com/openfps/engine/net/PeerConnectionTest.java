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
 * Tests for per-peer state: address, EWMA round-trip time, ack window and loss
 * statistics. There is no socket here and no I/O of any kind.
 */
@DisplayName("PeerConnection — per-peer state with no socket in it")
class PeerConnectionTest
{
    private static final String ADDRESS = "192.168.1.20:5021";
    private static final long NANOS_PER_TIC_60HZ = 16_666_666L;
    private static final long RTT_100MS = 100_000_000L;
    private static final long RTT_200MS = 200_000_000L;

    private PeerConnection peer;

    @BeforeEach
    void setUp()
    {
        peer = new PeerConnection(1, ADDRESS);
    }

    @Test
    @DisplayName("exposes its identity and address without owning a socket")
    void exposesItsIdentity()
    {
        assertThat(peer.peerId()).isEqualTo(1);
        assertThat(peer.address()).isEqualTo(ADDRESS);
        assertThat(peer.ackWindow()).isNotNull();
        assertThat(peer.packetsReceived()).isZero();
        assertThat(peer.hasRttSample()).isFalse();
        assertThat(peer.smoothedRttNanos()).isEqualTo(PeerConnection.NO_RTT);
    }

    @Test
    @DisplayName("takes the first round-trip sample verbatim instead of climbing to it from zero")
    void takesTheFirstRttSampleVerbatim()
    {
        peer.recordRttSample(RTT_100MS);

        assertThat(peer.hasRttSample()).isTrue();
        assertThat(peer.smoothedRttNanos()).isEqualTo(RTT_100MS);
    }

    @Test
    @DisplayName("smooths later samples as 0.7 old plus 0.3 new, in integer arithmetic")
    void smoothsLaterSamplesWithTheEwma()
    {
        peer.recordRttSample(RTT_100MS);

        peer.recordRttSample(RTT_200MS);

        // (7 * 100ms + 3 * 200ms) / 10 = 130ms
        assertThat(peer.smoothedRttNanos()).isEqualTo(130_000_000L);

        peer.recordRttSample(RTT_200MS);

        // (7 * 130ms + 3 * 200ms) / 10 = 151ms
        assertThat(peer.smoothedRttNanos()).isEqualTo(151_000_000L);
    }

    @Test
    @DisplayName("converges towards a steady round-trip time without ever overshooting it")
    void convergesTowardsASteadyRoundTrip()
    {
        peer.recordRttSample(0L);
        for (int i = 0; i < 100; i++)
        {
            peer.recordRttSample(RTT_100MS);
            assertThat(peer.smoothedRttNanos()).isLessThanOrEqualTo(RTT_100MS);
        }

        assertThat(peer.smoothedRttNanos()).isGreaterThan(RTT_100MS - 1_000_000L);
    }

    @Test
    @DisplayName("produces the same smoothed value from the same sample sequence every time, "
        + "because two peers disagreeing about window size is a bug that only shows under loss")
    void isDeterministicAcrossRuns()
    {
        final PeerConnection other = new PeerConnection(2, ADDRESS);
        final long[] samples = {5_000_000L, 90_000_000L, 12_000_000L, 40_000_000L, 7_000_000L};

        for (final long sample : samples)
        {
            peer.recordRttSample(sample);
            other.recordRttSample(sample);
        }

        assertThat(peer.smoothedRttNanos()).isEqualTo(other.smoothedRttNanos());
    }

    @Test
    @DisplayName("counts received packets separately from received tics, because one packet "
        + "carries several tics")
    void countsPacketsSeparatelyFromTics()
    {
        peer.recordReceivedPacket();
        peer.recordReceivedTic(0);
        peer.recordReceivedTic(1);
        peer.recordReceivedTic(2);

        assertThat(peer.packetsReceived()).isEqualTo(1);
        assertThat(peer.ackWindow().highestContiguousTic()).isEqualTo(2);
    }

    @Test
    @DisplayName("reports the loss measured by its ack window")
    void reportsLossFromTheAckWindow()
    {
        for (int tic = 0; tic <= 10; tic++)
        {
            if (tic % 2 == 0)
            {
                peer.recordReceivedTic(tic);
            }
        }

        assertThat(peer.lossPercent()).isEqualTo(peer.ackWindow().lossPercent()).isEqualTo(50);
    }

    @Test
    @DisplayName("advances its view of what the peer has acked, and never lets a reordered older "
        + "ack undo it")
    void keepsTheRemoteAckMonotonic()
    {
        assertThat(peer.remoteAckedTic()).isEqualTo(-1);

        assertThat(peer.recordRemoteAck(10, 0b1011L)).isTrue();
        assertThat(peer.remoteAckedTic()).isEqualTo(10);
        assertThat(peer.remoteAckBitfield()).isEqualTo(0b1011L);

        assertThat(peer.recordRemoteAck(4, 0L)).isFalse();
        assertThat(peer.remoteAckedTic()).isEqualTo(10);
        assertThat(peer.remoteAckBitfield()).isEqualTo(0b1011L);

        assertThat(peer.recordRemoteAck(10, 0L)).isFalse();
        assertThat(peer.remoteAckedTic()).isEqualTo(10);
    }

    @Test
    @DisplayName("sizes the redundancy window from the round trip when the peer is keeping up")
    void sizesTheWindowFromTheRoundTrip()
    {
        peer.recordRttSample(130_000_000L);
        peer.recordRemoteAck(59, 0L);

        // ceil(130ms / 16.667ms) = 8, plus the 2-tic safety margin
        assertThat(peer.redundancyWindow(60, NANOS_PER_TIC_60HZ)).isEqualTo(10);
    }

    @Test
    @DisplayName("widens the redundancy window past the round-trip estimate when the peer is "
        + "further behind than that")
    void widensTheWindowWhenThePeerIsFurtherBehind()
    {
        peer.recordRttSample(1_000_000L);
        peer.recordRemoteAck(10, 0L);

        assertThat(peer.redundancyWindow(40, NANOS_PER_TIC_60HZ)).isEqualTo(30);
    }

    @Test
    @DisplayName("never asks for more commands than the input ring still holds")
    void clampsTheWindowToTheRingDepth()
    {
        peer.recordRttSample(5_000_000_000L);

        assertThat(peer.redundancyWindow(10_000, NANOS_PER_TIC_60HZ))
            .isEqualTo(Constants.TIC_BUFFER_SIZE);
    }

    @Test
    @DisplayName("asks for at least one command even before any round trip has been measured")
    void alwaysAsksForAtLeastOneCommand()
    {
        assertThat(peer.redundancyWindow(0, NANOS_PER_TIC_60HZ))
            .isGreaterThanOrEqualTo(1)
            .isLessThanOrEqualTo(Constants.TIC_BUFFER_SIZE);
    }

    @Test
    @DisplayName("stalls the simulation once the peer falls further behind than the latency budget")
    void stallsOnceThePeerFallsTooFarBehind()
    {
        for (int tic = 0; tic <= 10; tic++)
        {
            peer.recordReceivedTic(tic);
        }

        assertThat(peer.isStalling(10 + Constants.MAX_LATENCY_TICS)).isFalse();
        assertThat(peer.isStalling(10 + Constants.MAX_LATENCY_TICS + 1)).isTrue();
    }

    @Test
    @DisplayName("treats a late joiner's base tic as already accounted for on both ack directions")
    void supportsALateJoiner()
    {
        final PeerConnection late = new PeerConnection(2, ADDRESS, 500);

        assertThat(late.remoteAckedTic()).isEqualTo(499);
        assertThat(late.ackWindow().highestContiguousTic()).isEqualTo(499);
        assertThat(late.isStalling(500)).isFalse();
    }

    @Test
    @DisplayName("rejects an out-of-range peer id, a missing address and impossible timings")
    void rejectsInvalidConstruction()
    {
        assertThatThrownBy(() -> new PeerConnection(-1, ADDRESS))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeerConnection(Constants.MAX_PLAYERS, ADDRESS))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeerConnection(0, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeerConnection(0, ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> peer.recordRttSample(-1L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> peer.redundancyWindow(0, 0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("prints its state for a readable failure message")
    void describesItself()
    {
        assertThat(peer.toString()).contains(ADDRESS).contains("id=1");
    }
}
