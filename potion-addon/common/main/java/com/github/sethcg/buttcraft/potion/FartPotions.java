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

/**
 * THE BOTTLED FART.
 *
 * <p>IT'S A REAL POTION, SO DRINKING IT, THE BREWING STAND (SEE THE RECIPES IN
 * data/buttcraft_potion_addon/recipe/brewing) AND THROWING IT ALL WORK LIKE VANILLA.
 * THE NAMES COME FROM THE LANG FILE: item.minecraft.potion.effect.fart AND FRIENDS.
 */
public final class FartPotions {

    // DRINKING IT GIVES 30 SECONDS OF STENCH II, LIKE POISON II (20 TICKS = 1 SECOND).
    // THROWN FARTS DON'T USE THIS, THEY GIVE THE STENCH I OF THEIR ThrownFart INSTEAD.
    private static final int STENCH_DURATION_TICKS = 600;
    private static final int STENCH_AMPLIFIER = 1;

    /**
     * WHAT A THROWN FART RELEASES WHERE IT BREAKS: A FART CLOUD LASTING cloudTicks, WHERE EACH
     * DOSE GIVES stenchTicks OF STENCH I. USED FOR BOTH THE CLOUD AND THE TOOLTIP, SO THEY MATCH.
     */
    public record ThrownFart(int cloudTicks, int stenchTicks) {
        public MobEffectInstance stench() {
            return new MobEffectInstance(ModEffects.STENCH, this.stenchTicks, 0);
        }
    }

    // A SPLASH FART RELEASES A NORMAL FART CLOUD
    private static final ThrownFart SPLASH = new ThrownFart(FartClouds.CLOUD_LIFETIME_TICKS, FartClouds.STENCH_DURATION_TICKS);

    // A LINGERING FART'S CLOUD LASTS LONGER THAN A NORMAL FART'S (BUT NOT THE USUAL 30 SECOND
    // POTION CLOUD) AND GIVES A LONGER STENCH I
    private static final ThrownFart LINGERING = new ThrownFart(360, 360);

    // A THROWN FART BREAKS THIS FAR OFF THE BLOCK IT HIT
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

    // HOW A SPLASH OR LINGERING FART BEHAVES. NULL FOR A DRINKABLE FART, WHICH GIVES THE
    // POTION'S OWN STENCH II, OR FOR ANYTHING THAT ISN'T A FART.
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

    // WHERE A THROWN FART RELEASES ITS GAS, KEPT OUT OF THE BLOCK IT HIT
    public static Vec3 impactPosition(HitResult hit) {
        Vec3 pos = hit.getLocation();
        if (hit instanceof BlockHitResult blockHit) {
            pos = pos.add(blockHit.getDirection().getUnitVec3().scale(SURFACE_OFFSET));
        }

        return pos;
    }
}
