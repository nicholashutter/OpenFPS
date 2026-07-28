/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.pool.ThreadPoolFactory;
import com.openfps.engine.core.subsystem.SubsystemRegistry;

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
        @DisplayName("the outline is solid: no blending, no falloff")
        void shouldPaintOneSolidColour()
        {
            // Solid means solid. Every painted pixel is the identical value,
            // so there is no gradient to be swallowed by a light grey wall.
            final Framebuffer fb = frame();
            box(fb, BOX_MIN_X, BOX_MIN_Y, BOX_MAX_X, BOX_MAX_Y, ID_A);
            defaultPass().draw(fb, null);

            assertThat(fb.colorBuffer()).containsOnly(CLEAR, OutlinePass.OUTLINE_COLOR);
            assertThat(Framebuffer.alpha(OutlinePass.OUTLINE_COLOR)).isEqualTo(0xFF);
        }

        @Test
        @DisplayName("the outline colour is a saturated corner of the colour cube")
        void shouldUseAColourNoTextureProduces()
        {
            // The requirement is a colour the art cannot accidentally contain.
            // A corner of the RGB cube — channels only ever 0 or 255 — is the
            // strongest form of that claim available without scanning every
            // atlas, and it rules out the desaturated flats the Kenney kits
            // are made of.
            final int red = Framebuffer.red(OutlinePass.OUTLINE_COLOR);
            final int green = Framebuffer.green(OutlinePass.OUTLINE_COLOR);
            final int blue = Framebuffer.blue(OutlinePass.OUTLINE_COLOR);

            assertThat(red).isZero();
            assertThat(green).isEqualTo(0xFF);
            assertThat(blue).isEqualTo(0xFF);
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
        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 4, 8})
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
        final Framebuffer fb = frame();
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
