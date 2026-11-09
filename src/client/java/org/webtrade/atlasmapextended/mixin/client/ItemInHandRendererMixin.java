package org.webtrade.atlasmapextended.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Redirect(
            method = "renderMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/item/ItemStack;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/MapRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/saveddata/maps/MapId;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;ZI)V"))
    private void atlasmapextended$renderHeldMap(
            net.minecraft.client.gui.MapRenderer mapRenderer,
            PoseStack pose,
            MultiBufferSource buffer,
            MapId mapId,
            MapItemSavedData data,
            boolean inItemFrame,
            int light
    ) {
        ResourceLocation texture = null;
        if (data != null && mapId != null) {
            texture = HeldMapTextures.get(mapId, data);
        }
        if (texture != null) {
            VertexConsumer vc = buffer.getBuffer(RenderType.text(texture));
            Matrix4f m = pose.last().pose();
            vc.addVertex(m, 0.0F, 128.0F, -0.01F).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
            vc.addVertex(m, 128.0F, 128.0F, -0.01F).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
            vc.addVertex(m, 128.0F, 0.0F, -0.01F).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
            vc.addVertex(m, 0.0F, 0.0F, -0.01F).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
        } else {
            mapRenderer.render(pose, buffer, mapId, data, inItemFrame, light);
        }
    }
}
