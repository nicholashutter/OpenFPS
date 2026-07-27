/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.resource.WadException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the WAD container reader.
 *
 * Every container here is synthesized by {@link WadBuilder}; no test needs
 * a .wad on disk. The one test that does touch the filesystem writes a
 * synthesized image into a JUnit temporary directory and deletes it with
 * the test.
 */
class WadReaderTest
{
    /** Payload of the first lump in {@link #sampleIwad()}. */
    private static final byte[] PLAYPAL = WadBuilder.bytes(0x10, 0x20, 0x30, 0x40, 0x50);

    /** Payload of the second lump in {@link #sampleIwad()}. */
    private static final byte[] COLORMAP = WadBuilder.bytes(0xAA, 0xBB);

    private static byte[] sampleIwad()
    {
        return WadBuilder.iwad()
            .lump("PLAYPAL", PLAYPAL)
            .lump("COLORMAP", COLORMAP)
            .marker("E1M1")
            .build();
    }

    // ===============================================================
    //  Header
    // ===============================================================

    @Nested
    @DisplayName("Header")
    class Header
    {
        @Test
        @DisplayName("a well-formed IWAD is recognized as a main WAD")
        void shouldReadIwad()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.wadType()).isEqualTo(WadReader.WadType.IWAD);
            assertThat(reader.lumpCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a well-formed PWAD is recognized as a patch WAD")
        void shouldReadPwad()
        {
            final byte[] image = WadBuilder.pwad()
                .lump("MAP01", WadBuilder.bytes(1, 2, 3))
                .build();

            final WadReader reader = WadReader.fromBytes(image);

            assertThat(reader.wadType()).isEqualTo(WadReader.WadType.PWAD);
            assertThat(reader.lumpCount()).isEqualTo(1);
            assertThat(reader.lumpName(0)).isEqualTo("MAP01");
        }

        @Test
        @DisplayName("an empty but valid WAD reports zero lumps")
        void shouldReadEmptyWad()
        {
            final WadReader reader = WadReader.fromBytes(WadBuilder.iwad().build());

            assertThat(reader.lumpCount()).isZero();
            assertThat(reader.imageBytes()).isEqualTo(WadBuilder.HEADER_SIZE);
            assertThat(reader.findLump("ANYTHING")).isEqualTo(WadReader.LUMP_NOT_FOUND);
        }

        @Test
        @DisplayName("the header is read little-endian, not big-endian")
        void shouldReadHeaderLittleEndian()
        {
            // 3 lumps encodes as 03 00 00 00; read big-endian it would be 50331648
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.lumpCount()).isEqualTo(3);
        }
    }

    // ===============================================================
    //  Malformed containers
    // ===============================================================

    @Nested
    @DisplayName("Malformed containers")
    class Malformed
    {
        @Test
        @DisplayName("a file shorter than the 12-byte header is rejected")
        void shouldRejectTruncatedHeader()
        {
            final byte[] image = WadBuilder.bytes('I', 'W', 'A', 'D', 1, 0, 0);

            assertThatThrownBy(() -> WadReader.fromBytes(image))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("truncated");
        }

        @Test
        @DisplayName("an empty file is rejected as truncated")
        void shouldRejectEmptyFile()
        {
            assertThatThrownBy(() -> WadReader.fromBytes(new byte[0]))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("truncated");
        }

        @Test
        @DisplayName("a null image is rejected")
        void shouldRejectNullImage()
        {
            assertThatThrownBy(() -> WadReader.fromBytes(null))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("null");
        }

        @Test
        @DisplayName("a container whose magic is neither IWAD nor PWAD is rejected")
        void shouldRejectBadMagic()
        {
            final byte[] image = WadBuilder.iwad()
                .magic("XWAD")
                .lump("PLAYPAL", PLAYPAL)
                .build();

            assertThatThrownBy(() -> WadReader.fromBytes(image))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("Bad WAD magic");
        }

        @Test
        @DisplayName("a ZIP masquerading as a WAD is rejected on its magic")
        void shouldRejectZipMagic()
        {
            final byte[] image = WadBuilder.iwad()
                .magic("PK")
                .lump("PLAYPAL", PLAYPAL)
                .build();

            assertThatThrownBy(() -> WadReader.fromBytes(image))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("Bad WAD magic");
        }

        @Test
        @DisplayName("a directory offset past the end of the file is rejected")
        void shouldRejectDirectoryOffsetPastEof()
        {
            final byte[] image = WadBuilder.iwad()
                .lump("PLAYPAL", PLAYPAL)
                .declaredDirectoryOffset(1_000_000)
                .build();

            assertThatThrownBy(() -> WadReader.fromBytes(image))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("past EOF");
        }

        @Test
        @DisplayName("a directory offset overlapping the header is rejected")
        void shouldRejectDirectoryOffsetInsideHeader()
        {
            final byte[] image = WadBuilder.iwad()
                .lump("PLAYPAL", PLAYPAL)
                .declaredDirectoryOffset(4)
                .build();

            assertThatThrownBy(() -> WadReader.fromBytes(image))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("overlaps");
        }

        @Test
        @DisplayName("a lump count larger than the directory actually holds is rejected")
        void shouldRejectOverstatedLumpCount()
        {
            final byte[] image = WadBuilder.iwad()
                .lump("PLAYPAL", PLAYPAL)
                .declaredLumpCount(64)
                .build();

            assertThatThrownBy(() -> WadReader.fromBytes(image))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("past EOF");
        }

        @Test
        @DisplayName("a lump count that overflows int when scaled is rejected, not wrapped")
        void shouldRejectOverflowingLumpCount()
        {
            final byte[] image = WadBuilder.iwad()
                .lump("PLAYPAL", PLAYPAL)
                .declaredLumpCount(Integer.MAX_VALUE)
                .build();

            assertThatThrownBy(() -> WadReader.fromBytes(image))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("past EOF");
        }

        @Test
        @DisplayName("a truncated directory — the file cut short after the header — is rejected")
        void shouldRejectTruncatedDirectory()
        {
            final byte[] full = sampleIwad();
            final byte[] cut = java.util.Arrays.copyOf(full, full.length - WadBuilder.ENTRY_SIZE);

            assertThatThrownBy(() -> WadReader.fromBytes(cut))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("past EOF");
        }
    }

    // ===============================================================
    //  Directory and lump access
    // ===============================================================

    @Nested
    @DisplayName("Directory")
    class Directory
    {
        @Test
        @DisplayName("lump names are decoded from their NUL-padded fields")
        void shouldReadLumpNames()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.lumpName(0)).isEqualTo("PLAYPAL");
            assertThat(reader.lumpName(1)).isEqualTo("COLORMAP");
            assertThat(reader.lumpName(2)).isEqualTo("E1M1");
        }

        @Test
        @DisplayName("lump sizes and offsets describe the payload exactly")
        void shouldReadLumpSizesAndOffsets()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.lumpSize(0)).isEqualTo(PLAYPAL.length);
            assertThat(reader.lumpSize(1)).isEqualTo(COLORMAP.length);
            assertThat(reader.lumpSize(2)).isZero();
            assertThat(reader.lumpOffset(0)).isEqualTo(WadBuilder.HEADER_SIZE);
            assertThat(reader.lumpOffset(1))
                .isEqualTo(WadBuilder.HEADER_SIZE + PLAYPAL.length);
        }

        @Test
        @DisplayName("a lump payload round-trips byte for byte")
        void shouldSliceLumpBytes()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.sliceLump(0)).containsExactly(PLAYPAL);
            assertThat(reader.sliceLump(1)).containsExactly(COLORMAP);
        }

        @Test
        @DisplayName("a zero-size marker lump yields an empty payload")
        void shouldSliceMarkerLump()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.sliceLump(2)).isEmpty();
        }

        @Test
        @DisplayName("a sliced payload is a copy — mutating it cannot corrupt the image")
        void shouldReturnIndependentSlices()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            final byte[] first = reader.sliceLump(0);
            first[0] = 0x7F;

            assertThat(reader.sliceLump(0)).containsExactly(PLAYPAL);
        }

        @Test
        @DisplayName("a name lookup finds the lump regardless of the caller's casing")
        void shouldFindLumpCaseInsensitively()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.findLump("PLAYPAL")).isZero();
            assertThat(reader.findLump("playpal")).isZero();
            assertThat(reader.findLump("PlayPal")).isZero();
        }

        @Test
        @DisplayName("a name lookup tolerates a caller-supplied NUL pad")
        void shouldFindLumpWithNulPaddedKey()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.findLump("E1M1\0\0\0\0")).isEqualTo(2);
        }

        @Test
        @DisplayName("a lump that is not in the directory reports not found")
        void shouldReportLumpNotFound()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.findLump("NOSUCH")).isEqualTo(WadReader.LUMP_NOT_FOUND);
            assertThat(reader.findLump("")).isEqualTo(WadReader.LUMP_NOT_FOUND);
            assertThat(reader.findLump(null)).isEqualTo(WadReader.LUMP_NOT_FOUND);
        }

        @Test
        @DisplayName("a duplicated lump name resolves to the first definition")
        void shouldResolveDuplicateNameToFirst()
        {
            final byte[] image = WadBuilder.pwad()
                .lump("DUP", WadBuilder.bytes(1))
                .lump("DUP", WadBuilder.bytes(2, 2))
                .build();

            final WadReader reader = WadReader.fromBytes(image);

            assertThat(reader.findLump("DUP")).isZero();
            assertThat(reader.sliceLump(1)).containsExactly(WadBuilder.bytes(2, 2));
        }

        @Test
        @DisplayName("an out-of-range lump index is rejected rather than returning null")
        void shouldRejectOutOfRangeIndex()
        {
            final WadReader reader = WadReader.fromBytes(sampleIwad());

            assertThat(reader.hasLump(-1)).isFalse();
            assertThat(reader.hasLump(3)).isFalse();
            assertThatThrownBy(() -> reader.sliceLump(3))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("out of range");
            assertThatThrownBy(() -> reader.lumpName(-1))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("out of range");
        }
    }

    // ===============================================================
    //  Filesystem path
    // ===============================================================

    @Nested
    @DisplayName("Filesystem")
    class Filesystem
    {
        @Test
        @DisplayName("a synthesized WAD written to a temp directory reads back identically")
        void shouldReadWadFromDisk(@TempDir final Path tempDir) throws IOException
        {
            final Path wadFile = tempDir.resolve("synth.wad");
            Files.write(wadFile, sampleIwad());

            final WadReader reader = WadReader.fromFile(wadFile.toString());

            assertThat(reader.wadType()).isEqualTo(WadReader.WadType.IWAD);
            assertThat(reader.lumpCount()).isEqualTo(3);
            assertThat(reader.sliceLump(0)).containsExactly(PLAYPAL);
        }

        @Test
        @DisplayName("a missing file is reported as a WAD failure, not an IOException")
        void shouldRejectMissingFile(@TempDir final Path tempDir)
        {
            final String missing = tempDir.resolve("absent.wad").toString();

            assertThatThrownBy(() -> WadReader.fromFile(missing))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("Failed to read WAD file");
        }

        @Test
        @DisplayName("a null path is rejected")
        void shouldRejectNullPath()
        {
            assertThatThrownBy(() -> WadReader.fromFile(null))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("null");
        }
    }
}
