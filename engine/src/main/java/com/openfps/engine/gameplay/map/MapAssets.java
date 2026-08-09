/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

/**
 * The asset paths a map's renderer needs.
 *
 * <p>Three paths, all expressed relative to the engine's asset root. The
 * {@code level} path is the one .ofm model holding the world geometry; the
 * {@code weapon} path is the player's viewmodel; and the {@code atlas} path
 * (optional) is a texture atlas to share across the level and the weapon
 * when they are both authored against one. The atlas is not optional in
 * principle — every Kenney kit ships one — but a procedurally generated
 * map has its own per-model textures and the atlas slot is empty.</p>
 *
 * <p>None of the paths are validated at construction; that is
 * {@code I_FilePort}'s job, and the engine will fail to load a missing asset
 * with a clear error rather than letting the wrong model slip into the
 * scene.</p>
 *
 * @param level  the level kit's .ofm path; must not be null
 * @param weapon the player's viewmodel .ofm path; must not be null
 * @param atlas  an optional shared texture atlas, or null
 */
public record MapAssets(String level, String weapon, String atlas)
{
    /**
     * Creates a {@code MapAssets} after validating the required paths.
     *
     * @throws IllegalArgumentException if {@code level} or {@code weapon}
     *     is null or blank
     */
    public MapAssets
    {
        if (level == null || level.isBlank())
        {
            throw new IllegalArgumentException("level must not be null or blank");
        }

        if (weapon == null || weapon.isBlank())
        {
            throw new IllegalArgumentException("weapon must not be null or blank");
        }

        level = level.intern();

        weapon = weapon.intern();

        if (atlas != null && atlas.isBlank())
        {
            atlas = null;
        }

        if (atlas != null)
        {
            atlas = atlas.intern();
        }
    }
}
