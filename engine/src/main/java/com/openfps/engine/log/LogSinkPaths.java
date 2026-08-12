/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Path resolution for {@link LogFileSink}.
 *
 * <p>Three sources are consulted in order; the first non-blank
 * wins. The default, when nothing is set, walks up from the
 * current working directory looking for a {@code settings.gradle.kts}
 * and writes into a sibling {@code logs/} directory there. The
 * Gradle JavaExec tasks default the working directory to a
 * subproject (e.g. {@code engine/} or {@code desktop/}), so a
 * relative {@code logs/openfps.log} would land in the wrong
 * place &mdash; walking up to the project root is the same
 * project-root discovery that {@code BuildAudit.resolveLogDir()}
 * already uses, and it is the convention this class follows.</p>
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>{@code openfps.log.file} system property (set with
 *       {@code -Dopenfps.log.file=path} on the JVM command line).
 *       May be absolute or relative to {@code user.dir}.</li>
 *   <li>{@code OPENFPS_LOG_FILE} environment variable. Same shape.</li>
 *   <li>Default: {@code <projectRoot>/logs/openfps-<timestamp>.log}
 *       where {@code projectRoot} is the directory containing
 *       {@code settings.gradle.kts}.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 *
 * <p>The path is read once on bootstrap from the calling thread
 * and is therefore a snapshot. Calls later in the run that should
 * pick up a new value (a test that flips the system property
 * between two {@code install} calls) only see the new value if
 * that second install is made after the system property was set.
 * The bus does not re-resolve on its own.</p>
 */
public final class LogSinkPaths
{
    /** System property name; same spelling as the env var, prefixed
     *  with {@code openfps.} to follow the project's existing
     *  {@code -Dopenfps.*} convention. */
    public static final String SYSTEM_PROPERTY = "openfps.log.file";

    /** Environment variable name; the standard SCREAMING_SNAKE_CASE form
     *  of the system property. */
    public static final String ENV_VARIABLE = "OPENFPS_LOG_FILE";

    /** Default log file name (stem); the timestamp suffix is added
     *  at install time. */
    public static final String DEFAULT_FILENAME_STEM = "openfps";

    /** Suffix of the default filename &mdash; matches the project's
     *  {@code *.log} {@code .gitignore} rule. */
    public static final String DEFAULT_FILENAME_SUFFIX = ".log";

    /** Default directory (sibling to {@code settings.gradle.kts} when
     *  one is found). */
    public static final String DEFAULT_DIR_NAME = "logs";

    /** Timestamp format used in the default filename. The locale
     *  is fixed so the format is stable across JVMs. */
    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private LogSinkPaths()
    {
        // Static utility.
    }

    /**
     * Resolves the log file path using the three-source policy.
     *
     * @return the resolved absolute path; never null; never blank
     */
    public static Path resolve()
    {
        return resolveAt(LocalDateTime.now());
    }

    /**
     * Resolves the log file path with a caller-supplied timestamp.
     * Exists for tests; production callers use {@link #resolve()}.
     *
     * @param now the timestamp baked into the default filename
     * @return the resolved absolute path; never null; never blank
     */
    public static Path resolveAt(final LocalDateTime now)
    {
        if (now == null)
        {
            throw new IllegalArgumentException("now must not be null");
        }

        // The disabled sentinel short-circuits regardless of which
        // source it came from. If the user set -Dopenfps.log.file=off,
        // they want the sink off; we do not silently fall through
        // to the default and write a log file anyway.
        if (isDisabledSentinelSet())
        {
            return null;
        }

        final Path fromProperty = resolveFromSystemProperty();

        if (fromProperty != null)
        {
            return fromProperty;
        }

        final Path fromEnv = resolveFromEnv();

        if (fromEnv != null)
        {
            return fromEnv;
        }

        return defaultPath(now);
    }

    // True if either the system property or the environment variable
    // carries the DISABLED_SENTINEL. Independent of resolveFrom*
    // because resolveFrom* returns null on sentinel (and so does
    // not disambiguate "absent" from "disabled"); this method
    // disambiguates the two for resolveAt.
    private static boolean isDisabledSentinelSet()
    {
        final String prop = System.getProperty(SYSTEM_PROPERTY);

        if (prop != null && !prop.isBlank()
            && LogFileSink.DISABLED_SENTINEL.equals(prop))
        {
            return true;
        }

        final String env = System.getenv(ENV_VARIABLE);

        return env != null && !env.isBlank()
            && LogFileSink.DISABLED_SENTINEL.equals(env);
    }

    /**
     * Test-only overload that bypasses the live system property
     * and environment. Pass {@code null} to fall through to the
     * next source. Used by tests that don't want to mutate the
     * process-wide environment.
     *
     * @param envOverride     the {@code OPENFPS_LOG_FILE} value to
     *                        consult instead of the live env var;
     *                        null to ignore
     * @param syspropOverride the {@code openfps.log.file} value to
     *                        consult instead of the live system
     *                        property; null to ignore
     * @return the resolved absolute path; never null; never blank
     */
    public static Path resolveWith(final String envOverride, final String syspropOverride)
    {
        return resolveWith(envOverride, syspropOverride, LocalDateTime.now());
    }

    /**
     * Test-only overload of {@link #resolveWith} that lets the
     * caller also pin the timestamp used in the default filename.
     *
     * @param envOverride     the {@code OPENFPS_LOG_FILE} override; null to ignore
     * @param syspropOverride the {@code openfps.log.file} override; null to ignore
     * @param now             the timestamp for the default filename
     * @return the resolved absolute path; never null; never blank
     */
    public static Path resolveWith(final String envOverride, final String syspropOverride,
        final LocalDateTime now)
    {
        // Test path: respect the disabled sentinel at any layer
        // exactly the way resolveAt() does in production.
        if (syspropOverride != null && LogFileSink.DISABLED_SENTINEL.equals(syspropOverride))
        {
            return null;
        }

        if (envOverride != null && LogFileSink.DISABLED_SENTINEL.equals(envOverride))
        {
            return null;
        }

        if (syspropOverride != null && !syspropOverride.isBlank())
        {
            return Paths.get(syspropOverride).toAbsolutePath();
        }

        if (envOverride != null && !envOverride.isBlank())
        {
            return Paths.get(envOverride).toAbsolutePath();
        }

        // Both null / blank: defer to the production resolver.
        return resolveAt(now);
    }

    // Reads the openfps.log.file system property; null if unset
    // or blank. The path is resolved relative to user.dir so a
    // -Dopenfps.log.file=logs/out.log from a subproject's cwd
    // still lands in the subproject's logs/, exactly the way the
    // developer typed it.
    private static Path resolveFromSystemProperty()
    {
        final String raw = System.getProperty(SYSTEM_PROPERTY);

        if (raw == null || raw.isBlank())
        {
            return null;
        }

        if (LogFileSink.DISABLED_SENTINEL.equals(raw))
        {
            return null;
        }

        return Paths.get(raw).toAbsolutePath();
    }

    // Reads the OPENFPS_LOG_FILE env var; null if unset or blank.
    // Same shape as the system property: relative paths are
    // resolved against user.dir.
    private static Path resolveFromEnv()
    {
        final String raw = System.getenv(ENV_VARIABLE);

        if (raw == null || raw.isBlank())
        {
            return null;
        }

        if (LogFileSink.DISABLED_SENTINEL.equals(raw))
        {
            return null;
        }

        return Paths.get(raw).toAbsolutePath();
    }

    // Walks up from user.dir looking for settings.gradle.kts, then
    // writes a sibling logs/openfps-<ts>.log there. Falls back to
    // user.dir/logs/openfps-<ts>.log if no Gradle root is found
    // (running from a JAR in a non-Gradle cwd, e.g. a release zip).
    private static Path defaultPath(final LocalDateTime now)
    {
        final String filename = DEFAULT_FILENAME_STEM + "-"
            + TS.format(now) + DEFAULT_FILENAME_SUFFIX;

        final Path cwd = Paths.get(System.getProperty("user.dir"))
            .toAbsolutePath();

        Path dir = cwd;

        while (dir != null)
        {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")))
            {
                return dir.resolve(DEFAULT_DIR_NAME).resolve(filename);
            }

            final Path parent = dir.getParent();

            if (parent == null || parent.equals(dir))
            {
                break;
            }

            dir = parent;
        }

        // No Gradle project root found: write into a logs/ dir under
        // user.dir, the JAR-launched case.
        return cwd.resolve(DEFAULT_DIR_NAME).resolve(filename);
    }
}
