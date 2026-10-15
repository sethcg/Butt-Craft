package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.potion.FartPotions;

import java.util.List;
import java.util.stream.Stream;

import net.minecraft.core.Holder;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.crafting.display.DisplayContentsFactory;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THERE ARE NO ARROWS OF FARTS, SO THE RECIPE BOOK DOESN'T SHOW ONE.
 *
 * <p>THE TIPPED ARROW RECIPE CYCLES THROUGH EVERY POTION, SO THIS SKIPS THE FART IN THE
 * LAMBDA THAT TURNS EACH POTION INTO ITS STACKS.
 */
@Mixin(SlotDisplay.WithAnyPotion.class)
public abstract class WithAnyPotionMixin {

    @Inject(method = "lambda$resolve$1", at = @At("HEAD"), cancellable = true)
    private static void buttcraftPotionAddon$skipFart(
        List<?> stacks,
        DisplayContentsFactory.ForStacks<?> factory,
        Holder.Reference<Potion> potion,
        CallbackInfoReturnable<Stream<?>> cir
    ) {
        if (potion.is(FartPotions.FART_KEY)) {
            cir.setReturnValue(Stream.empty());
        }
    }
}
