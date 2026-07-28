/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MenuButtonListener} — the button-to-action wiring.
 *
 * {@code changed()} reads neither argument, so it can be invoked with
 * nulls. That is what makes the menu's wiring testable at all without a
 * Scene2D stage and a GL context behind it.
 */
class MenuButtonListenerTest
{
    @Test
    @DisplayName("a null action is rejected")
    void shouldRejectNullAction()
    {
        assertThatThrownBy(() -> new MenuButtonListener(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("action");
    }

    @Test
    @DisplayName("a change event runs the wrapped action exactly once")
    void shouldRunActionOnChange()
    {
        final AtomicInteger runs = new AtomicInteger();
        final MenuButtonListener listener = new MenuButtonListener(runs::incrementAndGet);

        listener.changed(null, null);
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("repeated activations run the action each time")
    void shouldRunActionPerChange()
    {
        final AtomicInteger runs = new AtomicInteger();
        final MenuButtonListener listener = new MenuButtonListener(runs::incrementAndGet);

        listener.changed(null, null);
        listener.changed(null, null);
        listener.changed(null, null);
        assertThat(runs.get()).isEqualTo(3);
    }

    // The end-to-end case — a Quit button that really closes a real window —
    // is DefaultMenuActionsTest.shouldCloseAWindowThroughTheQuitButtonWiring,
    // over in :desktop. It has to live there: this module deliberately knows
    // no backend, so it has no window to close.
}
