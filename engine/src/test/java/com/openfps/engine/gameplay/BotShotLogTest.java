/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the per-tic record of return fire, and for {@link Match} filling it.
 *
 * <p>The property that matters is not that a shot is recorded — it is that the
 * recorded <b>ray is the one the hitscan used</b>. A tracer drawn down the
 * unscattered heading would show a bolt arriving dead-on while the health said
 * the shot went wide, and a player cannot learn to avoid fire that disagrees with
 * the damage. That is asserted here against {@link Hitscan} itself rather than
 * against a remembered constant.</p>
 */
@DisplayName("BotShotLog")
final class BotShotLogTest
{
    /** Float tolerance for a direction built from a sine and a cosine. */
    private static final float EPSILON = 1.0e-4f;

    /** A seed with no special properties, so the scatter is a real scatter. */
    private static final long SEED = 0x5EEDL;

    @Nested
    @DisplayName("as a buffer")
    final class Buffer
    {
        @Test
        @DisplayName("reads back exactly what was recorded")
        void roundTrips()
        {
            final BotShotLog log = new BotShotLog(3);

            log.record(7, 1.0f, 2.0f, 3.0f, 0.0f, 0.0f, 1.0f, 250.0f);

            assertThat(log.count()).isEqualTo(1);
            assertThat(log.shooterId(0)).isEqualTo(7);
            assertThat(log.originX(0)).isEqualTo(1.0f);
            assertThat(log.originY(0)).isEqualTo(2.0f);
            assertThat(log.originZ(0)).isEqualTo(3.0f);
            assertThat(log.directionZ(0)).isEqualTo(1.0f);
            assertThat(log.rangeUnits(0)).isEqualTo(250.0f);
        }

        @Test
        @DisplayName("clear forgets the tic without wiping anything")
        void clearsToEmpty()
        {
            final BotShotLog log = new BotShotLog(3);
            log.record(7, 1.0f, 2.0f, 3.0f, 0.0f, 0.0f, 1.0f, 250.0f);

            log.clear();

            assertThat(log.count()).isZero();
            assertThatThrownBy(() -> log.shooterId(0))
                .as("last tic's shot is still readable, so a tracer would respawn at a "
                    + "stale muzzle")
                .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("a shot past capacity is dropped rather than throwing")
        void overflowIsSilent()
        {
            // Unreachable — one entry per bot and a bot cannot fire twice on a
            // tic — but this is cosmetic data on the tic path, and an exception
            // here would take a running match down over a missing tracer.
            final BotShotLog log = new BotShotLog(1);

            log.record(2, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 10.0f);
            log.record(3, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 10.0f);

            assertThat(log.count()).isEqualTo(1);
            assertThat(log.shooterId(0)).isEqualTo(2);
        }

        @Test
        @DisplayName("a room with nobody in it is a legal, empty log")
        void emptyRosterIsLegal()
        {
            final BotShotLog log = new BotShotLog(0);

            assertThat(log.capacity()).isZero();
            assertThat(log.count()).isZero();
        }
    }

    @Nested
    @DisplayName("filled by a match")
    final class FromMatch
    {
        // A room of one bot standing 200 units down +z from a player at the
        // origin, ready to fire on the first tic it is asked.
        private Match roomOfOne(final BotSkill skill)
        {
            final Bot lone = new Bot(Match.FIRST_BOT_ENTITY_ID, 0.0f, 0.0f, 200.0f,
                BotPattern.SENTRY, 0.0f, 300, 0);
            return new Match(new Bot[] {lone}, new BotRng(SEED), skill, Match.UNLIMITED_DEATHS);
        }

        // Ticks until a shot is recorded, or gives up. Returns the tic it landed
        // on, or -1 — the trigger is a per-tic roll, so which tic is not knowable
        // in advance without duplicating BotRng here.
        private int ticUntilShot(final Match match, final int limit)
        {
            for (int tic = 0; tic < limit; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
                if (match.shotsThisTic().count() > 0)
                {
                    return tic;
                }
            }
            return -1;
        }

        @Test
        @DisplayName("records one entry per hitscan, naming the shooter")
        void recordsTheShooter()
        {
            final Match match = roomOfOne(BotSkill.MARKSMAN);

            assertThat(ticUntilShot(match, 200)).isNotNegative();
            assertThat(match.shotsThisTic().count()).isEqualTo(1);
            assertThat(match.shotsThisTic().shooterId(0))
                .isEqualTo(Match.FIRST_BOT_ENTITY_ID);
        }

        @Test
        @DisplayName("the recorded ray is the SCATTERED one the hitscan was given")
        void recordsTheRayThatWasFired()
        {
            // The whole point. A DUMB bot scatters its aim by up to 14 degrees, so
            // the recorded direction must not be the clean bearing to the player —
            // and the only way to know it is the real one is to re-fire it and get
            // the same answer the match got.
            final Match match = roomOfOne(BotSkill.DUMB);
            final int tic = ticUntilShot(match, 5000);
            assertThat(tic).as("the bot never fired at all").isNotNegative();

            final BotShotLog log = match.shotsThisTic();
            final float dirX = log.directionX(0);
            final float dirY = log.directionY(0);
            final float dirZ = log.directionZ(0);

            assertThat(dirX * dirX + dirY * dirY + dirZ * dirZ)
                .as("Hitscan requires a unit direction and rejects anything else")
                .isCloseTo(1.0f, within(EPSILON));

            // Re-run the shot through Hitscan against the same player box. It must
            // agree with what the match decided, because it is the same ray.
            final Target player = Target.aroundFeet(Match.PLAYER_ENTITY_ID, 0.0f, 0.0f, 0.0f,
                Bot.RADIUS_UNITS, Bot.HEIGHT_UNITS);
            final HitResult result = new HitResult();
            final boolean struck = Hitscan.fire(log.originX(0), log.originY(0), log.originZ(0),
                dirX, dirY, dirZ, new Target[] {player}, 1, result);

            assertThat(struck).isEqualTo(match.botShotsLanded() > 0);
        }

        @Test
        @DisplayName("the range is the distance to what the shooter was aiming at")
        void recordsTheRange()
        {
            final Match match = roomOfOne(BotSkill.MARKSMAN);
            assertThat(ticUntilShot(match, 200)).isNotNegative();

            // The bot is 200 units down +z and the player is at the origin, so the
            // ground distance to what it remembers is 200. That is what a tracer
            // aims its convergence at.
            assertThat(match.shotsThisTic().rangeUnits(0)).isCloseTo(200.0f, within(0.5f));
        }

        @Test
        @DisplayName("a tic on which nobody fires leaves an empty log, not the last one's")
        void staleShotsAreNotRepublished()
        {
            // Without the clear at the top of tick, the effect layer would respawn
            // the same bolts every tic until the next shot — a strobing line of
            // fire out of a bot that had fired once.
            final Match match = roomOfOne(BotSkill.MARKSMAN);
            final int tic = ticUntilShot(match, 200);
            assertThat(tic).isNotNegative();

            // MARKSMAN's cooldown is one tic and its fire chance is certain, so
            // step far enough that this is a tic the bot could not fire on: a dead
            // bot fires nothing at all.
            match.byId(Match.FIRST_BOT_ENTITY_ID).damage(Bot.MAX_HEALTH);
            match.tick(tic + 1, 0.0f, 0.0f, 0.0f);

            assertThat(match.shotsThisTic().count()).isZero();
        }

        @Test
        @DisplayName("holds one slot per bot, because a bot cannot fire twice on one tic")
        void capacityIsTheRoster()
        {
            final Bot[] roster = new Bot[Match.DEFAULT_BOT_COUNT];
            for (int index = 0; index < roster.length; index++)
            {
                roster[index] = new Bot(Match.FIRST_BOT_ENTITY_ID + index, index * 20.0f, 0.0f,
                    150.0f, BotPattern.SENTRY, 0.0f, 300, 0);
            }
            final Match match = new Match(roster, new BotRng(SEED), BotSkill.MARKSMAN,
                Match.UNLIMITED_DEATHS);

            match.tick(0, 0.0f, 0.0f, 0.0f);

            assertThat(match.shotsThisTic().capacity()).isEqualTo(Match.DEFAULT_BOT_COUNT);
            assertThat(match.shotsThisTic().count())
                .isLessThanOrEqualTo(match.shotsThisTic().capacity());
        }
    }
}
