package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
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
        for (FramedMapMesh.IconGroup group : mesh.iconGroups()) {
            org.webtrade.atlasmapextended.client.gui.Paper.ensureFrom(group.texture, group.source);
            collector.submitCustomGeometry(pose, RenderTypes.entityCutout(group.texture),
                    (p, consumer) -> group.emit(p.pose(), consumer));
        }
    }
}
