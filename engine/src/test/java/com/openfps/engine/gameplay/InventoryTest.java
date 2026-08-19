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
 * 2026-08: the pickup system. Pins the inventory's seed
 * state (blaster, infinite ammo, current), the pickup
 * effect (add ammo, mark has), the consume effect (spend
 * one round), and the setCurrent switch.
 */
final class InventoryTest
{
    @Nested
    @DisplayName("a fresh inventory")
    class Fresh
    {
        @Test
        @DisplayName("holds the blaster at infinite ammo, and the blaster is current")
        void shouldSeedWithBlaster()
        {
            final Inventory inv = new Inventory();

            assertThat(inv.has(Weapon.BLASTER)).isTrue();
            assertThat(inv.ammo(Weapon.BLASTER)).isEqualTo(-1);
            assertThat(inv.current()).isEqualTo(Weapon.BLASTER);
        }

        @Test
        @DisplayName("does not hold the shotgun or rocket until a pickup adds them")
        void shouldNotHoldLimitedWeapons()
        {
            final Inventory inv = new Inventory();

            assertThat(inv.has(Weapon.SHOTGUN)).isFalse();
            assertThat(inv.ammo(Weapon.SHOTGUN)).isZero();
            assertThat(inv.has(Weapon.ROCKET_LAUNCHER)).isFalse();
            assertThat(inv.ammo(Weapon.ROCKET_LAUNCHER)).isZero();
        }
    }

    @Nested
    @DisplayName("after a pickup")
    class AfterPickup
    {
        @Test
        @DisplayName("a shotgun pickup adds two rounds and marks the weapon as held")
        void shouldAddShotgunAmmo()
        {
            final Inventory inv = new Inventory();

            inv.add(Weapon.SHOTGUN);

            assertThat(inv.has(Weapon.SHOTGUN)).isTrue();
            assertThat(inv.ammo(Weapon.SHOTGUN)).isEqualTo(2);
        }

        @Test
        @DisplayName("a second shotgun pickup adds two more, for four total")
        void shouldStackPickups()
        {
            final Inventory inv = new Inventory();

            inv.add(Weapon.SHOTGUN);

            inv.add(Weapon.SHOTGUN);

            assertThat(inv.ammo(Weapon.SHOTGUN)).isEqualTo(4);
        }

        @Test
        @DisplayName("a rocket pickup adds one round and does not change the current weapon")
        void shouldAddRocketAndKeepCurrent()
        {
            final Inventory inv = new Inventory();

            inv.add(Weapon.ROCKET_LAUNCHER);

            assertThat(inv.has(Weapon.ROCKET_LAUNCHER)).isTrue();
            assertThat(inv.ammo(Weapon.ROCKET_LAUNCHER)).isEqualTo(1);
            assertThat(inv.current())
                .as("the current weapon is not changed by an add")
                .isEqualTo(Weapon.BLASTER);
        }
    }

    @Nested
    @DisplayName("after firing")
    class AfterFire
    {
        @Test
        @DisplayName("consume on a blaster is a no-op, infinite ammo holds")
        void shouldNotDrainBlaster()
        {
            final Inventory inv = new Inventory();

            inv.consume(Weapon.BLASTER);

            assertThat(inv.ammo(Weapon.BLASTER))
                .as("the blaster's ammo is -1 (infinite) and stays that way")
                .isEqualTo(-1);
        }

        @Test
        @DisplayName("consume on a shotgun drops the ammo count by one")
        void shouldDropShotgunAmmo()
        {
            final Inventory inv = new Inventory();

            inv.add(Weapon.SHOTGUN);

            inv.consume(Weapon.SHOTGUN);

            assertThat(inv.ammo(Weapon.SHOTGUN)).isEqualTo(1);
        }

        @Test
        @DisplayName("consume on a weapon not in the inventory is a no-op")
        void shouldBeNoOpOnAbsent()
        {
            final Inventory inv = new Inventory();

            inv.consume(Weapon.ROCKET_LAUNCHER);

            assertThat(inv.ammo(Weapon.ROCKET_LAUNCHER))
                .as("an absent weapon's ammo is 0, and consume does not push it negative")
                .isZero();
        }
    }

    @Nested
    @DisplayName("setCurrent")
    class SwitchWeapon
    {
        @Test
        @DisplayName("a successful switch moves the held-weapon pointer")
        void shouldSwitchToHeldWeapon()
        {
            final Inventory inv = new Inventory();

            inv.add(Weapon.SHOTGUN);

            final boolean switched = inv.setCurrent(Weapon.SHOTGUN);

            assertThat(switched).isTrue();
            assertThat(inv.current()).isEqualTo(Weapon.SHOTGUN);
        }

        @Test
        @DisplayName("a switch to a weapon the player does not have is a no-op, not an exception")
        void shouldRejectAbsentWeapon()
        {
            final Inventory inv = new Inventory();

            final boolean switched = inv.setCurrent(Weapon.ROCKET_LAUNCHER);

            assertThat(switched).isFalse();
            assertThat(inv.current())
                .as("a refused switch leaves the held weapon unchanged")
                .isEqualTo(Weapon.BLASTER);
        }
    }
}
