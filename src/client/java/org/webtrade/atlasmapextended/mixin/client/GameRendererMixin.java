package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.gui.WorldMarkerHud;

/** Finishes a deferred screenshot once the frame is fully rendered without marker icons. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void atlasmapextended$finishScreenshot(CallbackInfo ci) {
        WorldMarkerHud.finishScreenshot();
    }
}
