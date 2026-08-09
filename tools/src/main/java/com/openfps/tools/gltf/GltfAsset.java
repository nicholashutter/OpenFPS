/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.gltf;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * A loaded glTF 2.0 asset: its JSON document plus its resolved binary buffers.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>What this handles</h2>
 *
 * Both container forms the specification defines:
 * <ul>
 *   <li><b>GLB</b> — a 12-byte header followed by length-prefixed chunks, the
 *       first JSON and the optional second BIN. This is what Kenney ships.</li>
 *   <li><b>.gltf</b> — JSON on disk, with buffers and images either embedded
 *       as {@code data:} URIs or sitting in sibling files.</li>
 * </ul>
 *
 * <h2>Where the parsing comes from</h2>
 *
 * The JSON chunk is parsed by Gson (Apache-2.0), which
 * {@code docs/ASSETS.md} § 4 sanctions because build-time tooling ships
 * nothing. Everything structural — the GLB container, chunk framing, URI
 * resolution — is written here against the published Khronos specification.
 * Per {@code docs/ASSETS.md} § 8, specifications are cited and implementations
 * are not: reading a published spec is unambiguous where reading a
 * copyleft implementation to learn a format is not.
 *
 * Source — Khronos Group, glTF 2.0 Specification, § 3 (concepts) and § 4.4
 * (GLB file format) — https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
 */
public final class GltfAsset
{
    /** GLB container magic: the ASCII bytes {@code glTF} read little-endian. */
    private static final int GLB_MAGIC = 0x46546C67;

    /** GLB chunk type {@code JSON}. */
    private static final int CHUNK_JSON = 0x4E4F534A;

    /** GLB chunk type {@code BIN\0}. */
    private static final int CHUNK_BIN = 0x004E4942;

    /** Bytes in the GLB file header: magic, version, total length. */
    private static final int GLB_HEADER_BYTES = 12;

    /** Bytes in a GLB chunk header: length and type. */
    private static final int GLB_CHUNK_HEADER_BYTES = 8;

    /** The only GLB container version the specification defines. */
    private static final int GLB_VERSION = 2;

    /** Prefix identifying an inline data URI. */
    private static final String DATA_URI_PREFIX = "data:";

    /** Marker separating a data URI's media type from its base64 payload. */
    private static final String BASE64_MARKER = ";base64,";

    private final String name;
    private final Path baseDirectory;
    private final JsonObject root;
    private final byte[] binaryChunk;
    private final byte[][] buffers;

    // Holds a parsed document. Buffers are resolved lazily and cached.
    private GltfAsset(final String name, final Path baseDirectory, final JsonObject root,
        final byte[] binaryChunk)
    {
        this.name = name;

        this.baseDirectory = baseDirectory;

        this.root = root;

        this.binaryChunk = binaryChunk;

        this.buffers = new byte[optionalArray(root, "buffers").size()][];
    }

    /**
     * Loads a {@code .gltf} or {@code .glb} file from disk.
     *
     * <p>The container form is detected from the file's first four bytes, not
     * from its extension — a misnamed file is a plausible mistake and the
     * magic is authoritative.</p>
     *
     * @param path the file to load
     * @return the parsed asset
     * @throws GltfException if the file cannot be read or is not valid glTF 2.0
     */
    public static GltfAsset load(final Path path)
    {
        try
        {
            return of(path.getFileName().toString(), path.toAbsolutePath().getParent(),
                Files.readAllBytes(path));
        }
        catch (final IOException e)
        {
            throw new GltfException("cannot read " + path, e);
        }
    }

    /**
     * Parses an in-memory asset image, GLB or JSON.
     *
     * <p>Exposed so tests can build a fixture without touching the filesystem,
     * which is what keeps the converter's test suite hermetic.</p>
     *
     * @param name asset name, quoted in error messages
     * @param baseDirectory directory external URIs resolve against; may be null
     *     when the asset is self-contained
     * @param bytes the whole file
     * @return the parsed asset
     * @throws GltfException if the bytes are not valid glTF 2.0
     */
    public static GltfAsset of(final String name, final Path baseDirectory, final byte[] bytes)
    {
        if (bytes.length >= Integer.BYTES && magicOf(bytes) == GLB_MAGIC)
        {
            return fromGlb(name, baseDirectory, bytes);
        }

        return new GltfAsset(name, baseDirectory,
            parseJson(name, new String(bytes, StandardCharsets.UTF_8)), null);
    }

    /** Returns the asset's name, as used in diagnostics. */
    public String name()
    {
        return name;
    }

    /** Returns the root JSON object of the glTF document. */
    public JsonObject root()
    {
        return root;
    }

    /**
     * Returns a top-level array, or an empty array when it is absent.
     *
     * @param name the array property, for example {@code "meshes"}
     * @return the array; never null
     */
    public JsonArray array(final String name)
    {
        return optionalArray(root, name);
    }

    /**
     * Returns one element of a top-level array.
     *
     * @param arrayName the array property, for example {@code "accessors"}
     * @param index element index
     * @return the element as an object
     * @throws GltfException if the index is out of range or the element is not
     *     an object
     */
    public JsonObject item(final String arrayName, final int index)
    {
        final JsonArray items = optionalArray(root, arrayName);

        if (index < 0 || index >= items.size())
        {
            throw new GltfException(name + ": " + arrayName + "[" + index + "] is out of range; "
                + items.size() + " present");
        }

        final JsonElement element = items.get(index);

        if (!element.isJsonObject())
        {
            throw new GltfException(name + ": " + arrayName + "[" + index + "] is not an object");
        }

        return element.getAsJsonObject();
    }

    /**
     * Returns the bytes of one glTF buffer, resolving its URI on first use.
     *
     * <p>Three sources are possible and all three are handled: the GLB binary
     * chunk when a buffer declares no URI, an inline base64 {@code data:} URI,
     * and a relative path resolved against the asset's directory.</p>
     *
     * @param index buffer index
     * @return the buffer's bytes; the array is cached and must not be mutated
     * @throws GltfException if the buffer cannot be resolved
     */
    public byte[] buffer(final int index)
    {
        if (index < 0 || index >= buffers.length)
        {
            throw new GltfException(name + ": buffer " + index + " is out of range; "
                + buffers.length + " present");
        }

        if (buffers[index] == null)
        {
            buffers[index] = resolveBuffer(index);
        }

        return buffers[index];
    }

    /**
     * Returns the encoded bytes of one glTF image, from a URI or a buffer view.
     *
     * @param index image index
     * @return the encoded image, still in its source format (PNG, JPEG, ...)
     * @throws GltfException if the image cannot be resolved
     */
    public byte[] imageBytes(final int index)
    {
        final JsonObject image = item("images", index);

        if (image.has("uri"))
        {
            return resolveUri(image.get("uri").getAsString(), "image " + index);
        }

        if (!image.has("bufferView"))
        {
            throw new GltfException(name + ": image " + index + " has neither uri nor bufferView");
        }

        final JsonObject view = item("bufferViews", image.get("bufferView").getAsInt());

        final byte[] source = buffer(view.get("buffer").getAsInt());

        final int offset = optionalInt(view, "byteOffset", 0);

        final int length = view.get("byteLength").getAsInt();

        if (offset < 0 || length < 0 || (long) offset + length > source.length)
        {
            throw new GltfException(name + ": image " + index + " bufferView [" + offset + ", +"
                + length + ") runs past its " + source.length + "-byte buffer");
        }

        final byte[] encoded = new byte[length];

        System.arraycopy(source, offset, encoded, 0, length);

        return encoded;
    }

    /**
     * Returns an integer property, or a default when it is absent or null.
     *
     * @param object the JSON object to read
     * @param property the property name
     * @param fallback value to return when the property is not present
     * @return the property's value, or {@code fallback}
     */
    public static int optionalInt(final JsonObject object, final String property,
        final int fallback)
    {
        final JsonElement element = object.get(property);

        if (element == null || element.isJsonNull())
        {
            return fallback;
        }

        return element.getAsInt();
    }

    /**
     * Returns a boolean property, or a default when it is absent or null.
     *
     * @param object the JSON object to read
     * @param property the property name
     * @param fallback value to return when the property is not present
     * @return the property's value, or {@code fallback}
     */
    public static boolean optionalBoolean(final JsonObject object, final String property,
        final boolean fallback)
    {
        final JsonElement element = object.get(property);

        if (element == null || element.isJsonNull())
        {
            return fallback;
        }

        return element.getAsBoolean();
    }

    /**
     * Returns an array property, or an empty array when it is absent or null.
     *
     * @param object the JSON object to read
     * @param property the property name
     * @return the array; never null
     */
    public static JsonArray optionalArray(final JsonObject object, final String property)
    {
        final JsonElement element = object.get(property);

        if (element == null || !element.isJsonArray())
        {
            return new JsonArray();
        }

        return element.getAsJsonArray();
    }

    // ---- GLB container -------------------------------------------------

    // Splits a GLB into its JSON and BIN chunks. Specification section 4.4.
    private static GltfAsset fromGlb(final String name, final Path baseDirectory,
        final byte[] bytes)
    {
        if (bytes.length < GLB_HEADER_BYTES)
        {
            throw new GltfException(name + ": GLB file is " + bytes.length
                + " bytes; the header alone needs " + GLB_HEADER_BYTES);
        }

        final ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        final int version = in.getInt(Integer.BYTES);

        if (version != GLB_VERSION)
        {
            throw new GltfException(name + ": GLB container version " + version
                + "; only version " + GLB_VERSION + " is defined");
        }

        final int declaredLength = in.getInt(2 * Integer.BYTES);

        if (declaredLength != bytes.length)
        {
            throw new GltfException(name + ": GLB declares " + declaredLength + " bytes but is "
                + bytes.length + " — truncated or padded");
        }

        // MUTABLE locals — the chunk scan's cursor and its two results.
        int cursor = GLB_HEADER_BYTES;

        String json = null;

        byte[] binary = null;

        while (cursor + GLB_CHUNK_HEADER_BYTES <= bytes.length)
        {
            final int chunkLength = in.getInt(cursor);

            final int chunkType = in.getInt(cursor + Integer.BYTES);

            final int dataStart = cursor + GLB_CHUNK_HEADER_BYTES;

            if (chunkLength < 0 || (long) dataStart + chunkLength > bytes.length)
            {
                throw new GltfException(name + ": GLB chunk at byte " + cursor + " declares "
                    + Integer.toUnsignedString(chunkLength)
                    + " bytes, which runs past the end of the file");
            }

            if (chunkType == CHUNK_JSON && json == null)
            {
                json = new String(bytes, dataStart, chunkLength, StandardCharsets.UTF_8);
            }
            else if (chunkType == CHUNK_BIN && binary == null)
            {
                binary = new byte[chunkLength];

                System.arraycopy(bytes, dataStart, binary, 0, chunkLength);
            }

            // Chunk data is padded to a 4-byte boundary; unknown types are
            // skipped, which is what the specification requires of a reader.
            cursor = dataStart + align4(chunkLength);
        }

        if (json == null)
        {
            throw new GltfException(name + ": GLB contains no JSON chunk");
        }

        return new GltfAsset(name, baseDirectory, parseJson(name, json), binary);
    }

    // Rounds a chunk length up to the 4-byte alignment GLB mandates.
    private static int align4(final int length)
    {
        return (length + 3) & ~3;
    }

    // Reads the first four bytes as a little-endian int.
    private static int magicOf(final byte[] bytes)
    {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
    }

    // Parses the JSON document and checks it claims glTF 2.x.
    private static JsonObject parseJson(final String name, final String json)
    {
        final JsonElement parsed;

        try
        {
            parsed = JsonParser.parseString(json);
        }
        catch (final JsonParseException e)
        {
            throw new GltfException(name + ": JSON is malformed", e);
        }

        if (!parsed.isJsonObject())
        {
            throw new GltfException(name + ": top-level JSON is not an object");
        }

        final JsonObject document = parsed.getAsJsonObject();

        final JsonElement asset = document.get("asset");

        if (asset == null || !asset.isJsonObject() || !asset.getAsJsonObject().has("version"))
        {
            throw new GltfException(name + ": no asset.version — not a glTF document");
        }

        final String version = asset.getAsJsonObject().get("version").getAsString();

        if (!version.startsWith("2."))
        {
            throw new GltfException(name + ": glTF version " + version
                + "; this converter implements 2.x only");
        }

        return document;
    }

    // ---- Buffer and URI resolution -------------------------------------

    // Resolves one buffer from the GLB binary chunk, a data URI, or a file.
    private byte[] resolveBuffer(final int index)
    {
        final JsonObject descriptor = item("buffers", index);

        if (!descriptor.has("uri"))
        {
            if (binaryChunk == null)
            {
                throw new GltfException(name + ": buffer " + index
                    + " has no uri, but the asset has no GLB binary chunk to take it from");
            }

            return binaryChunk;
        }

        return resolveUri(descriptor.get("uri").getAsString(), "buffer " + index);
    }

    // Resolves a data: URI or a relative file reference to bytes.
    private byte[] resolveUri(final String uri, final String what)
    {
        if (uri.toLowerCase(Locale.ROOT).startsWith(DATA_URI_PREFIX))
        {
            return decodeDataUri(uri, what);
        }

        if (baseDirectory == null)
        {
            throw new GltfException(name + ": " + what + " references the external file '" + uri
                + "', but the asset was loaded without a base directory");
        }

        final Path target = baseDirectory.resolve(decodePath(uri, what));

        try
        {
            return Files.readAllBytes(target);
        }
        catch (final IOException e)
        {
            throw new GltfException(name + ": " + what + " references '" + uri
                + "', which could not be read from " + target, e);
        }
    }

    // Decodes the base64 payload of a data URI.
    private byte[] decodeDataUri(final String uri, final String what)
    {
        final int marker = uri.indexOf(BASE64_MARKER);

        if (marker < 0)
        {
            throw new GltfException(name + ": " + what
                + " uses a data URI that is not base64-encoded; only base64 is supported");
        }

        try
        {
            return Base64.getDecoder().decode(uri.substring(marker + BASE64_MARKER.length()));
        }
        catch (final IllegalArgumentException e)
        {
            throw new GltfException(name + ": " + what + " has a malformed base64 payload", e);
        }
    }

    // Percent-decodes a relative URI reference into a filesystem path fragment.
    // java.net.URI is used rather than URLDecoder because URLDecoder also maps
    // '+' to a space, which is form encoding, not URI encoding, and would
    // corrupt any filename containing a plus.
    private String decodePath(final String uri, final String what)
    {
        try
        {
            final String path = new URI(uri).getPath();

            if (path == null)
            {
                throw new GltfException(name + ": " + what + " has no usable path in '" + uri + "'");
            }

            return path;
        }
        catch (final URISyntaxException e)
        {
            throw new GltfException(name + ": " + what + " has a malformed URI '" + uri + "'", e);
        }
    }
}
