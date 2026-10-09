package com.github.sethcg.buttcraft.client.gas;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * ENTRY POINT FOR THE VOLUMETRIC FART GAS (MINECRAFT 26.3).
 *
 * <p>SIMULATION RUNS ON THE CLIENT TICK; THE GPU RAY MARCHER DRAWS AT THE END OF THE
 * MAIN WORLD PASS.
 */
public final class FartGas {

    private static final FartGasSimulation SIMULATION = new FartGasSimulation();
    private static final FartGasRenderer RENDERER = new FartGasRenderer(SIMULATION);
    private static @Nullable ClientLevel lastLevel;

    private FartGas() {
    }

    public static void initialize() {
        // TOUCH THE PIPELINE SO IT IS REGISTERED BEFORE THE FIRST RESOURCE RELOAD COMPILES SHADERS.
        FartGasRenderer.PIPELINE.getLocation();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> FartGasNoise.startGenerating());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            RENDERER.close();
            FartGasNoise.close();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // DROP ALL GAS WHEN LEAVING A WORLD OR CHANGING DIMENSION.
            if (client.level != lastLevel) {
                lastLevel = client.level;
                SIMULATION.clear();
            }

            if (client.level == null) {
                return;
            }

            if (!client.isPaused()) {
                SIMULATION.tick(client.level);
            }
        });

        LevelRenderEvents.END_MAIN.register(RENDERER::render);
    }

    public static void emit(Minecraft client, double x, double y, double z, float yaw) {
        if (client.level == null) {
            return;
        }

        SIMULATION.emit(x, y, z, yaw);
    }
}
