package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.ButtCraft;
import com.github.sethcg.buttcraft.potion.FartPotions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A SPLASH OR LINGERING FART RELEASES A FART CLOUD WHERE IT BREAKS, INSTEAD OF SPLASHING THE
 * BOTTLE'S STENCH II ON EVERYONE NEARBY OR LEAVING THE USUAL POTION CLOUD. HOW LONG THE CLOUD
 * AND ITS STENCH LAST DEPENDS ON THE BOTTLE (SEE {@link FartPotions.ThrownFart}).
 */
@Mixin({ ThrownSplashPotion.class, ThrownLingeringPotion.class })
public abstract class ThrownFartPotionMixin {

    @Inject(method = "onHitAsPotion", at = @At("HEAD"), cancellable = true)
    private void buttcraftPotionAddon$fartOnImpact(ServerLevel level, ItemStack stack, HitResult hit, CallbackInfo ci) {
        FartPotions.ThrownFart thrown = FartPotions.thrown(stack);
        if (thrown == null) {
            return;
        }

        // CREDIT WHOEVER THREW IT
        Projectile potion = (Projectile) (Object) this;
        ButtCraft.fartBurst(
            level,
            FartPotions.impactPosition(hit),
            potion.getOwner(),
            thrown.cloudTicks(),
            thrown.stenchTicks()
        );

        // SKIP THE VANILLA SPLASH OR POTION CLOUD, THE FART CLOUD GIVES THE STENCH
        ci.cancel();
    }
}
