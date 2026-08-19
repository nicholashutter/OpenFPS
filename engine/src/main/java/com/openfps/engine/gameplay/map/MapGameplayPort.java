/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import com.openfps.engine.demo.DemoEffects;
import com.openfps.engine.demo.DemoScene;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.BotPattern;
import com.openfps.engine.gameplay.BotRng;
import com.openfps.engine.gameplay.BotShotLog;
import com.openfps.engine.gameplay.BotSkill;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.MatchStatus;
import com.openfps.engine.gameplay.PhysicsWorld;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.gameplay.PlayerInputView;
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.InputState;
import com.openfps.engine.net.NetSession;
import com.openfps.engine.net.TicCmdEncoder;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReentrantLock;

/**
 * P_ The map's per-tic loop: latch input, move the player on a spec spawn,
 * aim the camera, run the spec's mode dispatch, and exchange inputs with
 * peers.
 *
 * <p>This is the join that turns a {@link MapSpec} into a windowed,
 * networkable match. The class is the same shape as
 * {@code DemoGameplayPort} for the same reason: the simulation is
 * tic-driven, the per-tic lock makes one tic atomic, and the renderer reads
 * the camera after the player has been moved. What changes between the two
 * is what feeds the per-tic loop — a {@code DemoScene} with pre-placed bots
 * versus a {@link MapSpec} with spec-derived spawns and bot waypoints.</p>
 *
 * <h2>What lives here, and what the spec owns</h2>
 *
 * <p>The spec owns identity (the map id and display name), the playable
 * area, the lanes and chokepoints the design describes, the team's spawn
 * placements and the bot patrol waypoints. It does not own a live
 * {@code Match} — that is built here, against the spec, so the spec stays
 * a passive record that many ports can construct against.</p>
 *
 * <p>The local player starts on the first spawn point of the team they were
 * assigned to (set by {@link #setPlayerTeam}). Multiplayer use of the
 * windowed launcher picks the team from the net id, so two peers on the
 * same machine land on different spawns. A single-player run leaves the
 * team at {@link Team#NEUTRAL} and the player starts on the first spec
 * spawn of any team.</p>
 *
 * <h2>What the first pass wires, and what it leaves for later passes</h2>
 *
 * <p>The net is wired: every tic the local player's input is encoded to a
 * {@code TicCmd} and recorded into the {@link NetSession}, and the session
 * drives the send/receive in the same call. The peer's commands land in
 * the session's ring but, <b>for this first pass, are not yet replayed into
 * a visible body in the map scene</b> — the architecture is in place (the
 * {@code RemotePlayers} seam in {@code DemoGameplayPort} documents the
 * shape) and the spec's spawn points give every peer a body, but the map
 * scene currently has no model staged for a peer's body to drive. A later
 * pass adds a remote-body instance pool to {@link MapScene} and a map
 * flavour of {@code RemotePlayers}; the lockstep math is unchanged.</p>
 *
 * <p>What the first pass <i>does</i> prove: two peers can pick the same map
 * from the menu, load the same {@code MapSpec}, run the same {@code Match}
 * with the same bot roster, exchange inputs both ways, and each compute
 * the same per-tic state for the local player. That is the lockstep claim
 * on the map side, and it is the load-bearing precondition for everything
 * else (peer bodies, replicated state) to land.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Every method runs on the game loop thread, like its demo equivalent.
 * The per-tic lock makes one tic atomic; the network session is volatile
 * because the launcher attaches it on the platform's main thread.</p>
 */
public final class MapGameplayPort implements I_GameplayPort
{
    private static final Logger LOG = LoggerFactory.getLogger(MapGameplayPort.class);

    /** Nanoseconds in a second, for turning the tic rate into a sample. */
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** Tics between shots — the player's rate of fire. Mirrors the demo port. */
    public static final int FIRE_INTERVAL_TICS = 12;

    /**
     * Movement patterns the spec bots cycle through, in order.
     *
     * <p>Three patterns so a room of twelve bots does not move in
     * unison. Each pattern shows up at least four times before the
     * cycle repeats, which is the spread the demo's
     * {@code BOT_PATTERNS} settled on after the "they move like a
     * single machine" feedback.</p>
     */
    private static final BotPattern[] SPEC_BOT_PATTERNS =
    {
        BotPattern.PACE_X,
        BotPattern.PACE_Z,
        BotPattern.ORBIT,
    };

    /**
     * Amplitudes for each spec bot pattern, paired with {@link
     * #SPEC_BOT_PATTERNS}.
     *
     * <p><b>2026-08 scaling:</b> the 16 maps were re-sized to
     * 3200-5600 (200-350m square) in commits 15f00cb / c419c58 /
     * 7675496 / ec7be2e. The original 40-unit / 30-unit amplitudes
     * were correct for the 320 x 320 ships — a bot's pace covered
     * 12% of a side — but the same numbers on a 4000-unit side are
     * 1% and the bots appeared stationary. The new amplitudes are
     * tied to the new grid spacing: 900 for PACE_X (a full column
     * step, so a PACE_X bot traces the length of one grid row)
     * and 300 for PACE_Z (a single row spacing on the 4000-5600
     * grids). ORBIT at 900 is a 1800-unit diameter circle, the
     * largest that does not put a bot on a column. PACE_Z stays
     * smaller than PACE_X because the grid's row spacing
     * (450-600) is much tighter than its col spacing (3600).</p>
     */
    private static final float[] SPEC_BOT_AMPLITUDES =
    {
        900.0f, 300.0f, 900.0f,
    };

    /**
     * Tics for one full circuit of each spec bot pattern, paired
     * with {@link #SPEC_BOT_PATTERNS}.
     *
     * <p>Twelve to fifteen seconds at 60 Hz, deliberately not a
     * common multiple. The 900-unit PACE_X (1800-unit slide) and
     * 900-unit ORBIT (1800-unit diameter) need a longer period to
     * stay under the player's 256-unit-per-second sprint speed; a
     * 600-tic period at 1800 units of reach works out to 180 units
     * per second, which is slow enough to chase but fast enough to
     * dodge. PACE_Z at 720 tics covers 600 units at 50 units per
     * second, which reads as "patrolling" rather than "fleeing".
     * The whole room never reaches its extremes on the same tic.</p>
     */
    private static final int[] SPEC_BOT_PERIODS =
    {
        600, 720, 600,
    };

    /** The spec this port is running. */
    private final MapSpec spec;

    /** Where the latched input comes from. */
    private final I_InputPort inputPort;

    /** Where the camera goes. */
    private final SoftwareRenderPort renderer;

    /** The player's position and heading. Mutated under {@link #tickLock}. */
    private final PlayerController controller;

    /** One reused adapter from input to {@code I_PlayerInput}. */
    private final PlayerInputView inputView = new PlayerInputView();

    /** The fixed tic duration in seconds. */
    private final float deltaSeconds;

    /** Makes one tic atomic against a concurrently dispatched neighbour. */
    private final ReentrantLock tickLock = new ReentrantLock();

    /** Tics applied so far. MUTABLE. */
    private volatile long ticsApplied;

    /** The round in progress. Never null. */
    private final Match match;

    /** The team the local player is on. MUTABLE. */
    private volatile Team playerTeam;

    /** The networked half of the match, or null for a local one. MUTABLE. */
    private volatile NetSession net;

    /** Tic index of the last shot, for the cooldown. MUTABLE under the lock. */
    private long lastFireTic = -FIRE_INTERVAL_TICS;

    /** MUTABLE: the match state already reported. */
    private MatchState reportedState = MatchState.IN_PROGRESS;

    /**
     * MUTABLE: starts false, gated on entering the world. The same seam the
     * demo port has — the engine ticks from the moment the process boots,
     * so without this guard a player who reads the menu for ten seconds
     * arrives already down health. The launcher flips this on
     * {@code MENU -> PLAYING}.
     */
    private volatile boolean matchLive;

    /**
     * MUTABLE: the shared tracer / smoke / flash effect pool the
     * populated {@link MapScene} staged. Set by the runtime after the
     * scene is built, before the port is swapped in. Null is the
     * level-only and headless smoke path - the same shape
     * {@code DemoGameplayPort} accepts, because the effect pool is
     * what the bots' incoming fire is published into and a level-only
     * scene has no visual feedback to give the player.
     */
    private volatile DemoEffects effects;

    /**
     * MUTABLE: the populated scene, held so the per-tic publish step
     * can move each bot's world instance to wherever the simulation
     * says it is. Set by the runtime after the scene is built, the
     * same way {@link #effects} is. Null on the level-only and
     * headless smoke paths.
     */
    private volatile MapScene scene;

    /**
     * MUTABLE: the per-scene collision world, shared between the
     * player controller and every bot in the match. Set by the
     * runtime after the scene is built, the same way {@link #scene}
     * is. Null on the level-only and headless smoke paths, and on
     * any port that has not yet wired the scene in - in those
     * cases the controller stays on {@link PhysicsWorld#OPEN} and
     * bots walk through walls, which is the historical demo
     * behaviour. Replaces the per-bot / per-controller defaults
     * rather than layering on top of them.
     */
    private volatile PhysicsWorld collisionWorld;

    /**
     * Scratch space for {@link #spawnIncomingFire}. {@link DemoScene#botMuzzle}
     * writes a bot's muzzle into the three floats; the per-shot caller
     * reuses one array across the loop rather than allocating per iteration.
     * MUTABLE, but only ever inside one call. The tic lock makes one tic
     * atomic, so the field is single-threaded between calls.
     */
    private final float[] muzzleScratch = new float[3];

    /**
     * Reusable 6-float buffer for the per-shot player fire path.
     *
     * <p>Eye at offset 0, aim at offset 3. Written by
     * {@link PlayerController#eyePositionInto} and
     * {@link PlayerController#forwardVectorInto} on every shot; the
     * hitscan reads the eye, the visual tracer reads the aim.</p>
     */
    private final float[] playerFireScratch = new float[6];

    /**
     * Reusable 3-float buffer for the player's outgoing tracer origin.
     *
     * <p>Written by {@link DemoScene#playerMuzzle} on every shot; the
     * visual tracer spawns at the viewmodel's barrel tip, not the eye, so
     * the bolt visibly leaves the gun rather than the camera.</p>
     */
    private final float[] playerMuzzleScratch = new float[3];

    /**
     * Reusable 16-float buffer for the per-tic bot publish path.
     *
     * <p>One body is in flight at a time (the publish loop iterates
     * and the renderer copies from this scratch into its per-instance
     * storage), so the scratch is sized for one body, not for
     * {@code roster.length}. Eliminates the per-tic {@code Mat4}
     * allocation {@code DemoScene.botPlacement} and
     * {@code .botWeaponPlacement} would otherwise produce; see
     * {@code docs/MEMORY.md} item 2 and {@code docs/PERFORMANCE_AUDIT.md}
     * § 6.1.</p>
     */
    private final float[] botScratch = new float[16];

    /**
     * The spawn point the local player started on — and the spawn point a
     * death returns them to. Captured at construction so the respawn
     * never has to re-look-up a team spawn.
     */
    private final float spawnX;
    private final float spawnY;
    private final float spawnZ;
    private final float spawnYaw;
    private final float spawnPitch;

    /**
     * Builds a map-mode gameplay port: a player on a spec spawn, a match for
     * the spec's mode, and a tick loop that drives them both.
     *
     * <p>The {@code spawnIndex} parameter is the position of the local
     * player's spawn within the spec's {@code spawnPoints()} list, filtered
     * to their team. Pass 0 for the first available spawn of the player's
     * team; a negative value picks the first spawn of any team, which is
     * what a single-player run with no team wants.</p>
     *
     * @param spec the map spec to run; must not be null
     * @param input the HAL input port; must not be null
     * @param renderPort the renderer to aim; must not be null
     * @param config the running configuration, fixing the tic duration;
     *     must not be null
     * @param playerTeam the team the local player is on; must not be null
     * @param spawnIndex the index into the team's spawn list, or -1 for
     *     the first spawn of any team
     * @return a new port that runs the spec's match
     */
    public static MapGameplayPort create(final MapSpec spec, final I_InputPort input,
        final SoftwareRenderPort renderPort, final com.openfps.engine.core.GameConfig config,
        final Team playerTeam, final int spawnIndex)
    {
        if (spec == null)
        {
            throw new IllegalArgumentException("spec must not be null");
        }

        if (input == null)
        {
            throw new IllegalArgumentException("input must not be null");
        }

        if (renderPort == null)
        {
            throw new IllegalArgumentException("renderPort must not be null");
        }

        if (config == null)
        {
            throw new IllegalArgumentException("config must not be null");
        }

        if (playerTeam == null)
        {
            throw new IllegalArgumentException("playerTeam must not be null");
        }

        final SpawnPoint spawn = pickSpawn(spec, playerTeam, spawnIndex);

        final PlayerController controller = new PlayerController(
            spawn.x(), spawn.y(), spawn.z(), spawn.yawRadians(), 0.0f);

        final Bot[] roster = botsFromSpec(spec);

        final Match match = new Match(roster, new BotRng(), BotSkill.DUMB,
            Match.UNLIMITED_DEATHS, spec);

        match.setPlayerTeam(playerTeam);

        return new MapGameplayPort(spec, input, renderPort, config, controller, match,
            playerTeam, spawn);
    }

    private MapGameplayPort(final MapSpec spec, final I_InputPort input,
        final SoftwareRenderPort renderPort, final com.openfps.engine.core.GameConfig config,
        final PlayerController controller, final Match match, final Team playerTeam,
        final SpawnPoint spawn)
    {
        this.spec = spec;

        this.inputPort = input;

        this.renderer = renderPort;

        this.controller = controller;

        this.deltaSeconds = (float) (config.nanosPerTic() / NANOS_PER_SECOND);

        this.match = match;

        this.playerTeam = playerTeam;

        this.spawnX = spawn.x();

        this.spawnY = spawn.y();

        this.spawnZ = spawn.z();

        this.spawnYaw = spawn.yawRadians();

        this.spawnPitch = 0.0f;
    }

    /**
     * Picks the spawn the local player starts on.
     *
     * <p>{@code spawnIndex >= 0} selects within the team's spawn list —
     * what a deterministic per-net-id assignment wants. A negative
     * {@code spawnIndex} picks the first spawn of any team, which is the
     * single-player case where {@code playerTeam} is
     * {@link Team#NEUTRAL}. A team with no spawns in the spec falls back
     * to the first spec spawn of any team; the spec's own
     * {@link MapSpec#spawnPoints()} validation guarantees there is at
     * least one.</p>
     *
     * @param spec the spec to draw the spawn from
     * @param team the local player's team
     * @param spawnIndex the index into the team's spawn list, or -1 for the
     *     first of any team
     * @return the chosen spawn, never null
     */
    private static SpawnPoint pickSpawn(final MapSpec spec, final Team team,
        final int spawnIndex)
    {
        if (spawnIndex >= 0)
        {
            int teamSeen = 0;

            for (final SpawnPoint s : spec.spawnPoints())
            {
                if (s.team() == team)
                {
                    if (teamSeen == spawnIndex)
                    {
                        return s;
                    }

                    teamSeen = teamSeen + 1;
                }
            }
        }

        // Either negative spawnIndex (single-player) or no spawn for the
        // requested team (a spec with only one team's spawns); return the
        // first spec spawn, which is the spec's own canonical placement.
        return spec.spawnPoints().get(0);
    }

    @Override
    public void init()
    {
        LOG.info("Map gameplay ready: spec={} ({} x {}, {}), team={}, spawn=({}, {}, {}) yaw={}",
            spec.id(), spec.dimensions().width(), spec.dimensions().depth(), spec.mode(),
            playerTeam, controller.positionX(), controller.positionY(), controller.positionZ(),
            controller.yawRadians());
    }

    @Override
    public void shutdown()
    {
        LOG.info("Map gameplay stopped after {} tics at {}; spec={}", ticsApplied, controller,
            spec.id());
    }

    @Override
    public boolean loadMap(final String mapName)
    {
        // A map-mode port is built for one spec; subsequent loadMap calls
        // are a no-op. The launcher's map-picker reload path is a
        // follow-up, not in this first pass.
        return true;
    }

    @Override
    public void tick(final int ticIndex)
    {
        tickLock.lock();

        try
        {
            if (!matchLive || match.state().isOver())
            {
                // Frozen: still latch input and aim the camera, so the view
                // does not desync from the world when the menu goes away.
                inputPort.sampleInput(ticIndex);

                inputView.wrap(inputPort.currentInput());

                controller.update(inputView, deltaSeconds);

                aimCamera();

                return;
            }

            inputPort.sampleInput(ticIndex);

            inputView.wrap(inputPort.currentInput());

            controller.update(inputView, deltaSeconds);

            aimCamera();

            fireIfRequested(ticIndex, inputPort.currentInput());

            final int damage = match.tick(ticIndex, controller.positionX(),
                controller.positionY(), controller.positionZ());

            if (damage > 0 && match.isPlayerDown())
            {
                // The damage this tic finished the player off. The order
                // matters: isPlayerDown is set inside Match.tick before
                // the return, and consumePlayerRespawned stays false
                // until the respawn tic arrives, so we can log this
                // without racing the respawn path.
                LOG.info("KILLED (tic {}) — death {}, respawning in {} tics (spec={})",
                    ticIndex, match.playerDeaths(),
                    match.respawnTicsRemaining(ticIndex), spec.id());
            }
            else if (damage > 0)
            {
                LOG.info("took {} damage — {} hp left (spec={})", damage,
                    match.playerHealth(), spec.id());
            }

            if (match.consumePlayerRespawned())
            {
                // The match decided a respawn happened; the port puts the
                // body back at the spawn the spec gave it.
                controller.respawnAt(spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);

                LOG.info("RESPAWNED at ({}, {}, {}) on {} (spec={})", spawnX, spawnY, spawnZ,
                    playerTeam, spec.id());
            }

            exchangeNetwork(ticIndex, inputPort.currentInput());

            // Publish visual feedback for the bots' shots that just landed
            // this tic, and advance any in-flight tracers / puffs. The
            // match is hitscan (a shot that connects is decided in
            // match.tick), so the visual is the only feedback the player
            // gets for where the bolt came from and where it went. Without
            // this, the player takes damage with no on-screen cause and
            // reads the bots as unfair.
            spawnIncomingFire(ticIndex);

            if (effects != null)
            {
                effects.advance();
            }

            ticsApplied = ticsApplied + 1;
        }
        finally
        {
            tickLock.unlock();
        }

        // Move each bot's world instance to wherever the simulation
        // says it is. The scene's transform was set ONCE at build
        // time, which is right for the build-time position (a SENTRY
        // bot that never moves stays at the waypoint) but wrong for
        // any pattern that actually moves; and even for SENTRY the
        // publish is the seam that hides a dead body (DemoScene returns
        // the degenerate HIDDEN transform when isAlive() is false) and
        // the seam that would let a future ORBIT or PACE_X pattern
        // work without another port-side change.
        publishBotPlacements();

        // Publish the per-tic effect placements outside the lock so the
        // renderer's internal frame lock is held for the minimum time. The
        // demo port publishes in the same place.
        if (effects != null)
        {
            effects.publish(renderer);
        }

        if (match.state() != reportedState)
        {
            reportedState = match.state();

            LOG.info("Map match state -> {} (tic {}, spec={})", reportedState, ticIndex,
                spec.id());
        }
    }

    /**
     * Aims the renderer at the player's eye. Skipped until the surface has
     * a positive size, exactly as the demo port does — a 0x0 aspect ratio
     * is a NaN that {@code Camera.create} rejects.
     */
    private void aimCamera()
    {
        final int width = renderer.surfaceWidth();

        final int height = renderer.surfaceHeight();

        if (width <= 0 || height <= 0)
        {
            return;
        }

        renderer.setCamera(controller.camera((float) width / (float) height));
    }

    /**
     * Publishes one incoming-fire tracer per shot the match decided this
     * tic. Mirrors {@code DemoGameplayPort.spawnIncomingFire}.
     *
     * <p>The match records each hitscan as it lands (or as it misses, the
     * same shot log either way) with the shooter's muzzle and the ray it
     * used. Spawning a tracer from those two points gives the player a
     * bolt that both leaves the bot's gun and ends where the shot
     * actually went - the cross-section of a hit and the path of a
     * miss are different things, and getting either wrong is worse
     * than no bolt at all.</p>
     *
     * <p>Skipped on a null effect pool (the level-only and headless
     * smoke paths have no scene to draw into).</p>
     */
    private void spawnIncomingFire(final int ticIndex)
    {
        if (effects == null)
        {
            return;
        }

        final BotShotLog shots = match.shotsThisTic();

        if (shots.count() == 0)
        {
            return;
        }

        // One scratch array, three floats. DemoScene.botMuzzle writes the
        // muzzle's world x, y, z into it; both the muzzle and the shot
        // log entry are read this tic, so reuse the scratch across shots.
        for (int slot = 0; slot < shots.count(); slot++)
        {
            final int shooterId = shots.shooterId(slot);

            final Bot shooter = findBot(shooterId);

            if (shooter == null)
            {
                continue;
            }

            // The muzzle is where the effect STARTS; the log's origin
            // and direction are the ray the hitscan actually used, and
            // both are needed - spawnIncoming reconciles them so the
            // bolt leaves the gun AND arrives where the shot went.
            // A bolt that did one but not the other is either floating
            // out of a chest or lying about the damage.
            DemoScene.botMuzzle(shooter, muzzleScratch);

            effects.spawnIncoming(muzzleScratch[0], muzzleScratch[1], muzzleScratch[2],
                shots.originX(slot), shots.originY(slot), shots.originZ(slot),
                shots.directionX(slot), shots.directionY(slot), shots.directionZ(slot),
                shots.rangeUnits(slot));
        }
    }

    /**
     * Linear scan for a bot by entity id. The match is small (one bot
     * per waypoint, capped at {@link Match#DEFAULT_BOT_COUNT}), so a
     * loop is cheaper than a hash and never grows.
     */
    private Bot findBot(final int entityId)
    {
        for (final Bot b : match.bots())
        {
            if (b.entityId() == entityId)
            {
                return b;
            }
        }

        return null;
    }

    /**
     * Puts each bot's world instance at the simulation's current
     * position, and the weapon the bot is holding at the same
     * transform {@code DemoScene.botWeaponPlacement} produces.
     *
     * <p>This is the seam between simulation and rendering for the map
     * mode. The build-time transform was set in
     * {@link MapScene#addBotInstances} and is right for the build-time
     * position - a SENTRY bot that never moves stays at the waypoint -
     * but two things still need this per-tic publish:</p>
     *
     * <ol>
     *   <li><b>Death visibility.</b> {@code DemoScene.botPlacement} returns
     *       the degenerate {@link com.openfps.engine.demo.DemoEffects#HIDDEN}
     *       transform when {@code isAlive()} is false. A dead body is
     *       hidden by an override on the renderer, not by anything in
     *       the simulation - the only place the un-hide can happen is
     *       here, on a future tic's publish.</li>
     *   <li><b>Future movement patterns.</b> ORBIT, PACE_X and any
     *       pattern the spec authors add later would move the bot
     *       in {@code match.tick} and the visual needs to follow.
     *       Without this publish the visual would freeze at the
     *       waypoint while the simulation walked away.</li>
     * </ol>
     *
     * <p>Mirrors {@code DemoGameplayPort.publishBotPlacements}; the
     * shape is the same, the indices come from the populated scene
     * instead of an instance field, and the publish is null-safe
     * the same way the demo port's is.</p>
     */
    private void publishBotPlacements()
    {
        if (scene == null || renderer.scene() == null || match == null)
        {
            // No scene yet, or the renderer is unbound. The game loop
            // publishes tics from the moment it starts, and on desktop
            // that is BEFORE the launcher has called setScene - the
            // renderer has no instance table to address, so there is
            // nothing to move and setWorldTransform would throw. Same
            // window, the same reason, as the demo port's publish.
            return;
        }

        final Bot[] roster = match.bots();

        // One reusable 16-float scratch for the per-tic publish.
        // Filled by DemoScene.botPlacementInto / .botWeaponPlacementInto,
        // copied into the renderer's per-instance storage by the
        // row-major setWorldTransform overload, never reallocated.
        final float[] scratch = botScratch;

        for (int index = 0; index < roster.length; index++)
        {
            final int bodyInstance = scene.botInstanceIndex(index);

            if (bodyInstance == MapScene.NO_INSTANCE)
            {
                continue;
            }

            com.openfps.engine.demo.DemoScene.botPlacementInto(roster[index], scratch, 0);

            renderer.setWorldTransform(bodyInstance, scratch, 0);

            final int weaponInstance = scene.botWeaponInstanceIndex(index);

            if (weaponInstance != MapScene.NO_INSTANCE)
            {
                com.openfps.engine.demo.DemoScene.botWeaponPlacementInto(roster[index], scratch, 0);

                renderer.setWorldTransform(weaponInstance, scratch, 0);
            }
        }
    }

    /**
     * Fires the player's shot if the trigger is held and the cooldown has
     * elapsed. Mirrors the demo port's behaviour: hitscan against the
     * match's living bots, no per-tic allocation beyond the BotShotLog
     * Match owns internally.
     *
     * <p><b>The aim direction is the controller's canonical
     * {@link PlayerController#forwardVectorInto} — not the negated version
     * the original port inlined.</b> The earlier sign flip on sinPitch and
     * cosYaw made the projectile fly away from where the crosshair sat
     * (toward the camera, not toward the target). The hitscan and the
     * tracer now both read the same vector and so point at the same thing
     * the player sees.</p>
     *
     * <p><b>The tracer is spawned at the viewmodel's barrel tip, not the
     * eye.</b> Same eye as the hitscan origin keeps the simulation aligned
     * with what the player sees; moving the tracer out to the muzzle keeps
     * the visible bolt attached to the gun. The eye → muzzle offset is
     * small but obvious on screen: a tracer that materialises in front of
     * the camera is visibly not a tracer that left the gun.</p>
     */
    private void fireIfRequested(final int ticIndex, final InputState input)
    {
        if (!input.fire())
        {
            return;
        }

        if (ticIndex - lastFireTic < FIRE_INTERVAL_TICS)
        {
            return;
        }

        final float eyeX = controller.positionX();

        final float eyeY = controller.positionY() + PlayerController.EYE_HEIGHT_UNITS;

        final float eyeZ = controller.positionZ();

        // Canonical forward vector, in the same six floats the demo port
        // uses (eyeAimScratch[0..2]=eye, eyeAimScratch[3..5]=aim). Reusing
        // the scratch shape keeps the per-shot work below one
        // pre-allocated buffer and avoids the prior inlined math that
        // flipped the sign on sinPitch and cosYaw.
        controller.eyePositionInto(playerFireScratch, 0);

        controller.forwardVectorInto(playerFireScratch, 3);

        final float aimX = playerFireScratch[3];

        final float aimY = playerFireScratch[4];

        final float aimZ = playerFireScratch[5];

        match.firePlayerShot(eyeX, eyeY, eyeZ, aimX, aimY, aimZ);

        // Publish an outgoing tracer the player can SEE leave the gun.
        // The demo port does this (see DemoGameplayPort.fireIfRequested);
        // the map port was missing it, so the player's hitscan
        // registered on the match side but never appeared on screen
        // — the gun visibly did nothing. Same fire call, just an
        // outgoing-tracer publish alongside it. The spawn point is the
        // viewmodel's barrel tip in world space, not the eye.
        if (effects != null)
        {
            DemoScene.playerMuzzle(controller, playerMuzzleScratch, 0);

            effects.spawn(playerMuzzleScratch[0], playerMuzzleScratch[1], playerMuzzleScratch[2],
                aimX, aimY, aimZ);
        }

        lastFireTic = ticIndex;
    }

    // Sends the local player's input to every peer, and receives theirs.
    //
    // The NetSession's tick() drains incoming datagrams, exchanges TicCmds,
    // and resends — the same call shape the demo port uses. The peers'
    // commands land in the session's ring; this first pass does not yet
    // replay them into a visible body in the map scene, but the lockstep
    // claim — every peer's input is on the wire and the round-trips are
    // measured — is in place.
    private void exchangeNetwork(final int ticIndex, final InputState input)
    {
        final NetSession session = net;

        if (session == null || !session.isOpen())
        {
            return;
        }

        // AFTER the controller has been updated, so the yaw and pitch
        // sent are the ones this tic's look deltas produced rather than
        // the previous tic's. The same ordering the demo port enforces.
        session.recordLocalCommand(ticIndex,
            TicCmdEncoder.encodeAxis(input.forwardAxis()),
            TicCmdEncoder.encodeAxis(input.strafeAxis()),
            TicCmdEncoder.encodeAngle(controller.yawRadians()),
            TicCmdEncoder.encodePitch(controller.pitchRadians()),
            TicCmdEncoder.encodeButtons(input));

        session.tick(ticIndex);
    }

    /**
     * Returns the round in progress. Never null.
     *
     * @return the match the port is driving
     */
    public Match match()
    {
        return match;
    }

    /** Returns the spec the port was built for. Never null. */
    public MapSpec spec()
    {
        return spec;
    }

    /** Returns the player's position and heading. */
    public PlayerController controller()
    {
        return controller;
    }

    /**
     * Starts or freezes the match.
     *
     * <p>Same shape as {@code DemoGameplayPort.setMatchLive}: the match
     * is what the bots act on, and freezing it on a menu is what keeps a
     * player from arriving already down health. The per-tic loop does not
     * advance the match when frozen.</p>
     */
    public void setMatchLive(final boolean live)
    {
        if (live && !matchLive)
        {
            LOG.info("Match live — bots are moving and shooting (spec={})", spec.id());
        }
        else if (!live && matchLive)
        {
            LOG.info("Match frozen — the menu is in front (spec={})", spec.id());
        }

        this.matchLive = live;
    }

    /**
     * Attaches the effect pool the populated scene staged. The runtime
     * calls this right after the port is created, before the delegating
     * swap, so the first tic of the new port already has incoming-fire
     * visuals to publish.
     *
     * <p>Null clears the pool: the level-only and headless smoke paths
     * have no scene to draw into, and the port handles a null pool
     * the same way the demo port does - skip the publish.</p>
     */
    public void setEffects(final DemoEffects sceneEffects)
    {
        this.effects = sceneEffects;
    }

    /**
     * Attaches the populated scene so the per-tic publish step can
     * move each bot's world instance to wherever the simulation says
     * it is. Set by the runtime right after the scene is built and
     * the effect pool is attached, before the delegating swap.
     *
     * <p>Null clears the scene: the level-only and headless smoke
     * paths have no scene, and the publish step handles that as
     * "skip the publish".</p>
     */
    public void setScene(final MapScene populatedScene)
    {
        this.scene = populatedScene;
    }

    /**
     * Attaches the per-scene {@link PhysicsWorld} to the player
     * controller and to every bot in the match.
     *
     * <p>Wired by the runtime right after the scene is built, the
     * same shape as {@link #setScene} and {@link #setEffects} for the
     * same reason: the port is constructed before the scene, so the
     * controller starts on {@link PhysicsWorld#OPEN} (no collision)
     * and the bots start with no world, and the runtime injects the
     * real world between construction and the first
     * {@link #tick}. Without this call the player walks through
     * every wall and the bots teleport through them, which is the
     * historical demo behaviour - a map-mode port that has not
     * called this is exactly the port a user notices.</p>
     *
     * <p>Forwards to the controller and every bot, then holds a
     * reference for the lifetime of the port. Null clears the world
     * (level-only and headless smoke paths), in which case the
     * controller and bots behave as if the call had not been made.
     * Replaces rather than accumulates - a map reload is a single
     * replacement, not a layered list.</p>
     *
     * @param world the collision world to clip the player and the
     *     bots against from now on, or null to clear collision on a
     *     port that has no scene behind it
     */
    public void setCollisionWorld(final PhysicsWorld world)
    {
        this.collisionWorld = world;

        if (world != null)
        {
            controller.setCollisionWorld(world);

            for (int index = 0; index < match.bots().length; index++)
            {
                match.bots()[index].setCollisionWorld(world);
            }
        }
    }

    /**
     * Attaches a network session, making this a multiplayer match.
     *
     * @param session the session to drive, or null for a local match
     */
    public void attachNetwork(final NetSession session)
    {
        this.net = session;

        if (session == null)
        {
            LOG.info("Map network detached — local match");

            return;
        }

        LOG.info("Map network attached: {}", session);
    }

    /**
     * Sets the team the local player is on. The first call is at
     * construction; later calls are how a rematch on a different team would
     * work, when that lands.
     */
    public void setPlayerTeam(final Team team)
    {
        if (team == null)
        {
            throw new IllegalArgumentException("team must not be null");
        }

        this.playerTeam = team;

        match.setPlayerTeam(team);
    }

    /** Returns the team the local player is on. */
    public Team playerTeam()
    {
        return playerTeam;
    }

    /**
     * Returns a snapshot of the live match for the score panel.
     *
     * <p>The match status is what the on-screen score reads; the player
     * id is the net id, or 0 for a local run.</p>
     */
    public MatchStatus status()
    {
        return MatchStatus.of(match, (int) ticsApplied);
    }

    /** Returns whether the match is currently advancing. */
    public boolean isMatchLive()
    {
        return matchLive;
    }

    /**
     * Returns a small bot roster derived from the spec's waypoints.
     *
     * <p>One bot per waypoint, up to {@link Match#DEFAULT_BOT_COUNT}, all
     * walking a sentry pattern at the waypoint's position. The behaviour
     * is the same shape {@code MapSmokeGameplayPort} uses for the headless
     * smoke test, so a port built here is interchangeable with a smoke
     * run when the net is detached.</p>
     */
    private static Bot[] botsFromSpec(final MapSpec spec)
    {
        final int count = Math.min(spec.botWaypoints().size(), Match.DEFAULT_BOT_COUNT);

        if (count == 0)
        {
            return new Bot[]
            {
                new Bot(Match.FIRST_BOT_ENTITY_ID, 0.0f, 0.0f, 0.0f,
                    BotPattern.SENTRY, 0.0f, 60, 0)
            };
        }

        final Bot[] bots = new Bot[count];

        for (int index = 0; index < count; index++)
        {
            final Waypoint wp = spec.botWaypoints().get(index);

            // Spec bots move while they shoot, by design — see Match's
            // botShotConnects for the part that makes the moving position
            // the shot origin. The pattern cycles through PACE_X /
            // PACE_Z / ORBIT so a room of twelve bots does not pulse in
            // unison, and the amplitude is small enough that a route does
            // not run a body through a wall before the collision clip
            // gets a chance to refuse the move.
            final BotPattern pattern = SPEC_BOT_PATTERNS[index % SPEC_BOT_PATTERNS.length];

            final float amplitude = SPEC_BOT_AMPLITUDES[index % SPEC_BOT_AMPLITUDES.length];

            final int period = SPEC_BOT_PERIODS[index % SPEC_BOT_PERIODS.length];

            bots[index] = new Bot(Match.FIRST_BOT_ENTITY_ID + index, wp.x(), wp.y(), wp.z(),
                pattern, amplitude, period, index);
        }

        return bots;
    }

    /**
     * Returns the mode the spec runs. A convenience accessor for callers
     * that want to branch on TDM vs Hardpoint vs Domination vs CTF.
     */
    public MatchMode mode()
    {
        return spec.mode();
    }
}
