package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

/** Immutable list of coloured quads in map-pixel space (128 px = 1 block). */
public final class FramedMapMesh {
    private final float[] positions;
    private final int[] colors;
    private final int count;

    FramedMapMesh(float[] positions, int[] colors, int count) {
        this.positions = positions;
        this.colors = colors;
        this.count = count;
    }

    public int quadCount() {
        return count;
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
}
