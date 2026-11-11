package com.vtpicker.mixin;

import com.vtpicker.TweaksScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev check only: -Dvtpicker.open=true opens the picker once the title screen is up. */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Unique
    private static boolean vtpicker$opened;

    @Inject(method = "init", at = @At("TAIL"))
    private void vtpicker$open(CallbackInfo ci) {
        if (!vtpicker$opened && Boolean.getBoolean("vtpicker.open")) {
            vtpicker$opened = true;
            com.vtpicker.VanillaTweaks.selfCheck();
            Minecraft.getInstance().gui.setScreen(new TweaksScreen((TitleScreen) (Object) this));
        }
    }
}
