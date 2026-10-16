package com.github.sethcg.buttcraft.client;

import com.github.sethcg.buttcraft.ButtCraft;
import com.github.sethcg.buttcraft.ModSounds;
import com.github.sethcg.buttcraft.client.gas.FartGas;
import com.github.sethcg.buttcraft.networking.FartBurstPayload;
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

    @Override
    public void onInitializeClient() {
        ButtCraft.LOGGER.info("BUTTCRAFT CLIENT INITIALIZED.");

        KeyBindings.initialize();
        FartGas.initialize();

        // CHECK FOR THE FART KEY EVERY CLIENT TICK
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // HANDLE EVERY FART KEY PRESS THAT HAS BEEN REGISTERED.
            // THE SERVER ENFORCES THE COOLDOWN, SO EVERY PRESS IS SENT.
            while (KeyBindings.FART_KEY.consumeClick()) {
                if (client.player != null) {
                    ClientPlayNetworking.send(new FartRequestPayload());
                }
            }
        });

        // RECEIVE FARTS FROM THE SERVER (HANDLERS RUN ON THE RENDER THREAD)
        ClientPlayNetworking.registerGlobalReceiver(
                FartPayload.TYPE,
                (payload, context) -> {
                    Minecraft client = context.client();
                    if (client.level == null)
                        return;

                    playFart(client);
                    FartGas.emit(payload.pos(), payload.yaw());
                });

        // RECEIVE FARTS THAT BURST OUT AROUND A POINT (E.G. A SPLASH POTION)
        ClientPlayNetworking.registerGlobalReceiver(
                FartBurstPayload.TYPE,
                (payload, context) -> {
                    Minecraft client = context.client();
                    if (client.level == null)
                        return;

                    playFart(client);
                    FartGas.emitBurst(payload.pos(), payload.lifetimeTicks(), payload.size());
                });
    }

    private void playFart(Minecraft client) {
        // NON-POSITIONAL: THE SERVER ONLY SENDS FARTS TO PLAYERS WITHIN RANGE,
        // SO EVERYONE WHO RECEIVES ONE HEARS IT AT FULL VOLUME.
        SimpleSoundInstance sound = new SimpleSoundInstance(
                ModSounds.FART.location(),
                SoundSource.PLAYERS,
                1.0F,
                1.0F,
                client.level.getRandom(),
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
