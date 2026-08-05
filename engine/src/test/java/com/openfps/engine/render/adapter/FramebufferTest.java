/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Framebuffer}.
 *
 * The two things worth reading here are {@link StridePadding}, which pins the
 * false-sharing invariant from {@code render/README.md} section 7, and
 * {@link ColourRoundTrip}, which is the empirical byte-order check
 * {@code docs/ASSETS.md} section 4 asks for.
 */
@DisplayName("Framebuffer")
final class FramebufferTest
{
    /** A colour with four distinct channels, so any swizzle is visible. */
    private static final int DISTINCT = Framebuffer.packRgba(0x12, 0x34, 0x56, 0x78);

    private static Framebuffer ready(final int width, final int height)
    {
        final Framebuffer fb = new Framebuffer();
        fb.init(width, height);
        return fb;
    }

    private static Framebuffer ready(final int width, final int height, final int tileSize)
    {
        final Framebuffer fb = new Framebuffer(tileSize);
        fb.init(width, height);
        return fb;
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle
    {
        @Test
        void shouldStartUninitializedAndAllocateNothing()
        {
            final Framebuffer fb = new Framebuffer();
            assertThat(fb.state()).isEqualTo(Framebuffer.State.UNINITIALIZED);
            assertThat(fb.colorBuffer()).isNull();
            assertThat(fb.depthBuffer()).isNull();
        }

        @Test
        void shouldBecomeReadyWhenInitialized()
        {
            final Framebuffer fb = ready(320, 200);
            assertThat(fb.state()).isEqualTo(Framebuffer.State.READY);
            assertThat(fb.colorBuffer()).hasSize(fb.pixelCount());
            assertThat(fb.depthBuffer()).hasSize(fb.pixelCount());
        }

        @Test
        void shouldRejectSecondInit()
        {
            final Framebuffer fb = ready(64, 64);
            assertThatThrownBy(() -> fb.init(64, 64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNINITIALIZED");
        }

        @Test
        void shouldRejectUseBeforeInit()
        {
            final Framebuffer fb = new Framebuffer();
            assertThatThrownBy(fb::clear)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNINITIALIZED");
            assertThatThrownBy(() -> fb.resize(32, 32))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldRejectNonPositiveDimensions()
        {
            final Framebuffer zeroWidth = new Framebuffer();
            assertThatThrownBy(() -> zeroWidth.init(0, 100))
                .isInstanceOf(IllegalArgumentException.class);

            final Framebuffer negativeHeight = new Framebuffer();
            assertThatThrownBy(() -> negativeHeight.init(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldReleaseBuffersOnShutdownAndStayTerminal()
        {
            final Framebuffer fb = ready(64, 64);
            fb.shutdown();
            assertThat(fb.state()).isEqualTo(Framebuffer.State.SHUTDOWN);
            assertThat(fb.colorBuffer()).isNull();
            assertThat(fb.depthBuffer()).isNull();
            assertThatThrownBy(fb::shutdown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
            assertThatThrownBy(fb::clear).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldRejectTileSizeThatIsNotACacheLineMultiple()
        {
            assertThatThrownBy(() -> new Framebuffer(24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Framebuffer.STRIDE_ALIGNMENT));
            assertThatThrownBy(() -> new Framebuffer(0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Framebuffer(-16))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldAcceptTheDefaultTileSize()
        {
            assertThat(Framebuffer.DEFAULT_TILE_SIZE % Framebuffer.STRIDE_ALIGNMENT).isZero();
            assertThat(new Framebuffer().tileSize()).isEqualTo(Framebuffer.DEFAULT_TILE_SIZE);
        }
    }

    @Nested
    @DisplayName("resize")
    class Resize
    {
        @Test
        void shouldReallocateAtTheNewSize()
        {
            final Framebuffer fb = ready(320, 200);
            fb.resize(640, 480);
            assertThat(fb.width()).isEqualTo(640);
            assertThat(fb.height()).isEqualTo(480);
            assertThat(fb.strideInPixels()).isEqualTo(640);
            assertThat(fb.colorBuffer()).hasSize(640 * 480);
            assertThat(fb.depthBuffer()).hasSize(640 * 480);
        }

        @Test
        void shouldKeepBufferLengthEqualToPixelCountAfterShrinking()
        {
            final Framebuffer fb = ready(1920, 1080);
            fb.resize(100, 50);
            assertThat(fb.colorBuffer()).hasSize(fb.pixelCount());
            assertThat(fb.depthBuffer()).hasSize(fb.pixelCount());
            assertThat(fb.pixelCount()).isEqualTo(112 * 50);
        }

        @Test
        void shouldNotAllocateWhenTheSizeIsUnchanged()
        {
            final Framebuffer fb = ready(333, 111);
            final int[] color = fb.colorBuffer();
            final float[] depth = fb.depthBuffer();
            fb.resize(333, 111);
            assertThat(fb.colorBuffer()).isSameAs(color);
            assertThat(fb.depthBuffer()).isSameAs(depth);
        }

        @Test
        void shouldRecomputeTileGridOnResize()
        {
            final Framebuffer fb = ready(128, 128, 64);
            assertThat(fb.tileCount()).isEqualTo(4);
            fb.resize(129, 128);
            assertThat(fb.tilesX()).isEqualTo(3);
            assertThat(fb.tilesY()).isEqualTo(2);
            assertThat(fb.tileCount()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("stride padding and false sharing")
    class StridePadding
    {
        @Test
        void shouldPadStrideUpToACacheLineMultiple()
        {
            assertThat(ready(320, 200).strideInPixels()).isEqualTo(320);
            assertThat(ready(1920, 1080).strideInPixels()).isEqualTo(1920);
            assertThat(ready(1, 1).strideInPixels()).isEqualTo(16);
            assertThat(ready(17, 3).strideInPixels()).isEqualTo(32);
            assertThat(ready(1023, 7).strideInPixels()).isEqualTo(1024);
            assertThat(ready(1366, 768).strideInPixels()).isEqualTo(1376);
        }

        @Test
        void shouldNeverPadBelowTheWidth()
        {
            for (int w = 1; w <= 200; w++)
            {
                final Framebuffer fb = ready(w, 4);
                assertThat(fb.strideInPixels()).isGreaterThanOrEqualTo(w);
                assertThat(fb.strideInPixels() - w).isLessThan(Framebuffer.STRIDE_ALIGNMENT);
                assertThat(fb.strideInPixels() % Framebuffer.STRIDE_ALIGNMENT).isZero();
            }
        }

        @Test
        @DisplayName("every tile row starts on a cache-line boundary")
        void shouldStartEveryTileRowOnACacheLineBoundary()
        {
            // The invariant the padding exists for. If this fails, two workers
            // rasterizing horizontally adjacent tiles share cache lines on
            // every scanline — a silent 2-3x parallel slowdown, not a wrong
            // picture. Deliberately an awkward, non-tile-multiple resolution.
            final Framebuffer fb = ready(1366, 743, 64);
            for (int tile = 0; tile < fb.tileCount(); tile++)
            {
                final int minX = fb.tileMinX(tile);
                assertThat(minX % Framebuffer.STRIDE_ALIGNMENT).isZero();
                for (int y = fb.tileMinY(tile); y <= fb.tileMaxY(tile); y++)
                {
                    assertThat(fb.index(minX, y) % Framebuffer.STRIDE_ALIGNMENT)
                        .as("tile %d row %d must start on a cache line", tile, y)
                        .isZero();
                }
            }
        }

        @Test
        void shouldAlignUpOnlyToPowersOfTwo()
        {
            assertThat(Framebuffer.alignUp(0, 16)).isZero();
            assertThat(Framebuffer.alignUp(1, 16)).isEqualTo(16);
            assertThat(Framebuffer.alignUp(16, 16)).isEqualTo(16);
            assertThat(Framebuffer.alignUp(17, 16)).isEqualTo(32);
            assertThat(Framebuffer.alignUp(1000, 16)).isEqualTo(1008);
            assertThat(Framebuffer.alignUp(7, 8)).isEqualTo(8);
        }

        @Test
        void shouldIndexByStrideNotWidth()
        {
            final Framebuffer fb = ready(100, 10);
            assertThat(fb.strideInPixels()).isEqualTo(112);
            assertThat(fb.index(0, 0)).isZero();
            assertThat(fb.index(99, 0)).isEqualTo(99);
            assertThat(fb.index(0, 1)).isEqualTo(112);
            assertThat(fb.index(99, 9)).isEqualTo(9 * 112 + 99);
            assertThat(fb.pixelCount()).isEqualTo(1120);
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear
    {
        @Test
        void shouldFillColourAndDepthIncludingPadding()
        {
            final Framebuffer fb = ready(100, 10);
            fb.clear(DISTINCT);
            assertThat(fb.colorBuffer()).containsOnly(DISTINCT);
            assertThat(fb.depthBuffer()).containsOnly(Framebuffer.DEPTH_CLEAR);
        }

        @Test
        void shouldDefaultToOpaqueBlackAndFarDepth()
        {
            final Framebuffer fb = ready(32, 32);
            fb.setPixel(4, 4, DISTINCT);
            fb.setDepth(4, 4, 3.5f);
            fb.clear();
            assertThat(fb.pixel(4, 4)).isEqualTo(Framebuffer.CLEAR_COLOR_OPAQUE_BLACK);
            assertThat(Framebuffer.alpha(fb.pixel(4, 4))).isEqualTo(0xFF);
            assertThat(fb.depthAt(4, 4)).isEqualTo(Framebuffer.DEPTH_CLEAR);
        }

        @Test
        void shouldClearDepthToZeroSoGreaterIsNearerAlwaysPassesFirst()
        {
            // README § 8: clear to 0, test "greater passes". Any finite
            // positive 1/w must therefore beat a cleared pixel.
            final Framebuffer fb = ready(16, 16);
            fb.clearDepth();
            assertThat(fb.depthAt(0, 0)).isEqualTo(0.0f);
            assertThat(Float.MIN_VALUE > fb.depthAt(0, 0)).isTrue();
        }

        @Test
        void shouldNotAllocateOnRepeatedClears()
        {
            final Framebuffer fb = ready(200, 120);
            final int[] color = fb.colorBuffer();
            final float[] depth = fb.depthBuffer();
            for (int frame = 0; frame < 100; frame++)
            {
                fb.clear();
            }
            assertThat(fb.colorBuffer()).isSameAs(color);
            assertThat(fb.depthBuffer()).isSameAs(depth);
        }

        @Test
        void shouldClearColourWithoutTouchingDepth()
        {
            final Framebuffer fb = ready(16, 16);
            fb.setDepth(2, 2, 0.25f);
            fb.clearColor(DISTINCT);
            assertThat(fb.pixel(2, 2)).isEqualTo(DISTINCT);
            assertThat(fb.depthAt(2, 2)).isEqualTo(0.25f);
        }
    }

    @Nested
    @DisplayName("entity id buffer")
    class EntityIdBuffer
    {
        /** An entity id with no special bit pattern. */
        private static final int TAG = 4242;

        @Test
        @DisplayName("it is a third parallel buffer of the same length and stride")
        void shouldBeParallelToColourAndDepth()
        {
            // Same length and same stride is what lets the span loop reuse the
            // one index it already computed for colour and depth.
            final Framebuffer fb = ready(100, 10);

            assertThat(fb.entityIdBuffer()).hasSameSizeAs(fb.colorBuffer());
            assertThat(fb.entityIdBuffer()).hasSize(fb.pixelCount());
        }

        @Test
        @DisplayName("a fresh buffer already reads UNTAGGED everywhere")
        void shouldStartUntagged()
        {
            // Scene.UNTAGGED is zero precisely so that a just-allocated int[]
            // needs no clear pass before the first frame.
            assertThat(Scene.UNTAGGED).isZero();
            assertThat(ready(64, 64).entityIdBuffer()).containsOnly(Scene.UNTAGGED);
        }

        @Test
        @DisplayName("clear() clears ids too — a stale id is a ghost outline")
        void shouldClearIdsWithTheWholeFrame()
        {
            // The frame-start clear. An id surviving into the next frame would
            // outline where the entity WAS.
            final Framebuffer fb = ready(32, 32);
            fb.setEntityId(4, 4, TAG);
            fb.clear(DISTINCT);

            assertThat(fb.entityIdAt(4, 4)).isEqualTo(Scene.UNTAGGED);
            assertThat(fb.entityIdBuffer()).containsOnly(Scene.UNTAGGED);
        }

        @Test
        @DisplayName("clearDepth() leaves ids alone, so the viewmodel reset costs one memset")
        void shouldNotClearIdsWhenOnlyDepthIsCleared()
        {
            // Deliberate, and the one place this implementation departs from
            // "cleared whenever depth is cleared": clearDepth()'s production
            // caller is the viewmodel pass separator, which runs AFTER the
            // outline has consumed the ids and before a pass that writes none.
            // Clearing them there is a second full-frame memset no later read
            // can distinguish. The frame-start clear() above is what actually
            // prevents ghosting.
            final Framebuffer fb = ready(32, 32);
            fb.setEntityId(4, 4, TAG);
            fb.clearDepth();

            assertThat(fb.depthAt(4, 4)).isEqualTo(Framebuffer.DEPTH_CLEAR);
            assertThat(fb.entityIdAt(4, 4)).isEqualTo(TAG);
        }

        @Test
        @DisplayName("clearEntityIds() clears ids and nothing else")
        void shouldClearIdsOnItsOwn()
        {
            final Framebuffer fb = ready(32, 32);
            fb.setPixel(4, 4, DISTINCT);
            fb.setDepth(4, 4, 0.25f);
            fb.setEntityId(4, 4, TAG);
            fb.clearEntityIds();

            assertThat(fb.entityIdAt(4, 4)).isEqualTo(Scene.UNTAGGED);
            assertThat(fb.pixel(4, 4)).isEqualTo(DISTINCT);
            assertThat(fb.depthAt(4, 4)).isEqualTo(0.25f);
        }

        @Test
        @DisplayName("clearing repeatedly allocates nothing")
        void shouldNotAllocateOnRepeatedIdClears()
        {
            final Framebuffer fb = ready(200, 120);
            final int[] ids = fb.entityIdBuffer();
            for (int frame = 0; frame < 100; frame++)
            {
                fb.clearEntityIds();
            }
            assertThat(fb.entityIdBuffer()).isSameAs(ids);
        }

        @Test
        @DisplayName("single-pixel access round-trips and is bounds-checked")
        void shouldRoundTripOnePixelAndRejectOutOfBounds()
        {
            final Framebuffer fb = ready(16, 16);
            fb.setEntityId(15, 15, TAG);

            assertThat(fb.entityIdAt(15, 15)).isEqualTo(TAG);
            assertThat(fb.entityIdAt(0, 0)).isEqualTo(Scene.UNTAGGED);
            assertThatThrownBy(() -> fb.entityIdAt(16, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fb.setEntityId(0, 16, TAG))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("shutdown releases it with the other two")
        void shouldReleaseTheIdBufferOnShutdown()
        {
            final Framebuffer fb = ready(16, 16);
            fb.shutdown();

            assertThat(fb.entityIdBuffer()).isNull();
        }

        @Test
        @DisplayName("a resize reallocates it to the new pixel count")
        void shouldResizeTheIdBufferWithTheFrame()
        {
            final Framebuffer fb = ready(64, 64);
            fb.setEntityId(1, 1, TAG);
            fb.resize(128, 32);

            assertThat(fb.entityIdBuffer()).hasSize(fb.pixelCount());
            assertThat(fb.entityIdBuffer()).containsOnly(Scene.UNTAGGED);
        }
    }

    @Nested
    @DisplayName("tile geometry")
    class TileGeometry
    {
        @Test
        void shouldTileAnExactMultipleWithNoPartialTiles()
        {
            final Framebuffer fb = ready(256, 128, 64);
            assertThat(fb.tilesX()).isEqualTo(4);
            assertThat(fb.tilesY()).isEqualTo(2);
            assertThat(fb.tileCount()).isEqualTo(8);
            for (int tile = 0; tile < fb.tileCount(); tile++)
            {
                assertThat(fb.tileWidth(tile)).isEqualTo(64);
                assertThat(fb.tileHeight(tile)).isEqualTo(64);
            }
        }

        @Test
        @DisplayName("clamps the last column and row when the size is not a tile multiple")
        void shouldClampPartialTilesAtRightAndBottom()
        {
            // 200x140 with 64px tiles: 4x3 tiles, last column 8 wide,
            // last row 12 tall. Off-by-one here corrupts exactly the screen
            // edges, which is why the numbers are spelled out.
            final Framebuffer fb = ready(200, 140, 64);
            assertThat(fb.tilesX()).isEqualTo(4);
            assertThat(fb.tilesY()).isEqualTo(3);
            assertThat(fb.tileCount()).isEqualTo(12);

            final int topLeft = 0;
            assertThat(fb.tileMinX(topLeft)).isZero();
            assertThat(fb.tileMinY(topLeft)).isZero();
            assertThat(fb.tileMaxX(topLeft)).isEqualTo(63);
            assertThat(fb.tileMaxY(topLeft)).isEqualTo(63);

            final int topRight = 3;
            assertThat(fb.tileMinX(topRight)).isEqualTo(192);
            assertThat(fb.tileMaxX(topRight)).isEqualTo(199);
            assertThat(fb.tileWidth(topRight)).isEqualTo(8);
            assertThat(fb.tileHeight(topRight)).isEqualTo(64);

            final int bottomLeft = 8;
            assertThat(fb.tileMinY(bottomLeft)).isEqualTo(128);
            assertThat(fb.tileMaxY(bottomLeft)).isEqualTo(139);
            assertThat(fb.tileHeight(bottomLeft)).isEqualTo(12);
            assertThat(fb.tileWidth(bottomLeft)).isEqualTo(64);

            final int bottomRight = 11;
            assertThat(fb.tileMaxX(bottomRight)).isEqualTo(199);
            assertThat(fb.tileMaxY(bottomRight)).isEqualTo(139);
            assertThat(fb.tileWidth(bottomRight)).isEqualTo(8);
            assertThat(fb.tileHeight(bottomRight)).isEqualTo(12);
        }

        @Test
        void shouldProduceOneClampedTileWhenSmallerThanATile()
        {
            final Framebuffer fb = ready(3, 5, 64);
            assertThat(fb.tileCount()).isEqualTo(1);
            assertThat(fb.tileMinX(0)).isZero();
            assertThat(fb.tileMinY(0)).isZero();
            assertThat(fb.tileMaxX(0)).isEqualTo(2);
            assertThat(fb.tileMaxY(0)).isEqualTo(4);
            assertThat(fb.tileWidth(0)).isEqualTo(3);
            assertThat(fb.tileHeight(0)).isEqualTo(5);
        }

        @Test
        void shouldHandleOneByOne()
        {
            final Framebuffer fb = ready(1, 1);
            assertThat(fb.tileCount()).isEqualTo(1);
            assertThat(fb.tileWidth(0)).isEqualTo(1);
            assertThat(fb.tileHeight(0)).isEqualTo(1);
        }

        @Test
        void shouldHandleExactlyOneOverATileBoundary()
        {
            final Framebuffer fb = ready(65, 65, 64);
            assertThat(fb.tilesX()).isEqualTo(2);
            assertThat(fb.tilesY()).isEqualTo(2);
            assertThat(fb.tileWidth(1)).isEqualTo(1);
            assertThat(fb.tileHeight(2)).isEqualTo(1);
            assertThat(fb.tileWidth(3)).isEqualTo(1);
            assertThat(fb.tileHeight(3)).isEqualTo(1);
        }

        @Test
        @DisplayName("tiles partition the visible rectangle exactly")
        void shouldPartitionEveryVisiblePixelIntoExactlyOneTile()
        {
            // The invariant the lock-free raster pass rests on: every pixel
            // belongs to exactly one tile, and the tiles cover nothing else.
            final int width = 201;
            final int height = 137;
            final Framebuffer fb = ready(width, height, 32);

            final int[] owner = new int[width * height];
            Arrays.fill(owner, -1);

            int covered = 0;
            for (int tile = 0; tile < fb.tileCount(); tile++)
            {
                for (int y = fb.tileMinY(tile); y <= fb.tileMaxY(tile); y++)
                {
                    for (int x = fb.tileMinX(tile); x <= fb.tileMaxX(tile); x++)
                    {
                        assertThat(owner[y * width + x])
                            .as("pixel (%d, %d) claimed twice", x, y)
                            .isEqualTo(-1);
                        owner[y * width + x] = tile;
                        covered++;
                    }
                }
            }
            assertThat(covered).isEqualTo(width * height);
            assertThat(owner).doesNotContain(-1);
        }

        @Test
        void shouldSumTileAreasToTheVisibleArea()
        {
            final Framebuffer fb = ready(1366, 743, 64);
            int area = 0;
            for (int tile = 0; tile < fb.tileCount(); tile++)
            {
                area += fb.tileWidth(tile) * fb.tileHeight(tile);
            }
            assertThat(area).isEqualTo(1366 * 743);
        }

        @Test
        void shouldRejectOutOfRangeTilesAndPixels()
        {
            final Framebuffer fb = ready(200, 140, 64);
            assertThatThrownBy(() -> fb.tileMinX(12))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fb.tileMinX(-1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fb.pixel(200, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fb.pixel(0, 140))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fb.pixel(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldNotAddressPaddingColumns()
        {
            // Padding exists for alignment; nothing may draw into it.
            final Framebuffer fb = ready(100, 4);
            assertThat(fb.strideInPixels()).isEqualTo(112);
            assertThatThrownBy(() -> fb.setPixel(100, 0, DISTINCT))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("colour round trip")
    class ColourRoundTrip
    {
        @Test
        void shouldRoundTripPackedChannels()
        {
            assertThat(Framebuffer.red(DISTINCT)).isEqualTo(0x12);
            assertThat(Framebuffer.green(DISTINCT)).isEqualTo(0x34);
            assertThat(Framebuffer.blue(DISTINCT)).isEqualTo(0x56);
            assertThat(Framebuffer.alpha(DISTINCT)).isEqualTo(0x78);
            assertThat(DISTINCT).isEqualTo(0x12345678);
        }

        @Test
        void shouldRoundTripTheExtremes()
        {
            final int white = Framebuffer.packRgba(0xFF, 0xFF, 0xFF, 0xFF);
            assertThat(white).isEqualTo(0xFFFFFFFF);
            assertThat(Framebuffer.red(white)).isEqualTo(0xFF);
            assertThat(Framebuffer.alpha(white)).isEqualTo(0xFF);

            final int transparentBlack = Framebuffer.packRgba(0, 0, 0, 0);
            assertThat(transparentBlack).isZero();

            // The top channel must survive sign extension: red 0xFF sets bit 31.
            final int redOnly = Framebuffer.packRgba(0xFF, 0, 0, 0);
            assertThat(redOnly).isEqualTo(0xFF000000);
            assertThat(Framebuffer.red(redOnly)).isEqualTo(0xFF);
        }

        @Test
        void shouldRoundTripThroughTheColourBuffer()
        {
            final Framebuffer fb = ready(37, 13);
            fb.clear();
            fb.setPixel(36, 12, DISTINCT);
            assertThat(fb.pixel(36, 12)).isEqualTo(DISTINCT);
            assertThat(fb.colorBuffer()[fb.index(36, 12)]).isEqualTo(DISTINCT);
            assertThat(fb.pixel(35, 12)).isEqualTo(Framebuffer.CLEAR_COLOR_OPAQUE_BLACK);
        }

        @Test
        @DisplayName("a packed int becomes the bytes R, G, B, A in that order")
        void shouldSerialiseToRgbaByteOrder()
        {
            // The half of the round trip this module owns: int -> bytes.
            final byte[] bytes = new byte[Integer.BYTES];
            Framebuffer.writeRgbaBytes(DISTINCT, bytes, 0);
            assertThat(bytes[0]).isEqualTo((byte) 0x12);
            assertThat(bytes[1]).isEqualTo((byte) 0x34);
            assertThat(bytes[2]).isEqualTo((byte) 0x56);
            assertThat(bytes[3]).isEqualTo((byte) 0x78);
            assertThat(Framebuffer.readRgbaBytes(bytes, 0)).isEqualTo(DISTINCT);
        }

        @Test
        @DisplayName("a big-endian IntBuffer view emits the same bytes; a native-order one may not")
        void shouldMatchABigEndianIntBufferViewAndExposeTheNativeOrderHazard()
        {
            // This is the byte-order step docs/ASSETS.md § 4 warns about,
            // exercised rather than assumed. The presentation path copies the
            // int[] into a direct ByteBuffer (libGDX Pixmap.getPixels());
            // whether 0xRRGGBBAA lands as R,G,B,A depends entirely on that
            // buffer's ByteOrder.
            final byte[] expected = new byte[Integer.BYTES];
            Framebuffer.writeRgbaBytes(DISTINCT, expected, 0);

            final ByteBuffer direct = ByteBuffer.allocateDirect(Integer.BYTES);
            assertThat(direct.order())
                .as("a fresh direct ByteBuffer is big-endian by contract")
                .isEqualTo(ByteOrder.BIG_ENDIAN);
            direct.asIntBuffer().put(DISTINCT);
            final byte[] viaBigEndian = new byte[Integer.BYTES];
            direct.duplicate().get(viaBigEndian);
            assertThat(viaBigEndian).isEqualTo(expected);

            // Negative control: the same bulk put through a native-order view.
            // On any little-endian machine it reverses the channels, which is
            // exactly the everything-looks-slightly-wrong bug.
            final ByteBuffer nativeOrder = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.nativeOrder());
            nativeOrder.asIntBuffer().put(DISTINCT);
            final byte[] viaNative = new byte[Integer.BYTES];
            nativeOrder.duplicate().get(viaNative);

            if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN)
            {
                assertThat(viaNative).isEqualTo(expected);
            }
            else
            {
                assertThat(viaNative).containsExactly(0x78, 0x56, 0x34, 0x12);
                assertThat(viaNative).isNotEqualTo(expected);
            }
        }

        @Test
        @DisplayName("a whole frame survives buffer -> bytes -> buffer unchanged")
        void shouldRoundTripAWholeFrameThroughBytes()
        {
            final Framebuffer fb = ready(19, 7);
            fb.clear();
            for (int y = 0; y < fb.height(); y++)
            {
                for (int x = 0; x < fb.width(); x++)
                {
                    fb.setPixel(x, y, Framebuffer.packRgba(x * 13, y * 31, x + y, 0xFF));
                }
            }

            final int[] contiguous = new int[fb.width() * fb.height()];
            fb.copyColorTo(contiguous);

            final byte[] wire = new byte[contiguous.length * Integer.BYTES];
            for (int i = 0; i < contiguous.length; i++)
            {
                Framebuffer.writeRgbaBytes(contiguous[i], wire, i * Integer.BYTES);
            }

            for (int y = 0; y < fb.height(); y++)
            {
                for (int x = 0; x < fb.width(); x++)
                {
                    final int offset = (y * fb.width() + x) * Integer.BYTES;
                    final int decoded = Framebuffer.readRgbaBytes(wire, offset);
                    assertThat(decoded)
                        .as("pixel (%d, %d)", x, y)
                        .isEqualTo(fb.pixel(x, y));
                    assertThat(Framebuffer.red(decoded)).isEqualTo((x * 13) & 0xFF);
                    assertThat(Framebuffer.green(decoded)).isEqualTo((y * 31) & 0xFF);
                    assertThat(Framebuffer.blue(decoded)).isEqualTo((x + y) & 0xFF);
                    assertThat(Framebuffer.alpha(decoded)).isEqualTo(0xFF);
                }
            }
        }

        @Test
        void shouldRejectShortByteWindows()
        {
            final byte[] tiny = new byte[3];
            assertThatThrownBy(() -> Framebuffer.writeRgbaBytes(DISTINCT, tiny, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Framebuffer.readRgbaBytes(tiny, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Framebuffer.writeRgbaBytes(DISTINCT, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("presentation handoff")
    class PresentationHandoff
    {
        @Test
        @DisplayName("copyColorTo strips the stride padding")
        void shouldStripPaddingWhenCopyingOut()
        {
            // The adapter must NOT upload colorBuffer() directly: it is
            // stride x height, not width x height.
            final Framebuffer fb = ready(100, 3);
            assertThat(fb.strideInPixels()).isEqualTo(112);
            fb.clear(Framebuffer.packRgba(0, 0, 0, 0xFF));
            for (int y = 0; y < 3; y++)
            {
                fb.setPixel(0, y, Framebuffer.packRgba(y + 1, 0, 0, 0xFF));
                fb.setPixel(99, y, Framebuffer.packRgba(0, y + 1, 0, 0xFF));
            }

            final int[] out = new int[100 * 3];
            fb.copyColorTo(out);

            for (int y = 0; y < 3; y++)
            {
                assertThat(Framebuffer.red(out[y * 100])).isEqualTo(y + 1);
                assertThat(Framebuffer.green(out[y * 100 + 99])).isEqualTo(y + 1);
            }
            assertThat(out).hasSize(fb.width() * fb.height());
        }

        @Test
        void shouldNotAllocateWhenCopyingOut()
        {
            final Framebuffer fb = ready(64, 64);
            final int[] out = new int[fb.width() * fb.height()];
            fb.clear(DISTINCT);
            for (int frame = 0; frame < 10; frame++)
            {
                fb.copyColorTo(out);
            }
            assertThat(out).containsOnly(DISTINCT);
        }

        @Test
        void shouldRejectAnUndersizedDestination()
        {
            final Framebuffer fb = ready(64, 64);
            assertThatThrownBy(() -> fb.copyColorTo(new int[64 * 64 - 1]))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fb.copyColorTo(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldAcceptAnOversizedDestination()
        {
            final Framebuffer fb = ready(16, 16);
            fb.clear(DISTINCT);
            final int[] out = new int[16 * 16 + 8];
            fb.copyColorTo(out);
            assertThat(out[16 * 16 - 1]).isEqualTo(DISTINCT);
            assertThat(out[16 * 16]).isZero();
        }
    }
}
