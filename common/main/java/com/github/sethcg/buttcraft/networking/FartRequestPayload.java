package com.github.sethcg.buttcraft.networking;

import com.github.sethcg.buttcraft.ButtCraft;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FartRequestPayload() implements CustomPacketPayload {

    public static final Type<FartRequestPayload> TYPE = new Type<>(ButtCraft.id("fart_request"));

    public static final StreamCodec<ByteBuf, FartRequestPayload> CODEC =
        StreamCodec.unit(new FartRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
