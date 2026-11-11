package com.vtpicker.mixin;

import com.vtpicker.TweaksScreen;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Vanilla Tweaks" button in the top left corner of the resource pack screen (not the data pack one). */
@Mixin(PackSelectionScreen.class)
public abstract class PackSelectionScreenMixin extends Screen {

    @Unique
    private boolean vtpicker$resourcePacks;

    protected PackSelectionScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vtpicker$remember(PackRepository repository, Consumer<PackRepository> output, Path packDir,
        Component title, CallbackInfo ci) {
        this.vtpicker$resourcePacks = packDir.equals(Minecraft.getInstance().getResourcePackDirectory());
    }

    @Unique
    private Button vtpicker$button;

    // Every tick instead of once in init: Lunar Client rebuilds this screen's widgets after init and drops ours.
    @Inject(method = "tick", at = @At("HEAD"))
    private void vtpicker$keepButton(CallbackInfo ci) {
        if (!this.vtpicker$resourcePacks || this.vtpicker$button != null && this.children().contains(this.vtpicker$button)) {
            return;
        }
        this.vtpicker$button = this.addRenderableWidget(Button.builder(Component.literal("Vanilla Tweaks"), b -> {
            // Close this screen first (it commits its own pack list), else it would undo our pack when closed later.
            this.onClose();
            Minecraft mc = Minecraft.getInstance();
            mc.gui.setScreen(new TweaksScreen(mc.gui.screen()));
        }).bounds(4, 4, 80, 20).build());
    }
}
