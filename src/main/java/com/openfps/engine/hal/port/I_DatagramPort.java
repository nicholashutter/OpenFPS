/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — raw UDP datagram send/receive.
 *
 * This is the HAL-level socket abstraction: bytes in, bytes out, no
 * knowledge of peers, tics, or the game protocol. The G_ networking
 * subsystem builds its P2P model on top of this.
 *
 * Not to be confused with {@code com.openfps.engine.net.port.I_NetworkPort},
 * which is the P2P-level port (connect, broadcastTicCmd, discoverPeers).
 * Both interfaces used to be named {@code I_NetworkPort}; this one was
 * renamed to {@code I_DatagramPort} because the collision was going to
 * become actively confusing once a real desktop implementation landed.
 */
public interface I_DatagramPort
{
    /**
     * Sends a raw datagram to the given address.
     *
     * @param data bytes to send
     * @param address destination address string (e.g., "192.168.1.10:5021")
     */
    void send(byte[] data, String address);

    /**
     * Receives a pending datagram, if any.
     * Non-blocking — returns null if nothing is queued.
     *
     * @return the received datagram bytes, or null if no data available
     */
    byte[] receive();

    /**
     * Binds the socket to the given local port.
     *
     * @param port local UDP port
     */
    void bind(int port);

    /**
     * Closes the socket. Called at engine shutdown.
     */
    void close();

    /**
     * Processes networking for one tic — called from the game loop.
     *
     * @param ticIndex the tic being processed
     */
    void processTic(int ticIndex);

    /**
     * Initializes the network subsystem. Called once at engine startup.
     */
    void init();

    /**
     * Shuts down the network subsystem. Called once at engine shutdown.
     */
    void shutdown();
}
