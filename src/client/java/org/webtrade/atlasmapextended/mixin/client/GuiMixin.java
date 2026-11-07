package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.gui.MinimapFrame;
import org.webtrade.atlasmapextended.client.gui.WorldMarkerHud;

/** Replaces Fabric API's HUD element registration for the minimap. */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void atlasmapextended$renderWorldMarkers(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        MinimapFrame.beginFrame();
        WorldMarkerHud.render(g);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void atlasmapextended$rememberMinimapFrame(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        MinimapFrame.remember(g, delta);
    }

    @Inject(method = "renderSavingIndicator", at = @At("TAIL"))
    private void atlasmapextended$renderMinimapLate(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        MinimapFrame.drawLate();
    }

}