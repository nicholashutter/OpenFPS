/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.openfps.tools.gltf.GltfConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the build-time model converter.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>Why a {@code main} lives here</h2>
 *
 * {@code AGENTS.md} forbids a {@code public static void main} in non-core
 * packages. That rule guards the engine against stray entry points competing
 * with {@code EngineMain}; it has no purchase on a separate, unshipped module
 * whose entire reason to exist is to be invoked by Gradle. The entry point is
 * kept in its own class so {@link GltfConverter} stays a library with no
 * process-level behaviour.
 *
 * <h2>Behaviour</h2>
 *
 * Converts every {@code .gltf} and {@code .glb} beneath the input directory
 * into a matching {@code .ofm} under the output directory, preserving the
 * relative path. A missing input directory is reported and exits successfully:
 * asset conversion is opt-in and {@code fetchAssets} is deliberately not wired
 * into {@code build}, so a clean clone has nothing to convert and that is not
 * an error. A budget violation is the opposite — it exits non-zero so Gradle
 * fails the build.
 */
public final class GltfConverterMain
{
    private static final Logger LOG = LoggerFactory.getLogger(GltfConverterMain.class);

    /** Extension written for a converted model: OpenFPS Model. */
    private static final String OUTPUT_EXTENSION = ".ofm";

    /** Exit status used when the arguments or the assets are wrong. */
    private static final int EXIT_FAILURE = 1;

    private GltfConverterMain()
    {
        // entry point holder
    }

    /**
     * Converts a directory tree of glTF assets.
     *
     * @param args input directory then output directory
     */
    public static void main(final String[] args)
    {
        if (args.length != 2)
        {
            LOG.error("usage: GltfConverterMain <inputDirectory> <outputDirectory>");

            System.exit(EXIT_FAILURE);

            return;
        }

        final Path input = Path.of(args[0]);

        final Path output = Path.of(args[1]);

        if (!Files.isDirectory(input))
        {
            LOG.warn("No model source directory at {} — nothing to convert."
                + " Run fetchAssets first, or pass -PmodelsIn=<dir>.", input);

            return;
        }

        try
        {
            convertTree(input, output);
        }
        catch (final RuntimeException e)
        {
            // Budget violations and malformed assets both land here. The
            // message already names the file and the value; re-wrapping it
            // would bury that, so it is logged and the process fails.
            LOG.error("Asset conversion failed: {}", e.getMessage(), e);

            System.exit(EXIT_FAILURE);
        }
    }

    /**
     * Converts every glTF asset beneath {@code input} into {@code output}.
     *
     * @param input directory to scan, recursively
     * @param output directory to write into; the input's relative structure is kept
     */
    public static void convertTree(final Path input, final Path output)
    {
        final List<Path> sources = findSources(input);

        if (sources.isEmpty())
        {
            LOG.warn("No .gltf or .glb files found under {}", input);

            return;
        }

        for (final Path source : sources)
        {
            GltfConverter.convert(source, output.resolve(targetName(input.relativize(source))));
        }

        LOG.info("Converted {} models into {}", sources.size(), output);
    }

    // Collects every .gltf / .glb beneath a directory, in a stable order.
    private static List<Path> findSources(final Path input)
    {
        final List<Path> sources = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(input))
        {
            walk.filter(GltfConverterMain::isModelSource).sorted().forEach(sources::add);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("cannot scan " + input, e);
        }

        return sources;
    }

    // True for a regular file whose extension marks it as a glTF asset.
    private static boolean isModelSource(final Path path)
    {
        if (!Files.isRegularFile(path))
        {
            return false;
        }

        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

        return name.endsWith(".gltf") || name.endsWith(".glb");
    }

    // Replaces the source extension with the runtime model extension.
    private static Path targetName(final Path relative)
    {
        final String name = relative.getFileName().toString();

        final int dot = name.lastIndexOf('.');

        final String stem = name.substring(0, dot);

        final Path parent = relative.getParent();

        if (parent == null)
        {
            return Path.of(stem + OUTPUT_EXTENSION);
        }

        return parent.resolve(stem + OUTPUT_EXTENSION);
    }
}
