package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.ButtCraft;
import com.github.sethcg.buttcraft.potion.FartPotions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AN ARROW OF FARTS LEAVES A SMALL FART CLOUD WHERE IT HITS.
 *
 * <p>THE HIT METHODS LIVE ON AbstractArrow (Arrow DOESN'T OVERRIDE THEM), SO THIS TARGETS
 * IT AND ONLY ACTS ON TIPPED ARROWS.
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {

    // ONE CLOUD PER ARROW, EVEN IF IT BOUNCES OFF SOMETHING OR PIERCES THROUGH A FEW MOBS
    @Unique
    private boolean buttcraftPotionAddon$farted;

    @Inject(method = "onHitEntity", at = @At("TAIL"))
    private void buttcraftPotionAddon$fartOnEntityHit(EntityHitResult hit, CallbackInfo ci) {
        this.buttcraftPotionAddon$fart(hit);
    }

    @Inject(method = "onHitBlock", at = @At("TAIL"))
    private void buttcraftPotionAddon$fartOnBlockHit(BlockHitResult hit, CallbackInfo ci) {
        this.buttcraftPotionAddon$fart(hit);
    }

    @Unique
    private void buttcraftPotionAddon$fart(HitResult hit) {
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (this.buttcraftPotionAddon$farted
            || !(arrow instanceof Arrow)
            || !(arrow.level() instanceof ServerLevel level)
            || !FartPotions.isFart(arrow.getPickupItemStackOrigin())) {
            return;
        }

        this.buttcraftPotionAddon$farted = true;

        // CREDIT WHOEVER SHOT IT
        ButtCraft.fartBurst(
            level,
            FartPotions.impactPosition(hit),
            arrow.getOwner(),
            FartPotions.ARROW_CLOUD_TICKS,
            FartPotions.ARROW_CLOUD_SIZE
        );
    }
}
