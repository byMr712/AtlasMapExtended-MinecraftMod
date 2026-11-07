package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Matrix4f;
import org.webtrade.atlasmapextended.client.data.ModConfig;

/**
 * Draws the 3D relief right after the vanilla map quad, using the same
 * map-pixel pose (128 px = 1 block).
 */
public final class FramedMapRenderer {
    private static final java.util.Set<Integer> LOGGED = new java.util.HashSet<>();

    private FramedMapRenderer() {
    }

    public static void render(ItemFrameRenderState state, PoseStack pose, SubmitNodeCollector collector) {
        if (!ModConfig.get().framedMaps3D || state.mapId == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        MapItemSavedData data = level.getMapData(state.mapId);
        if (LOGGED.add(state.mapId.id())) {
            System.out.println("[AtlasMap3D] frame map " + state.mapId.id()
                    + " data=" + (data != null)
                    + " colors=" + (data != null && data.colors != null));
        }
        if (data == null || data.colors == null) {
            return;
        }

        FramedMapMesh mesh = FramedMapCache.getOrRequest(level, state.mapId, data);
        if (mesh == null) {
            return;
        }
        collector.submitCustomGeometry(pose, RenderTypes.debugQuads(),
                (p, consumer) -> mesh.emit(p.pose(), consumer));
        if (mesh.lineCount() > 0) {
            collector.submitCustomGeometry(pose, RenderTypes.lines(),
                    (p, consumer) -> mesh.emitLines(p.pose(), consumer));
        }
        long gameTime = level.getGameTime();
        float angle = (gameTime % 3600L) * 0.02f;
        if (!mesh.voxelIcons().isEmpty()) {
            collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, consumer) -> {
                Matrix4f base = p.pose();
                for (FramedMapMesh.VoxelIcon icon : mesh.voxelIcons()) {
                    IconVoxelShapes.Shape shape = IconVoxelShapes.get(icon.icon(), icon.tint());
                    if (shape == null) {
                        continue;
                    }
                    Matrix4f matrix = new Matrix4f(base)
                            .translate(icon.x(), icon.y(), icon.z())
                            .rotateY(angle)
                            .scale(0.5f, 0.5f, 0.5f)
                            .translate(-8.0f, -8.0f, -shape.depth / 2.0f);
                    shape.emit(matrix, consumer);
                }
            });
        }
    }
}
