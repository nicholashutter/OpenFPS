/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.hal.adapter.nulladapter.NullSystemInfoPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ThreadPoolFactory}'s sizing rule.
 *
 * <b>Nothing here asserts a number for the machine it runs on.</b> The rule is
 * arithmetic on a processor count, so every case states the count it is asking
 * about; the two tests that do consult the real machine assert only properties
 * that hold at any core count. A test that hard-coded "eleven workers" would
 * pass on the author's laptop and fail on CI, which is precisely the class of
 * assumption this change exists to remove.
 */
class ThreadPoolFactoryTest
{
    /** Saved so a test that sets the override cannot leak it into the next one. */
    private String savedProperty;

    @AfterEach
    void restoreProperty()
    {
        if (savedProperty == null)
        {
            System.clearProperty(ThreadPoolFactory.WORKER_COUNT_PROPERTY);
        }
        else
        {
            System.setProperty(ThreadPoolFactory.WORKER_COUNT_PROPERTY, savedProperty);
        }
        savedProperty = null;
    }

    // Sets the override for one test, remembering whatever was there before.
    private void pinProperty(final String value)
    {
        savedProperty = System.getProperty(ThreadPoolFactory.WORKER_COUNT_PROPERTY);
        System.setProperty(ThreadPoolFactory.WORKER_COUNT_PROPERTY, value);
    }

    @Nested
    @DisplayName("the automatic rule")
    class AutomaticRule
    {
        @ParameterizedTest
        @CsvSource({"2, 1", "3, 2", "4, 3", "8, 7", "12, 11", "22, 21", "64, 63", "128, 127"})
        @DisplayName("one worker per logical processor, less the reserved one")
        void shouldLeaveExactlyOneProcessorSpare(final int processors, final int expected)
        {
            assertThat(ThreadPoolFactory.recommendedWorkerCount(processors))
                .isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({"-2147483648, 1", "-1, 1", "0, 1", "1, 1", "2, 1"})
        @DisplayName("a tiny or nonsensical processor count still yields a usable pool")
        void shouldNeverGoBelowTheFloor(final int processors, final int expected)
        {
            // A single-core machine is the case that matters: subtracting the
            // reserved processor would leave zero workers, and WorkerPool.init
            // rejects that outright. The floor is what keeps such a machine
            // booting at all — and it is safe, because submitParallel's caller
            // participates, so one worker is a working engine.
            //
            // Integer.MIN_VALUE is in the list because the obvious one-liner
            // (max(1, n - 1)) wraps on it and asks for two billion threads.
            assertThat(ThreadPoolFactory.recommendedWorkerCount(processors))
                .isEqualTo(expected)
                .isGreaterThanOrEqualTo(ThreadPoolFactory.MINIMUM_WORKERS);
        }

        @Test
        @DisplayName("the count tracks the hardware and never oversubscribes it")
        void shouldScaleWithTheMachineWithoutExceedingIt()
        {
            // The property the old logicalCores/2 rule failed: a bigger machine
            // must get a bigger pool, all the way up, with no cap hiding in the
            // arithmetic. Checked as a property over a wide range rather than
            // against a table, so a future cap cannot slip through by matching
            // the cases someone happened to write down.
            // MUTABLE: the previous iteration's count, for the monotonic check
            int previous = 0;
            for (int processors = 1; processors <= 512; processors++)
            {
                final int workers = ThreadPoolFactory.recommendedWorkerCount(processors);
                assertThat(workers)
                    .as("workers for %d processors", processors)
                    .isGreaterThanOrEqualTo(previous)
                    .isLessThanOrEqualTo(processors);
                previous = workers;
            }
            assertThat(previous).isEqualTo(511);
        }

        @Test
        @DisplayName("the rule is a pure function of its argument")
        void shouldNotDependOnTheMachineItRunsOn()
        {
            // No Runtime read inside the factory: the same argument gives the
            // same answer here, on CI, and on a single-core container.
            assertThat(ThreadPoolFactory.recommendedWorkerCount(6))
                .isEqualTo(ThreadPoolFactory.recommendedWorkerCount(6))
                .isNotEqualTo(ThreadPoolFactory.recommendedWorkerCount(7));
        }
    }

    @Nested
    @DisplayName("the override")
    class PinnedCount
    {
        @ParameterizedTest
        @CsvSource({"8, 1, 1", "8, 3, 3", "1, 16, 16", "4, 64, 64"})
        @DisplayName("a pinned count wins, and may exceed the processor count")
        void shouldPinTheCount(final int processors, final String pinned, final int expected)
        {
            // Oversubscription is allowed on purpose. The worker sweep in
            // docs/ASSETS.md ran 16 workers on a machine the auto rule would
            // have given 21, and a rule that refused counts it disagreed with
            // could not have produced that table.
            assertThat(ThreadPoolFactory.resolveWorkerCount(processors, pinned))
                .isEqualTo(expected);
        }

        @Test
        @DisplayName("surrounding whitespace is tolerated")
        void shouldTrimTheValue()
        {
            assertThat(ThreadPoolFactory.resolveWorkerCount(8, "  6  ")).isEqualTo(6);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "0", "-4", "two", "3.5", "8x"})
        @DisplayName("an unusable value falls back to the automatic rule")
        void shouldIgnoreAnUnusableValue(final String value)
        {
            // Warned about, not fatal. A mistyped benchmark flag must not stop
            // the game starting.
            assertThat(ThreadPoolFactory.resolveWorkerCount(9, value))
                .isEqualTo(ThreadPoolFactory.recommendedWorkerCount(9));
        }

        @Test
        @DisplayName("an absent value falls back to the automatic rule")
        void shouldIgnoreAnAbsentValue()
        {
            assertThat(ThreadPoolFactory.resolveWorkerCount(9, null))
                .isEqualTo(ThreadPoolFactory.recommendedWorkerCount(9));
        }
    }

    @Nested
    @DisplayName("the property the engine actually reads")
    class LiveProperty
    {
        @Test
        @DisplayName("the system property pins the pool the engine would build")
        void shouldHonourTheSystemProperty()
        {
            pinProperty("5");
            assertThat(ThreadPoolFactory.resolveWorkerCount(64)).isEqualTo(5);
        }

        @Test
        @DisplayName("a garbage system property leaves the automatic rule in charge")
        void shouldFallBackWhenThePropertyIsGarbage()
        {
            pinProperty("all of them");
            assertThat(ThreadPoolFactory.resolveWorkerCount(12))
                .isEqualTo(ThreadPoolFactory.recommendedWorkerCount(12));
        }

        @Test
        @DisplayName("the value derived from this machine is a legal pool size")
        void shouldProduceAPoolThisMachineCanStart() throws Exception
        {
            // The one end-to-end check: whatever this machine reports, the
            // number that comes out is one WorkerPool.init accepts. That is the
            // contract the engine depends on and it is asserted without naming
            // a count, so it holds on a one-core CI container and on a 128-way
            // build box alike.
            final int processors = new NullSystemInfoPort().logicalProcessorCount();
            final int workers = ThreadPoolFactory.resolveWorkerCount(processors);
            assertThat(workers).isGreaterThanOrEqualTo(ThreadPoolFactory.MINIMUM_WORKERS);

            final I_EventBusPort bus = EventBusFactory.createShared();
            bus.init(64);
            final I_ThreadPoolPort pool =
                ThreadPoolFactory.createFixed(bus, new SubsystemRegistry());
            try
            {
                pool.init(workers);
                pool.start();
                assertThat(pool.workerCount()).isEqualTo(workers);
                assertThat(pool.state()).isEqualTo(I_ThreadPoolPort.State.RUNNING);
            }
            finally
            {
                pool.shutdown();
                pool.awaitTermination(5000);
                bus.shutdown();
            }
        }
    }
}
