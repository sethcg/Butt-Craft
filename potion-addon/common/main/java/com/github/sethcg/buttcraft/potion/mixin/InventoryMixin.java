package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.potion.FartPotions;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// THE RECIPE BOOK ONLY LOOKS AT THE ITEM, NOT THE POTION, SO IT WOULD PULL A LINGERING FART INTO
// THE TIPPED ARROW RECIPE. THIS IS THE CHECK THAT KEEPS DAMAGED, ENCHANTED AND RENAMED ITEMS OUT
@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @Inject(method = "isUsableForCrafting", at = @At("HEAD"), cancellable = true)
    private static void buttcraftPotionAddon$skipLingeringFart(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.is(Items.LINGERING_POTION) && FartPotions.isFart(stack)) {
            cir.setReturnValue(false);
        }
    }
}
