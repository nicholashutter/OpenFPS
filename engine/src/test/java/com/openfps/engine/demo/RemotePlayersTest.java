/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;

import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.hal.port.I_DatagramPort;
import com.openfps.engine.net.NetSession;
import com.openfps.engine.net.TicCmdBuffer;
import com.openfps.engine.net.TicCmdEncoder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RemotePlayers} — the replay that gives a peer a body.
 *
 * <p>No socket anywhere. {@link RemotePlayers#advance} reads only the session's
 * ring and its peer count, so a test can write commands straight into the ring
 * and assert what the body did. That is the same separation that lets the six
 * transport classes be unit-tested: the bytes and the wire are somebody else's
 * problem.</p>
 */
@DisplayName("RemotePlayers")
class RemotePlayersTest
{
    /** A tic at 60 Hz, in seconds — the value the demo integrates with. */
    private static final float DELTA_SECONDS = 1.0f / 60.0f;

    /** A tic at 60 Hz in nanos, which sizes a session's redundancy window. */
    private static final long TIC_NANOS = 16_666_666L;

    /** The local player's id. */
    private static final int US = 1;

    /**
     * The peer's id.
     *
     * <p>Bounded to {@code [0, Constants.MAX_PLAYERS)} by
     * {@code PeerConnection}, so this cannot be an arbitrary large number even
     * though {@code NetSession} describes an id as a network identity rather than
     * an index. Slot and id happen to coincide for a single peer, which is why
     * the slot arithmetic is asserted separately in {@link RingContract} rather
     * than inferred from a passing walk test.</p>
     */
    private static final int THEM = 2;

    /** Where the peer is, as far as a session that never sends is concerned. */
    private static final String THEIR_ADDRESS = "10.0.0.2:5021";

    /** Full forward on the wire. */
    private static final int FORWARD_FULL = TicCmdEncoder.encodeAxis(1.0f);

    /** A dead stick on the wire. */
    private static final int AXIS_ZERO = TicCmdEncoder.encodeAxis(0.0f);

    /**
     * A socket that does nothing at all.
     *
     * <p>{@link RemotePlayers} never touches it — a session is only asked for its
     * ring and its peer count — but {@link NetSession} needs one to be
     * constructed. Doing nothing is therefore the correct behaviour rather than a
     * shortcut, and a test that started passing because this began delivering
     * packets would be testing the wrong thing.</p>
     */
    private static final class SilentSocket implements I_DatagramPort
    {
        @Override
        public void init()
        {
            // nothing to open
        }

        @Override
        public void bind(final int port)
        {
            // nothing to bind
        }

        @Override
        public void send(final byte[] data, final String address)
        {
            // nothing leaves
        }

        @Override
        public byte[] receive()
        {
            return null;
        }

        @Override
        public void processTic(final int ticIndex)
        {
            // nothing to pump
        }

        @Override
        public void close()
        {
            // nothing to close
        }

        @Override
        public void shutdown()
        {
            // nothing to release
        }
    }

    /** A session with one peer, whose ring a test writes into directly. */
    private static NetSession sessionWithOnePeer()
    {
        final NetSession session = new NetSession(new SilentSocket(), US, TIC_NANOS);
        session.addPeer(THEM, THEIR_ADDRESS);
        return session;
    }

    /** The demo's full art, so bodies and their carbines are both placed. */
    private static DemoModels armedKit(final Path root) throws IOException
    {
        for (final String piece : new String[] {"floor-square.ofm", "wall.ofm",
            "wall-doorway.ofm", "column.ofm", "crate.ofm", "stairs.ofm", "shape-slope.ofm"})
        {
            DemoModelFixture.write(root.resolve(DemoModels.LEVEL_DIRECTORY).resolve(piece));
        }
        DemoModelFixture.write(root.resolve(DemoModels.WEAPON_DIRECTORY)
            .resolve(DemoModels.WEAPON_MODEL));
        DemoModelFixture.write(root.resolve(DemoModels.WEAPON_DIRECTORY)
            .resolve(DemoModels.BOT_WEAPON_MODEL));
        for (final String person : new String[] {"character-a.ofm", "character-d.ofm",
            "character-h.ofm", "character-k.ofm", "character-n.ofm", "character-q.ofm",
            "character-r.ofm"})
        {
            DemoModelFixture.write(root.resolve(DemoModels.CHARACTER_DIRECTORY)
                .resolve(person));
        }
        return DemoModels.load(root);
    }

    /** Puts one command for the peer into slot 1 of a session's ring. */
    private static void postCommand(final NetSession session, final int tic,
        final int forward, final int strafe, final float yawRadians)
    {
        session.commands().put(1, tic, forward, strafe,
            TicCmdEncoder.encodeAngle(yawRadians), TicCmdEncoder.encodePitch(0.0f), 0);
    }

    @Nested
    @DisplayName("the pool as placed")
    final class Placement
    {
        @Test
        @DisplayName("places a body and a carbine for every peer a session can hold")
        void shouldPlaceOnePerPossiblePeer(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();

            // The pool is sized off what a SESSION can accept, not off what this
            // run happens to connect. A body that is not placed while the Scene is
            // being built can never be placed at all, so the bound has to be the
            // maximum rather than the actual.
            assertThat(peers.bodyCount()).isEqualTo(NetSession.MAX_PEERS);
        }

        @Test
        @DisplayName("every body starts hidden, before a frame is ever drawn")
        void shouldStartWithNobodyVisible(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();

            for (int body = 0; body < peers.bodyCount(); body++)
            {
                assertThat(peers.isLive(body))
                    .as("body %d is live before any input arrived", body)
                    .isFalse();
                assertThat(peers.nextTic(body)).isEqualTo(RemotePlayers.NO_TIC);
            }
        }

        @Test
        @DisplayName("body entity ids do not collide with the player's or any bot's")
        void shouldNotCollideWithTheBotIdBlock(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();

            // The whole reason Match owns FIRST_REMOTE_ENTITY_ID. A collision here
            // would be invisible and vicious: two different bodies would be the
            // same entity, and a shot at one would report a hit on the other.
            final int lastBotId = Match.FIRST_BOT_ENTITY_ID + demo.botCount() - 1;
            assertThat(Match.FIRST_REMOTE_ENTITY_ID)
                .as("the remote block starts past every bot and past the player")
                .isGreaterThan(lastBotId);
            assertThat(Match.FIRST_REMOTE_ENTITY_ID + peers.bodyCount() - 1)
                .as("the whole remote block fits above the bots")
                .isGreaterThanOrEqualTo(Match.FIRST_REMOTE_ENTITY_ID);
        }

        @Test
        @DisplayName("no character art means no bodies, and advancing is harmless")
        void shouldPlaceNothingWithoutCharacterArt(@TempDir final Path root) throws IOException
        {
            for (final String piece : new String[] {"floor-square.ofm", "wall.ofm",
                "wall-doorway.ofm", "column.ofm", "crate.ofm", "stairs.ofm",
                "shape-slope.ofm"})
            {
                DemoModelFixture.write(root.resolve(DemoModels.LEVEL_DIRECTORY)
                    .resolve(piece));
            }
            DemoModelFixture.write(root.resolve(DemoModels.WEAPON_DIRECTORY)
                .resolve(DemoModels.WEAPON_MODEL));
            final DemoScene demo = DemoScene.build(DemoModels.load(root));
            final RemotePlayers peers = demo.remotePlayers();

            assertThat(peers.bodyCount()).isZero();
            // A degraded demo rather than a broken one — the same answer addBots
            // gives, and it must not throw on the per-tic path.
            assertThat(peers.advance(sessionWithOnePeer(), DELTA_SECONDS)).isZero();
        }
    }

    @Nested
    @DisplayName("replaying a peer's input")
    final class Replay
    {
        @Test
        @DisplayName("a peer's first command brings its body to life")
        void shouldGoLiveOnTheFirstCommand(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();
            final NetSession session = sessionWithOnePeer();

            assertThat(peers.advance(session, DELTA_SECONDS))
                .as("an empty ring moves nobody")
                .isZero();
            assertThat(peers.isLive(0)).isFalse();

            postCommand(session, 0, AXIS_ZERO, AXIS_ZERO, 0.0f);
            assertThat(peers.advance(session, DELTA_SECONDS)).isEqualTo(1);
            assertThat(peers.isLive(0)).isTrue();
        }

        @Test
        @DisplayName("walking forward moves the body, and only the peer's own body")
        void shouldWalkTheBodyThatOwnsTheInput(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();
            final NetSession session = sessionWithOnePeer();
            final float startZ = peers.controller(0).positionZ();

            // Yaw zero faces world +z, which is PlayerController's convention and
            // the heading the demo spawns everyone on.
            for (int tic = 0; tic < 30; tic++)
            {
                postCommand(session, tic, FORWARD_FULL, AXIS_ZERO, 0.0f);
            }
            assertThat(peers.advance(session, DELTA_SECONDS)).isEqualTo(30);

            assertThat(peers.controller(0).positionZ())
                .as("thirty tics of full forward walked the body down the room")
                .isGreaterThan(startZ);
            // The second body shares the pool and the ring and must not have moved:
            // slot is derived from the peer index, and an off-by-one there would
            // drive the wrong body with the right data.
            assertThat(peers.isLive(1)).isFalse();
        }

        @Test
        @DisplayName("the reported yaw is applied absolutely, not integrated")
        void shouldTakeTheYawFromTheWire(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();
            final NetSession session = sessionWithOnePeer();

            // A quarter turn, sent once. Were the angle integrated as a delta this
            // would turn the body a further quarter on every tic; because it is
            // absolute, ten identical commands leave it facing exactly there.
            final float quarterTurn = (float) (StrictMath.PI / 2.0);
            for (int tic = 0; tic < 10; tic++)
            {
                postCommand(session, tic, AXIS_ZERO, AXIS_ZERO, quarterTurn);
            }
            peers.advance(session, DELTA_SECONDS);

            assertThat(peers.controller(0).yawRadians())
                .as("the body faces where the peer said it was facing")
                .isCloseTo(quarterTurn, org.assertj.core.data.Offset.offset(0.01f));
        }

        @Test
        @DisplayName("a redelivered command is applied once, not twice")
        void shouldNotReapplyARedeliveredCommand(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();
            final NetSession session = sessionWithOnePeer();

            // This is the property the whole reliability model depends on. Every
            // packet re-sends the last several commands, so the ring is constantly
            // overwritten with values it already holds; if the cursor did not
            // guard against it, a peer would move several times as far as it
            // actually walked.
            for (int tic = 0; tic < 10; tic++)
            {
                postCommand(session, tic, FORWARD_FULL, AXIS_ZERO, 0.0f);
            }
            assertThat(peers.advance(session, DELTA_SECONDS)).isEqualTo(10);
            final float afterFirstPass = peers.controller(0).positionZ();

            // The identical window arrives again, exactly as redundant redelivery
            // guarantees it will.
            for (int tic = 0; tic < 10; tic++)
            {
                postCommand(session, tic, FORWARD_FULL, AXIS_ZERO, 0.0f);
            }
            assertThat(peers.advance(session, DELTA_SECONDS))
                .as("nothing new arrived, so nothing was applied")
                .isZero();
            assertThat(peers.controller(0).positionZ())
                .as("the body did not walk the same ten tics twice")
                .isEqualTo(afterFirstPass);
        }

        @Test
        @DisplayName("a missing tic holds the body until the gap fills")
        void shouldHoldStillAcrossAGap(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();
            final NetSession session = sessionWithOnePeer();

            postCommand(session, 0, FORWARD_FULL, AXIS_ZERO, 0.0f);
            postCommand(session, 1, FORWARD_FULL, AXIS_ZERO, 0.0f);
            // Tic 2 is missing; 3 and 4 have arrived out of the sender's order,
            // which is exactly what an unordered datagram service permits.
            postCommand(session, 3, FORWARD_FULL, AXIS_ZERO, 0.0f);
            postCommand(session, 4, FORWARD_FULL, AXIS_ZERO, 0.0f);

            assertThat(peers.advance(session, DELTA_SECONDS))
                .as("only the contiguous run below the hole is applied")
                .isEqualTo(2);
            assertThat(peers.nextTic(0))
                .as("the cursor waits on the missing tic rather than skipping it")
                .isEqualTo(2);
            final float stalled = peers.controller(0).positionZ();
            assertThat(peers.advance(session, DELTA_SECONDS)).isZero();
            assertThat(peers.controller(0).positionZ()).isEqualTo(stalled);

            // The gap fills, and the body catches up across all three tics in one
            // call — which is the whole point of redundancy over retransmission.
            postCommand(session, 2, FORWARD_FULL, AXIS_ZERO, 0.0f);
            assertThat(peers.advance(session, DELTA_SECONDS)).isEqualTo(3);
            assertThat(peers.nextTic(0)).isEqualTo(5);
            assertThat(peers.controller(0).positionZ()).isGreaterThan(stalled);
        }

        @Test
        @DisplayName("a peer joining late starts from the oldest tic still held")
        void shouldAnchorOnTheOldestHeldTic(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();
            final NetSession session = sessionWithOnePeer();

            // A peer whose first packet lands at tic 500. Anchoring the cursor at
            // zero instead would make the body look for a history the ring has
            // never held, find tic 0 missing, and never move at all — a body
            // frozen on the spawn point for the whole match.
            postCommand(session, 500, FORWARD_FULL, AXIS_ZERO, 0.0f);
            postCommand(session, 501, FORWARD_FULL, AXIS_ZERO, 0.0f);

            assertThat(peers.advance(session, DELTA_SECONDS)).isEqualTo(2);
            assertThat(peers.nextTic(0)).isEqualTo(502);
        }

        @Test
        @DisplayName("a local match with no session leaves every body alone")
        void shouldIgnoreANullSession(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();

            assertThat(peers.advance(null, DELTA_SECONDS)).isZero();
            assertThat(peers.isLive(0)).isFalse();
        }
    }

    @Nested
    @DisplayName("the rematch")
    final class Reset
    {
        @Test
        @DisplayName("puts every body back on the spawn and forgets the tic sequence")
        void shouldClearPositionsAndCursors(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(armedKit(root));
            final RemotePlayers peers = demo.remotePlayers();
            final NetSession session = sessionWithOnePeer();
            for (int tic = 0; tic < 20; tic++)
            {
                postCommand(session, tic, FORWARD_FULL, AXIS_ZERO, 0.0f);
            }
            peers.advance(session, DELTA_SECONDS);
            assertThat(peers.isLive(0)).isTrue();

            peers.reset(demo.spawnX(), demo.spawnY(), demo.spawnZ(), 0.0f);

            final PlayerController back = peers.controller(0);
            assertThat(back.positionX()).isEqualTo(demo.spawnX());
            assertThat(back.positionZ()).isEqualTo(demo.spawnZ());
            // The cursor has to be forgotten as well as the position. A body that
            // resumed the previous round's tic numbering would sit waiting for a
            // tic the new round will not send for several seconds.
            assertThat(peers.isLive(0)).isFalse();
            assertThat(peers.nextTic(0)).isEqualTo(RemotePlayers.NO_TIC);
        }
    }

    @Nested
    @DisplayName("the ring contract this relies on")
    final class RingContract
    {
        @Test
        @DisplayName("the local player is slot 0 and peers take the slots above it")
        void shouldKeepTheLocalPlayerOutOfTheWay()
        {
            final NetSession session = sessionWithOnePeer();

            // RemotePlayers derives slot from peer index + 1. If LOCAL_SLOT ever
            // stopped being 0 this arithmetic would silently replay the local
            // player's own input into a peer's body, which would look like a
            // mirror image following the player around.
            assertThat(NetSession.LOCAL_SLOT).isZero();
            assertThat(session.peerCount()).isEqualTo(1);
            assertThat(session.commands().latestTic(1)).isEqualTo(TicCmdBuffer.EMPTY_TIC);
        }
    }
}
