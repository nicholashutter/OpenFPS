/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.memory.factory.MemoryPortFactory;
import com.openfps.engine.memory.port.I_MemoryPort;
import com.openfps.engine.resource.WadException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the demand-loaded, reference-counted lump cache.
 *
 * The fixture WAD holds four 100-byte lumps and the cache budget is 250
 * bytes, so exactly two lumps fit at once. That makes every eviction in
 * these tests deterministic rather than incidental.
 */
class LumpCacheTest
{
    /** Payload size of every lump in the fixture. */
    private static final int LUMP_BYTES = 100;

    /** Cache budget — room for two lumps, not three. */
    private static final int BUDGET = 250;

    /** Heap given to the memory port; deliberately larger than the budget. */
    private static final int HEAP = 4096;

    /** The fixture WAD. MUTABLE: rebuilt for every test. */
    private WadReader reader;

    /** Memory port under the cache. MUTABLE: rebuilt for every test. */
    private I_MemoryPort memory;

    /** Subject under test. MUTABLE: rebuilt for every test. */
    private LumpCache cache;

    private static byte[] filled(final int fillValue)
    {
        final byte[] out = new byte[LUMP_BYTES];

        java.util.Arrays.fill(out, (byte) fillValue);

        return out;
    }

    @BeforeEach
    void setUp()
    {
        final byte[] image = WadBuilder.iwad()
            .lump("LUMPA", filled(0xA1))
            .lump("LUMPB", filled(0xB2))
            .lump("LUMPC", filled(0xC3))
            .lump("LUMPD", filled(0xD4))
            .marker("MARKER")
            .build();

        reader = WadReader.fromBytes(image);

        memory = MemoryPortFactory.createJvm(HEAP);

        memory.init(HEAP);

        cache = new LumpCache(reader, memory, BUDGET);
    }

    // ===============================================================
    //  Demand loading
    // ===============================================================

    @Test
    @DisplayName("nothing is resident before the first read")
    void shouldStartEmpty()
    {
        assertThat(cache.residentCount()).isZero();

        assertThat(cache.residentBytes()).isZero();

        assertThat(cache.isResident(0)).isFalse();

        assertThat(memory.allocatedBytes()).isZero();
    }

    @Test
    @DisplayName("a first read loads the lump and charges the memory port")
    void shouldLoadOnDemand()
    {
        final byte[] payload = cache.load(0);

        assertThat(payload).hasSize(LUMP_BYTES);

        assertThat(cache.isResident(0)).isTrue();

        assertThat(cache.residentBytes()).isEqualTo(LUMP_BYTES);

        assertThat(cache.residentCount()).isEqualTo(1);

        assertThat(memory.allocatedBytes()).isEqualTo(LUMP_BYTES);

        assertThat(memory.handleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a repeated read returns the same instance and does not reload")
    void shouldServeRepeatedReadFromCache()
    {
        final byte[] first = cache.load(0);

        final byte[] second = cache.load(0);

        assertThat(second).isSameAs(first);

        assertThat(cache.residentCount()).isEqualTo(1);

        assertThat(memory.handleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a plain read leaves the lump unpinned")
    void shouldNotPinOnPlainRead()
    {
        cache.load(0);

        assertThat(cache.refCount(0)).isZero();
    }

    @Test
    @DisplayName("a zero-size marker lump caches as an empty payload")
    void shouldCacheMarkerLump()
    {
        final byte[] payload = cache.load(4);

        assertThat(payload).isEmpty();

        assertThat(cache.isResident(4)).isTrue();

        assertThat(cache.residentBytes()).isZero();

        assertThat(cache.residentCount()).isEqualTo(1);
    }

    // ===============================================================
    //  Reference counting
    // ===============================================================

    @Test
    @DisplayName("acquire raises the reference count and release lowers it")
    void shouldCountReferences()
    {
        cache.acquire(0);

        cache.acquire(0);

        assertThat(cache.refCount(0)).isEqualTo(2);

        cache.release(0);

        assertThat(cache.refCount(0)).isEqualTo(1);

        assertThat(cache.isResident(0)).isTrue();
    }

    @Test
    @DisplayName("the last release evicts the lump and returns its bytes to the memory port")
    void shouldEvictOnFinalRelease()
    {
        cache.acquire(0);

        cache.release(0);

        assertThat(cache.isResident(0)).isFalse();

        assertThat(cache.residentBytes()).isZero();

        assertThat(cache.residentCount()).isZero();

        assertThat(memory.handleCount()).isZero();

        assertThat(memory.allocatedBytes()).isZero();
    }

    @Test
    @DisplayName("releasing a lump that was never acquired is logged, not thrown")
    void shouldIgnoreReleaseWithoutAcquire()
    {
        cache.load(0);

        cache.release(0);

        assertThat(cache.isResident(0)).isTrue();

        assertThat(cache.refCount(0)).isZero();
    }

    @Test
    @DisplayName("releasing a lump that is not resident is logged, not thrown")
    void shouldIgnoreReleaseOfAbsentLump()
    {
        cache.release(1);

        assertThat(cache.isResident(1)).isFalse();
    }

    @Test
    @DisplayName("a re-acquired lump reloads cleanly after eviction")
    void shouldReloadAfterEviction()
    {
        final byte[] first = cache.acquire(0);

        cache.release(0);

        final byte[] second = cache.acquire(0);

        assertThat(second).isNotSameAs(first).containsExactly(first);

        assertThat(cache.refCount(0)).isEqualTo(1);
    }

    // ===============================================================
    //  Bounding and eviction
    // ===============================================================

    @Test
    @DisplayName("the cache never exceeds its byte budget")
    void shouldStayWithinBudget()
    {
        cache.load(0);

        cache.load(1);

        cache.load(2);

        cache.load(3);

        assertThat(cache.residentBytes()).isLessThanOrEqualTo(BUDGET);

        assertThat(cache.residentCount()).isEqualTo(2);

        assertThat(memory.allocatedBytes()).isEqualTo(cache.residentBytes());
    }

    @Test
    @DisplayName("making room evicts the least recently used unpinned lump")
    void shouldEvictLeastRecentlyUsed()
    {
        cache.load(0);

        cache.load(1);

        cache.load(0);   // lump 0 is now more recent than lump 1

        cache.load(2);

        assertThat(cache.isResident(1)).isFalse();

        assertThat(cache.isResident(0)).isTrue();

        assertThat(cache.isResident(2)).isTrue();
    }

    @Test
    @DisplayName("a pinned lump is never chosen as an eviction victim")
    void shouldNeverEvictPinnedLump()
    {
        cache.acquire(0);

        cache.load(1);

        cache.load(2);

        cache.load(3);

        assertThat(cache.isResident(0)).isTrue();

        assertThat(cache.refCount(0)).isEqualTo(1);

        assertThat(cache.residentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a load fails loudly when every resident lump is pinned")
    void shouldFailWhenAllResidentLumpsArePinned()
    {
        cache.acquire(0);

        cache.acquire(1);

        assertThatThrownBy(() -> cache.acquire(2))
            .isInstanceOf(WadException.class)
            .hasMessageContaining("budget exhausted");
    }

    @Test
    @DisplayName("a lump larger than the whole budget is rejected rather than thrashing")
    void shouldRejectLumpLargerThanBudget()
    {
        final byte[] image = WadBuilder.iwad()
            .lump("HUGE", new byte[BUDGET + 1])
            .build();

        final LumpCache tinyCache = new LumpCache(WadReader.fromBytes(image), memory, BUDGET);

        assertThatThrownBy(() -> tinyCache.load(0))
            .isInstanceOf(WadException.class)
            .hasMessageContaining("larger than the whole cache budget");
    }

    @Test
    @DisplayName("a buffer already handed out stays valid after its entry is evicted")
    void shouldKeepHandedOutBufferValidAfterEviction()
    {
        final byte[] held = cache.load(0);

        cache.load(1);

        cache.load(2);

        assertThat(cache.isResident(0)).isFalse();

        assertThat(held).hasSize(LUMP_BYTES).containsOnly((byte) 0xA1);
    }

    // ===============================================================
    //  Flush
    // ===============================================================

    @Test
    @DisplayName("flush drops every lump, pinned or not, and frees all handles")
    void shouldFlushEverything()
    {
        cache.acquire(0);

        cache.load(1);

        cache.flush();

        assertThat(cache.residentCount()).isZero();

        assertThat(cache.residentBytes()).isZero();

        assertThat(cache.refCount(0)).isZero();

        assertThat(memory.handleCount()).isZero();

        assertThat(memory.allocatedBytes()).isZero();
    }

    @Test
    @DisplayName("flushing an empty cache is a no-op")
    void shouldFlushEmptyCache()
    {
        cache.flush();

        assertThat(cache.residentCount()).isZero();
    }

    // ===============================================================
    //  Construction and bounds
    // ===============================================================

    @Test
    @DisplayName("an out-of-range lump index is rejected")
    void shouldRejectOutOfRangeIndex()
    {
        assertThatThrownBy(() -> cache.load(99))
            .isInstanceOf(WadException.class)
            .hasMessageContaining("out of range");

        assertThatThrownBy(() -> cache.release(-1))
            .isInstanceOf(WadException.class)
            .hasMessageContaining("out of range");
    }

    @Test
    @DisplayName("a cache cannot be built without a reader, a memory port or a budget")
    void shouldRejectBadConstruction()
    {
        assertThatThrownBy(() -> new LumpCache(null, memory, BUDGET))
            .isInstanceOf(WadException.class);

        assertThatThrownBy(() -> new LumpCache(reader, null, BUDGET))
            .isInstanceOf(WadException.class);

        assertThatThrownBy(() -> new LumpCache(reader, memory, 0))
            .isInstanceOf(WadException.class);
    }

    @Test
    @DisplayName("a memory port that refuses the allocation surfaces as a WAD failure")
    void shouldSurfaceMemoryPortRefusal()
    {
        final I_MemoryPort tinyHeap = MemoryPortFactory.createJvm(LUMP_BYTES / 2);

        tinyHeap.init(LUMP_BYTES / 2);

        final LumpCache starved = new LumpCache(reader, tinyHeap, BUDGET);

        assertThatThrownBy(() -> starved.load(0))
            .isInstanceOf(WadException.class)
            .hasMessageContaining("Memory port refused");
    }
}
