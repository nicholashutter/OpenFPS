/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.gltf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.tools.model.MipGenerator;
import com.openfps.tools.model.ModelBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts a glTF 2.0 or GLB asset into the engine's flat binary
 * {@link ModelFormat}.
 *
 * <b>Build time only.</b> This class is never on a runtime classpath, and the
 * root project's {@code verifyToolsIsolation} task fails the build if it ever
 * becomes so. {@code docs/ASSETS.md} § 4 is the reason: a software
 * rasterizer's scarcest resource is per-frame CPU and its cheapest is
 * build-time CPU, so everything expensive happens here, once, offline.
 *
 * <h2>What moves offline</h2>
 *
 * <ul>
 *   <li><b>Scene flattening.</b> The node hierarchy is walked and each node's
 *       world transform is baked into its vertices, so the runtime holds one
 *       model-space mesh rather than a tree to traverse per frame.</li>
 *   <li><b>Triangulation.</b> Triangle strips and fans are expanded into a
 *       plain triangle list. The runtime knows one primitive topology.</li>
 *   <li><b>Material flattening.</b> Base colour factor and vertex colour are
 *       multiplied together into one baked RGBA8888 vertex colour.</li>
 *   <li><b>Texture decode.</b> PNG or JPEG in, RGBA8888 texels out, in exactly
 *       the layout {@link Rgba} and the colour buffer share, so the sampler
 *       never swizzles a channel.</li>
 *   <li><b>Mip generation.</b> Full pyramids, because
 *       {@code docs/ASSETS.md} § 5 makes them required.</li>
 *   <li><b>Bounds.</b> The model-space AABB is computed once.</li>
 * </ul>
 *
 * <h2>Albedo only</h2>
 *
 * Normal, roughness, metallic, occlusion and emissive textures are dropped,
 * with a log line naming the material. There is no per-pixel lighting to
 * consume them ({@code docs/ASSETS.md} § 2), so importing them would cost
 * payload size to feed nothing. This is not treated as a budget violation —
 * a perfectly good Kenney model may ship maps we have no use for, and failing
 * the build over an unused input would reject valid art.
 *
 * <h2>Colour space</h2>
 *
 * glTF's {@code baseColorFactor} and {@code COLOR_0} are linear; the colour
 * buffer and the decoded albedo texels are 8-bit sRGB. Vertex colour is
 * therefore sRGB-encoded on the way in, so that the runtime's
 * texel-times-vertex-colour modulate lands where the author intended. The
 * common case — {@code baseColorFactor} of 1,1,1,1 and no {@code COLOR_0} —
 * is exact either way; the encode matters for the untextured, flat-colour
 * materials that make up much of a low-poly kit.
 *
 * Source — Khronos Group, glTF 2.0 Specification —
 * https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
 */
public final class GltfConverter
{
    private static final Logger LOG = LoggerFactory.getLogger(GltfConverter.class);

    /** Primitive mode: an independent triangle every three indices. */
    private static final int MODE_TRIANGLES = 4;

    /** Primitive mode: a triangle strip. */
    private static final int MODE_TRIANGLE_STRIP = 5;

    /** Primitive mode: a triangle fan. */
    private static final int MODE_TRIANGLE_FAN = 6;

    /** Components in an RGBA colour. */
    private static final int RGBA_COMPONENTS = 4;

    /** Largest value of an 8-bit channel. */
    private static final float CHANNEL_MAX = 255.0f;

    /** Threshold of the sRGB transfer function's linear segment. */
    private static final double SRGB_LINEAR_CUTOFF = 0.0031308;

    /** Slope of the sRGB transfer function's linear segment. */
    private static final float SRGB_LINEAR_SLOPE = 12.92f;

    /** Scale of the sRGB transfer function's power segment. */
    private static final double SRGB_SCALE = 1.055;

    /** Offset of the sRGB transfer function's power segment. */
    private static final double SRGB_OFFSET = 0.055;

    /** Exponent of the sRGB transfer function's power segment. */
    private static final double SRGB_EXPONENT = 1.0 / 2.4;

    /** Non-albedo texture slots, dropped with a log line rather than imported. */
    private static final String[] IGNORED_TEXTURES =
    {
        "normalTexture", "occlusionTexture", "emissiveTexture",
    };

    private final GltfAsset asset;
    private final ModelBuilder builder;

    /** Model texture index per glTF image, or -1 when not yet decoded. MUTABLE: filled on demand. */
    private final int[] textureByImage;

    /** Guards against a cyclic node graph. MUTABLE: set while a node is on the stack. */
    private final boolean[] onStack;

    private GltfConverter(final GltfAsset asset)
    {
        this.asset = asset;

        this.builder = new ModelBuilder(asset.name());

        this.textureByImage = new int[asset.array("images").size()];

        this.onStack = new boolean[asset.array("nodes").size()];

        Arrays.fill(textureByImage, -1);
    }

    /**
     * Converts one glTF or GLB file into a {@link ModelFormat} image.
     *
     * @param source the {@code .gltf} or {@code .glb} file
     * @return the converted model, ready for {@link ModelFormat#read}
     * @throws GltfException if the source is malformed or uses an unsupported
     *     feature
     * @throws com.openfps.tools.AssetBudgetException if the model violates a
     *     {@code docs/ASSETS.md} § 5 budget
     */
    public static byte[] convert(final Path source)
    {
        return new GltfConverter(GltfAsset.load(source)).run();
    }

    /**
     * Converts an in-memory glTF or GLB image.
     *
     * <p>Exposed so tests can convert a fixture built in the test itself,
     * which is what keeps this converter's suite hermetic — {@code fetchAssets}
     * is deliberately not wired into {@code build}.</p>
     *
     * @param name asset name, quoted in diagnostics and budget failures
     * @param bytes the whole source file
     * @return the converted model
     */
    public static byte[] convert(final String name, final byte[] bytes)
    {
        return new GltfConverter(GltfAsset.of(name, null, bytes)).run();
    }

    /**
     * Converts one file and writes the result.
     *
     * @param source the {@code .gltf} or {@code .glb} file
     * @param target the {@code .ofm} file to write; parent directories are created
     * @throws GltfException if the source cannot be read or the target written
     */
    public static void convert(final Path source, final Path target)
    {
        final byte[] model = convert(source);

        try
        {
            final Path parent = target.getParent();

            if (parent != null)
            {
                Files.createDirectories(parent);
            }

            Files.write(target, model);
        }
        catch (final IOException e)
        {
            throw new GltfException("cannot write " + target, e);
        }

        LOG.info("Converted {} -> {} ({} bytes)", source.getFileName(), target.getFileName(),
            model.length);
    }

    // ---- Scene walk -----------------------------------------------------

    // Flattens the default scene into the builder and serialises it.
    private byte[] run()
    {
        final JsonArray scenes = asset.array("scenes");

        if (scenes.size() == 0)
        {
            convertAllMeshes();
        }
        else
        {
            final int sceneIndex = GltfAsset.optionalInt(asset.root(), "scene", 0);

            final JsonObject scene = asset.item("scenes", sceneIndex);

            final JsonArray roots = GltfAsset.optionalArray(scene, "nodes");

            for (int i = 0; i < roots.size(); i++)
            {
                visitNode(roots.get(i).getAsInt(), Mat4.identity());
            }
        }

        LOG.info("{}: {} vertices, {} triangles, {} textures", asset.name(),
            builder.vertexCount(), builder.triangleCount(), builder.textureCount());

        return builder.toBytes();
    }

    // A document with no scene still has meshes worth converting.
    private void convertAllMeshes()
    {
        final int meshCount = asset.array("meshes").size();

        for (int mesh = 0; mesh < meshCount; mesh++)
        {
            convertMesh(mesh, Mat4.identity());
        }
    }

    // Bakes one node's world transform into its mesh, then recurses.
    private void visitNode(final int nodeIndex, final Mat4 parent)
    {
        if (nodeIndex < 0 || nodeIndex >= onStack.length)
        {
            throw new GltfException(asset.name() + ": node " + nodeIndex + " is out of range");
        }

        if (onStack[nodeIndex])
        {
            throw new GltfException(asset.name() + ": node " + nodeIndex
                + " is its own ancestor; the node graph must be a tree");
        }

        onStack[nodeIndex] = true;

        final JsonObject node = asset.item("nodes", nodeIndex);

        final Mat4 world = parent.multiply(nodeTransform(node));

        if (node.has("mesh"))
        {
            convertMesh(node.get("mesh").getAsInt(), world);
        }

        final JsonArray children = GltfAsset.optionalArray(node, "children");

        for (int i = 0; i < children.size(); i++)
        {
            visitNode(children.get(i).getAsInt(), world);
        }

        onStack[nodeIndex] = false;
    }

    // Reads a node's local transform: an explicit matrix, or composed TRS.
    private static Mat4 nodeTransform(final JsonObject node)
    {
        if (node.has("matrix"))
        {
            return matrixOf(GltfAsset.optionalArray(node, "matrix"));
        }

        return translationOf(node).multiply(rotationOf(node)).multiply(scaleOf(node));
    }

    // glTF matrices are column-major; Mat4 is row-major. Hence the transpose.
    private static Mat4 matrixOf(final JsonArray values)
    {
        if (values.size() != Mat4.ELEMENTS)
        {
            throw new GltfException("node matrix must have " + Mat4.ELEMENTS + " elements, got "
                + values.size());
        }

        final float[] rowMajor = new float[Mat4.ELEMENTS];

        for (int row = 0; row < Mat4.ORDER; row++)
        {
            for (int column = 0; column < Mat4.ORDER; column++)
            {
                rowMajor[(row * Mat4.ORDER) + column] =
                    values.get((column * Mat4.ORDER) + row).getAsFloat();
            }
        }

        return Mat4.ofRowMajor(rowMajor);
    }

    // Node translation, defaulting to the origin.
    private static Mat4 translationOf(final JsonObject node)
    {
        final JsonArray t = GltfAsset.optionalArray(node, "translation");

        if (t.size() != 3)
        {
            return Mat4.identity();
        }

        return Mat4.translation(t.get(0).getAsFloat(), t.get(1).getAsFloat(),
            t.get(2).getAsFloat());
    }

    // Node rotation from a glTF quaternion, stored x, y, z, w.
    private static Mat4 rotationOf(final JsonObject node)
    {
        final JsonArray q = GltfAsset.optionalArray(node, "rotation");

        if (q.size() != RGBA_COMPONENTS)
        {
            return Mat4.identity();
        }

        return quaternionMatrix(q.get(0).getAsFloat(), q.get(1).getAsFloat(),
            q.get(2).getAsFloat(), q.get(3).getAsFloat());
    }

    // The standard unit-quaternion to rotation-matrix expansion, row-major.
    private static Mat4 quaternionMatrix(final float x, final float y, final float z,
        final float w)
    {
        final float[] m = new float[Mat4.ELEMENTS];

        m[0] = 1.0f - (2.0f * ((y * y) + (z * z)));

        m[1] = 2.0f * ((x * y) - (z * w));

        m[2] = 2.0f * ((x * z) + (y * w));

        m[4] = 2.0f * ((x * y) + (z * w));

        m[5] = 1.0f - (2.0f * ((x * x) + (z * z)));

        m[6] = 2.0f * ((y * z) - (x * w));

        m[8] = 2.0f * ((x * z) - (y * w));

        m[9] = 2.0f * ((y * z) + (x * w));

        m[10] = 1.0f - (2.0f * ((x * x) + (y * y)));

        m[15] = 1.0f;

        return Mat4.ofRowMajor(m);
    }

    // Node scale, defaulting to unity.
    private static Mat4 scaleOf(final JsonObject node)
    {
        final JsonArray s = GltfAsset.optionalArray(node, "scale");

        if (s.size() != 3)
        {
            return Mat4.identity();
        }

        final float[] m = new float[Mat4.ELEMENTS];

        m[0] = s.get(0).getAsFloat();

        m[5] = s.get(1).getAsFloat();

        m[10] = s.get(2).getAsFloat();

        m[15] = 1.0f;

        return Mat4.ofRowMajor(m);
    }

    // ---- Meshes ---------------------------------------------------------

    // Converts every primitive of one mesh under a baked world transform.
    private void convertMesh(final int meshIndex, final Mat4 transform)
    {
        final JsonObject mesh = asset.item("meshes", meshIndex);

        final JsonArray primitives = GltfAsset.optionalArray(mesh, "primitives");

        for (int i = 0; i < primitives.size(); i++)
        {
            convertPrimitive(meshIndex + "/" + i, primitives.get(i).getAsJsonObject(), transform);
        }
    }

    // One glTF primitive becomes one submesh: its own index range and texture.
    private void convertPrimitive(final String label, final JsonObject primitive,
        final Mat4 transform)
    {
        final JsonObject attributes = primitive.getAsJsonObject("attributes");

        if (attributes == null || !attributes.has("POSITION"))
        {
            throw new GltfException(asset.name() + ": primitive " + label + " has no POSITION");
        }

        final float[] positions = GltfAccessor.readFloats(asset,
            attributes.get("POSITION").getAsInt(), 3);

        final int vertexCount = positions.length / 3;

        final float[] uvs = optionalAttribute(attributes, "TEXCOORD_0", 2, vertexCount);

        final float[] colours = optionalColours(attributes, vertexCount);

        final int[] triangles = triangulate(
            GltfAsset.optionalInt(primitive, "mode", MODE_TRIANGLES),
            indicesOf(primitive, vertexCount), label);

        final int materialIndex = GltfAsset.optionalInt(primitive, "material", -1);

        final float[] factor = baseColourFactor(materialIndex);

        builder.beginSubmesh(textureFor(materialIndex));

        final int base = builder.vertexCount();

        appendVertices(positions, uvs, colours, factor, transform, vertexCount);

        for (int i = 0; i < triangles.length; i += ModelFormat.INDICES_PER_TRIANGLE)
        {
            requireInRange(label, triangles, i, vertexCount);

            builder.addTriangle(base + triangles[i], base + triangles[i + 1],
                base + triangles[i + 2]);
        }

        builder.endSubmesh();
    }

    // Transforms and appends every vertex of one primitive.
    private void appendVertices(final float[] positions, final float[] uvs, final float[] colours,
        final float[] factor, final Mat4 transform, final int vertexCount)
    {
        final float[] point = new float[Mat4.ORDER];

        for (int v = 0; v < vertexCount; v++)
        {
            transform.transformPoint(positions[v * 3], positions[(v * 3) + 1],
                positions[(v * 3) + 2], point, 0);

            // MUTABLE locals — texture coordinates default to the origin when
            // the primitive carries none, which is the untextured case.
            float u = 0.0f;

            float w = 0.0f;

            if (uvs != null)
            {
                u = uvs[v * 2];

                w = uvs[(v * 2) + 1];
            }

            builder.addVertex(point[0], point[1], point[2], u, w,
                packColour(colours, v, factor));
        }
    }

    // Reads an optional float attribute, checking it matches the vertex count.
    private float[] optionalAttribute(final JsonObject attributes, final String name,
        final int components, final int vertexCount)
    {
        if (!attributes.has(name))
        {
            return null;
        }

        final float[] values = GltfAccessor.readFloats(asset, attributes.get(name).getAsInt(),
            components);

        if (values.length / components != vertexCount)
        {
            throw new GltfException(asset.name() + ": " + name + " has " + values.length / components
                + " elements but POSITION has " + vertexCount);
        }

        return values;
    }

    // COLOR_0 is VEC3 or VEC4; both are normalised to four components here.
    private float[] optionalColours(final JsonObject attributes, final int vertexCount)
    {
        if (!attributes.has("COLOR_0"))
        {
            return null;
        }

        final int accessor = attributes.get("COLOR_0").getAsInt();

        final String type = asset.item("accessors", accessor).get("type").getAsString();

        final int components = GltfAccessor.componentCount(type);

        final float[] source = GltfAccessor.readFloats(asset, accessor, components);

        if (source.length / components != vertexCount)
        {
            throw new GltfException(asset.name() + ": COLOR_0 has " + source.length / components
                + " elements but POSITION has " + vertexCount);
        }

        final float[] rgba = new float[vertexCount * RGBA_COMPONENTS];

        Arrays.fill(rgba, 1.0f);

        for (int v = 0; v < vertexCount; v++)
        {
            System.arraycopy(source, v * components, rgba, v * RGBA_COMPONENTS, components);
        }

        return rgba;
    }

    // The index array, or an implicit 0..n-1 sequence when the primitive has none.
    private int[] indicesOf(final JsonObject primitive, final int vertexCount)
    {
        if (primitive.has("indices"))
        {
            return GltfAccessor.readScalarInts(asset, primitive.get("indices").getAsInt());
        }

        final int[] implicit = new int[vertexCount];

        for (int i = 0; i < vertexCount; i++)
        {
            implicit[i] = i;
        }

        return implicit;
    }

    // Expands strips and fans into a plain triangle list; rejects line and
    // point topologies, which this renderer has no path for.
    private int[] triangulate(final int mode, final int[] source, final String label)
    {
        if (mode == MODE_TRIANGLES)
        {
            if (source.length % ModelFormat.INDICES_PER_TRIANGLE != 0)
            {
                throw new GltfException(asset.name() + ": primitive " + label + " has "
                    + source.length + " indices, not a multiple of "
                    + ModelFormat.INDICES_PER_TRIANGLE);
            }

            return source;
        }

        if (mode == MODE_TRIANGLE_STRIP)
        {
            return expandStrip(source);
        }

        if (mode == MODE_TRIANGLE_FAN)
        {
            return expandFan(source);
        }

        throw new GltfException(asset.name() + ": primitive " + label + " uses mode " + mode
            + "; only TRIANGLES (" + MODE_TRIANGLES + "), TRIANGLE_STRIP (" + MODE_TRIANGLE_STRIP
            + ") and TRIANGLE_FAN (" + MODE_TRIANGLE_FAN + ") produce surfaces to rasterize");
    }

    // Strip winding alternates, so odd triangles swap their first two vertices.
    private static int[] expandStrip(final int[] source)
    {
        if (source.length < ModelFormat.INDICES_PER_TRIANGLE)
        {
            return new int[0];
        }

        final int triangles = source.length - 2;

        final int[] out = new int[triangles * ModelFormat.INDICES_PER_TRIANGLE];

        for (int i = 0; i < triangles; i++)
        {
            final int at = i * ModelFormat.INDICES_PER_TRIANGLE;

            if (i % 2 == 0)
            {
                out[at] = source[i];

                out[at + 1] = source[i + 1];
            }
            else
            {
                out[at] = source[i + 1];

                out[at + 1] = source[i];
            }

            out[at + 2] = source[i + 2];
        }

        return out;
    }

    // A fan pivots every triangle on its first vertex.
    private static int[] expandFan(final int[] source)
    {
        if (source.length < ModelFormat.INDICES_PER_TRIANGLE)
        {
            return new int[0];
        }

        final int triangles = source.length - 2;

        final int[] out = new int[triangles * ModelFormat.INDICES_PER_TRIANGLE];

        for (int i = 0; i < triangles; i++)
        {
            final int at = i * ModelFormat.INDICES_PER_TRIANGLE;

            out[at] = source[0];

            out[at + 1] = source[i + 1];

            out[at + 2] = source[i + 2];
        }

        return out;
    }

    // An index outside the primitive's own vertices is a malformed source file.
    private void requireInRange(final String label, final int[] triangles, final int at,
        final int vertexCount)
    {
        for (int i = 0; i < ModelFormat.INDICES_PER_TRIANGLE; i++)
        {
            final int index = triangles[at + i];

            if (index < 0 || index >= vertexCount)
            {
                throw new GltfException(asset.name() + ": primitive " + label + " index "
                    + Integer.toUnsignedString(index) + " addresses outside its " + vertexCount
                    + " vertices");
            }
        }
    }

    // ---- Materials and textures -----------------------------------------

    // The material's base colour factor, defaulting to opaque white.
    private float[] baseColourFactor(final int materialIndex)
    {
        final float[] factor = new float[RGBA_COMPONENTS];

        Arrays.fill(factor, 1.0f);

        if (materialIndex < 0)
        {
            return factor;
        }

        final JsonObject pbr = asset.item("materials", materialIndex)
            .getAsJsonObject("pbrMetallicRoughness");

        if (pbr == null)
        {
            return factor;
        }

        final JsonArray values = GltfAsset.optionalArray(pbr, "baseColorFactor");

        for (int i = 0; i < Math.min(RGBA_COMPONENTS, values.size()); i++)
        {
            factor[i] = values.get(i).getAsFloat();
        }

        return factor;
    }

    // Resolves a material to a model texture index, decoding the image once.
    private int textureFor(final int materialIndex)
    {
        if (materialIndex < 0)
        {
            return ModelFormat.NO_TEXTURE;
        }

        final JsonObject material = asset.item("materials", materialIndex);

        reportIgnoredTextures(materialIndex, material);

        final JsonObject pbr = material.getAsJsonObject("pbrMetallicRoughness");

        if (pbr == null || !pbr.has("baseColorTexture"))
        {
            return ModelFormat.NO_TEXTURE;
        }

        final JsonObject reference = pbr.getAsJsonObject("baseColorTexture");

        final JsonObject texture = asset.item("textures", reference.get("index").getAsInt());

        if (!texture.has("source"))
        {
            return ModelFormat.NO_TEXTURE;
        }

        return imageTexture(texture.get("source").getAsInt());
    }

    // Albedo only: everything else is dropped, but never silently.
    private void reportIgnoredTextures(final int materialIndex, final JsonObject material)
    {
        for (final String slot : IGNORED_TEXTURES)
        {
            if (material.has(slot))
            {
                LOG.info("{}: material {} has {}; dropped — there is no per-pixel lighting"
                    + " to consume it (docs/ASSETS.md section 5)", asset.name(), materialIndex,
                    slot);
            }
        }

        final JsonObject pbr = material.getAsJsonObject("pbrMetallicRoughness");

        if (pbr != null && pbr.has("metallicRoughnessTexture"))
        {
            LOG.info("{}: material {} has metallicRoughnessTexture; dropped — albedo only",
                asset.name(), materialIndex);
        }
    }

    // Decodes one glTF image, generates its mips, and registers it once.
    private int imageTexture(final int imageIndex)
    {
        if (imageIndex < 0 || imageIndex >= textureByImage.length)
        {
            throw new GltfException(asset.name() + ": image " + imageIndex + " is out of range");
        }

        if (textureByImage[imageIndex] >= 0)
        {
            return textureByImage[imageIndex];
        }

        final String label = imageLabel(imageIndex);

        final BufferedImage image = decodeImage(imageIndex, label);

        final int width = image.getWidth();

        final int height = image.getHeight();

        builder.checkTextureDimensions(label, width, height);

        final int[][] levels = MipGenerator.generate(width, height, toRgba(image));

        final int index = builder.addTexture(label, width, height, levels);

        textureByImage[imageIndex] = index;

        LOG.debug("{}: decoded texture '{}' {}x{} with {} mip levels", asset.name(), label,
            width, height, levels.length);

        return index;
    }

    // ImageIO handles PNG and JPEG, which is every format glTF sanctions.
    private BufferedImage decodeImage(final int imageIndex, final String label)
    {
        final byte[] encoded = asset.imageBytes(imageIndex);

        final BufferedImage image;

        try
        {
            image = ImageIO.read(new ByteArrayInputStream(encoded));
        }
        catch (final IOException e)
        {
            throw new GltfException(asset.name() + ": image '" + label + "' could not be decoded",
                e);
        }

        if (image == null)
        {
            throw new GltfException(asset.name() + ": image '" + label
                + "' is in a format ImageIO does not recognise; glTF permits PNG and JPEG");
        }

        return image;
    }

    // A human-usable name for an image, falling back to its index.
    private String imageLabel(final int imageIndex)
    {
        final JsonObject image = asset.item("images", imageIndex);

        if (image.has("name"))
        {
            return image.get("name").getAsString();
        }

        if (image.has("uri"))
        {
            final String uri = image.get("uri").getAsString();

            if (!uri.startsWith("data:"))
            {
                return uri;
            }
        }

        return "image " + imageIndex;
    }

    // BufferedImage hands out 0xAARRGGBB; the engine's one pixel format is
    // 0xRRGGBBAA. Repacking through Rgba keeps that definition in one place.
    private static int[] toRgba(final BufferedImage image)
    {
        final int width = image.getWidth();

        final int height = image.getHeight();

        final int[] argb = image.getRGB(0, 0, width, height, null, 0, width);

        final int[] rgba = new int[argb.length];

        for (int i = 0; i < argb.length; i++)
        {
            rgba[i] = Rgba.pack((argb[i] >>> 16) & 0xFF, (argb[i] >>> 8) & 0xFF, argb[i] & 0xFF,
                (argb[i] >>> 24) & 0xFF);
        }

        return rgba;
    }

    // ---- Colour ---------------------------------------------------------

    // Bakes vertex colour and base colour factor into one packed RGBA8888 value.
    private static int packColour(final float[] colours, final int vertex, final float[] factor)
    {
        // MUTABLE locals — the running product of vertex colour and factor.
        float red = factor[0];

        float green = factor[1];

        float blue = factor[2];

        float alpha = factor[3];

        if (colours != null)
        {
            final int at = vertex * RGBA_COMPONENTS;

            red *= colours[at];

            green *= colours[at + 1];

            blue *= colours[at + 2];

            alpha *= colours[at + 3];
        }

        return Rgba.pack(toSrgbByte(red), toSrgbByte(green), toSrgbByte(blue), toByte(alpha));
    }

    // Encodes a linear channel to 8-bit sRGB.
    private static int toSrgbByte(final float linear)
    {
        final float clamped = clamp01(linear);

        if (clamped <= SRGB_LINEAR_CUTOFF)
        {
            return Math.round(SRGB_LINEAR_SLOPE * clamped * CHANNEL_MAX);
        }

        final double encoded = (SRGB_SCALE * Math.pow(clamped, SRGB_EXPONENT)) - SRGB_OFFSET;

        return (int) Math.round(encoded * CHANNEL_MAX);
    }

    // Alpha carries no transfer function, so it scales linearly.
    private static int toByte(final float value)
    {
        return Math.round(clamp01(value) * CHANNEL_MAX);
    }

    // Clamps to the unit interval, mapping NaN to zero.
    private static float clamp01(final float value)
    {
        if (Float.isNaN(value) || value < 0.0f)
        {
            return 0.0f;
        }

        if (value > 1.0f)
        {
            return 1.0f;
        }

        return value;
    }
}
