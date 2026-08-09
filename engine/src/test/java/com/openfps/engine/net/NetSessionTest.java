/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;

import com.openfps.engine.common.Constants;
import com.openfps.engine.hal.port.I_DatagramPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NetSession} over a scripted socket.
 *
 * <p>A fake {@link I_DatagramPort} rather than a real one, so the interesting
 * cases can actually be produced: <b>losing a specific packet</b>, receiving one
 * from a stranger, receiving a truncated one. None of those can be arranged
 * reliably over a real link, and the whole reliability model exists for the
 * first of them. {@code NetSessionLoopbackTest} covers the real socket.</p>
 */
@DisplayName("NetSession")
class NetSessionTest
{
    /** A tic at 60 Hz, which sizes the redundancy window. */
    private static final long TIC_NANOS = 16_666_666L;

    /** The local player's id. */
    private static final int US = 1;

    /** The remote player's id. */
    private static final int THEM = 2;

    /** Where the remote peer is, as far as the fake socket is concerned. */
    private static final String THEIR_ADDRESS = "10.0.0.2:5021";

    /**
     * A socket that keeps everything it is told to send and hands back whatever
     * has been posted to it.
     *
     * <p>The two halves are separate on purpose: {@link #sent} is what this
     * session put on the wire, {@link #inbox} is what it will read next. A test
     * wires one session's {@code sent} into another's {@code inbox} — or
     * deliberately does not, which is how a lost packet is produced.</p>
     */
    private static final class ScriptedSocket implements I_DatagramPort
    {
        /** Every datagram handed to {@link #send}, in order. */
        private final Deque<byte[]> sent = new ArrayDeque<>();

        /** Datagrams waiting to be read by {@link #receive}. */
        private final Deque<byte[]> inbox = new ArrayDeque<>();

        /** Where each datagram was addressed, parallel to {@link #sent}. */
        private final Deque<String> destinations = new ArrayDeque<>();

        /** True between init and shutdown. */
        private boolean live;

        /** The port passed to bind, or -1. */
        private int boundPort = -1;

        @Override
        public void init()
        {
            live = true;
        }

        @Override
        public void bind(final int port)
        {
            boundPort = port;
        }

        @Override
        public void send(final byte[] data, final String address)
        {
            sent.addLast(data.clone());

            destinations.addLast(address);
        }

        @Override
        public byte[] receive()
        {
            return inbox.pollFirst();
        }

        @Override
        public void processTic(final int ticIndex)
        {
            // nothing queued at this level
        }

        @Override
        public void close()
        {
            live = false;
        }

        @Override
        public void shutdown()
        {
            live = false;
        }

        /** Moves everything this socket sent into another's inbox. */
        private void deliverTo(final ScriptedSocket other)
        {
            while (!sent.isEmpty())
            {
                other.inbox.addLast(sent.pollFirst());

                destinations.pollFirst();
            }
        }

        /** Throws away everything this socket sent — a total outage. */
        private void dropAll()
        {
            sent.clear();

            destinations.clear();
        }
    }

    // A command with recognisable, tic-dependent fields.
    private static TicCmd commandFor(final int tic)
    {
        return new TicCmd(tic, tic % 100, -(tic % 50), tic * 7 % TicCmd.MAX_ANGLE,
            tic % 60, tic % 8);
    }

    @Nested
    @DisplayName("a session with one peer")
    class OnePeer
    {
        @Test
        @DisplayName("delivers every tic when nothing is lost")
        void shouldDeliverEveryTicWhenTheLinkIsClean()
        {
            final ScriptedSocket ourSocket = new ScriptedSocket();

            final ScriptedSocket theirSocket = new ScriptedSocket();

            final NetSession us = new NetSession(ourSocket, US, TIC_NANOS);

            final NetSession them = new NetSession(theirSocket, THEM, TIC_NANOS);

            us.open(0);

            them.open(0);

            us.addPeer(THEM, THEIR_ADDRESS);

            them.addPeer(US, "10.0.0.1:5021");

            for (int tic = 0; tic < 30; tic++)
            {
                us.recordLocalCommand(commandFor(tic));

                us.tick(tic);

                ourSocket.deliverTo(theirSocket);

                them.tick(tic);
            }

            for (int tic = 0; tic < 30; tic++)
            {
                assertThat(them.commands().has(1, tic))
                    .as("tic %d never arrived", tic)
                    .isTrue();

                assertThat(them.commands().forward(1, tic)).isEqualTo(commandFor(tic).forward());
            }
        }

        @Test
        @DisplayName("a lost packet costs nothing — the next one carries what it did")
        void shouldRecoverALostPacketFromTheNextOne()
        {
            // The entire point of the protocol. Under lockstep a peer must
            // consume every input, so a dropped packet cannot be skipped; but
            // waiting for a retransmission costs a round trip, which at 60 Hz is
            // several tics of frozen game. A command is twelve bytes, so
            // re-sending the last few every time makes the loss free.
            final ScriptedSocket ourSocket = new ScriptedSocket();

            final ScriptedSocket theirSocket = new ScriptedSocket();

            final NetSession us = new NetSession(ourSocket, US, TIC_NANOS);

            final NetSession them = new NetSession(theirSocket, THEM, TIC_NANOS);

            us.open(0);

            them.open(0);

            us.addPeer(THEM, THEIR_ADDRESS);

            them.addPeer(US, "10.0.0.1:5021");

            final int lostTic = 5;

            for (int tic = 0; tic < 20; tic++)
            {
                us.recordLocalCommand(commandFor(tic));

                us.tick(tic);

                if (tic == lostTic)
                {
                    ourSocket.dropAll();
                }
                else
                {
                    ourSocket.deliverTo(theirSocket);
                }

                them.tick(tic);
            }

            // The packet carrying tic 5 never arrived. Tic 5 did.
            assertThat(them.commands().has(1, lostTic))
                .as("the redundancy window did not cover the lost tic")
                .isTrue();

            assertThat(them.commands().forward(1, lostTic))
                .isEqualTo(commandFor(lostTic).forward());
        }

        @Test
        @DisplayName("survives a run of consecutive losses within the window")
        void shouldRecoverABurstWithinTheWindow()
        {
            final ScriptedSocket ourSocket = new ScriptedSocket();

            final ScriptedSocket theirSocket = new ScriptedSocket();

            final NetSession us = new NetSession(ourSocket, US, TIC_NANOS);

            final NetSession them = new NetSession(theirSocket, THEM, TIC_NANOS);

            us.open(0);

            them.open(0);

            us.addPeer(THEM, THEIR_ADDRESS);

            them.addPeer(US, "10.0.0.1:5021");

            for (int tic = 0; tic < 40; tic++)
            {
                us.recordLocalCommand(commandFor(tic));

                us.tick(tic);

                if (tic >= 10 && tic <= 12)
                {
                    ourSocket.dropAll();
                }
                else
                {
                    ourSocket.deliverTo(theirSocket);
                }

                them.tick(tic);
            }

            for (int tic = 10; tic <= 12; tic++)
            {
                assertThat(them.commands().has(1, tic))
                    .as("tic %d was in a burst of three and did not survive", tic)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("both ends see the traffic they expect")
        void shouldCountTrafficWhenExchanging()
        {
            final ScriptedSocket ourSocket = new ScriptedSocket();

            final ScriptedSocket theirSocket = new ScriptedSocket();

            final NetSession us = new NetSession(ourSocket, US, TIC_NANOS);

            final NetSession them = new NetSession(theirSocket, THEM, TIC_NANOS);

            us.open(0);

            them.open(0);

            us.addPeer(THEM, THEIR_ADDRESS);

            them.addPeer(US, "10.0.0.1:5021");

            for (int tic = 0; tic < 10; tic++)
            {
                us.recordLocalCommand(commandFor(tic));

                us.tick(tic);

                ourSocket.deliverTo(theirSocket);

                them.tick(tic);
            }

            assertThat(us.packetsSent()).isEqualTo(10);

            assertThat(them.packetsReceived()).isEqualTo(10);

            assertThat(them.commandsAccepted()).isGreaterThanOrEqualTo(10);

            assertThat(us.bytesSent()).isPositive();

            assertThat(them.bytesReceived()).isEqualTo(us.bytesSent());
        }

        @Test
        @DisplayName("sends nothing until there is a local command to send")
        void shouldSendNothingBeforeTheFirstCommand()
        {
            // packWindow would otherwise be asked for a window ending at tic -1.
            final ScriptedSocket ourSocket = new ScriptedSocket();

            final NetSession us = new NetSession(ourSocket, US, TIC_NANOS);

            us.open(0);

            us.addPeer(THEM, THEIR_ADDRESS);

            us.tick(0);

            us.tick(1);

            assertThat(us.packetsSent()).isZero();

            assertThat(us.latestLocalTic()).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("packets it should not act on")
    class Hostile
    {
        @Test
        @DisplayName("drops a packet from a player id it does not know")
        void shouldDropAPacketFromAStranger()
        {
            // Counted, not auto-connected. Adding peers from unsolicited traffic
            // is how a game becomes a reflector for someone else's traffic.
            final ScriptedSocket ourSocket = new ScriptedSocket();

            final ScriptedSocket strangerSocket = new ScriptedSocket();

            final NetSession us = new NetSession(ourSocket, US, TIC_NANOS);

            final NetSession stranger = new NetSession(strangerSocket, 99, TIC_NANOS);

            us.open(0);

            stranger.open(0);

            stranger.addPeer(US, "10.0.0.1:5021");

            stranger.recordLocalCommand(commandFor(0));

            stranger.tick(0);

            strangerSocket.deliverTo(ourSocket);

            us.tick(0);

            assertThat(us.packetsFromStrangers()).isEqualTo(1);

            assertThat(us.packetsReceived()).isZero();

            assertThat(us.commandsAccepted()).isZero();
        }

        @Test
        @DisplayName("drops a datagram too short to hold a header")
        void shouldDropATruncatedDatagram()
        {
            // On an open UDP port this is what a stray scan looks like. It must
            // not throw, and it must not be logged per packet either — that
            // would be a log-flooding hole.
            final ScriptedSocket ourSocket = new ScriptedSocket();

            final NetSession us = new NetSession(ourSocket, US, TIC_NANOS);

            us.open(0);

            us.addPeer(THEM, THEIR_ADDRESS);

            ourSocket.inbox.addLast(new byte[] { 1, 2, 3 });

            ourSocket.inbox.addLast(new byte[0]);

            us.tick(0);

            assertThat(us.packetsMalformed()).isEqualTo(2);

            assertThat(us.packetsReceived()).isZero();
        }

        @Test
        @DisplayName("drains everything queued rather than one packet a tic")
        void shouldDrainTheWholeQueueEachTic()
        {
            // A tic that fell behind may have several packets waiting. Reading
            // one per tic would make the backlog permanent and the game
            // permanently behind.
            final ScriptedSocket ourSocket = new ScriptedSocket();

            final ScriptedSocket theirSocket = new ScriptedSocket();

            final NetSession us = new NetSession(ourSocket, US, TIC_NANOS);

            final NetSession them = new NetSession(theirSocket, THEM, TIC_NANOS);

            us.open(0);

            them.open(0);

            us.addPeer(THEM, THEIR_ADDRESS);

            them.addPeer(US, "10.0.0.1:5021");

            for (int tic = 0; tic < 5; tic++)
            {
                them.recordLocalCommand(commandFor(tic));

                them.tick(tic);
            }

            theirSocket.deliverTo(ourSocket);

            us.tick(5);

            assertThat(us.packetsReceived()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("session setup")
    class Setup
    {
        @Test
        @DisplayName("refuses a peer that claims our own id")
        void shouldRefuseAPeerWithOurOwnId()
        {
            final NetSession us = new NetSession(new ScriptedSocket(), US, TIC_NANOS);

            assertThatThrownBy(() -> us.addPeer(US, THEIR_ADDRESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("our own");
        }

        @Test
        @DisplayName("refuses a duplicate peer id")
        void shouldRefuseADuplicatePeer()
        {
            // Two peers on one id would share a buffer slot and overwrite each
            // other's inputs, which reads as one peer teleporting.
            final NetSession us = new NetSession(new ScriptedSocket(), US, TIC_NANOS);

            us.addPeer(THEM, THEIR_ADDRESS);

            assertThatThrownBy(() -> us.addPeer(THEM, "10.0.0.3:5021"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already connected");
        }

        @Test
        @DisplayName("holds one peer per player slot but its own")
        void shouldFillEverySlotButOurOwn()
        {
            final NetSession us = new NetSession(new ScriptedSocket(), 0, TIC_NANOS);

            for (int peer = 1; peer <= NetSession.MAX_PEERS; peer++)
            {
                assertThat(us.addPeer(peer, "10.0.0." + peer)).isEqualTo(peer);
            }

            assertThat(us.peerCount()).isEqualTo(Constants.MAX_PLAYERS - 1);

            assertThatThrownBy(() -> us.addPeer(99, "10.0.0.99"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("puts the local player in slot 0 and peers above it")
        void shouldReserveSlotZeroForTheLocalPlayer()
        {
            final NetSession us = new NetSession(new ScriptedSocket(), US, TIC_NANOS);

            assertThat(NetSession.LOCAL_SLOT).isZero();

            assertThat(us.addPeer(THEM, THEIR_ADDRESS)).isEqualTo(1);

            assertThat(us.addPeer(3, "10.0.0.3")).isEqualTo(2);
        }

        @Test
        @DisplayName("looks a peer up by its player id")
        void shouldFindAPeerById()
        {
            final NetSession us = new NetSession(new ScriptedSocket(), US, TIC_NANOS);

            us.addPeer(THEM, THEIR_ADDRESS);

            assertThat(us.peerById(THEM)).isNotNull();

            assertThat(us.peerById(THEM).address()).isEqualTo(THEIR_ADDRESS);

            assertThat(us.peerById(4242)).isNull();
        }

        @Test
        @DisplayName("rejects a null socket, a negative id and a non-positive tic")
        void shouldRejectBadConstruction()
        {
            assertThatThrownBy(() -> new NetSession(null, US, TIC_NANOS))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new NetSession(new ScriptedSocket(), -1, TIC_NANOS))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new NetSession(new ScriptedSocket(), US, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a session that was never opened sends nothing and closes cleanly")
        void shouldBeInertUntilOpened()
        {
            // Shutdown paths run whether or not startup got that far.
            final ScriptedSocket socket = new ScriptedSocket();

            final NetSession us = new NetSession(socket, US, TIC_NANOS);

            us.addPeer(THEM, THEIR_ADDRESS);

            us.recordLocalCommand(commandFor(0));

            assertThat(us.isOpen()).isFalse();

            assertThat(us.tick(0)).isZero();

            assertThat(us.packetsSent()).isZero();

            us.close();

            us.close();
        }

        @Test
        @DisplayName("opening binds the port it was given")
        void shouldBindTheGivenPort()
        {
            final ScriptedSocket socket = new ScriptedSocket();

            new NetSession(socket, US, TIC_NANOS).open(NetSession.DEFAULT_PORT);

            assertThat(socket.boundPort).isEqualTo(Constants.DEFAULT_NET_PORT);

            assertThat(socket.live).isTrue();
        }
    }
}
