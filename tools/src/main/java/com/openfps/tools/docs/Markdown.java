/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.docs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the Markdown subset the OpenFPS documentation actually uses.
 *
 * <p>This is deliberately not a CommonMark implementation. docs/ASSETS.md § 4
 * sanctions third-party dependencies in {@code :tools} because build-time
 * tooling ships nothing, but every added artifact is one more name
 * {@code verifyToolsIsolation} has to police, and the corpus here is 21 files
 * written by one project to one house style. Its constructs were enumerated
 * before this class was written: ATX headings, fenced code, pipe tables, bullet
 * and ordered lists (including task lists and one level of nesting),
 * blockquotes, thematic breaks, links, images, bold, italic, strikethrough and
 * inline code. There are no setext headings, no reference links, no indented
 * code blocks, no raw HTML and no entity references anywhere in it. Supporting
 * exactly that list is a few hundred lines; supporting CommonMark is a
 * dependency.</p>
 *
 * <p>Two deviations from CommonMark are on purpose. Underscore emphasis is not
 * recognised, because the engine's own vocabulary is full of bare identifiers
 * such as {@code D_ / P_ / R_ / S_} that would otherwise turn into italics.
 * Indented code blocks are not recognised, because every four-space indent in
 * the corpus is either inside a fence already or a list-item continuation, and
 * honouring the rule would eat both.</p>
 *
 * <p>One instance renders one page: it accumulates that page's heading anchors,
 * so the slug de-duplication counter is per document.</p>
 */
public final class Markdown
{
    /** ATX heading, with optional closing hashes. */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    /** Thematic break. */
    private static final Pattern RULE = Pattern.compile("^ {0,3}(-{3,}|\\*{3,}|_{3,})\\s*$");

    /** Opening or closing code fence, with an optional info string. */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,})\\s*([A-Za-z0-9_+#-]*)\\s*$");

    /** Bullet list marker: indent, bullet, content. */
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+]) +(.*)$");

    /** Ordered list marker: indent, number, content. */
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,9})[.)] +(.*)$");

    /** Table header separator, for example {@code |---|:--:|}. */
    private static final Pattern DELIMITER = Pattern.compile("^\\s*\\|?[\\s:|-]*-[\\s:|-]*\\|?\\s*$");

    /** Task list checkbox at the head of a list item. */
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])\\] +(.*)$");

    /** Inline code span. */
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

    /** Inline image, matched before links so the leading bang is not orphaned. */
    private static final Pattern IMAGE =
        Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]*)(?:\\s+\"([^\"]*)\")?\\)");

    /** Inline link. */
    private static final Pattern LINK =
        Pattern.compile("\\[([^\\]]*)\\]\\(([^)\\s]*)(?:\\s+\"([^\"]*)\")?\\)");

    /** Bold. Spans newlines because paragraphs here are hard-wrapped. */
    private static final Pattern STRONG = Pattern.compile("(?s)\\*\\*(\\S(?:.*?\\S)?)\\*\\*");

    /** Italic. Single line only, and never opening on whitespace. */
    private static final Pattern EMPHASIS = Pattern.compile("\\*([^\\s*](?:[^*\\n]*[^\\s*])?)\\*");

    /** Strikethrough. */
    private static final Pattern STRIKE = Pattern.compile("(?s)~~(\\S(?:.*?\\S)?)~~");

    /**
     * Sentinel wrapping a lifted-out code span.
     *
     * <p>U+0001 cannot occur in these source files, so a placeholder built from
     * it cannot collide with document text.</p>
     */
    private static final String MARK = String.valueOf((char) 1);

    /** Restores a protected inline code span. */
    private static final Pattern PLACEHOLDER = Pattern.compile(MARK + "(\\d+)" + MARK);

    /** Characters a GitHub-compatible heading slug keeps before spaces fold to hyphens. */
    private static final Pattern SLUG_STRIP = Pattern.compile("[^a-z0-9 _-]");

    /** Longest summary kept for a page card, in characters. */
    private static final int SUMMARY_LIMIT = 190;

    /** Resolves a Markdown link target to something the generated site can serve. */
    public interface LinkResolver
    {
        /**
         * Rewrites one link target.
         *
         * @param target the href exactly as written in the Markdown source
         * @return the href to emit
         */
        String resolve(String target);
    }

    /** One heading, as collected for anchors and the per-page contents list. */
    public static final class Heading
    {
        /** Heading level, 1 to 6. */
        private final int level;

        /** The anchor id, unique within the page. */
        private final String id;

        /** The heading text with all inline markup removed. */
        private final String text;

        Heading(final int level, final String id, final String text)
        {
            this.level = level;
            this.id = id;
            this.text = text;
        }

        /**
         * Returns the heading level, 1 to 6.
         *
         * @return the heading level
         */
        public int level()
        {
            return level;
        }

        /**
         * Returns the anchor id, unique within the page.
         *
         * @return the anchor id
         */
        public String id()
        {
            return id;
        }

        /**
         * Returns the heading text with inline markup removed.
         *
         * @return the plain heading text
         */
        public String text()
        {
            return text;
        }
    }

    /** Where link targets are sent. */
    private final LinkResolver resolver;

    /** Headings seen so far. MUTABLE: appended as the document is rendered. */
    private final List<Heading> headings = new ArrayList<>();

    /** Slug occurrence counts, so a repeated heading gets a distinct anchor. MUTABLE. */
    private final Map<String, Integer> slugs = new HashMap<>();

    /**
     * Creates a renderer for one document.
     *
     * @param resolver rewrites link targets
     */
    public Markdown(final LinkResolver resolver)
    {
        this.resolver = resolver;
    }

    /**
     * Renders a whole document to an HTML fragment.
     *
     * @param lines the source lines, without line terminators
     * @return the HTML body fragment
     */
    public String render(final List<String> lines)
    {
        final StringBuilder out = new StringBuilder(lines.size() * 64);
        renderBlocks(lines, out);
        return out.toString();
    }

    /**
     * Returns the headings collected by the last render, in document order.
     *
     * @return the headings
     */
    public List<Heading> headings()
    {
        return headings;
    }

    // ------------------------------------------------------------------
    // Block level
    // ------------------------------------------------------------------

    private void renderBlocks(final List<String> lines, final StringBuilder out)
    {
        int index = 0;
        while (index < lines.size())
        {
            final String line = lines.get(index);
            if (line.isBlank())
            {
                index++;
                continue;
            }
            if (FENCE.matcher(line).matches())
            {
                index = renderFence(lines, index, out);
                continue;
            }
            final Matcher heading = HEADING.matcher(line);
            if (heading.matches())
            {
                renderHeading(heading.group(1).length(), heading.group(2), out);
                index++;
                continue;
            }
            if (RULE.matcher(line).matches())
            {
                out.append("<hr>\n");
                index++;
                continue;
            }
            if (isTableStart(lines, index))
            {
                index = renderTable(lines, index, out);
                continue;
            }
            if (line.stripLeading().startsWith(">"))
            {
                index = renderQuote(lines, index, out);
                continue;
            }
            if (BULLET.matcher(line).matches() || ORDERED.matcher(line).matches())
            {
                index = renderList(lines, index, out);
                continue;
            }
            index = renderParagraph(lines, index, out);
        }
    }

    private int renderFence(final List<String> lines, final int start, final StringBuilder out)
    {
        final Matcher open = FENCE.matcher(lines.get(start));
        open.matches();
        final String marker = open.group(1);
        final String language = open.group(2);
        final StringBuilder code = new StringBuilder();
        int index = start + 1;
        while (index < lines.size())
        {
            final Matcher close = FENCE.matcher(lines.get(index));
            if (close.matches() && close.group(1).length() >= marker.length()
                && close.group(2).isEmpty())
            {
                index++;
                break;
            }
            code.append(escape(lines.get(index))).append('\n');
            index++;
        }
        out.append("<div class=\"code\"");
        if (!language.isEmpty())
        {
            out.append(" data-lang=\"").append(attribute(language)).append('"');
        }
        out.append("><pre><code>").append(code).append("</code></pre></div>\n");
        return index;
    }

    private void renderHeading(final int level, final String source, final StringBuilder out)
    {
        final String text = plain(source);
        final String id = uniqueSlug(slug(text));
        headings.add(new Heading(level, id, text));
        out.append("<h").append(level).append(" id=\"").append(attribute(id)).append("\">")
            .append(inline(source))
            .append("<a class=\"anchor\" href=\"#").append(attribute(id))
            .append("\" aria-label=\"Permalink\">#</a>")
            .append("</h").append(level).append(">\n");
    }

    private int renderQuote(final List<String> lines, final int start, final StringBuilder out)
    {
        final List<String> inner = new ArrayList<>();
        int index = start;
        while (index < lines.size() && lines.get(index).stripLeading().startsWith(">"))
        {
            final String stripped = lines.get(index).stripLeading().substring(1);
            if (stripped.startsWith(" "))
            {
                inner.add(stripped.substring(1));
            }
            else
            {
                inner.add(stripped);
            }
            index++;
        }
        out.append("<blockquote>\n");
        renderBlocks(inner, out);
        out.append("</blockquote>\n");
        return index;
    }

    private int renderParagraph(final List<String> lines, final int start, final StringBuilder out)
    {
        final StringBuilder text = new StringBuilder(lines.get(start).strip());
        int index = start + 1;
        while (index < lines.size() && !startsBlock(lines, index))
        {
            text.append('\n').append(lines.get(index).strip());
            index++;
        }
        out.append("<p>").append(inline(text.toString())).append("</p>\n");
        return index;
    }

    // ------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------

    private boolean isTableStart(final List<String> lines, final int index)
    {
        if (lines.get(index).indexOf('|') < 0 || index + 1 >= lines.size())
        {
            return false;
        }
        final String next = lines.get(index + 1);
        return next.indexOf('|') >= 0 && DELIMITER.matcher(next).matches();
    }

    private int renderTable(final List<String> lines, final int start, final StringBuilder out)
    {
        final List<String> header = splitRow(lines.get(start));
        final List<String> alignments = alignmentsOf(splitRow(lines.get(start + 1)));
        out.append("<div class=\"tablewrap\"><table>\n<thead><tr>");
        for (int column = 0; column < header.size(); column++)
        {
            out.append("<th").append(alignmentAttribute(alignments, column)).append('>')
                .append(inline(header.get(column))).append("</th>");
        }
        out.append("</tr></thead>\n<tbody>\n");
        int index = start + 2;
        while (index < lines.size() && !lines.get(index).isBlank()
            && lines.get(index).indexOf('|') >= 0)
        {
            final List<String> cells = splitRow(lines.get(index));
            out.append("<tr>");
            for (int column = 0; column < cells.size(); column++)
            {
                out.append("<td").append(alignmentAttribute(alignments, column)).append('>')
                    .append(inline(cells.get(column))).append("</td>");
            }
            out.append("</tr>\n");
            index++;
        }
        out.append("</tbody>\n</table></div>\n");
        return index;
    }

    private static List<String> alignmentsOf(final List<String> delimiterCells)
    {
        final List<String> alignments = new ArrayList<>(delimiterCells.size());
        for (final String cell : delimiterCells)
        {
            final String trimmed = cell.strip();
            if (trimmed.startsWith(":") && trimmed.endsWith(":"))
            {
                alignments.add("center");
            }
            else if (trimmed.endsWith(":"))
            {
                alignments.add("right");
            }
            else
            {
                alignments.add("");
            }
        }
        return alignments;
    }

    private static String alignmentAttribute(final List<String> alignments, final int column)
    {
        if (column >= alignments.size() || alignments.get(column).isEmpty())
        {
            return "";
        }
        return " class=\"ta-" + alignments.get(column) + "\"";
    }

    // Splits a table row on unescaped pipes, dropping the optional outer pair.
    private static List<String> splitRow(final String line)
    {
        final List<String> cells = new ArrayList<>();
        final StringBuilder cell = new StringBuilder();
        final String trimmed = line.strip();
        int index = 0;
        if (trimmed.startsWith("|"))
        {
            index = 1;
        }
        while (index < trimmed.length())
        {
            final char current = trimmed.charAt(index);
            if (current == '\\' && index + 1 < trimmed.length() && trimmed.charAt(index + 1) == '|')
            {
                cell.append('|');
                index += 2;
                continue;
            }
            if (current == '|')
            {
                cells.add(cell.toString().strip());
                cell.setLength(0);
                index++;
                continue;
            }
            cell.append(current);
            index++;
        }
        if (cells.isEmpty() || !cell.toString().isBlank())
        {
            cells.add(cell.toString().strip());
        }
        return cells;
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    private int renderList(final List<String> lines, final int start, final StringBuilder out)
    {
        final boolean ordered = ORDERED.matcher(lines.get(start)).matches();
        final int baseIndent = indentOf(lines.get(start));
        final List<List<String>> items = new ArrayList<>();
        boolean loose = false;
        int index = start;

        while (index < lines.size())
        {
            final Matcher marker = markerAt(lines.get(index), ordered);
            if (marker == null || indentOf(lines.get(index)) != baseIndent)
            {
                break;
            }
            final int contentIndent = marker.start(3);
            final List<String> body = new ArrayList<>();
            body.add(marker.group(3));
            index++;

            while (index < lines.size())
            {
                int blanks = 0;
                while (index + blanks < lines.size() && lines.get(index + blanks).isBlank())
                {
                    blanks++;
                }
                final int next = index + blanks;
                if (next >= lines.size() || indentOf(lines.get(next)) < contentIndent)
                {
                    if (blanks > 0 && next < lines.size())
                    {
                        loose = true;
                    }
                    index = next;
                    break;
                }
                if (blanks > 0)
                {
                    loose = true;
                    for (int blank = 0; blank < blanks; blank++)
                    {
                        body.add("");
                    }
                }
                body.add(dedent(lines.get(next), contentIndent));
                index = next + 1;
            }
            items.add(body);
        }

        final String tag = listTag(ordered);
        out.append('<').append(tag).append(startAttribute(lines.get(start), ordered)).append(">\n");
        for (final List<String> body : items)
        {
            renderListItem(body, loose, out);
        }
        out.append("</").append(tag).append(">\n");
        return index;
    }

    private void renderListItem(final List<String> body, final boolean loose,
        final StringBuilder out)
    {
        final List<String> content = new ArrayList<>(body);
        // The checkbox is a class on the <li>, not an <input> spliced into the
        // text: everything below goes through inline(), which escapes markup,
        // and a loose item would otherwise put the box on its own line above
        // the first paragraph.
        String itemClass = "";
        final Matcher task = TASK.matcher(content.get(0));
        if (task.matches())
        {
            itemClass = " class=\"task todo\"";
            if (!" ".equals(task.group(1)))
            {
                itemClass = " class=\"task done\"";
            }
            content.set(0, task.group(2));
        }

        out.append("<li").append(itemClass).append('>');
        if (loose)
        {
            out.append('\n');
            renderBlocks(content, out);
        }
        else
        {
            int firstBlock = 1;
            while (firstBlock < content.size() && !startsBlock(content, firstBlock))
            {
                firstBlock++;
            }
            out.append(inline(String.join("\n", content.subList(0, firstBlock))));
            if (firstBlock < content.size())
            {
                out.append('\n');
                renderBlocks(content.subList(firstBlock, content.size()), out);
            }
        }
        out.append("</li>\n");
    }

    private static String listTag(final boolean ordered)
    {
        if (ordered)
        {
            return "ol";
        }
        return "ul";
    }

    private static String startAttribute(final String firstLine, final boolean ordered)
    {
        if (!ordered)
        {
            return "";
        }
        final Matcher marker = ORDERED.matcher(firstLine);
        if (!marker.matches() || "1".equals(marker.group(2)))
        {
            return "";
        }
        return " start=\"" + marker.group(2) + "\"";
    }

    private static Matcher markerAt(final String line, final boolean ordered)
    {
        Matcher marker = BULLET.matcher(line);
        if (ordered)
        {
            marker = ORDERED.matcher(line);
        }
        if (marker.matches())
        {
            return marker;
        }
        return null;
    }

    private boolean startsBlock(final List<String> lines, final int index)
    {
        final String line = lines.get(index);
        if (line.isBlank() || FENCE.matcher(line).matches() || HEADING.matcher(line).matches())
        {
            return true;
        }
        if (RULE.matcher(line).matches() || line.stripLeading().startsWith(">"))
        {
            return true;
        }
        if (BULLET.matcher(line).matches() || ORDERED.matcher(line).matches())
        {
            return true;
        }
        return isTableStart(lines, index);
    }

    private static int indentOf(final String line)
    {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ')
        {
            count++;
        }
        return count;
    }

    private static String dedent(final String line, final int columns)
    {
        return line.substring(Math.min(columns, indentOf(line)));
    }

    // ------------------------------------------------------------------
    // Inline level
    // ------------------------------------------------------------------

    private String inline(final String text)
    {
        final List<String> spans = new ArrayList<>();
        String work = protectCode(escape(text), spans);
        work = renderImages(work);
        work = renderLinks(work);
        work = STRONG.matcher(work).replaceAll("<strong>$1</strong>");
        work = EMPHASIS.matcher(work).replaceAll("<em>$1</em>");
        work = STRIKE.matcher(work).replaceAll("<del>$1</del>");
        return restoreCode(work, spans);
    }

    // Code spans are lifted out before any other inline rule runs, so the markup
    // characters inside `hal.adapter.*` or `*.ofm` stay literal.
    private static String protectCode(final String text, final List<String> spans)
    {
        final Matcher matcher = CODE_SPAN.matcher(text);
        final StringBuilder out = new StringBuilder(text.length());
        while (matcher.find())
        {
            spans.add(matcher.group(1));
            matcher.appendReplacement(out,
                Matcher.quoteReplacement(MARK + (spans.size() - 1) + MARK));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String restoreCode(final String text, final List<String> spans)
    {
        final Matcher matcher = PLACEHOLDER.matcher(text);
        final StringBuilder out = new StringBuilder(text.length());
        while (matcher.find())
        {
            final String code = spans.get(Integer.parseInt(matcher.group(1)));
            matcher.appendReplacement(out, Matcher.quoteReplacement("<code>" + code + "</code>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String renderImages(final String text)
    {
        final Matcher matcher = IMAGE.matcher(text);
        final StringBuilder out = new StringBuilder(text.length());
        while (matcher.find())
        {
            final StringBuilder tag = new StringBuilder("<img src=\"");
            tag.append(attribute(resolver.resolve(matcher.group(2))))
                .append("\" alt=\"").append(attribute(plain(matcher.group(1)))).append('"');
            if (matcher.group(3) != null)
            {
                tag.append(" title=\"").append(attribute(matcher.group(3))).append('"');
            }
            tag.append(" loading=\"lazy\">");
            matcher.appendReplacement(out, Matcher.quoteReplacement(tag.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String renderLinks(final String text)
    {
        final Matcher matcher = LINK.matcher(text);
        final StringBuilder out = new StringBuilder(text.length());
        while (matcher.find())
        {
            final String href = resolver.resolve(matcher.group(2));
            final StringBuilder tag = new StringBuilder("<a href=\"");
            tag.append(attribute(href)).append('"');
            if (matcher.group(3) != null)
            {
                tag.append(" title=\"").append(attribute(matcher.group(3))).append('"');
            }
            if (isExternal(href))
            {
                tag.append(" class=\"ext\" rel=\"noopener noreferrer\" target=\"_blank\"");
            }
            tag.append('>').append(matcher.group(1)).append("</a>");
            matcher.appendReplacement(out, Matcher.quoteReplacement(tag.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isExternal(final String href)
    {
        return href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//");
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    /**
     * Escapes the three characters that would otherwise be read as markup.
     *
     * @param text raw text
     * @return the same text, safe to place in an element body
     */
    public static String escape(final String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Escapes text for use inside a double-quoted HTML attribute.
     *
     * <p>Ampersands are deliberately left alone: every caller here passes a
     * value that has already been through {@link #escape(String)}, and escaping
     * twice turns a query string into {@code &amp;amp;}.</p>
     *
     * @param text ampersand-escaped text
     * @return the same text, safe inside double quotes
     */
    public static String attribute(final String text)
    {
        return text.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Strips inline markup, leaving readable plain text.
     *
     * <p>Used for anchor slugs, page titles, navigation labels and summaries —
     * everywhere a heading or sentence has to appear outside the rendered
     * body.</p>
     *
     * @param source Markdown source for one line or sentence
     * @return the same content with links, emphasis and code fences removed
     */
    public static String plain(final String source)
    {
        String work = IMAGE.matcher(source).replaceAll("");
        work = LINK.matcher(work).replaceAll("$1");
        work = work.replace("**", "").replace("~~", "").replace("`", "");
        work = EMPHASIS.matcher(work).replaceAll("$1");
        return work.strip();
    }

    /**
     * Condenses a document's opening prose into a one-line summary.
     *
     * @param lines the whole document
     * @return a short plain-text summary, possibly empty
     */
    public static String summarize(final List<String> lines)
    {
        final StringBuilder text = new StringBuilder();
        boolean seenHeading = false;
        for (final String line : lines)
        {
            final String trimmed = line.strip();
            if (HEADING.matcher(trimmed).matches())
            {
                if (seenHeading && text.length() > 0)
                {
                    break;
                }
                seenHeading = true;
                continue;
            }
            if (!seenHeading || trimmed.startsWith("[!["))
            {
                continue;
            }
            if (trimmed.isEmpty())
            {
                if (text.length() > 0)
                {
                    break;
                }
                continue;
            }
            String content = trimmed;
            if (content.startsWith(">"))
            {
                content = content.substring(1).strip();
            }
            if (text.length() > 0)
            {
                text.append(' ');
            }
            text.append(content);
        }
        return truncate(plain(text.toString()));
    }

    private static String truncate(final String text)
    {
        if (text.length() <= SUMMARY_LIMIT)
        {
            return text;
        }
        final int cut = text.lastIndexOf(' ', SUMMARY_LIMIT);
        if (cut < 0)
        {
            return text.substring(0, SUMMARY_LIMIT) + "…";
        }
        return text.substring(0, cut) + "…";
    }

    // GitHub's anchor rule: lowercase, drop punctuation, spaces become hyphens.
    // Matching it matters because README.md already links to `#running-the-demo`.
    private static String slug(final String text)
    {
        final String lowered = text.toLowerCase(Locale.ROOT);
        final String kept = SLUG_STRIP.matcher(lowered).replaceAll("");
        final String slugged = kept.strip().replace(' ', '-');
        if (slugged.isEmpty())
        {
            return "section";
        }
        return slugged;
    }

    private String uniqueSlug(final String base)
    {
        final Integer seen = slugs.get(base);
        if (seen == null)
        {
            slugs.put(base, 1);
            return base;
        }
        slugs.put(base, seen + 1);
        return base + "-" + seen;
    }
}
