/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.docs;

import java.nio.file.Path;
import java.util.List;

/**
 * One Markdown source file and the page the site generator makes of it.
 *
 * <p>Immutable. The source lines are read once during discovery, because the
 * navigation for every page needs every other page's title and summary before
 * the first page can be written.</p>
 */
public final class DocPage
{
    /** Words per minute used for the reading estimate. */
    private static final int READING_RATE = 210;

    /** Absolute path of the Markdown source. */
    private final Path source;

    /** Repository-relative path with forward slashes; the key link rewriting resolves against. */
    private final String key;

    /** File name of the generated page, for example {@code engine-net.html}. */
    private final String output;

    /** Which sidebar group this page belongs to. */
    private final String section;

    /** Short label shown in the sidebar. */
    private final String label;

    /** Full title, taken from the document's first heading. */
    private final String title;

    /** One-line plain-text summary of the opening prose. */
    private final String summary;

    /** The source lines, without line terminators. */
    private final List<String> lines;

    /**
     * Records one page.
     *
     * @param source absolute path of the Markdown file
     * @param key repository-relative path, forward slashes
     * @param output file name of the page to generate
     * @param section sidebar group name
     * @param label short sidebar label
     * @param title full page title
     * @param summary one-line summary, possibly empty
     * @param lines the source lines
     */
    public DocPage(final Path source, final String key, final String output, final String section,
        final String label, final String title, final String summary, final List<String> lines)
    {
        this.source = source;
        this.key = key;
        this.output = output;
        this.section = section;
        this.label = label;
        this.title = title;
        this.summary = summary;
        this.lines = List.copyOf(lines);
    }

    /**
     * Returns the absolute path of the Markdown source.
     *
     * @return the source path
     */
    public Path source()
    {
        return source;
    }

    /**
     * Returns the repository-relative source path, with forward slashes.
     *
     * @return the link-resolution key
     */
    public String key()
    {
        return key;
    }

    /**
     * Returns the generated page's file name.
     *
     * @return the output file name
     */
    public String output()
    {
        return output;
    }

    /**
     * Returns the sidebar group this page belongs to.
     *
     * @return the section name
     */
    public String section()
    {
        return section;
    }

    /**
     * Returns the short sidebar label.
     *
     * @return the label
     */
    public String label()
    {
        return label;
    }

    /**
     * Returns the full page title.
     *
     * @return the title
     */
    public String title()
    {
        return title;
    }

    /**
     * Returns the one-line summary of the opening prose.
     *
     * @return the summary, possibly empty
     */
    public String summary()
    {
        return summary;
    }

    /**
     * Returns the source lines.
     *
     * @return an unmodifiable list of lines
     */
    public List<String> lines()
    {
        return lines;
    }

    /**
     * Returns the directory the source sits in, as a link-resolution prefix.
     *
     * @return the parent directory key, empty for a repository-root file
     */
    public String directory()
    {
        final int cut = key.lastIndexOf('/');
        if (cut < 0)
        {
            return "";
        }
        return key.substring(0, cut + 1);
    }

    /**
     * Returns a rounded reading time in minutes, never less than one.
     *
     * @return the estimated minutes to read this page
     */
    public int readingMinutes()
    {
        int words = 0;
        for (final String line : lines)
        {
            if (!line.isBlank())
            {
                words += line.strip().split("\\s+").length;
            }
        }
        return Math.max(1, (words + READING_RATE / 2) / READING_RATE);
    }
}
