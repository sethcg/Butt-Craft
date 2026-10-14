package com.github.sethcg.buttcraft.potion;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ButtCraftPotionAddon implements ModInitializer {

    public static final String MOD_ID = "buttcraft_potion_addon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {

        // REGISTER THE BOTTLED FART POTION
        FartPotions.initialize();

        // LET GLASS BOTTLES SCOOP UP FART CLOUDS
        FartBottling.initialize();

        LOGGER.info("BUTTCRAFT POTION ADDON INITIALIZED.");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
