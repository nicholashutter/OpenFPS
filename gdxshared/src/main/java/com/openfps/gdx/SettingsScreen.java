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
 * The settings screen: two switches, and the way back.
 *
 * <p>The first is the debug frame counter. Pressing it flips
 * {@link DebugSettings}, which is read by the {@link DebugOverlay} that appears
 * in the world and — through {@code DebugSettings.onChange} — by the software
 * renderer's outline pass. The second is the render mode: pressing it cycles
 * {@link RenderSettings}, which the {@link FramebufferPresenter} answers by
 * re-sizing a live framebuffer. This screen knows about neither mechanism; it
 * moves one value and relabels one button, twice over.</p>
 *
 * <h2>A button says what the setting is, not what pressing it does</h2>
 *
 * <p>"DEBUG OVERLAY  ON" rather than "TURN DEBUG OVERLAY OFF", and
 * "RENDER  480P" rather than "LOWER THE RESOLUTION". A control labelled with
 * its action makes the reader work out the current state by inverting the
 * label, and gets it wrong about half the time. Labelling it with the state
 * means the screen can be read rather than decoded, which is why both labels
 * are rebuilt from the settings object on every press.</p>
 *
 * <p><b>Neither setting survives the run</b> — see {@link DebugSettings} and
 * {@link RenderMode} for exactly what persisting them would cost and why that
 * bill is not worth paying for a diagnostic overlay or a mode a player can
 * restore in one press.</p>
 *
 * <p><b>No logic lives here.</b> Every button forwards and does nothing else,
 * which is what leaves this class as pure layout — the part that needs a window
 * to judge and cannot be covered headlessly.</p>
 *
 * <p><b>Threading:</b> constructed and used only on the platform render thread,
 * after the GL context exists.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class SettingsScreen
{
    /** The word the heading spells. */
    public static final String TITLE_TEXT = "SETTINGS";

    /** The stem of the debug toggle's label; the state is appended. */
    public static final String DEBUG_LABEL = "DEBUG OVERLAY";

    /** What the debug toggle actually does, spelled out under it. */
    public static final String DEBUG_HINT =
        "FPS and frame time in the corner, and outlines around targets";

    /** The stem of the render-mode control's label; the mode is appended. */
    public static final String RENDER_LABEL = "RENDER";

    /** What the render control actually does, spelled out under it. */
    public static final String RENDER_HINT =
        "How many pixels the rasterizer fills. Lower is faster; menus stay sharp";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.38f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.10f;

    /** Largest gap between the heading, each control and the Back key, in pixels. */
    private static final float CONTROL_GAP = 56.0f;

    /** Smallest that gap may shrink to on a dense panel, in pixels. */
    private static final float MIN_CONTROL_GAP = 16.0f;

    /** Gap under a control before its explanatory line, in pixels. */
    private static final float HINT_GAP = 14.0f;

    /** Clear space left under the Back key, in pixels. */
    private static final float BOTTOM_MARGIN = 24.0f;

    /**
     * How many gaps the free space is shared between: heading to the first
     * control, control to control, and the last hint to Back.
     */
    private static final float SEPARATION_COUNT = 3.0f;

    /** Button width in pixels. */
    private static final float BUTTON_WIDTH = 420.0f;

    /** Button height in pixels, base included. */
    private static final float BUTTON_HEIGHT = 62.0f;

    /** Button label font magnification over the 15px built-in font. */
    private static final float BUTTON_FONT_SCALE = 1.6f;

    /** Hint font magnification. */
    private static final float HINT_FONT_SCALE = 1.1f;

    /** The switch this screen flips. Never null. */
    private final DebugSettings settings;

    /** The render mode this screen cycles. Never null. */
    private final RenderSettings render;

    /** The Scene2D stage that owns the widget hierarchy and input. */
    private final Stage stage;

    /** The 1x1 white texture every rectangle is drawn from. */
    private final Texture white;

    /** The built-in font, used for the labels and the hint. */
    private final BitmapFont font;

    /** The backdrop actor, sized to the window on every resize. */
    private final MenuBackground background;

    /** The block heading. */
    private final BlockTitle heading;

    /** The debug switch, relabelled on every press. */
    private final BlockButton debugButton;

    /** What the debug switch does, in words. */
    private final Label debugHint;

    /** The render-mode control, relabelled on every press. */
    private final BlockButton renderButton;

    /** What the render control does, in words. */
    private final Label renderHint;

    /** The way back to the menu. */
    private final BlockButton backButton;

    /** How far to magnify the fixed pixel metrics — 1 on desktop, density on a phone. */
    private final float uiScale;

    /**
     * Builds the screen at desktop metrics. Requires a live GL context.
     *
     * @param debugSettings the switch to flip; must not be null
     * @param renderSettings the render mode to cycle; must not be null
     * @param onBack run when the player leaves; must not be null
     */
    public SettingsScreen(final DebugSettings debugSettings,
        final RenderSettings renderSettings, final Runnable onBack)
    {
        this(debugSettings, renderSettings, onBack, 1.0f);
    }

    /**
     * Builds the screen. Requires a live GL context — construct from
     * {@code ApplicationListener.create()} or later, never earlier.
     *
     * @param debugSettings the switch to flip; must not be null
     * @param renderSettings the render mode to cycle; must not be null. It comes
     *     from {@code FramebufferPresenter.renderSettings()}, because the
     *     presenter is the only thing that can act on a change
     * @param onBack run when the player leaves; must not be null
     * @param scale multiplies every fixed pixel metric. 1 on a desktop monitor;
     *     a phone passes its density so a button stays the same physical size
     *     whatever the panel does. Must be positive
     * @throws IllegalArgumentException if anything is null or {@code scale} is
     *     not positive
     */
    public SettingsScreen(final DebugSettings debugSettings,
        final RenderSettings renderSettings, final Runnable onBack, final float scale)
    {
        if (debugSettings == null)
        {
            throw new IllegalArgumentException("debugSettings must not be null");
        }
        if (renderSettings == null)
        {
            throw new IllegalArgumentException("renderSettings must not be null");
        }
        if (onBack == null)
        {
            throw new IllegalArgumentException("onBack must not be null");
        }
        if (!(scale > 0.0f))
        {
            throw new IllegalArgumentException("scale must be positive, got " + scale);
        }
        this.settings = debugSettings;
        this.render = renderSettings;
        this.uiScale = scale;
        this.white = whitePixelTexture();
        final TextureRegion pixel = new TextureRegion(white);
        this.font = new BitmapFont();
        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);
        this.heading = new BlockTitle(TITLE_TEXT, pixel, MenuPalette.NEUTRAL_FACE);

        this.debugButton = new BlockButton(debugButtonLabel(debugSettings),
            MenuPalette.NET_FACE, MenuPalette.NET_SHADE, pixel, font,
            BUTTON_FONT_SCALE * scale, this::toggleDebug);
        this.debugHint = label(DEBUG_HINT, MenuPalette.HINT, HINT_FONT_SCALE * scale);
        this.renderButton = new BlockButton(renderButtonLabel(renderSettings),
            MenuPalette.NET_FACE, MenuPalette.NET_SHADE, pixel, font,
            BUTTON_FONT_SCALE * scale, this::cycleRender);
        this.renderHint = label(RENDER_HINT, MenuPalette.HINT, HINT_FONT_SCALE * scale);
        this.backButton = new BlockButton("BACK", MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE * scale, onBack);

        stage.addActor(background);
        stage.addActor(heading);
        debugButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);
        stage.addActor(debugButton);
        stage.addActor(debugHint);
        renderButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);
        stage.addActor(renderButton);
        stage.addActor(renderHint);
        backButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);
        stage.addActor(backButton);
    }

    /**
     * Returns the label the debug toggle should carry for a given setting.
     *
     * <p>Static and public because it is the one part of this screen a headless
     * test can reach: that the button reports the state rather than inverting
     * it. See the class Javadoc on why that distinction is worth pinning.</p>
     *
     * @param debugSettings the switch to describe; must not be null
     * @return the full button label, state included
     */
    public static String debugButtonLabel(final DebugSettings debugSettings)
    {
        if (debugSettings == null)
        {
            throw new IllegalArgumentException("debugSettings must not be null");
        }
        return DEBUG_LABEL + "   " + debugSettings.overlayLabel();
    }

    /**
     * Returns the label the render control should carry for a given setting.
     *
     * <p>Static and public for the same reason {@link #debugButtonLabel} is: it
     * is the part of this control a headless test can reach, and the property
     * worth pinning is that the button reports the mode in force rather than
     * the one a press would move to.</p>
     *
     * @param renderSettings the setting to describe; must not be null
     * @return the full button label, mode included
     * @throws IllegalArgumentException if the setting is null
     */
    public static String renderButtonLabel(final RenderSettings renderSettings)
    {
        if (renderSettings == null)
        {
            throw new IllegalArgumentException("renderSettings must not be null");
        }
        return RENDER_LABEL + "   " + renderSettings.label();
    }

    /** Returns the switch this screen flips. Never null. */
    public DebugSettings settings()
    {
        return settings;
    }

    /** Returns the render mode this screen cycles. Never null. */
    public RenderSettings renderSettings()
    {
        return render;
    }

    /** Returns the debug toggle. */
    public BlockButton debugButton()
    {
        return debugButton;
    }

    /** Returns the render-mode control. */
    public BlockButton renderButton()
    {
        return renderButton;
    }

    /** Returns the button that leaves this screen. */
    public BlockButton backButton()
    {
        return backButton;
    }

    // Flips the switch and makes the button say so. One method rather than a
    // two-statement lambda, per STYLE.md § 6.2.
    private void toggleDebug()
    {
        settings.toggleOverlay();
        debugButton.setLabel(debugButtonLabel(settings));
    }

    // Moves to the next render mode and makes the button say so.
    //
    // The presenter is what actually re-sizes the framebuffer, and it does so
    // from inside cycle() — this runs on the render thread out of a Scene2D
    // callback, which is where a Texture may be rebuilt. See
    // FramebufferPresenter on why that is the safe thread and the safe moment.
    private void cycleRender()
    {
        render.cycle();
        renderButton.setLabel(renderButtonLabel(render));
    }

    /**
     * Places everything for a given window size.
     *
     * <h2>The rhythm is derived, not fixed</h2>
     *
     * <p>It used to be a chain of named offsets, and a second control broke it:
     * every metric here is multiplied by the panel's density, so on a 560 dpi
     * handset three buttons and two hints came to more than the screen was
     * tall and the Back key walked off the bottom. Sharing whatever space is
     * left between the three separations fixes that for any density and for the
     * third control this screen will eventually grow, and it costs one
     * subtraction. {@link #CONTROL_GAP} survives as the <i>most</i> it will
     * spend, so a roomy desktop window still looks deliberately spaced rather
     * than stretched to fill.</p>
     *
     * @param width window width in pixels
     * @param height window height in pixels
     */
    public void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        final float headingWidth = width * TITLE_WIDTH_FRACTION;
        final float cell = heading.cellSizeFor(headingWidth);
        final float headingHeight = cell * BlockFont.GLYPH_HEIGHT;
        final float headingTop = height * (1.0f - TITLE_TOP_FRACTION);
        heading.setBounds((width - headingWidth) * 0.5f, headingTop - headingHeight,
            headingWidth, headingHeight);

        final float buttonWidth = BUTTON_WIDTH * uiScale;
        final float buttonHeight = BUTTON_HEIGHT * uiScale;
        debugButton.setSize(buttonWidth, buttonHeight);
        renderButton.setSize(buttonWidth, buttonHeight);
        backButton.setSize(buttonWidth, buttonHeight);
        // Packed before they are measured: a Label's height is whatever its
        // wrapped text came to, and the gap below depends on that answer.
        debugHint.pack();
        renderHint.pack();

        final float controlsTop = headingTop - headingHeight;
        final float gap = separationFor(controlsTop, buttonHeight);

        // MUTABLE local — the y of the next thing to place, walking downwards.
        float cursor = controlsTop - gap;
        cursor = placeControl(debugButton, debugHint, cursor, width) - gap;
        cursor = placeControl(renderButton, renderHint, cursor, width) - gap;
        backButton.setPosition((width - buttonWidth) * 0.5f, cursor - buttonHeight);
    }

    // The gap the three separations each get: the free space shared equally,
    // never more than CONTROL_GAP and never less than MIN_CONTROL_GAP. The
    // floor matters more than the ceiling — a panel dense enough to make the
    // free space negative still gets a readable screen rather than overlapping
    // buttons.
    private float separationFor(final float controlsTop, final float buttonHeight)
    {
        final float content = buttonHeight * 3.0f + HINT_GAP * uiScale * 2.0f
            + debugHint.getHeight() + renderHint.getHeight();
        final float free = controlsTop - BOTTOM_MARGIN * uiScale - content;
        return Math.max(MIN_CONTROL_GAP * uiScale,
            Math.min(CONTROL_GAP * uiScale, free / SEPARATION_COUNT));
    }

    // Centres one button with its already-packed hint under it, and returns the
    // y the next thing may start from.
    private float placeControl(final BlockButton button, final Label hint, final float top,
        final float width)
    {
        button.setPosition((width - button.getWidth()) * 0.5f, top - button.getHeight());
        final float hintTop = top - button.getHeight() - HINT_GAP * uiScale;
        hint.setPosition((width - hint.getWidth()) * 0.5f, hintTop - hint.getHeight());
        return hintTop - hint.getHeight();
    }

    /**
     * Clears the window and draws the settings over it.
     *
     * @param deltaSeconds wall time since the previous frame, used only for the
     *     backdrop's drift
     */
    public void render(final float deltaSeconds)
    {
        ScreenUtils.clear(MenuPalette.BACKDROP);
        stage.act(deltaSeconds);
        stage.draw();
    }

    /** Gives the Scene2D stage the input processor, so the buttons respond. */
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
