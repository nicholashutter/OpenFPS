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
import com.openfps.engine.hal.port.I_WindowPort;

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

    /** The window, so Quit goes through the port rather than calling finish(). */
    private final I_WindowPort window;

    /** Scene2D stage. MUTABLE: GL-backed, built in onSurfaceReady, freed in onSurfaceLost. */
    private Stage stage;

    /** Programmatic skin. MUTABLE: GL-backed, built in onSurfaceReady, freed in onSurfaceLost. */
    private Skin skin;

    /**
     * Creates the menu callback.
     *
     * @param window the window port; Quit is routed through
     *     {@code requestClose()} so the shutdown path is the same one a
     *     back-gesture or a desktop window-close takes
     */
    public MainMenuFrameCallback(final I_WindowPort window)
    {
        if (window == null)
        {
            throw new IllegalArgumentException("window must not be null");
        }
        this.window = window;
    }

    @Override
    public void onSurfaceReady(final int width, final int height)
    {
        final float density = Gdx.graphics.getDensity();
        Log.i(TAG, "Building main menu: " + width + "x" + height + " density=" + density);

        skin = MenuSkinFactory.create();
        // ScreenViewport, not FitViewport: the UI is laid out in real pixels
        // and sized from the density, so it must not be letterboxed to a
        // fictional design resolution.
        stage = new Stage(new ScreenViewport());
        stage.addActor(buildRoot(skin, density));
        Gdx.input.setInputProcessor(stage);
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
        Gdx.input.setInputProcessor(null);
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

        addButton(root, uiSkin, density, "Start Game", this::onStartGame);
        addButton(root, uiSkin, density, "Settings", this::onSettings);
        addButton(root, uiSkin, density, "Quit", this::onQuit);
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

    // Start Game: no gameplay to enter yet. Phase 2 replaces this with a map
    // load; the button exists now so the menu matches the desktop one.
    private void onStartGame()
    {
        Log.i(TAG, "Start Game pressed — no map loader until Phase 2");
    }

    // Settings: no settings screen yet.
    private void onSettings()
    {
        Log.i(TAG, "Settings pressed — no settings screen yet");
    }

    // Quit goes through the port, not Activity.finish(), so the engine sees
    // the same close request a desktop window-close produces.
    private void onQuit()
    {
        Log.i(TAG, "Quit pressed");
        window.requestClose();
    }
}
