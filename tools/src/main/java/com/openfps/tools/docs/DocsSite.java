/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.docs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the static documentation site from the repository's Markdown files.
 *
 * <p>Everything is done in two passes, because the sidebar on every page has to
 * list every other page: discovery reads all sources and works out titles,
 * sections and output names, then rendering writes each page with the complete
 * navigation already known.</p>
 *
 * <p>Link rewriting is the part that actually matters. A Markdown link such as
 * {@code [render/README.md](engine/src/main/java/com/openfps/engine/render/README.md)}
 * is resolved against the *source* file's directory, looked up in the page
 * index and replaced with the generated page's file name. A repository-relative
 * target that is not a generated page but does exist — {@code LICENSE},
 * {@code config/checkstyle/checkstyle.xml} — becomes a link into GitHub, since
 * a documentation site with no source tree beside it cannot serve it. Anything
 * left over is a broken link in the docs themselves and fails the build, which
 * is the whole reason the check is mechanical rather than a review step.</p>
 */
public final class DocsSite
{
    /** Where a repository-relative non-page target is sent. */
    private static final String BLOB = "https://github.com/nicholashutter/OpenFPS/blob/main/";

    /** Package root of the engine module; its child directories are the package pages. */
    private static final String ENGINE_PACKAGES = "engine/src/main/java/com/openfps/engine";

    /** Sidebar groups, in the order they appear. */
    private static final List<String> SECTIONS =
        List.of("Overview", "Building", "Architecture", "Modules", "Packages", "Reference");

    /** Groups that start collapsed: the deep material, present but not in the way. */
    private static final List<String> COLLAPSED = List.of("Packages", "Reference");

    /** Engine package reading order, as prescribed by the root README. */
    private static final List<String> PACKAGE_ORDER = List.of("core", "common", "hal", "gameplay",
        "render", "demo", "audio", "net", "resource", "memory");

    /** Module page order. */
    private static final List<String> MODULE_ORDER =
        List.of("engine.html", "desktop.html", "android.html", "tools.html");

    /** Sidebar labels that read better than the document's own first heading. */
    private static final Map<String, String> LABELS = Map.of(
        "README.md", "Overview",
        "AGENTS.md", "Agent instructions",
        "BUILD.md", "Build reference",
        "PLAN.md", "Project plan",
        "STYLE.md", "Code style",
        "docs/ASSETS.md", "Assets and licensing",
        "docs/DEMO_ASSETS.md", "Demo asset manifest",
        ENGINE_PACKAGES + "/README.md", ":engine");

    /** Directories never descended into during discovery. */
    private static final List<String> SKIP =
        List.of(".git", ".gradle", ".claude", "build", "bin", "out", "assets", "gradle");

    /** A URI scheme, so an absolute link is left alone. */
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    /** Splits a title at its em dash, leaving the short form. */
    private static final Pattern TITLE_TAIL = Pattern.compile("\\s+[\u2014-]\\s+.*$");

    /** Finds any link the rewriter failed to turn into a page. */
    private static final Pattern MARKDOWN_HREF =
        Pattern.compile("href=\"([^\":]*\\.md(?:#[^\"]*)?)\"");

    /** Fewest headings a page needs before it gets a contents list. */
    private static final int TOC_THRESHOLD = 4;

    /** Diagnostics, so the Gradle task reports what it did. */
    private static final Logger LOG = LoggerFactory.getLogger(DocsSite.class);

    /** Repository root; every source key is relative to this. */
    private final Path root;

    /** Where the generated site is written. */
    private final Path out;

    /** Every page, keyed by repository-relative source path. MUTABLE: filled by discovery. */
    private final Map<String, DocPage> pages = new LinkedHashMap<>();

    /** Links rewritten to another generated page. MUTABLE: counted during rendering. */
    private int internalLinks;

    /** Links rewritten to a file on GitHub. MUTABLE: counted during rendering. */
    private int repositoryLinks;

    /** Targets that resolved to nothing. MUTABLE: appended during rendering. */
    private final List<String> unresolved = new ArrayList<>();

    /**
     * Creates a builder.
     *
     * @param root the repository root
     * @param out the directory the site is written to; created if absent
     */
    public DocsSite(final Path root, final Path out)
    {
        this.root = root.toAbsolutePath().normalize();
        this.out = out.toAbsolutePath().normalize();
    }

    /**
     * Discovers the Markdown sources, renders them and writes the site.
     *
     * @throws IOException if a source cannot be read or a page cannot be written
     */
    public void build() throws IOException
    {
        discover();
        if (pages.isEmpty())
        {
            throw new IOException("No Markdown sources found under " + root);
        }
        Files.createDirectories(out);
        copyResource("site.css");
        copyResource("site.js");

        final Map<String, List<DocPage>> grouped = groupPages();
        for (final DocPage page : pages.values())
        {
            writePage(page, grouped);
        }

        LOG.info("Docs site: {} sources -> {} pages in {}", pages.size(), pages.size(), out);
        LOG.info("Links: {} rewritten between pages, {} sent to the repository on GitHub",
            internalLinks, repositoryLinks);
        if (!unresolved.isEmpty())
        {
            final StringBuilder message =
                new StringBuilder("Unresolved documentation links:\n");
            for (final String entry : unresolved)
            {
                message.append("  ").append(entry).append('\n');
            }
            throw new IOException(message.toString());
        }
        LOG.info("Links: 0 unresolved.");
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    private void discover() throws IOException
    {
        final List<Path> sources = new ArrayList<>();
        collectRootDocuments(sources);
        collectModuleReadmes(sources);
        collectEngineReadmes(sources);

        final List<DocPage> found = new ArrayList<>();
        for (final Path source : sources)
        {
            found.add(readPage(source));
        }
        found.sort(Comparator.<DocPage>comparingInt(DocsSite::sortKey)
            .thenComparing(DocPage::label));

        for (final DocPage page : found)
        {
            final DocPage clash = pages.put(page.key(), page);
            if (clash != null)
            {
                throw new IOException("Duplicate source key: " + page.key());
            }
        }
        verifyOutputNamesUnique();
    }

    private void collectRootDocuments(final List<Path> sources) throws IOException
    {
        try (Stream<Path> top = Files.list(root))
        {
            top.filter(Files::isRegularFile).filter(DocsSite::isMarkdown).forEach(sources::add);
        }
        final Path docs = root.resolve("docs");
        if (Files.isDirectory(docs))
        {
            try (Stream<Path> inner = Files.list(docs))
            {
                inner.filter(Files::isRegularFile).filter(DocsSite::isMarkdown)
                    .forEach(sources::add);
            }
        }
    }

    private void collectModuleReadmes(final List<Path> sources) throws IOException
    {
        try (Stream<Path> top = Files.list(root))
        {
            final List<Path> directories = top.filter(Files::isDirectory).toList();
            for (final Path directory : directories)
            {
                final String name = directory.getFileName().toString();
                if (SKIP.contains(name) || name.startsWith("."))
                {
                    continue;
                }
                final Path readme = directory.resolve("README.md");
                if (Files.isRegularFile(readme) && Files.isRegularFile(
                    directory.resolve("build.gradle.kts")))
                {
                    sources.add(readme);
                }
            }
        }
    }

    private void collectEngineReadmes(final List<Path> sources) throws IOException
    {
        final Path packageRoot = root.resolve(ENGINE_PACKAGES);
        if (!Files.isDirectory(packageRoot))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(packageRoot))
        {
            walk.filter(Files::isRegularFile)
                .filter(path -> "README.md".equals(path.getFileName().toString()))
                .forEach(sources::add);
        }
    }

    private static boolean isMarkdown(final Path path)
    {
        return path.getFileName().toString().endsWith(".md");
    }

    private DocPage readPage(final Path source) throws IOException
    {
        final List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        final String key = root.relativize(source).toString().replace('\\', '/');
        final String title = firstHeading(lines, key);
        return new DocPage(source, key, outputName(key), sectionOf(key), labelOf(key, title),
            title, Markdown.summarize(lines), lines);
    }

    private static String firstHeading(final List<String> lines, final String fallback)
    {
        for (final String line : lines)
        {
            if (line.startsWith("# "))
            {
                return Markdown.plain(line.substring(2));
            }
        }
        return fallback;
    }

    private static String sectionOf(final String key)
    {
        if ("README.md".equals(key) || "AGENTS.md".equals(key))
        {
            return "Overview";
        }
        if ("BUILD.md".equals(key))
        {
            return "Building";
        }
        if ("PLAN.md".equals(key) || "STYLE.md".equals(key))
        {
            return "Architecture";
        }
        if (key.startsWith("docs/"))
        {
            return "Reference";
        }
        if (key.equals(ENGINE_PACKAGES + "/README.md"))
        {
            return "Modules";
        }
        if (key.startsWith(ENGINE_PACKAGES + "/"))
        {
            return "Packages";
        }
        if (key.endsWith("/README.md"))
        {
            return "Modules";
        }
        return "Reference";
    }

    private static String labelOf(final String key, final String title)
    {
        final String fixed = LABELS.get(key);
        if (fixed != null)
        {
            return fixed;
        }
        final String shortened = TITLE_TAIL.matcher(title).replaceFirst("").strip();
        if (shortened.isEmpty())
        {
            return key;
        }
        return shortened;
    }

    private static String outputName(final String key)
    {
        if ("README.md".equals(key))
        {
            return "index.html";
        }
        if (key.equals(ENGINE_PACKAGES + "/README.md"))
        {
            return "engine.html";
        }
        if (key.startsWith(ENGINE_PACKAGES + "/"))
        {
            final String inner = key.substring(ENGINE_PACKAGES.length() + 1);
            return "engine-" + fileSlug(inner.substring(0, inner.length() - "/README.md".length()))
                + ".html";
        }
        if (key.endsWith("/README.md"))
        {
            return fileSlug(key.substring(0, key.indexOf('/'))) + ".html";
        }
        final int cut = key.lastIndexOf('/');
        final String name = key.substring(cut + 1, key.length() - ".md".length());
        return fileSlug(name) + ".html";
    }

    private static String fileSlug(final String name)
    {
        return name.toLowerCase(Locale.ROOT).replace('_', '-').replace('/', '-');
    }

    private void verifyOutputNamesUnique() throws IOException
    {
        final Map<String, String> seen = new LinkedHashMap<>();
        for (final DocPage page : pages.values())
        {
            final String clash = seen.put(page.output(), page.key());
            if (clash != null)
            {
                throw new IOException(
                    "Two sources map to " + page.output() + ": " + clash + " and " + page.key());
            }
        }
    }

    // Sorts pages into sidebar order: section first, then the curated order
    // within Packages and Modules, then whatever is left alphabetically.
    private static int sortKey(final DocPage page)
    {
        final int section = SECTIONS.indexOf(page.section()) * 1000;
        if ("Packages".equals(page.section()))
        {
            final String name = page.key().substring(ENGINE_PACKAGES.length() + 1)
                .replace("/README.md", "");
            final int index = PACKAGE_ORDER.indexOf(name);
            if (index >= 0)
            {
                return section + index;
            }
            return section + PACKAGE_ORDER.size();
        }
        if ("Modules".equals(page.section()))
        {
            final int index = MODULE_ORDER.indexOf(page.output());
            if (index >= 0)
            {
                return section + index;
            }
            return section + MODULE_ORDER.size();
        }
        if ("index.html".equals(page.output()))
        {
            return section;
        }
        return section + 1;
    }

    private Map<String, List<DocPage>> groupPages()
    {
        final Map<String, List<DocPage>> grouped = new LinkedHashMap<>();
        for (final String section : SECTIONS)
        {
            grouped.put(section, new ArrayList<>());
        }
        for (final DocPage page : pages.values())
        {
            grouped.computeIfAbsent(page.section(), key -> new ArrayList<>()).add(page);
        }
        grouped.values().removeIf(List::isEmpty);
        return grouped;
    }

    // ------------------------------------------------------------------
    // Link rewriting
    // ------------------------------------------------------------------

    /** Rewrites the links of one page against that page's own directory. */
    private final class Rewriter implements Markdown.LinkResolver
    {
        /** The source directory every relative target is resolved against. */
        private final String directory;

        /** The page being rendered, named only so a failure can say where. */
        private final String origin;

        Rewriter(final DocPage page)
        {
            this.directory = page.directory();
            this.origin = page.key();
        }

        @Override
        public String resolve(final String target)
        {
            if (target.isEmpty() || target.startsWith("#") || target.startsWith("//")
                || SCHEME.matcher(target).find())
            {
                return target;
            }
            String path = target;
            String fragment = "";
            final int hash = target.indexOf('#');
            if (hash >= 0)
            {
                path = target.substring(0, hash);
                fragment = target.substring(hash);
            }
            if (path.isEmpty())
            {
                return target;
            }
            final String resolved = normalize(directory + path);
            final DocPage page = pages.get(resolved);
            if (page != null)
            {
                internalLinks++;
                return page.output() + fragment;
            }
            if (Files.exists(root.resolve(resolved)))
            {
                repositoryLinks++;
                return BLOB + resolved + fragment;
            }
            unresolved.add(origin + " -> " + target);
            return target;
        }
    }

    private static String normalize(final String path)
    {
        final List<String> parts = new ArrayList<>();
        for (final String part : path.split("/"))
        {
            if (part.isEmpty() || ".".equals(part))
            {
                continue;
            }
            if ("..".equals(part))
            {
                if (!parts.isEmpty())
                {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void writePage(final DocPage page, final Map<String, List<DocPage>> grouped)
        throws IOException
    {
        final Markdown markdown = new Markdown(new Rewriter(page));
        final StringBuilder body = new StringBuilder(markdown.render(page.lines()));
        if ("index.html".equals(page.output()))
        {
            body.append(documentMap(grouped));
        }
        final String html = shell(page, body.toString(), markdown.headings(), grouped);

        final Matcher leftover = MARKDOWN_HREF.matcher(html);
        while (leftover.find())
        {
            unresolved.add(page.key() + " -> " + leftover.group(1) + " (still points at Markdown)");
        }
        Files.writeString(out.resolve(page.output()), html, StandardCharsets.UTF_8);
    }

    private String shell(final DocPage page, final String body,
        final List<Markdown.Heading> headings, final Map<String, List<DocPage>> grouped)
    {
        final StringBuilder html = new StringBuilder(body.length() + 8192);
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
            .append("<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<title>").append(Markdown.escape(page.title()))
            .append(" — OpenFPS documentation</title>\n");
        if (!page.summary().isEmpty())
        {
            html.append("<meta name=\"description\" content=\"")
                .append(Markdown.attribute(Markdown.escape(page.summary()))).append("\">\n");
        }
        html.append("<link rel=\"stylesheet\" href=\"site.css\">\n</head>\n<body>\n")
            .append("<a class=\"skip\" href=\"#doc\">Skip to content</a>\n")
            .append("<header class=\"topbar\">")
            .append("<button class=\"burger\" id=\"burger\" aria-controls=\"sidebar\"")
            .append(" aria-expanded=\"false\" aria-label=\"Show navigation\">")
            .append("<span></span><span></span><span></span></button>")
            .append("<span class=\"topbar-name\">OpenFPS docs</span></header>\n")
            .append("<div class=\"scrim\" id=\"scrim\" hidden></div>\n")
            .append("<div class=\"layout\">\n");
        sidebar(page, grouped, html);
        rail(headings, html);
        html.append("<main id=\"doc\">\n").append(meta(page)).append(body)
            .append(footer(page)).append("</main>\n</div>\n")
            .append("<script src=\"site.js\"></script>\n</body>\n</html>\n");
        return html.toString();
    }

    private void sidebar(final DocPage current, final Map<String, List<DocPage>> grouped,
        final StringBuilder html)
    {
        html.append("<aside class=\"sidebar\" id=\"sidebar\">\n")
            .append("<a class=\"brand\" href=\"index.html\">")
            .append("<span class=\"brand-mark\"></span>")
            .append("<span class=\"brand-name\">OpenFPS</span>")
            .append("<span class=\"brand-kind\">docs</span></a>\n")
            .append("<input class=\"filter\" id=\"filter\" type=\"search\" ")
            .append("placeholder=\"Filter pages\" aria-label=\"Filter pages\" autocomplete=\"off\">")
            .append("\n<nav class=\"nav\" id=\"nav\" aria-label=\"Documentation\">\n");

        int hue = 0;
        for (final Map.Entry<String, List<DocPage>> group : grouped.entrySet())
        {
            hue++;
            final boolean holdsCurrent = group.getValue().contains(current);
            html.append("<details class=\"grp\" data-hue=\"").append(hue).append('"');
            if (!COLLAPSED.contains(group.getKey()) || holdsCurrent)
            {
                html.append(" open");
            }
            html.append("><summary><span class=\"dot\"></span>")
                .append(Markdown.escape(group.getKey()))
                .append("<span class=\"count\">").append(group.getValue().size())
                .append("</span></summary>\n<ul>\n");
            for (final DocPage page : group.getValue())
            {
                html.append("<li><a href=\"").append(page.output()).append('"');
                if (page == current)
                {
                    html.append(" class=\"cur\" aria-current=\"page\"");
                }
                html.append('>').append(Markdown.escape(page.label())).append("</a></li>\n");
            }
            html.append("</ul></details>\n");
        }
        html.append("</nav>\n<p class=\"generated\">Generated from the repository's Markdown by ")
            .append("<code>:tools:buildDocsSite</code>.</p>\n</aside>\n");
    }

    private void rail(final List<Markdown.Heading> headings, final StringBuilder html)
    {
        final List<Markdown.Heading> shown = new ArrayList<>();
        for (final Markdown.Heading heading : headings)
        {
            if (heading.level() == 2 || heading.level() == 3)
            {
                shown.add(heading);
            }
        }
        if (shown.size() < TOC_THRESHOLD)
        {
            html.append("<aside class=\"rail\"></aside>\n");
            return;
        }
        html.append("<aside class=\"rail\"><details class=\"toc\" id=\"toc\" open>")
            .append("<summary>On this page</summary>\n<ul>\n");
        for (final Markdown.Heading heading : shown)
        {
            html.append("<li class=\"lv").append(heading.level()).append("\"><a href=\"#")
                .append(Markdown.attribute(heading.id())).append("\">")
                .append(Markdown.escape(heading.text())).append("</a></li>\n");
        }
        html.append("</ul></details></aside>\n");
    }

    private String meta(final DocPage page)
    {
        return "<p class=\"crumb\"><span class=\"tag\">" + Markdown.escape(page.section())
            + "</span><span class=\"sep\">/</span><code>" + Markdown.escape(page.key())
            + "</code><span class=\"sep\">/</span>" + page.readingMinutes() + " min read</p>\n";
    }

    private String footer(final DocPage page)
    {
        return "<footer class=\"pagefoot\"><p>Source: <a class=\"ext\" rel=\"noopener noreferrer\""
            + " target=\"_blank\" href=\"" + BLOB + page.key() + "\">" + Markdown.escape(page.key())
            + "</a>. This page is generated — edit the Markdown, not the HTML.</p></footer>\n";
    }

    // The landing page gets an index of everything, so the sheer volume of docs
    // is browsable from one screen instead of only through the sidebar.
    private String documentMap(final Map<String, List<DocPage>> grouped)
    {
        final StringBuilder html = new StringBuilder(4096);
        html.append("<hr>\n<h2 id=\"all-documentation\">All documentation")
            .append("<a class=\"anchor\" href=\"#all-documentation\" aria-label=\"Permalink\">#</a>")
            .append("</h2>\n");
        int hue = 0;
        for (final Map.Entry<String, List<DocPage>> group : grouped.entrySet())
        {
            hue++;
            html.append("<section class=\"mapsec\" data-hue=\"").append(hue)
                .append("\">\n<h3 class=\"mapgroup\"><span class=\"dot\"></span>")
                .append(Markdown.escape(group.getKey())).append("</h3>\n<div class=\"cards\">\n");
            for (final DocPage page : group.getValue())
            {
                html.append("<a class=\"card\" href=\"").append(page.output()).append("\">")
                    .append("<span class=\"card-title\">").append(Markdown.escape(page.label()))
                    .append("</span><span class=\"card-sub\">")
                    .append(Markdown.escape(page.title())).append("</span>");
                if (!page.summary().isEmpty())
                {
                    html.append("<span class=\"card-text\">")
                        .append(Markdown.escape(page.summary())).append("</span>");
                }
                html.append("<span class=\"card-meta\">").append(page.readingMinutes())
                    .append(" min</span></a>\n");
            }
            html.append("</div>\n</section>\n");
        }
        return html.toString();
    }

    private void copyResource(final String name) throws IOException
    {
        try (InputStream in = DocsSite.class.getResourceAsStream(name))
        {
            if (in == null)
            {
                throw new IOException("Missing packaged site resource: " + name);
            }
            Files.write(out.resolve(name), in.readAllBytes());
        }
    }
}
