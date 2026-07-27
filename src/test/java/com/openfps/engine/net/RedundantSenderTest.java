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
 * Tests for the redundant-input packet: packing, unpacking, and recovery from
 * deterministic packet loss patterns.
 *
 * The whole exchange runs in memory. There is no socket, no thread and no
 * clock, so every scenario below is exactly reproducible.
 */
@DisplayName("RedundantSender — redundancy instead of retransmission")
class RedundantSenderTest
{
    private static final int LOCAL_ID = 4242;
    private static final int LOCAL_SLOT = 0;
    private static final int REMOTE_SLOT = 1;
    private static final int WIRE_BYTES = 2048;

    private TicCmdBuffer localBuffer;
    private TicCmdBuffer remoteBuffer;
    private PeerConnection peerAtSender;
    private PeerConnection peerAtReceiver;
    private byte[] wire;

    @BeforeEach
    void setUp()
    {
        localBuffer = new TicCmdBuffer();
        remoteBuffer = new TicCmdBuffer();
        peerAtSender = new PeerConnection(REMOTE_SLOT, "10.0.0.2:5021");
        peerAtReceiver = new PeerConnection(LOCAL_SLOT, "10.0.0.1:5021");
        wire = new byte[WIRE_BYTES];
    }

    @Test
    @DisplayName("uses a twenty-byte header, because a bitfield without its anchor tic cannot be "
        + "decoded")
    void headerCarriesTheAckAnchorTic()
    {
        assertThat(RedundantSender.HEADER_BYTES).isEqualTo(20);
        assertThat(RedundantSender.packetBytes(8)).isEqualTo(20 + 8 * TicCmd.BYTES);
        assertThat(RedundantSender.commandCount(RedundantSender.packetBytes(8))).isEqualTo(8);
        assertThat(RedundantSender.commandCount(RedundantSender.HEADER_BYTES - 1)).isZero();
    }

    @Test
    @DisplayName("round trips every header field, including what we have received from the peer")
    void roundTripsTheHeader()
    {
        peerAtSender.recordReceivedTic(0);
        peerAtSender.recordReceivedTic(1);
        peerAtSender.recordReceivedTic(3);
        localBuffer.put(LOCAL_SLOT, new TicCmd(9, 0, 0, 0, 0, 0));

        final int length =
            RedundantSender.pack(wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 9);

        assertThat(RedundantSender.readPlayerId(wire, 0)).isEqualTo(LOCAL_ID);
        assertThat(RedundantSender.readLatestTic(wire, 0)).isEqualTo(9);
        assertThat(RedundantSender.readAckTic(wire, 0))
            .isEqualTo(peerAtSender.ackWindow().highestContiguousTic());
        assertThat(RedundantSender.readAckBitfield(wire, 0))
            .isEqualTo(peerAtSender.ackWindow().bitfield());
        assertThat(length).isGreaterThanOrEqualTo(RedundantSender.HEADER_BYTES);
    }

    @Test
    @DisplayName("packs only the newest command when the peer has acked everything before it")
    void packsOneCommandWhenThePeerIsCaughtUp()
    {
        fillLocal(0, 20);
        peerAtSender.recordRemoteAck(19, 0L);

        final int length =
            RedundantSender.pack(wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 20);

        assertThat(length).isEqualTo(RedundantSender.packetBytes(1));
        assertThat(TicCmd.decodeTicNumber(wire, RedundantSender.HEADER_BYTES)).isEqualTo(20);
    }

    @Test
    @DisplayName("re-sends every command the peer has not acked, in ascending tic order")
    void resendsEverythingUnacked()
    {
        fillLocal(0, 10);
        peerAtSender.recordRemoteAck(6, 0L);

        final int length =
            RedundantSender.pack(wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 10);

        assertThat(RedundantSender.commandCount(length)).isEqualTo(4);
        for (int i = 0; i < 4; i++)
        {
            final int offset = RedundantSender.HEADER_BYTES + i * TicCmd.BYTES;
            assertThat(TicCmd.decodeTicNumber(wire, offset)).isEqualTo(7 + i);
        }
    }

    @Test
    @DisplayName("writes at the caller's offset so several packets can share one scratch buffer")
    void packsAtACallerSuppliedOffset()
    {
        fillLocal(0, 3);
        final int offset = 37;

        final int length =
            RedundantSender.pack(wire, offset, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 3);

        assertThat(RedundantSender.readLatestTic(wire, offset)).isEqualTo(3);
        assertThat(RedundantSender.unpack(wire, offset, length, remoteBuffer, REMOTE_SLOT,
            peerAtReceiver)).isEqualTo(RedundantSender.commandCount(length));
    }

    @Test
    @DisplayName("delivers every input when no packet is lost")
    void deliversEverythingOnACleanLink()
    {
        for (int tic = 0; tic <= 20; tic++)
        {
            exchange(tic, true);
        }

        assertAllReceived(0, 20);
        assertThat(peerAtReceiver.ackWindow().lossPercent()).isZero();
        assertThat(peerAtReceiver.packetsReceived()).isEqualTo(21);
    }

    @Test
    @DisplayName("recovers a single dropped packet from the very next one, at zero added latency")
    void recoversFromOneDroppedPacket()
    {
        for (int tic = 0; tic <= 5; tic++)
        {
            exchange(tic, true);
        }

        exchange(6, false);

        assertThat(remoteBuffer.has(REMOTE_SLOT, 6)).isFalse();

        exchange(7, true);

        assertAllReceived(0, 7);
        assertThat(peerAtReceiver.ackWindow().highestContiguousTic()).isEqualTo(7);
    }

    @Test
    @DisplayName("recovers three consecutively dropped packets from the next one that arrives")
    void recoversFromThreeDroppedPackets()
    {
        for (int tic = 0; tic <= 5; tic++)
        {
            exchange(tic, true);
        }

        exchange(6, false);
        exchange(7, false);
        exchange(8, false);

        exchange(9, true);

        assertAllReceived(0, 9);
        assertThat(peerAtReceiver.ackWindow().highestContiguousTic()).isEqualTo(9);
    }

    @Test
    @DisplayName("recovers seven consecutively dropped packets, the burst the recommended window "
        + "is sized to absorb")
    void recoversFromSevenDroppedPackets()
    {
        for (int tic = 0; tic <= 5; tic++)
        {
            exchange(tic, true);
        }

        for (int tic = 6; tic <= 12; tic++)
        {
            exchange(tic, false);
        }

        assertThat(peerAtReceiver.ackWindow().highestContiguousTic()).isEqualTo(5);

        exchange(13, true);

        assertAllReceived(0, 13);
        assertThat(peerAtReceiver.ackWindow().highestContiguousTic()).isEqualTo(13);
        assertThat(peerAtReceiver.ackWindow().lossPercent()).isZero();
    }

    @Test
    @DisplayName("recovers from a scattered loss pattern rather than only from a clean burst")
    void recoversFromScatteredLoss()
    {
        final int last = 40;
        for (int tic = 0; tic <= last; tic++)
        {
            final boolean deliver = tic % 3 != 0 || tic == 0;
            exchange(tic, deliver);
        }

        assertAllReceived(0, last - 1);
        assertThat(peerAtReceiver.ackWindow().highestContiguousTic())
            .isGreaterThanOrEqualTo(last - 1);
    }

    @Test
    @DisplayName("keeps every input when a whole window of packets is lost but the burst stays "
        + "inside the ring depth")
    void survivesALossBurstUpToTheRingDepth()
    {
        final int burst = 20;
        for (int tic = 0; tic <= 5; tic++)
        {
            exchange(tic, true);
        }
        for (int tic = 6; tic < 6 + burst; tic++)
        {
            exchange(tic, false);
        }

        exchange(6 + burst, true);

        assertAllReceived(0, 6 + burst);
    }

    @Test
    @DisplayName("treats a packet delivered twice as an idempotent overwrite")
    void treatsADuplicatePacketAsIdempotent()
    {
        fillLocal(0, 4);
        final int length =
            RedundantSender.pack(wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 4);

        final int first =
            RedundantSender.unpack(wire, 0, length, remoteBuffer, REMOTE_SLOT, peerAtReceiver);
        final int second =
            RedundantSender.unpack(wire, 0, length, remoteBuffer, REMOTE_SLOT, peerAtReceiver);

        assertThat(second).isEqualTo(first);
        assertThat(peerAtReceiver.packetsReceived()).isEqualTo(2);
        assertThat(remoteBuffer.get(REMOTE_SLOT, 4)).isEqualTo(localBuffer.get(LOCAL_SLOT, 4));
        assertThat(peerAtReceiver.ackWindow().highestContiguousTic()).isEqualTo(4);
    }

    @Test
    @DisplayName("files packets that arrive out of order under the right tics anyway")
    void handlesOutOfOrderPacketArrival()
    {
        fillLocal(0, 3);

        final int lengthA = packAt(0, 0);
        final int lengthB = packAt(1, 1);
        final int lengthC = packAt(2, 2);
        final int lengthD = packAt(3, 3);

        unpackAt(2, lengthC);
        unpackAt(0, lengthA);
        unpackAt(3, lengthD);
        unpackAt(1, lengthB);

        assertAllReceived(0, 3);
        assertThat(peerAtReceiver.ackWindow().highestContiguousTic()).isEqualTo(3);
    }

    @Test
    @DisplayName("never builds a datagram that would fragment at a 1200-byte path MTU")
    void staysUnderTheMtu()
    {
        final int depth = Constants.TIC_BUFFER_SIZE;
        fillLocal(0, depth * 2);

        final int length = RedundantSender.packWindow(
            wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, depth * 2, Integer.MAX_VALUE);

        assertThat(RedundantSender.maxCommandsPerPacket()).isEqualTo(depth);
        assertThat(RedundantSender.commandCount(length)).isEqualTo(depth);
        assertThat(length).isEqualTo(RedundantSender.packetBytes(depth))
            .isLessThanOrEqualTo(RedundantSender.MAX_DATAGRAM_BYTES);
    }

    @Test
    @DisplayName("narrows the window to whatever room the caller's buffer actually has")
    void narrowsTheWindowToTheBufferItWasGiven()
    {
        fillLocal(0, 30);
        final byte[] tight = new byte[RedundantSender.packetBytes(2)];

        final int length = RedundantSender.packWindow(
            tight, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 30, 20);

        assertThat(length).isEqualTo(tight.length);
        assertThat(TicCmd.decodeTicNumber(tight, RedundantSender.HEADER_BYTES)).isEqualTo(29);
    }

    @Test
    @DisplayName("does not run off the start of the match when the newest tic is below the window")
    void doesNotRunOffTheStartOfTheMatch()
    {
        fillLocal(0, 2);

        final int length = RedundantSender.packWindow(
            wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 2, 40);

        assertThat(RedundantSender.commandCount(length)).isEqualTo(3);
        assertThat(TicCmd.decodeTicNumber(wire, RedundantSender.HEADER_BYTES)).isZero();
    }

    @Test
    @DisplayName("skips a command whose tic number is not a legal tic rather than trusting the wire")
    void skipsAMalformedCommand()
    {
        fillLocal(0, 1);
        RedundantSender.pack(wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 1);
        TicCmd.encodeFields(wire, RedundantSender.HEADER_BYTES, -1, 0, 0, 0, 0, 0);

        final int accepted = RedundantSender.unpack(wire, 0,
            RedundantSender.packetBytes(2), remoteBuffer, REMOTE_SLOT, peerAtReceiver);

        assertThat(accepted).isEqualTo(1);
        assertThat(remoteBuffer.has(REMOTE_SLOT, 1)).isTrue();
    }

    @Test
    @DisplayName("refuses a buffer with no room for a single command, and a datagram shorter than "
        + "the header")
    void refusesImpossibleBuffers()
    {
        fillLocal(0, 1);
        final byte[] tooSmall = new byte[RedundantSender.HEADER_BYTES + TicCmd.BYTES - 1];

        assertThatThrownBy(() -> RedundantSender.pack(
            tooSmall, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedundantSender.pack(
            wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedundantSender.unpack(
            wire, 0, RedundantSender.HEADER_BYTES - 1, remoteBuffer, REMOTE_SLOT, peerAtReceiver))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("carries the receiver's ack back to the sender so the window can close again")
    void feedsTheAckBackToTheSender()
    {
        for (int tic = 0; tic <= 3; tic++)
        {
            exchange(tic, true);
        }

        assertThat(peerAtSender.remoteAckedTic()).isEqualTo(3);

        exchange(4, false);
        exchange(5, false);

        assertThat(peerAtSender.remoteAckedTic()).isEqualTo(3);

        exchange(6, true);

        assertThat(peerAtSender.remoteAckedTic()).isEqualTo(6);
    }

    // Produces one tic of input, packs it, and either delivers the packet or drops it.
    // Delivery also carries the receiver's ack back, which is what closes the window.
    private void exchange(final int tic, final boolean deliver)
    {
        localBuffer.put(LOCAL_SLOT, new TicCmd(tic, tic, -tic, tic * 2, 0, tic % 2));
        final int length =
            RedundantSender.pack(wire, 0, peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, tic);
        if (!deliver)
        {
            return;
        }
        RedundantSender.unpack(wire, 0, length, remoteBuffer, REMOTE_SLOT, peerAtReceiver);
        peerAtSender.recordRemoteAck(peerAtReceiver.ackWindow().highestContiguousTic(),
            peerAtReceiver.ackWindow().bitfield());
    }

    private void fillLocal(final int firstTic, final int lastTic)
    {
        for (int tic = firstTic; tic <= lastTic; tic++)
        {
            localBuffer.put(LOCAL_SLOT, new TicCmd(tic, tic, -tic, tic * 2, 0, tic % 2));
        }
    }

    // Packs the packet for a tic into its own region of the shared scratch buffer.
    private int packAt(final int slotIndex, final int tic)
    {
        return RedundantSender.pack(wire, slotIndex * RedundantSender.MAX_DATAGRAM_BYTES / 4,
            peerAtSender, localBuffer, LOCAL_SLOT, LOCAL_ID, tic);
    }

    private void unpackAt(final int slotIndex, final int length)
    {
        RedundantSender.unpack(wire, slotIndex * RedundantSender.MAX_DATAGRAM_BYTES / 4,
            length, remoteBuffer, REMOTE_SLOT, peerAtReceiver);
    }

    private void assertAllReceived(final int firstTic, final int lastTic)
    {
        for (int tic = firstTic; tic <= lastTic; tic++)
        {
            assertThat(remoteBuffer.has(REMOTE_SLOT, tic))
                .withFailMessage("tic %d was never delivered", tic)
                .isTrue();
            assertThat(remoteBuffer.get(REMOTE_SLOT, tic))
                .isEqualTo(localBuffer.get(LOCAL_SLOT, tic));
        }
    }
}
