/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

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

    /** Surface size for the port; nothing looks at the pixels here. */
    private static final int SURFACE = 32;

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
            return renderer.worldTransformOverride(effects.puffInstanceIndex(slot, stage));
        }
    }

    @Nested
    @DisplayName("placement in the scene")
    final class Placement
    {
        @Test
        @DisplayName("occupies one instance per tracer and one per puff stage")
        void instanceCountIsTheWholePool()
        {
            final Scene.Builder builder = Scene.builder();
            final DemoEffects effects = DemoEffects.addTo(builder);

            assertThat(effects.instanceCount()).isEqualTo(DemoEffects.MAX_TRACERS
                + DemoEffects.MAX_PUFFS * DemoEffects.PUFF_STAGES);
            assertThat(builder.worldInstanceCount()).isEqualTo(effects.instanceCount());
        }

        @Test
        @DisplayName("the smoke is translucent and the tracers are not")
        void onlyTheSmokeIsTranslucent()
        {
            final Scene.Builder builder = Scene.builder();
            final DemoEffects effects = DemoEffects.addTo(builder);
            final Scene scene = builder.build();

            assertThat(scene.translucentInstanceCount())
                .isEqualTo(DemoEffects.MAX_PUFFS * DemoEffects.PUFF_STAGES);
            for (int slot = 0; slot < DemoEffects.MAX_TRACERS; slot++)
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
                            .isSameAs(DemoEffects.HIDDEN);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("what the renderer is told")
    final class Published
    {
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
                assertThat(fixture.tracerOverride(slot)).isSameAs(DemoEffects.HIDDEN);
            }
            for (int slot = 0; slot < DemoEffects.MAX_PUFFS; slot++)
            {
                for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
                {
                    assertThat(fixture.puffOverride(slot, stage))
                        .isSameAs(DemoEffects.HIDDEN);
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

            assertThat(fixture.tracerOverride(0)).isSameAs(DemoEffects.HIDDEN);
            for (int stage = 0; stage < DemoEffects.PUFF_STAGES; stage++)
            {
                assertThat(fixture.puffOverride(0, stage)).isSameAs(DemoEffects.HIDDEN);
            }
        }
    }
}
