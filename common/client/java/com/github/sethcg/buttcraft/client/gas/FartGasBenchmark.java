package com.github.sethcg.buttcraft.client.gas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.github.sethcg.buttcraft.ButtCraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * DEV-ONLY GAS BENCHMARK, ON WHEN THE BUTTCRAFT_GAS_BENCHMARK ENVIRONMENT VARIABLE IS SET (E.G.
 * RUN THE CLIENT WITH --quickPlaySingleplayer INTO A WORLD). FOR EACH FART COUNT AND MAX PUFFS
 * VALUE IT RELEASES THAT MANY STAGGERED FARTS IN FRONT OF THE CAMERA AND THEN AROUND IT, LOGS THE
 * AVERAGE GPU TIME OF THE GAS PASSES AND THE SIMULATION TICK, AND QUITS THE GAME WHEN DONE.
 *
 * <p>IT SAVES THE SETTINGS IT TESTS, SO BACK UP config/buttcraft.properties FIRST.
 */
public final class FartGasBenchmark {

    private static final Logger LOGGER = ButtCraft.logger("gas-benchmark");

    public static final boolean ACTIVE = System.getenv("BUTTCRAFT_GAS_BENCHMARK") != null;

    private static final int[] FARTS = { 20, 30 };
    private static final int[] MAX_PUFFS = { 128, 192, 256 };
    private static final int WARMUP_TICKS = 200;
    // FARTS GO OFF OVER THE FIRST EMIT_TICKS, THEN THE GAS IS MEASURED FROM SETTLE_TICKS ON.
    private static final int EMIT_TICKS = 60;
    private static final int SETTLE_TICKS = 70;
    private static final int RUN_TICKS = 200;

    private static int tick;
    private static int run;
    private static int runTick;
    private static Vec3 origin = Vec3.ZERO;
    private static final RandomSource RANDOM = RandomSource.create();

    private static double gasSum;
    private static double marchSum;
    private static int gpuSamples;
    private static long fpsSum;
    private static long tickNanosSum;
    private static int puffSum;
    private static float immersionSum;
    private static int sampleTicks;
    private static final List<String> RESULTS = new ArrayList<>();

    private FartGasBenchmark() {
    }

    static void recordGpu(double march, double total) {
        if (runTick >= SETTLE_TICKS) {
            marchSum += march;
            gasSum += total;
            gpuSamples++;
        }
    }

    static void tick(Minecraft client, FartGasSimulation simulation, long tickNanos) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        client.options.pauseOnLostFocus = false;
        // KEEPS THE AFK FRAMERATE LIMIT FROM KICKING IN PARTWAY THROUGH.
        client.getFramerateLimitTracker().onInputReceived();
        if (++tick < WARMUP_TICKS) {
            if (tick == 1 && player.connection != null) {
                player.connection.sendCommand("time set noon");
                player.connection.sendCommand("gamerule advance_time false");
            }
            return;
        }

        int runs = FARTS.length * MAX_PUFFS.length * 2;
        if (run >= runs) {
            if (run == runs) {
                run++;
                LOGGER.info("GAS BENCHMARK DONE (settings {} apart from max puffs):", FartGasSettings.current());
                RESULTS.forEach(LOGGER::info);
                client.stop();
            }
            return;
        }

        int config = run / 2;
        int farts = FARTS[config / MAX_PUFFS.length];
        int maxPuffs = MAX_PUFFS[config % MAX_PUFFS.length];
        boolean inside = run % 2 == 1;
        if (runTick == 0) {
            FartGasSettings.set(FartGasSettings.DEFAULTS.withMaxPuffs(maxPuffs));
            simulation.clear();
            // THE SAME FARTS IN THE SAME PLACES FOR EVERY MAX PUFFS VALUE.
            RANDOM.setSeed(farts * 2L + (inside ? 1 : 0));
            player.setDeltaMovement(Vec3.ZERO);
            // FACING +Z (YAW 0). OUTSIDE: A CROWD 6 BLOCKS AHEAD. INSIDE: AROUND THE PLAYER.
            origin = player.position().add(0.0, 0.0, inside ? 0.0 : 6.0);
            gasSum = marchSum = 0.0;
            gpuSamples = 0;
            fpsSum = 0;
            tickNanosSum = 0;
            puffSum = 0;
            immersionSum = 0.0F;
            sampleTicks = 0;
        }

        player.setYRot(0.0F);
        player.setXRot(0.0F);
        player.yRotO = 0.0F;
        player.xRotO = 0.0F;

        // FARTS SPREAD EVENLY OVER EMIT_TICKS, FROM A 3x3 BLOCK CROWD FACING EVERY WAY.
        if (runTick < EMIT_TICKS && runTick * farts / EMIT_TICKS != (runTick + 1) * farts / EMIT_TICKS) {
            Vec3 pos = origin.add(RANDOM.nextDouble() * 3.0 - 1.5, 0.0, RANDOM.nextDouble() * 3.0 - 1.5);
            simulation.emit(pos, RANDOM.nextFloat() * 360.0F, RANDOM.nextInt());
        }

        if (runTick >= SETTLE_TICKS) {
            fpsSum += client.getFps();
            tickNanosSum += tickNanos;
            puffSum += simulation.puffs().size();
            immersionSum += FartGasUniforms.lastImmersion;
            sampleTicks++;
        }

        if (++runTick >= RUN_TICKS) {
            RESULTS.add(String.format(Locale.ROOT,
                "  %d farts, max puffs %3d, %s: %5.1f puffs, immersion %.2f, GPU gas %6.2f ms (march %6.2f ms), tick %5.2f ms, %d fps",
                farts, maxPuffs, inside ? "inside " : "outside", puffSum / (double) sampleTicks,
                immersionSum / sampleTicks, gasSum / Math.max(gpuSamples, 1), marchSum / Math.max(gpuSamples, 1),
                tickNanosSum / 1.0E6 / sampleTicks, fpsSum / sampleTicks));
            LOGGER.info(RESULTS.getLast());
            run++;
            runTick = 0;
        }
    }
}
