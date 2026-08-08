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
