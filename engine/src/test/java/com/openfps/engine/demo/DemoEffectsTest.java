/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The tracer and smoke pools: how long an effect lives, and what the renderer
 * is told about it.
 *
 * <p>The lifetimes are the part worth testing rather than the geometry. An
 * effect that never expires leaks an instance out of a fixed pool and the demo
 * quietly stops showing new shots; an effect that expires a tic early flickers.
 * Neither shows up in a screenshot, and both are arithmetic.</p>
 *
 * <p>The instance placements are asserted through a real
 * {@link SoftwareRenderPort}, because {@code setWorldTransform} is the entire
 * seam between this class and anything visible — and because the override it
 * writes can be read straight back out, which makes "is this instance hidden"
 * an assertion rather than a screenshot.</p>
 */
@DisplayName("DemoEffects")
final class DemoEffectsTest
{
    /** A direction straight down world +z, which is where the demo spawn faces. */
    private static final float AIM_X = 0.0f;

    /** See {@link #AIM_X}. */
    private static final float AIM_Y = 0.0f;

    /** See {@link #AIM_X}. */
    private static final float AIM_Z = 1.0f;

    /** An eye position with no round numbers in it, so an axis swap shows up. */
    private static final float EYE_X = 3.0f;

    /** See {@link #EYE_X}. */
    private static final float EYE_Y = 41.0f;

    /** See {@link #EYE_X}. */
    private static final float EYE_Z = -192.0f;

    /**
     * The muzzle position the player's shot produces, derived the same way
     * {@link DemoEffects#spawn} derives it: the eye offset by
     * {@link DemoEffects#MUZZLE_FORWARD_UNITS} along the aim and
     * {@link DemoEffects#MUZZLE_RIGHT_UNITS} along the right, with the gun's
     * drop applied to Y. Pinned down so the flash test asserts a value the
     * code would actually produce — the constants used here are the same
     * numbers the spawner used.
     */
    private static final float EXPECTED_MUZZLE_X = EYE_X + 0.0f * DemoEffects.MUZZLE_FORWARD_UNITS
        + (-1.0f) * DemoEffects.MUZZLE_RIGHT_UNITS;

    /** See {@link #EXPECTED_MUZZLE_X}. */
    private static final float EXPECTED_MUZZLE_Y = EYE_Y + 0.0f * DemoEffects.MUZZLE_FORWARD_UNITS
        + 0.0f * DemoEffects.MUZZLE_RIGHT_UNITS - DemoEffects.MUZZLE_DROP_UNITS;

    /** See {@link #EXPECTED_MUZZLE_X}. */
    private static final float EXPECTED_MUZZLE_Z = EYE_Z + 1.0f * DemoEffects.MUZZLE_FORWARD_UNITS
        + 0.0f * DemoEffects.MUZZLE_RIGHT_UNITS;

    /** Surface size for the port; nothing looks at the pixels here. */
    private static final int SURFACE = 32;

    /**
     * The smoke tests need puffs to exist. Production leaves
     * {@link DemoEffects#SMOKE_ENABLED} at its default of false; every
     * test in this class runs with it true and resets to false in
     * {@link #resetSmoke()}, so a smoke regression in
     * {@code spawnPuff} shows up here even though no shipped
     * gameplay path would have triggered it.
     */
    @BeforeEach
    void enableSmokeForTests()
    {
        DemoEffects.SMOKE_ENABLED = true;
    }

    @AfterEach
    void resetSmoke()
    {
        DemoEffects.SMOKE_ENABLED = false;
    }

    /** A scene holding nothing but one effect pool, and the pool that drives it. */
    private static final class Fixture
    {
        private final DemoEffects effects;
        private final SoftwareRenderPort renderer;

        Fixture()
        {
            final Scene.Builder builder = Scene.builder();

            this.effects = DemoEffects.addTo(builder);

            final I_TimePort time = new NullTimePort();

            time.init();

            this.renderer = new SoftwareRenderPort(null, time);

            renderer.init();

            renderer.resize(SURFACE, SURFACE);

            renderer.setScene(builder.build());
        }

        void fire()
        {
            effects.spawn(EYE_X, EYE_Y, EYE_Z, AIM_X, AIM_Y, AIM_Z);
        }

        // One tic of the demo's own order: advance, then publish.
        void tic()
        {
            effects.advance();

            effects.publish(renderer);
        }

        Mat4 tracerOverride(final int slot)
        {
            return renderer.worldTransformOverride(effects.tracerInstanceIndex(slot));
        }

        Mat4 puffOverride(final int slot, final int stage)
        {
            return puffOverride(slot, stage, 0);
        }

        Mat4 puffOverride(final int slot, final int stage, final int lobe)
        {
            return renderer.worldTransformOverride(
                effects.puffInstanceIndex(slot, stage, lobe));
        }
    }

    @Nested
    @DisplayName("the smoke sphere")
    final class SmokeGeometry
    {
        /** Float tolerance for the sphere's radius, which is built from sin and cos. */
        private static final float EPSILON = 1.0e-5f;

        @Test
        @DisplayName("every vertex is the same distance from the origin — it is a sphere")
        void everyVertexIsOnTheSphere()
        {
            // A box has corners at sqrt(3) / 2 times its face distance and that is
            // precisely why it reads as a box. Nothing on this model may be
            // further out in one direction than another.
            final ModelFormat cloud = DemoEffects.sphere(DemoEffects.smokeColour());

            for (int vertex = 0; vertex < cloud.vertexCount(); vertex++)
            {
                final float x = cloud.positionX(vertex);

                final float y = cloud.positionY(vertex);

                final float z = cloud.positionZ(vertex);

                assertThat((float) StrictMath.sqrt(x * x + y * y + z * z))
                    .as("vertex %d distance from the origin", vertex)
                    .isCloseTo(0.5f, within(EPSILON));
            }
        }

        @Test
        @DisplayName("it occupies the same unit box the tracer does, so the placement scale "
            + "is unchanged")
        void fitsTheUnitBox()
        {
            final ModelFormat cloud = DemoEffects.sphere(DemoEffects.smokeColour());

            assertThat(cloud.minX()).isCloseTo(-0.5f, within(EPSILON));

            assertThat(cloud.maxY()).isCloseTo(0.5f, within(EPSILON));
        }

        @Test
        @DisplayName("every triangle faces outward, so backface culling leaves ONE layer")
        void isWoundOutward()
        {
            // The property that matters most for a translucent sphere. If the
            // winding were reversed the far hemisphere would survive the cull
            // and the near one would not, and every puff would composite the
            // wrong layer; if the model were not closed, both would draw and the
            // cloud would be twice as dense as the coverage ladder says.
            //
            // For a convex body about the origin, "outward" is exactly
            // "the face normal points away from the centroid".
            final ModelFormat cloud = DemoEffects.sphere(DemoEffects.smokeColour());

            final int[] indices = cloud.indices();

            for (int triangle = 0; triangle * 3 < indices.length; triangle++)
            {
                final int a = indices[triangle * 3];

                final int b = indices[triangle * 3 + 1];

                final int c = indices[triangle * 3 + 2];

                final float ux = cloud.positionX(b) - cloud.positionX(a);

                final float uy = cloud.positionY(b) - cloud.positionY(a);

                final float uz = cloud.positionZ(b) - cloud.positionZ(a);

                final float vx = cloud.positionX(c) - cloud.positionX(a);

                final float vy = cloud.positionY(c) - cloud.positionY(a);

                final float vz = cloud.positionZ(c) - cloud.positionZ(a);

                final float nx = uy * vz - uz * vy;

                final float ny = uz * vx - ux * vz;

                final float nz = ux * vy - uy * vx;

                final float outward = nx * cloud.positionX(a)
                    + ny * cloud.positionY(a) + nz * cloud.positionZ(a);

                assertThat(outward)
                    .as("triangle %d normal must point away from the centre", triangle)
                    .isPositive();
            }
        }

        @Test
        @DisplayName("it is closed: every edge is shared by exactly two triangles")
        void isClosed()
        {
            // An open sphere would show its inside through the gap, which on a
            // translucent instance is a bright hole rather than a missing patch.
            final ModelFormat cloud = DemoEffects.sphere(DemoEffects.smokeColour());

            final int[] indices = cloud.indices();

            final java.util.Map<Long, Integer> edges = new java.util.HashMap<>();

            for (int triangle = 0; triangle * 3 < indices.length; triangle++)
            {
                for (int corner = 0; corner < 3; corner++)
                {
                    final int from = indices[triangle * 3 + corner];

                    final int to = indices[triangle * 3 + (corner + 1) % 3];

                    final long key = (long) Math.min(from, to) << 32 | Math.max(from, to);

                    edges.merge(Long.valueOf(key), Integer.valueOf(1), Integer::sum);
                }
            }

            assertThat(edges.values())
                .as("every edge of a closed surface is walked exactly twice")
                .allMatch(count -> count.intValue() == 2);
        }

        @Test
        @DisplayName("it has no submesh table, so every triangle takes the flat colour path")
        void isUntextured()
        {
            // Required, not incidental: the translucent phase binds no texture
            // table at all, so a textured submesh here would index into nothing.
            final ModelFormat cloud = DemoEffects.sphere(DemoEffects.smokeColour());

            assertThat(cloud.submeshCount()).isZero();

            assertThat(cloud.textureCount()).isZero();

            assertThat(cloud.colour(0)).isEqualTo(DemoEffects.smokeColour());
        }

        @Test
        @DisplayName("it is rounder than the box it replaces, and still cheap")
        void isCoarseButRound()
        {
            final ModelFormat cloud = DemoEffects.sphere(DemoEffects.smokeColour());

            assertThat(cloud.triangleCount())
                .as("more facets than a cube's twelve, or it is still a block")
                .isGreaterThan(48);

            assertThat(cloud.triangleCount())
                .as("but nowhere near an authored asset — 36 of these are staged")
                .isLessThan(200);
        }
    }

    @Nested
    @DisplayName("the lobes are laid out across the view")
    final class LobeLayout
    {
        /** Float tolerance for a dot product of unit vectors. */
        private static final float EPSILON = 1.0e-4f;

        @Test
        @DisplayName("the lobes spread across the screen, not along the line of sight")
        void spreadsAcrossTheView()
        {
            // The whole reason the offsets use the shot's basis. Laid out along
            // world axes, a puff fired down +z would put its outriders directly
            // behind and in front of its centre — invisible from the one place
            // anybody is looking at it from, so the cloud would collapse back
            // into a single sphere exactly when the player shoots straight
            // ahead, which is most of the time.
            final Fixture fixture = new Fixture();

            fixture.fire();

            fixture.tic();

            final Mat4 centre = fixture.puffOverride(0, 0, 0);

            for (int lobe = 1; lobe < DemoEffects.PUFF_LOBES; lobe++)
            {
                final Mat4 out = fixture.puffOverride(0, 0, lobe);

                final float alongAim = (out.get(2, 3) - centre.get(2, 3)) * AIM_Z;

                assertThat(Math.abs(alongAim))
                    .as("lobe %d must not be displaced along the aim", lobe)
                    .isLessThan(EPSILON);

                assertThat(separation(centre, out))
                    .as("lobe %d must actually be somewhere else", lobe)
                    .isGreaterThan(0.0f);
            }
        }

        @Test
        @DisplayName("no lobe drifts so far that the cloud comes apart")
        void staysOneCloud()
        {
            // Lobes further apart than their radii are three balls, not a
            // cloud. Each outrider's centre has to be inside the main lobe.
            final Fixture fixture = new Fixture();

            fixture.fire();

            fixture.tic();

            final Mat4 centre = fixture.puffOverride(0, 0, 0);

            final float mainRadius = centre.get(1, 1) * 0.5f;

            for (int lobe = 1; lobe < DemoEffects.PUFF_LOBES; lobe++)
            {
                assertThat(separation(centre, fixture.puffOverride(0, 0, lobe)))
                    .as("lobe %d sits inside the main lobe", lobe)
                    .isLessThan(mainRadius);
            }
        }

        @Test
        @DisplayName("the outriders are smaller than the lobe they hang off")
        void outridersAreSmaller()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            fixture.tic();

            final float main = fixture.puffOverride(0, 0, 0).get(1, 1);

            for (int lobe = 1; lobe < DemoEffects.PUFF_LOBES; lobe++)
            {
                assertThat(fixture.puffOverride(0, 0, lobe).get(1, 1))
                    .as("lobe %d scale", lobe)
                    .isLessThan(main)
                    .isPositive();
            }
        }

        @Test
        @DisplayName("the lobes grow with the puff rather than drifting apart")
        void theArrangementScalesWithThePuff()
        {
            // Offsets are in multiples of the radius, so the cloud expands as
            // one shape. Offsets in fixed units would keep the lumps still
            // while the spheres swelled through each other, and the cloud would
            // turn into a single ball as it aged.
            final Fixture fixture = new Fixture();

            fixture.fire();

            fixture.tic();

            final Mat4 youngCentre = fixture.puffOverride(0, 0, 0);

            final float young = separation(youngCentre, fixture.puffOverride(0, 0, 1))
                / youngCentre.get(1, 1);

            for (int tic = 0; tic < DemoEffects.PUFF_LIFE_TICS - 2; tic++)
            {
                fixture.tic();
            }

            final int stage = DemoEffects.stageFor(fixture.effects.puffAge(0));

            final Mat4 oldCentre = fixture.puffOverride(0, stage, 0);

            final float old = separation(oldCentre, fixture.puffOverride(0, stage, 1))
                / oldCentre.get(1, 1);

            assertThat(oldCentre.get(1, 1))
                .as("the puff really did grow")
                .isGreaterThan(youngCentre.get(1, 1));

            assertThat(old)
                .as("and the lumps kept their proportions")
                .isCloseTo(young, within(EPSILON));
        }

        // Distance between two placements' translation columns.
        private float separation(final Mat4 first, final Mat4 second)
        {
            final float dx = second.get(0, 3) - first.get(0, 3);

            final float dy = second.get(1, 3) - first.get(1, 3);

            final float dz = second.get(2, 3) - first.get(2, 3);

            return (float) StrictMath.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    @Nested
    @DisplayName("placement in the scene")
    final class Placement
    {
        @Test
        @DisplayName("occupies one instance per tracer and one per puff stage per lobe")
        void instanceCountIsTheWholePool()
        {
            final Scene.Builder builder = Scene.builder();

            final DemoEffects effects = DemoEffects.addTo(builder);

            // The full pool: tracer slots (one instance per slot), puff slots
            // crossed with stages, lobes and colour variants (one instance per
            // lobe per stage per variant), and one flash slot per muzzle flash.
            // The flash is one instance per slot, the same shape as the tracer
            // — a flash lives or dies and the size is a transform property,
            // not a separate model.
            assertThat(effects.instanceCount()).isEqualTo(DemoEffects.tracerSlotCount()
                + DemoEffects.puffSlotCount() * DemoEffects.PUFF_STAGES
                    * DemoEffects.PUFF_LOBES * DemoEffects.PUFF_COLOR_VARIANTS
                + DemoEffects.flashSlotCount());

            assertThat(builder.worldInstanceCount()).isEqualTo(effects.instanceCount());
        }

        @Test
        @DisplayName("every lobe of every stage of every puff gets its own instance, all distinct")
        void everyLobeIsItsOwnInstance()
        {
            // Two lobes sharing an instance would look like one lobe and would
            // fight over the transform every tic — and it is exactly the sort of
            // index arithmetic that goes wrong silently. Every variant of every
            // (slot, stage, lobe) is its own instance, not just the warm one,
            // because the publish writes one per active variant.
            final Scene.Builder builder = Scene.builder();

            final DemoEffects effects = DemoEffects.addTo(builder);

            final java.util.Set<Integer> seen = new java.util.HashSet<>();

            for (int slot = 0; slot < DemoEffects.MAX_PUFFS; slot++)
            {
                for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
                {
                    for (int lobe = 0; lobe < DemoEffects.PUFF_LOBES; lobe++)
                    {
                        for (int variant = 0; variant < DemoEffects.PUFF_COLOR_VARIANTS; variant++)
                        {
                            assertThat(seen.add(
                                Integer.valueOf(
                                    effects.puffInstanceIndex(slot, stage, lobe, variant))))
                                .as("puff %d stage %d lobe %d variant %d is a fresh instance",
                                    slot, stage, lobe, variant)
                                .isTrue();
                        }
                    }
                }
            }

            assertThat(seen).hasSize(
                DemoEffects.MAX_PUFFS * DemoEffects.PUFF_STAGES * DemoEffects.PUFF_LOBES
                    * DemoEffects.PUFF_COLOR_VARIANTS);
        }

        @Test
        @DisplayName("the two-argument index names the main lobe, so old callers get the centre")
        void theShortIndexIsTheMainLobe()
        {
            final Scene.Builder builder = Scene.builder();

            final DemoEffects effects = DemoEffects.addTo(builder);

            assertThat(effects.puffInstanceIndex(1, 2))
                .isEqualTo(effects.puffInstanceIndex(1, 2, 0));
        }

        @Test
        @DisplayName("all lobes of one stage share that stage's coverage, so they are one run")
        void lobesOfAStageShareItsCoverage()
        {
            // The translucent phase draws maximal runs of EQUAL coverage. Lobes
            // that disagreed would cut the run and cost a whole extra batched
            // pass per puff, for a cloud that is meant to be one thing.
            final Scene.Builder builder = Scene.builder();

            final DemoEffects effects = DemoEffects.addTo(builder);

            final Scene scene = builder.build();

            for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
            {
                for (int lobe = 0; lobe < DemoEffects.PUFF_LOBES; lobe++)
                {
                    assertThat(scene.worldCoverage(effects.puffInstanceIndex(0, stage, lobe)))
                        .as("stage %d lobe %d", stage, lobe)
                        .isEqualTo(DemoEffects.coverageFor(stage));
                }
            }
        }

        @Test
        @DisplayName("a lobe's colour variants are consecutive instances, so a stage is one run")
        void variantsOfALobeAreAdjacent()
        {
            // The translucent phase draws maximal runs of EQUAL coverage. The
            // load-bearing adjacency for that is "a stage's instances are
            // consecutive": with the variant expansion, that means the
            // variants of one lobe run together, then the next lobe's
            // variants, and so on. A reader that does not have that property
            // would pay a back-to-front pass per lobe.
            final Scene.Builder builder = Scene.builder();

            final DemoEffects effects = DemoEffects.addTo(builder);

            for (int variant = 1; variant < DemoEffects.PUFF_COLOR_VARIANTS; variant++)
            {
                assertThat(effects.puffInstanceIndex(2, 1, 0, variant))
                    .isEqualTo(effects.puffInstanceIndex(2, 1, 0, variant - 1) + 1);
            }

            for (int lobe = 1; lobe < DemoEffects.PUFF_LOBES; lobe++)
            {
                assertThat(effects.puffInstanceIndex(2, 1, lobe, 0))
                    .as("lobe %d starts a fresh variant run", lobe)
                    .isEqualTo(effects.puffInstanceIndex(2, 1, lobe - 1, 0)
                        + DemoEffects.PUFF_COLOR_VARIANTS);
            }
        }

        @Test
        @DisplayName("the smoke is translucent and the tracers are not")
        void onlyTheSmokeIsTranslucent()
        {
            final Scene.Builder builder = Scene.builder();

            final DemoEffects effects = DemoEffects.addTo(builder);

            final Scene scene = builder.build();

            assertThat(scene.translucentInstanceCount())
                .isEqualTo(DemoEffects.puffSlotCount() * DemoEffects.PUFF_STAGES
                    * DemoEffects.PUFF_LOBES * DemoEffects.PUFF_COLOR_VARIANTS);

            for (int slot = 0; slot < DemoEffects.tracerSlotCount(); slot++)
            {
                assertThat(scene.isWorldTranslucent(effects.tracerInstanceIndex(slot)))
                    .as("a bolt in flight is solid")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("each puff stage carries its own coverage, faintest last")
        void stagesFadeDown()
        {
            final Scene.Builder builder = Scene.builder();

            final DemoEffects effects = DemoEffects.addTo(builder);

            final Scene scene = builder.build();

            for (int stage = 1; stage < DemoEffects.PUFF_STAGES; stage++)
            {
                assertThat(DemoEffects.coverageFor(stage))
                    .as("stage %d must be fainter than the one before it", stage)
                    .isLessThan(DemoEffects.coverageFor(stage - 1));
            }

            assertThat(scene.worldCoverage(effects.puffInstanceIndex(0, 0)))
                .isEqualTo(DemoEffects.coverageFor(0));

            assertThat(DemoEffects.coverageFor(0)).isLessThan(Scene.OPAQUE);
        }

        @Test
        @DisplayName("rejects a null builder rather than failing later with no scene")
        void rejectsNullBuilder()
        {
            assertThatThrownBy(() -> DemoEffects.addTo(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("visibility — the smoke has to be seen, not merely drawn")
    final class Visibility
    {
        /**
         * The demo room's lit wall and floor, sampled from a real capture at
         * 1280x720: {@code (141, 147, 177)}, a pale grey-blue.
         *
         * <p>A measured number rather than a derived one, because there is
         * nothing to derive it from — it is a Kenney atlas texel times whatever
         * the rasterizer does with it. If the art is restaged and this drifts,
         * the assertions below are the right thing to fail: they are the whole
         * question this fixture asks.</p>
         */
        private static final int ROOM_RED = 141;

        /** See {@link #ROOM_RED}. */
        private static final int ROOM_GREEN = 147;

        /** See {@link #ROOM_RED}. */
        private static final int ROOM_BLUE = 177;

        /**
         * Least per-channel distance from the background that counts as
         * visible, on the freshest rung.
         *
         * <p>Not a perceptual model, just a floor well above the level at which
         * the bug lived: the old smoke resolved about ten levels from the wall
         * on its most opaque rung, which nobody could see.</p>
         */
        private static final int VISIBLE_DELTA = 30;

        /** The same floor for the faintest rung, where a wisp is the intent. */
        private static final int WISP_DELTA = 6;

        @Test
        @DisplayName("the freshest rung is plainly darker than the room behind it")
        void freshSmokeStandsOutAgainstTheRoom()
        {
            // THE regression guard for "the smoke is not there".
            //
            // The smoke was never missing. It was drawn every frame, in the
            // right place, blended by exactly the right arithmetic — in a grey
            // that sat about ten levels from the pale grey-blue wall behind it.
            // Every test passed and the feature was invisible, because nothing
            // asserted the one property that matters to a player: that the
            // result differs from what it is drawn over.
            //
            // Composited through ALL the lobes, because that is what the frame
            // does — the per-lobe coverage on its own is meaningless now, and
            // asserting on it would be the same mistake in a new place.
            final int core = coreOf(0);

            assertThat(Math.abs(Rgba.red(core) - ROOM_RED))
                .as("red must move; composited %s over the room", core)
                .isGreaterThanOrEqualTo(VISIBLE_DELTA);

            assertThat(Math.abs(Rgba.green(core) - ROOM_GREEN))
                .isGreaterThanOrEqualTo(VISIBLE_DELTA);

            assertThat(Math.abs(Rgba.blue(core) - ROOM_BLUE))
                .isGreaterThanOrEqualTo(VISIBLE_DELTA);
        }

        @Test
        @DisplayName("the puff has a soft edge: rim, shoulder and core are three distinct "
            + "densities")
        void theCloudHasAGradient()
        {
            // THE reason the puff is five lobes rather than one. A single
            // instance at a single coverage is flat, and flat is what made the
            // old puff read as a block. Overlapping instances composite over
            // each other, so one coverage becomes a falloff — and this asserts
            // the falloff is real and each step of it is worth having, rather
            // than five lobes producing one indistinguishable smear.
            //
            // It is also what lets the cloud be as big as it now is without
            // reading as a pane of tinted glass: an edge that composites once
            // dissolves rather than ending. See puffStaysSmallEnoughToReadAsSmoke,
            // whose size ceiling used to be doing this test's job badly.
            final int rim = compositeLobes(0, 1);

            final int shoulder = compositeLobes(0, DemoEffects.PUFF_LOBES / 2);

            final int core = compositeLobes(0, DemoEffects.PUFF_LOBES);

            assertThat(Rgba.red(core)).isLessThan(Rgba.red(shoulder));

            assertThat(Rgba.red(shoulder)).isLessThan(Rgba.red(rim));

            assertThat(Rgba.red(rim)).isLessThan(ROOM_RED);

            // Each step has to be a step a viewer can see, or the gradient is a
            // rounding artefact rather than a shape.
            assertThat(Rgba.red(rim) - Rgba.red(shoulder))
                .as("rim to shoulder").isGreaterThanOrEqualTo(WISP_DELTA);

            assertThat(Rgba.red(shoulder) - Rgba.red(core))
                .as("shoulder to core").isGreaterThanOrEqualTo(WISP_DELTA);
        }

        @Test
        @DisplayName("a single lobe is a wisp, not a wall — the rim must not be the core")
        void theRimIsMuchLighterThanTheCore()
        {
            // If one lobe already looked like the finished cloud, the other four
            // would only be making it denser, and the edge would be as hard as
            // the old cube's.
            final int rim = compositeLobes(0, 1);

            final int core = coreOf(0);

            assertThat(ROOM_RED - Rgba.red(rim))
                .as("the rim is visible")
                .isGreaterThanOrEqualTo(WISP_DELTA);

            assertThat(ROOM_RED - Rgba.red(rim))
                .as("but is at most two thirds of the core's density")
                .isLessThan((ROOM_RED - Rgba.red(core)) * 2 / 3 + 1);
        }

        // The room with `lobes` overlapping lobes of one stage composited over
        // it, in the order the translucent phase composites them: back to front,
        // each srcOver the result of the last.
        private int compositeLobes(final int stage, final int lobes)
        {
            // MUTABLE local — the pixel as each successive lobe leaves it.
            int pixel = Rgba.pack(ROOM_RED, ROOM_GREEN, ROOM_BLUE, Scene.OPAQUE);

            for (int lobe = 0; lobe < lobes; lobe++)
            {
                pixel = Rgba.srcOver(DemoEffects.smokeColour(), pixel,
                    DemoEffects.coverageFor(stage));
            }

            return pixel;
        }

        // The densest point of a stage: every lobe overlapping.
        private int coreOf(final int stage)
        {
            return compositeLobes(stage, DemoEffects.PUFF_LOBES);
        }

        @Test
        @DisplayName("every rung moves the pixel, and the faintest is still a wisp")
        void everyRungIsPerceptible()
        {
            // A staircase whose lower steps land on the background is a fade
            // that stops early and then jumps to nothing. Each rung has to
            // carry some of the puff, or the stages below it are instances
            // rendered for no visible result.
            for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
            {
                assertThat(Math.abs(Rgba.red(coreOf(stage)) - ROOM_RED))
                    .as("stage %d must still tint the room", stage)
                    .isGreaterThanOrEqualTo(WISP_DELTA);
            }
        }

        @Test
        @DisplayName("the smoke stays translucent — a puff you cannot see through is a hole")
        void smokeIsNeverOpaque()
        {
            // The overcorrection this guards against was real: raising the top
            // rung to 228 against a dark colour produced a block that read as a
            // hole punched in the room rather than as a cloud. Coverage below
            // OPAQUE is what Scene needs to put the instance in the translucent
            // partition at all; leaving visible headroom below it is what makes
            // the result look like smoke.
            for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
            {
                assertThat(DemoEffects.coverageFor(stage))
                    .as("stage %d", stage)
                    .isLessThan(Scene.OPAQUE)
                    .isPositive();
            }

            // Against the DENSEST point of the cloud — every lobe overlapping —
            // because that is where an overshoot would show up, and because the
            // lobes compound: a per-lobe coverage that looks modest can still
            // add up to something you cannot see through. That is the new way
            // the old 228 mistake could come back.
            final int core = coreOf(0);

            assertThat(Rgba.red(core))
                .as("the room still shows through the thickest part of the cloud")
                .isGreaterThan(Rgba.red(DemoEffects.smokeColour()));

            assertThat(Rgba.red(core) - Rgba.red(DemoEffects.smokeColour()))
                .as("and by a visible margin, not by one level")
                .isGreaterThan(WISP_DELTA);
        }

        @Test
        @DisplayName("the muzzle is out at the drawn barrel, not back inside the weapon")
        void theMuzzleSitsAtTheBarrelTip()
        {
            // The second half of "the smoke is not there": at 0.9 units right
            // the puff was centred on the weapon's own body, which draws AFTER
            // the translucent phase behind its own depth clear and therefore
            // covers it. DemoScene.WEAPON_VIEW_RIGHT places the model's origin;
            // the muzzle is at the far end of the barrel, so it has to be
            // further out than that or the gun hides its own smoke.
            assertThat(DemoEffects.MUZZLE_RIGHT_UNITS)
                .as("further right than the weapon's origin")
                .isGreaterThan(DemoScene.WEAPON_VIEW_RIGHT);

            assertThat(DemoEffects.MUZZLE_FORWARD_UNITS)
                .as("and further out than the weapon is held")
                .isGreaterThan(DemoScene.WEAPON_VIEW_FORWARD);
        }

        @Test
        @DisplayName("a puff is a puff, not a fog bank — and it never reaches the point of aim")
        void puffStaysSmallEnoughToReadAsSmoke()
        {
            // At 2.4 units from the eye and a 60-degree vertical field of view,
            // a half-extent of r subtends roughly 2*atan(r / 2.4). Expressed as
            // a fraction of the frame rather than in units so it survives a
            // resolution change.
            //
            // Measured on the WHOLE cloud, not on the main lobe: the outriders
            // stick out past it, so sizing against one sphere would let the
            // thing the player actually sees grow by half without this noticing.
            //
            // THE CEILING HERE USED TO BE 0.20 AND IT WAS THE WRONG QUANTITY.
            // It was written after a 0.34-radius single cube read as a
            // translucent sheet across the view, and it recorded the size of
            // that failure rather than its cause. The cause was flatness: one
            // instance at one coverage has a hard silhouette and a uniform
            // interior at any size. A five-lobe cloud at a bigger radius does
            // not read as a pane, because its outer third composites once and
            // dissolves — which is what theCloudHasAGradient and
            // theRimIsMuchLighterThanTheCore actually assert, and they are the
            // real guard against that failure coming back.
            //
            // So this keeps a ceiling, because "puff" and "fog bank" are
            // different things, and adds the property that a size bound was
            // standing in for all along: the cloud must not creep across the
            // player's point of aim. A muzzle effect that veils the crosshair
            // stops being decoration and starts being a handicap.
            final float extent = DemoEffects.PUFF_RADIUS_END * DemoEffects.cloudExtentRadii();

            final double halfAngle =
                StrictMath.atan(extent / DemoEffects.MUZZLE_FORWARD_UNITS);

            final double fractionOfHeight = 2.0 * halfAngle / (Math.PI / 3.0);

            assertThat(fractionOfHeight)
                .as("a muzzle puff should not fill a third of the window")
                .isLessThan(0.35);

            assertThat(extent)
                .as("and never reaches back to where the player is aiming")
                .isLessThan(DemoEffects.MUZZLE_RIGHT_UNITS);

            assertThat(DemoEffects.PUFF_RADIUS_END)
                .as("but it still expands over its life")
                .isGreaterThan(DemoEffects.PUFF_RADIUS_START);

            assertThat(DemoEffects.cloudExtentRadii())
                .as("and the arrangement really is wider than one sphere")
                .isGreaterThan(1.0f);
        }
    }

    @Nested
    @DisplayName("lifetimes")
    final class Lifetimes
    {
        @Test
        @DisplayName("nothing is alive until something is fired")
        void startsEmpty()
        {
            final Fixture fixture = new Fixture();

            assertThat(fixture.effects.liveTracerCount()).isZero();

            assertThat(fixture.effects.livePuffCount()).isZero();

            assertThat(fixture.effects.tracerRemaining(0)).isEqualTo(DemoEffects.DEAD);

            assertThat(fixture.effects.puffAge(0)).isEqualTo(DemoEffects.DEAD);
        }

        @Test
        @DisplayName("one shot starts exactly one tracer and one puff")
        void oneShotOneOfEach()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            assertThat(fixture.effects.liveTracerCount()).isEqualTo(1);

            assertThat(fixture.effects.livePuffCount()).isEqualTo(1);

            assertThat(fixture.effects.tracerRemaining(0))
                .isEqualTo(DemoEffects.TRACER_LIFE_TICS);

            assertThat(fixture.effects.puffAge(0)).isZero();
        }

        @Test
        @DisplayName("a tracer survives exactly its life and then expires")
        void tracerExpiresOnSchedule()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            for (int tic = 1; tic < DemoEffects.TRACER_LIFE_TICS; tic++)
            {
                fixture.tic();

                assertThat(fixture.effects.tracerRemaining(0))
                    .as("still flying at tic %d of %d", tic, DemoEffects.TRACER_LIFE_TICS)
                    .isEqualTo(DemoEffects.TRACER_LIFE_TICS - tic);
            }

            fixture.tic();

            assertThat(fixture.effects.tracerRemaining(0)).isEqualTo(DemoEffects.DEAD);

            assertThat(fixture.effects.liveTracerCount()).isZero();
        }

        @Test
        @DisplayName("a puff survives exactly its life and then expires")
        void puffExpiresOnSchedule()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            for (int tic = 1; tic < DemoEffects.PUFF_LIFE_TICS; tic++)
            {
                fixture.tic();

                assertThat(fixture.effects.puffAge(0))
                    .as("still drifting at tic %d of %d", tic, DemoEffects.PUFF_LIFE_TICS)
                    .isEqualTo(tic);
            }

            fixture.tic();

            assertThat(fixture.effects.puffAge(0)).isEqualTo(DemoEffects.DEAD);

            assertThat(fixture.effects.livePuffCount()).isZero();
        }

        @Test
        @DisplayName("the smoke outlives the tracer, which is what leaves a trail at the muzzle")
        void smokeOutlivesTheBolt()
        {
            assertThat(DemoEffects.PUFF_LIFE_TICS)
                .isGreaterThan(DemoEffects.TRACER_LIFE_TICS);
        }

        @Test
        @DisplayName("advancing with nothing alive does nothing at all")
        void advancingEmptyIsSafe()
        {
            final Fixture fixture = new Fixture();

            fixture.tic();

            fixture.tic();

            assertThat(fixture.effects.liveTracerCount()).isZero();

            assertThat(fixture.effects.livePuffCount()).isZero();
        }

        @Test
        @DisplayName("firing more shots than there are slots wraps rather than overflowing")
        void poolWrapsRoundRobin()
        {
            final Fixture fixture = new Fixture();

            for (int shot = 0; shot < DemoEffects.MAX_TRACERS + 2; shot++)
            {
                fixture.fire();
            }

            // Every slot busy, none lost, nothing thrown. The two extra shots
            // overwrote the two oldest, which is the documented behaviour.
            assertThat(fixture.effects.liveTracerCount()).isEqualTo(DemoEffects.MAX_TRACERS);

            assertThat(fixture.effects.livePuffCount()).isEqualTo(DemoEffects.MAX_PUFFS);
        }

        @Test
        @DisplayName("consecutive shots claim different slots")
        void consecutiveShotsDoNotShareASlot()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            fixture.tic();

            fixture.fire();

            assertThat(fixture.effects.tracerRemaining(0))
                .as("the first bolt has aged")
                .isEqualTo(DemoEffects.TRACER_LIFE_TICS - 1);

            assertThat(fixture.effects.tracerRemaining(1))
                .as("the second is brand new, in its own slot")
                .isEqualTo(DemoEffects.TRACER_LIFE_TICS);
        }

        @Test
        @DisplayName("a flash survives exactly its life and then expires")
        void flashExpiresOnSchedule()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            assertThat(fixture.effects.flashRemaining(0))
                .isEqualTo(DemoEffects.FLASH_LIFE_TICS);

            for (int tic = 1; tic < DemoEffects.FLASH_LIFE_TICS; tic++)
            {
                fixture.tic();

                assertThat(fixture.effects.flashRemaining(0))
                    .as("still glowing at tic %d of %d", tic, DemoEffects.FLASH_LIFE_TICS)
                    .isEqualTo(DemoEffects.FLASH_LIFE_TICS - tic);
            }

            fixture.tic();

            assertThat(fixture.effects.flashRemaining(0)).isEqualTo(DemoEffects.DEAD);
        }

        @Test
        @DisplayName("a flash shares the same position as the tracer that left the muzzle")
        void flashMatchesMuzzle()
        {
            // The flash is born at the muzzle, and the muzzle is also where
            // the tracer starts. A flash that appeared anywhere else would
            // be a puff of smoke without a shot, which is the wrong effect.
            // The muzzle is derived from the eye and the aim the way
            // DemoEffects.spawn() derives it, so the test exercises the
            // SAME arithmetic and not a hand-rolled copy of it.
            final Fixture fixture = new Fixture();

            fixture.fire();

            assertThat(DemoEffects.flashSlotCount())
                .isEqualTo(DemoEffects.MAX_PLAYER_FLASHES + DemoEffects.MAX_BOT_FLASHES);

            assertThat(fixture.effects.flashPositionX(0)).isEqualTo(EXPECTED_MUZZLE_X);

            assertThat(fixture.effects.flashPositionY(0)).isEqualTo(EXPECTED_MUZZLE_Y);

            assertThat(fixture.effects.flashPositionZ(0)).isEqualTo(EXPECTED_MUZZLE_Z);
        }
    }

    @Nested
    @DisplayName("the fade staircase")
    final class Stages
    {
        @Test
        @DisplayName("age maps onto the stages in order and never off the end")
        void stagesAdvanceWithAge()
        {
            // MUTABLE local — the highest stage seen so far, which must never
            // go down as the puff ages.
            int previous = 0;

            for (int age = 0; age < DemoEffects.PUFF_LIFE_TICS; age++)
            {
                final int stage = DemoEffects.stageFor(age);

                assertThat(stage).isBetween(0, DemoEffects.PUFF_STAGES - 1);

                assertThat(stage).isGreaterThanOrEqualTo(previous);

                previous = stage;
            }

            assertThat(DemoEffects.stageFor(0)).isZero();

            assertThat(DemoEffects.stageFor(DemoEffects.PUFF_LIFE_TICS - 1))
                .isEqualTo(DemoEffects.PUFF_STAGES - 1);
        }

        @Test
        @DisplayName("an age past the end is clamped rather than reaching off the ladder")
        void stageIsClamped()
        {
            assertThat(DemoEffects.stageFor(DemoEffects.PUFF_LIFE_TICS * 2))
                .isEqualTo(DemoEffects.PUFF_STAGES - 1);
        }

        @Test
        @DisplayName("exactly one stage of a live puff is visible at a time")
        void onlyOneStageIsShown()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            for (int tic = 1; tic < DemoEffects.PUFF_LIFE_TICS; tic++)
            {
                fixture.tic();

                final int shown = DemoEffects.stageFor(fixture.effects.puffAge(0));

                for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
                {
                    if (stage == shown)
                    {
                        assertThat(fixture.puffOverride(0, stage))
                            .as("stage %d is the live one at tic %d", stage, tic)
                            .isNotSameAs(DemoEffects.HIDDEN);
                    }
                    else
                    {
                        assertThat(fixture.puffOverride(0, stage))
                            .as("stage %d must be hidden at tic %d", stage, tic)
                            .isEqualTo(DemoEffects.HIDDEN);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("what the renderer is told")
    final class Published
    {
        /**
         * The smoke-disabled contract: a shot under
         * {@code SMOKE_ENABLED = false} still spawns a flash and a
         * tracer, but no puffs. The flash is the visible proof of
         * the shot; the tracer is the bolt; the puff is the
         * expensive part the flag is gating.
         */
        @Test
        @DisplayName("SMOKE_ENABLED = false: a shot still spawns a flash and a tracer, but no puff")
        void shotWithoutSmoke()
        {
            // Override the class-wide @BeforeEach for this one test:
            // production leaves SMOKE_ENABLED false and that is the
            // path the user's report calls out as needing to be the
            // cheap one.
            DemoEffects.SMOKE_ENABLED = false;

            try
            {
                final Fixture fixture = new Fixture();

                fixture.fire();

                assertThat(fixture.effects.liveOutgoingTracerCount())
                    .as("tracer fires regardless of SMOKE_ENABLED")
                    .isEqualTo(1);

                assertThat(fixture.effects.liveOutgoingPuffCount())
                    .as("puff is gated on SMOKE_ENABLED")
                    .isZero();
            }
            finally
            {
                DemoEffects.SMOKE_ENABLED = true;
            }
        }

        @Test
        @DisplayName("the first publish hides the whole pool, before anything is fired")
        void firstPublishHidesEverything()
        {
            // Without this the instances sit at their scene placements, piled on
            // the world origin, and are visible in the room from the first
            // frame. Scene.Builder refuses a degenerate placement, so the pool
            // cannot enter the scene already hidden.
            final Fixture fixture = new Fixture();

            fixture.effects.publish(fixture.renderer);

            for (int slot = 0; slot < DemoEffects.MAX_TRACERS; slot++)
            {
                assertThat(fixture.tracerOverride(slot)).isEqualTo(DemoEffects.HIDDEN);
            }

            for (int slot = 0; slot < DemoEffects.MAX_PUFFS; slot++)
            {
                for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
                {
                    assertThat(fixture.puffOverride(slot, stage))
                        .isEqualTo(DemoEffects.HIDDEN);
                }
            }
        }

        @Test
        @DisplayName("the hiding transform is degenerate, which is what culls the instance")
        void hiddenIsDegenerate()
        {
            // Every vertex collapses onto one point, so every triangle has zero
            // screen area and the rasterizer rejects it. The bottom row is still
            // (0, 0, 0, 1) because the packed three-row transform has no fourth
            // row to carry anything else.
            for (int row = 0; row < Mat4.ORDER - 1; row++)
            {
                for (int column = 0; column < Mat4.ORDER; column++)
                {
                    assertThat(DemoEffects.HIDDEN.get(row, column)).isZero();
                }
            }

            assertThat(DemoEffects.HIDDEN.get(Mat4.ORDER - 1, Mat4.ORDER - 1)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("a live tracer is placed out along the ray, not left at the muzzle")
        void tracerTravels()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            fixture.tic();

            final float first = fixture.tracerOverride(0).get(2, Mat4.ORDER - 1);

            fixture.tic();

            final float second = fixture.tracerOverride(0).get(2, Mat4.ORDER - 1);

            assertThat(first)
                .as("one step down the aim before it is ever drawn")
                .isGreaterThan(EYE_Z);

            assertThat(second - first)
                .isEqualTo(DemoEffects.TRACER_SPEED_UNITS);
        }

        @Test
        @DisplayName("the muzzle is to the shooter's right, where the weapon is drawn")
        void muzzleSitsOnTheWeaponSide()
        {
            // Facing world +z, the camera's own right is world -x
            // (Camera defines right as normalize(forward x up)). Getting the
            // operands the other way round put the smoke off the left shoulder
            // while the weapon was drawn on the right — which is what this
            // pins down.
            final Fixture fixture = new Fixture();

            fixture.fire();

            fixture.tic();

            final Mat4 puff = fixture.puffOverride(0, 0);

            assertThat(puff.get(0, Mat4.ORDER - 1))
                .as("the puff must be on the same side of the eye as the weapon")
                .isLessThan(EYE_X);
        }

        @Test
        @DisplayName("a puff expands as it ages")
        void puffExpands()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            fixture.tic();

            final float young = fixture.puffOverride(0, DemoEffects.stageFor(
                fixture.effects.puffAge(0))).get(1, 1);

            for (int tic = 0; tic < DemoEffects.PUFF_LIFE_TICS - 2; tic++)
            {
                fixture.tic();
            }

            final float old = fixture.puffOverride(0, DemoEffects.stageFor(
                fixture.effects.puffAge(0))).get(1, 1);

            assertThat(old).isGreaterThan(young);
        }

        @Test
        @DisplayName("an expired effect is hidden again, and stays hidden")
        void expiredEffectsAreHidden()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            for (int tic = 0; tic < DemoEffects.PUFF_LIFE_TICS + 2; tic++)
            {
                fixture.tic();
            }

            assertThat(fixture.tracerOverride(0)).isEqualTo(DemoEffects.HIDDEN);

            for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
            {
                assertThat(fixture.puffOverride(0, stage)).isEqualTo(DemoEffects.HIDDEN);
            }
        }
    }

    @Nested
    @DisplayName("incoming fire")
    final class Incoming
    {
        /** Float tolerance for a direction that has been through a normalise. */
        private static final float EPSILON = 1.0e-3f;

        /** A bot standing well down the room, roughly where BOT_ROUTE_CENTRES puts one. */
        private static final float BOT_X = 0.0f;

        /** See {@link #BOT_X} — a bot's eye is 41 units up. */
        private static final float BOT_EYE_Y = 41.0f;

        /** See {@link #BOT_X}. */
        private static final float BOT_Z = 60.0f;

        /** How far the shot was taken at: bot at z=60, player at z=-192. */
        private static final float RANGE = 252.0f;

        // One incoming shot from a bot at (0, 41, 60) firing back down -z at the
        // player, with the muzzle offset from the eye the way a held carbine is.
        private static void fireBack(final DemoEffects effects)
        {
            effects.spawnIncoming(BOT_X + 9.0f, 30.0f, BOT_Z - 8.0f,
                BOT_X, BOT_EYE_Y, BOT_Z, 0.0f, 0.0f, -1.0f, RANGE);
        }

        @Test
        @DisplayName("uses the bots' half of the pool and never the player's")
        void incomingClaimsItsOwnSlots()
        {
            // The two halves must not be able to evict each other. A busy room
            // overwriting the bolt the player fired half a tic ago — the one they
            // are looking straight at — is the failure this separation prevents.
            final Fixture fixture = new Fixture();

            for (int shot = 0; shot < DemoEffects.MAX_BOT_TRACERS * 2; shot++)
            {
                fireBack(fixture.effects);
            }

            assertThat(fixture.effects.liveOutgoingTracerCount())
                .as("incoming fire took a slot from the player")
                .isZero();

            assertThat(fixture.effects.liveIncomingTracerCount())
                .isEqualTo(DemoEffects.MAX_BOT_TRACERS);

            assertThat(fixture.effects.liveOutgoingPuffCount()).isZero();

            assertThat(fixture.effects.liveIncomingPuffCount())
                .isEqualTo(DemoEffects.MAX_BOT_PUFFS);
        }

        @Test
        @DisplayName("the player's own fire is untouched by a room full of return fire")
        void outgoingSurvivesAVolley()
        {
            final Fixture fixture = new Fixture();

            fixture.fire();

            for (int shot = 0; shot < DemoEffects.MAX_BOT_TRACERS * 3; shot++)
            {
                fireBack(fixture.effects);
            }

            fixture.tic();

            assertThat(fixture.tracerOverride(0))
                .as("the player's bolt was evicted by return fire")
                .isNotSameAs(DemoEffects.HIDDEN);

            assertThat(fixture.effects.liveOutgoingTracerCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the bolt leaves the MUZZLE, not the eye the simulation fired from")
        void theBoltComesOutOfTheGun()
        {
            // The user's report was that return fire "isn't associated with other
            // guns". Match fires from the middle of a body; a bolt born there
            // floats out of a chest.
            final Fixture fixture = new Fixture();

            fireBack(fixture.effects);

            fixture.tic();

            final Mat4 bolt = fixture.tracerOverride(DemoEffects.MAX_TRACERS);

            // One tic of travel has already happened — the port advances before it
            // publishes — so back it out to recover where the bolt was born.
            // Column 2 is the model's +z, the direction of travel, scaled by the
            // bolt's length.
            final float bornY = bolt.get(1, 3)
                - DemoEffects.BOT_TRACER_SPEED_UNITS * bolt.get(1, 2)
                    / DemoEffects.TRACER_LENGTH_UNITS;

            assertThat(bornY)
                .as("the bolt started at the bot's eye rather than at its muzzle")
                .isCloseTo(30.0f, within(1.0f));
        }

        @Test
        @DisplayName("the bolt converges on the ray the hitscan actually used")
        void theBoltAgreesWithTheDamage()
        {
            // THE point of showing incoming fire: a near-miss the player can see
            // has to be the same near-miss the simulation resolved. Firing along
            // the scattered direction FROM the muzzle would draw a line parallel to
            // the shot and fourteen units to one side of it — most of a player
            // radius — so a hit would look like a miss.
            final Fixture fixture = new Fixture();

            fireBack(fixture.effects);

            fixture.tic();

            final Mat4 bolt = fixture.tracerOverride(DemoEffects.MAX_TRACERS);

            // Column 2 is the image of the model's +z, which is the bolt's own
            // direction of travel, scaled by its length.
            final float dirX = bolt.get(0, 2) / DemoEffects.TRACER_LENGTH_UNITS;

            final float dirY = bolt.get(1, 2) / DemoEffects.TRACER_LENGTH_UNITS;

            final float dirZ = bolt.get(2, 2) / DemoEffects.TRACER_LENGTH_UNITS;

            // Where the real ray ends up at the distance the shot was taken at —
            // the point the simulation aimed at, and the point the bolt has to
            // reach if the two are to agree about a near-miss.
            final float aimX = BOT_X;

            final float aimY = BOT_EYE_Y;

            final float aimZ = BOT_Z - RANGE;

            final float muzzleX = BOT_X + 9.0f;

            final float muzzleY = 30.0f;

            final float muzzleZ = BOT_Z - 8.0f;

            final float toX = aimX - muzzleX;

            final float toY = aimY - muzzleY;

            final float toZ = aimZ - muzzleZ;

            final float span = (float) StrictMath.sqrt(toX * toX + toY * toY + toZ * toZ);

            assertThat(dirX).as("across").isCloseTo(toX / span, within(EPSILON));

            assertThat(dirY).as("vertically").isCloseTo(toY / span, within(EPSILON));

            assertThat(dirZ).as("in depth").isCloseTo(toZ / span, within(EPSILON));

            assertThat(dirX * dirX + dirY * dirY + dirZ * dirZ)
                .as("the flight direction is not unit length")
                .isCloseTo(1.0f, within(EPSILON));

            // And it is NOT simply the ray's own direction, which is what a naive
            // implementation would draw and is the bug this converges away.
            assertThat(dirX).as("the bolt just copied the ray's direction")
                .isNotCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("incoming effects are sized against the range they are seen at")
        void incomingIsDistinguishable()
        {
            // Every one of these is sized against a DIFFERENT distance: the
            // player's effects sit 2.4 units from the eye, a bot's are between 60
            // and 512 units away. Reusing the player's numbers would have drawn a
            // cloud under a pixel across, which is the smoke bug again.
            //
            // Note the SPEED goes the other way. The player's bolt recedes and is
            // most visible at birth; an incoming one approaches, so its whole life
            // is the approach and it has to be slow enough and long-lived enough to
            // watch. See BOT_TRACER_SPEED_UNITS for what a capture caught here.
            final int mine = 0;

            final int theirs = DemoEffects.MAX_TRACERS;

            assertThat(DemoEffects.isIncomingTracer(mine)).isFalse();

            assertThat(DemoEffects.isIncomingTracer(theirs)).isTrue();

            assertThat(DemoEffects.tracerWidthOf(theirs))
                .isGreaterThan(DemoEffects.tracerWidthOf(mine));

            assertThat(DemoEffects.incomingLifeFor(400.0f))
                .isGreaterThan(DemoEffects.TRACER_LIFE_TICS);

            assertThat(DemoEffects.tracerSpeedOf(theirs))
                .as("an approaching bolt must not cross the room faster than the eye reads it")
                .isLessThan(DemoEffects.tracerSpeedOf(mine));

            assertThat(DemoEffects.isIncomingPuff(0)).isFalse();

            assertThat(DemoEffects.isIncomingPuff(DemoEffects.MAX_PUFFS)).isTrue();

            assertThat(DemoEffects.puffStartRadiusOf(DemoEffects.MAX_PUFFS))
                .isGreaterThan(DemoEffects.puffStartRadiusOf(0));

            assertThat(DemoEffects.puffEndRadiusOf(DemoEffects.MAX_PUFFS))
                .isGreaterThan(DemoEffects.puffEndRadiusOf(0));

            assertThat(DemoEffects.puffRiseOf(DemoEffects.MAX_PUFFS))
                .isGreaterThan(DemoEffects.puffRiseOf(0));
        }

        @Test
        @DisplayName("an incoming bolt crosses the whole engagement range before it expires")
        void theBoltReachesThePlayer()
        {
            // A bolt from the far edge of a bot's range must arrive or go past. One
            // that stopped short would hang in mid-air, which does not read as a
            // shot that missed — it reads as a shot that gave up.
            final float reach =
                DemoEffects.BOT_TRACER_SPEED_UNITS * DemoEffects.BOT_TRACER_LIFE_TICS;

            assertThat(reach)
                .as("an incoming bolt expires before it can cross the room")
                .isGreaterThan(com.openfps.engine.gameplay.Match.BOT_RANGE_UNITS);
        }

        @Test
        @DisplayName("an incoming bolt is on screen long enough to be read, not one frame")
        void theBoltLastsLongEnoughToSee()
        {
            // THE regression for what a ninety-frame capture caught: at 80 units a
            // tic a bolt from a bot 160 units away existed for two frames, going
            // from 441 pixels to 87,204 in one step. Every geometry test in this
            // file passed on it, because they all ask where the bolt is and none
            // asked how long it is there.
            //
            // Asserted as a time at a representative range rather than as the raw
            // constant, because that is the property a player experiences.
            final float typicalRange = 250.0f;

            final float ticsOnScreen = typicalRange / DemoEffects.BOT_TRACER_SPEED_UNITS;

            assertThat(ticsOnScreen)
                .as("a bolt from mid-room is a strobe rather than a flight")
                .isGreaterThan(6.0f);

            assertThat(DemoEffects.incomingLifeFor(250.0f))
                .as("a bolt from mid-room is drawn for too few frames to read")
                .isGreaterThanOrEqualTo(5);

            // And a shot from across the room lasts several times as long as one
            // from the next crate, which is what makes the flight tell a player how
            // far away the threat is without them counting anything.
            assertThat(DemoEffects.incomingLifeFor(500.0f))
                .isGreaterThan(DemoEffects.incomingLifeFor(150.0f) * 3);
        }

        @Test
        @DisplayName("the bolt stops a body length short instead of filling the screen")
        void theBoltStopsBeforeTheCamera()
        {
            // THE second thing the ninety-frame capture caught. Drawn all the way
            // in, a bolt's last two frames covered 62,000 and 73,600 pixels — 7%
            // and 8% of the window — because a 16-unit box a few units from the eye
            // fills it. That is a screen flash, and the shot's outcome was decided
            // by Hitscan long before the bolt got there.
            final float speed = DemoEffects.BOT_TRACER_SPEED_UNITS;

            for (final float range : new float[] {120.0f, 250.0f, 400.0f, 512.0f})
            {
                final float flown = DemoEffects.incomingLifeFor(range) * speed;

                assertThat(range - flown)
                    .as("a bolt fired from %s units ends up inside the player's head", range)
                    .isGreaterThanOrEqualTo(DemoEffects.INCOMING_STANDOFF_UNITS
                        - speed);
            }
        }

        @Test
        @DisplayName("a point-blank shot still gets a bolt, however short the flight")
        void pointBlankStillHasATell()
        {
            // The one shot a player most needs to notice must not be the only shot
            // with no tell. Inside the standoff the arithmetic gives zero.
            assertThat(DemoEffects.incomingLifeFor(20.0f))
                .isEqualTo(DemoEffects.BOT_TRACER_MIN_LIFE_TICS);

            assertThat(DemoEffects.incomingLifeFor(0.0f))
                .as("no range at all is the one case with nothing to shorten against")
                .isEqualTo(DemoEffects.BOT_TRACER_LIFE_TICS);
        }

        @Test
        @DisplayName("the puff pool is sized for every bot's worst case, not the common one")
        void thePoolFitsTheWholeRoom()
        {
            // MAX_BOT_PUFFS is derived rather than picked, and the relation it
            // is derived from is: a bot fires on every cooldown, and a puff lives
            // for PUFF_LIFE_TICS, so the number of puffs a single bot can have
            // alive at once is the ceiling of (PUFF_LIFE_TICS / cooldown) - one
            // for the older ratio, two for the post-bump ratio. 32 bodies
            // times that is the lower bound. Drop the cooldown
            // under PUFF_LIFE_TICS / 2 and this pool is undersized - which is
            // exactly the change that would do it silently.
            //
            // 2026-08: the bound is now "at least" the arithmetic minimum,
            // not exactly that. The pool includes headroom for the
            // round-robin's "overwrite the oldest" policy: a pool exactly
            // at the bound would let the round-robin eat a smoke puff
            // mid-flight, which is the failure mode the pool was sized to
            // avoid. The pool is 80; the bound for 32 bots * 2 puffs is
            // 64, so the headroom is 16. A future change that drops the
            // pool below 64 fails this test; one that drops the cooldown
            // below PUFF_LIFE_TICS / 2 fails the same test.
            final int maxConcurrent = (DemoEffects.PUFF_LIFE_TICS
                + com.openfps.engine.gameplay.BotSkill.DUMB.cooldownTics() - 1)
                / com.openfps.engine.gameplay.BotSkill.DUMB.cooldownTics();

            assertThat(DemoEffects.MAX_BOT_PUFFS)
                .as("the pool covers every bot's worst case")
                .isGreaterThanOrEqualTo(maxConcurrent
                    * com.openfps.engine.gameplay.Match.DEFAULT_BOT_COUNT);

            assertThat(DemoEffects.MAX_BOT_TRACERS)
                .as("the pool covers the worst case where every bot has two tracers alive")
                .isGreaterThanOrEqualTo(2
                    * com.openfps.engine.gameplay.Match.DEFAULT_BOT_COUNT);
        }
    }
}
