/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import java.io.IOException;

/**
 * Where {@link DemoModels} reads its {@code .ofm} files from.
 *
 * <h2>Why this is not just a {@code Path}</h2>
 *
 * <p>It was a {@code Path} until the game had to run on a phone, and on a phone
 * there is no such file. An APK is a zip; its {@code assets/} entries are read
 * through the platform's asset manager and have no filesystem path at all, so
 * {@code Files.readAllBytes} on any name you can construct for them fails. The
 * demo's model set is the one thing the engine loads from outside itself, so it
 * is the one place that had to stop assuming a directory.</p>
 *
 * <p>Three methods rather than one {@code read} that returns null, because
 * {@link DemoModels} genuinely needs all three and merging them costs something
 * real:</p>
 *
 * <ul>
 *   <li>{@link #has} answers "is the Kenney kit staged?" across seven files
 *       before deciding which of the two asset paths to take. Answering that
 *       with {@code read} would decode seven models — nine megabytes on the
 *       Android path — to look at whether they exist.</li>
 *   <li>{@link #read} is the actual load, and throws rather than returning null
 *       so a file that exists but cannot be read is never silently downgraded
 *       to "absent". A corrupt asset and a missing one need different messages
 *       and different fixes.</li>
 *   <li>{@link #describe} is what the log lines and the {@link DemoAssetException}
 *       message name. A relative path alone is not actionable — "level/wall.ofm
 *       is missing" does not say <i>which</i> {@code level/}. A directory source
 *       answers with an absolute path; an APK source says so in as many
 *       words.</li>
 * </ul>
 *
 * <h2>Path convention</h2>
 *
 * <p>Every path is relative to the model root and uses {@code /} as its
 * separator, on every platform — {@code "level/wall.ofm"}. That is what a zip
 * entry and an asset manager both want, and {@code Path.resolve} accepts it
 * happily on Windows, so the single convention costs the directory
 * implementation nothing. Paths never begin with {@code /} and never contain
 * {@code ..}; a source is free to reject one that does.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Called once at startup, from one thread, never per frame. An
 * implementation does not need to be thread-safe.</p>
 */
public interface ModelSource
{
    /**
     * Returns whether a model file is present and readable.
     *
     * <p>Must not throw for an absent file — that is the answer, not an error.
     * A source that cannot tell without reading may say true and let
     * {@link #read} report the truth.</p>
     *
     * @param relativePath the path below the model root, {@code /}-separated;
     *     must not be null
     * @return true if {@link #read} is expected to succeed
     */
    boolean has(String relativePath);

    /**
     * Reads one model file whole.
     *
     * @param relativePath the path below the model root, {@code /}-separated;
     *     must not be null
     * @return the file's bytes; never null
     * @throws IOException if the file is absent or cannot be read
     */
    byte[] read(String relativePath) throws IOException;

    /**
     * Returns a human-readable location for a path, for logs and errors.
     *
     * <p>Whatever a reader would need to go and look at the thing: an absolute
     * path where there is one, and a plain statement of where else it lives
     * where there is not.</p>
     *
     * @param relativePath the path below the model root; must not be null
     * @return a description of where that file is looked for; never null
     */
    String describe(String relativePath);

    /**
     * Returns a human-readable location for the model root itself.
     *
     * @return a description of the root; never null
     */
    String describeRoot();
}
