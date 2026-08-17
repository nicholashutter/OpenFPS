/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import com.openfps.engine.demo.DemoEffects;
import com.openfps.engine.demo.DemoModels;
import com.openfps.engine.demo.LocalPlayerBody;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.BotPattern;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.PhysicsWorld;
import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * The windowed render path for a {@link MapSpec}: turns a spec into
 * a populated {@link Scene} the renderer can draw, plus the
 * per-tic state the {@code MapGameplayPort} drives against it.
 *
 * <p>{@code MapSpec.assets().level()} names the level model by path.
 * For the shipped maps that path points at a resource on the
 * classpath: {@code engine/src/main/resources/maps/<id>/level.ofm}.
 * This class resolves the path, reads the .ofm bytes, parses them
 * with {@link ModelFormat#read}, and assembles a multi-instance
 * {@link Scene} via {@link Scene#builder()}: the level .ofm plus
 * the Kenney kit (floor, ceiling, perimeter walls, columns, crates),
 * one bot character per spec waypoint, the local player's
 * first-person arms, the held viewmodel, and the effect pool.</p>
 *
 * <h2>The two build paths</h2>
 *
 * <p>{@link #build(MapSpec)} is the level-only path. It returns a
 * scene that holds just the level .ofm. This is the headless smoke
 * path and the fallback when no demo models were loaded.</p>
 *
 * <p>{@link #build(MapSpec, DemoModels)} is the full path. It
 * reads the level .ofm, reads the kit, and assembles a complete
 * scene with everything the windowed launcher needs: a room, the
 * bots, the player's arms, the held gun, and the tracer/smoke
 * effect pool. This is what {@code MapRuntime.loadMap} drives when
 * a map is picked from the menu.</p>
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

    // ---- Kit constants ----------------------------------------------------

    /**
     * World units per Kenney grid unit for the level kit — <b>64</b>.
     *
     * <p>Inherited from {@code DemoScene.KIT_WORLD_SCALE} and the
     * DOOM-derived player constants. The kit author on a 1-unit
     * grid and the player on a 41-unit eye / 16-unit radius collide
     * cleanly at exactly this ratio; the demo's Javadoc documents
     * the derivation. Maps use the same scale so the kit
     * composer and the demo can share the same {@code .ofm} files
     * without re-tuning.</p>
     */
    public static final float KIT_WORLD_SCALE = 64.0f;

    /**
     * Floor tile pitch in world units, equal to {@link #KIT_WORLD_SCALE}.
     * One Kenney grid unit is one tile, scaled.
     */
    public static final float KIT_TILE_UNITS = KIT_WORLD_SCALE;

    /**
     * Half the wall slab thickness in world units, <b>6.4</b>.
     *
     * <p>The wall model is 0.20 grid units thick, scaled by
     * {@link #KIT_WORLD_SCALE} = 64 gives a 12.8-unit slab, half
     * of which is 6.4. The kit composer places the wall's
     * <b>centre</b> on the perimeter; the inner face is 6.4 units
     * inward of that, which is the surface the collision hull
     * stops the player against.</p>
     */
    public static final float KIT_WALL_HALF_THICKNESS_UNITS = 6.4f;

    /**
     * Stacked wall courses, floor to ceiling — <b>3</b> for the
     * map mode (the demo uses 2; the map mode adds a third so the
     * windowed room reads taller and the middle course can host
     * the wall-window alternation).
     */
    public static final int KIT_WALL_COURSES = 3;

    /**
     * Height of the ceiling above the floor, in world units.
     * <b>192</b> ({@link #KIT_WALL_COURSES} * {@link #KIT_WORLD_SCALE}).
     */
    public static final float KIT_CEILING_UNITS = KIT_WALL_COURSES * KIT_WORLD_SCALE;

    /**
     * Quoted by the wall-window rule: which course of the three
     * is the one whose wall tiles alternate solid/window. The
     * middle course is the one the player can see through when
     * looking from a low spawn, and is the height a peeking
     * player would shoot through.
     */
    private static final int WINDOW_COURSE = 1;

    /**
     * A quarter turn in radians — the rotation the z-running wall
     * needs to run along x. Same number the demo uses.
     */
    private static final float QUARTER_TURN_RADIANS = (float) (StrictMath.PI * 0.5);

    /**
     * Returns half the playable area of the spec, in world units.
     * The inner-face inset of the perimeter walls; the kit
     * composer's walls land on ±{@code halfRoomOf(spec)}.
     *
     * <p>Static so the kit composer methods (which are all static
     * and all take a {@link MapSpec}) can use it without having
     * to thread the instance through.</p>
     */
    private static float halfRoomOf(final MapSpec spec)
    {
        return spec.dimensions().width() * 0.5f;
    }

    /**
     * The instance index meaning "no such instance was staged".
     * Mirrors {@code DemoScene.NO_INSTANCE}.
     */
    public static final int NO_INSTANCE = -1;

    // ---- Fields -----------------------------------------------------------

    private final MapSpec spec;
    private final Scene scene;
    private final int[] botIndices;
    private final int[] weaponIndices;
    private final DemoEffects effects;
    private final LocalPlayerBody body;
    private final PhysicsWorld levelPhysics;

    // Level-only ctor (smoke path / fallback).
    private MapScene(final MapSpec spec, final Scene scene)
    {
        this(spec, scene, new int[0], new int[0], null, null, null);
    }

    // Full ctor (desktop launcher menu pick path).
    private MapScene(final MapSpec spec, final Scene scene, final int[] botIndices,
        final int[] weaponIndices, final DemoEffects effects, final LocalPlayerBody body,
        final PhysicsWorld levelPhysics)
    {
        this.spec = spec;

        this.scene = scene;

        this.botIndices = botIndices;

        this.weaponIndices = weaponIndices;

        this.effects = effects;

        this.body = body;

        this.levelPhysics = levelPhysics;
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
     * <p>The 1-arg path is the headless smoke test path. The
     * windowed launcher uses the 2-arg overload so it gets the
     * kit, the bots, the arms, the viewmodel, and the effect pool
     * — everything a first-person match needs visible.</p>
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

        LOG.info("MapScene: {} built (level-only) {} ({} triangles, {} vertices, {} textures)",
            spec.id(), scene, model.indexCount() / 3, model.vertexCount(), model.textureCount());

        return new MapScene(spec, scene);
    }

    /**
     * Builds a populated scene from a spec and a loaded set of demo
     * models: the level .ofm, the Kenney kit (floor, ceiling, three-
     * course perimeter wall with a windowed middle course, four
     * columns, six perimeter crates), one bot character + carbine
     * per spec waypoint, the local player's first-person arms, the
     * held viewmodel, and the tracer / smoke / flash effect pool.
     *
     * <p>This is the path the desktop launcher's map pick drives.
     * Without it the windowed launcher drops the player on a
     * level with no walls, no arms, no viewmodel and no bots —
     * the controller is updating and the match is running, but
     * the scene has no visual feedback to confirm either.</p>
     *
     * <p>The bot patrol uses the spec's {@code botWaypoints()} (one
     * bot per waypoint, up to {@link Match#DEFAULT_BOT_COUNT}); the
     * spawn placement is the first spawn of the player's team from
     * the spec. The collision world combines the level .ofm's
     * submesh AABBs (via {@link PhysicsWorld.Builder#addFromModel})
     * with the kit's wall/column/crate boxes, so a bot or the
     * player cannot walk through something the renderer is
     * drawing.</p>
     *
     * @param spec the map spec; must not be null
     * @param models the loaded demo model set; must not be null
     * @return the built scene and its per-tic state, never null
     * @throws IllegalArgumentException if {@code spec} or {@code models} is null
     */
    public static MapScene build(final MapSpec spec, final DemoModels models)
    {
        if (spec == null)
        {
            throw new IllegalArgumentException("spec must not be null");
        }

        if (models == null)
        {
            throw new IllegalArgumentException("models must not be null — MapScene.build(spec, models)"
                + " is the populated scene path. Did you mean build(spec)?");
        }

        final byte[] bytes = readLevel(spec);

        final boolean missing = bytes == null;

        final ModelFormat level;

        if (missing)
        {
            level = null;
        }
        else
        {
            level = ModelFormat.read(bytes);
        }

        if (level == null)
        {
            LOG.warn("MapScene: {} has no readable level model at {} — assembling kit only."
                + " (Was the level.ofm committed? The 16 shipped maps are at"
                + " engine/src/main/resources/maps/<id>/level.ofm.)",
                spec.id(), spec.assets().level());
        }

        final Scene.Builder builder = Scene.builder();

        if (level != null)
        {
            builder.addWorldInstance(level, Mat4.identity());
        }

        // Kit + bots + arms + viewmodel + effect pool. The kit
        // composer is the suite of addXxx methods below; each is
        // a no-op if its piece of the model set is missing, so a
        // partially-staged pack (e.g. no characters, or no viewmodel)
        // still produces a valid scene.
        addLevelKit(builder, spec, models);

        final int[] botIndices = new int[spec.botWaypoints().size()];

        final int[] weaponIndices = new int[botIndices.length];

        addBotInstances(builder, spec, models, botIndices, weaponIndices);

        final LocalPlayerBody body = addLocalPlayer(builder, spec, models);

        final DemoEffects effects = DemoEffects.addTo(builder);

        final PhysicsWorld physics = buildPhysics(spec, models, level);

        final Scene scene = builder.build();

        final MapScene result = new MapScene(spec, scene, botIndices, weaponIndices, effects, body,
            physics);

        final int solidCount;

        if (physics == null)
        {
            solidCount = 0;
        }
        else
        {
            solidCount = physics.solidCount();
        }

        LOG.info("MapScene: {} built (full) {} — bots={}, weapons={}, body={}, effects={}, "
            + "physics={} solids",
            spec.id(), scene, botIndices.length, weaponIndices.length,
            body != null, effects != null, solidCount);

        return result;
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
     * Returns the scene-instance index of the {@code i}-th bot
     * character, or {@link #NO_INSTANCE} if that bot has no body
     * (e.g. the character pack was not staged). Parallel to the
     * spec's {@code botWaypoints()}.
     */
    public int botInstanceIndex(final int i)
    {
        if (i < 0 || i >= botIndices.length)
        {
            return NO_INSTANCE;
        }

        return botIndices[i];
    }

    /**
     * Returns the scene-instance index of the {@code i}-th bot's
     * carbine, or {@link #NO_INSTANCE} if the weapon was not
     * staged. Parallel to the spec's {@code botWaypoints()}.
     */
    public int botWeaponInstanceIndex(final int i)
    {
        if (i < 0 || i >= weaponIndices.length)
        {
            return NO_INSTANCE;
        }

        return weaponIndices[i];
    }

    /**
     * Returns the shared effect pool (tracers, smoke puffs, muzzle
     * flashes), or null when the scene was built via the
     * level-only path.
     */
    public DemoEffects effects()
    {
        return effects;
    }

    /**
     * Returns the local player body (first-person arms), or null
     * when the scene was built via the level-only path.
     */
    public LocalPlayerBody localBody()
    {
        return body;
    }

    /**
     * Returns the collision world for the map (kit walls, columns,
     * crates, plus the level .ofm's submesh AABBs), or null when
     * the scene was built via the level-only path.
     */
    public PhysicsWorld levelPhysics()
    {
        return levelPhysics;
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

    // ---- Kit composer -----------------------------------------------------

    /**
     * The kit a single map assembles: the floor and ceiling tiles, the
     * perimeter wall ring, the four columns, and six perimeter crates.
     * Each piece is added in order; each is a no-op if its model is
     * null (e.g. a partially-staged pack).
     */
    private static void addLevelKit(final Scene.Builder builder, final MapSpec spec,
        final DemoModels models)
    {
        addFloorAndCeiling(builder, spec, models);

        addPerimeterRing(builder, spec, models);

        addColumnsAndCrates(builder, spec, models);
    }

    /**
     * Tiles the spec's playable area with the floor model and the same
     * grid upside down at ceiling height.
     *
     * <p>Each tile is a flat quad at y = 0 (floor) or y =
     * {@link #KIT_CEILING_UNITS} (ceiling). The ceiling is what makes
     * the room an interior rather than a walled yard: without it the
     * frame is a quarter dark sky and the eye reads the walls as
     * free-standing.</p>
     *
     * <p>{@code ROOM_TILES_PER_SIDE} is derived from the spec's
     * {@code dimensions.width()} so a 320x320 spec gets 5 tiles per
     * side (320 / {@link #KIT_TILE_UNITS} = 5) and a future 192x192
     * spec gets 3.</p>
     */
    private static void addFloorAndCeiling(final Scene.Builder builder, final MapSpec spec,
        final DemoModels models)
    {
        final ModelFormat tile = models.floor();

        if (tile == null)
        {
            return;
        }

        final int tilesPerSide = (int) (spec.dimensions().width() / KIT_TILE_UNITS);

        for (int alongX = 0; alongX < tilesPerSide; alongX++)
        {
            for (int alongZ = 0; alongZ < tilesPerSide; alongZ++)
            {
                final float x = tileCentre(tilesPerSide, alongX);

                final float z = tileCentre(tilesPerSide, alongZ);

                builder.addWorldInstance(tile, placement(x, 0.0f, z, 0.0f, KIT_WORLD_SCALE));

                builder.addWorldInstance(tile, invertedPlacement(x, KIT_CEILING_UNITS, z,
                    KIT_WORLD_SCALE));
            }
        }
    }

    /**
     * Three stacked wall courses forming a closed perimeter at
     * {@code x = ±halfRoom} and {@code z = ±halfRoom}, with the middle
     * course alternating solid tiles and the windowed tile (when one
     * is staged). The first and last tile of every side is always solid
     * so corners seal.
     */
    private static void addPerimeterRing(final Scene.Builder builder, final MapSpec spec,
        final DemoModels models)
    {
        final ModelFormat wall = models.wall();

        if (wall == null)
        {
            return;
        }

        final ModelFormat windowed = models.wallWindow();

        final int tilesPerSide = (int) (spec.dimensions().width() / KIT_TILE_UNITS);

        final float half = halfRoomOf(spec);

        for (int course = 0; course < KIT_WALL_COURSES; course++)
        {
            final float y = course * KIT_WORLD_SCALE;

            for (int tile = 0; tile < tilesPerSide; tile++)
            {
                final float centre = tileCentre(tilesPerSide, tile);

                // The two walls at constant x: length along z, no rotation.
                addWallSolid(builder, wall, windowed, course, tile, tilesPerSide,
                    -half, y, centre, 0.0f);

                addWallSolid(builder, wall, windowed, course, tile, tilesPerSide,
                    half, y, centre, 0.0f);

                // The two walls at constant z: length along x, quarter turn.
                addWallSolid(builder, wall, windowed, course, tile, tilesPerSide,
                    centre, y, -half, QUARTER_TURN_RADIANS);

                addWallSolid(builder, wall, windowed, course, tile, tilesPerSide,
                    centre, y, half, QUARTER_TURN_RADIANS);
            }
        }

        // The four corner cells where two plain wall tiles butt against
        // each other: a single 90-degree corner mesh, when staged,
        // covers the joint so the corner reads as one piece rather
        // than two. The plain wall tiles below stay in place so the
        // collision hull is unchanged; the corner is purely visual.
        final ModelFormat corner = models.wallCorner();

        if (corner != null)
        {
            for (int course = 0; course < KIT_WALL_COURSES; course++)
            {
                final float y = course * KIT_WORLD_SCALE;

                builder.addWorldInstance(corner, placement(-half, y, -half, 0.0f, KIT_WORLD_SCALE));

                builder.addWorldInstance(corner, placement(half, y, -half, 0.0f, KIT_WORLD_SCALE));

                builder.addWorldInstance(corner, placement(-half, y, half, 0.0f, KIT_WORLD_SCALE));

                builder.addWorldInstance(corner, placement(half, y, half, 0.0f, KIT_WORLD_SCALE));
            }
        }
    }

    /**
     * Stages one wall tile. Replaces solid with the windowed tile on
     * the middle course for alternating tiles; first and last tile of
     * every side are always solid so corners seal.
     */
    private static void addWallSolid(final Scene.Builder builder, final ModelFormat wall,
        final ModelFormat windowed, final int course, final int tile, final int tilesPerSide,
        final float x, final float y, final float z, final float yawRadians)
    {
        final boolean isWindow = course == WINDOW_COURSE
            && windowed != null
            && (tile & 1) == 1
            && tile != 0
            && tile != tilesPerSide - 1;

        final ModelFormat piece;

        if (isWindow)
        {
            piece = windowed;
        }
        else
        {
            piece = wall;
        }

        builder.addWorldInstance(piece, placement(x, y, z, yawRadians, KIT_WORLD_SCALE));
    }

    /**
     * Four corner columns and six perimeter crates — enough props
     * for the camera to find depth in an otherwise empty box.
     *
     * <p>Evenly-spaced props would give a moving camera a
     * repeating pattern that is genuinely hard to read motion
     * against, so the crate placements are deliberately irregular,
     * as the demo's {@code CRATE_PLACEMENTS} are. The four
     * columns are placed at 0.4 of the half-room on both axes
     * (matching the demo) so the bots' central patrol area is
     * clear.</p>
     */
    private static void addColumnsAndCrates(final Scene.Builder builder, final MapSpec spec,
        final DemoModels models)
    {
        final ModelFormat column = models.column();

        final ModelFormat crate = models.crate();

        if (column == null && crate == null)
        {
            return;
        }

        final float half = halfRoomOf(spec);

        final float columnOffset = half * 0.4f;

        if (column != null)
        {
            builder.addWorldInstance(column,
                placement(-columnOffset, 0.0f, -columnOffset, 0.0f, KIT_WORLD_SCALE));

            builder.addWorldInstance(column,
                placement(columnOffset, 0.0f, -columnOffset, 0.0f, KIT_WORLD_SCALE));

            builder.addWorldInstance(column,
                placement(-columnOffset, 0.0f, columnOffset, 0.0f, KIT_WORLD_SCALE));

            builder.addWorldInstance(column,
                placement(columnOffset, 0.0f, columnOffset, 0.0f, KIT_WORLD_SCALE));
        }

        if (crate == null)
        {
            return;
        }

        final ModelFormat crateColor = models.crateColor();

        final float[] placements = cratePlacements(half);

        // The third placement in cratePlacements is the base of a
        // stacked pair. When the optional coloured crate is staged,
        // it takes the base slot so the stack reads as a two-tone
        // stack rather than two brown boxes; a missing coloured
        // crate falls back to the plain one and the pair is uniform.
        final int colorSlot = 2;

        for (int i = 0; i < placements.length; i += 3)
        {
            final int placementIndex = i / 3;

            final boolean useColor = placementIndex == colorSlot && crateColor != null;

            final ModelFormat useCrate;

            if (useColor)
            {
                useCrate = crateColor;
            }
            else
            {
                useCrate = crate;
            }

            builder.addWorldInstance(useCrate,
                placement(placements[i], placements[i + 1], placements[i + 2], 0.0f,
                    KIT_WORLD_SCALE));
        }
    }

    /**
     * Six crate placements in playable-area-relative units, scaled
     * to the spec's dimensions so the same irregular layout fits a
     * 320x320 room and a future 192x192 room.
     *
     * <p>Deliberately irregular (see {@code DemoScene.CRATE_PLACEMENTS}
     * for the reason). The fourth entry sits on top of the third as
     * a stacked crate pair — the cheapest way to prove the depth
     * buffer is sorting vertically as well as horizontally.</p>
     */
    private static float[] cratePlacements(final float half)
    {
        final float s = half / 320.0f;

        return new float[]
        {
            -192.0f * s,  0.0f,    0.0f,
             160.0f * s,  0.0f,  -96.0f * s,
            -128.0f * s,  0.0f,   64.0f * s,
            -128.0f * s, 32.0f,   64.0f * s,
              96.0f * s,  0.0f,  192.0f * s,
             -64.0f * s,  0.0f, -224.0f * s,
        };
    }

    /**
     * Stages one bot character + carbine per spec waypoint, up to
     * {@link Match#DEFAULT_BOT_COUNT}. Mirrors the demo's
     * {@code addBots} but reads the placement from the spec rather
     * than from a hard-coded route.
     *
     * <p>A missing character pack leaves the bot instances at
     * {@link #NO_INSTANCE}; a missing carbine does the same for
     * the weapon index. The {@code MapGameplayPort} reads the
     * indices and skips publishing transforms for any that are
     * {@link #NO_INSTANCE}, so a partially-staged pack produces a
     * valid scene that simply shows fewer bot bodies.</p>
     */
    private static void addBotInstances(final Scene.Builder builder, final MapSpec spec,
        final DemoModels models, final int[] botIndices, final int[] weaponIndices)
    {
        if (!models.hasCharacters())
        {
            return;
        }

        final ModelFormat[] people = models.characters();

        final ModelFormat blaster = models.botWeapon();

        final int count = Math.min(spec.botWaypoints().size(), botIndices.length);

        for (int index = 0; index < count; index++)
        {
            final Waypoint wp = spec.botWaypoints().get(index);

            final int entityId = Match.FIRST_BOT_ENTITY_ID + index;

            final Bot bot = new Bot(entityId, wp.x(), 0.0f, wp.z(), BotPattern.SENTRY, 0.0f, 60,
                index);

            final ModelFormat character = people[index % people.length];

            botIndices[index] = builder.worldInstanceCount();

            builder.addWorldInstance(character, botPlacement(bot), entityId);

            weaponIndices[index] = addBotWeapon(builder, blaster, bot);
        }
    }

    /**
     * Adds the bot's carbine as a separate world instance. Returns
     * {@link #NO_INSTANCE} if no blaster was staged, so the caller
     * can leave the weapon index at the sentinel.
     */
    private static int addBotWeapon(final Scene.Builder builder, final ModelFormat blaster,
        final Bot bot)
    {
        if (blaster == null)
        {
            return NO_INSTANCE;
        }

        final int at = builder.worldInstanceCount();

        builder.addWorldInstance(blaster, botWeaponPlacement(bot), bot.entityId());

        return at;
    }

    /**
     * Stages the local player's first-person arms and the held
     * viewmodel. Both are present in the demo and live at a
     * constant view-space offset from the eye; the per-tic port
     * publishes their transforms every tick.
     *
     * <p>The arms are added as a world instance (depth-tested
     * against walls so a held arm does not poke through a wall
     * the player is standing against). The viewmodel is added as
     * a view instance (depth-cleared so it always draws over the
     * world) at the {@code DemoScene.WEAPON_VIEW_*} constant
     * placement. A missing viewmodel is a logged warning, not an
     * error — the bot weapon below it covers the player has
     * nothing to shoot with either way.</p>
     */
    private static LocalPlayerBody addLocalPlayer(final Scene.Builder builder, final MapSpec spec,
        final DemoModels models)
    {
        final LocalPlayerBody body = LocalPlayerBody.addTo(builder);

        if (models.weapon() != null)
        {
            // View instance 0 — the demo's effect pool only adds world
            // instances, so the only view instance in the scene is the
            // viewmodel. The render port reads it at fixed index 0.
            builder.addViewInstance(models.weapon(), com.openfps.engine.demo.DemoScene.weaponTransform());

            LOG.info("MapScene: viewmodel placed at view instance 0");
        }
        else
        {
            LOG.warn("MapScene: no viewmodel staged for {} — the player will have no held gun."
                + " (Did you regenerate DemoModels with the Blaster Kit? See"
                + " DemoModels.REGENERATE_COMMAND.)", spec.id());
        }

        return body;
    }

    /**
     * Builds the collision world: the level .ofm's submesh AABBs
     * (via {@link PhysicsWorld.Builder#addFromModel}) plus the kit's
     * wall/column/crate boxes. Returns an open world when the
     * level is missing.
     */
    private static PhysicsWorld buildPhysics(final MapSpec spec, final DemoModels models,
        final ModelFormat level)
    {
        final PhysicsWorld.Builder builder = PhysicsWorld.builder(PhysicsWorld.PLAYER_HALF_WIDTH_UNITS);

        if (level != null)
        {
            builder.addFromModel(level);
        }

        addKitCollision(builder, spec);

        return builder.build();
    }

    /**
     * The kit's solid boxes: four perimeter walls (depth
     * {@code 2 * wallHalfThick} into the playable area), four
     * columns at 0.4 of the half-room on both axes. Crates are
     * intentionally <b>not</b> added — the demo's collision
     * doesn't have them either, and a 32-unit crate at y=0 is
     * a body the player's eye sees but the foot never meets.
     */
    private static void addKitCollision(final PhysicsWorld.Builder builder, final MapSpec spec)
    {
        final float half = halfRoomOf(spec);

        final float wallThick = 2.0f * KIT_WALL_HALF_THICKNESS_UNITS;

        final float colHalf = KIT_WORLD_SCALE * 0.5f * 0.20f;

        // North wall (z = -half)
        builder.addBox(-half, -half - wallThick, half, -half);

        // South wall (z = +half)
        builder.addBox(-half, half, half, half + wallThick);

        // West wall (x = -half)
        builder.addBox(-half - wallThick, -half, -half, half);

        // East wall (x = +half)
        builder.addBox(half, -half, half + wallThick, half);

        // Four columns at 0.4 of half
        final float colOff = half * 0.4f;

        builder.addBoxAt(-colOff, -colOff, colHalf, colHalf);

        builder.addBoxAt(colOff, -colOff, colHalf, colHalf);

        builder.addBoxAt(-colOff, colOff, colHalf, colHalf);

        builder.addBoxAt(colOff, colOff, colHalf, colHalf);
    }

    /**
     * Centre of tile {@code index} in a row of {@code tilesPerSide} tiles,
     * centred on the world origin. Tile 0's centre is at
     * {@code -(tilesPerSide-1) * KIT_TILE_UNITS / 2} and tile
     * {@code tilesPerSide-1}'s is at the symmetric positive value,
     * so an odd tilesPerSide puts the middle tile on the origin
     * and an even one puts the centre between two tiles. Matches
     * the demo's tile-centre math.
     */
    private static float tileCentre(final int tilesPerSide, final int index)
    {
        return (index - (tilesPerSide - 1) * 0.5f) * KIT_TILE_UNITS;
    }

    /**
     * Returns the model-to-world transform for a model placed at
     * {@code (x, y, z)} facing {@code yawRadians}, scaled by
     * {@code scale}. The Kenney grid model is 1x1x1; a 64-unit
     * world tile is therefore a scale-64 placement.
     */
    private static Mat4 placement(final float x, final float y, final float z,
        final float yawRadians, final float scale)
    {
        return com.openfps.engine.demo.DemoScene.placement(x, y, z, yawRadians, scale);
    }

    /**
     * Returns the model-to-world transform for a model placed at
     * {@code (x, y, z)} flipped upside down (for the ceiling tile
     * stack). Same shape as {@link #placement} with a yaw that
     * inverts the Y axis.
     */
    private static Mat4 invertedPlacement(final float x, final float y, final float z,
        final float scale)
    {
        return com.openfps.engine.demo.DemoScene.invertedPlacement(x, y, z, scale);
    }

    /**
     * Returns the model-to-world transform for a bot at its current
     * position. Mirrors {@code DemoScene.botPlacement(bot)}.
     */
    private static Mat4 botPlacement(final Bot bot)
    {
        return com.openfps.engine.demo.DemoScene.botPlacement(bot);
    }

    /**
     * Returns the model-to-world transform for a bot's carbine at
     * the bot's current position. Mirrors
     * {@code DemoScene.botWeaponPlacement(bot)}.
     */
    private static Mat4 botWeaponPlacement(final Bot bot)
    {
        return com.openfps.engine.demo.DemoScene.botWeaponPlacement(bot);
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof MapScene otherScene))
        {
            return false;
        }

        // MapSpec's equals is by id, so two MapScenes for the same
        // spec are equal. The scene is a function of the spec.
        return spec.equals(otherScene.spec);
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
