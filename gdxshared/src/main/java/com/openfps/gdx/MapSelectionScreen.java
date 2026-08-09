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

import java.util.List;

/**
 * The map picker: a list of the registered maps, the current selection
 * marked, and the way back to the menu.
 *
 * <h2>What the screen is, and is not</h2>
 *
 * <p>The screen owns the choice the launcher will use on the next boot.
 * The currently-running engine is unaffected: switching maps in the same
 * process would mean rebuilding the engine's world, the {@code Match}
 * inside it, and the Scene2D actors that hold it together — and that is
 * a follow-up, not in this first pass. The picker is honest about that:
 * it stores the selection in {@link MapSelection} and the launcher reads
 * it next time {@code DesktopLauncher.main} runs.</p>
 *
 * <p><b>The list is the launcher's, not ours.</b> The screen does not import
 * {@code com.openfps.engine.gameplay.map.MapLibrary} — the platform-adapter
 * rule {@code STYLE.md} § 1.1 lays down is non-negotiable, and
 * {@code MapLibrary} is engine code. The launcher hands the screen a
 * {@code List<Entry>} of {@code (id, displayName)} pairs and a callback
 * for the click, and the screen stays a pure view.</p>
 *
 * <h2>The selected one is marked, the others are buttons</h2>
 *
 * <p>The current selection gets a {@code ">"} prefix and the
 * {@link MenuPalette#PLAY_FACE} colour so it reads as "the one you have";
 * every other entry is a {@link BlockButton} in the neutral face colour
 * that activates the same callback. Clicking the same entry twice is a
 * no-op; clicking a different entry stores the new id in
 * {@link MapSelection} and the next time the screen redraws, the prefix
 * has moved. The visual change is the confirmation; there is no "PICK"
 * button because the click itself is the pick.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Constructed and used only on the platform render thread, after the
 * GL context exists.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class MapSelectionScreen
{
    /**
     * One row in the picker: the engine's stable id and the human-readable
     * name the launcher hands the screen.
     *
     * <p>{@code id} is the value the launcher writes to the engine; the
     * display name is what the player reads. Keeping them in one record
     * means a screen that takes a {@code List<Entry>} cannot accidentally
     * sort by display name and lose the id correspondence.</p>
     */
    public static final class Entry
    {
        /** The engine's stable id. Never null or blank. */
        private final String id;

        /** The human-readable name. Never null or blank. */
        private final String displayName;

        /**
         * Creates a row.
         *
         * @param id the engine's id; must be non-blank
         * @param displayName the human-readable name; must be non-blank
         * @throws IllegalArgumentException if either is null or blank
         */
        public Entry(final String id, final String displayName)
        {
            if (id == null || id.isBlank())
            {
                throw new IllegalArgumentException("id must not be blank");
            }

            if (displayName == null || displayName.isBlank())
            {
                throw new IllegalArgumentException("displayName must not be blank");
            }

            this.id = id;

            this.displayName = displayName;
        }

        /** Returns the engine's stable id. */
        public String id()
        {
            return id;
        }

        /** Returns the human-readable name. */
        public String displayName()
        {
            return displayName;
        }
    }

    /** The word the heading spells. */
    public static final String TITLE_TEXT = "SELECT MAP";

    /** The line of help under the heading. */
    public static final String SUBTITLE_TEXT = "Click a map to pick it; BACK returns to the menu";

    /**
     * The marker prefix on the currently selected map.
     *
     * <p>A ">" rather than a "selected" word so the line stays the same
     * shape as the other rows — the label, a fixed gap, then the name —
     * and the prefix is the only thing the player needs to read.</p>
     */
    public static final String SELECTED_PREFIX = "> ";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.48f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.10f;

    /** Gap between the heading and the subtitle. */
    private static final float SUBTITLE_GAP = 18.0f;

    /** Gap between the subtitle and the first map row. */
    private static final float LIST_GAP = 28.0f;

    /** Gap between map rows. */
    private static final float ROW_GAP = 10.0f;

    /** Clear space left under the Back key. */
    private static final float BOTTOM_MARGIN = 28.0f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 540.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 50.0f;

    /** Label font magnification. */
    private static final float BUTTON_FONT_SCALE = 1.35f;

    /** Subtitle font magnification. */
    private static final float SUBTITLE_FONT_SCALE = 1.0f;

    /** How many map rows. */
    private final int rowCount;

    /** The shared selection; the screen reads and writes this. */
    private final MapSelection selection;

    /** The rows, in the order the launcher handed them in. */
    private final List<Entry> entries;

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

    /** The per-row buttons, in the same order as {@link #entries}. */
    private final BlockButton[] rowButtons;

    /** The way back to the menu. */
    private final BlockButton backButton;

    /**
     * Builds the picker.
     *
     * <p>Requires a live GL context — construct from
     * {@code ApplicationListener.create()} or later, never earlier.</p>
     *
     * @param selection the shared selection; the screen writes here on click
     * @param entries the rows to show, in display order; must not be null
     *     and must contain at least one entry
     * @param onBack run when the player clicks BACK; must not be null
     * @throws IllegalArgumentException if any argument is null, the entries
     *     list is empty, or the entries are otherwise unusable
     */
    public MapSelectionScreen(final MapSelection selection, final List<Entry> entries,
        final Runnable onBack)
    {
        if (selection == null)
        {
            throw new IllegalArgumentException("selection must not be null");
        }

        if (entries == null || entries.isEmpty())
        {
            throw new IllegalArgumentException("entries must not be null or empty");
        }

        if (onBack == null)
        {
            throw new IllegalArgumentException("onBack must not be null");
        }

        this.selection = selection;

        this.entries = List.copyOf(entries);

        this.rowCount = this.entries.size();

        this.white = whitePixelTexture();

        final TextureRegion pixel = new TextureRegion(white);

        this.font = new BitmapFont();

        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);

        this.heading = new BlockTitle(TITLE_TEXT, pixel, MenuPalette.NEUTRAL_FACE);

        this.subtitle = label(SUBTITLE_TEXT, MenuPalette.HINT, SUBTITLE_FONT_SCALE);

        // One button per row; the current selection is given a different face
        // colour by the renderer so the player can see which one they have
        // before clicking. The label includes the SELECTED_PREFIX on the
        // current row, refreshed every click.
        this.rowButtons = new BlockButton[rowCount];

        for (int row = 0; row < rowCount; row++)
        {
            final Entry entry = this.entries.get(row);

            final int captured = row;

            this.rowButtons[row] = new BlockButton(rowLabel(entry.id(), selection),
                rowColor(entry.id(), selection), rowShade(entry.id(), selection),
                pixel, font, BUTTON_FONT_SCALE, () -> pickRow(captured));
        }

        this.backButton = new BlockButton("BACK", MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE, onBack);

        stage.addActor(background);

        stage.addActor(heading);

        stage.addActor(subtitle);

        for (final BlockButton button : rowButtons)
        {
            button.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

            stage.addActor(button);
        }

        backButton.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

        stage.addActor(backButton);
    }

    /** Returns the rows the screen was built with, in the same order. */
    public List<Entry> entries()
    {
        return entries;
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
     * Clears the window and draws the picker.
     *
     * @param deltaSeconds wall time since the previous frame; used for the
     *     backdrop's drift and the heading's colour cycle only
     */
    public void render(final float deltaSeconds)
    {
        ScreenUtils.clear(MenuPalette.BACKDROP);

        stage.act(deltaSeconds);

        stage.draw();
    }

    /**
     * Gives the Scene2D stage the input processor, so the buttons respond.
     */
    public void attachInputProcessor()
    {
        if (Gdx.input == null)
        {
            return;
        }

        Gdx.input.setInputProcessor(stage);
    }

    /**
     * Takes the input processor away, so the picker consumes nothing.
     *
     * <p>Called on leaving {@link UiState#MAP_SELECT}. This is the difference
     * between a picker that is hidden and a picker that is genuinely gone:
     * without it the stage still hit-tests every mouse position and a click
     * aimed at the next screen would land on whichever invisible row was
     * under the cursor.</p>
     */
    public void detachInputProcessor()
    {
        if (Gdx.input == null)
        {
            return;
        }

        Gdx.input.setInputProcessor(null);
    }

    /**
     * Releases the stage, texture and font. Safe to call once.
     */
    public void dispose()
    {
        stage.dispose();

        white.dispose();

        font.dispose();
    }

    /**
     * Returns the label a row button should carry, with the selected prefix
     * on the row that matches the current selection.
     *
     * <p>Instance method because the display name comes from the entries
     * the launcher supplied, not from the id alone. A test that hands the
     * screen a known entries list and asserts the label is a useful test;
     * the static {@link #rowIdForLabel} below handles the simpler case
     * where the id is also the display name.</p>
     *
     * @param rowId the id of the row being labelled; must not be null
     * @param selection the current selection; must not be null
     * @return the full button label, prefix included when the row is selected
     */
    public String rowLabel(final String rowId, final MapSelection selection)
    {
        if (rowId == null)
        {
            throw new IllegalArgumentException("rowId must not be null");
        }

        if (selection == null)
        {
            throw new IllegalArgumentException("selection must not be null");
        }

        final String displayName = displayNameFor(rowId);

        if (rowId.equals(selection.currentMapId()))
        {
            return SELECTED_PREFIX + displayName;
        }

        return displayName;
    }

    /**
     * Returns just the row id from a label that {@link #rowLabel} produced.
     *
     * <p>Strips the {@link #SELECTED_PREFIX} if present, otherwise returns
     * the label as-is. The id is the part the picker cares about — the
     * display name is for the screen, and the launcher's handler reads the
     * id, which is what was stored in {@link MapSelection} when the row
     * was clicked. The static shape is for tests that don't need the
     * full instance method.</p>
     *
     * @param label a label produced by {@link #rowLabel}; must not be null
     * @return the row id, with any prefix removed
     */
    public static String rowIdForLabel(final String label)
    {
        if (label == null)
        {
            throw new IllegalArgumentException("label must not be null");
        }

        if (label.startsWith(SELECTED_PREFIX))
        {
            return label.substring(SELECTED_PREFIX.length());
        }

        return label;
    }

    // Resolves the row id to its display name from the entries the launcher
    // handed the screen. Falls back to the id itself if the id is not in
    // the entries — a row whose display name the launcher did not supply
    // is still readable as its id, and a missing entry is a launcher bug
    // rather than a player one.
    private String displayNameFor(final String rowId)
    {
        for (final Entry entry : entries)
        {
            if (entry.id().equals(rowId))
            {
                return entry.displayName();
            }
        }

        return rowId;
    }

    // Called from a BlockButton's click. Stores the picked id in the shared
    // selection and relabels every row so the prefix moves.
    private void pickRow(final int rowIndex)
    {
        if (rowIndex < 0 || rowIndex >= rowCount)
        {
            return;
        }

        final Entry picked = entries.get(rowIndex);

        selection.setCurrentMapId(picked.id());

        relabelRows();
    }

    // Walks the row buttons and replaces each label with the one that
    // reflects the current selection. The labels' colours stay the same
    // — the visual difference between the selected and the unselected rows
    // is the prefix, and changing both is more change than the click
    // justified.
    private void relabelRows()
    {
        for (int row = 0; row < rowCount; row++)
        {
            final Entry entry = entries.get(row);

            rowButtons[row].setLabel(rowLabel(entry.id(), selection));
        }
    }

    // The face colour a row should wear. The selected row wears the play
    // face (green) so it reads as "the one you have"; the others wear the
    // neutral face so they read as "the ones you do not".
    private static Color rowColor(final String rowId, final MapSelection selection)
    {
        if (rowId.equals(selection.currentMapId()))
        {
            return MenuPalette.PLAY_FACE;
        }

        return MenuPalette.NEUTRAL_FACE;
    }

    // The shade of a row's base. The selected row gets the play shade to
    // match; the others get the neutral shade.
    private static Color rowShade(final String rowId, final MapSelection selection)
    {
        if (rowId.equals(selection.currentMapId()))
        {
            return MenuPalette.PLAY_SHADE;
        }

        return MenuPalette.NEUTRAL_SHADE;
    }

    // Places everything for a given window size. Absolute placement, same as
    // the menu and the settings screen: the heading is sized to the window,
    // the rows are fixed pixel size so they stay finger-and-pointer sized
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

        // The map rows stack up from the subtitle; the Back button sits at
        // the bottom margin. The whole column is left-aligned to the same x,
        // so the selected row's green face lines up with the green play
        // button on the menu.
        float nextTop = subtitleTop - subtitle.getHeight() - LIST_GAP;

        for (int row = 0; row < rowCount; row++)
        {
            rowButtons[row].setPosition((width - BUTTON_WIDTH) * 0.5f,
                nextTop - BUTTON_HEIGHT);

            nextTop = nextTop - BUTTON_HEIGHT - ROW_GAP;
        }

        backButton.setPosition((width - BUTTON_WIDTH) * 0.5f, BOTTOM_MARGIN);
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

    private Label label(final String text, final Color colour, final float scale)
    {
        final Label built = new Label(text, new Label.LabelStyle(font, colour));

        built.setFontScale(scale);

        return built;
    }
}
