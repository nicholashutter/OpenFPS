/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Path;

import com.openfps.engine.core.FrameRate;
import com.openfps.engine.core.GameConfig;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.InputState;
import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the demo's per-tic loop — the join between input, the player and
 * the camera.
 *
 * <p>The interesting assertion is the last one: that the camera the renderer
 * actually renders from is the one the controller produced. Every other part of
 * this chain has its own tests, and the chain still failed to exist for several
 * phases because nothing connected them.</p>
 */
@DisplayName("DemoGameplayPort")
final class DemoGameplayPortTest
{
    /** Viewport used where a surface is needed; small, because nothing looks at it. */
    private static final int SURFACE = 64;

    /** Floating-point slack for position comparisons, in world units. */
    private static final float EPSILON = 1.0e-3f;

    /** A configuration at the default rate. */
    private static GameConfig config()
    {
        return GameConfig.unbounded(FrameRate.FPS_60);
    }

    /** A render port with no worker pool — the serial reference path. */
    private static SoftwareRenderPort renderer()
    {
        final I_TimePort time = new NullTimePort();
        time.init();
        final SoftwareRenderPort port = new SoftwareRenderPort(null, time);
        port.init();
        return port;
    }

    /**
     * An input port that replays one snapshot and counts how often it is
     * latched.
     *
     * <p>Counting matters: the look deltas are an integral since the previous
     * latch, so sampling twice in a tic would double a turn and sampling not at
     * all would freeze the view. Both are silent.</p>
     */
    private static final class ScriptedInput implements I_InputPort
    {
        /** What every latch produces. */
        private final InputState state;

        /** How many times {@link #sampleInput} has been called. MUTABLE. */
        private int samples;

        /** What {@link #currentInput} returns. MUTABLE: set by sampleInput. */
        private InputState latched = InputState.NEUTRAL;

        ScriptedInput(final InputState scripted)
        {
            this.state = scripted;
        }

        @Override
        public void init()
        {
            // nothing to open
        }

        @Override
        public void shutdown()
        {
            // nothing to close
        }

        @Override
        public void sampleInput(final int ticIndex)
        {
            samples++;
            latched = state;
        }

        @Override
        public InputState currentInput()
        {
            return latched;
        }

        @Override
        public boolean isShutdownRequested()
        {
            return false;
        }

        int samples()
        {
            return samples;
        }
    }

    @Nested
    @DisplayName("construction")
    final class Construction
    {
        @Test
        @DisplayName("derives the tic duration from the configured rate")
        void deltaComesFromTheConfig()
        {
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), renderer(), new PlayerController(),
                GameConfig.unbounded(FrameRate.FPS_60));

            // The fixed-timestep loop makes a tic exactly 1/rate seconds; a
            // measured render delta would make movement speed depend on the
            // monitor.
            assertThat(port.deltaSeconds()).isCloseTo(1.0f / 60.0f, within(1.0e-6f));
        }

        @Test
        @DisplayName("follows the rate when it changes")
        void deltaTracksTheRate()
        {
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), renderer(), new PlayerController(),
                GameConfig.unbounded(FrameRate.FPS_120));

            assertThat(port.deltaSeconds()).isCloseTo(1.0f / 120.0f, within(1.0e-6f));
        }

        @Test
        @DisplayName("rejects every null dependency")
        void rejectsNulls()
        {
            final I_InputPort input = new ScriptedInput(InputState.NEUTRAL);
            final SoftwareRenderPort render = renderer();
            final PlayerController player = new PlayerController();

            assertThatThrownBy(() -> new DemoGameplayPort(null, render, player, config()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DemoGameplayPort(input, null, player, config()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DemoGameplayPort(input, render, null, config()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DemoGameplayPort(input, render, player, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("per tic")
    final class PerTic
    {
        @Test
        @DisplayName("latches the input exactly once")
        void latchesOncePerTic()
        {
            final ScriptedInput input = new ScriptedInput(InputState.NEUTRAL);
            final DemoGameplayPort port = new DemoGameplayPort(input, renderer(),
                new PlayerController(), config());

            port.tick(0);
            port.tick(1);
            port.tick(2);

            assertThat(input.samples()).isEqualTo(3);
            assertThat(port.ticsApplied()).isEqualTo(3L);
        }

        @Test
        @DisplayName("walks the player forward when the forward axis is held")
        void movesThePlayer()
        {
            final ScriptedInput input = new ScriptedInput(
                InputState.of(1.0f, 0.0f, 0.0f, 0.0f, false, false, false));
            final PlayerController player = new PlayerController();
            final DemoGameplayPort port = new DemoGameplayPort(input, renderer(), player,
                config());

            for (int tic = 0; tic < 60; tic++)
            {
                port.tick(tic);
            }

            // Yaw 0 faces world +z, and a second of walking is one
            // MOVE_SPEED_UNITS_PER_SECOND.
            assertThat(player.positionZ())
                .isCloseTo(PlayerController.MOVE_SPEED_UNITS_PER_SECOND, within(0.5f));
            assertThat(player.positionX()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("turns the player when the look delta is non-zero")
        void turnsThePlayer()
        {
            final float perTic = 0.01f;
            final ScriptedInput input = new ScriptedInput(
                InputState.of(0.0f, 0.0f, perTic, 0.0f, false, false, false));
            final PlayerController player = new PlayerController();
            final DemoGameplayPort port = new DemoGameplayPort(input, renderer(), player,
                config());

            for (int tic = 0; tic < 50; tic++)
            {
                port.tick(tic);
            }

            // Fifty rightward nudges, and a rightward turn DECREASES this angle
            // — PlayerController.applyLook subtracts the delta, because its yaw
            // sweeps +z toward +x and that is a left turn. Wrapped into
            // [0, 2pi), so the expectation is a whole turn less the total.
            //
            // This test is about the port latching and forwarding every tic's
            // delta exactly once, not about which way that is; the direction is
            // pinned against a strafe step in PlayerControllerTest.LookDirection,
            // because an angle compared with another angle cannot see a mirror.
            assertThat(player.yawRadians())
                .isCloseTo(PlayerController.FULL_TURN_RADIANS - 50 * perTic, within(EPSILON));
        }

        @Test
        @DisplayName("does not aim a camera before there is a surface")
        void survivesTicsBeforeTheWindowExists()
        {
            // The game loop publishes tics from the moment it starts, which on
            // desktop is before GLFW has reported a size. An aspect of 0/0 is a
            // NaN that Camera.create rejects, so this must be skipped, not
            // attempted.
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), renderer(), new PlayerController(),
                config());

            assertThatCode(() -> port.tick(0)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the camera handoff")
    final class CameraHandoff
    {
        @Test
        @DisplayName("the frame is rendered from the controller's own eye")
        void cameraReachesTheRenderer()
        {
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(Scene.of(ModelFormat.read(DemoModelFixture.quad())));

            final PlayerController player = new PlayerController(10.0f, 0.0f, -20.0f,
                0.0f, 0.0f);
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), render, player, config());

            port.tick(0);
            render.renderFrame(0);

            // This is the whole join, asserted end to end: the camera the
            // rasterizer actually used is the one the player produced.
            assertThat(render.lastCamera()).isNotNull();
            assertThat(render.lastCamera().eye().x()).isCloseTo(10.0f, within(EPSILON));
            assertThat(render.lastCamera().eye().y())
                .isCloseTo(PlayerController.EYE_HEIGHT_UNITS, within(EPSILON));
            assertThat(render.lastCamera().eye().z()).isCloseTo(-20.0f, within(EPSILON));
        }

        @Test
        @DisplayName("the camera follows the player as input moves them")
        void cameraTracksMovement()
        {
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(Scene.of(ModelFormat.read(DemoModelFixture.quad())));

            final PlayerController player = new PlayerController();
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.of(1.0f, 0.0f, 0.0f, 0.0f, false, false, false)),
                render, player, config());

            port.tick(0);
            render.renderFrame(0);
            final float first = render.lastCamera().eye().z();

            for (int tic = 1; tic <= 30; tic++)
            {
                port.tick(tic);
            }
            render.renderFrame(1);

            assertThat(render.lastCamera().eye().z()).isGreaterThan(first);
        }
    }

    @Nested
    @DisplayName("the bot handoff")
    final class BotHandoff
    {
        /** Tics to run before checking placements. Past a quarter of every route. */
        private static final int SETTLE_TICS = 130;

        // The demo world, with all seven character models staged.
        private static DemoScene world(final Path root) throws IOException
        {
            for (final String person : new String[] {"character-a.ofm", "character-d.ofm",
                "character-h.ofm", "character-k.ofm", "character-n.ofm", "character-q.ofm",
                "character-r.ofm"})
            {
                DemoModelFixture.write(
                    root.resolve(DemoModels.CHARACTER_DIRECTORY).resolve(person));
            }
            for (final String piece : new String[] {"floor-square.ofm", "wall.ofm",
                "wall-doorway.ofm", "column.ofm", "crate.ofm", "stairs.ofm", "shape-slope.ofm"})
            {
                DemoModelFixture.write(root.resolve(DemoModels.LEVEL_DIRECTORY).resolve(piece));
            }
            DemoModelFixture.write(
                root.resolve(DemoModels.WEAPON_DIRECTORY).resolve(DemoModels.WEAPON_MODEL));
            return DemoScene.build(DemoModels.load(root));
        }

        // The scene index of every bot, exactly as DesktopLauncher builds it.
        private static int[] indicesOf(final DemoScene demo)
        {
            final int[] indices = new int[demo.botCount()];
            for (int index = 0; index < indices.length; index++)
            {
                indices[index] = demo.botInstanceIndex(index);
            }
            return indices;
        }

        // A port with the match already running. Every test below is about what
        // a LIVE match does; the frozen case has its own test.
        private static DemoGameplayPort portFor(final DemoScene demo,
            final SoftwareRenderPort render, final Match round)
        {
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), render, demo.spawnController(),
                config(), round, indicesOf(demo));
            port.setMatchLive(true);
            return port;
        }

        @Test
        @DisplayName("a match does not start until the UI says the player is in the world")
        void shouldFreezeTheMatchUntilTheWorldIsInFront(@TempDir final Path root)
            throws IOException
        {
            // The game loop starts with the process and ticks whatever is on
            // screen — that is the design, and it is right. The MATCH must not.
            // Before this gate existed the bots patrolled and fired from the
            // moment the window opened, so a player who read the title screen
            // for ten seconds arrived already down a fifth of their health.
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(demo.scene());

            final Match round = demo.newMatch();
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), render, demo.spawnController(),
                config(), round, indicesOf(demo));

            assertThat(port.isMatchLive()).as("a new port starts frozen").isFalse();

            // Long enough that every bot's cadence has come round several times.
            for (int tic = 0; tic <= Match.BOT_FIRE_INTERVAL_TICS * 3; tic++)
            {
                port.tick(tic);
            }

            assertThat(round.playerHealth())
                .as("the player was shot while the menu was up")
                .isEqualTo(Match.PLAYER_MAX_HEALTH);
            final int walker = firstMover(round);
            assertThat(round.bots()[walker].positionX())
                .as("a bot walked its patrol behind the menu")
                .isEqualTo(0.0f);
        }

        @Test
        @DisplayName("the match resumes exactly where it was left")
        void shouldResumeWhenTheWorldComesBack(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(demo.scene());

            final Match round = demo.newMatch();
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.NEUTRAL), render, demo.spawnController(),
                config(), round, indicesOf(demo));

            port.tick(0);
            port.setMatchLive(true);
            assertThat(port.isMatchLive()).isTrue();
            for (int tic = 1; tic <= SETTLE_TICS; tic++)
            {
                port.tick(tic);
            }

            // Positions are a closed-form function of the tic index, so a match
            // that was frozen for a while does not resume in the past — it
            // resumes wherever the clock has reached. That is the property that
            // lets a peer join late and agree with everyone else.
            final int walker = firstMover(round);
            assertThat(round.bots()[walker].positionX()).isNotEqualTo(0.0f);
        }

        @Test
        @DisplayName("each bot's MODEL ends up where that bot's BODY is")
        void shouldPlaceEveryBotsModelOnItsOwnBody(@TempDir final Path root) throws IOException
        {
            // The invariant this whole feature rests on, and the one a
            // screenshot cannot establish: the body you SEE and the box the
            // simulation TESTS are the same thing in the same place. They are
            // joined by a single int — the scene instance index — and if that
            // index is off by one, every model walks somebody else's patrol
            // while the hitboxes stay put. You would shoot a visible bot and
            // hit nothing, and see nothing wrong in any single frame.
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(demo.scene());

            final Match round = demo.newMatch();
            final DemoGameplayPort port = portFor(demo, render, round);
            for (int tic = 0; tic <= SETTLE_TICS; tic++)
            {
                port.tick(tic);
            }

            final Bot[] roster = round.bots();
            for (int index = 0; index < roster.length; index++)
            {
                final Mat4 placed = render.worldTransformOverride(demo.botInstanceIndex(index));
                assertThat(placed)
                    .as("bot %d's model was never placed", index)
                    .isNotNull();
                // Column 3 of a placement is its translation.
                assertThat(placed.get(0, 3))
                    .as("bot %d's model is not standing on bot %d's feet (x)", index, index)
                    .isCloseTo(roster[index].positionX(), within(EPSILON));
                assertThat(placed.get(2, 3))
                    .as("bot %d's model is not standing on bot %d's feet (z)", index, index)
                    .isCloseTo(roster[index].positionZ(), within(EPSILON));
            }
        }

        @Test
        @DisplayName("a moving bot's model moves with it")
        void shouldMoveTheModelWhenTheBotWalks(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(demo.scene());

            final Match round = demo.newMatch();
            final DemoGameplayPort port = portFor(demo, render, round);

            port.tick(0);
            final int walker = firstMover(round);
            final float startX = render
                .worldTransformOverride(demo.botInstanceIndex(walker)).get(0, 3);

            for (int tic = 1; tic <= SETTLE_TICS; tic++)
            {
                port.tick(tic);
            }
            final float laterX = render
                .worldTransformOverride(demo.botInstanceIndex(walker)).get(0, 3);

            assertThat(laterX)
                .as("bot %d walks a route but its model never left the spot", walker)
                .isNotEqualTo(startX);
        }

        @Test
        @DisplayName("a sentry's model does not drift")
        void shouldLeaveASentrysModelWhereItStands(@TempDir final Path root) throws IOException
        {
            // The other half of the test above. A model that moves when it
            // should not is the same index bug seen from the other side.
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(demo.scene());

            final Match round = demo.newMatch();
            final DemoGameplayPort port = portFor(demo, render, round);
            final int sentry = firstSentry(round);

            port.tick(0);
            final float startX = render
                .worldTransformOverride(demo.botInstanceIndex(sentry)).get(0, 3);
            final float startZ = render
                .worldTransformOverride(demo.botInstanceIndex(sentry)).get(2, 3);

            for (int tic = 1; tic <= SETTLE_TICS; tic++)
            {
                port.tick(tic);
            }

            assertThat(render.worldTransformOverride(demo.botInstanceIndex(sentry)).get(0, 3))
                .isEqualTo(startX);
            assertThat(render.worldTransformOverride(demo.botInstanceIndex(sentry)).get(2, 3))
                .isEqualTo(startZ);
        }

        @Test
        @DisplayName("a killed bot's model lies down")
        void shouldTopplTheModelWhenABotIsKilled(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(demo.scene());

            final Match round = demo.newMatch();
            final DemoGameplayPort port = portFor(demo, render, round);
            port.tick(0);

            final int victim = firstSentry(round);
            round.bots()[victim].damage(Bot.MAX_HEALTH);
            port.tick(1);

            // Column 1 is the image of the model's up axis. Standing, it is
            // world up; fallen, it is horizontal.
            final Mat4 down = render.worldTransformOverride(demo.botInstanceIndex(victim));
            assertThat(down.get(1, 1))
                .as("the body is still standing up")
                .isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("does not touch the renderer before a scene is bound")
        void shouldSurviveTicsBeforeTheSceneExists(@TempDir final Path root) throws IOException
        {
            // The game loop publishes tics from the moment it starts, which on
            // desktop is BEFORE the launcher calls setScene. Moving an instance
            // then throws, and the throw surfaced as a subsystem error on tic 0
            // of every run.
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);

            final DemoGameplayPort port = portFor(demo, render, demo.newMatch());

            assertThatCode(() -> port.tick(0)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the very first pull of the trigger fires")
        void shouldFireTheFirstShotOfAMatch(@TempDir final Path root) throws IOException
        {
            // The regression for a bug that made the weapon useless on every
            // platform for the whole of a run. lastFireTic was initialised to
            // Long.MIN_VALUE — the obvious "has never fired" sentinel — and the
            // cooldown test is a subtraction: ticIndex - lastFireTic. That
            // overflows, wraps to a large NEGATIVE number, and compares as
            // "still cooling down". lastFireTic is only written after the test
            // passes, so it never passed and the trigger never worked.
            //
            // Nothing else would have caught it. Match.firePlayerShot has its
            // own tests and they pass; Hitscan's pass; the input path's pass.
            // The defect lives exactly in the join between them, and it took
            // firing at a bot by hand on a phone to find it.
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(demo.scene());

            final Match round = demo.newMatch();
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.of(0.0f, 0.0f, 0.0f, 0.0f, true, false, false)),
                render, demo.spawnController(), config(), round, indicesOf(demo));
            port.setMatchLive(true);

            port.tick(0);

            assertThat(round.playerShotsFired())
                .as("the trigger was held on tic 0 and nothing came out")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a held trigger fires on the weapon's cadence, not once per tic")
        void shouldRespectTheCooldown(@TempDir final Path root) throws IOException
        {
            // The other half of the same arithmetic. With the trigger held for
            // a whole second at 60 Hz the weapon must fire on its own interval;
            // a fix that simply removed the cooldown would pass the test above
            // and turn the blaster into 60 hitscans a second.
            final DemoScene demo = world(root);
            final SoftwareRenderPort render = renderer();
            render.resize(SURFACE, SURFACE);
            render.setScene(demo.scene());

            final Match round = demo.newMatch();
            final DemoGameplayPort port = new DemoGameplayPort(
                new ScriptedInput(InputState.of(0.0f, 0.0f, 0.0f, 0.0f, true, false, false)),
                render, demo.spawnController(), config(), round, indicesOf(demo));
            port.setMatchLive(true);

            final int tics = 60;
            for (int tic = 0; tic < tics; tic++)
            {
                port.tick(tic);
            }

            // Tic 0, then every twelfth: 0, 12, 24, 36, 48 — five shots.
            assertThat(round.playerShotsFired())
                .isEqualTo(1 + ((tics - 1) / DemoGameplayPort.FIRE_INTERVAL_TICS));
        }

        // The first bot with a route that actually goes somewhere.
        private static int firstMover(final Match round)
        {
            final Bot[] roster = round.bots();
            for (int index = 0; index < roster.length; index++)
            {
                if (roster[index].pattern().moves())
                {
                    return index;
                }
            }
            throw new IllegalStateException("the roster has no moving bot to test with");
        }

        // The first bot that holds still.
        private static int firstSentry(final Match round)
        {
            final Bot[] roster = round.bots();
            for (int index = 0; index < roster.length; index++)
            {
                if (!roster[index].pattern().moves())
                {
                    return index;
                }
            }
            throw new IllegalStateException("the roster has no sentry to test with");
        }
    }
}
