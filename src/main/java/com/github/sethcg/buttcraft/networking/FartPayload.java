package com.github.sethcg.buttcraft.networking;

import com.github.sethcg.buttcraft.ButtCraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record FartPayload(double x, double y, double z) implements CustomPacketPayload {

    public static final Identifier ID = ButtCraft.id("fart");
    public static final Type<FartPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, FartPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> {
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeDouble(payload.z());
            },
            buf -> new FartPayload(
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble()
            )
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
