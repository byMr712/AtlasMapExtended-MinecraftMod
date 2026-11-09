package org.webtrade.atlasmapextended.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Shadow
    @Final
    private MapRenderState mapRenderState;

    @Inject(
            method = "renderMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"))
    private void atlasmapextended$heldMapTexture(
            PoseStack pose, MultiBufferSource buffer, int light, ItemStack stack, CallbackInfo ci
    ) {
        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        MapItemSavedData data = level.getMapData(mapId);
        if (data != null) {
            var texture = HeldMapTextures.get(mapId, data);
            if (texture != null) {
                mapRenderState.texture = texture;
            }
        }
    }
}
