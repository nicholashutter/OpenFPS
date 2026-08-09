/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.memory.factory.MemoryPortFactory;
import com.openfps.engine.memory.port.I_MemoryPort;
import com.openfps.engine.resource.WadException;
import com.openfps.engine.resource.port.I_WadPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Tests for the real I_WadPort implementation.
 *
 * The port is exercised entirely against in-memory containers except for
 * the two tests that must cover {@code open(String)}; those write a
 * synthesized image into a JUnit temporary directory.
 */
class WadFilePortTest
{
    /** Cache budget for the fixture port. */
    private static final int BUDGET = 4096;

    /** Payload of the PLAYPAL fixture lump. */
    private static final byte[] PLAYPAL = WadBuilder.bytes(0x10, 0x20, 0x30);

    /** Payload of the THINGS fixture lump. */
    private static final byte[] THINGS = WadBuilder.words(1056, -3616, 90, 1, 7);

    /** Memory port under the fixture. MUTABLE: rebuilt for every test. */
    private I_MemoryPort memory;

    /** Subject under test. MUTABLE: rebuilt for every test. */
    private WadFilePort port;

    private static byte[] sampleIwad()
    {
        return WadBuilder.iwad()
            .lump("PLAYPAL", PLAYPAL)
            .marker("E1M1")
            .lump("THINGS", THINGS)
            .build();
    }

    @BeforeEach
    void setUp()
    {
        memory = MemoryPortFactory.createJvm(BUDGET);

        port = new WadFilePort(memory, BUDGET);

        port.init();
    }

    @AfterEach
    void tearDown()
    {
        if (memory.state() != I_MemoryPort.State.SHUTDOWN)
        {
            port.shutdown();
        }
    }

    // ===============================================================
    //  Lifecycle
    // ===============================================================

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle
    {
        @Test
        @DisplayName("a freshly initialized port reports no WAD open")
        void shouldReportNoWadOpen()
        {
            assertThat(port.lumpCount()).isEqualTo(-1);

            assertThat(port.wadType()).isNull();

            assertThat(port.readLump(0)).isNull();

            assertThat(port.readLump("PLAYPAL")).isNull();
        }

        @Test
        @DisplayName("init() arms the memory port")
        void shouldInitializeMemoryPort()
        {
            assertThat(memory.state()).isEqualTo(I_MemoryPort.State.READY);
        }

        @Test
        @DisplayName("a second init() is ignored rather than throwing")
        void shouldIgnoreSecondInit()
        {
            port.init();

            assertThat(memory.state()).isEqualTo(I_MemoryPort.State.READY);
        }

        @Test
        @DisplayName("opening before init() is rejected")
        void shouldRejectOpenBeforeInit()
        {
            final WadFilePort unarmed = new WadFilePort(MemoryPortFactory.createJvm(BUDGET),
                BUDGET);

            assertThatThrownBy(() -> unarmed.openInMemory(sampleIwad(), "test"))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("called before init()");
        }

        @Test
        @DisplayName("shutdown() closes the WAD and shuts the memory port down")
        void shouldShutDown()
        {
            port.openInMemory(sampleIwad(), "test");

            port.readLump(0);

            port.shutdown();

            assertThat(port.lumpCount()).isEqualTo(-1);

            assertThat(memory.state()).isEqualTo(I_MemoryPort.State.SHUTDOWN);
        }

        @Test
        @DisplayName("a port cannot be built without a memory port or a positive budget")
        void shouldRejectBadConstruction()
        {
            assertThatThrownBy(() -> new WadFilePort(null, BUDGET))
                .isInstanceOf(WadException.class);

            assertThatThrownBy(() -> new WadFilePort(MemoryPortFactory.createJvm(BUDGET), -1))
                .isInstanceOf(WadException.class);
        }

        @Test
        @DisplayName("the port satisfies the I_WadPort contract it is declared against")
        void shouldSatisfyPortInterface()
        {
            final I_WadPort asPort = port;

            assertThat(asPort.lumpCount()).isEqualTo(-1);

            assertThat(asPort.open("no/such/file.wad")).isFalse();

            asPort.flushCache();

            asPort.close();
        }
    }

    // ===============================================================
    //  Opening
    // ===============================================================

    @Nested
    @DisplayName("Opening")
    class Opening
    {
        @Test
        @DisplayName("a valid IWAD opens and reports its lumps")
        void shouldOpenIwad()
        {
            assertThat(port.openInMemory(sampleIwad(), "sample")).isTrue();

            assertThat(port.wadType()).isEqualTo(WadReader.WadType.IWAD);

            assertThat(port.lumpCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a valid PWAD opens and reports its lumps")
        void shouldOpenPwad()
        {
            final byte[] image = WadBuilder.pwad()
                .lump("MAP01", WadBuilder.bytes(9, 9, 9))
                .build();

            assertThat(port.openInMemory(image, "patch")).isTrue();

            assertThat(port.wadType()).isEqualTo(WadReader.WadType.PWAD);

            assertThat(port.lumpCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a bad magic fails the open instead of throwing out of the port")
        void shouldFailOpenOnBadMagic()
        {
            final byte[] image = WadBuilder.iwad()
                .magic("XWAD")
                .lump("PLAYPAL", PLAYPAL)
                .build();

            assertThat(port.openInMemory(image, "corrupt")).isFalse();

            assertThat(port.lumpCount()).isEqualTo(-1);
        }

        @Test
        @DisplayName("a truncated image fails the open")
        void shouldFailOpenOnTruncatedImage()
        {
            assertThat(port.openInMemory(WadBuilder.bytes('I', 'W', 'A'), "stub")).isFalse();

            assertThat(port.lumpCount()).isEqualTo(-1);
        }

        @Test
        @DisplayName("a directory offset past EOF fails the open")
        void shouldFailOpenOnDirectoryPastEof()
        {
            final byte[] image = WadBuilder.iwad()
                .lump("PLAYPAL", PLAYPAL)
                .declaredDirectoryOffset(999_999)
                .build();

            assertThat(port.openInMemory(image, "corrupt")).isFalse();
        }

        @Test
        @DisplayName("opening a second WAD closes the first and frees its cache")
        void shouldCloseFirstWadWhenOpeningSecond()
        {
            port.openInMemory(sampleIwad(), "first");

            port.precacheLump(0);

            assertThat(port.cachedLumps()).isEqualTo(1);

            port.openInMemory(WadBuilder.pwad().lump("ONLY", WadBuilder.bytes(1)).build(),
                "second");

            assertThat(port.lumpCount()).isEqualTo(1);

            assertThat(port.cachedLumps()).isZero();

            assertThat(memory.handleCount()).isZero();
        }

        @Test
        @DisplayName("closing with no WAD open is a no-op")
        void shouldTolerateRedundantClose()
        {
            port.close();

            port.close();

            assertThat(port.lumpCount()).isEqualTo(-1);
        }
    }

    // ===============================================================
    //  Reading
    // ===============================================================

    @Nested
    @DisplayName("Reading")
    class Reading
    {
        @BeforeEach
        void openSample()
        {
            port.openInMemory(sampleIwad(), "sample");
        }

        @Test
        @DisplayName("a lump read by index returns its exact bytes")
        void shouldReadLumpByIndex()
        {
            assertThat(port.readLump(0)).containsExactly(PLAYPAL);

            assertThat(port.readLump(2)).containsExactly(THINGS);
        }

        @Test
        @DisplayName("a lump read by name returns its exact bytes")
        void shouldReadLumpByName()
        {
            assertThat(port.readLump("PLAYPAL")).containsExactly(PLAYPAL);

            assertThat(port.readLump("things")).containsExactly(THINGS);
        }

        @Test
        @DisplayName("a name that is not in the directory reads as null")
        void shouldReturnNullForUnknownName()
        {
            assertThat(port.readLump("NOSUCH")).isNull();

            assertThat(port.findLump("NOSUCH")).isEqualTo(WadReader.LUMP_NOT_FOUND);
        }

        @Test
        @DisplayName("an out-of-range index reads as null rather than throwing")
        void shouldReturnNullForOutOfRangeIndex()
        {
            assertThat(port.readLump(-1)).isNull();

            assertThat(port.readLump(3)).isNull();
        }

        @Test
        @DisplayName("a marker lump reads as an empty payload, not null")
        void shouldReadMarkerLumpAsEmpty()
        {
            assertThat(port.readLump("E1M1")).isEmpty();
        }

        @Test
        @DisplayName("a repeated read is served from the cache")
        void shouldServeRepeatedReadFromCache()
        {
            final byte[] first = port.readLump(0);

            final byte[] second = port.readLump("PLAYPAL");

            assertThat(second).isSameAs(first);

            assertThat(port.cachedLumps()).isEqualTo(1);
        }
    }

    // ===============================================================
    //  Caching
    // ===============================================================

    @Nested
    @DisplayName("Caching")
    class Caching
    {
        @BeforeEach
        void openSample()
        {
            port.openInMemory(sampleIwad(), "sample");
        }

        @Test
        @DisplayName("precacheLump makes a lump resident and charges the memory port")
        void shouldPrecacheLump()
        {
            port.precacheLump(0);

            assertThat(port.cachedLumps()).isEqualTo(1);

            assertThat(port.cachedBytes()).isEqualTo(PLAYPAL.length);

            assertThat(memory.allocatedBytes()).isEqualTo(PLAYPAL.length);
        }

        @Test
        @DisplayName("releaseLump drops the last reference and evicts the lump")
        void shouldReleasePrecachedLump()
        {
            port.precacheLump(0);

            port.releaseLump(0);

            assertThat(port.cachedLumps()).isZero();

            assertThat(memory.handleCount()).isZero();
        }

        @Test
        @DisplayName("flushCache drops every resident lump")
        void shouldFlushCache()
        {
            port.precacheLump(0);

            port.readLump(2);

            assertThat(port.cachedLumps()).isEqualTo(2);

            port.flushCache();

            assertThat(port.cachedLumps()).isZero();

            assertThat(port.cachedBytes()).isZero();

            assertThat(memory.allocatedBytes()).isZero();
        }

        @Test
        @DisplayName("precaching an out-of-range index is logged, not thrown")
        void shouldIgnoreOutOfRangePrecache()
        {
            port.precacheLump(99);

            port.releaseLump(99);

            assertThat(port.cachedLumps()).isZero();
        }

        @Test
        @DisplayName("cache operations with no WAD open are logged, not thrown")
        void shouldTolerateCacheCallsWithNoWad()
        {
            port.close();

            port.precacheLump(0);

            port.releaseLump(0);

            port.flushCache();

            assertThat(port.cachedLumps()).isZero();

            assertThat(port.cachedBytes()).isZero();
        }
    }

    // ===============================================================
    //  Filesystem
    // ===============================================================

    @Nested
    @DisplayName("Filesystem")
    class Filesystem
    {
        @Test
        @DisplayName("open() reads a synthesized WAD written to a temp directory")
        void shouldOpenWadFromDisk(@TempDir final Path tempDir) throws IOException
        {
            final Path wadFile = tempDir.resolve("synth.wad");

            Files.write(wadFile, sampleIwad());

            assertThat(port.open(wadFile.toString())).isTrue();

            assertThat(port.lumpCount()).isEqualTo(3);

            assertThat(port.readLump("PLAYPAL")).containsExactly(PLAYPAL);
        }

        @Test
        @DisplayName("open() of a missing file returns false rather than throwing")
        void shouldFailOpenOfMissingFile(@TempDir final Path tempDir)
        {
            assertThat(port.open(tempDir.resolve("absent.wad").toString())).isFalse();

            assertThat(port.lumpCount()).isEqualTo(-1);
        }
    }
}
