/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.app.Activity;
import android.os.Bundle;

/**
 * Android entry point — PLACEHOLDER.
 *
 * This exists so the module compiles, lint passes, and the APK is
 * installable while the real launcher is written. It shows a blank screen
 * and starts no engine.
 *
 * The real implementation extends libGDX's {@code AndroidApplication} and
 * calls {@code initialize(listener, config)} with an
 * {@code ApplicationListener} that drives
 * {@code I_WindowPort.runFrameLoop}'s callback. See
 * {@code hal/port/I_WindowPort.java} — on Android the framework owns the
 * frame loop, so the adapter registers a callback rather than pumping.
 */
public final class AndroidLauncher extends Activity
{
    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
    }
}
