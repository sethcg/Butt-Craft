package com.github.sethcg.buttcraft.potion;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * THE BOTTLED FART.
 *
 * <p>IT'S A REAL POTION, SO DRINKING IT, THE BREWING STAND (SEE THE RECIPES IN
 * data/buttcraft_potion_addon/recipe/brewing) AND THROWING IT ALL WORK LIKE VANILLA.
 * THE NAMES COME FROM THE LANG FILE: item.minecraft.potion.effect.fart AND FRIENDS.
 */
public final class FartPotions {

    // DRINKING IT GIVES A LONG POISON (20 TICKS = 1 SECOND)
    private static final int POISON_DURATION_TICKS = 1800;
    private static final int POISON_AMPLIFIER = 0;

    // A LINGERING FART'S CLOUD LASTS THIS LONG, INSTEAD OF THE USUAL 30 SECOND POTION CLOUD
    public static final int LINGERING_CLOUD_TICKS = 360;

    // AN ARROW OF FARTS LEAVES A SMALL, SHORT CLOUD WHERE IT HITS (1 = A NORMAL FART'S SIZE)
    public static final int ARROW_CLOUD_TICKS = 80;
    public static final float ARROW_CLOUD_SIZE = 0.5F;

    // A THROWN FART BREAKS THIS FAR OFF THE BLOCK IT HIT
    private static final double SURFACE_OFFSET = 0.3;

    public static final ResourceKey<Potion> FART_KEY = ResourceKey.create(
        Registries.POTION,
        ButtCraftPotionAddon.id("fart")
    );

    public static final Holder<Potion> FART = Registry.registerForHolder(
        BuiltInRegistries.POTION,
        FART_KEY,
        new Potion("fart", new MobEffectInstance(MobEffects.POISON, POISON_DURATION_TICKS, POISON_AMPLIFIER))
    );

    private FartPotions() {
    }

    public static void initialize() {
        ButtCraftPotionAddon.LOGGER.info("REGISTERING BUTTCRAFT POTIONS");
    }

    public static ItemStack createBottle() {
        return PotionContents.createItemStack(Items.POTION, FART);
    }

    public static boolean isFart(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(FART);
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
