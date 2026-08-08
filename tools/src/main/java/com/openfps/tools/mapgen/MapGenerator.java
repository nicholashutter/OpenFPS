/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.mapgen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openfps.engine.render.adapter.Rgba;
import com.openfps.tools.model.KenneyTexture;
import com.openfps.tools.model.MipGenerator;
import com.openfps.tools.model.ModelBuilder;

/**
 * Walks a {@link MapGenConfig} and produces the matching {@code .ofm} bytes.
 *
 * <h2>The flow</h2>
 *
 * <ol>
 *   <li>Group primitives by {@code (submesh, texture)} pair. Each pair gets
 *       one submesh in the output model. The grouping order is the
 *       submesh's order in the config, then the primitive's order within
 *       the submesh — so a config that interleaves two textures in one
 *       submesh produces one submesh per texture, with the geometry in
 *       config order.</li>
 *   <li>For each unique swatch name encountered, either load it from the
 *       Kenney atlas (if {@code atlasPath} is non-null) or synthesise a
 *       flat-colour tile from the swatch's name. The synthesised tile is
 *       the one a clone without the Kenney pack staged still ships.</li>
 *   <li>Open the submesh for each group, call every primitive's
 *       {@link Primitive#addTo} in order, and close the submesh. The
 *       generator owns the submesh lifecycle; primitives just append.</li>
 *   <li>Serialise the model and return the bytes.</li>
 * </ol>
 *
 * <h2>Allocation</h2>
 *
 * <p>The generator itself allocates one {@code LinkedHashMap} for the
 * submesh groups and one {@code HashMap} for the swatch cache. Those are
 * O(primitives) and O(unique swatches) respectively, and they are paid
 * once per map, not per primitive. No per-primitive allocation happens
 * inside the loop beyond the {@code ModelBuilder}'s growable arrays,
 * which is the same allocation profile the existing hand-written
 * builders have.</p>
 */
public final class MapGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(MapGenerator.class);

    private final Path atlasPath;
    private final PrimitiveFactory factory;

    /**
     * Creates a generator that uses the named atlas (when non-null) and the
     * given primitive factory.
     *
     * @param atlasPath the path to the Kenney Prototype Kit colormap.png,
     *     or null to use a procedural fallback per swatch
     * @param factory the primitive factory to look types and swatches up
     *     in; must not be null
     */
    public MapGenerator(final Path atlasPath, final PrimitiveFactory factory)
    {
        this.atlasPath = atlasPath;
        this.factory = factory;
    }

    /**
     * Produces the {@code .ofm} bytes for the given config.
     *
     * @param config the parsed config; must not be null
     * @return the file image, ready to write to disk and to hand to
     *     {@link com.openfps.engine.render.adapter.ModelFormat#read}
     */
    public byte[] generate(final MapGenConfig config)
    {
        final ModelBuilder builder = new ModelBuilder(config.id() + "-level");
        final int textureEdge = config.textureEdge();
        final Map<String, Integer> textureIndices = new LinkedHashMap<>();
        final Map<String, int[][]> mipCache = new HashMap<>();
        // First pass: group primitives by (submesh, texture) pair, preserving
        // config order. A LinkedHashMap with a composite key keeps the
        // submeshes in the order they were first declared.
        final Map<String, List<Primitive>> submeshGroups = new LinkedHashMap<>();
        for (final Primitive primitive : config.primitives())
        {
            final String key = primitive.submesh() + ":" + primitive.texture();
            final List<Primitive> group = submeshGroups.computeIfAbsent(key, k -> new ArrayList<>());
            group.add(primitive);
        }
        // Second pass: open each submesh, add its primitives, close it.
        for (final Map.Entry<String, List<Primitive>> entry : submeshGroups.entrySet())
        {
            final List<Primitive> group = entry.getValue();
            final Primitive first = group.get(0);
            final String swatchName = first.texture();
            // Re-use an already-loaded texture when two submeshes sample the
            // same swatch. The cached mip chain is byte-identical and the
            // model dedupes by content only when the writer checks, which
            // it does not — so two submeshes with the same swatch would emit
            // two texture records holding the same texels. Caching avoids
            // the duplicate and keeps the file smaller.
            int textureIndex;
            if (textureIndices.containsKey(swatchName))
            {
                textureIndex = textureIndices.get(swatchName);
            }
            else
            {
                final int[][] levels = loadMips(swatchName, textureEdge, mipCache);
                textureIndex = builder.addTexture(swatchName, textureEdge, textureEdge, levels);
                textureIndices.put(swatchName, textureIndex);
            }
            builder.beginSubmesh(textureIndex);
            for (final Primitive primitive : group)
            {
                // The cached mip chain is reused by index; the second
                // parameter is a no-op for primitives that append to the
                // open submesh and a sanity check for those that do not.
                primitive.addTo(builder, textureIndex);
            }
            builder.endSubmesh();
        }
        final byte[] bytes = builder.toBytes();
        LOG.info("Generated {}: {} primitives, {} submeshes, {} textures",
            config.id(), config.primitives().size(), submeshGroups.size(), textureIndices.size());
        return bytes;
    }

    // Loads the mip chain for a swatch, either from the Kenney atlas or from
    // a procedural fallback. The cache key is the swatch name; the mip chain
    // is built once per generator run and shared by every submesh that names
    // the same swatch.
    private int[][] loadMips(final String swatchName, final int textureEdge,
        final Map<String, int[][]> mipCache)
    {
        final int[][] cached = mipCache.get(swatchName);
        if (cached != null)
        {
            return cached;
        }
        final int[] level0;
        final KenneySwatch swatch = factory.swatch(swatchName);
        if (swatch == null)
        {
            throw new IllegalStateException("unknown swatch: " + swatchName
                + " (registered: " + factory.swatchNames() + ")");
        }
        if (atlasPath != null)
        {
            final int[] texels = swatch.load(atlasPath);
            level0 = KenneyTexture.forceOpaque(texels);
        }
        else
        {
            level0 = proceduralTile(swatchName, textureEdge);
        }
        final int[][] levels = MipGenerator.generate(textureEdge, textureEdge, level0);
        mipCache.put(swatchName, levels);
        return levels;
    }

    // A solid-colour tile for the no-atlas case. Distinct colours per swatch
    // make a no-atlas build look at least as intended for tests and clones
    // without the Kenney pack staged.
    private static int[] proceduralTile(final String swatchName, final int textureEdge)
    {
        int colour;
        if (KenneySwatch.NAME_FLOOR.equals(swatchName))
        {
            colour = Rgba.pack(82, 84, 88, 255);
        }
        else if (KenneySwatch.NAME_WALL.equals(swatchName))
        {
            colour = Rgba.pack(64, 66, 70, 255);
        }
        else if (KenneySwatch.NAME_CRATE.equals(swatchName))
        {
            colour = Rgba.pack(36, 52, 84, 255);
        }
        else if (KenneySwatch.NAME_COLUMN.equals(swatchName))
        {
            colour = Rgba.pack(28, 48, 92, 255);
        }
        else if (KenneySwatch.NAME_ACCENT_RED.equals(swatchName))
        {
            colour = Rgba.pack(204, 48, 48, 255);
        }
        else if (KenneySwatch.NAME_ACCENT_ORANGE.equals(swatchName))
        {
            colour = Rgba.pack(220, 132, 36, 255);
        }
        else
        {
            colour = Rgba.pack(196, 60, 124, 255);
        }
        final int[] texels = new int[textureEdge * textureEdge];
        for (int i = 0; i < texels.length; i++)
        {
            texels[i] = colour;
        }
        return texels;
    }
}
