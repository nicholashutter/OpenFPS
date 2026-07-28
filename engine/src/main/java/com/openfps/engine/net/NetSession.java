/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import com.openfps.engine.common.Constants;
import com.openfps.engine.hal.port.I_DatagramPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A_ The live end of the peer-to-peer protocol: one socket, a set of peers, and
 * a tic's worth of input going out and coming in.
 *
 * <p>Every piece of this already existed and none of them were connected.
 * {@link TicCmd} knew how to be twelve bytes, {@link RedundantSender} knew how
 * to lay a window of them into a datagram, {@link AckWindow} knew which had
 * arrived, {@link PeerConnection} knew a peer's round trip time — and nothing
 * opened a socket. This is the class that does, and it is deliberately thin:
 * it owns the loop and the slot bookkeeping, and delegates every decision about
 * bytes to the parts that were already tested.</p>
 *
 * <h2>Redundancy, not retransmission</h2>
 *
 * <p>Each packet carries <b>a window of recent commands, not just the newest
 * one</b>. That is the whole reliability model and it is the one thing worth
 * understanding here. Under lockstep every peer must consume every input, so a
 * lost packet cannot simply be skipped — but waiting for a retransmission costs
 * a round trip, and at 60 Hz a round trip is several tics of frozen game. Since
 * a command is twelve bytes, re-sending the last several every time makes a
 * lost packet cost <b>nothing</b>: the next packet already contains what the
 * lost one did. See {@code net/README.md} for the bandwidth this trades
 * away.</p>
 *
 * <p>{@link PeerConnection#redundancyWindow} sizes it from the peer's measured
 * loss rather than fixing it, so a clean link pays almost nothing and a lossy
 * one widens automatically.</p>
 *
 * <h2>Peers are identified by what they say, not where they are</h2>
 *
 * <p>{@link I_DatagramPort#receive()} returns bytes and drops the source
 * address, so a packet is matched to a peer by the player id in its header.
 * That is a real limitation and worth stating plainly: <b>this trusts the
 * sender's claim about who it is.</b> On a LAN between peers who chose to play
 * together that is acceptable, and it is what the current port contract allows.
 * A packet whose id matches no known peer is dropped and counted rather than
 * silently creating one, because auto-adding peers from unsolicited traffic is
 * how a game becomes an amplifier.</p>
 *
 * <h2>Slots</h2>
 *
 * <p>The local player always occupies {@link #LOCAL_SLOT} in the shared
 * {@link TicCmdBuffer}; peers take the slots above it, in the order they were
 * added. Slot is a buffer index and player id is a network identity, and they
 * are deliberately not the same number — ids come from whoever set the match
 * up, and nothing may assume they are dense or ordered.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Not thread-safe. It belongs to the game loop thread, which is the thread
 * that has a tic to send and needs the tics that have arrived.</p>
 */
public final class NetSession
{
    /** The buffer slot the local player's own commands occupy. */
    public static final int LOCAL_SLOT = 0;

    /** How many remote peers a session can hold — every slot but the local one. */
    public static final int MAX_PEERS = Constants.MAX_PLAYERS - 1;

    /** The port a session binds when none is named. */
    public static final int DEFAULT_PORT = Constants.DEFAULT_NET_PORT;

    private static final Logger LOG = LoggerFactory.getLogger(NetSession.class);

    /** The socket. Owned by the caller's HAL, opened and closed here. */
    private final I_DatagramPort socket;

    /** Our identity on the wire. */
    private final int localPlayerId;

    /** The tic duration, which sizes the redundancy window against measured loss. */
    private final long nanosPerTic;

    /** Everyone's recent commands, ours in {@link #LOCAL_SLOT}. */
    private final TicCmdBuffer commands = new TicCmdBuffer();

    /** Connected peers, densely packed from index 0. Slot is index + 1. */
    private final PeerConnection[] peers = new PeerConnection[MAX_PEERS];

    /**
     * One datagram's worth of scratch, reused.
     *
     * <p>Allocated once because {@link #tick} runs sixty times a second: a fresh
     * 1200-byte array per peer per tic is 500 KB a second of garbage for no
     * reason, and {@code STYLE.md} § 13.4 bans allocation on a per-tic path.</p>
     */
    private final byte[] sendBuffer = new byte[RedundantSender.MAX_DATAGRAM_BYTES];

    /** How many peers have been added. MUTABLE. */
    private int peerCount;

    /** The newest tic we have a local command for. MUTABLE. */
    private int latestLocalTic = -1;

    /** True between {@link #open} and {@link #close}. MUTABLE. */
    private boolean open;

    /** Datagrams sent. MUTABLE, for the summary. */
    private long packetsSent;

    /** Datagrams received and matched to a peer. MUTABLE. */
    private long packetsReceived;

    /** Datagrams received that named nobody we know. MUTABLE. */
    private long packetsFromStrangers;

    /** Datagrams received that were too short to be a packet. MUTABLE. */
    private long packetsMalformed;

    /** Commands accepted into the ring from peers. MUTABLE. */
    private long commandsAccepted;

    /** Bytes handed to the socket. MUTABLE. */
    private long bytesSent;

    /** Bytes taken from the socket. MUTABLE. */
    private long bytesReceived;

    /**
     * Creates a session over a socket.
     *
     * @param datagramPort the socket to use; must not be null. Not opened here —
     *     see {@link #open}
     * @param playerId this peer's identity on the wire; must not be negative
     * @param ticDurationNanos the simulation's tic duration, used to size the
     *     redundancy window; must be positive
     * @throws IllegalArgumentException if any argument is out of range
     */
    public NetSession(final I_DatagramPort datagramPort, final int playerId,
        final long ticDurationNanos)
    {
        if (datagramPort == null)
        {
            throw new IllegalArgumentException("datagramPort must not be null");
        }
        if (playerId < 0)
        {
            throw new IllegalArgumentException("playerId must not be negative, got " + playerId);
        }
        if (ticDurationNanos <= 0)
        {
            throw new IllegalArgumentException(
                "tic duration must be positive, got " + ticDurationNanos);
        }
        this.socket = datagramPort;
        this.localPlayerId = playerId;
        this.nanosPerTic = ticDurationNanos;
    }

    /**
     * Opens the socket on a local port.
     *
     * @param port the UDP port to bind; 0 asks the OS for any free port, which
     *     is what a second instance on one machine wants
     */
    public void open(final int port)
    {
        socket.init();
        socket.bind(port);
        this.open = true;
        LOG.info("NetSession open: player {} on UDP {}", localPlayerId, port);
    }

    /**
     * Adds a peer to send to and accept from.
     *
     * @param peerId the peer's player id; must differ from ours and from every
     *     peer already added
     * @param address the peer's {@code host:port}, or {@code host} for the
     *     default port; must not be null or empty
     * @return the buffer slot the peer's commands will land in
     * @throws IllegalStateException if the session is full
     * @throws IllegalArgumentException if the id collides or the address is
     *     unusable
     */
    public int addPeer(final int peerId, final String address)
    {
        if (peerCount >= MAX_PEERS)
        {
            throw new IllegalStateException("a session holds at most " + MAX_PEERS + " peers");
        }
        if (peerId == localPlayerId)
        {
            throw new IllegalArgumentException(
                "peer id " + peerId + " is our own — a peer cannot be itself");
        }
        if (peerById(peerId) != null)
        {
            throw new IllegalArgumentException("peer " + peerId + " is already connected");
        }
        peers[peerCount] = new PeerConnection(peerId, address);
        peerCount = peerCount + 1;
        final int slot = peerCount;
        LOG.info("Peer {} at {} takes slot {}", peerId, address, slot);
        return slot;
    }

    /**
     * Records what the local player did on a tic.
     *
     * <p>Must be called before {@link #tick} for the same tic, because that is
     * what the outgoing packet carries. A tic with no local command is not an
     * error — the window simply has a hole, and the peer's
     * {@link TicCmdBuffer} will report it as missing rather than as zero
     * input.</p>
     *
     * @param cmd the local player's command for this tic; must not be null
     * @return true if the ring accepted it, false if the tic is older than the
     *     ring depth
     */
    public boolean recordLocalCommand(final TicCmd cmd)
    {
        if (cmd == null)
        {
            throw new IllegalArgumentException("cmd must not be null");
        }
        if (!commands.put(LOCAL_SLOT, cmd))
        {
            return false;
        }
        if (cmd.ticNumber() > latestLocalTic)
        {
            this.latestLocalTic = cmd.ticNumber();
        }
        return true;
    }

    /**
     * Records the local player's tic from loose fields, allocating nothing.
     *
     * <p>The per-tic path. {@link TicCmd} is immutable, so the overload above
     * builds one object every tic — sixty a second, forever, for a value that is
     * read once and discarded. {@code STYLE.md} § 13.4 bans that on a per-tic
     * path, and {@link TicCmdBuffer} already stores loose fields, so this simply
     * skips the middle.</p>
     *
     * @param ticIndex the tic this input belongs to; must not be negative
     * @param forward forward axis, already quantised — see {@link TicCmdEncoder}
     * @param strafe strafe axis, already quantised
     * @param angle yaw, already quantised
     * @param pitch pitch, already quantised
     * @param buttons the action bitmask
     * @return true if the ring accepted it
     */
    public boolean recordLocalCommand(final int ticIndex, final int forward, final int strafe,
        final int angle, final int pitch, final int buttons)
    {
        if (!commands.put(LOCAL_SLOT, ticIndex, forward, strafe, angle, pitch, buttons))
        {
            return false;
        }
        if (ticIndex > latestLocalTic)
        {
            this.latestLocalTic = ticIndex;
        }
        return true;
    }

    /**
     * Drains everything that has arrived, then sends this tic to every peer.
     *
     * <p><b>Receive first.</b> The acknowledgements in an outgoing packet are
     * read from what we have received, so draining first means this tic's
     * packet acknowledges this tic's arrivals rather than the previous one's.
     * That is one whole tic off the round-trip the sender measures, and it costs
     * nothing but the ordering.</p>
     *
     * @param ticIndex the tic being processed
     * @return how many commands were accepted from peers this tic
     */
    public int tick(final int ticIndex)
    {
        if (!open)
        {
            return 0;
        }
        final int accepted = receiveAll();
        socket.processTic(ticIndex);
        sendToPeers();
        return accepted;
    }

    /**
     * Closes the socket.
     *
     * <p>Idempotent, and safe to call on a session that was never opened —
     * shutdown paths run whether or not startup got that far.</p>
     */
    public void close()
    {
        if (!open)
        {
            return;
        }
        this.open = false;
        socket.close();
        socket.shutdown();
        LOG.info("NetSession closed: {}", this);
    }

    /** Returns everyone's recent commands. The local player is in {@link #LOCAL_SLOT}. */
    public TicCmdBuffer commands()
    {
        return commands;
    }

    /**
     * Returns a connected peer by index.
     *
     * @param index the peer's position, from 0
     * @return the peer's connection state
     */
    public PeerConnection peer(final int index)
    {
        if (index < 0 || index >= peerCount)
        {
            throw new IndexOutOfBoundsException("peer " + index + " of " + peerCount);
        }
        return peers[index];
    }

    /** Returns how many peers are connected. */
    public int peerCount()
    {
        return peerCount;
    }

    /** Returns this peer's identity on the wire. */
    public int localPlayerId()
    {
        return localPlayerId;
    }

    /** Returns the newest tic a local command has been recorded for, or -1. */
    public int latestLocalTic()
    {
        return latestLocalTic;
    }

    /** Returns whether the socket is open. */
    public boolean isOpen()
    {
        return open;
    }

    /** Returns how many datagrams have been sent. */
    public long packetsSent()
    {
        return packetsSent;
    }

    /** Returns how many datagrams have been received and matched to a peer. */
    public long packetsReceived()
    {
        return packetsReceived;
    }

    /** Returns how many datagrams named a player id we do not know. */
    public long packetsFromStrangers()
    {
        return packetsFromStrangers;
    }

    /** Returns how many datagrams were too short to be a packet. */
    public long packetsMalformed()
    {
        return packetsMalformed;
    }

    /** Returns how many commands have been accepted into the ring from peers. */
    public long commandsAccepted()
    {
        return commandsAccepted;
    }

    /** Returns how many bytes have been handed to the socket. */
    public long bytesSent()
    {
        return bytesSent;
    }

    /** Returns how many bytes have been taken from the socket. */
    public long bytesReceived()
    {
        return bytesReceived;
    }

    /**
     * Returns the peer with a given player id, or null.
     *
     * @param peerId the id to look for
     * @return the peer, or null when no peer carries that id
     */
    public PeerConnection peerById(final int peerId)
    {
        for (int index = 0; index < peerCount; index++)
        {
            if (peers[index].peerId() == peerId)
            {
                return peers[index];
            }
        }
        return null;
    }

    // Everything the socket has queued, applied to the ring. The port is
    // non-blocking and returns null when empty, so this drains rather than
    // reading a fixed number: a tic that fell behind may have several packets
    // waiting, and leaving them queued would make the backlog permanent.
    private int receiveAll()
    {
        int accepted = 0;
        byte[] datagram = socket.receive();
        while (datagram != null)
        {
            accepted = accepted + applyDatagram(datagram);
            datagram = socket.receive();
        }
        return accepted;
    }

    // One datagram, matched to its sender and unpacked into that peer's slot.
    private int applyDatagram(final byte[] datagram)
    {
        this.bytesReceived = bytesReceived + datagram.length;
        if (datagram.length < RedundantSender.HEADER_BYTES)
        {
            // Too short to name a sender, so there is nobody to blame and
            // nothing to unpack. Counted rather than logged: on an open port
            // this is what a stray scan looks like, and logging every one would
            // be a log-flooding hole.
            this.packetsMalformed = packetsMalformed + 1;
            return 0;
        }
        final int senderId = RedundantSender.readPlayerId(datagram, 0);
        final int index = peerIndexById(senderId);
        if (index < 0)
        {
            // A packet claiming to be from someone we are not playing with.
            // Dropped rather than auto-connected: adding peers from unsolicited
            // traffic is how a game becomes a reflector.
            this.packetsFromStrangers = packetsFromStrangers + 1;
            return 0;
        }
        this.packetsReceived = packetsReceived + 1;
        final int accepted = RedundantSender.unpack(datagram, 0, datagram.length,
            commands, index + 1, peers[index]);
        this.commandsAccepted = commandsAccepted + accepted;
        return accepted;
    }

    // This tic's window to every peer. Skipped entirely until there is a local
    // command to send — before that, packWindow would be asked for a window
    // ending at tic -1.
    private void sendToPeers()
    {
        if (latestLocalTic < 0)
        {
            return;
        }
        for (int index = 0; index < peerCount; index++)
        {
            final PeerConnection peer = peers[index];
            final int window = peer.redundancyWindow(latestLocalTic, nanosPerTic);
            final int length = RedundantSender.packWindow(sendBuffer, 0, peer, commands,
                LOCAL_SLOT, localPlayerId, latestLocalTic, window);
            socket.send(trimmed(length), peer.address());
            this.packetsSent = packetsSent + 1;
            this.bytesSent = bytesSent + length;
        }
    }

    // The send path wants exactly the bytes written, and I_DatagramPort takes a
    // byte[] rather than an offset and a length — so one copy per packet is
    // unavoidable at this seam. It is a few hundred bytes at 60 Hz per peer;
    // removing it means widening the port contract, which net/README.md
    // records as a change worth making when there is a reason beyond this.
    private byte[] trimmed(final int length)
    {
        final byte[] out = new byte[length];
        System.arraycopy(sendBuffer, 0, out, 0, length);
        return out;
    }

    // The index of a peer by player id, or -1.
    private int peerIndexById(final int peerId)
    {
        for (int index = 0; index < peerCount; index++)
        {
            if (peers[index].peerId() == peerId)
            {
                return index;
            }
        }
        return -1;
    }

    /** Returns a debug rendering of the traffic counters. */
    @Override
    public String toString()
    {
        return "NetSession{player=" + localPlayerId + ", peers=" + peerCount
            + ", sent=" + packetsSent + " (" + bytesSent + " B)"
            + ", received=" + packetsReceived + " (" + bytesReceived + " B)"
            + ", commands=" + commandsAccepted
            + ", strangers=" + packetsFromStrangers
            + ", malformed=" + packetsMalformed + "}";
    }
}
