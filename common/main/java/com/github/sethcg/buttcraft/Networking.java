package com.github.sethcg.buttcraft;

import com.github.sethcg.buttcraft.networking.FartPayload;
import com.github.sethcg.buttcraft.networking.FartRequestPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class Networking {

    private Networking() {
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.serverboundPlay().register(
            FartRequestPayload.TYPE,
            FartRequestPayload.CODEC
        );

        PayloadTypeRegistry.clientboundPlay().register(
            FartPayload.TYPE,
            FartPayload.CODEC
        );
    }
}