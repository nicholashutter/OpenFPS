/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogFileFormat}: a {@link LogEvent} turned into
 * the multi-line shape written to {@code openfps-*.log}.
 *
 * <p>The test fixture builds {@link LogEvent}s directly via the
 * public constructor &mdash; the bus does not expose static
 * factories, and adding them would be test-only API creep. The
 * helper {@link #event} keeps call sites readable.</p>
 */
@DisplayName("LogFileFormat")
class LogFileFormatTest
{
    private static final long FIXED_TS = 1_700_000_000_000L;

    private static LogEvent event(final LogLevel level, final String source,
        final String message)
    {
        return event(level, source, message, null);
    }

    private static LogEvent event(final LogLevel level, final String source,
        final String message, final Throwable cause)
    {
        return new LogEvent(FIXED_TS, source, "com.openfps.engine.test", level,
            message, cause);
    }

    @Test
    @DisplayName("a minimal event produces one header line plus a trailing newline")
    void shouldFormatMinimalEvent()
    {
        final String formatted = LogFileFormat.format(event(LogLevel.INFO,
            "engine.core", "boot complete"));

        // format() always ends with a newline so multiple
        // concatenations land on separate lines. split("\n", -1)
        // returns a trailing empty string for that newline; the
        // body must be exactly one header line.
        final String[] lines = formatted.split("\n", -1);

        assertThat(lines).hasSize(2);

        assertThat(lines[1]).isEmpty();

        final String header = lines[0];

        assertThat(header).contains("INFO ");

        assertThat(header).contains("engine.core");

        assertThat(header).contains("boot complete");

        // The header must start with a bracketed year-digit
        // timestamp; the format is "[YYYY-MM-DD HH:MM:SS.mmm]".
        assertThat(header).startsWith("[20");

        assertThat(header).contains("] ");
    }

    @Test
    @DisplayName("five-letter levels stay five letters; shorter levels pad with a trailing space")
    void shouldPadShorterLevels()
    {
        assertThat(LogFileFormat.format(event(LogLevel.WARN, "engine.core", "x"))
            .split("\n", -1)[0]).contains("WARN ");

        assertThat(LogFileFormat.format(event(LogLevel.ERROR, "engine.core", "x"))
            .split("\n", -1)[0]).contains("ERROR");

        assertThat(LogFileFormat.format(event(LogLevel.TRACE, "engine.core", "x"))
            .split("\n", -1)[0]).contains("TRACE");
    }

    @Test
    @DisplayName("an event with a cause appends a stack trace indented under the line")
    void shouldAppendCauseStack()
    {
        final RuntimeException cause = new RuntimeException("boom");

        final String formatted = LogFileFormat.format(event(LogLevel.ERROR,
            "engine.gameplay", "shot failed", cause));

        assertThat(formatted).contains("shot failed");

        assertThat(formatted).contains("java.lang.RuntimeException");

        assertThat(formatted).contains("boom");

        // Stack frames start with TAB-SPACE.
        assertThat(formatted).contains("\tat ");
    }

    @Test
    @DisplayName("an event without a cause does not start a stack section")
    void shouldNotAppendBlankStackWhenNoCause()
    {
        final String formatted = LogFileFormat.format(event(LogLevel.INFO,
            "engine.render", "no-cause"));

        final String[] lines = formatted.split("\n", -1);

        // Body is just the header line; the trailing newline is
        // the second element.
        assertThat(lines).hasSize(2);

        assertThat(lines[1]).isEmpty();

        assertThat(formatted).doesNotContain("\tat ");
    }

    @Test
    @DisplayName("the source field uses the event's source string verbatim")
    void shouldUseEventSource()
    {
        final String formatted = LogFileFormat.format(event(LogLevel.INFO,
            "custom-bus", "hello"));

        assertThat(formatted).contains("custom-bus");

        // The subsystem prefix convention is "engine.<name>"; a
        // non-conforming name must NOT be auto-mangled.
        assertThat(formatted).doesNotContain("engine.engine.");
    }

    @Test
    @DisplayName("each formatted header starts with a bracketed year-digit timestamp")
    void shouldEmitTimestamp()
    {
        final String formatted = LogFileFormat.format(event(LogLevel.INFO,
            "engine.core", "boot"));

        // The formatter prepends "[" and a year-digit token
        // immediately after. We assert that the prefix of the
        // string matches the date pattern; we don't anchor on
        // the end because the format always ends with a
        // newline that .* does not cover.
        final java.util.regex.Pattern header = java.util.regex.Pattern
            .compile("\\[\\d{4}-\\d{2}-\\d{2}");

        assertThat(header.matcher(formatted).find()).isTrue();
    }

    @Test
    @DisplayName("the formatter is idempotent over multiple events")
    void shouldBeIdempotent()
    {
        final String first = LogFileFormat.format(event(LogLevel.INFO,
            "engine.core", "a"));

        final String second = LogFileFormat.format(event(LogLevel.INFO,
            "engine.core", "a"));

        assertThat(first).isEqualTo(second);
    }
}
