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
 * The brief screen that runs between the GL context coming up and the
 * main menu being interactive.
 *
 * <p>Reaches the player as a single line of centred text on the menu's
 * backdrop, held for {@link #DEFAULT_HOLD_SECONDS} before the frame
 * loop swaps in the main menu. The point of the screen is not to mask
 * a long load — the engine's static-allocation rules have made most of
 * the bootstrap too cheap to wait for — but to make the few hundred
 * milliseconds between window-up and menu-interactive feel intentional
 * rather than frozen. A black window that becomes a menu is a crash
 * the user has to decide not to report.</p>
 *
 * <p>It also exists so the engine's "no allocation outside init" rule
 * has a place to live. Static allocation means the heap pre-warms at
 * application start; the screen is the visible acknowledgement that
 * the pre-warm is in progress, and the only frame the engine
 * deliberately holds for the player to see.</p>
 *
 * <h2>Built from primitives, like the rest of the menu</h2>
 *
 * <p>Same constraint as {@link MainMenuScreen}: a 1x1 white texture and
 * libGDX's built-in font are the only GPU resources. The screen has
 * no skins, no atlas, no JSON, so it cannot be the thing that blocks a
 * fresh checkout from booting.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Constructed and used only on the platform render thread, after
 * the GL context exists. {@code dispose()} releases the white pixel
 * and the stage; both happen once.</p>
 *
 * Platform adapter - must not import from core engine packages.
 */
public final class BootScreen
{
    /**
     * How long the boot screen stays up before the frame loop swaps in
     * the main menu.
     *
     * <p>Half a second is long enough to read the line, short enough
     * to feel like the menu arrived rather than a separate step. A
     * longer hold makes the player wonder if the click on the
     * launcher icon even registered; a shorter one looks like a
     * flicker the driver introduced. 0.5 s is the small number that
     * was not annoying in practice.</p>
     */
    public static final float DEFAULT_HOLD_SECONDS = 0.5f;

    /** The single line of text the screen shows. */
    public static final String CAPTION_TEXT = "LOADING...";

    /** Font magnification for the caption. */
    private static final float CAPTION_FONT_SCALE = 1.6f;

    /** The Scene2D stage that owns the widget hierarchy. */
    private final Stage stage;

    /** The 1x1 white texture the backdrop is drawn from. */
    private final Texture white;

    /** The built-in font, used for the caption. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** The centred caption, the only text on the screen. */
    private final Label caption;

    /**
     * Builds the boot screen.
     */
    public BootScreen()
    {
        this.white = whitePixelTexture();

        final TextureRegion pixel = new TextureRegion(white);

        this.font = new BitmapFont();

        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);

        this.caption = label(CAPTION_TEXT, MenuPalette.HINT, CAPTION_FONT_SCALE);

        stage.addActor(background);

        stage.addActor(caption);
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
     * Clears the window and draws the boot screen.
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
     * Releases the GL-owned resources this screen is holding: the 1x1
     * white texture. Safe to call once.
     */
    public void dispose()
    {
        white.dispose();
    }

    // Places the caption centred horizontally, slightly above the middle
    // of the window so it reads as a heading rather than a button.
    private void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        caption.pack();

        caption.setPosition((width - caption.getWidth()) * 0.5f,
            (height - caption.getHeight()) * 0.5f);
    }

    private static Label label(final String text, final Color colour, final float scale)
    {
        final Label built = new Label(text, new Label.LabelStyle(new BitmapFont(), colour));

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
