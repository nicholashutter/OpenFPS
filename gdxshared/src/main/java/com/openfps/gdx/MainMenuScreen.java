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
 * The welcome screen: a cube-built title over a drifting block field, and four
 * chunky keys to press.
 *
 * <h2>Everything is drawn, nothing is loaded</h2>
 *
 * <p><b>The screen owns exactly two GPU resources: a 1x1 white texture and
 * libGDX's built-in font.</b> Every block, every button face, every shadow is
 * that one white pixel drawn at a size and tinted a colour. The usual libGDX
 * route — {@code new Skin(Gdx.files.internal("uiskin.json"))} — needs a packed
 * atlas, a font and a JSON file on the classpath, and this project's asset
 * pipeline deliberately fails until the payload release exists
 * ({@code docs/ASSETS.md} § 9). A menu that cannot open on a fresh checkout is
 * worse than a menu drawn from primitives.</p>
 *
 * <p>It also happens to be the right look. The world behind this screen is
 * flat-shaded cubes from Kenney's kits; a title made of the same cubes belongs
 * there in a way a smoothly rendered wordmark would not. See {@link BlockFont}
 * for why the title is not simply the built-in font scaled up.</p>
 *
 * <h2>No logic lives here</h2>
 *
 * <p>Every button forwards to {@link MenuActions} and does nothing else. That is
 * what the headless tests cover; this class is layout, and layout needs a
 * window to judge.</p>
 *
 * <h2>The screen does not claim the input processor on construction</h2>
 *
 * <p>It has {@link #attachInputProcessor()} and {@link #detachInputProcessor()}
 * instead, and {@link GdxFrameLoopListener} calls them as {@link UiState}
 * changes. Claiming it in the constructor would mean the stage kept hit-testing,
 * hovering and dispatching clicks through the whole of {@link UiState#PLAYING} —
 * an invisible Quit button still sitting under the crosshair.</p>
 *
 * <b>Threading:</b> constructed and used only on the platform render thread,
 * after the GL context exists. Creating a {@link Texture} before
 * {@code create()} would fail.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class MainMenuScreen
{
    /** The word the title spells. */
    public static final String TITLE_TEXT = "OPENFPS";

    /** The line under the title. */
    public static final String TAGLINE_TEXT = "a software-rendered arena";

    /** Fraction of the window width the title spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.66f;

    /** Where the title's top edge sits, as a fraction of window height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.10f;

    /** Gap under the title before the tagline, in pixels. */
    private static final float TAGLINE_GAP = 26.0f;

    /** Gap under the tagline before the first button, in pixels. */
    private static final float BUTTON_BLOCK_GAP = 54.0f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 380.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 62.0f;

    /** Vertical gap between buttons, in pixels. */
    private static final float BUTTON_GAP = 16.0f;

    /** Label font magnification over the 15px built-in font. */
    private static final float BUTTON_FONT_SCALE = 1.6f;

    /** Tagline font magnification. */
    private static final float TAGLINE_FONT_SCALE = 1.15f;

    /** Footer font magnification. */
    private static final float FOOTER_FONT_SCALE = 0.95f;

    /** How far above the bottom edge the footer sits, in pixels. */
    private static final float FOOTER_MARGIN = 22.0f;

    /** The Scene2D stage that owns the widget hierarchy and input. */
    private final Stage stage;

    /** The 1x1 white texture every rectangle is drawn from. */
    private final Texture white;

    /** The built-in font, used for button labels and the footer. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** The block title, sized and placed on every resize. */
    private final BlockTitle title;

    /** The line under the title. */
    private final Label tagline;

    /** The controls hint along the bottom. */
    private final Label footer;

    /** The menu keys, top to bottom. */
    private final BlockButton[] buttons;

    /**
     * Builds the menu. Requires a live GL context — construct from
     * {@code ApplicationListener.create()}, never earlier.
     *
     * @param actions receives every button activation; must not be null
     */
    public MainMenuScreen(final MenuActions actions)
    {
        if (actions == null)
        {
            throw new IllegalArgumentException("actions must not be null");
        }

        this.white = whitePixelTexture();

        final TextureRegion pixel = new TextureRegion(white);

        this.font = new BitmapFont();

        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);

        this.title = new BlockTitle(TITLE_TEXT, pixel);

        this.tagline = label(TAGLINE_TEXT, MenuPalette.HINT, TAGLINE_FONT_SCALE);

        this.footer = label("W A S D  move    MOUSE  look    LEFT CLICK  fire    SPACE  jump",
            MenuPalette.HINT, FOOTER_FONT_SCALE);

        this.buttons = new BlockButton[]
        {
            new BlockButton("SINGLE PLAYER", MenuPalette.PLAY_FACE, MenuPalette.PLAY_SHADE,
                pixel, font, BUTTON_FONT_SCALE, actions::onStartGame),
            new BlockButton("MULTIPLAYER", MenuPalette.NET_FACE, MenuPalette.NET_SHADE,
                pixel, font, BUTTON_FONT_SCALE, actions::onMultiplayer),
            new BlockButton("SETTINGS", MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE,
                pixel, font, BUTTON_FONT_SCALE, actions::onSettings),
            new BlockButton("CONTROLS", MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE,
                pixel, font, BUTTON_FONT_SCALE, actions::onControls),
            new BlockButton("QUIT", MenuPalette.QUIT_FACE, MenuPalette.QUIT_SHADE,
                pixel, font, BUTTON_FONT_SCALE, actions::onQuit),
        };

        // Painter's order: backdrop, then title, then the keys on top.
        stage.addActor(background);

        stage.addActor(title);

        stage.addActor(tagline);

        for (final BlockButton button : buttons)
        {
            button.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);

            stage.addActor(button);
        }

        stage.addActor(footer);
    }

    /** Returns the menu keys, top to bottom. Never null. */
    public BlockButton[] buttons()
    {
        return buttons.clone();
    }

    /** Returns the block title actor. */
    public BlockTitle title()
    {
        return title;
    }

    /** Returns the drifting backdrop actor. */
    public MenuBackground background()
    {
        return background;
    }

    /**
     * Places everything for a given window size.
     *
     * <p><b>Absolute placement rather than a {@code Table}</b>, because the
     * pieces are sized in different terms: the title is a fraction of the window
     * width so it scales with the window, and the buttons are a fixed pixel size
     * so they stay finger-and-pointer sized whatever the window does. A single
     * layout container would have to be told both, one cell at a time, which is
     * more code than the arithmetic below and harder to read.</p>
     *
     * <p>Exposed and separate from {@link #resize} so a test can drive it
     * without a viewport.</p>
     *
     * @param width window width in pixels
     * @param height window height in pixels
     */
    public void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        final float titleWidth = width * TITLE_WIDTH_FRACTION;

        final float cell = title.cellSizeFor(titleWidth);

        final float titleHeight = cell * BlockFont.GLYPH_HEIGHT;

        final float titleTop = height * (1.0f - TITLE_TOP_FRACTION);

        title.setBounds((width - titleWidth) * 0.5f, titleTop - titleHeight,
            titleWidth, titleHeight);

        tagline.pack();

        final float taglineTop = titleTop - titleHeight - TAGLINE_GAP;

        tagline.setPosition((width - tagline.getWidth()) * 0.5f,
            taglineTop - tagline.getHeight());

        float nextTop = taglineTop - tagline.getHeight() - BUTTON_BLOCK_GAP;

        for (final BlockButton button : buttons)
        {
            button.setPosition((width - BUTTON_WIDTH) * 0.5f, nextTop - BUTTON_HEIGHT);

            nextTop = nextTop - BUTTON_HEIGHT - BUTTON_GAP;
        }

        footer.pack();

        footer.setPosition((width - footer.getWidth()) * 0.5f, FOOTER_MARGIN);
    }

    /**
     * Clears the window and draws the menu over it.
     *
     * <p><b>The clear belongs here.</b> There was briefly a second entry point
     * that drew the menu without clearing, so it could composite over the
     * software rasterizer's frame — and that is precisely the effect
     * {@link UiState} was introduced to remove: the world visibly carrying on
     * behind the buttons. In {@link UiState#MENU} the presenter is not called at
     * all, so nothing has covered the window when this runs and clearing is
     * required rather than merely allowed.</p>
     *
     * @param deltaSeconds wall time since the previous frame, used for the
     *     backdrop's drift and the title's colour cycle only — this never
     *     advances the simulation
     */
    public void render(final float deltaSeconds)
    {
        ScreenUtils.clear(MenuPalette.BACKDROP);

        stage.act(deltaSeconds);

        stage.draw();
    }

    /**
     * Clears the window to the menu background and draws nothing else.
     *
     * <p>Exists for the one frame shape that has neither a world nor a menu to
     * put on screen: {@link UiState#PLAYING} before the rasterizer has published
     * its first frame. Something must own the clear or the window shows whatever
     * the driver left in the back buffer, and it cannot be {@link #render}
     * because that would draw the menu the player just dismissed.</p>
     */
    public static void clearBackground()
    {
        ScreenUtils.clear(MenuPalette.BACKDROP);
    }

    /**
     * Gives the Scene2D stage the input processor, so the buttons respond.
     *
     * <p>Called on entering {@link UiState#MENU}. Headless — in tests, before
     * the application starts — {@code Gdx.input} is null and this does
     * nothing.</p>
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
     * Takes the input processor away, so the menu consumes nothing.
     *
     * <p>Called on entering {@link UiState#PLAYING}. This is the difference
     * between a menu that is hidden and a menu that is genuinely gone: without
     * it the stage still hit-tests every mouse position and a click aimed at the
     * game would land on whichever invisible button was under the
     * crosshair.</p>
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
     * Re-lays out the menu for a new surface size.
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
