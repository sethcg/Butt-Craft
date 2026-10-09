package com.github.sethcg.buttcraft.client.options;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import com.github.sethcg.buttcraft.client.gas.FartGasQuality;
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
 * <p>BUILT FRESH FOR EACH SCREEN SO THEY ALWAYS START FROM THE SAVED VALUES. PICKING A PRESET
 * MOVES THE FOUR DETAIL OPTIONS; MOVING A DETAIL OPTION SWITCHES THE PRESET TO WHICHEVER ONE
 * MATCHES (OR CUSTOM), LIKE VANILLA'S GRAPHICS PRESET.
 */
public final class ButtCraftOptions {

    public static final Component HEADER = Component.translatable("options.buttcraft.header");

    private static final Codec<FartGasQuality> QUALITY_CODEC = Codec.STRING.xmap(
        value -> FartGasQuality.valueOf(value.toUpperCase(Locale.ROOT)),
        quality -> quality.name().toLowerCase(Locale.ROOT));

    public final OptionInstance<FartGasQuality> quality;
    public final OptionInstance<Integer> resolution;
    public final OptionInstance<Integer> viewSteps;
    public final OptionInstance<Integer> shadowSteps;
    public final OptionInstance<Integer> puffBudget;

    private boolean applyingPreset;

    public ButtCraftOptions() {
        FartGasSettings.Values values = FartGasSettings.current();

        this.quality = new OptionInstance<>(
            "options.buttcraft.gas_quality",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.buttcraft.gas_quality.tooltip")),
            (caption, quality) -> Component.translatable(quality.translationKey()),
            new OptionInstance.Enum<>(List.copyOf(Arrays.asList(FartGasQuality.values())), QUALITY_CODEC),
            FartGasSettings.preset(),
            this::applyPreset);

        this.resolution = new OptionInstance<>(
            "options.buttcraft.gas_resolution",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.buttcraft.gas_resolution.tooltip")),
            (caption, divisor) -> Component.translatable("options.buttcraft.gas_resolution." + divisor),
            new OptionInstance.Enum<>(Arrays.stream(FartGasSettings.RESOLUTION_DIVISORS).boxed().toList(), Codec.INT),
            values.resolutionDivisor(),
            divisor -> this.update(v -> v.withResolutionDivisor(divisor)));

        this.viewSteps = slider("options.buttcraft.gas_detail",
            FartGasSettings.MIN_VIEW_STEPS, FartGasSettings.MAX_VIEW_STEPS, values.maxViewSteps(),
            steps -> this.update(v -> v.withMaxViewSteps(steps)));

        this.shadowSteps = slider("options.buttcraft.gas_shading",
            FartGasSettings.MIN_SHADOW_STEPS, FartGasSettings.MAX_SHADOW_STEPS, values.shadowSteps(),
            steps -> this.update(v -> v.withShadowSteps(steps)));

        this.puffBudget = slider("options.buttcraft.gas_puffs",
            FartGasSettings.MIN_PUFF_BUDGET, FartGasSettings.MAX_PUFF_BUDGET, values.puffBudget(),
            budget -> this.update(v -> v.withPuffBudget(budget)));
    }

    public List<OptionInstance<?>> details() {
        return List.of(this.resolution, this.viewSteps, this.shadowSteps, this.puffBudget);
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

    private void applyPreset(FartGasQuality preset) {
        if (preset.values == null || this.applyingPreset) {
            return; // CUSTOM KEEPS WHATEVER IS SET.
        }

        this.applyingPreset = true;
        try {
            FartGasSettings.set(preset.values);
            OptionsSubScreen screen = currentScreen();
            sync(screen, this.resolution, preset.values.resolutionDivisor());
            sync(screen, this.viewSteps, preset.values.maxViewSteps());
            sync(screen, this.shadowSteps, preset.values.shadowSteps());
            sync(screen, this.puffBudget, preset.values.puffBudget());
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
            sync(currentScreen(), this.quality, FartGasSettings.preset());
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
