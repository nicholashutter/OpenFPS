/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.desktop;

import com.openfps.engine.common.Constants;
import com.openfps.engine.hal.port.I_DatagramPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/**
 * Desktop implementation of {@link I_DatagramPort} on
 * {@link DatagramChannel} — pure JDK, no native dependency.
 *
 * <b>Non-blocking.</b> The channel is put in non-blocking mode at
 * {@link #init()}, so {@link #receive()} returns null the instant nothing
 * is queued rather than parking the game loop.
 *
 * <b>Why direct buffers.</b> Both scratch buffers are allocated ONCE at
 * {@link #init()} and reused for every call. They are
 * {@link ByteBuffer#allocateDirect direct} on purpose: when a heap buffer
 * is handed to a channel, the JDK allocates a temporary direct buffer,
 * copies through it, and then copies back — on every single I/O call. At
 * tic rate that is pure garbage. Direct buffers hand the socket the
 * address of the memory and skip both copies.
 *
 * Send and receive get separate buffers so a send from the network thread
 * cannot corrupt an in-flight receive on the game loop.
 *
 * <b>Allocation.</b> Steady-state receive of nothing allocates zero bytes.
 * The one unavoidable allocation is the exact-sized {@code byte[]} handed
 * back by {@link #receive()} when a datagram actually arrives — the port
 * signature returns {@code byte[]}, and this HAL adapter is the boundary
 * where raw platform bytes enter the engine, the same sanctioned-adapter
 * carve-out that lets the time port call {@code System.nanoTime()}.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class DesktopDatagramPort implements I_DatagramPort
{
    private static final Logger LOG = LoggerFactory.getLogger(DesktopDatagramPort.class);

    /**
     * Scratch buffer size in bytes: the largest payload a single UDP
     * datagram can carry over IPv4 (65535 total minus the 20-byte IP
     * header and the 8-byte UDP header). Sized once so no datagram the
     * network can legally deliver is ever truncated.
     */
    private static final int MAX_DATAGRAM_BYTES = 65507;

    /** Loopback host used when an address string carries no host part. */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    /** Reused receive scratch. MUTABLE: rewound and refilled every receive(). */
    private ByteBuffer receiveBuffer;

    /** Reused send scratch. MUTABLE: rewound and refilled every send(). */
    private ByteBuffer sendBuffer;

    /** The open socket. MUTABLE: assigned at init(), nulled at close(). */
    private DatagramChannel channel;

    @Override
    public void init()
    {
        if (channel != null)
        {
            throw new IllegalStateException("init() called twice on DesktopDatagramPort");
        }

        receiveBuffer = ByteBuffer.allocateDirect(MAX_DATAGRAM_BYTES);

        sendBuffer = ByteBuffer.allocateDirect(MAX_DATAGRAM_BYTES);

        try
        {
            channel = DatagramChannel.open();

            channel.configureBlocking(false);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("Failed to open UDP datagram channel", e);
        }

        LOG.info("DesktopDatagramPort initialized: non-blocking, {}-byte direct buffers",
            MAX_DATAGRAM_BYTES);
    }

    @Override
    public void bind(final int port)
    {
        requireOpen();

        try
        {
            channel.bind(new InetSocketAddress(port));

            LOG.info("DesktopDatagramPort bound to local port {}", localPort());
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("Failed to bind UDP socket to port " + port, e);
        }
    }

    @Override
    public void send(final byte[] data, final String address)
    {
        if (data == null)
        {
            throw new IllegalArgumentException("data must not be null");
        }

        if (data.length > MAX_DATAGRAM_BYTES)
        {
            throw new IllegalArgumentException("datagram too large: " + data.length
                + " bytes, max is " + MAX_DATAGRAM_BYTES);
        }

        requireOpen();

        sendBuffer.clear();

        sendBuffer.put(data);

        sendBuffer.flip();

        try
        {
            channel.send(sendBuffer, parseAddress(address));
        }
        catch (final IOException e)
        {
            LOG.warn("DesktopDatagramPort: send of {} bytes to {} failed",
                data.length, address, e);
        }
    }

    @Override
    public byte[] receive()
    {
        if (channel == null || !channel.isOpen())
        {
            return null;
        }

        receiveBuffer.clear();

        final SocketAddress from;

        try
        {
            from = channel.receive(receiveBuffer);
        }
        catch (final IOException e)
        {
            LOG.warn("DesktopDatagramPort: receive failed", e);

            return null;
        }

        if (from == null)
        {
            // Nothing queued — the steady-state path. Zero allocation.
            return null;
        }

        receiveBuffer.flip();

        final int length = receiveBuffer.remaining();

        // Sanctioned adapter allocation: I_DatagramPort#receive() returns
        // byte[], so the payload has to leave the reused direct buffer as
        // an exact-sized array. See the class Javadoc.
        final byte[] payload = new byte[length];

        receiveBuffer.get(payload);

        LOG.debug("DesktopDatagramPort: received {} bytes from {}", length, from);

        return payload;
    }

    @Override
    public void processTic(final int ticIndex)
    {
        // Nothing to pump: the socket is non-blocking and the G_ networking
        // subsystem drains it by calling receive() until it returns null.
    }

    @Override
    public void close()
    {
        if (channel == null)
        {
            return;
        }

        try
        {
            channel.close();
        }
        catch (final IOException e)
        {
            LOG.warn("DesktopDatagramPort: error closing UDP socket", e);
        }

        channel = null;
    }

    @Override
    public void shutdown()
    {
        close();

        receiveBuffer = null;

        sendBuffer = null;

        LOG.info("DesktopDatagramPort shut down");
    }

    /**
     * Returns the local UDP port the socket is bound to, or -1 when it is
     * closed or has never been bound. Mainly useful after
     * {@code bind(0)}, which asks the OS for an ephemeral port.
     *
     * @return the bound local port, or -1 if unbound
     */
    public int localPort()
    {
        if (channel == null || !channel.isOpen())
        {
            return -1;
        }

        try
        {
            final SocketAddress local = channel.getLocalAddress();

            if (local instanceof InetSocketAddress)
            {
                return ((InetSocketAddress) local).getPort();
            }

            return -1;
        }
        catch (final IOException e)
        {
            LOG.warn("DesktopDatagramPort: could not read local address", e);

            return -1;
        }
    }

    // Fails fast if the caller skipped init() or already closed the socket.
    private void requireOpen()
    {
        if (channel == null || !channel.isOpen())
        {
            throw new IllegalStateException("DesktopDatagramPort is not open — call init() first");
        }
    }

    /**
     * Parses a {@code "host:port"} destination. A bare host falls back to
     * {@link Constants#DEFAULT_NET_PORT}; a bare {@code ":port"} falls back
     * to loopback.
     *
     * @param address destination string, e.g. "192.168.1.10:5021"
     * @return the resolved socket address
     */
    private static InetSocketAddress parseAddress(final String address)
    {
        if (address == null || address.isBlank())
        {
            throw new IllegalArgumentException("address must not be null or blank");
        }

        final int separator = address.lastIndexOf(':');

        if (separator < 0)
        {
            return new InetSocketAddress(address, Constants.DEFAULT_NET_PORT);
        }

        String host = address.substring(0, separator);

        if (host.isBlank())
        {
            host = LOOPBACK_HOST;
        }

        final String portText = address.substring(separator + 1);

        try
        {
            return new InetSocketAddress(host, Integer.parseInt(portText));
        }
        catch (final NumberFormatException e)
        {
            throw new IllegalArgumentException("malformed address, expected host:port: " + address, e);
        }
    }
}
