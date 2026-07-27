/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.desktop;

import com.openfps.engine.common.Constants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DesktopDatagramPort}.
 *
 * These bind real sockets, but only on the loopback interface and only on
 * OS-assigned ephemeral ports (bind 0), so two runs can never collide and
 * nothing leaves the machine.
 *
 * Determinism: the receive path is non-blocking, so a round-trip test has
 * to poll. It polls against a deadline read from {@link DesktopTimePort}
 * rather than sleeping a fixed amount — a loopback datagram is delivered
 * by the kernel essentially immediately, so the deadline exists only to
 * fail the test rather than hang it if delivery never happens.
 */
class DesktopDatagramPortTest
{
    /** Ask the OS for an ephemeral port. */
    private static final int EPHEMERAL_PORT = 0;

    /** How long to poll for a loopback datagram before declaring failure. */
    private static final long RECEIVE_DEADLINE_NANOS = 5_000_000_000L;

    /** Loopback host for every address built here. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Fixed payload — no unseeded randomness anywhere in this suite. */
    private static final byte[] PAYLOAD = "openfps-tic-0".getBytes(StandardCharsets.UTF_8);

    private DesktopTimePort clock;

    @BeforeEach
    void setUp()
    {
        clock = new DesktopTimePort();
        clock.init();
    }

    @AfterEach
    void tearDown()
    {
        clock.shutdown();
    }

    // Polls the non-blocking port until a datagram arrives or the deadline
    // passes. Returns null on timeout so the caller can assert on it.
    private byte[] receiveWithin(final DesktopDatagramPort port)
    {
        final long deadline = clock.nanos() + RECEIVE_DEADLINE_NANOS;
        while (clock.nanos() < deadline)
        {
            final byte[] payload = port.receive();
            if (payload != null)
            {
                return payload;
            }
            Thread.onSpinWait();
        }
        return null;
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle
    {
        @Test
        @DisplayName("init() opens a socket and bind(0) yields a real ephemeral port")
        void shouldBindToAnEphemeralPort()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            port.init();
            try
            {
                assertThat(port.localPort()).isEqualTo(-1);
                port.bind(EPHEMERAL_PORT);
                assertThat(port.localPort()).isGreaterThan(0);
            }
            finally
            {
                port.shutdown();
            }
        }

        @Test
        @DisplayName("localPort() is -1 before init() and again after shutdown()")
        void shouldReportNoLocalPortWhenClosed()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            assertThat(port.localPort()).isEqualTo(-1);

            port.init();
            port.bind(EPHEMERAL_PORT);
            port.shutdown();

            assertThat(port.localPort()).isEqualTo(-1);
        }

        @Test
        @DisplayName("init() twice is rejected")
        void shouldRejectDoubleInit()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            port.init();
            try
            {
                assertThatThrownBy(port::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("twice");
            }
            finally
            {
                port.shutdown();
            }
        }

        @Test
        @DisplayName("close() is idempotent and shutdown() after close() does not throw")
        void shouldTolerateRepeatedClose()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            port.init();
            port.close();
            port.close();
            port.shutdown();

            assertThat(port.localPort()).isEqualTo(-1);
        }

        @Test
        @DisplayName("bind() before init() is rejected")
        void shouldRejectBindBeforeInit()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            assertThatThrownBy(() -> port.bind(EPHEMERAL_PORT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not open");
        }
    }

    @Nested
    @DisplayName("receive")
    class Receive
    {
        @Test
        @DisplayName("receive() returns null when nothing is queued — non-blocking")
        void shouldReturnNullWhenNoDatagramIsQueued()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            port.init();
            try
            {
                port.bind(EPHEMERAL_PORT);
                assertThat(port.receive()).isNull();
            }
            finally
            {
                port.shutdown();
            }
        }

        @Test
        @DisplayName("receive() returns null on a closed port instead of throwing")
        void shouldReturnNullWhenClosed()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            port.init();
            port.shutdown();

            assertThat(port.receive()).isNull();
        }
    }

    @Nested
    @DisplayName("send")
    class Send
    {
        @Test
        @DisplayName("send() rejects a null payload")
        void shouldRejectNullPayload()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            port.init();
            try
            {
                assertThatThrownBy(() -> port.send(null, LOOPBACK + ":" + Constants.DEFAULT_NET_PORT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
            }
            finally
            {
                port.shutdown();
            }
        }

        @Test
        @DisplayName("send() rejects a blank destination address")
        void shouldRejectBlankAddress()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            port.init();
            try
            {
                assertThatThrownBy(() -> port.send(PAYLOAD, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or blank");
            }
            finally
            {
                port.shutdown();
            }
        }

        @Test
        @DisplayName("send() rejects a destination whose port is not a number")
        void shouldRejectMalformedAddress()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            port.init();
            try
            {
                assertThatThrownBy(() -> port.send(PAYLOAD, LOOPBACK + ":not-a-port"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected host:port");
            }
            finally
            {
                port.shutdown();
            }
        }

        @Test
        @DisplayName("send() before init() is rejected")
        void shouldRejectSendBeforeInit()
        {
            final DesktopDatagramPort port = new DesktopDatagramPort();
            assertThatThrownBy(() -> port.send(PAYLOAD, LOOPBACK + ":" + Constants.DEFAULT_NET_PORT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not open");
        }
    }

    @Test
    @DisplayName("a datagram sent over loopback round-trips byte-for-byte")
    void shouldRoundTripADatagramOverLoopback()
    {
        final DesktopDatagramPort receiver = new DesktopDatagramPort();
        final DesktopDatagramPort sender = new DesktopDatagramPort();
        receiver.init();
        sender.init();
        try
        {
            receiver.bind(EPHEMERAL_PORT);
            sender.bind(EPHEMERAL_PORT);

            sender.send(PAYLOAD, LOOPBACK + ":" + receiver.localPort());

            final byte[] received = receiveWithin(receiver);
            assertThat(received).isNotNull();
            assertThat(received).isEqualTo(PAYLOAD);
            assertThat(received).hasSize(PAYLOAD.length);
        }
        finally
        {
            sender.shutdown();
            receiver.shutdown();
        }
    }

    @Test
    @DisplayName("two datagrams round-trip in order, and the reused buffer does not bleed")
    void shouldRoundTripTwoDatagramsWithoutBufferBleed()
    {
        final byte[] longFirst = "a-much-longer-first-datagram".getBytes(StandardCharsets.UTF_8);
        final byte[] shortSecond = "short".getBytes(StandardCharsets.UTF_8);

        final DesktopDatagramPort receiver = new DesktopDatagramPort();
        final DesktopDatagramPort sender = new DesktopDatagramPort();
        receiver.init();
        sender.init();
        try
        {
            receiver.bind(EPHEMERAL_PORT);
            sender.bind(EPHEMERAL_PORT);
            final String destination = LOOPBACK + ":" + receiver.localPort();

            sender.send(longFirst, destination);
            final byte[] first = receiveWithin(receiver);
            assertThat(first).isEqualTo(longFirst);

            // The second datagram is shorter. If the reused direct buffer
            // were not cleared and flipped correctly, the tail of the
            // first payload would leak into this one.
            sender.send(shortSecond, destination);
            final byte[] second = receiveWithin(receiver);
            assertThat(second).isEqualTo(shortSecond);
            assertThat(second).hasSize(shortSecond.length);
        }
        finally
        {
            sender.shutdown();
            receiver.shutdown();
        }
    }

    @Test
    @DisplayName("processTic() is a no-op that leaves the socket usable")
    void shouldTreatProcessTicAsANoOp()
    {
        final DesktopDatagramPort port = new DesktopDatagramPort();
        port.init();
        try
        {
            port.bind(EPHEMERAL_PORT);
            final int boundPort = port.localPort();
            port.processTic(0);

            assertThat(port.localPort()).isEqualTo(boundPort);
            assertThat(port.receive()).isNull();
        }
        finally
        {
            port.shutdown();
        }
    }
}
