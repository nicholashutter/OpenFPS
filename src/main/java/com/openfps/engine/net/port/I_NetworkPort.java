/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net.port;

/**
 * G_ Port interface — P2P networking.
 * Handles tic command distribution, peer discovery, and snapshot delta.
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
