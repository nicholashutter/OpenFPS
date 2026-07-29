/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.subsystem.SubsystemRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System-level factory for the worker thread pool. The engine never
 * instantiates a pool directly; it asks the factory.
 *
 * <h2>How the pool is sized</h2>
 *
 * <p>This class is the one place the rule lives. It is arithmetic on a
 * processor count the caller supplies — never a read of {@link Runtime} — so
 * the rule is a pure function that a test can drive with any machine's shape.
 * The engine passes {@code I_SystemInfoPort.logicalProcessorCount()}, which is
 * where {@code Runtime.availableProcessors()} is actually read, because all
 * system info goes through the HAL (AGENTS.md rule 1).</p>
 *
 * <p><b>The rule was {@code logicalCores / 2} and that was too timid.</b>
 * Halving was a hedge against hyperthreading: a logical processor is not a
 * core, so counting them all looked like counting the machine twice. The
 * measured worker sweep in {@code docs/ASSETS.md} says otherwise. On a
 * 16-physical / 22-logical part the demo scene renders in 5.2 ms at 8 workers
 * and 4.0 ms at 16 — the SMT and E-core siblings are worth a further 23%, which
 * the old rule declined to collect, because it stopped at 11. At the other end
 * the same table is worse news: a 4-thread device got 2 workers and a 16.3 ms
 * frame, missing 60 Hz outright, when it had two more processors to give.</p>
 *
 * <p><b>Being honest about what those extra threads are.</b> They are not
 * cores. An SMT sibling shares its core's execution units and an E-core clocks
 * roughly half a P-core, so worker number 16 is emphatically not worth what
 * worker number 4 was — the speed-up above is 6.55x on 22 logical processors,
 * not 22x. The claim here is only that they are worth <i>more than nothing</i>,
 * which the measurements support, and that a scheduler distributes work across
 * them better than we can from here.</p>
 *
 * <p><b>Why one is held back.</b> The workers are not the only threads that
 * want to run. The game loop has a thread of its own and the platform frame
 * loop (GLFW on desktop, the {@code GLSurfaceView} thread on Android) has
 * another, and during a raster fan-out both may be runnable at once. Neither is
 * CPU-hungry — the loop sleeps most of its period and the frame loop spends
 * itself waiting on vsync and the texture upload — so reserving a whole
 * processor <i>each</i> would give away real throughput to threads that are
 * mostly asleep. Reserving exactly one keeps the total runnable set at the
 * processor count during the pass that matters, and leaves the two occasional
 * threads somewhere to land without preempting a tile mid-flight. It is a
 * judgement call rather than a measurement; {@link #RESERVED_PROCESSORS} is
 * where to change it.</p>
 *
 * <p>Note that the participating caller of
 * {@link I_ThreadPoolPort#submitParallel} is itself one of these workers — it
 * is a worker mid-dispatch — so it adds nothing to the count.</p>
 *
 * <p><b>Overriding.</b> {@code -D}{@value #WORKER_COUNT_PROPERTY}{@code =N}
 * pins the count for a benchmark or a test. It is deliberately not clamped to
 * the processor count: the sweep in {@code docs/ASSETS.md} oversubscribes on
 * purpose, and a rule that refused to be oversubscribed could not have produced
 * the table that justifies this one. Anything unparseable or below
 * {@link #MINIMUM_WORKERS} is warned about and ignored — a bad diagnostic
 * setting must not stop the engine booting.</p>
 */
public final class ThreadPoolFactory
{
    /**
     * System property that pins the worker count, e.g.
     * {@code -Dopenfps.workers=4}. Absent, empty, unparseable or below
     * {@link #MINIMUM_WORKERS} means "size it automatically".
     */
    public static final String WORKER_COUNT_PROPERTY = "openfps.workers";

    /**
     * Logical processors held back from the pool for the game loop thread and
     * the platform frame loop thread. See the class Javadoc for why it is one
     * and not two.
     */
    public static final int RESERVED_PROCESSORS = 1;

    /**
     * The smallest pool the engine will build. A single-processor machine must
     * still run, and it can: {@code submitParallel}'s caller participates, so
     * one worker is a working engine rather than a deadlocked one.
     */
    public static final int MINIMUM_WORKERS = 1;

    /** Sentinel from {@link #parseOverride} meaning "no usable override". */
    private static final int NOT_PINNED = 0;

    private static final Logger LOG = LoggerFactory.getLogger(ThreadPoolFactory.class);

    private ThreadPoolFactory()
    {
    }

    /**
     * Creates a fixed-size worker pool tied to the given bus and
     * subsystem registry.
     *
     * @param bus the event bus the workers consume from
     * @param registry the subsystem registry workers dispatch to
     * @return a fresh, uninitialized pool (call init() then start())
     */
    public static I_ThreadPoolPort createFixed(final I_EventBusPort bus,
                                                final SubsystemRegistry registry)
    {
        return new WorkerPool(bus, registry);
    }

    /**
     * The automatic rule: one worker per logical processor, less
     * {@link #RESERVED_PROCESSORS}, never below {@link #MINIMUM_WORKERS}.
     *
     * <p>Pure arithmetic — no property read, no {@link Runtime} call — so a
     * test can ask what a 1-, 2- or 64-processor machine would get. Callers
     * that want the override honoured should use
     * {@link #resolveWorkerCount(int)} instead.</p>
     *
     * @param logicalProcessorCount processors the machine reports; a
     *     non-positive count is treated as one, because a machine that cannot
     *     count itself still has to run
     * @return recommended worker count, always at least {@link #MINIMUM_WORKERS}
     */
    public static int recommendedWorkerCount(final int logicalProcessorCount)
    {
        // Clamp the input BEFORE subtracting, not after. Subtracting from
        // Integer.MIN_VALUE wraps to Integer.MAX_VALUE, so a nonsensical count
        // would ask for two billion threads through a Math.max that is looking
        // the other way. Nothing produces such a count today; the clamp is here
        // so that nothing ever can.
        final int usable = Math.max(MINIMUM_WORKERS, logicalProcessorCount);
        return Math.max(MINIMUM_WORKERS, usable - RESERVED_PROCESSORS);
    }

    /**
     * The whole sizing rule as a pure function: the override when it is usable,
     * otherwise {@link #recommendedWorkerCount(int)}.
     *
     * <p>The override arrives as a parameter rather than being read here so
     * that this method is testable without mutating global JVM state — a test
     * that sets a system property leaks it into every test that follows.</p>
     *
     * @param logicalProcessorCount processors the machine reports
     * @param override the raw property value, or null when it is not set
     * @return the worker count to build the pool with, at least
     *     {@link #MINIMUM_WORKERS}
     */
    public static int resolveWorkerCount(final int logicalProcessorCount,
                                         final String override)
    {
        final int pinned = parseOverride(override);
        if (pinned >= MINIMUM_WORKERS)
        {
            return pinned;
        }
        if (override != null && !override.trim().isEmpty())
        {
            LOG.warn("Ignoring -D{}={}: expected a whole number >= {}",
                WORKER_COUNT_PROPERTY, override, MINIMUM_WORKERS);
        }
        return recommendedWorkerCount(logicalProcessorCount);
    }

    /**
     * The sizing rule applied to this JVM, reading
     * {@value #WORKER_COUNT_PROPERTY}, and logging what it decided.
     *
     * <p>The log line is the point of this overload. A pool sized by a rule
     * nobody can see is a pool nobody can debug: "the frame is slow on that
     * laptop" is unanswerable without knowing how many workers that laptop
     * built. It reports the count, the processors it was derived from, and
     * which of the two rules produced it. Called once per engine boot.</p>
     *
     * @param logicalProcessorCount processors the machine reports, from
     *     {@code I_SystemInfoPort}
     * @return the worker count to build the pool with
     */
    public static int resolveWorkerCount(final int logicalProcessorCount)
    {
        final String configured = System.getProperty(WORKER_COUNT_PROPERTY);
        final int workers = resolveWorkerCount(logicalProcessorCount, configured);
        if (workers == parseOverride(configured))
        {
            LOG.info("Worker pool pinned to {} threads by -D{}={} ({} logical processors)",
                workers, WORKER_COUNT_PROPERTY, configured, logicalProcessorCount);
        }
        else
        {
            LOG.info("Worker pool sized to {} threads: {} logical processors less {} "
                + "reserved for the game loop and the platform frame loop",
                workers, logicalProcessorCount, RESERVED_PROCESSORS);
        }
        return workers;
    }

    // Silent on purpose: resolveWorkerCount owns the warning, so this can be
    // called twice (once to decide, once to describe) without warning twice.
    private static int parseOverride(final String override)
    {
        if (override == null || override.trim().isEmpty())
        {
            return NOT_PINNED;
        }
        try
        {
            final int parsed = Integer.parseInt(override.trim());
            if (parsed < MINIMUM_WORKERS)
            {
                return NOT_PINNED;
            }
            return parsed;
        }
        catch (final NumberFormatException e)
        {
            return NOT_PINNED;
        }
    }
}
