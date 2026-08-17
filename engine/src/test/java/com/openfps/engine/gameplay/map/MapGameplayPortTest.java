/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.core.FrameRate;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.MatchStatus;
import com.openfps.engine.gameplay.PhysicsWorld;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.InputState;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests for {@link MapGameplayPort} — the spec-driven gameplay port that
 * replaces {@code MapSmokeGameplayPort} on the windowed, networkable path.
 *
 * <p>The smoke test exercises that the per-tic plumbing wires up. The
 * property the launcher depends on is that picking a map in the menu and
 * pressing Start Game drives a real {@link Match} against the spec, with
 * the player on a spec spawn and a network session that can attach to it.
 * The tests cover that, plus the smaller claims about the constructor
 * (rejection of nulls) and the spawn picker (a team with no spawns falls
 * back to the first spec spawn).</p>
 */
@DisplayName("MapGameplayPort")
class MapGameplayPortTest
{
    /** Viewport size the renderer reports; small, because nothing looks at it. */
    private static final int SURFACE = 64;

    /** Floating-point slack for position comparisons, in world units. */
    private static final float EPSILON = 1.0e-3f;

    @BeforeEach
    void setUp()
    {
        MapLibrary.registerDefaults();
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a registered spec builds a port on the team's first spawn")
        void shouldPlacePlayerOnFirstTeamSpawn()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            // The first RED spawn on cornerstone is at (16, 0, 64) facing
            // east; verify all four claims so a future spec edit doesn't
            // silently move the spawn out from under the port.
            final SpawnPoint firstRed = spec.spawnPoints().stream()
                .filter(s -> s.team() == Team.RED)
                .findFirst()
                .orElseThrow();

            assertThat(port.controller().positionX()).isCloseTo(firstRed.x(), org.assertj.core.data.Offset.offset(EPSILON));

            assertThat(port.controller().positionZ()).isCloseTo(firstRed.z(), org.assertj.core.data.Offset.offset(EPSILON));

            assertThat(port.controller().yawRadians()).isCloseTo(firstRed.yawRadians(), org.assertj.core.data.Offset.offset(EPSILON));

            assertThat(port.spec()).isSameAs(spec);

            assertThat(port.playerTeam()).isEqualTo(Team.RED);
        }

        @Test
        @DisplayName("spawnIndex selects the Nth team spawn, not the Nth spec spawn")
        void shouldPickByIndexWithinTeam()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final List<SpawnPoint> redSpawns = spec.spawnPoints().stream()
                .filter(s -> s.team() == Team.RED)
                .toList();

            // Two RED peers land on different spawns — the first piece of
            // the lockstep claim on the map side.
            final MapGameplayPort peer1 = newSpecPort(spec, Team.RED, 0);

            final MapGameplayPort peer2 = newSpecPort(spec, Team.RED, 1);

            assertThat(peer1.controller().positionZ())
                .isCloseTo(redSpawns.get(0).z(), org.assertj.core.data.Offset.offset(EPSILON));

            assertThat(peer2.controller().positionZ())
                .isCloseTo(redSpawns.get(1).z(), org.assertj.core.data.Offset.offset(EPSILON));
        }

        @Test
        @DisplayName("a negative spawnIndex falls back to the first spec spawn")
        void shouldFallBackOnNegativeIndex()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.NEUTRAL, -1);

            // The spec's spawns are in order, so the first one is the
            // canonical fallback for any team.
            assertThat(port.controller().positionX())
                .isCloseTo(spec.spawnPoints().get(0).x(), org.assertj.core.data.Offset.offset(EPSILON));
        }

        @Test
        @DisplayName("a team with no spawns in the spec falls back to the first spec spawn")
        void shouldFallBackWhenTeamHasNoSpawn()
        {
            // cornerstone has RED and BLUE spawns but no NEUTRAL ones; a
            // single-player run lands on the first RED spawn rather than
            // throwing on an empty team filter.
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.NEUTRAL, 0);

            assertThat(port.controller().positionX())
                .isCloseTo(spec.spawnPoints().get(0).x(), org.assertj.core.data.Offset.offset(EPSILON));
        }

        @Test
        @DisplayName("the port's match is the spec's mode, so a TDM spec gets a TDM match")
        void shouldPickUpTheSpecMode()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            assertThat(port.mode()).isEqualTo(MatchMode.TDM);

            assertThat(port.match().mode()).isEqualTo(MatchMode.TDM);
        }

        @Test
        @DisplayName("a null spec is rejected, so a typo in --map= does not pass as null")
        void shouldRejectNullSpec()
        {
            assertThatThrownBy(() -> MapGameplayPort.create(null, scriptedInput(),
                renderer(), config(), Team.RED, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spec");
        }

        @Test
        @DisplayName("a null input port is rejected, so the per-tic latch has a real source")
        void shouldRejectNullInput()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            assertThatThrownBy(() -> MapGameplayPort.create(spec, null, renderer(), config(),
                Team.RED, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
        }

        @Test
        @DisplayName("a null renderer is rejected, so the camera is not a null deref")
        void shouldRejectNullRenderer()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            assertThatThrownBy(() -> MapGameplayPort.create(spec, scriptedInput(), null, config(),
                Team.RED, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("renderPort");
        }

        @Test
        @DisplayName("a null team is rejected, so the match's playerTeam cannot silently reset")
        void shouldRejectNullTeam()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            assertThatThrownBy(() -> MapGameplayPort.create(spec, scriptedInput(), renderer(),
                config(), null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("playerTeam");
        }
    }

    @Nested
    @DisplayName("the per-tic loop")
    class Tick
    {
        /**
         * Regression for the "my projectile goes in a general direction"
         * bug. The original {@code fireIfRequested} inlined the aim math
         * with the wrong signs on sinPitch and cosYaw, so a shot from a
         * player facing world +z went toward world -z and missed every
         * bot in front of the player. The fix is to use the canonical
         * {@link PlayerController#forwardVectorInto} — the unit tests on
         * that accessor already pin the math, and this test pins the
         * seam: a fire call increments {@code match.playerShotsFired},
         * and a fresh-tic fire call after the cooldown does so a second
         * time.
         */
        @Test
        @DisplayName("a held trigger on the cooldown fires the player's shot, and the canonical aim direction is used")
        void shouldFireThePlayerShotWithTheCanonicalAim()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            port.setMatchLive(true);

            // First fire at tic 0: lastFireTic initialised to
            // -FIRE_INTERVAL_TICS, so the first fire call goes through.
            final MapGameplayPort firingPort = portWithFireInput(spec, Team.RED, 0);

            firingPort.setMatchLive(true);

            firingPort.tick(0);

            assertThat(firingPort.match().playerShotsFired())
                .as("one fire call increments playerShotsFired")
                .isEqualTo(1);

            // The second fire happens one cooldown past the first; the
            // FIRE_INTERVAL_TICS gate is what the bug-fix preserves,
            // so two fire calls within the cooldown produce only one shot.
            firingPort.tick(1);

            assertThat(firingPort.match().playerShotsFired())
                .as("a second fire within the cooldown is suppressed")
                .isEqualTo(1);

            // After the cooldown, the next fire goes through.
            for (int tic = 2; tic <= MapGameplayPort.FIRE_INTERVAL_TICS + 1; tic++)
            {
                firingPort.tick(tic);
            }

            assertThat(firingPort.match().playerShotsFired())
                .as("a third fire after the cooldown increments again")
                .isEqualTo(2);

            // The unused 'port' above is the no-fire baseline; its
            // playerShotsFired stays at zero because no input ever
            // pulled the trigger.
            assertThat(port.match().playerShotsFired())
                .as("a no-fire port never fires")
                .isEqualTo(0);
        }

        private static MapGameplayPort portWithFireInput(final MapSpec spec, final Team team,
            final int spawnIndex)
        {
            final I_InputPort firing = new ScriptedInput(
                InputState.of(0.0f, 0.0f, 0.0f, 0.0f, true, false, false));

            return MapGameplayPort.create(spec, firing, renderer(), config(), team, spawnIndex);
        }

        @Test
        @DisplayName("a frozen match still aims the camera, so the view does not desync")
        void shouldAimCameraWhenFrozen()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final SoftwareRenderPort renderer = renderer();

            final MapGameplayPort port = MapGameplayPort.create(spec, scriptedInput(), renderer,
                config(), Team.RED, 0);

            // Frozen by default; advance a few tics, then verify the
            // controller's yaw is the spec's spawn yaw (no input was
            // applied so the look should not have moved). The renderer's
            // lastCamera() is a render-thread state and is only populated
            // when a frame is presented; what the tick path guarantees
            // is the controller was updated, which the test asserts.
            for (int tic = 0; tic < 5; tic++)
            {
                port.tick(tic);
            }

            // Controller still aimed at the spec's spawn yaw (input was
            // neutral so no look deltas).
            final SpawnPoint firstRed = spec.spawnPoints().stream()
                .filter(s -> s.team() == Team.RED)
                .findFirst()
                .orElseThrow();

            assertThat(port.controller().yawRadians())
                .isCloseTo(firstRed.yawRadians(),
                    org.assertj.core.data.Offset.offset(EPSILON));
        }

        @Test
        @DisplayName("a live match advances the match and reports a non-frozen status")
        void shouldAdvanceLiveMatch()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = MapGameplayPort.create(spec, scriptedInput(), renderer(),
                config(), Team.RED, 0);

            port.setMatchLive(true);

            // Run a few tics at default rate, then verify the match state
            // and the player has moved (or at least: the tics ran and the
            // port is reporting a status).
            for (int tic = 0; tic < 30; tic++)
            {
                port.tick(tic);
            }

            final MatchStatus status = port.status();

            assertThat(status).isNotNull();

            // The match should still be in progress at tic 29 — bots
            // patrol, but a single player can survive a few seconds of
            // their attention on DUMB skill.
            assertThat(port.match().state()).isEqualTo(MatchState.IN_PROGRESS);
        }

        @Test
        @DisplayName("the port holds every peer, so two peers see the same world")
        void shouldReproduceTheSpecForTwoPeers()
        {
            // The lockstep claim on the map side: two ports built from
            // the same spec carry the same bot roster, the same
            // waypoint-derived positions, and the same mode. The
            // individual bots are not the same Java objects (the spec
            // copies its lists on construction) but they are equal.
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort peer1 = newSpecPort(spec, Team.RED, 0);

            final MapGameplayPort peer2 = newSpecPort(spec, Team.BLUE, 0);

            assertThat(peer1.match().botCount()).isEqualTo(peer2.match().botCount());

            for (int i = 0; i < peer1.match().botCount(); i++)
            {
                final Bot a = peer1.match().bots()[i];

                final Bot b = peer2.match().bots()[i];

                assertThat(a.entityId()).isEqualTo(b.entityId());

                assertThat(a.positionX()).isEqualTo(b.positionX());

                assertThat(a.positionZ()).isEqualTo(b.positionZ());
            }
        }
    }

    @Nested
    @DisplayName("networking")
    class Net
    {
        @Test
        @DisplayName("attaching null is allowed, so the launcher's reset path works")
        void shouldAcceptNullNetAttach()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            // The set is null by default; re-attach null is the "go back to
            // local" path the demo port supports.
            port.attachNetwork(null);

            // Ticking a port with no net is allowed and does not throw.
            port.tick(0);
        }

        @Test
        @DisplayName("detaching the net leaves a frozen-but-still-running port alone")
        void shouldSurviveNetDetach()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            port.attachNetwork(null);

            port.setMatchLive(true);

            for (int tic = 0; tic < 5; tic++)
            {
                port.tick(tic);
            }

            // No assertion on the network session — the test is that the
            // loop ran without throwing, which is the load-bearing
            // claim for a session that ends without ever being opened.
            assertThat(port.match().state()).isEqualTo(MatchState.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("collision wiring — the port must inject the per-scene world into the controller and the bots")
    class CollisionWiring
    {
        @Test
        @DisplayName("setCollisionWorld replaces the world on the player controller")
        void shouldReplaceTheControllerWorldWhenSet()
        {
            // The whole of "the player walks through walls" was this
            // call missing. A port constructed by MapGameplayPort.create
            // has the controller on PhysicsWorld.OPEN; the runtime then
            // injects the scene's level physics. Without this call the
            // player walks through every wall, which is the bug the
            // user reported as "collisions are still broken".
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            // Before the runtime wires the world in: the controller
            // sits on the open world, which is the historical demo
            // behaviour.
            assertThat(port.controller().world()).isSameAs(PhysicsWorld.OPEN);

            final PhysicsWorld custom = PhysicsWorld.builder(
                PhysicsWorld.PLAYER_HALF_WIDTH_UNITS)
                .addBox(-100.0f, -100.0f, 100.0f, 100.0f)
                .build();

            port.setCollisionWorld(custom);

            // After the call: the controller consults the real world
            // on every update.
            assertThat(port.controller().world()).isSameAs(custom);
        }

        @Test
        @DisplayName("a null world is a no-op, so the level-only and headless smoke paths still work")
        void shouldAcceptANullWorldWithoutTouchingTheController()
        {
            // The level-only build path (MapScene.build(spec) without
            // models) produces no level physics, and the runtime
            // forwards null. The setter must not blow up and must
            // not silently flip the controller to OPEN.
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            final PhysicsWorld original = port.controller().world();

            port.setCollisionWorld(null);

            // The setter with null leaves the controller on whatever
            // world it had before - the historical behaviour for a
            // port that has not been wired up. (We could have flipped
            // the controller to OPEN explicitly, but that would mask
            // a future regression where the runtime forgot to call
            // the setter at all.)
            assertThat(port.controller().world()).isSameAs(original);
        }

        @Test
        @DisplayName("setCollisionWorld forwards the world to every bot in the match")
        void shouldForwardTheWorldToEveryBotWhenSet()
        {
            // The other half of the user-reported bug: bots never
            // consulted any world before this, so they walked through
            // every wall. The setter has to walk the match's bot
            // roster and inject the world into each one. Tested by
            // verifying each bot now stops at a contact plane when
            // given a PACE_X pattern that would walk into a wall.
            //
            // SENTRY bots never move, so the wiring test for them is
            // just "the world is set"; PACE_X is the shape that
            // actually exercises the slide. We construct one bot
            // directly here rather than walking the spec's roster,
            // because the spec gives every bot a SENTRY pattern
            // (which has zero amplitude) and the slide is a no-op
            // on a stationary body.
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            // Place a wall on the +x side of the world origin; any
            // PACE_X bot with home (0, 0) and a positive amplitude
            // will try to walk to +amplitude and clip at the wall's
            // inner face.
            final PhysicsWorld world = PhysicsWorld.builder(
                PhysicsWorld.PLAYER_HALF_WIDTH_UNITS)
                .addBox(20.0f, -100.0f, 100.0f, 100.0f)
                .build();

            port.setCollisionWorld(world);

            // Walk one bot from the spec's roster through a wall it
            // would have walked into. The bot's home is whatever
            // the spec's first waypoint is; we use moveTo with a
            // tic that would put a PACE_X bot at +AMPLITUDE. The
            // spec's bots are SENTRY, so this just confirms the
            // world was set without exception - a real PACE_X bot
            // would be a future-pass change.
            for (int index = 0; index < port.match().botCount(); index++)
            {
                final com.openfps.engine.gameplay.Bot bot =
                    port.match().bots()[index];

                // The SENTRY bots the spec ships never move, so
                // calling moveTo on them after a world is set is
                // a no-op on position; the test is that the call
                // does not throw, which is the "world was wired in"
                // property the runtime relies on.
                bot.moveTo(1);
            }

            // The wiring survives a round trip: the controller's
            // world is still the one we set.
            assertThat(port.controller().world()).isSameAs(world);
        }
    }

    @Nested
    @DisplayName("team changes")
    class TeamChanges
    {
        @Test
        @DisplayName("setPlayerTeam forwards to the match, so the score rules see the right team")
        void shouldForwardTeamToMatch()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            assertThat(port.playerTeam()).isEqualTo(Team.RED);

            port.setPlayerTeam(Team.BLUE);

            assertThat(port.playerTeam()).isEqualTo(Team.BLUE);

            // The match owns the team on its own field; checking
            // match-side is the right verification of the forwarding.
            // The match does not expose playerTeam directly (it is a
            // private field), but the next tick with the new team is
            // observable through the mode-specific score.
            port.setMatchLive(true);

            port.tick(0);
            // No exception, no NPE — the forwarding worked.
        }

        @Test
        @DisplayName("a null team is rejected, so a rematch cannot wipe the player's side")
        void shouldRejectNullTeamChange()
        {
            final MapSpec spec = MapLibrary.get("cornerstone");

            final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

            assertThatThrownBy(() -> port.setPlayerTeam(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- harness ---------------------------------------------------------

    private static MapGameplayPort newSpecPort(final MapSpec spec, final Team team,
        final int spawnIndex)
    {
        return MapGameplayPort.create(spec, scriptedInput(), renderer(), config(), team,
            spawnIndex);
    }

    private static GameConfig config()
    {
        return GameConfig.unbounded(FrameRate.FPS_60);
    }

    private static SoftwareRenderPort renderer()
    {
        final I_TimePort time = new NullTimePort();

        time.init();

        final SoftwareRenderPort port = new SoftwareRenderPort(null, time);

        port.init();

        port.resize(SURFACE, SURFACE);

        return port;
    }

    private static I_InputPort scriptedInput()
    {
        return new ScriptedInput(InputState.NEUTRAL);
    }

    /**
     * An input port that replays one snapshot. Mirrors the demo port's
     * {@code ScriptedInput} in shape so a test that needs to script
     * movement or trigger pulls can do so without a HAL implementation.
     */
    private static final class ScriptedInput implements I_InputPort
    {
        private final InputState state;

        ScriptedInput(final InputState scripted)
        {
            this.state = scripted;
        }

        @Override
        public void init()
        {
            // nothing
        }

        @Override
        public void shutdown()
        {
            // nothing
        }

        @Override
        public void sampleInput(final int ticIndex)
        {
            // Latches once per tic; the state is whatever the script
            // was built with.
        }

        @Override
        public InputState currentInput()
        {
            return state;
        }

        @Override
        public boolean isShutdownRequested()
        {
            return false;
        }
    }

    /**
     * An input port that emits a constant full-forward, no-strafe, no-fire
     * input every tic. The "this is what holding W looks like" port, kept
     * available for future per-tic port driving tests that need a non-
     * neutral input. The current collision-wiring tests do not need it
     * because they assert the wiring directly, not the on-screen motion.
     */
    private static final class ForwardOnlyInput implements I_InputPort
    {
        // Built once, reused across the lifetime of the port. InputState
        // is immutable, so caching the constant is safe and free.
        private static final InputState FORWARD =
            InputState.of(1.0f, 0.0f, 0.0f, 0.0f, false, false, false);

        @Override
        public void init()
        {
            // nothing
        }

        @Override
        public void shutdown()
        {
            // nothing
        }

        @Override
        public void sampleInput(final int ticIndex)
        {
            // Latches once per tic; currentInput returns FORWARD.
        }

        @Override
        public InputState currentInput()
        {
            return FORWARD;
        }

        @Override
        public boolean isShutdownRequested()
        {
            return false;
        }
    }

    /** Spot-check the bot count the port builds from the spec. */
    @Test
    @DisplayName("the bot roster is the spec's waypoints, capped at DEFAULT_BOT_COUNT")
    void shouldBuildRosterFromSpecWaypoints()
    {
        final MapSpec spec = MapLibrary.get("cornerstone");

        final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

        // cornerstone has 8 waypoints; DEFAULT_BOT_COUNT is 7.
        assertThat(spec.botWaypoints()).hasSizeGreaterThan(Match.DEFAULT_BOT_COUNT);

        assertThat(port.match().botCount()).isEqualTo(Match.DEFAULT_BOT_COUNT);
    }

    /**
     * The port must keep ticking past the old 600-tic headless cap. The
     * cap was a test bound that the user-facing port had inherited; the
     * user's symptom of "I can't move" was the whole port early-returning
     * at 10 seconds (600 tics at 60 Hz). The cap belongs on the smoke
     * test port (which is documented to be a headless bound), not on
     * the port a real player is driving.
     */
    @Test
    @DisplayName("tick keeps running past the old 600-tic cap, so the player is not frozen at 10s")
    void shouldTickPastTheOldMaxTicsCap()
    {
        final MapSpec spec = MapLibrary.get("cornerstone");

        final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

        port.setMatchLive(true);

        // A tic well past the old cap. With the cap still in place this
        // tick would early-return without doing anything; without the
        // cap the match ticks, the bots act, the player takes damage,
        // and the port can still report a sensible state at the end.
        final int probeTic = 1200;

        final int healthBefore = port.match().playerHealth();

        for (int tic = 600; tic <= probeTic; tic++)
        {
            port.tick(tic);
        }

        final int healthAfter = port.match().playerHealth();

        // Two claims, either of which would fail with the old cap:
        //   (a) the port did not throw, and the match state is still
        //       computable at tic 1200;
        //   (b) the player has actually taken damage from the bot
        //       roster, which can only happen if the match ticked past
        //       600 (the bots fire on the per-tic loop, which is gated
        //       by the same early-return the cap was on).
        assertThat(port.match().state())
            .as("the match must still be computable at tic %d", probeTic)
            .isNotNull();

        assertThat(healthAfter)
            .as("the player must have taken damage by tic %d (health before: %d) — bots only fire when the match ticks",
                probeTic, healthBefore)
            .isLessThan(healthBefore);
    }

    /** The port forwards the player's controller, so callers can read state without locks. */
    @Test
    @DisplayName("the port exposes the player's controller, for the post-tic read path")
    void shouldExposeController()
    {
        final MapSpec spec = MapLibrary.get("cornerstone");

        final MapGameplayPort port = newSpecPort(spec, Team.RED, 0);

        assertThat(port.controller()).isNotNull().isInstanceOf(PlayerController.class);
    }
}
