package org.webtrade.atlasmapextended.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;

/** Swaps the held filled-map texture for the AtlasMap papyrus version. */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Shadow
    private MapRenderState mapRenderState;

    @Inject(
            method = "renderMap",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MapRenderer;extractRenderState(Lnet/minecraft/world/level/saveddata/maps/MapId;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;Lnet/minecraft/client/renderer/state/MapRenderState;)V",
                    shift = At.Shift.AFTER))
    private void atlasmapextended$papyrusHeldMap(
            PoseStack pose, SubmitNodeCollector collector, int light, ItemStack stack, CallbackInfo ci
    ) {
        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null || Minecraft.getInstance().level == null) {
            return;
        }
        MapItemSavedData data = MapItem.getSavedData(mapId, Minecraft.getInstance().level);
        if (data == null) {
            return;
        }
        Identifier texture = HeldMapTextures.get(mapId, data);
        if (texture != null) {
            mapRenderState.texture = texture;
        }
    }
}
