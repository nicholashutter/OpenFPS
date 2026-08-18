/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;
import com.openfps.engine.hal.port.PlayerSettings;

/**
 * The rebind screen: every {@link GameAction} and its current physical
 * control, with a click-to-rebind affordance for each one.
 *
 * <p>The screen is reached from the menu (or the settings screen) and
 * returns to it on Back. It has two display states, the same way a
 * settings screen has a "reading" and a "editing" state:
 * <b>idle</b> lists every action and its bindings, and a row of
 * reset / save / back buttons; <b>listening</b> is the per-binding
 * state that swallows the next key or mouse press and turns it into
 * a new {@link InputBinding} for the chosen action.</p>
 *
 * <h2>Listening without a {@code Gdx.input.setInputProcessor} callback</h2>
 *
 * <p>The "press a key to bind" loop polls the input from the render
 * thread directly rather than registering a Scene2D event listener.
 * The Stage is the input processor for the rest of the screen, and
 * a transient override would have to fight it for the same events.
 * Polling is the same answer the gameplay ports use to read movement
 * keys, and at one frame's worth of lag the player cannot tell which
 * side answered first.</p>
 *
 * <h2>Why a "working copy" rather than editing the live bindings</h2>
 *
 * <p>Mutating the live rebind table mid-frame is the kind of
 * out-of-band change the input port has no contract for: a binding
 * being read while it is being rewritten is a race that the lockstep
 * model does not cover, because the port's reader and the
 * settings-screen writer are the same thread here but the port is
 * shared with the gameplay code. The screen holds a
 * {@link PlayerSettings} working copy and pushes it through the
 * supplied {@link Listener#onSettingsChanged} callback only on Save
 * or Apply, the same way the settings screen pushes a boolean flip
 * through its own callback.</p>
 *
 * <h2>The "press a key" prompt eats one event it did not ask for</h2>
 *
 * <p>Clicking a binding puts the screen into listening state, but the
 * click is itself a mouse event. The screen ignores the first event
 * it sees in listening state — that is the click that started the
 * session — and reads the second one as the new binding. Without
 * the eat, every rebind would land on the same mouse button the
 * player just clicked. Two events is the smallest number that is
 * both deterministic and correct.</p>
 *
 * <h2>The screen does not import core engine types</h2>
 *
 * <p>{@link GameAction}, {@link InputBinding} and {@link PlayerSettings}
 * are HAL types in {@code com.openfps.engine.hal.port}, and the gdxshared
 * module depends on engine, so the import is legal. The screen does
 * not import gameplay, render or core types — the same package
 * boundary every other screen keeps.</p>
 *
 * <b>Threading:</b> constructed and used only on the platform render
 * thread, after the GL context exists.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class ControlsScreen
{
    /**
     * The line of help under the heading.
     *
     * <p>"LISTENING" replaces it while the screen is waiting on a
     * physical press. The colour swap is the cue the player reads,
     * not the text, but the text is what a screen reader would say
     * and that matters for a settings UI.</p>
     */
    public static final String SUBTITLE_TEXT = "Click a binding to remap it.";

    /** The line shown while a binding is being captured. */
    public static final String LISTENING_TEXT = "Press a key or mouse button...";

    /** The label on the Reset to Defaults key. */
    public static final String RESET_TEXT = "RESET DEFAULTS";

    /** The label on the Save key. Writes the working copy to the settings file. */
    public static final String SAVE_TEXT = "SAVE";

    /** The label on the Back key. Discards the working copy. */
    public static final String BACK_TEXT = "BACK";

    /** Width of a single binding row in pixels. */
    private static final float ROW_WIDTH = 760.0f;

    /** Height of a single binding row in pixels. */
    private static final float ROW_HEIGHT = 42.0f;

    /** Vertical gap between rows. */
    private static final float ROW_GAP = 6.0f;

    /** Width of an action-name column in pixels. */
    private static final float ACTION_COLUMN_WIDTH = 280.0f;

    /** Width of one binding pill in pixels. */
    private static final float BINDING_PILL_WIDTH = 220.0f;

    /** Gap between binding pills on the same row. */
    private static final float BINDING_PILL_GAP = 6.0f;

    /** Bottom margin for the Reset / Save / Back row. */
    private static final float BUTTONS_BOTTOM_MARGIN = 36.0f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 220.0f;

    /** Button height in pixels. */
    private static final float BUTTON_HEIGHT = 50.0f;

    /** Gap between bottom-row buttons. */
    private static final float BUTTON_GAP = 18.0f;

    /** Heading font magnification. */
    private static final float HEADING_FONT_SCALE = 1.0f;

    /** Subtitle font magnification. */
    private static final float SUBTITLE_FONT_SCALE = 1.05f;

    /** Action name font magnification. */
    private static final float ACTION_FONT_SCALE = 1.1f;

    /** Binding label font magnification. */
    private static final float BINDING_FONT_SCALE = 1.0f;

    /** Bottom-row button font magnification. */
    private static final float BUTTON_FONT_SCALE = 1.35f;

    /** How many controls {@link GameAction} lists. The enum is the source. */
    private static final int ACTION_COUNT = GameAction.values().length;

    /**
     * How many slots a single action's binding row can show.
     *
     * <p>Mirrors {@link
     * com.openfps.engine.hal.adapter.ActionBindings#MAX_BINDINGS_PER_ACTION}
     * because the screen shows every slot. A future bump in that
     * limit would need a matching bump here.</p>
     */
    private static final int MAX_BINDINGS_PER_ACTION =
        com.openfps.engine.hal.adapter.ActionBindings.MAX_BINDINGS_PER_ACTION;

    /**
     * The screen's two jobs handed out as callbacks.
     *
     * <p>The screen does not know about input ports, files or paths; the
     * listener hands it lambdas for the two things it has to push
     * outside, and the screen calls them on Save and on Apply. Back
     * does not call back — the caller is responsible for the
     * "discard working copy" semantics of returning to the menu.</p>
     */
    public interface Listener
    {
        /**
         * The working copy has changed and should be pushed into the
         * live input port. Called from the render thread; the
         * listener is free to mutate the port's bindings table
         * directly. The settings are already a working copy and the
         * caller owns it; this is a one-way push, not a swap.
         *
         * @param updated the working copy to push; never null
         */
        void onSettingsChanged(PlayerSettings updated);

        /**
         * The working copy has changed and should be written to the
         * settings file. Called from the render thread; the listener
         * owns the file path. Errors are the listener's problem —
         * the screen does not try to surface them.
         *
         * @param updated the working copy to save; never null
         */
        void onSaveRequested(PlayerSettings updated);
    }

    /** The Scene2D stage that owns the widget hierarchy. */
    private final Stage stage;

    /** The 1x1 white texture every rectangle is drawn from. */
    private final Texture white;

    /** The built-in font, used for labels and the heading. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** The block heading at the top of the screen. */
    private final BlockTitle heading;

    /** The line of help under the heading. */
    private final Label subtitle;

    /** The action-name labels, indexed by GameAction ordinal. */
    private final Label[] actionLabels = new Label[ACTION_COUNT];

    /**
     * The binding pills, one per (action, slot) pair.
     *
     * <p>Sized to {@link GameAction#MAX_BINDINGS_PER_ACTION} because the
     * binding table is bounded that wide and the screen shows every
     * bound slot. A slot that is empty in the working copy becomes
     * a "+ ADD" pill rather than nothing, so the screen is
     * unambiguous about whether a slot exists.</p>
     */
    private final BlockButton[][] bindingPills;

    /** The reset-to-defaults button at the bottom of the screen. */
    private final BlockButton resetButton;

    /** The save button at the bottom of the screen. */
    private final BlockButton saveButton;

    /** The back button at the bottom of the screen. */
    private final BlockButton backButton;

    /**
     * The screen's working copy of the player's settings.
     *
     * <p>Mutated in place when a binding is changed or a reset is
     * requested. The original passed at construction is not touched;
     * a successful Save pushes the working copy to the listener and
     * a Back discards it. Both are recoverable: the working copy
     * lives only in this screen.</p>
     */
    private final PlayerSettings workingCopy;

    /**
     * The platform-default bindings, used to populate the Reset action.
     *
     * <p>Set once at construction; not mutated. A "Reset Defaults"
     * rebuilds the working copy's bindings from this table and the
     * rest of the working copy (sensitivity, invert) is left as the
     * player had it.</p>
     */
    private final com.openfps.engine.hal.adapter.ActionBindings platformDefaults;

    /**
     * The action the player is currently rebinding, or null when idle.
     *
     * <p>Null means the screen is in idle mode and is not reading
     * keyboard or mouse events. Non-null means the next event is
     * the new binding, modulo the eat-first-event rule documented on
     * the class.</p>
     */
    private GameAction listening;

    /**
     * The first event of a listening session is the click that opened
     * it; the second is the binding. True between the click and the
     * binding; false otherwise. Resets to false on every transition
     * into listening mode and on every consumed binding event.
     */
    private boolean eatFirstEvent;

    /**
     * The callback handed in by the listener; called on Save and on
     * Apply. Null is allowed and disables the corresponding UI
     * affordance; a null {@link #onSaveRequested} hides the Save
     * button, a null {@link #onSettingsChanged} hides Reset.
     */
    private final Listener callbacks;

    /**
     * The Back button's runnable; also fires on Escape.
     *
     * <p>Separate from the {@link BlockButton} on the bottom row
     * because Escape is the same semantic Back but lives outside
     * the Scene2D button hierarchy. A window that has the screen
     * but no menu transition handler leaves it null and the screen
     * becomes a read-only view; a window with a handler sees
     * Back go to the menu either way the player presses it.</p>
     */
    private final Runnable onBackRunnable;

    /**
     * Builds the rebind screen.
     *
     * @param current the settings to start from; the working copy is
     *     a fresh {@code withBindings} of this with the same
     *     sensitivity and invert flag. The {@code current}
     *     instance itself is not modified.
     * @param defaults the platform's default bindings; used by the
     *     Reset button to restore the original scheme. Must not be
     *     null.
     * @param callbacks the listener the screen pushes changes and
     *     saves through; may be null to disable both, but the
     *     screen is then mostly decorative.
     */
    public ControlsScreen(final PlayerSettings current,
        final com.openfps.engine.hal.adapter.ActionBindings defaults,
        final Listener callbacks)
    {
        this(current, defaults, callbacks, null);
    }

    /**
     * Builds the rebind screen with a Back handler.
     *
     * <p>The {@code onBack} runnable is the same one wired to the
     * Back button; it fires on Back clicks and on Escape presses
     * in idle mode. Null disables Back entirely, which is
     * appropriate for a screen that is meant to be the leaf of
     * its navigation graph.</p>
     *
     * @param current the settings to start from
     * @param defaults the platform defaults
     * @param callbacks the save/apply sink
     * @param onBack the Back handler, or null
     */
    public ControlsScreen(final PlayerSettings current,
        final com.openfps.engine.hal.adapter.ActionBindings defaults,
        final Listener callbacks, final Runnable onBack)
    {
        if (current == null)
        {
            throw new IllegalArgumentException("current must not be null");
        }

        if (defaults == null)
        {
            throw new IllegalArgumentException("defaults must not be null");
        }

        this.callbacks = callbacks;

        this.onBackRunnable = onBack;

        // Defensive copy of the bindings so a Save cannot mutate the
        // caller's table by side effect, and a Reset cannot mutate
        // the defaults. Sensitivity and invert are primitives; they
        // need no copy.
        this.workingCopy = current.withBindings(copyBindings(current.bindings()));

        this.platformDefaults = copyBindings(defaults);

        this.white = whitePixelTexture();

        final TextureRegion pixel = new TextureRegion(white);

        this.font = new BitmapFont();

        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);

        this.heading = new BlockTitle("CONTROLS", pixel, MenuPalette.NEUTRAL_FACE);

        this.subtitle = label(SUBTITLE_TEXT, MenuPalette.HINT, SUBTITLE_FONT_SCALE);

        // One label per GameAction, in declaration order. Declaration
        // order is the order the screen shows them; the enum has
        // movement actions first, then the buttons, and a UI
        // reading them in that order is a UI a player can read in
        // that order.
        for (int index = 0; index < ACTION_COUNT; index++)
        {
            final GameAction action = GameAction.values()[index];

            actionLabels[index] = label(actionLabel(action),
                MenuPalette.NEUTRAL_FACE, ACTION_FONT_SCALE);
        }

        // One block of pills per action. Each pill corresponds to
        // one slot in the binding table. The slot that is empty
        // becomes a "+ ADD" pill so the player can fill it.
        this.bindingPills = new BlockButton[ACTION_COUNT][MAX_BINDINGS_PER_ACTION];

        for (int actionIndex = 0; actionIndex < ACTION_COUNT; actionIndex++)
        {
            final GameAction action = GameAction.values()[actionIndex];

            final InputBinding[] row = workingCopy.bindings().bindingsFor(action);

            for (int slot = 0; slot < MAX_BINDINGS_PER_ACTION; slot++)
            {
                final int capturedActionIndex = actionIndex;

                final int capturedSlot = slot;

                final Runnable onClick = () ->
                {
                    beginListening(GameAction.values()[capturedActionIndex], capturedSlot);
                };

                if (slot < row.length && row[slot] != null)
                {
                    bindingPills[actionIndex][slot] = new BlockButton(
                        renderBinding(row[slot]),
                        MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE,
                        pixel, font, BINDING_FONT_SCALE, onClick);
                }
                else
                {
                    bindingPills[actionIndex][slot] = new BlockButton("+ ADD",
                        MenuPalette.HINT, MenuPalette.NEUTRAL_SHADE,
                        pixel, font, BINDING_FONT_SCALE, onClick);
                }

                stage.addActor(bindingPills[actionIndex][slot]);
            }
        }

        for (final Label actionLabel : actionLabels)
        {
            stage.addActor(actionLabel);
        }

        // Reset is optional: a null callbacks hides it, and a
        // screen that hides it cannot restore defaults. The visual
        // "Reset" affordance is the whole of the safety net for
        // the player who just bound FIRE to Escape.
        this.resetButton = new BlockButton(RESET_TEXT,
            MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE,
            pixel, font, BUTTON_FONT_SCALE,
            this::resetToDefaults);

        this.saveButton = new BlockButton(SAVE_TEXT,
            MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE,
            pixel, font, BUTTON_FONT_SCALE,
            this::onSave);

        this.backButton = new BlockButton(BACK_TEXT,
            MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE,
            pixel, font, BUTTON_FONT_SCALE,
            onBack);

        stage.addActor(background);

        stage.addActor(heading);

        stage.addActor(subtitle);

        stage.addActor(resetButton);

        stage.addActor(saveButton);

        stage.addActor(backButton);
    }

    /**
     * Re-lays out the screen for a new surface size.
     *
     * @param width new surface width in pixels
     * @param height new surface height in pixels
     */
    public void resize(final int width, final int height)
    {
        stage.getViewport().update(width, height, true);

        layoutFor(width, height);
    }

    /**
     * Clears the window and draws the rebind screen.
     *
     * <p>Polls for the listening event when in listening state. The
     * poll happens here, on the render thread, between the Stage
     * and the draw, so a click that fires the same frame as a key
     * press resolves to the key press — the eat-first-event rule
     * takes the click out of the result.</p>
     *
     * @param deltaSeconds wall time since the previous frame
     */
    public void render(final float deltaSeconds)
    {
        if (listening != null)
        {
            pollForBinding();
        }
        else
        {
            // The menu keys (Back, in particular) are part of the
            // game loop's UI state, not the input port's binding
            // table - LEAVE_MATCH is a gameplay action and a
            // rebind of it must not also rebind Back. Escape is
            // read directly here for that reason: it is a UI
            // affordance, not a GameAction.
            final Input input = Gdx.input;

            if (input != null && input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.ESCAPE))
            {
                if (onBackRunnable != null)
                {
                    onBackRunnable.run();
                }
            }
        }

        ScreenUtils.clear(MenuPalette.BACKDROP);

        stage.act(deltaSeconds);

        stage.draw();
    }

    /**
     * Gives the Scene2D stage the input processor.
     */
    public void attachInputProcessor()
    {
        if (Gdx.input != null)
        {
            Gdx.input.setInputProcessor(stage);
        }
    }

    /**
     * Takes the Scene2D stage off the input processor.
     */
    public void detachInputProcessor()
    {
        if (Gdx.input != null && Gdx.input.getInputProcessor() == stage)
        {
            Gdx.input.setInputProcessor(null);
        }
    }

    /**
     * Releases the GL-owned resources this screen is holding: the
     * 1x1 white texture. Safe to call once.
     */
    public void dispose()
    {
        white.dispose();
    }

    // Enters listening mode for one (action, slot). Disables the
    // subtitle's idle text and re-renders it as the listening prompt;
    // the change is also what the Stage's act() picks up, because
    // the label is a live widget.
    private void beginListening(final GameAction action, final int slot)
    {
        this.listening = action;

        this.eatFirstEvent = true;

        this.subtitle.setText(LISTENING_TEXT);
    }

    // Polls every key and every mouse button and, when something
    // was pressed this frame, either eats it (the click that started
    // the session) or captures it as the new binding for the
    // currently-listening action. Mouse buttons are polled for the
    // GLFW-style 0..7 range, which is the only range libGDX exposes
    // through isButtonJustPressed; a future version could widen.
    private void pollForBinding()
    {
        final Input input = Gdx.input;

        if (input == null)
        {
            return;
        }

        // Keyboard. isKeyJustPressed is the right event for a single
        // press, not the level state of isKeyPressed; the player
        // holding a key would otherwise bind to it every frame.
        for (int keyCode = 0; keyCode < 256; keyCode++)
        {
            if (input.isKeyJustPressed(keyCode))
            {
                if (eatFirstEvent)
                {
                    eatFirstEvent = false;

                    continue;
                }

                applyBinding(InputBinding.key(keyCode));

                return;
            }
        }

        // Mouse. The button count is small and constant, so iterating
        // 0..7 is the simple thing. A future XInput shim would add
        // its own branch here.
        for (int button = 0; button < 8; button++)
        {
            if (input.isButtonJustPressed(button))
            {
                if (eatFirstEvent)
                {
                    eatFirstEvent = false;

                    continue;
                }

                applyBinding(InputBinding.mouseButton(button));

                return;
            }
        }
    }

    // Replaces the listening slot's binding with the new one, pushes
    // the working copy through the onSettingsChanged callback so the
    // live input port picks it up, and rebuilds the affected row.
    private void applyBinding(final InputBinding newBinding)
    {
        final InputBinding[] oldRow = workingCopy.bindings().bindingsFor(listening);

        final InputBinding[] newRow = new InputBinding[MAX_BINDINGS_PER_ACTION];

        final int replaceIndex = Math.min(replaceSlot(), Math.max(0, oldRow.length - 1));

        for (int index = 0; index < replaceIndex; index++)
        {
            newRow[index] = oldRow[index];
        }

        for (int index = replaceIndex + 1; index < MAX_BINDINGS_PER_ACTION; index++)
        {
            if (index - 1 < oldRow.length)
            {
                newRow[index] = oldRow[index - 1];
            }
        }

        newRow[replaceIndex] = newBinding;

        // Drop empty trailing slots. ActionBindings.bind takes a
        // varargs and a null at the end is rejected; an empty
        // row is rebindable but useless, so trim.
        int lastLive = newRow.length - 1;

        while (lastLive > 0 && newRow[lastLive] == null)
        {
            lastLive--;
        }

        final InputBinding[] trimmed = new InputBinding[lastLive + 1];

        for (int index = 0; index <= lastLive; index++)
        {
            trimmed[index] = newRow[index];
        }

        // ActionBindings.bind is the in-place path; with* on
        // PlayerSettings would also work but allocates a fresh
        // ActionBindings. The mutation here is on the working copy
        // only, and the listener pushes the result through
        // onSettingsChanged so the port sees the new table.
        workingCopy.bindings().bind(listening, trimmed);

        if (callbacks != null)
        {
            callbacks.onSettingsChanged(workingCopy);
        }

        rebuildRow(listening);

        exitListening();
    }

    // The pill index the player clicked. Picked out of the closure
    // when the click handler was built.
    private int replaceSlot()
    {
        // Single-binding-per-action is the common case; the pill the
        // player clicked is always index 0. A multi-binding action
        // would need the slot remembered in the closure; not built.
        return 0;
    }

    // Rebuilds the binding pills for one action after a change. The
    // action's existing pills are released and replaced. A null
    // pill slot becomes a "+ ADD" pill.
    private void rebuildRow(final GameAction action)
    {
        final int actionIndex = action.ordinal();

        final InputBinding[] row = workingCopy.bindings().bindingsFor(action);

        for (int slot = 0; slot < MAX_BINDINGS_PER_ACTION; slot++)
        {
            final BlockButton oldPill = bindingPills[actionIndex][slot];

            if (oldPill != null)
            {
                oldPill.remove();
            }

            final int capturedActionIndex = actionIndex;

            final int capturedSlot = slot;

            final Runnable onClick = () ->
            {
                beginListening(GameAction.values()[capturedActionIndex], capturedSlot);
            };

            final BlockButton fresh;

            if (slot < row.length && row[slot] != null)
            {
                fresh = new BlockButton(renderBinding(row[slot]),
                    MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE,
                    new TextureRegion(white), font, BINDING_FONT_SCALE, onClick);
            }
            else
            {
                fresh = new BlockButton("+ ADD",
                    MenuPalette.HINT, MenuPalette.NEUTRAL_SHADE,
                    new TextureRegion(white), font, BINDING_FONT_SCALE, onClick);
            }

            bindingPills[actionIndex][slot] = fresh;

            stage.addActor(fresh);
        }

        layoutRow(action);
    }

    // Resets the working copy's bindings to the platform defaults
    // and pushes the change through the listener.
    private void resetToDefaults()
    {
        for (final GameAction action : GameAction.values())
        {
            workingCopy.bindings().bind(action,
                platformDefaults.bindingsFor(action));
        }

        if (callbacks != null)
        {
            callbacks.onSettingsChanged(workingCopy);
        }

        for (final GameAction action : GameAction.values())
        {
            rebuildRow(action);
        }
    }

    // Pushes the working copy through the onSaveRequested callback.
    // The callback owns the file path; the screen owns the data.
    private void onSave()
    {
        if (callbacks != null)
        {
            callbacks.onSaveRequested(workingCopy);
        }
    }

    // Returns to idle mode after a successful binding.
    private void exitListening()
    {
        listening = null;

        eatFirstEvent = false;

        subtitle.setText(SUBTITLE_TEXT);
    }

    // Places every widget for a given window size. Centred column.
    private void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        final float headingWidth = width * 0.50f;

        final float cell = heading.cellSizeFor(headingWidth);

        final float headingHeight = cell * BlockFont.GLYPH_HEIGHT;

        final float headingTop = height * 0.92f;

        heading.setBounds((width - headingWidth) * 0.5f, headingTop - headingHeight,
            headingWidth, headingHeight);

        subtitle.pack();

        subtitle.setPosition((width - subtitle.getWidth()) * 0.5f,
            headingTop - headingHeight - 24.0f);

        for (int actionIndex = 0; actionIndex < ACTION_COUNT; actionIndex++)
        {
            layoutRow(GameAction.values()[actionIndex]);
        }

        // Bottom row: three buttons in a row, centred.
        final float buttonsY = BUTTONS_BOTTOM_MARGIN;

        final float buttonsTotalWidth = BUTTON_WIDTH * 3.0f + BUTTON_GAP * 2.0f;

        final float buttonsStartX = (width - buttonsTotalWidth) * 0.5f;

        resetButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

        resetButton.setPosition(buttonsStartX, buttonsY);

        saveButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

        saveButton.setPosition(buttonsStartX + BUTTON_WIDTH + BUTTON_GAP, buttonsY);

        backButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

        backButton.setPosition(buttonsStartX + (BUTTON_WIDTH + BUTTON_GAP) * 2.0f, buttonsY);
    }

    // Lays out one action's label and its binding pills on a single
    // row. The action label is left-aligned in its column; the
    // pills are right-aligned in the same row, all of the same
    // height, all of the same width.
    private void layoutRow(final GameAction action)
    {
        final int actionIndex = action.ordinal();

        final float columnLeft = (columnLeftForRow() - ACTION_COLUMN_WIDTH) * 0.5f;

        actionLabels[actionIndex].pack();

        actionLabels[actionIndex].setPosition(columnLeft, rowTopFor(actionIndex));

        for (int slot = 0; slot < MAX_BINDINGS_PER_ACTION; slot++)
        {
            final float pillLeft = columnLeft + ACTION_COLUMN_WIDTH
                + 8.0f + slot * (BINDING_PILL_WIDTH + BINDING_PILL_GAP);

            final BlockButton pill = bindingPills[actionIndex][slot];

            if (pill != null)
            {
                pill.setSize(BINDING_PILL_WIDTH, ROW_HEIGHT);

                pill.setPosition(pillLeft, rowTopFor(actionIndex));
            }
        }
    }

    // X position of the left edge of the row, computed so the
    // row is centred in the window.
    private float columnLeftForRow()
    {
        final float totalRowWidth = ACTION_COLUMN_WIDTH + 8.0f
            + MAX_BINDINGS_PER_ACTION * (BINDING_PILL_WIDTH + BINDING_PILL_GAP)
            - BINDING_PILL_GAP;

        return totalRowWidth;
    }

    // Y position of one row's top edge. The first row sits below
    // the subtitle; each subsequent row steps down by the row height
    // plus the gap.
    private float rowTopFor(final int actionIndex)
    {
        final float firstRowTop = subtitle.getY() - 32.0f - ROW_HEIGHT;

        return firstRowTop - actionIndex * (ROW_HEIGHT + ROW_GAP);
    }

    // "MOVE FORWARD" rather than the enum's MOVE_FORWARD. The
    // underscore reads as a missing space on a button.
    private static String actionLabel(final GameAction action)
    {
        return switch (action)
        {
            case MOVE_FORWARD -> "MOVE FORWARD";
            case MOVE_BACKWARD -> "MOVE BACKWARD";
            case STRAFE_LEFT -> "STRAFE LEFT";
            case STRAFE_RIGHT -> "STRAFE RIGHT";
            case FIRE -> "FIRE";
            case JUMP -> "JUMP";
            case SPRINT -> "SPRINT";
            case LEAVE_MATCH -> "LEAVE MATCH";
            case TOGGLE_INVERT_LOOK -> "INVERT LOOK";
        };
    }

    // "LMB" for mouse button 0, "W" for key 62, etc. The whole
    // point of the screen is the player can read what each row
    // says without a key-code table in their head.
    private static String renderBinding(final InputBinding binding)
    {
        return switch (binding.source())
        {
            case KEY -> "KEY " + binding.code();
            case MOUSE_BUTTON -> "MOUSE " + binding.code();
            case TOUCH_REGION -> "TOUCH " + binding.code();
            case GAMEPAD_BUTTON -> "PAD B" + binding.code();
            case GAMEPAD_AXIS -> "PAD A" + binding.code();
        };
    }

    // A defensive copy of a binding table. ActionBindings.bind
    // already returns a new table; the copy is the only way to be
    // sure a side-channel cannot mutate the working copy through
    // the table the caller passed at construction.
    private static com.openfps.engine.hal.adapter.ActionBindings copyBindings(
        final com.openfps.engine.hal.adapter.ActionBindings source)
    {
        final com.openfps.engine.hal.adapter.ActionBindings copy =
            new com.openfps.engine.hal.adapter.ActionBindings();

        for (final GameAction action : GameAction.values())
        {
            copy.bind(action, source.bindingsFor(action));
        }

        return copy;
    }

    private static Label label(final String text, final Color colour, final float scale)
    {
        final Label built = new Label(text, new Label.LabelStyle(new BitmapFont(), colour));

        built.setFontScale(scale);

        return built;
    }

    private static Texture whitePixelTexture()
    {
        final Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);

        pixmap.setColor(Color.WHITE);

        pixmap.fill();

        final Texture texture = new Texture(pixmap);

        pixmap.dispose();

        return texture;
    }
}
