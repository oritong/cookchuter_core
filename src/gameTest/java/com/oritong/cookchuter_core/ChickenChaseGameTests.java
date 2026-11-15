/* SPDX-License-Identifier: GPL-3.0-only */
package com.oritong.cookchuter_core;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CookchuterCore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChickenChaseGameTests {
    @GameTest(template = "chase_arena", timeoutTicks = 180, batch = "cliff")
    public static void stopsBeforeCliff(GameTestHelper helper) {
        floor(helper);
        for (int x = 1; x <= 6; x++) {
            for (int z = 1; z <= 10; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
        Player target = target(helper, 9.5, 1.0, 5.5);
        Chicken chicken = helper.spawn(EntityType.CHICKEN, new Vec3(3.5, 4.0, 5.5));
        double startX = chicken.getX();
        double platformTop = helper.absoluteVec(new Vec3(0, 4, 0)).y;
        helper.onEachTick(() -> helper.assertTrue(chicken.getY() >= platformTop - 0.1,
                "Chicken walked off a three-block cliff while chasing"));
        helper.runAtTickTime(140, () -> {
            helper.assertTrue(chicken.getX() > startX + 0.3, "Chicken never attempted to approach the player");
            target.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "chase_arena", timeoutTicks = 160, batch = "flat")
    public static void stillAttacksOnFlatGround(GameTestHelper helper) {
        floor(helper);
        Player target = target(helper, 9.3, 1.0, 5.5);
        Chicken chicken = helper.spawn(EntityType.CHICKEN, new Vec3(3.5, 1.0, 5.5));
        helper.runAtTickTime(120, () -> {
            helper.assertTrue(target.getHealth() < target.getMaxHealth() && target.getLastHurtByMob() == chicken,
                    "Chicken stopped short of melee range on flat ground");
            target.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "chase_arena", timeoutTicks = 260, batch = "detour")
    public static void followsSafeDetourAroundHole(GameTestHelper helper) {
        floor(helper);
        for (int x = 1; x <= 16; x++) {
            for (int z = 1; z <= 10; z++) {
                if (x >= 7 && x <= 9 && z <= 7) {
                    continue;
                }
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
        Player target = target(helper, 12.5, 4.0, 5.5);
        Chicken chicken = helper.spawn(EntityType.CHICKEN, new Vec3(3.5, 4.0, 5.5));
        double platformTop = helper.absoluteVec(new Vec3(0, 4, 0)).y;
        helper.onEachTick(() -> helper.assertTrue(chicken.getY() >= platformTop - 0.1,
                "Chicken cut across the hole instead of following the safe route"));
        helper.runAtTickTime(220, () -> {
            helper.assertTrue(target.getHealth() < target.getMaxHealth() && target.getLastHurtByMob() == chicken,
                    "Chicken failed to reach the player using a safe detour");
            target.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "chase_arena", timeoutTicks = 180, batch = "steps")
    public static void stillDescendsSingleBlockSteps(GameTestHelper helper) {
        floor(helper);
        for (int x = 1; x <= 10; x++) {
            int height = x <= 6 ? 3 : (x <= 8 ? 2 : 1);
            for (int z = 1; z <= 10; z++) {
                for (int y = 1; y <= height; y++) {
                    helper.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
        Player target = target(helper, 12.5, 1.0, 5.5);
        Chicken chicken = helper.spawn(EntityType.CHICKEN, new Vec3(3.5, 4.0, 5.5));
        helper.runAtTickTime(150, () -> {
            helper.assertTrue(target.getHealth() < target.getMaxHealth() && target.getLastHurtByMob() == chicken,
                    "Chicken refused a safe staircase");
            target.discard();
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

    private static Player target(GameTestHelper helper, double x, double y, double z) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 position = helper.absoluteVec(new Vec3(x, y, z));
        player.setPos(position.x, position.y, position.z);
        helper.getLevel().addFreshEntity(player);
        return player;
    }
}
