package com.github.sethcg.buttcraft.client;

import com.github.sethcg.buttcraft.ButtCraft;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.KeyMapping;

public final class KeyBindings {

    public static KeyMapping FART_KEY;

    private KeyBindings() {
    }

    public static void initialize() {
        KeyMapping.Category category = KeyMapping.Category.register(
                ButtCraft.id("buttcraft")
        );

        FART_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                "key.buttcraft.fart",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_R,
                category
            )
        );
    }
}