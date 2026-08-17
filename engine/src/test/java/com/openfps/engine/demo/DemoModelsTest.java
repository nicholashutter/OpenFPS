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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            // Cast because load() is overloaded on Path and ModelSource now,
            // and a bare null names neither.
            assertThatThrownBy(() -> DemoModels.load((Path) null))
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

    /**
     * Optional kit pieces: the corner wall, the coloured crate, and
     * the windowed wall.
     *
     * <p>None of these are required for a complete install — a
     * missing optional file is a {@code null} slot, not a build
     * error — but the {@code MapScene} kit composer uses them when
     * they are present, and the contract that "present means
     * non-null" / "absent means null" is what that wiring depends
     * on.</p>
     */
    @Nested
    @DisplayName("with optional kit pieces")
    final class OptionalKitPieces
    {
        @Test
        @DisplayName("every optional slot is null when the required kit is staged but no optional files are")
        void absentOptionalPiecesAreNull(@TempDir final Path root) throws IOException
        {
            // The required kit is staged; the three optional files
            // are not. The loader must still report KENNEY_KIT (the
            // optional pieces are not part of "the kit is complete"),
            // and every optional accessor must return null.
            writeKit(root);

            writeWeapon(root);

            final DemoModels models = DemoModels.load(root);

            assertThat(models.source()).isEqualTo(DemoModels.Source.KENNEY_KIT);

            assertThat(models.wallCorner())
                .as("wallCorner() must be null when wall-corner.ofm is not staged")
                .isNull();

            assertThat(models.crateColor())
                .as("crateColor() must be null when crate-color.ofm is not staged")
                .isNull();

            assertThat(models.wallWindow())
                .as("wallWindow() must be null when wall-window-medium.ofm is not staged")
                .isNull();
        }

        @Test
        @DisplayName("every optional slot is populated when the three files are staged alongside the kit")
        void presentOptionalPiecesAreLoaded(@TempDir final Path root) throws IOException
        {
            writeKit(root);

            writeWeapon(root);

            // The optional pieces are NOT part of the required kit
            // file list, so a partial required kit would still be
            // enough to make the loader reach the optional stage.
            // We stage all three optional files at the on-disk
            // names declared by DemoModels.
            for (final String name : DemoModels.OPTIONAL_KIT_FILES)
            {
                DemoModelFixture.write(root.resolve(DemoModels.LEVEL_DIRECTORY).resolve(name));
            }

            final DemoModels models = DemoModels.load(root);

            assertThat(models.source()).isEqualTo(DemoModels.Source.KENNEY_KIT);

            assertThat(models.wallCorner())
                .as("wallCorner() must be non-null when wall-corner.ofm is staged")
                .isNotNull();

            assertThat(models.crateColor())
                .as("crateColor() must be non-null when crate-color.ofm is staged")
                .isNotNull();

            assertThat(models.wallWindow())
                .as("wallWindow() must be non-null when wall-window-medium.ofm is staged")
                .isNotNull();
        }
    }

    /**
     * A {@link ModelSource} backed by a map, which is what an APK looks like
     * from here: entries addressed by name, no filesystem path, nothing to
     * resolve. If the loader works against this it will work against Android's
     * asset manager, and this test needs no device to say so.
     */
    private static final class InMemorySource implements ModelSource
    {
        /** Entry bytes by relative path. */
        private final Map<String, byte[]> entries = new HashMap<>();

        /** Every path {@link #has} was asked about, in order. */
        private final List<String> asked = new ArrayList<>();

        /** Adds a valid model at a path. */
        InMemorySource put(final String path)
        {
            entries.put(path, DemoModelFixture.quad());

            return this;
        }

        /** Adds arbitrary bytes at a path. */
        InMemorySource putRaw(final String path, final byte[] bytes)
        {
            entries.put(path, bytes);

            return this;
        }

        /** Returns every path this source was asked about. */
        List<String> asked()
        {
            return asked;
        }

        @Override
        public boolean has(final String relativePath)
        {
            asked.add(relativePath);

            return entries.containsKey(relativePath);
        }

        @Override
        public byte[] read(final String relativePath) throws IOException
        {
            final byte[] bytes = entries.get(relativePath);

            if (bytes == null)
            {
                throw new IOException("no entry " + relativePath);
            }

            return bytes;
        }

        @Override
        public String describe(final String relativePath)
        {
            return "in-memory:" + relativePath;
        }

        @Override
        public String describeRoot()
        {
            return "in-memory";
        }
    }

    /** A map source carrying a complete level kit. */
    private static InMemorySource sourceWithKit()
    {
        final InMemorySource source = new InMemorySource();

        for (final String piece : KIT)
        {
            source.put(DemoModels.LEVEL_DIRECTORY + "/" + piece);
        }

        return source;
    }

    @Nested
    @DisplayName("from a source that is not a directory")
    final class FromASource
    {
        @Test
        @DisplayName("loads the kit with no filesystem anywhere in the picture")
        void loadsTheKitFromAMap()
        {
            final DemoModels models = DemoModels.load(sourceWithKit()
                .put(DemoModels.WEAPON_DIRECTORY + "/" + DemoModels.WEAPON_MODEL)
                .put(DemoModels.CHARACTER_DIRECTORY + "/character-a.ofm"));

            assertThat(models.source()).isEqualTo(DemoModels.Source.KENNEY_KIT);

            assertThat(models.floor()).isNotNull();

            assertThat(models.weapon()).isNotNull();

            assertThat(models.characters()).hasSize(1);
        }

        @Test
        @DisplayName("asks for slash-separated paths, not the platform separator")
        void usesSlashSeparatedPaths()
        {
            // The convention ModelSource fixes, checked where it is load-bearing:
            // a Windows JVM must still ask for "level/wall.ofm", because a zip
            // entry has no backslash in it on any platform. Nothing in the
            // directory implementation would notice — Path.resolve accepts both
            // — so only a non-filesystem source can catch a regression here.
            final InMemorySource source = sourceWithKit();

            DemoModels.load(source);

            assertThat(source.asked()).contains("level/wall.ofm")
                .allSatisfy(path -> assertThat(path).doesNotContain("\\"));
        }

        @Test
        @DisplayName("still refuses a source with no geometry at all")
        void refusesAnEmptySource()
        {
            assertThatThrownBy(() -> DemoModels.load(new InMemorySource()))
                .isInstanceOf(DemoAssetException.class)
                .hasMessageContaining(DemoModels.REGENERATE_COMMAND);
        }

        @Test
        @DisplayName("still fails loudly on a corrupt entry rather than degrading")
        void refusesACorruptEntry()
        {
            final InMemorySource source = sourceWithKit()
                .putRaw(DemoModels.LEVEL_DIRECTORY + "/crate.ofm", new byte[] {1, 2, 3})
                .put(DemoModels.FALLBACK_MODEL);

            assertThatThrownBy(() -> DemoModels.load(source))
                .isInstanceOf(DemoAssetException.class)
                .hasMessageContaining("crate.ofm");
        }

        @Test
        @DisplayName("rejects a null source")
        void rejectsNullSource()
        {
            assertThatThrownBy(() -> DemoModels.load((ModelSource) null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("DirectoryModelSource")
    final class Directory
    {
        @Test
        @DisplayName("accepts a slash-separated path on any platform")
        void resolvesSlashSeparatedPaths(@TempDir final Path root) throws IOException
        {
            DemoModelFixture.write(root.resolve("level").resolve("wall.ofm"));

            final DirectoryModelSource source = new DirectoryModelSource(root);

            assertThat(source.has("level/wall.ofm")).isTrue();

            assertThat(source.read("level/wall.ofm")).isNotEmpty();

            assertThat(source.has("level/missing.ofm")).isFalse();
        }

        @Test
        @DisplayName("describes a file as an absolute path, so a log line is actionable")
        void describesAbsolutely(@TempDir final Path root)
        {
            final DirectoryModelSource source = new DirectoryModelSource(root);

            assertThat(source.describe("level/wall.ofm"))
                .isEqualTo(root.resolve("level").resolve("wall.ofm").toAbsolutePath().toString());

            assertThat(source.describeRoot()).isEqualTo(root.toAbsolutePath().toString());
        }

        @Test
        @DisplayName("a root that does not exist is not an error until something is asked for")
        void toleratesAnAbsentRoot(@TempDir final Path root)
        {
            // A missing model root must reach DemoModels and be reported with
            // the regenerate command, not die one layer early with a stack
            // trace about a directory.
            final DirectoryModelSource source =
                new DirectoryModelSource(root.resolve("does-not-exist"));

            assertThat(source.has("level/wall.ofm")).isFalse();

            assertThatThrownBy(() -> DemoModels.load(source))
                .isInstanceOf(DemoAssetException.class)
                .hasMessageContaining(DemoModels.REGENERATE_COMMAND);
        }

        @Test
        @DisplayName("rejects a null root")
        void rejectsNullRoot()
        {
            assertThatThrownBy(() -> new DirectoryModelSource(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
