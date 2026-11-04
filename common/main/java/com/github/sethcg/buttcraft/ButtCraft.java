package com.github.sethcg.buttcraft;

import com.github.sethcg.buttcraft.networking.FartPayload;
import com.github.sethcg.buttcraft.networking.FartRequestPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ButtCraft implements ModInitializer {

    public static final String MOD_ID = "buttcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int FART_COOLDOWN_TICKS = 20;

    // GAME TIME OF THE PLAYER'S NEXT ALLOWED FART. NOT SAVED: IT GOES AWAY WITH THE ENTITY
    private static final AttachmentType<Long> NEXT_FART = AttachmentRegistry.create(id("next_fart"));

    private static final double FART_SOUND_RADIUS = 32.0;

    private static final double FART_EFFECT_RADIUS = 4.0;
    private static final double FART_EFFECT_RADIUS_SQUARED = FART_EFFECT_RADIUS * FART_EFFECT_RADIUS;

    private static final float FART_DAMAGE = 2.0F;

    private static final double FART_KNOCKBACK = 0.5;
    private static final double FART_VERTICAL_KNOCKBACK = 0.2;

    @Override
    public void onInitialize() {

        ModSounds.initialize();
        ModEffects.initialize();
        Networking.registerPayloads();
        FartClouds.initialize();

        ServerPlayNetworking.registerGlobalReceiver(
            FartRequestPayload.TYPE,
            (payload, context) -> {
                ServerPlayer player = context.player();
                if (!player.isRemoved() && player.isAlive() && !player.isSpectator()) {
                    tryFart(player);
                }
            }
        );

        LOGGER.info("BUTTCRAFT INITIALIZED.");
    }

    private static void tryFart(ServerPlayer player) {
        long currentTick = player.level().getGameTime();

        if (currentTick < player.getAttachedOrElse(NEXT_FART, 0L)) {
            return;
        }

        player.setAttached(NEXT_FART, currentTick + FART_COOLDOWN_TICKS);

        fart(player);
    }

    private static void fart(ServerPlayer player) {
        ServerLevel level = player.level();

        affectNearbyEntities(player, level);

        FartClouds.spawn(level, player);

        Vec3 pos = player.position();
        broadcast(level, pos, FartPayload.fart(pos, player.getYRot(), level.getRandom().nextInt()));
    }

    // A FART THAT ISN'T COMING OUT OF A PLAYER (E.G. A SPLASH POTION): SPREADS EVENLY AROUND THE POSITION
    public static void fartBurst(
        ServerLevel level,
        Vec3 pos,
        @Nullable Entity owner,
        int lifetimeTicks,
        int stenchTicks
    ) {

        FartClouds.spawnBurst(level, pos, owner, lifetimeTicks, stenchTicks);

        broadcast(level, pos, FartPayload.burst(pos, lifetimeTicks, level.getRandom().nextInt()));
    }

    private static void broadcast(ServerLevel level, Vec3 pos, FartPayload fart) {
        for (ServerPlayer other : PlayerLookup.around(level, pos, FART_SOUND_RADIUS)) {
            ServerPlayNetworking.send(other, fart);
        }
    }

    private static void affectNearbyEntities(ServerPlayer player, ServerLevel level) {

        AABB fartArea = player
            .getBoundingBox()
            .inflate(FART_EFFECT_RADIUS);

        List<LivingEntity> entities = level.getEntitiesOfClass(
            LivingEntity.class,
            fartArea,
            entity ->
                entity != player
                && entity.isAlive()
                && entity.distanceToSqr(player) <= FART_EFFECT_RADIUS_SQUARED
        );

        double yaw = Math.toRadians(player.getYRot());
        double backwardX = Math.sin(yaw);
        double backwardZ = -Math.cos(yaw);

        for (LivingEntity entity : entities) {
            // NO KNOCKBACK IF THE HIT DIDN'T LAND (CREATIVE, HIT COOLDOWN, PVP OFF)
            if (!entity.hurtServer(
                level,
                level.damageSources().playerAttack(player),
                FART_DAMAGE
            )) {
                continue;
            }

            double offsetX = entity.getX() - player.getX();
            double offsetZ = entity.getZ() - player.getZ();

            double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);

            if (distance < 0.001) {
                offsetX = 0.0;
                offsetZ = 1.0;
                distance = 1.0;
            }

            offsetX /= distance;
            offsetZ /= distance;

            // FULL STRENGTH DIRECTLY BEHIND THE PLAYER, HALF DIRECTLY IN FRONT
            double direction = offsetX * backwardX + offsetZ * backwardZ;
            double knockbackMultiplier = 0.75 + (direction * 0.25);

            double knockback = FART_KNOCKBACK * knockbackMultiplier;
            double verticalKnockback = FART_VERTICAL_KNOCKBACK * knockbackMultiplier;

            entity.push(
                offsetX * knockback,
                Math.max(verticalKnockback, entity.getDeltaMovement().y),
                offsetZ * knockback
            );
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static Logger logger(String subsystem) {
        return LoggerFactory.getLogger(MOD_ID + "/" + subsystem);
    }
}