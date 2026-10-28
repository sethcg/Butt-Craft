package com.github.sethcg.buttcraft.client.gas;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.github.sethcg.buttcraft.ButtCraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * THE "buttcraft:fart_gas" F3 ENTRY (TURN IT ON IN THE DEBUG OPTIONS SCREEN): HOW MANY PUFFS
 * AND CLOUDS THERE ARE, HOW THEY ARE SPLIT INTO SCREEN RECTANGLES, AND WHAT THEY COST ON THE
 * CPU (SIMULATION TICK) AND GPU (THE MARCH, AND EVERY GAS PASS TOGETHER).
 *
 * <p>EVERYTHING HERE IS WRITTEN AND READ ON THE RENDER THREAD (THE CLIENT TICK RUNS THERE TOO).
 */
public final class FartGasDebug implements DebugScreenEntry {

    public static final Identifier ID = ButtCraft.id("fart_gas");

    // EACH NEW SAMPLE MOVES THE SHOWN AVERAGE THIS FAR, SO IT SETTLES OVER ABOUT 20 SAMPLES.
    private static final double SMOOTHING = 0.05;

    private static int puffs;
    private static int clouds;
    private static double tickMillis = Double.NaN;

    private static int uploadedPuffs;
    private static int rects;
    private static int largestRun;

    private static double marchMillis = Double.NaN;
    private static double gasMillis = Double.NaN;

    private FartGasDebug() {
    }

    public static void register() {
        DebugScreenEntries.register(ID, new FartGasDebug());
    }

    /** WHETHER THE ENTRY IS ON SCREEN, SO THE GPU TIMER ONLY RUNS WHILE SOMEONE IS LOOKING. */
    public static boolean shown() {
        return FartGasBenchmark.ACTIVE || Minecraft.getInstance().debugEntries.isCurrentlyEnabled(ID);
    }

    static void recordTick(long nanos, int puffCount, int cloudCount) {
        puffs = puffCount;
        clouds = cloudCount;
        tickMillis = smooth(tickMillis, nanos / 1.0E6);
    }

    /** WHAT THE LAST FRAME UPLOADED (ALL ZERO WHEN IT DREW NO GAS). */
    public static void recordUpload(int uploaded, int rectCount, int largest) {
        uploadedPuffs = uploaded;
        rects = rectCount;
        largestRun = largest;
    }

    public static void recordGpu(double march, double total) {
        if (FartGasBenchmark.ACTIVE) {
            FartGasBenchmark.recordGpu(march, total);
        }

        marchMillis = smooth(marchMillis, march);
        gasMillis = smooth(gasMillis, total);
    }

    private static double smooth(double average, double sample) {
        return Double.isNaN(average) ? sample : average + (sample - average) * SMOOTHING;
    }

    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
        displayer.addLine(String.format(Locale.ROOT, "Fart gas: %d/%d puffs, %d clouds, tick %s",
            puffs, FartGasSettings.current().maxPuffs(), clouds, millis(tickMillis)));
        displayer.addLine(String.format(Locale.ROOT, "Fart gas draw: %d puffs in %d rects (largest %d), GPU %s (march %s)",
            uploadedPuffs, rects, largestRun, millis(gasMillis), millis(marchMillis)));
    }

    private static String millis(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.2f ms", value);
    }
}
