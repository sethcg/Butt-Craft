package com.github.sethcg.buttcraft;

import com.github.sethcg.buttcraft.networking.FartPayload;
import com.github.sethcg.buttcraft.networking.FartRequestPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ButtCraft implements ModInitializer {

    public static final String MOD_ID = "buttcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // FART COOLDOWN (AVOID SPAMMING, 20 TICKS = 1 SECOND)
    private static final int FART_COOLDOWN_TICKS = 20;

    // TRACK WHEN EACH PLAYER CAN FART AGAIN
    private static final Map<UUID, Long> FART_COOLDOWNS = new HashMap<>();

    // FART VISIBILITY / SOUND
    private static final double FART_SOUND_RADIUS = 32.0;
    private static final double FART_SOUND_RADIUS_SQUARED = FART_SOUND_RADIUS * FART_SOUND_RADIUS;

    // FART AREA OF EFFECT
    private static final double FART_EFFECT_RADIUS = 4.0;
    private static final double FART_EFFECT_RADIUS_SQUARED = FART_EFFECT_RADIUS * FART_EFFECT_RADIUS;

    // FART DAMAGE (2.0 = 1 HEART)
    private static final float FART_DAMAGE = 2.0F;

    // FART KNOCKBACK
    private static final double FART_KNOCKBACK = 0.5;
    private static final double FART_VERTICAL_KNOCKBACK = 0.2;

    @Override
    public void onInitialize() {

        // REGISTER CUSTOM SOUND
        ModSounds.initialize();

        // VERSION-SPECIFIC NETWORKING REGISTRATION
        Networking.registerPayloads();

        // RECEIVE FART REQUESTS FROM CLIENTS
        ServerPlayNetworking.registerGlobalReceiver(
            FartRequestPayload.TYPE,
            (payload, context) -> {
                ServerPlayer player = context.player();
                if (!player.isRemoved()) {
                    tryFart(player);
                }
            }
        );

        // REMOVE PLAYER'S FART COOLDOWN WHEN THEY DISCONNECT
        ServerPlayConnectionEvents.DISCONNECT.register(
            (handler, server) -> {
                FART_COOLDOWNS.remove(handler.getPlayer().getUUID());
            }
        );

        LOGGER.info("BUTTCRAFT INITIALIZED.");
    }

    private static void tryFart(ServerPlayer player) {
        long currentTick = player.level().getGameTime();
        UUID playerId = player.getUUID();
        Long nextAllowedTick = FART_COOLDOWNS.get(playerId);

        // PLAYER IS STILL ON COOLDOWN
        if (nextAllowedTick != null && currentTick < nextAllowedTick) {
            return;
        }

        // SET NEXT AVAILABLE FART TIME
        FART_COOLDOWNS.put(
            playerId,
            currentTick + FART_COOLDOWN_TICKS
        );

        // ACTUALLY FART
        fart(player);
    }

    private static void fart(ServerPlayer player) {
        ServerLevel level = player.level();

        // GET THE PLAYER'S REAL SERVER-SIDE POSITION
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();

        // DAMAGE AND KNOCK BACK ENTITIES AROUND THE PLAYER
        affectNearbyEntities(player, level);

        // CREATE THE PAYLOAD THAT WILL BE SENT TO NEARBY CLIENTS
        FartPayload fart = new FartPayload(x, y, z, yaw);

        level.players().stream()
            .filter(other -> other.distanceToSqr(player) <= FART_SOUND_RADIUS_SQUARED)
            .forEach(other -> ServerPlayNetworking.send(other, fart));
    }

    private static void affectNearbyEntities(ServerPlayer player, ServerLevel level) {

        // CREATE THE FART'S AREA OF EFFECT
        AABB fartArea = player
            .getBoundingBox()
            .inflate(FART_EFFECT_RADIUS);

        // FIND ALL LIVING ENTITIES INSIDE THE AREA
        List<LivingEntity> entities = level.getEntitiesOfClass(
            LivingEntity.class,
            fartArea,
            entity ->
                entity != player
                && entity.isAlive()
                && entity.distanceToSqr(player) <= FART_EFFECT_RADIUS_SQUARED
        );

        // DIRECTION DIRECTLY BEHIND THE PLAYER
        double yaw = Math.toRadians(player.getYRot());
        double backwardX = Math.sin(yaw);
        double backwardZ = -Math.cos(yaw);

        for (LivingEntity entity : entities) {
            // APPLY DAMAGE
            entity.hurtServer(
                level,
                level.damageSources().playerAttack(player),
                FART_DAMAGE
            );

            // CALCULATE HORIZONTAL DIRECTION FROM THE PLAYER TO THE ENTITY
            double dx = entity.getX() - player.getX();
            double dz = entity.getZ() - player.getZ();

            double distance = Math.sqrt(dx * dx + dz * dz);

            // ENTITY ON TOP OF PLAYER, AVOID ZERO DIVIDE
            if (distance < 0.001) {
                dx = 0.0;
                dz = 1.0;
                distance = 1.0;
            }

            // NORMALIZE THE DIRECTION
            dx /= distance;
            dz /= distance;

            double direction = dx * backwardX + dz * backwardZ;
            double knockbackMultiplier = 0.75 + (direction * 0.25);

            double knockback = FART_KNOCKBACK * knockbackMultiplier;
            double verticalKnockback = FART_VERTICAL_KNOCKBACK * knockbackMultiplier;

            // APPLY KNOCKBACK (X, Y, Z)
            entity.push(
                dx * knockback,
                Math.max(verticalKnockback, entity.getDeltaMovement().y),
                dz * knockback
            );
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}