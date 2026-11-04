package com.github.sethcg.buttcraft.potion;

import com.github.sethcg.buttcraft.FartClouds;
import com.github.sethcg.buttcraft.ModEffects;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

public final class FartPotions {

    // THROWN FARTS DON'T USE THIS, THEY GIVE THE STENCH I OF THEIR ThrownFart INSTEAD
    private static final int STENCH_DURATION_TICKS = 600;
    private static final int STENCH_AMPLIFIER = 1;

    // USED FOR BOTH THE CLOUD AND THE TOOLTIP, SO THEY MATCH
    public record ThrownFart(int cloudTicks, int stenchTicks) {
        public MobEffectInstance stench() {
            return new MobEffectInstance(ModEffects.STENCH, this.stenchTicks, 0);
        }
    }

    private static final ThrownFart SPLASH = new ThrownFart(FartClouds.CLOUD_LIFETIME_TICKS, FartClouds.STENCH_DURATION_TICKS);

    private static final ThrownFart LINGERING = new ThrownFart(360, 360);

    private static final double SURFACE_OFFSET = 0.3;

    public static final ResourceKey<Potion> FART_KEY = ResourceKey.create(
        Registries.POTION,
        ButtCraftPotionAddon.id("fart")
    );

    public static final Holder<Potion> FART = Registry.registerForHolder(
        BuiltInRegistries.POTION,
        FART_KEY,
        new Potion("fart", new MobEffectInstance(ModEffects.STENCH, STENCH_DURATION_TICKS, STENCH_AMPLIFIER))
    );

    private FartPotions() {
    }

    public static void initialize() {
        // THERE ARE NO ARROWS OF FARTS, BUT VANILLA LISTS A TIPPED ARROW FOR EVERY POTION
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> {
            output.getDisplayStacks().removeIf(FartPotions::isFartArrow);
            output.getSearchTabStacks().removeIf(FartPotions::isFartArrow);
        });
    }

    private static boolean isFartArrow(ItemStack stack) {
        return stack.is(Items.TIPPED_ARROW) && isFart(stack);
    }

    public static ItemStack createBottle() {
        return PotionContents.createItemStack(Items.POTION, FART);
    }

    public static boolean isFart(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(FART);
    }

    // NULL FOR A DRINKABLE FART, WHICH GIVES THE POTION'S OWN STENCH II, OR FOR ANYTHING ELSE
    public static @Nullable ThrownFart thrown(ItemStack stack) {
        if (!isFart(stack)) {
            return null;
        }
        if (stack.is(Items.SPLASH_POTION)) {
            return SPLASH;
        }
        if (stack.is(Items.LINGERING_POTION)) {
            return LINGERING;
        }

        return null;
    }

    public static Vec3 impactPosition(HitResult hit) {
        Vec3 pos = hit.getLocation();
        if (hit instanceof BlockHitResult blockHit) {
            pos = pos.add(blockHit.getDirection().getUnitVec3().scale(SURFACE_OFFSET));
        }

        return pos;
    }
}
