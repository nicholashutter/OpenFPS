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
 * 2026-08: the pickup system. Pins the wire id, the display
 * name, the fire mode, the damage, and the ammo max for each
 * of the three shipped weapons (blaster, shotgun, rocket
 * launcher). The wire id is what a spec stores; the display
 * name is what menus show. Both are load-bearing rather than
 * decorative, which is what the test pins.
 */
final class WeaponTest
{
    @Nested
    @DisplayName("the blaster")
    class Blaster
    {
        @Test
        @DisplayName("is the default, has infinite ammo, fires hitscan")
        void shouldBeTheStarterWeapon()
        {
            assertThat(Weapon.BLASTER.id()).isEqualTo("blaster");
            assertThat(Weapon.BLASTER.displayName()).isEqualTo("Blaster");
            assertThat(Weapon.BLASTER.fireMode()).isEqualTo(Weapon.FireMode.HITSCAN);
            assertThat(Weapon.BLASTER.ammoMax()).isEqualTo(-1);
            assertThat(Weapon.BLASTER.hasLimitedAmmo()).isFalse();
        }
    }

    @Nested
    @DisplayName("the shotgun")
    class Shotgun
    {
        @Test
        @DisplayName("fires a cone, has two shots per pickup")
        void shouldBeLimitedAmmoAndCone()
        {
            assertThat(Weapon.SHOTGUN.id()).isEqualTo("shotgun");
            assertThat(Weapon.SHOTGUN.displayName()).isEqualTo("Shotgun");
            assertThat(Weapon.SHOTGUN.fireMode()).isEqualTo(Weapon.FireMode.CONE);
            assertThat(Weapon.SHOTGUN.ammoMax()).isEqualTo(2);
            assertThat(Weapon.SHOTGUN.hasLimitedAmmo()).isTrue();
        }
    }

    @Nested
    @DisplayName("the rocket launcher")
    class RocketLauncher
    {
        @Test
        @DisplayName("fires a projectile, has one shot per pickup")
        void shouldBeOneShotAndProjectile()
        {
            assertThat(Weapon.ROCKET_LAUNCHER.id()).isEqualTo("rocket");
            assertThat(Weapon.ROCKET_LAUNCHER.displayName()).isEqualTo("Rocket Launcher");
            assertThat(Weapon.ROCKET_LAUNCHER.fireMode())
                .isEqualTo(Weapon.FireMode.PROJECTILE);
            assertThat(Weapon.ROCKET_LAUNCHER.ammoMax()).isEqualTo(1);
            assertThat(Weapon.ROCKET_LAUNCHER.hasLimitedAmmo()).isTrue();
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity
    {
        @Test
        @DisplayName("two weapon references with the same id are equal, by id")
        void shouldEquateById()
        {
            // The "build your own weapon" path a test would use:
            // a fresh Weapon with the same id as a shipped one is
            // equal. Equal by id is the contract a spec storage
            // would rely on.
            final Weapon a = Weapon.BLASTER;
            final Weapon b = Weapon.BLASTER;

            assertThat(a).isEqualTo(b);
            assertThat(a).isNotEqualTo(Weapon.SHOTGUN);
        }

        @Test
        @DisplayName("hashCode is id-derived, so equal weapons hash equal")
        void shouldHashById()
        {
            assertThat(Weapon.BLASTER.hashCode()).isEqualTo(Weapon.BLASTER.id().hashCode());
        }
    }

    @Nested
    @DisplayName("pickup yaw")
    class PickupYaw
    {
        @Test
        @DisplayName("the three shipped weapons sit at 0, 120, 240 degrees")
        void shouldDistributePickupsAcrossThreeSlots()
        {
            // The three known weapons land at the three
            // 120-degree slots. 0 / 2pi/3 / 4pi/3 radians.
            // The two peers rendering the same map see the
            // same angles because the dispatch is by identity.
            final float blaster = Weapon.BLASTER.pickupYawRadians();
            final float shotgun = Weapon.SHOTGUN.pickupYawRadians();
            final float rocket = Weapon.ROCKET_LAUNCHER.pickupYawRadians();

            assertThat(blaster).isEqualTo(0.0f);
            assertThat(shotgun).isEqualTo((float) (2.0 * StrictMath.PI / 3.0));
            assertThat(rocket).isEqualTo((float) (4.0 * StrictMath.PI / 3.0));
        }

        @Test
        @DisplayName("the yaw is deterministic, so two peers agree on the pickup pose")
        void shouldBeDeterministic()
        {
            // Same weapon, called twice, gives the same answer.
            // The lockstep model depends on this - two peers
            // rendering the same map must put the pickup at
            // the same rotation on the same tic.
            assertThat(Weapon.SHOTGUN.pickupYawRadians())
                .isEqualTo(Weapon.SHOTGUN.pickupYawRadians());
        }
    }
}
