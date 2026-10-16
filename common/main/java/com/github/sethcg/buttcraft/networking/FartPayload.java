package com.github.sethcg.buttcraft.networking;

import com.github.sethcg.buttcraft.ButtCraft;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

public record FartPayload(Vec3 pos, float yaw) implements CustomPacketPayload {

    public static final Type<FartPayload> TYPE = new Type<>(ButtCraft.id("fart"));

    public static final StreamCodec<ByteBuf, FartPayload> CODEC = StreamCodec.composite(
        Vec3.STREAM_CODEC, FartPayload::pos,
        ByteBufCodecs.FLOAT, FartPayload::yaw,
        FartPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
