/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * The windowed render path for a {@link MapSpec}: turns a spec's
 * level .ofm into a {@link Scene} the renderer can draw.
 *
 * <p>{@code MapSpec.assets().level()} names the level model by path.
 * For the shipped maps that path points at a resource on the
 * classpath: {@code engine/src/main/resources/maps/<id>/level.ofm}.
 * This class resolves the path, reads the .ofm bytes, parses them
 * with {@link ModelFormat#read}, and assembles a one-instance
 * {@link Scene} via {@link Scene#builder()}.</p>
 *
 * <h2>What it is and what it is not</h2>
 *
 * <p>What it is: the smallest thing that closes the loop between a
 * spec and a rendered frame. One world instance, identity transform,
 * no view-model — a starting point for the windowed launcher to
 * draw a map in a window.</p>
 *
 * <p>What it is not: a full {@code DemoScene} equivalent. The
 * first-person demo holds the room, the props, the spawn placement,
 * the bot patrol, the weapon, the held view-model and the
 * gameplay port. {@code MapScene} holds only the level geometry,
 * because the rest (spawn placement, mode markers, the bots' own
 * route) is the job of the map's gameplay port. The wiring on
 * top of {@code MapScene} — the per-tic simulation that reads
 * the spec — is the work a future pass will add when the
 * windowed path is fleshed out. For now the load-bearing claim
 * is that {@code --map=cornerstone} on the windowed launcher
 * can construct and present a scene rather than failing
 * silently with the legacy demo room.</p>
 *
 * <h2>Path resolution</h2>
 *
 * <p>The path the spec carries can be either an absolute filesystem
 * path (the form the build tool writes) or a classpath-relative
 * path (the form the runtime actually finds the file at). The two
 * are not the same string — the build-time path is
 * {@code engine/src/main/resources/maps/<id>/level.ofm}, the
 * classpath-relative path is {@code maps/<id>/level.ofm}. The
 * resource lookup is tried first; the filesystem lookup is the
 * fallback. A path the classpath knows about always wins.</p>
 *
 * <h2>Threading and lifetime</h2>
 *
 * <p>Built once, on the main thread, before the frame loop starts.
 * The returned scene is immutable; rendering it allocates nothing.
 * Calling {@link #build(MapSpec)} twice with the same spec is
 * safe; the two scenes are independent.</p>
 */
public final class MapScene
{
    private static final Logger LOG = LoggerFactory.getLogger(MapScene.class);

    /**
     * Classpath prefix the build tool writes its .ofm files under.
     * The runtime resource path drops the
     * {@code engine/src/main/resources/} part because that prefix
     * is the classpath root.
     */
    private static final String BUILD_PATH_PREFIX = "engine/src/main/resources/";

    /**
     * Substring of the spec's level path that, when present, says
     * "the .ofm is a runtime resource, not a build-time file".
     * Used to decide which lookup to try first.
     */
    private static final String RESOURCE_HINT = "src/main/resources";

    /**
     * Prefix the older specs use for the resource path. The
     * shipped cornerstone map records its level at
     * {@code assets/maps/cornerstone/level.ofm} (the form the
     * AssetStage / GltfConverter pipeline produces), which is
     * also on the classpath.
     */
    private static final String ASSETS_PREFIX = "assets/";

    private final MapSpec spec;
    private final Scene scene;

    private MapScene(final MapSpec spec, final Scene scene)
    {
        this.spec = spec;
        this.scene = scene;
    }

    /**
     * Builds a scene from the spec's level .ofm, reading the bytes
     * through the classpath or filesystem as appropriate.
     *
     * <p>A spec with a missing or unreadable level path is logged
     * at WARN and returns a {@link Scene#EMPTY}, so the launcher's
     * "the window must not crash" invariant is preserved. A
     * malformed .ofm (one that exists but does not parse) is
     * logged at ERROR and re-thrown — a corrupt model is not a
     * missing one, and silently substituting an empty scene for
     * a corrupt asset would hide the corruption.</p>
     *
     * @param spec the map spec to build a scene for; must not be null
     * @return the built scene, never null
     * @throws IllegalArgumentException if {@code spec} is null
     * @throws RuntimeException if the .ofm file exists but does not parse
     */
    public static MapScene build(final MapSpec spec)
    {
        if (spec == null)
        {
            throw new IllegalArgumentException("spec must not be null");
        }
        final byte[] bytes = readLevel(spec);
        if (bytes == null)
        {
            LOG.warn("MapScene: {} has no readable level model at {} — presenting an empty scene."
                + " (Was the level.ofm committed? The four shipped maps are at"
                + " engine/src/main/resources/maps/<id>/level.ofm.)",
                spec.id(), spec.assets().level());
            return new MapScene(spec, Scene.EMPTY);
        }
        final ModelFormat model = ModelFormat.read(bytes);
        final Scene scene = Scene.builder()
            .addWorldInstance(model, com.openfps.engine.render.adapter.Mat4.identity())
            .build();
        LOG.info("MapScene: {} built {} ({} triangles, {} vertices, {} textures)",
            spec.id(), scene, model.indexCount() / 3, model.vertexCount(), model.textureCount());
        return new MapScene(spec, scene);
    }

    /** Returns the spec this scene was built from. Never null. */
    public MapSpec spec()
    {
        return spec;
    }

    /** Returns the rendered scene. Never null. */
    public Scene scene()
    {
        return scene;
    }

    /**
     * Reads the spec's level .ofm from the classpath or filesystem,
     * returning null when neither has it.
     *
     * <p>The classpath lookup is preferred: the build tool writes
     * .ofm files into {@code engine/src/main/resources/maps/<id>/},
     * which is on the runtime classpath at
     * {@code maps/<id>/level.ofm}. The spec's stored path is the
     * build-time form, so the {@link #BUILD_PATH_PREFIX} is stripped
     * before the resource lookup.</p>
     *
     * <p>If the spec's path does not look like a resource path
     * (i.e. does not contain {@link #RESOURCE_HINT}), the
     * classpath lookup is skipped — there's nothing to find there —
     * and the filesystem lookup runs against the path verbatim.</p>
     *
     * @param spec the spec whose level path is read
     * @return the .ofm bytes, or null if neither path resolves
     */
    private static byte[] readLevel(final MapSpec spec)
    {
        final String path = spec.assets().level();
        if (path == null || path.isBlank())
        {
            return null;
        }
        // Try the classpath first, in two forms: the spec's
        // resource path verbatim, and (when the spec carries the
        // build-time prefix) the classpath-relative path. The
        // older cornerstone spec uses {@code assets/maps/...} as
        // its resource path; the Pass 2 specs use
        // {@code engine/src/main/resources/maps/...} and have to
        // be stripped to {@code maps/...} before the classpath
        // lookup will find them.
        final byte[] verbatim = readFromClasspath(path);
        if (verbatim != null)
        {
            return verbatim;
        }
        if (path.contains(RESOURCE_HINT))
        {
            final String resourcePath = stripBuildPrefix(path);
            final byte[] fromClasspath = readFromClasspath(resourcePath);
            if (fromClasspath != null)
            {
                return fromClasspath;
            }
        }
        // The classpath lookup is the load-bearing one — every
        // shipped map's level .ofm is committed at
        // engine/src/main/resources/maps/<id>/, which is on the
        // runtime classpath. Fall back to the filesystem only
        // when the classpath is empty, which is what an external
        // build that wrote the .ofm to a different path would
        // produce.
        return readFromFilesystem(path);
    }

    /**
     * Strips the {@link #BUILD_PATH_PREFIX} from a build-time path
     * to produce a classpath-relative path. Returns the input
     * unchanged if the prefix is not present.
     *
     * @param path the build-time path
     * @return the classpath-relative path
     */
    private static String stripBuildPrefix(final String path)
    {
        final int index = path.indexOf(BUILD_PATH_PREFIX);
        if (index < 0)
        {
            return path;
        }
        return path.substring(index + BUILD_PATH_PREFIX.length());
    }

    /**
     * Reads a resource from the classpath. Returns null on any
     * failure, because a missing resource is the expected
     * outcome for a non-shipped spec and is not a build error.
     *
     * @param resourcePath the classpath-relative path
     * @return the bytes, or null if not found
     */
    private static byte[] readFromClasspath(final String resourcePath)
    {
        // Classloader.getResourceAsStream is the canonical lookup;
        // it returns null on a missing resource, which is the
        // "not found" signal we want.
        final ClassLoader loader = MapScene.class.getClassLoader();
        if (loader == null)
        {
            return null;
        }
        final String normalized;
        if (resourcePath.startsWith("/"))
        {
            normalized = resourcePath.substring(1);
        }
        else
        {
            normalized = resourcePath;
        }
        try (InputStream stream = loader.getResourceAsStream(normalized))
        {
            if (stream == null)
            {
                return null;
            }
            return stream.readAllBytes();
        }
        catch (final IOException e)
        {
            LOG.warn("MapScene: failed to read classpath resource {}: {}", normalized,
                e.getMessage());
            return null;
        }
    }

    /**
     * Reads a file from the filesystem. Returns null on any
     * failure, for the same reason {@link #readFromClasspath} does.
     *
     * @param path the filesystem path
     * @return the bytes, or null if not readable
     */
    private static byte[] readFromFilesystem(final String path)
    {
        final java.nio.file.Path file = java.nio.file.Path.of(path);
        if (!java.nio.file.Files.isRegularFile(file))
        {
            return null;
        }
        try
        {
            return java.nio.file.Files.readAllBytes(file);
        }
        catch (final IOException e)
        {
            LOG.warn("MapScene: failed to read {}: {}", path, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof MapScene))
        {
            return false;
        }
        // MapSpec's equals is by id, so two MapScenes for the same
        // spec are equal. The scene is a function of the spec.
        return spec.equals(((MapScene) other).spec);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(spec);
    }

    @Override
    public String toString()
    {
        return "MapScene{spec=" + spec.id() + ", scene=" + scene + "}";
    }
}
