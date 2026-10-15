package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.ModEffects;
import com.github.sethcg.buttcraft.potion.FartPotions;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AN ARROW OF FARTS GIVES STENCH I TO WHATEVER IT HITS, INSTEAD OF THE BOTTLE'S STENCH II.
 */
@Mixin(Arrow.class)
public abstract class ArrowMixin {

    @Inject(method = "doPostHurtEffects", at = @At("HEAD"), cancellable = true)
    private void buttcraftPotionAddon$stenchOnHit(LivingEntity target, CallbackInfo ci) {
        Arrow arrow = (Arrow) (Object) this;
        if (!FartPotions.isFart(arrow.getPickupItemStackOrigin())) {
            return;
        }

        // UNDEAD DON'T BREATHE, SO THEY CAN'T SMELL IT
        if (ModEffects.canSmell(target)) {
            target.addEffect(
                new MobEffectInstance(ModEffects.STENCH, FartPotions.ARROW_STENCH_TICKS, 0),
                arrow.getEffectSource()
            );
        }

        // SKIP THE VANILLA TIPPED ARROW EFFECT
        ci.cancel();
    }
}
