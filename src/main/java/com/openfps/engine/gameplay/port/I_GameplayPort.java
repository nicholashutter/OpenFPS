/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.port;

/**
 * P_ Port interface — gameplay logic.
 * Called once per tic from the game loop after input sampling.
 */
public interface I_GameplayPort
{
    /**
     * Processes one game tic — advances player physics,
     * entity updates, and map logic.
     *
     * @param ticIndex the current tic number
     */
    void tick(final int ticIndex);

    /**
     * Loads a map by name. Frees all tagged game memory.
     *
     * @param mapName name of the map to load (e.g., "E1M1")
     * @return true if the map was found and loaded
     */
    boolean loadMap(final String mapName);

    /**
     * Spawns an entity of the given type at the given position.
     *
     * @param entityType type identifier
     * @param x fixed-point x position
     * @param y fixed-point y position
     * @param z fixed-point z position
     * @return entity ID, or -1 on failure
     */
    int spawnEntity(final int entityType, final int x, final int y, final int z);

    /**
     * Removes an entity from the active list.
     *
     * @param entityId the entity to remove
     */
    void removeEntity(final int entityId);

    /**
     * Initializes the gameplay subsystem.
     */
    void init();

    /**
     * Shuts down the gameplay subsystem.
     */
    void shutdown();
}
