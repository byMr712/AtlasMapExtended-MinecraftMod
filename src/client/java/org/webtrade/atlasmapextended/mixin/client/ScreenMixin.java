package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.gui.MinimapFrame;

/** Draws the minimap just before an open screen, above the HUD but below the screen. */
@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void atlasmapextended$renderMinimapBehindScreen(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        MinimapFrame.drawBehindScreen(g);
    }

}