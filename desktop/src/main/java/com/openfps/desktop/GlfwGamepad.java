/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;

import com.openfps.gdx.AnalogStick;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A desktop gamepad, read straight from GLFW.
 *
 * <h2>Why there is no gamepad library here</h2>
 *
 * <p>libGDX's own answer to controllers is the {@code gdx-controllers}
 * extension, whose desktop backend is Jamepad and therefore SDL — several
 * megabytes of precompiled native library for Windows, macOS and Linux, plus a
 * second licence chain in a {@code NOTICE} file that is maintained by hand.
 * <b>It would buy nothing this module does not already have.</b> The LWJGL3
 * backend already puts {@code org.lwjgl:lwjgl-glfw} on this module's compile
 * and runtime classpath, GLFW 3.3 already exposes a complete gamepad API, and —
 * the part that actually matters — GLFW already carries the SDL controller
 * mapping database internally, so {@code glfwGetGamepadState} reports the same
 * standardised Xbox-shaped layout for a DualShock, a Switch Pro pad and an
 * eight-year-old third-party clone that SDL itself would.</p>
 *
 * <p>So the choice was between shipping SDL twice and calling the copy that is
 * already in the process. Six functions later, this file is the whole desktop
 * backend, the distribution is unchanged, and {@code NOTICE} gains a paragraph
 * about a native library it was already recording rather than an entry for a
 * new one.</p>
 *
 * <h2>The first usable pad wins</h2>
 *
 * <p>GLFW numbers joystick slots 0..15 and they are not compacted: unplugging
 * the pad in slot 0 does not move the one in slot 1 down. So every poll scans
 * from the bottom and takes the first slot holding something GLFW recognises as
 * a <i>gamepad</i>, which is what makes unplug-and-replug work without any
 * bookkeeping — the scan simply finds it somewhere else next frame.</p>
 *
 * <p>A joystick GLFW has no mapping for is deliberately skipped rather than
 * read raw. Without a mapping there is no way to know which axis is the left
 * stick, and guessing produces a game that walks sideways when the player
 * pushes forward. Reporting no controller is the honest outcome, and it is
 * logged once so the reason is discoverable.</p>
 *
 * <h2>Threading and lifecycle</h2>
 *
 * <p>{@link #poll()} must run on the thread that owns the window — GLFW
 * requires it, and the LWJGL3 backend's render callback is that thread, which
 * is where {@code GdxInputPort.pollDevice} already runs. Nothing is allocated
 * per frame: the state struct is taken from LWJGL's thread-local stack and
 * released by the try-with-resources, so a controller costs no garbage.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class GlfwGamepad implements GamepadSource
{
    /** Reported by {@link #name()} when no controller is connected. */
    public static final String NO_PAD = "none";

    /** How many joystick slots GLFW exposes. Slots are not compacted on unplug. */
    private static final int SLOTS = GLFW.GLFW_JOYSTICK_LAST + 1;

    /** Buttons in a GLFW gamepad state — A through D-pad left. */
    private static final int BUTTONS = GLFW.GLFW_GAMEPAD_BUTTON_LAST + 1;

    /** Axes in a GLFW gamepad state — two sticks and two triggers. */
    private static final int AXES = GLFW.GLFW_GAMEPAD_AXIS_LAST + 1;

    private static final Logger LOG = LoggerFactory.getLogger(GlfwGamepad.class);

    /**
     * Button levels from the last poll, indexed by GLFW button constant.
     * MUTABLE: overwritten every poll, zeroed on disconnect. Render thread only.
     */
    private final boolean[] buttons = new boolean[BUTTONS];

    /**
     * Button levels from the poll before that, for edge detection.
     * MUTABLE: render thread only.
     */
    private final boolean[] previousButtons = new boolean[BUTTONS];

    /**
     * Axis readings from the last poll, raw and unshaped, indexed by GLFW axis
     * constant. MUTABLE: overwritten every poll, zeroed on disconnect.
     */
    private final float[] axes = new float[AXES];

    /** Which joystick slot is being read, or −1 for none. MUTABLE: per poll. */
    private int slot = -1;

    /** The connected pad's name, or {@link #NO_PAD}. MUTABLE: per poll. */
    private String padName = NO_PAD;

    /**
     * Whether an unmapped joystick has already been reported.
     * MUTABLE: latched once so a permanently attached flight stick does not
     * write a log line every frame for the rest of the session.
     */
    private boolean warnedUnmapped;

    @Override
    public void poll()
    {
        System.arraycopy(buttons, 0, previousButtons, 0, BUTTONS);
        final int found = findGamepadSlot();
        slot = found;
        if (found < 0)
        {
            forget();
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            final GLFWGamepadState state = GLFWGamepadState.malloc(stack);
            if (!GLFW.glfwGetGamepadState(found, state))
            {
                // Present a moment ago, gone between the scan and the read.
                // Genuinely possible — this is a USB device — and the honest
                // answer is "no controller", which forget() makes the accessors
                // give.
                slot = -1;
                forget();
                return;
            }
            for (int index = 0; index < BUTTONS; index++)
            {
                buttons[index] = state.buttons(index) == GLFW.GLFW_PRESS;
            }
            for (int index = 0; index < AXES; index++)
            {
                axes[index] = state.axes(index);
            }
        }
        padName = gamepadName(found);
    }

    @Override
    public boolean isConnected()
    {
        return slot >= 0;
    }

    @Override
    public String name()
    {
        return padName;
    }

    @Override
    public boolean isButtonDown(final int buttonIndex)
    {
        if (!isConnected() || buttonIndex < 0 || buttonIndex >= BUTTONS)
        {
            return false;
        }
        return buttons[buttonIndex];
    }

    @Override
    public boolean didButtonGoDown(final int buttonIndex)
    {
        if (!isConnected() || buttonIndex < 0 || buttonIndex >= BUTTONS)
        {
            return false;
        }
        return buttons[buttonIndex] && !previousButtons[buttonIndex];
    }

    @Override
    public boolean isAxisPressed(final int axisIndex)
    {
        if (!isConnected())
        {
            return false;
        }
        if (axisIndex != GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER
            && axisIndex != GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)
        {
            // A stick axis. "Is the left stick pressed" has no answer — see
            // GamepadSource.isAxisPressed — so this degrades rather than
            // guesses, which is what stops a stick bound to MOVE_FORWARD from
            // also reading as a held button.
            return false;
        }
        // GLFW rests its triggers at -1 because a trigger is a joystick axis to
        // it. AnalogStick owns the single threshold, in a normalised 0..1.
        return AnalogStick.isTriggerPulled(
            AnalogStick.triggerFromCentred(axes[axisIndex]));
    }

    @Override
    public float leftStickX()
    {
        return axis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
    }

    @Override
    public float leftStickY()
    {
        return axis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);
    }

    @Override
    public float rightStickX()
    {
        return axis(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X);
    }

    @Override
    public float rightStickY()
    {
        return axis(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y);
    }

    /** Returns which GLFW joystick slot is being read, or −1 for none. */
    public int slot()
    {
        return slot;
    }

    /** Returns a debug rendering of the connected pad. */
    @Override
    public String toString()
    {
        return "GlfwGamepad[slot=" + slot + ", name=" + padName + "]";
    }

    // The lowest slot holding something GLFW has a gamepad mapping for, or -1.
    //
    // Wrapped because this is the one path that can be reached before GLFW is
    // initialised: GdxInputPort guards on Gdx.input, which is non-null only once
    // Lwjgl3Application has called glfwInit, but a future caller need not. A
    // controller is a convenience and must never be the reason the game does not
    // start, so a native call that goes wrong costs the pad and nothing else.
    private int findGamepadSlot()
    {
        try
        {
            return scanSlots();
        }
        catch (final RuntimeException e)
        {
            if (!warnedUnmapped)
            {
                warnedUnmapped = true;
                LOG.warn("GLFW joystick query failed ({}) — running without a controller",
                    e.toString());
            }
            return -1;
        }
    }

    // The scan itself. Separate so the guard above reads as a guard.
    private int scanSlots()
    {
        int unmapped = -1;
        for (int candidate = 0; candidate < SLOTS; candidate++)
        {
            if (!GLFW.glfwJoystickPresent(candidate))
            {
                continue;
            }
            if (GLFW.glfwJoystickIsGamepad(candidate))
            {
                return candidate;
            }
            unmapped = candidate;
        }
        if (unmapped >= 0 && !warnedUnmapped)
        {
            warnedUnmapped = true;
            LOG.warn("Joystick in slot {} has no GLFW gamepad mapping — ignoring it."
                + " Without a mapping there is no way to tell which axis is which,"
                + " and guessing walks the player sideways.", Integer.valueOf(unmapped));
        }
        return -1;
    }

    // The pad's name, defensively: glfwGetGamepadName returns null for a slot
    // that emptied between the read and this call.
    private static String gamepadName(final int candidate)
    {
        final String reported = GLFW.glfwGetGamepadName(candidate);
        if (reported == null)
        {
            return NO_PAD;
        }
        return reported;
    }

    // Returns everything to rest. Called the moment a pad is not there, so the
    // accessors never describe a device that has gone — the caller still has to
    // clear the accumulator, because a level it already stored is not this
    // object's to reach.
    private void forget()
    {
        padName = NO_PAD;
        for (int index = 0; index < BUTTONS; index++)
        {
            buttons[index] = false;
        }
        for (int index = 0; index < AXES; index++)
        {
            axes[index] = 0.0f;
        }
    }

    // One axis, or zero when there is nothing to read.
    private float axis(final int axisIndex)
    {
        if (!isConnected())
        {
            return 0.0f;
        }
        return axes[axisIndex];
    }
}
