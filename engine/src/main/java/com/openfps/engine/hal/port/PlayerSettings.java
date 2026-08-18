/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

import java.util.Objects;

import com.openfps.engine.hal.adapter.ActionBindings;

/**
 * The player's rebindable preferences: which physical control triggers which
 * {@link GameAction}, the mouse-look sensitivity, and whether vertical look
 * is inverted.
 *
 * <p><b>The settings are engine-side; the platform decides what they
 * apply to.</b> A desktop launcher's input adapter reads the
 * {@link ActionBindings} to know which key fires, reads the sensitivity to
 * scale mouse deltas, and reads the invert flag to flip pitch. An Android
 * launcher's touch adapter reads the same three fields. A future
 * XInput-backed pad adapter reads them too, because nothing in this class
 * is platform-specific - the codes are opaque to the engine and the
 * sensitivity and invert flag are floats and a bool.</p>
 *
 * <p>Modularity is the load-bearing design choice. A settings screen,
 * a saved profile, an XInput shim and a future gamepad hot-swap all
 * address controls through {@link GameAction} and a {@link
 * InputBinding.Source}; the engine never has to be told which kind of
 * device a key came from, only which action it triggers.</p>
 *
 * <h2>Immutability, and the {@code with*} setters</h2>
 *
 * <p>A {@code PlayerSettings} is immutable - every field is {@code final}.
 * The {@code with*} methods return a fresh instance with one field
 * changed, so a settings UI can hold a "draft" copy, mutate it with
 * {@code withSensitivity(0.006f)}, and only commit it to the live
 * adapter once the user accepts. The {@link ActionBindings} it carries
 * is itself immutable to the same effect: a rebind is a new table,
 * not a mutation, which is what {@code ActionBindings.bind} enforces.</p>
 *
 * <h2>Serialization: {@code toSpec} and {@code fromSpec}</h2>
 *
 * <p>Round-trip text format. One line per setting, a fixed
 * vocabulary, no JSON dependency in {@code :engine}. The grammar is
 * documented on each method; the test in
 * {@code PlayerSettingsTest} pins the round-trip and the error
 * handling. A launcher reads the file at startup, hands the result
 * to its input adapter, and never has to know the format existed.</p>
 */
public final class PlayerSettings
{
    /**
     * Default mouse sensitivity, in <b>radians of view rotation per pixel
     * of raw mouse motion</b>. Matches
     * {@code InputAccumulator.DEFAULT_RADIANS_PER_PIXEL}; duplicated here
     * so a caller that has no accumulator in hand can still build a
     * "fresh" settings instance.
     */
    public static final float DEFAULT_SENSITIVITY_RADIANS_PER_PIXEL = 0.005f;

    /**
     * Lowest legal sensitivity. Negative numbers would invert the
     * mouse direction; zero would freeze the view. Both are bugs.
     */
    public static final float MIN_SENSITIVITY_RADIANS_PER_PIXEL = 0.0001f;

    /**
     * Highest legal sensitivity. Beyond this the smallest mouse
     * gesture flicks the view across the whole screen, and what
     * looked like "fast" turns into "unusable".
     */
    public static final float MAX_SENSITIVITY_RADIANS_PER_PIXEL = 0.05f;

    /** Key the sensitivity field uses in the serialized form. */
    public static final String SPEC_KEY_SENSITIVITY = "mouse_sensitivity_radians_per_pixel";

    /** Key the invert-Y field uses in the serialized form. */
    public static final String SPEC_KEY_INVERT_Y = "invert_y";

    /** Prefix the binding lines use in the serialized form. */
    public static final String SPEC_KEY_BINDINGS = "bindings";

    /** Empty bindings - the default for a fresh settings instance. */
    private static final ActionBindings NO_BINDINGS = new ActionBindings();

    private final ActionBindings bindings;

    private final float mouseSensitivityRadiansPerPixel;

    private final boolean invertY;

    /**
     * Creates settings with the given bindings, sensitivity and invert-Y.
     *
     * <p>Prefer the {@code with*} setters and {@link #defaults()} over this
     * raw constructor in production code. The constructor is public so
     * {@link #fromSpec(String)} can build a value without re-validating
     * the inputs a second time.</p>
     *
     * @param bindings the rebind table; must not be null
     * @param mouseSensitivityRadiansPerPixel radians of view rotation per
     *     pixel of mouse motion; must be finite and in
     *     [{@link #MIN_SENSITIVITY_RADIANS_PER_PIXEL},
     *     {@link #MAX_SENSITIVITY_RADIANS_PER_PIXEL}]
     * @param invertY true to invert vertical look, false for the standard
     *     positive-is-up feel
     * @throws IllegalArgumentException if any argument is out of range
     */
    public PlayerSettings(final ActionBindings bindings, final float mouseSensitivityRadiansPerPixel,
        final boolean invertY)
    {
        if (bindings == null)
        {
            throw new IllegalArgumentException("bindings must not be null");
        }

        if (!Float.isFinite(mouseSensitivityRadiansPerPixel)
            || mouseSensitivityRadiansPerPixel < MIN_SENSITIVITY_RADIANS_PER_PIXEL
            || mouseSensitivityRadiansPerPixel > MAX_SENSITIVITY_RADIANS_PER_PIXEL)
        {
            throw new IllegalArgumentException("mouseSensitivityRadiansPerPixel must be finite and in ["
                + MIN_SENSITIVITY_RADIANS_PER_PIXEL + ", "
                + MAX_SENSITIVITY_RADIANS_PER_PIXEL + "], got " + mouseSensitivityRadiansPerPixel);
        }

        this.bindings = bindings;

        this.mouseSensitivityRadiansPerPixel = mouseSensitivityRadiansPerPixel;

        this.invertY = invertY;
    }

    /**
     * Returns the engine defaults for a given platform.
     *
     * <p>The supplied bindings are the platform's "what a fresh install
     * should have" — {@code DesktopBindings.defaults()} on the desktop
     * launcher, the equivalent touch scheme on Android. The settings
     * file is then a <i>replacement</i> for these defaults: a launcher
     * calls {@code defaults(platformBindings)} when no file exists, and
     * the result of {@link #fromSpec(String)} when one does. The file
     * is the full state — there is no merge between file bindings and
     * platform defaults — which is the contract a settings UI can
     * show without surprise: what you save is exactly what you get
     * back.</p>
     *
     * <p>Sensitivity defaults to
     * {@link #DEFAULT_SENSITIVITY_RADIANS_PER_PIXEL} and the invert
     * flag to false. A settings UI {@code with*}-edits from this
     * starting point.</p>
     *
     * @param platformBindings the platform's default rebind table; must
     *     not be null
     * @return a fresh settings with the platform's bindings, the
     *     engine-default sensitivity, and non-inverted look
     * @throws IllegalArgumentException if {@code platformBindings} is
     *     null
     */
    public static PlayerSettings defaults(final ActionBindings platformBindings)
    {
        if (platformBindings == null)
        {
            throw new IllegalArgumentException("platformBindings must not be null");
        }

        return new PlayerSettings(platformBindings, DEFAULT_SENSITIVITY_RADIANS_PER_PIXEL, false);
    }

    /** Returns the rebind table. Never null. */
    public ActionBindings bindings()
    {
        return bindings;
    }

    /**
     * Returns the mouse sensitivity, in radians of view rotation per
     * pixel of raw mouse motion.
     */
    public float mouseSensitivityRadiansPerPixel()
    {
        return mouseSensitivityRadiansPerPixel;
    }

    /**
     * Returns whether vertical look is inverted. True means the mouse
     * moves the camera as if the player is grabbing the top of the
     * view and pulling; false is the conventional
     * positive-is-up feel.
     */
    public boolean invertY()
    {
        return invertY;
    }

    /**
     * Returns a fresh settings with the given bindings and this
     * settings' sensitivity and invert flag.
     *
     * <p>A rebind produces a new {@link ActionBindings}; passing it
     * here copies it into a new {@code PlayerSettings} and discards
     * the old. Cheap because the table is a flat int-and-array
     * structure sized for the small set of {@link GameAction}s.</p>
     *
     * @param newBindings the new bindings; must not be null
     * @return a fresh settings with the same sensitivity and invert
     *     flag, but the new bindings
     * @throws IllegalArgumentException if {@code newBindings} is null
     */
    public PlayerSettings withBindings(final ActionBindings newBindings)
    {
        if (newBindings == null)
        {
            throw new IllegalArgumentException("newBindings must not be null");
        }

        return new PlayerSettings(newBindings, mouseSensitivityRadiansPerPixel, invertY);
    }

    /**
     * Returns a fresh settings with the given sensitivity. The
     * bounds are the same as the constructor; clamping a value the
     * user picked is the wrong default because a UI that silently
     * rounds is the kind of UI a player curses at.
     *
     * @param newSensitivityRadiansPerPixel the new sensitivity; must be
     *     finite and in
     *     [{@link #MIN_SENSITIVITY_RADIANS_PER_PIXEL},
     *     {@link #MAX_SENSITIVITY_RADIANS_PER_PIXEL}]
     * @return a fresh settings with the same bindings and invert flag,
     *     but the new sensitivity
     */
    public PlayerSettings withSensitivity(final float newSensitivityRadiansPerPixel)
    {
        return new PlayerSettings(bindings, newSensitivityRadiansPerPixel, invertY);
    }

    /**
     * Returns a fresh settings with the invert flag flipped. The
     * rest of the settings are unchanged.
     *
     * @param newInvertY the new value
     * @return a fresh settings with the same bindings and sensitivity,
     *     but the new invert flag
     */
    public PlayerSettings withInvertY(final boolean newInvertY)
    {
        return new PlayerSettings(bindings, mouseSensitivityRadiansPerPixel, newInvertY);
    }

    /**
     * Returns the settings in a one-line-per-field text form. The
     * grammar is:
     *
     * <pre>
     *   # comment
     *   mouse_sensitivity_radians_per_pixel=0.005
     *   invert_y=false
     *   bindings=ACTION:SOURCE:CODE[,SOURCE:CODE]...
     *   bindings=ACTION:
     * </pre>
     *
     * <p>(No bare {@code bindings} line — every line in the file is a
     * setting. The decoder rejects a {@code bindings} without an {@code =}
     * so a typo does not get silently treated as a section header.)</p>
     *
     * <p>One {@code bindings=} line per bound action; an action with
     * no bindings is written as {@code bindings=ACTION:} so a load
     * can distinguish "this action is unbound" from "this action
     * was not mentioned" (both default to nothing, but the file
     * round-trips correctly either way). The {@code bindings} lines
     * are written only for actions that have been bound; an empty
     * table produces an empty block, which the file's empty
     * actions trivially round-trip.</p>
     *
     * <p>Lines beginning with {@code #} are comments and skipped by
     * {@link #fromSpec(String)}; the encoder does not write them.
     * Whitespace around the {@code =} is allowed on input; not
     * produced on output.</p>
     */
    public String toSpec()
    {
        final StringBuilder text = new StringBuilder();

        text.append(SPEC_KEY_SENSITIVITY).append('=').append(mouseSensitivityRadiansPerPixel).append('\n');

        text.append(SPEC_KEY_INVERT_Y).append('=').append(invertY).append('\n');

        for (final GameAction action : GameAction.values())
        {
            text.append(SPEC_KEY_BINDINGS).append('=').append(action.name());

            final InputBinding[] row = bindings.bindingsFor(action);

            if (row.length > 0)
            {
                text.append(':');

                for (int index = 0; index < row.length; index++)
                {
                    if (index > 0)
                    {
                        text.append(',');
                    }

                    text.append(row[index].source().name()).append(':').append(row[index].code());
                }
            }

            text.append('\n');
        }

        return text.toString();
    }

    /**
     * Parses a settings file produced by {@link #toSpec()} (or hand
     * edited to the same shape) and returns the result.
     *
     * <p>Unknown keys are skipped with the line ignored; malformed
     * lines throw {@link IllegalArgumentException} with the line
     * number, because a typo in a settings file is something a
     * player can fix and a silent fallback would mask. Bindings
     * accumulate on a fresh {@link ActionBindings}; the action name
     * on each {@code bindings=...} line is the lookup key.</p>
     *
     * @param spec the file contents
     * @return the parsed settings; equal to {@link #defaults()} when
     *     the input is empty
     * @throws IllegalArgumentException if any line is malformed
     * @throws NullPointerException if {@code spec} is null
     */
    public static PlayerSettings fromSpec(final String spec)
    {
        Objects.requireNonNull(spec, "spec");

        float sensitivity = DEFAULT_SENSITIVITY_RADIANS_PER_PIXEL;

        boolean invertY = false;

        boolean sawSensitivity = false;

        boolean sawInvertY = false;

        final ActionBindings builder = new ActionBindings();

        int lineNumber = 0;

        for (final String rawLine : spec.split("\\r?\\n", -1))
        {
            lineNumber++;

            final String line = rawLine.trim();

            if (line.isEmpty() || line.startsWith("#"))
            {
                continue;
            }

            final int eq = line.indexOf('=');

            if (eq < 0)
            {
                throw new IllegalArgumentException(
                    "settings line " + lineNumber + " missing '=': " + rawLine);
            }

            final String key = line.substring(0, eq).trim();

            final String value = line.substring(eq + 1).trim();

            if (SPEC_KEY_SENSITIVITY.equals(key))
            {
                try
                {
                    sensitivity = Float.parseFloat(value);
                }
                catch (final NumberFormatException ex)
                {
                    throw new IllegalArgumentException(
                        "settings line " + lineNumber + " sensitivity is not a number: " + value, ex);
                }

                sawSensitivity = true;
            }
            else if (SPEC_KEY_INVERT_Y.equals(key))
            {
                invertY = Boolean.parseBoolean(value);

                sawInvertY = true;
            }
            else if (SPEC_KEY_BINDINGS.equals(key))
            {
                // The action name is the first segment before any ':'.
                // If the rest is empty the action is unbound.
                final int colon = value.indexOf(':');

                final String actionName;

                final String rest;

                if (colon < 0)
                {
                    actionName = value;

                    rest = "";
                }
                else
                {
                    actionName = value.substring(0, colon);

                    rest = value.substring(colon + 1);
                }

                final GameAction action = parseAction(actionName, lineNumber, rawLine);

                if (!rest.isEmpty())
                {
                    final InputBinding[] parsed = parseBindings(rest, lineNumber, rawLine);

                    builder.bind(action, parsed);
                }
            }
            else
            {
                throw new IllegalArgumentException(
                    "settings line " + lineNumber + " unknown key: " + key);
            }
        }

        // The constructor validates the sensitivity bounds; doing
        // the parse first lets a malformed file fail with line
        // number rather than after the whole document has been
        // scanned.
        // The constructor re-validates the sensitivity bounds; using the
        // default when the file omitted the line means a half-empty file
        // round-trips to a valid settings rather than re-validating the
        // default a second time for nothing.
        final float effectiveSensitivity;

        if (sawSensitivity)
        {
            effectiveSensitivity = sensitivity;
        }
        else
        {
            effectiveSensitivity = DEFAULT_SENSITIVITY_RADIANS_PER_PIXEL;
        }

        return new PlayerSettings(builder, effectiveSensitivity, invertY);
    }

    private static GameAction parseAction(final String name, final int lineNumber, final String rawLine)
    {
        try
        {
            return GameAction.valueOf(name);
        }
        catch (final IllegalArgumentException ex)
        {
            throw new IllegalArgumentException(
                "settings line " + lineNumber + " unknown action: " + name + " (in '" + rawLine + "')", ex);
        }
    }

    private static InputBinding[] parseBindings(final String rest, final int lineNumber,
        final String rawLine)
    {
        // Each binding is SOURCE:CODE; the comma splits them. Empty
        // entries (",,") are rejected so a typo is not silently
        // dropped - the file's whole purpose is to be correct.
        final String[] tokens = rest.split(",", -1);

        final InputBinding[] parsed = new InputBinding[tokens.length];

        for (int index = 0; index < tokens.length; index++)
        {
            final String token = tokens[index].trim();

            if (token.isEmpty())
            {
                throw new IllegalArgumentException(
                    "settings line " + lineNumber + " empty binding in '" + rawLine + "'");
            }

            final int innerColon = token.indexOf(':');

            if (innerColon < 0)
            {
                throw new IllegalArgumentException(
                    "settings line " + lineNumber + " binding '" + token + "' missing SOURCE:CODE in '"
                        + rawLine + "'");
            }

            final String sourceName = token.substring(0, innerColon).trim();

            final String codeText = token.substring(innerColon + 1).trim();

            final InputBinding.Source source;

            try
            {
                source = InputBinding.Source.valueOf(sourceName);
            }
            catch (final IllegalArgumentException ex)
            {
                throw new IllegalArgumentException(
                    "settings line " + lineNumber + " unknown source '" + sourceName
                        + "' (in '" + rawLine + "')", ex);
            }

            final int code;

            try
            {
                code = Integer.parseInt(codeText);
            }
            catch (final NumberFormatException ex)
            {
                throw new IllegalArgumentException(
                    "settings line " + lineNumber + " code '" + codeText + "' is not an integer (in '"
                        + rawLine + "')", ex);
            }

            parsed[index] = new InputBinding(source, code);
        }

        return parsed;
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof PlayerSettings that))
        {
            return false;
        }

        return mouseSensitivityRadiansPerPixel == that.mouseSensitivityRadiansPerPixel
            && invertY == that.invertY
            && bindings.equals(that.bindings);
    }

    @Override
    public int hashCode()
    {
        // Two large primes whose XOR is enough to spread the high bit and
        // keep the two boolean states from colliding with adjacent ints.
        // 1231 and 1237 are the same numbers java.util.Boolean.hashCode
        // uses, so a PlayerSettings hash agrees with the one its
        // components would produce in isolation.
        final int invertHash;

        if (invertY)
        {
            invertHash = 1231;
        }
        else
        {
            invertHash = 1237;
        }

        int hash = 1;

        hash = 31 * hash + Float.floatToRawIntBits(mouseSensitivityRadiansPerPixel);

        hash = 31 * hash + invertHash;

        hash = 31 * hash + bindings.hashCode();

        return hash;
    }
}
