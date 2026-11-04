package com.github.sethcg.buttcraft.client.gas;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

public final class FartGas {

    private static final FartGasSimulation SIMULATION = new FartGasSimulation();
    private static final FartGasRenderer RENDERER = new FartGasRenderer(SIMULATION);
    private static @Nullable ClientLevel lastLevel;

    private FartGas() {
    }

    public static void initialize() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RENDERER.close());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastLevel) {
                lastLevel = client.level;
                SIMULATION.clear();
            }

            if (client.level == null) {
                return;
            }

            if (!client.isPaused() && client.level.tickRateManager().runsNormally()) {
                SIMULATION.tick(client.level);
            }
        });

        LevelRenderEvents.END_MAIN.register(RENDERER::render);
    }

    public static void emit(Vec3 pos, float yaw, int seed) {
        SIMULATION.emit(pos, yaw, seed);
    }

    public static void emitBurst(Vec3 pos, int lifetimeTicks, int seed) {
        SIMULATION.emitBurst(pos, lifetimeTicks, seed);
    }
}
