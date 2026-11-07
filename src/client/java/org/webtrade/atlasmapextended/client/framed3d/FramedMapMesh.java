package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

/** Immutable list of coloured quads and line segments in map-pixel space. */
public final class FramedMapMesh {
    private final float[] positions;
    private final int[] colors;
    private final int count;
    private final float[] linePositions;
    private final int[] lineColors;
    private final int lineCount;

    FramedMapMesh(float[] positions, int[] colors, int count,
                  float[] linePositions, int[] lineColors, int lineCount) {
        this.positions = positions;
        this.colors = colors;
        this.count = count;
        this.linePositions = linePositions;
        this.lineColors = lineColors;
        this.lineCount = lineCount;
    }

    public int quadCount() {
        return count;
    }

    public int lineCount() {
        return lineCount;
    }

    public void emit(Matrix4fc matrix, VertexConsumer consumer) {
        for (int quad = 0; quad < count; quad++) {
            int p = quad * 12;
            int color = colors[quad];
            for (int vertex = 0; vertex < 4; vertex++) {
                int o = p + vertex * 3;
                consumer.addVertex(matrix, positions[o], positions[o + 1], positions[o + 2])
                        .setColor(color);
            }
        }
    }

    /** Two vertices per segment; RenderTypes.lines() consumes pairs. */
    public void emitLines(Matrix4fc matrix, VertexConsumer consumer) {
        for (int line = 0; line < lineCount; line++) {
            int o = line * 6;
            int c = lineColors[line];
            consumer.addVertex(matrix, linePositions[o], linePositions[o + 1], linePositions[o + 2])
                    .setColor(c);
            consumer.addVertex(matrix, linePositions[o + 3], linePositions[o + 4], linePositions[o + 5])
                    .setColor(c);
        }
    }
}
