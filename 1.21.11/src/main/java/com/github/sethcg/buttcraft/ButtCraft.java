package com.github.sethcg.buttcraft;

import com.github.sethcg.buttcraft.networking.FartPayload;
import com.github.sethcg.buttcraft.networking.FartRequestPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ButtCraft implements ModInitializer {

    public static final String MOD_ID = "buttcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // SET THE MAXIMUM DISTANCE THAT A FART CAN BE HEARD (16x16 BLOCK RADIUS)
    private static final double FART_RADIUS = 16.0;
    private static final double FART_RADIUS_SQUARED = FART_RADIUS * FART_RADIUS;

    @Override
    public void onInitialize() {

		// REGISTER CUSTOM SOUNDS
        ModSounds.initialize();

        // REGISTER THE CLIENT -> SERVER FART REQUEST.
        PayloadTypeRegistry.playC2S().register(
			FartRequestPayload.TYPE,
			FartRequestPayload.CODEC
        );

        // REGISTER THE SERVER -> CLIENT FART PAYLOAD.
        PayloadTypeRegistry.playS2C().register(
			FartPayload.TYPE,
			FartPayload.CODEC
        );

        // RECEIVE FART REQUESTS FROM CLIENTS.
        ServerPlayNetworking.registerGlobalReceiver(
			FartRequestPayload.TYPE,
			(payload, context) -> {
				var player = context.player();

				// GET THE PLAYER'S REAL SERVER-SIDE POSITION.
				double x = player.getX();
				double y = player.getY();
				double z = player.getZ();
                float yaw = player.getYRot();

				// CREATE THE PAYLOAD THAT WILL BE SENT TO NEARBY CLIENTS.
				FartPayload fart = new FartPayload(x, y, z, yaw);

				// SEND THE FART TO EVERY PLAYER WITHIN 16 BLOCKS.
				player.level().players().stream()
					.filter(other -> other.distanceToSqr(player) <= FART_RADIUS_SQUARED)
					.forEach(other -> ServerPlayNetworking.send(other, fart));
			}
        );

        LOGGER.info("BUTTCRAFT INITIALIZED.");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
