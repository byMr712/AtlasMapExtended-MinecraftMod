package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4fc;

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

    FramedMapMesh(float[] positions, int[] colors, int count,
                  float[] linePositions, int[] lineColors, int lineCount,
                  List<VoxelIcon> voxelIcons) {
        this.positions = positions;
        this.colors = colors;
        this.count = count;
        this.linePositions = linePositions;
        this.lineColors = lineColors;
        this.lineCount = lineCount;
        this.voxelIcons = voxelIcons;
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

    public void emit(Matrix4fc matrix, VertexConsumer consumer, float brightness, int light) {
        for (int quad = 0; quad < count; quad++) {
            int p = quad * 12;
            int color = brighten(colors[quad], brightness);
            for (int vertex = 0; vertex < 4; vertex++) {
                int o = p + vertex * 3;
                consumer.addVertex(matrix, positions[o], positions[o + 1], positions[o + 2])
                        .setColor(color)
                        .setUv(0.0f, 0.0f)
                        .setLight(light)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setNormal(0.0f, 0.0f, 1.0f);
            }
        }
    }

    /** Two vertices per segment; RenderTypes.lines() consumes pairs. */
    public void emitLines(Matrix4fc matrix, VertexConsumer consumer, float brightness) {
        for (int line = 0; line < lineCount; line++) {
            int o = line * 6;
            int c = brighten(lineColors[line], brightness);
            consumer.addVertex(matrix, linePositions[o], linePositions[o + 1], linePositions[o + 2])
                    .setColor(c)
                    .setNormal(0.0f, 1.0f, 0.0f)
                    .setLineWidth(3.0f);
            consumer.addVertex(matrix, linePositions[o + 3], linePositions[o + 4], linePositions[o + 5])
                    .setColor(c)
                    .setNormal(0.0f, 1.0f, 0.0f)
                    .setLineWidth(3.0f);
        }
    }

    /** Raises the RGB channels while keeping alpha untouched. */
    static int brighten(int argb, float factor) {
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
