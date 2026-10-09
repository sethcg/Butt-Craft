package com.github.sethcg.buttcraft.client.gas;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import com.github.sethcg.buttcraft.ButtCraft;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * LIVE GAS RENDERING SETTINGS. EDITED IN VIDEO SETTINGS (SEE
 * {@link com.github.sethcg.buttcraft.client.options.ButtCraftOptions}) AND STORED IN
 * {@code config/buttcraft.properties}. CHANGES APPLY IMMEDIATELY.
 *
 * <ul>
 *   <li>RESOLUTION DIVISOR: THE RAY MARCH RUNS AT 1/N OF THE SCREEN RESOLUTION PER AXIS.</li>
 *   <li>MAX VIEW STEPS / SHADOW STEPS: SAMPLES ALONG THE VIEW RAY AND TOWARD THE LIGHT.</li>
 *   <li>PUFF BUDGET: BEYOND IT, THE MOST-OVERLAPPING PUFFS MERGE INTO BIGGER ONES. EVERY
 *   SAMPLE LOOPS OVER THE PUFFS ON ITS RAY, SO FEWER PUFFS ARE DIRECTLY CHEAPER.</li>
 * </ul>
 */
public final class FartGasSettings {

    public static final int[] RESOLUTION_DIVISORS = { 1, 2, 4 };
    public static final int MIN_VIEW_STEPS = 16;
    public static final int MAX_VIEW_STEPS = 128;
    public static final int MIN_SHADOW_STEPS = 1;
    /** THE SHADER'S SHADOW LOOP STOPS AT 8. */
    public static final int MAX_SHADOW_STEPS = 8;
    public static final int MIN_PUFF_BUDGET = 8;
    public static final int MAX_PUFF_BUDGET = FartGasSimulation.MAX_PUFFS;

    private static final String FILE_NAME = ButtCraft.MOD_ID + ".properties";
    private static final String PRESET_KEY = "gas_quality";
    private static final String RESOLUTION_KEY = "resolution_divisor";
    private static final String VIEW_STEPS_KEY = "max_view_steps";
    private static final String SHADOW_STEPS_KEY = "shadow_steps";
    private static final String PUFF_BUDGET_KEY = "puff_budget";

    public record Values(int resolutionDivisor, int maxViewSteps, int shadowSteps, int puffBudget) {
        public Values {
            resolutionDivisor = nearestDivisor(resolutionDivisor);
            maxViewSteps = Mth.clamp(maxViewSteps, MIN_VIEW_STEPS, MAX_VIEW_STEPS);
            shadowSteps = Mth.clamp(shadowSteps, MIN_SHADOW_STEPS, MAX_SHADOW_STEPS);
            puffBudget = Mth.clamp(puffBudget, MIN_PUFF_BUDGET, MAX_PUFF_BUDGET);
        }

        public Values withResolutionDivisor(int value) {
            return new Values(value, maxViewSteps, shadowSteps, puffBudget);
        }

        public Values withMaxViewSteps(int value) {
            return new Values(resolutionDivisor, value, shadowSteps, puffBudget);
        }

        public Values withShadowSteps(int value) {
            return new Values(resolutionDivisor, maxViewSteps, value, puffBudget);
        }

        public Values withPuffBudget(int value) {
            return new Values(resolutionDivisor, maxViewSteps, shadowSteps, value);
        }
    }

    private static volatile Values current = FartGasQuality.DEFAULT.values;

    private FartGasSettings() {
    }

    public static Values current() {
        return current;
    }

    public static FartGasQuality preset() {
        return FartGasQuality.matching(current);
    }

    /** APPLIES NEW VALUES IMMEDIATELY AND SAVES THEM. */
    public static void set(Values values) {
        if (values.equals(current)) {
            return;
        }

        current = values;
        save(values);
        ButtCraft.LOGGER.info("FART GAS SETTINGS: {} ({}).", values, preset());
    }

    /**
     * READS THE SAVED SETTINGS, WRITING THE DEFAULTS IF THE FILE DOESN'T EXIST YET. MISSING
     * VALUES FALL BACK TO THE SAVED PRESET (OR THE DEFAULT PRESET).
     */
    public static void load() {
        Path path = path();
        if (!Files.exists(path)) {
            current = FartGasQuality.DEFAULT.values;
            save(current);
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            ButtCraft.LOGGER.warn("COULD NOT READ {}, USING DEFAULT GAS SETTINGS.", path, e);
            current = FartGasQuality.DEFAULT.values;
            return;
        }

        Values base = FartGasQuality.DEFAULT.values;
        String presetName = properties.getProperty(PRESET_KEY, "").trim().toUpperCase(Locale.ROOT);
        try {
            FartGasQuality preset = FartGasQuality.valueOf(presetName);
            if (preset.values != null) {
                base = preset.values;
            }
        } catch (IllegalArgumentException ignored) {
            // CUSTOM OR UNKNOWN: THE INDIVIDUAL VALUES BELOW DECIDE.
        }

        current = new Values(
            readInt(properties, RESOLUTION_KEY, base.resolutionDivisor()),
            readInt(properties, VIEW_STEPS_KEY, base.maxViewSteps()),
            readInt(properties, SHADOW_STEPS_KEY, base.shadowSteps()),
            readInt(properties, PUFF_BUDGET_KEY, base.puffBudget()));
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            ButtCraft.LOGGER.warn("BAD {} '{}' IN {}, USING {}.", key, value, FILE_NAME, fallback);
            return fallback;
        }
    }

    private static void save(Values values) {
        Path path = path();
        Properties properties = new Properties();
        properties.setProperty(PRESET_KEY, FartGasQuality.matching(values).name().toLowerCase(Locale.ROOT));
        properties.setProperty(RESOLUTION_KEY, Integer.toString(values.resolutionDivisor()));
        properties.setProperty(VIEW_STEPS_KEY, Integer.toString(values.maxViewSteps()));
        properties.setProperty(SHADOW_STEPS_KEY, Integer.toString(values.shadowSteps()));
        properties.setProperty(PUFF_BUDGET_KEY, Integer.toString(values.puffBudget()));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "ButtCraft gas settings (also in Video Settings)");
            }
        } catch (IOException e) {
            ButtCraft.LOGGER.warn("COULD NOT WRITE {}.", path, e);
        }
    }

    /** ONLY USES CONSTANTS: PRESETS BUILD VALUES BEFORE THIS CLASS IS INITIALISED. */
    private static int nearestDivisor(int divisor) {
        return divisor >= 3 ? 4 : divisor >= 2 ? 2 : 1;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
