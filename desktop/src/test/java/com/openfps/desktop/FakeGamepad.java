/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.util.HashSet;
import java.util.Set;

import com.openfps.gdx.AnalogStick;

/**
 * A controller a test can hold in its hand.
 *
 * <p>The reason {@link GamepadSource} is an interface. Everything that can
 * actually go wrong with a gamepad — a stick left at full deflection when the
 * cable is pulled, a button edge that fires on the wrong frame, a vertical axis
 * whose sign is upside down — needs a device to reproduce, and CI has none. This
 * class is that device: fields, no natives, and an {@link #unplug()} that stages
 * the one failure a human with a USB cable can produce and a build server never
 * can.</p>
 *
 * <p>Modelled on {@link GlfwGamepad}'s contract rather than on convenience: it
 * reports zeros and falses while disconnected, and it computes button edges
 * against the previous {@link #poll()} the same way GLFW's level-only API forces
 * the real one to.</p>
 */
final class FakeGamepad implements GamepadSource
{
    /** Buttons the test says are physically down. */
    private final Set<Integer> pressed = new HashSet<>();

    /** What {@link #pressed} held at the previous poll, for edge detection. */
    private Set<Integer> previouslyPressed = new HashSet<>();

    /** Trigger pulls, 0..1, by axis code. */
    private final Set<Integer> pulledTriggers = new HashSet<>();

    /** Whether a pad is plugged in at all. */
    private boolean connected = true;

    /** Left stick, raw. */
    private float leftX;

    /** Left stick, raw, negative when pushed away from the player. */
    private float leftY;

    /** Right stick, raw. */
    private float rightX;

    /** Right stick, raw, negative when pushed away from the player. */
    private float rightY;

    /** How many times {@link #poll()} has been called. */
    private int polls;

    @Override
    public void poll()
    {
        polls = polls + 1;

        previouslyPressed = new HashSet<>(pressed);
    }

    @Override
    public boolean isConnected()
    {
        return connected;
    }

    @Override
    public String name()
    {
        if (!connected)
        {
            return GlfwGamepad.NO_PAD;
        }

        return "Fake Pad";
    }

    @Override
    public boolean isButtonDown(final int buttonIndex)
    {
        return connected && pressed.contains(Integer.valueOf(buttonIndex));
    }

    @Override
    public boolean didButtonGoDown(final int buttonIndex)
    {
        if (!connected)
        {
            return false;
        }

        final Integer code = Integer.valueOf(buttonIndex);

        return pressed.contains(code) && !previouslyPressed.contains(code);
    }

    @Override
    public boolean isAxisPressed(final int axisIndex)
    {
        return connected && pulledTriggers.contains(Integer.valueOf(axisIndex));
    }

    @Override
    public float leftStickX()
    {
        return axis(leftX);
    }

    @Override
    public float leftStickY()
    {
        return axis(leftY);
    }

    @Override
    public float rightStickX()
    {
        return axis(rightX);
    }

    @Override
    public float rightStickY()
    {
        return axis(rightY);
    }

    /**
     * Sets the movement stick.
     *
     * @param x −1 left to 1 right
     * @param y −1 <b>away</b> from the player to 1 toward them, the convention
     *     every gamepad API this project has met reports
     * @return this pad, so a test reads as one line
     */
    FakeGamepad withLeftStick(final float x, final float y)
    {
        this.leftX = x;

        this.leftY = y;

        return this;
    }

    /**
     * Sets the look stick.
     *
     * @param x −1 left to 1 right
     * @param y −1 away from the player to 1 toward them
     * @return this pad
     */
    FakeGamepad withRightStick(final float x, final float y)
    {
        this.rightX = x;

        this.rightY = y;

        return this;
    }

    /**
     * Holds a button down.
     *
     * @param buttonIndex the button code
     * @return this pad
     */
    FakeGamepad press(final int buttonIndex)
    {
        pressed.add(Integer.valueOf(buttonIndex));

        return this;
    }

    /**
     * Releases a button.
     *
     * @param buttonIndex the button code
     * @return this pad
     */
    FakeGamepad release(final int buttonIndex)
    {
        pressed.remove(Integer.valueOf(buttonIndex));

        return this;
    }

    /**
     * Pulls a trigger past {@link AnalogStick#TRIGGER_THRESHOLD}.
     *
     * @param axisIndex the trigger's axis code
     * @return this pad
     */
    FakeGamepad pullTrigger(final int axisIndex)
    {
        pulledTriggers.add(Integer.valueOf(axisIndex));

        return this;
    }

    /**
     * Yanks the cable out, leaving whatever the sticks were last set to still
     * sitting in the fields.
     *
     * <p><b>Deliberately does not zero them.</b> That is the whole failure being
     * staged: a real pad's last reported deflection does not become zero because
     * the device went away, and anything that relies on the device politely
     * centring itself on the way out is relying on something that does not
     * happen. {@link #isConnected()} goes false and the accessors report zeros
     * — as {@link GlfwGamepad} does — but the port must still clear what it has
     * already stored.</p>
     *
     * @return this pad
     */
    FakeGamepad unplug()
    {
        this.connected = false;

        return this;
    }

    /**
     * Plugs a pad back in.
     *
     * @return this pad
     */
    FakeGamepad replug()
    {
        this.connected = true;

        return this;
    }

    /** Returns how many times this pad has been polled. */
    int pollCount()
    {
        return polls;
    }

    // Zero while disconnected, matching GlfwGamepad rather than being lenient.
    private float axis(final float value)
    {
        if (!connected)
        {
            return 0.0f;
        }

        return value;
    }
}
