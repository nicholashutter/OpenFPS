/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import java.util.concurrent.locks.ReentrantLock;

import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.gameplay.PlayerInputView;
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.InputState;
import com.openfps.engine.net.NetSession;
import com.openfps.engine.net.TicCmdEncoder;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.engine.render.adapter.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P_ The demo's per-tic loop: latch input, move the player, aim the camera.
 *
 * <p>This is the join that makes the first-person demo a demo rather than a
 * pile of working parts. Every tic, in this order:</p>
 *
 * <pre>
 *   inputPort.sampleInput(tic)      latch the accumulated mouse and keys
 *   view.wrap(currentInput())       present that snapshot as I_PlayerInput
 *   controller.update(view, dt)     turn, then move
 *   renderer.setCamera(camera)      aim the next frame
 * </pre>
 *
 * <p>The scene is <b>not</b> touched here. It is immutable, it is built once
 * before the loop starts, and nothing in this demo moves except the camera —
 * so rebuilding it per tic would allocate for no reason at all.</p>
 *
 * <h2>Why the delta is a constant</h2>
 *
 * <p>{@code deltaSeconds} comes from {@link GameConfig#nanosPerTic()}, not from
 * a measured frame time, and that is the correct source rather than a
 * convenience. {@code GameLoop} is a <b>fixed-timestep</b> clock: it computes
 * an absolute deadline per tic and corrects drift against it, so a tic
 * <i>is</i> {@code 1 / rate} seconds by construction. The render side is
 * vsync-driven and runs at whatever the display does — feeding
 * {@code Gdx.graphics.getDeltaTime()} into the controller would make movement
 * speed depend on the monitor, which is the exact bug the fixed-rate loop
 * exists to prevent.</p>
 *
 * <h2>Threading — this is why there is a lock</h2>
 *
 * <p>{@code Subsystem}'s Javadoc is explicit that <b>multiple workers may be
 * inside {@code onEvent} for the same subsystem at once</b>, so two adjacent
 * {@code TickEvent}s really can land on two worker threads concurrently.
 * {@link PlayerController} is documented as mutable and not thread-safe, and it
 * is: an unguarded concurrent update would interleave the yaw write with the
 * movement read that depends on it, and the player would slide sideways
 * relative to where they are looking.</p>
 *
 * <p>One lock makes each tic atomic, which is what correctness needs. It does
 * <b>not</b> impose an order on two concurrent tics, and that is deliberate
 * rather than overlooked: the quantities involved — a yaw increment and a
 * displacement — commute over a single 16 ms window, so paying for a sequence
 * number to reject an out-of-order tic would buy a guarantee nothing can
 * observe. If lockstep networking later needs strict tic ordering, it needs it
 * at the bus, not here.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>The {@link PlayerInputView} is created once and re-pointed with
 * {@code wrap} — that is the entire reason it is mutable. The only per-tic
 * allocation is the {@code Camera}, which {@code render/README.md} § 4
 * explicitly sanctions ("Build one per frame") because it is immutable and is
 * the value that crosses to the render workers.</p>
 *
 * <p><b>On importing {@link SoftwareRenderPort}.</b> {@code STYLE.md} § 1.1
 * permits a composition root to name concrete implementations, and this package
 * is the demo's composition root. The import is unavoidable in any case:
 * {@code I_RenderPort} has no {@code setCamera}, and {@code render/README.md}
 * § 12 keeps it that way on purpose — the port renders and stops.</p>
 */
public final class DemoGameplayPort implements I_GameplayPort
{
    private static final Logger LOG = LoggerFactory.getLogger(DemoGameplayPort.class);

    /** Nanoseconds in a second. */
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /** Where the latched input comes from. */
    private final I_InputPort inputPort;

    /** Where the camera goes. */
    private final SoftwareRenderPort renderer;

    /** The player's position and heading. Mutated under {@link #tickLock}. */
    private final PlayerController controller;

    /** One reused adapter from {@code InputState} to {@code I_PlayerInput}. */
    private final PlayerInputView inputView = new PlayerInputView();

    /** The fixed tic duration in seconds. See the class Javadoc. */
    private final float deltaSeconds;

    /** Makes one tic atomic against a concurrently dispatched neighbour. */
    private final ReentrantLock tickLock = new ReentrantLock();

    /** Tics applied so far. MUTABLE: bumped under the lock, read for logging. */
    private volatile long ticsApplied;

    /**
     * Tics between shots — the weapon's rate of fire.
     *
     * <p>A cooldown is not decoration here. {@code InputState.fire()} is true
     * on every tic the button is held, so without one a held trigger fires once
     * per tic: 60 hitscans a second at the default rate and 120 at
     * {@code FPS_120} — a weapon whose rate of fire depends on the configured
     * tic rate, which is precisely the coupling the fixed-timestep loop exists
     * to prevent. Twelve tics is five shots a second at 60 Hz.</p>
     */
    public static final int FIRE_INTERVAL_TICS = 12;

    /**
     * The round in progress, or null when this port is just flying a camera
     * around a room.
     *
     * <p>Null is the {@code --model=} case and the no-art case, and it is a
     * legitimate state rather than a defect: the demo predates having anything
     * to shoot at, and looking at one converted model in a window is still the
     * quickest way to check a conversion.</p>
     */
    private final Match match;

    /**
     * The scene each bot's model occupies, in the same order as
     * {@link Match#bots()}, or null when there is no match.
     *
     * <p>This is what turns simulation state into something visible. A bot moves
     * every tic; its model has to follow, and {@code SoftwareRenderPort}
     * addresses world instances by position.</p>
     */
    private final int[] botInstances;

    /**
     * The pre-placed instances a shot makes visible, or null when this port has
     * no scene behind it.
     *
     * <p>Not part of the simulation and deliberately so. A tracer and a puff of
     * smoke are consequences of a shot rather than causes of anything: the
     * hitscan is already resolved from geometry by the time either exists, and
     * nothing reads them back. That is what lets them be null without a second
     * code path, and what stops a peer that renders them differently from
     * desyncing.</p>
     */
    private final DemoEffects effects;

    /**
     * The networked half of the match, or null for a local one.
     *
     * <p>MUTABLE: attached by the launcher before the loop starts, or left null.
     * When present, every tic's input is encoded as a {@link TicCmd} and sent to
     * every peer, and everyone else's arrives in the same call.</p>
     */
    private volatile NetSession net;

    /**
     * Tic index of the last shot, for the cooldown.
     *
     * <p>MUTABLE, under the lock. It starts at {@code -FIRE_INTERVAL_TICS} —
     * "the weapon last fired just long enough ago to be ready" — and
     * <b>emphatically not</b> at {@link Long#MIN_VALUE}, which is the obvious
     * "never" sentinel and is wrong in a way nothing else would have caught.
     * The cooldown test is {@code ticIndex - lastFireTic < FIRE_INTERVAL_TICS};
     * with {@code MIN_VALUE} that subtraction overflows on the very first shot,
     * wraps to a large negative number, and reads as "not ready yet". Since
     * {@code lastFireTic} is only assigned <i>after</i> the test passes, it
     * never passed and the trigger did nothing, for the whole run, on every
     * platform.</p>
     *
     * <p>Found on an Android emulator, where a weapon that never fires is the
     * only thing you can check by hand. It was equally broken on desktop and
     * looked exactly like a hit-detection problem.
     * {@code DemoGameplayPortTest.Trigger} is the regression.</p>
     */
    private long lastFireTic = -FIRE_INTERVAL_TICS;

    /** MUTABLE: the match state already reported, so the result is logged once. */
    private MatchState reportedState = MatchState.IN_PROGRESS;

    /**
     * Whether the match is live, or frozen behind a menu.
     *
     * <p><b>Starts false, and that is the important half.</b> The game loop
     * publishes tics from the moment the engine starts, which is well before the
     * player has pressed anything — so without this the bots begin patrolling
     * and shooting the instant the process launches, and a player who reads the
     * menu for ten seconds arrives in the world already down a fifth of their
     * health. That is not a subtle bug: it was visible in the very first run of
     * the packaged build as "took 2 damage" scrolling past while the title
     * screen was still on screen.</p>
     *
     * <p>Volatile: set from the platform's render thread as the UI changes, read
     * on the game loop thread every tic.</p>
     */
    private volatile boolean matchLive;

    /**
     * Creates the demo's gameplay port with nothing to shoot at.
     *
     * @param input the HAL input port to latch each tic; must not be null
     * @param renderPort the renderer to aim; must not be null
     * @param playerController the player to move; must not be null
     * @param config the running configuration, which fixes the tic duration;
     *     must not be null
     */
    public DemoGameplayPort(final I_InputPort input, final SoftwareRenderPort renderPort,
        final PlayerController playerController, final GameConfig config)
    {
        this(input, renderPort, playerController, config, null, null);
    }

    /**
     * Creates the demo gameplay port for a round against bots.
     *
     * @param input the HAL input port to latch each tic; must not be null
     * @param renderPort the renderer to aim; must not be null
     * @param playerController the player to move; must not be null
     * @param config the running configuration; must not be null
     * @param round the match to drive, or null for a camera-only demo.
     *     <b>Its bots must not include a box around this player</b>: a ray
     *     origin inside a box is a hit at distance zero, so a shooter listed
     *     among its own targets shoots itself on every trigger pull
     * @param botInstanceIndices where each bot's model sits among the scene's
     *     world instances, in {@code round.bots()} order; must be non-null and
     *     at least as long as the roster whenever {@code round} is given
     */
    public DemoGameplayPort(final I_InputPort input, final SoftwareRenderPort renderPort,
        final PlayerController playerController, final GameConfig config,
        final Match round, final int[] botInstanceIndices)
    {
        this(input, renderPort, playerController, config, round, botInstanceIndices, null);
    }

    /**
     * Creates the demo gameplay port for a round against bots, with visible
     * shots.
     *
     * @param input the HAL input port to latch each tic; must not be null
     * @param renderPort the renderer to aim; must not be null
     * @param playerController the player to move; must not be null
     * @param config the running configuration; must not be null
     * @param round the match to drive, or null for a camera-only demo. <b>Its
     *     bots must not include a box around this player</b>: a ray origin
     *     inside a box is a hit at distance zero, so a shooter listed among its
     *     own targets shoots itself on every trigger pull
     * @param botInstanceIndices where each bot's model sits among the scene's
     *     world instances, in {@code round.bots()} order; must be non-null and
     *     at least as long as the roster whenever {@code round} is given
     * @param shotEffects the pre-placed tracer and smoke instances a shot
     *     drives, or null to fire invisibly. Null is a legitimate state, not a
     *     degraded one: the effects belong to a {@link DemoScene}, and a port
     *     built over a bare {@code PlayerController} — which is what most of
     *     this class's own tests do — has no scene to have placed them in
     */
    public DemoGameplayPort(final I_InputPort input, final SoftwareRenderPort renderPort,
        final PlayerController playerController, final GameConfig config,
        final Match round, final int[] botInstanceIndices, final DemoEffects shotEffects)
    {
        this.effects = shotEffects;
        if (round != null && botInstanceIndices == null)
        {
            throw new IllegalArgumentException(
                "a match needs the scene index of each of its bots");
        }
        if (round != null && botInstanceIndices.length < round.botCount())
        {
            throw new IllegalArgumentException("got " + botInstanceIndices.length
                + " scene indices for " + round.botCount() + " bots");
        }
        if (input == null)
        {
            throw new IllegalArgumentException("input must not be null");
        }
        if (renderPort == null)
        {
            throw new IllegalArgumentException("renderPort must not be null");
        }
        if (playerController == null)
        {
            throw new IllegalArgumentException("playerController must not be null");
        }
        if (config == null)
        {
            throw new IllegalArgumentException("config must not be null");
        }
        this.inputPort = input;
        this.renderer = renderPort;
        this.controller = playerController;
        this.deltaSeconds = (float) (config.nanosPerTic() / NANOS_PER_SECOND);
        this.match = round;
        if (round == null)
        {
            this.botInstances = null;
        }
        else
        {
            this.botInstances = botInstanceIndices.clone();
        }
    }

    @Override
    public void init()
    {
        if (match == null)
        {
            LOG.info("Demo gameplay ready: {} s per tic, spawn {} — no opponents, camera only",
                deltaSeconds, controller);
            return;
        }
        LOG.info("Demo gameplay ready: {} s per tic, spawn {}, {} opponents, {} hp",
            deltaSeconds, controller, match.botCount(), match.playerHealth());
    }

    @Override
    public void shutdown()
    {
        if (match == null)
        {
            LOG.info("Demo gameplay stopped after {} tics at {}", ticsApplied, controller);
            return;
        }
        LOG.info("Demo gameplay stopped after {} tics at {}; {}", ticsApplied, controller, match);
    }

    /** Returns the round in progress, or null for a camera-only demo. */
    public Match match()
    {
        return match;
    }

    /**
     * Starts or freezes the match.
     *
     * <p>The seam between "the engine is running" and "the player is playing".
     * They are not the same thing and never were: the game loop starts with the
     * process and keeps a fixed rate whatever is on screen, while a match begins
     * when somebody presses a button on a menu. Everything that is <i>part of
     * the match</i> — the bots' patrol, their return fire, the player's trigger —
     * is gated on this. Everything that is part of the <i>view</i> — latching
     * input, moving the camera — is not, so the world behind a menu is still
     * aimed correctly the moment the menu goes away.</p>
     *
     * <p>Called from the platform's UI, which is the only thing that knows
     * whether a menu is in front. Safe from any thread.</p>
     *
     * @param live true once the player is in the world
     */
    public void setMatchLive(final boolean live)
    {
        if (live && !matchLive)
        {
            LOG.info("Match live — bots are moving and shooting");
        }
        else if (!live && matchLive)
        {
            LOG.info("Match frozen — the menu is in front");
        }
        this.matchLive = live;
    }

    /** Returns whether the match is currently advancing. */
    public boolean isMatchLive()
    {
        return matchLive;
    }

    /**
     * Attaches a network session, making this a multiplayer match.
     *
     * <p>Once attached, every tic's input is quantised to a {@link TicCmd} and
     * sent to every peer, and everything they have sent arrives in the same
     * call. Attaching null returns the port to a purely local match.</p>
     *
     * <p><b>What this does and does not yet do.</b> It carries inputs both ways
     * and keeps the acknowledgement and loss state that a lockstep simulation
     * needs — the transport is real and tested over a real socket. It does
     * <b>not</b> yet simulate remote players into bodies you can see and shoot:
     * that needs a {@code PlayerController} per peer driven from the received
     * ring, plus a stall rule for a peer whose tics have not arrived, and both
     * are the next piece of work rather than part of this one. Saying so here
     * because a session that exchanges packets perfectly and shows nobody looks
     * exactly like a session that is broken.</p>
     *
     * @param session the session to drive, or null for a local match
     */
    public void attachNetwork(final NetSession session)
    {
        this.net = session;
        if (session == null)
        {
            LOG.info("Network detached — this is a local match");
            return;
        }
        LOG.info("Network attached: {}", session);
    }

    /** Returns the network session, or null for a local match. */
    public NetSession network()
    {
        return net;
    }

    /**
     * Advances the player by one tic and points the camera at the result.
     *
     * @param ticIndex the tic being processed
     */
    @Override
    public void tick(final int ticIndex)
    {
        tickLock.lock();
        try
        {
            // Latching is a consuming read — the look deltas are an integral
            // since the previous call, so exactly one caller per tic may do it.
            inputPort.sampleInput(ticIndex);
            inputView.wrap(inputPort.currentInput());
            controller.update(inputView, deltaSeconds);
            // Opponents move BEFORE the player shoots, so a shot is resolved
            // against where the bodies are on this tic rather than where they
            // were on the last one. The other order makes leading a moving
            // target feel a frame late, which is the sort of thing a player
            // registers as "the hit detection is off" without being able to say
            // why.
            advanceMatch(ticIndex);
            exchangeNetwork(ticIndex, inputPort.currentInput());
            fireIfRequested(inputPort.currentInput().fire(), ticIndex);
            // AFTER the trigger and BEFORE the publish, which is not an
            // arbitrary order: a tracer spawned this tic sits at the muzzle,
            // 2.4 units from the eye, and it is 40 units long. Published from
            // there it would enclose the camera. Advancing first puts it out in
            // the room, one step down the ray, which is where a bolt leaving a
            // barrel is by the time anyone could see it anyway.
            advanceEffects();
            publishBotPlacements();
            publishEffects();
            aimCamera();
            this.ticsApplied = ticsApplied + 1;
        }
        finally
        {
            tickLock.unlock();
        }
    }

    // Fires a hitscan shot if the trigger is down and the weapon is ready.
    //
    // Called under tickLock from tick(), so lastFireTic and the shared
    // HitResult are single-threaded here.
    //
    // The shot is resolved from GEOMETRY, deliberately, and not by sampling the
    // renderer's entity-id buffer at the centre pixel — which would be
    // pixel-exact and free, and would also make hit detection a function of
    // resolution and worker count. Two peers rendering the same tic at
    // different sizes would disagree about who was hit, and under the lockstep
    // model in net/README.md that is a desync rather than a rounding
    // difference. Hitscan uses StrictMath and is guarded by a constant-pool
    // test for exactly this reason.
    private void fireIfRequested(final boolean triggerDown, final int ticIndex)
    {
        if (!triggerDown || match == null || !matchLive || match.state().isOver())
        {
            return;
        }
        if (ticIndex - lastFireTic < FIRE_INTERVAL_TICS)
        {
            return;
        }
        this.lastFireTic = ticIndex;

        // Two Vec3 allocations per SHOT, not per tic. The alternative is to
        // recompute the view basis here from yaw and pitch, duplicating the
        // one piece of maths in this engine that has already been wrong once
        // (the mirrored basis, commit 1776548). Reusing the accessor that the
        // camera also uses keeps a single definition of "forward".
        final Vec3 eye = controller.eyePosition();
        final Vec3 aim = controller.forwardVector();
        // The visible shot, from the same eye and the same ray the hitscan uses
        // — one definition of where a shot comes from and which way it goes, so
        // the tracer cannot drift away from what was actually resolved.
        //
        // Spawned BEFORE the hit is resolved and unconditionally: a miss throws
        // exactly as much light and smoke as a hit, and a tracer that only
        // appeared when you connected would be an aimbot's tell.
        if (effects != null)
        {
            effects.spawn(eye.x(), eye.y(), eye.z(), aim.x(), aim.y(), aim.z());
        }
        final int struck = match.firePlayerShot(eye.x(), eye.y(), eye.z(),
            aim.x(), aim.y(), aim.z());
        if (struck == Match.NO_HIT)
        {
            LOG.debug("miss (tic {})", ticIndex);
            return;
        }
        final Bot victim = match.byId(struck);
        if (victim != null && !victim.isAlive())
        {
            LOG.info("KILL entity {} (tic {}) — {} of {} down", struck, ticIndex,
                match.botsKilled(), match.botCount());
            return;
        }
        LOG.info("HIT entity {} (tic {})", struck, ticIndex);
    }

    // Puts this tic's input on the wire and takes off whatever has arrived.
    //
    // AFTER the controller has been updated, so the yaw and pitch sent are the
    // ones this tic's look deltas produced rather than the previous tic's. A
    // peer receiving the earlier pair would see the sender's aim lag their own
    // by one tic — which is exactly the discrepancy that makes a networked
    // shooter feel like it is not registering hits.
    //
    // Allocation-free: the axes and angles are quantised to ints and handed
    // straight to the ring, so a tic that changes nothing still costs no
    // garbage. TicCmdEncoder explains what each field costs on the wire.
    private void exchangeNetwork(final int ticIndex, final InputState input)
    {
        final NetSession session = net;
        if (session == null || !session.isOpen())
        {
            return;
        }
        session.recordLocalCommand(ticIndex,
            TicCmdEncoder.encodeAxis(input.forwardAxis()),
            TicCmdEncoder.encodeAxis(input.strafeAxis()),
            TicCmdEncoder.encodeAngle(controller.yawRadians()),
            TicCmdEncoder.encodePitch(controller.pitchRadians()),
            TicCmdEncoder.encodeButtons(input));
        session.tick(ticIndex);
    }

    // Moves the opponents and lets them shoot back. Reports the result once,
    // the tic it is decided, rather than every tic afterwards.
    private void advanceMatch(final int ticIndex)
    {
        if (match == null || !matchLive)
        {
            return;
        }
        final int damage = match.tick(ticIndex, controller.positionX(), controller.positionY(),
            controller.positionZ());
        if (damage > 0)
        {
            LOG.info("took {} damage — {} hp left", damage, match.playerHealth());
        }
        final MatchState now = match.state();
        if (now != reportedState)
        {
            this.reportedState = now;
            LOG.info("MATCH {} — {}", now, match);
        }
    }

    // Ages every tracer and puff of smoke by one tic.
    //
    // NOT gated on matchLive, unlike the bots. A menu opening mid-burst must not
    // freeze a tracer in the air: the effects are consequences of shots that
    // have already happened, so what a frozen match owes them is to let them
    // finish, not to suspend them. They cannot start while the match is frozen
    // in any case, because the trigger is gated.
    private void advanceEffects()
    {
        if (effects == null)
        {
            return;
        }
        effects.advance();
    }

    // Writes every live effect's placement into the renderer, and hides the
    // instances whose effect has just ended.
    //
    // Guarded on a bound scene for exactly the reason publishBotPlacements is:
    // the game loop publishes tics from the moment it starts, which on desktop
    // is before the launcher has called setScene, and setWorldTransform would
    // throw against a renderer with no instance table.
    private void publishEffects()
    {
        if (effects == null || renderer.scene() == null)
        {
            return;
        }
        effects.publish(renderer);
    }

    // Makes each bot's model sit where the simulation says its body is.
    //
    // This is the seam between simulation and rendering, and it is one
    // reference store per bot per tic. The Scene itself is untouched: it is
    // immutable, and rebuilding it to move seven bodies would re-derive the
    // texture table, the stream offsets and the entity ids of a 295-instance
    // room for no reason at all.
    private void publishBotPlacements()
    {
        if (match == null || renderer.scene() == null)
        {
            // No scene yet. The game loop publishes tics from the moment it
            // starts, and on desktop that is BEFORE the launcher has called
            // setScene — the renderer has no instance table to address, so
            // there is nothing to move and setWorldTransform would throw. Same
            // window, and the same reason, as aimCamera's surface check below.
            return;
        }
        final Bot[] roster = match.bots();
        for (int index = 0; index < roster.length; index++)
        {
            renderer.setWorldTransform(botInstances[index],
                DemoScene.botPlacement(roster[index]));
        }
    }

    // Points the renderer at the player's eye.
    //
    // Skipped entirely until a surface exists. The game loop publishes tics
    // from the moment it starts, which on desktop is before the GLFW window has
    // reported its size, and an aspect ratio of 0/0 is a NaN that Camera.create
    // rejects outright. renderFrame is a no-op in that same window, so there is
    // nothing to aim at yet either.
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
     * Returns false — the demo loads no maps.
     *
     * <p>The room is a {@code Scene} assembled by {@link DemoScene} before the
     * loop starts, not a map file, so there is nothing here to load by name.
     * Returning false rather than a silent true keeps
     * {@code GameplaySubsystem}'s warning honest if a {@code MapLoadEvent} ever
     * reaches this port.</p>
     *
     * @param mapName ignored
     * @return false, always
     */
    @Override
    public boolean loadMap(final String mapName)
    {
        return false;
    }

    /**
     * Returns -1 — the demo has no entity system.
     *
     * @param entityType ignored
     * @param x ignored
     * @param y ignored
     * @param z ignored
     * @return -1, always
     */
    @Override
    public int spawnEntity(final int entityType, final int x, final int y, final int z)
    {
        return -1;
    }

    /**
     * Does nothing — the demo has no entity system.
     *
     * @param entityId ignored
     */
    @Override
    public void removeEntity(final int entityId)
    {
        // no entities to remove
    }

    /** Returns the player this port moves. Never null. */
    public PlayerController controller()
    {
        return controller;
    }

    /** Returns the fixed tic duration in seconds. */
    public float deltaSeconds()
    {
        return deltaSeconds;
    }

    /** Returns how many tics have been applied since construction. */
    public long ticsApplied()
    {
        return ticsApplied;
    }

    /** Returns a debug rendering of the port's state. */
    @Override
    public String toString()
    {
        return "DemoGameplayPort{dt=" + deltaSeconds + "s, tics=" + ticsApplied
            + ", " + controller + "}";
    }
}
