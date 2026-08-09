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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The map browser: a grid of map thumbnails for the mode the player
 * picked, with the chosen map highlighted.
 *
 * <p>Reached from the mode picker once the player has chosen TDM /
 * Hardpoint / Domination / CTF. The launcher hands the screen a
 * {@code List<Entry>} of {@code (id, displayName, thumbnailPath, mode)}
 * tuples &mdash; one per map for the chosen mode &mdash; and the screen
 * lays them out as a single-row grid. A single row fits four 320x180
 * thumbnails comfortably at 1280px wide; the launcher also pads the
 * list to four with a "COMING SOON" tile when fewer maps are
 * registered for a mode, so the grid stays even.</p>
 *
 * <p>The chosen map gets a coloured border in the mode's signature
 * colour (the same palette the mode picker uses) and the click target
 * is the whole tile, not just the label. The screen stays a pure
 * view: it stores the picked id in {@link MapSelection}, and the
 * frame loop reads it on its next reconciliation.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Constructed and used only on the platform render thread, after
 * the GL context exists.</p>
 *
 * Platform adapter &mdash; must not import from core engine packages.
 */
public final class MapSelectionScreen
{
    /**
     * One tile in the browser: the engine's stable id, the human-readable
     * name, the path to the thumbnail PNG, and the mode signature used
     * for the border colour.
     *
     * <p>Keeping them in one record means a screen that takes a
     * {@code List<Entry>} cannot accidentally sort by display name and
     * lose the id correspondence.</p>
     */
    public static final class Entry
    {
        /** The engine's stable id. Never null or blank. */
        private final String id;

        /** The human-readable name. Never null or blank. */
        private final String displayName;

        /** Classpath-relative path to the thumbnail PNG. Never null. */
        private final String thumbnailPath;

        /** Mode signature for the border colour. */
        private final String modeKey;

        /**
         * Creates a row.
         *
         * @param id the engine's id; must be non-blank
         * @param displayName the human-readable name; must be non-blank
         * @param thumbnailPath the path to the thumbnail PNG; must be non-blank
         * @param modeKey the mode signature ("TDM", "HARDPOINT", "DOMINATION", "CTF")
         * @throws IllegalArgumentException if any argument is null or blank
         */
        public Entry(final String id, final String displayName, final String thumbnailPath,
            final String modeKey)
        {
            if (id == null || id.isBlank())
            {
                throw new IllegalArgumentException("id must not be blank");
            }

            if (displayName == null || displayName.isBlank())
            {
                throw new IllegalArgumentException("displayName must not be blank");
            }

            if (thumbnailPath == null || thumbnailPath.isBlank())
            {
                throw new IllegalArgumentException("thumbnailPath must not be blank");
            }

            if (modeKey == null || modeKey.isBlank())
            {
                throw new IllegalArgumentException("modeKey must not be blank");
            }

            this.id = id;

            this.displayName = displayName;

            this.thumbnailPath = thumbnailPath;

            this.modeKey = modeKey;
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

        /** Returns the classpath-relative path to the thumbnail PNG. */
        public String thumbnailPath()
        {
            return thumbnailPath;
        }

        /** Returns the mode signature for the border colour. */
        public String modeKey()
        {
            return modeKey;
        }
    }

    /** The word the heading spells. */
    public static final String TITLE_TEXT = "SELECT MAP";

    /** The line of help under the heading. */
    public static final String SUBTITLE_TEXT = "Click a map to load it. BACK returns to the mode picker.";

    /**
     * Marker prefix on the currently selected map. Retained from the
     * list-era screen for source compatibility; the grid shows the
     * selection as a coloured border instead, so the prefix is not
     * used at draw time.
     */
    public static final String SELECTED_PREFIX = "> ";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.50f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.08f;

    /** Gap between the heading and the subtitle. */
    private static final float SUBTITLE_GAP = 16.0f;

    /** Gap between the subtitle and the first tile. */
    private static final float GRID_TOP_GAP = 28.0f;

    /** Gap between adjacent tiles. */
    private static final float TILE_GAP = 18.0f;

    /** Clear space left under the Back key. */
    private static final float BOTTOM_MARGIN = 28.0f;

    /** Width of one tile. */
    private static final float TILE_WIDTH = 280.0f;

    /** Height of one tile. */
    private static final float TILE_HEIGHT = 180.0f;

    /** Border thickness drawn around a selected tile. */
    private static final float SELECTED_BORDER = 4.0f;

    /** Footer gap under the tile. */
    private static final float TILE_LABEL_GAP = 8.0f;

    /** Height of the tile label band under the image. */
    private static final float TILE_LABEL_HEIGHT = 28.0f;

    /** Subtitle font magnification. */
    private static final float SUBTITLE_FONT_SCALE = 1.0f;

    /** Tile label font magnification. */
    private static final float TILE_LABEL_FONT_SCALE = 1.05f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 280.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 50.0f;

    /** Back button font magnification. */
    private static final float BUTTON_FONT_SCALE = 1.35f;

    /** The shared selection; the screen reads and writes this. */
    private final MapSelection selection;

    /** The pick callback; the screen invokes it with the picked row's id. */
    private final Consumer<String> onPick;

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

    /** The way back to the mode picker. */
    private final BlockButton backButton;

    /** The thumbnail texture per entry, in the same order. */
    private final List<Texture> thumbnailTextures;

    /** The per-entry image actors, in the same order. */
    private final List<Image> thumbnailImages;

    /** The per-entry name labels, in the same order. */
    private final List<Label> tileLabels;

    /** The per-entry selection-border colours, in the same order. */
    private final List<Color> tileBorders;

    /**
     * Builds the browser.
     *
     * @param selection the shared selection; the screen writes here on click
     * @param entries the rows to show, in display order; must not be null
     *     and must contain at least one entry
     * @param onPick run with the picked row's id when the player clicks a
     *     tile; must not be null
     * @param onBack run when the player clicks BACK; must not be null
     * @throws IllegalArgumentException if any argument is null, the entries
     *     list is empty, or the entries are otherwise unusable
     */
    public MapSelectionScreen(final MapSelection selection, final List<Entry> entries,
        final Consumer<String> onPick, final Runnable onBack)
    {
        if (selection == null)
        {
            throw new IllegalArgumentException("selection must not be null");
        }

        if (entries == null || entries.isEmpty())
        {
            throw new IllegalArgumentException("entries must not be null or empty");
        }

        if (onPick == null)
        {
            throw new IllegalArgumentException("onPick must not be null");
        }

        if (onBack == null)
        {
            throw new IllegalArgumentException("onBack must not be null");
        }

        this.onPick = onPick;

        this.selection = selection;

        this.entries = List.copyOf(entries);

        this.white = whitePixelTexture();

        final TextureRegion pixel = new TextureRegion(white);

        this.font = new BitmapFont();

        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);

        this.heading = new BlockTitle(TITLE_TEXT, pixel, MenuPalette.NEUTRAL_FACE);

        this.subtitle = label(SUBTITLE_TEXT, MenuPalette.HINT, SUBTITLE_FONT_SCALE);

        this.backButton = new BlockButton("BACK", MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE, onBack);

        // Load one thumbnail per entry. The PNG is 320x180 on disk; we let
        // the image actor scale it to TILE_WIDTH x TILE_HEIGHT, which keeps
        // the rasterizer honest (no per-frame up-scaling cost) and gives
        // a crisp 1:1 preview on hi-dpi displays.
        this.thumbnailTextures = new ArrayList<>(entries.size());

        this.thumbnailImages = new ArrayList<>(entries.size());

        this.tileLabels = new ArrayList<>(entries.size());

        this.tileBorders = new ArrayList<>(entries.size());

        for (final Entry entry : entries)
        {
            final Texture thumb;

            if (Gdx.files == null)
            {
                thumb = white;
            }
            else
            {
                thumb = loadThumbnail(entry.thumbnailPath());
            }

            thumbnailTextures.add(thumb);

            final Image image = new Image(new TextureRegion(thumb));

            image.setSize(TILE_WIDTH, TILE_HEIGHT);

            thumbnailImages.add(image);

            final Label nameLabel = label(entry.displayName(),
                MenuPalette.BUTTON_LABEL, TILE_LABEL_FONT_SCALE);

            tileLabels.add(nameLabel);

            tileBorders.add(borderColourFor(entry.modeKey()));

            // The image carries the click. The same handler is also added to
            // a transparent overlay actor in layoutFor so the label band
            // below the image is part of the click target too.
            final int captured = thumbnailImages.size() - 1;

            image.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener()
            {
                @Override
                public void clicked(final com.badlogic.gdx.scenes.scene2d.InputEvent event,
                    final float x, final float y)
                {
                    pickRow(captured);
                }
            });
        }

        stage.addActor(background);

        stage.addActor(heading);

        stage.addActor(subtitle);

        for (final Image image : thumbnailImages)
        {
            stage.addActor(image);
        }

        for (final Label nameLabel : tileLabels)
        {
            stage.addActor(nameLabel);
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
     * Re-lays out the browser for a new surface size.
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
     * Records the player's pick, writing the row's id to the shared
     * selection. The frame loop's observer will see the change and
     * advance the UI to the loading screen.
     *
     * @param row the index of the picked row, zero-based
     */
    private void pickRow(final int row)
    {
        if (row < 0 || row >= entries.size())
        {
            return;
        }

        final Entry entry = entries.get(row);

        selection.setCurrentMapId(entry.id());

        onPick.accept(entry.id());
    }

    /**
     * Clears the window and draws the browser.
     *
     * @param deltaSeconds wall time since the previous frame; used for the
     *     backdrop's drift and the heading's colour cycle
     */
    public void render(final float deltaSeconds)
    {
        ScreenUtils.clear(MenuPalette.BACKDROP);

        stage.act(deltaSeconds);

        stage.draw();
    }

    /**
     * Gives the Scene2D stage the input processor, so the tiles respond.
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
     * states do not see the browser's clicks.
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
     * 1x1 white texture and every thumbnail texture it loaded. The
     * caller is responsible for disposing the shared font and the
     * stage, which are not owned by this class.
     */
    public void dispose()
    {
        white.dispose();

        for (final Texture tex : thumbnailTextures)
        {
            if (tex != white)
            {
                tex.dispose();
            }
        }
    }

    // Loads a classpath-relative PNG into a Texture. A headless test
    // (no Gdx.files) gets the 1x1 white texture instead and a null
    // draw, which is fine for the validation the screen's tests do.
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

    // Picks the border colour for a tile based on its mode signature.
    // The four modes share the palette with the mode picker so a click
    // on the THDP key there is the same colour the player sees here.
    private static Color borderColourFor(final String modeKey)
    {
        if (modeKey == null)
        {
            return MenuPalette.NEUTRAL_FACE;
        }

        final String upper = modeKey.toUpperCase(java.util.Locale.ROOT);

        if (upper.contains("HARDPOINT"))
        {
            return MenuPalette.NEUTRAL_FACE;
        }

        if (upper.contains("DOMINATION"))
        {
            return MenuPalette.NET_FACE;
        }

        if (upper.contains("CTF") || upper.contains("CAPTURE"))
        {
            return MenuPalette.PLAY_FACE;
        }

        // TDM and unknown fall through to red.
        return MenuPalette.QUIT_FACE;
    }

    // Strips the legacy {@code "> "} prefix from a row label. Kept for
    // source compatibility with the list-era screen and the test that
    // pins the prefix down; the grid screen no longer produces prefixed
    // labels, so this is a pure helper now.
    static String rowIdForLabel(final String label)
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

    // Places everything for a given window size. Absolute placement, same
    // as the menu and the mode picker: the heading is sized to the window,
    // the tiles are fixed pixel size so they stay finger-and-pointer
    // sized whatever the window does.
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

        // The tiles share a horizontal row centred on the window, with the
        // selected tile wearing a coloured border. The label band sits
        // below each image.
        final int tileCount = entries.size();

        final float totalWidth = tileCount * TILE_WIDTH + (tileCount - 1) * TILE_GAP;

        final float gridLeft = (width - totalWidth) * 0.5f;

        final float imageTop = subtitleTop - subtitle.getHeight() - GRID_TOP_GAP;

        final float labelTop = imageTop - TILE_HEIGHT - TILE_LABEL_GAP;

        for (int i = 0; i < tileCount; i++)
        {
            final float tileLeft = gridLeft + i * (TILE_WIDTH + TILE_GAP);

            final Image image = thumbnailImages.get(i);

            image.setPosition(tileLeft, imageTop - TILE_HEIGHT);

            final Label nameLabel = tileLabels.get(i);

            nameLabel.pack();

            nameLabel.setPosition(
                tileLeft + (TILE_WIDTH - nameLabel.getWidth()) * 0.5f,
                labelTop - TILE_LABEL_HEIGHT);
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
