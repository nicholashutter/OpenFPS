/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BotSkill}.
 *
 * <p>Mostly range checks, which is what a value object of six numbers deserves —
 * except for the two profiles, where the assertions are about the <b>design</b>
 * rather than the arithmetic. {@link BotSkill#DUMB} has to be genuinely dumb and
 * {@link BotSkill#MARKSMAN} has to be genuinely infallible, because half of
 * {@code MatchTest} would silently become a statistical test if the second one
 * ever drifted.</p>
 */
@DisplayName("BotSkill")
class BotSkillTest
{
    @Nested
    @DisplayName("the shipped profile")
    class Dumb
    {
        @Test
        @DisplayName("scatters shots widely enough to miss a player at mid range")
        void shouldHaveASpreadWiderThanTheTargetSubtends()
        {
            // The spread has to be compared against something, and the only
            // meaningful something is HOW BIG THE PLAYER LOOKS. A player 250 units
            // away subtends atan(16 / 250) either side of centre, about 3.7
            // degrees; a cone narrower than that would hit every time whatever its
            // documentation said.
            final float playerHalfAngle =
                (float) StrictMath.atan(Bot.RADIUS_UNITS / 250.0f);

            assertThat(BotSkill.DUMB.aimSpreadRadians())
                .as("the scatter cone is narrower than the player is wide — it cannot miss")
                .isGreaterThan(playerHalfAngle * 2.0f);
        }

        @Test
        @DisplayName("reacts slowly enough for a running player to get clear")
        void shouldReactSlowlyEnoughToBeOutrun()
        {
            // A reaction of one tic is not a reaction. The bar is the player's own
            // hitbox: the memory has to go stale by more than a body width in the
            // time it is held, or there is nothing to run out of.
            final float staleUnits = PlayerController.MOVE_SPEED_UNITS_PER_SECOND
                * BotSkill.DUMB.reactionTics() / 60.0f;

            assertThat(staleUnits).isGreaterThan(Bot.RADIUS_UNITS * 2.0f);
        }

        @Test
        @DisplayName("sometimes shoots at nothing at all")
        void shouldSometimesFireWild()
        {
            assertThat(BotSkill.DUMB.wildShotChancePermille()).isPositive();
        }

        @Test
        @DisplayName("keeps the room's rate of fire near the old fixed cadence")
        void shouldFireAtRoughlyTheOldRoomRate()
        {
            // The constraint the whole profile was solved under: the bots miss far
            // more than they did, so the room has to still SOUND as busy or the
            // demo goes quiet. The old cadence was 150 tics per bot, which across
            // seven bots is a shot somewhere every 21.
            final int oldRoomInterval = 150 / Match.DEFAULT_BOT_COUNT;

            final int newRoomInterval =
                BotSkill.DUMB.meanShotIntervalTics() / Match.DEFAULT_BOT_COUNT;

            assertThat(newRoomInterval)
                .as("the room fires every %d tics against the old %d",
                    newRoomInterval, oldRoomInterval)
                .isBetween(oldRoomInterval / 2, oldRoomInterval * 2);
        }

        @Test
        @DisplayName("has a randomised cooldown, so two bots cannot stay in step")
        void shouldSpreadItsCooldown()
        {
            // A fixed cooldown floor is a cadence again: two bots that happened to
            // fire on the same tic would stay in step for the rest of the round.
            assertThat(BotSkill.DUMB.cooldownSpreadTics()).isPositive();
        }
    }

    @Nested
    @DisplayName("the test instrument")
    class Marksman
    {
        @Test
        @DisplayName("never misses, never hesitates, always knows where the player is")
        void shouldBeInfallible()
        {
            // If any of these drifts, every geometry assertion in MatchTest
            // quietly becomes a statistical one and the suite starts failing once
            // a month for no reason. That is worth four lines here.
            assertThat(BotSkill.MARKSMAN.aimSpreadRadians()).isZero();

            assertThat(BotSkill.MARKSMAN.wildShotChancePermille()).isZero();

            assertThat(BotSkill.MARKSMAN.fireChancePermille()).isEqualTo(BotRng.PER_MILLE);

            assertThat(BotSkill.MARKSMAN.reactionTics()).isEqualTo(1);

            assertThat(BotSkill.MARKSMAN.cooldownSpreadTics()).isZero();

            assertThat(BotSkill.MARKSMAN.cooldownTics()).isEqualTo(1);
        }

        @Test
        @DisplayName("is not what the demo ships")
        void shouldNotBeTheDefault()
        {
            assertThat(new Match(new Bot[0]).skill()).isSameAs(BotSkill.DUMB);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("rejects a chance outside 0-1000")
        void shouldRejectAnOutOfRangeChance()
        {
            assertThatThrownBy(() -> new BotSkill(-1, 45, 90, 0.2f, 150, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggerChancePermille");

            assertThatThrownBy(() -> new BotSkill(1001, 45, 90, 0.2f, 150, 24))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new BotSkill(25, 45, 90, 0.2f, 1001, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildChancePermille");
        }

        @Test
        @DisplayName("rejects a cooldown of zero, which would let a bot fire twice on one tic")
        void shouldRejectANonPositiveCooldown()
        {
            assertThatThrownBy(() -> new BotSkill(25, 0, 90, 0.2f, 150, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumCooldownTics");
        }

        @Test
        @DisplayName("rejects a NaN or infinite spread before it can reach a shot direction")
        void shouldRejectANonFiniteSpread()
        {
            // NaN fails every comparison, so it would pass a naive >= 0 check and
            // then poison a shot direction into a ray Hitscan rejects — a bot that
            // silently never hit anything.
            assertThatThrownBy(() -> new BotSkill(25, 45, 90, Float.NaN, 150, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spreadRadians");

            assertThatThrownBy(
                () -> new BotSkill(25, 45, 90, Float.POSITIVE_INFINITY, 150, 24))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new BotSkill(25, 45, 90, -0.1f, 150, 24))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a reaction of zero and a negative cooldown spread")
        void shouldRejectTheRemainingOutOfRangeArguments()
        {
            assertThatThrownBy(() -> new BotSkill(25, 45, 90, 0.2f, 150, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("awarenessTics");

            assertThatThrownBy(() -> new BotSkill(25, 45, -1, 0.2f, 150, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extraCooldownTics");
        }

        @Test
        @DisplayName("a profile that never fires reports an infinite mean rather than dividing")
        void shouldNotDivideByAZeroChance()
        {
            assertThat(new BotSkill(0, 45, 90, 0.2f, 0, 24).meanShotIntervalTics())
                .isEqualTo(Integer.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("description")
    class Description
    {
        @Test
        @DisplayName("toString names every figure, so a log line explains a round")
        void shouldDescribeItself()
        {
            final String text = BotSkill.DUMB.toString();

            assertThat(text).contains("fire").contains("cooldown").contains("spread")
                .contains("wild").contains("reaction")
                .contains(String.valueOf(BotSkill.DUMB.meanShotIntervalTics()));
        }
    }
}
