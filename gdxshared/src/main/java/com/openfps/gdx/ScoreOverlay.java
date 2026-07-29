/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import com.openfps.engine.gameplay.MatchStatus;

/**
 * The in-game score: kills, deaths, health, and the notice that says you have
 * just been killed.
 *
 * <h2>Why this exists, and why the death notice is the important half</h2>
 *
 * <p>The end-of-match screen was written because a match used to stop silently.
 * A <b>death</b> used to do exactly the same thing one level down, and worse:
 * with a respawn in place, being killed makes the view jump back to the spawn
 * point with no explanation whatsoever. That is indistinguishable from a
 * teleport bug, and it was reported as one. Nothing in the world tells the player
 * what happened — the log line does, and nobody plays a game with a console
 * open.</p>
 *
 * <p>So this draws two things. A quiet corner panel with the running score,
 * which answers "how am I doing"; and, while
 * {@link MatchStatus#isPlayerDown()}, a large centred notice with a
 * <b>counting-down</b> number, which answers "what just happened and when do I
 * get to move again". The countdown is what distinguishes it from a hang.</p>
 *
 * <h2>Drawn from the same white pixel as everything else</h2>
 *
 * <p>{@link BlockFont} cells, exactly like {@link DebugOverlay} and
 * {@link BlockTitle} — no font file, no atlas, no asset. Not only for
 * consistency: a bitmap font scaled up over a game view is a blurry smear, and
 * the one line a dying player has to read at a glance is the worst possible
 * place for that. Block cells are solid rectangles and stay sharp at any
 * size.</p>
 *
 * <h2>The strings are rebuilt only when a figure moves</h2>
 *
 * <p>The same caching rule {@link DebugOverlay} documents, and it matters more
 * here: a kill count changes seven times in a round, so a steady frame allocates
 * nothing at all. The respawn line is the exception and is rebuilt once a second
 * rather than once a frame, because it is cached on the whole seconds it
 * displays.</p>
 *
 * <p><b>Threading:</b> constructed and drawn on the platform render thread only,
 * after the GL context exists.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class ScoreOverlay
{
    /** Size of one {@link BlockFont} cell in the score panel, in pixels. */
    public static final float CELL_PIXELS = 4.0f;

    /**
     * Size of one cell in the death notice, in pixels — three times the panel's.
     *
     * <p>Big enough to be read without looking for it. The notice appears when
     * the player has just lost control of the camera, which is the moment they
     * are least likely to be scanning a corner of the screen.</p>
     *
     * <p>Eight rather than twelve, which is where it started: at twelve
     * "ELIMINATED!" spanned 792 px of a 1280 px window — 62% of the width — and
     * the band under it swallowed a third of the room. Judged from a capture
     * rather than from the arithmetic, which is the only way to judge it: the
     * word has to dominate the frame without replacing it.</p>
     */
    public static final float NOTICE_CELL_PIXELS = 8.0f;

    /** Fraction of a cell left empty, so the digits read as blocks. */
    public static final float CELL_GAP_FRACTION = 0.15f;

    /** Gap between one line and the next, in cells. */
    public static final float LINE_SPACING_CELLS = 2.0f;

    /** How far the panel sits from the top-right corner, in pixels. */
    public static final float MARGIN_PIXELS = 14.0f;

    /** Padding between the panel edge and the text, in pixels. */
    public static final float PADDING_PIXELS = 10.0f;

    /** Health at or below which the health line turns red. */
    public static final int LOW_HEALTH = 30;

    /** How many lines the score panel shows. */
    private static final int LINE_COUNT = 3;

    /** Index of the kills line. */
    private static final int LINE_KILLS = 0;

    /** Index of the deaths line. */
    private static final int LINE_DEATHS = 1;

    /** Index of the health line. */
    private static final int LINE_HEALTH = 2;

    /** Where the notice sits, as a fraction of the surface height from the bottom. */
    private static final float NOTICE_HEIGHT_FRACTION = 0.62f;

    /** The backdrop the score sits on, so it reads over any part of the world. */
    private static final Color PANEL = new Color(0.03f, 0.04f, 0.08f, 0.72f);

    /** The backdrop behind the death notice — darker, because it is an interruption. */
    private static final Color NOTICE_PANEL = new Color(0.10f, 0.02f, 0.03f, 0.82f);

    /** Colour of the kill count. The menu's primary-action green. */
    private static final Color KILLS_COLOUR = new Color(0.55f, 0.84f, 0.34f, 1.0f);

    /** Colour of the death count and of healthy health. */
    private static final Color QUIET_COLOUR = new Color(0.62f, 0.68f, 0.82f, 1.0f);

    /** Colour of a health figure that is nearly gone, and of the death notice. */
    private static final Color ALARM_COLOUR = new Color(0.95f, 0.36f, 0.34f, 1.0f);

    /** Sentinel meaning "no value has been formatted into this line yet". */
    private static final int NO_READING = Integer.MIN_VALUE;

    /** The heading the death notice shows. */
    private static final String NOTICE_TEXT = "ELIMINATED!";

    /** The countdown line under it, with the seconds appended. */
    private static final String NOTICE_COUNTDOWN = "RESPAWN IN ";

    /** Draws the panel and every cell. MUTABLE: built on first use, freed by dispose. */
    private SpriteBatch batch;

    /** The 1x1 white texture. MUTABLE: built on first use, freed by dispose. */
    private Texture white;

    /** The region every rectangle is drawn from. MUTABLE: built with the texture. */
    private TextureRegion pixel;

    /** The score lines, already formatted. MUTABLE: rebuilt when a figure moves. */
    private final String[] lines = new String[LINE_COUNT];

    /** The countdown line, already formatted. MUTABLE: rebuilt once a second. */
    private String countdown = NOTICE_COUNTDOWN + "0";

    /** Kills last rendered into {@link #lines}. MUTABLE. */
    private int shownKills = NO_READING;

    /** Bot count last rendered. MUTABLE: changes once a process, and is cached anyway. */
    private int shownBotCount = NO_READING;

    /** Deaths last rendered. MUTABLE. */
    private int shownDeaths = NO_READING;

    /** Health last rendered. MUTABLE. */
    private int shownHealth = NO_READING;

    /** Respawn seconds last rendered. MUTABLE. */
    private int shownSeconds = NO_READING;

    /** Creates an overlay. Builds no GL resource until it is first drawn. */
    public ScoreOverlay()
    {
        // GPU resources are deferred to the first render() for the reason
        // DebugOverlay defers its: there is no GL context until the surface
        // exists, and a window with no match should cost nothing at all.
    }

    /**
     * Returns the score lines a status would be drawn as, top to bottom.
     *
     * <p>Static, public and returning plain strings so a headless test can assert
     * <b>what the player is told</b> rather than that a draw call happened. That
     * distinction has cost this project real bugs: an effect once shipped
     * "working" while being ten colour levels from its background, because the
     * test asserted it was drawn rather than that it could be seen. The strings
     * are the part of this class a plain JVM can reach; the rest needs a
     * window.</p>
     *
     * @param status the live figures; must not be null
     * @return the three lines to draw, never null
     * @throws IllegalArgumentException if {@code status} is null
     */
    public static String[] scoreText(final MatchStatus status)
    {
        if (status == null)
        {
            throw new IllegalArgumentException("status must not be null");
        }
        return new String[]
        {
            "KILLS " + status.botsKilled() + "/" + status.botCount(),
            "DEATHS " + status.playerDeaths(),
            "HEALTH " + Math.max(0, status.playerHealth()),
        };
    }

    /**
     * Returns the countdown line the death notice shows.
     *
     * <p>Static and public for the reason {@link #scoreText} is, and this is the
     * one that had to be pinned: the whole point of the notice is that the number
     * <b>changes</b>, because a static "you died" is indistinguishable from a
     * hung game. A test can watch it count down without a display.</p>
     *
     * @param status the live figures; must not be null
     * @param ticsPerSecond the simulation rate, for turning tics into seconds
     * @return the line to draw under {@link #NOTICE_TEXT}
     * @throws IllegalArgumentException if {@code status} is null
     */
    public static String countdownText(final MatchStatus status, final int ticsPerSecond)
    {
        if (status == null)
        {
            throw new IllegalArgumentException("status must not be null");
        }
        return NOTICE_COUNTDOWN + status.respawnSecondsRemaining(ticsPerSecond);
    }

    /**
     * Draws the score in the top-right corner, and the death notice over the
     * middle when the player is down.
     *
     * <p>A no-op with a null status, which is what a window with no match — the
     * {@code --model=} path — supplies every frame.</p>
     *
     * @param status the live figures, or null for a window with no match
     * @param surfaceWidth the surface width in pixels; must be positive to draw
     * @param surfaceHeight the surface height in pixels; must be positive to draw
     * @param ticsPerSecond the simulation rate, for the respawn countdown
     */
    public void render(final MatchStatus status, final int surfaceWidth,
        final int surfaceHeight, final int ticsPerSecond)
    {
        if (status == null || surfaceWidth <= 0 || surfaceHeight <= 0)
        {
            return;
        }
        ensureResources();
        refreshLines(status, ticsPerSecond);

        batch.getProjectionMatrix().setToOrtho2D(0.0f, 0.0f, surfaceWidth, surfaceHeight);
        batch.begin();
        drawScorePanel(status, surfaceWidth, surfaceHeight);
        if (status.isPlayerDown())
        {
            drawDeathNotice(surfaceWidth, surfaceHeight);
        }
        batch.end();
    }

    // The quiet corner panel. Right-aligned, because the left is where
    // DebugOverlay lives and two panels fighting over one corner is worse than
    // either of them.
    private void drawScorePanel(final MatchStatus status, final int surfaceWidth,
        final int surfaceHeight)
    {
        final float cellGap = CELL_PIXELS * CELL_GAP_FRACTION;
        final float lineHeight = (BlockFont.GLYPH_HEIGHT + LINE_SPACING_CELLS) * CELL_PIXELS;
        final float textWidth = widestLinePixels();
        final float panelWidth = textWidth + PADDING_PIXELS * 2.0f;
        final float panelHeight = lineHeight * LINE_COUNT - LINE_SPACING_CELLS * CELL_PIXELS
            + PADDING_PIXELS * 2.0f;
        final float panelLeft = surfaceWidth - MARGIN_PIXELS - panelWidth;
        final float panelTop = surfaceHeight - MARGIN_PIXELS;

        batch.setColor(PANEL);
        batch.draw(pixel, panelLeft, panelTop - panelHeight, panelWidth, panelHeight);

        float lineTop = panelTop - PADDING_PIXELS;
        for (int index = 0; index < LINE_COUNT; index++)
        {
            batch.setColor(colourFor(index, status));
            drawLine(lines[index], panelLeft + PADDING_PIXELS, lineTop, CELL_PIXELS, cellGap);
            lineTop = lineTop - lineHeight;
        }
    }

    // The interruption: two centred lines over a dark band, only while the player
    // is on the floor.
    private void drawDeathNotice(final int surfaceWidth, final int surfaceHeight)
    {
        final float cell = NOTICE_CELL_PIXELS;
        final float cellGap = cell * CELL_GAP_FRACTION;
        final float lineHeight = (BlockFont.GLYPH_HEIGHT + LINE_SPACING_CELLS) * cell;
        final float headingWidth = BlockFont.widthInBlocks(NOTICE_TEXT) * cell;
        final float countdownWidth = BlockFont.widthInBlocks(countdown) * cell;
        final float bandHeight = lineHeight * 2.0f - LINE_SPACING_CELLS * cell
            + PADDING_PIXELS * 4.0f;
        final float bandTop = surfaceHeight * NOTICE_HEIGHT_FRACTION + bandHeight * 0.5f;

        // The band spans the full width rather than boxing the text. A centred
        // box reads as a dialogue the player is expected to dismiss; a band reads
        // as a state they are in, which is what this is.
        batch.setColor(NOTICE_PANEL);
        batch.draw(pixel, 0.0f, bandTop - bandHeight, surfaceWidth, bandHeight);

        final float headingTop = bandTop - PADDING_PIXELS * 2.0f;
        batch.setColor(ALARM_COLOUR);
        drawLine(NOTICE_TEXT, (surfaceWidth - headingWidth) * 0.5f, headingTop, cell, cellGap);
        batch.setColor(QUIET_COLOUR);
        drawLine(countdown, (surfaceWidth - countdownWidth) * 0.5f, headingTop - lineHeight,
            cell, cellGap);
    }

    // The kill count is picked out; health turns red when it is nearly gone.
    private static Color colourFor(final int lineIndex, final MatchStatus status)
    {
        if (lineIndex == LINE_KILLS)
        {
            return KILLS_COLOUR;
        }
        if (lineIndex == LINE_HEALTH && status.playerHealth() <= LOW_HEALTH)
        {
            // The one piece of colour in this panel that carries information
            // rather than hierarchy. A number going down is easy to miss; a
            // number going down and turning red is not.
            return ALARM_COLOUR;
        }
        return QUIET_COLOUR;
    }

    // Draws one string of block cells, its top-left cell at (left, top).
    //
    // The lambda body is a single call to a named method rather than the draw
    // itself: STYLE.md § 6.2 caps lambdas at one operation, and the cell maths
    // needs four captured values.
    private void drawLine(final String text, final float left, final float top,
        final float cell, final float gap)
    {
        final float size = cell - gap;
        BlockFont.forEachBlock(text, (column, row, glyph) ->
            batch.draw(pixel, left + column * cell, top - (row + 1) * cell, size, size));
    }

    // The width of the longest score line, in pixels, so the panel fits the text.
    private float widestLinePixels()
    {
        int widest = 0;
        for (int index = 0; index < LINE_COUNT; index++)
        {
            final int cells = BlockFont.widthInBlocks(lines[index]);
            if (cells > widest)
            {
                widest = cells;
            }
        }
        return widest * CELL_PIXELS;
    }

    // Re-formats only the lines whose displayed figure has actually moved. See
    // the class Javadoc on why that is worth a handful of fields.
    private void refreshLines(final MatchStatus status, final int ticsPerSecond)
    {
        if (status.botsKilled() != shownKills || status.botCount() != shownBotCount)
        {
            shownKills = status.botsKilled();
            shownBotCount = status.botCount();
            lines[LINE_KILLS] = scoreText(status)[LINE_KILLS];
        }
        if (status.playerDeaths() != shownDeaths)
        {
            shownDeaths = status.playerDeaths();
            lines[LINE_DEATHS] = scoreText(status)[LINE_DEATHS];
        }
        if (status.playerHealth() != shownHealth)
        {
            shownHealth = status.playerHealth();
            lines[LINE_HEALTH] = scoreText(status)[LINE_HEALTH];
        }
        final int seconds = status.respawnSecondsRemaining(ticsPerSecond);
        if (seconds != shownSeconds)
        {
            shownSeconds = seconds;
            countdown = countdownText(status, ticsPerSecond);
        }
    }

    // Builds the batch and the white pixel, once, on the render thread.
    private void ensureResources()
    {
        if (batch != null)
        {
            return;
        }
        batch = new SpriteBatch();
        final Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        white = new Texture(pixmap);
        pixmap.dispose();
        pixel = new TextureRegion(white);
    }

    /** Releases the batch and texture. Safe to call when nothing was ever built. */
    public void dispose()
    {
        if (batch != null)
        {
            batch.dispose();
            batch = null;
        }
        if (white != null)
        {
            white.dispose();
            white = null;
        }
        pixel = null;
    }

    /** Returns a debug rendering of what the panel last showed. */
    @Override
    public String toString()
    {
        return "ScoreOverlay{kills=" + shownKills + "/" + shownBotCount + ", deaths="
            + shownDeaths + ", health=" + shownHealth + "}";
    }
}
