/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net.port;

/**
 * G_ Port interface — P2P networking.
 * Handles tic command distribution, peer discovery, and snapshot delta.
 *
 * ====================================================================
 *  P2P NETCODE MATH (Phase 3+ — references below)
 * ====================================================================
 *
 *  Full network model documented in
 *  src/main/java/com/openfps/engine/net/README.md. Summary:
 *
 *  1. TIC COMMAND ROUND-TRIP TIME (RTT):
 *       rtt = now - sentTimeAtSend
 *     Smoothing: smoothedRtt = 0.7 * smoothedRtt + 0.3 * rtt
 *     (7-tap moving average; same scheme Quake 3 uses)
 *     Source: qcommon/net_chan.c
 *     https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/net_chan.c
 *
 *  2. PACKET LOSS RATE:
 *       lossRate = missingPackets / totalSent (over 64-packet window)
 *     Use for adaptive throttling — back off send rate if loss > 5%.
 *
 *  3. BANDWIDTH BUDGET:
 *       8 players × 64 bytes/tic × 35 tics/sec = 17,920 bytes/sec
 *     Per peer: 17.92 KB/s up + 17.92 KB/s down = ~36 KB/s total.
 *     Fits in any modern broadband.
 *     For snapshots (Phase 4+), budget grows to ~100 KB/s per peer.
 *
 *  4. LAG COMPENSATION REWIND:
 *       To detect a hit from peer P at time t_now:
 *         rewindTo = t_now - peerP.rtt / 2 - 1 tic
 *         snapshot = stateBuffer[rewindTo]
 *         testBulletAgainst(snapshot)
 *         if hit: applyDamageAt(snapshot.target, currentState.target)
 *     The "1 tic" extra accounts for the time between peer sending
 *     the shot and us processing it.
 *     Source: Gabriel Gambetta, "Lag Compensation"
 *     https://www.gabrielgambetta.com/lag-compensation.html
 *     Also: Vinnie Lee, "Lag Compensation for Real-Time Games"
 *     http://www.vinnieleer.com/articles/lag-compensation-in-real-time-games/
 *
 *  5. LOCKSTEP DETERMINISM RULES:
 *     For all peers to compute the same GameState given the same inputs:
 *       - No HashMap / HashSet — only fixed-order int[] arrays
 *       - No Math.random — use a seeded int PRNG (Mulberry32 or PCG)
 *       - No float accumulation in gameplay — use common.FixedMath
 *       - No System.currentTimeMillis — use I_TimePort
 *       - No Thread.sleep — game loop self-times
 *     Source: net/README.md "Lockstep determinism"
 *     Reference: "1500 Archers on a 28.8" — Mark Terrano / Paul Bettner
 *     https://www.gamedeveloper.com/programming/1500-archers-on-a-288-network-programming-in-age-of-empires-and-beyond
 *
 *  6. SNAPSHOT DELTA BIT-PACKING (Phase 4+):
 *     Bit-mask per entity: which fields are present in this delta.
 *     Only the marked fields follow. Skips zero-delta entities.
 *     Source: Quake 3 net_chan.c
 */
public interface I_NetworkPort
{
    /**
     * Connects to a peer at the given address.
     *
     * @param address "host:port" string
     * @return assigned peer ID, or -1 on failure
     */
    int connect(final String address);

    /**
     * Disconnects the given peer.
     *
     * @param peerId the peer to disconnect
     */
    void disconnect(final int peerId);

    /**
     * Broadcasts the local player's tic command to all connected peers.
     *
     * @param ticIndex the tic number
     * @param cmdBytes serialized TicCmd bytes
     */
    void broadcastTicCmd(final int ticIndex, final byte[] cmdBytes);

    /**
     * Polls for received tic commands from peers.
     * Returns null if no command is available for the given tic.
     *
     * @param ticIndex the tic number
     * @param peerId the peer ID
     * @return TicCmd bytes, or null
     */
    byte[] pollTicCmd(final int ticIndex, final int peerId);

    /**
     * Broadcasts a map-change announcement.
     *
     * @param mapName the name of the new map
     */
    void broadcastMapChange(final String mapName);

    /**
     * Initiates LAN peer discovery via broadcast.
     */
    void discoverPeers();

    /**
     * Returns the number of currently connected peers.
     */
    int connectedPeerCount();

    /**
     * Initializes the networking subsystem.
     */
    void init();

    /**
     * Shuts down the networking subsystem.
     */
    void shutdown();
}
