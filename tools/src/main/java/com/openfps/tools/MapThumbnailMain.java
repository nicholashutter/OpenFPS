/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.gameplay.map.MapLibrary;
import com.openfps.engine.gameplay.map.MapSetting;
import com.openfps.engine.gameplay.map.MapSpec;
import com.openfps.engine.hal.adapter.desktop.DesktopTimePort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.render.adapter.Camera;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.engine.render.adapter.Vec3;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders one {@code thumbnail.png} per map under a maps directory, plus a
 * {@code meta.json} sidecar with the display name, setting, mode, and a
 * generated caption. Headless &mdash; no window, no GL.
 *
 * Build-time only &mdash; this class is never on a runtime classpath.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The map browser in {@code gdxshared} shows a thumbnail per map
 * ({@code MapSelectionScreen.Entry.thumbnail}); the data on disk is just a
 * {@code .ofm} model. Rendering one 320x180 PNG per map ahead of time and
 * committing it alongside the {@code .ofm} keeps the menu fast (a texture
 * load, not a render) and keeps the rasterizer out of the menu's hot path.
 * The same approach the demo's load screen takes.</p>
 *
 * <p>The output path is deliberately the same directory as the {@code .ofm}:
 * the thumbnail is part of the map's committed payload (committed via
 * {@code git add -f} like the {@code .ofm} itself), so a fresh clone that
 * wants the visual menu needs the assets in the same place the engine
 * reads the model from.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   gradlew :tools:renderMapThumbnails
 *
 *   -PmapsDir=&lt;path&gt;     override the maps directory
 *                          (default: engine/src/main/resources/maps)
 *   -Pwidth=N              thumbnail width  (default 320)
 *   -Pheight=N             thumbnail height (default 180)
 *   -Pthreads=N            worker threads; 0 (default) runs serially
 *   -Ponly=id1,id2,...     render only these map ids; default = every map
 * </pre>
 *
 * <p>Idempotent and re-runnable. Re-running overwrites existing
 * {@code thumbnail.png} and {@code meta.json} &mdash; a meta sidecar that was
 * hand-edited is preserved if its caption is non-empty, on the principle that
 * a human edit is more local to the project than the tool's auto-generated
 * default. Use {@code git checkout} to recover the auto-generated copy if
 * the hand edit goes wrong.</p>
 */
public final class MapThumbnailMain
{
    /** Default frame width when {@code -Pwidth} is not given. */
    public static final int DEFAULT_WIDTH = 320;

    /** Default frame height when {@code -Pheight} is not given. */
    public static final int DEFAULT_HEIGHT = 180;

    /** Default maps directory when {@code -PmapsDir} is not given. */
    public static final String DEFAULT_MAPS_DIR = "engine/src/main/resources/maps";

    /** Name of the model file inside each map directory. */
    public static final String MODEL_FILE = "level.ofm";

    /** Name of the PNG written alongside the model. */
    public static final String THUMBNAIL_FILE = "thumbnail.png";

    /** Name of the JSON sidecar written alongside the model. */
    public static final String META_FILE = "meta.json";

    /**
     * Pitch of the camera below horizontal, in degrees. 45deg gives a
     * 3/4 "isometric" view that shows the lanes, the corners and the
     * vertical play all at once; pure top-down (90deg) flattens the
     * vertical dimension and the result looks like a satellite photo
     * rather than a map preview.
     */
    public static final float PITCH_DEGREES = 45.0f;

    /**
     * Multiplier from map extent to camera distance. 1.4 leaves a little
     * breathing room around the map edge in the rendered frame; below 1.0
     * the map fills the frame and the corners clip.
     */
    public static final float DISTANCE_FACTOR = 1.4f;

    private static final Logger LOG = LoggerFactory.getLogger(MapThumbnailMain.class);

    private MapThumbnailMain()
    {
        // entry point holder
    }

    /**
     * Renders thumbnails for every map under the given directory.
     *
     * @param args the program arguments (currently unused; config is via -P
     *             Gradle properties on the task)
     * @throws IOException if a model cannot be read or a PNG cannot be written
     */
    public static void main(final String[] args) throws IOException
    {
        final String mapsDir = System.getProperty("mapsDir", DEFAULT_MAPS_DIR);

        final int width = intProperty("width", DEFAULT_WIDTH);

        final int height = intProperty("height", DEFAULT_HEIGHT);

        final int threads = intProperty("threads", 0);

        final String only = System.getProperty("only", "");

        final ToolPool pool = ToolPool.open(threads);

        try
        {
            final Path root = Path.of(mapsDir);

            if (!Files.isDirectory(root))
            {
                LOG.error("Maps directory not found: {}", root.toAbsolutePath());

                System.exit(2);

                return;
            }

            int rendered = 0;
            int skipped = 0;
            int failed = 0;

            final java.util.List<Path> mapDirs = new java.util.ArrayList<>();

            try (var stream = Files.list(root))
            {
                stream.filter(Files::isDirectory)
                    .sorted()
                    .forEach(mapDirs::add);
            }

            for (final Path mapDir : mapDirs)
            {
                final String id = mapDir.getFileName().toString();

                if (!only.isEmpty() && !only.contains(id))
                {
                    skipped++;

                    continue;
                }

                final Path modelPath = mapDir.resolve(MODEL_FILE);

                if (!Files.isRegularFile(modelPath))
                {
                    LOG.warn("Skipping {}: no {} found", id, MODEL_FILE);

                    skipped++;

                    continue;
                }

                try
                {
                    renderOne(id, mapDir, modelPath, width, height, pool.port());

                    rendered++;
                }
                catch (final Exception e)
                {
                    LOG.error("Failed to render thumbnail for {}: {}", id, e.getMessage());

                    failed++;
                }
            }

            LOG.info("MapThumbnailMain: {} rendered, {} skipped, {} failed, {} total",
                rendered, skipped, failed, mapDirs.size());
        }
        finally
        {
            pool.close();
        }
    }

    /**
     * Renders one map's thumbnail and writes its meta sidecar.
     *
     * @param id         the map's id (also the directory name)
     * @param mapDir     the directory holding the .ofm, where the outputs go
     * @param modelPath  the path to the {@code level.ofm}
     * @param width      output width in pixels
     * @param height     output height in pixels
     * @param pool       worker pool, or null to run serially
     * @throws IOException if the model cannot be read or the PNG written
     */
    private static void renderOne(final String id, final Path mapDir,
        final Path modelPath, final int width, final int height,
        final I_ThreadPoolPort pool) throws IOException
    {
        final byte[] image = Files.readAllBytes(modelPath);

        final ModelFormat model = ModelFormat.read(image);

        final I_TimePort time = new DesktopTimePort();

        time.init();

        final SoftwareRenderPort renderer = new SoftwareRenderPort(pool, time);

        renderer.init();

        renderer.resize(width, height);

        renderer.loadModel(model);

        renderer.setCamera(thumbnailCamera(model, (float) width / (float) height));

        renderer.renderFrame(0);

        final Path out = mapDir.resolve(THUMBNAIL_FILE);

        FramePng.write(renderer, out, width, height);

        renderer.shutdown();

        time.shutdown();

        writeMetaIfAbsent(id, mapDir);

        LOG.info("Rendered {} thumbnail: {}x{} -> {}", id, width, height, out);
    }

    /**
     * Builds a fixed 3/4-view camera framing the model's XZ extent.
     *
     * <p>The camera looks down and forward at the model centre from a point
     * {@link #DISTANCE_FACTOR} times the larger of the XZ extent away, on a
     * 45deg pitch. FOV and near plane are the pipeline defaults &mdash; the
     * same pair {@code RenderPreviewMain} uses &mdash; so a thumbnail is
     * directly comparable to a {@code renderPreview} output for the same
     * model.</p>
     *
     * @param model  the map's flat model
     * @param aspect the thumbnail aspect (width / height)
     * @return the camera, ready to hand to {@code SoftwareRenderPort.setCamera}
     */
    private static Camera thumbnailCamera(final ModelFormat model, final float aspect)
    {
        final float centreX = (model.minX() + model.maxX()) * 0.5f;

        final float centreY = (model.minY() + model.maxY()) * 0.5f;

        final float centreZ = (model.minZ() + model.maxZ()) * 0.5f;

        final float spanX = model.maxX() - model.minX();

        final float spanZ = model.maxZ() - model.minZ();

        // MUTABLE local -- the larger of the two horizontal extents, with a
        // 1-unit floor so a degenerate model still produces a camera.
        float extent = Math.max(spanX, spanZ);

        if (!(extent > 0.0f))
        {
            extent = 1.0f;
        }

        final float distance = extent * DISTANCE_FACTOR;

        final float pitchRadians = (float) Math.toRadians(PITCH_DEGREES);

        // Pitch is measured DOWN from horizontal. eye is therefore above and
        // back of the model centre.
        final float eyeHeight = distance * (float) Math.sin(pitchRadians);

        final float eyeBack = distance * (float) Math.cos(pitchRadians);

        final Vec3 eye = new Vec3(centreX, centreY + eyeHeight, centreZ - eyeBack);

        return Camera.lookingAt(eye, new Vec3(centreX, centreY, centreZ),
            new Vec3(0.0f, 1.0f, 0.0f), SoftwareRenderPort.DEFAULT_FOV_Y, aspect,
            SoftwareRenderPort.DEFAULT_NEAR);
    }

    /**
     * Writes a default {@code meta.json} next to the model, unless one is
     * already present with a non-empty caption (the test for a hand edit).
     *
     * <p>Auto-generated metadata is what the menu falls back to when the
     * project does not commit a per-map description; a hand edit wins on
     * the principle that a deliberate local choice is more authoritative
     * than a tool's generic default.</p>
     *
     * @param id     the map's id
     * @param mapDir the directory to write into
     * @throws IOException if the sidecar cannot be written
     */
    private static void writeMetaIfAbsent(final String id, final Path mapDir)
        throws IOException
    {
        final Path metaPath = mapDir.resolve(META_FILE);

        if (Files.isRegularFile(metaPath))
        {
            final MapMeta existing = readMeta(metaPath);

            if (existing != null && existing.caption != null
                && !existing.caption.isBlank())
            {
                LOG.info("Preserving hand-edited meta for {}", id);

                return;
            }
        }

        final MapSpec spec;

        if (MapLibrary.has(id))
        {
            spec = MapLibrary.get(id);
        }
        else
        {
            spec = null;
        }

        final String displayName;

        if (spec != null)
        {
            displayName = spec.displayName();
        }
        else
        {
            displayName = humanize(id);
        }

        final String setting;

        if (spec != null)
        {
            setting = spec.setting().name();
        }
        else
        {
            setting = MapSetting.URBAN_WARZONE.name();
        }

        final String mode;

        if (spec != null)
        {
            mode = spec.mode().name();
        }
        else
        {
            mode = "TDM";
        }

        final String caption;

        if (spec != null)
        {
            caption = defaultCaption(spec);
        }
        else
        {
            caption = defaultCaption(id, displayName, setting, mode);
        }

        final MapMeta meta = new MapMeta();

        meta.id = id;
        meta.displayName = displayName;
        meta.setting = setting;
        meta.mode = mode;
        meta.caption = caption;
        meta.thumbnail = THUMBNAIL_FILE;

        final Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Files.writeString(metaPath, gson.toJson(meta));
    }

    /**
     * Humanises a map id into a display name when no {@code MapSpec} is
     * registered &mdash; the test-fixture case, where a directory exists on
     * disk but the Java registry has no entry for it.
     *
     * @param id the map's id, e.g. {@code cornerstone}
     * @return a display name, e.g. {@code Cornerstone}
     */
    private static String humanize(final String id)
    {
        if (id == null || id.isEmpty())
        {
            return id;
        }

        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    /**
     * Produces a one-line caption for a map from its setting and mode. The
     * copy is deliberately generic; a hand-edited {@code caption} field in
     * the sidecar wins, on the principle that a deliberate local choice is
     * more authoritative than a tool's generated default.
     *
     * @param spec the registered map spec
     * @return a generated caption, never null
     */
    private static String defaultCaption(final MapSpec spec)
    {
        return defaultCaption(spec.id(), spec.displayName(),
            spec.setting().name(), spec.mode().name());
    }

    private static String defaultCaption(final String id, final String displayName,
        final String setting, final String mode)
    {
        final String settingName = settingName(setting);

        final String modeName = modeName(mode);

        return String.format(Locale.ROOT, "%s. %s map. %s ruleset.",
            displayName, settingName, modeName);
    }

    private static String settingName(final String setting)
    {
        if (setting == null)
        {
            return "Unknown";
        }
        switch (setting)
        {
            case "URBAN_WARZONE":
                return "Urban warzone";
            case "INDUSTRIAL_COMPLEX":
                return "Industrial complex";
            case "DESERT_RAVINE":
                return "Desert ravine";
            case "ARCTIC_STATION":
                return "Arctic station";
            default:
                return humanize(setting.toLowerCase().replace('_', ' '));
        }
    }

    private static String modeName(final String mode)
    {
        if (mode == null)
        {
            return "Unknown";
        }
        switch (mode)
        {
            case "TDM":
                return "Team Deathmatch";
            case "HARDPOINT":
                return "Hardpoint";
            case "DOMINATION":
                return "Domination";
            case "CTF":
                return "Capture the Flag";
            default:
                return mode;
        }
    }

    /**
     * Reads a sidecar meta file. Returns null on parse error so the
     * caller can fall through to the auto-generated default; the file
     * is then overwritten on the next run.
     *
     * @param metaPath the sidecar's path
     * @return the parsed meta, or null if the file is missing or malformed
     */
    private static MapMeta readMeta(final Path metaPath)
    {
        try
        {
            final String body = Files.readString(metaPath);

            return new Gson().fromJson(body, MapMeta.class);
        }
        catch (final Exception e)
        {
            LOG.warn("Could not read existing meta {}: {}", metaPath, e.getMessage());

            return null;
        }
    }

    private static int intProperty(final String key, final int fallback)
    {
        final String value = System.getProperty(key);

        if (value == null || value.isEmpty())
        {
            return fallback;
        }

        return Integer.parseInt(value);
    }

    /**
     * The on-disk shape of a map's metadata sidecar. Field names match the
     * JSON keys; Gson's reflection-based binding does the rest.
     */
    public static final class MapMeta
    {
        /** The map's stable id. */
        public String id;

        /** The human-readable name. */
        public String displayName;

        /** The setting enum name, e.g. {@code URBAN_WARZONE}. */
        public String setting;

        /** The mode enum name, e.g. {@code TDM}. */
        public String mode;

        /** One-line caption for the menu. */
        public String caption;

        /** The thumbnail file name relative to the sidecar. */
        public String thumbnail;
    }
}
