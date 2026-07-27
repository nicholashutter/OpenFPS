/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * The Scene2D main menu: a title and the Start Game / Settings / Quit
 * buttons.
 *
 * <b>The skin is built in code, on purpose.</b> The usual libGDX route is
 * {@code new Skin(Gdx.files.internal("uiskin.json"))}, which needs a packed
 * atlas, a font, and a JSON file on the classpath. There is no asset
 * pipeline yet — {@code gradlew fetchAssets} deliberately fails until the
 * payload release exists (see {@code docs/ASSETS.md} § 9) — so requiring an
 * asset here would mean the menu could not run at all. Instead
 * {@link #buildSkin()} tints a 1×1 white {@link Pixmap} for the button
 * backgrounds and uses libGDX's built-in default {@link BitmapFont}. Both
 * ship inside the gdx jar, so this menu has zero external file
 * dependencies. Swap in a real skin when the asset payload lands.
 *
 * <b>No logic lives here.</b> Every button forwards to {@link MenuActions},
 * which is what the headless tests cover; this class is layout and nothing
 * else.
 *
 * <b>Threading:</b> constructed and used only on the platform render
 * thread, after the GL context exists. Creating a {@link Texture} before
 * {@code create()} would fail.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class MainMenuScreen
{
    /** Window background. */
    private static final Color BACKGROUND = new Color(0.08f, 0.09f, 0.12f, 1f);

    /** Title text colour. */
    private static final Color TITLE_COLOR = new Color(0.93f, 0.74f, 0.28f, 1f);

    /** Button label colour. */
    private static final Color LABEL_COLOR = new Color(0.94f, 0.94f, 0.96f, 1f);

    /** Button background, resting. */
    private static final Color BUTTON_UP = new Color(0.18f, 0.20f, 0.26f, 1f);

    /** Button background, pressed. */
    private static final Color BUTTON_DOWN = new Color(0.34f, 0.38f, 0.48f, 1f);

    /** Button background, hovered. */
    private static final Color BUTTON_OVER = new Color(0.26f, 0.29f, 0.37f, 1f);

    /** Title font magnification over the 15px built-in font. */
    private static final float TITLE_FONT_SCALE = 3.0f;

    /** Button label font magnification. */
    private static final float BUTTON_FONT_SCALE = 1.5f;

    /** Button width in logical pixels. */
    private static final float BUTTON_WIDTH = 260f;

    /** Button height in logical pixels. */
    private static final float BUTTON_HEIGHT = 52f;

    /** Vertical gap between buttons. */
    private static final float BUTTON_GAP = 14f;

    /** Gap between the title and the first button. */
    private static final float TITLE_GAP = 48f;

    /** The Scene2D stage that owns the widget hierarchy and input. */
    private final Stage stage;

    /** The programmatically built skin. Owns the texture and font. */
    private final Skin skin;

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
        this.skin = buildSkin();
        this.stage = new Stage(new ScreenViewport());

        final Label title = new Label("OpenFPS", skin.get("title", Label.LabelStyle.class));
        title.setFontScale(TITLE_FONT_SCALE);

        final Table root = new Table();
        root.setFillParent(true);
        root.add(title).padBottom(TITLE_GAP).row();
        addButton(root, "Start Game", actions::onStartGame);
        addButton(root, "Settings", actions::onSettings);
        addButton(root, "Quit", actions::onQuit);
        stage.addActor(root);

        Gdx.input.setInputProcessor(stage);
    }

    // Adds one full-width menu button wired to a single action.
    private void addButton(final Table root, final String text, final Runnable action)
    {
        final TextButton button = new TextButton(text, skin.get(TextButton.TextButtonStyle.class));
        button.getLabel().setFontScale(BUTTON_FONT_SCALE);
        button.addListener(new MenuButtonListener(action));
        root.add(button).width(BUTTON_WIDTH).height(BUTTON_HEIGHT).padBottom(BUTTON_GAP).row();
    }

    /**
     * Builds a skin with no external asset files: a 1×1 white texture,
     * tinted per button state, plus libGDX's built-in default font.
     *
     * The texture and font are registered with the skin so
     * {@link Skin#dispose()} frees both — they are the only native
     * resources this screen owns.
     *
     * @return a ready-to-use skin holding a "title" label style, a default
     *     label style, and a default text-button style
     */
    private static Skin buildSkin()
    {
        final Skin built = new Skin();

        final Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        final Texture texture = new Texture(pixmap);
        pixmap.dispose();
        built.add("white", texture, Texture.class);

        final BitmapFont font = new BitmapFont();
        built.add("default", font, BitmapFont.class);

        built.add("title", new Label.LabelStyle(font, TITLE_COLOR));
        built.add("default", new Label.LabelStyle(font, LABEL_COLOR));

        final TextureRegionDrawable white = new TextureRegionDrawable(new TextureRegion(texture));
        final TextButton.TextButtonStyle buttonStyle = new TextButton.TextButtonStyle();
        buttonStyle.font = font;
        buttonStyle.fontColor = LABEL_COLOR;
        buttonStyle.up = white.tint(BUTTON_UP);
        buttonStyle.down = white.tint(BUTTON_DOWN);
        buttonStyle.over = white.tint(BUTTON_OVER);
        built.add("default", buttonStyle);

        return built;
    }

    /**
     * Clears the screen and draws the menu.
     *
     * @param deltaSeconds wall time since the previous frame, used for
     *     widget animation only — this never advances the simulation
     */
    public void render(final float deltaSeconds)
    {
        ScreenUtils.clear(BACKGROUND);
        stage.act(deltaSeconds);
        stage.draw();
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
    }

    /** Releases the stage, texture and font. Safe to call once. */
    public void dispose()
    {
        stage.dispose();
        skin.dispose();
    }
}
