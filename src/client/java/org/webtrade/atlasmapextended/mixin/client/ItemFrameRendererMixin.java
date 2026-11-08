package org.webtrade.atlasmapextended.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.joml.Matrix4f;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapConnections;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapRenderer;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;

/** Adds the AtlasMap 3D relief to filled maps in item frames. */
@Mixin(ItemFrameRenderer.class)
public class ItemFrameRendererMixin {

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"))
    private void atlasmapextended$beginFrame(
            ItemFrameRenderState state,
            PoseStack pose,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            CallbackInfo ci
    ) {
        FramedMapConnections.begin(Minecraft.getInstance().level, state, camera);
    }

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("RETURN"))
    private void atlasmapextended$endFrame(
            ItemFrameRenderState state,
            PoseStack pose,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            CallbackInfo ci
    ) {
        FramedMapConnections.end();
    }

    /**
     * In a connected group the vanilla frame is replaced by a rim drawn only on
     * the outer edges, so the maps touch without a divider.
     */
    @Redirect(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockModelRenderState;submitWithZOffset(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void atlasmapextended$frameModel(
            BlockModelRenderState model,
            PoseStack pose,
            SubmitNodeCollector collector,
            int light,
            int overlay,
            int outlineColor
    ) {
        FramedMapConnections.Context context = FramedMapConnections.context();
        boolean hide3D = ModConfig.get().framedMaps3D
                && context != null && !context.suppress3D();
        if (context != null && (context.hideFrame() || hide3D)) {
            // In 3D the rim would float above the relief and can occlude the
            // relief's side walls. A mega-map is also borderless in 2D; only
            // normal groups keep the thin outer rim.
            if (!ModConfig.get().framedMaps3D && context.tileDiv() <= 1) {
                FramedMapConnections.renderOuterFrame(
                        pose, collector, context.worldMask(), context.glow(), context.light());
            }
            return;
        }
        model.submitWithZOffset(pose, collector, light, overlay, outlineColor);
    }

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MapRenderer;render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ZI)V",
                    shift = At.Shift.BEFORE))
    private void atlasmapextended$papyrusFrameBase(
            ItemFrameRenderState state,
            PoseStack pose,
            SubmitNodeCollector collector,
            CameraRenderState camera,
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
            if (ModConfig.get().framedMaps3D
                    && tileDiv <= 1
                    && state.direction == Direction.UP) {
                // A lone floor map keeps the orientation chosen when it was
                // placed, so walking around it does not spin the picture.
                // The pose is already translated by -64,-64, so the centre
                // compensation keeps the map inside its frame.
                int steps = FramedMapConnections.currentTileRotation();
                if (steps != 0) {
                    pose.translate(64.0f, 64.0f, 0.0f);
                    pose.mulPose(new Matrix4f().rotationZ((float) Math.toRadians(-steps * 90.0f)));
                    pose.translate(-64.0f, -64.0f, 0.0f);
                }
            }
            if (tileDiv > 1) {
                state.mapRenderState.decorations.clear();
            }
            var texture = HeldMapTextures.getFramed(
                    state.mapId, data, FramedMapConnections.currentTextureMask(),
                    tileDiv, FramedMapConnections.currentTileCol(), FramedMapConnections.currentTileRow(),
                    FramedMapConnections.currentTileRotation());
            if (texture != null) {
                state.mapRenderState.texture = texture;
            }
        }
    }

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MapRenderer;render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ZI)V",
                    shift = At.Shift.AFTER))
    private void atlasmapextended$renderFramed3d(
            ItemFrameRenderState state,
            PoseStack pose,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            CallbackInfo ci
    ) {
        if (FramedMapConnections.currentSuppress3D()
                || !FramedMapConnections.currentActive3D()) {
            return;
        }
        FramedMapRenderer.render(
                state, pose, collector, FramedMapConnections.currentTextureMask(),
                FramedMapConnections.currentTileDiv(),
                FramedMapConnections.currentTileCol(),
                FramedMapConnections.currentTileRow(),
                FramedMapConnections.currentTileRotation());
    }
}
