package com.github.sethcg.buttcraft.potion;

import com.github.sethcg.buttcraft.FartClouds;

import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

public final class FartBottling {

    private static final double SCOOP_REACH = 2.0;

    private FartBottling() {
    }

    public static void initialize() {
        UseItemCallback.EVENT.register(FartBottling::tryBottle);
    }

    private static InteractionResult tryBottle(Player player, Level level, InteractionHand hand) {

        // ONLY THE SERVER KNOWS WHERE THE CLOUDS ARE. THE CLIENT FALLS THROUGH TO THE
        // NORMAL GLASS BOTTLE, WHICH STILL SENDS THE USE TO THE SERVER
        if (!(level instanceof ServerLevel serverLevel) || player.isSpectator()) {
            return InteractionResult.PASS;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(Items.GLASS_BOTTLE)) {
            return InteractionResult.PASS;
        }

        if (!FartClouds.isTouching(serverLevel, player.getBoundingBox().inflate(SCOOP_REACH))) {
            return InteractionResult.PASS;
        }

        level.playSound(
            null,
            player.getX(),
            player.getY(),
            player.getZ(),
            SoundEvents.BOTTLE_FILL_DRAGONBREATH,
            SoundSource.NEUTRAL,
            1.0F,
            1.0F
        );
        level.gameEvent(player, GameEvent.FLUID_PICKUP, player.position());
        player.awardStat(Stats.ITEM_USED.get(Items.GLASS_BOTTLE));

        // THE CALLBACK SKIPS VANILLA, SO THE BOTTLE IS SWAPPED HERE
        player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, FartPotions.createBottle()));

        // SWING FROM THE SERVER, SINCE THE CLIENT DOESN'T KNOW IT SCOOPED ANYTHING
        return InteractionResult.SUCCESS_SERVER;
    }
}
