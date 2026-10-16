package com.github.sethcg.buttcraft.networking;

import com.github.sethcg.buttcraft.ButtCraft;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

public record FartBurstPayload(Vec3 pos, int lifetimeTicks, float size) implements CustomPacketPayload {

    public static final Type<FartBurstPayload> TYPE = new Type<>(ButtCraft.id("fart_burst"));

    public static final StreamCodec<ByteBuf, FartBurstPayload> CODEC = StreamCodec.composite(
        Vec3.STREAM_CODEC, FartBurstPayload::pos,
        ByteBufCodecs.VAR_INT, FartBurstPayload::lifetimeTicks,
        ByteBufCodecs.FLOAT, FartBurstPayload::size,
        FartBurstPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
