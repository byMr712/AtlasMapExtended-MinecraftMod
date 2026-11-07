package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.gui.MinimapFrame;
import org.webtrade.atlasmapextended.client.gui.WorldMarkerHud;

/** Replaces Fabric API's HUD element registration for the minimap. */
@Mixin(Gui.class)
public class GuiMixin {

    @Shadow
    @Final
    private LayeredDraw layers;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void atlasmapextended$addMinimapLayer(CallbackInfo ci) {
        // On Fabric the HUD is drawn from a layer list, so append after the scoreboard layer.
        if (MinimapFrame.useLayer()) {
            this.layers.add((GuiGraphics g, DeltaTracker delta) -> MinimapFrame.draw(g, delta));
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void atlasmapextended$renderWorldMarkers(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        MinimapFrame.beginFrame();
        WorldMarkerHud.render(g);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void atlasmapextended$renderMinimapFallback(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        MinimapFrame.draw(g, delta);
    }

}