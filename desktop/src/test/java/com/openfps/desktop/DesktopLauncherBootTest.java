/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the launcher's boot decision: when is the demo
 * built, when is the map built, and when is the menu the first
 * thing the player sees?
 *
 * <p>The previous version of the launcher hardcoded the demo as
 * the default for any no-{@code --map=} run, which made the demo
 * the implicit "first thing" the user saw. A menu pick then
 * changed the renderer's scene in memory, but the demo was
 * still what was bound &mdash; the user saw the demo forever
 * even after picking a map. The bug is fixed by
 * {@link DesktopLauncher#buildDemoIfRequested} always returning
 * null and the boot path keying off {@code --model=}. This test
 * pins the policy so a future refactor does not regress it.</p>
 */
@DisplayName("DesktopLauncher boot decision")
class DesktopLauncherBootTest
{
    @Test
    @DisplayName("no --map= and no --model= builds no demo (the menu is the first thing)")
    void shouldNotBuildDemoForNoArgs()
    {
        // The previous bug: this returned a non-null DemoScene,
        // which the boot path then bound to the renderer, and
        // the user could never escape it. The fix is to
        // always return null here.
        assertThat(DesktopLauncher.buildDemoIfRequested(null, "assets/models"))
            .as("no args must not build the demo room — the menu is the first thing")
            .isNull();

        assertThat(DesktopLauncher.buildDemoIfRequested("", "assets/models"))
            .as("blank explicit model is the same as no model")
            .isNull();
    }

    @Test
    @DisplayName("--model= also builds no demo (the model is drawn separately, not the demo room)")
    void shouldNotBuildDemoForExplicitModel()
    {
        // The --model= case draws that one model via
        // bindWorld(renderer, null, explicitModel); the demo
        // room is not the default, and the boot path's
        // loadMapCallback still fires on a menu pick.
        assertThat(DesktopLauncher.buildDemoIfRequested("path/to/model.ofm", "assets/models"))
            .as("--model= draws that one model, not the demo room")
            .isNull();
    }

    @Test
    @DisplayName("arg parsing: --map= and --model= are read independently of each other")
    void shouldParseArgsIndependently()
    {
        // Sanity: the launcher's arg parsers do not interact.
        assertThat(DesktopLauncher.mapArg(new String[] {"--map=cornerstone"})).isEqualTo("cornerstone");

        assertThat(DesktopLauncher.mapArg(new String[] {"--map=cornerstone", "--model=foo.ofm"}))
            .isEqualTo("cornerstone");

        assertThat(DesktopLauncher.mapArg(new String[] {"--model=foo.ofm"})).isNull();

        assertThat(DesktopLauncher.mapArg(new String[] {})).isNull();

        assertThat(DesktopLauncher.modelArg(new String[] {"--model=foo.ofm"})).isEqualTo("foo.ofm");

        assertThat(DesktopLauncher.modelArg(new String[] {"--map=cornerstone"})).isNull();
    }
}
