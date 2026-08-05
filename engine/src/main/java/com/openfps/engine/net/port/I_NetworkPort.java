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
 *     (an EWMA — one long of state; the same scheme Quake 3 uses)
 *     Source: qcommon/net_chan.c
 *     https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/net_chan.c
 *
 *  2. PACKET LOSS RATE:
 *       lossRate = missingPackets / totalSent (over 64-packet window)
 *     Use for adaptive throttling — back off send rate if loss > 5%.
 *
 *  3. BANDWIDTH BUDGET:
 *     See README.md "Transport decision" section 6. The figures are
 *     deliberately NOT duplicated here — the copy that used to live in this
 *     Javadoc drifted from the README and understated the real cost by
 *     omitting the 28-byte IPv4+UDP header and the redundancy window.
 *
 *  4. LAG COMPENSATION REWIND (Phase 4+, NOT Phase 3):
 *     Under pure lockstep there is nothing to rewind — every peer simulates
 *     identical inputs at identical tics, so hit results already agree.
 *     The rewind below applies once prediction/snapshot exists.
 *       To detect a hit from peer P at time t_now:
 *         rewindTo = t_now - peerP.rtt / 2 - 1 tic
 *         snapshot = stateBuffer[rewindTo]
 *         testBulletAgainst(snapshot)
 *         if hit: applyDamageAt(snapshot.target, currentState.target)
 *     The "1 tic" extra accounts for the time between peer sending
 *     the shot and us processing it.
 *     Source: Gabriel Gambetta, "Lag Compensation"
 *     https://www.gabrielgambetta.com/lag-compensation.html
 *     Also: Yahn Bernier, "Latency Compensating Methods in Client/Server
 *     In-game Protocol Design and Optimization" (GDC 2001)
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
 *
 *  7. RELIABILITY MODEL — REDUNDANCY, NOT RETRANSMISSION:
 *     Transport is UDP. Lockstep needs every input, but retransmit-on-timeout
 *     costs 2 RTT to recover a loss. Instead every packet carries all cmds
 *     since the peer's last ack, so a lost packet is covered by the next one
 *     at zero added latency. Ack = highest contiguous tic + a 64-bit bitfield
 *     (width == Constants.TIC_BUFFER_SIZE).
 *     See README.md "Transport decision" sections 3-4 for why TCP cannot do
 *     this, and section 5 for where dropping stale packets IS correct.
 *     Source: Glenn Fiedler, "Deterministic Lockstep"
 *     https://gafferongames.com/post/deterministic_lockstep/
 */
public interface I_NetworkPort
{
    /**
     * Initializes the networking subsystem.
     */
    void init();

    /**
     * Shuts down the networking subsystem.
     */
    void shutdown();
}
