package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4fc;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extrudes a marker icon PNG into a real 3D voxel slab. Every icon becomes a
 * small solid object with front, back and side faces, so it can rotate around
 * its vertical axis and still look three-dimensional.
 */
public final class IconVoxelShapes {
    private static final int GRID = 16;
    private static final float DEPTH = 6.0f;
    private static final int ALPHA_THRESHOLD = 32;
    private static final Map<String, Shape> CACHE = new ConcurrentHashMap<>();

    private IconVoxelShapes() {
    }

    public static final class Shape {
        public final float[] positions;
        public final int[] colors;
        public final int count;
        public final float depth;

        Shape(float[] positions, int[] colors, int count, float depth) {
            this.positions = positions;
            this.colors = colors;
            this.count = count;
            this.depth = depth;
        }

        public void emit(Matrix4fc matrix, VertexConsumer consumer, float brightness, int light) {
            for (int quad = 0; quad < count; quad++) {
                int p = quad * 12;
                int color = FramedMapMesh.brighten(colors[quad], brightness);
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
    }

    public static Shape get(String icon, int tint) {
        String name = icon == null || icon.isBlank() ? "pin" : icon;
        String key = name + "#" + (tint == 0 ? 0 : tint);
        return CACHE.computeIfAbsent(key, ignored -> build(name, tint));
    }

    private static Shape build(String icon, int tint) {
        boolean tinted = tint != 0;
        String path = "/assets/atlasmapextended/textures/gui/markers"
                + (tinted ? "_tint" : "") + "/" + icon + ".png";
        NativeImage image;
        try (InputStream in = IconVoxelShapes.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            image = NativeImage.read(in);
        } catch (Exception e) {
            return null;
        }

        int[] pixels = new int[GRID * GRID];
        try {
            for (int y = 0; y < GRID; y++) {
                int sy = y * image.getHeight() / GRID;
                for (int x = 0; x < GRID; x++) {
                    int sx = x * image.getWidth() / GRID;
                    int pixel = image.getPixel(sx, sy);
                    int a = (pixel >>> 24) & 0xFF;
                    if (a < ALPHA_THRESHOLD) {
                        continue;
                    }
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    if (tinted) {
                        r = r * ((tint >> 16) & 0xFF) / 255;
                        g = g * ((tint >> 8) & 0xFF) / 255;
                        b = b * (tint & 0xFF) / 255;
                    }
                    pixels[y * GRID + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
        } finally {
            image.close();
        }

        java.util.List<float[]> quads = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();

        // front and back faces, merged horizontally
        for (int y = 0; y < GRID; y++) {
            int x = 0;
            while (x < GRID) {
                int color = pixels[y * GRID + x];
                if (color == 0) {
                    x++;
                    continue;
                }
                int run = 1;
                while (x + run < GRID && pixels[y * GRID + x + run] == color) {
                    run++;
                }
                addQuad(quads, colors, x, y, x + run, y + 1, DEPTH, color);
                addQuad(quads, colors, x, y, x + run, y + 1, 0.0f, shade(color, 0.55f));
                x += run;
            }
        }

        // side walls around every filled pixel
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int color = pixels[y * GRID + x];
                if (color == 0) {
                    continue;
                }
                int side = shade(color, 0.78f);
                int front = shade(color, 0.68f);
                if (empty(pixels, x + 1, y)) {
                    addWallX(quads, colors, x + 1, y, side);
                }
                if (empty(pixels, x - 1, y)) {
                    addWallX(quads, colors, x, y, side);
                }
                if (empty(pixels, x, y + 1)) {
                    addWallY(quads, colors, x, y + 1, front);
                }
                if (empty(pixels, x, y - 1)) {
                    addWallY(quads, colors, x, y, front);
                }
            }
        }

        float[] positions = new float[quads.size() * 12];
        int[] colorArray = new int[colors.size()];
        for (int i = 0; i < quads.size(); i++) {
            System.arraycopy(quads.get(i), 0, positions, i * 12, 12);
            colorArray[i] = colors.get(i);
        }
        return new Shape(positions, colorArray, quads.size(), DEPTH);
    }

    private static boolean empty(int[] pixels, int x, int y) {
        return x < 0 || y < 0 || x >= GRID || y >= GRID || pixels[y * GRID + x] == 0;
    }

    private static void addQuad(java.util.List<float[]> quads, java.util.List<Integer> colors,
                                float x0, float y0, float x1, float y1, float z, int color) {
        quads.add(new float[]{
                x0, y0, z,
                x1, y0, z,
                x1, y1, z,
                x0, y1, z
        });
        colors.add(color);
    }

    private static void addWallX(java.util.List<float[]> quads, java.util.List<Integer> colors,
                                 float x, int y, int color) {
        quads.add(new float[]{
                x, y, 0.0f,
                x, y + 1, 0.0f,
                x, y + 1, DEPTH,
                x, y, DEPTH
        });
        colors.add(color);
    }

    private static void addWallY(java.util.List<float[]> quads, java.util.List<Integer> colors,
                                 int x, float y, int color) {
        quads.add(new float[]{
                x, y, 0.0f,
                x + 1, y, 0.0f,
                x + 1, y, DEPTH,
                x, y, DEPTH
        });
        colors.add(color);
    }

    private static int shade(int argb, float factor) {
        int a = argb >>> 24;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, Math.round((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
