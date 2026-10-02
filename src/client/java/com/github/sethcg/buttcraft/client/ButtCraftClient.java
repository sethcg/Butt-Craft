package com.github.sethcg.buttcraft.client;

import com.github.sethcg.buttcraft.ButtCraft;
import com.github.sethcg.buttcraft.ModSounds;
import com.github.sethcg.buttcraft.networking.FartPayload;
import com.github.sethcg.buttcraft.networking.FartRequestPayload;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundSource;

public class ButtCraftClient implements ClientModInitializer {

    private final KeyMapping.Category CATEGORY =
		KeyMapping.Category.register(
				ButtCraft.id("buttcraft")
		);

    private final KeyMapping FART_KEY =
		KeyMappingHelper.registerKeyMapping(
			new KeyMapping(
				"key.buttcraft.fart",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_R,
				this.CATEGORY
			)
		);

    @Override
    public void onInitializeClient() {

        // CHECK FOR THE FART KEY EVERY CLIENT TICK.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // HANDLE EVERY FART KEY PRESS THAT HAS BEEN REGISTERED.
            while (this.FART_KEY.consumeClick()) {
                if (client.player == null) continue;

                // TELL THE SERVER THAT THE PLAYER PRESSED THE FART KEY.
                ClientPlayNetworking.send(new FartRequestPayload());
            }
        });

        // RECEIVE FARTS FROM THE SERVER.
        ClientPlayNetworking.registerGlobalReceiver(
			FartPayload.TYPE,
			(payload, context) -> {
				// RUN THE SOUND CODE ON THE CLIENT THREAD.
				context.client().execute(() -> {
					playFart(
						context.client(),
						payload.x(),
						payload.y(),
						payload.z()
					);
				});
			}
        );
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
			z
        );

        client.getSoundManager().play(sound);
    }
}
