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
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import com.openfps.engine.gameplay.MatchMode;

import java.util.function.Consumer;

/**
 * The mode picker: a column of four coloured keys for the four real
 * rule sets, plus a Back.
 *
 * <p>Reached from the main menu after the player picks single- or
 * multiplayer; the call records the body source on the state machine
 * before the transition. The player's mode pick is the input to
 * {@code UiStateMachine.pickMode}, which records the rule set and
 * advances the screen to the map picker. The mode is what the map
 * picker then filters on &mdash; the four thumbnails it shows are
 * the four maps registered for the chosen rule set.</p>
 *
 * <h2>Four colours, four modes</h2>
 *
 * <p>Each rule set gets the colour the rest of the game uses for it:
 * TDM red, Hardpoint yellow, Domination blue, CTF green. The
 * thumbnail border in the map picker reads from the same palette, so
 * the colour the player just pressed is the colour that frames the
 * map the player is about to pick. The convention is documented in
 * STYLE.md.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Constructed and used only on the platform render thread, after the
 * GL context exists.</p>
 *
 * Platform adapter &mdash; must not import from core engine packages.
 */
public final class ModeSelectionScreen
{
    /** The word the heading spells. */
    public static final String TITLE_TEXT = "SELECT MODE";

    /** The line of help under the heading. */
    public static final String SUBTITLE_TEXT = "Pick a rule set. Then pick a map.";

    /** The label on the Back key. */
    public static final String BACK_TEXT = "BACK";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.58f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.10f;

    /** Gap between the heading and the subtitle. */
    private static final float SUBTITLE_GAP = 18.0f;

    /** Gap between the subtitle and the first mode key. */
    private static final float LIST_GAP = 36.0f;

    /** Gap between mode keys. */
    private static final float ROW_GAP = 14.0f;

    /** Clear space left under the Back key. */
    private static final float BOTTOM_MARGIN = 28.0f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 540.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 60.0f;

    /** Label font magnification. */
    private static final float BUTTON_FONT_SCALE = 1.45f;

    /** Subtitle font magnification. */
    private static final float SUBTITLE_FONT_SCALE = 1.0f;

    /** The Scene2D stage that owns the widget hierarchy and input. */
    private final Stage stage;

    /** The 1x1 white texture every rectangle is drawn from. */
    private final Texture white;

    /** The built-in font, used for the labels. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** The block heading. */
    private final BlockTitle heading;

    /** The line under the heading. */
    private final Label subtitle;

    /** The four mode keys, top to bottom. */
    private final BlockButton[] modeButtons;

    /** The way back to the previous screen. */
    private final BlockButton backButton;

    /**
     * Builds the mode picker.
     *
     * @param onPick called with the mode the player chose; must not be null
     * @param onBack called when the player clicks BACK; must not be null
     * @throws IllegalArgumentException if any argument is null
     */
    public ModeSelectionScreen(final Consumer<MatchMode> onPick, final Runnable onBack)
    {
        if (onPick == null)
        {
            throw new IllegalArgumentException("onPick must not be null");
        }

        if (onBack == null)
        {
            throw new IllegalArgumentException("onBack must not be null");
        }

        this.white = whitePixelTexture();

        final TextureRegion pixel = new TextureRegion(white);

        this.font = new BitmapFont();

        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);

        this.heading = new BlockTitle(TITLE_TEXT, pixel, MenuPalette.NEUTRAL_FACE);

        this.subtitle = label(SUBTITLE_TEXT, MenuPalette.HINT, SUBTITLE_FONT_SCALE);

        // TDM red, Hardpoint yellow, Domination blue, CTF green.
        // The face/shade pair keeps hue and drops value so the bevel reads as
        // the same material in shadow rather than as a darker colour.
        this.modeButtons = new BlockButton[]
        {
            new BlockButton("TEAM DEATHMATCH", MenuPalette.QUIT_FACE, MenuPalette.QUIT_SHADE,
                pixel, font, BUTTON_FONT_SCALE, () -> onPick.accept(MatchMode.TDM)),
            new BlockButton("HARDPOINT", MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE,
                pixel, font, BUTTON_FONT_SCALE, () -> onPick.accept(MatchMode.HARDPOINT)),
            new BlockButton("DOMINATION", MenuPalette.NET_FACE, MenuPalette.NET_SHADE,
                pixel, font, BUTTON_FONT_SCALE, () -> onPick.accept(MatchMode.DOMINATION)),
            new BlockButton("CAPTURE THE FLAG", MenuPalette.PLAY_FACE, MenuPalette.PLAY_SHADE,
                pixel, font, BUTTON_FONT_SCALE, () -> onPick.accept(MatchMode.CTF)),
        };

        this.backButton = new BlockButton(BACK_TEXT, MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE, onBack);

        stage.addActor(background);

        stage.addActor(heading);

        stage.addActor(subtitle);

        for (final BlockButton button : modeButtons)
        {
            button.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

            stage.addActor(button);
        }

        backButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

        stage.addActor(backButton);
    }

    /** Returns the four mode keys, top to bottom. Never null. */
    public BlockButton[] modeButtons()
    {
        return modeButtons.clone();
    }

    /** Returns the way-back button. */
    public BlockButton backButton()
    {
        return backButton;
    }

    /**
     * Re-lays out the picker for a new surface size.
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
     * Draws the picker. Caller has already cleared the framebuffer.
     *
     * @param deltaSeconds wall time since the previous frame; used for the
     *     background drift and not otherwise consumed here
     */
    public void render(final float deltaSeconds)
    {
        stage.act(deltaSeconds);

        stage.draw();
    }

    /**
     * Releases the GL-owned resources this screen is holding: the
     * 1x1 white texture. The caller is responsible for disposing the
     * shared font, the stage, and any platform-owned resources.
     */
    public void dispose()
    {
        white.dispose();
    }

    /**
     * Gives the Scene2D stage the input processor, so the buttons respond.
     */
    public void attachInputProcessor()
    {
        if (Gdx.input != null)
        {
            Gdx.input.setInputProcessor(stage);
        }
    }

    /**
     * Takes the Scene2D stage off the input processor, so subsequent
     * states do not see the picker's clicks.
     */
    public void detachInputProcessor()
    {
        if (Gdx.input != null && Gdx.input.getInputProcessor() == stage)
        {
            Gdx.input.setInputProcessor(null);
        }
    }

    // Places everything for a given window size. Absolute placement, same as
    // the menu and the map picker: the heading is sized to the window, the
    // keys are fixed pixel size so they stay finger-and-pointer sized
    // whatever the window does.
    public void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        final float headingWidth = width * TITLE_WIDTH_FRACTION;

        final float cell = heading.cellSizeFor(headingWidth);

        final float headingHeight = cell * BlockFont.GLYPH_HEIGHT;

        final float headingTop = height * (1.0f - TITLE_TOP_FRACTION);

        heading.setBounds((width - headingWidth) * 0.5f, headingTop - headingHeight,
            headingWidth, headingHeight);

        subtitle.pack();

        final float subtitleTop = headingTop - headingHeight - SUBTITLE_GAP;

        subtitle.setPosition((width - subtitle.getWidth()) * 0.5f,
            subtitleTop - subtitle.getHeight());

        float nextTop = subtitleTop - subtitle.getHeight() - LIST_GAP;

        for (int row = 0; row < modeButtons.length; row++)
        {
            modeButtons[row].setPosition((width - BUTTON_WIDTH) * 0.5f,
                nextTop - BUTTON_HEIGHT);

            nextTop = nextTop - BUTTON_HEIGHT - ROW_GAP;
        }

        backButton.setPosition((width - BUTTON_WIDTH) * 0.5f, BOTTOM_MARGIN);
    }

    private Label label(final String text, final Color colour, final float scale)
    {
        final Label built = new Label(text, new Label.LabelStyle(font, colour));

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
