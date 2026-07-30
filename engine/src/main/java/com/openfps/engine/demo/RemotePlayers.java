/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.PhysicsWorld;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.gameplay.port.I_PlayerInput;
import com.openfps.engine.net.NetSession;
import com.openfps.engine.net.TicCmdBuffer;
import com.openfps.engine.net.TicCmdEncoder;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A_ The other players, given bodies.
 *
 * <p>This is the piece the transport was waiting for. {@link NetSession} has
 * carried peers' inputs both ways for some time and put them in a ring indexed
 * by tic; nothing turned them into anyone you could see, so two instances that
 * were talking perfectly showed each other an empty room. That is
 * indistinguishable from a broken session, which is why it was the oldest open
 * item in the project.</p>
 *
 * <h2>A peer's body is simulated, not interpolated</h2>
 *
 * <p>Each peer gets its own {@link PlayerController}, bound to the same
 * {@link PhysicsWorld} the local player collides with, and it is driven by
 * <b>replaying that peer's inputs through the same movement code the peer ran
 * itself</b>. Nothing sends a position. That is what makes this lockstep rather
 * than snapshot replication: two peers handed the same commands at the same tics
 * compute the same place, so there is nothing to reconcile and no authority to
 * disagree with. It also means a peer's body clips against the walls for free —
 * it is walking, not being dragged.</p>
 *
 * <h2>Every input is consumed exactly once, in order</h2>
 *
 * <p>{@link #advance} holds a per-peer cursor and applies tics strictly
 * ascending from it, one {@code update} per tic, stopping at the first tic that
 * has not arrived. <b>This is the rule lockstep cannot bend</b>: input is a
 * sequence and every element of it is needed, so a body may not skip to the
 * newest command the way a snapshot receiver would. The redundancy layer
 * re-sends recent commands, so the ring is repeatedly overwritten with values it
 * already holds — the cursor is what makes that idempotent, because it only ever
 * moves forward.</p>
 *
 * <p>A peer whose next tic has not arrived <b>holds still</b> rather than
 * guessing. Its body stops where its last known input put it and carries on the
 * moment the gap fills, catching up several tics in one call if it has to. That
 * is deliberately not the same as the full lockstep stall — this does not freeze
 * the local player, because a demo in which one packet loss locks up the whole
 * window would be unusable — and it is the one place this class knowingly
 * departs from a strict reading of {@code net/README.md} § 4. The honest
 * consequence is stated in {@link #advance}.</p>
 *
 * <h2>Bodies are pre-placed, then hidden</h2>
 *
 * <p>{@link Scene} is immutable, so a body cannot be created when a peer
 * connects. {@link #addTo} places the full complement of
 * {@link #MAX_BODIES} at build time and every one of them is hidden by the first
 * {@link #publish}; a body appears when its peer's first command arrives. This
 * is exactly the pattern {@link DemoEffects} uses for tracers and smoke,
 * including the reason the pool cannot simply be built hidden:
 * {@code Scene.Builder} validates every transform it is handed and rejects the
 * degenerate one, so hiding has to happen through a render override afterwards.
 * The {@link #hidden} flag is what guarantees it happens before a frame is
 * drawn, rather than relying on a caller to remember.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Not thread-safe, and it does not need to be: every method belongs to the
 * game loop thread, which is the thread that has a tic to apply and a transform
 * to publish. {@code DemoGameplayPort} calls both under its tic lock.</p>
 */
public final class RemotePlayers
{
    /**
     * How many peer bodies exist, which is every player but the local one.
     *
     * <p>The same bound {@link NetSession#MAX_PEERS} enforces on the session, so
     * a session that accepted a peer can never be holding one this pool has no
     * body for.</p>
     */
    public static final int MAX_BODIES = NetSession.MAX_PEERS;

    /** Cursor value meaning "this body has never had an input applied". */
    public static final int NO_TIC = -1;

    private static final Logger LOG = LoggerFactory.getLogger(RemotePlayers.class);

    /** Where each body's model sits among the scene's world instances. */
    private final int[] bodyInstance;

    /**
     * Where each body's carbine sits, or {@link DemoScene#NO_INSTANCE}.
     *
     * <p>A second table rather than an interleaved one, for the reason
     * {@code DesktopLauncher} already records about the bots: the two are
     * published in the same loop and unpacking a stride is exactly the kind of
     * arithmetic that ends up moving a weapon to a body's position.</p>
     */
    private final int[] weaponInstance;

    /** One controller per body, all bound to the room's own solid geometry. */
    private final PlayerController[] controller;

    /**
     * The next tic each body wants. MUTABLE: advanced as inputs are applied.
     *
     * <p>{@link #NO_TIC} until the peer's first command arrives, which is what
     * makes a body appear at the right moment rather than at tic zero.</p>
     */
    private final int[] nextTic;

    /** How many tics each body has applied. MUTABLE, for the summary. */
    private final long[] ticsApplied;

    /**
     * Reused input adapter. MUTABLE, and only ever inside one call.
     *
     * <p>One object rather than one per tic per peer: this runs sixty times a
     * second and {@code STYLE.md} § 13.4 bans allocation on a per-tic path. Safe
     * because {@link #advance} applies one command at a time and nothing here is
     * re-entrant — the same trade {@code PlayerInputView} already makes for the
     * local player.</p>
     */
    private final WireInput wireInput = new WireInput();

    /**
     * Whether the pool has been hidden yet. MUTABLE, set by the first publish.
     *
     * <p>A flag rather than a {@code hideAll()} the caller must remember, and it
     * is load-bearing: the builder refuses the degenerate transform, so every
     * body enters the scene <b>visible</b>, standing on the spawn point. Without
     * this the demo opens with seven identical strangers piled on the player.</p>
     */
    private boolean hidden;

    /** How many bodies have ever been live. MUTABLE, for the summary. */
    private int liveCount;

    // Built only by addTo, which is the only thing that can know the scene
    // indices — they have to be captured as the instances are appended.
    private RemotePlayers(final int[] bodyIndices, final int[] weaponIndices,
        final PlayerController[] controllers)
    {
        this.bodyInstance = bodyIndices;
        this.weaponInstance = weaponIndices;
        this.controller = controllers;
        this.nextTic = new int[controllers.length];
        this.ticsApplied = new long[controllers.length];
        for (int body = 0; body < nextTic.length; body++)
        {
            nextTic[body] = NO_TIC;
        }
    }

    /**
     * Places every peer body into a scene under construction.
     *
     * <p>Call once, while the scene is being built, and hand the result to
     * {@code DemoGameplayPort}. Each body is added at the spawn placement — a
     * legal transform, which is what {@link Scene.Builder} requires — and hidden
     * by the first {@link #publish} before a frame is drawn.</p>
     *
     * <p>Every body starts at the <b>same spawn placement the local player
     * gets</b>, and that is not a placeholder: it is what makes the simulation
     * agree. A peer's own controller starts at that spawn too, so replaying its
     * inputs from the same origin reaches the same position. Starting a body
     * anywhere else would put every peer permanently offset from where it
     * actually is.</p>
     *
     * @param builder the scene under construction; instances are appended. Must
     *     not be null
     * @param models the loaded model set, for the character and carbine art;
     *     must not be null
     * @param physics the room's solid geometry, which every body clips against;
     *     must not be null
     * @param spawnFeetX spawn position, world x — the local player's own
     * @param spawnFeetY spawn position, world y; the floor, not the eye
     * @param spawnFeetZ spawn position, world z
     * @param spawnYawRadians the heading a body faces before it has moved
     * @return the pool, holding the scene index of everything it placed. Empty,
     *     and harmless, when no character art is staged
     */
    public static RemotePlayers addTo(final Scene.Builder builder, final DemoModels models,
        final PhysicsWorld physics, final float spawnFeetX, final float spawnFeetY,
        final float spawnFeetZ, final float spawnYawRadians)
    {
        if (builder == null)
        {
            throw new IllegalArgumentException("builder must not be null");
        }
        if (models == null)
        {
            throw new IllegalArgumentException("models must not be null");
        }
        if (physics == null)
        {
            throw new IllegalArgumentException("physics must not be null");
        }
        if (!models.hasCharacters())
        {
            // The same degraded-but-not-broken case addBots has: no character
            // art means no bodies to place, here and for the bots alike. A
            // networked match then still exchanges inputs and still shows
            // nobody, which is at least the same answer the single-player demo
            // gives.
            LOG.info("Remote player bodies: none placed — no character art is staged");
            return new RemotePlayers(new int[0], new int[0], new PlayerController[0]);
        }

        final ModelFormat[] people = models.characters();
        final ModelFormat blaster = models.botWeapon();
        final int[] bodies = new int[MAX_BODIES];
        final int[] weapons = new int[MAX_BODIES];
        final PlayerController[] controllers = new PlayerController[MAX_BODIES];
        for (int body = 0; body < MAX_BODIES; body++)
        {
            final int entityId = Match.FIRST_REMOTE_ENTITY_ID + body;
            // Counted backwards through the staged models so a two-player match
            // does not put the peer in the same shirt as bot zero. Cosmetic, and
            // the only reason it is worth a line: telling a player from a bot at
            // a glance is most of what makes a networked room readable.
            final ModelFormat model = people[(people.length - 1 - body % people.length)];
            bodies[body] = builder.worldInstanceCount();
            builder.addWorldInstance(model, DemoScene.placement(spawnFeetX, spawnFeetY,
                spawnFeetZ, spawnYawRadians, DemoScene.CHARACTER_WORLD_SCALE), entityId);
            weapons[body] = addWeapon(builder, blaster, entityId, spawnFeetX, spawnFeetY,
                spawnFeetZ, spawnYawRadians);
            controllers[body] = new PlayerController(spawnFeetX, spawnFeetY, spawnFeetZ,
                spawnYawRadians, 0.0f, physics);
        }
        LOG.info("Remote player bodies: {} placed from {} model(s), ids {}..{}, armed: {}",
            MAX_BODIES, people.length, Match.FIRST_REMOTE_ENTITY_ID,
            Match.FIRST_REMOTE_ENTITY_ID + MAX_BODIES - 1, blaster != null);
        return new RemotePlayers(bodies, weapons, controllers);
    }

    // One body's carbine, tagged with the BODY's entity id rather than its own,
    // so the outline pass draws a single silhouette round a peer and what it is
    // holding — the same choice addBotWeapon makes, and for the same reason.
    private static int addWeapon(final Scene.Builder builder, final ModelFormat blaster,
        final int entityId, final float feetX, final float feetY, final float feetZ,
        final float yawRadians)
    {
        if (blaster == null)
        {
            return DemoScene.NO_INSTANCE;
        }
        final int at = builder.worldInstanceCount();
        builder.addWorldInstance(blaster, DemoScene.heldWeaponPlacement(feetX, feetY, feetZ,
            yawRadians), entityId);
        return at;
    }

    /**
     * Replays every peer's arrived inputs into its body.
     *
     * <p>For each connected peer, applies commands strictly ascending from that
     * body's cursor and stops at the first tic missing from the ring. A body may
     * therefore advance by several tics in one call — that is a peer catching up
     * after a lost packet, which the redundancy layer fills in with no
     * retransmission — or by none at all, which is a peer whose next tic is still
     * in flight.</p>
     *
     * <p><b>What "none at all" costs, stated plainly.</b> The body holds its last
     * position rather than extrapolating. Under sustained loss a peer looks like
     * it is stuttering, because it is: this shows the truth about what has
     * arrived instead of inventing motion that the next packet would then
     * contradict. A strict lockstep implementation would instead stall the entire
     * simulation until the tic arrived, which is correct for a competitive match
     * and wrong for a window someone is trying to look at — see the class
     * Javadoc.</p>
     *
     * @param session the live session whose ring holds the arrived commands, or
     *     null for a local match, in which case nothing happens
     * @param deltaSeconds the tic duration in seconds — the <b>same</b> value the
     *     local player integrates with, which is what makes the replay match what
     *     the peer computed. Must be non-negative
     * @return how many peer tics were applied across all bodies
     */
    public int advance(final NetSession session, final float deltaSeconds)
    {
        if (session == null || controller.length == 0)
        {
            return 0;
        }
        final TicCmdBuffer ring = session.commands();
        final int peers = session.peerCount();
        int applied = 0;
        for (int peer = 0; peer < peers && peer < controller.length; peer++)
        {
            // Slot is peer index + 1: NetSession.LOCAL_SLOT is 0 and peers take
            // the slots above it, in the order they were added. Body index and
            // peer index are the same number; neither is the player id, which
            // is a network identity and may be any value at all.
            applied = applied + advanceBody(peer, ring, peer + 1, deltaSeconds);
        }
        return applied;
    }

    // One body, caught up as far as the ring allows.
    private int advanceBody(final int body, final TicCmdBuffer ring, final int slot,
        final float deltaSeconds)
    {
        int cursor = nextTic[body];
        if (cursor == NO_TIC)
        {
            // The peer's first packet decides where its body starts in time.
            // Anchoring on the ring's oldest available tic rather than on zero
            // matters when a peer joins late: starting at zero would make the
            // body replay a history the ring no longer holds, find every tic
            // missing, and never move at all.
            final int latest = ring.latestTic(slot);
            if (latest == TicCmdBuffer.EMPTY_TIC)
            {
                return 0;
            }
            cursor = oldestHeldTic(ring, slot, latest);
            this.liveCount = liveCount + 1;
            LOG.info("Remote body {} is live — first input at tic {}", body, cursor);
        }
        int applied = 0;
        while (ring.has(slot, cursor))
        {
            apply(body, ring, slot, cursor, deltaSeconds);
            cursor = cursor + 1;
            applied = applied + 1;
        }
        nextTic[body] = cursor;
        ticsApplied[body] = ticsApplied[body] + applied;
        return applied;
    }

    // The oldest tic still held for a slot, scanned across the whole ring window.
    //
    // It scans rather than walking back from the newest and stopping at the first
    // hole, and the difference is a real bug rather than a style choice: a first
    // packet that arrives with a gap already in it — tics 0, 1, 3, 4, which is
    // ordinary for an unordered datagram service — would anchor the cursor at 3
    // and silently discard tics 0 and 1. Under lockstep, dropping input is the one
    // thing that may never happen, so the anchor has to be the oldest tic present
    // anywhere in the window and not the start of the newest contiguous run.
    //
    // Bounded by the ring depth, so it cannot run away on a wrapped buffer: a tic
    // older than that is no longer addressable and has genuinely gone.
    private static int oldestHeldTic(final TicCmdBuffer ring, final int slot, final int latest)
    {
        int floor = latest - ring.depth() + 1;
        if (floor < 0)
        {
            floor = 0;
        }
        for (int tic = floor; tic <= latest; tic++)
        {
            if (ring.has(slot, tic))
            {
                return tic;
            }
        }
        // Unreachable in practice — the caller has already established that the
        // slot holds `latest`. Returning it is the answer that keeps the cursor
        // sane rather than one that would replay the whole ring.
        return latest;
    }

    // One command, through the same movement code the sender ran.
    //
    // The look is SET rather than integrated, because the wire carries the
    // sender's absolute quantised yaw and pitch. Summing deltas instead would
    // drift a little further on every lost packet and never recover; an absolute
    // angle is self-correcting by construction. PlayerController.setLook exists
    // for this and says so.
    private void apply(final int body, final TicCmdBuffer ring, final int slot,
        final int tic, final float deltaSeconds)
    {
        final PlayerController peer = controller[body];
        peer.setLook(TicCmdEncoder.decodeAngle(ring.angle(slot, tic)),
            TicCmdEncoder.decodePitch(ring.pitch(slot, tic)));
        wireInput.set(TicCmdEncoder.decodeAxis(ring.forward(slot, tic)),
            TicCmdEncoder.decodeAxis(ring.strafe(slot, tic)),
            TicCmdEncoder.isDown(ring.buttons(slot, tic), TicCmdEncoder.BUTTON_JUMP));
        peer.update(wireInput, deltaSeconds);
    }

    /**
     * Moves every live body's model to where its simulation says it is, and hides
     * the rest.
     *
     * <p>The seam between simulation and rendering, and one reference store per
     * body per tic. The {@link Scene} itself is untouched: it is immutable, and
     * rebuilding it to move a body would re-derive the texture table and the
     * entity ids of a whole room for nothing.</p>
     *
     * <p>Bodies with no input yet are hidden with {@link DemoEffects#HIDDEN},
     * which collapses every vertex onto a point — no colour, no depth and
     * <b>no entity id</b>, so an unused slot cannot turn the crosshair red.</p>
     *
     * @param renderer the renderer to publish into; must not be null and must
     *     already have a scene bound
     */
    public void publish(final SoftwareRenderPort renderer)
    {
        if (renderer == null)
        {
            throw new IllegalArgumentException("renderer must not be null");
        }
        if (controller.length == 0)
        {
            return;
        }
        if (!hidden)
        {
            // Before anything else, and exactly once: the pool entered the scene
            // visible because the builder rejects the degenerate transform.
            hideEverything(renderer);
            this.hidden = true;
        }
        for (int body = 0; body < controller.length; body++)
        {
            if (nextTic[body] == NO_TIC)
            {
                // Never had an input, so it is still standing hidden on the
                // spawn point. Not republished — an idle slot costs nothing,
                // the same way DemoEffects skips a slot it hid last tic.
                continue;
            }
            final PlayerController peer = controller[body];
            renderer.setWorldTransform(bodyInstance[body], DemoScene.placement(
                peer.positionX(), peer.positionY(), peer.positionZ(), peer.yawRadians(),
                DemoScene.CHARACTER_WORLD_SCALE));
            publishWeapon(renderer, body, peer);
        }
    }

    // One body's carbine, moved to wherever its holder's hand is. Published in
    // the same iteration as the body so there is no tic on which one has moved
    // and the other has not — a weapon left behind is the most conspicuous
    // object in a room.
    private void publishWeapon(final SoftwareRenderPort renderer, final int body,
        final PlayerController peer)
    {
        final int instance = weaponInstance[body];
        if (instance == DemoScene.NO_INSTANCE)
        {
            return;
        }
        renderer.setWorldTransform(instance, DemoScene.heldWeaponPlacement(
            peer.positionX(), peer.positionY(), peer.positionZ(), peer.yawRadians()));
    }

    // Every instance in the pool, collapsed to nothing.
    private void hideEverything(final SoftwareRenderPort renderer)
    {
        for (final int instance : bodyInstance)
        {
            renderer.setWorldTransform(instance, DemoEffects.HIDDEN);
        }
        for (final int instance : weaponInstance)
        {
            if (instance != DemoScene.NO_INSTANCE)
            {
                renderer.setWorldTransform(instance, DemoEffects.HIDDEN);
            }
        }
    }

    /**
     * Puts every body back on the spawn point and forgets what has arrived.
     *
     * <p>The rematch, and the counterpart to {@code Match.reset()}. The cursors
     * are cleared as well as the positions, so a body does not try to resume a
     * tic sequence from the round that just ended.</p>
     *
     * @param spawnFeetX spawn position, world x
     * @param spawnFeetY spawn position, world y
     * @param spawnFeetZ spawn position, world z
     * @param spawnYawRadians the heading to face
     */
    public void reset(final float spawnFeetX, final float spawnFeetY, final float spawnFeetZ,
        final float spawnYawRadians)
    {
        for (int body = 0; body < controller.length; body++)
        {
            controller[body].respawnAt(spawnFeetX, spawnFeetY, spawnFeetZ, spawnYawRadians,
                0.0f);
            nextTic[body] = NO_TIC;
        }
        this.liveCount = 0;
        // Forces the next publish to hide the pool again, which is what makes a
        // rematch start with nobody visible rather than with the previous
        // round's bodies frozen where they stopped.
        this.hidden = false;
    }

    /**
     * Returns how many bodies this pool placed.
     *
     * @return the body count, zero when no character art is staged
     */
    public int bodyCount()
    {
        return controller.length;
    }

    /**
     * Returns whether a body has had any input applied.
     *
     * @param body which body, from 0
     * @return true once the peer's first command has been replayed
     */
    public boolean isLive(final int body)
    {
        return nextTic[body] != NO_TIC;
    }

    /**
     * Returns the next tic a body is waiting for.
     *
     * @param body which body, from 0
     * @return the cursor, or {@link #NO_TIC} before the first input
     */
    public int nextTic(final int body)
    {
        return nextTic[body];
    }

    /**
     * Returns how many tics a body has applied.
     *
     * @param body which body, from 0
     * @return the count, which is how far that peer has been simulated
     */
    public long ticsApplied(final int body)
    {
        return ticsApplied[body];
    }

    /**
     * Returns a body's controller, for the camera-independent state a test or a
     * log line needs.
     *
     * @param body which body, from 0
     * @return that peer's simulated controller, never null once placed
     */
    public PlayerController controller(final int body)
    {
        return controller[body];
    }

    /**
     * Returns the scene instance index of a body's model.
     *
     * @param body which body, from 0
     * @return its index among the scene's world instances
     */
    public int bodyInstanceIndex(final int body)
    {
        return bodyInstance[body];
    }

    /**
     * Returns the scene instance index of a body's weapon.
     *
     * @param body which body, from 0
     * @return its weapon's index, or {@link DemoScene#NO_INSTANCE} when no
     *     carbine was staged
     */
    public int weaponInstanceIndex(final int body)
    {
        return weaponInstance[body];
    }

    /**
     * Returns a debug rendering of every live body's progress and placement.
     *
     * <p>The live bodies are listed individually rather than counted, because the
     * count alone cannot answer the only question worth asking of a networked run:
     * <b>did the peer actually move, and how far did its simulation get?</b> A
     * body that is live but stuck reports a tic total that has stopped growing and
     * a position that has not changed, which is the signature of the failure this
     * whole class exists to make visible.</p>
     *
     * @return a rendering naming each live body's applied tics and position
     */
    @Override
    public String toString()
    {
        final StringBuilder text = new StringBuilder(64);
        text.append("RemotePlayers{bodies=").append(controller.length)
            .append(", live=").append(liveCount);
        for (int body = 0; body < controller.length; body++)
        {
            if (nextTic[body] == NO_TIC)
            {
                continue;
            }
            final PlayerController peer = controller[body];
            text.append(", [").append(body).append("] tics=").append(ticsApplied[body])
                .append(" nextTic=").append(nextTic[body])
                .append(" at (").append(peer.positionX()).append(", ")
                .append(peer.positionY()).append(", ").append(peer.positionZ())
                .append(") yaw=").append(peer.yawRadians());
        }
        return text.append('}').toString();
    }

    /**
     * The decoded wire command, presented as the four numbers and one flag
     * {@link PlayerController} consumes.
     *
     * <p>The look deltas are always zero, and that is the whole design rather
     * than a stub: the sender's absolute angles are applied through
     * {@link PlayerController#setLook} immediately before {@code update}, so
     * there is no delta left to integrate. Reporting anything else here would
     * turn the angle twice.</p>
     */
    private static final class WireInput implements I_PlayerInput
    {
        /** Forward intent from the wire. MUTABLE, rewritten per command. */
        private float forward;

        /** Strafe intent from the wire. MUTABLE, rewritten per command. */
        private float strafe;

        /** Whether the peer held jump. MUTABLE, rewritten per command. */
        private boolean jumping;

        // Points this adapter at one decoded command.
        void set(final float forwardAxis, final float strafeAxis, final boolean jumpHeld)
        {
            this.forward = forwardAxis;
            this.strafe = strafeAxis;
            this.jumping = jumpHeld;
        }

        @Override
        public float forwardAxis()
        {
            return forward;
        }

        @Override
        public float strafeAxis()
        {
            return strafe;
        }

        @Override
        public float yawDelta()
        {
            return 0.0f;
        }

        @Override
        public float pitchDelta()
        {
            return 0.0f;
        }

        @Override
        public boolean jump()
        {
            return jumping;
        }
    }
}
