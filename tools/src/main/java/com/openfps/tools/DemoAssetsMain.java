/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.tools.model.ProceduralRoom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and verifies the first-person demo's model set: staged glTF in,
 * {@code .ofm} out, every result read back through {@link ModelFormat} and
 * reported against the {@code docs/ASSETS.md} § 5 budget.
 *
 * Build-time only — this class is never on a runtime classpath.
 *
 * <h2>Why conversion and verification are one step</h2>
 *
 * <p>A model that converts without throwing is not a model that works. The
 * converter's own budget checks run on the builder's in-memory totals, so they
 * prove the geometry was in budget — not that the bytes written to disk parse
 * back into the same geometry. Reading every output through the runtime's own
 * reader closes that gap, and doing it in the same process that wrote them
 * means nobody can regenerate the assets and skip the check.</p>
 *
 * <h2>The fallback</h2>
 *
 * <p>{@code docs/ASSETS.md} § 6 keeps upstream art out of git and § 3 permits
 * only CC0 sources, so a fresh clone has nothing to convert. When no glTF has
 * been staged this writes {@link ProceduralRoom} instead, at {@code WARN}, and
 * names it {@code generated-room.ofm} — a generated room is a working floor to
 * stand on, but presenting one as third-party art would falsify the provenance
 * record {@code docs/ASSETS.md} § 7 requires.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm files go; required
 *   --gltf=&lt;dir&gt;    staged glTF/GLB tree to convert; optional
 *   --forceFallback also emit the generated room even when glTF was converted
 * </pre>
 *
 * <p>With {@code --out} alone it converts nothing and only verifies what is
 * already there, which is the cheap way to re-check an existing payload.</p>
 */
public final class DemoAssetsMain
{
    /** Name given to the generated fallback room, so it is never mistaken for upstream art. */
    public static final String FALLBACK_MODEL_NAME = "generated-room.ofm";

    /** Extension of a converted model: OpenFPS Model. */
    public static final String MODEL_EXTENSION = ".ofm";

    private static final Logger LOG = LoggerFactory.getLogger(DemoAssetsMain.class);

    /** Exit status used when an argument, an asset, or a budget is wrong. */
    private static final int EXIT_FAILURE = 1;

    /** Percent, for reporting a triangle count as a fraction of the budget. */
    private static final double PERCENT = 100.0;

    private DemoAssetsMain()
    {
        // entry point holder
    }

    /**
     * Converts the staged glTF tree, or generates a fallback room, then
     * verifies every model produced.
     *
     * @param args see the class Javadoc
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: DemoAssetsMain --out=<directory> [--gltf=<directory>]"
                + " [--forceFallback]");
            System.exit(EXIT_FAILURE);
            return;
        }

        final Path output = Path.of(out);
        try
        {
            build(output, option(args, "--gltf="), flag(args, "--forceFallback"));
        }
        catch (final IOException e)
        {
            LOG.error("Demo asset generation failed: {}", e.getMessage(), e);
            System.exit(EXIT_FAILURE);
            return;
        }
        catch (final RuntimeException e)
        {
            // A budget violation or a malformed source lands here. The message
            // already names the file and the offending value, so it is logged
            // rather than re-wrapped, and the process fails so Gradle does too.
            LOG.error("Demo asset generation failed: {}", e.getMessage(), e);
            System.exit(EXIT_FAILURE);
            return;
        }

        if (!verify(output))
        {
            System.exit(EXIT_FAILURE);
        }
    }

    // Converts what was staged, then decides whether the fallback is needed.
    private static void build(final Path output, final String gltf, final boolean forceFallback)
        throws IOException
    {
        if (gltf != null && Files.isDirectory(Path.of(gltf)))
        {
            GltfConverterMain.convertTree(Path.of(gltf), output);
        }
        else if (gltf != null)
        {
            LOG.warn("No staged glTF at {} — nothing to convert."
                + " Stage a CC0 pack first; see docs/DEMO_ASSETS.md.", gltf);
        }

        Files.createDirectories(output);
        if (forceFallback || models(output).isEmpty())
        {
            writeFallback(output);
        }
    }

    // Emits the generated room, saying plainly that is what it is.
    private static void writeFallback(final Path output) throws IOException
    {
        final Path target = output.resolve(FALLBACK_MODEL_NAME);
        Files.write(target, ProceduralRoom.build());
        LOG.warn("Shipping GENERATED geometry, not third-party art: wrote {} ({} triangles)."
            + " This is the docs/ASSETS.md section 3 fallback — a room to stand in when no CC0"
            + " pack has been staged. Do not record it against an upstream source.",
            target.toAbsolutePath(), ProceduralRoom.triangleCount());
    }

    /**
     * Reads every model under a directory back through {@link ModelFormat} and
     * reports it against the {@code docs/ASSETS.md} § 5 budget.
     *
     * @param output directory holding the {@code .ofm} files
     * @return true when every model parsed, carried geometry, and fitted the budget
     */
    public static boolean verify(final Path output)
    {
        final List<Path> models = models(output);
        if (models.isEmpty())
        {
            LOG.error("No {} files under {} — nothing was produced.", MODEL_EXTENSION, output);
            return false;
        }

        // MUTABLE locals — the running verdict and the whole-scene cost of
        // placing one of every model at once.
        boolean ok = true;
        int totalTriangles = 0;

        for (final Path model : models)
        {
            final int triangles = report(model);
            if (triangles < 0)
            {
                ok = false;
            }
            else
            {
                totalTriangles += triangles;
            }
        }

        LOG.info("{} models verified. One of each placed at once costs {} triangles;"
            + " docs/ASSETS.md section 2 affords roughly 10-20k per frame at 720p60.",
            models.size(), totalTriangles);
        return ok;
    }

    // Reads and reports one model, returning its triangle count or -1 on failure.
    private static int report(final Path path)
    {
        final ModelFormat model;
        try
        {
            model = ModelFormat.read(Files.readAllBytes(path));
        }
        catch (final IOException | RuntimeException e)
        {
            LOG.error("{}: does not read back as a model — {}", path.getFileName(),
                e.getMessage(), e);
            return -1;
        }

        LOG.info("{}: {} vertices, {} triangles ({}% of the {} budget), {} submeshes,"
            + " {} textures{}, extent {}", path.getFileName(), model.vertexCount(),
            model.triangleCount(),
            String.format(Locale.ROOT, "%.1f",
                PERCENT * model.triangleCount() / ModelFormat.MAX_TRIANGLES_PER_MODEL),
            ModelFormat.MAX_TRIANGLES_PER_MODEL, model.submeshCount(), model.textureCount(),
            textureSummary(model), extent(model));

        return verdict(path, model);
    }

    // The model-space bounding box as "w x h x d", which is what a level
    // builder needs in order to know the kit's grid spacing.
    private static String extent(final ModelFormat model)
    {
        return String.format(Locale.ROOT, "%.2f x %.2f x %.2f",
            model.maxX() - model.minX(), model.maxY() - model.minY(),
            model.maxZ() - model.minZ());
    }

    // The checks a converted model must pass to count as usable art.
    private static int verdict(final Path path, final ModelFormat model)
    {
        if (model.vertexCount() == 0 || model.triangleCount() == 0)
        {
            LOG.error("{}: parses but holds no geometry — {} vertices, {} triangles.",
                path.getFileName(), model.vertexCount(), model.triangleCount());
            return -1;
        }
        if (model.triangleCount() > ModelFormat.MAX_TRIANGLES_PER_MODEL)
        {
            LOG.error("{}: {} triangles exceeds the budget of {} (docs/ASSETS.md section 5).",
                path.getFileName(), model.triangleCount(),
                ModelFormat.MAX_TRIANGLES_PER_MODEL);
            return -1;
        }
        for (int texture = 0; texture < model.textureCount(); texture++)
        {
            if (!textureInBudget(path, model, texture))
            {
                return -1;
            }
        }
        return model.triangleCount();
    }

    // A texture must fit the cap, be a power of two, and carry a mip pyramid.
    private static boolean textureInBudget(final Path path, final ModelFormat model,
        final int texture)
    {
        final int width = model.textureWidth(texture);
        final int height = model.textureHeight(texture);
        if (width > ModelFormat.MAX_TEXTURE_DIMENSION
            || height > ModelFormat.MAX_TEXTURE_DIMENSION)
        {
            LOG.error("{}: texture {} is {}x{}, over the {} squared budget"
                + " (docs/ASSETS.md section 5).", path.getFileName(), texture, width, height,
                ModelFormat.MAX_TEXTURE_DIMENSION);
            return false;
        }
        if (!isPowerOfTwo(width) || !isPowerOfTwo(height))
        {
            LOG.error("{}: texture {} is {}x{}, not a power of two.", path.getFileName(),
                texture, width, height);
            return false;
        }
        if (model.textureLevelCount(texture) < 2)
        {
            LOG.error("{}: texture {} has {} mip level(s); mipmaps are required, not optional"
                + " (docs/ASSETS.md section 5).", path.getFileName(), texture,
                model.textureLevelCount(texture));
            return false;
        }
        return true;
    }

    // A compact "512x512x10" per texture, appended to the model's report line.
    private static String textureSummary(final ModelFormat model)
    {
        if (model.textureCount() == 0)
        {
            return "";
        }
        // MUTABLE local — the accumulating summary.
        final StringBuilder summary = new StringBuilder(" [");
        for (int texture = 0; texture < model.textureCount(); texture++)
        {
            if (texture > 0)
            {
                summary.append(", ");
            }
            summary.append(model.textureWidth(texture)).append('x')
                .append(model.textureHeight(texture)).append(", ")
                .append(model.textureLevelCount(texture)).append(" mips");
        }
        return summary.append(']').toString();
    }

    private static boolean isPowerOfTwo(final int value)
    {
        return value > 0 && (value & (value - 1)) == 0;
    }

    // Every .ofm directly under a directory, in a stable order.
    private static List<Path> models(final Path output)
    {
        if (!Files.isDirectory(output))
        {
            return List.of();
        }
        final List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(output))
        {
            walk.filter(DemoAssetsMain::isModel).sorted().forEach(found::add);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("cannot scan " + output, e);
        }
        return found;
    }

    private static boolean isModel(final Path path)
    {
        return Files.isRegularFile(path)
            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(MODEL_EXTENSION);
    }

    private static String option(final String[] args, final String prefix)
    {
        for (final String arg : args)
        {
            if (arg != null && arg.startsWith(prefix))
            {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static boolean flag(final String[] args, final String name)
    {
        for (final String arg : args)
        {
            if (name.equals(arg))
            {
                return true;
            }
        }
        return false;
    }
}
