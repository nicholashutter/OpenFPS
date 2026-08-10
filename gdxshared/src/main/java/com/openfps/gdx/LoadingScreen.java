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

/**
 * The loading screen: a centred thumbnail of the chosen map, the map
 * name in large type, and a "LOADING" caption that the engine hides
 * the moment the scene swap is complete.
 *
 * <p>Reached from the map browser once the player has picked a map.
 * Single-player goes straight here from the browser; multiplayer
 * comes via the lobby. The screen stays up long enough for the
 * engine to load the map's {@code .ofm} and build its scene; on the
 * next reconciliation the frame loop sees {@link UiState#PLAYING}
 * and stops drawing the loading screen.</p>
 *
 * <p>For the case where the engine hangs on the load, the screen has
 * a Back affordance that returns the player to the map browser
 * rather than trapping them on a frozen glass.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Constructed and used only on the platform render thread, after
 * the GL context exists.</p>
 *
 * Platform adapter &mdash; must not import from core engine packages.
 */
public final class LoadingScreen
{
    /** The line of help under the heading. */
    public static final String SUBTITLE_TEXT = "Loading the map...";

    /** The label on the Back key. */
    public static final String BACK_TEXT = "BACK";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.50f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.10f;

    /** Gap between the heading and the thumbnail. */
    private static final float THUMBNAIL_GAP = 28.0f;

    /** Width of the thumbnail displayed on this screen. */
    private static final float THUMBNAIL_WIDTH = 480.0f;

    /** Height of the thumbnail displayed on this screen. */
    private static final float THUMBNAIL_HEIGHT = 270.0f;

    /** Gap between the thumbnail and the caption. */
    private static final float CAPTION_GAP = 20.0f;

    /** Clear space left under the Back key. */
    private static final float BOTTOM_MARGIN = 28.0f;

    /**
     * How long the loading screen waits before auto-transitioning
     * to the game. Long enough for a player to read the map name
     * and feel the screen arrive; short enough that the screen is
     * not a wait. The actual map swap is instant for the shipped
     * maps (the .ofm is already in the classpath), so this is
     * primarily a UX delay.
     */
    public static final float AUTO_START_SECONDS = 1.5f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 280.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 50.0f;

    /** Heading font magnification. */
    private static final float HEADING_FONT_SCALE = 1.0f;

    /** Subtitle font magnification. */
    private static final float SUBTITLE_FONT_SCALE = 1.05f;

    /** Caption font magnification. */
    private static final float CAPTION_FONT_SCALE = 1.2f;

    /** Back button font magnification. */
    private static final float BUTTON_FONT_SCALE = 1.35f;

    /** The Scene2D stage that owns the widget hierarchy and input. */
    private final Stage stage;

    /** The 1x1 white texture every rectangle is drawn from. */
    private final Texture white;

    /** The built-in font, used for the labels. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** The block heading (the map's display name). */
    private final BlockTitle heading;

    /** The line under the heading. */
    private final Label subtitle;

    /** The chosen map's thumbnail. */
    private final Image thumbnail;

    /** The thumbnail texture, owned by this screen and released in {@link #dispose()}. */
    private final Texture thumbnailTexture;

    /** The way back to the map browser, in case the engine hangs. */
    private final BlockButton backButton;

    /** Run when the loading screen's auto-start timer fires. */
    private final Runnable onReady;

    /** Seconds the screen has been on glass. Drives the auto-start. */
    private float elapsedSeconds;

    /**
     * Builds the loading screen.
     *
     * @param displayName the map's display name; must not be blank
     * @param thumbnailPath the classpath-relative path to the map's
     *     thumbnail PNG; must not be blank
     * @param onReady run when the screen has been on glass long enough
     *     and the engine is ready to enter the world; must not be null
     * @param onBack run when the player clicks BACK; must not be null
     * @throws IllegalArgumentException if any argument is null or blank
     */
    public LoadingScreen(final String displayName, final String thumbnailPath,
        final Runnable onReady, final Runnable onBack)
    {
        if (displayName == null || displayName.isBlank())
        {
            throw new IllegalArgumentException("displayName must not be blank");
        }

        if (thumbnailPath == null || thumbnailPath.isBlank())
        {
            throw new IllegalArgumentException("thumbnailPath must not be blank");
        }

        if (onReady == null)
        {
            throw new IllegalArgumentException("onReady must not be null");
        }

        if (onBack == null)
        {
            throw new IllegalArgumentException("onBack must not be null");
        }

        this.onReady = onReady;

        this.white = whitePixelTexture();

        final TextureRegion pixel = new TextureRegion(white);

        this.font = new BitmapFont();

        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);

        this.heading = new BlockTitle(displayName.toUpperCase(java.util.Locale.ROOT),
            pixel, MenuPalette.NEUTRAL_FACE);

        this.subtitle = label(SUBTITLE_TEXT, MenuPalette.HINT, SUBTITLE_FONT_SCALE);

        this.thumbnailTexture = loadThumbnail(thumbnailPath);

        this.thumbnail = new Image(new TextureRegion(thumbnailTexture));

        this.thumbnail.setSize(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);

        this.backButton = new BlockButton(BACK_TEXT, MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE, onBack);

        stage.addActor(background);

        stage.addActor(heading);

        stage.addActor(subtitle);

        stage.addActor(thumbnail);

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
     * Clears the window and draws the loading screen.
     *
     * @param deltaSeconds wall time since the previous frame
     */
    public void render(final float deltaSeconds)
    {
        ScreenUtils.clear(MenuPalette.BACKDROP);

        stage.act(deltaSeconds);

        stage.draw();

        // Auto-start after AUTO_START_SECONDS. The actual map swap is
        // instant for the shipped maps, so this is a UX delay rather
        // than a load progress; once a real loader is in place the
        // same callback can be wired to the loader's "ready" event
        // and the timer can be removed.
        elapsedSeconds += deltaSeconds;

        if (elapsedSeconds >= AUTO_START_SECONDS)
        {
            elapsedSeconds = AUTO_START_SECONDS;

            onReady.run();
        }
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
     * 1x1 white texture and the loaded thumbnail.
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

    // Places everything for a given window size. Centred column.
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

        final float subtitleTop = headingTop - headingHeight - THUMBNAIL_GAP;

        subtitle.setPosition((width - subtitle.getWidth()) * 0.5f,
            subtitleTop - subtitle.getHeight());

        final float thumbLeft = (width - THUMBNAIL_WIDTH) * 0.5f;

        final float thumbTop = subtitleTop - subtitle.getHeight() - THUMBNAIL_HEIGHT - CAPTION_GAP;

        thumbnail.setPosition(thumbLeft, thumbTop);

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
