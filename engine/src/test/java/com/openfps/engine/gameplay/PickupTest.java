/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 2026-08: the pickup system. Pins the proximity check that
 * the map-mode pickup detection uses every tic. The check is
 * squared throughout (no sqrt on the hot path); the test
 * exercises the boundary cases the squares would otherwise
 * miss.
 */
final class PickupTest
{
    @Nested
    @DisplayName("isAt proximity check")
    class Proximity
    {
        @Test
        @DisplayName("the player on the pickup's tile is on the pickup")
        void shouldPickUpWhenPlayerIsOnTheTile()
        {
            final Pickup p = new Pickup(Weapon.SHOTGUN, 100.0f, 0.0f, 200.0f);

            assertThat(p.isAt(100.0f, 0.0f, 200.0f))
                .as("the player is exactly on the pickup")
                .isTrue();
        }

        @Test
        @DisplayName("a player one tile over in XZ is out of range")
        void shouldNotPickUpFromTheNextTile()
        {
            final Pickup p = new Pickup(Weapon.SHOTGUN, 0.0f, 0.0f, 0.0f);

            assertThat(p.isAt(Pickup.PICKUP_RADIUS_UNITS * 2.0f, 0.0f, 0.0f))
                .as("the player is two tile-widths away, well outside PICKUP_RADIUS_UNITS")
                .isFalse();
        }

        @Test
        @DisplayName("a player on a gantry too far below cannot reach a gantry pickup")
        void shouldNotPickUpFromTooFarBelow()
        {
            // The shipped vertical reach is 96 units (one and a half
            // Kenney grid units), which a 64-unit gantry DOES reach
            // from the ground. Use a 256-unit overhead so the
            // "out of reach" assertion is unambiguous.
            final Pickup p = new Pickup(Weapon.ROCKET_LAUNCHER, 0.0f, 256.0f, 0.0f);

            assertThat(p.isAt(0.0f, 0.0f, 0.0f))
                .as("a player on the ground is 256 units below the pickup, well outside the 96-unit reach")
                .isFalse();

            assertThat(p.isAt(0.0f, 256.0f, 0.0f))
                .as("a player on the gantry is at the pickup's Y")
                .isTrue();
        }

        @Test
        @DisplayName("a player one tile up but on the XZ column is out of range")
        void shouldNotPickUpFromTooFarAbove()
        {
            final Pickup p = new Pickup(Weapon.SHOTGUN, 0.0f, 0.0f, 0.0f);

            assertThat(p.isAt(0.0f, Pickup.PICKUP_VERTICAL_REACH_UNITS * 2.0f, 0.0f))
                .as("a player well above the pickup cannot reach down")
                .isFalse();
        }
    }
}
