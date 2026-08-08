/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MatchMode}.
 *
 * <p>The extension adds four new entries ({@link MatchMode#TDM},
 * {@link MatchMode#HARDPOINT}, {@link MatchMode#DOMINATION},
 * {@link MatchMode#CTF}) and a {@link MatchMode#isRuleSet()} helper. These
 * tests pin the new behaviour.</p>
 */
@DisplayName("MatchMode")
class MatchModeTest
{
    @Test
    @DisplayName("the four new rule-set entries are present")
    void shouldHaveAllFourRuleSets()
    {
        assertThat(MatchMode.valueOf("TDM")).isNotNull();
        assertThat(MatchMode.valueOf("HARDPOINT")).isNotNull();
        assertThat(MatchMode.valueOf("DOMINATION")).isNotNull();
        assertThat(MatchMode.valueOf("CTF")).isNotNull();
    }

    @Test
    @DisplayName("legacy entries are still present (backward compat)")
    void shouldKeepLegacyEntries()
    {
        assertThat(MatchMode.valueOf("SINGLE_PLAYER")).isNotNull();
        assertThat(MatchMode.valueOf("MULTIPLAYER")).isNotNull();
    }

    @Test
    @DisplayName("isRuleSet returns true for the four real rule sets")
    void shouldRecogniseRuleSets()
    {
        assertThat(MatchMode.TDM.isRuleSet()).isTrue();
        assertThat(MatchMode.HARDPOINT.isRuleSet()).isTrue();
        assertThat(MatchMode.DOMINATION.isRuleSet()).isTrue();
        assertThat(MatchMode.CTF.isRuleSet()).isTrue();
    }

    @Test
    @DisplayName("isRuleSet returns false for the legacy two")
    void shouldNotRecogniseLegacy()
    {
        assertThat(MatchMode.SINGLE_PLAYER.isRuleSet()).isFalse();
        assertThat(MatchMode.MULTIPLAYER.isRuleSet()).isFalse();
    }
}
