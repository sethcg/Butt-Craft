package com.github.sethcg.buttcraft;

import com.github.sethcg.buttcraft.effect.StenchMobEffect;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * CUSTOM STATUS EFFECTS.
 *
 * <p>THE NAME COMES FROM THE LANG FILE (effect.buttcraft.stench), VANILLA ADDS THE "II"
 * FOR AMPLIFIER 1. THE ICON IS assets/buttcraft/textures/mob_effect/stench.png (18x18).
 */
public final class ModEffects {

    // SICKLY GREEN-BROWN (SAME FORMAT AS VANILLA, 0xRRGGBB)
    private static final int STENCH_COLOR = 0x7A8C2E;

    public static final Holder<MobEffect> STENCH = Registry.registerForHolder(
        BuiltInRegistries.MOB_EFFECT,
        ButtCraft.id("stench"),
        new StenchMobEffect(MobEffectCategory.HARMFUL, STENCH_COLOR)
    );

    private ModEffects() {
    }

    public static void initialize() {
        ButtCraft.LOGGER.info("REGISTERING BUTTCRAFT EFFECTS");
    }

    // MOBS THAT IGNORE POISON (UNDEAD) DON'T BREATHE, SO THEY CAN'T SMELL EITHER
    public static boolean canSmell(LivingEntity entity) {
        return !entity.is(EntityTypeTags.IGNORES_POISON_AND_REGEN);
    }
}
