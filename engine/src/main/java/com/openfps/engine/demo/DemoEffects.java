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
 * tics and {@link #PUFF_LIFE_TICS} of 36 means at most three puffs overlap. The
 * pools are one larger than that arithmetic needs, and a spawn that finds every
 * slot busy <b>overwrites the oldest</b> rather than being dropped. Round-robin
 * makes that O(1) and total: there is no failure path to test, and the worst
 * case is that a tracer nobody was looking at disappears a few tics early.</p>
 *
 * <p><b>That overlap is the point and not a cost.</b> A tracer lives 8 tics
 * against a 12-tic cadence, so roughly one frame in three legitimately has
 * nothing in the air — which is correct for a bolt, since a bolt has left. Smoke
 * has not left. Sized under the gap it would strobe at five hertz; at 36 tics it
 * always has two older puffs behind it, and a held trigger reads as one cloud
 * being fed rather than as a row of separate ones.</p>
 *
 * <h2>Why the smoke is thirty instances per puff</h2>
 *
 * <p>Two independent multipliers, and each of them is forced by something the
 * renderer will not let a single instance do.</p>
 *
 * <p><b>{@link #PUFF_STAGES}, because a puff has to fade</b>, and coverage is
 * fixed when a {@link Scene} is built — see {@code Scene}'s own note on why that
 * is a deliberate immutability rather than an oversight. So one puff owns one
 * set of instances per rung of a descending coverage ladder, and exactly one
 * rung is visible at a time: the puff ages, the stage advances, the previous
 * stage is hidden and the next is shown. The fade is a staircase rather than a
 * ramp, which at 60 Hz over a step every six tics nobody can see, and it costs
 * the render port nothing per frame — the hidden stages are culled before the
 * rasterizer.</p>
 *
 * <p><b>{@link #PUFF_LOBES}, because a puff has to be soft</b>, and a single
 * instance at a single coverage has a hard edge and a flat middle by
 * construction. Five overlapping spheres composite over each other, so one
 * coverage becomes five densities on screen. That constant's Javadoc has the
 * argument; {@link #PUFF_COVERAGE} has the arithmetic.</p>
 *
 * <p>Thirty instances a puff and a hundred and twenty in the pool sounds like a
 * lot and costs almost nothing: at most one puff's worth is ever visible per
 * rung, all five lobes of a rung share a coverage so they are one run in the
 * back-to-front sort and one batched pass between them, and everything else in
 * the pool is a degenerate transform {@code sortBackToFront} drops on a frustum
 * test before the rasterizer is ever asked. What the pool size does buy is a
 * per-frame cull over 120 entries instead of 36 — an insertion sort's worth of
 * comparisons over the handful that survive, which is not a number worth
 * optimising against a room that submits thousands of triangles.</p>
 *
 * <p>Three puffs alive at three different ages is three different coverages,
 * hence three runs and three batched passes rather than the one a single puff
 * costs. That is the price of the overlap that makes the effect continuous, and
 * it is paid only while the trigger is held.</p>
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

    /**
     * Smoke puffs that can be visible at once — <b>4</b>.
     *
     * <p>Derived from the other two numbers rather than chosen:
     * {@code DemoGameplayPort.FIRE_INTERVAL_TICS} is 12 and
     * {@link #PUFF_LIFE_TICS} is 36, so a held trigger keeps three puffs in the
     * air at all times, and the pool is one larger than that arithmetic needs
     * for the reason the class Javadoc gives.</p>
     */
    public static final int MAX_PUFFS = 4;

    /**
     * Tics a puff lives — <b>36</b>, which is six tenths of a second.
     *
     * <p><b>Doubled from 18, and this is the single change that mattered
     * most.</b> A shot lands every 12 tics, so at 18 the smoke was only ever
     * one-and-a-half puffs deep and each individual puff was gone in three
     * tenths of a second. Measured on a plain wall, the densest rung of a puff
     * covered about 2,500 pixels for five tics and then thinned out — an 83 ms
     * flash of 0.3% of the frame, in the far right periphery, over the part of
     * the demo room that is already full of grey crates. It was drawn exactly as
     * designed and it was still perfectly possible to fire a magazine without
     * noticing it, which is what two rounds of "the smoke is not working" were
     * actually reporting.</p>
     *
     * <p>At 36 the cloud outlives the gap between shots three times over, so
     * while the trigger is held there is always a fresh puff arriving into two
     * older ones — a churn rather than a blink. That is the difference between
     * an effect that is <i>present in a screenshot</i> and one that is
     * <i>visible in motion</i>, and only the second one is the feature.</p>
     */
    public static final int PUFF_LIFE_TICS = 36;

    /**
     * Coverage rungs a puff fades down — <b>6</b>.
     *
     * <p>Raised from 4 with {@link #PUFF_LIFE_TICS}, to hold the step interval
     * where it was: 36 tics over six rungs is a step every six tics, near enough
     * the 4-5 of the old ladder. Keeping four rungs over a doubled life would
     * have made each step nine tics apart, which is long enough to see the fade
     * <i>tick</i> — and a staircase you can count is worse than no fade.</p>
     */
    public static final int PUFF_STAGES = 6;

    /**
     * Overlapping spheres one puff is built from — <b>5</b>.
     *
     * <p><b>This is what stopped the smoke reading as a block.</b> A puff used
     * to be one cube, and a cube composited at a uniform coverage has a hard
     * straight edge and a flat interior — it is a translucent <i>object</i>, and
     * the eye reads objects. Smoke has no edge; it has a dense middle that
     * thins out.</p>
     *
     * <p>Overlapping lobes produce that for free, because {@code Rgba.srcOver}
     * is applied per instance and therefore <b>compounds where they
     * overlap</b>. A pixel covered by one lobe composites once, by two lobes
     * twice, by all five five times, so a single coverage per stage becomes a
     * five-step radial falloff on screen without the per-instance coverage ever
     * changing — which it cannot, because {@link Scene} fixes it at build time.
     * The compounding is the feature rather than a hazard to be avoided; the
     * whole of {@link #PUFF_COVERAGE} is chosen against it.</p>
     *
     * <p><b>Raised from three, because the cloud got bigger and three lumps do
     * not fill a 230-pixel silhouette.</b> At the old size three spheres read as
     * a cloud; at {@link #PUFF_RADIUS_END} they read as three balls in a bag,
     * with visible straight gaps between them where the falloff should have
     * been. Five is also what buys the density back: a five-deep compound
     * reaches the 0.82 core {@link #PUFF_COVERAGE} is solved for while its
     * outermost lobe still only composites once, so the cloud gets both a
     * thicker middle and a softer rim from the same change.</p>
     *
     * <p>Five and not more: every lobe is another 24 instances in the pool
     * ({@code MAX_PUFFS x PUFF_STAGES x PUFF_LOBES}), another sphere in the
     * back-to-front sort, and one more {@code Mat4} per tic per visible puff.
     * Five already gives a lopsided silhouette and five density levels, which is
     * as much structure as a grey blob can carry at any size.</p>
     */
    public static final int PUFF_LOBES = 5;

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

    /**
     * How far right of the eye the muzzle is, in world units — 1.55.
     *
     * <p><b>Measured off the rendered weapon, not copied from its origin.</b>
     * This used to be 0.9, to match {@code DemoScene.WEAPON_VIEW_RIGHT} of
     * 0.92 — but that constant places the weapon model's <i>origin</i>, and a
     * pistol's muzzle is at the far end of a barrel that runs forward and to
     * the right of it. At 0.9 the effect appeared over the middle of the gun,
     * which is not where a shot comes from and is the part of the frame the gun
     * body itself covers.</p>
     *
     * <p>1.55 puts it at the end of the drawn slide: at 720p and the demo's
     * field of view that is about 400 px right of centre, and the weapon's
     * barrel tip renders at about 430. Taken from a capture rather than from
     * the model's bounds, because {@code WEAPON_VIEW_SCALE} sizes the viewmodel
     * for the screen rather than for the world — where the muzzle
     * <i>appears</i> is the only definition that makes the smoke look attached
     * to the gun.</p>
     */
    public static final float MUZZLE_RIGHT_UNITS = 1.55f;

    /**
     * How far below the eye the muzzle is, in world units — 0.22.
     *
     * <p>Raised from 0.30 along with {@link #MUZZLE_RIGHT_UNITS} and for the
     * same reason: the barrel sits along the <i>top</i> of the slide, so the
     * muzzle is higher than the weapon's origin. It also lifts the puff clear
     * of the gun body, which was covering its lower half.</p>
     */
    public static final float MUZZLE_DROP_UNITS = 0.22f;

    /**
     * Half-extent of the <b>main lobe</b> when a puff is born, in world units —
     * 0.16.
     *
     * <p>Sub-unit numbers, which look wrong beside a 41-unit eye height until
     * you notice what they are measured against: the puff sits
     * {@link #MUZZLE_FORWARD_UNITS} from the eye, so its <i>apparent</i> size
     * is the ratio of the two. At 720p and the demo's field of view a world unit
     * at 2.4 units out is about 262 px, and the cloud reaches
     * {@link #cloudExtentRadii()} — roughly 1.46 — times this radius. So 0.16
     * is a cloud about 122 px across at the moment of the shot. Sized in the
     * room's units instead it would fill the screen.</p>
     *
     * <p><b>Doubled from 0.075, and the reason is the one thing about the old
     * puff that nobody would have guessed: it was smallest exactly when it was
     * densest.</b> Coverage falls with age while the radius grows, so the rung a
     * player could actually see was a 57 px smudge that lasted five tics, and
     * the phases that lasted were the ones you could see through. Measured on a
     * plain wall the densest rung covered about 2,500 px of a 921,600 px frame.
     * Starting big inverts that: the loud part of the effect is now the big part
     * of it.</p>
     */
    public static final float PUFF_RADIUS_START = 0.16f;

    /**
     * Half-extent of the <b>main lobe</b> at the end of a puff's life — 0.30.
     * See {@link #PUFF_RADIUS_START} for why these numbers are so small.
     *
     * <p>About 230 px of cloud at 720p. <b>The last time this went up it went
     * too far:</b> 0.34 with a single cube produced something like 300 px of
     * uniform translucent grey, which does not read as smoke at a muzzle — it
     * reads as a pane of glass across the view. What makes 0.30 different is not
     * the number but what is being scaled: five lobes at
     * {@link #PUFF_COVERAGE}'s ladder have a dense core and a rim that
     * composites once, so the silhouette dissolves at its edge instead of ending
     * at one. A big soft thing and a big flat thing are not the same picture at
     * the same size.</p>
     *
     * <p><b>The growth per tic is slower than it was</b>, not faster: 0.14 of
     * expansion over 36 tics is 0.0039 a tic, against the old 0.0047. Smoke
     * that swells visibly reads as inflating rather than as dispersing, and the
     * longer life would have made the old rate three times as much travel.</p>
     */
    public static final float PUFF_RADIUS_END = 0.30f;

    /**
     * Each lobe's offset from the puff centre <b>across</b> the shooter's view,
     * in multiples of the current puff radius.
     *
     * <p>Across and up rather than along world axes, and that is deliberate: a
     * spread laid out in world x and z would collapse into a single blob
     * whenever the player happened to be shooting along one of them, so the
     * cloud's shape would depend on which way they were facing. The offsets are
     * expressed in the shot's own basis — the same {@code across} the muzzle
     * offset uses, and {@code up = aim x across} — so the lumps are spread
     * across the screen from every angle.</p>
     *
     * <p>The first lobe is at the centre and is the largest; the rest sit off it
     * in four different directions, no two of them opposite, so the silhouette
     * is lopsided. A symmetric arrangement would read as a flower.</p>
     *
     * <p><b>Every offset was pulled IN when the fourth and fifth lobes arrived,
     * and the outriders were made bigger.</b> Kept at the three-lobe spacing,
     * five spheres of decreasing size read as bubbles: each one's circular edge
     * survived as an edge because there was nothing else covering it. Overlapping
     * them harder is what fuses the outlines — a lobe boundary that runs through
     * the middle of a neighbour is a density step and not a silhouette. The
     * measure of it is {@link #cloudExtentRadii()}, which comes down to about
     * 1.38 from 1.46: the extra lobes are here to fill the shape in, not to make
     * it larger. {@link #PUFF_RADIUS_END} owns the size, and it is the only thing
     * that should.</p>
     */
    public static final float[] LOBE_ACROSS = {0.00f, 0.50f, -0.42f, 0.24f, -0.56f};

    /** Each lobe's offset <b>up</b> the shooter's view. See {@link #LOBE_ACROSS}. */
    public static final float[] LOBE_UP = {0.00f, 0.30f, -0.26f, -0.52f, 0.18f};

    /**
     * Each lobe's radius as a fraction of the puff's. The centre lobe is full
     * size and the outriders get progressively smaller, which is what makes the
     * cloud read as one thing with bulges rather than as five balls.
     */
    public static final float[] LOBE_SCALE = {1.00f, 0.80f, 0.74f, 0.66f, 0.60f};

    /**
     * World units a puff drifts upward per tic — 0.006.
     *
     * <p>Tiny, and it has to be. The puff sits 2.4 units from the eye, so a
     * rise is magnified by the same ratio that makes
     * {@link #PUFF_RADIUS_START} small: over {@link #PUFF_LIFE_TICS} this is
     * 0.22 units, which is about 56 screen pixels at 720p. At 0.022, which is
     * where it started, the same drift carried the puff a hundred pixels up the
     * screen and left it hanging above the weapon rather than at its
     * muzzle.</p>
     *
     * <p><b>Cut from 0.010 when the life doubled</b>, so the total travel is
     * about what it always was rather than twice it. The drift is there to say
     * the cloud is not a decal stuck to the screen; a puff that climbed a
     * hundred pixels while it faded would be a balloon.</p>
     */
    public static final float PUFF_RISE_UNITS = 0.006f;

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
     * Dark warm grey, the colour of powder smoke — and it is dark on purpose.
     *
     * <p><b>Measured against the room it is composited over, not chosen from a
     * palette.</b> The Kenney room's lit walls and floor sample at about
     * {@code (141, 147, 177)}, a pale grey-blue. This used to be
     * {@code (158, 158, 152)}, which is that colour with the blue taken out and
     * almost nothing else changed — the whole puff resolved to within about ten
     * levels of the background at its most opaque rung. It was drawn correctly,
     * every frame, and it was invisible; the player's report was "the smoke is
     * not there", and they were right.</p>
     *
     * <p>Dark rather than light because the room is light: there are only 114
     * levels of headroom upward from the background and 141 downward, and the
     * pale end of that range is where the walls, the ceiling and the doorway
     * trim already live. But <b>not as dark as it can be</b> — {@code (74, 72,
     * 68)} was tried and overshot, resolving to about {@code (84, 84, 91)} at
     * the first rung, which reads as a solid black block rather than as smoke.
     * Something you cannot see through is not a cloud, whatever its
     * coverage.</p>
     *
     * <p>{@code (92, 90, 86)} lands the freshest puff's <b>core</b> near
     * {@code (101, 100, 102)} against that wall: a drop of some forty levels,
     * plainly visible, with nearly a fifth of the background still showing
     * through it. Its rim lands near {@code (127, 130, 151)}, a wisp rather than
     * a boundary.</p>
     *
     * <p>The colour has now survived the move from one cube to three spheres and
     * then to five, and a doubling of both the life and the size, unchanged —
     * which is the point of having derived it. Every time the shape of the effect
     * changed, {@link #PUFF_COVERAGE} was solved to put the composited core back
     * where this colour had already been judged to work, rather than the colour
     * being re-tuned around a new ladder.</p>
     */
    private static final int SMOKE_COLOUR = Rgba.pack(92, 90, 86, 255);

    /**
     * Coverage of <b>one lobe</b> at each puff stage, faintest last.
     *
     * <p>Every rung is a distinct blended {@code SpanRenderer} in the render
     * port and a potential extra batched pass, so this is six values rather
     * than thirty-six. Six steps over {@link #PUFF_LIFE_TICS} is a step every
     * six tics, which reads as a fade. All {@link #PUFF_LOBES} lobes of a puff
     * share the stage's coverage, so they are one contiguous run in the
     * back-to-front sort and cost one pass between them.</p>
     *
     * <p><b>These are per-lobe figures and they are far lower than the
     * coverage anyone would write down for the density they produce, because
     * the lobes compound.</b> Coverage {@code a} applied {@code n} times leaves
     * {@code 1 - (1 - a)^n} of the smoke, so the numbers are solved backwards
     * from what the <i>composite</i> should be rather than picked:</p>
     *
     * <pre>
     *   stage  per lobe   1 lobe   2 lobes   3 lobes   4 lobes   5 lobes
     *     0        74       0.29     0.50      0.64      0.75      0.82
     *     1        59       0.23     0.41      0.55      0.65      0.73
     *     2        45       0.18     0.32      0.44      0.54      0.62
     *     3        32       0.13     0.24      0.33      0.41      0.49
     *     4        20       0.08     0.15      0.22      0.28      0.34
     *     5        10       0.04     0.08      0.11      0.15      0.18
     * </pre>
     *
     * <p>Composited over the demo room's lit wall at {@code (141, 147, 177)},
     * the core of a fresh puff lands near {@code (101, 100, 102)} — forty levels
     * down on red, forty-seven on green and seventy-five on blue, which is the
     * whole reason {@code smokeColour} is warm: it does not just darken the
     * wall, it takes the blue out of it, and that is a second signal on top of
     * the first. Its rim lands near {@code (127, 130, 151)}, a wisp rather than
     * a boundary. The final rung is about {@code (132, 137, 161)} — a faint
     * haze, so the staircase ends in a fade rather than in a disappearance.</p>
     *
     * <p><b>The top rung deliberately does not go higher.</b> Something you
     * cannot see through is a hole in the room and not a cloud, and that
     * overshoot has already happened once here — 228 on an older ladder, with a
     * dark enough colour, produced a black block. At 0.82 composite, nearly a
     * fifth of the wall still shows through the thickest point of the thickest
     * rung, and that point is the small five-deep overlap at the middle rather
     * than the whole cloud.</p>
     */
    private static final int[] PUFF_COVERAGE = {74, 59, 45, 32, 20, 10};

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

    /**
     * Meridians round the smoke sphere — <b>12</b>.
     *
     * <p>Coarse on purpose, and sized against the picture rather than against
     * taste. <b>Sized against the DENSE rung, which is the small one.</b> The
     * only lobe edge a player can resolve is one with enough coverage behind it
     * to be a boundary, and coverage falls as the puff grows: a fresh puff's main
     * lobe is about 84 px across at 720p, so twelve facets put a corner every
     * 22 px of its silhouette. The 157 px lobe at the end of a puff's life gets a
     * corner every 40 px instead, and it composites at 0.29 where a straight edge
     * has nothing to be straight against.</p>
     *
     * <p>Raised from 8 when {@link #PUFF_RADIUS_END} nearly doubled, because the
     * old count was sized against a cloud half this size and eight facets on the
     * new one put a visible flat down the side of the outermost wisp.</p>
     */
    private static final int SPHERE_MERIDIANS = 12;

    /**
     * Stacks from pole to pole — <b>8</b>. With
     * {@link #SPHERE_MERIDIANS} that is 86 vertices and 168 triangles, against
     * the cube's 8 and 12. The whole pool is 120 spheres and at most fifteen are
     * ever visible, so the pass carries about 2,500 triangles at its busiest —
     * beside a room that submits thousands. Blended fill is set by the area
     * covered rather than by the triangle count, so this is the cheap half of
     * making the cloud bigger.
     */
    private static final int SPHERE_STACKS = 8;

    /** Half-turn in radians, for the sphere's polar sweep. */
    private static final float HALF_TURN_RADIANS = (float) StrictMath.PI;

    /** Full turn in radians, for the sphere's azimuthal sweep. */
    private static final float FULL_TURN_RADIANS = (float) (2.0 * StrictMath.PI);

    /**
     * Radius the smoke sphere is authored at — a half unit, so it occupies
     * exactly the {@code -0.5 .. +0.5} box the tracer does and the same
     * {@code scale = diameter} placement arithmetic applies unchanged.
     */
    private static final float SPHERE_RADIUS = 0.5f;

    /** Scene instance index of each tracer. */
    private final int[] tracerInstance;

    /**
     * Scene instance index of each puff lobe, addressed by
     * {@code (puff * PUFF_STAGES + stage) * PUFF_LOBES + lobe}.
     */
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

    /**
     * The shot basis each puff's lobes are laid out in — across the view, then
     * up it, {@link #AXES} floats each per slot. MUTABLE: written once at spawn.
     *
     * <p>Stored rather than recomputed at publish time because the aim it came
     * from is gone by then: the puff stays where it was made while the player
     * carries on turning, and a cloud that reshuffled its lumps as the camera
     * moved would be a very odd thing to watch.</p>
     */
    private final float[] puffAcross;

    /** The other half of that basis. See {@link #puffAcross}. MUTABLE. */
    private final float[] puffUp;

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
        this.puffAcross = new float[MAX_PUFFS * AXES];
        this.puffUp = new float[MAX_PUFFS * AXES];
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
        // One sphere shared by all 36 lobe instances. SoftwareRenderPort.prepare
        // keys on reference identity, so the flattened submesh table and the mip
        // chains are built once for the whole pool rather than once per lobe.
        final ModelFormat cloud = sphere(SMOKE_COLOUR);

        final int[] tracers = new int[MAX_TRACERS];
        for (int slot = 0; slot < MAX_TRACERS; slot++)
        {
            tracers[slot] = builder.worldInstanceCount();
            builder.addWorldInstance(bolt, Mat4.identity());
        }

        final int[] puffs = new int[MAX_PUFFS * PUFF_STAGES * PUFF_LOBES];
        for (int puff = 0; puff < MAX_PUFFS; puff++)
        {
            for (int stage = 0; stage < PUFF_STAGES; stage++)
            {
                for (int lobe = 0; lobe < PUFF_LOBES; lobe++)
                {
                    puffs[stageOffset(puff, stage) + lobe] = builder.worldInstanceCount();
                    builder.addTranslucentWorldInstance(cloud, Mat4.identity(), Scene.UNTAGGED,
                        PUFF_COVERAGE[stage]);
                }
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
        spawnPuff(muzzleX, muzzleY, muzzleZ, aimX, aimY, aimZ, right);
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

    // One puff, at rest at the muzzle, with the basis its lobes are laid out
    // in frozen at the moment of the shot.
    //
    // `across` is the caller's scratch and is copied rather than kept, because
    // the caller reuses it — see acrossScratch.
    private void spawnPuff(final float x, final float y, final float z,
        final float aimX, final float aimY, final float aimZ, final float[] across)
    {
        final int slot = puffCursor;
        this.puffCursor = (puffCursor + 1) % MAX_PUFFS;
        final int at = slot * AXES;
        puffPosition[at] = x;
        puffPosition[at + 1] = y;
        puffPosition[at + 2] = z;
        puffAcross[at] = across[0];
        puffAcross[at + 1] = across[1];
        puffAcross[at + 2] = across[2];
        // up = aim x across, the same operand order tracerPlacement uses to
        // complete its (across, up, forward) frame. Any unit vector
        // perpendicular to both would do here — the lobes only need two
        // independent screen-plane directions — but reusing the one definition
        // keeps a second cross-product convention out of the file.
        puffUp[at] = aimY * across[2] - aimZ * across[1];
        puffUp[at + 1] = aimZ * across[0] - aimX * across[2];
        puffUp[at + 2] = aimX * across[1] - aimY * across[0];
        puffAge[slot] = 0;
    }

    /**
     * Kills every tracer and puff outright, as though nothing had ever been
     * fired.
     *
     * <p>For the world <b>reset</b> when the player leaves the game-over screen.
     * A rematch that inherited three bolts and a cloud of smoke from the last
     * round would be a room that visibly remembered a match that is supposed to
     * have been forgotten — and the bolts would be halfway across it, in the
     * air, going nowhere.</p>
     *
     * <p><b>Deliberately not called on a respawn</b>, which is the other place
     * it plausibly belongs. The distinction is the one {@code DemoGameplayPort}
     * already draws for the menu: an effect is a consequence of a shot that has
     * already happened, so what a <i>pause</i> owes it is to let it finish. A
     * death is a pause. Restarting the world is not, and this is the difference
     * between the two.</p>
     *
     * <p>The instances are not hidden here — this only forgets the state. The
     * next {@link #publish} does the hiding, which is the same division of
     * labour every other transition in this class uses.</p>
     */
    public void clear()
    {
        for (int slot = 0; slot < MAX_TRACERS; slot++)
        {
            tracerRemaining[slot] = DEAD;
        }
        for (int slot = 0; slot < MAX_PUFFS; slot++)
        {
            puffAge[slot] = DEAD;
        }
        // The cursors go back to zero too, so a fresh round claims slots in the
        // same order the first one did. Nothing looks different either way, and
        // "indistinguishable from a fresh start" is easier to assert than
        // "indistinguishable apart from two cursors nobody can see".
        this.tracerCursor = 0;
        this.puffCursor = 0;
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

    // One puff: show every lobe of the stage its age selects, and hide whichever
    // stage it was showing before if that has moved on.
    //
    // A stage is all-or-nothing. Showing one lobe of a rung and one of the next
    // would put two coverages in the same cloud, which is not a fade — it is a
    // seam.
    private void publishPuff(final SoftwareRenderPort renderer, final int slot)
    {
        final int age = puffAge[slot];
        if (age == DEAD)
        {
            if (puffShownStage[slot] != DEAD)
            {
                hideStage(renderer, slot, puffShownStage[slot]);
                puffShownStage[slot] = DEAD;
            }
            return;
        }
        final int stage = stageFor(age);
        if (puffShownStage[slot] != DEAD && puffShownStage[slot] != stage)
        {
            hideStage(renderer, slot, puffShownStage[slot]);
        }
        for (int lobe = 0; lobe < PUFF_LOBES; lobe++)
        {
            renderer.setWorldTransform(puffInstanceIndex(slot, stage, lobe),
                puffPlacement(slot, lobe, age));
        }
        puffShownStage[slot] = stage;
    }

    // Puts every lobe of one rung out of sight.
    private void hideStage(final SoftwareRenderPort renderer, final int slot, final int stage)
    {
        for (int lobe = 0; lobe < PUFF_LOBES; lobe++)
        {
            renderer.setWorldTransform(puffInstanceIndex(slot, stage, lobe), HIDDEN);
        }
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
     * Returns how far the finished cloud reaches from its centre, in multiples
     * of the puff radius.
     *
     * <p>The largest {@code |offset| + scale} over the lobes — the radius of the
     * smallest sphere containing all of them. It is what decides the puff's
     * <b>apparent size</b>, and {@link #PUFF_RADIUS_END} is set against it
     * rather than against a lone lobe: the thing that has to stay under control
     * is how much of the window the cloud covers, and that is a property of the
     * arrangement and not of any one sphere in it.</p>
     *
     * @return the cloud's bounding radius, as a multiple of the puff radius
     */
    public static float cloudExtentRadii()
    {
        // MUTABLE local — the farthest reach found so far.
        float reach = 0.0f;
        for (int lobe = 0; lobe < PUFF_LOBES; lobe++)
        {
            final float offset = (float) StrictMath.sqrt(
                LOBE_ACROSS[lobe] * LOBE_ACROSS[lobe] + LOBE_UP[lobe] * LOBE_UP[lobe]);
            reach = Math.max(reach, offset + LOBE_SCALE[lobe]);
        }
        return reach;
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

    /**
     * Returns the packed RGBA8888 colour a puff composites in.
     *
     * <p>Exposed so a test can assert the thing that actually went wrong:
     * whether the composited result is far enough from the room behind it to be
     * seen. The colour on its own says nothing — the previous one was a
     * perfectly reasonable grey, and it was invisible against this particular
     * room. Only the pair of (colour, background) means anything, so the test
     * needs both.</p>
     *
     * @return the smoke colour, packed {@code 0xRRGGBBAA}
     */
    public static int smokeColour()
    {
        return SMOKE_COLOUR;
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

    // Where one lobe of a puff sits and how big it has grown. A uniform scale,
    // so the determinant is radius^3 and stays positive for any positive radius.
    //
    // The expansion is continuous even though the fade is a staircase, because
    // scale lives in the transform and the transform is the thing that is
    // allowed to change per tic — see the class Javadoc. The lobe offsets scale
    // with the puff, so the cloud grows as one shape rather than spreading its
    // lumps apart.
    private Mat4 puffPlacement(final int slot, final int lobe, final int age)
    {
        final int at = slot * AXES;
        final float grown = PUFF_RADIUS_START
            + (PUFF_RADIUS_END - PUFF_RADIUS_START) * age / PUFF_LIFE_TICS;
        final float outAcross = LOBE_ACROSS[lobe] * grown;
        final float outUp = LOBE_UP[lobe] * grown;
        final float centreX = puffPosition[at]
            + puffAcross[at] * outAcross + puffUp[at] * outUp;
        final float centreY = puffPosition[at + 1]
            + puffAcross[at + 1] * outAcross + puffUp[at + 1] * outUp;
        final float centreZ = puffPosition[at + 2]
            + puffAcross[at + 2] * outAcross + puffUp[at + 2] * outUp;
        return DemoScene.placement(centreX, centreY, centreZ, 0.0f,
            grown * LOBE_SCALE[lobe] * 2.0f);
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
     * Returns the scene instance index of the <b>main lobe</b> of one stage of
     * one puff.
     *
     * <p>Lobe zero, which is the one at the puff's own centre and the largest —
     * the whole of what a puff used to be. Callers that mean the cloud rather
     * than a lobe want {@link #PUFF_LOBES} of them; see the three-argument
     * form.</p>
     *
     * @param slot puff slot in {@code [0, MAX_PUFFS)}
     * @param stage stage index in {@code [0, PUFF_STAGES)}
     * @return its index among the scene's world instances
     */
    public int puffInstanceIndex(final int slot, final int stage)
    {
        return puffInstanceIndex(slot, stage, 0);
    }

    /**
     * Returns the scene instance index of one lobe of one stage of one puff.
     *
     * @param slot puff slot in {@code [0, MAX_PUFFS)}
     * @param stage stage index in {@code [0, PUFF_STAGES)}
     * @param lobe lobe index in {@code [0, PUFF_LOBES)}
     * @return its index among the scene's world instances
     */
    public int puffInstanceIndex(final int slot, final int stage, final int lobe)
    {
        return puffInstance[stageOffset(slot, stage) + lobe];
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

    // Where one stage's PUFF_LOBES consecutive instance indices begin.
    private static int stageOffset(final int slot, final int stage)
    {
        return (slot * PUFF_STAGES + stage) * PUFF_LOBES;
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

    /**
     * Builds a coarse sphere of radius {@link #SPHERE_RADIUS}, one colour
     * throughout, centred on the model origin.
     *
     * <p><b>Built in memory rather than staged as an asset, for the reason
     * {@link ModelFormat#ofGeometry} exists:</b> nobody is going to model a grey
     * ball in Blender, and routing one through the offline converter would mean
     * an extra {@code .ofm} file, a build step to produce it and a staging
     * failure mode, for geometry that is two nested loops of trigonometry.</p>
     *
     * <p>A sphere and not a box because a box has <b>corners and flat faces</b>,
     * and a translucent flat face composited at a uniform coverage is a pane of
     * tinted glass — the exact thing the puff kept being reported as. A sphere
     * has no straight silhouette anywhere, and three overlapping ones have no
     * flat interior either.</p>
     *
     * <p>Wound <b>counter-clockwise seen from outside</b>, the glTF 2.0 rule
     * that {@code SoftwareRenderPort.BACKFACE_CULL_MODE} was measured against.
     * That is what culls the far hemisphere, which matters more here than it
     * does for opaque geometry: a translucent sphere whose back faces also drew
     * would composite twice everywhere and be twice as dense as the coverage
     * ladder says.</p>
     *
     * <p>No submesh table and no texture, so every triangle takes the flat path
     * and the baked vertex colour — the same contract the tracer's box uses, and
     * what the translucent phase requires, since it binds no texture table.</p>
     *
     * @param colour the baked vertex colour, packed RGBA8888
     * @return a sphere of radius {@link #SPHERE_RADIUS} about the origin
     */
    static ModelFormat sphere(final int colour)
    {
        final int ringVertices = (SPHERE_STACKS - 1) * SPHERE_MERIDIANS;
        final int south = ringVertices + 1;
        final int[] vertices = new int[(south + 1) * ModelFormat.VERTEX_STRIDE_INTS];
        ModelFormat.writeVertex(vertices, 0, 0.0f, SPHERE_RADIUS, 0.0f, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, south, 0.0f, -SPHERE_RADIUS, 0.0f, 0.0f, 0.0f, colour);
        for (int stack = 1; stack < SPHERE_STACKS; stack++)
        {
            final float polar = HALF_TURN_RADIANS * stack / SPHERE_STACKS;
            final float height = SPHERE_RADIUS * (float) StrictMath.cos(polar);
            final float ring = SPHERE_RADIUS * (float) StrictMath.sin(polar);
            for (int meridian = 0; meridian < SPHERE_MERIDIANS; meridian++)
            {
                final float azimuth = FULL_TURN_RADIANS * meridian / SPHERE_MERIDIANS;
                ModelFormat.writeVertex(vertices, ringVertex(stack, meridian),
                    ring * (float) StrictMath.cos(azimuth), height,
                    ring * (float) StrictMath.sin(azimuth), 0.0f, 0.0f, colour);
            }
        }

        // Two caps of MERIDIANS triangles and STACKS-2 bands of twice that.
        final int triangles = SPHERE_MERIDIANS * 2 * (SPHERE_STACKS - 1);
        final int[] indices = new int[triangles * ModelFormat.INDICES_PER_TRIANGLE];
        // MUTABLE local — the write cursor into the index array.
        int at = 0;
        for (int meridian = 0; meridian < SPHERE_MERIDIANS; meridian++)
        {
            final int next = (meridian + 1) % SPHERE_MERIDIANS;
            // North cap. The pole is the degenerate case of the band below with
            // its upper ring collapsed to a point, so the order is the band's
            // second triangle with u[j] and u[j+1] both replaced by the pole.
            at = triangle(indices, at, 0, ringVertex(1, next), ringVertex(1, meridian));
            for (int stack = 1; stack < SPHERE_STACKS - 1; stack++)
            {
                final int upperHere = ringVertex(stack, meridian);
                final int upperNext = ringVertex(stack, next);
                final int lowerHere = ringVertex(stack + 1, meridian);
                final int lowerNext = ringVertex(stack + 1, next);
                at = triangle(indices, at, upperHere, upperNext, lowerNext);
                at = triangle(indices, at, upperHere, lowerNext, lowerHere);
            }
            // South cap, the same degeneracy at the other end.
            at = triangle(indices, at, ringVertex(SPHERE_STACKS - 1, meridian),
                ringVertex(SPHERE_STACKS - 1, next), south);
        }
        return ModelFormat.ofGeometry(vertices, indices);
    }

    // The vertex index of one point on one ring. Ring `stack` runs 1 to
    // STACKS-1; the two poles are 0 and (STACKS-1) * MERIDIANS + 1.
    private static int ringVertex(final int stack, final int meridian)
    {
        return 1 + (stack - 1) * SPHERE_MERIDIANS + meridian;
    }

    // Writes one triangle and returns the next write position.
    private static int triangle(final int[] indices, final int at, final int a, final int b,
        final int c)
    {
        indices[at] = a;
        indices[at + 1] = b;
        indices[at + 2] = c;
        return at + ModelFormat.INDICES_PER_TRIANGLE;
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
