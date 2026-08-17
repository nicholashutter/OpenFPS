/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import com.openfps.engine.core.GameConfig;
import com.openfps.engine.demo.DemoModels;
import com.openfps.engine.gameplay.adapter.NullGameplayPort;
import com.openfps.engine.gameplay.port.DelegatingGameplayPort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * P_ The seam between a map id and a running map-mode game.
 *
 * <p>Owns one {@link MapSpec} + {@link MapScene} + {@link MapGameplayPort}
 * at a time. A launcher that wants to load or unload a map &mdash; the
 * menu-driven flow is the case, but the {@code --map=} CLI path is
 * another &mdash; calls {@link #loadMap(String)} or {@link #unload()} and
 * gets the rest of the engine in the right state:
 * {@link MapScene#scene()} is bound to the renderer, the crosshair is
 * enabled, and the swappable {@link DelegatingGameplayPort} now points
 * at a fresh per-tic loop.</p>
 *
 * <p>The launcher is still the only object that knows about
 * {@code --map=}, the {@code I_AdapterFactory} and the
 * {@link com.openfps.engine.render.adapter.SoftwareRenderPort}. This
 * class is the only object that knows how to turn a {@link MapSpec} into
 * a running port, and how to put the renderer back to "no world loaded"
 * when the user returns to the menu. That is the entire split.</p>
 *
 * <p>Threading: every method on this class runs on the platform's
 * render thread. The renderer's {@code setScene} takes an internal
 * frame lock; the delegating port's {@code setActual} is an atomic
 * swap. Both are safe to call from the render thread against an engine
 * that is ticking on its own thread.</p>
 */
public final class MapRuntime
{
    private static final Logger LOG = LoggerFactory.getLogger(MapRuntime.class);

    private final SoftwareRenderPort renderer;
    private final I_InputPort input;
    private final GameConfig config;
    private final Team playerTeam;
    private final int spawnIndex;
    private final DelegatingGameplayPort port;
    private final DemoModels models;

    private MapSpec spec;
    private MapScene scene;
    private MapGameplayPort mapPort;

    /**
     * Builds a runtime that will load maps against the given renderer
     * and the engine's input port.
     *
     * @param renderer the renderer that will draw the map; must not be null
     * @param input the engine's input port; must not be null
     * @param config the running game config; must not be null
     * @param playerTeam the team the local player is on; must not be null
     * @param spawnIndex the index into the team's spawn list, or -1 for
     *     the first spawn of any team (the single-player case)
     * @param port the swappable port the engine is ticking; must not be
     *     null
     */
    public MapRuntime(final SoftwareRenderPort renderer, final I_InputPort input,
        final GameConfig config, final Team playerTeam, final int spawnIndex,
        final DelegatingGameplayPort port)
    {
        this(renderer, input, config, playerTeam, spawnIndex, port, null);
    }

    /**
     * Builds a runtime that will load maps against the given renderer,
     * the engine's input port, and a pre-loaded {@code DemoModels}.
     *
     * <p>The {@code models} parameter is what makes the map look like
     * a room: the Kenney kit (floor, ceiling, perimeter walls, columns,
     * crates), the bot characters + weapons, the local player's
     * first-person arms, the held viewmodel, and the tracer/smoke
     * effect pool. Without it, the 1-arg {@link MapScene#build} path
     * is taken and the player ends up on a bare level with no walls
     * and no bots — the NPE-causing path the menu pick used to take.</p>
     *
     * @param renderer the renderer that will draw the map; must not be null
     * @param input the engine's input port; must not be null
     * @param config the running game config; must not be null
     * @param playerTeam the team the local player is on; must not be null
     * @param spawnIndex the index into the team's spawn list, or -1 for
     *     the first spawn of any team (the single-player case)
     * @param port the swappable port the engine is ticking; must not be
     *     null
     * @param models the loaded Kenney kit + characters + weapons, or
     *     null to fall back to the level-only build (the headless
     *     smoke path that does not need the kit)
     */
    public MapRuntime(final SoftwareRenderPort renderer, final I_InputPort input,
        final GameConfig config, final Team playerTeam, final int spawnIndex,
        final DelegatingGameplayPort port,
        final com.openfps.engine.demo.DemoModels models)
    {
        if (renderer == null)
        {
            throw new IllegalArgumentException("renderer must not be null");
        }

        if (input == null)
        {
            throw new IllegalArgumentException("input must not be null");
        }

        if (config == null)
        {
            throw new IllegalArgumentException("config must not be null");
        }

        if (playerTeam == null)
        {
            throw new IllegalArgumentException("playerTeam must not be null");
        }

        if (port == null)
        {
            throw new IllegalArgumentException("port must not be null");
        }

        this.renderer = renderer;

        this.input = input;

        this.config = config;

        this.playerTeam = playerTeam;

        this.spawnIndex = spawnIndex;

        this.port = port;

        this.models = models;
    }

    /**
     * Loads the named map, replacing any currently loaded map. The
     * renderer's scene is updated, the crosshair is enabled, and the
     * delegating port is swapped to a fresh per-tic loop. A null return
     * means the id was unknown to {@link MapLoader} and nothing was
     * loaded.
     *
     * <p>Loading the same id twice is allowed; the second call tears
     * down the first and rebuilds. That is the "back to menu and pick
     * the same map" case the launcher's unload path supports.</p>
     *
     * @param mapId the id of the map to load; must not be null or blank
     * @return the spec that was loaded, or null if the id is unknown
     */
    public MapSpec loadMap(final String mapId)
    {
        if (mapId == null || mapId.isBlank())
        {
            throw new IllegalArgumentException("mapId must not be null or blank");
        }

        if (hasMap())
        {
            unload();
        }

        final MapSpec newSpec = MapLoader.load(mapId);

        if (newSpec == null)
        {
            LOG.warn("MapRuntime.loadMap: unknown map id '{}' — no spec registered", mapId);

            return null;
        }

        final MapScene newScene;

        if (models != null)
        {
            newScene = MapScene.build(newSpec, models);
        }
        else
        {
            // Headless smoke path: no kit, no bots, no viewmodel. The
            // per-tic loop runs against the level-only scene, which is
            // what the smoke tests need to assert "bots alive, no
            // damage" without dragging in the visual machinery.
            newScene = MapScene.build(newSpec);
        }

        final MapGameplayPort newPort = MapGameplayPort.create(newSpec, input, renderer, config,
            playerTeam, spawnIndex);

        // The match gate is the contract that freezes the port when the
        // menu is in front and unfreezes it when the player enters the
        // world, but the gate fires on UI state CHANGES — a fresh
        // loadMap that lands on a port that is already "playing" still
        // needs an explicit flip, because there is no state change to
        // observe. Without this, a load followed by an immediate
        // LOADING -> PLAYING transition would race: the new port is
        // built with matchLive=false, the gate hook is re-attached to
        // the same UI state, and the gate's "we were already in this
        // state" guard (see DesktopLauncher.createMatchGate) suppresses
        // its own flip. Setting matchLive=true here makes the port live
        // unconditionally, and the gate hook is only ever asked to
        // freeze it again when the player returns to the menu.
        newPort.setMatchLive(true);

        renderer.setScene(newScene.scene());

        renderer.setCrosshairEnabled(true);

        port.setActual(newPort);

        this.spec = newSpec;

        this.scene = newScene;

        this.mapPort = newPort;

        LOG.info("MapRuntime.loadMap: id={} mode={} ({}x{}); bound scene with {} instances"
            + " — match live={}",
            newSpec.id(), newSpec.mode(), newSpec.dimensions().width(),
            newSpec.dimensions().depth(), newScene.scene().worldInstanceCount(),
            newPort.isMatchLive());

        return newSpec;
    }

    /**
     * Releases the current map. The renderer is reset to an empty
     * scene, the crosshair is disabled, and the delegating port is
     * swapped to a {@link NullGameplayPort} so the engine keeps ticking
     * (the menu is up; the engine should not stop). A no-op when no
     * map is loaded.
     */
    public void unload()
    {
        if (!hasMap())
        {
            return;
        }

        final String previousId = spec.id();

        // Clear the renderer first, so any in-flight tick that has not
        // yet observed the port swap cannot run a controller against
        // a scene that has just been released.
        renderer.setScene(Scene.EMPTY);

        renderer.setCrosshairEnabled(false);

        // setActual shuts down the outgoing port and inits the
        // incoming one; the NullGameplayPort is a no-op, so this is
        // the engine's "frozen, menu is up" state.
        port.setActual(new NullGameplayPort());

        this.spec = null;

        this.scene = null;

        this.mapPort = null;

        LOG.info("MapRuntime.unload: released '{}'", previousId);
    }

    /**
     * Returns whether a map is currently loaded.
     *
     * @return true when {@link #loadMap} has run and {@link #unload}
     *     has not yet
     */
    public boolean hasMap()
    {
        return spec != null;
    }

    /** Returns the spec of the currently loaded map, or null. */
    public MapSpec spec()
    {
        return spec;
    }

    /** Returns the scene of the currently loaded map, or null. */
    public MapScene scene()
    {
        return scene;
    }

    /** Returns the per-tic port of the currently loaded map, or null. */
    public MapGameplayPort mapPort()
    {
        return mapPort;
    }

    /**
     * Returns the swappable port the engine is ticking. Never null.
     *
     * @return the engine's gameplay port, with the current map as its
     *     delegate when one is loaded, and a {@link NullGameplayPort}
     *     otherwise
     */
    public DelegatingGameplayPort enginePort()
    {
        return port;
    }

    // For diagnostic assertions in tests; the runtime itself does not
    // use it.
    @Override
    public String toString()
    {
        if (spec == null)
        {
            return "MapRuntime(unloaded)";
        }

        return "MapRuntime(id=" + spec.id() + " mode=" + spec.mode() + ")";
    }

    // Catch a class of bug where two runtimes get out of sync on
    // Object identity; the equals test in tests would silently pass
    // for two unloaded runtimes otherwise.
    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof MapRuntime))
        {
            return false;
        }

        final MapRuntime that = (MapRuntime) other;

        final String thisId;

        if (this.spec == null)
        {
            thisId = null;
        }
        else
        {
            thisId = this.spec.id();
        }

        final String thatId;

        if (that.spec == null)
        {
            thatId = null;
        }
        else
        {
            thatId = that.spec.id();
        }

        return Objects.equals(thisId, thatId);
    }

    @Override
    public int hashCode()
    {
        if (spec == null)
        {
            return 0;
        }

        return spec.id().hashCode();
    }
}
