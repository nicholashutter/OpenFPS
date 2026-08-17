/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.maps;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.tools.mapgen.JsonConfigParser;
import com.openfps.tools.mapgen.MapGenConfig;
import com.openfps.tools.mapgen.MapGenerator;
import com.openfps.tools.mapgen.PrimitiveFactory;

/**
 * Geometric-port test for the 16 shipped maps.
 *
 * <p>For every map, runs the hand-written {@code *MapBuilder.build()} to
 * produce the oracle {@code .ofm} in a temp file, runs the JSON
 * {@code mapgen} pipeline to produce a second temp {@code .ofm}, then
 * compares the two via {@link ModelFormat}: submesh count must match
 * exactly, vertex count must be within &plusmn;5&nbsp;%, and triangle count
 * must be within &plusmn;5&nbsp;%.</p>
 *
 * <p>The texel data is allowed to diverge (the JSON path uses the
 * {@code mapgen} swatches and procedural fallback texels; the
 * hand-written builders use their own per-map procedural texels). What
 * the test pins is the <em>geometric</em> port: the same boxes at the
 * same positions, partitioned into the same submesh groupings.</p>
 *
 * <p>Hand-written builder and JSON config are paired in {@link #CASES}.
 * The hand-written builder class is invoked by reflection (no {@code
 * Class.forName} of an absent class at link time) so the test compiles
 * even if a future pass removes a builder.</p>
 *
 * <h2>Subzero</h2>
 *
 * <p>The user-supplied prompt lists {@code ArcticHpMapBuilder.java} as
 * the builder for {@code arctic-hp}, but no such file exists in the
 * tree. The actual builder is {@code SubzeroMapBuilder.java}, which
 * {@code docs/PROJECT_STATE.md} documents as orphaned. This test
 * nevertheless uses it as the oracle for {@code arctic-hp} (the file
 * does exist; only the registration in {@code Maps.java} and {@code
 * MapLibrary} is missing) and emits a one-line note in the test
 * output.</p>
 */
final class MapgenGeometricPortTest
{
    /** Engine resource root. The Gradle test task runs with cwd set to the
     *  {@code tools} subproject, so the engine tree is one level up. */
    private static final Path ENGINE_MAPS_ROOT = Paths.get("..", "engine", "src", "main",
        "resources", "maps");

    /** JSON config root, alongside the test's cwd. */
    private static final Path CONFIG_ROOT = Paths.get("config", "maps");

    /**
     * One entry per shipped map: id, the hand-written builder's simple
     * class name, and the JSON config filename.
     */
    private static final List<MapCase> CASES = List.of(
        new MapCase("cornerstone",     "CornerstoneMapBuilder"),
        new MapCase("overpass",        "OverpassMapBuilder"),
        new MapCase("tripoint",        "TripointMapBuilder"),
        new MapCase("extraction",      "ExtractionMapBuilder"),
        new MapCase("refinery",        "RefineryMapBuilder"),
        new MapCase("foundry",         "FoundryMapBuilder"),
        new MapCase("pipeline",        "PipelineMapBuilder"),
        new MapCase("storage",         "StorageMapBuilder"),
        new MapCase("crossroads",      "CrossroadsMapBuilder"),
        new MapCase("mesa",            "MesaMapBuilder"),
        new MapCase("sandbar",         "SandbarMapBuilder"),
        new MapCase("stronghold",      "StrongholdMapBuilder"),
        new MapCase("arctic-station",  "ArcticStationMapBuilder"),
        new MapCase("arctic-hp",       "SubzeroMapBuilder"),
        new MapCase("arctic-dom",      "ArcticDomMapBuilder"),
        new MapCase("coldfront",       "ColdfrontMapBuilder")
    );

    /** Vertex and triangle count tolerance, expressed as a fraction (0.05 = 5%). */
    private static final double COUNT_TOLERANCE = 0.05;

    private static String status(final boolean ok)
    {
        if (ok)
        {
            return "OK";
        }
        return "FAIL";
    }

    @TestFactory
    List<DynamicTest> geometricPort() throws IOException
    {
        final List<DynamicTest> tests = new ArrayList<>();

        for (final MapCase mapCase : CASES)
        {
            tests.add(DynamicTest.dynamicTest(
                "geometricPort_" + mapCase.id,
                () -> assertGeometricPort(mapCase)));
        }

        return tests;
    }

    private static void assertGeometricPort(final MapCase mapCase) throws Exception
    {
        final byte[] handWrittenBytes = runHandWrittenBuilder(mapCase);

        final byte[] generatedBytes = generateFromConfig(mapCase);

        final ModelFormat handWritten = ModelFormat.read(handWrittenBytes);
        final ModelFormat generated = ModelFormat.read(generatedBytes);

        final int hwSubs = handWritten.submeshCount();
        final int genSubs = generated.submeshCount();
        final int hwVerts = handWritten.vertexCount();
        final int genVerts = generated.vertexCount();
        final int hwTris = handWritten.triangleCount();
        final int genTris = generated.triangleCount();

        final double vertDiff = percentDiff(hwVerts, genVerts);
        final double triDiff = percentDiff(hwTris, genTris);

        final boolean submeshOk = hwSubs == genSubs;
        final boolean vertOk = Math.abs(vertDiff) <= COUNT_TOLERANCE * 100.0;
        final boolean triOk = Math.abs(triDiff) <= COUNT_TOLERANCE * 100.0;

        final String subStatus = status(submeshOk);
        final String vertStatus = status(vertOk);
        final String triStatus = status(triOk);

        final String summary = String.format(
            "%s: submeshes=%d vs %d %s, verts %d vs %d (%+.2f%%) %s, tris %d vs %d (%+.2f%%) %s",
            mapCase.id,
            genSubs, hwSubs, subStatus,
            genVerts, hwVerts, vertDiff, vertStatus,
            genTris, hwTris, triDiff, triStatus);

        // Always print a one-line summary so the report can capture the run.
        System.out.println(summary);

        assertThat(submeshOk)
            .as("submesh count for " + mapCase.id + " (hand=" + hwSubs + " gen=" + genSubs + ")")
            .isTrue();

        assertThat(vertOk)
            .as("vertex count within 5% for " + mapCase.id
                + " (hand=" + hwVerts + " gen=" + genVerts
                + " diff=" + String.format("%.2f%%", vertDiff) + ")")
            .isTrue();

        assertThat(triOk)
            .as("triangle count within 5% for " + mapCase.id
                + " (hand=" + hwTris + " gen=" + genTris
                + " diff=" + String.format("%.2f%%", triDiff) + ")")
            .isTrue();
    }

    /**
     * Returns the signed percentage difference from {@code hw} to {@code gen},
     * computed as {@code (gen - hw) * 100 / hw}.
     */
    private static double percentDiff(final int hw, final int gen)
    {
        if (hw == 0)
        {
            if (gen == 0)
            {
                return 0.0;
            }
            return 100.0;
        }
        return (gen - hw) * 100.0 / hw;
    }

    private static byte[] runHandWrittenBuilder(final MapCase mapCase) throws Exception
    {
        final String fqcn = "com.openfps.tools." + mapCase.builderClassName;

        final Class<?> cls = Class.forName(fqcn);

        final Method m = findBuildMethod(cls);

        final byte[] bytes = (byte[]) m.invoke(null);

        assertThat(bytes)
            .as("hand-written builder produced bytes for " + mapCase.id)
            .isNotNull();

        return bytes;
    }

    private static Method findBuildMethod(final Class<?> cls)
    {
        for (final Method m : cls.getDeclaredMethods())
        {
            if (!"build".equals(m.getName()))
            {
                continue;
            }

            if (!Modifier.isStatic(m.getModifiers()))
            {
                continue;
            }

            if (m.getParameterCount() != 0)
            {
                continue;
            }

            if (m.getReturnType() != byte[].class)
            {
                continue;
            }

            m.setAccessible(true);

            return m;
        }

        throw new IllegalStateException(
            "no public static byte[] build() on " + cls.getName());
    }

    private static byte[] generateFromConfig(final MapCase mapCase) throws IOException
    {
        final Path configPath = CONFIG_ROOT.resolve(mapCase.id + ".json");

        assertThat(Files.exists(configPath))
            .as("JSON config exists at " + configPath)
            .isTrue();

        final PrimitiveFactory factory = PrimitiveFactory.createDefault();

        final JsonConfigParser parser = new JsonConfigParser(factory);

        final MapGenConfig config = parser.parse(configPath);

        final MapGenerator generator = new MapGenerator(null, factory);

        return generator.generate(config);
    }

    /**
     * One shipped map: its id and the hand-written builder class name.
     * The test pairs the builder's {@code build()} output with the
     * {@code <id>.json} config at {@link #CONFIG_ROOT}.
     */
    private static final class MapCase
    {
        private final String id;
        private final String builderClassName;

        MapCase(final String id, final String builderClassName)
        {
            this.id = id;
            this.builderClassName = builderClassName;
        }
    }
}
