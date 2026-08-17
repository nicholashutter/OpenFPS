/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.render.adapter.ModelFormat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic helper for the four maps assigned to the 4-map fix pass:
 * crossroads, arctic-station, arctic-dom, pipeline. Reads each
 * {@code level.ofm} and dumps every submesh Aabb in the world
 * coordinates the level composer produced. The map-fix agent reads
 * these to decide which waypoints sit inside a wall Aabb.
 *
 * <p>Writes a single dump file at
 * {@code build/four-maps-aabb-dump.txt} that the agent reads after
 * the test run; gradle's test stdout is not always easy to scrape.</p>
 */
final class MapAabbDumpFourMapsTest
{
    @Test
    void dumpFourMapsAabbs() throws Exception
    {
        final String[] ids = {"crossroads", "arctic-station", "arctic-dom", "pipeline"};

        final List<String> lines = new ArrayList<>();

        final Path projectRoot = resolveProjectRoot();

        for (final String id : ids)
        {
            final Path p = projectRoot.resolve("engine/src/main/resources/maps/" + id + "/level.ofm");

            if (!Files.exists(p))
            {
                lines.add("MAP " + id + "  (level.ofm not found)");

                continue;
            }

            final byte[] bytes = Files.readAllBytes(p);

            final ModelFormat m = ModelFormat.read(bytes);

            lines.add(String.format("MAP %-16s minX=%.2f minY=%.2f minZ=%.2f  maxX=%.2f maxY=%.2f"
                + " maxZ=%.2f  tris=%d submeshes=%d", id, m.minX(), m.minY(), m.minZ(), m.maxX(),
                m.maxY(), m.maxZ(), m.indexCount() / 3, m.submeshCount()));

            for (int submesh = 0; submesh < m.submeshCount(); submesh++)
            {
                final int firstIndex = m.submeshFirstIndex(submesh);

                final int indexCount = m.submeshIndexCount(submesh);

                if (indexCount <= 0)
                {
                    continue;
                }

                float minX = Float.POSITIVE_INFINITY;
                float minZ = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY;
                float maxZ = Float.NEGATIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY;
                float maxY = Float.NEGATIVE_INFINITY;

                for (int i = 0; i < indexCount; i++)
                {
                    final int vi = m.indices()[firstIndex + i];

                    final float x = m.positionX(vi);
                    final float z = m.positionZ(vi);

                    if (x < minX)
                    {
                        minX = x;
                    }

                    if (x > maxX)
                    {
                        maxX = x;
                    }

                    if (z < minZ)
                    {
                        minZ = z;
                    }

                    if (z > maxZ)
                    {
                        maxZ = z;
                    }
                }

                final boolean collapsed = !(minX < maxX && minZ < maxZ);

                final String tag;

                if (collapsed)
                {
                    tag = "(degenerate)";
                }
                else
                {
                    tag = "";
                }

                lines.add(String.format(
                    "  %-14s submesh %2d  minX=%7.2f minZ=%7.2f  maxX=%7.2f maxZ=%7.2f"
                        + "  minY=%.2f maxY=%.2f  tris=%d  %s",
                    id, submesh, minX, minZ, maxX, maxZ, minY, maxY, indexCount / 3, tag));
            }
        }

        final Path dumpFile = projectRoot.resolve("build/four-maps-aabb-dump.txt");

        Files.createDirectories(dumpFile.getParent());

        Files.write(dumpFile, lines);

        for (final String line : lines)
        {
            System.out.println(line);
        }

        assertThat(ids).hasSize(4);
    }

    /**
     * Resolves the project root by walking up from the current working
     * directory until a {@code settings.gradle.kts} is found, falling
     * back to the user.dir system property if not. This survives gradle
     * launching the test from {@code engine/} or the project root.
     */
    private static Path resolveProjectRoot()
    {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

        while (current != null)
        {
            if (Files.exists(current.resolve("settings.gradle.kts")))
            {
                return current;
            }

            current = current.getParent();
        }

        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }
}
