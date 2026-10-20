package com.github.sethcg.buttcraft.client.gas;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.github.sethcg.buttcraft.ButtCraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * ENTRY POINT FOR THE VOLUMETRIC FART GAS.
 *
 * <p>SIMULATION RUNS ON THE CLIENT TICK; THE GPU RAY MARCHER DRAWS AT THE END OF THE
 * MAIN WORLD PASS.
 */
public final class FartGas {

    private static final Logger LOGGER = ButtCraft.logger("gas");

    private static final FartGasSimulation SIMULATION = new FartGasSimulation();
    private static final FartGasRenderer RENDERER = new FartGasRenderer(SIMULATION);
    private static @Nullable ClientLevel lastLevel;

    private FartGas() {
    }

    public static void initialize() {
        FartGasSettings.load();
        LOGGER.info("FART GAS SETTINGS: {} ({}).", FartGasSettings.current(), FartGasSettings.preset());

        FartGasRenderer.initialize();

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RENDERER.close());

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

    /** CALLERS MUST BE IN A WORLD (THE GAS IS DROPPED WHEN LEAVING IT). */
    public static void emit(Vec3 pos, float yaw) {
        SIMULATION.emit(pos, yaw);
    }

    public static void emitBurst(Vec3 pos, int lifetimeTicks, float size) {
        SIMULATION.emitBurst(pos, lifetimeTicks, size);
    }
}
