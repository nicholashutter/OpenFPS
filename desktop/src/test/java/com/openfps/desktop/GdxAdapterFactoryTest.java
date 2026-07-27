/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.adapter.nulladapter.NullAdapterFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link GdxAdapterFactory}'s decoration of an existing HAL
 * backend.
 *
 * The delegate here is {@link NullAdapterFactory} rather than the real
 * desktop one, so these stay off disk and off the network. What matters is
 * the decoration contract, which is backend-independent: every port except
 * the window comes straight from the delegate, and the window is the real
 * GLFW-backed one.
 */
class GdxAdapterFactoryTest
{
    private static GdxAdapterFactory factoryOver(final I_AdapterFactory delegate)
    {
        return new GdxAdapterFactory(delegate, new GdxWindowPort());
    }

    @Test
    @DisplayName("a null delegate is rejected")
    void shouldRejectNullDelegate()
    {
        assertThatThrownBy(() -> new GdxAdapterFactory(null, new GdxWindowPort()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delegate");
    }

    @Test
    @DisplayName("a null window port is rejected")
    void shouldRejectNullWindowPort()
    {
        assertThatThrownBy(() -> new GdxAdapterFactory(new NullAdapterFactory(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("windowPort");
    }

    @Test
    @DisplayName("every port except the window comes from the delegate")
    void shouldDelegateEveryPortExceptTheWindow()
    {
        final I_AdapterFactory delegate = new NullAdapterFactory();
        final GdxAdapterFactory factory = factoryOver(delegate);
        factory.init();
        try
        {
            assertThat(factory.getTimePort()).isSameAs(delegate.getTimePort());
            assertThat(factory.getInputPort()).isSameAs(delegate.getInputPort());
            assertThat(factory.getDatagramPort()).isSameAs(delegate.getDatagramPort());
            assertThat(factory.getFilePort()).isSameAs(delegate.getFilePort());
            assertThat(factory.getSystemInfoPort()).isSameAs(delegate.getSystemInfoPort());
            assertThat(factory.getUserProfilePort()).isSameAs(delegate.getUserProfilePort());
            assertThat(factory.getWindowPort()).isNotSameAs(delegate.getWindowPort());
        }
        finally
        {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("the window port is the real GLFW-backed one")
    void shouldSupplyARealWindow()
    {
        final GdxAdapterFactory factory = factoryOver(new NullAdapterFactory());
        factory.init();
        try
        {
            assertThat(factory.getWindowPort()).isInstanceOf(GdxWindowPort.class);
            assertThat(factory.getWindowPort().isRealWindow()).isTrue();
            assertThat(factory.getWindowPort().isCloseRequested()).isFalse();
        }
        finally
        {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("init configures the window so runFrameLoop is legal")
    void shouldConfigureWindowDuringInit()
    {
        final GdxWindowPort window = new GdxWindowPort();
        final GdxAdapterFactory factory = new GdxAdapterFactory(new NullAdapterFactory(), window);
        assertThat(window.state()).isEqualTo(GdxWindowPort.State.NEW);

        factory.init();
        assertThat(window.state()).isEqualTo(GdxWindowPort.State.CREATED);

        factory.shutdown();
        assertThat(window.state()).isEqualTo(GdxWindowPort.State.SHUTDOWN);
    }
}
