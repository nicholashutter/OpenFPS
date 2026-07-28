/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.hal.adapter.desktop.DesktopDatagramPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two {@link NetSession}s exchanging tics over <b>real UDP sockets</b> on the
 * loopback interface.
 *
 * <h2>Why a real socket, when a scripted one covers more cases</h2>
 *
 * <p>{@code NetSessionTest} can produce a lost packet and a hostile one, and a
 * real socket cannot. But it also cannot fail in any of the ways a real socket
 * fails, and every one of those is a way this feature could be broken while
 * every unit test passes:</p>
 *
 * <ul>
 *   <li>the address string this code builds is not the one
 *       {@code DesktopDatagramPort} parses;</li>
 *   <li>the receive buffer is smaller than a full window and silently
 *       truncates;</li>
 *   <li>the channel is left blocking, so the first empty read hangs the game
 *       loop instead of returning null;</li>
 *   <li>bind and send disagree about which address family they are on.</li>
 * </ul>
 *
 * <p>None of those is exotic and none is visible without a socket. This test
 * opens two, on ephemeral ports so it never collides with a running game or with
 * another copy of itself in a parallel build.</p>
 *
 * <h2>It does not sleep</h2>
 *
 * <p>Loopback UDP is delivered by the kernel before {@code send} returns, so a
 * receive immediately afterwards finds the datagram waiting. A test that slept
 * "to let the network settle" would be slower and would still be a race — the
 * fix for a flaky network test is not a longer sleep, it is a loop that keeps
 * ticking until the expected state arrives or a bounded number of tics have
 * passed. That is what {@link #TICS} is.</p>
 */
@DisplayName("NetSession over real UDP")
class NetSessionLoopbackTest
{
    /** A tic at 60 Hz. */
    private static final long TIC_NANOS = 16_666_666L;

    /** The first peer's id. */
    private static final int ALICE = 1;

    /** The second peer's id. */
    private static final int BOB = 2;

    /**
     * How many tics to exchange — comfortably more than the redundancy window,
     * so a tic that only arrives as a re-delivery still has room to.
     */
    private static final int TICS = 40;

    // A command with recognisable, tic-dependent fields.
    private static TicCmd commandFor(final int tic, final int who)
    {
        return new TicCmd(tic, tic + who, -(tic + who), (tic * 13 + who) % TicCmd.MAX_ANGLE,
            tic % 40, who);
    }

    @Test
    @DisplayName("two processes' worth of sessions exchange every tic")
    void shouldExchangeEveryTicOverLoopback()
    {
        final DesktopDatagramPort aliceSocket = new DesktopDatagramPort();
        final DesktopDatagramPort bobSocket = new DesktopDatagramPort();
        final NetSession alice = new NetSession(aliceSocket, ALICE, TIC_NANOS);
        final NetSession bob = new NetSession(bobSocket, BOB, TIC_NANOS);
        try
        {
            // Port 0 asks the OS for a free one, which is what lets this run
            // alongside a real game and alongside itself.
            alice.open(0);
            bob.open(0);
            final int alicePort = aliceSocket.localPort();
            final int bobPort = bobSocket.localPort();
            assertThat(alicePort).as("the OS gave us no port").isPositive();
            assertThat(bobPort).isPositive();
            assertThat(bobPort).isNotEqualTo(alicePort);

            alice.addPeer(BOB, "127.0.0.1:" + bobPort);
            bob.addPeer(ALICE, "127.0.0.1:" + alicePort);

            for (int tic = 0; tic < TICS; tic++)
            {
                alice.recordLocalCommand(commandFor(tic, ALICE));
                bob.recordLocalCommand(commandFor(tic, BOB));
                alice.tick(tic);
                bob.tick(tic);
            }
            // One more pass with no new input, so the last tic each side sent
            // has somewhere to be received.
            alice.tick(TICS);
            bob.tick(TICS);

            // TICS + 1: the drain pass above also sends, because a session with
            // a local command keeps re-offering its window rather than going
            // quiet the moment the input stops. That is the redundancy model
            // working, not an off-by-one.
            assertThat(alice.packetsSent()).isEqualTo(TICS + 1L);
            assertThat(bob.packetsReceived()).isPositive();
            assertThat(alice.packetsReceived()).isPositive();
            assertThat(alice.packetsMalformed()).isZero();
            assertThat(alice.packetsFromStrangers()).isZero();

            // Every tic either side sent must be readable on the other, with
            // the values it was given. Bit-exact rather than "something
            // arrived": a quantisation bug in TicCmd would pass the latter.
            for (int tic = 0; tic < TICS; tic++)
            {
                assertThat(bob.commands().has(1, tic))
                    .as("Bob never received Alice's tic %d", tic)
                    .isTrue();
                assertThat(bob.commands().forward(1, tic))
                    .isEqualTo(commandFor(tic, ALICE).forward());
                assertThat(bob.commands().buttons(1, tic))
                    .isEqualTo(commandFor(tic, ALICE).buttons());

                assertThat(alice.commands().has(1, tic))
                    .as("Alice never received Bob's tic %d", tic)
                    .isTrue();
                assertThat(alice.commands().strafe(1, tic))
                    .isEqualTo(commandFor(tic, BOB).strafe());
            }
        }
        finally
        {
            alice.close();
            bob.close();
        }
    }

    @Test
    @DisplayName("each side ends up acknowledging the other, so round-trip state is live")
    void shouldAcknowledgeAcrossTheLink()
    {
        // The acks are what size the redundancy window and what a loss estimate
        // is built from. If they never travelled, the protocol would still look
        // like it worked — every tic would arrive — and would degrade badly the
        // first time a real link dropped anything.
        final DesktopDatagramPort aliceSocket = new DesktopDatagramPort();
        final DesktopDatagramPort bobSocket = new DesktopDatagramPort();
        final NetSession alice = new NetSession(aliceSocket, ALICE, TIC_NANOS);
        final NetSession bob = new NetSession(bobSocket, BOB, TIC_NANOS);
        try
        {
            alice.open(0);
            bob.open(0);
            alice.addPeer(BOB, "127.0.0.1:" + bobSocket.localPort());
            bob.addPeer(ALICE, "127.0.0.1:" + aliceSocket.localPort());

            for (int tic = 0; tic < TICS; tic++)
            {
                alice.recordLocalCommand(commandFor(tic, ALICE));
                bob.recordLocalCommand(commandFor(tic, BOB));
                alice.tick(tic);
                bob.tick(tic);
            }

            assertThat(alice.peerById(BOB).remoteAckedTic())
                .as("Bob never acknowledged anything Alice sent")
                .isGreaterThanOrEqualTo(0);
            assertThat(bob.peerById(ALICE).remoteAckedTic())
                .isGreaterThanOrEqualTo(0);
            assertThat(alice.peerById(BOB).ackWindow().highestContiguousTic())
                .as("a clean loopback link should have no gaps")
                .isGreaterThan(TICS / 2);
        }
        finally
        {
            alice.close();
            bob.close();
        }
    }

    @Test
    @DisplayName("a receive on an idle socket returns nothing rather than blocking")
    void shouldNotBlockOnAnIdleSocket()
    {
        // If the channel were left blocking, this call would hang the game loop
        // forever and the test would time out rather than fail. That is the
        // failure mode worth having a test for: a hang is much harder to
        // diagnose from a bug report than a wrong value.
        final DesktopDatagramPort socket = new DesktopDatagramPort();
        final NetSession lonely = new NetSession(socket, ALICE, TIC_NANOS);
        try
        {
            lonely.open(0);
            lonely.recordLocalCommand(commandFor(0, ALICE));

            assertThat(lonely.tick(0)).isZero();
            assertThat(lonely.packetsReceived()).isZero();
            // No peers, so nothing was sent either — a host waiting for someone
            // to join is a legitimate state, not an error.
            assertThat(lonely.packetsSent()).isZero();
        }
        finally
        {
            lonely.close();
        }
    }
}
