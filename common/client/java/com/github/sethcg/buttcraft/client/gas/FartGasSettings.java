package com.github.sethcg.buttcraft.client.gas;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;

import com.github.sethcg.buttcraft.ButtCraft;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * LIVE GAS RENDERING SETTINGS. EDITED IN VIDEO SETTINGS (SEE
 * {@link com.github.sethcg.buttcraft.client.options.ButtCraftOptions}) AND STORED IN
 * {@code config/buttcraft.properties}. CHANGES APPLY IMMEDIATELY.
 *
 * <p>HOW MUCH GAS:
 * <ul>
 *   <li>MAX PUFFS: BEYOND IT, THE MOST-OVERLAPPING PUFFS MERGE INTO BIGGER ONES. A FART
 *   RELEASES {@link FartGasSimulation#PUFFS_PER_FART} PUFFS. EVERY PIXEL TESTS EVERY PUFF,
 *   AND EVERY SAMPLE LOOPS OVER THE PUFFS ON ITS RAY, SO FEWER PUFFS ARE CHEAPER.</li>
 * </ul>
 *
 * <p>HOW IT LOOKS:
 * <ul>
 *   <li>RESOLUTION DIVISOR: THE RAY MARCH RUNS AT 1/N OF THE SCREEN RESOLUTION PER AXIS.</li>
 *   <li>MAX VIEW STEPS / SHADOW STEPS: SAMPLES ALONG THE VIEW RAY AND TOWARD THE LIGHT.</li>
 *   <li>TEMPORAL SMOOTHING: BLENDS EACH FRAME'S MARCH WITH THE LAST FEW, SO THE SAME STEP
 *   COUNTS LOOK MUCH LESS GRAINY. COSTS ONE SMALL EXTRA PASS.</li>
 * </ul>
 */
public final class FartGasSettings {

    private static final Logger LOGGER = ButtCraft.logger("gas");

    public static final int[] RESOLUTION_DIVISORS = { 1, 2, 4 };
    public static final int MIN_VIEW_STEPS = 16;
    public static final int MAX_VIEW_STEPS = 128;
    public static final int MIN_SHADOW_STEPS = 1;
    /** THE SHADER'S SHADOW LOOP STOPS AT 8. */
    public static final int MAX_SHADOW_STEPS = 8;
    public static final int MIN_MAX_PUFFS = 8;
    /** THE SHADER'S PUFF ARRAY HOLDS {@link FartGasSimulation#MAX_PUFFS}. */
    public static final int MAX_MAX_PUFFS = FartGasSimulation.MAX_PUFFS;

    private static final String FILE_NAME = ButtCraft.MOD_ID + ".properties";
    private static final String PRESET_KEY = "gas_preset";
    private static final String MAX_PUFFS_KEY = "max_puffs";
    private static final String RESOLUTION_KEY = "resolution_divisor";
    private static final String VIEW_STEPS_KEY = "max_view_steps";
    private static final String SHADOW_STEPS_KEY = "shadow_steps";
    private static final String TEMPORAL_SMOOTHING_KEY = "temporal_smoothing";
    // FROM BEFORE THE LOW / MEDIUM / HIGH PRESETS WERE REPLACED BY DEFAULT / CUSTOM
    private static final String LEGACY_QUALITY_KEY = "gas_quality";
    private static final String LEGACY_PUFF_BUDGET_KEY = "puff_budget";

    public record Values(int maxPuffs, int resolutionDivisor, int maxViewSteps, int shadowSteps, boolean temporalSmoothing) {
        public Values {
            maxPuffs = Mth.clamp(maxPuffs, MIN_MAX_PUFFS, MAX_MAX_PUFFS);
            resolutionDivisor = nearestDivisor(resolutionDivisor);
            maxViewSteps = Mth.clamp(maxViewSteps, MIN_VIEW_STEPS, MAX_VIEW_STEPS);
            shadowSteps = Mth.clamp(shadowSteps, MIN_SHADOW_STEPS, MAX_SHADOW_STEPS);
        }

        public Values withMaxPuffs(int value) {
            return new Values(value, resolutionDivisor, maxViewSteps, shadowSteps, temporalSmoothing);
        }

        public Values withResolutionDivisor(int value) {
            return new Values(maxPuffs, value, maxViewSteps, shadowSteps, temporalSmoothing);
        }

        public Values withMaxViewSteps(int value) {
            return new Values(maxPuffs, resolutionDivisor, value, shadowSteps, temporalSmoothing);
        }

        public Values withShadowSteps(int value) {
            return new Values(maxPuffs, resolutionDivisor, maxViewSteps, value, temporalSmoothing);
        }

        public Values withTemporalSmoothing(boolean value) {
            return new Values(maxPuffs, resolutionDivisor, maxViewSteps, shadowSteps, value);
        }

        /** ONLY USES CONSTANTS, SO BUILDING {@link #DEFAULTS} DOESN'T DEPEND ON CLASS LOAD ORDER. */
        private static int nearestDivisor(int divisor) {
            return divisor >= 3 ? 4 : divisor >= 2 ? 2 : 1;
        }
    }

    /**
     * THE OLD LOW PRESET (QUARTER RESOLUTION, 2 SHADOW STEPS, SMOOTHING) WITH 20 VIEW STEPS, NOT 24,
     * AND THE MOST PUFFS THE SHADER HOLDS: 20 FARTS NEED ABOUT 140 AND 30 ABOUT 200, SO NOTHING MERGES
     * UNTIL ABOUT 35 FARTS ARE IN THE AIR AT ONCE.
     */
    public static final Values DEFAULTS = new Values(MAX_MAX_PUFFS, 4, 20, 2, true);

    private static volatile Values current = DEFAULTS;

    private FartGasSettings() {
    }

    public static Values current() {
        return current;
    }

    public static FartGasPreset preset() {
        return FartGasPreset.matching(current);
    }

    /** APPLIES NEW VALUES IMMEDIATELY AND SAVES THEM. */
    public static void set(Values values) {
        if (values.equals(current)) {
            return;
        }

        current = values;
        save(values);
        LOGGER.info("FART GAS SETTINGS: {} ({}).", values, preset());
    }

    /**
     * READS THE SAVED SETTINGS, WRITING THE DEFAULTS IF THE FILE DOESN'T EXIST YET. A SAVED
     * DEFAULT PRESET ALWAYS LOADS THE CURRENT DEFAULTS, SO CHANGING THEM REACHES EVERYONE WHO
     * HASN'T CUSTOMISED ANYTHING. MISSING CUSTOM VALUES FALL BACK TO THE DEFAULTS.
     */
    public static void load() {
        Path path = path();
        if (!Files.exists(path)) {
            current = DEFAULTS;
            save(current);
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            LOGGER.warn("CAN'T READ {}, USING DEFAULTS.", path, e);
            current = DEFAULTS;
            return;
        }

        if (isDefaultPreset(properties)) {
            current = DEFAULTS;
            return;
        }

        String maxPuffsKey = properties.getProperty(MAX_PUFFS_KEY) != null ? MAX_PUFFS_KEY : LEGACY_PUFF_BUDGET_KEY;
        current = new Values(
            readInt(properties, maxPuffsKey, DEFAULTS.maxPuffs()),
            readInt(properties, RESOLUTION_KEY, DEFAULTS.resolutionDivisor()),
            readInt(properties, VIEW_STEPS_KEY, DEFAULTS.maxViewSteps()),
            readInt(properties, SHADOW_STEPS_KEY, DEFAULTS.shadowSteps()),
            readBoolean(properties, TEMPORAL_SMOOTHING_KEY, DEFAULTS.temporalSmoothing()));
    }

    /**
     * TRUE FOR A SAVED DEFAULT PRESET, OR AN OLD FILE ON THE LOW PRESET (THE OLD DEFAULT,
     * WHICH TODAY'S DEFAULTS REPLACE). OLD MEDIUM / HIGH FILES KEEP THEIR VALUES AS CUSTOM.
     */
    private static boolean isDefaultPreset(Properties properties) {
        String preset = properties.getProperty(PRESET_KEY);
        if (preset != null) {
            return preset.trim().equalsIgnoreCase(FartGasPreset.DEFAULT.name());
        }

        return properties.getProperty(LEGACY_QUALITY_KEY, "").trim().equalsIgnoreCase("low");
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("BAD SETTING FORMAT: {}={} IN {}, USING {}.", key, value, FILE_NAME, fallback);
            return fallback;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }

        // Boolean.parseBoolean WOULD QUIETLY READ A TYPO AS false.
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }

        LOGGER.warn("BAD SETTING FORMAT: {}={} IN {}, USING {}.", key, value, FILE_NAME, fallback);
        return fallback;
    }

    private static void save(Values values) {
        Path path = path();
        Properties properties = new Properties();
        properties.setProperty(PRESET_KEY, FartGasPreset.matching(values).name().toLowerCase(Locale.ROOT));
        properties.setProperty(MAX_PUFFS_KEY, Integer.toString(values.maxPuffs()));
        properties.setProperty(RESOLUTION_KEY, Integer.toString(values.resolutionDivisor()));
        properties.setProperty(VIEW_STEPS_KEY, Integer.toString(values.maxViewSteps()));
        properties.setProperty(SHADOW_STEPS_KEY, Integer.toString(values.shadowSteps()));
        properties.setProperty(TEMPORAL_SMOOTHING_KEY, Boolean.toString(values.temporalSmoothing()));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "ButtCraft gas settings (also in Video Settings)");
            }
        } catch (IOException e) {
            LOGGER.warn("CAN'T SAVE {}, CHANGES LAST UNTIL RESTART.", path, e);
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
