/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import java.util.concurrent.locks.ReentrantLock;

import com.openfps.engine.audio.adapter.NullAudioPort;
import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.audio.port.SoundId;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.BotShotLog;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.MatchStatus;
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
     * The scene instance each bot's weapon occupies, in the same order as
     * {@link Match#bots()}, or null when nothing is armed.
     *
     * <p>Null is the no-art case and the camera-only case. Entries may
     * individually be {@link DemoScene#NO_INSTANCE} for the same reason, which is
     * what a checkout with characters staged and no weapon produces.</p>
     */
    private final int[] botWeaponInstances;

    /** Where the player started, world x — the point a respawn returns them to. */
    private final float spawnX;

    /** Where the player started, world y. */
    private final float spawnY;

    /** Where the player started, world z. */
    private final float spawnZ;

    /** The heading the player started with. */
    private final float spawnYawRadians;

    /** The elevation the player started with. */
    private final float spawnPitchRadians;

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
     * The peers' bodies, or null when nothing has been attached.
     *
     * <p>MUTABLE, attached by the launcher before the loop starts, the same shape
     * and for the same reason as {@link #net} — the pool belongs to the
     * {@code DemoScene}, which the bootstrap's gameplay factory cannot see.
     * Volatile because the launcher attaches on the platform's main thread and
     * the game loop thread reads it every tic.</p>
     */
    private volatile RemotePlayers remoteBodies;

    /**
     * Where the weapon's noise goes. Never null.
     *
     * <p><b>Starts silent rather than starting absent.</b> A
     * {@link NullAudioPort} default means {@link #fireIfRequested} has one code
     * path instead of a null check on the hottest branch in the class, and it
     * means every test, every headless run and every {@code --model=} session
     * behaves identically to a windowed one apart from the noise. The
     * alternative — a null field — puts a conditional in the trigger, which is
     * exactly where this class has already had one bug that made the weapon
     * useless for a whole run.</p>
     *
     * <p>MUTABLE: replaced by {@link #attachAudio} before the loop starts, the
     * same shape as {@link #attachNetwork}. Volatile because the launcher
     * attaches on the platform's main thread and the game loop thread reads it
     * every shot.</p>
     *
     * <p>This is an {@code I_AudioPort} and never a libGDX type. The rule is not
     * negotiable and it is the reason the port exists: {@code :engine} compiles
     * and tests with no libGDX, no GLFW and no sound card (hal/README.md).</p>
     */
    private volatile I_AudioPort audio = new NullAudioPort();

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

    /**
     * Where {@link DemoScene#botMuzzle} writes, so spawning a bot's muzzle flash
     * allocates nothing. MUTABLE, and only ever inside one call.
     *
     * <p>A field rather than a local because this is the tic path and three
     * floats cannot come back from a Java method without something to hold them —
     * the same trade {@code DemoEffects.acrossScratch} already makes, and safe for
     * the same reason: everything that touches it runs under {@link #tickLock},
     * so one tic is atomic and nothing here is re-entrant.</p>
     */
    private final float[] muzzleScratch = new float[3];

    /**
     * How much of the room's fire is allowed to be heard. Never null.
     *
     * <p>Seven opponents on seven independent cadences will ask for a noise far
     * more often than one player can, and identical copies of one generated buffer
     * started on the same tic sum in phase rather than mixing. See
     * {@link BotFireVoices}, which owns both numbers and the arithmetic behind
     * them.</p>
     */
    private final BotFireVoices botVoices = new BotFireVoices();

    /** MUTABLE: the match state already reported, so the result is logged once. */
    private MatchState reportedState = MatchState.IN_PROGRESS;

    /**
     * The tic most recently applied, for turning the match's absolute respawn tic
     * into a countdown the HUD can draw.
     *
     * <p>MUTABLE, written under the tic lock and read from the platform's render
     * thread, hence volatile. A stale read costs one frame of a countdown that
     * ticks sixty times a second, which is not a class of error anybody can
     * see — and taking the lock on the render thread to avoid it would put the
     * simulation behind the display.</p>
     */
    private volatile int lastTicIndex;

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
        this(input, renderPort, playerController, config, round, botInstanceIndices,
            shotEffects, null);
    }

    /**
     * Creates the demo gameplay port for a round against armed bots.
     *
     * @param input the HAL input port to latch each tic; must not be null
     * @param renderPort the renderer to aim; must not be null
     * @param playerController the player to move; must not be null. <b>Its
     *     current placement is taken as the spawn point</b>, which is what a
     *     death and a rematch both return the player to — see
     *     {@link #restartMatch()}
     * @param config the running configuration; must not be null
     * @param round the match to drive, or null for a camera-only demo. <b>Its
     *     bots must not include a box around this player</b>: a ray origin
     *     inside a box is a hit at distance zero, so a shooter listed among its
     *     own targets shoots itself on every trigger pull
     * @param botInstanceIndices where each bot's model sits among the scene's
     *     world instances, in {@code round.bots()} order; must be non-null and
     *     at least as long as the roster whenever {@code round} is given
     * @param shotEffects the pre-placed tracer and smoke instances a shot
     *     drives, or null to fire invisibly
     * @param botWeaponIndices where each bot's weapon sits among the scene's
     *     world instances, in the same order, or null when the bots hold
     *     nothing. Individual entries may be {@link DemoScene#NO_INSTANCE}
     */
    public DemoGameplayPort(final I_InputPort input, final SoftwareRenderPort renderPort,
        final PlayerController playerController, final GameConfig config,
        final Match round, final int[] botInstanceIndices, final DemoEffects shotEffects,
        final int[] botWeaponIndices)
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
        if (botWeaponIndices == null)
        {
            this.botWeaponInstances = null;
        }
        else
        {
            this.botWeaponInstances = botWeaponIndices.clone();
        }
        // The controller's placement AT CONSTRUCTION is the spawn, captured here
        // rather than passed in. Two reasons, and the second is the one that
        // decided it: DemoScene.spawnController() is what built this object's
        // controller, so the two can never disagree — whereas a spawn handed in
        // separately is a second copy of the same fact, and the failure mode of a
        // second copy is a respawn that puts the player somewhere the level
        // designer never chose. It also keeps every existing caller unchanged.
        this.spawnX = playerController.positionX();
        this.spawnY = playerController.positionY();
        this.spawnZ = playerController.positionZ();
        this.spawnYawRadians = playerController.yawRadians();
        this.spawnPitchRadians = playerController.pitchRadians();
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
     * <p><b>Peers become visible only if {@link #attachRemoteBodies} is also
     * called.</b> The session on its own carries inputs and keeps the
     * acknowledgement and loss state a lockstep simulation needs; the bodies are
     * a separate attachment because the pool belongs to the scene, and a session
     * with no pool is still a useful thing (every test in this class is one).
     * Saying so here because a session that exchanges packets perfectly and shows
     * nobody looks exactly like a session that is broken.</p>
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
     * Attaches the pool of bodies the peers are simulated into.
     *
     * <p>The other half of {@link #attachNetwork}, and the piece that turns
     * arriving packets into someone you can see. Every tic, each connected peer's
     * inputs are replayed out of the session's ring through its own
     * {@link com.openfps.engine.gameplay.PlayerController} and its model is moved
     * to the result — see {@link RemotePlayers}.</p>
     *
     * <p>A setter rather than a constructor parameter for the reason
     * {@link #attachAudio} gives: this port is built inside
     * {@code EngineMain.start} by a factory that takes only the input port, while
     * the pool comes from the {@code DemoScene} the launcher holds.</p>
     *
     * <p>Harmless without a session — the bodies simply stay hidden, which is
     * exactly what a single-player run wants.</p>
     *
     * @param bodies the pool to drive, or null to stop simulating peers
     */
    public void attachRemoteBodies(final RemotePlayers bodies)
    {
        this.remoteBodies = bodies;
        if (bodies == null)
        {
            LOG.info("Remote bodies detached — peers will not be visible");
            return;
        }
        LOG.info("Remote bodies attached: {}", bodies);
    }

    /** Returns the pool the peers are simulated into, or null when none is attached. */
    public RemotePlayers remoteBodies()
    {
        return remoteBodies;
    }

    /**
     * Attaches the sound output the weapon fires through.
     *
     * <p>A setter rather than an eighth constructor parameter, and the same
     * shape as {@link #attachNetwork} for the same reason: the audio port comes
     * from the HAL factory, which the launcher holds, while this port is built
     * inside {@code EngineMain.start} by a factory taking only the input port.
     * Widening that factory signature to carry audio would push the platform's
     * device choice through the engine's bootstrap, which is precisely what the
     * {@code I_GameplayPortFactory} indirection exists to avoid.</p>
     *
     * <p>Call before the frame loop starts. Safe from any thread.</p>
     *
     * @param port where to play the weapon sound; null restores silence rather
     *     than being rejected, because "this build has no audio" is a
     *     configuration, not a mistake
     */
    public void attachAudio(final I_AudioPort port)
    {
        if (port == null)
        {
            this.audio = new NullAudioPort();
            LOG.info("Audio detached — the weapon fires silently");
            return;
        }
        this.audio = port;
        // The class, not isAudible(): a real backend cannot say yet. Its device
        // and its sounds are opened lazily, on the first shot, because on
        // desktop there IS no libGDX audio until the frame loop has started —
        // and that is after this call. The port logs the answer itself when it
        // knows it.
        LOG.info("Audio attached: {}", port.getClass().getSimpleName());
    }

    /** Returns the port the weapon fires through. Never null. */
    public I_AudioPort audio()
    {
        return audio;
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
            // IMMEDIATELY after the match ticked, because Match.tick clears the
            // shot log at the top of the NEXT one — the window is a single tic
            // wide, which is the right lifetime for a per-tic event and the reason
            // it needs no queue. See BotShotLog.
            spawnIncomingFire(ticIndex);
            exchangeNetwork(ticIndex, inputPort.currentInput());
            // IMMEDIATELY after the exchange, because that call is what drained
            // the socket: replaying here uses the commands that landed on THIS
            // tic rather than making every peer's body a tic staler than the
            // data available to it.
            //
            // And BEFORE the trigger, for the same reason the bots move before
            // it — a shot should resolve against where the bodies are on this
            // tic. The peers are not shootable yet (see advanceRemoteBodies),
            // but putting the call in the wrong place now would make that a bug
            // to find later rather than a line to delete.
            advanceRemoteBodies();
            fireIfRequested(inputPort.currentInput().fire(), ticIndex);
            // AFTER the trigger and BEFORE the publish, which is not an
            // arbitrary order: a tracer spawned this tic sits at the muzzle,
            // 2.4 units from the eye, and it is 40 units long. Published from
            // there it would enclose the camera. Advancing first puts it out in
            // the room, one step down the ray, which is where a bolt leaving a
            // barrel is by the time anyone could see it anyway.
            advanceEffects();
            publishBotPlacements();
            publishRemoteBodies();
            publishEffects();
            aimCamera();
            this.ticsApplied = ticsApplied + 1;
            this.lastTicIndex = ticIndex;
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
        if (match.isPlayerDown())
        {
            // A corpse does not shoot. Gating here rather than inside Match for
            // the reason firePlayerShot's Javadoc gives: whether a trigger MAY be
            // pulled is the caller's rule, and this caller's rule is that the two
            // seconds on the floor are two seconds of not playing. Without it a
            // player who held the mouse down would keep firing from where they
            // fell and would spend their own accuracy figure on it.
            return;
        }
        if (ticIndex - lastFireTic < FIRE_INTERVAL_TICS)
        {
            return;
        }
        this.lastFireTic = ticIndex;

        // The shot is now committed, so this is the noise of a shot rather than
        // the noise of a trigger. Three things follow from where this line sits:
        //
        //  - AFTER the cooldown, so a held trigger makes five sounds a second
        //    and not sixty. Before it, the blaster would be a buzz whose pitch
        //    depended on --fps, which is the exact coupling FIRE_INTERVAL_TICS
        //    exists to break.
        //  - BEFORE the hitscan resolves, and unconditionally, so a miss sounds
        //    identical to a hit. The same rule as the tracer: a sound that only
        //    played when you connected would tell the player the outcome before
        //    the game does.
        //  - Through the PORT. The engine says "make this noise" and knows
        //    nothing else — no device, no file, no format. On a headless run
        //    this is a counter increment, which is why CI can assert the cadence
        //    above without a sound card.
        //
        // Non-blocking by contract (I_AudioPort#play), which matters here: this
        // runs on the game loop thread inside tickLock. A port that waited for
        // the sound to finish would cost eleven tics per shot at 60 Hz.
        audio.play(fireSound());

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
        // IMMEDIATELY after the shot resolves, because the award is a consequence
        // of it: the streak is completed inside firePlayerShot and the flag is
        // cleared by the next tick, so this is the only window there is. Before the
        // miss branch below, since a shot that killed somebody is not a miss and a
        // reward announced after a `return` is a reward nobody hears.
        if (match.consumeSuperBlasterAwarded())
        {
            audio.play(SoundId.SUPER_BLASTER_READY);
            LOG.info("SUPER BLASTER (tic {}) — {} kills without dying, x{} damage for {} tics",
                ticIndex, Match.SUPER_BLASTER_KILL_STREAK,
                Match.SUPER_BLASTER_DAMAGE_MULTIPLIER, match.superBlasterTicsRemaining());
        }
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

    // Which of the player's two weapon noises this shot makes.
    //
    // The reward would be inaudible without this, during exactly the activity it
    // modifies: the player hears their own trigger five times a second, so a buff
    // that changed the damage and not the sound could only be SEEN. It is also
    // what makes the expiry audible without looking away from the fight — the
    // weapon simply sounds like itself again.
    //
    // Read from the match rather than from a field here, so there is one answer to
    // "is the reward live" and it is the simulation's.
    private SoundId fireSound()
    {
        if (match.isSuperBlaster())
        {
            return SoundId.SUPER_WEAPON_FIRE;
        }
        return SoundId.WEAPON_FIRE;
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

    // Replays every peer's arrived inputs into its body.
    //
    // The whole of "a remote player is not simulated into a body", which was the
    // oldest open item in the project: the inputs had been arriving correctly for
    // some time and nothing consumed them, so two instances that were talking
    // perfectly showed each other an empty room.
    //
    // Deliberately NOT gated on matchLive. A peer's body is not part of this
    // match's state — Match has never heard of it — and freezing peers while the
    // local player reads a menu would mean their inputs piled up unconsumed and
    // then replayed in one burst on resume, teleporting everyone. Lockstep cannot
    // drop input, so the only safe thing to do with it is keep applying it.
    //
    // The peers are not yet shootable: Match builds its target list from its bot
    // roster, and adding a peer's hitbox to it is a separate piece of work with
    // its own question attached (who decides a hit, when both peers resolve it
    // independently). Bodies you can see and walk around, but not damage.
    private void advanceRemoteBodies()
    {
        final RemotePlayers bodies = remoteBodies;
        if (bodies == null)
        {
            return;
        }
        bodies.advance(net, deltaSeconds);
    }

    // Puts every peer body back on the spawn point for a rematch.
    private void resetRemoteBodies()
    {
        final RemotePlayers bodies = remoteBodies;
        if (bodies == null)
        {
            return;
        }
        bodies.reset(spawnX, spawnY, spawnZ, spawnYawRadians);
    }

    // Moves each live peer's model to where the replay says it is.
    //
    // Guarded on a bound scene for exactly the reason publishBotPlacements is:
    // the game loop publishes tics from the moment it starts, which on desktop is
    // before the launcher has called setScene, and setWorldTransform would throw
    // against a renderer with no instance table.
    private void publishRemoteBodies()
    {
        final RemotePlayers bodies = remoteBodies;
        if (bodies == null || renderer.scene() == null)
        {
            return;
        }
        bodies.publish(renderer);
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
        if (damage > 0 && match.isPlayerDown())
        {
            LOG.info("KILLED (tic {}) — death {}, respawning in {} tics", ticIndex,
                match.playerDeaths(), match.respawnTicsRemaining(ticIndex));
        }
        else if (damage > 0)
        {
            LOG.info("took {} damage — {} hp left", damage, match.playerHealth());
        }
        // AFTER the tick, because the tick is what ages the reward, and a consuming
        // read so exactly one thing announces each expiry. Covers both ways it can
        // end — the timer running out and a death cancelling it — because from the
        // player's side those are the same event: the gun stopped being special.
        if (match.consumeSuperBlasterExpired())
        {
            audio.play(SoundId.SUPER_BLASTER_SPENT);
            LOG.info("super blaster spent (tic {}) — back to {} damage a shot", ticIndex,
                Match.PLAYER_SHOT_DAMAGE);
        }
        // AFTER the tick, because the tick is what decides it, and consuming the
        // flag here means exactly one thing acts on each respawn. Match owns WHEN
        // a respawn happens; it has never heard of a spawn point, so putting the
        // body back is this object's half of the split.
        if (match.consumePlayerRespawned())
        {
            controller.respawnAt(spawnX, spawnY, spawnZ, spawnYawRadians, spawnPitchRadians);
            LOG.info("RESPAWNED at ({}, {}, {}) with {} hp", spawnX, spawnY, spawnZ,
                match.playerHealth());
        }
        final MatchState now = match.state();
        if (now != reportedState)
        {
            this.reportedState = now;
            LOG.info("MATCH {} — {}", now, match);
        }
    }

    // Turns this tic's return fire into something the player can see and hear.
    //
    // This is the whole of "the enemies do appear to shoot back, but you wouldn't
    // know it". Match had always resolved these shots and taken the health away;
    // nothing drew them and nothing played them, so a player under fire had no way
    // to tell it was happening, where it was coming from, or that moving would
    // help.
    //
    // Allocation-free per tic: the log is a reused buffer, the muzzle goes into a
    // reused array, and DemoEffects writes into arrays it allocated at startup.
    // On the great majority of tics the loop does not execute at all — seven DUMB
    // bots produce a shot every eighteen tics between them.
    //
    // NOT gated on match == null || effects == null the way the visible half is:
    // the sound is worth playing on a port with no scene behind it, which is what
    // every one of this class's own tests is.
    private void spawnIncomingFire(final int ticIndex)
    {
        if (match == null || !matchLive)
        {
            return;
        }
        final BotShotLog shots = match.shotsThisTic();
        for (int slot = 0; slot < shots.count(); slot++)
        {
            final Bot shooter = match.byId(shots.shooterId(slot));
            if (shooter == null)
            {
                continue;
            }
            // The noise, BEFORE the bolt and unconditionally on the outcome — the
            // same rule the player's own weapon follows, and for the same reason: a
            // sound that only played when a shot connected would tell the player
            // they had been hit before the health did.
            //
            // Through the VOICE GATE, which is the difference between this and the
            // player's trigger. That one is bounded by its own cooldown; seven
            // independent shooters are bounded by nothing, and copies of one
            // generated buffer started on the same tic sum in phase rather than
            // mixing. BotFireVoices owns the numbers.
            if (botVoices.allow(ticIndex))
            {
                audio.play(SoundId.BOT_WEAPON_FIRE);
            }
            if (effects == null)
            {
                continue;
            }
            // The muzzle is where the effect STARTS; the log's origin and
            // direction are the ray the hitscan actually used, and both are
            // needed — spawnIncoming reconciles them so the bolt leaves the gun
            // AND arrives where the shot went. A bolt that did one but not the
            // other is either floating out of a chest or lying about the damage.
            DemoScene.botMuzzle(shooter, muzzleScratch);
            effects.spawnIncoming(muzzleScratch[0], muzzleScratch[1], muzzleScratch[2],
                shots.originX(slot), shots.originY(slot), shots.originZ(slot),
                shots.directionX(slot), shots.directionY(slot), shots.directionZ(slot),
                shots.rangeUnits(slot));
        }
    }

    /**
     * Returns the gate deciding how much of the room's fire is heard. Never null.
     *
     * <p>Exposed so a test can assert the cap without a sound card, which is the
     * only way it can be asserted at all: {@code NullAudioPort} counts plays but
     * cannot say what a stack of them would sound like.</p>
     *
     * @return the bot-fire voice gate
     */
    public BotFireVoices botFireVoices()
    {
        return botVoices;
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
            publishBotWeapon(index, roster[index]);
        }
    }

    // One bot's blaster, moved to wherever its holder's hand is — or hidden,
    // because DemoScene.botWeaponPlacement returns the degenerate transform for a
    // dead bot exactly as botPlacement does.
    //
    // The two MUST agree about death, and this is the pairing that makes them: a
    // weapon left visible over a corpse is the most conspicuous object in the
    // room, and it would make "the body goes away" read as a rendering fault
    // rather than as a kill. They are published in the same loop iteration so
    // there is no tic on which one has been updated and the other has not.
    private void publishBotWeapon(final int index, final Bot bot)
    {
        if (botWeaponInstances == null || index >= botWeaponInstances.length)
        {
            return;
        }
        final int instance = botWeaponInstances[index];
        if (instance == DemoScene.NO_INSTANCE)
        {
            return;
        }
        renderer.setWorldTransform(instance, DemoScene.botWeaponPlacement(bot));
    }

    /**
     * Puts the whole world back to how it was before the round started.
     *
     * <p>This is the rematch. Everything that carries match state is restored
     * together, and <b>together is the operative word</b> — the state is spread
     * across four objects with four owners, and a reset that missed one would
     * produce a room that was mostly new:</p>
     *
     * <ul>
     *   <li>{@link Match#reset()} — every bot alive at full health, back on its
     *       route at tic 0, cooldown cleared, memory of the player forgotten;
     *       and every counter zeroed, so the next summary is this round's rather
     *       than the sum of two.</li>
     *   <li>{@link PlayerController#respawnAt} — position, yaw, pitch and
     *       vertical velocity back to the spawn this port captured at
     *       construction.</li>
     *   <li>{@link DemoEffects#clear()} — the tracers and smoke of the last shot
     *       of the last round, which would otherwise still be crossing the room
     *       when the new one began.</li>
     *   <li>{@link #publishBotPlacements()} — and this is the one that is easy to
     *       forget and impossible to miss. A dead bot is hidden by a
     *       <i>degenerate transform override</i> held in the renderer, not by
     *       anything in the simulation. Reviving a bot in {@code Match} does not
     *       un-hide it; only publishing its placement again does. Without this
     *       line the rematch is seven live, shooting, invisible opponents.</li>
     * </ul>
     *
     * <p>Under the tic lock, so a rematch cannot land halfway through a tic and
     * leave the bots reset and the player not. Called from the platform's UI
     * thread when the player leaves the game-over screen, which is why it needs
     * to be — every other write to this state happens on the game loop
     * thread.</p>
     *
     * <p>Harmless on a port with no match: the {@code --model=} path has none,
     * and "restart nothing" is the correct answer rather than a reason to make
     * the caller check.</p>
     */
    public void restartMatch()
    {
        tickLock.lock();
        try
        {
            if (match == null)
            {
                return;
            }
            match.reset();
            controller.respawnAt(spawnX, spawnY, spawnZ, spawnYawRadians, spawnPitchRadians);
            if (effects != null)
            {
                effects.clear();
            }
            this.reportedState = MatchState.IN_PROGRESS;
            this.lastFireTic = -FIRE_INTERVAL_TICS;
            // Beside the effects and for the same reason: a rematch that inherited
            // the last round's final shot would refuse the first few tics of the
            // new one, and "the first bot to shoot at me was silent" is not a bug
            // anybody would trace back to a rematch.
            botVoices.clear();
            // The peers go back to the spawn with everyone else. Their cursors are
            // cleared too, so a body does not try to resume the tic sequence of the
            // round that just ended — RemotePlayers.reset explains why that has to
            // be forgotten rather than carried over.
            resetRemoteBodies();
            // The un-hide. See the Javadoc above: this is what makes the corpses
            // visible again, and nothing in Match can do it.
            publishBotPlacements();
            publishRemoteBodies();
            publishEffects();
            LOG.info("MATCH RESTARTED — {} opponents back up, {}", match.botCount(), match);
        }
        finally
        {
            tickLock.unlock();
        }
    }

    /**
     * Returns a one-frame snapshot of the live score, or null when there is no
     * match.
     *
     * <p>What the on-screen counter and the death notice are drawn from. Called
     * from the platform's render thread once a frame; see {@link MatchStatus} for
     * why it is a copy rather than a reference to the live {@code Match}.</p>
     *
     * <p>Deliberately does <b>not</b> take the tic lock. Every field it reads is
     * an {@code int} or a {@code boolean}, so the worst a concurrent tic can do
     * is give the frame a health value from one tic beside a kill count from the
     * next — a discrepancy that lasts 16 ms and that nobody can see. Taking the
     * lock would put the simulation behind the display, which is a defect anybody
     * can see.</p>
     *
     * @return the live figures, or null for a camera-only demo
     */
    public MatchStatus status()
    {
        if (match == null)
        {
            return null;
        }
        return MatchStatus.of(match, lastTicIndex);
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
