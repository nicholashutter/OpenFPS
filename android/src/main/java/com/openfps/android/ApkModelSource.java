/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.content.res.AssetManager;

import com.openfps.engine.demo.ModelSource;

/**
 * The demo's models, read out of the APK.
 *
 * <p>The reason {@code ModelSource} exists. On desktop the model root is a
 * directory and {@code Files.readAllBytes} is the whole story; inside an APK
 * the same files are zip entries with no filesystem path at all, so there is
 * nothing for a {@code java.nio.file.Path} to point at.
 * {@link AssetManager} is the only thing that can open them.</p>
 *
 * <p>The APK's {@code assets/} directory is populated at build time by the
 * {@code stageModelAssets} task in {@code android/build.gradle.kts}, which
 * copies the repository's {@code assets/models} tree. That tree is gitignored —
 * {@code docs/ASSETS.md} § 6 keeps upstream art out of git — so an APK built
 * from a fresh clone simply has no models in it, and {@code DemoModels} reports
 * that by name rather than crashing. The menu still comes up; there is just no
 * world behind it.</p>
 *
 * <h2>Why the platform asset manager rather than {@code Gdx.files}</h2>
 *
 * <p>libGDX could open these too, but only once {@code AndroidApplication
 * .initialize()} has run — and that is the same call that starts the frame
 * loop, which needs the gameplay port, which needs the models. Taking the
 * {@code AssetManager} straight from the {@code Context} breaks that circle:
 * it is available the instant {@code onCreate} begins, so the world can be
 * built before anything is asked to draw it.</p>
 *
 * <h2>{@code has()} opens the file</h2>
 *
 * <p>{@link AssetManager} has no exists() — it has {@code open}, which throws
 * for anything absent. So {@link #has} opens and immediately closes. That costs
 * a file handle per query and nothing else: entries are not decompressed until
 * they are read, and the whole of {@code DemoModels}' probing is about twenty
 * calls, once, at startup.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Called once, from the Activity's {@code onCreate}. {@code AssetManager} is
 * documented as thread-safe, but nothing here relies on that.</p>
 *
 * Platform adapter — must not import from core engine packages beyond the
 * demo's asset contract.
 */
public final class ApkModelSource implements ModelSource
{
    /** Where the models live inside the APK's assets. */
    public static final String ASSET_ROOT = "models";

    /** Read buffer size. One page; these files are 1–2 MB each. */
    private static final int COPY_BUFFER_BYTES = 8192;

    /** The APK's asset manager. Never null. */
    private final AssetManager assets;

    /** The asset directory every relative path is resolved against. */
    private final String root;

    /**
     * Creates a source over the APK's {@value #ASSET_ROOT} directory.
     *
     * @param assetManager from {@code Context.getAssets()}; must not be null
     * @throws IllegalArgumentException if {@code assetManager} is null
     */
    public ApkModelSource(final AssetManager assetManager)
    {
        this(assetManager, ASSET_ROOT);
    }

    /**
     * Creates a source over an explicit asset directory.
     *
     * @param assetManager from {@code Context.getAssets()}; must not be null
     * @param assetRoot the directory below the APK's {@code assets/}; must not
     *     be null or empty
     * @throws IllegalArgumentException if either argument is null, or the root
     *     is empty
     */
    public ApkModelSource(final AssetManager assetManager, final String assetRoot)
    {
        if (assetManager == null)
        {
            throw new IllegalArgumentException("assetManager must not be null");
        }
        if (assetRoot == null || assetRoot.isEmpty())
        {
            throw new IllegalArgumentException("assetRoot must not be null or empty");
        }
        this.assets = assetManager;
        this.root = assetRoot;
    }

    @Override
    public boolean has(final String relativePath)
    {
        try (InputStream stream = assets.open(qualify(relativePath)))
        {
            return stream != null;
        }
        catch (final IOException e)
        {
            // The documented way to ask an AssetManager whether something is
            // there. Absent is an answer, not a failure, so nothing is logged:
            // DemoModels reports the whole missing set at once, with the
            // command that produces it.
            return false;
        }
    }

    @Override
    public byte[] read(final String relativePath) throws IOException
    {
        try (InputStream stream = assets.open(qualify(relativePath)))
        {
            return readFully(stream);
        }
    }

    @Override
    public String describe(final String relativePath)
    {
        return "APK asset " + qualify(relativePath);
    }

    @Override
    public String describeRoot()
    {
        return "APK asset " + root;
    }

    // The asset name for a model-root-relative path.
    private String qualify(final String relativePath)
    {
        return root + "/" + relativePath;
    }

    // Drains a stream. Not InputStream.readAllBytes(): that arrived in Java 9
    // and, on Android, is one of the methods core library desugaring does NOT
    // backport — it would link on API 34 and throw NoSuchMethodError on the
    // API 23 this module still supports.
    private static byte[] readFully(final InputStream stream) throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(COPY_BUFFER_BYTES);
        final byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int read = stream.read(buffer);
        while (read >= 0)
        {
            out.write(buffer, 0, read);
            read = stream.read(buffer);
        }
        return out.toByteArray();
    }

    /** Returns a debug rendering naming the asset root. */
    @Override
    public String toString()
    {
        return "ApkModelSource[" + describeRoot() + "]";
    }
}
