package com.github.sethcg.buttcraft.networking;

import com.github.sethcg.buttcraft.ButtCraft;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * A FART FOR NEARBY CLIENTS TO HEAR AND SEE. EITHER A PLAYER'S FART (BLOWN BACKWARD FROM pos,
 * FACING yaw) OR A BURST THAT SPREADS OUT AROUND pos (E.G. A SPLASH POTION) FOR lifetimeTicks,
 * size TIMES AS FAR AS A NORMAL FART. EVERY CLIENT SEEDS THE GAS'S RANDOMNESS WITH seed, SO THEY
 * ALL SEE THE SAME CLOUD. USE {@link #fart} / {@link #burst}.
 */
public record FartPayload(Vec3 pos, boolean burst, float yaw, int lifetimeTicks, float size, int seed) implements CustomPacketPayload {

    public static final Type<FartPayload> TYPE = new Type<>(ButtCraft.id("fart"));

    public static final StreamCodec<ByteBuf, FartPayload> CODEC = StreamCodec.composite(
        Vec3.STREAM_CODEC, FartPayload::pos,
        ByteBufCodecs.BOOL, FartPayload::burst,
        ByteBufCodecs.FLOAT, FartPayload::yaw,
        ByteBufCodecs.VAR_INT, FartPayload::lifetimeTicks,
        ByteBufCodecs.FLOAT, FartPayload::size,
        ByteBufCodecs.INT, FartPayload::seed,
        FartPayload::new
    );

    public static FartPayload fart(Vec3 pos, float yaw, int seed) {
        return new FartPayload(pos, false, yaw, 0, 1.0F, seed);
    }

    public static FartPayload burst(Vec3 pos, int lifetimeTicks, float size, int seed) {
        return new FartPayload(pos, true, 0.0F, lifetimeTicks, size, seed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
