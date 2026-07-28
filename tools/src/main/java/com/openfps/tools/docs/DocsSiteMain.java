/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.docs;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the documentation site generator.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>Why this lives in {@code :tools} rather than a script</h2>
 *
 * Every other generated artefact in this repository — converted models, staged
 * demo assets, headless preview frames — is produced by a class in this module
 * driven by a Gradle task. A shell or Python script would be the only build
 * step that needed a toolchain Gradle does not already provide and that CI
 * would have to install separately. It also gets Checkstyle and
 * {@code -Werror} for free, which a script does not, and
 * {@code verifyToolsIsolation} already proves this module reaches no shipped
 * classpath.
 *
 * <h2>Behaviour</h2>
 *
 * Reads every Markdown file the repository documents itself with and writes a
 * self-contained static site. Failure is loud: an unresolved link between two
 * documents exits non-zero, because that is a real broken link in the docs and
 * silence would let it rot.
 */
public final class DocsSiteMain
{
    /** Diagnostics, so the Gradle task reports what it did. */
    private static final Logger LOG = LoggerFactory.getLogger(DocsSiteMain.class);

    /** Exit status used when the arguments or the documents are wrong. */
    private static final int EXIT_FAILURE = 1;

    private DocsSiteMain()
    {
        // entry point holder
    }

    /**
     * Generates the site.
     *
     * @param args repository root then output directory
     */
    public static void main(final String[] args)
    {
        if (args.length != 2)
        {
            LOG.error("usage: DocsSiteMain <repositoryRoot> <outputDirectory>");
            System.exit(EXIT_FAILURE);
            return;
        }

        final Path root = Path.of(args[0]);
        final Path out = Path.of(args[1]);
        try
        {
            new DocsSite(root, out).build();
        }
        catch (final IOException e)
        {
            // A broken cross-document link arrives here with its own listing
            // already formatted; re-wrapping it would bury the paths.
            LOG.error("Documentation site build failed: {}", e.getMessage(), e);
            System.exit(EXIT_FAILURE);
        }
    }
}
