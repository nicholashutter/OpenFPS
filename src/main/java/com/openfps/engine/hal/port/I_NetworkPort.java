/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — raw network send/receive.
 * Used by the G_ networking subsystem; abstracts UDP sockets
 * across desktop and mobile targets.
 */
public interface I_NetworkPort
{
    /**
     * Sends a raw datagram to the given address.
     *
     * @param data bytes to send
     * @param address destination address string (e.g., "192.168.1.10:5021")
     */
    void send(final byte[] data, final String address);

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
    void bind(final int port);

    /**
     * Closes the socket. Called at engine shutdown.
     */
    void close();

    /**
     * Processes networking for one tic — called from the game loop.
     *
     * @param ticIndex the tic being processed
     */
    void processTic(final int ticIndex);

    /**
     * Initializes the network subsystem. Called once at engine startup.
     */
    void init();

    /**
     * Shuts down the network subsystem. Called once at engine shutdown.
     */
    void shutdown();
}
