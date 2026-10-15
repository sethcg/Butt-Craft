package com.github.sethcg.buttcraft.potion.mixin;

import com.github.sethcg.buttcraft.potion.FartPotions;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TooltipProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SPLASH AND LINGERING FARTS SHOW THE STENCH I THEY REALLY GIVE, INSTEAD OF THE
 * BOTTLE'S STENCH II.
 *
 * <p>THE POTION'S OWN TOOLTIP ONLY SEES THE COMPONENTS, NOT THE ITEM, SO THIS HOOKS IN
 * WHERE THE STACK HANDS THE POTION CONTENTS ITS TOOLTIP.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(
        method = "addToTooltip(Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/component/TooltipProvider$Getter;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private <T> void buttcraftPotionAddon$thrownStenchTooltip(
        DataComponentType<T> type,
        TooltipProvider.Getter<T> getter,
        Item.TooltipContext context,
        TooltipDisplay display,
        Consumer<Component> lines,
        TooltipFlag flag,
        CallbackInfo ci
    ) {
        ItemStack stack = (ItemStack) (Object) this;
        if (type != DataComponents.POTION_CONTENTS || !FartPotions.isFart(stack)) {
            return;
        }

        MobEffectInstance stench = FartPotions.thrownStench(stack);
        if (stench == null) {
            return;
        }

        // THE DURATION IS ALREADY THE REAL ONE, SO NO LINGERING DURATION SCALE
        if (display.shows(type)) {
            PotionContents.addPotionTooltip(List.of(stench), lines, 1.0F, context.tickRate());
        }

        ci.cancel();
    }
}
