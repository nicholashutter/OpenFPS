/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import java.util.Map;

import com.openfps.tools.model.ModelBuilder;

/**
 * One geometric shape in a map config: a box, a sign, anything the generator
 * knows how to place.
 *
 * <h2>The contract</h2>
 *
 * <p>A primitive owns its shape and its placement, and nothing else. The
 * generator wires primitives into a model in the order it reads them, opening
 * a submesh per unique {@link #texture} name and closing it at the end.</p>
 *
 * <p>{@link #submesh()} picks the submesh the primitive is appended to: 0 for
 * floors, 1 for walls, 2 for accents. Two primitives that share a texture and
 * a submesh are emitted as one continuous draw call; two primitives that
 * differ in either end up in different submeshes. The submesh index is a
 * design knob rather than a property of the type — a "floor" box uses 0 and a
 * "wall" box uses 1, but the choice is the primitive's and not the
 * generator's, so a future "ceiling" box can sit in submesh 1 without changing
 * the type registry.</p>
 *
 * <h2>Why a factory and not {@code instanceof}</h2>
 *
 * <p>New primitive types are added without touching the generator. A future
 * "Ramp" or "Column" type registers itself with {@link PrimitiveFactory} and
 * the config schema grows by one {@code type} string. The generator is the
 * single pass that turns a list of primitives into a model; the primitives
 * are the things that know what a box or a sign is.</p>
 */
public interface Primitive
{
    /**
     * Returns the {@code type} string the config names this primitive by.
     *
     * <p>Matches the {@code type} field of the JSON object and the key
     * {@link PrimitiveFactory} looks the primitive up under. Stable across
     * versions — renaming a type is a breaking change for every config file.</p>
     *
     * @return the type name, e.g. {@code "box"} or {@code "sign"}
     */
    String type();

    /**
     * Returns the submesh this primitive's triangles are appended to.
     *
     * <p>The default submesh groupings are 0 (floor), 1 (wall), 2 (accent), but
     * a primitive may use any non-negative index; the generator does not
     * police the value.</p>
     *
     * @return the submesh index
     */
    int submesh();

    /**
     * Returns the Kenney swatch name this primitive samples.
     *
     * <p>One of {@code floor}, {@code wall}, {@code accent}, {@code accentRed},
     * {@code accentOrange}, {@code crate}, {@code column}. The map is the
     * one the {@link ModelBuilder} passes in at generation time, so the
     * primitive does not have to know the swatch coordinates — only its
     * name.</p>
     *
     * @return the swatch name
     */
    String texture();

    /**
     * Appends this primitive's triangles to the open submesh of the builder.
     *
     * <p>Called by the generator after it has opened the submesh that
     * {@link #submesh()} names and the texture that {@link #texture()} names.
     * The primitive is responsible for handing back complete triangles — the
     * generator does not know or care what a box is.</p>
     *
     * @param builder the model under construction
     * @param textureIndex the texture's index in the model, already added by
     *     the generator, for use in {@code beginSubmesh}-equivalent calls
     *     (most primitives ignore this and rely on the submesh being open)
     */
    void addTo(ModelBuilder builder, int textureIndex);

    /**
     * Validates the primitive's internal state.
     *
     * <p>Called once, after construction and before generation, so a primitive
     * built from a malformed JSON object fails the build at config-parse time
     * rather than at vertex-append time. The {@link JsonConfigParser} runs
     * this; no other code should have to.</p>
     */
    void validate();
}
