package com.github.sethcg.buttcraft.client;

import com.github.sethcg.buttcraft.ButtCraft;
import com.github.sethcg.buttcraft.ModSounds;
import com.github.sethcg.buttcraft.networking.FartPayload;
import com.github.sethcg.buttcraft.networking.FartRequestPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;

public class ButtCraftClient implements ClientModInitializer {

    // CLIENT-SIDE COOLDOWN (NOT NECESSARY AS SERVER ENFORCES THIS)
    private static final int FART_COOLDOWN_TICKS = 20;
    private long fartCooldown = 0;

    private static final int POISON_COLOR = 0xFF000000 | MobEffects.POISON.value().getColor();
    private static final ColorParticleOption POISON_PARTICLE = 
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, POISON_COLOR);

    @Override
    public void onInitializeClient() {
        ButtCraft.LOGGER.info("BUTTCRAFT CLIENT INITIALIZED.");

        KeyBindings.initialize();

        // CHECK FOR THE FART KEY EVERY CLIENT TICK
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // HANDLE EVERY FART KEY PRESS THAT HAS BEEN REGISTERED
            while (KeyBindings.FART_KEY.consumeClick()) {
                if (client.player == null)
                    continue;

                if (!canFart(client))
                    continue;

                // TELL THE SERVER THAT THE PLAYER PRESSED THE FART KEY
                ClientPlayNetworking.send(new FartRequestPayload());
            }
        });

        // RECEIVE FARTS FROM THE SERVER
        ClientPlayNetworking.registerGlobalReceiver(
                FartPayload.TYPE,
                (payload, context) -> {
                    // RUN THE SOUND AND PARTICLE CODE ON THE CLIENT THREAD
                    Minecraft client = context.client();

                    if (client.player == null)
                        return;

                    double x = payload.x();
                    double y = payload.y();
                    double z = payload.z();
                    float yaw = payload.yaw();

                    client.execute(() -> {
                        playFart(client, x, y, z);
                        spawnPoisonParticles(client, x, y, z, yaw);
                    });
                });
    }

    private boolean canFart(Minecraft client) {
        long currentTick = client.player.level().getGameTime();

        if (currentTick < this.fartCooldown) {
            return false;
        }

        this.fartCooldown = currentTick + FART_COOLDOWN_TICKS;

        return true;
    }

    private void playFart(Minecraft client, double x, double y, double z) {
        if (client.player == null) return;

        SimpleSoundInstance sound = new SimpleSoundInstance(
                ModSounds.FART,
                SoundSource.PLAYERS,
                1.0F,
                1.0F,
                client.player.level().getRandom(),
                x,
                y,
                z);

        client.getSoundManager().play(sound);
    }

    private void spawnPoisonParticles(Minecraft client, double x, double y, double z, float yaw) {
        if (client.player == null) return;

        var level = client.player.level();
        RandomSource random = level.getRandom();

        double angle = Math.toRadians(yaw);

        double backwardX = Math.sin(angle);
        double backwardZ = -Math.cos(angle);

        double sideX = -backwardZ;
        double sideZ = backwardX;

        // SPAWN A CLOUD OF POISON PARTICLES AT WAIST LEVEL
        for (int i = 0; i < 28; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 0.15;
            double offsetY = 0.45 + random.nextDouble() * 0.2;
            double offsetZ = (random.nextDouble() - 0.5) * 0.15;

            double speedX;
            double speedY;
            double speedZ;

            if (random.nextDouble() < 0.30) {
                speedX = (random.nextDouble() - 0.5) * 0.025;
                speedY = random.nextDouble() * 0.015;
                speedZ = (random.nextDouble() - 0.5) * 0.025;

            } else {
                double backwardSpeed = 0.08 + random.nextDouble() * 0.08;
                double sidewaysSpeed = (random.nextDouble() - 0.5) * 0.12;

                speedX = backwardX * backwardSpeed + sideX * sidewaysSpeed;
                speedZ = backwardZ * backwardSpeed + sideZ * sidewaysSpeed;
                speedY = (random.nextDouble() - 0.2) * 0.04;
            }

            level.addParticle(
                POISON_PARTICLE,
                x + offsetX,
                y + offsetY,
                z + offsetZ,
                speedX,
                speedY,
                speedZ);
        }
    }

}
