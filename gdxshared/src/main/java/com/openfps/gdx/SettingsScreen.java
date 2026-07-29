/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * The settings screen: currently one switch, and the way back.
 *
 * <p>The switch is the debug frame counter. Pressing it flips
 * {@link DebugSettings}, which is read by the {@link DebugOverlay} that appears
 * in the world and — through {@code DebugSettings.onChange} — by the software
 * renderer's outline pass. This screen knows about none of that; it flips one
 * boolean and relabels one button.</p>
 *
 * <h2>The button says what the setting is, not what pressing it does</h2>
 *
 * <p>"DEBUG OVERLAY  ON" rather than "TURN DEBUG OVERLAY OFF". A toggle labelled
 * with its action makes the reader work out the current state by inverting the
 * label, and gets it wrong about half the time. Labelling it with the state
 * means the screen can be read rather than decoded, which is why the label is
 * rebuilt from {@link DebugSettings#overlayLabel()} on every press.</p>
 *
 * <p><b>The setting does not survive the run</b> — see {@link DebugSettings} for
 * exactly what persisting it would cost and why that bill is not worth paying
 * for a diagnostic overlay.</p>
 *
 * <p><b>No logic lives here.</b> Both buttons forward and do nothing else, which
 * is what leaves this class as pure layout — the part that needs a window to
 * judge and cannot be covered headlessly.</p>
 *
 * <p><b>Threading:</b> constructed and used only on the platform render thread,
 * after the GL context exists.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class SettingsScreen
{
    /** The word the heading spells. */
    public static final String TITLE_TEXT = "SETTINGS";

    /** The stem of the debug toggle's label; the state is appended. */
    public static final String DEBUG_LABEL = "DEBUG OVERLAY";

    /** What the debug toggle actually does, spelled out under it. */
    public static final String DEBUG_HINT =
        "FPS and frame time in the corner, and outlines around targets";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.46f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.14f;

    /** Gap under the heading before the first control, in pixels. */
    private static final float CONTROL_GAP = 56.0f;

    /** Gap under a control before its explanatory line, in pixels. */
    private static final float HINT_GAP = 14.0f;

    /** Gap under the hint before the Back key, in pixels. */
    private static final float BACK_GAP = 48.0f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 420.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 62.0f;

    /** Button label font magnification over the 15px built-in font. */
    private static final float BUTTON_FONT_SCALE = 1.6f;

    /** Hint font magnification. */
    private static final float HINT_FONT_SCALE = 1.1f;

    /** The switch this screen flips. Never null. */
    private final DebugSettings settings;

    /** The Scene2D stage that owns the widget hierarchy and input. */
    private final Stage stage;

    /** The 1x1 white texture every rectangle is drawn from. */
    private final Texture white;

    /** The built-in font, used for the labels and the hint. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** The block heading. */
    private final BlockTitle heading;

    /** The debug switch, relabelled on every press. */
    private final BlockButton debugButton;

    /** What the debug switch does, in words. */
    private final Label debugHint;

    /** The way back to the menu. */
    private final BlockButton backButton;

    /** How far to magnify the fixed pixel metrics — 1 on desktop, density on a phone. */
    private final float uiScale;

    /**
     * Builds the screen at desktop metrics. Requires a live GL context.
     *
     * @param debugSettings the switch to flip; must not be null
     * @param onBack run when the player leaves; must not be null
     */
    public SettingsScreen(final DebugSettings debugSettings, final Runnable onBack)
    {
        this(debugSettings, onBack, 1.0f);
    }

    /**
     * Builds the screen. Requires a live GL context — construct from
     * {@code ApplicationListener.create()} or later, never earlier.
     *
     * @param debugSettings the switch to flip; must not be null
     * @param onBack run when the player leaves; must not be null
     * @param scale multiplies every fixed pixel metric. 1 on a desktop monitor;
     *     a phone passes its density so a button stays the same physical size
     *     whatever the panel does. Must be positive
     * @throws IllegalArgumentException if anything is null or {@code scale} is
     *     not positive
     */
    public SettingsScreen(final DebugSettings debugSettings, final Runnable onBack,
        final float scale)
    {
        if (debugSettings == null)
        {
            throw new IllegalArgumentException("debugSettings must not be null");
        }
        if (onBack == null)
        {
            throw new IllegalArgumentException("onBack must not be null");
        }
        if (!(scale > 0.0f))
        {
            throw new IllegalArgumentException("scale must be positive, got " + scale);
        }
        this.settings = debugSettings;
        this.uiScale = scale;
        this.white = whitePixelTexture();
        final TextureRegion pixel = new TextureRegion(white);
        this.font = new BitmapFont();
        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);
        this.heading = new BlockTitle(TITLE_TEXT, pixel, MenuPalette.NEUTRAL_FACE);

        this.debugButton = new BlockButton(debugButtonLabel(debugSettings),
            MenuPalette.NET_FACE, MenuPalette.NET_SHADE, pixel, font,
            BUTTON_FONT_SCALE * scale, this::toggleDebug);
        this.debugHint = label(DEBUG_HINT, MenuPalette.HINT, HINT_FONT_SCALE * scale);
        this.backButton = new BlockButton("BACK", MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE * scale, onBack);

        stage.addActor(background);
        stage.addActor(heading);
        debugButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);
        stage.addActor(debugButton);
        stage.addActor(debugHint);
        backButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);
        stage.addActor(backButton);
    }

    /**
     * Returns the label the debug toggle should carry for a given setting.
     *
     * <p>Static and public because it is the one part of this screen a headless
     * test can reach: that the button reports the state rather than inverting
     * it. See the class Javadoc on why that distinction is worth pinning.</p>
     *
     * @param debugSettings the switch to describe; must not be null
     * @return the full button label, state included
     */
    public static String debugButtonLabel(final DebugSettings debugSettings)
    {
        if (debugSettings == null)
        {
            throw new IllegalArgumentException("debugSettings must not be null");
        }
        return DEBUG_LABEL + "   " + debugSettings.overlayLabel();
    }

    /** Returns the switch this screen flips. Never null. */
    public DebugSettings settings()
    {
        return settings;
    }

    /** Returns the debug toggle. */
    public BlockButton debugButton()
    {
        return debugButton;
    }

    /** Returns the button that leaves this screen. */
    public BlockButton backButton()
    {
        return backButton;
    }

    // Flips the switch and makes the button say so. One method rather than a
    // two-statement lambda, per STYLE.md § 6.2.
    private void toggleDebug()
    {
        settings.toggleOverlay();
        debugButton.setLabel(debugButtonLabel(settings));
    }

    /**
     * Places everything for a given window size.
     *
     * @param width window width in pixels
     * @param height window height in pixels
     */
    public void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        final float headingWidth = width * TITLE_WIDTH_FRACTION;
        final float cell = heading.cellSizeFor(headingWidth);
        final float headingHeight = cell * BlockFont.GLYPH_HEIGHT;
        final float headingTop = height * (1.0f - TITLE_TOP_FRACTION);
        heading.setBounds((width - headingWidth) * 0.5f, headingTop - headingHeight,
            headingWidth, headingHeight);

        final float buttonWidth = BUTTON_WIDTH * uiScale;
        final float buttonHeight = BUTTON_HEIGHT * uiScale;
        debugButton.setSize(buttonWidth, buttonHeight);
        backButton.setSize(buttonWidth, buttonHeight);

        final float debugTop = headingTop - headingHeight - CONTROL_GAP * uiScale;
        debugButton.setPosition((width - buttonWidth) * 0.5f, debugTop - buttonHeight);

        debugHint.pack();
        final float hintTop = debugTop - buttonHeight - HINT_GAP * uiScale;
        debugHint.setPosition((width - debugHint.getWidth()) * 0.5f,
            hintTop - debugHint.getHeight());

        final float backTop = hintTop - debugHint.getHeight() - BACK_GAP * uiScale;
        backButton.setPosition((width - buttonWidth) * 0.5f, backTop - buttonHeight);
    }

    /**
     * Clears the window and draws the settings over it.
     *
     * @param deltaSeconds wall time since the previous frame, used only for the
     *     backdrop's drift
     */
    public void render(final float deltaSeconds)
    {
        ScreenUtils.clear(MenuPalette.BACKDROP);
        stage.act(deltaSeconds);
        stage.draw();
    }

    /** Gives the Scene2D stage the input processor, so the buttons respond. */
    public void attachInputProcessor()
    {
        if (Gdx.input == null)
        {
            return;
        }
        Gdx.input.setInputProcessor(stage);
    }

    /** Takes the input processor away, so this screen consumes nothing. */
    public void detachInputProcessor()
    {
        if (Gdx.input == null)
        {
            return;
        }
        Gdx.input.setInputProcessor(null);
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

    /** Releases the stage, texture and font. Safe to call once. */
    public void dispose()
    {
        stage.dispose();
        white.dispose();
        font.dispose();
    }

    // One white pixel, which everything on this screen is a tinted rectangle of.
    private static Texture whitePixelTexture()
    {
        final Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        final Texture texture = new Texture(pixmap);
        pixmap.dispose();
        return texture;
    }

    // A scaled label in the built-in font.
    private Label label(final String text, final Color colour, final float scale)
    {
        final Label built = new Label(text, new Label.LabelStyle(font, colour));
        built.setFontScale(scale);
        return built;
    }
}
