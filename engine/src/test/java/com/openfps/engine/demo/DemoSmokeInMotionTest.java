/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.Camera;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.engine.render.adapter.Vec3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How much of the muzzle smoke a player actually sees, tic by tic, through the
 * real rasterizer.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>The smoke was reported as missing three times. It was never missing. Every
 * time, it was drawn in the right place with the right blend, and every time the
 * existing tests passed — because they asserted the <b>ingredients</b>: a
 * coverage value, a composited colour, a placement, an instance index. None of
 * those is the thing the player is looking at, which is <b>how much of the
 * screen changes, on which frames</b>. That number was never measured, so it was
 * free to be 0.27% of the frame for five tics and then nothing, which is what it
 * was, and which nobody can see in a moving picture.</p>
 *
 * <p>So the fixture here renders. It puts the effect pool in front of a flat
 * wall the colour the demo room samples at, drives it exactly as
 * {@link DemoGameplayPort#tick} does, and counts pixels that came out different
 * from the empty room. That is a measurement of the picture and not of the
 * intent, and it is the only kind of assertion the earlier rounds were missing.
 * The figures in the constants below are the ones this fixture reported, and the
 * comparison figures for the version that could not be seen are recorded beside
 * them.</p>
 *
 * <h2>Why the counting window is the right-hand third</h2>
 *
 * <p>A shot spawns a tracer as well as a puff, and the tracer is far brighter.
 * It travels along the aim ray, so it stays at the middle of the screen and
 * shrinks; the puff stays at the muzzle, which
 * {@link DemoEffects#MUZZLE_RIGHT_UNITS} puts about four fifths of the way
 * across. Counting only past {@link #SMOKE_WINDOW_FRACTION} of the width
 * therefore counts smoke and nothing else, without needing a switch in the
 * production code to turn the tracer off.</p>
 */
@DisplayName("the muzzle smoke, as a player sees it")
final class DemoSmokeInMotionTest
{
    /**
     * Frame width. Small, because every assertion here is a <i>fraction</i> of
     * the frame rather than a pixel count, and a 16:9 quarter-resolution frame
     * puts the muzzle in exactly the same place a 720p one does.
     */
    private static final int WIDTH = 640;

    /** Frame height, keeping 16:9 — the aspect the muzzle offsets were tuned at. */
    private static final int HEIGHT = 360;

    /** Pixels in a frame, the denominator of every fraction below. */
    private static final float FRAME_PIXELS = (float) (WIDTH * HEIGHT);

    /**
     * The demo room's lit wall, sampled from a real capture:
     * {@code (141, 147, 177)}. The smoke's colour was chosen against this, so
     * the backdrop has to be it or the contrast measured here is contrast
     * against nothing in particular.
     */
    private static final int WALL = Rgba.pack(141, 147, 177, Scene.OPAQUE);

    /** How far down the view axis the backdrop sits — far enough to be scenery. */
    private static final float WALL_DISTANCE = 200.0f;

    /** Half-edge of the backdrop quad, comfortably wider than the frustum there. */
    private static final float WALL_HALF = 400.0f;

    /** Corners in a quad. */
    private static final int QUAD_CORNERS = 4;

    /** A quad wound counter-clockwise as seen from the camera. */
    private static final int[] QUAD_INDICES = {0, 1, 2, 0, 2, 3};

    /** Where the smoke-only counting window starts, as a fraction of the width. */
    private static final float SMOKE_WINDOW_FRACTION = 0.6f;

    /**
     * Per-channel difference from the backdrop that counts as a changed pixel.
     *
     * <p>Three, not one: the rasterizer's own interpolation puts the odd level
     * of noise into a flat quad, and a test that counted those would report
     * smoke where there is none.</p>
     */
    private static final int CHANGED_DELTA = 3;

    /**
     * Least fraction of the frame a <b>fresh</b> puff must cover — 0.8%.
     *
     * <p>Measured: the current puff covers about 1.06% one tic after the shot.
     * <b>The version that could not be seen covered 0.27%</b>, and it was at its
     * densest exactly then, so this is the floor that the invisible one fails and
     * the visible one clears with room to spare.</p>
     */
    private static final float FRESH_PUFF_FRACTION = 0.008f;

    /**
     * Least fraction of the frame covered on <b>every</b> tic while the trigger
     * is held — 0.5%.
     *
     * <p>Lower than {@link #FRESH_PUFF_FRACTION} because the oldest rungs are
     * faint and some of a faint cloud falls under {@link #CHANGED_DELTA}. It is
     * still nearly twice what the old smoke managed at its very best.</p>
     */
    private static final float SUSTAINED_FRACTION = 0.005f;

    /**
     * Least fraction of the frame once three shots' worth of smoke has piled up
     * — 2%.
     *
     * <p>Measured at about 3.2%. This is the assertion that the overlap is real:
     * if {@link DemoEffects#PUFF_LIFE_TICS} were cut back under the fire
     * interval, the count would stop accumulating and sit at one puff's worth.</p>
     */
    private static final float HELD_TRIGGER_FRACTION = 0.02f;

    /** Shots the held-trigger tests fire. Three is the first tic all four slots matter. */
    private static final int SHOTS = 3;

    // A flat single-coloured quad in its own z = 0 plane, built in memory for the
    // reason ModelFormat.ofGeometry exists: this fixture must run on a checkout
    // with no staged art, which is most CI checkouts.
    private static ModelFormat wall()
    {
        final int[] vertices = new int[QUAD_CORNERS * ModelFormat.VERTEX_STRIDE_INTS];

        ModelFormat.writeVertex(vertices, 0, -WALL_HALF, -WALL_HALF, 0.0f, 0.0f, 0.0f, WALL);

        ModelFormat.writeVertex(vertices, 1, WALL_HALF, -WALL_HALF, 0.0f, 1.0f, 0.0f, WALL);

        ModelFormat.writeVertex(vertices, 2, WALL_HALF, WALL_HALF, 0.0f, 1.0f, 1.0f, WALL);

        ModelFormat.writeVertex(vertices, 3, -WALL_HALF, WALL_HALF, 0.0f, 0.0f, 1.0f, WALL);

        return ModelFormat.ofGeometry(vertices, QUAD_INDICES);
    }

    /**
     * The effect pool in front of a wall, plus the frame it draws into.
     *
     * <p>A class rather than a method because the empty room has to be rendered
     * once and kept: every count below is "how many pixels differ from the room
     * with nothing in the air", and that reference is what makes the measurement
     * a measurement rather than a guess at which colours count as smoke.</p>
     */
    private static final class Fixture
    {
        /** The pool under test. */
        private final DemoEffects effects;

        /** The port the pool publishes into and the frame is read from. */
        private final SoftwareRenderPort port;

        /** The room with nothing in the air — the comparison for every count. */
        private final int[] empty = new int[WIDTH * HEIGHT];

        /** The frame just rendered. MUTABLE: overwritten once per tic. */
        private final int[] frame = new int[WIDTH * HEIGHT];

        Fixture()
        {
            final Scene.Builder builder = Scene.builder();

            builder.addWorldInstance(wall(),
                DemoScene.placement(0.0f, 0.0f, -WALL_DISTANCE, 0.0f, 1.0f));

            this.effects = DemoEffects.addTo(builder);

            final I_TimePort time = new NullTimePort();

            time.init();

            this.port = new SoftwareRenderPort(null, time);

            port.init();

            port.resize(WIDTH, HEIGHT);

            // The demo's own camera parameters, because the muzzle offsets are
            // tuned against them: a different field of view puts the puff
            // somewhere else and changes what fraction of the frame it covers.
            port.setCamera(Camera.create(new Vec3(0.0f, 0.0f, 0.0f),
                new Vec3(0.0f, 0.0f, -1.0f), new Vec3(0.0f, 1.0f, 0.0f),
                PlayerController.DEFAULT_FOV_Y_RADIANS,
                (float) WIDTH / (float) HEIGHT,
                PlayerController.DEFAULT_NEAR_PLANE_UNITS));

            port.setScene(builder.build());

            // The first publish is what hides the pool, so the empty room has to
            // be taken after it and not before — see DemoEffects.hidden.
            effects.publish(port);

            port.renderFrame(0);

            port.copyColorInto(empty);
        }

        // One tic in exactly DemoGameplayPort's order: fire, advance, publish.
        // Getting that order wrong here would measure a picture the game never
        // draws.
        void tic(final boolean fire, final int index)
        {
            if (fire)
            {
                effects.spawn(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f);
            }

            effects.advance();

            effects.publish(port);

            port.renderFrame(index);

            port.copyColorInto(frame);
        }

        /** Returns the fraction of the frame the smoke changed on the last tic. */
        float smokeFraction()
        {
            final int from = (int) (WIDTH * SMOKE_WINDOW_FRACTION);

            // MUTABLE local — pixels counted so far.
            int changed = 0;

            for (int y = 0; y < HEIGHT; y++)
            {
                for (int x = from; x < WIDTH; x++)
                {
                    if (differs(frame[y * WIDTH + x], empty[y * WIDTH + x]))
                    {
                        changed++;
                    }
                }
            }

            return changed / FRAME_PIXELS;
        }

        /**
         * Returns the largest per-channel drop from the wall anywhere in the
         * smoke window — how dark the thickest part of the cloud gets.
         */
        int deepestDrop()
        {
            final int from = (int) (WIDTH * SMOKE_WINDOW_FRACTION);

            // MUTABLE local — the deepest drop found so far.
            int deepest = 0;

            for (int y = 0; y < HEIGHT; y++)
            {
                for (int x = from; x < WIDTH; x++)
                {
                    final int pixel = frame[y * WIDTH + x];

                    deepest = Math.max(deepest, Rgba.blue(WALL) - Rgba.blue(pixel));
                }
            }

            return deepest;
        }

        void shutdown()
        {
            port.shutdown();
        }

        private static boolean differs(final int pixel, final int reference)
        {
            return Math.abs(Rgba.red(pixel) - Rgba.red(reference)) >= CHANGED_DELTA
                || Math.abs(Rgba.green(pixel) - Rgba.green(reference)) >= CHANGED_DELTA
                || Math.abs(Rgba.blue(pixel) - Rgba.blue(reference)) >= CHANGED_DELTA;
        }
    }

    @Nested
    @DisplayName("one shot")
    final class OneShot
    {
        @Test
        @DisplayName("puts a cloud on screen on the very next frame, not a smudge")
        void freshPuffIsBigStraightAway()
        {
            // The old puff was smallest exactly when it was densest — coverage
            // falls with age while the radius grows, so the only rung with
            // enough contrast to catch the eye was also the smallest one, and it
            // lasted five tics. Whatever else is true of the effect, the frame
            // after the trigger has to have something on it.
            final Fixture fixture = new Fixture();

            fixture.tic(true, 0);

            assertThat(fixture.smokeFraction())
                .as("fraction of the frame covered one tic after the shot")
                .isGreaterThan(FRESH_PUFF_FRACTION);

            fixture.shutdown();
        }

        @Test
        @DisplayName("is still on screen half a second later")
        void aSinglePuffLastsLongEnoughToNotice()
        {
            // Half a second is the number, not a tic count, because that is the
            // quantity a player has. At 60 Hz the old 18-tic puff was three
            // tenths of a second from spawn to gone — quick enough that a shot
            // and its smoke read as one event and the smoke was the part you
            // could drop.
            final Fixture fixture = new Fixture();

            fixture.tic(true, 0);

            // MUTABLE local — tics on which the cloud was visible at all.
            int seen = 0;

            for (int tic = 1; tic <= DemoEffects.PUFF_LIFE_TICS; tic++)
            {
                fixture.tic(false, tic);

                if (fixture.smokeFraction() > 0.0f)
                {
                    seen++;
                }
            }

            assertThat(seen)
                .as("tics of a single puff a player could see something on")
                .isGreaterThanOrEqualTo(30);

            fixture.shutdown();
        }

        @Test
        @DisplayName("thins out and goes away rather than being switched off")
        void thePuffFadesAndThenEnds()
        {
            // A staircase that ends on a visible rung is a cloud that vanishes,
            // which reads as a bug even when the timing is right. The last rung
            // has to be a wisp and the tic after the last has to be empty.
            // The shot's own tic advances the puff to age 1, so the last tic it
            // is alive on is PUFF_LIFE_TICS - 1 of them later, and the one after
            // that is the first empty frame. Counting to PUFF_LIFE_TICS instead
            // steps past the end and measures the empty frame twice.
            final Fixture fixture = new Fixture();

            fixture.tic(true, 0);

            for (int tic = 1; tic < DemoEffects.PUFF_LIFE_TICS - 1; tic++)
            {
                fixture.tic(false, tic);
            }

            final float lastRung = fixture.smokeFraction();

            fixture.tic(false, DemoEffects.PUFF_LIFE_TICS - 1);

            assertThat(lastRung)
                .as("the final rung is still a wisp")
                .isGreaterThan(0.0f);

            assertThat(fixture.smokeFraction())
                .as("and the tic after the puff's life is empty")
                .isZero();

            fixture.shutdown();
        }
    }

    @Nested
    @DisplayName("a held trigger")
    final class HeldTrigger
    {
        @Test
        @DisplayName("never has a frame with no smoke in it")
        void thereIsNoGapBetweenShots()
        {
            // THE assertion the effect was missing. A tracer lives 8 tics
            // against a 12-tic cadence, so a third of the frames legitimately
            // have no bolt in them — a bolt has left. Smoke has not left, and a
            // puff shorter-lived than the gap between shots strobes at five
            // hertz, which the eye reads as a glitch rather than as smoke.
            final Fixture fixture = new Fixture();

            final int tics = SHOTS * DemoGameplayPort.FIRE_INTERVAL_TICS;

            for (int tic = 0; tic < tics; tic++)
            {
                final boolean fire = tic % DemoGameplayPort.FIRE_INTERVAL_TICS == 0;

                fixture.tic(fire, tic);

                assertThat(fixture.smokeFraction())
                    .as("smoke on screen at tic %d of %d", tic, tics)
                    .isGreaterThan(SUSTAINED_FRACTION);
            }

            fixture.shutdown();
        }

        @Test
        @DisplayName("builds up into one churning cloud instead of repeating one puff")
        void overlappingPuffsAccumulate()
        {
            // If a puff died before the next shot, this number would return to
            // where it started every twelve tics. That it keeps climbing is what
            // makes the effect read as a cloud being fed rather than as a row of
            // separate puffs.
            //
            // The exact ratio the 3-puff cloud sits at over the 1-puff cloud
            // changes with the radius-vs-life math: a fresh puff that starts
            // bigger and lives longer is more "puff" and less "growth", so 3
            // puffs cover somewhat less than 2x of a single fresh puff. The
            // property the test still guards is the climb, not the multiplier.
            final Fixture fixture = new Fixture();

            fixture.tic(true, 0);

            final float afterOne = fixture.smokeFraction();

            for (int tic = 1; tic < SHOTS * DemoGameplayPort.FIRE_INTERVAL_TICS; tic++)
            {
                fixture.tic(tic % DemoGameplayPort.FIRE_INTERVAL_TICS == 0, tic);
            }

            assertThat(fixture.smokeFraction())
                .as("three shots in, against one shot's %s", afterOne)
                .isGreaterThan(HELD_TRIGGER_FRACTION)
                .isGreaterThan(afterOne * 1.5f);

            fixture.shutdown();
        }

        @Test
        @DisplayName("is never a hole in the room — the wall shows through everywhere")
        void theCloudStaysTranslucent()
        {
            // The other way this has already gone wrong: pushed too dense, the
            // puff stopped looking like a cloud and started looking like a black
            // block punched through the wall. Measured on the deepest pixel in
            // the whole sequence, because that is where an overshoot appears —
            // and against the SMOKE's own colour, since a pixel that reached it
            // would be one the wall contributed nothing to.
            final Fixture fixture = new Fixture();

            final int floor = Rgba.blue(WALL) - Rgba.blue(DemoEffects.smokeColour());

            for (int tic = 0; tic < SHOTS * DemoGameplayPort.FIRE_INTERVAL_TICS; tic++)
            {
                fixture.tic(tic % DemoGameplayPort.FIRE_INTERVAL_TICS == 0, tic);

                assertThat(fixture.deepestDrop())
                    .as("deepest pixel at tic %d must be visibly darker than the wall", tic)
                    .isGreaterThan(CHANGED_DELTA);

                assertThat(fixture.deepestDrop())
                    .as("but the wall must still show through it at tic %d", tic)
                    .isLessThan(floor);
            }

            fixture.shutdown();
        }
    }
}
