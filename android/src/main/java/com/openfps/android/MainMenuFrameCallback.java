/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.util.Log;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.gdx.MenuActions;

/**
 * The Android main menu: title, Start Game, Settings, Quit.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * This is the engine's side of the platform frame loop while there is no
 * game to draw. It matches the desktop menu's content but not its metrics:
 * every dimension here is expressed in density-independent pixels and
 * multiplied by {@code Gdx.graphics.getDensity()} at build time, so a button
 * is the same physical size on a 160 dpi tablet and a 560 dpi phone. The
 * {@value #BUTTON_HEIGHT_DP} dp button height is well above the 48 dp
 * Material minimum touch target, because a menu is not a place to make
 * someone aim.
 *
 * <b>This does not advance the simulation.</b> Same rule as the desktop
 * callback: {@code GameLoop} owns the tick at a fixed 30/60/120 Hz on its own
 * thread, and the platform frame rate — 60 Hz, 90 Hz, or whatever the panel
 * and the thermal governor agree on this second — never drives a tic.
 *
 * <b>GL context loss.</b> Android can destroy the EGL context and rebuild it
 * while the process lives, and libGDX does <em>not</em> re-issue
 * {@code create()} when that happens (see {@link GdxLifecycleBridge}). So
 * {@link #onSurfaceReady} runs exactly once and everything it creates has to
 * survive the gap by itself. It does, because all of it is managed:
 * {@link MenuSkinFactory} builds the white texture over a retained pixmap
 * with {@code managed = true} and the font over a classpath file handle, and
 * the {@code Stage}'s internal {@code SpriteBatch} shader is managed by
 * libGDX. When the context returns, libGDX's own
 * {@code Texture.invalidateAllTextures} / {@code ShaderProgram
 * .invalidateAllShaderPrograms} re-upload every one of them from data still
 * held in RAM. Nothing here needs a rebuild, and {@link #onResume} therefore
 * has nothing to do but log. The moment anything unmanaged is added — a
 * {@code FrameBuffer}, a mesh built by hand — that stops being true and the
 * rebuild has to be written; the guard rail is that
 * {@link #onSurfaceLost} releases through one {@code Skin.dispose()}, so
 * there is exactly one place to extend.
 *
 * <b>Threading.</b> Every method runs on the GLSurfaceView render thread.
 */
public final class MainMenuFrameCallback implements I_FrameCallback
{
    /** Logcat tag. Android has no SLF4J binding, so platform code logs here. */
    private static final String TAG = "OpenFPS";

    /** Button width in density-independent pixels. */
    private static final float BUTTON_WIDTH_DP = 300f;

    /** Button height in dp. Material's minimum touch target is 48 dp. */
    private static final float BUTTON_HEIGHT_DP = 72f;

    /** Vertical gap between buttons, in dp. Wide enough to stop mis-taps. */
    private static final float BUTTON_GAP_DP = 20f;

    /** Gap between the title and the first button, in dp. */
    private static final float TITLE_GAP_DP = 56f;

    /** Title cap height in dp. */
    private static final float TITLE_TEXT_DP = 44f;

    /** Button label cap height in dp. */
    private static final float BUTTON_TEXT_DP = 22f;

    /** How many buttons the menu stacks. */
    private static final int BUTTON_COUNT = 4;

    /**
     * Fraction of the surface height the menu is allowed to occupy.
     *
     * <p>The remainder is breathing room above the title and below the last
     * button. Filling the screen edge to edge would fit, and would look like
     * something that had only just fitted.</p>
     */
    private static final float VERTICAL_FILL = 0.86f;

    /**
     * Pixel size of libGDX's built-in lsans-15 face (declared {@code size=15}
     * in its .fnt). The divisor that turns a dp text size into a Scene2D
     * font scale.
     */
    private static final float BASE_FONT_PIXELS = 15f;

    /**
     * Background clear colour. Held as an instance, not parsed per frame:
     * {@code Color.valueOf} allocates, and {@link #onFrame} is a hot path
     * that runs 60+ times a second. Never mutated after construction.
     */
    private static final Color BACKGROUND = Color.valueOf("10141cff");

    /** What the buttons do. Never null. */
    private final MenuActions actions;

    /** Scene2D stage. MUTABLE: GL-backed, built in onSurfaceReady, freed in onSurfaceLost. */
    private Stage stage;

    /** Programmatic skin. MUTABLE: GL-backed, built in onSurfaceReady, freed in onSurfaceLost. */
    private Skin skin;

    /**
     * Creates the menu callback.
     *
     * @param menuActions what the buttons do; must not be null. Quit is
     *     expected to route through {@code I_WindowPort.requestClose()} so the
     *     shutdown path is the same one a back gesture or a desktop
     *     window-close takes — {@code DefaultMenuActions} is the implementation
     *     that does, and it is shared with desktop rather than rewritten here
     */
    public MainMenuFrameCallback(final MenuActions menuActions)
    {
        if (menuActions == null)
        {
            throw new IllegalArgumentException("menuActions must not be null");
        }
        this.actions = menuActions;
    }

    /**
     * The total height the menu wants, in density-independent pixels.
     *
     * <p>Title, the gap under it, and four buttons each with a gap below.
     * Public so a test can assert the fit rule against it rather than against a
     * number copied out of this file.</p>
     *
     * @return the menu's natural height in dp
     */
    public static float naturalHeightDp()
    {
        return TITLE_TEXT_DP + TITLE_GAP_DP
            + (BUTTON_COUNT * (BUTTON_HEIGHT_DP + BUTTON_GAP_DP));
    }

    /**
     * Returns the density to lay the menu out at so it fits the surface.
     *
     * <p><b>This is not a nicety.</b> The menu's sizes are in dp, which is the
     * right unit — a button should be the same physical size on a tablet and a
     * phone — but dp says nothing about whether the result fits. Four 72 dp
     * buttons plus a title want 468 dp of height, and a landscape phone at
     * 2.625x has 411 dp of it. The first emulator run showed exactly that: the
     * title clipped off the top and Quit clipped off the bottom, which on a
     * screen whose only two controls are "start" and "leave" is a menu you
     * cannot use.</p>
     *
     * <p>So the density is capped rather than the sizes changed. Everything
     * stays proportional and everything stays touch-sized down to the point
     * where the whole block fits; below that the alternative would be a
     * scrolling menu, which is worse for four buttons.</p>
     *
     * @param height the surface height in pixels
     * @param density the screen's true density, pixels per dp
     * @return the density to lay out at — never more than {@code density}
     */
    public static float layoutDensity(final int height, final float density)
    {
        if (height <= 0 || !(density > 0.0f))
        {
            return density;
        }
        final float fits = (height * VERTICAL_FILL) / naturalHeightDp();
        if (fits < density)
        {
            return fits;
        }
        return density;
    }

    @Override
    public void onSurfaceReady(final int width, final int height)
    {
        final float density = layoutDensity(height, Gdx.graphics.getDensity());
        Log.i(TAG, "Building main menu: " + width + "x" + height
            + " density=" + Gdx.graphics.getDensity() + " laid out at " + density);

        skin = MenuSkinFactory.create();
        // ScreenViewport, not FitViewport: the UI is laid out in real pixels
        // and sized from the density, so it must not be letterboxed to a
        // fictional design resolution.
        stage = new Stage(new ScreenViewport());
        stage.addActor(buildRoot(skin, density));
        // The processor is NOT claimed here. Whoever owns the UI state decides
        // which of the menu and the game reads touches, and reasserting it from
        // inside the menu would take input back from the game every time the
        // surface was rebuilt. See AndroidUiFrameCallback.
    }

    /**
     * Gives the menu the input processor, so its buttons take touches.
     *
     * <p>Called on entering {@link com.openfps.gdx.UiState#MENU}, and once at
     * startup. A no-op before the surface exists.</p>
     */
    public void attachInputProcessor()
    {
        if (stage == null || Gdx.input == null)
        {
            return;
        }
        Gdx.input.setInputProcessor(stage);
    }

    /**
     * Takes the input processor away.
     *
     * <p>What makes the world genuinely free of the menu rather than merely
     * drawn over it: with the stage detached there is no hit testing and no
     * invisible Quit button under the player's aiming thumb.</p>
     */
    public void detachInputProcessor()
    {
        if (Gdx.input == null)
        {
            return;
        }
        Gdx.input.setInputProcessor(null);
    }

    /** Returns whether the menu has built its stage yet. */
    public boolean isBuilt()
    {
        return stage != null;
    }

    @Override
    public void onFrame(final float deltaSeconds)
    {
        if (stage == null)
        {
            return;
        }
        ScreenUtils.clear(BACKGROUND);
        stage.act(deltaSeconds);
        stage.draw();
    }

    @Override
    public void onResize(final int width, final int height)
    {
        if (stage == null)
        {
            return;
        }
        Log.i(TAG, "Menu resized: " + width + "x" + height);
        // centerCamera = true: the root table is centred on the viewport, so
        // the camera has to move with it or a rotation leaves the menu off
        // to one side.
        stage.getViewport().update(width, height, true);
    }

    @Override
    public void onPause()
    {
        // The last guaranteed callback before Android may kill the process.
        // The menu holds no unsaved state — the user profile is persisted
        // through I_UserProfilePort, which is not wired on Android yet — so
        // there is genuinely nothing to flush. When it is wired, the save
        // goes HERE and it must complete before this returns; onStop and
        // onDestroy are not promised.
        Log.i(TAG, "Menu paused — no unsaved state to flush");
    }

    @Override
    public void onResume()
    {
        // Deliberately empty of recreation work. See the class Javadoc: every
        // GL resource the menu owns is managed, so libGDX has already
        // re-uploaded it by the time this runs.
        Log.i(TAG, "Menu resumed — managed GL resources restored by libGDX");
    }

    @Override
    public void onSurfaceLost()
    {
        Log.i(TAG, "Menu surface lost — releasing stage and skin");
        detachInputProcessor();
        if (stage != null)
        {
            stage.dispose();
            stage = null;
        }
        if (skin != null)
        {
            // One call releases the pixmap, the white texture and the font.
            skin.dispose();
            skin = null;
        }
    }

    // Lays out the title and the three buttons in a centred, full-parent table.
    private Table buildRoot(final Skin uiSkin, final float density)
    {
        final Table root = new Table();
        root.setFillParent(true);
        root.center();

        final Label title = new Label("OpenFPS", uiSkin, MenuSkinFactory.TITLE_STYLE);
        title.setFontScale(fontScale(TITLE_TEXT_DP, density));
        root.add(title).padBottom(TITLE_GAP_DP * density).row();

        addButton(root, uiSkin, density, "Single Player", actions::onStartGame);
        addButton(root, uiSkin, density, "Multiplayer", actions::onMultiplayer);
        addButton(root, uiSkin, density, "Settings", actions::onSettings);
        addButton(root, uiSkin, density, "Quit", actions::onQuit);
        return root;
    }

    // Adds one touch-sized button wired to an action.
    private void addButton(final Table root, final Skin uiSkin, final float density,
                           final String text, final Runnable action)
    {
        final TextButton button = new TextButton(text, uiSkin);
        button.getLabel().setFontScale(fontScale(BUTTON_TEXT_DP, density));
        button.addListener(new ChangeListener()
        {
            @Override
            public void changed(final ChangeEvent event, final Actor actor)
            {
                action.run();
            }
        });
        root.add(button)
            .width(BUTTON_WIDTH_DP * density)
            .height(BUTTON_HEIGHT_DP * density)
            .padBottom(BUTTON_GAP_DP * density)
            .row();
    }

    // Converts a dp text size into the Scene2D font scale that renders it.
    private static float fontScale(final float targetDp, final float density)
    {
        return (targetDp * density) / BASE_FONT_PIXELS;
    }
}
