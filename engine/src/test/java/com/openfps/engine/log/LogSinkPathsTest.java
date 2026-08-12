/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogSinkPaths}: the resolution policy that turns
 * the various configuration sources into a concrete log-file path.
 *
 * <p>The precedence is fixed and tested explicitly:</p>
 * <ol>
 *   <li>{@code -Dopenfps.log.file} system property (developer
 *       override),</li>
 *   <li>{@code OPENFPS_LOG_FILE} environment variable (CI / Docker),</li>
 *   <li>walked-up directory containing {@code settings.gradle.kts}.</li>
 * </ol>
 *
 * <p>The literal {@link LogFileSink#DISABLED_SENTINEL "off"} disables
 * the sink.</p>
 */
@DisplayName("LogSinkPaths")
class LogSinkPathsTest
{
    private String savedSystemProp;
    private boolean hadSystemProp;

    @BeforeEach
    void saveSystemProperty()
    {
        savedSystemProp = System.getProperty(LogSinkPaths.SYSTEM_PROPERTY);

        hadSystemProp = savedSystemProp != null;

        // Start every test from a clean state; each test re-sets
        // only what it cares about.
        System.clearProperty(LogSinkPaths.SYSTEM_PROPERTY);
    }

    @AfterEach
    void restoreSystemProperty()
    {
        if (hadSystemProp)
        {
            System.setProperty(LogSinkPaths.SYSTEM_PROPERTY, savedSystemProp);
        }
        else
        {
            System.clearProperty(LogSinkPaths.SYSTEM_PROPERTY);
        }
    }

    @Test
    @DisplayName("explicit system property wins, no env required")
    void shouldHonorSystemProperty()
    {
        final Path explicit = Path.of("build", "test", "explicit.log");

        System.setProperty(LogSinkPaths.SYSTEM_PROPERTY,
            explicit.toAbsolutePath().toString());

        assertThat(LogSinkPaths.resolve()).isEqualTo(explicit.toAbsolutePath());
    }

    @Test
    @DisplayName("explicit system property of \"off\" disables the sink")
    void shouldDisableViaSystemProperty()
    {
        System.setProperty(LogSinkPaths.SYSTEM_PROPERTY,
            LogFileSink.DISABLED_SENTINEL);

        assertThat(LogSinkPaths.resolve()).isNull();
    }

    @Test
    @DisplayName("blank system property falls through to the default")
    void shouldIgnoreBlankSystemProperty()
    {
        System.setProperty(LogSinkPaths.SYSTEM_PROPERTY, "   ");

        // The default walks up to find settings.gradle.kts; in
        // the test cwd that is the project root. The exact path
        // depends on the runner's working directory, so we only
        // assert the sink is NOT disabled and the path ends in
        // the conventional log directory.
        final Path resolved = LogSinkPaths.resolve();

        assertThat(resolved).isNotNull();

        assertThat(resolved.getFileName().toString()).startsWith("openfps-");

        assertThat(resolved.toString()).contains("logs");
    }

    @Test
    @DisplayName("resolveAt rejects a null timestamp")
    void shouldRejectNullNow()
    {
        assertThatThrownBy(() -> LogSinkPaths.resolveAt(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("now");
    }

    @Test
    @DisplayName("resolveWith: sysprop wins over env wins over default")
    void shouldPreferSyspropOverEnvOverDefault()
    {
        final Path sysprop = Path.of("sysprop.log").toAbsolutePath();
        final Path env = Path.of("env.log").toAbsolutePath();

        final Path resolved = LogSinkPaths.resolveWith(env.toString(),
            sysprop.toString());

        assertThat(resolved).isEqualTo(sysprop);
    }

    @Test
    @DisplayName("resolveWith: env is used when sysprop is null")
    void shouldFallThroughFromSyspropToEnv()
    {
        final Path env = Path.of("env-only.log").toAbsolutePath();

        final Path resolved = LogSinkPaths.resolveWith(env.toString(), null);

        assertThat(resolved).isEqualTo(env);
    }

    @Test
    @DisplayName("resolveWith: env value of \"off\" disables the sink")
    void shouldDisableViaEnvOverride()
    {
        final Path resolved = LogSinkPaths.resolveWith(
            LogFileSink.DISABLED_SENTINEL, null);

        assertThat(resolved).isNull();
    }

    @Test
    @DisplayName("resolveWith: blank sysprop falls through to env")
    void shouldTreatBlankSyspropAsAbsent()
    {
        final Path env = Path.of("env-only.log").toAbsolutePath();

        final Path resolved = LogSinkPaths.resolveWith(env.toString(), "   ");

        assertThat(resolved).isEqualTo(env);
    }

    @Test
    @DisplayName("resolveWith with both null uses the default")
    void shouldUseDefaultWhenBothNull()
    {
        final Path resolved = LogSinkPaths.resolveWith(null, null);

        assertThat(resolved).isNotNull();

        assertThat(resolved.toString()).contains("openfps-");
    }

    @Nested
    @DisplayName("default walking")
    class DefaultWalking
    {
        @Test
        @DisplayName("walks up to find settings.gradle.kts")
        void shouldWalkUpToGradleRoot()
        {
            // The runner's cwd during :engine:test is the project
            // root, so the walk-up must terminate at it.
            final Path resolved = LogSinkPaths.resolve();

            assertThat(resolved).isNotNull();

            assertThat(resolved.toString()).contains("logs");

            assertThat(resolved.toString()).contains("openfps-");
        }
    }

    @Nested
    @DisplayName("system-property contract")
    class SystemPropertyContract
    {
        @Test
        @DisplayName("SYSTEM_PROPERTY constant has the documented name")
        void shouldExposeStableSystemPropertyName()
        {
            // A user reading the README will set this by hand; the
            // constant exists so it does not get out of sync with
            // the property name parsed here.
            assertThat(LogSinkPaths.SYSTEM_PROPERTY).isEqualTo("openfps.log.file");

            assertThat(LogSinkPaths.ENV_VARIABLE).isEqualTo("OPENFPS_LOG_FILE");
        }

        @Test
        @DisplayName("reflection guards against renaming the system property")
        void shouldKeepSystemPropertyFieldFinal() throws Exception
        {
            // If a future edit renames the constant via field rename
            // but the system-property string inside is left behind,
            // users would set the wrong property name. The reverse
            // risk &mdash; field renamed, string kept in sync &mdash;
            // is acceptable, but the field must stay final.
            final Field field = LogSinkPaths.class.getField("SYSTEM_PROPERTY");

            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                .as("SYSTEM_PROPERTY must stay final")
                .isTrue();
        }
    }
}
