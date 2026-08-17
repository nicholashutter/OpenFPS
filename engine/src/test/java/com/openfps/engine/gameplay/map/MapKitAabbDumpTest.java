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
 * Diagnostic helper: dumps the (min, max) Aabb of every Kenney
 * kit piece and every shipped level .ofm that this pass cares
 * about. The map-fix agent reads the printed extents to verify
 * "the kit floor is at y=0", "the level floor extends y=-6 to
 * y=-2", etc. — numbers the design spec references but never
 * prints.
 *
 * <p>Writes the dump to a file the agent can read after the
 * test run; gradle's test stdout is not always easy to scrape.
 * The asserts are guards so a future CI that picks the test up
 * still produces a passing line.</p>
 */
final class MapKitAabbDumpTest
{
    @Test
    void dumpKitAndLevelAabbs() throws Exception
    {
        final Path assetsRoot = Paths.get("assets/models");

        final List<String> lines = new ArrayList<>();

        for (final String name : new String[] {"floor-square.ofm", "wall.ofm", "column.ofm",
            "crate.ofm", "wall-window-medium.ofm", "wall-corner.ofm"})
        {
            final Path p = assetsRoot.resolve("level").resolve(name);

            if (!Files.exists(p))
            {
                continue;
            }

            final byte[] bytes = Files.readAllBytes(p);

            final ModelFormat m = ModelFormat.read(bytes);

            lines.add(String.format("KIT %-26s minX=%.2f minY=%.2f minZ=%.2f  maxX=%.2f maxY=%.2f"
                + " maxZ=%.2f", name, m.minX(), m.minY(), m.minZ(), m.maxX(), m.maxY(), m.maxZ()));
        }

        for (final String id : new String[] {"refinery", "foundry", "mesa", "arctic-hp"})
        {
            final Path p = Paths.get("engine/src/main/resources/maps/" + id + "/level.ofm");

            if (!Files.exists(p))
            {
                continue;
            }

            final byte[] bytes = Files.readAllBytes(p);

            final ModelFormat m = ModelFormat.read(bytes);

            lines.add(String.format("MAP %-10s minX=%.2f minY=%.2f minZ=%.2f  maxX=%.2f maxY=%.2f"
                + " maxZ=%.2f  tris=%d submeshes=%d", id, m.minX(), m.minY(), m.minZ(), m.maxX(),
                m.maxY(), m.maxZ(), m.indexCount() / 3, m.submeshCount()));
        }

        final Path dumpFile = Paths.get("build/map-kit-aabb-dump.txt");

        Files.createDirectories(dumpFile.getParent());

        Files.write(dumpFile, lines);

        for (final String line : lines)
        {
            System.out.println(line);
        }

        assertThat(assetsRoot).isNotNull();
    }
}
