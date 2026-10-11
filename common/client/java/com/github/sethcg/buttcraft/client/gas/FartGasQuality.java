package com.github.sethcg.buttcraft.client.gas;

import java.util.Locale;

/**
 * GAS QUALITY PRESETS. PICKING ONE FILLS IN ALL {@link FartGasSettings.Values}; CHANGING ANY
 * SINGLE VALUE AFTERWARDS SHOWS {@link #CUSTOM} UNLESS THE RESULT MATCHES A PRESET AGAIN.
 */
public enum FartGasQuality {
    LOW(new FartGasSettings.Values(4, 32, 2, 32)),
    MEDIUM(new FartGasSettings.Values(2, 48, 3, 48)),
    HIGH(new FartGasSettings.Values(2, 64, 4, FartGasSimulation.MAX_PUFFS)),
    CUSTOM(null);

    public static final FartGasQuality DEFAULT = MEDIUM;

    /** NULL FOR {@link #CUSTOM}. */
    public final FartGasSettings.Values values;

    FartGasQuality(FartGasSettings.Values values) {
        this.values = values;
    }

    public String translationKey() {
        return "options.buttcraft.gas_quality." + this.name().toLowerCase(Locale.ROOT);
    }

    /** THE PRESET WITH EXACTLY THESE VALUES, OR {@link #CUSTOM}. */
    public static FartGasQuality matching(FartGasSettings.Values values) {
        for (FartGasQuality quality : values()) {
            if (values.equals(quality.values)) {
                return quality;
            }
        }
        return CUSTOM;
    }
}
