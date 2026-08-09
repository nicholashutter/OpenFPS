/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.util.Log;
import android.view.MotionEvent;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;

import com.openfps.gdx.AnalogStick;

import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.InputBinding;
import com.openfps.engine.hal.port.InputState;
import com.openfps.gdx.InputAccumulator;
import com.openfps.gdx.UiStateMachine;

/**
 * Real Android input: a floating thumbstick, drag-to-look, and three buttons.
 *
 * <p>The Android counterpart of {@code GdxInputPort}, and split the same way
 * for the same reason. This class knows about touch events and nothing else;
 * {@link TouchLayout} owns every number and every piece of arithmetic and
 * imports no toolkit at all, so the part that can be wrong is the part CI runs.
 * {@link InputAccumulator} — shared with desktop rather than reimplemented —
 * owns the accumulate-and-latch handoff between the platform's frame rate and
 * the simulation's fixed one.</p>
 *
 * <h2>Events, not polling</h2>
 *
 * <p>Desktop asks GLFW "is this key down?" once per frame. Android cannot: a
 * touchscreen has no state to ask about between events, and the interesting
 * quantity — how far a finger travelled since the last frame — exists only as
 * the difference between two events. So this is an {@link InputProcessor} and
 * the platform pushes into it.</p>
 *
 * <p>Pushed events still land on the render thread, which is where libGDX
 * delivers them: it queues events from the UI thread and drains the queue on
 * the GL thread immediately before {@code render()}. So this class has exactly
 * the same threading as its desktop sibling — writes on the render thread,
 * {@link #sampleInput} on the game loop thread, an {@code InputAccumulator}
 * between them — and none of that had to be arranged.</p>
 *
 * <h2>Every finger is tracked separately</h2>
 *
 * <p>A phone shooter is unplayable without it: walking and turning at once is
 * two fingers, and firing while doing either is a third. libGDX gives each an
 * index, and this class remembers, per index, which control that finger landed
 * on and where. The alternative — treating "a touch" as one thing — makes the
 * camera jump to the movement thumb every time the player takes a step.</p>
 *
 * <p><b>A finger keeps the control it started on.</b> Sliding a thumb off the
 * fire button does not release the trigger, and sliding a look drag over the
 * jump button does not jump. Region is decided once, at
 * {@link #touchDown}, because the alternative is that every sweep of the look
 * thumb across the lower right of the screen fires the weapon.</p>
 *
 * <h2>What is not read while the menu is up</h2>
 *
 * <p>The same rule as desktop: outside {@code UiState.PLAYING} this port banks
 * nothing and forgets anything pending. The menu's Scene2D stage holds the
 * input processor then, so in practice these callbacks are not even reached —
 * but the state is checked anyway, because "the wrong processor is installed"
 * is a bug that should cost nothing rather than walk the player into a wall.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class AndroidInputPort implements I_InputPort, InputProcessor
{
    /**
     * Radians of view rotation per density-independent pixel of drag.
     *
     * <p>Expressed per <b>dp</b> rather than per pixel, which is the one real
     * difference from the desktop sensitivity. A mouse reports counts that mean
     * the same thing on every monitor; a finger travels a physical distance,
     * and the same swipe is 400 px on one phone and 1100 px on another. Per-dp
     * makes the same thumb movement turn the same amount on both.</p>
     *
     * <p>At this value a full 360° turn takes about 838 dp — roughly one and a
     * half screen widths in landscape, which is the range mobile shooters
     * converge on.</p>
     */
    public static final float TOUCH_RADIANS_PER_DP = 0.0075f;

    /**
     * How many simultaneous fingers are tracked.
     *
     * <p>Well past the three the control scheme can use. Sized generously
     * because the cost is one small array and the failure mode of being too
     * small is that a stray palm touch takes the slot a real control needed.</p>
     */
    public static final int MAX_POINTERS = 10;

    /**
     * Which platform axis the movement stick's horizontal half is.
     *
     * <p>Named as a constant so {@link AndroidBindings} can put it in the table
     * and a settings screen can report "movement: left stick" without this file
     * and that one each knowing the number. The value is Android's own
     * {@code MotionEvent.AXIS_X}, which is what makes it a meaningful platform
     * code rather than an index this project invented — see
     * {@code InputBinding}'s note on codes being opaque and platform-owned.</p>
     */
    public static final int AXIS_LEFT_STICK = MotionEvent.AXIS_X;

    /** Which platform axis the look stick's horizontal half is. */
    public static final int AXIS_RIGHT_STICK = MotionEvent.AXIS_Z;

    /** Which platform axis the right trigger is. */
    public static final int AXIS_RIGHT_TRIGGER = MotionEvent.AXIS_RTRIGGER;

    /** Logcat tag. Android has no SLF4J binding, so platform code logs here. */
    private static final String TAG = "OpenFPS";

    /** Geometry and arithmetic for the on-screen controls. Never null. */
    private final TouchLayout layout;

    /** Where the render thread deposits readings and the loop thread collects them. */
    private final InputAccumulator accumulator;

    /** Which control each finger landed on, or {@link TouchLayout#REGION_NONE}. */
    private final int[] pointerRegion = new int[MAX_POINTERS];

    /** Where each finger went down, in pixels. */
    private final float[] anchorX = new float[MAX_POINTERS];

    /** Where each finger went down, in pixels from the top. */
    private final float[] anchorY = new float[MAX_POINTERS];

    /** Where each finger was last seen, in pixels. */
    private final float[] lastX = new float[MAX_POINTERS];

    /** Where each finger was last seen, in pixels from the top. */
    private final float[] lastY = new float[MAX_POINTERS];

    /**
     * The snapshot the current tic sees.
     * MUTABLE: replaced by {@link #sampleInput}, read by {@link #currentInput}.
     * Volatile because those are different threads, and the value is immutable
     * so publishing the reference is the whole of the handoff.
     */
    private volatile InputState latched = InputState.NEUTRAL;

    /**
     * Whether this port is looking at the menu or at the game.
     *
     * MUTABLE: replaced by {@link #bindUiState}. Defaults to a private machine
     * parked in the menu so an unbound port — every test — is never null.
     */
    private volatile UiStateMachine uiState = new UiStateMachine();

    /**
     * Which controls trigger which actions.
     * MUTABLE: replaced by {@link #bindActions}, read on the render thread.
     */
    private volatile ActionBindings bindings = AndroidBindings.defaults();

    /**
     * Set for one frame when a leave-match control is activated.
     * MUTABLE: set on the render thread by a touch or a back press, consumed by
     * {@link #consumeLeaveRequest()} on the same thread.
     */
    private boolean leaveRequested;

    /**
     * Whether a gamepad button bound to fire is held.
     *
     * <p>MUTABLE: set and cleared by {@link #keyDown}/{@link #keyUp} on the
     * render thread. Separate from the touch button state because the two are
     * separate devices and either may be in use — a player with a controller
     * plugged into a phone can still reach the screen.</p>
     */
    private boolean padFire;

    /** Whether a gamepad button bound to jump is held. MUTABLE: render thread. */
    private boolean padJump;

    /** Whether a gamepad button bound to sprint is held. MUTABLE: render thread. */
    private boolean padSprint;

    /**
     * The right trigger's pull, 0 released to 1 fully pulled.
     *
     * <p>MUTABLE: overwritten by {@link #onGamepadAxes}. Held as the raw travel
     * rather than a boolean so the threshold lives in one place
     * ({@link AnalogStick#TRIGGER_THRESHOLD}) and applies identically on both
     * platforms.</p>
     */
    private float padRightTrigger;

    /**
     * Whether a gamepad has been seen at all this session.
     *
     * <p>MUTABLE: render thread. Logged once on the edge, exactly as the desktop
     * port does, so a connected pad is discoverable in logcat without writing a
     * line every frame.</p>
     */
    private boolean padSeen;

    /**
     * Creates a port for one screen density at the default sensitivity.
     *
     * @param density pixels per density-independent pixel; must be finite and
     *     positive
     */
    public AndroidInputPort(final float density)
    {
        this(new TouchLayout(density),
            new InputAccumulator(TOUCH_RADIANS_PER_DP / density));
    }

    /**
     * Creates a port over explicit collaborators. Exists so a test can drive
     * the whole event path without a device.
     *
     * @param touchLayout the control geometry; must not be null
     * @param inputAccumulator the accumulator to latch from; must not be null
     */
    public AndroidInputPort(final TouchLayout touchLayout,
        final InputAccumulator inputAccumulator)
    {
        if (touchLayout == null)
        {
            throw new IllegalArgumentException("touchLayout must not be null");
        }

        if (inputAccumulator == null)
        {
            throw new IllegalArgumentException("inputAccumulator must not be null");
        }

        this.layout = touchLayout;

        this.accumulator = inputAccumulator;

        clearPointers();
    }

    /** Returns the control geometry this port reads against. Never null. */
    public TouchLayout layout()
    {
        return layout;
    }

    /** Returns the accumulator this port latches from. Never null. */
    public InputAccumulator accumulator()
    {
        return accumulator;
    }

    /** Returns the control scheme this port resolves against. Never null. */
    public ActionBindings actionBindings()
    {
        return bindings;
    }

    /**
     * Replaces the control scheme this port reads.
     *
     * @param actionBindings the table to resolve against; must not be null
     * @throws IllegalArgumentException if {@code actionBindings} is null
     */
    public void bindActions(final ActionBindings actionBindings)
    {
        if (actionBindings == null)
        {
            throw new IllegalArgumentException("actionBindings must not be null");
        }

        this.bindings = actionBindings;
    }

    /**
     * Names the UI state this port obeys.
     *
     * <p>Binding also drops anything banked: whatever the previous machine was
     * doing has nothing to say about the new one.</p>
     *
     * @param machine the UI state machine to follow; must not be null
     * @throws IllegalArgumentException if {@code machine} is null
     */
    public void bindUiState(final UiStateMachine machine)
    {
        if (machine == null)
        {
            throw new IllegalArgumentException("machine must not be null");
        }

        this.uiState = machine;

        forgetEverything();
    }

    /** Returns the UI state machine this port follows. Never null. */
    public UiStateMachine uiState()
    {
        return uiState;
    }

    /**
     * Sizes the control layout to the surface.
     *
     * <p>Also drops every in-flight finger. A rotation moves the fire button to
     * a different part of the screen, so a finger that went down on it before
     * the resize is holding a control that is no longer there.</p>
     *
     * @param width surface width in pixels
     * @param height surface height in pixels
     */
    public void resize(final int width, final int height)
    {
        layout.resize(width, height);

        forgetEverything();
    }

    @Override
    public void init()
    {
        forgetEverything();

        latched = InputState.NEUTRAL;

        final GameAction unbound = bindings.firstUnbound();

        if (unbound != null)
        {
            // Not fatal — the game is playable with no sprint control, which is
            // exactly the case this reports. Loud anyway, because the same gap
            // on LEAVE_MATCH is a match with no way out of it.
            Log.w(TAG, "No control is bound to " + unbound + " — that action cannot be"
                + " triggered on this device");
        }

        Log.i(TAG, "AndroidInputPort initialized — " + layout + ", "
            + accumulator.radiansPerPixel() + " rad/px, controls " + bindings);
    }

    @Override
    public void shutdown()
    {
        forgetEverything();

        latched = InputState.NEUTRAL;

        Log.i(TAG, "AndroidInputPort shut down");
    }

    /**
     * Latches everything gathered since the previous tic.
     *
     * <p>Runs on the game loop thread and touches no platform API, so it is
     * safe while the render thread is delivering touch events.</p>
     *
     * @param ticIndex the tic being processed
     */
    @Override
    public void sampleInput(final int ticIndex)
    {
        latched = accumulator.latch();
    }

    @Override
    public InputState currentInput()
    {
        return latched;
    }

    /**
     * Returns false always — closing is the Activity's business.
     *
     * <p>{@code AndroidWindowPort} already owns the close flag and hears about
     * the menu's Quit button and {@code finish()}. Duplicating that here would
     * give the engine two disagreeing shutdown signals. Leaving a match returns
     * to the menu; it does not end the app.</p>
     *
     * @return false
     */
    @Override
    public boolean isShutdownRequested()
    {
        return false;
    }

    /**
     * Returns whether a leave-match control was activated, and clears the flag.
     *
     * <p>Edge-triggered and consumed exactly once, which is what stops a held
     * back button from bouncing the player straight back out of the menu they
     * just returned to. Called once per frame by the UI callback, on the render
     * thread, which is also the only thread that sets it.</p>
     *
     * @return true if the player asked to leave since the last call
     */
    public boolean consumeLeaveRequest()
    {
        final boolean requested = leaveRequested;

        leaveRequested = false;

        return requested;
    }

    /**
     * Drops every pending reading and forgets every finger.
     *
     * <p>Called across a UI transition, a resize, and at init and shutdown.
     * Without it a thumb held on the stick at the moment the menu appears is
     * still "held" afterwards, walking the player into a wall while they read
     * the buttons.</p>
     *
     * <p>The controller goes with it. Android delivers no key-up when the app is
     * backgrounded, so a pad button held at that moment would still be held on
     * the way back — the same reason every finger is dropped, and a real one:
     * {@code AndroidUiFrameCallback} calls this on exactly that edge.</p>
     */
    public void forgetEverything()
    {
        clearPointers();

        accumulator.clearAll();

        leaveRequested = false;

        padFire = false;

        padJump = false;

        padSprint = false;

        padRightTrigger = 0.0f;
    }

    @Override
    public boolean touchDown(final int screenX, final int screenY, final int pointer,
        final int button)
    {
        if (!isPlayable(pointer))
        {
            return false;
        }

        final int region = layout.regionAt(screenX, screenY);

        pointerRegion[pointer] = region;

        anchorX[pointer] = screenX;

        anchorY[pointer] = screenY;

        lastX[pointer] = screenX;

        lastY[pointer] = screenY;

        applyRegionDown(region);

        return true;
    }

    @Override
    public boolean touchDragged(final int screenX, final int screenY, final int pointer)
    {
        if (!isPlayable(pointer))
        {
            return false;
        }

        final int region = pointerRegion[pointer];

        if (region == TouchLayout.REGION_MOVE_STICK)
        {
            accumulator.setMovementAxes(
                layout.stickForward(anchorX[pointer], anchorY[pointer], screenX, screenY),
                layout.stickStrafe(anchorX[pointer], anchorY[pointer], screenX, screenY));
        }
        else if (region == TouchLayout.REGION_LOOK)
        {
            // Relative motion since the previous event, in the accumulator's
            // screen convention: +x right, +y DOWN. The sign flip to
            // positive-is-up happens once, at latch, exactly as it does for a
            // mouse — so an "invert look" setting is still one negation in one
            // place for both platforms.
            accumulator.accumulateLook(
                Math.round(screenX - lastX[pointer]),
                Math.round(screenY - lastY[pointer]));
        }

        lastX[pointer] = screenX;

        lastY[pointer] = screenY;

        return true;
    }

    @Override
    public boolean touchUp(final int screenX, final int screenY, final int pointer,
        final int button)
    {
        if (pointer < 0 || pointer >= MAX_POINTERS)
        {
            return false;
        }

        final int region = pointerRegion[pointer];

        pointerRegion[pointer] = TouchLayout.REGION_NONE;

        applyRegionUp(region);

        return true;
    }

    /**
     * Treats a cancelled touch exactly as a release.
     *
     * <p>Android cancels every pointer when a system gesture takes over —
     * a notification shade pull, a split-screen drag. Without this the finger
     * that was on the stick is never released and the player walks forever.</p>
     *
     * @param screenX x in pixels
     * @param screenY y in pixels
     * @param pointer the finger index
     * @param button unused on a touchscreen
     * @return true if the event was handled
     */
    @Override
    public boolean touchCancelled(final int screenX, final int screenY, final int pointer,
        final int button)
    {
        return touchUp(screenX, screenY, pointer, button);
    }

    /**
     * Records a gamepad's stick and trigger positions.
     *
     * <p><b>Pushed in as plain floats, on purpose.</b> Android reports joystick
     * axes only inside a {@code MotionEvent}, which cannot be constructed in a
     * local unit test — so the Activity does the six {@code getAxisValue} calls
     * and everything that can actually be wrong happens here, where CI can see
     * it. The same split as {@link TouchLayout} and for the same reason.</p>
     *
     * <p>Values are handed to the accumulator raw. The dead zone and the response
     * curve are applied inside it, so this port and the desktop one cannot drift
     * apart on the numbers a player feels.</p>
     *
     * @param leftX movement stick, −1 left to 1 right
     * @param leftY movement stick, −1 <b>away</b> from the player to 1 toward
     *     them — Android's own convention, passed through unchanged
     * @param rightX look stick, −1 left to 1 right
     * @param rightY look stick, −1 away from the player to 1 toward them, which
     *     is already the accumulator's documented "+y downward"
     * @param rightTrigger the right trigger's pull, 0 released to 1 pulled
     */
    public void onGamepadAxes(final float leftX, final float leftY,
        final float rightX, final float rightY, final float rightTrigger)
    {
        if (!uiState.isPlaying())
        {
            // Same rule as a touch: a stick leaning while the menu is up is not
            // a request to walk.
            accumulator.clearGamepad();

            return;
        }

        noteGamepad();

        // Forward is positive here and "pushed away" is negative on the device,
        // so the vertical axis is negated — as a pair, leaving the magnitude and
        // therefore the radial dead zone untouched.
        accumulator.setGamepadMovementAxes(0.0f - leftY, leftX);

        // No sign flip on the look stick. Android reports the same "+y downward"
        // the accumulator documents and the desktop mouse already speaks; a
        // second negation here is exactly what shipped an inverted camera once
        // before. See InputAccumulator.setGamepadLookAxes.
        accumulator.setGamepadLookAxes(rightX, rightY);

        padRightTrigger = rightTrigger;

        publishGamepadActions();
    }

    /**
     * Drops every gamepad reading, leaving the touch controls untouched.
     *
     * <p><b>The hot-plug fix.</b> A stick deflection is a level: it persists
     * until something overwrites it, and a controller that has been unplugged —
     * or a wireless one whose battery has died — never will. Without this, a pad
     * removed at full deflection walks the player into a wall for the rest of
     * the match with nobody touching anything. It is the same failure
     * {@code touchCancelled} handles for a finger that Android takes away
     * mid-gesture, and the same one {@code InputAccumulator.clearAll()} was
     * written for.</p>
     *
     * <p>Partial rather than total: a player whose controller dies still has a
     * touchscreen, and clearing a thumb that is on the stick right now would be
     * a second bug.</p>
     */
    public void onGamepadDisconnected()
    {
        if (padSeen)
        {
            padSeen = false;

            Log.i(TAG, "Controller disconnected — gamepad input dropped,"
                + " touch controls unaffected");
        }

        padFire = false;

        padJump = false;

        padSprint = false;

        padRightTrigger = 0.0f;

        accumulator.clearGamepad();
    }

    /**
     * Tells the accumulator how long a tic lasts, from the configured frame
     * rate.
     *
     * <p>Only stick look depends on this, and it depends on it completely: a
     * held stick is a rate, and a rate is not an angle until something supplies
     * the seconds. A drag is a displacement and needs none.</p>
     *
     * @param ticsPerSecond the simulation rate in Hz; must be greater than zero
     * @throws IllegalArgumentException if the rate is not positive
     */
    public void setTicRate(final int ticsPerSecond)
    {
        if (ticsPerSecond <= 0)
        {
            throw new IllegalArgumentException(
                "tic rate must be positive, got " + ticsPerSecond);
        }

        accumulator.setTicDuration(1.0f / ticsPerSecond);
    }

    /**
     * Handles a key or gamepad button going down.
     *
     * <p><b>On Android a gamepad button IS a key event</b> — libGDX delivers
     * {@code Input.Keys.BUTTON_A} through this same callback, because the
     * platform numbers pad buttons in the same key space as the back button.
     * That is why {@code AndroidBindings} carries libGDX key constants in its
     * {@code GAMEPAD_BUTTON} codes while the desktop table carries GLFW gamepad
     * indices: a binding code is opaque and belongs to the platform that wrote
     * it, which is the whole reason {@code InputBinding.Source} exists.</p>
     *
     * @param keycode the libGDX key or button constant
     * @return true if this port consumed the press
     */
    @Override
    public boolean keyDown(final int keycode)
    {
        if (namesControl(GameAction.LEAVE_MATCH, keycode))
        {
            leaveRequested = true;

            return true;
        }

        if (!uiState.isPlaying())
        {
            return false;
        }

        return applyPadButton(keycode, true);
    }

    /**
     * Handles a key or gamepad button coming up.
     *
     * <p>Needed only because of the pad. A touch button is released by
     * {@code touchUp}, and the back key is edge-triggered — but a pad's fire
     * button is a level, and a level that is never released is a weapon that
     * fires for the rest of the match.</p>
     *
     * @param keycode the libGDX key or button constant
     * @return true if this port consumed the release
     */
    @Override
    public boolean keyUp(final int keycode)
    {
        return applyPadButton(keycode, false);
    }

    @Override
    public boolean keyTyped(final char character)
    {
        return false;
    }

    @Override
    public boolean mouseMoved(final int screenX, final int screenY)
    {
        return false;
    }

    @Override
    public boolean scrolled(final float amountX, final float amountY)
    {
        return false;
    }

    /**
     * Returns which control a finger is currently holding.
     *
     * <p>Exposed so {@link TouchOverlay} can draw a pressed button as pressed,
     * and so a test can assert the tracking without reaching into the arrays.</p>
     *
     * @param pointer the finger index
     * @return one of {@link TouchLayout}'s region constants, or
     *     {@link TouchLayout#REGION_NONE}
     */
    public int regionOf(final int pointer)
    {
        if (pointer < 0 || pointer >= MAX_POINTERS)
        {
            return TouchLayout.REGION_NONE;
        }

        return pointerRegion[pointer];
    }

    /**
     * Returns the finger index currently working the movement stick, or −1.
     *
     * @return the pointer index, or −1 when nobody is touching the stick
     */
    public int stickPointer()
    {
        for (int index = 0; index < MAX_POINTERS; index++)
        {
            if (pointerRegion[index] == TouchLayout.REGION_MOVE_STICK)
            {
                return index;
            }
        }

        return -1;
    }

    /**
     * Returns where the stick's finger went down, or 0 when there is none.
     *
     * @param pointer the finger index
     * @return the anchor x in pixels
     */
    public float anchorXOf(final int pointer)
    {
        if (pointer < 0 || pointer >= MAX_POINTERS)
        {
            return 0.0f;
        }

        return anchorX[pointer];
    }

    /**
     * Returns where the stick's finger went down, or 0 when there is none.
     *
     * @param pointer the finger index
     * @return the anchor y in pixels from the top
     */
    public float anchorYOf(final int pointer)
    {
        if (pointer < 0 || pointer >= MAX_POINTERS)
        {
            return 0.0f;
        }

        return anchorY[pointer];
    }

    /**
     * Returns where the stick's finger is now.
     *
     * @param pointer the finger index
     * @return the current x in pixels
     */
    public float currentXOf(final int pointer)
    {
        if (pointer < 0 || pointer >= MAX_POINTERS)
        {
            return 0.0f;
        }

        return lastX[pointer];
    }

    /**
     * Returns where the stick's finger is now.
     *
     * @param pointer the finger index
     * @return the current y in pixels from the top
     */
    public float currentYOf(final int pointer)
    {
        if (pointer < 0 || pointer >= MAX_POINTERS)
        {
            return 0.0f;
        }

        return lastY[pointer];
    }

    /** Returns whether any finger is holding the fire button. */
    public boolean isFireHeld()
    {
        return isHeld(TouchLayout.REGION_FIRE);
    }

    /** Returns whether any finger is holding the jump button. */
    public boolean isJumpHeld()
    {
        return isHeld(TouchLayout.REGION_JUMP);
    }

    /**
     * Returns whether any finger is currently on a given control.
     *
     * <p>The general form of the two above, and the one {@link TouchOverlay}
     * uses: the overlay walks {@link TouchLayout#buttonRegions()} rather than
     * naming the buttons a second time, so a fourth button becomes drawable and
     * pressable without either file learning its name.</p>
     *
     * @param region one of {@link TouchLayout}'s region constants
     * @return true while at least one finger is on it; always false for
     *     {@link TouchLayout#REGION_NONE}, which is what an idle slot holds
     */
    public boolean isHeld(final int region)
    {
        if (region == TouchLayout.REGION_NONE)
        {
            // Every unused slot reads REGION_NONE, so asking this question
            // literally would answer "yes, ten fingers are holding nothing".
            return false;
        }

        for (int index = 0; index < MAX_POINTERS; index++)
        {
            if (pointerRegion[index] == region)
            {
                return true;
            }
        }

        return false;
    }

    // A touch is only meaningful while the world is in front, and only for a
    // finger index this port has a slot for.
    private boolean isPlayable(final int pointer)
    {
        if (pointer < 0 || pointer >= MAX_POINTERS)
        {
            return false;
        }

        return uiState.isPlaying();
    }

    // Applies the consequence of a finger arriving on a control.
    private void applyRegionDown(final int region)
    {
        if (region == TouchLayout.REGION_LEAVE)
        {
            leaveRequested = true;

            return;
        }

        publishButtons();
    }

    // Applies the consequence of a finger leaving a control.
    private void applyRegionUp(final int region)
    {
        if (region == TouchLayout.REGION_MOVE_STICK && stickPointer() < 0)
        {
            // The last stick finger is gone. Centre the stick rather than
            // leaving the last deflection standing, or the player keeps walking
            // after taking their thumb off.
            accumulator.setMovementAxes(0.0f, 0.0f);
        }

        publishButtons();
    }

    // Pushes the current TOUCH button levels into the accumulator's keyboard and
    // touch channel. Sprint is always false here because no screen region is
    // bound to it — see AndroidBindings on why a phone has no sprint button and
    // a controller's shoulder is a different question. A pad's sprint arrives
    // through publishGamepadActions and the accumulator ORs the two, so this
    // false does not hold the pad's true down.
    private void publishButtons()
    {
        accumulator.setActionKeys(isFireHeld(), isJumpHeld(), false);
    }

    // Whether an action names a given control. The touch regions are resolved
    // by the geometry rather than here, so this exists for the binding kinds
    // that arrive as an opaque code from the platform: a key and a pad button.
    private boolean isAnyActive(final GameAction action, final InputBinding.Source source,
        final int code)
    {
        final InputBinding[] bound = bindings.bindingsFor(action);

        for (int index = 0; index < bound.length; index++)
        {
            if (bound[index].source() == source && bound[index].code() == code)
            {
                return true;
            }
        }

        return false;
    }

    // Whether an action names a given key code, as either a keyboard key or a
    // gamepad button. Both, because on Android the two arrive through one
    // callback in one number space — see keyDown — and an action may legitimately
    // name each: LEAVE_MATCH is the system back key AND the pad's Start.
    private boolean namesControl(final GameAction action, final int keycode)
    {
        return isAnyActive(action, InputBinding.Source.KEY, keycode)
            || isAnyActive(action, InputBinding.Source.GAMEPAD_BUTTON, keycode);
    }

    // Applies a pad button edge to the three action levels, and reports whether
    // the code meant anything. Each action is tested separately because one
    // button may legitimately drive two — the same way A both jumps and fires in
    // the desktop scheme.
    private boolean applyPadButton(final int keycode, final boolean down)
    {
        boolean consumed = false;

        if (isAnyActive(GameAction.FIRE, InputBinding.Source.GAMEPAD_BUTTON, keycode))
        {
            padFire = down;

            consumed = true;
        }

        if (isAnyActive(GameAction.JUMP, InputBinding.Source.GAMEPAD_BUTTON, keycode))
        {
            padJump = down;

            consumed = true;
        }

        if (isAnyActive(GameAction.SPRINT, InputBinding.Source.GAMEPAD_BUTTON, keycode))
        {
            padSprint = down;

            consumed = true;
        }

        if (!consumed)
        {
            return false;
        }

        if (down)
        {
            noteGamepad();
        }

        publishGamepadActions();

        return true;
    }

    // Pushes the pad's action levels into the accumulator's gamepad channel —
    // never into the touch channel, so neither device can release the other's
    // trigger. The trigger is folded in here rather than at the axis event
    // because a trigger IS a fire control and the accumulator should not have to
    // hear about it twice.
    private void publishGamepadActions()
    {
        final boolean triggerFires =
            isAnyActive(GameAction.FIRE, InputBinding.Source.GAMEPAD_AXIS, AXIS_RIGHT_TRIGGER)
                && AnalogStick.isTriggerPulled(padRightTrigger);

        accumulator.setGamepadActions(padFire || triggerFires, padJump, padSprint);
    }

    // Logs the arrival of a controller once. A phone that has had a pad attached
    // since launch should say so somewhere, and a line per frame is not that.
    private void noteGamepad()
    {
        if (padSeen)
        {
            return;
        }

        padSeen = true;

        Log.i(TAG, "Controller in use — left stick moves, right stick looks;"
            + " the touch controls still work");
    }

    // Forgets every finger without touching the accumulator.
    private void clearPointers()
    {
        for (int index = 0; index < MAX_POINTERS; index++)
        {
            pointerRegion[index] = TouchLayout.REGION_NONE;

            anchorX[index] = 0.0f;

            anchorY[index] = 0.0f;

            lastX[index] = 0.0f;

            lastY[index] = 0.0f;
        }
    }

    /** Returns a debug rendering of the layout and control scheme. */
    @Override
    public String toString()
    {
        return "AndroidInputPort[" + layout + ", " + bindings + "]";
    }

    /**
     * Returns whether this port would treat a key code as a leave-match
     * control.
     *
     * <p>Public so the UI callback can ask libGDX to catch exactly the keys
     * that matter — {@code Input.setCatchKey(Input.Keys.BACK, true)} is
     * required or Android consumes back before libGDX ever sees it, and asking
     * for keys nothing is bound to would silently break the app's back
     * gesture.</p>
     *
     * @param keycode a libGDX {@link Input.Keys} constant
     * @return true if the key leaves a match
     */
    public boolean isLeaveKey(final int keycode)
    {
        return isAnyActive(GameAction.LEAVE_MATCH, InputBinding.Source.KEY, keycode);
    }
}
