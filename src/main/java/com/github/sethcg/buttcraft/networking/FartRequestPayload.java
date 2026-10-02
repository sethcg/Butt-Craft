package com.github.sethcg.buttcraft.networking;

import com.github.sethcg.buttcraft.ButtCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record FartRequestPayload() implements CustomPacketPayload {

    public static final Identifier ID = ButtCraft.id("fart_request");
    public static final Type<FartRequestPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, FartRequestPayload> CODEC =
        StreamCodec.unit(new FartRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
