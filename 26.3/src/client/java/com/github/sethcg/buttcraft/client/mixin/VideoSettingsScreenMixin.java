package com.github.sethcg.buttcraft.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.sethcg.buttcraft.client.options.ButtCraftOptions;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

// ADDS A "BUTT-CRAFT" SECTION TO THE END OF VIDEO SETTINGS
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {

    private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void buttcraft$addOptions(CallbackInfo ci) {
        ButtCraftOptions options = new ButtCraftOptions();
        this.list.addHeader(ButtCraftOptions.HEADER);
        this.list.addSmall(options.quality);
        this.list.addSmall(options.details().toArray(OptionInstance[]::new));
    }
}
