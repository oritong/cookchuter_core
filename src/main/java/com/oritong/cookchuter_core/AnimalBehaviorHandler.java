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
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
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
public final class AnimalBehaviorHandler {
    private static final String FLEE_TICKS_TAG = "CookchuterFleeTicks";
    private static final String FLEE_DIRECTION_X_TAG = "CookchuterFleeDirectionX";
    private static final String FLEE_DIRECTION_Z_TAG = "CookchuterFleeDirectionZ";
    private static final String RECOVERY_DELAY_TICKS_TAG = "CookchuterRecoveryDelayTicks";
    private static final String RECOVERY_TICKS_TAG = "CookchuterRecoveryTicks";
    private static final String HEALTH_PROFILE_TAG = "CookchuterHealthProfile";
    private static final BehaviorProfile CHICKEN = new BehaviorProfile("chicken_6_10", 6, 10, 1.0F, 6 * 20, 15 * 20, false);
    private static final BehaviorProfile SHEEP = new BehaviorProfile("sheep_8_14", 8, 14, 2.0F, 4 * 20, 8 * 20, true);
    private static final int RECOVERY_INTERVAL_TICKS = 20;
    private static final double PLAYER_DETECTION_RANGE = 7.5;
    private static final double PATHFINDING_RANGE = 32.0;
    private static final double APPROACH_SPEED = 0.8;
    private static final double FLEE_SPEED = 1.0;
    private static final float RECOVERY_FRACTION_PER_SECOND = 0.05F;

    private AnimalBehaviorHandler() {
    }

    private record BehaviorProfile(String healthProfile, int minHealth, int maxHealth, float attackDamage,
                                   int fleeTicks, int recoveryDelayTicks, boolean windingEscape) {
    }

    private static BehaviorProfile profile(Animal animal) {
        return animal instanceof Sheep ? SHEEP : CHICKEN;
    }

    private static boolean isSupported(Animal animal) {
        return animal instanceof Chicken || animal instanceof Sheep;
    }

    @SubscribeEvent
    public static void onAnimalJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Animal animal) || !isSupported(animal) || event.getLevel().isClientSide()) {
            return;
        }

        CompoundTag data = animal.getPersistentData();
        BehaviorProfile profile = profile(animal);
        if (!profile.healthProfile().equals(data.getString(HEALTH_PROFILE_TAG))) {
            AttributeInstance maxHealth = animal.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                float healthFraction = event.loadedFromDisk() ? animal.getHealth() / animal.getMaxHealth() : 1.0F;
                maxHealth.setBaseValue(profile.minHealth() + animal.getRandom().nextInt(profile.maxHealth() - profile.minHealth() + 1));
                animal.setHealth(animal.getMaxHealth() * healthFraction);
                data.putString(HEALTH_PROFILE_TAG, profile.healthProfile());
            }
        }

        animal.goalSelector.removeAllGoals(goal -> goal instanceof PanicGoal);
        boolean hasApproachGoal = hasGoal(animal, AnimalApproachGoal.class);
        boolean hasFleeGoal = hasGoal(animal, AnimalFleeGoal.class);
        boolean hasRecoveryGoal = hasGoal(animal, AnimalRecoveryGoal.class);
        if (!hasFleeGoal) {
            animal.goalSelector.addGoal(-1, new AnimalFleeGoal(animal));
        }
        if (!hasApproachGoal) {
            animal.goalSelector.addGoal(2, new AnimalApproachGoal(animal));
        }
        if (!hasRecoveryGoal) {
            animal.goalSelector.addGoal(3, new AnimalRecoveryGoal(animal));
        }
    }

    @SubscribeEvent
    public static void onAnimalDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Animal animal)
                || !isSupported(animal)
                || animal.level().isClientSide()
                || event.getNewDamage() <= 0.0F) {
            return;
        }

        if (event.getSource().getEntity() instanceof Player attacker
                && (attacker.isCreative() || attacker.isSpectator())) {
            return;
        }

        CompoundTag data = animal.getPersistentData();
        data.putInt(FLEE_TICKS_TAG, profile(animal).fleeTicks());
        data.putInt(RECOVERY_DELAY_TICKS_TAG, profile(animal).recoveryDelayTicks());
        data.putInt(RECOVERY_TICKS_TAG, 0);
        Player attacker = event.getSource().getEntity() instanceof Player player ? player : findNearestPlayer(animal);
        setFleeDirection(animal, attacker);
        moveAway(animal);
    }

    @SubscribeEvent
    public static void onAnimalTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Animal animal) || !isSupported(animal) || animal.level().isClientSide()) {
            return;
        }

        CompoundTag data = animal.getPersistentData();
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

        float maxHealth = animal.getMaxHealth();
        if (animal.getHealth() < maxHealth) {
            int recoveryTicks = data.getInt(RECOVERY_TICKS_TAG) + 1;
            if (recoveryTicks >= RECOVERY_INTERVAL_TICKS) {
                animal.heal(maxHealth * RECOVERY_FRACTION_PER_SECOND);
                recoveryTicks = 0;
            }
            data.putInt(RECOVERY_TICKS_TAG, recoveryTicks);
        } else {
            data.remove(RECOVERY_TICKS_TAG);
        }
    }

    private static boolean canApproach(Animal animal) {
        return animal.getPersistentData().getInt(FLEE_TICKS_TAG) <= 0
                && animal.getHealth() >= animal.getMaxHealth();
    }

    private static boolean hasGoal(Animal animal, Class<? extends Goal> goalType) {
        return animal.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrappedGoal -> goalType.isInstance(wrappedGoal.getGoal()));
    }

    private static Player findNearestPlayer(Animal animal) {
        AABB searchArea = animal.getBoundingBox().inflate(PLAYER_DETECTION_RANGE);
        return animal.level().getEntitiesOfClass(Player.class, searchArea,
                        player -> player.isAlive()
                                && !player.isCreative()
                                && !player.isSpectator()
                                && animal.distanceToSqr(player) <= PLAYER_DETECTION_RANGE * PLAYER_DETECTION_RANGE)
                .stream()
                .min(Comparator.comparingDouble(animal::distanceToSqr))
                .orElse(null);
    }

    private static void setFleeDirection(Animal animal, Player attacker) {
        double directionX = attacker == null ? animal.getRandom().nextDouble() - 0.5 : animal.getX() - attacker.getX();
        double directionZ = attacker == null ? animal.getRandom().nextDouble() - 0.5 : animal.getZ() - attacker.getZ();
        if (directionX * directionX + directionZ * directionZ < 1.0E-4) {
            directionX = animal.getRandom().nextDouble() - 0.5;
            directionZ = animal.getRandom().nextDouble() - 0.5;
        }
        double length = Math.sqrt(directionX * directionX + directionZ * directionZ);
        if (length < 1.0E-4) {
            directionX = 1.0;
            directionZ = 0.0;
            length = 1.0;
        }
        CompoundTag data = animal.getPersistentData();
        data.putDouble(FLEE_DIRECTION_X_TAG, directionX / length);
        data.putDouble(FLEE_DIRECTION_Z_TAG, directionZ / length);
    }

    private static void moveAway(Animal animal) {
        moveAway(animal, 0.0);
    }

    private static void moveAway(Animal animal, double turnAngle) {
        CompoundTag data = animal.getPersistentData();
        double directionX = data.getDouble(FLEE_DIRECTION_X_TAG);
        double directionZ = data.getDouble(FLEE_DIRECTION_Z_TAG);
        if (directionX * directionX + directionZ * directionZ < 1.0E-4) {
            setFleeDirection(animal, findNearestPlayer(animal));
            directionX = animal.getPersistentData().getDouble(FLEE_DIRECTION_X_TAG);
            directionZ = animal.getPersistentData().getDouble(FLEE_DIRECTION_Z_TAG);
        }

        double rotatedX = directionX * Math.cos(turnAngle) - directionZ * Math.sin(turnAngle);
        double rotatedZ = directionX * Math.sin(turnAngle) + directionZ * Math.cos(turnAngle);
        double distance = profile(animal).windingEscape() ? 4.0 : 8.0;
        double destinationX = animal.getX() + rotatedX * distance;
        double destinationZ = animal.getZ() + rotatedZ * distance;
        boolean pathStarted = animal.getNavigation().moveTo(destinationX, animal.getY(), destinationZ, FLEE_SPEED);
        if (!pathStarted || animal.getNavigation().isStuck()) {
            animal.getMoveControl().setWantedPosition(destinationX, animal.getY(), destinationZ, FLEE_SPEED);
        }
    }

    private static boolean hasSafeFootingAhead(Animal animal, double targetX, double targetZ) {
        if (!animal.onGround()) {
            return false;
        }

        double directionX = targetX - animal.getX();
        double directionZ = targetZ - animal.getZ();
        double distance = Math.sqrt(directionX * directionX + directionZ * directionZ);
        if (distance < 1.0E-4) {
            return false;
        }
        directionX /= distance;
        directionZ /= distance;

        double lookAhead = Math.min(distance, animal.getBbWidth() + 0.4);
        double sideOffset = animal.getBbWidth() * 0.35;
        // Check the whole short movement, including both sides, so a diagonal cannot cut across a hole.
        for (double forward = 0.2; forward < lookAhead + 0.2; forward += 0.2) {
            double offset = Math.min(forward, lookAhead);
            for (int side = -1; side <= 1; side++) {
                double sampleX = animal.getX() + directionX * offset - directionZ * side * sideOffset;
                double sampleZ = animal.getZ() + directionZ * offset + directionX * side * sideOffset;
                if (!hasSupportingSurface(animal, sampleX, sampleZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasSupportingSurface(Animal animal, double x, double z) {
        double feetY = animal.getY();
        AABB probe = new AABB(x - 0.025, feetY - 1.05, z - 0.025, x + 0.025, feetY + 1.05, z + 0.025);
        // Collision surfaces also support slabs and stairs; liquids and empty space do not.
        for (VoxelShape shape : animal.level().getBlockCollisions(animal, probe)) {
            double top = shape.max(Direction.Axis.Y);
            if (top >= feetY - 1.01 && top <= feetY + 1.01) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeNextPathStep(Animal animal, Path path) {
        if (!animal.onGround()) {
            return true;
        }
        double previousY = animal.getY();
        // Navigation may advance to the following node later in this tick; check that transition too.
        int end = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 2);
        for (int i = path.getNextNodeIndex(); i < end; i++) {
            Vec3 node = path.getEntityPosAtNode(animal, i);
            if (previousY - node.y > 1.01) {
                return false;
            }
            previousY = node.y;
        }
        Vec3 next = path.getNextEntityPos(animal);
        return animal.distanceToSqr(next.x, animal.getY(), next.z) < 0.01
                || hasSafeFootingAhead(animal, next.x, next.z);
    }

    private static void stopChasingMovement(Animal animal) {
        animal.getNavigation().stop();
        animal.getMoveControl().setWantedPosition(animal.getX(), animal.getY(), animal.getZ(), 0.0);
        animal.setSpeed(0.0F);
        animal.setZza(0.0F);
        animal.setXxa(0.0F);
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

    private static final class AnimalApproachGoal extends Goal {
        private final Animal animal;
        private final ChaseNodeEvaluator nodeEvaluator = new ChaseNodeEvaluator();
        private final PathFinder pathFinder = new PathFinder(nodeEvaluator, (int) PATHFINDING_RANGE * 16);
        private Player target;
        private int attackCooldown;
        private int pathCooldown;

        private AnimalApproachGoal(Animal animal) {
            this.animal = animal;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!canApproach(animal)) {
                return false;
            }
            target = findNearestPlayer(animal);
            return target != null;
        }

        @Override
        public boolean canContinueToUse() {
            return canApproach(animal)
                    && target != null
                    && target.isAlive()
                    && !target.isCreative()
                    && !target.isSpectator()
                    && animal.distanceToSqr(target) <= PLAYER_DETECTION_RANGE * PLAYER_DETECTION_RANGE;
        }

        @Override
        public void tick() {
            if (!canContinueToUse()) {
                stopChasingMovement(animal);
                return;
            }

            animal.getLookControl().setLookAt(target, 30.0F, 30.0F);
            boolean inMeleeRange = animal.isWithinMeleeAttackRange(target);
            boolean canSeeTarget = animal.getSensing().hasLineOfSight(target);
            if (inMeleeRange && canSeeTarget) {
                stopChasingMovement(animal);
            } else {
                if (--pathCooldown <= 0 && animal.onGround()) {
                    animal.getNavigation().moveTo(createSafePath(), APPROACH_SPEED);
                    pathCooldown = 10;
                }
                Path path = animal.getNavigation().getPath();
                if (path != null && !path.isDone() && !animal.getNavigation().isStuck()) {
                    if (!isSafeNextPathStep(animal, path)) {
                        stopChasingMovement(animal);
                    }
                } else if (canSeeTarget && hasSafeFootingAhead(animal, target.getX(), target.getZ())) {
                    // Recheck every tick and only issue a short move, rather than a command across the whole gap.
                    Vec3 step = target.position().subtract(animal.position()).multiply(1.0, 0.0, 1.0).normalize().scale(0.4);
                    animal.getNavigation().stop();
                    animal.getMoveControl().setWantedPosition(animal.getX() + step.x, animal.getY(), animal.getZ() + step.z, APPROACH_SPEED);
                } else {
                    stopChasingMovement(animal);
                }
            }

            if (attackCooldown > 0) {
                attackCooldown--;
            }
            if (inMeleeRange && animal.getSensing().hasLineOfSight(target) && attackCooldown == 0) {
                animal.swing(InteractionHand.MAIN_HAND);
                target.hurt(animal.damageSources().mobAttack(animal), profile(animal).attackDamage());
                attackCooldown = 20;
            }
        }

        private Path createSafePath() {
            NodeEvaluator navigationEvaluator = animal.getNavigation().getNodeEvaluator();
            nodeEvaluator.setCanPassDoors(navigationEvaluator.canPassDoors());
            nodeEvaluator.setCanOpenDoors(navigationEvaluator.canOpenDoors());
            nodeEvaluator.setCanFloat(navigationEvaluator.canFloat());
            nodeEvaluator.setCanWalkOverFences(navigationEvaluator.canWalkOverFences());
            BlockPos center = animal.blockPosition();
            int radius = (int) PATHFINDING_RANGE + 8;
            PathNavigationRegion region = new PathNavigationRegion(animal.level(),
                    center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius));
            return pathFinder.findPath(region, animal, Set.of(target.blockPosition()), (float) PATHFINDING_RANGE, 0, 1.0F);
        }

        @Override
        public void stop() {
            target = null;
            pathCooldown = 0;
            stopChasingMovement(animal);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }

    private static final class AnimalFleeGoal extends Goal {
        private final Animal animal;
        private int turnCooldown;
        private double turnAngle;
        private int turnSign;
        private AnimalFleeGoal(Animal animal) {
            this.animal = animal;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return animal.getPersistentData().getInt(FLEE_TICKS_TAG) > 0;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            turnCooldown = 0;
            turnAngle = 0.0;
            turnSign = animal.getRandom().nextBoolean() ? 1 : -1;
            moveAway(animal);
        }

        @Override
        public void tick() {
            if (profile(animal).windingEscape()) {
                if (--turnCooldown <= 0) {
                    // Alternate random bends while preserving an overall direction away from the attacker.
                    turnAngle = turnSign * (0.35 + animal.getRandom().nextDouble() * 0.65);
                    turnSign = -turnSign;
                    turnCooldown = 10 + animal.getRandom().nextInt(7);
                    animal.getNavigation().stop();
                    moveAway(animal, turnAngle);
                } else if (animal.getNavigation().isDone() || animal.getNavigation().isStuck()) {
                    moveAway(animal, turnAngle);
                }
                return;
            }
            if (animal.tickCount % 10 == 0 || animal.getNavigation().isDone() || animal.getNavigation().isStuck()) {
                moveAway(animal);
            }
        }

        @Override
        public void stop() {
            animal.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }

    private static final class AnimalRecoveryGoal extends Goal {
        private final Animal animal;

        private AnimalRecoveryGoal(Animal animal) {
            this.animal = animal;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return animal.getPersistentData().getInt(FLEE_TICKS_TAG) <= 0
                    && animal.getHealth() < animal.getMaxHealth();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            animal.getNavigation().stop();
        }

        @Override
        public void stop() {
            animal.getNavigation().stop();
        }
    }
}
