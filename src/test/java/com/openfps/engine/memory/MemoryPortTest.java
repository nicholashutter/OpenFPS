/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory;

import com.openfps.engine.memory.adapter.JvmMemoryPort;
import com.openfps.engine.memory.adapter.ZoneMemoryPort;
import com.openfps.engine.memory.port.I_MemoryPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for the memory port — both backends.
 *
 *   positive:  allocate / free / reset all work in the happy path
 *   negative:  invalid arguments throw, invalid state throws
 *   random:    stress with random alloc/free patterns
 *   overflow:  OOM returns NULL_HANDLE, state stays ACTIVE
 *   underflow: double-free throws, free of unknown handle throws
 *
 * Each test is run against both backends (JvmMemoryPort and
 * ZoneMemoryPort) via the parameterized helper below.
 */
class MemoryPortTest
{
    /** Test heap is 16 KB — large enough that both JVM and Zone
     *  backends have realistic behavior. Zone port reserves 8-byte
     *  headers per allocation so 4 KB wouldn't give meaningful tests. */
    private static final int TEST_HEAP_SIZE = 16384;

    /** Returns a fresh, initialized port for the given backend. */
    private static I_MemoryPort freshPort(final Backend backend)
    {
        final I_MemoryPort port = switch (backend)
        {
            case JVM  -> new JvmMemoryPort();
            case ZONE -> new ZoneMemoryPort();
        };
        port.init(TEST_HEAP_SIZE);
        return port;
    }

    enum Backend
    {
        JVM,
        ZONE
    }

    /**
     * Run the same test logic against both backends.
     * The lambda receives a freshly-initialized port and should make
     * no assumption about which backend it is.
     */
    private void forBothBackends(final java.util.function.Consumer<I_MemoryPort> test)
    {
        for (final Backend b : Backend.values())
        {
            final I_MemoryPort port = freshPort(b);
            try
            {
                test.accept(port);
            }
            finally
            {
                if (port.state() != I_MemoryPort.State.SHUTDOWN
                    && port.state() != I_MemoryPort.State.UNINITIALIZED)
                {
                    port.shutdown();
                }
            }
        }
    }

    // ===============================================================
    //  State machine
    // ===============================================================

    @Nested
    @DisplayName("State machine")
    class StateMachine
    {
        @Test
        @DisplayName("newly constructed port is UNINITIALIZED")
        void shouldStartUninitialized()
        {
            assertThat(new JvmMemoryPort().state()).isEqualTo(I_MemoryPort.State.UNINITIALIZED);
            assertThat(new ZoneMemoryPort().state()).isEqualTo(I_MemoryPort.State.UNINITIALIZED);
        }

        @Test
        @DisplayName("init() moves UNINITIALIZED → READY")
        void shouldMoveToReadyAfterInit()
        {
            forBothBackends(port -> {
                assertThat(port.state()).isEqualTo(I_MemoryPort.State.READY);
            });
        }

        @Test
        @DisplayName("first allocate() moves READY → ACTIVE")
        void shouldMoveToActiveOnFirstAllocate()
        {
            forBothBackends(port -> {
                final int h = port.allocate(16, I_MemoryPort.TAG_STATIC);
                assertThat(h).isNotEqualTo(I_MemoryPort.NULL_HANDLE);
                assertThat(port.state()).isEqualTo(I_MemoryPort.State.ACTIVE);
            });
        }

        @Test
        @DisplayName("shutdown() moves to SHUTDOWN (terminal)")
        void shouldShutdown()
        {
            forBothBackends(port -> {
                port.shutdown();
                assertThat(port.state()).isEqualTo(I_MemoryPort.State.SHUTDOWN);
            });
        }

        @Test
        @DisplayName("reset() moves ACTIVE → READY and clears allocations")
        void shouldReset()
        {
            forBothBackends(port -> {
                port.allocate(64, I_MemoryPort.TAG_GAME);
                port.allocate(128, I_MemoryPort.TAG_GAME);
                assertThat(port.handleCount()).isEqualTo(2);

                port.reset();
                assertThat(port.state()).isEqualTo(I_MemoryPort.State.READY);
                assertThat(port.handleCount()).isEqualTo(0);
                assertThat(port.allocatedBytes()).isEqualTo(0);
            });
        }

        @Test
        @DisplayName("init() twice throws — state machine rejects")
        void shouldThrowOnDoubleInit()
        {
            forBothBackends(port -> {
                assertThatThrownBy(() -> port.init(1024))
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("UNINITIALIZED");
            });
        }

        @Test
        @DisplayName("allocate() before init() throws")
        void shouldThrowOnAllocateBeforeInit()
        {
            final I_MemoryPort port = new JvmMemoryPort();
            assertThatThrownBy(() -> port.allocate(64, I_MemoryPort.TAG_STATIC))
                .isInstanceOf(MemoryException.class)
                .hasMessageContaining("UNINITIALIZED");
        }

        @Test
        @DisplayName("allocate() after shutdown() throws")
        void shouldThrowOnAllocateAfterShutdown()
        {
            forBothBackends(port -> {
                port.shutdown();
                assertThatThrownBy(() -> port.allocate(64, I_MemoryPort.TAG_STATIC))
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("SHUTDOWN");
            });
        }

        @Test
        @DisplayName("free() before any allocation throws")
        void shouldThrowOnFreeBeforeFirstAllocate()
        {
            forBothBackends(port -> {
                assertThatThrownBy(() -> port.free(0))
                    .isInstanceOf(MemoryException.class);
            });
        }

        @Test
        @DisplayName("double shutdown() throws")
        void shouldThrowOnDoubleShutdown()
        {
            forBothBackends(port -> {
                port.shutdown();
                assertThatThrownBy(port::shutdown)
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("SHUTDOWN");
            });
        }

        @Test
        @DisplayName("reset() after shutdown() throws")
        void shouldThrowOnResetAfterShutdown()
        {
            forBothBackends(port -> {
                port.shutdown();
                assertThatThrownBy(port::reset)
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("SHUTDOWN");
            });
        }

        @Test
        @DisplayName("init() with zero or negative size throws")
        void shouldThrowOnBadInitSize()
        {
            final I_MemoryPort jvm = new JvmMemoryPort();
            assertThatThrownBy(() -> jvm.init(0))
                .isInstanceOf(MemoryException.class);
            assertThatThrownBy(() -> jvm.init(-1))
                .isInstanceOf(MemoryException.class);

            final I_MemoryPort zone = new ZoneMemoryPort();
            assertThatThrownBy(() -> zone.init(0))
                .isInstanceOf(MemoryException.class);
            assertThatThrownBy(() -> zone.init(-1))
                .isInstanceOf(MemoryException.class);
        }
    }

    // ===============================================================
    //  Positive — happy path
    // ===============================================================

    @Nested
    @DisplayName("Positive — happy path")
    class Positive
    {
        @Test
        @DisplayName("allocate returns a valid handle and tracks size")
        void shouldAllocateAndTrack()
        {
            forBothBackends(port -> {
                final int h = port.allocate(128, I_MemoryPort.TAG_GAME);
                assertThat(h).isNotEqualTo(I_MemoryPort.NULL_HANDLE);
                assertThat(port.handleCount()).isEqualTo(1);
                assertThat(port.sizeOf(h)).isEqualTo(128);
                assertThat(port.allocatedBytes()).isGreaterThanOrEqualTo(128);
            });
        }

        @Test
        @DisplayName("free() releases the handle and decrements count")
        void shouldFreeHandle()
        {
            forBothBackends(port -> {
                final int h1 = port.allocate(64, I_MemoryPort.TAG_GAME);
                final int h2 = port.allocate(64, I_MemoryPort.TAG_GAME);
                assertThat(port.handleCount()).isEqualTo(2);

                port.free(h1);
                assertThat(port.handleCount()).isEqualTo(1);
                assertThat(port.sizeOf(h1)).isEqualTo(0);
                assertThat(port.sizeOf(h2)).isEqualTo(64);
            });
        }

        @Test
        @DisplayName("multiple allocations get distinct handles")
        void shouldAllocateDistinctHandles()
        {
            forBothBackends(port -> {
                final int n = 50;
                final Set<Integer> handles = new HashSet<>();
                for (int i = 0; i < n; i++)
                {
                    final int h = port.allocate(8, I_MemoryPort.TAG_STATIC);
                    assertThat(h).isNotEqualTo(I_MemoryPort.NULL_HANDLE);
                    assertThat(handles).doesNotContain(h);
                    handles.add(h);
                }
                assertThat(handles).hasSize(n);
                assertThat(port.handleCount()).isEqualTo(n);
            });
        }

        @Test
        @DisplayName("freeByTag() removes all allocations of that tag")
        void shouldFreeByTag()
        {
            forBothBackends(port -> {
                final int a1 = port.allocate(32, I_MemoryPort.TAG_GAME);
                final int a2 = port.allocate(32, I_MemoryPort.TAG_GAME);
                final int a3 = port.allocate(32, I_MemoryPort.TAG_CACHE);

                final int freed = port.freeByTag(I_MemoryPort.TAG_GAME);
                assertThat(freed).isEqualTo(2);
                assertThat(port.handleCount()).isEqualTo(1);
                assertThat(port.sizeOf(a3)).isEqualTo(32);
                assertThat(port.sizeOf(a1)).isEqualTo(0);
                assertThat(port.sizeOf(a2)).isEqualTo(0);
            });
        }

        @Test
        @DisplayName("size = 0 is a valid request (no-op allocation)")
        void shouldAllowZeroSize()
        {
            forBothBackends(port -> {
                final int h = port.allocate(0, I_MemoryPort.TAG_STATIC);
                assertThat(h).isNotEqualTo(I_MemoryPort.NULL_HANDLE);
                assertThat(port.sizeOf(h)).isEqualTo(0);
            });
        }
    }

    // ===============================================================
    //  Negative — invalid arguments and operations
    // ===============================================================

    @Nested
    @DisplayName("Negative — invalid arguments")
    class Negative
    {
        @Test
        @DisplayName("negative size throws")
        void shouldThrowOnNegativeSize()
        {
            forBothBackends(port -> {
                assertThatThrownBy(() -> port.allocate(-1, I_MemoryPort.TAG_STATIC))
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("sizeBytes");
            });
        }

        @Test
        @DisplayName("tag out of range throws")
        void shouldThrowOnBadTag()
        {
            forBothBackends(port -> {
                assertThatThrownBy(() -> port.allocate(64, -1))
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("tag");
                assertThatThrownBy(() -> port.allocate(64, 256))
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("tag");
            });
        }

        @Test
        @DisplayName("free of negative handle throws")
        void shouldThrowOnFreeNegativeHandle()
        {
            forBothBackends(port -> {
                port.allocate(64, I_MemoryPort.TAG_STATIC);  // get to ACTIVE
                assertThatThrownBy(() -> port.free(-1))
                    .isInstanceOf(MemoryException.class);
            });
        }

        @Test
        @DisplayName("free of out-of-range handle throws")
        void shouldThrowOnFreeOutOfRangeHandle()
        {
            forBothBackends(port -> {
                port.allocate(64, I_MemoryPort.TAG_STATIC);
                assertThatThrownBy(() -> port.free(99999))
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("out of range");
            });
        }

        @Test
        @DisplayName("double-free throws — no silent failures")
        void shouldThrowOnDoubleFree()
        {
            forBothBackends(port -> {
                final int h = port.allocate(64, I_MemoryPort.TAG_GAME);
                port.free(h);
                assertThatThrownBy(() -> port.free(h))
                    .isInstanceOf(MemoryException.class)
                    .hasMessageContaining("not currently allocated");
            });
        }

        @Test
        @DisplayName("sizeOf of invalid handle returns 0, not exception")
        void shouldReturnZeroForSizeOfInvalidHandle()
        {
            forBothBackends(port -> {
                port.allocate(64, I_MemoryPort.TAG_GAME);
                assertThat(port.sizeOf(-1)).isEqualTo(0);
                assertThat(port.sizeOf(99999)).isEqualTo(0);
            });
        }
    }

    // ===============================================================
    //  Overflow — OOM
    // ===============================================================

    @Nested
    @DisplayName("Overflow — OOM")
    class Overflow
    {
        @Test
        @DisplayName("single allocation larger than heap returns NULL_HANDLE")
        void shouldReturnNullOnOversizeAllocate()
        {
            forBothBackends(port -> {
                final int h = port.allocate(TEST_HEAP_SIZE * 2, I_MemoryPort.TAG_STATIC);
                assertThat(h).isEqualTo(I_MemoryPort.NULL_HANDLE);
                assertThat(port.handleCount()).isEqualTo(0);
            });
        }

        @Test
        @DisplayName("cumulative allocations that exceed heap return NULL_HANDLE")
        void shouldReturnNullOnCumulativeOverflow()
        {
            forBothBackends(port -> {
                // Use a fill size that leaves room for at most one more alloc
                // (accounts for both backends: JVM has no overhead, zone has 8 bytes/alloc)
                final int fillSize = TEST_HEAP_SIZE - 1024;
                int h = port.allocate(fillSize, I_MemoryPort.TAG_GAME);
                assertThat(h).isNotEqualTo(I_MemoryPort.NULL_HANDLE);
                // Now try to allocate 2 KB more — must overflow for both backends
                h = port.allocate(2048, I_MemoryPort.TAG_GAME);
                assertThat(h).isEqualTo(I_MemoryPort.NULL_HANDLE);
            });
        }

        @Test
        @DisplayName("after OOM, allocator remains usable for smaller allocations")
        void shouldRemainUsableAfterOom()
        {
            forBothBackends(port -> {
                // Use a size that's larger than the heap's usable space
                // (accounting for zone-port header overhead, which is 8 bytes
                // per allocation, but for the JVM port this is just payload)
                final int fillSize = TEST_HEAP_SIZE - 64;  // leaves a tiny margin
                port.allocate(fillSize, I_MemoryPort.TAG_GAME);
                final int big = port.allocate(1024, I_MemoryPort.TAG_GAME);
                assertThat(big).isEqualTo(I_MemoryPort.NULL_HANDLE);

                // Free everything, then try a small one
                port.reset();
                final int small = port.allocate(64, I_MemoryPort.TAG_GAME);
                assertThat(small).isNotEqualTo(I_MemoryPort.NULL_HANDLE);
            });
        }
    }

    // ===============================================================
    //  Underflow — sub-boundary conditions
    // ===============================================================

    @Nested
    @DisplayName("Underflow — sub-boundary conditions")
    class Underflow
    {
        @Test
        @DisplayName("freeByTag on tag with no allocations is a no-op (returns 0)")
        void shouldFreeByUnknownTag()
        {
            forBothBackends(port -> {
                port.allocate(64, I_MemoryPort.TAG_GAME);
                final int freed = port.freeByTag(I_MemoryPort.TAG_CACHE);
                assertThat(freed).isEqualTo(0);
                assertThat(port.handleCount()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("repeated reset() on empty allocator does not throw")
        void shouldAllowRepeatedReset()
        {
            forBothBackends(port -> {
                port.reset();
                port.reset();
                assertThat(port.state()).isEqualTo(I_MemoryPort.State.READY);
            });
        }

        @Test
        @DisplayName("free of all → state still ACTIVE (not auto-shutdown)")
        void shouldNotShutdownWhenAllFreed()
        {
            forBothBackends(port -> {
                final int h1 = port.allocate(64, I_MemoryPort.TAG_GAME);
                final int h2 = port.allocate(64, I_MemoryPort.TAG_GAME);
                port.free(h1);
                port.free(h2);
                assertThat(port.handleCount()).isEqualTo(0);
                assertThat(port.state()).isEqualTo(I_MemoryPort.State.ACTIVE);

                // Still usable
                final int h3 = port.allocate(64, I_MemoryPort.TAG_GAME);
                assertThat(h3).isNotEqualTo(I_MemoryPort.NULL_HANDLE);
            });
        }
    }

    // ===============================================================
    //  Random — stress
    // ===============================================================

    @Nested
    @DisplayName("Random — stress")
    class RandomStress
    {
        @Test
        @DisplayName("1000 random alloc/free cycles never produce a NULL_HANDLE mid-run")
        void shouldSurviveRandomAllocFreeCycles()
        {
            forBothBackends(port -> {
                final Random rng = new Random(0xC0FFEE);
                final List<Integer> liveHandles = new ArrayList<>();

                for (int iter = 0; iter < 1000; iter++)
                {
                    final int choice = rng.nextInt(3);
                    if (choice == 0 || liveHandles.isEmpty())
                    {
                        final int size = 16 + rng.nextInt(256);
                        // Use the port's own maxAllocatable() so we don't have to
                        // account for backend-specific overhead (zone has 8 bytes/alloc header)
                        final boolean shouldFit = port.maxAllocatable() >= size;
                        final int h = port.allocate(size, I_MemoryPort.TAG_GAME);

                        if (shouldFit)
                        {
                            assertThat(h).as("alloc size=%d iter=%d freeBytes=%d",
                                size, iter, port.freeBytes())
                                .isNotEqualTo(I_MemoryPort.NULL_HANDLE);
                            liveHandles.add(h);
                        }
                        else
                        {
                            assertThat(h).as("OOM at size=%d iter=%d freeBytes=%d",
                                size, iter, port.freeBytes())
                                .isEqualTo(I_MemoryPort.NULL_HANDLE);
                        }
                    }
                    else if (choice == 1)
                    {
                        // free a random live handle
                        final int idx = rng.nextInt(liveHandles.size());
                        final int h = liveHandles.remove(idx);
                        port.free(h);
                    }
                    else
                    {
                        // freeByTag
                        port.freeByTag(I_MemoryPort.TAG_GAME);
                        liveHandles.clear();
                    }
                }
            });
        }

        @Test
        @DisplayName("10000 random alloc-only runs respect capacity")
        void shouldRespectCapacityInRandomAllocs()
        {
            forBothBackends(port -> {
                final Random rng = new Random(0xDEADBEEFL);
                int successfulAllocs = 0;
                int failedAllocs = 0;
                int totalBytes = 0;

                for (int i = 0; i < 10_000; i++)
                {
                    final int size = 1 + rng.nextInt(128);
                    final int h = port.allocate(size, I_MemoryPort.TAG_STATIC);
                    if (h != I_MemoryPort.NULL_HANDLE)
                    {
                        successfulAllocs++;
                        totalBytes += size;
                    }
                    else
                    {
                        failedAllocs++;
                        // Verify we don't blow past capacity
                        assertThat(port.allocatedBytes())
                            .isLessThanOrEqualTo(TEST_HEAP_SIZE);
                    }
                }
                assertThat(successfulAllocs).isGreaterThan(0);
                assertThat(totalBytes).isLessThanOrEqualTo(TEST_HEAP_SIZE);
            });
        }
    }

    // ===============================================================
    //  Tag-based behavior
    // ===============================================================

    @Nested
    @DisplayName("Tag behavior")
    class TagBehavior
    {
        @Test
        @DisplayName("freeByTag affects only matching allocations")
        void shouldIsolateTags()
        {
            forBothBackends(port -> {
                final int g1 = port.allocate(64, I_MemoryPort.TAG_GAME);
                final int g2 = port.allocate(64, I_MemoryPort.TAG_GAME);
                final int c1 = port.allocate(64, I_MemoryPort.TAG_CACHE);
                final int s1 = port.allocate(64, I_MemoryPort.TAG_STATIC);

                final int freed = port.freeByTag(I_MemoryPort.TAG_GAME);
                assertThat(freed).isEqualTo(2);
                assertThat(port.sizeOf(g1)).isEqualTo(0);
                assertThat(port.sizeOf(g2)).isEqualTo(0);
                assertThat(port.sizeOf(c1)).isEqualTo(64);
                assertThat(port.sizeOf(s1)).isEqualTo(64);
            });
        }

        @Test
        @DisplayName("all four tags can coexist")
        void shouldHandleAllTags()
        {
            forBothBackends(port -> {
                for (int tag = 0; tag <= 3; tag++)
                {
                    final int h = port.allocate(64, tag);
                    assertThat(h).isNotEqualTo(I_MemoryPort.NULL_HANDLE);
                }
                assertThat(port.handleCount()).isEqualTo(4);
            });
        }

        @ParameterizedTest
        @EnumSource(value = I_MemoryPort.State.class,
            names = {"UNINITIALIZED", "SHUTDOWN"})
        @DisplayName("freeByTag rejects invalid states")
        void shouldRejectFreeByTagInBadState(final I_MemoryPort.State badState)
        {
            final I_MemoryPort port = new JvmMemoryPort();
            if (badState == I_MemoryPort.State.SHUTDOWN)
            {
                port.init(1024);
                port.shutdown();
            }
            assertThatThrownBy(() -> port.freeByTag(I_MemoryPort.TAG_GAME))
                .isInstanceOf(MemoryException.class);
        }
    }
}
