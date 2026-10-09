package com.github.sethcg.buttcraft.client;

import com.github.sethcg.buttcraft.ButtCraft;
import com.github.sethcg.buttcraft.ModSounds;
import com.github.sethcg.buttcraft.client.gas.FartGas;
import com.github.sethcg.buttcraft.networking.FartPayload;
import com.github.sethcg.buttcraft.networking.FartRequestPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

public class ButtCraftClient implements ClientModInitializer {

    // CLIENT-SIDE COOLDOWN (NOT NECESSARY AS SERVER ENFORCES THIS)
    private static final int FART_COOLDOWN_TICKS = 20;
    private long fartCooldown = 0;

    @Override
    public void onInitializeClient() {
        ButtCraft.LOGGER.info("BUTTCRAFT CLIENT INITIALIZED.");

        KeyBindings.initialize();
        FartGas.initialize();

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
                    // RUN THE SOUND AND GAS CODE ON THE CLIENT THREAD
                    Minecraft client = context.client();

                    if (client.player == null)
                        return;

                    double x = payload.x();
                    double y = payload.y();
                    double z = payload.z();
                    float yaw = payload.yaw();

                    client.execute(() -> {
                        playFart(client);
                        FartGas.emit(client, x, y, z, yaw);
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

    private void playFart(Minecraft client) {
        if (client.player == null) return;

        // NON-POSITIONAL: THE SERVER ONLY SENDS FARTS TO PLAYERS WITHIN RANGE,
        // SO EVERYONE WHO RECEIVES ONE HEARS IT AT FULL VOLUME.
        SimpleSoundInstance sound = new SimpleSoundInstance(
                ModSounds.FART.location(),
                SoundSource.PLAYERS,
                1.0F,
                1.0F,
                client.player.level().getRandom(),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0.0,
                0.0,
                0.0,
                true);

        client.getSoundManager().play(sound);
    }

}
