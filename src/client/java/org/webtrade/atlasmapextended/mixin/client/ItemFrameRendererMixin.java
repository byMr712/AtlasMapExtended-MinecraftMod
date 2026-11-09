package org.webtrade.atlasmapextended.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapConnections;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapRenderer;
import org.webtrade.atlasmapextended.client.framed3d.ItemFrameRenderState;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;

@Mixin(ItemFrameRenderer.class)
public class ItemFrameRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void atlasmapextended$beginFrame(
            ItemFrame frame,
            float yaw,
            float tickDelta,
            PoseStack pose,
            MultiBufferSource buffer,
            int light,
            CallbackInfo ci
    ) {
        ClientLevel level = (ClientLevel) frame.level();
        ItemFrameRenderState state = ItemFrameRenderState.from(frame);
        FramedMapConnections.begin(level, state, light);
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void atlasmapextended$endFrame(
            ItemFrame frame,
            float yaw,
            float tickDelta,
            PoseStack pose,
            MultiBufferSource buffer,
            int light,
            CallbackInfo ci
    ) {
        FramedMapConnections.end();
    }

    @Redirect(
            method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/decoration/ItemFrame;getRotation()I"))
    private int atlasmapextended$frameRotation(ItemFrame frame) {
        FramedMapConnections.Context context = FramedMapConnections.context();
        if (context != null && context.tileDiv() > 1) {
            return 0;
        }
        return frame.getRotation();
    }

    @Redirect(
            method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
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

    @Redirect(
            method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/MapRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/saveddata/maps/MapId;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;ZI)V"))
    private void atlasmapextended$renderMap(
            net.minecraft.client.gui.MapRenderer mapRenderer,
            PoseStack pose,
            MultiBufferSource buffer,
            MapId mapId,
            MapItemSavedData data,
            boolean inItemFrame,
            int light
    ) {
        FramedMapConnections.Context context = FramedMapConnections.context();
        boolean glow = context != null && context.glow();
        int mapLight = glow ? 15728850 : light;

        int tileDiv = FramedMapConnections.currentTileDiv();
        boolean suppress3D = FramedMapConnections.currentSuppress3D();
        boolean show2D = !ModConfig.get().framedMaps3D || suppress3D;

        ResourceLocation customTexture = null;
        if (data != null && mapId != null) {
            customTexture = HeldMapTextures.getFramed(
                    mapId, data, FramedMapConnections.currentTextureMask(),
                    tileDiv, FramedMapConnections.currentTileCol(), FramedMapConnections.currentTileRow(),
                    FramedMapConnections.currentTileRotation(), show2D);
        }

        if (customTexture != null) {
            VertexConsumer vc = buffer.getBuffer(RenderType.text(customTexture));
            Matrix4f m = pose.last().pose();
            vc.addVertex(m, 0.0F, 128.0F, -0.01F).setColor(-1).setUv(0.0F, 1.0F).setLight(mapLight);
            vc.addVertex(m, 128.0F, 128.0F, -0.01F).setColor(-1).setUv(1.0F, 1.0F).setLight(mapLight);
            vc.addVertex(m, 128.0F, 0.0F, -0.01F).setColor(-1).setUv(1.0F, 0.0F).setLight(mapLight);
            vc.addVertex(m, 0.0F, 0.0F, -0.01F).setColor(-1).setUv(0.0F, 0.0F).setLight(mapLight);
        } else {
            mapRenderer.render(pose, buffer, mapId, data, inItemFrame, mapLight);
        }

        if (!FramedMapConnections.currentSuppress3D()
                && FramedMapConnections.currentActive3D()
                && FramedMapConnections.currentGroupLeader()
                && context != null) {
            FramedMapRenderer.render(
                    FramedMapConnections.currentState(), pose, buffer, FramedMapConnections.currentTextureMask(),
                    FramedMapConnections.currentTileDiv(),
                    FramedMapConnections.currentTileCol(),
                    FramedMapConnections.currentTileRow(),
                    FramedMapConnections.currentTileRotation(),
                    light);
        }
    }
}
