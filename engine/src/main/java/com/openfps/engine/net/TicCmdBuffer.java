/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import com.openfps.engine.common.Constants;

/**
 * G_ Preallocated lockstep input ring: {@link Constants#TIC_BUFFER_SIZE} tics
 * deep by {@link Constants#MAX_PLAYERS} wide.
 *
 * ====================================================================
 *  WHY A RING INDEXED BY TIC NUMBER
 * ====================================================================
 *  Reliability in this engine is redundant redelivery, not retransmission
 *  (net/README.md section 4). Every packet re-sends the last W tics of input,
 *  so the same command arrives several times and out of order. Indexing by
 *  {@code ticNumber % TIC_BUFFER_SIZE} makes a duplicate an idempotent
 *  overwrite and needs no dedup bookkeeping.
 *
 *  The slot also stores the tic number it holds, which gives the staleness
 *  check for free: a command whose tic is older than the one already in the
 *  slot is a wrapped-around straggler and is rejected, never applied.
 *
 * ====================================================================
 *  ALLOCATION
 * ====================================================================
 *  Three {@code int[]} rings are allocated once in the constructor:
 * <pre>
 *  TIC_BUFFER_SIZE(64) x MAX_PLAYERS(8) x 12 B = 6 KB
 * </pre>
 *  Fields are packed into ints rather than a {@code byte[]} so the ring costs
 *  exactly the 12 bytes per command that net/README.md section 6 budgets, while
 *  honouring the "no {@code new byte[]} outside a memory port" rule
 *  (STYLE.md 13.4). Steady-state per-tic allocation is zero.
 *
 *  This class is not thread-safe. It is owned by the game loop thread.
 */
public final class TicCmdBuffer
{
    /** Tic number stored in a slot that has never been written. */
    public static final int EMPTY_TIC = -1;

    private static final int SHIFT_HIGH = 16;
    private static final int SHIFT_PITCH = 8;
    private static final int MASK_16 = 0xFFFF;
    private static final int MASK_8 = 0xFF;

    /** Tic number held by each slot. MUTABLE: overwritten as tics arrive. */
    private final int[] slotTics;

    /** Forward in the high 16 bits, strafe in the low 16. MUTABLE: per tic. */
    private final int[] axes;

    /** Yaw in the high 16 bits, pitch in bits 8-15, buttons in bits 0-7. MUTABLE: per tic. */
    private final int[] look;

    /** Highest tic accepted per player. MUTABLE: advances as tics arrive. */
    private final int[] latestTics;

    /**
     * Allocates the ring. This is the only allocation the class ever performs.
     */
    public TicCmdBuffer()
    {
        final int slots = Constants.TIC_BUFFER_SIZE * Constants.MAX_PLAYERS;

        this.slotTics = new int[slots];

        this.axes = new int[slots];

        this.look = new int[slots];

        this.latestTics = new int[Constants.MAX_PLAYERS];

        reset();
    }

    /** Returns the ring depth in tics, which is {@link Constants#TIC_BUFFER_SIZE}. */
    public int depth()
    {
        return Constants.TIC_BUFFER_SIZE;
    }

    /** Returns the number of player slots, which is {@link Constants#MAX_PLAYERS}. */
    public int playerCount()
    {
        return Constants.MAX_PLAYERS;
    }

    /**
     * Clears every slot for every player.
     */
    public void reset()
    {
        for (int i = 0; i < slotTics.length; i++)
        {
            slotTics[i] = EMPTY_TIC;

            axes[i] = 0;

            look[i] = 0;
        }

        for (int p = 0; p < latestTics.length; p++)
        {
            latestTics[p] = EMPTY_TIC;
        }
    }

    /**
     * Clears every slot belonging to one player, for use when a peer drops.
     *
     * @param playerSlot the player slot to clear
     */
    public void clear(final int playerSlot)
    {
        requirePlayerSlot(playerSlot);

        final int base = playerSlot * Constants.TIC_BUFFER_SIZE;

        for (int i = 0; i < Constants.TIC_BUFFER_SIZE; i++)
        {
            slotTics[base + i] = EMPTY_TIC;

            axes[base + i] = 0;

            look[base + i] = 0;
        }

        latestTics[playerSlot] = EMPTY_TIC;
    }

    /**
     * Stores a command, rejecting it if the slot already holds a newer tic.
     *
     * @param playerSlot the sending player's slot
     * @param cmd the command to store
     * @return true if stored, false if a newer tic already occupies the slot
     */
    public boolean put(final int playerSlot, final TicCmd cmd)
    {
        return put(playerSlot,
            cmd.ticNumber(),
            cmd.forward(),
            cmd.strafe(),
            cmd.angle(),
            cmd.pitch(),
            cmd.buttons());
    }

    /**
     * Stores loose fields without building a {@code TicCmd} first. This is the
     * zero-allocation receive path.
     *
     * <p>A tic equal to the one already in the slot overwrites it, which is what
     * makes redundant redelivery idempotent. A tic older than the one in the
     * slot is a wrapped straggler and is rejected.
     *
     * @param playerSlot the sending player's slot
     * @param ticNumber the tic this input belongs to; must not be negative
     * @param forward forward axis
     * @param strafe strafe axis
     * @param angle quantized yaw
     * @param pitch quantized pitch
     * @param buttons button bitmask
     * @return true if stored, false if a newer tic already occupies the slot
     */
    public boolean put(final int playerSlot,
                       final int ticNumber,
                       final int forward,
                       final int strafe,
                       final int angle,
                       final int pitch,
                       final int buttons)
    {
        requirePlayerSlot(playerSlot);

        requireTicNumber(ticNumber);

        final int index = index(playerSlot, ticNumber);

        final int held = slotTics[index];

        if (held > ticNumber)
        {
            return false;
        }

        slotTics[index] = ticNumber;

        axes[index] = ((forward & MASK_16) << SHIFT_HIGH) | (strafe & MASK_16);

        look[index] = ((angle & MASK_16) << SHIFT_HIGH)
            | ((pitch & MASK_8) << SHIFT_PITCH)
            | (buttons & MASK_8);

        if (ticNumber > latestTics[playerSlot])
        {
            latestTics[playerSlot] = ticNumber;
        }

        return true;
    }

    /**
     * Decodes a wire command straight into the ring, allocating nothing.
     *
     * @param playerSlot the sending player's slot
     * @param src buffer holding an encoded {@link TicCmd}
     * @param offset index of the first byte of the command
     * @return true if stored, false if a newer tic already occupies the slot
     */
    public boolean putEncoded(final int playerSlot, final byte[] src, final int offset)
    {
        return put(playerSlot,
            TicCmd.decodeTicNumber(src, offset),
            TicCmd.decodeForward(src, offset),
            TicCmd.decodeStrafe(src, offset),
            TicCmd.decodeAngle(src, offset),
            TicCmd.decodePitch(src, offset),
            TicCmd.decodeButtons(src, offset));
    }

    /**
     * Reports whether the ring currently holds the given tic for the given
     * player. A tic more than {@link Constants#TIC_BUFFER_SIZE} old has been
     * overwritten and reads as absent.
     *
     * @param playerSlot the player slot to query
     * @param ticNumber the tic to look for
     * @return true if that exact tic is present
     */
    public boolean has(final int playerSlot, final int ticNumber)
    {
        requirePlayerSlot(playerSlot);

        if (ticNumber < 0)
        {
            return false;
        }

        return slotTics[index(playerSlot, ticNumber)] == ticNumber;
    }

    /** Returns the highest tic accepted for a player, or {@link #EMPTY_TIC} if none. */
    public int latestTic(final int playerSlot)
    {
        requirePlayerSlot(playerSlot);

        return latestTics[playerSlot];
    }

    /**
     * Returns the forward axis for a stored tic, sign-extended.
     *
     * @param playerSlot the player slot to query
     * @param ticNumber the tic to read
     * @return the forward axis
     */
    public int forward(final int playerSlot, final int ticNumber)
    {
        return (short) (axes[requirePresent(playerSlot, ticNumber)] >>> SHIFT_HIGH);
    }

    /**
     * Returns the strafe axis for a stored tic, sign-extended.
     *
     * @param playerSlot the player slot to query
     * @param ticNumber the tic to read
     * @return the strafe axis
     */
    public int strafe(final int playerSlot, final int ticNumber)
    {
        return (short) axes[requirePresent(playerSlot, ticNumber)];
    }

    /**
     * Returns the quantized yaw for a stored tic, zero-extended.
     *
     * @param playerSlot the player slot to query
     * @param ticNumber the tic to read
     * @return the yaw, 0..65535
     */
    public int angle(final int playerSlot, final int ticNumber)
    {
        return (look[requirePresent(playerSlot, ticNumber)] >>> SHIFT_HIGH) & MASK_16;
    }

    /**
     * Returns the quantized pitch for a stored tic, sign-extended.
     *
     * @param playerSlot the player slot to query
     * @param ticNumber the tic to read
     * @return the pitch, -128..127
     */
    public int pitch(final int playerSlot, final int ticNumber)
    {
        return (byte) (look[requirePresent(playerSlot, ticNumber)] >>> SHIFT_PITCH);
    }

    /**
     * Returns the button bitmask for a stored tic, zero-extended.
     *
     * @param playerSlot the player slot to query
     * @param ticNumber the tic to read
     * @return the bitmask, 0..255
     */
    public int buttons(final int playerSlot, final int ticNumber)
    {
        return look[requirePresent(playerSlot, ticNumber)] & MASK_8;
    }

    /**
     * Encodes a stored command into a caller-supplied buffer without
     * allocating. This is how {@link RedundantSender} fills a packet.
     *
     * @param dst destination buffer
     * @param offset index of the first byte written
     * @param playerSlot the player slot to read
     * @param ticNumber the tic to read
     * @return {@link TicCmd#BYTES} if written, or 0 if that tic is not held
     */
    public int encodeInto(final byte[] dst,
                          final int offset,
                          final int playerSlot,
                          final int ticNumber)
    {
        if (!has(playerSlot, ticNumber))
        {
            return 0;
        }

        final int index = index(playerSlot, ticNumber);

        return TicCmd.encodeFields(dst,
            offset,
            ticNumber,
            (short) (axes[index] >>> SHIFT_HIGH),
            (short) axes[index],
            (look[index] >>> SHIFT_HIGH) & MASK_16,
            (byte) (look[index] >>> SHIFT_PITCH),
            look[index] & MASK_8);
    }

    /**
     * Materialises a stored command as an immutable value. Allocates, so this
     * is for tests and cold paths; the per-field accessors do not.
     *
     * @param playerSlot the player slot to read
     * @param ticNumber the tic to read
     * @return the stored command, or null if that tic is not held
     */
    public TicCmd get(final int playerSlot, final int ticNumber)
    {
        if (!has(playerSlot, ticNumber))
        {
            return null;
        }

        final int index = index(playerSlot, ticNumber);

        return new TicCmd(ticNumber,
            (short) (axes[index] >>> SHIFT_HIGH),
            (short) axes[index],
            (look[index] >>> SHIFT_HIGH) & MASK_16,
            (byte) (look[index] >>> SHIFT_PITCH),
            look[index] & MASK_8);
    }

    // Flat index into the rings. TIC_BUFFER_SIZE is a power of two, so the
    // modulo lowers to a mask; it is written as a modulo for readability.
    private static int index(final int playerSlot, final int ticNumber)
    {
        return playerSlot * Constants.TIC_BUFFER_SIZE + ticNumber % Constants.TIC_BUFFER_SIZE;
    }

    // Resolves the slot and fails loudly if the tic is not actually held.
    private int requirePresent(final int playerSlot, final int ticNumber)
    {
        if (!has(playerSlot, ticNumber))
        {
            throw new IllegalArgumentException(
                "no command held for player " + playerSlot + " tic " + ticNumber);
        }

        return index(playerSlot, ticNumber);
    }

    private static void requirePlayerSlot(final int playerSlot)
    {
        if (playerSlot < 0 || playerSlot >= Constants.MAX_PLAYERS)
        {
            throw new IllegalArgumentException("playerSlot out of range: " + playerSlot);
        }
    }

    private static void requireTicNumber(final int ticNumber)
    {
        if (ticNumber < 0)
        {
            throw new IllegalArgumentException("ticNumber must not be negative: " + ticNumber);
        }
    }
}
