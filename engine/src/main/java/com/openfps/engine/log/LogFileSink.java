/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.openfps.engine.common.Constants;

/**
 * The on-disk consumer of the engine's log bus.
 *
 * <p>{@code LogFileSink} subscribes to {@link LogBusFactory#main()}
 * and writes every event to a rotating log file. A developer running
 * {@code gradlew :engine:run} or launching the desktop client gets
 * a {@code logs/openfps-<timestamp>.log} next to
 * {@code settings.gradle.kts} automatically; the file rolls over
 * when it fills {@link Constants#LOG_FILE_ROTATE_BYTES}, and the
 * last {@link Constants#LOG_FILE_KEEP_FILES} files are kept.</p>
 *
 * <p>Why a sink on top of the bus instead of a logback file
 * appender: SLF4J has the events first, the bus is what actually
 * models the engine's logging semantics. A bus sink respects the
 * "all engine logs flow through the bus" rule from the start; a
 * logback appender has to be wired around the bridge just to share
 * the same source-channel logic. The sink's only contract with the
 * logging stack is "give me every event on the main bus."</p>
 *
 * <h2>Async, never blocks publish</h2>
 *
 * <p>An event from {@link I_LogBus#publish(LogEvent)} lands on an
 * internal bounded {@link ArrayBlockingQueue}, picked up by a daemon
 * writer thread, formatted by {@link LogFileFormat}, and written
 * with a {@link BufferedWriter}. The publish path is the bus's
 * own (non-blocking ring buffer); the sink is one further step
 * with its own bounded queue, so the bus's
 * {@link RingBufferLogBus#publish} is the slowest hot-path cost.
 * A burst that overflows the queue drops events and increments
 * {@link #droppedCount()} &mdash; the dropped counter is the way
 * silent loss becomes observable.</p>
 *
 * <h2>Rotation</h2>
 *
 * <p>When the writer's byte counter crosses
 * {@link Constants#LOG_FILE_ROTATE_BYTES}, the current file is
 * closed, renamed to {@code <base>.1.<suffix>}, files {@code .1}
 * through {@code .(keep-1)} are shifted up by one, and a fresh
 * {@code <base>.<suffix>} is opened. The writer thread does the
 * rename so a future {@link #close()} is simple.</p>
 *
 * <h2>Why this owns its own thread</h2>
 *
 * <p>AGENTS.md says "no {@code new Thread(...)} for event handling;
 * use {@code WorkerPool}." The exception is the same as
 * {@link LogBusFactory#startDrainTask()}'s drain thread: the
 * file sink must keep draining its own queue even when the engine
 * is shutting down and the worker pool is itself draining. A worker
 * pool task would race shutdown for the right to finish.</p>
 */
public final class LogFileSink implements AutoCloseable
{
    /** Thread name for the writer thread; matches the bus drain's
     *  naming scheme. */
    private static final String WRITER_THREAD_NAME = "openfps-log-file-writer";

    /** Special sentinel for {@code -Dopenfps.log.file=off} /
     *  {@code OPENFPS_LOG_FILE=off}: ask the sink to be skipped. */
    public static final String DISABLED_SENTINEL = "off";

    /** Minimum drain interval on close, in milliseconds. The writer
     *  polls the queue with a short timeout so {@link #close} can
     *  interrupt it promptly. */
    private static final long POLL_TIMEOUT_MS = 50L;

    /** The resolved log file path (the active {@code openfps.log}
     *  after rotation). Never null. */
    private final Path logFile;

    /** The rotation size in bytes. */
    private final int rotateBytes;

    /** Number of rotated files to keep. */
    private final int keepFiles;

    /** Minimum level to write. Events below this are dropped before
     *  the queue. */
    private final LogLevel minLevel;

    /** Bus subscription; null if the bus was empty at install
     *  time (a cored without ever touching the main bus). */
    private LogSubscription subscription;

    /** The event queue between the bus handler and the writer
     *  thread. Bounded by {@code Constants.LOG_FILE_QUEUE_CAPACITY}. */
    private final BlockingQueue<LogEvent> queue;

    /** Bytes written to the current file since the last rotation. */
    private final AtomicLong currentFileBytes = new AtomicLong();

    /** Events dropped because the queue was full. */
    private final AtomicLong dropped = new AtomicLong();

    /** Events successfully written. */
    private final AtomicLong written = new AtomicLong();

    /** Whether {@link #close} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Whether the writer thread has been started. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** The writer thread. Volatile so {@link #close} can read. */
    private volatile Thread writerThread;

    /**
     * Builds a sink with default rotation, keep, and queue capacity.
     * Min level defaults to {@link LogLevel#INFO}.
     *
     * @param logFile the active log file path; must not be null
     * @throws IllegalArgumentException if {@code logFile} is null
     */
    public LogFileSink(final Path logFile)
    {
        this(logFile, LogLevel.INFO,
            Constants.LOG_FILE_ROTATE_BYTES,
            Constants.LOG_FILE_KEEP_FILES,
            Constants.LOG_FILE_QUEUE_CAPACITY);
    }

    /**
     * Builds a sink with full control over every knob. Tests use
     * this with a small rotation size and queue capacity; production
     * code uses the single-arg form.
     *
     * @param logFile       the active log file path; must not be null
     * @param minLevel      the minimum level to write; must not be null
     * @param rotateBytes   rotation threshold; must be positive
     * @param keepFiles     rotated file count; must be at least 1
     * @param queueCapacity queue capacity; must be positive
     * @throws IllegalArgumentException if any argument is null,
     *     blank, or non-positive (where applicable)
     */
    public LogFileSink(final Path logFile, final LogLevel minLevel,
        final int rotateBytes, final int keepFiles, final int queueCapacity)
    {
        if (logFile == null)
        {
            throw new IllegalArgumentException("logFile must not be null");
        }

        if (minLevel == null)
        {
            throw new IllegalArgumentException("minLevel must not be null");
        }

        if (rotateBytes <= 0)
        {
            throw new IllegalArgumentException("rotateBytes must be positive");
        }

        if (keepFiles < 1)
        {
            throw new IllegalArgumentException("keepFiles must be at least 1");
        }

        if (queueCapacity <= 0)
        {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }

        this.logFile = logFile;
        this.minLevel = minLevel;
        this.rotateBytes = rotateBytes;
        this.keepFiles = keepFiles;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    /**
     * Returns the active log file path.
     *
     * @return the logFile passed to the constructor; never null
     */
    public Path logFile()
    {
        return logFile;
    }

    /**
     * Returns the number of events dropped because the queue was full.
     * The counter is what makes silent loss observable: a non-zero
     * value after a busy run says the writer fell behind and
     * publishing was faster than rotation + filesystem.
     *
     * @return the dropped-event count, never negative
     */
    public long droppedCount()
    {
        return dropped.get();
    }

    /**
     * Returns the number of events successfully written to disk.
     *
     * @return the written-event count, never negative
     */
    public long writtenCount()
    {
        return written.get();
    }

    /**
     * Subscribes the sink to the engine's main log bus and starts
     * the writer thread. Idempotent: a second call is a no-op.
     */
    public synchronized void start()
    {
        if (started.get())
        {
            return;
        }

        ensureDirectory();

        subscription = LogBusFactory.main().subscribe(this::onEvent);

        writerThread = new Thread(this::writeLoop, WRITER_THREAD_NAME);

        writerThread.setDaemon(true);

        writerThread.start();

        started.set(true);
    }

    /**
     * Stops the sink: unsubscribes from the bus, drains the queue,
     * flushes the writer, joins the writer thread. Idempotent.
     * A second close is a no-op.
     */
    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }

        if (subscription != null)
        {
            subscription.close();

            subscription = null;
        }

        if (writerThread != null)
        {
            writerThread.interrupt();

            try
            {
                writerThread.join(2000L);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        // Final flush of any events the queue still holds after the
        // writer thread exited. The writer should have drained them
        // already; this is a last-resort belt for a thread that
        // exited mid-write. Anything left is dropped and counted.
        LogEvent leftover;

        while ((leftover = queue.poll()) != null)
        {
            writeOne(leftover);
        }
    }

    /**
     * Returns the maximum level accepted by this sink. Useful for
     * tests and for logback's filters if a future config wants to
     * mirror the sink's floor.
     *
     * @return the minimum level; never null
     */
    public LogLevel minLevel()
    {
        return minLevel;
    }

    // ---- internals ----

    /**
     * Called by the bus on the publishing thread for every event.
     * Hands the event to the bounded queue with a non-blocking offer;
     * a full queue increments the dropped counter and returns.
     *
     * <p>This method MUST NOT throw. The bus's publish holds no
     * lock during subscriber dispatch (see RingBufferLogBus), but
     * a thrown handler would still be a regression &mdash; the
     * RingBufferLogBus already handles subscriber exceptions, but
     * a defensive try/catch is cheap and protects against future
     * changes.</p>
     *
     * @param event the event from the bus; must not be null
     */
    private void onEvent(final LogEvent event)
    {
        if (event == null)
        {
            return;
        }

        if (closed.get())
        {
            return;
        }

        if (!levelAtLeast(event.level(), minLevel))
        {
            return;
        }

        final boolean accepted = queue.offer(event);

        if (!accepted)
        {
            dropped.incrementAndGet();
        }
    }

    // Comparison helper that does not allocate. LogLevel#isAtLeast
    // allocates nothing itself, but inlining it here keeps the
    // hot path obvious without losing readability.
    private static boolean levelAtLeast(final LogLevel have, final LogLevel floor)
    {
        return have.rank() >= floor.rank();
    }

    // The writer thread loop. Polls the queue with a short timeout
    // so close() can interrupt and join.
    private void writeLoop()
    {
        try
        {
            while (!Thread.currentThread().isInterrupted() && !closed.get())
            {
                final LogEvent event = queue.poll(POLL_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);

                if (event != null)
                {
                    writeOne(event);
                }
            }
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            // Flush whatever we have on exit; close() guarantees
            // we get one more chance to clean up.
            LogEvent tail;

            while ((tail = queue.poll()) != null)
            {
                try
                {
                    writeOne(tail);
                }
                catch (final RuntimeException ignored)
                {
                    // Best-effort drain; ignore individual write
                    // failures so one bad event can't keep the
                    // tail in the queue forever.
                }
            }
        }
    }

    // Writes one formatted event to the file, flushing and rotating
    // if the byte threshold is crossed. Synchronized on the writer
    // so the rotation dance does not race the per-event write.
    private synchronized void writeOne(final LogEvent event)
    {
        final String text = LogFileFormat.format(event);

        final int byteCount = text.getBytes(StandardCharsets.UTF_8).length;

        try
        {
            openWriter();

            writer.write(text);

            writer.flush();

            currentFileBytes.addAndGet(byteCount);
            written.incrementAndGet();

            if (currentFileBytes.get() >= rotateBytes)
            {
                rotate();
            }
        }
        catch (final IOException e)
        {
            // A write that fails should not loop forever. The next
            // event will try again; repeated failures are visible
            // as missing lines in the file. Logging here would
            // route through the bus and back to this sink, so we
            // deliberately swallow silently.
        }
    }

    /** Lazily opened BufferedWriter; created on first event, kept
     *  open until close/rotation. Not thread-safe on its own; every
     *  call goes through {@link #writeOne} which synchronizes. */
    private BufferedWriter writer;

    // Opens the writer against the active file. The parent directory
    // exists by this point because ensureDirectory() ran at start().
    private void openWriter() throws IOException
    {
        if (writer == null)
        {
            writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        }
    }

    // Closes the current writer and rotates the file. For
    // keepFiles > 1, the active file becomes ".1", and ".1" through
    // ".(keepFiles-1)" are each shifted up by one; the oldest,
    // ".keepFiles", is deleted. For keepFiles == 1 there is no
    // history to keep, so the active file is deleted and a fresh
    // one is created on the next write.
    private void rotate() throws IOException
    {
        if (writer != null)
        {
            writer.flush();
            writer.close();
            writer = null;
        }

        if (keepFiles == 1)
        {
            Files.deleteIfExists(logFile);
        }
        else
        {
            for (int i = keepFiles - 1; i >= 1; i--)
            {
                final Path from;

                if (i == 1)
                {
                    from = logFile;
                }
                else
                {
                    from = sibling(i - 1);
                }

                final Path to = sibling(i);

                if (Files.exists(from))
                {
                    Files.move(from, to,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        currentFileBytes.set(0L);
    }

    // Returns <logFile>'s sibling with the rotation index inserted
    // before the suffix: openfps.log -> openfps.1.log etc.
    private Path sibling(final int index)
    {
        final String name = logFile.getFileName().toString();

        final int dot = name.lastIndexOf('.');

        if (dot < 0)
        {
            return logFile.resolveSibling(name + "." + index);
        }

        final String stem = name.substring(0, dot);

        final String suffix = name.substring(dot);

        return logFile.resolveSibling(stem + "." + index + suffix);
    }

    // Creates the log file's parent directory. Idempotent and
    // swallowed: a developer's cwd may not be writable, and the
    // engine still has to come up. The sink stays subscribed to
    // the bus; events continue to queue until the next write
    // attempt finds the directory in place.
    private void ensureDirectory()
    {
        final Path parent = logFile.getParent();

        if (parent == null)
        {
            return;
        }

        try
        {
            Files.createDirectories(parent);
        }
        catch (final IOException | UncheckedIOException e)
        {
            // Silent: see method-level note.
        }
    }
}
