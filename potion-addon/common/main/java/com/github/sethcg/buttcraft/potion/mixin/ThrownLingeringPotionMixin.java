package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.ButtCraft;
import com.github.sethcg.buttcraft.potion.FartPotions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A LINGERING FART RELEASES A LONGER-LASTING FART CLOUD WHERE IT BREAKS, INSTEAD OF THE
 * USUAL POTION CLOUD.
 */
@Mixin(ThrownLingeringPotion.class)
public abstract class ThrownLingeringPotionMixin {

    @Inject(method = "onHitAsPotion", at = @At("HEAD"), cancellable = true)
    private void buttcraftPotionAddon$fartOnImpact(ServerLevel level, ItemStack stack, HitResult hit, CallbackInfo ci) {
        if (!FartPotions.isFart(stack)) {
            return;
        }

        // CREDIT WHOEVER THREW IT
        ThrownLingeringPotion potion = (ThrownLingeringPotion) (Object) this;
        ButtCraft.fartBurst(
            level,
            FartPotions.impactPosition(hit),
            potion.getOwner(),
            FartPotions.LINGERING_CLOUD_TICKS,
            1.0F
        );

        // SKIP THE VANILLA POTION CLOUD, THE FART CLOUD DOES THE POISONING
        ci.cancel();
    }
}
