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

    // FART COOLDOWN (AVOID SPAMMING, 20 TICKS = 1 SECOND)
    private static final int FART_COOLDOWN_TICKS = 20;

    // GAME TIME WHEN EACH PLAYER CAN FART AGAIN. NOT SAVED: IT GOES AWAY WITH THE PLAYER ENTITY.
    private static final AttachmentType<Long> NEXT_FART = AttachmentRegistry.create(id("next_fart"));

    // FART VISIBILITY / SOUND
    private static final double FART_SOUND_RADIUS = 32.0;

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

        // REGISTER CUSTOM STATUS EFFECTS
        ModEffects.initialize();

        // REGISTER PACKET PAYLOADS
        Networking.registerPayloads();

        // TICK THE POISONOUS GAS CLOUDS LEFT BEHIND BY FARTS
        FartClouds.initialize();

        // RECEIVE FART REQUESTS FROM CLIENTS
        ServerPlayNetworking.registerGlobalReceiver(
            FartRequestPayload.TYPE,
            (payload, context) -> {
                ServerPlayer player = context.player();
                // DEAD PLAYERS AND SPECTATORS CAN'T FART
                if (!player.isRemoved() && player.isAlive() && !player.isSpectator()) {
                    tryFart(player);
                }
            }
        );

        LOGGER.info("BUTTCRAFT INITIALIZED.");
    }

    private static void tryFart(ServerPlayer player) {
        long currentTick = player.level().getGameTime();

        // PLAYER IS STILL ON COOLDOWN
        if (currentTick < player.getAttachedOrElse(NEXT_FART, 0L)) {
            return;
        }

        // SET NEXT AVAILABLE FART TIME
        player.setAttached(NEXT_FART, currentTick + FART_COOLDOWN_TICKS);

        // ACTUALLY FART
        fart(player);
    }

    private static void fart(ServerPlayer player) {
        ServerLevel level = player.level();

        // DAMAGE AND KNOCK BACK ENTITIES AROUND THE PLAYER
        affectNearbyEntities(player, level);

        // LEAVE A POISONOUS CLOUD WHERE THE GAS WILL BE
        FartClouds.spawn(level, player);

        // SEND THE PLAYER'S REAL SERVER-SIDE POSITION TO NEARBY CLIENTS
        Vec3 pos = player.position();
        broadcast(level, pos, FartPayload.fart(pos, player.getYRot(), level.getRandom().nextInt()));
    }

    /**
     * RELEASES A FART THAT ISN'T COMING OUT OF A PLAYER (E.G. A SPLASH POTION BREAKING).
     * THE GAS SPREADS OUT EVENLY AROUND THE POINT AND LINGERS FOR lifetimeTicks (20 TICKS =
     * 1 SECOND), AND EACH DOSE GIVES stenchTicks OF STENCH I. THE OWNER (IF ANY) GETS THE CREDIT.
     */
    public static void fartBurst(
        ServerLevel level,
        Vec3 pos,
        @Nullable Entity owner,
        int lifetimeTicks,
        int stenchTicks
    ) {

        // LEAVE A STINKY CLOUD WHERE THE GAS WILL BE
        FartClouds.spawnBurst(level, pos, owner, lifetimeTicks, stenchTicks);

        broadcast(level, pos, FartPayload.burst(pos, lifetimeTicks, level.getRandom().nextInt()));
    }

    // EVERY PLAYER WITHIN EARSHOT HEARS AND SEES THE FART
    private static void broadcast(ServerLevel level, Vec3 pos, FartPayload fart) {
        for (ServerPlayer other : PlayerLookup.around(level, pos, FART_SOUND_RADIUS)) {
            ServerPlayNetworking.send(other, fart);
        }
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
            // APPLY DAMAGE. NO KNOCKBACK IF IT DIDN'T LAND (CREATIVE, HIT COOLDOWN, PVP OFF).
            if (!entity.hurtServer(
                level,
                level.damageSources().playerAttack(player),
                FART_DAMAGE
            )) {
                continue;
            }

            // CALCULATE HORIZONTAL DIRECTION FROM THE PLAYER TO THE ENTITY
            double offsetX = entity.getX() - player.getX();
            double offsetZ = entity.getZ() - player.getZ();

            double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);

            // ENTITY ON TOP OF PLAYER, AVOID ZERO DIVIDE
            if (distance < 0.001) {
                offsetX = 0.0;
                offsetZ = 1.0;
                distance = 1.0;
            }

            // NORMALIZE THE DIRECTION
            offsetX /= distance;
            offsetZ /= distance;

            double direction = offsetX * backwardX + offsetZ * backwardZ;
            double knockbackMultiplier = 0.75 + (direction * 0.25);

            double knockback = FART_KNOCKBACK * knockbackMultiplier;
            double verticalKnockback = FART_VERTICAL_KNOCKBACK * knockbackMultiplier;

            // APPLY KNOCKBACK (X, Y, Z)
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

    /**
     * A LOGGER FOR ONE PART OF THE MOD, NAMED buttcraft/subsystem (E.G. buttcraft/gas), SO ITS
     * LINES SAY WHERE THEY CAME FROM AND A LOG4J CONFIG CAN CHANGE ITS LEVEL ON ITS OWN.
     */
    public static Logger logger(String subsystem) {
        return LoggerFactory.getLogger(MOD_ID + "/" + subsystem);
    }
}