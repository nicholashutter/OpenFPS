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
 * The settings screen: three controls in two named groups, and the way back.
 *
 * <h2>The groups are the feature, not decoration</h2>
 *
 * <p>Under <b>ACCESSIBILITY</b> sits the target outline: pressing it flips
 * {@link AccessibilitySettings}, which — through
 * {@code AccessibilitySettings.onChange} — is what the software renderer's
 * outline pass answers to. Under <b>DISPLAY &amp; DIAGNOSTICS</b> sit the render
 * mode, which cycles {@link RenderSettings} and which the
 * {@link FramebufferPresenter} answers by re-sizing a live framebuffer, and the
 * debug frame counter, which flips {@link DebugSettings} and is read by the
 * {@link DebugOverlay}. This screen knows about none of those mechanisms; it
 * moves one value and relabels one button, three times over.</p>
 *
 * <p><b>The outline used to be in the second group and that was wrong.</b> It
 * was one boolean with the frame counter, so a player who needed a visual aid
 * had to switch on a developer tool to get it. A heading is the cheapest possible
 * way of saying which controls are for playing the game and which are for looking
 * at it — cheaper than a second screen, and it fits the blocky no-asset style
 * this screen is built in. {@link AccessibilitySettings} has the rest of that
 * argument.</p>
 *
 * <h2>A button says what the setting is, not what pressing it does</h2>
 *
 * <p>"TARGET OUTLINE  ON" rather than "TURN THE OUTLINE OFF", and
 * "RENDER  480P" rather than "LOWER THE RESOLUTION". A control labelled with
 * its action makes the reader work out the current state by inverting the
 * label, and gets it wrong about half the time. Labelling it with the state
 * means the screen can be read rather than decoded, which is why every label
 * is rebuilt from the settings object on every press.</p>
 *
 * <p>That rule is only worth anything if the state it reports is the state in
 * force, and for the outline it once was not — see
 * {@link AccessibilitySettings} on the startup disagreement and on whose job it
 * is to push the initial value across.</p>
 *
 * <p><b>No setting here survives the run</b> — see {@link DebugSettings} and
 * {@link RenderMode} for exactly what persisting them would cost and why that
 * bill is not worth paying for a diagnostic overlay or a mode a player can
 * restore in one press. {@link AccessibilitySettings} is the one with a genuine
 * claim on the profile store, and says so.</p>
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

    /** Heading over the controls that help a player see what is happening. */
    public static final String ACCESSIBILITY_GROUP = "ACCESSIBILITY";

    /** Heading over the controls that are about the picture and the diagnostics. */
    public static final String DISPLAY_GROUP = "DISPLAY & DIAGNOSTICS";

    /**
     * The stem of the target outline's label; the state is appended.
     *
     * <p><b>Named for what the player sees, not for how it is drawn.</b> It was
     * "DEBUG OUTLINE" while it lived on the debug switch, which told a player who
     * needed it that it was not for them, and told a player who did not need it
     * nothing at all. "TARGET" is the word for the thing it marks.</p>
     */
    public static final String OUTLINE_LABEL = "TARGET OUTLINE";

    /** What the target outline actually does, spelled out under it. */
    public static final String OUTLINE_HINT =
        "Draws a bright keyline around the opponent you are aiming at";

    /** The stem of the debug toggle's label; the state is appended. */
    public static final String DEBUG_LABEL = "DEBUG OVERLAY";

    /**
     * What the debug toggle actually does, spelled out under it.
     *
     * <p>It used to end "and outlines around targets", because it did. It no
     * longer does either thing, and the hint had to move with the behaviour or it
     * would be the second place this screen lied about the outline.</p>
     */
    public static final String DEBUG_HINT =
        "FPS, frame time and resolution in the corner";

    /** The stem of the render-mode control's label; the mode is appended. */
    public static final String RENDER_LABEL = "RENDER";

    /** What the render control actually does, spelled out under it. */
    public static final String RENDER_HINT =
        "How many pixels the rasterizer fills. Lower is faster; menus stay sharp";

    /** Fraction of the window width the heading spans. */
    private static final float TITLE_WIDTH_FRACTION = 0.38f;

    /** Where the heading's top edge sits, as a fraction of height from the top. */
    private static final float TITLE_TOP_FRACTION = 0.10f;

    /**
     * The most of the surface height the heading may occupy, whatever the width
     * would otherwise give it.
     *
     * <p>The same cap, for the same reason, as
     * {@link GameOverScreen#headingWidthFor}: {@link BlockTitle} derives its cell
     * size from its <i>width</i>, so on a wide short panel a heading sized to look
     * right horizontally is far too tall, and it is the item with the least claim
     * on the space.</p>
     */
    private static final float HEADING_MAX_HEIGHT_FRACTION = 0.22f;

    /** Largest gap between the heading, each control and the Back key, in pixels. */
    private static final float CONTROL_GAP = 56.0f;

    /** Smallest that gap may shrink to on a dense panel, in pixels. */
    private static final float MIN_CONTROL_GAP = 16.0f;

    /** Gap under a control before its explanatory line, in pixels. */
    private static final float HINT_GAP = 14.0f;

    /** Gap under a group heading before the first control in it, in pixels. */
    private static final float GROUP_GAP = 10.0f;

    /** Clear space left under the Back key, in pixels. */
    private static final float BOTTOM_MARGIN = 24.0f;

    /**
     * How many gaps the free space is shared between: the title to the first
     * group, each group heading to the one above it, the two controls inside the
     * second group, and the last hint to Back.
     */
    private static final float SEPARATION_COUNT = 4.0f;

    /** Group heading font magnification. */
    private static final float GROUP_FONT_SCALE = 1.15f;

    /**
     * How many pointer-sized buttons this screen stacks: three controls and the
     * way back. Named because the fit rule counts them and a fourth control would
     * otherwise be added without the arithmetic noticing.
     */
    private static final float BUTTON_COUNT = 4.0f;

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

    /** The visual aids this screen flips. Never null. */
    private final AccessibilitySettings accessibility;

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

    /** The word over the accessibility group. */
    private final Label accessibilityGroup;

    /** The target outline switch, relabelled on every press. */
    private final BlockButton outlineButton;

    /** What the target outline does, in words. */
    private final Label outlineHint;

    /** The words over the display and diagnostics group. */
    private final Label displayGroup;

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
     * @param accessibilitySettings the visual aids to flip; must not be null
     * @param debugSettings the switch to flip; must not be null
     * @param renderSettings the render mode to cycle; must not be null
     * @param onBack run when the player leaves; must not be null
     */
    public SettingsScreen(final AccessibilitySettings accessibilitySettings,
        final DebugSettings debugSettings, final RenderSettings renderSettings,
        final Runnable onBack)
    {
        this(accessibilitySettings, debugSettings, renderSettings, onBack, 1.0f);
    }

    /**
     * Builds the screen. Requires a live GL context — construct from
     * {@code ApplicationListener.create()} or later, never earlier.
     *
     * @param accessibilitySettings the visual aids to flip; must not be null.
     *     Held by the launcher, which is the only object that can see both this
     *     screen and the renderer the outline lives in
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
    public SettingsScreen(final AccessibilitySettings accessibilitySettings,
        final DebugSettings debugSettings, final RenderSettings renderSettings,
        final Runnable onBack, final float scale)
    {
        if (accessibilitySettings == null)
        {
            throw new IllegalArgumentException("accessibilitySettings must not be null");
        }

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

        this.accessibility = accessibilitySettings;

        this.settings = debugSettings;

        this.render = renderSettings;

        this.uiScale = scale;

        this.white = whitePixelTexture();

        final TextureRegion pixel = new TextureRegion(white);

        this.font = new BitmapFont();

        this.stage = new Stage(new ScreenViewport());

        this.background = new MenuBackground(pixel);

        this.heading = new BlockTitle(TITLE_TEXT, pixel, MenuPalette.NEUTRAL_FACE);

        // The accessibility group first, and that ordering is a statement rather
        // than a habit: the aid a player might need to play at all should not be
        // below the developer tooling in the reading order.
        this.accessibilityGroup =
            label(ACCESSIBILITY_GROUP, MenuPalette.BUTTON_LABEL, GROUP_FONT_SCALE * scale);

        this.outlineButton = new BlockButton(outlineButtonLabel(accessibilitySettings),
            MenuPalette.PLAY_FACE, MenuPalette.PLAY_SHADE, pixel, font,
            BUTTON_FONT_SCALE * scale, this::toggleOutline);

        this.outlineHint = label(OUTLINE_HINT, MenuPalette.HINT, HINT_FONT_SCALE * scale);

        // Green for the aid, blue for the instruments — the same colour language
        // the main menu already uses, so the grouping survives being seen out of
        // the corner of an eye as well as being read.
        this.displayGroup =
            label(DISPLAY_GROUP, MenuPalette.BUTTON_LABEL, GROUP_FONT_SCALE * scale);

        this.renderButton = new BlockButton(renderButtonLabel(renderSettings),
            MenuPalette.NET_FACE, MenuPalette.NET_SHADE, pixel, font,
            BUTTON_FONT_SCALE * scale, this::cycleRender);

        this.renderHint = label(RENDER_HINT, MenuPalette.HINT, HINT_FONT_SCALE * scale);

        this.debugButton = new BlockButton(debugButtonLabel(debugSettings),
            MenuPalette.NET_FACE, MenuPalette.NET_SHADE, pixel, font,
            BUTTON_FONT_SCALE * scale, this::toggleDebug);

        this.debugHint = label(DEBUG_HINT, MenuPalette.HINT, HINT_FONT_SCALE * scale);

        this.backButton = new BlockButton("BACK", MenuPalette.NEUTRAL_FACE,
            MenuPalette.NEUTRAL_SHADE, pixel, font, BUTTON_FONT_SCALE * scale, onBack);

        stage.addActor(background);

        stage.addActor(heading);

        stage.addActor(accessibilityGroup);

        outlineButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);

        stage.addActor(outlineButton);

        stage.addActor(outlineHint);

        stage.addActor(displayGroup);

        renderButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);

        stage.addActor(renderButton);

        stage.addActor(renderHint);

        debugButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);

        stage.addActor(debugButton);

        stage.addActor(debugHint);

        backButton.setSize(BUTTON_WIDTH * scale, BUTTON_HEIGHT * scale);

        stage.addActor(backButton);
    }

    /**
     * Returns the label the target outline control should carry for a given
     * setting.
     *
     * <p>Static and public for the reason {@link #debugButtonLabel} is, and with
     * one more: this is the label that <b>disagreed with the game</b> when the
     * outline was gated by the debug switch, so "the button says ON when the
     * outline is on" is a property worth a headless assertion rather than a
     * reading of the source.</p>
     *
     * @param accessibilitySettings the switch to describe; must not be null
     * @return the full button label, state included
     * @throws IllegalArgumentException if the setting is null
     */
    public static String outlineButtonLabel(final AccessibilitySettings accessibilitySettings)
    {
        if (accessibilitySettings == null)
        {
            throw new IllegalArgumentException("accessibilitySettings must not be null");
        }

        return OUTLINE_LABEL + "   " + accessibilitySettings.targetOutlineLabel();
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

    /** Returns the visual aids this screen flips. Never null. */
    public AccessibilitySettings accessibilitySettings()
    {
        return accessibility;
    }

    /** Returns the render mode this screen cycles. Never null. */
    public RenderSettings renderSettings()
    {
        return render;
    }

    /** Returns the target outline toggle. */
    public BlockButton outlineButton()
    {
        return outlineButton;
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

    // Flips the visual aid and makes the button say so.
    //
    // Nothing here touches the renderer. The launcher hung
    // SoftwareRenderPort.setOutlineEnabled off AccessibilitySettings.onChange,
    // so moving the value is the whole of the work — which is exactly what this
    // screen did when the outline was on the debug switch, and is why the switch
    // being the wrong one was invisible from here.
    private void toggleOutline()
    {
        accessibility.toggleTargetOutline();

        outlineButton.setLabel(outlineButtonLabel(accessibility));
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
     * left between the separations fixes that for any density and for however
     * many controls this screen grows, and it costs one subtraction.
     * {@link #CONTROL_GAP} survives as the <i>most</i> it will spend, so a roomy
     * desktop window still looks deliberately spaced rather than stretched to
     * fill.</p>
     *
     * <p>The group headings are placed inside the same walk rather than as a
     * special case, which is what keeps this method one cursor moving down the
     * screen. Each one takes only its own height plus {@link #GROUP_GAP}; the
     * <i>separation</i> above a heading is the same shared gap every other
     * boundary gets, so a group reads as one block with a name on it.</p>
     *
     * <h2>Shrinking the gaps was not enough, and a phone proved it</h2>
     *
     * <p>Sharing the free space fixed the case where the <i>rhythm</i> did not
     * fit. It cannot fix the case where the <b>content</b> does not fit, and on a
     * 2400x1080 handset at 2.625x that is the case: four pointer-sized buttons,
     * three explanatory lines and two group headings come to roughly 1140 px under
     * a heading whose own bottom edge is already at 840 px. Every gap collapsed to
     * zero and the BACK key still sat about 300 px below the bottom edge — on the
     * one screen that owns the input processor, has un-caught the back key, and
     * therefore has no other way off it. The emulator showed exactly that: the
     * groups reading correctly, DEBUG OVERLAY sliced off at the bottom, and no
     * BACK at all.</p>
     *
     * <p>So there are now three corrections, applied in the order that costs the
     * player least, and the order is the whole argument:</p>
     *
     * <ol>
     *   <li><b>The heading yields first</b>, capped by height as well as width —
     *       it is decoration and it is the single largest item. Same rule, same
     *       reasoning and the same inversion of {@link BlockTitle}'s arithmetic as
     *       {@link GameOverScreen#headingWidthFor}.</li>
     *   <li><b>Then the hints go.</b> They are sentences <i>about</i> controls,
     *       not controls, and this screen's own rule is that a button already says
     *       what the setting is and what state it is in — see the class Javadoc.
     *       A player who cannot reach BACK has lost strictly more than a player
     *       who cannot read why the render mode matters.</li>
     *   <li><b>Then the gaps shrink</b>, as they already did.</li>
     * </ol>
     *
     * <p><b>Nothing that is touched is ever scaled.</b> All four buttons keep their
     * full pointer-sized boxes at every surface size, because a screen that fits by
     * shrinking its own touch targets has solved the wrong problem — the same line
     * {@link GameOverScreen#layoutFor} draws, and drawn in the same place.</p>
     *
     * @param width window width in pixels
     * @param height window height in pixels
     */
    public void layoutFor(final float width, final float height)
    {
        background.setBounds(0.0f, 0.0f, width, height);

        final float buttonWidth = BUTTON_WIDTH * uiScale;

        final float buttonHeight = BUTTON_HEIGHT * uiScale;

        outlineButton.setSize(buttonWidth, buttonHeight);

        debugButton.setSize(buttonWidth, buttonHeight);

        renderButton.setSize(buttonWidth, buttonHeight);

        backButton.setSize(buttonWidth, buttonHeight);

        // Packed before anything is measured: a Label's height is whatever its
        // text came to, and both the heading's budget and the hint decision
        // depend on that answer.
        accessibilityGroup.pack();

        outlineHint.pack();

        displayGroup.pack();

        renderHint.pack();

        debugHint.pack();

        final float headingTop = height * (1.0f - TITLE_TOP_FRACTION);

        final float headingWidth = headingWidthFor(width, height, heading.widthInBlocks(),
            headingHeightBudget(height, reachableContentHeight(buttonHeight)));

        final float cell = heading.cellSizeFor(headingWidth);

        final float headingHeight = cell * BlockFont.GLYPH_HEIGHT;

        heading.setBounds((width - headingWidth) * 0.5f, headingTop - headingHeight,
            headingWidth, headingHeight);

        final float controlsTop = headingTop - headingHeight;

        final boolean showHints = hintsFit(
            controlsTop - reachableContentHeight(buttonHeight), hintsHeight());

        outlineHint.setVisible(showHints);

        renderHint.setVisible(showHints);

        debugHint.setVisible(showHints);

        final float gap = separationFor(controlsTop, buttonHeight, showHints);

        // MUTABLE local — the y of the next thing to place, walking downwards.
        float cursor = controlsTop - gap;

        cursor = placeGroup(accessibilityGroup, cursor, width);

        cursor = placeControl(outlineButton, outlineHint, cursor, width, showHints) - gap;

        cursor = placeGroup(displayGroup, cursor, width);

        cursor = placeControl(renderButton, renderHint, cursor, width, showHints) - gap;

        cursor = placeControl(debugButton, debugHint, cursor, width, showHints) - gap;

        backButton.setPosition((width - buttonWidth) * 0.5f, cursor - buttonHeight);
    }

    /**
     * Returns the width to draw the heading at, capped by the surface and by
     * whatever vertical room the reachable controls have left.
     *
     * <p>Static and taking the block count rather than reading it off the heading,
     * so the rule can be asserted in a plain JVM — everything else on this screen
     * needs a GL context to exist at all. It is the same rule
     * {@link GameOverScreen#headingWidthFor} states, and it is written twice rather
     * than shared because the two screens cap against different content and the
     * shared version would take both their measurements as arguments and be no
     * shorter.</p>
     *
     * @param width the surface width in pixels
     * @param height the surface height in pixels
     * @param blocks the heading's width in {@link BlockFont} cells
     * @param heightBudget the most vertical room the heading may take, in pixels;
     *     {@link Float#MAX_VALUE} means there is no such limit
     * @return the heading width in pixels, never more than
     *     {@link #TITLE_WIDTH_FRACTION} of the surface
     */
    public static float headingWidthFor(final float width, final float height,
        final int blocks, final float heightBudget)
    {
        final float wanted = width * TITLE_WIDTH_FRACTION;

        if (blocks <= 0)
        {
            return wanted;
        }

        // BlockTitle derives its cell size from its WIDTH and draws downward, so
        // every height limit has to be expressed as a width by inverting that
        // arithmetic. Doing it here rather than guessing a second constant is
        // what keeps the two from drifting apart.
        final float allowedHeight =
            Math.min(height * HEADING_MAX_HEIGHT_FRACTION, heightBudget);

        final float capped = (allowedHeight / BlockFont.GLYPH_HEIGHT) * blocks;

        if (capped < wanted)
        {
            return capped;
        }

        return wanted;
    }

    /**
     * Returns how much vertical room is left for the heading once everything that
     * must stay reachable has taken its share.
     *
     * <p>Static and public because it is the half of the fit rule that decides
     * whether the player has a way off this screen, and it is assertable without a
     * window — which the rest of {@link #layoutFor} is not. Never negative: a
     * heading of zero height is ugly and a BACK key below the bottom edge is a dead
     * end, and only one of those two is recoverable.</p>
     *
     * @param surfaceHeight the surface height in pixels
     * @param contentHeight the height of everything below the heading that must
     *     stay on screen — all four buttons, both group headings and the bottom
     *     margin
     * @return pixels the heading may occupy, zero or more
     */
    public static float headingHeightBudget(final float surfaceHeight,
        final float contentHeight)
    {
        final float budget = surfaceHeight * (1.0f - TITLE_TOP_FRACTION) - contentHeight;

        if (budget < 0.0f)
        {
            return 0.0f;
        }

        return budget;
    }

    /**
     * Returns whether the explanatory hints can be afforded.
     *
     * <p>The second of the three corrections {@link #layoutFor} documents, as
     * arithmetic. A hint is a sentence about a control rather than a control, so it
     * is the next thing to go after the heading has already yielded as far as it
     * can — and it goes as a block of three, because two controls explained and one
     * not reads as a rendering fault rather than as a decision.</p>
     *
     * <p>Static and public because it is the part of this layout whose failure is a
     * screen with no way off it, and the part worth pinning without a window.</p>
     *
     * @param available pixels left once every reachable control has taken its
     *     share; may be negative, which is the case this rule exists for
     * @param hintsHeight the height the three hints and their gaps want, in pixels
     * @return true if the hints fit and should be drawn
     */
    public static boolean hintsFit(final float available, final float hintsHeight)
    {
        if (!(hintsHeight > 0.0f))
        {
            return true;
        }

        return available >= hintsHeight;
    }

    // Everything below the heading that must stay on screen: the four buttons,
    // the two group headings that name them, and the bottom margin. The hints are
    // deliberately absent — they are what this measurement decides the fate of.
    private float reachableContentHeight(final float buttonHeight)
    {
        return buttonHeight * BUTTON_COUNT + GROUP_GAP * uiScale * 2.0f
            + accessibilityGroup.getHeight() + displayGroup.getHeight()
            + BOTTOM_MARGIN * uiScale;
    }

    // What the three hints cost, their own gaps included. Measured rather than
    // computed, because a Label's height is the font's business.
    private float hintsHeight()
    {
        return HINT_GAP * uiScale * 3.0f
            + outlineHint.getHeight() + renderHint.getHeight() + debugHint.getHeight();
    }

    // The gap each separation gets: the free space shared equally, never more
    // than CONTROL_GAP.
    //
    // The floor is ZERO and used to be MIN_CONTROL_GAP, which is a change worth
    // explaining because it looks like a loosening. It is the opposite. A floor
    // does not make a panel fit — when the free space is already negative, a
    // floor is a guarantee that the Back key goes off the bottom, and it costs
    // SEPARATION_COUNT floors' worth of the room that was needed to avoid that.
    // Buttons that touch are still legible, because each one draws its own
    // six-pixel base; a Back key nobody can reach is not. So MIN_CONTROL_GAP
    // survives as the number a comfortable panel relaxes to and no longer as a
    // promise the geometry cannot keep.
    private float separationFor(final float controlsTop, final float buttonHeight,
        final boolean showHints)
    {
        // MUTABLE local — the hints are added only when they are being drawn, or
        // the gaps would be squeezed to pay for three labels nobody can see.
        float content = reachableContentHeight(buttonHeight);

        if (showHints)
        {
            content = content + hintsHeight();
        }

        final float free = controlsTop - content;

        final float shared = Math.min(CONTROL_GAP * uiScale, free / SEPARATION_COUNT);

        if (shared < MIN_CONTROL_GAP * uiScale)
        {
            return Math.max(0.0f, shared);
        }

        return shared;
    }

    // Centres a group heading and returns the y its first control starts from.
    private float placeGroup(final Label group, final float top, final float width)
    {
        group.setPosition((width - group.getWidth()) * 0.5f, top - group.getHeight());

        return top - group.getHeight() - GROUP_GAP * uiScale;
    }

    // Centres one button with its already-packed hint under it, and returns the
    // y the next thing may start from.
    //
    // A hidden hint costs nothing at all — not its height and not its gap. It is
    // still positioned, so that a later resize which can afford it again finds it
    // in the right place rather than wherever the last cramped layout left it.
    private float placeControl(final BlockButton button, final Label hint, final float top,
        final float width, final boolean showHints)
    {
        button.setPosition((width - button.getWidth()) * 0.5f, top - button.getHeight());

        final float hintTop = top - button.getHeight() - HINT_GAP * uiScale;

        hint.setPosition((width - hint.getWidth()) * 0.5f, hintTop - hint.getHeight());

        if (!showHints)
        {
            return top - button.getHeight();
        }

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
