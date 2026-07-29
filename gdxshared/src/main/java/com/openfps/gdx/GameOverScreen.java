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

import com.openfps.engine.gameplay.MatchSummary;

/**
 * The end of a round: VICTORY or DEFEAT, the numbers behind it, and the way out.
 *
 * <h2>Why this screen exists at all</h2>
 *
 * <p>Before it, a match simply stopped. {@code Match} decided the round on some
 * tic, {@code DemoGameplayPort} wrote one line to the log, the bots went still,
 * and the player was left standing in a room that had quietly finished without
 * telling them. Winning and dying looked identical from inside the window. This
 * is the screen that says which one happened.</p>
 *
 * <h2>The heading is coloured, and that carries the result</h2>
 *
 * <p>Green for a win, red for a loss, using the same two palette entries as the
 * Single Player and Quit buttons — so the colour language of the menu carries
 * over instead of a third scheme appearing at the most important moment. The
 * heading is pinned rather than cycling ({@link BlockTitle}'s fixed-colour
 * form): a drifting rainbow would carry VICTORY through red twice a pass, which
 * is precisely the wrong signal.</p>
 *
 * <h2>Built per result, not once and reused</h2>
 *
 * <p>The heading word and every summary figure are fixed at construction, so a
 * screen is built when a match ends and disposed when the player leaves it. That
 * is the cheap direction: a match ends once, and the alternative — one screen
 * holding two headings and six mutable labels, kept in step with whichever
 * result last arrived — is more state to get wrong for a screen that is on the
 * glass for a few seconds.</p>
 *
 * <p><b>No logic lives here.</b> The one button forwards to the {@link Runnable}
 * it was given, and every figure comes off an immutable {@link MatchSummary}.
 * The accuracy arithmetic is that class's, deliberately, so both platforms show
 * the same percentage.</p>
 *
 * <p><b>Threading:</b> constructed and used only on the platform render thread,
 * after the GL context exists.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class GameOverScreen
{
    /** The heading shown when the player cleared the room. */
    public static final String WIN_TEXT = "VICTORY";

    /** The heading shown when the player died. */
    public static final String LOSS_TEXT = "DEFEAT";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.52f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.14f;

    /** Gap under the heading before the first summary line, in pixels. */
    private static final float SUMMARY_GAP = 40.0f;

    /** Gap between summary lines, in pixels. */
    private static final float LINE_GAP = 12.0f;

    /** Gap under the last summary line before the button, in pixels. */
    private static final float BUTTON_GAP = 48.0f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 380.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 62.0f;

    /** Summary line font magnification over the 15px built-in font. */
    private static final float SUMMARY_FONT_SCALE = 1.35f;

    /** Button label font magnification. */
    private static final float BUTTON_FONT_SCALE = 1.6f;

    /** The result being shown. Never null. */
    private final MatchSummary summary;

    /** The Scene2D stage that owns the widget hierarchy and input. */
    private final Stage stage;

    /** The 1x1 white texture every rectangle is drawn from. */
    private final Texture white;

    /** The built-in font, used for the summary lines and the button label. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** VICTORY or DEFEAT, in block letters. */
    private final BlockTitle heading;

    /** The summary lines, top to bottom. */
    private final Label[] summaryLines;

    /** The one way off this screen. */
    private final BlockButton backButton;

    /** How far to magnify the fixed pixel metrics — 1 on desktop, density on a phone. */
    private final float uiScale;

    /**
     * Builds the screen at desktop metrics. Requires a live GL context.
     *
     * @param result the finished match to report; must not be null
     * @param onBackToMenu run when the player leaves; must not be null
     */
    public GameOverScreen(final MatchSummary result, final Runnable onBackToMenu)
    {
        this(result, onBackToMenu, 1.0f);
    }

    /**
     * Builds the screen. Requires a live GL context — construct from
     * {@code ApplicationListener.create()} or later, never earlier.
     *
     * @param result the finished match to report; must not be null
     * @param onBackToMenu run when the player leaves; must not be null
     * @param scale multiplies every fixed pixel metric. 1 on a desktop monitor;
     *     a phone passes its density so a button stays the same physical size on
     *     a 160 dpi tablet and a 560 dpi handset. Must be positive
     * @throws IllegalArgumentException if anything is null or {@code scale} is
     *     not positive
     */
    public GameOverScreen(final MatchSummary result, final Runnable onBackToMenu,
        final float scale)
    {
        if (result == null)
        {
            throw new IllegalArgumentException("result must not be null");
        }
        if (onBackToMenu == null)
        {
            throw new IllegalArgumentException("onBackToMenu must not be null");
        }
        if (!(scale > 0.0f))
        {
            throw new IllegalArgumentException("scale must be positive, got " + scale);
        }
        this.summary = result;
        this.uiScale = scale;
        this.white = whitePixelTexture();
        final TextureRegion pixel = new TextureRegion(white);
        this.font = new BitmapFont();
        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);
        this.heading = new BlockTitle(headingText(result), pixel, headingColour(result));

        final String[] text = summaryText(result);
        this.summaryLines = new Label[text.length];
        for (int index = 0; index < text.length; index++)
        {
            summaryLines[index] = label(text[index], MenuPalette.HINT,
                SUMMARY_FONT_SCALE * scale);
        }

        this.backButton = new BlockButton("BACK TO MENU", MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE * scale, onBackToMenu);

        // Painter's order: backdrop, then heading, then the numbers, then the key.
        stage.addActor(background);
        stage.addActor(heading);
        for (final Label line : summaryLines)
        {
            stage.addActor(line);
        }
        backButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);
        stage.addActor(backButton);
    }

    /**
     * Returns the heading a result should carry.
     *
     * <p>Static and public because it is the one thing on this screen worth
     * asserting headlessly: that a loss never renders as VICTORY. Everything
     * else here is placement, which needs a window to judge.</p>
     *
     * @param result the finished match; must not be null
     * @return {@link #WIN_TEXT} or {@link #LOSS_TEXT}
     */
    public static String headingText(final MatchSummary result)
    {
        if (result.isWin())
        {
            return WIN_TEXT;
        }
        return LOSS_TEXT;
    }

    /**
     * Returns the colour a result's heading should carry.
     *
     * @param result the finished match; must not be null
     * @return the menu's primary-action green for a win, its quit red for a loss
     */
    public static Color headingColour(final MatchSummary result)
    {
        if (result.isWin())
        {
            return MenuPalette.PLAY_FACE;
        }
        return MenuPalette.QUIT_FACE;
    }

    /**
     * Returns the summary lines for a result, top to bottom.
     *
     * <p>Static, public and returning plain strings so a headless test can
     * assert what the player is told — that the kill count matches the match,
     * that accuracy is reported rather than the raw hit count, that a player who
     * fired nothing is not shown a division by zero. None of that needs a
     * window and all of it is worth pinning.</p>
     *
     * @param result the finished match; must not be null
     * @return the lines to draw, never null and never empty
     */
    public static String[] summaryText(final MatchSummary result)
    {
        if (result == null)
        {
            throw new IllegalArgumentException("result must not be null");
        }
        return new String[]
        {
            "KILLS   " + result.botsKilled() + " of " + result.botCount(),
            "ACCURACY   " + result.accuracyPercent() + "%   ("
                + result.shotsHit() + " of " + result.shotsFired() + " shots)",
            "DAMAGE TAKEN   " + result.damageTaken(),
            "HEALTH LEFT   " + Math.max(0, result.playerHealth()),
        };
    }

    /** Returns the result this screen reports. Never null. */
    public MatchSummary summary()
    {
        return summary;
    }

    /** Returns the heading actor. */
    public BlockTitle heading()
    {
        return heading;
    }

    /** Returns the button that leaves this screen. */
    public BlockButton backButton()
    {
        return backButton;
    }

    /**
     * Places everything for a given window size.
     *
     * <p>Absolute placement rather than a {@code Table}, for the reason
     * {@link MainMenuScreen#layoutFor} records: the heading is a fraction of the
     * window width so it scales with the window, and the button is a fixed
     * pixel size so it stays pointer-sized whatever the window does.</p>
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

        float nextTop = headingTop - headingHeight - SUMMARY_GAP * uiScale;
        for (final Label line : summaryLines)
        {
            line.pack();
            line.setPosition((width - line.getWidth()) * 0.5f, nextTop - line.getHeight());
            nextTop = nextTop - line.getHeight() - LINE_GAP * uiScale;
        }

        final float buttonWidth = BUTTON_WIDTH * uiScale;
        final float buttonHeight = BUTTON_HEIGHT * uiScale;
        backButton.setSize(buttonWidth, buttonHeight);
        backButton.setPosition((width - buttonWidth) * 0.5f,
            nextTop - BUTTON_GAP * uiScale - buttonHeight);
    }

    /**
     * Clears the window and draws the result over it.
     *
     * <p>The clear belongs here for the reason {@link MainMenuScreen#render}
     * records: in {@link UiState#GAME_OVER} the world presenter is not called at
     * all, so nothing has covered the window when this runs.</p>
     *
     * @param deltaSeconds wall time since the previous frame, used only for the
     *     backdrop's drift — this never advances the simulation, which by
     *     definition has already stopped
     */
    public void render(final float deltaSeconds)
    {
        ScreenUtils.clear(MenuPalette.BACKDROP);
        stage.act(deltaSeconds);
        stage.draw();
    }

    /** Gives the Scene2D stage the input processor, so the button responds. */
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
