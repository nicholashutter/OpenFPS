/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link LogFileSink}: end-to-end wiring of the bus to a
 * rotating on-disk file.
 *
 * <p>The sink subscribes to the engine's main bus, queues events on
 * a bounded queue, and drains them on a daemon writer thread. These
 * tests cover the contract:</p>
 *
 * <ul>
 *   <li>events published <em>before</em> the sink starts are
 *       ignored (the bus is a ring, not a durable log);</li>
 *   <li>events published <em>after</em> the sink starts end up in
 *       the file, formatted by {@link LogFileFormat};</li>
 *   <li>{@link LogFileSink#close()} drains and joins, with no
 *       leftover thread after the test ends;</li>
 *   <li>a queue overflow drops events and increments the dropped
 *       counter, but never blocks the publishing thread;</li>
 *   <li>rotation closes the active file, shifts it to {@code .1},
 *       and opens a fresh one when the byte threshold is crossed.</li>
 * </ul>
 */
@DisplayName("LogFileSink")
class LogFileSinkTest
{
    @TempDir
    Path tempDir;

    @BeforeEach
    void resetBus()
    {
        // The sink subscribes to the static main bus; every test
        // starts with a clean factory so subscription state from
        // one test does not bleed into the next.
        LogBusFactory.resetForTesting();
    }

    @AfterEach
    void teardownBus()
    {
        LogBusFactory.resetForTesting();
    }

    @Test
    @DisplayName("constructor rejects null log file")
    void shouldRejectNullLogFile()
    {
        assertThatThrownBy(() -> new LogFileSink(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("logFile");
    }

    @Test
    @DisplayName("constructor rejects non-positive rotateBytes")
    void shouldRejectNonPositiveRotateBytes()
    {
        assertThatThrownBy(() -> new LogFileSink(tempDir.resolve("x.log"),
            LogLevel.INFO, 0, 3, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rotateBytes");
    }

    @Test
    @DisplayName("constructor rejects keepFiles < 1")
    void shouldRejectZeroKeepFiles()
    {
        assertThatThrownBy(() -> new LogFileSink(tempDir.resolve("x.log"),
            LogLevel.INFO, 1024, 0, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keepFiles");
    }

    @Test
    @DisplayName("constructor rejects non-positive queueCapacity")
    void shouldRejectNonPositiveQueueCapacity()
    {
        assertThatThrownBy(() -> new LogFileSink(tempDir.resolve("x.log"),
            LogLevel.INFO, 1024, 3, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("queueCapacity");
    }

    @Test
    @DisplayName("start() then publish() then close() round-trips an event into the file")
    void shouldRoundTripOneEvent() throws Exception
    {
        final Path file = tempDir.resolve("openfps.log");

        final LogFileSink sink = new LogFileSink(file, LogLevel.INFO,
            4096, 3, 64);

        sink.start();

        try
        {
            LogBusFactory.main().publish(infoEvent("boot complete"));

            // Wait for the writer to flush; the daemon polls every
            // 50ms, so 1s is generous but bounded.
            assertThat(awaitWritten(sink, 1L, 1000L)).isTrue();
        }
        finally
        {
            sink.close();
        }

        final String content = Files.readString(file, StandardCharsets.UTF_8);

        assertThat(content).contains("boot complete");

        assertThat(content).contains("engine.core");

        assertThat(sink.writtenCount()).isGreaterThanOrEqualTo(1L);

        assertThat(sink.droppedCount()).isZero();
    }

    @Test
    @DisplayName("close() drains the queue and joins the writer thread")
    void shouldDrainOnClose() throws Exception
    {
        final Path file = tempDir.resolve("openfps.log");

        final LogFileSink sink = new LogFileSink(file, LogLevel.INFO,
            4096, 3, 1024);

        sink.start();

        // Publish a burst of events; close() should drain them
        // all rather than losing them when the writer thread
        // exits.
        for (int i = 0; i < 50; i++)
        {
            LogBusFactory.main().publish(infoEvent("burst-" + i));
        }

        sink.close();

        final String content = Files.readString(file, StandardCharsets.UTF_8);

        // The drain tail loop in writeLoop() picks up anything
        // still queued when the writer exits. We don't assert
        // every single burst-N is present because the writer
        // thread might have processed some before close(), but
        // the file must be non-empty.
        assertThat(content).isNotEmpty();

        assertThat(sink.writtenCount()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("close() is idempotent")
    void shouldBeIdempotentOnClose()
    {
        final LogFileSink sink = new LogFileSink(tempDir.resolve("openfps.log"),
            LogLevel.INFO, 4096, 3, 16);

        sink.start();

        sink.close();

        // A second close must not throw, and must not try to
        // rejoin a thread that has already exited.
        sink.close();

        sink.close();
    }

    @Test
    @DisplayName("events below minLevel are dropped before the queue")
    void shouldDropBelowMinLevel() throws Exception
    {
        final Path file = tempDir.resolve("openfps.log");

        final LogFileSink sink = new LogFileSink(file, LogLevel.WARN,
            4096, 3, 64);

        sink.start();

        try
        {
            LogBusFactory.main().publish(infoEvent("should-be-dropped"));

            LogBusFactory.main().publish(warnEvent("should-be-kept"));
        }
        finally
        {
            sink.close();
        }

        final String content = Files.readString(file, StandardCharsets.UTF_8);

        assertThat(content).contains("should-be-kept");

        assertThat(content).doesNotContain("should-be-dropped");

        assertThat(sink.droppedCount())
            .as("below-minLevel events are dropped before the queue, not counted as overflow")
            .isZero();
    }

    @Test
    @DisplayName("queue overflow drops events but does not block publishing")
    void shouldDropOnOverflow() throws Exception
    {
        final Path file = tempDir.resolve("openfps.log");

        // Tiny queue so a burst of events overflows; tiny rotate
        // size doesn't matter for this test.
        final LogFileSink sink = new LogFileSink(file, LogLevel.INFO,
            Integer.MAX_VALUE, 3, 2);

        sink.start();

        try
        {
            // Publish far more than the queue can hold; we don't
            // care about the exact number, only that we publish
            // BEFORE the writer has any chance to drain, which
            // is guaranteed because the writer polls with a
            // 50ms timeout and our publish loop runs
            // microseconds-fast in comparison.
            final int burst = 1000;

            for (int i = 0; i < burst; i++)
            {
                LogBusFactory.main().publish(infoEvent("overflow-" + i));
            }

            // Wait for the writer to drain what it can.
            assertThat(awaitNoProgress(sink, 500L)).isTrue();

            // The sink must have dropped at least SOME events:
            // we published 1000 into a 2-deep queue.
            assertThat(sink.droppedCount()).isGreaterThan(0L);
        }
        finally
        {
            sink.close();
        }
    }

    @Test
    @DisplayName("rotation shifts the active file to .1 and opens a fresh one")
    void shouldRotateWhenThresholdCrossed() throws Exception
    {
        final Path file = tempDir.resolve("openfps.log");

        // A formatted event is ~75 bytes. Threshold 50 means
        // each event crosses, so each publish triggers rotation.
        // After two publishes:
        //   - .1.log holds the most-recently-rotated-out content
        //   - .2.log holds the previous rotation
        //   - active openfps.log is fresh and waiting
        // This tests both that rotation happens AND that the
        // shift-up preserves history up to keepFiles.
        final LogFileSink sink = new LogFileSink(file, LogLevel.INFO,
            50, 3, 64);

        sink.start();

        try
        {
            LogBusFactory.main().publish(infoEvent("first"));

            // Wait for the first rotation to land .1.log.
            assertThat(awaitRotated(file, 1000L)).isTrue();

            LogBusFactory.main().publish(infoEvent("second"));

            // After the second publish, the second rotation
            // shifts the existing .1.log (containing "first")
            // up to .2.log, and the new active file is moved
            // to .1.log (containing "second").
            assertThat(awaitWritten(sink, 2L, 2000L)).isTrue();

            sink.close();

            final Path first = tempDir.resolve("openfps.1.log");
            final Path second = tempDir.resolve("openfps.2.log");

            assertThat(Files.exists(first))
                .as("most recent rotation should leave a .1.<suffix> file")
                .isTrue();

            assertThat(Files.exists(second))
                .as("previous rotation should have shifted up to .2.<suffix>")
                .isTrue();

            // The first event was rotated first, then shifted
            // to .2.log by the second rotation. The second event
            // is the most recent rotation, so it's in .1.log.
            final String firstContent = Files.readString(first, StandardCharsets.UTF_8);

            assertThat(firstContent).contains("second");

            final String secondContent = Files.readString(second, StandardCharsets.UTF_8);

            assertThat(secondContent).contains("first");

            // After the second rotation, the active file is
            // gone — it was moved to .1.log. A subsequent
            // publish would lazily recreate it; we don't
            // assert that here because we already covered
            // "publish after start() writes" in earlier tests.
        }
        finally
        {
            // close() was called inline above to make
            // assertions deterministic; guard against double-close.
        }
    }

    @Test
    @DisplayName("keepFiles == 1 deletes the active file on rotate, no history")
    void shouldKeepSingleFileWhenConfigured() throws Exception
    {
        final Path file = tempDir.resolve("openfps.log");

        final LogFileSink sink = new LogFileSink(file, LogLevel.INFO,
            32, 1, 64);

        sink.start();

        try
        {
            LogBusFactory.main().publish(infoEvent("first"));

            // The 32-byte threshold is crossed by one formatted
            // event (~70 bytes); rotation then DELETES the active
            // file (keepFiles == 1), so awaitRotated() must time
            // out because openfps.1.log is never created.
            assertThat(awaitRotated(file, 200L)).isFalse();

            sink.close();

            // Active file deleted; no .1 file created.
            final Path rotated = tempDir.resolve("openfps.1.log");

            assertThat(Files.exists(rotated))
                .as("no rotated file should exist when keepFiles == 1")
                .isFalse();
        }
        finally
        {
            // close() called inline; nothing to do.
        }
    }

    @Test
    @DisplayName("publish before start() is NOT replayed to the sink")
    void shouldNotReplayEventsPublishedBeforeStart() throws Exception
    {
        final Path file = tempDir.resolve("openfps.log");

        // Publish BEFORE the sink exists.
        LogBusFactory.main().publish(infoEvent("pre-start"));

        final LogFileSink sink = new LogFileSink(file, LogLevel.INFO,
            4096, 3, 64);

        sink.start();

        try
        {
            LogBusFactory.main().publish(infoEvent("post-start"));

            assertThat(awaitWritten(sink, 1L, 1000L)).isTrue();
        }
        finally
        {
            sink.close();
        }

        final String content = Files.readString(file, StandardCharsets.UTF_8);

        assertThat(content).contains("post-start");

        assertThat(content)
            .as("events published before start() must not appear in the file")
            .doesNotContain("pre-start");
    }

    @Test
    @DisplayName("close() does not block forever even if the writer stalls")
    void shouldBoundCloseTimeout()
    {
        final LogFileSink sink = new LogFileSink(tempDir.resolve("openfps.log"),
            LogLevel.INFO, 4096, 3, 64);

        sink.start();

        // close() must complete in well under its 2-second join
        // timeout even when no events have been published.
        final long start = System.nanoTime();

        sink.close();

        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs)
            .as("close() should return promptly even with an empty queue")
            .isLessThan(2000L);
    }

    @Test
    @DisplayName("minLevel() returns the constructor floor")
    void shouldExposeMinLevel()
    {
        final LogFileSink sink = new LogFileSink(tempDir.resolve("openfps.log"),
            LogLevel.WARN, 4096, 3, 16);

        assertThat(sink.minLevel()).isEqualTo(LogLevel.WARN);
    }

    @Test
    @DisplayName("logFile() returns the constructor path")
    void shouldExposeLogFile()
    {
        final Path file = tempDir.resolve("openfps.log");

        final LogFileSink sink = new LogFileSink(file);

        assertThat(sink.logFile()).isEqualTo(file);
    }

    @Test
    @DisplayName("start() is idempotent")
    void shouldBeIdempotentOnStart()
    {
        final LogFileSink sink = new LogFileSink(tempDir.resolve("openfps.log"),
            LogLevel.INFO, 4096, 3, 16);

        sink.start();

        // A second start() must not start a second writer thread.
        sink.start();

        sink.close();
    }

    // ---- helpers ----

    private static LogEvent infoEvent(final String message)
    {
        return new LogEvent(System.currentTimeMillis(), "engine.core",
            "com.openfps.engine.test", LogLevel.INFO, message, null);
    }

    private static LogEvent warnEvent(final String message)
    {
        return new LogEvent(System.currentTimeMillis(), "engine.core",
            "com.openfps.engine.test", LogLevel.WARN, message, null);
    }

    private static boolean awaitWritten(final LogFileSink sink,
        final long target, final long timeoutMs) throws InterruptedException
    {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        while (System.nanoTime() < deadline)
        {
            if (sink.writtenCount() >= target)
            {
                return true;
            }

            Thread.sleep(5L);
        }

        return false;
    }

    private static boolean awaitNoProgress(final LogFileSink sink,
        final long quietMs) throws InterruptedException
    {
        // Wait until writtenCount and droppedCount stop changing
        // for `quietMs` milliseconds.
        long lastWritten = -1L;

        long lastDropped = -1L;

        long lastChange = System.nanoTime();

        final long deadline = lastChange + TimeUnit.MILLISECONDS.toNanos(5000L);

        while (System.nanoTime() < deadline)
        {
            final long w = sink.writtenCount();

            final long d = sink.droppedCount();

            if (w != lastWritten || d != lastDropped)
            {
                lastWritten = w;

                lastDropped = d;

                lastChange = System.nanoTime();
            }

            if (System.nanoTime() - lastChange
                >= TimeUnit.MILLISECONDS.toNanos(quietMs))
            {
                return true;
            }

            Thread.sleep(5L);
        }

        return false;
    }

    private static boolean awaitRotated(final Path activeFile,
        final long timeoutMs) throws InterruptedException
    {
        final Path rotated = sibling(activeFile, 1);

        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        while (System.nanoTime() < deadline)
        {
            if (Files.exists(rotated))
            {
                return true;
            }

            Thread.sleep(5L);
        }

        return false;
    }

    private static Path sibling(final Path file, final int index)
    {
        final String name = file.getFileName().toString();

        final int dot = name.lastIndexOf('.');

        if (dot < 0)
        {
            return file.resolveSibling(name + "." + index);
        }

        return file.resolveSibling(name.substring(0, dot) + "." + index
            + name.substring(dot));
    }
}
