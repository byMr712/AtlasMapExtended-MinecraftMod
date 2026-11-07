package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

import java.util.List;

/** Immutable list of coloured quads, line segments and textured marker billboards. */
public final class FramedMapMesh {
    private final float[] positions;
    private final int[] colors;
    private final int count;
    private final float[] linePositions;
    private final int[] lineColors;
    private final int lineCount;
    private final List<IconGroup> icons;

    FramedMapMesh(float[] positions, int[] colors, int count,
                  float[] linePositions, int[] lineColors, int lineCount,
                  List<IconGroup> icons) {
        this.positions = positions;
        this.colors = colors;
        this.count = count;
        this.linePositions = linePositions;
        this.lineColors = lineColors;
        this.lineCount = lineCount;
        this.icons = icons;
    }

    public int quadCount() {
        return count;
    }

    public int lineCount() {
        return lineCount;
    }

    public List<IconGroup> iconGroups() {
        return icons;
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

    public static final class IconInstance {
        public final float x;
        public final float y;
        public final float z;
        public final int color;
        public final float size;

        public IconInstance(float x, float y, float z, int color, float size) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
            this.size = size;
        }
    }

    public static final class IconGroup {
        public final Identifier texture;
        public final Identifier source;
        private final List<IconInstance> instances;

        IconGroup(Identifier texture, Identifier source, List<IconInstance> instances) {
            this.texture = texture;
            this.source = source;
            this.instances = instances;
        }

        public int count() {
            return instances.size();
        }

        /** Two crossed quads per icon, slowly rotating around the vertical axis. */
        public void emit(Matrix4fc matrix, VertexConsumer consumer, float angle) {
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            for (IconInstance icon : instances) {
                float s = icon.size;
                cube(matrix, consumer, icon, cos, sin, s);
                cube(matrix, consumer, icon, -sin, cos, s);
            }
        }

        private static void cube(Matrix4fc matrix, VertexConsumer consumer, IconInstance icon,
                                 float hx, float hz, float s) {
            float cx = icon.x;
            float cy = icon.y;
            float cz = icon.z;

            // clockwise order seen from the front: TL, TR, BR, BL
            vertex(matrix, consumer, icon, cx - hx * s, cy - s, cz - hz * s, 0.0f, 0.0f);
            vertex(matrix, consumer, icon, cx + hx * s, cy - s, cz + hz * s, 1.0f, 0.0f);
            vertex(matrix, consumer, icon, cx + hx * s, cy + s, cz + hz * s, 1.0f, 1.0f);
            vertex(matrix, consumer, icon, cx - hx * s, cy + s, cz - hz * s, 0.0f, 1.0f);
        }

        private static void vertex(Matrix4fc matrix, VertexConsumer consumer, IconInstance icon,
                                   float x, float y, float z, float u, float v) {
            consumer.addVertex(matrix, x, y, z)
                    .setColor(icon.color)
                    .setUv(u, v)
                    .setLight(15728880)
                    .setOverlay(0)
                    .setNormal(0.0f, 0.0f, 1.0f);
        }
    }
}
