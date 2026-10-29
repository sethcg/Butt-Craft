package com.github.sethcg.buttcraft;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * SERVER-SIDE STENCH AREA FOR EACH FART.
 *
 * <p>THE VISIBLE GAS IS SIMULATED PER CLIENT, SO THE SERVER CAN'T SEE IT. INSTEAD EACH
 * FART GETS ONE SPHERE THAT ROUGHLY FOLLOWS THE CLOUD: IT STARTS SMALL BEHIND THE PLAYER,
 * DRIFTS BACKWARD WITH THE JET, GROWS AS THE GAS SPREADS OUT AND IS CARRIED BY THE SAME
 * {@link FartBreeze} AS THE CLIENTS' GAS.
 */
public final class FartClouds {

    // ONE DOSE = STENCH I PLUS ONE HIT OF GAS DAMAGE (20 TICKS = 1 SECOND). THE GAS DAMAGE
    // IS CREDITED TO THE FARTER AND CAN KILL; THE STENCH KEEPS HURTING AFTER LEAVING THE CLOUD.
    private static final int DOSE_INTERVAL_TICKS = 20;
    private static final int STENCH_AMPLIFIER = 0;

    // HOW LONG EACH DOSE OF STENCH LASTS BY DEFAULT
    public static final int STENCH_DURATION_TICKS = 180;

    // GAS DAMAGE PER DOSE (2.0 = 1 HEART)
    private static final float GAS_DAMAGE = 1.0F;

    // THE FARTER IS IMMUNE TO THEIR OWN GAS FOR THIS LONG, SO THEY CAN WALK AWAY FROM IT
    private static final int OWNER_GRACE_TICKS = 40;

    // HOW LONG A CLOUD STAYS POISONOUS BY DEFAULT. THE GAS PUFFS LIVE 150-240 TICKS AND
    // ARE MOSTLY FADED OUT FOR THE LAST PART OF THAT.
    public static final int CLOUD_LIFETIME_TICKS = 180;

    // SHAPE: KEEP ROUGHLY IN SYNC WITH THE CLIENT'S FartGasSimulation.
    private static final double ORIGIN_HEIGHT = 0.7;
    private static final double ORIGIN_BEHIND = 0.36;
    private static final double INITIAL_RADIUS = 0.28;
    private static final double DIFFUSION = 0.0065;
    private static final double JET_DISTANCE = 1.4;
    private static final double JET_SPREAD = 0.7;
    private static final double JET_TICKS = 8.0;

    // A BURST (E.G. A SPLASH POTION) HAS NO JET, IT SPREADS OUT EVENLY AS FAR AS THE JET GOES
    private static final double BURST_SPREAD = JET_DISTANCE + JET_SPREAD;

    // HOW MUCH OF ITS BREEZE SPEED THE CLOUD KEEPS EACH TICK, LIKE THE CLIENT PUFFS' AIR DRAG
    // AT BREEZE SPEEDS. THE CLOUD SETTLES AT ABOUT 0.014 BLOCKS / TICK IN THE OPEN.
    private static final double BREEZE_DRAG = 0.92;

    // KEEP THE CENTER THIS FAR OFF A WALL THE JET RUNS INTO
    private static final double WALL_MARGIN = 0.1;

    // EACH CLOUD SEARCHES FOR ENTITIES EVERY TICK, SO THIS MANY AT ONCE (E.G. A DISPENSER FARM
    // THROWING SPLASH FARTS) IS WORTH POINTING OUT WHEN SOMEONE HUNTS FOR LAG.
    private static final int MANY_CLOUDS = 256;

    private static final Logger LOGGER = ButtCraft.logger("clouds");

    private static final List<Cloud> CLOUDS = new ArrayList<>();

    // WARN ONCE EACH TIME THE COUNT CLIMBS PAST MANY_CLOUDS
    private static boolean warnedManyClouds;

    // GAME TIME WHEN EACH ENTITY CAN BE DOSED AGAIN (SHARED BY ALL CLOUDS, SO THEY DON'T STACK).
    // NOT SAVED: IT GOES AWAY WITH THE ENTITY.
    private static final AttachmentType<Long> NEXT_DOSE = AttachmentRegistry.create(ButtCraft.id("next_fart_dose"));

    private FartClouds() {
    }

    public static void initialize() {
        ServerTickEvents.END_LEVEL_TICK.register(FartClouds::tick);

        // DROP ALL CLOUDS WHEN THE SERVER STOPS (E.G. LEAVING A SINGLEPLAYER WORLD)
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            CLOUDS.clear();
            warnedManyClouds = false;
        });
    }

    public static void spawn(ServerLevel level, ServerPlayer owner) {
        double yaw = Math.toRadians(owner.getYRot());
        Vec3 backward = new Vec3(Math.sin(yaw), 0.0, -Math.cos(yaw));
        Vec3 origin = owner.position().add(0.0, ORIGIN_HEIGHT, 0.0);

        // THE GAS PILES UP AGAINST A WALL BEHIND THE PLAYER INSTEAD OF PASSING THROUGH IT
        double maxOffset = ORIGIN_BEHIND + JET_DISTANCE;
        BlockHitResult wall = clip(level, origin, origin.add(backward.scale(maxOffset)));
        if (wall.getType() != HitResult.Type.MISS) {
            maxOffset = Math.max(0.0, wall.getLocation().distanceTo(origin) - WALL_MARGIN);
        }

        add(new Cloud(
            level.dimension(),
            owner.getUUID(),
            OWNER_GRACE_TICKS,
            CLOUD_LIFETIME_TICKS,
            STENCH_DURATION_TICKS,
            origin,
            backward,
            maxOffset,
            JET_SPREAD
        ));
    }

    /**
     * SPAWNS A CLOUD THAT SPREADS OUT EVENLY AROUND A POINT INSTEAD OF BEHIND A PLAYER.
     * THE OWNER (IF ANY) GETS THE CREDIT BUT ISN'T IMMUNE TO IT. stenchTicks IS HOW LONG
     * EACH DOSE OF STENCH LASTS.
     */
    public static void spawnBurst(
        ServerLevel level,
        Vec3 center,
        @Nullable Entity owner,
        int lifetimeTicks,
        int stenchTicks
    ) {
        add(new Cloud(
            level.dimension(),
            owner == null ? null : owner.getUUID(),
            0,
            lifetimeTicks,
            stenchTicks,
            center,
            Vec3.ZERO,
            0.0,
            BURST_SPREAD
        ));
    }

    private static void add(Cloud cloud) {
        CLOUDS.add(cloud);
        if (CLOUDS.size() <= MANY_CLOUDS) {
            warnedManyClouds = false;
        } else if (!warnedManyClouds) {
            warnedManyClouds = true;
            LOGGER.warn("{} ACTIVE FART CLOUDS, POSSIBLE LAG SOURCE.", CLOUDS.size());
        }
    }

    /** TRUE IF ANY POISONOUS CLOUD IN THE LEVEL TOUCHES THE BOX. */
    public static boolean isTouching(ServerLevel level, AABB box) {
        for (Cloud cloud : CLOUDS) {
            if (cloud.dimension != level.dimension() || cloud.age >= cloud.lifetimeTicks) {
                continue;
            }

            double radius = cloud.radius();
            if (box.distanceToSqr(cloud.center()) <= radius * radius) {
                return true;
            }
        }

        return false;
    }

    private static void tick(ServerLevel level) {
        if (CLOUDS.isEmpty()) {
            return;
        }

        long currentTick = level.getGameTime();
        Holder<DamageType> gasDamage = FartGasDamageSource.type(level);

        for (int cloudIndex = CLOUDS.size() - 1; cloudIndex >= 0; cloudIndex--) {
            Cloud cloud = CLOUDS.get(cloudIndex);
            if (cloud.dimension != level.dimension()) {
                continue;
            }

            if (cloud.age >= cloud.lifetimeTicks) {
                CLOUDS.remove(cloudIndex);
                continue;
            }

            cloud.drift(level, currentTick);
            Vec3 center = cloud.center();
            double radius = cloud.radius();
            double radiusSquared = radius * radius;
            boolean ownerImmune = cloud.age < cloud.ownerGraceTicks;

            // FIND ALL LIVING ENTITIES (PLAYERS AND MOBS) TOUCHING THE SPHERE
            List<LivingEntity> entities = level.getEntitiesOfClass(
                LivingEntity.class,
                AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0),
                entity ->
                    entity.isAlive()
                    && !entity.isSpectator()
                    && !(ownerImmune && entity.getUUID().equals(cloud.owner))
                    && entity.getBoundingBox().distanceToSqr(center) <= radiusSquared
            );

            // CREDIT THE FARTER (IF THEY ARE STILL ONLINE)
            ServerPlayer owner = cloud.owner == null || entities.isEmpty()
                ? null
                : level.getServer().getPlayerList().getPlayer(cloud.owner);

            for (LivingEntity entity : entities) {
                // ENTITY WAS DOSED RECENTLY
                if (currentTick < entity.getAttachedOrElse(NEXT_DOSE, 0L)) {
                    continue;
                }

                // POISON-IMMUNE MOBS (UNDEAD) DON'T MIND THE GAS
                if (!ModEffects.canSmell(entity)) {
                    continue;
                }

                MobEffectInstance stench = new MobEffectInstance(
                    ModEffects.STENCH,
                    cloud.stenchTicks,
                    STENCH_AMPLIFIER
                );
                if (!entity.canBeAffected(stench)) {
                    continue;
                }

                // THE GAS DOESN'T GO THROUGH WALLS
                if (!canReach(level, center, entity)) {
                    continue;
                }

                entity.setAttached(NEXT_DOSE, currentTick + DOSE_INTERVAL_TICKS);
                entity.addEffect(stench, owner);
                entity.hurtServer(level, FartGasDamageSource.create(gasDamage, owner), GAS_DAMAGE);
            }

            cloud.age++;
        }
    }

    // TRUE IF NO BLOCK IS BETWEEN THE CENTER OF THE CLOUD AND THE ENTITY'S BODY OR EYES
    private static boolean canReach(ServerLevel level, Vec3 center, LivingEntity entity) {
        AABB box = entity.getBoundingBox();
        if (box.contains(center)) {
            return true;
        }

        return clip(level, center, box.getCenter()).getType() == HitResult.Type.MISS
            || clip(level, center, entity.getEyePosition()).getType() == HitResult.Type.MISS;
    }

    private static BlockHitResult clip(ServerLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(
            from,
            to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            CollisionContext.empty()
        ));
    }

    private static final class Cloud {
        final ResourceKey<Level> dimension;
        final @Nullable UUID owner;
        final int ownerGraceTicks;
        final int lifetimeTicks;
        final int stenchTicks;
        final Vec3 origin;
        final Vec3 backward;
        final double maxOffset;
        final double spread;
        int age;

        // HOW FAR THE BREEZE HAS CARRIED THE CLOUD, AND HOW FAST IT IS MOVING IT NOW
        double driftX;
        double driftZ;
        double driftSpeedX;
        double driftSpeedZ;

        Cloud(
            ResourceKey<Level> dimension,
            @Nullable UUID owner,
            int ownerGraceTicks,
            int lifetimeTicks,
            int stenchTicks,
            Vec3 origin,
            Vec3 backward,
            double maxOffset,
            double spread
        ) {
            this.dimension = dimension;
            this.owner = owner;
            this.ownerGraceTicks = ownerGraceTicks;
            this.lifetimeTicks = lifetimeTicks;
            this.stenchTicks = stenchTicks;
            this.origin = origin;
            this.backward = backward;
            this.maxOffset = maxOffset;
            this.spread = spread;
        }

        // HOW FAR THE JET HAS GONE, FROM 0 TO 1
        double jet() {
            return 1.0 - Math.exp(-this.age / JET_TICKS);
        }

        // THE JET CARRIES THE GAS BACKWARD (A BURST HAS NO DIRECTION), THEN THE BREEZE TAKES IT
        Vec3 center() {
            double offset = Math.min(ORIGIN_BEHIND + JET_DISTANCE * this.jet(), this.maxOffset);
            return this.origin.add(
                this.backward.x * offset + this.driftX,
                0.0,
                this.backward.z * offset + this.driftZ
            );
        }

        // OUTDOORS THE BREEZE PUSHES THE CLOUD ALONG, UNTIL A WALL STOPS IT
        void drift(ServerLevel level, long gameTime) {
            Vec3 center = this.center();
            double exposure = FartBreeze.exposure(
                level.dimensionType().hasSkyLight(),
                level.getBrightness(LightLayer.SKY, BlockPos.containing(center)) / 15.0
            );
            this.driftSpeedX = this.driftSpeedX * BREEZE_DRAG + FartBreeze.x(gameTime) * exposure;
            this.driftSpeedZ = this.driftSpeedZ * BREEZE_DRAG + FartBreeze.z(gameTime) * exposure;

            double x = center.x + this.driftSpeedX;
            if (isFullBlock(level, x, center.y, center.z)) {
                this.driftSpeedX = 0.0;
                x = center.x;
            } else {
                this.driftX += this.driftSpeedX;
            }

            if (isFullBlock(level, x, center.y, center.z + this.driftSpeedZ)) {
                this.driftSpeedZ = 0.0;
            } else {
                this.driftZ += this.driftSpeedZ;
            }
        }

        private static boolean isFullBlock(ServerLevel level, double x, double y, double z) {
            BlockPos pos = BlockPos.containing(x, y, z);
            return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
        }

        // THE JET FANS THE GAS OUT, THEN IT SLOWLY DIFFUSES
        double radius() {
            return Math.sqrt(INITIAL_RADIUS * INITIAL_RADIUS + DIFFUSION * this.age)
                + this.spread * this.jet();
        }
    }
}
