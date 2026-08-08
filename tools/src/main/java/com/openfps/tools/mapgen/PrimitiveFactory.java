/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;

/**
 * Registry of primitive types the config parser can look up.
 *
 * <h2>Why a registry</h2>
 *
 * <p>New primitive types are added by registering a builder under a type name.
 * The factory is the only place the parser turns a {@code "type"} field into
 * a concrete {@link Primitive}, which means a future "Ramp" or "Column" lands
 * in exactly one new place: a register call from a class loaded alongside
 * {@link Box} and {@link Sign}. The generator never grows a switch and the
 * config schema never grows a new keyword outside the registry.</p>
 *
 * <h2>Why no auto-discovery</h2>
 *
 * <p>The registry is explicit. Auto-discovery (classpath scanning, service
 * loaders, reflection) would couple the generator's behaviour to whatever
 * happens to be on the classpath at build time, which is the wrong shape for
 * a build-time tool — a Gradle dependency added in a different module
 * silently changing how maps are parsed is a debugging session nobody wants
 * to have. The registry, by contrast, is read in one place at one moment.</p>
 *
 * <h2>Built-in types</h2>
 *
 * <p>The factory is constructed with {@link Box} and {@link Sign} already
 * registered. {@link #registerBuiltin(String, String, float)} adds the
 * Kenney swatch map: the names a config can put in a primitive's
 * {@code texture} field are bound to the {@link KenneySwatch}es the generator
 * knows about.</p>
 */
public final class PrimitiveFactory
{
    /** Name of the built-in box primitive. Stable across versions. */
    public static final String TYPE_BOX = "box";

    /** Name of the built-in sign primitive. Stable across versions. */
    public static final String TYPE_SIGN = "sign";

    private final Map<String, PrimitiveBuilder> registry = new HashMap<>();
    private final Map<String, KenneySwatch> swatches = new HashMap<>();

    /**
     * Creates a factory with the built-in primitive types and Kenney swatches
     * pre-registered.
     *
     * <p>The returned factory is ready to parse configs; no further setup is
     * needed. New primitives are added with {@link #registerType} after
     * construction.</p>
     *
     * @return a new factory
     */
    public static PrimitiveFactory createDefault()
    {
        final PrimitiveFactory factory = new PrimitiveFactory();
        factory.registerType(TYPE_BOX, (obj, worldUnitsPerTile) -> Box.fromJson(obj,
            worldUnitsPerTile));
        factory.registerType(TYPE_SIGN, (obj, worldUnitsPerTile) -> Sign.fromJson(obj,
            worldUnitsPerTile));
        KenneySwatch.registerBuiltins(factory);
        return factory;
    }

    /**
     * Registers a primitive type under the given name.
     *
     * <p>Re-registration replaces the previous binding. That is intentional
     * for tests but should not be done in production code: the parser would
     * silently start building the new type for the registered name.</p>
     *
     * @param type the type name; must be non-blank
     * @param builder the builder that turns a JSON object into a primitive
     * @throws IllegalArgumentException if {@code type} is blank
     */
    public void registerType(final String type, final PrimitiveBuilder builder)
    {
        if (type == null || type.isBlank())
        {
            throw new IllegalArgumentException("type must not be blank");
        }
        registry.put(type, builder);
    }

    /**
     * Registers a Kenney swatch the config can name.
     *
     * @param name the swatch name; must be non-blank
     * @param swatch the swatch; must not be null
     */
    public void registerSwatch(final String name, final KenneySwatch swatch)
    {
        if (name == null || name.isBlank())
        {
            throw new IllegalArgumentException("swatch name must not be blank");
        }
        swatches.put(name, swatch);
    }

    /**
     * Returns the swatch registered under the given name, or null.
     *
     * @param name the swatch name a primitive's {@code texture} field carries
     * @return the swatch, or null if no swatch is registered under that name
     */
    public KenneySwatch swatch(final String name)
    {
        return swatches.get(name);
    }

    /**
     * Returns the set of registered swatch names.
     *
     * <p>Used by the JSON parser to validate that every primitive's
     * {@code texture} field names a swatch the factory knows about.</p>
     *
     * @return an unmodifiable view of the registered swatch names
     */
    public Set<String> swatchNames()
    {
        return Set.copyOf(swatches.keySet());
    }

    /**
     * Builds a primitive from a JSON object.
     *
     * @param obj the JSON object; its {@code type} field names the primitive
     * @param worldUnitsPerTile the config's world-units-per-texture-repeat
     * @return the primitive
     * @throws IllegalArgumentException if the {@code type} field is missing,
     *     unknown, or the object fails the registered builder's validation
     */
    public Primitive create(final JsonObject obj, final float worldUnitsPerTile)
    {
        if (obj == null)
        {
            throw new IllegalArgumentException("primitive JSON must not be null");
        }
        if (!obj.has("type") || obj.get("type").isJsonNull())
        {
            throw new IllegalArgumentException("primitive is missing 'type' field");
        }
        final String type = obj.get("type").getAsString();
        final PrimitiveBuilder builder = registry.get(type);
        if (builder == null)
        {
            throw new IllegalArgumentException("unknown primitive type: '" + type
                + "' (registered: " + registry.keySet() + ")");
        }
        final Primitive primitive = builder.build(obj, worldUnitsPerTile);
        primitive.validate();
        return primitive;
    }

    /**
     * Strategy that turns a JSON object into a primitive.
     *
     * <p>The two parameters are the parsed JSON and the config's
     * {@code worldUnitsPerTile}, so a single builder can be reused across
     * configs with different texture scales.</p>
     */
    @FunctionalInterface
    public interface PrimitiveBuilder
    {
        /**
         * Builds a primitive from a JSON object.
         *
         * @param obj the JSON object the parser is currently processing
         * @param worldUnitsPerTile the config's world-units-per-texture-repeat
         * @return the primitive
         */
        Primitive build(JsonObject obj, float worldUnitsPerTile);
    }
}
