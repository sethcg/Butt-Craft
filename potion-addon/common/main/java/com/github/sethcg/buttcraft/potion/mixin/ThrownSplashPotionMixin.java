package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.ButtCraft;
import com.github.sethcg.buttcraft.potion.FartPotions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A SPLASH FART RELEASES A FART CLOUD WHERE IT BREAKS.
 */
@Mixin(ThrownSplashPotion.class)
public abstract class ThrownSplashPotionMixin {

    @Inject(method = "onHitAsPotion", at = @At("TAIL"))
    private void buttcraftPotionAddon$fartOnImpact(ServerLevel level, ItemStack stack, HitResult hit, CallbackInfo ci) {
        if (!FartPotions.isFart(stack)) {
            return;
        }

        // CREDIT WHOEVER THREW IT
        ThrownSplashPotion potion = (ThrownSplashPotion) (Object) this;
        ButtCraft.fartBurst(level, FartPotions.impactPosition(hit), potion.getOwner());
    }
}
