/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.pool.ThreadPoolFactory;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.hal.adapter.nulladapter.NullSystemInfoPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OutlinePass}, driven by a hand-painted entity-id buffer.
 *
 * <p>The id buffer is written directly rather than rasterized into, because the
 * question these tests answer is what the edge rule does with a given
 * arrangement of ids — not whether the rasterizer produces that arrangement,
 * which {@code SoftwareRenderPortTest} covers separately.</p>
 *
 * <p>The one that matters most is
 * {@link Silhouettes#shouldSeparateTwoEntitiesThatOverlapInScreenSpace()}. A
 * naive edge test asks "is my neighbour untagged", which finds no edge where
 * one player stands in front of another and merges them into a single blob.
 * Comparing ids finds it. Two players in a line have to read as two players.</p>
 *
 * <p>The threading tests carry a hard timeout for the same reason
 * {@code RasterizerTest}'s do: the failure mode of a tiled pass is a hang, and
 * a hang that is not a test failure is a hung CI job.</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@DisplayName("OutlinePass")
final class OutlinePassTest
{
    /** Frame width; a whole number of tiles across. */
    private static final int WIDTH = 96;

    /** Frame height. */
    private static final int HEIGHT = 96;

    /**
     * Tile edge. Small on purpose: at 16 px the 96x96 frame is a 6x6 grid, so
     * every fixture below straddles several tile boundaries and the edge test
     * genuinely has to read pixels a different worker owns.
     */
    private static final int TILE = 16;

    /** One entity's id. */
    private static final int ID_A = 7;

    /** A second entity's id. */
    private static final int ID_B = 9;

    /** A third, for the determinism fixture. */
    private static final int ID_C = 1_000_003;

    /** The background the frame is cleared to before the pass runs. */
    private static final int CLEAR = Framebuffer.CLEAR_COLOR_OPAQUE_BLACK;

    /**
     * The demo room's pale side wall, {@code #A6ADD0}, sampled from a rendered
     * 1280x720 frame — Rec.709 luminance 174.
     *
     * <p><b>Every fixture that says anything about the keyline has to paint over
     * this rather than over the cleared frame</b>, because
     * {@link Framebuffer#CLEAR_COLOR_OPAQUE_BLACK} is bit-for-bit
     * {@link OutlinePass#KEYLINE_COLOR}: on a cleared frame "keyed" and "never
     * touched" are the same pixel value, so an assertion about where the keyline
     * lands would pass identically if the keyline did not exist. A real surface
     * from the real room is also the honest background to be asserting
     * legibility against.</p>
     */
    private static final int ROOM_GREY = Framebuffer.packRgba(166, 173, 208, 255);

    /** Left edge of the single-entity fixture. */
    private static final int BOX_MIN_X = 32;

    /** Top edge of the single-entity fixture. */
    private static final int BOX_MIN_Y = 32;

    /** Right edge of the single-entity fixture, inclusive. */
    private static final int BOX_MAX_X = 63;

    /** Bottom edge of the single-entity fixture, inclusive. */
    private static final int BOX_MAX_Y = 63;

    /** A row through the middle of the fixtures. */
    private static final int MID_Y = 48;

    /** Left edge of the left entity in the two-entity fixture. */
    private static final int PAIR_MIN_X = 20;

    /** Where the left entity ends, inclusive — the shared edge. */
    private static final int PAIR_SPLIT_X = 47;

    /** Right edge of the right entity, inclusive. */
    private static final int PAIR_MAX_X = 75;

    /**
     * Where the depth step falls in the crease fixtures, inclusive on the near
     * side. Shared by the crease tests and the keyline's crease test, which have
     * to name the same column to be talking about the same fold.
     */
    private static final int CREASE_COLUMN = 47;

    private I_EventBusPort bus;
    private I_ThreadPoolPort pool;

    @AfterEach
    void tearDown() throws Exception
    {
        if (pool != null && pool.state() != I_ThreadPoolPort.State.SHUTDOWN)
        {
            pool.shutdown();
            pool.awaitTermination(5000);
            pool = null;
        }
        if (bus != null && bus.state() != I_EventBusPort.State.SHUTDOWN)
        {
            bus.shutdown();
            bus = null;
        }
    }

    @Nested
    @DisplayName("subject — one entity, not all of them")
    class Subject
    {
        @Test
        @DisplayName("naming one of two entities marks that one and leaves the other bare")
        void shouldMarkOnlyTheSubject()
        {
            // The change the player asked for: the mark says "you are pointing
            // at this one", so the body beside it must carry nothing at all —
            // not a thinner line, not a partial one.
            final Framebuffer fb = frame();
            box(fb, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
            box(fb, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_B);
            defaultPass().draw(fb, null, ID_A);

            assertThat(painted(fb, PAIR_MIN_X, MID_Y))
                .as("the subject's outer silhouette")
                .isTrue();
            assertThat(painted(fb, PAIR_SPLIT_X, MID_Y))
                .as("the subject's edge against its neighbour")
                .isTrue();
            assertThat(painted(fb, PAIR_SPLIT_X + 1, MID_Y))
                .as("the neighbour's own edge is NOT drawn")
                .isFalse();
            assertThat(painted(fb, PAIR_MAX_X, MID_Y))
                .as("nor is any part of the neighbour")
                .isFalse();
        }

        @Test
        @DisplayName("the whole of the non-subject entity is left untouched, every pixel")
        void shouldLeaveTheNonSubjectCompletelyUnpainted()
        {
            // Sweeping the whole body rather than sampling two pixels of it:
            // "mostly not outlined" is not a state the eye distinguishes from
            // "outlined", and a bug that left one edge on would pass the
            // spot checks above.
            final Framebuffer fb = frame();
            box(fb, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
            box(fb, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_B);
            defaultPass().draw(fb, null, ID_A);

            for (int y = BOX_MIN_Y; y <= BOX_MAX_Y; y++)
            {
                for (int x = PAIR_SPLIT_X + 1; x <= PAIR_MAX_X; x++)
                {
                    assertThat(painted(fb, x, y))
                        .as("pixel (%d,%d) of the entity that is not the subject", x, y)
                        .isFalse();
                }
            }
        }

        @Test
        @DisplayName("a subject of UNTAGGED — the crosshair on a wall — paints nothing at all")
        void shouldPaintNothingWhenNothingIsAimedAt()
        {
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null, Scene.UNTAGGED);

            assertThat(anyPainted(fb))
                .as("UNTAGGED must mean 'nobody', never 'everybody'")
                .isFalse();
        }

        @Test
        @DisplayName("a subject nothing in the frame carries paints nothing")
        void shouldPaintNothingForAnAbsentSubject()
        {
            // An entity that walked behind a wall between the sample and the
            // pass. Silently marking somebody else would be worse than nothing.
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null, ID_C);

            assertThat(anyPainted(fb)).isFalse();
        }

        @Test
        @DisplayName("EVERY_TAGGED_ENTITY is the two-argument behaviour, bit for bit")
        void shouldReproduceTheAllEntitiesBehaviour()
        {
            final Framebuffer both = frame();
            box(both, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
            box(both, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_B);
            defaultPass().draw(both, null);

            final Framebuffer sentinel = frame();
            box(sentinel, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
            box(sentinel, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_B);
            defaultPass().draw(sentinel, null, OutlinePass.EVERY_TAGGED_ENTITY);

            assertThat(sentinel.colorBuffer()).isEqualTo(both.colorBuffer());
        }

        @Test
        @DisplayName("the sentinel cannot collide with a real entity id")
        void shouldKeepTheSentinelOutOfTheIdSpace()
        {
            // Scene.UNTAGGED and a bot id are both reachable values; the
            // sentinel must be neither, or aiming at nothing would outline
            // everything.
            assertThat(OutlinePass.EVERY_TAGGED_ENTITY).isNotEqualTo(Scene.UNTAGGED);
            assertThat(OutlinePass.EVERY_TAGGED_ENTITY).isNegative();
        }

        @Test
        @DisplayName("the subject a pass last drew is reported back")
        void shouldReportItsSubject()
        {
            final OutlinePass pass = defaultPass();
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);

            pass.draw(fb, null, ID_A);
            assertThat(pass.subjectEntityId()).isEqualTo(ID_A);
            pass.draw(fb, null);
            assertThat(pass.subjectEntityId()).isEqualTo(OutlinePass.EVERY_TAGGED_ENTITY);
        }

        // Whether the pass painted anywhere in the visible rectangle.
        private boolean anyPainted(final Framebuffer fb)
        {
            for (int y = 0; y < fb.height(); y++)
            {
                for (int x = 0; x < fb.width(); x++)
                {
                    if (painted(fb, x, y))
                    {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    @Nested
    @DisplayName("silhouettes")
    class Silhouettes
    {
        @Test
        @DisplayName("a lone entity is outlined on its border and hollow inside")
        void shouldOutlineTheBorderAndLeaveTheInteriorAlone()
        {
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null);

            // Just inside each edge: outlined. Well inside: untouched.
            assertThat(painted(fb, BOX_MIN_X, MID_Y)).isTrue();
            assertThat(painted(fb, BOX_MAX_X, MID_Y)).isTrue();
            assertThat(painted(fb, MID_Y, BOX_MIN_Y)).isTrue();
            assertThat(painted(fb, MID_Y, BOX_MAX_Y)).isTrue();
            assertThat(painted(fb, MID_Y, MID_Y))
                .as("the middle of an entity is not an edge")
                .isFalse();
        }

        @Test
        @DisplayName("untagged background is never painted")
        void shouldNotPaintOutsideTheEntity()
        {
            // The outline is drawn INSIDE the silhouette: an id of UNTAGGED
            // fails the very first test, whatever its neighbours say.
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null);

            assertThat(painted(fb, BOX_MIN_X - 1, MID_Y)).isFalse();
            assertThat(painted(fb, BOX_MAX_X + 1, MID_Y)).isFalse();
            assertThat(painted(fb, 0, 0)).isFalse();
        }

        @Test
        @DisplayName("two entities overlapping in screen space read as two, not one blob")
        void shouldSeparateTwoEntitiesThatOverlapInScreenSpace()
        {
            // THE case. Two players standing in a line touch in screen space,
            // and every pixel along their junction has a tagged neighbour — so
            // an "is my neighbour untagged" edge test finds nothing there and
            // draws one merged silhouette around the pair. Comparing ids finds
            // the boundary between 7 and 9 exactly as readily as the boundary
            // between 7 and the wall behind it.
            final Framebuffer fb = frame();
            box(fb, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
            box(fb, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_B);
            defaultPass().draw(fb, null);

            assertThat(painted(fb, PAIR_SPLIT_X, MID_Y))
                .as("the left entity's edge against the right one")
                .isTrue();
            assertThat(painted(fb, PAIR_SPLIT_X + 1, MID_Y))
                .as("and the right entity's edge against the left one")
                .isTrue();
            assertThat(painted(fb, PAIR_MIN_X, MID_Y))
                .as("the outer silhouette survives too")
                .isTrue();
            assertThat(painted(fb, PAIR_MAX_X, MID_Y)).isTrue();
        }

        @Test
        @DisplayName("one entity built from two instances has no seam through its own joint")
        void shouldNotOutlineBetweenTwoInstancesSharingAnId()
        {
            // The converse of the test above, and why Scene lets two instances
            // share an id: a character assembled from several models must not
            // be drawn with a bright line through its own joints.
            final Framebuffer fb = frame();
            box(fb, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
            box(fb, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null);

            assertThat(painted(fb, PAIR_SPLIT_X, MID_Y)).isFalse();
            assertThat(painted(fb, PAIR_SPLIT_X + 1, MID_Y)).isFalse();
            assertThat(painted(fb, PAIR_MIN_X, MID_Y))
                .as("the joined outer silhouette is still outlined")
                .isTrue();
        }

        @Test
        @DisplayName("an entity flush against the screen edge is not outlined along the window")
        void shouldNotOutlineTheWindowBorder()
        {
            // Off-screen counts as "not different". A player walking half out
            // of frame would otherwise draw a bright line down the side of the
            // window, which reads as a rendering fault rather than as a player.
            final Framebuffer fb = frame();
            box(fb, 0, 0, BOX_MIN_X, HEIGHT - 1, ID_A);
            defaultPass().draw(fb, null);

            assertThat(painted(fb, 0, MID_Y)).as("the left window edge").isFalse();
            assertThat(painted(fb, MID_Y / 2, 0)).as("the top window edge").isFalse();
            assertThat(painted(fb, BOX_MIN_X, MID_Y))
                .as("the real silhouette, against the background, is still drawn")
                .isTrue();
        }

        @Test
        @DisplayName("a frame with nothing tagged is left exactly as it was")
        void shouldPaintNothingWhenNothingIsTagged()
        {
            final Framebuffer fb = frame();
            defaultPass().draw(fb, null);

            assertThat(fb.colorBuffer()).containsOnly(CLEAR);
        }

        @Test
        @DisplayName("the mark is solid: two flat tones, no blending, no falloff")
        void shouldPaintOneSolidColour()
        {
            // Solid means solid. Every painted pixel is one of exactly two
            // values, so there is no gradient to be swallowed by a light grey
            // wall — and no THIRD value, which is what a blend would leave.
            //
            // Over a mid-grey background rather than the cleared black every
            // other fixture uses, because the keyline IS that black
            // (Framebuffer.CLEAR_COLOR_OPAQUE_BLACK), so a cleared frame cannot
            // tell "keyed" from "untouched" and this assertion would be
            // vacuously true.
            final Framebuffer fb = frame();
            fb.clearColor(ROOM_GREY);
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null);

            assertThat(fb.colorBuffer()).containsOnly(ROOM_GREY, OutlinePass.OUTLINE_COLOR,
                OutlinePass.KEYLINE_COLOR);
            assertThat(Framebuffer.alpha(OutlinePass.OUTLINE_COLOR)).isEqualTo(0xFF);
            assertThat(Framebuffer.alpha(OutlinePass.KEYLINE_COLOR)).isEqualTo(0xFF);
        }

        @Test
        @DisplayName("the outline colour is red — a saturated corner of the colour cube")
        void shouldUseAColourNoTextureProduces()
        {
            // Still a corner of the RGB cube — channels only ever 0 or 255 —
            // which is what rules out the desaturated warm flats the Kenney kits
            // are made of. The corner CHANGED, from cyan to red, and the reason
            // is not contrast: see OUTLINE_COLOR. The old assertion is kept in
            // form and inverted in value rather than deleted, because "which
            // corner" is exactly the thing that moved.
            final int red = Framebuffer.red(OutlinePass.OUTLINE_COLOR);
            final int green = Framebuffer.green(OutlinePass.OUTLINE_COLOR);
            final int blue = Framebuffer.blue(OutlinePass.OUTLINE_COLOR);

            assertThat(red).isEqualTo(0xFF);
            assertThat(green).isZero();
            assertThat(blue).isZero();
        }
    }

    @Nested
    @DisplayName("interior creases — what makes the mark a wireframe and not a halo")
    class Creases
    {
        /** 1/w of the near half of the crease fixture. */
        private static final float NEAR_INV_W = 0.50f;

        /** 1/w of the far half — a 40% step, well over CREASE_DEPTH_RATIO. */
        private static final float FAR_INV_W = 0.30f;

        /**
         * 1/w one flat surface's shading gradient might drift by across a
         * pixel — 0.2% of {@link #NEAR_INV_W}, far under the threshold.
         */
        private static final float GRAZING_INV_W = 0.499f;

        /** Where the depth step falls inside the fixture, inclusive on the near side. */
        private static final int CREASE_X = CREASE_COLUMN;

        @Test
        @DisplayName("one entity folded over itself is drawn on the fold")
        void shouldPaintAnInteriorDepthStep()
        {
            // The whole point of the redesign. Before this, the only thing the
            // pass could see was the outer border, so the mark was a ring of
            // colour round a body — which reads as a damage or status effect,
            // because that is what a filled ring on an enemy means everywhere
            // else. A line where the body's own surfaces meet reads as
            // geometry instead.
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            depthBox(fb, BOX_MIN_X, BOX_MIN_Y, CREASE_X, BOX_MAX_Y, NEAR_INV_W);
            depthBox(fb, CREASE_X + 1, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, FAR_INV_W);

            defaultPass().draw(fb, null);

            assertThat(painted(fb, CREASE_X, MID_Y))
                .as("the near side of the fold")
                .isTrue();
            assertThat(painted(fb, CREASE_X + 1, MID_Y))
                .as("and the far side of it")
                .isTrue();
            assertThat(painted(fb, CREASE_X - 4, MID_Y))
                .as("but not the flat surface leading up to it")
                .isFalse();
            assertThat(painted(fb, CREASE_X + 5, MID_Y))
                .as("nor the flat surface leading away from it")
                .isFalse();
        }

        @Test
        @DisplayName("a flat surface at a grazing angle is not a crease")
        void shouldNotPaintASmoothDepthGradient()
        {
            // The failure mode that would turn the wireframe back into a fill:
            // a threshold low enough to catch every ordinary per-pixel step
            // scribbles over the whole body. This is the guard on
            // CREASE_DEPTH_RATIO being comfortably above that.
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            depthBox(fb, BOX_MIN_X, BOX_MIN_Y, CREASE_X, BOX_MAX_Y, NEAR_INV_W);
            depthBox(fb, CREASE_X + 1, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, GRAZING_INV_W);

            defaultPass().draw(fb, null);

            assertThat(painted(fb, CREASE_X, MID_Y)).isFalse();
            assertThat(painted(fb, CREASE_X + 1, MID_Y)).isFalse();
        }

        @Test
        @DisplayName("the crease line is one pixel each side, however thick the silhouette is")
        void shouldNotWidenTheCreaseWithThickness()
        {
            // A crease is interior. Widening it does not trace the body more
            // boldly, it fills the body in — so the crease test takes exactly
            // one step whatever `thickness` says. Asserted at a thickness that
            // would obviously show if it did not.
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            depthBox(fb, BOX_MIN_X, BOX_MIN_Y, CREASE_X, BOX_MAX_Y, NEAR_INV_W);
            depthBox(fb, CREASE_X + 1, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, FAR_INV_W);

            new OutlinePass(4, OutlinePass.OUTLINE_COLOR).draw(fb, null);

            assertThat(painted(fb, CREASE_X, MID_Y)).isTrue();
            assertThat(painted(fb, CREASE_X - 1, MID_Y))
                .as("one step in from the fold, not four")
                .isFalse();
            assertThat(painted(fb, CREASE_X + 2, MID_Y))
                .as("and one step out from it")
                .isFalse();
        }

        @Test
        @DisplayName("a neighbour of a DIFFERENT entity is a silhouette, never a crease")
        void shouldNotTreatACrossEntityStepAsACrease()
        {
            // Two bodies at very different depths share a screen-space border.
            // The silhouette test already owns that border; letting the crease
            // test fire there too would double-count, and — worse — would let a
            // background pixel's cleared DEPTH_CLEAR into the comparison, where
            // it would find a "crease" against every entity on screen.
            final Framebuffer fb = frame();
            box(fb, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
            box(fb, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_B);
            depthBox(fb, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, NEAR_INV_W);
            depthBox(fb, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, FAR_INV_W);

            defaultPass().draw(fb, null);

            // The shared border is painted — but by the silhouette test, which
            // reaches `thickness` pixels. One pixel further in than that, the
            // depth is uniform and nothing may be painted.
            assertThat(painted(fb, PAIR_SPLIT_X, MID_Y)).isTrue();
            assertThat(painted(fb, PAIR_SPLIT_X - OutlinePass.OUTLINE_THICKNESS_PIXELS, MID_Y))
                .as("inside entity A, past the silhouette band, on uniform depth")
                .isFalse();
        }

        @Test
        @DisplayName("a frame whose depth was never written has no creases anywhere")
        void shouldFindNoCreaseInAClearedDepthBuffer()
        {
            // DEPTH_CLEAR is 0, so every difference and every denominator is
            // zero. The comparison must come out false rather than NaN-true —
            // which is what would happen if the ratio were applied the other
            // way round, as a division.
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);

            defaultPass().draw(fb, null);

            assertThat(painted(fb, MID_Y, MID_Y))
                .as("the middle of a depth-less entity is still hollow")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("the keyline — what makes a dark red line legible on a red body")
    class Keyline
    {
        /**
         * The demo's amber crate decal, {@code #FFB54A}, sampled from a rendered
         * frame — luminance 189, the brightest surface in the room and the one
         * whose hue (33 degrees) is closest to the line's.
         */
        private static final int CRATE_AMBER = Framebuffer.packRgba(255, 181, 74, 255);

        /**
         * One bot's jacket, {@code #CF534F}, sampled from a rendered frame —
         * luminance 109, hue 2 degrees. The worst body in the demo to draw a red
         * line on, and the reason the keyline is not optional.
         */
        private static final int JACKET_RED = Framebuffer.packRgba(207, 83, 79, 255);

        @Test
        @DisplayName("one pixel of black immediately inside the line, and no more")
        void shouldBackTheLineOnePixelInside()
        {
            final Framebuffer fb = wallFrame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null, ID_A);

            // Walking in from each edge: line, keyline, then the body as the
            // world pass left it. The third pixel is the one that says this is a
            // border and not a band.
            assertThat(fb.pixel(BOX_MIN_X, MID_Y)).as("the line").
                isEqualTo(OutlinePass.OUTLINE_COLOR);
            assertThat(fb.pixel(BOX_MIN_X + 1, MID_Y)).as("keyed, one pixel in").
                isEqualTo(OutlinePass.KEYLINE_COLOR);
            assertThat(fb.pixel(BOX_MIN_X + 2, MID_Y)).as("two pixels in: untouched").
                isEqualTo(ROOM_GREY);

            assertThat(fb.pixel(BOX_MAX_X - 1, MID_Y)).isEqualTo(OutlinePass.KEYLINE_COLOR);
            assertThat(fb.pixel(BOX_MAX_X - 2, MID_Y)).isEqualTo(ROOM_GREY);
            assertThat(fb.pixel(MID_Y, BOX_MIN_Y + 1)).isEqualTo(OutlinePass.KEYLINE_COLOR);
            assertThat(fb.pixel(MID_Y, BOX_MAX_Y - 1)).isEqualTo(OutlinePass.KEYLINE_COLOR);
        }

        @Test
        @DisplayName("the coloured part of the mark is still exactly one pixel deep")
        void shouldKeepTheColouredLineOnePixelDeep()
        {
            // The regression guard on the whole reason the line is a hairline: a
            // second pixel of SATURATED colour is what read as fill, and fill on
            // an enemy reads as damage. Counting rather than spot-checking,
            // because "one more than intended" is exactly the failure that a
            // spot check one pixel further in would miss.
            final Framebuffer fb = wallFrame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null, ID_A);

            // MUTABLE local — how many consecutive line pixels there are.
            int deep = 0;
            while (fb.pixel(BOX_MIN_X + deep, MID_Y) == OutlinePass.OUTLINE_COLOR)
            {
                deep++;
            }
            assertThat(deep).isEqualTo(OutlinePass.OUTLINE_THICKNESS_PIXELS).isOne();
        }

        @Test
        @DisplayName("nothing outside the subject is keyed — the body does not grow a pixel")
        void shouldNotPaintTheKeylineOutsideTheSubject()
        {
            // The mark must not change the shape it describes. A keyline painted
            // outward would make an aimed opponent one pixel wider than an
            // unaimed one, and would have a tile worker writing pixels it does
            // not own.
            final Framebuffer fb = wallFrame();
            box(fb, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
            box(fb, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_B);
            defaultPass().draw(fb, null, ID_A);

            assertThat(fb.pixel(PAIR_MIN_X - 1, MID_Y)).as("background, just outside").
                isEqualTo(ROOM_GREY);
            assertThat(fb.pixel(0, 0)).as("a far corner").isEqualTo(ROOM_GREY);
            for (int y = BOX_MIN_Y; y <= BOX_MAX_Y; y++)
            {
                for (int x = PAIR_SPLIT_X + 1; x <= PAIR_MAX_X; x++)
                {
                    assertThat(fb.pixel(x, y))
                        .as("pixel (%d,%d) of the entity that is not the subject", x, y)
                        .isEqualTo(ROOM_GREY);
                }
            }
        }

        @Test
        @DisplayName("an interior crease is keyed as well as the silhouette")
        void shouldBackTheCreaseToo()
        {
            // The crease is the half of the mark that lies ENTIRELY on the body,
            // so it is the half most exposed to a body the colour of the line.
            // One rule covers both kinds of edge, which is why there is no
            // special case for it in the pass.
            final Framebuffer fb = wallFrame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            depthBox(fb, BOX_MIN_X, BOX_MIN_Y, CREASE_COLUMN, BOX_MAX_Y, 0.50f);
            depthBox(fb, CREASE_COLUMN + 1, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, 0.30f);
            defaultPass().draw(fb, null, ID_A);

            assertThat(fb.pixel(CREASE_COLUMN, MID_Y)).as("the near side of the fold").
                isEqualTo(OutlinePass.OUTLINE_COLOR);
            assertThat(fb.pixel(CREASE_COLUMN - 1, MID_Y)).as("keyed on the near side").
                isEqualTo(OutlinePass.KEYLINE_COLOR);
            assertThat(fb.pixel(CREASE_COLUMN + 2, MID_Y)).as("keyed on the far side").
                isEqualTo(OutlinePass.KEYLINE_COLOR);
            assertThat(fb.pixel(CREASE_COLUMN - 2, MID_Y)).as("and no further").
                isEqualTo(ROOM_GREY);
        }

        @Test
        @DisplayName("the mark survives the red-jacket bot, which the line alone does not")
        void shouldStayVisibleOnARedBody()
        {
            // THE case the keyline exists for, stated with the numbers rather
            // than with an adjective. 29% of the pixels immediately inside this
            // pass's own line are red-dominant in a real aimed frame, and the
            // worst body in the demo is a jacket at #CF534F: two degrees of hue
            // from the line, 55 luminance levels from it. At one pixel that is
            // not a line, it is a shading step in the jacket.
            final Framebuffer fb = frame();
            fb.clearColor(JACKET_RED);
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null, ID_A);

            assertThat(fb.pixel(BOX_MIN_X + 1, MID_Y))
                .as("the keyline is what the player actually reads the mark from")
                .isEqualTo(OutlinePass.KEYLINE_COLOR);
            assertThat(luminance(JACKET_RED) - luminance(OutlinePass.OUTLINE_COLOR))
                .as("the line against this jacket: barely a shading step")
                .isLessThan(60);
            assertThat(luminance(JACKET_RED) - luminance(OutlinePass.KEYLINE_COLOR))
                .as("the keyline against the same jacket: twice as far")
                .isGreaterThan(100);
        }

        @Test
        @DisplayName("on a body of the line's exact colour the line vanishes and the mark does not")
        void shouldStayVisibleOnABodyOfItsOwnColour()
        {
            // The limit of the case above, and the assertion is deliberately an
            // admission: the line is not merely hard to see here, it is bit-for-
            // bit the surface it is drawn on. Everything the player perceives at
            // that point is the keyline.
            final Framebuffer fb = frame();
            fb.clearColor(OutlinePass.OUTLINE_COLOR);
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null, ID_A);

            assertThat(fb.pixel(BOX_MIN_X, MID_Y))
                .as("the line is genuinely indistinguishable from this body")
                .isEqualTo(fb.pixel(BOX_MIN_X - 1, MID_Y));
            assertThat(fb.pixel(BOX_MIN_X + 1, MID_Y))
                .as("and the keyline is the whole of the visible mark")
                .isEqualTo(OutlinePass.KEYLINE_COLOR);
        }

        @Test
        @DisplayName("the mark carries its own contrast, so no background can defeat it")
        void shouldContrastWithItself()
        {
            // The measurement that justifies the colour, and the one thing red
            // alone could not claim. Red is luminance 54 and the room runs 28 to
            // 201, so red is NOT far from every surface — the amber crate is
            // red-dominant and the doorway is nearly as dark. What cannot be
            // defeated is the step between the two tones of the mark, because a
            // background does not sit between them: both are painted, adjacent,
            // every frame.
            final int line = OutlinePass.OUTLINE_COLOR;
            final int key = OutlinePass.KEYLINE_COLOR;

            assertThat(luminance(line) - luminance(key))
                .as("luminance step inside the mark")
                .isGreaterThanOrEqualTo(50);
            assertThat(Framebuffer.red(key)).isEqualTo(Framebuffer.green(key));
            assertThat(Framebuffer.green(key))
                .as("the keyline is achromatic, so the step is in chroma too")
                .isEqualTo(Framebuffer.blue(key));
            assertThat(Framebuffer.red(line) - Framebuffer.blue(line))
                .as("and the line is fully saturated")
                .isEqualTo(0xFF);
            // The amber crate decal is the room surface whose hue is nearest the
            // line's, and it is 135 luminance levels above the line and 189 above
            // the keyline — so the pair is legible over it even though red alone
            // shares its hue family.
            assertThat(luminance(CRATE_AMBER) - luminance(line)).isGreaterThan(130);
        }

        @Test
        @DisplayName("a body too thin to have an inside gets the line and no keyline")
        void shouldDegradeOnAOnePixelBody()
        {
            // A limb two pixels across at distance. Every pixel is on the
            // silhouette, so there is nowhere inward to key — and inventing
            // somewhere would mean painting over the room.
            final Framebuffer fb = wallFrame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MIN_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null, ID_A);

            assertThat(fb.pixel(BOX_MIN_X, MID_Y)).isEqualTo(OutlinePass.OUTLINE_COLOR);
            assertThat(fb.colorBuffer())
                .as("no keyline anywhere in the frame")
                .doesNotContain(OutlinePass.KEYLINE_COLOR);
        }

        @Test
        @DisplayName("the line and the keyline are the reticle's own two colours")
        void shouldShareTheReticlesColours()
        {
            // Not "the same value" but the same constants, which is the point:
            // the reticle turns red over the body this pass outlines, off the
            // same aimedEntityId sample on the same frame. One fact, one colour,
            // in two places. Two independently chosen reds would drift the first
            // time either was tuned, and the player would be left learning that
            // hue means nothing in particular.
            assertThat(OutlinePass.OUTLINE_COLOR).isEqualTo(Crosshair.coreColor(true));
            assertThat(OutlinePass.KEYLINE_COLOR).isEqualTo(Crosshair.OUTLINE_COLOR);
            assertThat(OutlinePass.OUTLINE_COLOR).isNotEqualTo(Crosshair.coreColor(false));
        }

        @Test
        @DisplayName("a caller-supplied keyline colour is honoured and reported")
        void shouldHonourAnExplicitKeylineColour()
        {
            final OutlinePass pass = new OutlinePass(1, CRATE_AMBER, ROOM_GREY);
            assertThat(pass.outlineColor()).isEqualTo(CRATE_AMBER);
            assertThat(pass.keylineColor()).isEqualTo(ROOM_GREY);
            assertThat(pass.toString()).contains("keyline");
        }
    }

    @Nested
    @DisplayName("thickness")
    class Thickness
    {
        @Test
        @DisplayName("the default is the named constant, not a literal")
        void shouldDefaultToTheNamedThickness()
        {
            assertThat(new OutlinePass().thickness())
                .isEqualTo(OutlinePass.OUTLINE_THICKNESS_PIXELS);
            assertThat(new OutlinePass().outlineColor()).isEqualTo(OutlinePass.OUTLINE_COLOR);
            assertThat(new OutlinePass().keylineColor()).isEqualTo(OutlinePass.KEYLINE_COLOR);
            assertThat(new OutlinePass(2, OutlinePass.OUTLINE_COLOR).keylineColor())
                .as("the two-argument constructor keys with the default")
                .isEqualTo(OutlinePass.KEYLINE_COLOR);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 5})
        @DisplayName("the band is exactly `thickness` pixels deep, and equally deep on both sides")
        void shouldHonourThicknessSymmetrically(final int thickness)
        {
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            new OutlinePass(thickness, OutlinePass.OUTLINE_COLOR).draw(fb, null);

            // Walking in from the left edge along a row through the middle,
            // the first `thickness` pixels are painted and the next is not.
            for (int step = 0; step < thickness; step++)
            {
                assertThat(painted(fb, BOX_MIN_X + step, MID_Y))
                    .as("left band, %d px in", step).isTrue();
                assertThat(painted(fb, BOX_MAX_X - step, MID_Y))
                    .as("right band, %d px in", step).isTrue();
                assertThat(painted(fb, MID_Y, BOX_MIN_Y + step))
                    .as("top band, %d px in", step).isTrue();
                assertThat(painted(fb, MID_Y, BOX_MAX_Y - step))
                    .as("bottom band, %d px in", step).isTrue();
            }
            assertThat(painted(fb, BOX_MIN_X + thickness, MID_Y))
                .as("one past the left band").isFalse();
            assertThat(painted(fb, BOX_MAX_X - thickness, MID_Y))
                .as("one past the right band").isFalse();
            assertThat(painted(fb, MID_Y, BOX_MIN_Y + thickness))
                .as("one past the top band").isFalse();
            assertThat(painted(fb, MID_Y, BOX_MAX_Y - thickness))
                .as("one past the bottom band").isFalse();
        }

        @Test
        @DisplayName("a non-positive thickness is refused")
        void shouldRefuseANonPositiveThickness()
        {
            assertThatThrownBy(() -> new OutlinePass(0, OutlinePass.OUTLINE_COLOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thickness");
        }
    }

    @Nested
    @DisplayName("threading")
    class Threading
    {
        // The fixed ladder, plus the count this machine's pool would actually
        // build — the one worker count nobody chose by hand, and the one that
        // changes from runner to runner. Deduplicated, because a two-processor
        // box would otherwise run 1 twice.
        static IntStream workerCounts()
        {
            return IntStream.of(1, 2, 3, 4, 8, ThreadPoolFactory.resolveWorkerCount(
                new NullSystemInfoPort().logicalProcessorCount())).distinct();
        }

        @ParameterizedTest
        @MethodSource("workerCounts")
        @DisplayName("the frame is bit-identical to the serial one at every worker count")
        void shouldMatchTheSerialFrameAtEveryWorkerCount(final int workers)
        {
            // The pass reads a neighbourhood that crosses tile boundaries, so
            // a worker reads pixels another worker owns. That is safe only
            // because the two buffers are disjoint — ids in, colour out — and
            // the id buffer is frozen before the pass starts. Fuse this into
            // the raster pass and this test is what fails.
            final Framebuffer serial = crowdedFrame();
            defaultPass().draw(serial, null);

            final Framebuffer parallel = crowdedFrame();
            defaultPass().draw(parallel, startPool(workers));

            assertThat(parallel.colorBuffer()).isEqualTo(serial.colorBuffer());
            assertThat(parallel.entityIdBuffer())
                .as("the pass must not write one id")
                .isEqualTo(serial.entityIdBuffer());
        }

        @Test
        @DisplayName("running the same frame twice paints the same pixels")
        void shouldBeIdempotent()
        {
            // A pure function of the id buffer, so a second run over an
            // already-painted frame must change nothing.
            final Framebuffer fb = crowdedFrame();
            defaultPass().draw(fb, null);
            final int[] once = fb.colorBuffer().clone();
            defaultPass().draw(fb, null);

            assertThat(fb.colorBuffer()).isEqualTo(once);
        }
    }

    @Nested
    @DisplayName("preconditions")
    class Preconditions
    {
        @Test
        @DisplayName("a null or unready framebuffer is refused")
        void shouldRefuseAnUnusableFramebuffer()
        {
            assertThatThrownBy(() -> defaultPass().draw(null, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> defaultPass().draw(new Framebuffer(TILE), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
        }
    }

    // ---- fixtures ----

    private static OutlinePass defaultPass()
    {
        return new OutlinePass();
    }

    // A cleared frame whose colour buffer holds a real surface from the demo
    // room, so a keyed pixel can be told from an untouched one. See ROOM_GREY for
    // why the cleared black every other fixture uses cannot.
    private static Framebuffer wallFrame()
    {
        final Framebuffer fb = frame();
        fb.clearColor(ROOM_GREY);
        return fb;
    }

    // Rec.709 relative luminance of a packed colour, weighted over the sRGB
    // values as stored rather than over linearised ones.
    //
    // That is the convention every measurement in Crosshair's Javadoc uses — it
    // is what makes #FFC457 "201" there — so keeping it here is what lets the
    // numbers in the two classes be compared at all. It is not a colour-science
    // claim; it is a shared ruler.
    private static int luminance(final int rgba)
    {
        return Math.round(0.2126f * Framebuffer.red(rgba)
            + 0.7152f * Framebuffer.green(rgba)
            + 0.0722f * Framebuffer.blue(rgba));
    }

    // Fills an inclusive rectangle of the depth buffer with one 1/w value.
    private static void depthBox(final Framebuffer fb, final int minX, final int minY,
        final int maxX, final int maxY, final float invW)
    {
        for (int y = minY; y <= maxY; y++)
        {
            for (int x = minX; x <= maxX; x++)
            {
                fb.setDepth(x, y, invW);
            }
        }
    }

    // A cleared frame with a deliberately small tile size.
    private static Framebuffer frame()
    {
        final Framebuffer fb = new Framebuffer(TILE);
        fb.init(WIDTH, HEIGHT);
        fb.clear();
        return fb;
    }

    // Three entities, two of them touching, all of them straddling several
    // tile boundaries — the arrangement that makes the worker-count comparison
    // mean something.
    private static Framebuffer crowdedFrame()
    {
        // Over a room surface rather than the cleared black, so the worker-count
        // comparison covers the KEYLINE writes too. On a cleared frame a keyline
        // pixel and an untouched pixel hold the same value, so a keyline that
        // landed in a different place at eight workers than at one would have
        // compared equal.
        final Framebuffer fb = wallFrame();
        box(fb, PAIR_MIN_X, BOX_MIN_Y, PAIR_SPLIT_X, BOX_MAX_Y, ID_A);
        box(fb, PAIR_SPLIT_X + 1, BOX_MIN_Y, PAIR_MAX_X, BOX_MAX_Y, ID_B);
        box(fb, 1, 1, TILE + 3, TILE * 3 + 5, ID_C);
        return fb;
    }

    // Fills an inclusive rectangle of the id buffer.
    private static void box(final Framebuffer fb, final int minX, final int minY,
        final int maxX, final int maxY, final int id)
    {
        for (int y = minY; y <= maxY; y++)
        {
            for (int x = minX; x <= maxX; x++)
            {
                fb.setEntityId(x, y, id);
            }
        }
    }

    private static boolean painted(final Framebuffer fb, final int x, final int y)
    {
        return fb.pixel(x, y) == OutlinePass.OUTLINE_COLOR;
    }

    // Brings a real WorkerPool up with the given worker count.
    private I_ThreadPoolPort startPool(final int workers)
    {
        bus = EventBusFactory.createShared();
        bus.init(512);
        pool = ThreadPoolFactory.createFixed(bus, new SubsystemRegistry());
        pool.init(workers);
        pool.start();
        return pool;
    }
}
