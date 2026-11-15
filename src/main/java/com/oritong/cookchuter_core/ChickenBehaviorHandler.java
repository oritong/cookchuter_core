/* SPDX-License-Identifier: GPL-3.0-only */
package com.oritong.cookchuter_core;

import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = CookchuterCore.MOD_ID)
public final class ChickenBehaviorHandler {
    private static final String FLEE_TICKS_TAG = "CookchuterFleeTicks";
    private static final String FLEE_DIRECTION_X_TAG = "CookchuterFleeDirectionX";
    private static final String FLEE_DIRECTION_Z_TAG = "CookchuterFleeDirectionZ";
    private static final String RECOVERY_DELAY_TICKS_TAG = "CookchuterRecoveryDelayTicks";
    private static final String RECOVERY_TICKS_TAG = "CookchuterRecoveryTicks";
    private static final String HEALTH_RANDOMIZED_TAG = "CookchuterHealthRandomized";
    private static final int MIN_CHICKEN_HEALTH = 12;
    private static final int CHICKEN_HEALTH_VARIANCE = 7;
    private static final int FLEE_DURATION_TICKS = 6 * 20;
    private static final int RECOVERY_DELAY_TICKS = 15 * 20;
    private static final int RECOVERY_INTERVAL_TICKS = 20;
    private static final double PLAYER_RANGE = 32.0;
    private static final double APPROACH_SPEED = 0.8;
    private static final double FLEE_SPEED = 1.0;
    private static final float ATTACK_DAMAGE = 1.0F;
    private static final float RECOVERY_FRACTION_PER_SECOND = 0.05F;

    private ChickenBehaviorHandler() {
    }

    @SubscribeEvent
    public static void onChickenJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken) || event.getLevel().isClientSide()) {
            return;
        }

        CompoundTag data = chicken.getPersistentData();
        if (!data.getBoolean(HEALTH_RANDOMIZED_TAG)) {
            if (!event.loadedFromDisk()) {
                AttributeInstance maxHealth = chicken.getAttribute(Attributes.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(MIN_CHICKEN_HEALTH + chicken.getRandom().nextInt(CHICKEN_HEALTH_VARIANCE));
                    chicken.setHealth(chicken.getMaxHealth());
                }
            }
            data.putBoolean(HEALTH_RANDOMIZED_TAG, true);
        }

        chicken.goalSelector.removeAllGoals(goal -> goal instanceof PanicGoal);
        boolean hasApproachGoal = hasGoal(chicken, ChickenApproachGoal.class);
        boolean hasFleeGoal = hasGoal(chicken, ChickenFleeGoal.class);
        boolean hasRecoveryGoal = hasGoal(chicken, ChickenRecoveryGoal.class);
        if (!hasFleeGoal) {
            chicken.goalSelector.addGoal(-1, new ChickenFleeGoal(chicken));
        }
        if (!hasApproachGoal) {
            chicken.goalSelector.addGoal(2, new ChickenApproachGoal(chicken));
        }
        if (!hasRecoveryGoal) {
            chicken.goalSelector.addGoal(3, new ChickenRecoveryGoal(chicken));
        }
    }

    @SubscribeEvent
    public static void onChickenDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Chicken chicken)
                || chicken.level().isClientSide()
                || event.getNewDamage() <= 0.0F) {
            return;
        }

        if (event.getSource().getEntity() instanceof Player attacker
                && (attacker.isCreative() || attacker.isSpectator())) {
            return;
        }

        CompoundTag data = chicken.getPersistentData();
        data.putInt(FLEE_TICKS_TAG, FLEE_DURATION_TICKS);
        data.putInt(RECOVERY_DELAY_TICKS_TAG, RECOVERY_DELAY_TICKS);
        data.putInt(RECOVERY_TICKS_TAG, 0);
        Player attacker = event.getSource().getEntity() instanceof Player player ? player : findNearestPlayer(chicken);
        setFleeDirection(chicken, attacker);
        moveAway(chicken);
    }

    @SubscribeEvent
    public static void onChickenTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Chicken chicken) || chicken.level().isClientSide()) {
            return;
        }

        CompoundTag data = chicken.getPersistentData();
        int fleeTicks = data.getInt(FLEE_TICKS_TAG);
        if (fleeTicks > 0) {
            data.putInt(FLEE_TICKS_TAG, fleeTicks - 1);
        }

        int recoveryDelayTicks = data.getInt(RECOVERY_DELAY_TICKS_TAG);
        if (recoveryDelayTicks > 0) {
            data.putInt(RECOVERY_DELAY_TICKS_TAG, recoveryDelayTicks - 1);
            if (recoveryDelayTicks > 1) {
                return;
            }
            data.putInt(RECOVERY_TICKS_TAG, RECOVERY_INTERVAL_TICKS - 1);
        }

        float maxHealth = chicken.getMaxHealth();
        if (chicken.getHealth() < maxHealth) {
            int recoveryTicks = data.getInt(RECOVERY_TICKS_TAG) + 1;
            if (recoveryTicks >= RECOVERY_INTERVAL_TICKS) {
                chicken.heal(maxHealth * RECOVERY_FRACTION_PER_SECOND);
                recoveryTicks = 0;
            }
            data.putInt(RECOVERY_TICKS_TAG, recoveryTicks);
        } else {
            data.remove(RECOVERY_TICKS_TAG);
        }
    }

    private static boolean canApproach(Chicken chicken) {
        return chicken.getPersistentData().getInt(FLEE_TICKS_TAG) <= 0
                && chicken.getHealth() >= chicken.getMaxHealth();
    }

    private static boolean hasGoal(Chicken chicken, Class<? extends Goal> goalType) {
        return chicken.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrappedGoal -> goalType.isInstance(wrappedGoal.getGoal()));
    }

    private static Player findNearestPlayer(Chicken chicken) {
        AABB searchArea = chicken.getBoundingBox().inflate(PLAYER_RANGE);
        return chicken.level().getEntitiesOfClass(Player.class, searchArea,
                        player -> player.isAlive() && !player.isCreative() && !player.isSpectator())
                .stream()
                .min(Comparator.comparingDouble(chicken::distanceToSqr))
                .orElse(null);
    }

    private static void setFleeDirection(Chicken chicken, Player attacker) {
        double directionX = attacker == null ? chicken.getRandom().nextDouble() - 0.5 : chicken.getX() - attacker.getX();
        double directionZ = attacker == null ? chicken.getRandom().nextDouble() - 0.5 : chicken.getZ() - attacker.getZ();
        if (directionX * directionX + directionZ * directionZ < 1.0E-4) {
            directionX = chicken.getRandom().nextDouble() - 0.5;
            directionZ = chicken.getRandom().nextDouble() - 0.5;
        }
        double length = Math.sqrt(directionX * directionX + directionZ * directionZ);
        CompoundTag data = chicken.getPersistentData();
        data.putDouble(FLEE_DIRECTION_X_TAG, directionX / length);
        data.putDouble(FLEE_DIRECTION_Z_TAG, directionZ / length);
    }

    private static void moveAway(Chicken chicken) {
        CompoundTag data = chicken.getPersistentData();
        double directionX = data.getDouble(FLEE_DIRECTION_X_TAG);
        double directionZ = data.getDouble(FLEE_DIRECTION_Z_TAG);
        if (directionX * directionX + directionZ * directionZ < 1.0E-4) {
            setFleeDirection(chicken, findNearestPlayer(chicken));
            directionX = chicken.getPersistentData().getDouble(FLEE_DIRECTION_X_TAG);
            directionZ = chicken.getPersistentData().getDouble(FLEE_DIRECTION_Z_TAG);
        }

        double destinationX = chicken.getX() + directionX * 8.0;
        double destinationZ = chicken.getZ() + directionZ * 8.0;
        boolean pathStarted = chicken.getNavigation().moveTo(destinationX, chicken.getY(), destinationZ, FLEE_SPEED);
        if (!pathStarted || chicken.getNavigation().isStuck()) {
            chicken.getMoveControl().setWantedPosition(destinationX, chicken.getY(), destinationZ, FLEE_SPEED);
        }
    }

    private static final class ChickenApproachGoal extends Goal {
        private final Chicken chicken;
        private Player target;
        private int attackCooldown;

        private ChickenApproachGoal(Chicken chicken) {
            this.chicken = chicken;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!canApproach(chicken)) {
                return false;
            }
            target = findNearestPlayer(chicken);
            return target != null;
        }

        @Override
        public boolean canContinueToUse() {
            return canApproach(chicken)
                    && target != null
                    && target.isAlive()
                    && !target.isCreative()
                    && !target.isSpectator()
                    && chicken.distanceToSqr(target) <= PLAYER_RANGE * PLAYER_RANGE;
        }

        @Override
        public void tick() {
            if (target == null) {
                return;
            }

            chicken.getLookControl().setLookAt(target, 30.0F, 30.0F);
            boolean inMeleeRange = chicken.isWithinMeleeAttackRange(target);
            if (inMeleeRange) {
                chicken.getNavigation().stop();
            } else if (chicken.tickCount % 5 == 0 || chicken.getNavigation().isDone() || chicken.getNavigation().isStuck()) {
                boolean pathStarted = chicken.getNavigation().moveTo(target, APPROACH_SPEED);
                if ((!pathStarted || chicken.getNavigation().isStuck()) && chicken.getSensing().hasLineOfSight(target)) {
                    chicken.getMoveControl().setWantedPosition(target.getX(), chicken.getY(), target.getZ(), APPROACH_SPEED);
                }
            }

            if (attackCooldown > 0) {
                attackCooldown--;
            }
            if (inMeleeRange && chicken.getSensing().hasLineOfSight(target) && attackCooldown == 0) {
                chicken.swing(InteractionHand.MAIN_HAND);
                target.hurt(chicken.damageSources().mobAttack(chicken), ATTACK_DAMAGE);
                attackCooldown = 20;
            }
        }

        @Override
        public void stop() {
            target = null;
            chicken.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }

    private static final class ChickenFleeGoal extends Goal {
        private final Chicken chicken;
        private ChickenFleeGoal(Chicken chicken) {
            this.chicken = chicken;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return chicken.getPersistentData().getInt(FLEE_TICKS_TAG) > 0;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            moveAway(chicken);
        }

        @Override
        public void tick() {
            if (chicken.tickCount % 10 == 0 || chicken.getNavigation().isDone() || chicken.getNavigation().isStuck()) {
                moveAway(chicken);
            }
        }

        @Override
        public void stop() {
            chicken.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }

    private static final class ChickenRecoveryGoal extends Goal {
        private final Chicken chicken;

        private ChickenRecoveryGoal(Chicken chicken) {
            this.chicken = chicken;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return chicken.getPersistentData().getInt(FLEE_TICKS_TAG) <= 0
                    && chicken.getHealth() < chicken.getMaxHealth();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            chicken.getNavigation().stop();
        }

        @Override
        public void stop() {
            chicken.getNavigation().stop();
        }
    }
}
