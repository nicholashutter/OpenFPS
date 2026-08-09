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
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import com.openfps.engine.gameplay.MatchMode;

/**
 * The multiplayer lobby: the chosen map's thumbnail on the left, the
 * chosen map and mode on the right, and three keys: HOST, JOIN, BACK.
 *
 * <p>Reached from the map browser when the player has chosen a map
 * for a multiplayer game. The screen's only responsibility is to
 * collect a HOST / JOIN choice and the chosen map; the network
 * plumbing (the bind, the lobby protocol, the matchmaking) is owned
 * by the engine, not the menu. The state machine advances to
 * {@link UiState#LOADING} on either choice, and the engine does the
 * rest.</p>
 *
 * <p>For the first cut the screen is a stub: HOST and JOIN both
 * route through the loading screen, the same way the single-player
 * path does. The single/multi distinction lives in the engine, not
 * in the menu, so the menu's only job here is to record the choice
 * and pass control back.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Constructed and used only on the platform render thread, after
 * the GL context exists.</p>
 *
 * Platform adapter &mdash; must not import from core engine packages.
 */
public final class LobbyScreen
{
    /** The line of help under the heading. */
    public static final String SUBTITLE_TEXT = "Host a new match, or join one already running.";

    /** The label on the Back key. */
    public static final String BACK_TEXT = "BACK";

    /** The label on the Host key. */
    public static final String HOST_TEXT = "HOST MATCH";

    /** The label on the Join key. */
    public static final String JOIN_TEXT = "JOIN MATCH";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.50f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.10f;

    /** Gap between the heading and the body row. */
    private static final float BODY_GAP = 28.0f;

    /** Width of the thumbnail on the left of the body row. */
    private static final float THUMBNAIL_WIDTH = 480.0f;

    /** Height of the thumbnail on the left of the body row. */
    private static final float THUMBNAIL_HEIGHT = 270.0f;

    /** Gap between the thumbnail and the right-hand text. */
    private static final float SIDE_GAP = 32.0f;

    /** Width of the right-hand text column. */
    private static final float TEXT_WIDTH = 320.0f;

    /** Gap between the body row and the buttons. */
    private static final float BUTTON_GAP = 36.0f;

    /** Gap between adjacent buttons. */
    private static final float BUTTON_ROW_GAP = 14.0f;

    /** Clear space left under the Back key. */
    private static final float BOTTOM_MARGIN = 28.0f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 320.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 54.0f;

    /** Subtitle font magnification. */
    private static final float SUBTITLE_FONT_SCALE = 1.0f;

    /** Body text font magnification. */
    private static final float BODY_FONT_SCALE = 1.15f;

    /** Button font magnification. */
    private static final float BUTTON_FONT_SCALE = 1.35f;

    /** The Scene2D stage that owns the widget hierarchy and input. */
    private final Stage stage;

    /** The 1x1 white texture every rectangle is drawn from. */
    private final Texture white;

    /** The built-in font, used for the labels. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** The block heading (the mode name). */
    private final BlockTitle heading;

    /** The line under the heading. */
    private final Label subtitle;

    /** The chosen map's thumbnail. */
    private final Image thumbnail;

    /** The thumbnail texture, owned by this screen and released in {@link #dispose()}. */
    private final Texture thumbnailTexture;

    /** The map name label on the right of the body row. */
    private final Label mapNameLabel;

    /** The mode name label on the right of the body row. */
    private final Label modeNameLabel;

    /** The Host key. */
    private final BlockButton hostButton;

    /** The Join key. */
    private final BlockButton joinButton;

    /** The way back to the map browser. */
    private final BlockButton backButton;

    /**
     * Builds the lobby.
     *
     * @param displayName the map's display name; must not be blank
     * @param mode the multiplayer mode; must not be null
     * @param thumbnailPath the classpath-relative path to the map's
     *     thumbnail PNG; must not be blank
     * @param onHost run when the player clicks HOST; must not be null
     * @param onJoin run when the player clicks JOIN; must not be null
     * @param onBack run when the player clicks BACK; must not be null
     * @throws IllegalArgumentException if any argument is null or blank
     */
    public LobbyScreen(final String displayName, final MatchMode mode, final String thumbnailPath,
        final Runnable onHost, final Runnable onJoin, final Runnable onBack)
    {
        if (displayName == null || displayName.isBlank())
        {
            throw new IllegalArgumentException("displayName must not be blank");
        }

        if (mode == null)
        {
            throw new IllegalArgumentException("mode must not be null");
        }

        if (thumbnailPath == null || thumbnailPath.isBlank())
        {
            throw new IllegalArgumentException("thumbnailPath must not be blank");
        }

        if (onHost == null)
        {
            throw new IllegalArgumentException("onHost must not be null");
        }

        if (onJoin == null)
        {
            throw new IllegalArgumentException("onJoin must not be null");
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

        final String headingText;

        if (mode == MatchMode.TDM)
        {
            headingText = "TEAM DEATHMATCH";
        }
        else if (mode == MatchMode.HARDPOINT)
        {
            headingText = "HARDPOINT";
        }
        else if (mode == MatchMode.DOMINATION)
        {
            headingText = "DOMINATION";
        }
        else if (mode == MatchMode.CTF)
        {
            headingText = "CAPTURE THE FLAG";
        }
        else
        {
            headingText = mode.name();
        }

        this.heading = new BlockTitle(headingText, pixel, MenuPalette.NET_FACE);

        this.subtitle = label(SUBTITLE_TEXT, MenuPalette.HINT, SUBTITLE_FONT_SCALE);

        this.thumbnailTexture = loadThumbnail(thumbnailPath);

        this.thumbnail = new Image(new TextureRegion(thumbnailTexture));

        this.thumbnail.setSize(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);

        this.mapNameLabel = label("MAP:  " + displayName, MenuPalette.BUTTON_LABEL,
            BODY_FONT_SCALE);

        this.modeNameLabel = label("MODE:  " + headingText, MenuPalette.BUTTON_LABEL,
            BODY_FONT_SCALE);

        this.hostButton = new BlockButton(HOST_TEXT, MenuPalette.PLAY_FACE,
            MenuPalette.PLAY_SHADE, pixel, font, BUTTON_FONT_SCALE, onHost);

        this.joinButton = new BlockButton(JOIN_TEXT, MenuPalette.NET_FACE,
            MenuPalette.NET_SHADE, pixel, font, BUTTON_FONT_SCALE, onJoin);

        this.backButton = new BlockButton(BACK_TEXT, MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE, onBack);

        stage.addActor(background);

        stage.addActor(heading);

        stage.addActor(subtitle);

        stage.addActor(thumbnail);

        stage.addActor(mapNameLabel);

        stage.addActor(modeNameLabel);

        hostButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

        stage.addActor(hostButton);

        joinButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

        stage.addActor(joinButton);

        backButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

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
     * Clears the window and draws the lobby.
     *
     * @param deltaSeconds wall time since the previous frame
     */
    public void render(final float deltaSeconds)
    {
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
     * Releases the GL-owned resources this screen is holding.
     */
    public void dispose()
    {
        white.dispose();

        if (thumbnailTexture != white)
        {
            thumbnailTexture.dispose();
        }
    }

    private Texture loadThumbnail(final String classpathPath)
    {
        try
        {
            return new Texture(Gdx.files.internal(classpathPath));
        }
        catch (final Exception e)
        {
            return white;
        }
    }

    // Places everything for a given window size. Heading centred at
    // the top; the body row (thumbnail on the left, text on the
    // right) sits below it; the three buttons stack below the body.
    private void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        final float headingWidth = width * TITLE_WIDTH_FRACTION;

        final float cell = heading.cellSizeFor(headingWidth);

        final float headingHeight = cell * BlockFont.GLYPH_HEIGHT;

        final float headingTop = height * (1.0f - TITLE_TOP_FRACTION);

        heading.setBounds((width - headingWidth) * 0.5f, headingTop - headingHeight,
            headingWidth, headingHeight);

        subtitle.pack();

        final float subtitleTop = headingTop - headingHeight - BODY_GAP;

        subtitle.setPosition((width - subtitle.getWidth()) * 0.5f,
            subtitleTop - subtitle.getHeight());

        // Body row: thumbnail on the left, text on the right.
        final float bodyWidth = THUMBNAIL_WIDTH + SIDE_GAP + TEXT_WIDTH;

        final float bodyLeft = (width - bodyWidth) * 0.5f;

        final float bodyTop = subtitleTop - subtitle.getHeight();

        thumbnail.setPosition(bodyLeft, bodyTop - THUMBNAIL_HEIGHT);

        final float textLeft = bodyLeft + THUMBNAIL_WIDTH + SIDE_GAP;

        final float textTop = bodyTop - 40.0f;

        mapNameLabel.pack();

        mapNameLabel.setPosition(textLeft, textTop - mapNameLabel.getHeight());

        modeNameLabel.pack();

        modeNameLabel.setPosition(textLeft, textTop - mapNameLabel.getHeight()
            - modeNameLabel.getHeight() - 12.0f);

        // Buttons centred under the body.
        final float buttonStackLeft = (width - BUTTON_WIDTH) * 0.5f;

        final float buttonStackTop = bodyTop - THUMBNAIL_HEIGHT - BUTTON_GAP;

        hostButton.setPosition(buttonStackLeft, buttonStackTop - BUTTON_HEIGHT);

        joinButton.setPosition(buttonStackLeft,
            buttonStackTop - BUTTON_HEIGHT - BUTTON_HEIGHT - BUTTON_ROW_GAP);

        backButton.setPosition(buttonStackLeft, BOTTOM_MARGIN);
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
