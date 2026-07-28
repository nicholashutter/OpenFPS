/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the demo's asset loading, which is mostly a test of its failure
 * modes.
 *
 * <p>The success path is one {@code Files.readAllBytes} per model and is
 * uninteresting. What matters is that the three outcomes stay distinguishable:
 * a clone with the real kit must not be told it has the fallback, a clone with
 * the fallback must not be allowed to believe it has the kit, and a clone with
 * neither must be told what command to run. Every one of those has been a
 * silent failure in some engine.</p>
 */
@DisplayName("DemoModels")
final class DemoModelsTest
{
    /** Every level piece the kit must supply before it counts as complete. */
    private static final String[] KIT =
    {
        "floor-square.ofm", "wall.ofm", "wall-doorway.ofm", "column.ofm",
        "crate.ofm", "stairs.ofm", "shape-slope.ofm",
    };

    /** Writes a complete level kit under a root. */
    private static void writeKit(final Path root) throws IOException
    {
        for (final String piece : KIT)
        {
            DemoModelFixture.write(root.resolve(DemoModels.LEVEL_DIRECTORY).resolve(piece));
        }
    }

    /** Writes the weapon under a root. */
    private static void writeWeapon(final Path root) throws IOException
    {
        DemoModelFixture.write(root.resolve(DemoModels.WEAPON_DIRECTORY)
            .resolve(DemoModels.WEAPON_MODEL));
    }

    /** Writes the generated fallback room under a root. */
    private static void writeFallback(final Path root) throws IOException
    {
        DemoModelFixture.write(root.resolve(DemoModels.FALLBACK_MODEL));
    }

    @Nested
    @DisplayName("with the real kit")
    final class RealKit
    {
        @Test
        @DisplayName("reports KENNEY_KIT and loads every piece")
        void loadsTheKit(@TempDir final Path root) throws IOException
        {
            writeKit(root);
            writeWeapon(root);

            final DemoModels models = DemoModels.load(root);

            assertThat(models.source()).isEqualTo(DemoModels.Source.KENNEY_KIT);
            assertThat(models.isRealArt()).isTrue();
            assertThat(models.floor()).isNotNull();
            assertThat(models.wall()).isNotNull();
            assertThat(models.doorway()).isNotNull();
            assertThat(models.column()).isNotNull();
            assertThat(models.crate()).isNotNull();
            assertThat(models.stairs()).isNotNull();
            assertThat(models.slope()).isNotNull();
            assertThat(models.weapon()).isNotNull();
            // The fallback is not loaded when it is not needed.
            assertThat(models.room()).isNull();
        }

        @Test
        @DisplayName("survives a missing weapon rather than failing")
        void weaponIsOptional(@TempDir final Path root) throws IOException
        {
            writeKit(root);

            final DemoModels models = DemoModels.load(root);

            assertThat(models.source()).isEqualTo(DemoModels.Source.KENNEY_KIT);
            assertThat(models.weapon()).isNull();
        }
    }

    @Nested
    @DisplayName("with only the generated room")
    final class Fallback
    {
        @Test
        @DisplayName("reports GENERATED_ROOM and exposes no kit pieces")
        void loadsTheFallback(@TempDir final Path root) throws IOException
        {
            writeFallback(root);

            final DemoModels models = DemoModels.load(root);

            assertThat(models.source()).isEqualTo(DemoModels.Source.GENERATED_ROOM);
            assertThat(models.isRealArt()).isFalse();
            assertThat(models.room()).isNotNull();
            // Every kit accessor is null, so nothing can mistake the greybox
            // room for a piece of the real set.
            assertThat(models.floor()).isNull();
            assertThat(models.wall()).isNull();
            assertThat(models.doorway()).isNull();
            assertThat(models.column()).isNull();
            assertThat(models.crate()).isNull();
            assertThat(models.stairs()).isNull();
            assertThat(models.slope()).isNull();
        }

        @Test
        @DisplayName("a PARTIAL kit falls back rather than half-building a room")
        void partialKitIsNotUsed(@TempDir final Path root) throws IOException
        {
            writeKit(root);
            Files.delete(root.resolve(DemoModels.LEVEL_DIRECTORY).resolve("wall.ofm"));
            writeFallback(root);

            final DemoModels models = DemoModels.load(root);

            // A room with no walls is worse than an honest greybox room, and
            // "six of seven pieces" is exactly the state a half-finished
            // regeneration leaves behind.
            assertThat(models.source()).isEqualTo(DemoModels.Source.GENERATED_ROOM);
        }
    }

    @Nested
    @DisplayName("with nothing")
    final class Missing
    {
        @Test
        @DisplayName("fails with a message naming the regeneration command")
        void namesTheCommand(@TempDir final Path root)
        {
            assertThatThrownBy(() -> DemoModels.load(root))
                .isInstanceOf(DemoAssetException.class)
                .hasMessageContaining("regenerateDemoAssets")
                .hasMessageContaining("kenneyRaw");
        }

        @Test
        @DisplayName("names the pieces it could not find")
        void namesTheMissingPieces(@TempDir final Path root)
        {
            assertThatThrownBy(() -> DemoModels.load(root))
                .isInstanceOf(DemoAssetException.class)
                .hasMessageContaining("floor-square.ofm")
                .hasMessageContaining("wall.ofm");
        }

        @Test
        @DisplayName("rejects a null root")
        void rejectsNullRoot()
        {
            assertThatThrownBy(() -> DemoModels.load(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("with a corrupt model")
    final class Corrupt
    {
        @Test
        @DisplayName("fails loudly instead of quietly using the fallback")
        void doesNotDowngradeToFallback(@TempDir final Path root) throws IOException
        {
            writeKit(root);
            writeFallback(root);
            // Present, the right name, the right extension — and not a model.
            Files.write(root.resolve(DemoModels.LEVEL_DIRECTORY).resolve("crate.ofm"),
                "this is not a model".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Substituting greybox for a corrupt asset would hide the
            // corruption behind a working-looking demo, which is the whole
            // failure this class exists to prevent.
            assertThatThrownBy(() -> DemoModels.load(root))
                .isInstanceOf(DemoAssetException.class)
                .hasMessageContaining("crate.ofm");
        }
    }
}
