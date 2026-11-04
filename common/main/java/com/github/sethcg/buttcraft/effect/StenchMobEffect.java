package com.github.sethcg.buttcraft.effect;

import com.github.sethcg.buttcraft.FartGasDamageSource;
import com.github.sethcg.buttcraft.ModEffects;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

public class StenchMobEffect extends MobEffect {

    // TICKS BETWEEN HITS AT STENCH I, HALVED FOR EACH LEVEL ABOVE THAT
    private static final int DAMAGE_INTERVAL = 25;
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
