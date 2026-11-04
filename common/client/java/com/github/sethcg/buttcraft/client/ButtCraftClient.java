package com.github.sethcg.buttcraft.client;

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
import net.minecraft.world.phys.Vec3;

public class ButtCraftClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        KeyBindings.initialize();

        FartGas.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (KeyBindings.FART_KEY.consumeClick()) {
                if (client.player != null) {
                    ClientPlayNetworking.send(new FartRequestPayload());
                }
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(
                FartPayload.TYPE,
                (payload, context) -> {
                    Minecraft client = context.client();
                    if (client.level == null)
                        return;

                    playFart(client, payload.pos());
                    if (payload.burst()) {
                        FartGas.emitBurst(payload.pos(), payload.lifetimeTicks(), payload.seed());
                    } else {
                        FartGas.emit(payload.pos(), payload.yaw(), payload.seed());
                    }
                });
    }

    private void playFart(Minecraft client, Vec3 pos) {
        SimpleSoundInstance sound = new SimpleSoundInstance(
                ModSounds.FART.location(),
                SoundSource.PLAYERS,
                1.0F,
                1.0F,
                client.level.getRandom(),
                false,
                0,
                SoundInstance.Attenuation.LINEAR,
                pos.x,
                pos.y,
                pos.z,
                false);

        client.getSoundManager().play(sound);
    }

}
