package org.webtrade.atlasmapextended.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapConnections;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapRenderer;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;

@Mixin(ItemFrameRenderer.class)
public class ItemFrameRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void atlasmapextended$beginFrame(
            ItemFrameRenderState state,
            PoseStack pose,
            MultiBufferSource buffer,
            int light,
            CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        FramedMapConnections.begin(mc.level, state, light);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void atlasmapextended$endFrame(
            ItemFrameRenderState state,
            PoseStack pose,
            MultiBufferSource buffer,
            int light,
            CallbackInfo ci
    ) {
        FramedMapConnections.end();
    }

    @Redirect(
            method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;renderModel(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/resources/model/BakedModel;FFFII)V"))
    private void atlasmapextended$frameModel(
            ModelBlockRenderer instance,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            BlockState state,
            BakedModel model,
            float r,
            float g,
            float b,
            int light,
            int overlay
    ) {
        FramedMapConnections.Context context = FramedMapConnections.context();
        boolean hide3D = ModConfig.get().framedMaps3D
                && context != null && !context.suppress3D();
        if (context != null && (context.hideFrame() || hide3D)) {
            return;
        }
        int frameLight = context != null && context.glow() ? 15728880 : light;
        instance.renderModel(pose, consumer, state, model, r, g, b, frameLight, overlay);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MapRenderer;render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V",
                    shift = At.Shift.BEFORE))
    private void atlasmapextended$papyrusFrameBase(
            ItemFrameRenderState state,
            PoseStack pose,
            MultiBufferSource buffer,
            int light,
            CallbackInfo ci
    ) {
        if (state.mapId == null) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        MapItemSavedData data = level.getMapData(state.mapId);
        if (data != null) {
            int tileDiv = FramedMapConnections.currentTileDiv();
            if (tileDiv > 1) {
                state.mapRenderState.decorations.clear();
            }
            boolean suppress3D = FramedMapConnections.currentSuppress3D();
            boolean show2D = !ModConfig.get().framedMaps3D || suppress3D;
            var texture = HeldMapTextures.getFramed(
                    state.mapId, data, FramedMapConnections.currentTextureMask(),
                    tileDiv, FramedMapConnections.currentTileCol(), FramedMapConnections.currentTileRow(),
                    FramedMapConnections.currentTileRotation(), show2D);
            if (texture != null) {
                state.mapRenderState.texture = texture;
            }
        }
    }

    @ModifyArg(
            method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MapRenderer;render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V"),
            index = 4)
    private int atlasmapextended$mapLight(int light) {
        FramedMapConnections.Context context = FramedMapConnections.context();
        if (context != null && context.glow()) {
            return 15728850;
        }
        return light;
    }

    @Inject(
            method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MapRenderer;render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V",
                    shift = At.Shift.AFTER))
    private void atlasmapextended$renderFramed3d(
            ItemFrameRenderState state,
            PoseStack pose,
            MultiBufferSource buffer,
            int light,
            CallbackInfo ci
    ) {
        if (FramedMapConnections.currentSuppress3D()
                || !FramedMapConnections.currentActive3D()
                || !FramedMapConnections.currentGroupLeader()) {
            return;
        }
        FramedMapRenderer.render(
                state, pose, buffer, FramedMapConnections.currentTextureMask(),
                FramedMapConnections.currentTileDiv(),
                FramedMapConnections.currentTileCol(),
                FramedMapConnections.currentTileRow(),
                FramedMapConnections.currentTileRotation(),
                light);
    }
}
