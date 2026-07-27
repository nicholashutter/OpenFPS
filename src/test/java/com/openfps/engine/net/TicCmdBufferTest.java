/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import com.openfps.engine.common.Constants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the preallocated lockstep input ring.
 */
@DisplayName("TicCmdBuffer — the preallocated lockstep input ring")
class TicCmdBufferTest
{
    private static final int SLOT = 3;

    private TicCmdBuffer buffer;

    @BeforeEach
    void setUp()
    {
        buffer = new TicCmdBuffer();
    }

    @Test
    @DisplayName("is sized from the shared constants, not from a local literal")
    void isSizedFromTheSharedConstants()
    {
        assertThat(buffer.depth()).isEqualTo(Constants.TIC_BUFFER_SIZE);
        assertThat(buffer.playerCount()).isEqualTo(Constants.MAX_PLAYERS);
    }

    @Test
    @DisplayName("starts empty for every player slot")
    void startsEmpty()
    {
        for (int slot = 0; slot < Constants.MAX_PLAYERS; slot++)
        {
            assertThat(buffer.latestTic(slot)).isEqualTo(TicCmdBuffer.EMPTY_TIC);
            assertThat(buffer.has(slot, 0)).isFalse();
        }
    }

    @Test
    @DisplayName("stores a command and reads every field back unchanged")
    void storesAndReadsBack()
    {
        final TicCmd cmd = new TicCmd(11, TicCmd.MIN_AXIS, TicCmd.MAX_AXIS,
            TicCmd.MAX_ANGLE, TicCmd.MIN_PITCH, TicCmd.ALL_BUTTONS);

        assertThat(buffer.put(SLOT, cmd)).isTrue();

        assertThat(buffer.has(SLOT, 11)).isTrue();
        assertThat(buffer.forward(SLOT, 11)).isEqualTo(TicCmd.MIN_AXIS);
        assertThat(buffer.strafe(SLOT, 11)).isEqualTo(TicCmd.MAX_AXIS);
        assertThat(buffer.angle(SLOT, 11)).isEqualTo(TicCmd.MAX_ANGLE);
        assertThat(buffer.pitch(SLOT, 11)).isEqualTo(TicCmd.MIN_PITCH);
        assertThat(buffer.buttons(SLOT, 11)).isEqualTo(TicCmd.ALL_BUTTONS);
        assertThat(buffer.get(SLOT, 11)).isEqualTo(cmd);
        assertThat(buffer.latestTic(SLOT)).isEqualTo(11);
    }

    @Test
    @DisplayName("keeps each player's inputs in its own lane")
    void keepsPlayersIndependent()
    {
        for (int slot = 0; slot < Constants.MAX_PLAYERS; slot++)
        {
            buffer.put(slot, new TicCmd(20, slot * 100, 0, 0, 0, 0));
        }

        for (int slot = 0; slot < Constants.MAX_PLAYERS; slot++)
        {
            assertThat(buffer.forward(slot, 20)).isEqualTo(slot * 100);
        }
    }

    @Test
    @DisplayName("wraps around the ring so a tic one full depth later reuses the same slot")
    void wrapsAroundTheRing()
    {
        final int depth = Constants.TIC_BUFFER_SIZE;
        buffer.put(SLOT, new TicCmd(0, 111, 0, 0, 0, 0));
        assertThat(buffer.has(SLOT, 0)).isTrue();

        assertThat(buffer.put(SLOT, new TicCmd(depth, 222, 0, 0, 0, 0))).isTrue();

        assertThat(buffer.has(SLOT, depth)).isTrue();
        assertThat(buffer.forward(SLOT, depth)).isEqualTo(222);
        assertThat(buffer.has(SLOT, 0)).isFalse();
    }

    @Test
    @DisplayName("refuses to let an old tic overwrite the newer one sharing its slot")
    void rejectsAStaleTicInAnOccupiedSlot()
    {
        final int depth = Constants.TIC_BUFFER_SIZE;
        buffer.put(SLOT, new TicCmd(depth, 222, 0, 0, 0, 0));

        assertThat(buffer.put(SLOT, new TicCmd(0, 111, 0, 0, 0, 0))).isFalse();

        assertThat(buffer.forward(SLOT, depth)).isEqualTo(222);
        assertThat(buffer.latestTic(SLOT)).isEqualTo(depth);
    }

    @Test
    @DisplayName("treats a re-delivered command as an idempotent overwrite, which is what makes "
        + "redundant redelivery free of dedup bookkeeping")
    void treatsDuplicatesAsIdempotent()
    {
        final TicCmd cmd = new TicCmd(7, 42, -42, 1000, 5, 3);

        assertThat(buffer.put(SLOT, cmd)).isTrue();
        assertThat(buffer.put(SLOT, cmd)).isTrue();
        assertThat(buffer.put(SLOT, cmd)).isTrue();

        assertThat(buffer.get(SLOT, 7)).isEqualTo(cmd);
        assertThat(buffer.latestTic(SLOT)).isEqualTo(7);
    }

    @Test
    @DisplayName("accepts commands arriving out of order and files each under its own tic")
    void acceptsOutOfOrderArrival()
    {
        buffer.put(SLOT, new TicCmd(5, 5, 0, 0, 0, 0));
        buffer.put(SLOT, new TicCmd(2, 2, 0, 0, 0, 0));
        buffer.put(SLOT, new TicCmd(4, 4, 0, 0, 0, 0));
        buffer.put(SLOT, new TicCmd(3, 3, 0, 0, 0, 0));

        assertThat(buffer.forward(SLOT, 2)).isEqualTo(2);
        assertThat(buffer.forward(SLOT, 3)).isEqualTo(3);
        assertThat(buffer.forward(SLOT, 4)).isEqualTo(4);
        assertThat(buffer.forward(SLOT, 5)).isEqualTo(5);
        assertThat(buffer.latestTic(SLOT)).isEqualTo(5);
    }

    @Test
    @DisplayName("never lets latestTic go backwards when an older tic is filled in late")
    void latestTicNeverGoesBackwards()
    {
        buffer.put(SLOT, new TicCmd(9, 0, 0, 0, 0, 0));
        buffer.put(SLOT, new TicCmd(4, 0, 0, 0, 0, 0));

        assertThat(buffer.latestTic(SLOT)).isEqualTo(9);
    }

    @Test
    @DisplayName("holds exactly one full ring depth of history and no more")
    void holdsExactlyOneRingDepth()
    {
        final int depth = Constants.TIC_BUFFER_SIZE;
        for (int tic = 0; tic < depth * 2; tic++)
        {
            buffer.put(SLOT, new TicCmd(tic, tic, 0, 0, 0, 0));
        }

        for (int tic = depth; tic < depth * 2; tic++)
        {
            assertThat(buffer.has(SLOT, tic)).isTrue();
            assertThat(buffer.forward(SLOT, tic)).isEqualTo(tic);
        }
        for (int tic = 0; tic < depth; tic++)
        {
            assertThat(buffer.has(SLOT, tic)).isFalse();
        }
    }

    @Test
    @DisplayName("decodes a wire command straight into the ring without an intermediate object")
    void storesFromTheWireForm()
    {
        final byte[] wire = new byte[TicCmd.BYTES];
        final TicCmd cmd = new TicCmd(31, -7, 7, 4242, -9, 9);
        cmd.encode(wire, 0);

        assertThat(buffer.putEncoded(SLOT, wire, 0)).isTrue();

        assertThat(buffer.get(SLOT, 31)).isEqualTo(cmd);
    }

    @Test
    @DisplayName("encodes a stored command back onto the wire byte for byte")
    void encodesStoredCommandsBackOntoTheWire()
    {
        final TicCmd cmd = new TicCmd(15, TicCmd.MIN_AXIS, TicCmd.MAX_AXIS,
            TicCmd.MAX_ANGLE, TicCmd.MIN_PITCH, TicCmd.ALL_BUTTONS);
        buffer.put(SLOT, cmd);

        final byte[] direct = new byte[TicCmd.BYTES];
        final byte[] fromRing = new byte[TicCmd.BYTES];
        cmd.encode(direct, 0);

        assertThat(buffer.encodeInto(fromRing, 0, SLOT, 15)).isEqualTo(TicCmd.BYTES);
        assertThat(fromRing).isEqualTo(direct);
    }

    @Test
    @DisplayName("writes nothing and reports zero bytes when asked to encode a tic it does not hold")
    void encodesNothingForAMissingTic()
    {
        final byte[] wire = new byte[TicCmd.BYTES];

        assertThat(buffer.encodeInto(wire, 0, SLOT, 99)).isZero();
        assertThat(wire).containsOnly((byte) 0);
        assertThat(buffer.get(SLOT, 99)).isNull();
    }

    @Test
    @DisplayName("clears one player's lane without touching the others")
    void clearsOnePlayerOnly()
    {
        buffer.put(0, new TicCmd(1, 1, 0, 0, 0, 0));
        buffer.put(1, new TicCmd(1, 2, 0, 0, 0, 0));

        buffer.clear(0);

        assertThat(buffer.has(0, 1)).isFalse();
        assertThat(buffer.latestTic(0)).isEqualTo(TicCmdBuffer.EMPTY_TIC);
        assertThat(buffer.has(1, 1)).isTrue();
    }

    @Test
    @DisplayName("resets every lane at once")
    void resetsEveryLane()
    {
        buffer.put(0, new TicCmd(1, 1, 0, 0, 0, 0));
        buffer.put(1, new TicCmd(1, 2, 0, 0, 0, 0));

        buffer.reset();

        assertThat(buffer.has(0, 1)).isFalse();
        assertThat(buffer.has(1, 1)).isFalse();
    }

    @Test
    @DisplayName("rejects a player slot outside the match size")
    void rejectsAnOutOfRangePlayerSlot()
    {
        assertThatThrownBy(() -> buffer.put(-1, new TicCmd(0, 0, 0, 0, 0, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buffer.put(Constants.MAX_PLAYERS, new TicCmd(0, 0, 0, 0, 0, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buffer.latestTic(Constants.MAX_PLAYERS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a negative tic number rather than indexing backwards into the ring")
    void rejectsANegativeTicNumber()
    {
        assertThatThrownBy(() -> buffer.put(SLOT, -1, 0, 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(buffer.has(SLOT, -1)).isFalse();
    }

    @Test
    @DisplayName("fails loudly when a field is read for a tic it does not hold")
    void failsLoudlyOnAbsentReads()
    {
        assertThatThrownBy(() -> buffer.forward(SLOT, 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tic 5");
        assertThatThrownBy(() -> buffer.strafe(SLOT, 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buffer.angle(SLOT, 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buffer.pitch(SLOT, 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buffer.buttons(SLOT, 5))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
