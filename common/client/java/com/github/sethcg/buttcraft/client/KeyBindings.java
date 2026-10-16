package com.github.sethcg.buttcraft.client;

import com.github.sethcg.buttcraft.ButtCraft;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public final class KeyBindings {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            ButtCraft.id("buttcraft")
    );

    public static final KeyMapping FART_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.buttcraft.fart",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_R,
                    CATEGORY
            )
    );

    private KeyBindings() {
    }

    /** LOADS THIS CLASS, WHICH REGISTERS THE KEY MAPPINGS. */
    public static void initialize() {
    }
}
