package org.webtrade.atlasmapextended.client.framed3d;

import org.webtrade.atlasmapextended.client.map.ChunkScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a surface-only relief. Columns at or below the base height stay flat;
 * everything above is extruded towards the viewer. Sides exist only for the
 * visible height difference between neighbouring surface columns.
 */
public final class FramedMapMesher {
    private static final int MAX_QUADS = 120_000;
    private static final float MAX_DEPTH = 96.0f;
    private static final float BASE_OFFSET = 0.05f;

    private FramedMapMesher() {
    }

    private static final class Quad {
        final float[] p = new float[12];
        final int color;

        Quad(float x0, float y0, float z0, float x1, float y1, float z1,
             float x2, float y2, float z2, float x3, float y3, float z3, int color) {
            p[0] = x0; p[1] = y0; p[2] = z0;
            p[3] = x1; p[4] = y1; p[5] = z1;
            p[6] = x2; p[7] = y2; p[8] = z2;
            p[9] = x3; p[10] = y3; p[11] = z3;
            this.color = color;
        }
    }

    public static FramedMapMesh build(FramedMapVolume volume, FramedMapKey key, int startY) {
        float[] depth = new float[FramedMapVolume.COLUMNS];
        int[] colors = new int[FramedMapVolume.COLUMNS];
        int scaleFactor = key.scaleFactor();

        for (int pz = 0; pz < FramedMapVolume.SIZE; pz++) {
            for (int px = 0; px < FramedMapVolume.SIZE; px++) {
                int index = pz * FramedMapVolume.SIZE + px;
                if (!volume.valid[index] || volume.surfaceY[index] <= startY) {
                    continue;
                }
                int hn = neighbourHeight(volume, px, pz - 1, volume.surfaceY[index]);
                int hw = neighbourHeight(volume, px - 1, pz, volume.surfaceY[index]);
                int wx = key.originX() + px * scaleFactor;
                int wz = key.originZ() + pz * scaleFactor;
                float relief = (volume.surfaceY[index] - startY) * (1.0f / scaleFactor);
                depth[index] = Math.min(MAX_DEPTH, relief) + BASE_OFFSET;
                colors[index] = ChunkScanner.papyrus(
                        volume.rgb[index],
                        volume.surfaceY[index],
                        hn, hw,
                        volume.water[index] ? 1 : 0,
                        wx, wz);
            }
        }

        List<Quad> quads = new ArrayList<>();

        // top faces, merged horizontally while colour and depth match
        for (int pz = 0; pz < FramedMapVolume.SIZE; pz++) {
            int px = 0;
            while (px < FramedMapVolume.SIZE) {
                int index = pz * FramedMapVolume.SIZE + px;
                float d = depth[index];
                if (d <= 0) {
                    px++;
                    continue;
                }
                int color = colors[index];
                int run = 1;
                while (px + run < FramedMapVolume.SIZE) {
                    int next = pz * FramedMapVolume.SIZE + px + run;
                    if (depth[next] != d || colors[next] != color) {
                        break;
                    }
                    run++;
                }
                quads.add(new Quad(
                        px, pz, d,
                        px, pz + 1, d,
                        px + run, pz + 1, d,
                        px + run, pz, d,
                        color));
                if (quads.size() >= MAX_QUADS) {
                    return finish(quads);
                }
                px += run;
            }
        }

        // side faces between surface heights; no underground geometry
        for (int pz = 0; pz < FramedMapVolume.SIZE; pz++) {
            for (int px = 0; px < FramedMapVolume.SIZE; px++) {
                int index = pz * FramedMapVolume.SIZE + px;
                float d = depth[index];
                if (d <= 0 || volume.water[index]) {
                    continue;
                }
                int sideColor = shade(colors[index], 0.78f);
                int edgeColor = shade(colors[index], 0.68f);

                float east = px + 1 < FramedMapVolume.SIZE ? depth[index + 1] : 0;
                if (east < d) {
                    quads.add(new Quad(
                            px + 1, pz, east,
                            px + 1, pz + 1, east,
                            px + 1, pz + 1, d,
                            px + 1, pz, d,
                            sideColor));
                }
                float west = px > 0 ? depth[index - 1] : 0;
                if (west < d) {
                    quads.add(new Quad(
                            px, pz + 1, west,
                            px, pz, west,
                            px, pz, d,
                            px, pz + 1, d,
                            sideColor));
                }
                float south = pz + 1 < FramedMapVolume.SIZE ? depth[index + FramedMapVolume.SIZE] : 0;
                if (south < d) {
                    quads.add(new Quad(
                            px + 1, pz + 1, south,
                            px, pz + 1, south,
                            px, pz + 1, d,
                            px + 1, pz + 1, d,
                            edgeColor));
                }
                float north = pz > 0 ? depth[index - FramedMapVolume.SIZE] : 0;
                if (north < d) {
                    quads.add(new Quad(
                            px, pz, north,
                            px + 1, pz, north,
                            px + 1, pz, d,
                            px, pz, d,
                            edgeColor));
                }
                if (quads.size() >= MAX_QUADS) {
                    return finish(quads);
                }
            }
        }

        return finish(quads);
    }

    private static FramedMapMesh finish(List<Quad> quads) {
        float[] positions = new float[quads.size() * 12];
        int[] colors = new int[quads.size()];
        for (int i = 0; i < quads.size(); i++) {
            Quad quad = quads.get(i);
            System.arraycopy(quad.p, 0, positions, i * 12, 12);
            colors[i] = quad.color;
        }
        return new FramedMapMesh(positions, colors, quads.size());
    }

    private static int neighbourHeight(FramedMapVolume volume, int px, int pz, int fallback) {
        if (px < 0 || pz < 0 || px >= FramedMapVolume.SIZE || pz >= FramedMapVolume.SIZE) {
            return fallback;
        }
        int index = pz * FramedMapVolume.SIZE + px;
        return volume.valid[index] ? volume.surfaceY[index] : fallback;
    }

    private static int shade(int argb, float factor) {
        int a = argb >>> 24;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, Math.round((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
