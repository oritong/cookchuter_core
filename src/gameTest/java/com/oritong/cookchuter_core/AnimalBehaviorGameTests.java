/* SPDX-License-Identifier: GPL-3.0-only */
package com.oritong.cookchuter_core;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CookchuterCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AnimalBehaviorGameTests {
    @GameTest(template = "chase_arena", batch = "animal_health")
    public static void randomizesHealthAndMigratesOldChickens(GameTestHelper helper) {
        floor(helper);
        for (int i = 0; i < 16; i++) {
            Chicken chicken = helper.spawn(EntityType.CHICKEN, new Vec3(3.5, 1.0, 5.5));
            Sheep sheep = helper.spawn(EntityType.SHEEP, new Vec3(8.5, 1.0, 5.5));
            helper.assertTrue(chicken.getMaxHealth() >= 6 && chicken.getMaxHealth() <= 10,
                    "New chicken health is outside 6-10");
            helper.assertTrue(sheep.getMaxHealth() >= 8 && sheep.getMaxHealth() <= 14,
                    "New sheep health is outside 8-14");
            chicken.discard();
            sheep.discard();
        }

        Chicken oldChicken = helper.spawn(EntityType.CHICKEN, new Vec3(3.5, 1.0, 5.5));
        oldChicken.getAttribute(Attributes.MAX_HEALTH).setBaseValue(16);
        oldChicken.setHealth(8);
        oldChicken.getPersistentData().remove("CookchuterHealthProfile");
        oldChicken.getPersistentData().putBoolean("CookchuterHealthRandomized", true);
        AnimalBehaviorHandler.onAnimalJoinLevel(new EntityJoinLevelEvent(oldChicken, helper.getLevel(), true));
        float updatedMax = oldChicken.getMaxHealth();
        helper.assertTrue(updatedMax >= 6 && updatedMax <= 10 && oldChicken.getHealth() == updatedMax * 0.5F,
                "Existing chicken health was not migrated while preserving its injured state");
        AnimalBehaviorHandler.onAnimalJoinLevel(new EntityJoinLevelEvent(oldChicken, helper.getLevel(), true));
        helper.assertTrue(oldChicken.getMaxHealth() == updatedMax && oldChicken.getHealth() == updatedMax * 0.5F,
                "Reloading rerandomized the chicken health");
        helper.succeed();
    }

    @GameTest(template = "chase_arena", timeoutTicks = 40, batch = "sheep_damage")
    public static void sheepDealsTwoMeleeDamage(GameTestHelper helper) {
        floor(helper);
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Player player = target(helper, GameType.SURVIVAL, 5.5, 1.0, 5.5);
        player.getFoodData().setFoodLevel(17);
        Sheep sheep = helper.spawn(EntityType.SHEEP, new Vec3(4.2, 1.0, 5.5));
        helper.runAtTickTime(15, () -> {
            helper.assertTrue(player.getHealth() == player.getMaxHealth() - 2 && player.getLastHurtByMob() == sheep,
                    "Sheep melee hit did not deal exactly two damage: health=" + player.getHealth()
                            + ", attacker=" + player.getLastHurtByMob() + ", sheep=" + sheep.position()
                            + ", player=" + player.position());
            player.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "chase_arena", timeoutTicks = 300, batch = "sheep_recovery")
    public static void sheepFleesFourSecondsAndRecoversAfterEight(GameTestHelper helper) {
        floor(helper);
        Sheep sheep = helper.spawn(EntityType.SHEEP, new Vec3(5.5, 1.0, 5.5));
        sheep.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10);
        sheep.setHealth(10);
        sheep.setNoAi(true);
        helper.runAtTickTime(1, () -> sheep.hurt(sheep.damageSources().generic(), 2));
        helper.runAtTickTime(78, () -> helper.assertTrue(sheep.getPersistentData().getInt("CookchuterFleeTicks") > 0,
                "Sheep stopped fleeing before four seconds"));
        helper.runAtTickTime(84, () -> helper.assertTrue(sheep.getPersistentData().getInt("CookchuterFleeTicks") == 0,
                "Sheep kept fleeing after four seconds"));
        helper.runAtTickTime(158, () -> helper.assertTrue(sheep.getHealth() == 8,
                "Sheep started healing before eight seconds"));
        helper.runAtTickTime(164, () -> helper.assertTrue(sheep.getHealth() == 8.5F,
                "Sheep did not recover 5% of max health after eight seconds"));
        helper.runAtTickTime(184, () -> helper.assertTrue(sheep.getHealth() == 9,
                "Sheep did not continue healing at 5% per second"));
        helper.runAtTickTime(225, () -> {
            helper.assertTrue(sheep.getHealth() == 10, "Sheep did not fully recover");
            sheep.setNoAi(false);
        });
        Player player = target(helper, GameType.SURVIVAL, 7.5, 1.0, 5.5);
        helper.runAtTickTime(280, () -> {
            helper.assertTrue(player.getLastHurtByMob() == sheep, "Sheep did not resume attacking after recovering");
            player.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "chase_arena", timeoutTicks = 100, batch = "sheep_winding_escape")
    public static void sheepEscapesWithBends(GameTestHelper helper) {
        floor(helper);
        Player player = target(helper, GameType.SURVIVAL, 1.5, 1.0, 5.5);
        Sheep sheep = helper.spawn(EntityType.SHEEP, new Vec3(4.5, 1.0, 5.5));
        double startX = sheep.getX();
        final double[] previous = {sheep.getX(), sheep.getZ()};
        final int[] turns = {0};
        final double[] lastLateralDirection = {0};
        helper.runAtTickTime(1, () -> sheep.hurt(sheep.damageSources().playerAttack(player), 1));
        helper.onEachTick(() -> {
            double dx = sheep.getX() - previous[0];
            double dz = sheep.getZ() - previous[1];
            if (dx > 0.01 && Math.abs(dz) > 0.015) {
                double direction = Math.signum(dz);
                if (lastLateralDirection[0] != 0 && direction != lastLateralDirection[0]) {
                    turns[0]++;
                }
                lastLateralDirection[0] = direction;
            }
            previous[0] = sheep.getX();
            previous[1] = sheep.getZ();
        });
        helper.runAtTickTime(78, () -> {
            helper.assertTrue(sheep.getX() > startX + 3 && turns[0] >= 2,
                    "Sheep failed to move away while making multiple bends");
            player.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "chase_arena", timeoutTicks = 50, batch = "sheep_player_modes")
    public static void sheepIgnoresCreativeAndSpectator(GameTestHelper helper) {
        floor(helper);
        Player creative = target(helper, GameType.CREATIVE, 5.5, 1.0, 5.5);
        Player spectator = target(helper, GameType.SPECTATOR, 6.5, 1.0, 5.5);
        Sheep sheep = helper.spawn(EntityType.SHEEP, new Vec3(3.5, 1.0, 5.5));
        sheep.goalSelector.removeAllGoals(goal -> goal instanceof RandomStrollGoal);
        double startX = sheep.getX();
        helper.runAtTickTime(25, () -> {
            helper.assertTrue(Math.abs(sheep.getX() - startX) < 0.5 && creative.getLastHurtByMob() == null
                            && spectator.getLastHurtByMob() == null,
                    "Sheep targeted a creative or spectator player");
            sheep.hurt(sheep.damageSources().playerAttack(creative), 1);
            helper.assertTrue(sheep.getPersistentData().getInt("CookchuterFleeTicks") == 0,
                    "Creative player damage triggered fleeing");
            creative.discard();
            spectator.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "chase_arena", timeoutTicks = 160, batch = "sheep_cliff")
    public static void sheepStopsBeforeCliff(GameTestHelper helper) {
        floor(helper);
        for (int x = 1; x <= 6; x++) {
            for (int z = 1; z <= 10; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
        Player player = target(helper, GameType.SURVIVAL, 9.5, 1.0, 5.5);
        Sheep sheep = helper.spawn(EntityType.SHEEP, new Vec3(3.5, 4.0, 5.5));
        double startX = sheep.getX();
        double platformTop = helper.absoluteVec(new Vec3(0, 4, 0)).y;
        helper.onEachTick(() -> helper.assertTrue(sheep.getY() >= platformTop - 0.1, "Sheep chased off a cliff"));
        helper.runAtTickTime(140, () -> {
            helper.assertTrue(sheep.getX() > startX + 0.3, "Sheep never started chasing");
            player.discard();
            helper.succeed();
        });
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    private static Player target(GameTestHelper helper, GameType mode, double x, double y, double z) {
        Player player = helper.makeMockPlayer(mode);
        Vec3 position = helper.absoluteVec(new Vec3(x, y, z));
        player.setPos(position.x, position.y, position.z);
        helper.getLevel().addFreshEntity(player);
        return player;
    }
}
