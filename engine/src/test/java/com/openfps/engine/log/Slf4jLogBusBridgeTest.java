/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Slf4jLogBusBridge#sourceFor(String)}: the prefix
 * logic that maps an SLF4J logger name to the engine's subsystem
 * source string.
 *
 * <p>The mapping is intentionally lenient &mdash; an SLF4J logger
 * named {@code com.openfps.engine.core.EngineMain} lands on
 * {@code "engine.core"}; one outside the project lands on
 * {@code "external"}; a {@code null} maps to {@code "external"}.
 * These tests pin the contract.</p>
 */
@DisplayName("Slf4jLogBusBridge.sourceFor")
class Slf4jLogBusBridgeTest
{
    @Test
    @DisplayName("a com.openfps.engine.core.* logger maps to engine.core")
    void shouldStripProjectPrefixAndMatchSubsystem()
    {
        assertThat(Slf4jLogBusBridge.sourceFor("com.openfps.engine.core.EngineMain"))
            .isEqualTo("engine.core");
    }

    @Test
    @DisplayName("a com.openfps.engine.gameplay.* logger maps to engine.gameplay")
    void shouldMapGameplayLogger()
    {
        assertThat(Slf4jLogBusBridge.sourceFor(
            "com.openfps.engine.gameplay.Match"))
            .isEqualTo("engine.gameplay");
    }

    @Test
    @DisplayName("a deeply-nested logger keeps the longest matching prefix")
    void shouldPickLongestMatchingPrefix()
    {
        // A logger inside the gameplay subsystem (e.g. a future
        // "engine.gameplay.match" subsystem) should still land on
        // "engine.gameplay" if the longer prefix is not yet a
        // registered subsystem.
        assertThat(Slf4jLogBusBridge.sourceFor(
            "com.openfps.engine.gameplay.match.MatchImpl"))
            .isEqualTo("engine.gameplay");
    }

    @Test
    @DisplayName("a logger outside com.openfps maps to external")
    void shouldMapNonEngineLoggerToExternal()
    {
        assertThat(Slf4jLogBusBridge.sourceFor("org.apache.commons.cli.DefaultParser"))
            .isEqualTo("external");
    }

    @Test
    @DisplayName("a null logger maps to external")
    void shouldMapNullToExternal()
    {
        assertThat(Slf4jLogBusBridge.sourceFor(null))
            .isEqualTo("external");
    }

    @Test
    @DisplayName("the bare project prefix is treated as external (no subsystem)")
    void shouldMapBareProjectPrefixToExternal()
    {
        // "com.openfps." alone is not a subsystem, so the prefix
        // strip leaves an empty string that won't match any
        // subsystem; the result is "external".
        assertThat(Slf4jLogBusBridge.sourceFor("com.openfps."))
            .isEqualTo("external");
    }
}