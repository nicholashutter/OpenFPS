/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.gltf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.openfps.engine.render.adapter.Rgba;

/**
 * Builds glTF 2.0 and GLB fixtures in memory.
 *
 * <p>The converter's tests must stay hermetic: {@code fetchAssets} is
 * deliberately not wired into {@code build}, so CI has no Kenney GLB to point
 * at. Every fixture here is assembled byte by byte in the test process, which
 * means the suite runs on a machine with no network and no asset payload, and
 * means each test can construct exactly the malformed or over-budget input it
 * wants to see rejected.</p>
 *
 * <p>The one thing this cannot cover is a real upstream asset — see the note
 * in {@code GltfConverterTest}.</p>
 */
final class GltfFixtures
{
    /** GLB magic, chunk types and header sizes, per the specification. */
    static final int GLB_MAGIC = 0x46546C67;

    /** GLB JSON chunk type. */
    static final int CHUNK_JSON = 0x4E4F534A;

    /** GLB BIN chunk type. */
    static final int CHUNK_BIN = 0x004E4942;

    /** Accessor component type FLOAT. */
    static final int FLOAT = 5126;

    /** Accessor component type UNSIGNED_BYTE. */
    static final int UNSIGNED_BYTE = 5121;

    /** Accessor component type UNSIGNED_SHORT. */
    static final int UNSIGNED_SHORT = 5123;

    /** Accessor component type UNSIGNED_INT. */
    static final int UNSIGNED_INT = 5125;

    private final JsonObject root = new JsonObject();
    private final JsonArray bufferViews = new JsonArray();
    private final JsonArray accessors = new JsonArray();
    private final JsonArray meshes = new JsonArray();
    private final JsonArray materials = new JsonArray();
    private final JsonArray images = new JsonArray();
    private final JsonArray textures = new JsonArray();
    private final JsonArray nodes = new JsonArray();
    private final JsonArray scenes = new JsonArray();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

    GltfFixtures()
    {
        final JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        root.add("asset", asset);
    }

    // Replaces the asset version, so a glTF 1.0 document can be built.
    GltfFixtures version(final String version)
    {
        root.getAsJsonObject("asset").addProperty("version", version);
        return this;
    }

    // ---- Buffer views and accessors --------------------------------------

    // Appends bytes to the single buffer and returns the new view's index.
    int bufferView(final byte[] data)
    {
        pad4();
        final int offset = binary.size();
        binary.writeBytes(data);
        final JsonObject view = new JsonObject();
        view.addProperty("buffer", 0);
        view.addProperty("byteOffset", offset);
        view.addProperty("byteLength", data.length);
        bufferViews.add(view);
        return bufferViews.size() - 1;
    }

    // Declares an accessor over an existing view.
    int accessor(final int view, final int componentType, final String type, final int count)
    {
        final JsonObject accessor = new JsonObject();
        accessor.addProperty("bufferView", view);
        accessor.addProperty("componentType", componentType);
        accessor.addProperty("type", type);
        accessor.addProperty("count", count);
        accessors.add(accessor);
        return accessors.size() - 1;
    }

    // A tightly packed float accessor of the given vector type.
    int floats(final String type, final float... values)
    {
        final int components = GltfAccessor.componentCount(type);
        return accessor(bufferView(floatBytes(values)), FLOAT, type, values.length / components);
    }

    // A normalized unsigned-byte accessor, the compact form glTF allows for
    // colours and texture coordinates.
    int normalizedBytes(final String type, final int... values)
    {
        final int components = GltfAccessor.componentCount(type);
        final byte[] raw = new byte[values.length];
        for (int i = 0; i < values.length; i++)
        {
            raw[i] = (byte) values[i];
        }
        final int index = accessor(bufferView(raw), UNSIGNED_BYTE, type,
            values.length / components);
        accessors.get(index).getAsJsonObject().addProperty("normalized", true);
        return index;
    }

    // A SCALAR index accessor of the requested integer width.
    int indices(final int componentType, final int... values)
    {
        return accessor(bufferView(indexBytes(componentType, values)), componentType, "SCALAR",
            values.length);
    }

    // Appends bytes as a view that declares an interleaved stride.
    int stridedView(final byte[] data, final int strideBytes)
    {
        final int view = bufferView(data);
        bufferViews.get(view).getAsJsonObject().addProperty("byteStride", strideBytes);
        return view;
    }

    // Offsets an accessor within its view, as an interleaved layout requires.
    void accessorOffset(final int accessorIndex, final int byteOffset)
    {
        accessors.get(accessorIndex).getAsJsonObject().addProperty("byteOffset", byteOffset);
    }

    // Marks an accessor sparse, which the converter refuses.
    void markSparse(final int accessorIndex)
    {
        accessors.get(accessorIndex).getAsJsonObject().add("sparse", new JsonObject());
    }

    // ---- Materials and textures ------------------------------------------

    // Embeds a PNG as a buffer view and declares an image over it.
    int image(final String name, final byte[] png)
    {
        final JsonObject image = new JsonObject();
        image.addProperty("name", name);
        image.addProperty("bufferView", bufferView(png));
        images.add(image);
        return images.size() - 1;
    }

    // Declares a texture over an image.
    int texture(final int imageIndex)
    {
        final JsonObject texture = new JsonObject();
        texture.addProperty("source", imageIndex);
        textures.add(texture);
        return textures.size() - 1;
    }

    // Declares a material, optionally textured, with a base colour factor.
    int material(final int textureIndex, final float[] baseColorFactor)
    {
        final JsonObject pbr = new JsonObject();
        if (textureIndex >= 0)
        {
            final JsonObject reference = new JsonObject();
            reference.addProperty("index", textureIndex);
            pbr.add("baseColorTexture", reference);
        }
        if (baseColorFactor != null)
        {
            final JsonArray factor = new JsonArray();
            for (final float value : baseColorFactor)
            {
                factor.add(value);
            }
            pbr.add("baseColorFactor", factor);
        }
        final JsonObject material = new JsonObject();
        material.add("pbrMetallicRoughness", pbr);
        materials.add(material);
        return materials.size() - 1;
    }

    // Attaches a non-albedo texture slot, which the converter must drop.
    void materialSlot(final int materialIndex, final String slot, final int textureIndex)
    {
        final JsonObject reference = new JsonObject();
        reference.addProperty("index", textureIndex);
        materials.get(materialIndex).getAsJsonObject().add(slot, reference);
    }

    // ---- Meshes, nodes and scenes ----------------------------------------

    // Builds one primitive; a negative index omits the corresponding property.
    static JsonObject primitive(final int position, final int texcoord, final int colour,
        final int indices, final int material, final int mode)
    {
        final JsonObject attributes = new JsonObject();
        attributes.addProperty("POSITION", position);
        if (texcoord >= 0)
        {
            attributes.addProperty("TEXCOORD_0", texcoord);
        }
        if (colour >= 0)
        {
            attributes.addProperty("COLOR_0", colour);
        }

        final JsonObject primitive = new JsonObject();
        primitive.add("attributes", attributes);
        if (indices >= 0)
        {
            primitive.addProperty("indices", indices);
        }
        if (material >= 0)
        {
            primitive.addProperty("material", material);
        }
        primitive.addProperty("mode", mode);
        return primitive;
    }

    // Declares a mesh from one or more primitives.
    int mesh(final JsonObject... primitives)
    {
        final JsonArray array = new JsonArray();
        for (final JsonObject primitive : primitives)
        {
            array.add(primitive);
        }
        final JsonObject mesh = new JsonObject();
        mesh.add("primitives", array);
        meshes.add(mesh);
        return meshes.size() - 1;
    }

    // Declares a node holding a mesh, with optional TRS components.
    int node(final int meshIndex, final float[] translation, final float[] rotation,
        final float[] scale)
    {
        final JsonObject node = new JsonObject();
        if (meshIndex >= 0)
        {
            node.addProperty("mesh", meshIndex);
        }
        addVector(node, "translation", translation);
        addVector(node, "rotation", rotation);
        addVector(node, "scale", scale);
        nodes.add(node);
        return nodes.size() - 1;
    }

    // Declares a node carrying an explicit column-major matrix.
    int matrixNode(final int meshIndex, final float[] columnMajor)
    {
        final JsonObject node = new JsonObject();
        node.addProperty("mesh", meshIndex);
        addVector(node, "matrix", columnMajor);
        nodes.add(node);
        return nodes.size() - 1;
    }

    // Attaches children to an existing node.
    void children(final int nodeIndex, final int... childIndices)
    {
        final JsonArray array = new JsonArray();
        for (final int child : childIndices)
        {
            array.add(child);
        }
        nodes.get(nodeIndex).getAsJsonObject().add("children", array);
    }

    // Declares the default scene over the given root nodes.
    void scene(final int... rootNodes)
    {
        final JsonArray array = new JsonArray();
        for (final int node : rootNodes)
        {
            array.add(node);
        }
        final JsonObject scene = new JsonObject();
        scene.add("nodes", array);
        scenes.add(scene);
    }

    // ---- Serialisation ---------------------------------------------------

    // Returns the assembled JSON document.
    JsonObject document()
    {
        pad4();
        final JsonObject document = root.deepCopy();
        addIfPresent(document, "bufferViews", bufferViews);
        addIfPresent(document, "accessors", accessors);
        addIfPresent(document, "meshes", meshes);
        addIfPresent(document, "materials", materials);
        addIfPresent(document, "images", images);
        addIfPresent(document, "textures", textures);
        addIfPresent(document, "nodes", nodes);
        addIfPresent(document, "scenes", scenes);
        return document;
    }

    // A self-contained .gltf: the buffer travels as a base64 data URI.
    byte[] gltf()
    {
        final JsonObject document = document();
        final byte[] bytes = binary.toByteArray();
        if (bytes.length > 0)
        {
            final JsonObject buffer = new JsonObject();
            buffer.addProperty("byteLength", bytes.length);
            buffer.addProperty("uri", "data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(bytes));
            final JsonArray buffers = new JsonArray();
            buffers.add(buffer);
            document.add("buffers", buffers);
        }
        return document.toString().getBytes(StandardCharsets.UTF_8);
    }

    // The same asset as a GLB container: JSON chunk then BIN chunk.
    byte[] glb()
    {
        final JsonObject document = document();
        final byte[] bytes = binary.toByteArray();
        if (bytes.length > 0)
        {
            final JsonObject buffer = new JsonObject();
            buffer.addProperty("byteLength", bytes.length);
            final JsonArray buffers = new JsonArray();
            buffers.add(buffer);
            document.add("buffers", buffers);
        }
        return glb(document.toString().getBytes(StandardCharsets.UTF_8), bytes);
    }

    // Wraps arbitrary JSON and BIN payloads into a GLB, padding as specified.
    static byte[] glb(final byte[] json, final byte[] bin)
    {
        final byte[] jsonChunk = padTo4(json, (byte) ' ');
        final byte[] binChunk = padTo4(bin, (byte) 0);
        final int total = 12 + 8 + jsonChunk.length + chunkSize(binChunk);

        final ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(GLB_MAGIC);
        out.putInt(2);
        out.putInt(total);
        out.putInt(jsonChunk.length);
        out.putInt(CHUNK_JSON);
        out.put(jsonChunk);
        if (binChunk.length > 0)
        {
            out.putInt(binChunk.length);
            out.putInt(CHUNK_BIN);
            out.put(binChunk);
        }
        return out.array();
    }

    // A structurally valid GLB carrying only a BIN chunk. The specification
    // requires the JSON chunk to come first and to be present.
    static byte[] binOnlyGlb()
    {
        final ByteBuffer out = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(GLB_MAGIC);
        out.putInt(2);
        out.putInt(24);
        out.putInt(4);
        out.putInt(CHUNK_BIN);
        out.putInt(0);
        return out.array();
    }

    // ---- Encoding helpers -------------------------------------------------

    // Encodes an RGBA8888 image as PNG, which is what glTF embeds.
    static byte[] png(final int width, final int height, final int[] rgba)
    {
        final BufferedImage image = new BufferedImage(width, height,
            BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                final int texel = rgba[(y * width) + x];
                image.setRGB(x, y, (Rgba.alpha(texel) << 24) | (Rgba.red(texel) << 16)
                    | (Rgba.green(texel) << 8) | Rgba.blue(texel));
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try
        {
            ImageIO.write(image, "png", out);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("cannot encode fixture PNG", e);
        }
        return out.toByteArray();
    }

    // A solid-colour image of the given size.
    static byte[] solidPng(final int width, final int height, final int colour)
    {
        final int[] texels = new int[width * height];
        Arrays.fill(texels, colour);
        return png(width, height, texels);
    }

    // Little-endian float payload.
    static byte[] floatBytes(final float... values)
    {
        final ByteBuffer out = ByteBuffer.allocate(values.length * Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (final float value : values)
        {
            out.putFloat(value);
        }
        return out.array();
    }

    // Little-endian index payload of the requested width.
    static byte[] indexBytes(final int componentType, final int... values)
    {
        final int width = indexWidth(componentType);
        final ByteBuffer out = ByteBuffer.allocate(values.length * width)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (final int value : values)
        {
            if (width == 1)
            {
                out.put((byte) value);
            }
            else if (width == 2)
            {
                out.putShort((short) value);
            }
            else
            {
                out.putInt(value);
            }
        }
        return out.array();
    }

    private static int indexWidth(final int componentType)
    {
        if (componentType == UNSIGNED_BYTE)
        {
            return 1;
        }
        if (componentType == UNSIGNED_SHORT)
        {
            return 2;
        }
        if (componentType == UNSIGNED_INT)
        {
            return 4;
        }
        throw new IllegalArgumentException("not an index component type: " + componentType);
    }

    private static void addVector(final JsonObject node, final String name, final float[] values)
    {
        if (values == null)
        {
            return;
        }
        final JsonArray array = new JsonArray();
        for (final float value : values)
        {
            array.add(value);
        }
        node.add(name, array);
    }

    private static void addIfPresent(final JsonObject document, final String name,
        final JsonArray array)
    {
        if (array.size() > 0)
        {
            document.add(name, array);
        }
    }

    // Pads the binary buffer so the next view starts 4-byte aligned.
    private void pad4()
    {
        while (binary.size() % 4 != 0)
        {
            binary.write(0);
        }
    }

    private static byte[] padTo4(final byte[] data, final byte filler)
    {
        final int padded = (data.length + 3) & ~3;
        final byte[] out = new byte[padded];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < padded; i++)
        {
            out[i] = filler;
        }
        return out;
    }

    private static int chunkSize(final byte[] chunk)
    {
        if (chunk.length == 0)
        {
            return 0;
        }
        return chunk.length + 8;
    }
}
