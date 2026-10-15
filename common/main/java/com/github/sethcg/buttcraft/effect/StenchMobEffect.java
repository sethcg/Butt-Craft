package com.github.sethcg.buttcraft.effect;

import com.github.sethcg.buttcraft.FartGasDamageSource;
import com.github.sethcg.buttcraft.ModEffects;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * STENCH: LIKE POISON, HURTS EVERY 25 TICKS (STENCH II EVERY 12), BUT UNLIKE POISON IT
 * CAN KILL. DEATHS USE THE FART GAS DEATH MESSAGES.
 */
public class StenchMobEffect extends MobEffect {

    // TICKS BETWEEN HITS AT STENCH I, HALVED FOR EACH LEVEL ABOVE THAT (20 TICKS = 1 SECOND)
    private static final int DAMAGE_INTERVAL = 25;

    // DAMAGE PER HIT (2.0 = 1 HEART)
    private static final float DAMAGE = 1.0F;

    public StenchMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        if (ModEffects.canSmell(entity)) {
            entity.hurtServer(level, FartGasDamageSource.create(level, null), DAMAGE);
        }

        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int remainingDuration, int amplifier) {
        int interval = DAMAGE_INTERVAL >> amplifier;
        return interval <= 0 || remainingDuration % interval == 0;
    }
}
