/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link ModelSource} over an ordinary directory — the desktop model root.
 *
 * <p>What {@code assets/models} has always been, now behind the interface. It
 * is the only implementation in {@code :engine}: the APK-backed one needs a
 * platform asset manager and therefore lives in {@code :android}, which is the
 * whole reason the interface exists.</p>
 *
 * <p>Relative paths arrive {@code /}-separated per {@link ModelSource}, and
 * {@code Path.resolve} accepts that on Windows as readily as on Linux, so no
 * separator translation happens here. It would be a bug if it did — a
 * translated path and an untranslated one would describe the same file with two
 * different strings in two different log lines.</p>
 */
public final class DirectoryModelSource implements ModelSource
{
    /** The directory every relative path is resolved against. Never null. */
    private final Path root;

    /**
     * Creates a source over one directory.
     *
     * <p>The directory is not required to exist. A model root that is simply
     * absent must produce the same reported outcome as one that is present and
     * empty — {@link DemoModels} names {@link DemoModels#REGENERATE_COMMAND}
     * either way — and failing here would replace that message with a stack
     * trace one layer too early to be useful.</p>
     *
     * @param modelRoot the directory holding {@code level/}, {@code weapon/}
     *     and {@code character/}; must not be null
     * @throws IllegalArgumentException if {@code modelRoot} is null
     */
    public DirectoryModelSource(final Path modelRoot)
    {
        if (modelRoot == null)
        {
            throw new IllegalArgumentException("modelRoot must not be null");
        }
        this.root = modelRoot;
    }

    @Override
    public boolean has(final String relativePath)
    {
        return Files.isRegularFile(root.resolve(relativePath));
    }

    @Override
    public byte[] read(final String relativePath) throws IOException
    {
        return Files.readAllBytes(root.resolve(relativePath));
    }

    @Override
    public String describe(final String relativePath)
    {
        return root.resolve(relativePath).toAbsolutePath().toString();
    }

    @Override
    public String describeRoot()
    {
        return root.toAbsolutePath().toString();
    }

    /** Returns the directory this source reads from. Never null. */
    public Path root()
    {
        return root;
    }

    /** Returns a debug rendering naming the root directory. */
    @Override
    public String toString()
    {
        return "DirectoryModelSource[" + describeRoot() + "]";
    }
}
