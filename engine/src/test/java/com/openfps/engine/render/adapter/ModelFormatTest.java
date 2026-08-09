/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the flat binary runtime model format.
 *
 * <p>This class reads binary files, so malformed input matters more than
 * usual. Three kinds of coverage sit here and they are deliberately different
 * from one another:</p>
 *
 * <ul>
 *   <li><b>Round trip against literal bytes.</b> {@link ModelFileFixture}
 *       writes every offset by hand, so these tests pin the layout rather than
 *       the reader's self-consistency.</li>
 *   <li><b>Named rejections.</b> One test per structural invariant, each
 *       asserting the message names the field and the offending value —
 *       because a binary parse failure that does not say which field is a
 *       failure the reader has to reproduce with a hex editor.</li>
 *   <li><b>Exhaustive corruption.</b> Every truncation length and every single
 *       byte position of a valid file, checked to produce a
 *       {@link ModelFormatException} or a clean parse, and never an
 *       {@code ArrayIndexOutOfBoundsException}, an
 *       {@code OutOfMemoryError} or anything else. That is the property that
 *       actually matters: no path through this reader indexes outside the
 *       buffer it was handed.</li>
 * </ul>
 */
class ModelFormatTest
{
    @Nested
    @DisplayName("round trip")
    class RoundTrip
    {
        @Test
        @DisplayName("a known file image reads back with every field intact")
        void shouldReadKnownImage()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.valid());

            assertThat(model.versionMajor()).isEqualTo(ModelFormat.VERSION_MAJOR);

            assertThat(model.versionMinor()).isEqualTo(ModelFormat.VERSION_MINOR);

            assertThat(model.vertexCount()).isEqualTo(4);

            assertThat(model.indexCount()).isEqualTo(6);

            assertThat(model.triangleCount()).isEqualTo(2);

            assertThat(model.submeshCount()).isEqualTo(2);

            assertThat(model.textureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("vertex positions, UVs and baked colours survive the round trip")
        void shouldReadVertexAttributes()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.valid());

            assertThat(model.positionX(0)).isEqualTo(0.0f);

            assertThat(model.positionY(2)).isEqualTo(1.0f);

            assertThat(model.positionZ(3)).isEqualTo(0.0f);

            assertThat(model.texCoordU(1)).isEqualTo(1.0f);

            assertThat(model.texCoordV(2)).isEqualTo(1.0f);

            assertThat(model.colour(0)).isEqualTo(0xFF0000FF);

            assertThat(model.colour(3)).isEqualTo(0xFFFFFFFF);
        }

        @Test
        @DisplayName("the packed colour is the format Rgba defines, not a swizzle of it")
        void shouldUseRgbaChannelOrder()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.valid());

            assertThat(Rgba.red(model.colour(0))).isEqualTo(255);

            assertThat(Rgba.green(model.colour(0))).isZero();

            assertThat(Rgba.blue(model.colour(0))).isZero();

            assertThat(Rgba.alpha(model.colour(0))).isEqualTo(255);
        }

        @Test
        @DisplayName("indices and the interleaved block are exposed as live arrays")
        void shouldExposeRawArrays()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.valid());

            assertThat(model.indices()).containsExactly(0, 1, 2, 0, 2, 3);

            assertThat(model.vertexData())
                .hasSize(4 * ModelFormat.VERTEX_STRIDE_INTS)
                .isSameAs(model.vertexData());
        }

        @Test
        @DisplayName("submeshes carry their own index range and texture")
        void shouldReadSubmeshTable()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.valid());

            assertThat(model.submeshFirstIndex(0)).isZero();

            assertThat(model.submeshIndexCount(0)).isEqualTo(3);

            assertThat(model.submeshTextureIndex(0)).isZero();

            assertThat(model.submeshFirstIndex(1)).isEqualTo(3);

            assertThat(model.submeshTextureIndex(1)).isEqualTo(ModelFormat.NO_TEXTURE);
        }

        @Test
        @DisplayName("a texture becomes the MipChain the sampler consumes")
        void shouldBuildMipChain()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.valid());

            final MipChain chain = model.mipChain(0);

            assertThat(model.textureWidth(0)).isEqualTo(2);

            assertThat(model.textureHeight(0)).isEqualTo(2);

            assertThat(model.textureLevelCount(0)).isEqualTo(2);

            assertThat(chain.levelCount()).isEqualTo(2);

            assertThat(chain.texel(0, 0, 0)).isEqualTo(0xFF0000FF);

            assertThat(chain.texel(0, 1, 1)).isEqualTo(0xFFFFFFFF);

            assertThat(chain.texel(1, 0, 0)).isEqualTo(0x7F7F7FFF);
        }

        @Test
        @DisplayName("the mip chain is built once and shared, not rebuilt per call")
        void shouldShareMipChain()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.valid());

            assertThat(model.mipChain(0)).isSameAs(model.mipChain(0));
        }

        @Test
        @DisplayName("the precomputed bounding box survives the round trip")
        void shouldReadBounds()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.valid());

            assertThat(model.minX()).isEqualTo(0.0f);

            assertThat(model.minY()).isEqualTo(0.0f);

            assertThat(model.minZ()).isEqualTo(0.0f);

            assertThat(model.maxX()).isEqualTo(1.0f);

            assertThat(model.maxY()).isEqualTo(1.0f);

            assertThat(model.maxZ()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("an empty model is structurally valid and reads as empty")
        void shouldReadEmptyModel()
        {
            final ModelFormat model = ModelFormat.read(ModelFileFixture.empty());

            assertThat(model.vertexCount()).isZero();

            assertThat(model.indexCount()).isZero();

            assertThat(model.submeshCount()).isZero();

            assertThat(model.textureCount()).isZero();

            assertThat(ModelFileFixture.empty()).hasSize(ModelFileFixture.HEADER_BYTES);
        }

        @Test
        @DisplayName("the header is exactly 96 bytes and every section is 4-byte aligned")
        void shouldLayOutSectionsAsDocumented()
        {
            final byte[] data = ModelFileFixture.valid();

            assertThat(ModelFormat.HEADER_SIZE).isEqualTo(ModelFileFixture.HEADER_BYTES);

            assertThat(ModelFileFixture.getInt(data, ModelFileFixture.AT_VERTEX_OFFSET))
                .isEqualTo(ModelFileFixture.HEADER_BYTES);

            assertThat(ModelFileFixture.getInt(data, ModelFileFixture.AT_INDEX_OFFSET) % 4)
                .isZero();

            assertThat(ModelFileFixture.getInt(data, ModelFileFixture.AT_TEXEL_OFFSET) % 4)
                .isZero();

            assertThat(ModelFileFixture.getInt(data, ModelFileFixture.AT_FILE_SIZE))
                .isEqualTo(data.length);
        }
    }

    @Nested
    @DisplayName("magic and version")
    class MagicAndVersion
    {
        @Test
        @DisplayName("null input is refused rather than dereferenced")
        void shouldRejectNull()
        {
            assertThatThrownBy(() -> ModelFormat.read(null))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("null");
        }

        @Test
        @DisplayName("a bad magic is refused, quoting both values")
        void shouldRejectBadMagic()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_MAGIC, 0x12345678);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("magic")
                .hasMessageContaining("12345678");
        }

        @Test
        @DisplayName("a WAD file offered as a model is refused on magic, not misparsed")
        void shouldRejectForeignFile()
        {
            final byte[] wad = new byte[ModelFileFixture.HEADER_BYTES];

            wad[0] = 'I';

            wad[1] = 'W';

            wad[2] = 'A';

            wad[3] = 'D';

            assertThatThrownBy(() -> ModelFormat.read(wad))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("magic");
        }

        @Test
        @DisplayName("an unrecognised major version fails loudly, naming the version")
        void shouldRejectUnknownMajorVersion()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putShort(data, ModelFileFixture.AT_VERSION_MAJOR, 7);

            ModelFileFixture.putShort(data, ModelFileFixture.AT_VERSION_MINOR, 3);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("version")
                .hasMessageContaining("7.3")
                .hasMessageContaining(Integer.toString(ModelFormat.VERSION_MAJOR));
        }

        @Test
        @DisplayName("a version-zero file is refused; the format starts at 1")
        void shouldRejectVersionZero()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putShort(data, ModelFileFixture.AT_VERSION_MAJOR, 0);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("version");
        }

        @Test
        @DisplayName("a higher minor version is accepted; minor bumps are additive")
        void shouldAcceptHigherMinorVersion()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putShort(data, ModelFileFixture.AT_VERSION_MINOR, 9);

            final ModelFormat model = ModelFormat.read(data);

            assertThat(model.versionMinor()).isEqualTo(9);

            assertThat(model.vertexCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("a header size smaller than the version 1.0 header is refused")
        void shouldRejectShrunkenHeader()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_HEADER_SIZE, 32);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("headerSize")
                .hasMessageContaining("32");
        }

        @Test
        @DisplayName("a header size past the end of the file is refused")
        void shouldRejectHeaderPastEndOfFile()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_HEADER_SIZE, data.length + 4);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("headerSize");
        }
    }

    @Nested
    @DisplayName("truncation and size")
    class TruncationAndSize
    {
        @Test
        @DisplayName("a file shorter than the header is refused before any field is read")
        void shouldRejectShortFile()
        {
            assertThatThrownBy(() -> ModelFormat.read(new byte[8]))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("8")
                .hasMessageContaining(Integer.toString(ModelFormat.HEADER_SIZE));
        }

        @Test
        @DisplayName("an empty array is refused")
        void shouldRejectEmptyArray()
        {
            assertThatThrownBy(() -> ModelFormat.read(new byte[0]))
                .isInstanceOf(ModelFormatException.class);
        }

        @Test
        @DisplayName("a declared size that disagrees with the buffer is refused")
        void shouldRejectSizeMismatch()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_FILE_SIZE, data.length - 4);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("truncated or padded");
        }

        @Test
        @DisplayName("every truncation of a valid file is refused, never misread")
        void shouldRejectEveryTruncation()
        {
            final byte[] full = ModelFileFixture.valid();

            for (int length = 0; length < full.length; length++)
            {
                final byte[] cut = Arrays.copyOf(full, length);

                assertThatThrownBy(() -> ModelFormat.read(cut))
                    .describedAs("truncated to %d bytes", length)
                    .isInstanceOf(ModelFormatException.class);
            }
        }

        @Test
        @DisplayName("trailing bytes appended to a valid file are refused")
        void shouldRejectTrailingBytes()
        {
            final byte[] padded = Arrays.copyOf(ModelFileFixture.valid(),
                ModelFileFixture.valid().length + 16);

            assertThatThrownBy(() -> ModelFormat.read(padded))
                .isInstanceOf(ModelFormatException.class);
        }
    }

    @Nested
    @DisplayName("section addressing")
    class SectionAddressing
    {
        @Test
        @DisplayName("a section offset inside the header is refused")
        void shouldRejectOffsetInsideHeader()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_VERTEX_OFFSET, 16);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("vertex")
                .hasMessageContaining("16");
        }

        @Test
        @DisplayName("a section offset past the end of the file is refused")
        void shouldRejectOffsetPastEndOfFile()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_INDEX_OFFSET, data.length + 64);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("index");
        }

        @Test
        @DisplayName("a negative section offset is refused, not read as a huge unsigned one")
        void shouldRejectNegativeOffset()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_TEXEL_OFFSET, -4);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("texel");
        }

        @Test
        @DisplayName("a misaligned section offset is refused")
        void shouldRejectMisalignedOffset()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_VERTEX_OFFSET,
                ModelFileFixture.HEADER_BYTES + 1);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("aligned");
        }

        @Test
        @DisplayName("a section that runs past the end of the file is refused")
        void shouldRejectSectionOverrun()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_VERTEX_COUNT, 64);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("vertex");
        }

        @Test
        @DisplayName("a negative count is refused rather than wrapped")
        void shouldRejectNegativeCount()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_TEXTURE_COUNT, -1);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("textureCount");
        }

        @Test
        @DisplayName("a count past the reader's safety cap is refused before allocating")
        void shouldRejectAbsurdCount()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_VERTEX_COUNT,
                ModelFormat.MAX_VERTEX_COUNT + 1);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("vertexCount");
        }

        @Test
        @DisplayName("an index count that is not a whole number of triangles is refused")
        void shouldRejectPartialTriangle()
        {
            final byte[] data = ModelFileFixture.valid();

            ModelFileFixture.putInt(data, ModelFileFixture.AT_INDEX_COUNT, 4);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("multiple of 3");
        }
    }

    @Nested
    @DisplayName("content validation")
    class ContentValidation
    {
        @Test
        @DisplayName("an index addressing a vertex that does not exist is refused")
        void shouldRejectOutOfRangeIndex()
        {
            final byte[] data = ModelFileFixture.valid();

            final int indexOffset = ModelFileFixture.getInt(data, ModelFileFixture.AT_INDEX_OFFSET);

            ModelFileFixture.putInt(data, indexOffset + 4, 99);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("index[1]")
                .hasMessageContaining("99");
        }

        @Test
        @DisplayName("a negative index is refused")
        void shouldRejectNegativeIndex()
        {
            final byte[] data = ModelFileFixture.valid();

            final int indexOffset = ModelFileFixture.getInt(data, ModelFileFixture.AT_INDEX_OFFSET);

            ModelFileFixture.putInt(data, indexOffset, -1);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("index[0]");
        }

        @Test
        @DisplayName("a submesh covering indices that do not exist is refused")
        void shouldRejectSubmeshOverrun()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_SUBMESH_OFFSET);

            ModelFileFixture.putInt(data, at + 4, 300);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("submesh 0")
                .hasMessageContaining("300");
        }

        @Test
        @DisplayName("a submesh whose span is not a whole number of triangles is refused")
        void shouldRejectSubmeshPartialTriangle()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_SUBMESH_OFFSET);

            ModelFileFixture.putInt(data, at + 4, 2);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("not a multiple of 3");
        }

        @Test
        @DisplayName("a submesh naming a texture that does not exist is refused")
        void shouldRejectSubmeshTexture()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_SUBMESH_OFFSET);

            ModelFileFixture.putInt(data, at + 8, 5);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("texture 5");
        }

        @Test
        @DisplayName("NO_TEXTURE is a legal submesh texture and is not range checked")
        void shouldAcceptNoTexture()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_SUBMESH_OFFSET);

            ModelFileFixture.putInt(data, at + 8, ModelFormat.NO_TEXTURE);

            assertThatCode(() -> ModelFormat.read(data)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a non-power-of-two texture is refused, naming the dimension")
        void shouldRejectNonPowerOfTwoTexture()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_TEXTURE_OFFSET);

            ModelFileFixture.putInt(data, at, 3);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("width")
                .hasMessageContaining("3");
        }

        @Test
        @DisplayName("a texture declaring more mip levels than its size allows is refused")
        void shouldRejectTooManyLevels()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_TEXTURE_OFFSET);

            ModelFileFixture.putInt(data, at + 8, 6);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("mip levels");
        }

        @Test
        @DisplayName("a texture declaring zero mip levels is refused; mipmaps are required")
        void shouldRejectZeroLevels()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_TEXTURE_OFFSET);

            ModelFileFixture.putInt(data, at + 8, 0);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("mip levels");
        }

        @Test
        @DisplayName("a texture whose levels run past the texel blob is refused")
        void shouldRejectTexelOverrun()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_TEXTURE_OFFSET);

            ModelFileFixture.putInt(data, at + 12, 3);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("blob");
        }

        @Test
        @DisplayName("a texture starting at a negative texel is refused")
        void shouldRejectNegativeFirstTexel()
        {
            final byte[] data = ModelFileFixture.valid();

            final int at = ModelFileFixture.getInt(data, ModelFileFixture.AT_TEXTURE_OFFSET);

            ModelFileFixture.putInt(data, at + 12, -1);

            assertThatThrownBy(() -> ModelFormat.read(data))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("texel");
        }

        @Test
        @DisplayName("a 512-square texture with a full 10-level chain reads back correctly")
        void shouldReadBudgetSizedTexture()
        {
            final int size = ModelFormat.MAX_TEXTURE_DIMENSION;

            final int[] texels = new int[pyramidTexels(size)];

            Arrays.fill(texels, Rgba.OPAQUE_BLACK);

            final byte[] data = ModelFileFixture.build(new int[0], new int[0], new int[0],
                new int[] {size, size, 10, 0}, texels, new float[6]);

            final ModelFormat model = ModelFormat.read(data);

            assertThat(model.mipChain(0).levelCount()).isEqualTo(10);

            assertThat(model.mipChain(0).width(9)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("exhaustive corruption")
    class ExhaustiveCorruption
    {
        @Test
        @DisplayName("flipping any single byte never escapes as a non-format exception")
        void shouldContainEverySingleByteCorruption()
        {
            final byte[] original = ModelFileFixture.valid();

            for (int at = 0; at < original.length; at++)
            {
                assertSurvivesCorruption(original, at, (byte) 0xFF);

                assertSurvivesCorruption(original, at, (byte) 0x00);

                assertSurvivesCorruption(original, at, (byte) 0x80);
            }
        }

        @Test
        @DisplayName("a header of arbitrary bytes is refused, not acted on")
        void shouldRejectArbitraryHeader()
        {
            final byte[] noise = new byte[ModelFormat.HEADER_SIZE];

            for (int i = 0; i < noise.length; i++)
            {
                noise[i] = (byte) ((i * 37) + 11);
            }

            assertThatThrownBy(() -> ModelFormat.read(noise))
                .isInstanceOf(ModelFormatException.class);
        }
    }

    // Corrupts one byte and asserts the reader either parses it or refuses it
    // with a ModelFormatException — never an array index, arithmetic or
    // allocation failure escaping from inside the parse.
    private static void assertSurvivesCorruption(final byte[] original, final int at,
        final byte value)
    {
        final byte[] data = original.clone();

        if (data[at] == value)
        {
            return;
        }

        data[at] = value;

        try
        {
            ModelFormat.read(data);
        }
        catch (final ModelFormatException expected)
        {
            assertThat(expected.getMessage()).isNotEmpty();
        }
        catch (final RuntimeException unexpected)
        {
            throw new AssertionError("byte " + at + " set to " + value
                + " escaped as " + unexpected.getClass().getName(), unexpected);
        }
    }

    // Total texels in a complete square pyramid, level 0 down to 1x1.
    private static int pyramidTexels(final int size)
    {
        // MUTABLE local — running sum over the pyramid.
        int total = 0;

        for (int dimension = size; dimension >= 1; dimension >>= 1)
        {
            total += dimension * dimension;
        }

        return total;
    }
}
