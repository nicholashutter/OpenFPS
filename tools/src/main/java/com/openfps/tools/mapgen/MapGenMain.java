/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openfps.engine.render.adapter.ModelFormat;

/**
 * Command-line entry point for the config-driven map generator.
 *
 * <p>Invoked from the {@code :tools:buildMapFromConfig} Gradle task. The
 * arguments are:</p>
 *
 * <ul>
 *   <li>{@code --config=<path>} — the JSON config; required.</li>
 *   <li>{@code --out=<dir>} — the directory to write the produced
 *       {@code level.ofm} into; required.</li>
 *   <li>{@code --atlas=<path>} — the Kenney Prototype Kit
 *       {@code colormap.png}; optional, falls back to procedural
 *       solid-colour tiles when absent.</li>
 * </ul>
 *
 * <p>Run with no arguments, the program prints the usage and exits non-zero
 * — the same shape the existing {@code *MapBuilder} entry points use.</p>
 */
public final class MapGenMain
{
    private static final Logger LOG = LoggerFactory.getLogger(MapGenMain.class);

    private static final int EXIT_OK = 0;
    private static final int EXIT_BAD_ARGS = 1;
    private static final int EXIT_IO_ERROR = 2;
    private static final int EXIT_BAD_MODEL = 3;

    private MapGenMain()
    {
        // entry point holder
    }

    /**
     * Entry point for the Gradle task.
     *
     * @param args CLI arguments
     */
    public static void main(final String[] args)
    {
        final String configPath = option(args, "--config=");
        final String outPath = option(args, "--out=");
        final String atlasPath = option(args, "--atlas=");
        if (configPath == null || outPath == null)
        {
            LOG.error("usage: MapGenMain --config=<map.json> --out=<directory>"
                + " [--atlas=<colormap.png>]");
            System.exit(EXIT_BAD_ARGS);
            return;
        }
        final Path config = Path.of(configPath);
        final Path outDir = Path.of(outPath);
        final Path atlas;
        if (atlasPath == null)
        {
            atlas = null;
        }
        else
        {
            atlas = Path.of(atlasPath);
        }
        try
        {
            Files.createDirectories(outDir);
        }
        catch (final IOException e)
        {
            LOG.error("could not create output directory {}: {}", outDir, e.getMessage());
            System.exit(EXIT_IO_ERROR);
            return;
        }
        final MapGenConfig parsed;
        try
        {
            final PrimitiveFactory factory = PrimitiveFactory.createDefault();
            final JsonConfigParser parser = new JsonConfigParser(factory);
            parsed = parser.parse(config);
        }
        catch (final IOException e)
        {
            LOG.error("could not read config {}: {}", config, e.getMessage());
            System.exit(EXIT_IO_ERROR);
            return;
        }
        catch (final IllegalArgumentException e)
        {
            LOG.error("config {} is invalid: {}", config, e.getMessage());
            System.exit(EXIT_BAD_ARGS);
            return;
        }
        final MapGenerator generator = new MapGenerator(atlas, PrimitiveFactory.createDefault());
        final byte[] bytes;
        try
        {
            bytes = generator.generate(parsed);
        }
        catch (final RuntimeException e)
        {
            LOG.error("generation failed: {}", e.getMessage(), e);
            System.exit(EXIT_BAD_ARGS);
            return;
        }
        // Read the bytes back through the runtime reader to make sure they
        // are valid. The same defensive check the existing *MapBuilder mains
        // run, for the same reason.
        final ModelFormat verified;
        try
        {
            verified = ModelFormat.read(bytes);
        }
        catch (final RuntimeException e)
        {
            LOG.error("produced .ofm failed to parse: {}", e.getMessage());
            System.exit(EXIT_BAD_MODEL);
            return;
        }
        final Path outFile = outDir.resolve("level.ofm");
        try
        {
            Files.write(outFile, bytes);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("could not write " + outFile, e);
        }
        LOG.info("Wrote {} ({} triangles, {} vertices, {} textures)",
            outFile, verified.indexCount() / 3, verified.vertexCount(), verified.textureCount());
        System.exit(EXIT_OK);
    }

    private static String option(final String[] args, final String prefix)
    {
        for (final String arg : args)
        {
            if (arg.startsWith(prefix))
            {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }
}
