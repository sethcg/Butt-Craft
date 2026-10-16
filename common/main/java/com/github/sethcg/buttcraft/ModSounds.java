package com.github.sethcg.buttcraft;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class ModSounds {
    public static final Identifier FART_ID = ButtCraft.id("fart");

    public static final SoundEvent FART =
        Registry.register(BuiltInRegistries.SOUND_EVENT, FART_ID, SoundEvent.createVariableRangeEvent(FART_ID));

    private ModSounds() {
    }

    public static void initialize() {
        ButtCraft.LOGGER.info("REGISTERING BUTTCRAFT SOUNDS");
    }
}
