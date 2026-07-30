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
 * <p>So this draws three things. A quiet corner panel with the running score,
 * which answers "how am I doing"; while {@link MatchStatus#isPlayerDown()}, a
 * large centred notice with a <b>counting-down</b> number, which answers "what
 * just happened and when do I get to move again"; and while
 * {@link MatchStatus#isSuperBlaster()}, a centred plaque that answers "why is my
 * gun suddenly killing things in two shots". The countdown is what distinguishes
 * each of the last two from a hang and from a badge somebody forgot to take
 * away.</p>
 *
 * <h2>The reward needs two different sizes of feedback, not one</h2>
 *
 * <p>A buff nobody can see is indistinguishable from no buff, and it fails in two
 * separate ways that need answering separately.</p>
 *
 * <ul>
 *   <li><b>Arriving unexplained.</b> A gun that changes on the third kill with
 *       nothing counting up to it is a mystery. So the corner panel carries a
 *       <b>STREAK n/3</b> line at all times — the cheapest line on the glass and
 *       the one that teaches the rule.</li>
 *   <li><b>Being live without being noticed.</b> Four seconds is short and the
 *       player is looking at the room, not at a corner, so the live state gets the
 *       middle of the screen.</li>
 * </ul>
 *
 * <p>The plaque is a <b>centred box and not a full-width band</b>, which is the
 * one place it deliberately departs from the death notice above it. A band is
 * right for a death — the player has lost control and the interruption <i>is</i>
 * the message — and wrong for a reward, because the player is still playing and
 * still has to be able to see what they are shooting at. It is also why the box
 * sits above the crosshair rather than over it, and why it is narrower than the
 * distance to the score panel: nothing this class draws may cover something else
 * this class draws.</p>
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

    /**
     * Size of one cell in the super-blaster plaque, in pixels — six.
     *
     * <p>Between the panel's 4 and the death notice's 8, and the ordering is the
     * message: a death stops the round, the reward punctuates it, the score is
     * reference. Six also keeps "SUPER BLASTER" at 462 px on a 1280 px window —
     * 36% of the width, wide enough to be read without looking for it and narrow
     * enough that the room either side of it is still the room.</p>
     */
    public static final float SUPER_CELL_PIXELS = 6.0f;

    /** Health at or below which the health line turns red. */
    public static final int LOW_HEALTH = 30;

    /** How many lines the score panel shows. */
    private static final int LINE_COUNT = 4;

    /** Index of the kills line. */
    private static final int LINE_KILLS = 0;

    /** Index of the deaths line. */
    private static final int LINE_DEATHS = 1;

    /** Index of the health line. */
    private static final int LINE_HEALTH = 2;

    /**
     * Index of the streak line — appended <b>last</b>, deliberately.
     *
     * <p>Reading order would put it under KILLS, and the cost of that is every
     * index below it moving. {@code ScoreOverlayTextTest} addresses these lines
     * positionally, so a reshuffle would rewrite assertions that are about health
     * and deaths and have nothing to say about a streak — churn that hides the one
     * line actually being added.</p>
     */
    private static final int LINE_STREAK = 3;

    /** Where the notice sits, as a fraction of the surface height from the bottom. */
    private static final float NOTICE_HEIGHT_FRACTION = 0.62f;

    /**
     * Where the super-blaster plaque sits, as a fraction of the surface height.
     *
     * <p>0.66 — above the crosshair at 0.5 rather than over it. A reward that
     * covered the thing the player is aiming at would be self-defeating, and this
     * is the only element on the glass that appears while the player is in full
     * control.</p>
     */
    private static final float SUPER_HEIGHT_FRACTION = 0.66f;

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

    /**
     * Colour of the super-blaster plaque and of the streak line while it is live.
     *
     * <p>Amber, and it is the third colour in this class that carries information
     * rather than hierarchy — distinct from the kill count's green, the panel's
     * blue-grey and the alarm's red, so "which of these is telling me something
     * new" is answerable without reading any of them.</p>
     */
    private static final Color SUPER_COLOUR = new Color(1.0f, 0.72f, 0.18f, 1.0f);

    /** The backdrop behind the plaque — warm and dark, so the amber reads over any room. */
    private static final Color SUPER_PANEL = new Color(0.14f, 0.07f, 0.01f, 0.80f);

    /** Sentinel meaning "no value has been formatted into this line yet". */
    private static final int NO_READING = Integer.MIN_VALUE;

    /** The heading the death notice shows. */
    private static final String NOTICE_TEXT = "ELIMINATED!";

    /** The countdown line under it, with the seconds appended. */
    private static final String NOTICE_COUNTDOWN = "RESPAWN IN ";

    /** The heading the super-blaster plaque shows. */
    private static final String SUPER_TEXT = "SUPER BLASTER";

    /** The line under it, with the seconds appended and {@link #SUPER_SUFFIX} after. */
    private static final String SUPER_COUNTDOWN = "X2 DAMAGE ";

    /** Trails the plaque's countdown, so "4" reads as a duration. */
    private static final String SUPER_SUFFIX = "S";

    /** What the streak line says instead of a count while the reward is live. */
    private static final String SUPER_STREAK_LINE = "SUPER X2";

    /** Label on the streak line while the reward is being earned. */
    private static final String STREAK_LABEL = "STREAK ";

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

    /** The plaque's countdown line, already formatted. MUTABLE: rebuilt once a second. */
    private String superCountdown = SUPER_COUNTDOWN + "0" + SUPER_SUFFIX;

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

    /** Kill streak last rendered. MUTABLE. */
    private int shownStreak = NO_READING;

    /** Super-blaster seconds last rendered. MUTABLE. */
    private int shownSuperSeconds = NO_READING;

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
     * @return the four lines to draw, never null
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
            streakText(status),
        };
    }

    /**
     * Returns the streak line: progress toward the reward, or the reward itself.
     *
     * <p>Two strings on one line rather than two lines, because the panel is
     * reference material and the pair are never both true. While the reward is live
     * the count would read <b>0/3</b> — the award spends the streak that earned it
     * — and a zero beside a plaque announcing double damage is not a progress
     * indicator, it is a contradiction.</p>
     *
     * @param status the live figures; must not be null
     * @return the line to draw
     * @throws IllegalArgumentException if {@code status} is null
     */
    public static String streakText(final MatchStatus status)
    {
        if (status == null)
        {
            throw new IllegalArgumentException("status must not be null");
        }
        if (status.isSuperBlaster())
        {
            return SUPER_STREAK_LINE;
        }
        return STREAK_LABEL + status.killStreak() + "/" + status.killStreakTarget();
    }

    /**
     * Returns the countdown line the super-blaster plaque shows.
     *
     * <p>Static and public for the reason {@link #countdownText} is, and it is the
     * same property that matters: the number has to <b>move</b>. A badge that sat
     * there unchanging for four seconds would be indistinguishable from one the
     * game had forgotten to take away, and the whole point of a timed reward is
     * that the player can decide what to spend it on.</p>
     *
     * @param status the live figures; must not be null
     * @param ticsPerSecond the simulation rate, for turning tics into seconds
     * @return the line to draw under {@link #SUPER_TEXT}
     * @throws IllegalArgumentException if {@code status} is null
     */
    public static String superCountdownText(final MatchStatus status, final int ticsPerSecond)
    {
        if (status == null)
        {
            throw new IllegalArgumentException("status must not be null");
        }
        return SUPER_COUNTDOWN + status.superBlasterSecondsRemaining(ticsPerSecond)
            + SUPER_SUFFIX;
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
        // No else. The two cannot both be true — Match cancels the reward on the
        // death that would show the notice — and writing that as an else would
        // hide a broken cancellation behind a layout rule instead of showing it as
        // two overlapping panels, which is what a bug should look like.
        if (status.isSuperBlaster())
        {
            drawSuperNotice(surfaceWidth, surfaceHeight);
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

    // The reward, as a compact plaque over the upper middle of the view.
    //
    // A BOX and not a band: see the class Javadoc. Sized to its own text, so it is
    // roughly a third of the width and cannot reach the score panel in the corner
    // — the two are the only things this class draws simultaneously.
    private void drawSuperNotice(final int surfaceWidth, final int surfaceHeight)
    {
        final float cell = SUPER_CELL_PIXELS;
        final float cellGap = cell * CELL_GAP_FRACTION;
        final float lineHeight = (BlockFont.GLYPH_HEIGHT + LINE_SPACING_CELLS) * cell;
        final float headingWidth = BlockFont.widthInBlocks(SUPER_TEXT) * cell;
        final float countdownWidth = BlockFont.widthInBlocks(superCountdown) * cell;
        final float boxWidth = Math.max(headingWidth, countdownWidth) + PADDING_PIXELS * 4.0f;
        final float boxHeight = lineHeight * 2.0f - LINE_SPACING_CELLS * cell
            + PADDING_PIXELS * 3.0f;
        final float boxTop = surfaceHeight * SUPER_HEIGHT_FRACTION + boxHeight * 0.5f;

        batch.setColor(SUPER_PANEL);
        batch.draw(pixel, (surfaceWidth - boxWidth) * 0.5f, boxTop - boxHeight,
            boxWidth, boxHeight);

        final float headingTop = boxTop - PADDING_PIXELS * 1.5f;
        batch.setColor(SUPER_COLOUR);
        drawLine(SUPER_TEXT, (surfaceWidth - headingWidth) * 0.5f, headingTop, cell, cellGap);
        // The countdown in the quiet colour rather than in amber: the heading is
        // what has to be seen, and two lines in one bright colour would make the
        // plaque a block rather than a sentence with a number in it.
        batch.setColor(QUIET_COLOUR);
        drawLine(superCountdown, (surfaceWidth - countdownWidth) * 0.5f,
            headingTop - lineHeight, cell, cellGap);
    }

    // The kill count is picked out; health turns red when it is nearly gone.
    private static Color colourFor(final int lineIndex, final MatchStatus status)
    {
        if (lineIndex == LINE_KILLS)
        {
            return KILLS_COLOUR;
        }
        if (lineIndex == LINE_STREAK && status.isSuperBlaster())
        {
            // The second piece of colour that carries information: the panel's own
            // line changes with the state, so the corner and the middle of the
            // screen cannot disagree about whether the reward is live.
            return SUPER_COLOUR;
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
        // Keyed on the streak AND on the reward's seconds, because the line's text
        // depends on both: it swaps from a count to a label the moment the reward
        // starts, and back when it ends. Keyed on the streak alone it would still
        // read "STREAK 0/3" through a whole live buff, since the award leaves the
        // count where a cache would find it unchanged.
        final int superSeconds = status.superBlasterSecondsRemaining(ticsPerSecond);
        if (status.killStreak() != shownStreak || superSeconds != shownSuperSeconds)
        {
            shownStreak = status.killStreak();
            lines[LINE_STREAK] = streakText(status);
        }
        if (superSeconds != shownSuperSeconds)
        {
            shownSuperSeconds = superSeconds;
            superCountdown = superCountdownText(status, ticsPerSecond);
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
            + shownDeaths + ", health=" + shownHealth + ", streak=" + shownStreak
            + ", superFor=" + shownSuperSeconds + "s}";
    }
}
