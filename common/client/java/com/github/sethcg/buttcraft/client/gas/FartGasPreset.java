package com.github.sethcg.buttcraft.client.gas;

import java.util.Locale;

/**
 * WHETHER THE GAS SETTINGS ARE THE DEFAULTS. PICKING {@link #DEFAULT} RESETS EVERY
 * {@link FartGasSettings.Values}; CHANGING ANY SINGLE VALUE SHOWS {@link #CUSTOM} UNLESS THE
 * RESULT MATCHES THE DEFAULTS AGAIN.
 */
public enum FartGasPreset {
    DEFAULT,
    CUSTOM;

    public String translationKey() {
        return "options.buttcraft.gas_preset." + this.name().toLowerCase(Locale.ROOT);
    }

    public static FartGasPreset matching(FartGasSettings.Values values) {
        return values.equals(FartGasSettings.DEFAULTS) ? DEFAULT : CUSTOM;
    }
}
