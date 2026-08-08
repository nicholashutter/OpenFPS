/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;

/**
 * P_ The player's own forearms and hands, generated.
 *
 * <p>One procedurally-authored model, used by the local player's first-person
 * view. The Kenney Blaster Kit is a <i>weapon</i> pack: it ships the gun, not
 * the arms that hold it, and adding a CC0 arms model would be a fresh asset
 * dependency for one screen. The viewmodel {@code blaster-b.ofm} that ships
 * today is, by itself, a pistol floating in the lower-right corner of the
 * frame — readable as a gun, unreadable as a held gun. A pair of procedurally
 * generated arms, positioned at the grip and reaching back to below the eye,
 * is what turns "a floating pistol" into "a player's hands holding a
 * pistol".</p>
 *
 * <h2>Why the model is procedural, and not a third-party mesh</h2>
 *
 * <p>{@code docs/ASSETS.md} § 6 keeps upstream art out of git. The arms are
 * part of the demo, so they have to be present in a fresh clone; routing them
 * through the offline converter would mean shipping one more {@code .ofm}
 * file, a new build step, and a new failure mode for geometry that is eight
 * boxes of arithmetic. The converter exists to move <i>expensive</i> work
 * offline, and nothing about four boxes of an arm is expensive.</p>
 *
 * <p>The same precedent {@link BlockCarbine} sets: a procedural stand-in
 * authored to the right box, deliberately plain, that the demo can always
 * rely on. A pair of arms is no different in kind from a stand-in carbine —
 * the difference is which slot of the body they fill.</p>
 *
 * <h2>The authoring is in view space, and the placement rotates it</h2>
 *
 * <p>Every box below is positioned in <b>view space</b> — {@code +X} right,
 * {@code +Y} up, {@code +Z} forward of the eye — so the model has no idea what
 * the player is looking at. The placement, built by
 * {@link LocalPlayerBody#armsTransform}, applies the player's yaw and pitch as
 * a rotation and the eye position as a translation, and the arms end up
 * fixed in the view at the offsets the boxes were authored to. The bob is
 * a single extra Y translation in <i>world</i> space, applied after the
 * rotation, so the arms bounce up and down rather than swaying in the view.</p>
 *
 * <h2>Why the hands are placed at the grip, not around it</h2>
 *
 * <p>The viewmodel is a <b>view instance</b> drawn over a depth-cleared
 * buffer — that is what stops it clipping into a wall the player stands
 * against, and it is also what makes it always draw on top of any world
 * instance. A hand that wrapped around the front of the grip would be partly
 * hidden by the gun, and "the fingers are missing" is the detail that
 * telegraphs a faked hand. The boxes below put the hands AT the grip end and
 * slightly below it, where the back of the hand is what the player sees and
 * the palm/fingers are out of frame — the same "back-of-hand only" pose that
 * most first-person shooters use to dodge the depth-clear problem.</p>
 *
 * <h2>Flat-shaded, deliberately</h2>
 *
 * <p>No submeshes and no textures, for the same reason {@link BlockCarbine}
 * and {@code DemoEffects}'s box are flat-shaded: every triangle takes the
 * flat path and gets its baked vertex colour, which is what keeps the model
 * to a handful of triangles and a single byte per pixel.</p>
 */
public final class FirstPersonArms
{
    /** Coordinates in one box corner: x, y and z. */
    private static final int AXES = 3;

    /** Corners of a box. */
    private static final int BOX_VERTICES = 8;

    /** Triangles per box — two per face, six faces. */
    private static final int BOX_TRIANGLES = 12;

    /** Floats per entry in {@link #PARTS}: two corners of three coordinates. */
    private static final int PART_STRIDE = 6;

    /**
     * Skin colour, for the back of the hands. A warm beige that reads as
     * "human skin" against the bluish Kenney room.
     */
    private static final int SKIN_COLOUR = Rgba.pack(214, 178, 138, 255);

    /**
     * Sleeve colour, for the forearms. Dark grey-blue rather than a uniform
     * colour, because the rest of the demo's silhouettes (the bots'
     * blocky-character shirts) sit in the same hue band and the arms have to
     * not be mistaken for a sleeve of one of them. A dark suit sleeve is
     * also what the arms look like in the closest real reference, which is
     * a first-person shooter from the early 2000s.
     */
    private static final int SLEEVE_COLOUR = Rgba.pack(46, 56, 78, 255);

    /**
     * Cuff colour, for the ring where sleeve meets hand. A single tone
     * between sleeve and skin, because the cuff is a small detail and a third
     * mid-tone is what stops it reading as a separate block.
     */
    private static final int CUFF_COLOUR = Rgba.pack(94, 96, 102, 255);

    /**
     * The seven boxes, as {@code minX, minY, minZ, maxX, maxY, maxZ} in
     * <b>view space</b>: {@code +X} is right, {@code +Y} is up,
     * {@code +Z} is <b>behind</b> the eye (a part in front of the eye has
     * negative Z).
     *
     * <p>The local-to-world transform is a right-handed basis
     * {@code (right, up, -forward)}, so the model is in a right-handed
     * view space and the matrix has positive determinant — the only form
     * {@code Scene} accepts. Parts that should appear in front of the
     * player have negative Z, so the hands at the grip end up at positive
     * Z in world space. The two conventions read identically: "the hands
     * are in front of the eye" — the difference is the sign of Z.</p>
     *
     * <p>Two forearms come up from below the eye and meet the two hands,
     * which are placed at the grip end of the held weapon. The grip sits
     * roughly at {@code (0.92, -0.38, 1.45)} in world space — see
     * {@code DemoScene.WEAPON_VIEW_*} and the muzzle-flip arithmetic the
     * viewmodel placement records — and the hands are placed at and below
     * the grip, with the forearms angled to follow them back toward the
     * eye.</p>
     *
     * <p>Each forearm is one long box, not a chain of short ones, so the
     * visible silhouette is a smooth taper rather than a row of segments.
     * The hands are flat boxes at the grip end, deliberately thin along Z
     * (the axis the gun runs along) so they sit at the back of the grip
     * rather than wrapping around it — see the class Javadoc on why.</p>
     */
    private static final float[] PARTS =
    {
        // Right forearm: from below-right of the eye, up and forward to the
        // right hand at the grip. Sized so the visible silhouette is about
        // a third of the weapon's length. minZ is FURTHER from the eye than
        // maxZ because the part is in front of the eye and z is negative.
        0.32f, -0.92f, -0.92f, 0.58f, -0.42f, -0.30f,
        // Right cuff: a short ring at the wrist, between sleeve and hand.
        0.46f, -0.50f, -0.96f, 0.62f, -0.40f, -0.84f,
        // Right hand: a flat box at the back of the grip, just below it. The
        // back of the hand faces the camera; fingers/palm are out of frame.
        0.74f, -0.62f, -1.54f, 0.98f, -0.42f, -1.36f,
        // Left forearm: from below-left of the eye, up and forward to the
        // left hand at the fore-end of the grip.
        -0.18f, -0.92f, -0.96f, 0.08f, -0.42f, -0.36f,
        // Left cuff: matching the right one.
        0.00f, -0.50f, -1.00f, 0.16f, -0.40f, -0.88f,
        // Left hand: at the fore-end of the grip, below it. Closer to the
        // muzzle than the right hand and slightly to the left of the gun.
        0.60f, -0.62f, -1.68f, 0.84f, -0.42f, -1.50f,
        // Chest: a flat wedge visible at the bottom of the frame, in the
        // sleeve colour, so the arms have something to come out of. Without
        // it the sleeves end in space at the edge of the frame, which the
        // eye reads as "the arms are floating".
        -0.55f, -1.10f, -0.40f, 0.85f, -0.88f, -0.10f,
    };

    /** Which colour each of {@link #PARTS} is baked in. */
    private static final int[] COLOURS =
    {
        SLEEVE_COLOUR, CUFF_COLOUR, SKIN_COLOUR,
        SLEEVE_COLOUR, CUFF_COLOUR, SKIN_COLOUR,
        SLEEVE_COLOUR,
    };

    /** How many boxes the arms are built from. */
    public static int partCount()
    {
        return PARTS.length / PART_STRIDE;
    }

    private FirstPersonArms()
    {
        // geometry holder
    }

    /**
     * Builds the arms model.
     *
     * <p>Allocates, and is called <b>once</b> while {@link DemoModels} (or
     * the equivalent builder) is loading. It is not on any per-tic or
     * per-frame path.</p>
     *
     * @return the arms, authored in view space, never null
     */
    public static ModelFormat model()
    {
        final int parts = partCount();
        final int[] vertices = new int[parts * BOX_VERTICES * ModelFormat.VERTEX_STRIDE_INTS];
        final int[] indices =
            new int[parts * BOX_TRIANGLES * ModelFormat.INDICES_PER_TRIANGLE];
        // MUTABLE local — where the next box's indices are written.
        int at = 0;
        for (int part = 0; part < parts; part++)
        {
            writeBox(vertices, part, COLOURS[part]);
            at = writeBoxIndices(indices, at, part * BOX_VERTICES);
        }
        return ModelFormat.ofGeometry(vertices, indices);
    }

    private static void writeBox(final int[] vertices, final int part, final int colour)
    {
        final int base = part * PART_STRIDE;
        final float lowX = PARTS[base];
        final float lowY = PARTS[base + 1];
        final float lowZ = PARTS[base + 2];
        final float highX = PARTS[base + AXES];
        final float highY = PARTS[base + AXES + 1];
        final float highZ = PARTS[base + AXES + 2];
        final int first = part * BOX_VERTICES;
        ModelFormat.writeVertex(vertices, first, lowX, lowY, lowZ, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, first + 1, highX, lowY, lowZ, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, first + 2, highX, highY, lowZ, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, first + 3, lowX, highY, lowZ, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, first + 4, lowX, lowY, highZ, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, first + 5, highX, lowY, highZ, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, first + 6, highX, highY, highZ, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, first + 7, lowX, highY, highZ, 0.0f, 0.0f, colour);
    }

    // Twelve triangles wound COUNTER-CLOCKWISE SEEN FROM OUTSIDE — the same
    // winding BlockCarbine and DemoEffects use. Winding backwards and the
    // arms render inside-out, which looks like a plausible shape rather than
    // like an error.
    private static int writeBoxIndices(final int[] indices, final int at, final int first)
    {
        final int[] winding =
        {
            4, 5, 6, 4, 6, 7,
            1, 0, 3, 1, 3, 2,
            5, 1, 2, 5, 2, 6,
            0, 4, 7, 0, 7, 3,
            7, 6, 2, 7, 2, 3,
            0, 1, 5, 0, 5, 4,
        };
        for (int slot = 0; slot < winding.length; slot++)
        {
            indices[at + slot] = first + winding[slot];
        }
        return at + winding.length;
    }
}
