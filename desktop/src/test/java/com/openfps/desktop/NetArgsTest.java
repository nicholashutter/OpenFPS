/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NetArgs}.
 *
 * <p>Argument parsing is where a networked build fails first and least
 * informatively. Every case below is one where the alternative to a clear
 * message is either a {@code NumberFormatException} out of a socket call three
 * layers down, or — worse — a session that opens perfectly and shows nobody.</p>
 */
@DisplayName("NetArgs")
class NetArgsTest
{
    @Nested
    @DisplayName("no networking asked for")
    class Absent
    {
        @Test
        @DisplayName("a plain command line is a local match")
        void shouldReportALocalMatchWhenNoNetArgument()
        {
            assertThat(NetArgs.parse(new String[] {"--fps=60"}).isRequested()).isFalse();
            assertThat(NetArgs.parse(new String[0]).isRequested()).isFalse();
            assertThat(NetArgs.parse(null).isRequested()).isFalse();
        }

        @Test
        @DisplayName("peers without an identity of our own are refused")
        void shouldRefusePeersWithoutANetArgument()
        {
            // A command line that looks like it should work and cannot: there
            // would be nothing to put in the packets we send.
            assertThatThrownBy(
                () -> NetArgs.parse(new String[] {"--peer=2@127.0.0.1:5022"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--net=");
        }
    }

    @Nested
    @DisplayName("a well-formed command line")
    class Accepted
    {
        @Test
        @DisplayName("reads our identity and port")
        void shouldReadOurIdentityAndPort()
        {
            final NetArgs args = NetArgs.parse(new String[] {"--net=1:5021"});

            assertThat(args.isRequested()).isTrue();
            assertThat(args.playerId()).isEqualTo(1);
            assertThat(args.port()).isEqualTo(5021);
            assertThat(args.peers()).isEmpty();
        }

        @Test
        @DisplayName("port 0 is legal and means 'any free port'")
        void shouldAcceptPortZero()
        {
            // What a second instance on one machine wants, and the case anyone
            // testing this hits first.
            assertThat(NetArgs.parse(new String[] {"--net=2:0"}).port()).isZero();
        }

        @Test
        @DisplayName("reads several peers, in the order given")
        void shouldReadEveryPeerInOrder()
        {
            final NetArgs args = NetArgs.parse(new String[]
            {
                "--net=1:5021", "--peer=2@127.0.0.1:5022", "--peer=3@10.0.0.5:5023",
            });

            assertThat(args.peers()).hasSize(2);
            assertThat(args.peers().get(0).id()).isEqualTo(2);
            assertThat(args.peers().get(0).address()).isEqualTo("127.0.0.1:5022");
            assertThat(args.peers().get(1).id()).isEqualTo(3);
            assertThat(args.peers().get(1).address()).isEqualTo("10.0.0.5:5023");
        }

        @Test
        @DisplayName("a bare host is left for the datagram port to default")
        void shouldPassAHostWithoutAPortThrough()
        {
            // DesktopDatagramPort already defaults a missing port to
            // Constants.DEFAULT_NET_PORT, so re-implementing that here would be
            // a second place for the default to live and drift.
            final NetArgs args =
                NetArgs.parse(new String[] {"--net=1:5021", "--peer=2@10.0.0.5"});

            assertThat(args.peers().get(0).address()).isEqualTo("10.0.0.5");
        }

        @Test
        @DisplayName("ignores arguments that are not its own")
        void shouldIgnoreUnrelatedArguments()
        {
            final NetArgs args = NetArgs.parse(new String[]
            {
                "--fps=120", "--start-in-game", "--net=4:0", "--assets=x/y",
            });

            assertThat(args.playerId()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("command lines it refuses")
    class Refused
    {
        @Test
        @DisplayName("a --net with no separator")
        void shouldRefuseANetWithoutASeparator()
        {
            assertThatThrownBy(() -> NetArgs.parse(new String[] {"--net=5021"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<playerId>");
        }

        @Test
        @DisplayName("a non-numeric id or port, naming what was actually typed")
        void shouldRefuseNonNumericFields()
        {
            assertThatThrownBy(() -> NetArgs.parse(new String[] {"--net=me:5021"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("me");
            assertThatThrownBy(() -> NetArgs.parse(new String[] {"--net=1:http"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
        }

        @Test
        @DisplayName("a port outside the 16-bit range")
        void shouldRefuseAnImpossiblePort()
        {
            assertThatThrownBy(() -> NetArgs.parse(new String[] {"--net=1:70000"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0..65535");
            assertThatThrownBy(() -> NetArgs.parse(new String[] {"--net=1:-1"}))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a negative player id")
        void shouldRefuseANegativePlayerId()
        {
            assertThatThrownBy(() -> NetArgs.parse(new String[] {"--net=-1:5021"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("a peer carrying our own player id")
        void shouldRefuseAPeerThatIsUs()
        {
            // The failure this prevents is invisible: a session that opens
            // perfectly, sends perfectly, and drops every incoming packet as
            // coming from itself. The game would simply show nobody.
            assertThatThrownBy(() -> NetArgs.parse(new String[]
            {
                "--net=1:5021", "--peer=1@127.0.0.1:5022",
            }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("our own player id");
        }

        @Test
        @DisplayName("two peers sharing a player id")
        void shouldRefuseDuplicatePeerIds()
        {
            // They would share a buffer slot and overwrite each other's inputs,
            // which reads as one peer teleporting rather than as a mistake.
            assertThatThrownBy(() -> NetArgs.parse(new String[]
            {
                "--net=1:5021", "--peer=2@10.0.0.2", "--peer=2@10.0.0.3",
            }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share player id");
        }

        @Test
        @DisplayName("a --peer with no separator or no address")
        void shouldRefuseAMalformedPeer()
        {
            assertThatThrownBy(
                () -> NetArgs.parse(new String[] {"--net=1:5021", "--peer=127.0.0.1"}))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> NetArgs.parse(new String[] {"--net=1:5021", "--peer=2@"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no address");
        }
    }
}
