package com.github.sethcg.buttcraft.client.options;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import com.github.sethcg.buttcraft.client.gas.FartGasPreset;
import com.github.sethcg.buttcraft.client.gas.FartGasSettings;
import com.mojang.serialization.Codec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * BUTT-CRAFT ENTRIES FOR VANILLA'S VIDEO SETTINGS SCREEN (ADDED BY
 * {@code VideoSettingsScreenMixin}). VALUES ARE STORED IN THE MOD'S OWN CONFIG FILE,
 * NOT {@code options.txt}.
 *
 * <p>BUILT FRESH FOR EACH SCREEN SO THEY ALWAYS START FROM THE SAVED VALUES. PICKING DEFAULT
 * RESETS THE FIVE DETAIL OPTIONS; MOVING A DETAIL OPTION SWITCHES THE PRESET TO CUSTOM (OR
 * BACK TO DEFAULT IF EVERYTHING MATCHES AGAIN), LIKE VANILLA'S GRAPHICS PRESET.
 *
 * <p>THE DETAILS GO HOW MUCH GAS FIRST (MAX PUFFS), THEN HOW IT LOOKS, CHEAPEST TO TRADE FIRST.
 */
public final class ButtCraftOptions {

    public static final Component HEADER = Component.translatable("options.buttcraft.header");

    private static final Codec<FartGasPreset> PRESET_CODEC = Codec.STRING.xmap(
        value -> FartGasPreset.valueOf(value.toUpperCase(Locale.ROOT)),
        preset -> preset.name().toLowerCase(Locale.ROOT));

    public final OptionInstance<FartGasPreset> preset;
    public final OptionInstance<Integer> maxPuffs;
    public final OptionInstance<Integer> resolution;
    public final OptionInstance<Integer> viewSteps;
    public final OptionInstance<Integer> shadowSteps;
    public final OptionInstance<Boolean> smoothing;

    private boolean applyingPreset;

    public ButtCraftOptions() {
        FartGasSettings.Values values = FartGasSettings.current();

        this.preset = new OptionInstance<>(
            "options.buttcraft.gas_preset",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.buttcraft.gas_preset.tooltip")),
            (caption, preset) -> Component.translatable(preset.translationKey()),
            new OptionInstance.Enum<>(List.of(FartGasPreset.values()), PRESET_CODEC),
            FartGasSettings.preset(),
            this::applyPreset);

        this.maxPuffs = slider("options.buttcraft.gas_puffs",
            FartGasSettings.MIN_MAX_PUFFS, FartGasSettings.MAX_MAX_PUFFS, values.maxPuffs(),
            puffs -> this.update(settings -> settings.withMaxPuffs(puffs)));

        this.resolution = new OptionInstance<>(
            "options.buttcraft.gas_resolution",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.buttcraft.gas_resolution.tooltip")),
            (caption, divisor) -> Component.translatable("options.buttcraft.gas_resolution." + divisor),
            new OptionInstance.Enum<>(Arrays.stream(FartGasSettings.RESOLUTION_DIVISORS).boxed().toList(), Codec.INT),
            values.resolutionDivisor(),
            divisor -> this.update(settings -> settings.withResolutionDivisor(divisor)));

        this.viewSteps = slider("options.buttcraft.gas_detail",
            FartGasSettings.MIN_VIEW_STEPS, FartGasSettings.MAX_VIEW_STEPS, values.maxViewSteps(),
            steps -> this.update(settings -> settings.withMaxViewSteps(steps)));

        this.shadowSteps = slider("options.buttcraft.gas_shading",
            FartGasSettings.MIN_SHADOW_STEPS, FartGasSettings.MAX_SHADOW_STEPS, values.shadowSteps(),
            steps -> this.update(settings -> settings.withShadowSteps(steps)));

        this.smoothing = OptionInstance.createBoolean(
            "options.buttcraft.gas_smoothing",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.buttcraft.gas_smoothing.tooltip")),
            values.temporalSmoothing(),
            smooth -> this.update(settings -> settings.withTemporalSmoothing(smooth)));
    }

    public List<OptionInstance<?>> details() {
        return List.of(this.maxPuffs, this.resolution, this.viewSteps, this.shadowSteps, this.smoothing);
    }

    private static OptionInstance<Integer> slider(String key, int min, int max, int initial,
                                                  OptionInstance.ValueUpdateListener<Integer> onChange) {
        // APPLIED ON RELEASE, SO DRAGGING DOESN'T REWRITE THE CONFIG OR MERGE PUFFS EVERY STEP.
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            Options::genericValueLabel,
            new OptionInstance.IntRange(min, max, false),
            initial,
            onChange);
    }

    private void applyPreset(FartGasPreset preset) {
        if (preset != FartGasPreset.DEFAULT || this.applyingPreset) {
            return; // CUSTOM KEEPS WHATEVER IS SET.
        }

        FartGasSettings.Values defaults = FartGasSettings.DEFAULTS;
        this.applyingPreset = true;
        try {
            FartGasSettings.set(defaults);
            OptionsSubScreen screen = currentScreen();
            sync(screen, this.maxPuffs, defaults.maxPuffs());
            sync(screen, this.resolution, defaults.resolutionDivisor());
            sync(screen, this.viewSteps, defaults.maxViewSteps());
            sync(screen, this.shadowSteps, defaults.shadowSteps());
            sync(screen, this.smoothing, defaults.temporalSmoothing());
        } finally {
            this.applyingPreset = false;
        }
    }

    private void update(UnaryOperator<FartGasSettings.Values> change) {
        if (this.applyingPreset) {
            return;
        }

        FartGasSettings.set(change.apply(FartGasSettings.current()));
        this.applyingPreset = true;
        try {
            sync(currentScreen(), this.preset, FartGasSettings.preset());
        } finally {
            this.applyingPreset = false;
        }
    }

    private static <T> void sync(OptionsSubScreen screen, OptionInstance<T> option, T value) {
        if (!value.equals(option.get())) {
            option.set(value);
            if (screen != null) {
                screen.resetOption(option);
            }
        }
    }

    private static OptionsSubScreen currentScreen() {
        return Minecraft.getInstance().gui.screen() instanceof OptionsSubScreen screen ? screen : null;
    }
}
