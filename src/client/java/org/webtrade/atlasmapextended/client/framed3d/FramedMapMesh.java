package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;

import java.util.List;

/** Immutable list of coloured quads, line segments and 3D icon instances. */
public final class FramedMapMesh {
    private final float[] positions;
    private final int[] colors;
    private final int count;
    private final float[] linePositions;
    private final int[] lineColors;
    private final int lineCount;
    private final List<VoxelIcon> voxelIcons;
    /** Tile index per quad for a shared mega-group mesh; null for one map. */
    private final int[] quadTiles;

    FramedMapMesh(float[] positions, int[] colors, int count,
                  float[] linePositions, int[] lineColors, int lineCount,
                  List<VoxelIcon> voxelIcons, int[] quadTiles) {
        this.positions = positions;
        this.colors = colors;
        this.count = count;
        this.linePositions = linePositions;
        this.lineColors = lineColors;
        this.lineCount = lineCount;
        this.voxelIcons = voxelIcons;
        this.quadTiles = quadTiles;
    }

    public int quadCount() {
        return count;
    }

    public int lineCount() {
        return lineCount;
    }

    public List<VoxelIcon> voxelIcons() {
        return voxelIcons;
    }

    public void emit(Matrix4f matrix, VertexConsumer consumer, float brightness, int light) {
        emit(matrix, consumer, brightness, light, null, light, brightness);
    }

    /**
     * Emits a shared mega-group mesh with per-tile glow. Quads that belong to
     * a glowing frame use the glow light and brightness; the rest keep the
     * normal ones. This restores mixed glow/normal parts without splitting
     * the group back into per-tile meshes.
     */
    public void emitTiled(Matrix4f matrix, VertexConsumer consumer,
                          float brightness, int light,
                          boolean[] tileGlow, int glowLight, float glowBrightness) {
        emit(matrix, consumer, brightness, light, tileGlow, glowLight, glowBrightness);
    }

    private void emit(Matrix4f matrix, VertexConsumer consumer,
                      float brightness, int light,
                      boolean[] tileGlow, int glowLight, float glowBrightness) {
        for (int quad = 0; quad < count; quad++) {
            boolean glow = tileGlow != null && quadTiles != null
                    && glowAt(tileGlow, quadTiles[quad]);
            int color = brighten(colors[quad], glow ? glowBrightness : brightness);
            int vertexLight = glow ? glowLight : light;
            int p = quad * 12;
            for (int vertex = 0; vertex < 4; vertex++) {
                int o = p + vertex * 3;
                consumer.addVertex(matrix, positions[o], positions[o + 1], positions[o + 2])
                        .setColor(color)
                        .setUv(0.0f, 0.0f)
                        .setLight(vertexLight)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setNormal(0.0f, 0.0f, 1.0f);
            }
        }
    }

    private static boolean glowAt(boolean[] tileGlow, int tile) {
        return tile >= 0 && tile < tileGlow.length && tileGlow[tile];
    }

    /** Two vertices per segment; RenderType.lines() consumes pairs. */
    public void emitLines(Matrix4f matrix, VertexConsumer consumer, float brightness) {
        for (int line = 0; line < lineCount; line++) {
            int o = line * 6;
            int c = brighten(lineColors[line], brightness);
            consumer.addVertex(matrix, linePositions[o], linePositions[o + 1], linePositions[o + 2])
                    .setColor(c)
                    .setNormal(0.0f, 1.0f, 0.0f)
;
            consumer.addVertex(matrix, linePositions[o + 3], linePositions[o + 4], linePositions[o + 5])
                    .setColor(c)
                    .setNormal(0.0f, 1.0f, 0.0f)
;
        }
    }

    /** Raises the RGB channels while keeping alpha untouched. */
    public static int brighten(int argb, float factor) {
        if (factor <= 1.0f) {
            return argb;
        }
        int a = argb >>> 24;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, Math.round((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** One real 3D voxel icon floating above the surface. */
    public record VoxelIcon(String icon, int tint, float x, float y, float z) {
    }
}
