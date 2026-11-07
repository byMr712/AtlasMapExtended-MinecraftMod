package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.world.level.material.MapColor;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Stroke;
import org.webtrade.atlasmapextended.client.map.ChunkScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the framed relief with real side-wall strata and a frozen overlay
 * snapshot. Only the top block drives the height; the shallow block profile is
 * used to paint cut faces as dirt/stone layers.
 */
public final class FramedMapMesher {
    private static final int MAX_QUADS = 200_000;
    private static final float MAX_DEPTH = 96.0f;
    private static final float BASE_OFFSET = 0.05f;
    private static final float SIDE_SHADE = 0.78f;
    private static final float EDGE_SHADE = 0.68f;
    /** How far above the surface marker icons hover, in map pixels. */
    private static final float ICON_HOVER = 16.0f;

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

    private static final class Line {
        final float[] p = new float[6];
        final int color;

        Line(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
            p[0] = x0; p[1] = y0; p[2] = z0;
            p[3] = x1; p[4] = y1; p[5] = z1;
            this.color = color;
        }
    }

    public static FramedMapMesh build(FramedMapVolume volume, FramedMapKey key, int startY,
                                      FramedMapOverlay overlay) {
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
                int waterDepth = volume.water[index]
                        ? Math.max(1, volume.waterDepth[index] & 0xFF) : 0;
                colors[index] = ChunkScanner.papyrus(
                        volume.rgb[index],
                        volume.surfaceY[index],
                        hn, hw,
                        waterDepth,
                        wx, wz);
            }
        }

        List<Quad> quads = new ArrayList<>();
        List<Quad> fills = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        List<FramedMapMesh.VoxelIcon> icons = new ArrayList<>();
        float overlayZ = 0.0f;
        for (int i = 0; i < FramedMapVolume.COLUMNS; i++) {
            if (depth[i] > overlayZ) {
                overlayZ = depth[i];
            }
        }
        overlayZ += 4.0f;

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
                    return finish(quads, fills, lines, icons);
                }
                px += run;
            }
        }

        // side faces split into one-block layers so the cut shows real strata
        for (int pz = 0; pz < FramedMapVolume.SIZE; pz++) {
            for (int px = 0; px < FramedMapVolume.SIZE; px++) {
                int index = pz * FramedMapVolume.SIZE + px;
                float d = depth[index];
                if (d <= 0) {
                    continue;
                }
                int wx = key.originX() + px * scaleFactor;
                int wz = key.originZ() + pz * scaleFactor;
                int surfaceColor = colors[index];

                float east = px + 1 < FramedMapVolume.SIZE ? depth[index + 1] : 0;
                if (east < d) {
                    addWallEast(quads, volume, index, scaleFactor, wx, wz,
                            px + 1, pz, east, d, surfaceColor);
                }
                float west = px > 0 ? depth[index - 1] : 0;
                if (west < d) {
                    addWallWest(quads, volume, index, scaleFactor, wx, wz,
                            px, pz, west, d, surfaceColor);
                }
                float south = pz + 1 < FramedMapVolume.SIZE ? depth[index + FramedMapVolume.SIZE] : 0;
                if (south < d) {
                    addWallSouth(quads, volume, index, scaleFactor, wx, wz,
                            px, pz + 1, south, d, surfaceColor);
                }
                float north = pz > 0 ? depth[index - FramedMapVolume.SIZE] : 0;
                if (north < d) {
                    addWallNorth(quads, volume, index, scaleFactor, wx, wz,
                            px, pz, north, d, surfaceColor);
                }
                if (quads.size() >= MAX_QUADS) {
                    return finish(quads, fills, lines, icons);
                }
            }
        }

        if (overlay != null) {
            addOverlay(volume, overlayZ, fills, lines, icons, overlay, scaleFactor);
        }
        return finish(quads, fills, lines, icons);
    }

    private static void addWallEast(List<Quad> quads, FramedMapVolume volume, int index, int scaleFactor,
                                    int wx, int wz, float x, float z, float from, float to, int surfaceColor) {
        for (float a = from; a < to; ) {
            float b = Math.min(to, a + 1.0f);
            int color = wallColor(volume, index, scaleFactor, wx, wz, to, (a + b) * 0.5f,
                    surfaceColor, SIDE_SHADE);
            quads.add(new Quad(x, z, a, x, z + 1, a, x, z + 1, b, x, z, b, color));
            a = b;
        }
    }

    private static void addWallWest(List<Quad> quads, FramedMapVolume volume, int index, int scaleFactor,
                                    int wx, int wz, float x, float z, float from, float to, int surfaceColor) {
        for (float a = from; a < to; ) {
            float b = Math.min(to, a + 1.0f);
            int color = wallColor(volume, index, scaleFactor, wx, wz, to, (a + b) * 0.5f,
                    surfaceColor, SIDE_SHADE);
            quads.add(new Quad(x, z + 1, a, x, z, a, x, z, b, x, z + 1, b, color));
            a = b;
        }
    }

    private static void addWallSouth(List<Quad> quads, FramedMapVolume volume, int index, int scaleFactor,
                                     int wx, int wz, float x, float z, float from, float to, int surfaceColor) {
        for (float a = from; a < to; ) {
            float b = Math.min(to, a + 1.0f);
            int color = wallColor(volume, index, scaleFactor, wx, wz, to, (a + b) * 0.5f,
                    surfaceColor, EDGE_SHADE);
            quads.add(new Quad(x + 1, z, a, x, z, a, x, z, b, x + 1, z, b, color));
            a = b;
        }
    }

    private static void addWallNorth(List<Quad> quads, FramedMapVolume volume, int index, int scaleFactor,
                                     int wx, int wz, float x, float z, float from, float to, int surfaceColor) {
        for (float a = from; a < to; ) {
            float b = Math.min(to, a + 1.0f);
            int color = wallColor(volume, index, scaleFactor, wx, wz, to, (a + b) * 0.5f,
                    surfaceColor, EDGE_SHADE);
            quads.add(new Quad(x, z, a, x + 1, z, a, x + 1, z, b, x, z, b, color));
            a = b;
        }
    }

    /** Picks the sampled block colour for the wall segment, falling back to the surface colour. */
    private static int wallColor(FramedMapVolume volume, int index, int scaleFactor,
                                 int wx, int wz, float topDepth, float midDepth,
                                 int surfaceColor, float factor) {
        int below = Math.round((topDepth - midDepth) * scaleFactor);
        if (below <= 0) {
            return shade(surfaceColor, factor);
        }
        int layer = Math.min(FramedMapVolume.LAYERS - 1, below - 1);
        int packed = volume.layerColor[index * FramedMapVolume.LAYERS + layer] & 0xFF;
        if (packed == 0) {
            return shade(surfaceColor, factor);
        }
        int rgb = MapColor.byId(packed >> 2).col;
        return shade(ChunkScanner.papyrus(rgb, 0, 0, 0, 0, wx, wz), factor);
    }

    private static void addOverlay(FramedMapVolume volume, float overlayZ,
                                   List<Quad> fills, List<Line> lines,
                                   List<FramedMapMesh.VoxelIcon> icons,
                                   FramedMapOverlay overlay, int scaleFactor) {
        for (Marker marker : overlay.markers) {
            int px = pixelX(overlay, scaleFactor, marker.x);
            int pz = pixelZ(overlay, scaleFactor, marker.z);
            if (px < 4 || pz < 4 || px >= FramedMapVolume.SIZE - 4 || pz >= FramedMapVolume.SIZE - 4) {
                continue;
            }
            int index = pz * FramedMapVolume.SIZE + px;
            if (!volume.valid[index]) {
                continue;
            }
            String iconName = marker.icon == null || marker.icon.isBlank() ? "pin" : marker.icon;
            icons.add(new FramedMapMesh.VoxelIcon(iconName, marker.color, px, pz, overlayZ + 8.0f));
        }

        float lineZ = overlayZ + 0.5f;
        for (Region region : overlay.regions) {
            if (region.filled) {
                addRegionFill(fills, overlay, region, scaleFactor, overlayZ);
            }
            double[] pts = region.pts;
            for (int i = 0; i + 3 < pts.length; i += 2) {
                addLine(overlay, scaleFactor, lineZ, lines,
                        pts[i], pts[i + 1], pts[i + 2], pts[i + 3], region.color);
            }
            if (pts.length >= 4) {
                addLine(overlay, scaleFactor, lineZ, lines,
                        pts[pts.length - 2], pts[pts.length - 1], pts[0], pts[1], region.color);
            }
        }

        for (Stroke stroke : overlay.strokes) {
            for (int i = 0; i + 3 < stroke.pts.length; i += 2) {
                addLine(overlay, scaleFactor, lineZ, lines,
                        stroke.pts[i], stroke.pts[i + 1], stroke.pts[i + 2], stroke.pts[i + 3], stroke.color);
            }
        }
    }

    /** Flat translucent fill, rasterised per map pixel like the 2D region fill. */
    private static void addRegionFill(List<Quad> fills, FramedMapOverlay overlay, Region region,
                                      int scaleFactor, float z) {
        double[] b = region.bounds();
        int px0 = pixelX(overlay, scaleFactor, b[0]);
        int px1 = pixelX(overlay, scaleFactor, b[1]);
        int pz0 = pixelZ(overlay, scaleFactor, b[2]);
        int pz1 = pixelZ(overlay, scaleFactor, b[3]);
        px0 = Math.max(0, px0);
        pz0 = Math.max(0, pz0);
        px1 = Math.min(FramedMapVolume.SIZE - 1, px1);
        pz1 = Math.min(FramedMapVolume.SIZE - 1, pz1);
        int color = (region.color & 0xFFFFFF) | 0x55000000;
        for (int pz = pz0; pz <= pz1; pz++) {
            double wz = overlay.centerZ + (pz + 0.5 - 64.0) * scaleFactor;
            int runStart = -1;
            for (int px = px0; px <= px1 + 1; px++) {
                boolean inside = false;
                if (px <= px1) {
                    double wx = overlay.centerX + (px + 0.5 - 64.0) * scaleFactor;
                    inside = region.contains(wx, wz);
                }
                if (inside && runStart < 0) {
                    runStart = px;
                } else if (!inside && runStart >= 0) {
                    fills.add(new Quad(runStart, pz, z,
                            runStart, pz + 1, z,
                            px, pz + 1, z,
                            px, pz, z, color));
                    runStart = -1;
                }
            }
        }
    }

    private static void addLine(FramedMapOverlay overlay, int scaleFactor,
                                float z, List<Line> lines,
                                double x0, double z0, double x1, double z1, int color) {
        int px0 = pixelX(overlay, scaleFactor, x0);
        int pz0 = pixelZ(overlay, scaleFactor, z0);
        int px1 = pixelX(overlay, scaleFactor, x1);
        int pz1 = pixelZ(overlay, scaleFactor, z1);
        if (!inside(px0, pz0) || !inside(px1, pz1)) {
            return;
        }
        lines.add(new Line(px0, pz0, z, px1, pz1, z, color));
    }

    private static boolean inside(int px, int pz) {
        return px >= 0 && pz >= 0 && px < FramedMapVolume.SIZE && pz < FramedMapVolume.SIZE;
    }

    private static int pixelX(FramedMapOverlay overlay, int scaleFactor, double worldX) {
        return (int) Math.round(64.0 + (worldX - overlay.centerX) / scaleFactor);
    }

    private static int pixelZ(FramedMapOverlay overlay, int scaleFactor, double worldZ) {
        return (int) Math.round(64.0 + (worldZ - overlay.centerZ) / scaleFactor);
    }

    private static FramedMapMesh finish(List<Quad> quads, List<Quad> fills, List<Line> lines,
                                        List<FramedMapMesh.VoxelIcon> icons) {
        float[] positions = new float[quads.size() * 12];
        int[] colors = new int[quads.size()];
        for (int i = 0; i < quads.size(); i++) {
            Quad quad = quads.get(i);
            System.arraycopy(quad.p, 0, positions, i * 12, 12);
            colors[i] = quad.color;
        }
        float[] fillPositions = new float[fills.size() * 12];
        int[] fillColors = new int[fills.size()];
        for (int i = 0; i < fills.size(); i++) {
            Quad quad = fills.get(i);
            System.arraycopy(quad.p, 0, fillPositions, i * 12, 12);
            fillColors[i] = quad.color;
        }
        float[] linePositions = new float[lines.size() * 6];
        int[] lineColors = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            System.arraycopy(line.p, 0, linePositions, i * 6, 6);
            lineColors[i] = line.color;
        }
        return new FramedMapMesh(positions, colors, quads.size(), linePositions, lineColors,
                lines.size(), fillPositions, fillColors, fills.size(), icons);
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
