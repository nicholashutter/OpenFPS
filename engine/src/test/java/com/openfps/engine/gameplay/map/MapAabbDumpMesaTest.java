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
 * Diagnostic helper: dumps the overall AABB and every submesh AABB
 * of the mesa level .ofm, with X/Y/Z extents and triangle counts.
 * The map-fix agent uses this to verify whether a bot waypoint's
 * (x, z) sits inside a wall Aabb or above a missing floor.
 *
 * <p>Mirrors the four-maps dump in shape so the output is
 * diff-friendly against the previous maps' dumps.</p>
 */
final class MapAabbDumpMesaTest
{
    @Test
    void dumpMesaAabbs() throws Exception
    {
        final String id = "mesa";

        final List<String> lines = new ArrayList<>();

        final Path projectRoot = resolveProjectRoot();

        final Path p = projectRoot.resolve("engine/src/main/resources/maps/" + id + "/level.ofm");

        if (!Files.exists(p))
        {
            lines.add("MAP " + id + "  (level.ofm not found at " + p + ")");
        }
        else
        {
            final byte[] bytes = Files.readAllBytes(p);

            final ModelFormat m = ModelFormat.read(bytes);

            // The AABB line uses minX/minY/minZ and maxX/maxY/maxZ, then
            // the total triangle count and the submesh count.
            final float minX = m.minX();
            final float minY = m.minY();
            final float minZ = m.minZ();
            final float maxX = m.maxX();
            final float maxY = m.maxY();
            final float maxZ = m.maxZ();
            final int totalTris = m.indexCount() / 3;
            final int submeshCount = m.submeshCount();

            lines.add(String.format(
                "MAP %-10s minX=%.2f minY=%.2f minZ=%.2f  maxX=%.2f maxY=%.2f"
                    + " maxZ=%.2f  tris=%d submeshes=%d",
                id, minX, minY, minZ, maxX, maxY, maxZ, totalTris, submeshCount));

            for (int submesh = 0; submesh < submeshCount; submesh++)
            {
                final int firstIndex = m.submeshFirstIndex(submesh);
                final int indexCount = m.submeshIndexCount(submesh);

                if (indexCount <= 0)
                {
                    lines.add(String.format("  %-10s submesh %2d  (empty)", id, submesh));
                    continue;
                }

                float subMinX = Float.POSITIVE_INFINITY;
                float subMinY = Float.POSITIVE_INFINITY;
                float subMinZ = Float.POSITIVE_INFINITY;
                float subMaxX = Float.NEGATIVE_INFINITY;
                float subMaxY = Float.NEGATIVE_INFINITY;
                float subMaxZ = Float.NEGATIVE_INFINITY;

                for (int i = 0; i < indexCount; i++)
                {
                    final int vi = m.indices()[firstIndex + i];
                    final float x = m.positionX(vi);
                    final float y = m.positionY(vi);
                    final float z = m.positionZ(vi);

                    if (x < subMinX) subMinX = x;
                    if (x > subMaxX) subMaxX = x;
                    if (y < subMinY) subMinY = y;
                    if (y > subMaxY) subMaxY = y;
                    if (z < subMinZ) subMinZ = z;
                    if (z > subMaxZ) subMaxZ = z;
                }

                final boolean collapsed = !(subMinX < subMaxX && subMinZ < subMaxZ);

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
                    "  %-10s submesh %2d  X[%7.2f..%7.2f]  Y[%7.2f..%7.2f]"
                        + "  Z[%7.2f..%7.2f]  tris=%d  %s",
                    id, submesh, subMinX, subMaxX, subMinY, subMaxY, subMinZ, subMaxZ,
                    indexCount / 3, tag));
            }
        }

        final Path dumpFile = projectRoot.resolve("build/mesa-aabb-dump.txt");

        Files.createDirectories(dumpFile.getParent());

        Files.write(dumpFile, lines);

        for (final String line : lines)
        {
            System.out.println(line);
        }

        assertThat(lines).isNotEmpty();
    }

    /**
     * Resolves the project root by walking up from the current working
     * directory until a {@code settings.gradle.kts} is found, falling
     * back to the user.dir system property if not.
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
