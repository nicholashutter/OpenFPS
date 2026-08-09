/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

/**
 * G_ Packs and unpacks the redundant-input packet — the reliability layer from
 * net/README.md section 4.
 *
 * ====================================================================
 *  THE MODEL: REDUNDANCY, NOT RETRANSMISSION
 * ====================================================================
 *  Every packet carries all commands since the peer's last ack. Losing k
 *  consecutive packets is fully covered by the next packet that arrives, as
 *  long as k is below the window — with zero added latency, because the
 *  recovery data was already in flight before anyone knew a loss had happened.
 *  Retransmit-on-timeout would cost one round trip to learn of the loss and a
 *  second to repair it.
 *
 * ====================================================================
 *  PACKET LAYOUT — 20-BYTE HEADER, BIG-ENDIAN
 * ====================================================================
 * <pre>
 *  offset  size  field
 *  0       4     playerId       sender; the reason TicCmd has no playerId
 *  4       4     latestTic      newest command in this packet
 *  8       4     ackTic         highest contiguous tic received FROM the peer
 *  12      8     ackBitfield    64 tics below the sender's newest received tic
 *  20      ...   cmd[]          ascending, 12 bytes each
 * </pre>
 *
 *  <b>Deviation from net/README.md section 6, deliberate.</b> The README budgets
 *  a 16-byte header and section 4 writes the packet as
 *  {@code playerId | latestTic | ackBitfield | cmd[...]} — which is 4 + 4 + 8 =
 *  16 and omits the ack's anchor tic. A bitfield is meaningless without the tic
 *  number bit 0 hangs off, and it cannot be inferred from {@code latestTic}:
 *  that is the sender's own newest input, which has nothing to do with what it
 *  has received from the peer. So {@code ackTic} is a real field and the header
 *  is 20 bytes. Cost at the recommended window: 140 to 144 bytes per datagram,
 *  about 59 to 61 KB/s per peer. Noise against the correctness it buys.
 *
 *  The command count is not a header field; it is implied by the datagram
 *  length, and each command carries its own tic number, so a packet is
 *  self-describing under reordering and truncation.
 *
 * ====================================================================
 *  ALLOCATION
 * ====================================================================
 *  Every method writes into or reads from a caller-supplied array. Nothing here
 *  allocates on the send or receive path.
 */
public final class RedundantSender
{
    /** Size of the packet header in bytes. */
    public static final int HEADER_BYTES = 20;

    /**
     * Largest datagram we will build. Kept under the common 1500-byte Ethernet
     * MTU minus the 28-byte IPv4 and UDP headers, with room to spare, so a
     * packet is never fragmented (net/README.md section 6).
     */
    public static final int MAX_DATAGRAM_BYTES = 1200;

    private static final int OFFSET_PLAYER_ID = 0;
    private static final int OFFSET_LATEST_TIC = 4;
    private static final int OFFSET_ACK_TIC = 8;
    private static final int OFFSET_ACK_BITFIELD = 12;

    private RedundantSender()
    {
        // utility class — the packet format is stateless
    }

    /**
     * Returns the largest number of commands that fit in one datagram. Bounded
     * by the MTU and by {@link AckWindow#WIDTH}, because a command older than
     * the input ring depth no longer exists to send.
     */
    public static int maxCommandsPerPacket()
    {
        final int byMtu = (MAX_DATAGRAM_BYTES - HEADER_BYTES) / TicCmd.BYTES;

        return Math.min(byMtu, AckWindow.WIDTH);
    }

    /**
     * Returns the exact datagram size for a given command count.
     *
     * @param commandCount number of commands in the packet
     * @return the packet size in bytes
     */
    public static int packetBytes(final int commandCount)
    {
        return HEADER_BYTES + commandCount * TicCmd.BYTES;
    }

    /**
     * Packs a packet for one peer into a caller-supplied buffer.
     *
     * <p>The command range is {@code [peer.remoteAckedTic() + 1 .. latestTic]},
     * narrowed to what the input ring still holds and to what the buffer can
     * take. The newest command always ships, even when the peer has acked
     * everything, because that is the tic the peer is waiting on.
     *
     * @param dst destination buffer; must have room for the header and at
     *            least one command
     * @param offset index of the first byte written
     * @param peer the peer being sent to; supplies both the ack we send and the
     *             ack that bounds the window
     * @param buffer the input ring holding our own commands
     * @param playerSlot our slot in {@code buffer}
     * @param playerId our player id, written into the header
     * @param latestTic the newest tic we hold
     * @return the number of bytes written
     */
    public static int pack(final byte[] dst,
                           final int offset,
                           final PeerConnection peer,
                           final TicCmdBuffer buffer,
                           final int playerSlot,
                           final int playerId,
                           final int latestTic)
    {
        return packWindow(dst,
            offset,
            peer,
            buffer,
            playerSlot,
            playerId,
            latestTic,
            latestTic - peer.remoteAckedTic());
    }

    /**
     * Packs a packet with an explicitly chosen redundancy window, for callers
     * that want the RTT-derived floor from
     * {@link PeerConnection#redundancyWindow(int, long)} rather than the ack
     * alone. The window is clamped to 1..{@link AckWindow#WIDTH}, to the MTU,
     * and to the room left in {@code dst}.
     *
     * @param dst destination buffer
     * @param offset index of the first byte written
     * @param peer the peer being sent to
     * @param buffer the input ring holding our own commands
     * @param playerSlot our slot in {@code buffer}
     * @param playerId our player id, written into the header
     * @param latestTic the newest tic we hold
     * @param windowTics the desired number of commands
     * @return the number of bytes written
     */
    public static int packWindow(final byte[] dst,
                                 final int offset,
                                 final PeerConnection peer,
                                 final TicCmdBuffer buffer,
                                 final int playerSlot,
                                 final int playerId,
                                 final int latestTic,
                                 final int windowTics)
    {
        if (latestTic < 0)
        {
            throw new IllegalArgumentException("latestTic must not be negative: " + latestTic);
        }

        final int capacityCommands = (dst.length - offset - HEADER_BYTES) / TicCmd.BYTES;

        if (capacityCommands < 1)
        {
            throw new IllegalArgumentException(
                "buffer too small for a packet: need at least "
                    + packetBytes(1) + " bytes from offset " + offset);
        }

        final int window = Math.min(
            Math.min(AckWindow.clampWindow(windowTics), maxCommandsPerPacket()),
            capacityCommands);

        int firstTic = latestTic - window + 1;

        if (firstTic < 0)
        {
            firstTic = 0;
        }

        NetBytes.writeInt(dst, offset + OFFSET_PLAYER_ID, playerId);

        NetBytes.writeInt(dst, offset + OFFSET_LATEST_TIC, latestTic);

        NetBytes.writeInt(dst, offset + OFFSET_ACK_TIC, peer.ackWindow().highestContiguousTic());

        NetBytes.writeLong(dst, offset + OFFSET_ACK_BITFIELD, peer.ackWindow().bitfield());

        int cursor = offset + HEADER_BYTES;

        for (int tic = firstTic; tic <= latestTic; tic++)
        {
            cursor += buffer.encodeInto(dst, cursor, playerSlot, tic);
        }

        return cursor - offset;
    }

    /**
     * Applies a received packet to the input ring and to the sender's peer state.
     *
     * <p>Commands are indexed by their own tic number, so a re-delivered command
     * is an idempotent overwrite and an out-of-order one lands in the right
     * slot. Commands older than the ring depth are rejected by
     * {@link TicCmdBuffer#put} rather than corrupting a newer tic.
     *
     * @param src buffer holding the received datagram
     * @param offset index of the first byte of the packet
     * @param length the datagram length in bytes
     * @param buffer the input ring to fill
     * @param playerSlot the slot the sending peer occupies in {@code buffer}
     * @param peer the sending peer's state, updated with acks and loss
     * @return the number of commands accepted; re-deliveries count, stale ones do not
     */
    public static int unpack(final byte[] src,
                             final int offset,
                             final int length,
                             final TicCmdBuffer buffer,
                             final int playerSlot,
                             final PeerConnection peer)
    {
        if (length < HEADER_BYTES)
        {
            throw new IllegalArgumentException("packet shorter than header: " + length);
        }

        peer.recordReceivedPacket();

        peer.recordRemoteAck(readAckTic(src, offset), readAckBitfield(src, offset));

        final int count = commandCount(length);

        int accepted = 0;

        for (int i = 0; i < count; i++)
        {
            final int cmdOffset = offset + HEADER_BYTES + i * TicCmd.BYTES;

            final int ticNumber = TicCmd.decodeTicNumber(src, cmdOffset);

            if (ticNumber < 0)
            {
                continue;
            }

            if (buffer.putEncoded(playerSlot, src, cmdOffset))
            {
                peer.recordReceivedTic(ticNumber);

                accepted++;
            }
        }

        return accepted;
    }

    /**
     * Returns how many commands a datagram of the given length carries.
     *
     * @param length the datagram length in bytes
     * @return the command count, never negative
     */
    public static int commandCount(final int length)
    {
        if (length < HEADER_BYTES)
        {
            return 0;
        }

        return (length - HEADER_BYTES) / TicCmd.BYTES;
    }

    /**
     * Reads the sender's player id from a packet header.
     *
     * @param src buffer holding the packet
     * @param offset index of the first byte of the packet
     * @return the sender's player id
     */
    public static int readPlayerId(final byte[] src, final int offset)
    {
        return NetBytes.readInt(src, offset + OFFSET_PLAYER_ID);
    }

    /**
     * Reads the newest tic carried by a packet.
     *
     * @param src buffer holding the packet
     * @param offset index of the first byte of the packet
     * @return the sender's newest tic
     */
    public static int readLatestTic(final byte[] src, final int offset)
    {
        return NetBytes.readInt(src, offset + OFFSET_LATEST_TIC);
    }

    /**
     * Reads the sender's highest contiguous received tic — the ack anchor.
     *
     * @param src buffer holding the packet
     * @param offset index of the first byte of the packet
     * @return the acked tic
     */
    public static int readAckTic(final byte[] src, final int offset)
    {
        return NetBytes.readInt(src, offset + OFFSET_ACK_TIC);
    }

    /**
     * Reads the sender's ack bitfield.
     *
     * @param src buffer holding the packet
     * @param offset index of the first byte of the packet
     * @return the 64-bit ack bitfield
     */
    public static long readAckBitfield(final byte[] src, final int offset)
    {
        return NetBytes.readLong(src, offset + OFFSET_ACK_BITFIELD);
    }

}
