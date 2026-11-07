package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.gui.MinimapFrame;
import org.webtrade.atlasmapextended.client.gui.WorldMarkerHud;

/** Finishes the frame: minimap on top of the HUD, then the deferred screenshot. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void atlasmapextended$renderMinimap(CallbackInfo ci) {
        MinimapFrame.drawLate();
        WorldMarkerHud.finishScreenshot();
    }
}