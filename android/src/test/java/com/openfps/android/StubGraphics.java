/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.badlogic.gdx.Graphics;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A {@link Graphics} that reports a fixed surface size and frame delta.
 *
 * {@link GdxLifecycleBridge} reads exactly three things from the global
 * {@code Gdx.graphics}: the width and height at create time, and the delta on
 * every frame. Everything else on that interface — some fifty methods about
 * monitors, display modes, cursors and GL version — is irrelevant to the
 * bridge and would be fifty hand-written stubs that break whenever libGDX
 * adds a method.
 *
 * So this is a JDK dynamic proxy rather than an {@code implements Graphics}
 * class: three methods answer, every other returns its type's zero value, and
 * no test dependency is added to do it. It stands in for a device, and no test
 * may treat what it returns as evidence about a real one.
 */
final class StubGraphics
{
    private StubGraphics()
    {
        // utility class
    }

    /**
     * Creates a graphics stand-in.
     *
     * @param width the surface width to report
     * @param height the surface height to report
     * @param deltaSeconds the frame delta to report
     * @return a Graphics suitable for assignment to {@code Gdx.graphics}
     */
    static Graphics of(final int width, final int height, final float deltaSeconds)
    {
        return (Graphics) Proxy.newProxyInstance(
            Graphics.class.getClassLoader(),
            new Class<?>[] {Graphics.class},
            (proxy, method, args) -> answer(proxy, method, args, width, height, deltaSeconds));
    }

    // Answers the three calls the bridge makes, plus the Object methods a
    // proxy must handle itself; everything else gets a zero value.
    private static Object answer(final Object proxy, final Method method, final Object[] args,
                                 final int width, final int height, final float deltaSeconds)
    {
        final String name = method.getName();
        if ("getWidth".equals(name) || "getBackBufferWidth".equals(name))
        {
            return width;
        }
        if ("getHeight".equals(name) || "getBackBufferHeight".equals(name))
        {
            return height;
        }
        if ("getDeltaTime".equals(name))
        {
            return deltaSeconds;
        }
        if ("hashCode".equals(name))
        {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name))
        {
            return proxy == args[0];
        }
        if ("toString".equals(name))
        {
            return "StubGraphics[" + width + "x" + height + "]";
        }
        return zeroValue(method.getReturnType());
    }

    // Returns the default value for a return type, so an unstubbed primitive
    // getter does not blow up on unboxing a null.
    private static Object zeroValue(final Class<?> type)
    {
        if (!type.isPrimitive() || type == void.class)
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == char.class)
        {
            return Character.valueOf('\0');
        }
        if (type == long.class)
        {
            return Long.valueOf(0L);
        }
        if (type == float.class)
        {
            return Float.valueOf(0f);
        }
        if (type == double.class)
        {
            return Double.valueOf(0d);
        }
        if (type == byte.class)
        {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class)
        {
            return Short.valueOf((short) 0);
        }
        return Integer.valueOf(0);
    }
}
