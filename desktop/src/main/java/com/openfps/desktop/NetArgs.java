/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the networking command line: who we are, what port we listen on, and
 * who to talk to.
 *
 * <pre>
 *   --net=1:5021                       be player 1, listen on UDP 5021
 *   --net=2:0                          be player 2, let the OS pick a port
 *   --peer=1&#64;127.0.0.1:5021            player 1 is at that address
 * </pre>
 *
 * <p>Split out of {@link DesktopLauncher} because parsing is the part with the
 * failure modes and the launcher has no way to be tested. A malformed address
 * here should be a clear message and a non-zero exit, not a
 * {@code NumberFormatException} out of a socket call three layers down — and
 * getting that right takes more cases than belong in a {@code main}.</p>
 *
 * <h2>Both halves of {@code --net} are required</h2>
 *
 * <p>Neither has a safe default, and the reasons are different. Two peers
 * sharing a player id would each read the other's packets as claiming to be
 * themselves and drop them, with no error anywhere — the game would simply show
 * nobody. And a default port cannot work for two instances on one machine, which
 * is the very first thing anyone testing this does.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class NetArgs
{
    /** Separator between the player id and the port in {@code --net}. */
    public static final char NET_SEPARATOR = ':';

    /** Separator between the player id and the address in {@code --peer}. */
    public static final char PEER_SEPARATOR = '@';

    /** This peer's player id, or -1 when networking was not requested. */
    private final int playerId;

    /** The local UDP port to bind, or -1 when networking was not requested. */
    private final int port;

    /** The peers to connect to, in the order given. */
    private final List<Peer> peers;

    /** One {@code --peer} argument: an id and where to reach it. */
    public static final class Peer
    {
        /** The peer's player id. */
        private final int id;

        /** The peer's {@code host:port}. */
        private final String address;

        Peer(final int peerId, final String peerAddress)
        {
            this.id = peerId;
            this.address = peerAddress;
        }

        /** Returns the peer's player id. */
        public int id()
        {
            return id;
        }

        /** Returns the peer's {@code host:port}. Never null or empty. */
        public String address()
        {
            return address;
        }

        @Override
        public String toString()
        {
            return id + "@" + address;
        }
    }

    private NetArgs(final int localPlayerId, final int localPort, final List<Peer> connectTo)
    {
        this.playerId = localPlayerId;
        this.port = localPort;
        this.peers = connectTo;
    }

    /**
     * Reads the networking arguments.
     *
     * @param args the whole command line, may be null
     * @return the parsed settings; {@link #isRequested()} is false when no
     *     {@code --net} was given
     * @throws IllegalArgumentException if an argument is present but malformed,
     *     or if peers are named without a {@code --net} to own them
     */
    public static NetArgs parse(final String[] args)
    {
        final String net = valueOf(args, DesktopLauncher.NET_ARG);
        final List<Peer> peers = parsePeers(args);
        if (net == null)
        {
            if (!peers.isEmpty())
            {
                // Peers with no identity of our own is a command line that looks
                // like it should work and cannot: there would be nothing to put
                // in the packets we send.
                throw new IllegalArgumentException(DesktopLauncher.PEER_ARG
                    + " needs " + DesktopLauncher.NET_ARG + "<playerId>:<port> as well");
            }
            return new NetArgs(-1, -1, peers);
        }

        final int split = net.indexOf(NET_SEPARATOR);
        if (split < 0)
        {
            throw new IllegalArgumentException(DesktopLauncher.NET_ARG
                + "<playerId>" + NET_SEPARATOR + "<port>, got '" + net + "'");
        }
        final int localId = parseNumber(net.substring(0, split), "player id", net);
        final int localPort = parseNumber(net.substring(split + 1), "port", net);
        if (localId < 0)
        {
            throw new IllegalArgumentException("player id must not be negative, got " + localId);
        }
        if (localPort < 0 || localPort > 65535)
        {
            throw new IllegalArgumentException(
                "port must be 0..65535, got " + localPort + " — 0 asks the OS for a free one");
        }
        for (final Peer peer : peers)
        {
            if (peer.id() == localId)
            {
                throw new IllegalArgumentException("peer " + peer
                    + " uses our own player id — a peer cannot be us");
            }
        }
        return new NetArgs(localId, localPort, peers);
    }

    /** Returns whether {@code --net} was given at all. */
    public boolean isRequested()
    {
        return playerId >= 0;
    }

    /** Returns this peer's player id, or -1 when networking was not requested. */
    public int playerId()
    {
        return playerId;
    }

    /**
     * Returns which spawn point the local player should stand on.
     *
     * <p>The player id when networking was requested, and <b>0 when it was
     * not</b> — which is the canonical spawn, so a single-player run is placed
     * exactly where it always was. This exists rather than callers using
     * {@link #playerId()} directly because that returns -1 for a local run, and
     * -1 is not a spawn: it would have to be special-cased at every call site,
     * and the one that forgot would place the player outside the room.</p>
     *
     * @return a non-negative spawn id, never -1
     */
    public int localSpawnId()
    {
        if (!isRequested())
        {
            return 0;
        }
        return playerId;
    }

    /** Returns the local UDP port, or -1 when networking was not requested. */
    public int port()
    {
        return port;
    }

    /** Returns the peers to connect to, in the order given. Never null. */
    public List<Peer> peers()
    {
        return List.copyOf(peers);
    }

    /** Returns a debug rendering of the parsed settings. */
    @Override
    public String toString()
    {
        if (!isRequested())
        {
            return "NetArgs{local match}";
        }
        return "NetArgs{player=" + playerId + ", port=" + port + ", peers=" + peers + "}";
    }

    // Every --peer argument, in order.
    private static List<Peer> parsePeers(final String[] args)
    {
        final List<Peer> found = new ArrayList<>();
        if (args == null)
        {
            return found;
        }
        for (final String arg : args)
        {
            if (arg == null || !arg.startsWith(DesktopLauncher.PEER_ARG))
            {
                continue;
            }
            found.add(parsePeer(arg.substring(DesktopLauncher.PEER_ARG.length())));
        }
        for (int index = 0; index < found.size(); index++)
        {
            for (int other = 0; other < index; other++)
            {
                if (found.get(other).id() == found.get(index).id())
                {
                    // Two peers on one id would share a buffer slot and
                    // overwrite each other's inputs, which reads as one peer
                    // teleporting rather than as a configuration mistake.
                    throw new IllegalArgumentException("two peers share player id "
                        + found.get(index).id());
                }
            }
        }
        return found;
    }

    // One --peer value: id@host:port.
    private static Peer parsePeer(final String value)
    {
        final int split = value.indexOf(PEER_SEPARATOR);
        if (split < 0)
        {
            throw new IllegalArgumentException(DesktopLauncher.PEER_ARG
                + "<playerId>" + PEER_SEPARATOR + "<host>:<port>, got '" + value + "'");
        }
        final int id = parseNumber(value.substring(0, split), "peer id", value);
        if (id < 0)
        {
            throw new IllegalArgumentException("peer id must not be negative, got " + id);
        }
        final String address = value.substring(split + 1);
        if (address.isEmpty())
        {
            throw new IllegalArgumentException("peer " + id + " has no address");
        }
        return new Peer(id, address);
    }

    // An integer, or a message that names the whole argument rather than just
    // the fragment — "got 'x'" is much less useful than showing what was typed.
    private static int parseNumber(final String text, final String what, final String whole)
    {
        try
        {
            return Integer.parseInt(text.trim());
        }
        catch (final NumberFormatException e)
        {
            throw new IllegalArgumentException(
                what + " must be a number, got '" + text + "' in '" + whole + "'", e);
        }
    }

    // The value of the first argument carrying a prefix, or null.
    private static String valueOf(final String[] args, final String prefix)
    {
        if (args == null)
        {
            return null;
        }
        for (final String arg : args)
        {
            if (arg != null && arg.startsWith(prefix))
            {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }
}
