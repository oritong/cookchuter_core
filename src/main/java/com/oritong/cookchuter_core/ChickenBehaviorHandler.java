/* SPDX-License-Identifier: GPL-3.0-only */
package com.oritong.cookchuter_core;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
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
    private static final double PLAYER_DETECTION_RANGE = 7.5;
    private static final double PATHFINDING_RANGE = 32.0;
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
        AABB searchArea = chicken.getBoundingBox().inflate(PLAYER_DETECTION_RANGE);
        return chicken.level().getEntitiesOfClass(Player.class, searchArea,
                        player -> player.isAlive()
                                && !player.isCreative()
                                && !player.isSpectator()
                                && chicken.distanceToSqr(player) <= PLAYER_DETECTION_RANGE * PLAYER_DETECTION_RANGE)
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

    private static boolean hasSafeFootingAhead(Chicken chicken, double targetX, double targetZ) {
        if (!chicken.onGround()) {
            return false;
        }

        double directionX = targetX - chicken.getX();
        double directionZ = targetZ - chicken.getZ();
        double distance = Math.sqrt(directionX * directionX + directionZ * directionZ);
        if (distance < 1.0E-4) {
            return false;
        }
        directionX /= distance;
        directionZ /= distance;

        double lookAhead = Math.min(distance, chicken.getBbWidth() + 0.4);
        double sideOffset = chicken.getBbWidth() * 0.35;
        // Check the whole short movement, including both sides, so a diagonal cannot cut across a hole.
        for (double forward = 0.2; forward < lookAhead + 0.2; forward += 0.2) {
            double offset = Math.min(forward, lookAhead);
            for (int side = -1; side <= 1; side++) {
                double sampleX = chicken.getX() + directionX * offset - directionZ * side * sideOffset;
                double sampleZ = chicken.getZ() + directionZ * offset + directionX * side * sideOffset;
                if (!hasSupportingSurface(chicken, sampleX, sampleZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasSupportingSurface(Chicken chicken, double x, double z) {
        double feetY = chicken.getY();
        AABB probe = new AABB(x - 0.025, feetY - 1.05, z - 0.025, x + 0.025, feetY + 1.05, z + 0.025);
        // Collision surfaces also support slabs and stairs; liquids and empty space do not.
        for (VoxelShape shape : chicken.level().getBlockCollisions(chicken, probe)) {
            double top = shape.max(Direction.Axis.Y);
            if (top >= feetY - 1.01 && top <= feetY + 1.01) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeNextPathStep(Chicken chicken, Path path) {
        if (!chicken.onGround()) {
            return true;
        }
        double previousY = chicken.getY();
        // Navigation may advance to the following node later in this tick; check that transition too.
        int end = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 2);
        for (int i = path.getNextNodeIndex(); i < end; i++) {
            Vec3 node = path.getEntityPosAtNode(chicken, i);
            if (previousY - node.y > 1.01) {
                return false;
            }
            previousY = node.y;
        }
        Vec3 next = path.getNextEntityPos(chicken);
        return chicken.distanceToSqr(next.x, chicken.getY(), next.z) < 0.01
                || hasSafeFootingAhead(chicken, next.x, next.z);
    }

    private static void stopChasingMovement(Chicken chicken) {
        chicken.getNavigation().stop();
        chicken.getMoveControl().setWantedPosition(chicken.getX(), chicken.getY(), chicken.getZ(), 0.0);
        chicken.setSpeed(0.0F);
        chicken.setZza(0.0F);
        chicken.setXxa(0.0F);
    }

    private static final class ChaseNodeEvaluator extends WalkNodeEvaluator {
        private final boolean[] safeNeighbors = new boolean[32];

        @Override
        public int getNeighbors(Node[] output, Node origin) {
            int count = super.getNeighbors(output, origin);
            // Evaluate diagonals before compacting the array: both adjacent sides must be safe.
            for (int i = 0; i < count; i++) {
                Node next = output[i];
                safeNeighbors[i] = origin.y - next.y <= 1;
                if (next.x != origin.x && next.z != origin.z) {
                    safeNeighbors[i] &= hasSafeSide(output, count, next.x, origin.z, origin.y)
                            && hasSafeSide(output, count, origin.x, next.z, origin.y);
                }
            }
            int safeCount = 0;
            for (int i = 0; i < count; i++) {
                // Exclude cliffs during the search so a safe detour can still be found.
                if (safeNeighbors[i]) {
                    output[safeCount++] = output[i];
                }
            }
            return safeCount;
        }

        private boolean hasSafeSide(Node[] neighbors, int count, int x, int z, int fromY) {
            for (int i = 0; i < count; i++) {
                Node side = neighbors[i];
                if (side.x == x && side.z == z && fromY - side.y <= 1) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ChickenApproachGoal extends Goal {
        private final Chicken chicken;
        private final ChaseNodeEvaluator nodeEvaluator = new ChaseNodeEvaluator();
        private final PathFinder pathFinder = new PathFinder(nodeEvaluator, (int) PATHFINDING_RANGE * 16);
        private Player target;
        private int attackCooldown;
        private int pathCooldown;

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
                    && chicken.distanceToSqr(target) <= PLAYER_DETECTION_RANGE * PLAYER_DETECTION_RANGE;
        }

        @Override
        public void tick() {
            if (!canContinueToUse()) {
                stopChasingMovement(chicken);
                return;
            }

            chicken.getLookControl().setLookAt(target, 30.0F, 30.0F);
            boolean inMeleeRange = chicken.isWithinMeleeAttackRange(target);
            boolean canSeeTarget = chicken.getSensing().hasLineOfSight(target);
            if (inMeleeRange && canSeeTarget) {
                stopChasingMovement(chicken);
            } else {
                if (--pathCooldown <= 0 && chicken.onGround()) {
                    chicken.getNavigation().moveTo(createSafePath(), APPROACH_SPEED);
                    pathCooldown = 10;
                }
                Path path = chicken.getNavigation().getPath();
                if (path != null && !path.isDone() && !chicken.getNavigation().isStuck()) {
                    if (!isSafeNextPathStep(chicken, path)) {
                        stopChasingMovement(chicken);
                    }
                } else if (canSeeTarget && hasSafeFootingAhead(chicken, target.getX(), target.getZ())) {
                    // Recheck every tick and only issue a short move, rather than a command across the whole gap.
                    Vec3 step = target.position().subtract(chicken.position()).multiply(1.0, 0.0, 1.0).normalize().scale(0.4);
                    chicken.getNavigation().stop();
                    chicken.getMoveControl().setWantedPosition(chicken.getX() + step.x, chicken.getY(), chicken.getZ() + step.z, APPROACH_SPEED);
                } else {
                    stopChasingMovement(chicken);
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

        private Path createSafePath() {
            NodeEvaluator navigationEvaluator = chicken.getNavigation().getNodeEvaluator();
            nodeEvaluator.setCanPassDoors(navigationEvaluator.canPassDoors());
            nodeEvaluator.setCanOpenDoors(navigationEvaluator.canOpenDoors());
            nodeEvaluator.setCanFloat(navigationEvaluator.canFloat());
            nodeEvaluator.setCanWalkOverFences(navigationEvaluator.canWalkOverFences());
            BlockPos center = chicken.blockPosition();
            int radius = (int) PATHFINDING_RANGE + 8;
            PathNavigationRegion region = new PathNavigationRegion(chicken.level(),
                    center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius));
            return pathFinder.findPath(region, chicken, Set.of(target.blockPosition()), (float) PATHFINDING_RANGE, 0, 1.0F);
        }

        @Override
        public void stop() {
            target = null;
            pathCooldown = 0;
            stopChasingMovement(chicken);
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
