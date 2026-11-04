package com.github.sethcg.buttcraft;

import com.github.sethcg.buttcraft.effect.StenchMobEffect;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

public final class ModEffects {

    private static final int STENCH_COLOR = 0x7A8C2E;

    public static final Holder<MobEffect> STENCH = Registry.registerForHolder(
        BuiltInRegistries.MOB_EFFECT,
        ButtCraft.id("stench"),
        new StenchMobEffect(MobEffectCategory.HARMFUL, STENCH_COLOR)
    );

    private ModEffects() {
    }

    public static void initialize() {
    }

    // MOBS THAT IGNORE POISON ALSO IGNORE STENCH
    public static boolean canSmell(LivingEntity entity) {
        return !entity.is(EntityTypeTags.IGNORES_POISON_AND_REGEN);
    }
}
