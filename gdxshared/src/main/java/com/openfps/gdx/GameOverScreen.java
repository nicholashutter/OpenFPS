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

    /**
     * The most of the surface height the heading may occupy.
     *
     * <p>The heading is sized from the <b>width</b>, which is right on a desktop
     * window and runaway on a landscape phone: 52% of 2400 px is a word 240 px
     * tall on a screen only 1080 px high, and everything below it inherits the
     * overflow. Capping here rather than lowering
     * {@link #TITLE_WIDTH_FRACTION} keeps the heading full-width on the shapes
     * where it already fitted.</p>
     */
    private static final float HEADING_MAX_HEIGHT_FRACTION = 0.22f;

    /** Clearance kept below the button, in pixels before scaling. */
    private static final float BOTTOM_MARGIN = 28.0f;

    /** Gap under the heading before the first summary line, in pixels. */
    private static final float SUMMARY_GAP = 40.0f;

    /** Gap between summary lines, in pixels. */
    private static final float LINE_GAP = 12.0f;

    /** Gap under the last summary line before the first button, in pixels. */
    private static final float BUTTON_GAP = 48.0f;

    /** Gap between the two buttons, in pixels. */
    private static final float BUTTON_SPACING = 16.0f;

    /** How many buttons this screen stacks. */
    private static final int BUTTON_COUNT = 2;

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

    /**
     * The rematch, and the first thing on the screen a player reaches for.
     *
     * <p>First in the stack and in the menu's primary-action green, because it is
     * what somebody who has just cleared a room wants. It exists at all only
     * because {@code Match.reset()} does — the end screen carried a single Back
     * button for as long as a rematch would have dropped the player into the room
     * they had already emptied.</p>
     */
    private final BlockButton playAgainButton;

    /** The other way off this screen. */
    private final BlockButton backButton;

    /** How far to magnify the fixed pixel metrics — 1 on desktop, density on a phone. */
    private final float uiScale;

    /**
     * Builds the screen at desktop metrics. Requires a live GL context.
     *
     * @param result the finished match to report; must not be null
     * @param onPlayAgain run when the player wants another round; must not be null
     * @param onBackToMenu run when the player leaves; must not be null
     */
    public GameOverScreen(final MatchSummary result, final Runnable onPlayAgain,
        final Runnable onBackToMenu)
    {
        this(result, onPlayAgain, onBackToMenu, 1.0f);
    }

    /**
     * Builds the screen. Requires a live GL context — construct from
     * {@code ApplicationListener.create()} or later, never earlier.
     *
     * @param result the finished match to report; must not be null
     * @param onPlayAgain run when the player wants another round; must not be
     *     null. <b>It must restore the world before it changes the UI state</b> —
     *     see {@code UiStateMachine.restartMatch}
     * @param onBackToMenu run when the player leaves; must not be null
     * @param scale multiplies every fixed pixel metric. 1 on a desktop monitor;
     *     a phone passes its density so a button stays the same physical size on
     *     a 160 dpi tablet and a 560 dpi handset. Must be positive
     * @throws IllegalArgumentException if anything is null or {@code scale} is
     *     not positive
     */
    public GameOverScreen(final MatchSummary result, final Runnable onPlayAgain,
        final Runnable onBackToMenu, final float scale)
    {
        if (result == null)
        {
            throw new IllegalArgumentException("result must not be null");
        }

        if (onPlayAgain == null)
        {
            throw new IllegalArgumentException("onPlayAgain must not be null");
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

        this.playAgainButton = new BlockButton("PLAY AGAIN", MenuPalette.PLAY_FACE,
            MenuPalette.PLAY_SHADE, pixel, font, BUTTON_FONT_SCALE * scale, onPlayAgain);

        this.backButton = new BlockButton("BACK TO MENU", MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE * scale, onBackToMenu);

        // Painter's order: backdrop, then heading, then the numbers, then the keys.
        stage.addActor(background);

        stage.addActor(heading);

        for (final Label line : summaryLines)
        {
            stage.addActor(line);
        }

        playAgainButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);

        stage.addActor(playAgainButton);

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
            // Second, directly under the kills, because the two are one figure:
            // seven for none and seven for nine are very different rounds, and a
            // death count buried at the bottom would be read as an afterthought.
            // It is a SCORE — a death respawns the player and ends nothing.
            "DEATHS   " + result.playerDeaths(),
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

    /** Returns the button that starts another round. */
    public BlockButton playAgainButton()
    {
        return playAgainButton;
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
     * <h2>The block is made to fit, and that is not cosmetic</h2>
     *
     * <p>Laid out naively at a phone's density this screen is about 1123 px tall
     * on a 1080 px surface, and what falls off the bottom is the <b>button</b> —
     * the only way off a screen that has already taken the input processor away
     * from everything else and un-caught the back key. The emulator showed it
     * exactly: DEFEAT, four tidy figures, and a sliver of yellow along the
     * bottom edge. That is not a layout blemish, it is a dead end.</p>
     *
     * <p>Two bounded corrections, in the order that costs the player least.
     * First the heading is capped by height as well as width — it is decoration
     * and it is the single largest item. Then the three gaps are shrunk
     * together by whatever fraction still does not fit. <b>Nothing that is
     * touched, sized or read is scaled</b>: the button keeps its full
     * pointer-sized box and the figures keep their font, because a screen that
     * fits by shrinking its own touch target has solved the wrong problem. The
     * same reasoning, and the same emulator, produced
     * {@code MainMenuFrameCallback.layoutDensity}.</p>
     *
     * @param width window width in pixels
     * @param height window height in pixels
     */
    public void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        // Packed FIRST, because the heading's own budget now depends on how tall
        // they turn out to be, and a label that has not been packed reports a size
        // it will not keep.
        for (final Label line : summaryLines)
        {
            line.pack();
        }

        final float buttonWidth = BUTTON_WIDTH * uiScale;

        final float buttonHeight = BUTTON_HEIGHT * uiScale;

        final float headingTop = height * (1.0f - TITLE_TOP_FRACTION);

        final float mustFit = summaryLinesHeight() + buttonHeight * BUTTON_COUNT
            + BOTTOM_MARGIN * uiScale;

        final float headingWidth = headingWidthFor(width, height, heading.widthInBlocks(),
            headingHeightBudget(height, mustFit));

        final float cell = heading.cellSizeFor(headingWidth);

        final float headingHeight = cell * BlockFont.GLYPH_HEIGHT;

        heading.setBounds((width - headingWidth) * 0.5f, headingTop - headingHeight,
            headingWidth, headingHeight);

        final float gapScale = gapScaleFor(headingTop - headingHeight, buttonHeight);

        float nextTop = headingTop - headingHeight - SUMMARY_GAP * uiScale * gapScale;

        for (final Label line : summaryLines)
        {
            line.setPosition((width - line.getWidth()) * 0.5f, nextTop - line.getHeight());

            nextTop = nextTop - line.getHeight() - LINE_GAP * uiScale * gapScale;
        }

        final float firstButtonTop = nextTop - BUTTON_GAP * uiScale * gapScale;

        playAgainButton.setSize(buttonWidth, buttonHeight);

        playAgainButton.setPosition((width - buttonWidth) * 0.5f,
            firstButtonTop - buttonHeight);

        // The spacing between the two buttons is scaled with the other gaps, but
        // NEITHER button's own box ever is — see layoutFor's Javadoc. A screen
        // that fits by shrinking its own touch targets has solved the wrong
        // problem, and one of these two is now the only way to play again.
        backButton.setSize(buttonWidth, buttonHeight);

        backButton.setPosition((width - buttonWidth) * 0.5f,
            firstButtonTop - buttonHeight * BUTTON_COUNT
                - BUTTON_SPACING * uiScale * gapScale);
    }

    /**
     * Returns the width to draw the heading at, capped so it cannot dominate a
     * short surface.
     *
     * <p>A width rather than a height because that is the only dimension
     * {@link BlockTitle} reads — it derives its cell size from
     * {@code getWidth()} and draws downward from the top of its bounds, so the
     * bounds height is a placement hint and never a limit. The cap is therefore
     * expressed by inverting {@code BlockTitle}'s own arithmetic rather than by
     * guessing a second constant that could drift from it.</p>
     *
     * <p>Static, and taking the block count rather than reading it off the
     * heading, so the rule can be asserted in a plain JVM. Everything else on
     * this screen needs a GL context to exist at all.</p>
     *
     * @param width the surface width in pixels
     * @param height the surface height in pixels
     * @param blocks the heading's width in {@link BlockFont} cells
     * @return the heading width in pixels, never more than
     *     {@link #TITLE_WIDTH_FRACTION} of the surface
     */
    public static float headingWidthFor(final float width, final float height, final int blocks)
    {
        return headingWidthFor(width, height, blocks, Float.MAX_VALUE);
    }

    /**
     * Returns the width to draw the heading at, capped by the surface and by
     * whatever vertical room the rest of the screen has left.
     *
     * <p><b>The third cap was added when the screen grew a fifth summary line and
     * a second button, and it is the same dead end returning.</b> Laid out at a
     * phone's density the stack is about 698 px of fixed content under a heading
     * top at 929 px on a 1080 px surface — six pixels over, with every gap already
     * collapsed to nothing. What falls off the bottom is the <b>BACK</b> button,
     * on a screen that has taken the input processor away from everything else.
     * The first time this happened the emulator showed DEFEAT, four tidy figures,
     * and a sliver of yellow along the bottom edge.</p>
     *
     * <p>So the correction is the one {@link #layoutFor} already documents,
     * extended: the heading is <b>decoration and the single largest item</b>, so it
     * yields first and it yields as far as it has to. Nothing that is touched,
     * sized or read is scaled — the buttons keep their full pointer-sized boxes and
     * the figures keep their font — because a screen that fits by shrinking its own
     * touch target has solved the wrong problem.</p>
     *
     * @param width the surface width in pixels
     * @param height the surface height in pixels
     * @param blocks the heading's width in {@link BlockFont} cells
     * @param heightBudget the most vertical room the heading may take, in pixels —
     *     see {@link #headingHeightBudget}. {@link Float#MAX_VALUE} means "no such
     *     limit", which is the three-argument form
     * @return the heading width in pixels
     */
    public static float headingWidthFor(final float width, final float height,
        final int blocks, final float heightBudget)
    {
        final float wanted = width * TITLE_WIDTH_FRACTION;

        if (blocks <= 0)
        {
            return wanted;
        }

        // BlockTitle derives its cell size from its WIDTH and draws downward, so
        // every height limit has to be expressed as a width by inverting that
        // arithmetic. Doing it here rather than guessing a second constant is what
        // keeps the two from drifting apart.
        final float allowedHeight =
            Math.min(height * HEADING_MAX_HEIGHT_FRACTION, heightBudget);

        final float capped = (allowedHeight / BlockFont.GLYPH_HEIGHT) * blocks;

        if (capped < wanted)
        {
            return capped;
        }

        return wanted;
    }

    /**
     * Returns how much vertical room is left for the heading once everything that
     * must be reachable has taken its share.
     *
     * <p>Static and public because it is the half of the fit rule that decides
     * whether the player has a way off this screen, and it is assertable without a
     * window — which the rest of {@link #layoutFor} is not. Never negative: a
     * heading of zero height is ugly and a button below the bottom edge is a dead
     * end, and only one of those two is recoverable.</p>
     *
     * @param surfaceHeight the surface height in pixels
     * @param contentHeight the height of everything below the heading that must
     *     stay on screen — the summary lines, both buttons, and the bottom margin
     * @return pixels the heading may occupy, zero or more
     */
    public static float headingHeightBudget(final float surfaceHeight,
        final float contentHeight)
    {
        final float budget = surfaceHeight * (1.0f - TITLE_TOP_FRACTION) - contentHeight;

        if (budget < 0.0f)
        {
            return 0.0f;
        }

        return budget;
    }

    /**
     * Returns the fraction of their natural size a block of gaps may take.
     *
     * <p>The whole fit rule, as arithmetic: 1 when everything already fits — so
     * no window that was correct becomes tighter — falling proportionally as the
     * surface runs out, and 0 rather than negative when there is nothing left to
     * give. At 0 the figures sit directly on the button, which is cramped and
     * still reachable; that is the trade this method exists to make, and it is
     * strictly better than a button below the bottom edge.</p>
     *
     * <p>Static and public because it is the part of this layout worth pinning
     * without a window, and the part whose failure is a screen with no way off
     * it.</p>
     *
     * @param available pixels left for the gaps once every fixed-size item has
     *     taken its share
     * @param natural the height the gaps want, in pixels
     * @return a factor in 0..1 to multiply every gap by
     */
    public static float gapFitFraction(final float available, final float natural)
    {
        if (!(natural > 0.0f))
        {
            return 1.0f;
        }

        if (available >= natural)
        {
            return 1.0f;
        }

        if (available <= 0.0f)
        {
            return 0.0f;
        }

        return available / natural;
    }

    // The gap factor for this screen's actual contents. Measures the packed
    // labels and hands the decision to gapFitFraction, which is where the rule
    // lives and where it is tested.
    private float gapScaleFor(final float spaceBelowHeading, final float buttonHeight)
    {
        // One gap under the heading, one after each line — the layout loop
        // subtracts LINE_GAP after the last line too — one above the first
        // button, and one between the two buttons.
        final float natural = (SUMMARY_GAP + BUTTON_GAP + BUTTON_SPACING
            + LINE_GAP * summaryLines.length) * uiScale;

        final float available = spaceBelowHeading - summaryLinesHeight()
            - buttonHeight * BUTTON_COUNT - BOTTOM_MARGIN * uiScale;

        return gapFitFraction(available, natural);
    }

    // The packed height of every summary line. Measured rather than computed,
    // because a Label's height is the font's business and depends on the scale it
    // was given.
    private float summaryLinesHeight()
    {
        float lines = 0.0f;

        for (final Label line : summaryLines)
        {
            lines = lines + line.getHeight();
        }

        return lines;
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
