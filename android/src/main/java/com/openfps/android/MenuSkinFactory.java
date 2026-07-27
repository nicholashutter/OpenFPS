/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

/**
 * Builds the menu {@code Skin} in code — no {@code uiskin.json}, no atlas,
 * no external asset of any kind.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * There is no asset pipeline yet (see {@code docs/ASSETS.md}), so a skin
 * loaded from a file would mean the APK either ships an unlicensed asset or
 * crashes on a missing one. Everything here is synthesised instead: one 1x1
 * white pixel tinted per style, and libGDX's built-in {@code lsans-15} face,
 * which ships inside {@code gdx.jar} and resolves through
 * {@code Gdx.files.classpath}. Verified present in the built APK at
 * {@code com/badlogic/gdx/utils/lsans-15.fnt} — the packaging block's
 * {@code META-INF/*} exclusion does not reach it.
 *
 * <b>Everything this creates is a <em>managed</em> GL resource.</b> That is
 * the whole trick to surviving an Android context loss, and it is deliberate:
 *
 * <ul>
 *   <li>The white {@code Texture} is built from a {@link PixmapTextureData}
 *       constructed with {@code managed = true} and {@code disposePixmap =
 *       false}. libGDX registers it in its managed-texture table and
 *       re-uploads it from the retained CPU-side {@code Pixmap} when the
 *       context comes back. {@code new Texture(pixmap)} would NOT do this —
 *       that convenience constructor marks the data unmanaged, and the
 *       result is a menu that renders as untextured white rectangles after
 *       the first task-switch. This is the single most common libGDX
 *       Android bug and the reason the constructor is spelled out longhand.</li>
 *   <li>The {@code Pixmap} itself is added to the {@code Skin}, so it stays
 *       alive exactly as long as the texture that reloads from it, and is
 *       disposed by the same {@code Skin.dispose()} call. Four bytes held
 *       for the life of the menu.</li>
 *   <li>The {@code BitmapFont} is backed by a {@code FileTextureData} from a
 *       classpath {@code FileHandle}, which libGDX manages by construction.</li>
 * </ul>
 *
 * The net effect is that nothing in the menu needs manual recreation across
 * a context loss.
 */
public final class MenuSkinFactory
{
    /** Skin resource name of the 1x1 white texture every drawable tints. */
    public static final String WHITE = "white";

    /** Skin resource name of the title label style. */
    public static final String TITLE_STYLE = "title";

    /** Skin resource name of the retained pixel source for the white texture. */
    private static final String WHITE_SOURCE = "white-source";

    /** Skin resource name of the shared font. */
    private static final String FONT = "default-font";

    /** Style name libGDX widgets look up when no style is named. */
    private static final String DEFAULT_STYLE = "default";

    /** Button fill at rest. */
    private static final String BUTTON_UP_HEX = "2d3748ff";

    /** Button fill while held. Lighter, because a finger covers the button. */
    private static final String BUTTON_DOWN_HEX = "4a5568ff";

    /** Button fill while disabled. */
    private static final String BUTTON_DISABLED_HEX = "1f2530ff";

    /** Accent used for the title and for pressed button text. */
    private static final String ACCENT_HEX = "f6ad55ff";

    private MenuSkinFactory()
    {
        // utility class
    }

    /**
     * Creates a complete skin: one white texture, one font, a title label
     * style, a default label style and a default text-button style.
     *
     * The caller owns the result and must call {@code dispose()} on it when
     * the surface goes away — that single call releases the pixmap, the
     * texture and the font together.
     *
     * @return a fresh skin; never null
     */
    public static Skin create()
    {
        final Skin skin = new Skin();

        final Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixel.setColor(Color.WHITE);
        pixel.fill();

        // managed = true is load-bearing — see the class Javadoc.
        final Texture white = new Texture(new PixmapTextureData(pixel, null, false, false, true));
        final TextureRegionDrawable base = new TextureRegionDrawable(new TextureRegion(white));

        // Both go into the skin so one dispose() releases both, and so the
        // pixmap cannot be collected while the texture still reloads from it.
        skin.add(WHITE_SOURCE, pixel);
        skin.add(WHITE, white);

        final BitmapFont font = createFont();
        skin.add(FONT, font);

        skin.add(DEFAULT_STYLE, new Label.LabelStyle(font, Color.WHITE));
        skin.add(TITLE_STYLE, new Label.LabelStyle(font, Color.valueOf(ACCENT_HEX)));
        skin.add(DEFAULT_STYLE, buttonStyle(font, base));

        return skin;
    }

    // Loads libGDX's built-in lsans-15 from the classpath. Linear filtering
    // because the menu scales this 15px face up several times for touch
    // sizing; nearest-neighbour at 4x is unreadable.
    private static BitmapFont createFont()
    {
        final BitmapFont font = new BitmapFont();
        font.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear,
            Texture.TextureFilter.Linear);
        return font;
    }

    // Tints the shared white pixel into the three button states. tint()
    // returns a new drawable that shares the same texture, so this costs no
    // additional GPU memory and nothing extra to restore.
    private static TextButton.TextButtonStyle buttonStyle(final BitmapFont font,
                                                          final TextureRegionDrawable base)
    {
        final TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
        style.font = font;
        style.up = base.tint(Color.valueOf(BUTTON_UP_HEX));
        style.down = base.tint(Color.valueOf(BUTTON_DOWN_HEX));
        // No hover on a touchscreen, but a connected mouse or a TV remote
        // still generates it, and an unset `over` draws nothing at all.
        style.over = base.tint(Color.valueOf(BUTTON_DOWN_HEX));
        style.disabled = base.tint(Color.valueOf(BUTTON_DISABLED_HEX));
        style.fontColor = Color.WHITE;
        style.downFontColor = Color.valueOf(ACCENT_HEX);
        style.disabledFontColor = Color.GRAY;
        return style;
    }
}
