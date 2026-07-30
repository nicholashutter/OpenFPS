/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;

/**
 * S_ A carbine built from six boxes of arithmetic, for when the real one was
 * never staged.
 *
 * <h2>Why this exists — the bug it closes</h2>
 *
 * <p>The opponents' weapon is {@code blaster-p.ofm}, a Kenney Blaster Kit
 * carbine. {@code assets/} is gitignored ({@code docs/ASSETS.md} § 6), the model
 * is produced by {@code :tools:regenerateDemoAssets}, and <b>a payload staged
 * before {@code blaster-p} joined the curated list does not contain it</b>.
 * {@link DemoModels} then returned null, {@code DemoScene.addBotWeapon} returned
 * {@link DemoScene#NO_INSTANCE}, and no weapon instance was ever put in the
 * scene at all.</p>
 *
 * <p><b>Measured, because that is how this project settles "is it there":</b> the
 * bot weapon instance was tinted a colour no asset in either pack contains and
 * the frame was counted at 1280x720. With the file absent the tint covered
 * <b>0 pixels</b> — not a smudge, not a cross-section, nothing. With the file
 * present it covered <b>3,454</b>, in a band at chest height across five visible
 * bodies. The weapon was not too small, not edge-on and not inside anybody: it
 * did not exist. Two rounds of "the carbines are not visible" were reporting
 * exactly that, and both were right.</p>
 *
 * <p>A {@code WARN} was already logged and had been for as long as the feature
 * existed. It is the correct log and it changed nothing, because a warning in a
 * console scrollback is not a thing a player looking at a room can see. So the
 * demo no longer has a state in which the opponents are unarmed to look at:
 * absent art costs the <i>art</i>, not the feature. That is the same call
 * {@link DemoEffects} already makes for the tracer and the smoke — "generated
 * rather than loaded, so unlike every other model in this scene it cannot fail to
 * be staged" — and the reason it matters more here is that incoming fire is
 * specified to come out of a weapon. With no weapon there is nowhere for it to
 * come from.</p>
 *
 * <h2>Authored in {@code blaster-p}'s own frame, deliberately</h2>
 *
 * <p>Every extent below is the real model's, read out of its {@code .ofm} header
 * rather than eyeballed: {@code x +-0.08}, {@code y +-0.185},
 * {@code z +-0.431} — 0.16 x 0.37 x 0.86 model units, centred on its own origin,
 * <b>muzzle along model -z</b>. Matching it is not tidiness. Three separate
 * constants are solved against that geometry —
 * {@link DemoScene#BOT_WEAPON_WORLD_SCALE},
 * {@link DemoScene#BOT_WEAPON_YAW_DEGREES}'s muzzle flip, and
 * {@link DemoScene#BOT_WEAPON_MUZZLE_UNITS}, which is where a tracer and a puff
 * of smoke are born — and every one of them keeps working unchanged only if the
 * substitute occupies the same box and points the same way. A fallback that
 * needed its own copies of those three numbers would be a second description of
 * the same fact, and the failure mode of a second description is smoke coming out
 * of the wrong end of a gun on machines nobody tested.</p>
 *
 * <h2>Plainly generated, and that is a requirement</h2>
 *
 * <p>{@code docs/ASSETS.md} § 7 wants provenance recorded honestly, and
 * {@code DemoAssetsMain} sets the precedent it is worth copying: the fallback
 * room is called {@code generated-room.ofm} and logged at {@code WARN} so nobody
 * records greybox against an upstream source. This is flat gunmetal boxes with no
 * texture — it reads as a weapon at across-the-room distances, which is the whole
 * job, and it reads as <i>not the Kenney model</i> from two paces. Making it
 * prettier would make it harder to tell the two payloads apart, which is the
 * opposite of what is wanted.</p>
 *
 * <p>No submesh table and no texture, so every triangle takes the flat path and
 * the baked vertex colour — the same contract {@code DemoEffects}' box and sphere
 * use, and for the same reason.</p>
 */
public final class BlockCarbine
{
    /** Half-width of the real model, model units — {@code blaster-p.ofm}'s own. */
    public static final float HALF_WIDTH = 0.08f;

    /** Half-height of the real model, model units. */
    public static final float HALF_HEIGHT = 0.1854509f;

    /**
     * Half-length of the real model along z, model units — <b>0.431</b>.
     *
     * <p>The number {@link DemoScene#BOT_WEAPON_MUZZLE_UNITS} is derived from,
     * and the reason this class exists as a shaped substitute rather than as a
     * plain cube: the muzzle is at {@code z = -}this, and a puff of smoke has to
     * appear there whichever model is standing in.</p>
     */
    public static final float HALF_LENGTH = 0.43104f;

    /** Coordinates in one box corner: x, y and z. */
    private static final int AXES = 3;

    /** Corners of a box. */
    private static final int BOX_VERTICES = 8;

    /** Triangles per box — two per face, six faces. */
    private static final int BOX_TRIANGLES = 12;

    /**
     * Gunmetal, for the parts a hand goes near: receiver, stock, magazine, grip.
     *
     * <p>Dark rather than light, because the demo room's lit walls sample at
     * about {@code (141, 147, 177)} and a weapon is only a silhouette if it is
     * darker than what is behind it — the same measurement
     * {@code DemoEffects.smokeColour} is solved against. It is also nothing like
     * the real carbine's green, which is the point: two payloads that look the
     * same are two payloads nobody can tell apart in a screenshot.</p>
     */
    private static final int BODY_COLOUR = Rgba.pack(78, 84, 92, 255);

    /** Darker still, for the barrel and the sight, so the shape has some structure. */
    private static final int STEEL_COLOUR = Rgba.pack(46, 50, 56, 255);

    /**
     * The six boxes, as {@code minX, minY, minZ, maxX, maxY, maxZ} in model
     * units, in the order {@link #COLOURS} names them.
     *
     * <p>Laid out so the union spans exactly the real model's box: the magazine
     * reaches {@code -HALF_HEIGHT}, the sight reaches {@code +HALF_HEIGHT}, the
     * barrel reaches {@code -HALF_LENGTH} — which is the muzzle — and the stock
     * reaches {@code +HALF_LENGTH}. {@code BlockCarbineTest} asserts that union
     * against {@link ModelFormat}'s own bounds rather than trusting the table.</p>
     */
    private static final float[] PARTS =
    {
        // barrel, from the muzzle back into the receiver
        -0.028f, -0.020f, -HALF_LENGTH, 0.028f, 0.038f, -0.060f,
        // receiver, the thick middle
        -0.055f, -0.060f, -0.100f, 0.055f, 0.090f, 0.200f,
        // stock, back to the butt
        -0.045f, -0.050f, 0.200f, 0.045f, 0.075f, HALF_LENGTH,
        // magazine, hanging below
        -0.035f, -HALF_HEIGHT, 0.020f, 0.035f, -0.050f, 0.120f,
        // grip, behind the magazine
        -0.040f, -0.160f, 0.160f, 0.040f, -0.050f, 0.260f,
        // sight, standing on the receiver
        -HALF_WIDTH, 0.090f, -0.020f, HALF_WIDTH, HALF_HEIGHT, 0.080f,
    };

    /** Which colour each of {@link #PARTS} is baked in. */
    private static final int[] COLOURS =
    {
        STEEL_COLOUR, BODY_COLOUR, BODY_COLOUR, BODY_COLOUR, BODY_COLOUR, STEEL_COLOUR,
    };

    /** Floats per entry in {@link #PARTS}: two corners of three coordinates. */
    private static final int PART_STRIDE = 6;

    private BlockCarbine()
    {
        // geometry holder
    }

    /**
     * Returns how many boxes the carbine is built from.
     *
     * <p>Exposed so a test can assert the triangle count against the shape rather
     * than against a number somebody copied out of a log line.</p>
     *
     * @return the part count
     */
    public static int partCount()
    {
        return PARTS.length / PART_STRIDE;
    }

    /**
     * Builds the substitute carbine.
     *
     * <p>Allocates, and is called <b>once</b>, while {@link DemoModels} is
     * loading. It is not on any per-tic or per-frame path.</p>
     *
     * @return a carbine occupying the same model-space box as
     *     {@code blaster-p.ofm}, muzzle along model -z
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

    // The eight corners of one box, in the corner order writeBoxIndices winds
    // against: low-low-low, then round the -z face, then the same round +z.
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

    // Twelve triangles wound COUNTER-CLOCKWISE SEEN FROM OUTSIDE — the glTF 2.0
    // rule, which is what SoftwareRenderPort.BACKFACE_CULL_MODE was measured
    // against. Copied corner-for-corner from DemoEffects.box rather than
    // re-derived: get the winding backwards and the boxes render inside-out,
    // which looks like a plausible shape rather than like an error.
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
