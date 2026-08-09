/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.format;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Inserts exactly one blank line between every pair of consecutive
 * statements inside every {@link BlockStmt} in a Java source tree.
 *
 * <p>Build-time only &mdash; this class is never on a runtime classpath.
 * Implements STYLE.md &sect; 14 mechanically: walks each {@code BlockStmt} in
 * the file's AST, finds every (A, B) pair of consecutive statements, and
 * rewrites the gap between them so that it contains exactly one blank line.
 * Where the gap already has 2+ blank lines, the extras are removed; where it
 * has none, one is inserted; where it has one, the file is left untouched.
 * Leading and trailing comments on the next statement, and trailing comments
 * on the previous statement, are preserved verbatim &mdash; the algorithm
 * never deletes a non-blank line, it only adds blank lines and removes
 * duplicate blank lines.</p>
 *
 * <h2>Why this is a build tool, not a Spotless plugin</h2>
 *
 * <p>Spotless / google-java-format do not expose a "blank line between every
 * statement" rule. A regex pass would mangle generics, string literals and
 * annotations. JavaParser's AST gives statement ranges exactly, and the
 * reflow is local to the gap text &mdash; the rest of the file is byte-for-
 * byte preserved. See {@code docs/STYLE.md} &sect; 14 for the rule itself
 * and the rationale.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   gradlew :tools:formatBlankLines --args="&lt;path1&gt; [&lt;path2&gt; ...]"
 * </pre>
 *
 * <p>Each argument is either a {@code .java} file or a directory. Directories
 * are walked recursively for {@code .java} files; the {@code build/} and
 * {@code .gradle/} trees are skipped. Files that fail to parse are reported
 * to stderr and left untouched.</p>
 *
 * <p>Deliberately NOT wired into {@code build}: this rewrites source files,
 * and a tool that touches every {@code .java} in the tree should not run as
 * a side-effect of compiling.</p>
 */
public final class BlankLineFormatter
{
    /** A pending edit, expressed as a line index in the ORIGINAL file. */
    private static final class Edit implements Comparable<Edit>
    {
        /** Zero-based line index in the unmodified file. */
        final int lineIndex;
        /** True to insert a blank line at this index; false to remove it. */
        final boolean insert;

        Edit(final int lineIndex, final boolean insert)
        {
            this.lineIndex = lineIndex;

            this.insert = insert;
        }

        @Override
        public int compareTo(final Edit other)
        {
            // Descending: highest original index first, so an edit cannot
            // shift a not-yet-applied edit's target line.
            return Integer.compare(other.lineIndex, this.lineIndex);
        }

        @Override
        public String toString()
        {
            final String kind;

            if (insert)
            {
                kind = "INSERT";
            }
            else
            {
                kind = "REMOVE";
            }

            return kind + "@" + lineIndex;
        }
    }

    private BlankLineFormatter()
    {
        // Static utility; not instantiable.
    }

    /**
     * Parser configuration that matches the engine source tree. The engine
     * uses Java 17 features (notably Java 16+ {@code instanceof} patterns
     * and Java 14+ {@code switch} expressions), and JavaParser's default
     * language level is older; without this override the parser rejects
     * those constructs as syntax errors before AST construction runs.
     */
    private static final ParserConfiguration PARSER_CONFIG =
        new ParserConfiguration().setLanguageLevel(
            ParserConfiguration.LanguageLevel.JAVA_17
        );

    static
    {
        StaticJavaParser.setConfiguration(PARSER_CONFIG);
    }

    /**
     * Entry point. See class Javadoc for argument syntax.
     *
     * @param args paths to {@code .java} files or directories
     * @throws IOException if a file cannot be read or written
     */
    public static void main(final String[] args) throws IOException
    {
        if (args.length < 1)
        {
            System.err.println(
                "Usage: BlankLineFormatter <path1> [path2 ...]\n"
                + "  Each path is a .java file or a directory to walk recursively.\n"
                + "  build/ and .gradle/ trees are skipped automatically."
            );

            System.exit(1);

            return;
        }

        int total = 0;

        int changed = 0;

        int failed = 0;

        for (final String arg : args)
        {
            final List<Path> files = expand(Path.of(arg));

            for (final Path file : files)
            {
                total++;

                final int result = processFile(file);

                if (result == 1)
                {
                    changed++;
                }
                else if (result < 0)
                {
                    failed++;
                }
            }
        }

        System.out.println(
            "BlankLineFormatter: " + changed + " changed, "
            + (total - changed - failed) + " already conformant, "
            + failed + " failed, " + total + " total"
        );
    }

    /**
     * Expands a path into the list of {@code .java} files to process.
     * Directories are walked recursively; {@code build/} and {@code .gradle/}
     * subtrees are skipped.
     *
     * @param root the path to expand
     * @return the list of {@code .java} files to process (possibly empty)
     * @throws IOException if directory walking fails
     */
    private static List<Path> expand(final Path root) throws IOException
    {
        final List<Path> out = new ArrayList<>();

        if (Files.isRegularFile(root))
        {
            if (root.toString().endsWith(".java"))
            {
                out.add(root);
            }

            return out;
        }

        if (!Files.isDirectory(root))
        {
            return out;
        }

        try (var stream = Files.walk(root))
        {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p ->
                {
                    final String s = p.toString();

                    return !s.contains("build" + java.io.File.separator)
                        && !s.contains(".gradle" + java.io.File.separator);
                })
                .forEach(out::add);
        }

        Collections.sort(out);

        return out;
    }

    /**
     * Processes one file in place.
     *
     * @param file the {@code .java} file to reformat
     * @return 1 if the file was rewritten, 0 if it was already conformant,
     *         -1 if it could not be parsed
     * @throws IOException if the file cannot be read or written
     */
    private static int processFile(final Path file) throws IOException
    {
        final String content = Files.readString(file, StandardCharsets.UTF_8);

        final CompilationUnit cu;

        try
        {
            cu = StaticJavaParser.parse(content);
        }
        catch (final ParseProblemException e)
        {
            System.err.println("PARSE ERROR in " + file + ": " + e.getMessage());

            return -1;
        }

        // Collect (endLine, beginLine) for every consecutive statement pair,
        // top-down so edits don't perturb a pair we haven't processed yet.
        final List<int[]> pairs = new ArrayList<>();

        cu.walk(BlockStmt.class, block ->
        {
            final List<Statement> stmts = block.getStatements();

            for (int i = 0; i + 1 < stmts.size(); i++)
            {
                final Statement a = stmts.get(i);

                final Statement b = stmts.get(i + 1);

                final Optional<Range> aEnd = a.getRange();

                final Optional<Range> bBegin = b.getRange();

                if (aEnd.isPresent() && bBegin.isPresent())
                {
                    final int endLine = aEnd.get().end.line;

                    final int beginLine = bBegin.get().begin.line;

                    if (endLine < beginLine)
                    {
                        pairs.add(new int[] {endLine, beginLine});
                    }
                }
            }
        });

        pairs.sort((x, y) -> Integer.compare(x[1], y[1]));

        // Preserve the file's line endings: detect CRLF vs LF before splitting,
        // otherwise the rewrite would flip every line's terminator.
        final String lineEnding;

        if (content.contains("\r\n"))
        {
            lineEnding = "\r\n";
        }
        else
        {
            lineEnding = "\n";
        }

        final String[] splitLines = content.split("\r?\n", -1);

        final List<String> lines = new ArrayList<>(Arrays.asList(splitLines));

        final List<Edit> edits = new ArrayList<>();

        for (final int[] pair : pairs)
        {
            final int endLine = pair[0];

            final int beginLine = pair[1];

            // gapStart: zero-based index of the first line after A's last line.
            // gapEnd:   zero-based index of the last line before B's first line.
            final int gapStart = endLine;

            final int gapEnd = beginLine - 2;

            if (gapStart > gapEnd)
            {
                // A and B are on adjacent lines with nothing between them.
                edits.add(new Edit(gapStart, true));

                continue;
            }

            int firstBlank = -1;

            for (int i = gapStart; i <= gapEnd; i++)
            {
                if (lines.get(i).trim().isEmpty())
                {
                    firstBlank = i;

                    break;
                }
            }

            if (firstBlank < 0)
            {
                // No blank line in the gap; insert one at the start of the gap,
                // i.e. immediately after A's last line. If A's last line carries
                // a trailing `// ...` comment, the blank lands after that line
                // and before any leading comments on B or B itself.
                edits.add(new Edit(gapStart, true));
            }
            else
            {
                // At least one blank already; remove every subsequent blank so
                // exactly one survives, the leftmost one.
                for (int i = gapEnd; i > firstBlank; i--)
                {
                    if (lines.get(i).trim().isEmpty())
                    {
                        edits.add(new Edit(i, false));
                    }
                }
            }
        }

        if (edits.isEmpty())
        {
            return 0;
        }

        Collections.sort(edits);

        for (final Edit edit : edits)
        {
            if (edit.insert)
            {
                lines.add(edit.lineIndex, "");
            }
            else
            {
                lines.remove(edit.lineIndex);
            }
        }

        final String rewritten = String.join(lineEnding, lines);

        if (rewritten.equals(content))
        {
            return 0;
        }

        Files.writeString(file, rewritten, StandardCharsets.UTF_8);

        System.out.println("Reformatted: " + file + " (" + edits.size() + " edits)");

        return 1;
    }
}
