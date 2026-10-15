package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.potion.FartPotions;

import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ImbueRecipe;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THERE ARE NO ARROWS OF FARTS, SO A LINGERING FART CAN'T BE CRAFTED INTO TIPPED ARROWS.
 *
 * <p>THE VANILLA TIPPED ARROW RECIPE TAKES ANY LINGERING POTION IN THE MIDDLE SLOT.
 */
@Mixin(ImbueRecipe.class)
public abstract class ImbueRecipeMixin {

    @Inject(
        method = "matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
        at = @At("RETURN"),
        cancellable = true
    )
    private void buttcraftPotionAddon$noFartArrows(CraftingInput input, Level level, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && FartPotions.isFart(input.getItem(1, 1))) {
            cir.setReturnValue(false);
        }
    }
}
