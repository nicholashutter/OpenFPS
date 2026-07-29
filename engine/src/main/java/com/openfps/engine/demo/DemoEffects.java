/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

/**
 * What a shot looks like: a tracer flying down the aim ray and a puff of smoke
 * at the muzzle.
 *
 * <h2>Pre-allocated instances, moved — never added</h2>
 *
 * <p>{@link Scene} is immutable and nothing can be added to one after it is
 * built. That is load-bearing rather than inconvenient: it is what makes
 * rendering a scene allocate nothing and share safely across the render
 * workers. So an effect is not created when it is fired — <b>every instance it
 * will ever need is placed in the scene before the loop starts</b>, and firing
 * moves one, exactly as a walking bot moves one
 * ({@code SoftwareRenderPort.setWorldTransform}).</p>
 *
 * <p>An instance that is not currently part of a live effect is <b>hidden with
 * a degenerate transform</b> — {@link #HIDDEN}, which is all zeroes bar the
 * bottom row. It collapses every vertex onto one point, so every triangle has
 * zero screen area and {@code Rasterizer} rejects it before it reaches a pixel.
 * {@link Scene.Builder} would refuse such a transform outright, and rightly:
 * as a <i>placement</i> it is meaningless. As an override it is the cheapest
 * possible "not right now" — one reference store, no pass structure changed,
 * nothing re-derived.</p>
 *
 * <h2>The counts are fixed, and the pool wraps rather than fails</h2>
 *
 * <p>{@link #MAX_TRACERS} and {@link #MAX_PUFFS} are hard maxima, chosen
 * against the weapon's rate of fire rather than picked round:
 * {@code DemoGameplayPort.FIRE_INTERVAL_TICS} is 12, so a shot lands every 12
 * tics and {@link #PUFF_LIFE_TICS} of 18 means at most two puffs overlap. The
 * pools are one larger than that arithmetic needs, and a spawn that finds every
 * slot busy <b>overwrites the oldest</b> rather than being dropped. Round-robin
 * makes that O(1) and total: there is no failure path to test, and the worst
 * case is that a tracer nobody was looking at disappears a few tics early.</p>
 *
 * <h2>Why the smoke is several instances per puff</h2>
 *
 * <p>A puff has to <b>fade</b>, and coverage is fixed when a {@link Scene} is
 * built — see {@code Scene}'s own note on why that is a deliberate immutability
 * rather than an oversight. So one puff owns {@link #PUFF_STAGES} instances,
 * one per rung of a descending coverage ladder, and exactly one of them is
 * visible at a time: the puff ages, the stage advances, the previous stage is
 * hidden and the next is shown. The fade is a staircase rather than a ramp,
 * which at 60 Hz over 18 tics nobody can see, and it costs the render port
 * nothing per frame — the hidden stages are culled before the rasterizer.</p>
 *
 * <p>The <b>expansion</b> is continuous, because scale lives in the transform
 * and the transform is the thing that is allowed to change.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>All effect state is primitive arrays sized at construction. A tic
 * allocates one {@link Mat4} per <i>visible</i> effect and nothing else —
 * {@code setWorldTransform} takes a matrix, so that is the price of the seam,
 * and it is the same price {@code DemoScene.botPlacement} already pays per bot.
 * A slot that is hidden and was hidden last tic is not republished at all, so
 * an idle trigger costs nothing whatsoever.</p>
 */
public final class DemoEffects
{
    /** Tracers that can be in the air at once. */
    public static final int MAX_TRACERS = 3;

    /** Tics a tracer flies before it is hidden. */
    public static final int TRACER_LIFE_TICS = 8;

    /** Smoke puffs that can be visible at once. */
    public static final int MAX_PUFFS = 3;

    /** Tics a puff lives. Longer than the fire interval, so two can overlap. */
    public static final int PUFF_LIFE_TICS = 18;

    /** Coverage rungs a puff fades down, one instance each. */
    public static final int PUFF_STAGES = 4;

    /**
     * World units a tracer covers per tic — 60.
     *
     * <p><b>Bounded by the room, not by realism.</b> The demo room's interior
     * is 640 units across and the player spawns 192 back from its centre, so
     * the far wall is about 512 units down the spawn bearing. Speed times
     * {@link #TRACER_LIFE_TICS} therefore has to stay under that or the bolt
     * spends most of its life outside the room, correctly depth-tested away by
     * the wall, and the effect looks broken. 60 x 8 is 480, which lands just
     * short. It first shipped at 140, which put the bolt through the wall after
     * three tics and made it invisible in exactly the screenshot taken to
     * check it.</p>
     *
     * <p>60 units a tic at 60 Hz is 3,600 units a second, about fourteen times
     * the player's own speed, which is what makes it read as fired rather than
     * thrown.</p>
     */
    public static final float TRACER_SPEED_UNITS = 60.0f;

    /** How long the tracer bolt is, along its direction of travel. */
    public static final float TRACER_LENGTH_UNITS = 46.0f;

    /**
     * How wide the tracer bolt is across that direction — 11 units.
     *
     * <p>Wide relative to its length on purpose. A shot travels <b>away</b>
     * from the eye that fired it, so the bolt is seen almost end-on and its
     * length is foreshortened to nearly nothing; what the player actually sees
     * is its cross-section, shrinking as it recedes. Sizing this from how a
     * tracer looks from the side would make it invisible from the one angle
     * that matters.</p>
     */
    public static final float TRACER_WIDTH_UNITS = 11.0f;

    /**
     * How far in front of the eye the muzzle is, in world units — 2.4.
     *
     * <p>Small, and deliberately so: it is where the weapon <b>appears</b> to
     * be, not where a rifle barrel would be on a body. The viewmodel is a view
     * instance sized for the screen rather than for the world
     * ({@code DemoScene.WEAPON_VIEW_SCALE}), and it sits 1.85 view units ahead
     * of the eye. View units and world units are the same units once past the
     * camera — view space is the world, rotated and translated — so 2.4 puts
     * the effect just beyond the drawn muzzle. Anything larger would have the
     * smoke appear detached, out in the room ahead of the gun.</p>
     */
    public static final float MUZZLE_FORWARD_UNITS = 2.4f;

    /** How far right of the eye the muzzle is, matching the weapon's own offset. */
    public static final float MUZZLE_RIGHT_UNITS = 0.9f;

    /** How far below the eye the muzzle is. */
    public static final float MUZZLE_DROP_UNITS = 0.30f;

    /**
     * Half-extent of a puff when it is born, in world units — 0.14.
     *
     * <p>Sub-unit numbers, which look wrong beside a 41-unit eye height until
     * you notice what they are measured against: the puff sits
     * {@link #MUZZLE_FORWARD_UNITS} from the eye, so its <i>apparent</i> size
     * is the ratio of the two. 0.14 at 2.4 units subtends about the same angle
     * as 37 units would at the far wall. Sized in the room's units instead it
     * would fill the screen.</p>
     */
    public static final float PUFF_RADIUS_START = 0.14f;

    /** Half-extent of a puff at the end of its life. See {@link #PUFF_RADIUS_START}. */
    public static final float PUFF_RADIUS_END = 0.34f;

    /**
     * World units a puff drifts upward per tic — 0.010.
     *
     * <p>Tiny, and it has to be. The puff sits 2.4 units from the eye, so a
     * rise is magnified by the same ratio that makes
     * {@link #PUFF_RADIUS_START} small: over {@link #PUFF_LIFE_TICS} this is
     * 0.18 units, which is about 47 screen pixels at 720p. At 0.022, which is
     * where it started, the same drift carried the puff a hundred pixels up the
     * screen and left it hanging above the weapon rather than at its
     * muzzle.</p>
     */
    public static final float PUFF_RISE_UNITS = 0.010f;

    /** Sentinel age meaning "this slot holds no effect". */
    public static final int DEAD = -1;

    /**
     * The override that makes an instance invisible: everything zero but the
     * bottom row.
     *
     * <p>See the class Javadoc. Immutable and therefore shared — hiding costs
     * one reference store and no allocation at all, which matters because most
     * of these instances are hidden most of the time.</p>
     */
    public static final Mat4 HIDDEN = Mat4.ofRowMajor(new float[]
    {
        0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f,
    });

    /** Hot amber, the colour of a bolt in flight. */
    private static final int TRACER_COLOUR = Rgba.pack(255, 216, 112, 255);

    /**
     * Mid grey, the colour of powder smoke.
     *
     * <p>Darker than smoke "should" be, and deliberately: the demo room is a
     * pale grey-blue and a near-white puff composited over it at any coverage
     * is a slightly brighter patch of nothing. Contrast is what makes it read
     * as an object rather than a rendering artefact.</p>
     */
    private static final int SMOKE_COLOUR = Rgba.pack(158, 158, 152, 255);

    /**
     * Coverage of each puff stage, faintest last.
     *
     * <p>Every rung is a distinct blended {@code SpanRenderer} in the render
     * port and a potential extra batched pass, so this is four values rather
     * than sixteen. Four steps over 18 tics is a step every 4-5 tics, which
     * reads as a fade.</p>
     */
    private static final int[] PUFF_COVERAGE = {150, 114, 78, 42};

    /** Coordinates per position or direction triple. */
    private static final int AXES = 3;

    /**
     * Below this, a direction is treated as parallel to world up and the tracer
     * basis is built against a different reference axis.
     *
     * <p>Not a tolerance to be tuned: looking straight up or straight down is a
     * thing a player does constantly, and {@code cross(up, aim)} is exactly zero
     * there. Without this the tracer's basis would be all NaN and the instance
     * would vanish — silently, and only while aiming at the ceiling.</p>
     */
    private static final float PARALLEL_EPSILON = 1.0e-4f;

    /** Corners of a box. */
    private static final int BOX_VERTICES = 8;

    /** Scene instance index of each tracer. */
    private final int[] tracerInstance;

    /** Scene instance index of each puff stage, {@code puff * PUFF_STAGES + stage}. */
    private final int[] puffInstance;

    /** Tics each tracer has left, or {@link #DEAD}. MUTABLE: aged every tic. */
    private final int[] tracerRemaining;

    /** Where each tracer is, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] tracerPosition;

    /** Which way each tracer flies, unit length, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] tracerDirection;

    /** Whether each tracer's instance is currently shown. MUTABLE. */
    private final boolean[] tracerShown;

    /** Tics each puff has lived, or {@link #DEAD}. MUTABLE. */
    private final int[] puffAge;

    /** Where each puff sits, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] puffPosition;

    /** Which stage instance of each puff is shown, or {@link #DEAD}. MUTABLE. */
    private final int[] puffShownStage;

    /**
     * Scratch for the vector across the aim direction. MUTABLE, and a field
     * rather than a local so that spawning and publishing allocate nothing.
     *
     * <p>Safe to share between the two because both run under
     * {@code DemoGameplayPort}'s tic lock and neither is re-entrant — one tic
     * is atomic, which is the same guarantee the controller relies on.</p>
     */
    private final float[] acrossScratch = new float[AXES];

    /** Next tracer slot a spawn will claim. MUTABLE: round-robin cursor. */
    private int tracerCursor;

    /** Next puff slot a spawn will claim. MUTABLE: round-robin cursor. */
    private int puffCursor;

    /**
     * Whether every instance has been hidden once. MUTABLE: set by the first
     * {@link #publish}.
     *
     * <p><b>Without this the whole pool is visible, piled on the world origin,
     * until each slot is first used.</b> {@link Scene.Builder} validates the
     * placement of everything it is given and refuses a degenerate one — see
     * {@link #HIDDEN} — so these instances have to enter the scene at some
     * legal transform, and identity is the only honest choice. That leaves
     * fourteen unit boxes stacked at the origin, which in the demo room is a
     * few pixels of amber and grey on the floor ahead of the spawn: small
     * enough to look like a texture artefact, and visible in the very first
     * screenshot taken with the trigger released.</p>
     *
     * <p>The first publish is the earliest moment an override can exist, so it
     * is where the pool puts itself out of sight. A flag rather than a separate
     * {@code hideAll()} the caller must remember to call, because a caller that
     * forgets gets exactly the bug this replaced.</p>
     */
    private boolean hidden;

    // Takes ownership of the two index tables the builder handed back.
    private DemoEffects(final int[] tracerIndices, final int[] puffIndices)
    {
        this.tracerInstance = tracerIndices;
        this.puffInstance = puffIndices;
        this.tracerRemaining = new int[MAX_TRACERS];
        this.tracerPosition = new float[MAX_TRACERS * AXES];
        this.tracerDirection = new float[MAX_TRACERS * AXES];
        this.tracerShown = new boolean[MAX_TRACERS];
        this.puffAge = new int[MAX_PUFFS];
        this.puffPosition = new float[MAX_PUFFS * AXES];
        this.puffShownStage = new int[MAX_PUFFS];
        for (int slot = 0; slot < MAX_TRACERS; slot++)
        {
            tracerRemaining[slot] = DEAD;
        }
        for (int slot = 0; slot < MAX_PUFFS; slot++)
        {
            puffAge[slot] = DEAD;
            puffShownStage[slot] = DEAD;
        }
    }

    /**
     * Places every effect instance this demo will ever need into a scene under
     * construction, and returns the pool that drives them.
     *
     * <p>Call once, while the scene is being built. Each instance is added at
     * its own home placement — a legal transform, because {@link Scene.Builder}
     * validates every one it is given — and is hidden by the first
     * {@link #publish} before a frame is ever drawn.</p>
     *
     * @param builder the scene under construction; instances are appended
     * @return the effect pool, holding the scene index of everything it placed
     */
    public static DemoEffects addTo(final Scene.Builder builder)
    {
        if (builder == null)
        {
            throw new IllegalArgumentException("builder must not be null");
        }
        final ModelFormat bolt = box(TRACER_COLOUR);
        final ModelFormat cloud = box(SMOKE_COLOUR);

        final int[] tracers = new int[MAX_TRACERS];
        for (int slot = 0; slot < MAX_TRACERS; slot++)
        {
            tracers[slot] = builder.worldInstanceCount();
            builder.addWorldInstance(bolt, Mat4.identity());
        }

        final int[] puffs = new int[MAX_PUFFS * PUFF_STAGES];
        for (int puff = 0; puff < MAX_PUFFS; puff++)
        {
            for (int stage = 0; stage < PUFF_STAGES; stage++)
            {
                puffs[puff * PUFF_STAGES + stage] = builder.worldInstanceCount();
                builder.addTranslucentWorldInstance(cloud, Mat4.identity(), Scene.UNTAGGED,
                    PUFF_COVERAGE[stage]);
            }
        }
        return new DemoEffects(tracers, puffs);
    }

    /**
     * Starts a tracer and a puff of smoke for one shot.
     *
     * <p>The muzzle is derived here from the eye and the aim rather than passed
     * in, so there is one definition of where a shot comes from. Both effects
     * start there; the tracer then leaves along {@code aim} and the smoke stays
     * put and rises.</p>
     *
     * @param eyeX eye position, world x
     * @param eyeY eye position, world y
     * @param eyeZ eye position, world z
     * @param aimX aim direction, world x; should be unit length
     * @param aimY aim direction, world y
     * @param aimZ aim direction, world z
     */
    public void spawn(final float eyeX, final float eyeY, final float eyeZ,
        final float aimX, final float aimY, final float aimZ)
    {
        final float[] right = acrossScratch;
        crossWithReference(aimX, aimY, aimZ, right);

        final float muzzleX = eyeX + aimX * MUZZLE_FORWARD_UNITS + right[0] * MUZZLE_RIGHT_UNITS;
        final float muzzleY = eyeY + aimY * MUZZLE_FORWARD_UNITS + right[1] * MUZZLE_RIGHT_UNITS
            - MUZZLE_DROP_UNITS;
        final float muzzleZ = eyeZ + aimZ * MUZZLE_FORWARD_UNITS + right[2] * MUZZLE_RIGHT_UNITS;

        spawnTracer(muzzleX, muzzleY, muzzleZ, aimX, aimY, aimZ);
        spawnPuff(muzzleX, muzzleY, muzzleZ);
    }

    // One bolt, at the muzzle and not yet moving.
    //
    // It is never DRAWN there: the caller advances before it publishes, which
    // matters because the muzzle is 2.4 units from the eye and the bolt is 40
    // units long — drawn at the muzzle it would enclose the camera, and most of
    // it would be behind the near plane. See DemoGameplayPort.tick, which owns
    // that ordering.
    private void spawnTracer(final float x, final float y, final float z,
        final float aimX, final float aimY, final float aimZ)
    {
        final int slot = tracerCursor;
        this.tracerCursor = (tracerCursor + 1) % MAX_TRACERS;
        final int at = slot * AXES;
        tracerPosition[at] = x;
        tracerPosition[at + 1] = y;
        tracerPosition[at + 2] = z;
        tracerDirection[at] = aimX;
        tracerDirection[at + 1] = aimY;
        tracerDirection[at + 2] = aimZ;
        tracerRemaining[slot] = TRACER_LIFE_TICS;
    }

    // One puff, at rest at the muzzle.
    private void spawnPuff(final float x, final float y, final float z)
    {
        final int slot = puffCursor;
        this.puffCursor = (puffCursor + 1) % MAX_PUFFS;
        final int at = slot * AXES;
        puffPosition[at] = x;
        puffPosition[at + 1] = y;
        puffPosition[at + 2] = z;
        puffAge[slot] = 0;
    }

    /**
     * Ages every live effect by one tic, expiring those that are finished.
     *
     * <p>Purely simulation: nothing is drawn and no transform is written. Call
     * it once per tic, then {@link #publish}.</p>
     */
    public void advance()
    {
        for (int slot = 0; slot < MAX_TRACERS; slot++)
        {
            if (tracerRemaining[slot] == DEAD)
            {
                continue;
            }
            final int at = slot * AXES;
            tracerPosition[at] += tracerDirection[at] * TRACER_SPEED_UNITS;
            tracerPosition[at + 1] += tracerDirection[at + 1] * TRACER_SPEED_UNITS;
            tracerPosition[at + 2] += tracerDirection[at + 2] * TRACER_SPEED_UNITS;
            tracerRemaining[slot]--;
            if (tracerRemaining[slot] <= 0)
            {
                tracerRemaining[slot] = DEAD;
            }
        }
        for (int slot = 0; slot < MAX_PUFFS; slot++)
        {
            if (puffAge[slot] == DEAD)
            {
                continue;
            }
            puffPosition[slot * AXES + 1] += PUFF_RISE_UNITS;
            puffAge[slot]++;
            if (puffAge[slot] >= PUFF_LIFE_TICS)
            {
                puffAge[slot] = DEAD;
            }
        }
    }

    /**
     * Writes every effect instance's placement into the render port.
     *
     * <p>Only what changed: a live effect is republished because it moved, and
     * a slot that has just died or just changed stage is hidden once. A slot
     * that was already hidden is left entirely alone, which is why an idle
     * trigger costs nothing.</p>
     *
     * @param renderer the port holding the scene these instances live in; must
     *     already have that scene bound
     */
    public void publish(final SoftwareRenderPort renderer)
    {
        if (!hidden)
        {
            hideEverything(renderer);
            this.hidden = true;
        }
        for (int slot = 0; slot < MAX_TRACERS; slot++)
        {
            if (tracerRemaining[slot] != DEAD)
            {
                renderer.setWorldTransform(tracerInstance[slot], tracerPlacement(slot));
                tracerShown[slot] = true;
            }
            else if (tracerShown[slot])
            {
                renderer.setWorldTransform(tracerInstance[slot], HIDDEN);
                tracerShown[slot] = false;
            }
        }
        for (int slot = 0; slot < MAX_PUFFS; slot++)
        {
            publishPuff(renderer, slot);
        }
    }

    // Puts the whole pool out of sight. Runs once, on the first publish — see
    // the `hidden` field for why it cannot be done when the scene is built.
    private void hideEverything(final SoftwareRenderPort renderer)
    {
        for (final int instance : tracerInstance)
        {
            renderer.setWorldTransform(instance, HIDDEN);
        }
        for (final int instance : puffInstance)
        {
            renderer.setWorldTransform(instance, HIDDEN);
        }
    }

    // One puff: show the stage its age selects, and hide whichever stage it was
    // showing before if that has moved on.
    private void publishPuff(final SoftwareRenderPort renderer, final int slot)
    {
        final int age = puffAge[slot];
        if (age == DEAD)
        {
            if (puffShownStage[slot] != DEAD)
            {
                renderer.setWorldTransform(puffInstanceIndex(slot, puffShownStage[slot]), HIDDEN);
                puffShownStage[slot] = DEAD;
            }
            return;
        }
        final int stage = stageFor(age);
        if (puffShownStage[slot] != DEAD && puffShownStage[slot] != stage)
        {
            renderer.setWorldTransform(puffInstanceIndex(slot, puffShownStage[slot]), HIDDEN);
        }
        renderer.setWorldTransform(puffInstanceIndex(slot, stage), puffPlacement(slot, age));
        puffShownStage[slot] = stage;
    }

    /**
     * Returns which coverage rung a puff of a given age is drawn at.
     *
     * @param age tics the puff has lived, in {@code [0, PUFF_LIFE_TICS)}
     * @return the stage index, in {@code [0, PUFF_STAGES)}
     */
    public static int stageFor(final int age)
    {
        final int stage = age * PUFF_STAGES / PUFF_LIFE_TICS;
        return Math.min(stage, PUFF_STAGES - 1);
    }

    /**
     * Returns the coverage one puff stage composites at.
     *
     * @param stage stage index in {@code [0, PUFF_STAGES)}
     * @return the coverage, 0-255, decreasing with the stage
     */
    public static int coverageFor(final int stage)
    {
        return PUFF_COVERAGE[stage];
    }

    // Where a tracer's stretched box sits and which way it points.
    //
    // The columns are the images of the model's own axes: model +x and +y go to
    // the two axes across the bolt, scaled by its width, and model +z goes along
    // the direction of travel, scaled by its length. That basis is orthonormal
    // and right-handed by construction — see crossWithReference — so the
    // determinant is width^2 * length, which is positive. It has to be: a
    // negative one reverses triangle winding and renders the box inside-out.
    private Mat4 tracerPlacement(final int slot)
    {
        final int at = slot * AXES;
        final float dirX = tracerDirection[at];
        final float dirY = tracerDirection[at + 1];
        final float dirZ = tracerDirection[at + 2];

        final float[] across = acrossScratch;
        crossWithReference(dirX, dirY, dirZ, across);
        // up = forward x across, which completes a right-handed
        // (across, up, forward) — see crossWithReference for why that is the
        // order rather than the more obvious (across, forward, up).
        final float upX = dirY * across[2] - dirZ * across[1];
        final float upY = dirZ * across[0] - dirX * across[2];
        final float upZ = dirX * across[1] - dirY * across[0];

        final float wide = TRACER_WIDTH_UNITS;
        final float along = TRACER_LENGTH_UNITS;
        return Mat4.ofRowMajor(new float[]
        {
            across[0] * wide, upX * wide, dirX * along, tracerPosition[at],
            across[1] * wide, upY * wide, dirY * along, tracerPosition[at + 1],
            across[2] * wide, upZ * wide, dirZ * along, tracerPosition[at + 2],
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    // Where a puff sits and how big it has grown. A uniform scale, so the
    // determinant is radius^3 and stays positive for any positive radius.
    private Mat4 puffPlacement(final int slot, final int age)
    {
        final int at = slot * AXES;
        final float grown = PUFF_RADIUS_START
            + (PUFF_RADIUS_END - PUFF_RADIUS_START) * age / PUFF_LIFE_TICS;
        return DemoScene.placement(puffPosition[at], puffPosition[at + 1],
            puffPosition[at + 2], 0.0f, grown * 2.0f);
    }

    // The unit vector to the shooter's RIGHT, perpendicular to the aim. Also
    // the axis the tracer's box is widened across.
    //
    // `cross(aim, worldUp)` with worldUp = (0, 1, 0), written out because two
    // thirds of a cross product against an axis vector is multiplying by zero.
    //
    // <b>The order of the operands is the whole of it, and it was wrong first
    // time.</b> `cross(worldUp, aim)` is just as plausible on the page and
    // points the other way, which put the muzzle smoke off the player's left
    // shoulder while the weapon was drawn on the right. This order is not a
    // preference: {@code Camera} defines right as {@code normalize(forward x
    // up)}, and the muzzle has to agree with the camera or the effect detaches
    // from the gun. That is the same basis whose sign flipped once already —
    // see SoftwareRenderPort's note on the backface convention — so it is
    // copied from Camera rather than re-derived.
    //
    // The eventual (across, up', forward) is right-handed whichever way this
    // points: up' is forward x across, so across x up' = forward for any unit
    // `across` perpendicular to the aim. The determinant is positive and the
    // box is not mirrored.
    private static void crossWithReference(final float dx, final float dy, final float dz,
        final float[] out)
    {
        out[0] = -dz;
        out[1] = 0.0f;
        out[2] = dx;
        if (lengthOf(out) > PARALLEL_EPSILON)
        {
            normalise(out);
            return;
        }
        // Straight up or straight down, where the aim IS world up and the cross
        // above is exactly zero. `cross(aim, worldForward)` instead, which
        // cannot also degenerate because the two references are perpendicular
        // to each other.
        out[0] = dy;
        out[1] = -dx;
        out[2] = 0.0f;
        normalise(out);
    }

    private static float lengthOf(final float[] vector)
    {
        return (float) StrictMath.sqrt(
            vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
    }

    // Scales a vector to unit length. A zero vector is left alone rather than
    // divided into NaN; crossWithReference has already made that unreachable,
    // and this is what keeps it unreachable if anyone gives it a second caller.
    private static void normalise(final float[] vector)
    {
        final float length = lengthOf(vector);
        if (!(length > 0.0f))
        {
            return;
        }
        vector[0] /= length;
        vector[1] /= length;
        vector[2] /= length;
    }

    /**
     * Returns the scene instance index of one tracer.
     *
     * @param slot tracer slot in {@code [0, MAX_TRACERS)}
     * @return its index among the scene's world instances
     */
    public int tracerInstanceIndex(final int slot)
    {
        return tracerInstance[slot];
    }

    /**
     * Returns the scene instance index of one stage of one puff.
     *
     * @param slot puff slot in {@code [0, MAX_PUFFS)}
     * @param stage stage index in {@code [0, PUFF_STAGES)}
     * @return its index among the scene's world instances
     */
    public int puffInstanceIndex(final int slot, final int stage)
    {
        return puffInstance[stageOffset(slot, stage)];
    }

    /**
     * Returns how many tics one tracer has left, or {@link #DEAD}.
     *
     * @param slot tracer slot in {@code [0, MAX_TRACERS)}
     * @return the remaining life
     */
    public int tracerRemaining(final int slot)
    {
        return tracerRemaining[slot];
    }

    /**
     * Returns how many tics one puff has lived, or {@link #DEAD}.
     *
     * @param slot puff slot in {@code [0, MAX_PUFFS)}
     * @return the age
     */
    public int puffAge(final int slot)
    {
        return puffAge[slot];
    }

    /** Returns how many tracers are in the air. */
    public int liveTracerCount()
    {
        return liveCount(tracerRemaining);
    }

    /** Returns how many puffs are visible. */
    public int livePuffCount()
    {
        return liveCount(puffAge);
    }

    private static int liveCount(final int[] slots)
    {
        // MUTABLE local — running count.
        int live = 0;
        for (final int slot : slots)
        {
            if (slot != DEAD)
            {
                live++;
            }
        }
        return live;
    }

    /** Returns how many world instances this pool occupies in the scene. */
    public int instanceCount()
    {
        return tracerInstance.length + puffInstance.length;
    }

    private static int stageOffset(final int slot, final int stage)
    {
        return slot * PUFF_STAGES + stage;
    }

    /**
     * Builds a unit box, one colour throughout, centred on the model origin.
     *
     * <p>Eight vertices and twelve triangles, wound <b>counter-clockwise seen
     * from outside</b> — the glTF 2.0 rule, which is what
     * {@code SoftwareRenderPort.BACKFACE_CULL_MODE} was measured against. Get
     * the winding backwards and the box renders inside-out, which looks like a
     * plausible shape rather than like an error.</p>
     *
     * <p>Eight vertices rather than twenty-four because there is one colour and
     * no texture, so no corner needs to disagree with itself about anything. No
     * submesh table either, which is what puts every triangle on the flat path
     * and gives it the baked vertex colour — see
     * {@link ModelFormat#ofGeometry}.</p>
     *
     * @param colour the baked vertex colour, packed RGBA8888
     * @return a 1x1x1 box from -0.5 to +0.5 on every axis
     */
    static ModelFormat box(final int colour)
    {
        final float low = -0.5f;
        final float high = 0.5f;
        final int[] vertices = new int[BOX_VERTICES * ModelFormat.VERTEX_STRIDE_INTS];
        ModelFormat.writeVertex(vertices, 0, low, low, low, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 1, high, low, low, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 2, high, high, low, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 3, low, high, low, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 4, low, low, high, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 5, high, low, high, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 6, high, high, high, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 7, low, high, high, 0.0f, 0.0f, colour);

        final int[] indices =
        {
            4, 5, 6, 4, 6, 7,
            1, 0, 3, 1, 3, 2,
            5, 1, 2, 5, 2, 6,
            0, 4, 7, 0, 7, 3,
            7, 6, 2, 7, 2, 3,
            0, 1, 5, 0, 5, 4,
        };
        return ModelFormat.ofGeometry(vertices, indices);
    }

    /** Returns a debug rendering of the pool's state. */
    @Override
    public String toString()
    {
        return "DemoEffects{tracers=" + liveTracerCount() + "/" + MAX_TRACERS
            + ", puffs=" + livePuffCount() + "/" + MAX_PUFFS
            + ", instances=" + instanceCount() + "}";
    }
}
