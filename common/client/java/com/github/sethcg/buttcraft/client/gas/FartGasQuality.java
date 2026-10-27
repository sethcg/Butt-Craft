package com.github.sethcg.buttcraft.client.gas;

import java.util.Locale;

/**
 * GAS QUALITY PRESETS. PICKING ONE FILLS IN ALL {@link FartGasSettings.Values}; CHANGING ANY
 * SINGLE VALUE AFTERWARDS SHOWS {@link #CUSTOM} UNLESS THE RESULT MATCHES A PRESET AGAIN.
 */
public enum FartGasQuality {
    LOW(new FartGasSettings.Values(4, 24, 2, 32, true)),
    MEDIUM(new FartGasSettings.Values(2, 32, 3, 48, true)),
    HIGH(new FartGasSettings.Values(2, 48, 4, FartGasSimulation.MAX_PUFFS, true)),
    CUSTOM(null);

    public static final FartGasQuality DEFAULT = LOW;

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
