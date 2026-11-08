package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.world.level.material.MapColor;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Stroke;
import org.webtrade.atlasmapextended.client.map.ChunkScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the framed relief with real side-wall strata and a frozen overlay
 * snapshot. Only the top block drives the height; the shallow block profile is
 * used to paint cut faces as dirt/stone layers.
 */
public final class FramedMapMesher {
    private static final int MAX_QUADS = 200_000;
    private static final float MAX_DEPTH = 96.0f;
    /** Keeps the relief clearly in front of the flat map and avoids z-fighting. */
    private static final float BASE_OFFSET = 0.5f;
    /** The internal seam is closed by a real wall, not by overlapping top faces. */
    private static final float SEAM_OVERLAP = 0.0f;
    private static final float SIDE_SHADE = 0.78f;
    private static final float EDGE_SHADE = 0.68f;
    /** How far above the surface marker icons hover, in map pixels. */
    private static final float ICON_HOVER = 6.0f;
    private static final int REGION_FILL_ALPHA = 0x55;

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

    private static final int WALL_EAST = 0;
    private static final int WALL_WEST = 1;
    private static final int WALL_SOUTH = 2;
    private static final int WALL_NORTH = 3;

    private record WallKey(int dir, float plane, float from, float to, int color) {
    }

    /**
     * Collects wall strips and merges adjacent strips with the same plane,
     * height and colour. This is the same surface as drawing every column
     * separately, just with far fewer quads.
     */
    private static final class WallCollector {
        private final Map<WallKey, List<float[]>> spans = new HashMap<>();

        void add(int dir, float plane, float start, float end, float from, float to, int color) {
            spans.computeIfAbsent(new WallKey(dir, plane, from, to, color), k -> new ArrayList<>())
                    .add(new float[]{start, end});
        }

        void emit(List<Quad> quads) {
            for (Map.Entry<WallKey, List<float[]>> entry : spans.entrySet()) {
                WallKey key = entry.getKey();
                List<float[]> list = entry.getValue();
                list.sort((a, b) -> Float.compare(a[0], b[0]));
                float start = list.get(0)[0];
                float end = list.get(0)[1];
                for (int i = 1; i < list.size(); i++) {
                    float[] span = list.get(i);
                    if (span[0] <= end + 1e-4f) {
                        if (span[1] > end) {
                            end = span[1];
                        }
                    } else {
                        emitQuad(quads, key, start, end);
                        if (quads.size() >= MAX_QUADS) {
                            return;
                        }
                        start = span[0];
                        end = span[1];
                    }
                }
                emitQuad(quads, key, start, end);
                if (quads.size() >= MAX_QUADS) {
                    return;
                }
            }
        }

        private static void emitQuad(List<Quad> quads, WallKey key, float start, float end) {
            float plane = key.plane();
            float from = key.from();
            float to = key.to();
            switch (key.dir()) {
                case WALL_EAST -> quads.add(new Quad(
                        plane, start, from, plane, end, from, plane, end, to, plane, start, to,
                        key.color()));
                case WALL_WEST -> quads.add(new Quad(
                        plane, end, from, plane, start, from, plane, start, to, plane, end, to,
                        key.color()));
                case WALL_SOUTH -> quads.add(new Quad(
                        end, plane, from, start, plane, from, start, plane, to, end, plane, to,
                        key.color()));
                case WALL_NORTH -> quads.add(new Quad(
                        start, plane, from, end, plane, from, end, plane, to, start, plane, to,
                        key.color()));
                default -> {
                }
            }
        }
    }

    public static FramedMapMesh build(FramedMapVolume volume, FramedMapKey key, int startY,
                                      FramedMapOverlay overlay, int textureMask,
                                      int tileDiv, int tileCol, int tileRow, int tileRotation) {
        int div = Math.max(1, tileDiv);
        int rotation = div > 1 ? tileRotation & 3 : 0;
        int dstX0 = div == 1 ? 0 : tileCol * FramedMapVolume.SIZE / div;
        int dstX1 = div == 1 ? FramedMapVolume.SIZE : (tileCol + 1) * FramedMapVolume.SIZE / div;
        int dstZ0 = div == 1 ? 0 : tileRow * FramedMapVolume.SIZE / div;
        int dstZ1 = div == 1 ? FramedMapVolume.SIZE : (tileRow + 1) * FramedMapVolume.SIZE / div;
        FramedMapVolume source = volume;
        if (div > 1) {
            FramedMapVolume sampled = new FramedMapVolume();
            sampled.clear();
            for (int pz = 0; pz < FramedMapVolume.SIZE; pz++) {
                int rz = dstZ0 + (int) ((long) pz * (dstZ1 - dstZ0) / FramedMapVolume.SIZE);
                for (int px = 0; px < FramedMapVolume.SIZE; px++) {
                    int rx = dstX0 + (int) ((long) px * (dstX1 - dstX0) / FramedMapVolume.SIZE);
                    int sx = FramedMapConnections.unrotateDisplayX(rx, rz, rotation);
                    int sz = FramedMapConnections.unrotateDisplayZ(rx, rz, rotation);
                    int from = sz * FramedMapVolume.SIZE + sx;
                    int to = pz * FramedMapVolume.SIZE + px;
                    if (!source.valid[from]) {
                        continue;
                    }
                    sampled.valid[to] = true;
                    sampled.surfaceY[to] = source.surfaceY[from];
                    sampled.rgb[to] = source.rgb[from];
                    sampled.water[to] = source.water[from];
                    sampled.waterDepth[to] = source.waterDepth[from];
                    System.arraycopy(source.layerColor, from * FramedMapVolume.LAYERS,
                            sampled.layerColor, to * FramedMapVolume.LAYERS, FramedMapVolume.LAYERS);
                }
            }
            volume = sampled;
        }

        float[] depth = new float[FramedMapVolume.COLUMNS];
        int[] colors = new int[FramedMapVolume.COLUMNS];
        int scaleFactor = key.scaleFactor();
        // A mega-map stretches one source block over {@code div} mesh units in
        // both axes, so the relief cap must stretch with it. Capping at the
        // single-map 96 units flattened every tile bigger than ~2x2.
        float maxDepth = MAX_DEPTH * div;

        for (int pz = 0; pz < FramedMapVolume.SIZE; pz++) {
            for (int px = 0; px < FramedMapVolume.SIZE; px++) {
                int index = pz * FramedMapVolume.SIZE + px;
                if (!volume.valid[index]) {
                    continue;
                }
                int hn = neighbourHeight(volume, px, pz - 1, volume.surfaceY[index]);
                int hw = neighbourHeight(volume, px - 1, pz, volume.surfaceY[index]);
                int rx = dstX0 + (int) ((long) px * (dstX1 - dstX0) / FramedMapVolume.SIZE);
                int rz = dstZ0 + (int) ((long) pz * (dstZ1 - dstZ0) / FramedMapVolume.SIZE);
                int sx = FramedMapConnections.unrotateDisplayX(rx, rz, rotation);
                int sz = FramedMapConnections.unrotateDisplayZ(rx, rz, rotation);
                int wx = key.originX() + sx * scaleFactor;
                int wz = key.originZ() + sz * scaleFactor;
                // One map pixel is one terrain block tall even on zoomed-out
                // maps. Dividing by the map scale made large maps almost flat.
                float relief = Math.max(0, volume.surfaceY[index] - startY) * div;
                depth[index] = Math.min(maxDepth, relief) + BASE_OFFSET;
                int waterDepth = volume.water[index]
                        ? Math.max(1, volume.waterDepth[index] & 0xFF) : 0;
                colors[index] = ChunkScanner.papyrus(
                        volume.rgb[index],
                        volume.surfaceY[index],
                        hn, hw,
                        waterDepth,
                        wx, wz);
                if (overlay != null) {
                    double cx = wx + scaleFactor * 0.5;
                    double cz = wz + scaleFactor * 0.5;
                    for (Region region : overlay.regions) {
                        if (region.filled && !region.hidden && region.contains(cx, cz)) {
                            colors[index] = blendFill(colors[index], region.color);
                        }
                    }
                }
            }
        }

        List<Quad> quads = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        List<FramedMapMesh.VoxelIcon> icons = new ArrayList<>();

        // top faces, greedily merged into colour/depth rectangles
        boolean[] used = new boolean[FramedMapVolume.COLUMNS];
        for (int pz = 0; pz < FramedMapVolume.SIZE; pz++) {
            for (int px = 0; px < FramedMapVolume.SIZE; px++) {
                int index = pz * FramedMapVolume.SIZE + px;
                if (used[index]) {
                    continue;
                }
                float d = depth[index];
                if (d <= 0) {
                    used[index] = true;
                    continue;
                }
                int color = colors[index];
                int width = 1;
                while (px + width < FramedMapVolume.SIZE) {
                    int next = index + width;
                    if (used[next] || depth[next] != d || colors[next] != color) {
                        break;
                    }
                    width++;
                }
                int height = 1;
                outer:
                while (pz + height < FramedMapVolume.SIZE) {
                    int row = (pz + height) * FramedMapVolume.SIZE + px;
                    for (int x = 0; x < width; x++) {
                        int next = row + x;
                        if (used[next] || depth[next] != d || colors[next] != color) {
                            break outer;
                        }
                    }
                    height++;
                }
                for (int z = 0; z < height; z++) {
                    int row = (pz + z) * FramedMapVolume.SIZE + px;
                    for (int x = 0; x < width; x++) {
                        used[row + x] = true;
                    }
                }
                float x0 = px == 0 && (textureMask & FramedMapConnections.TEX_LEFT) != 0
                        ? -SEAM_OVERLAP : px;
                float x1 = px + width == FramedMapVolume.SIZE
                        && (textureMask & FramedMapConnections.TEX_RIGHT) != 0
                        ? FramedMapVolume.SIZE + SEAM_OVERLAP : px + width;
                float z0 = pz == 0 && (textureMask & FramedMapConnections.TEX_TOP) != 0
                        ? -SEAM_OVERLAP : pz;
                float z1 = pz + height == FramedMapVolume.SIZE
                        && (textureMask & FramedMapConnections.TEX_BOTTOM) != 0
                        ? FramedMapVolume.SIZE + SEAM_OVERLAP : pz + height;
                quads.add(new Quad(
                        x0, z0, d,
                        x0, z1, d,
                        x1, z1, d,
                        x1, z0, d,
                        color));
                if (quads.size() >= MAX_QUADS) {
                    return finish(quads, lines, icons);
                }
            }
        }

        // side faces split into one-block layers so the cut shows real strata
        WallCollector walls = new WallCollector();
        for (int pz = 0; pz < FramedMapVolume.SIZE; pz++) {
            for (int px = 0; px < FramedMapVolume.SIZE; px++) {
                int index = pz * FramedMapVolume.SIZE + px;
                float d = depth[index];
                if (d <= 0) {
                    continue;
                }
                int sx = dstX0 + (int) ((long) px * (dstX1 - dstX0) / FramedMapVolume.SIZE);
                int sz = dstZ0 + (int) ((long) pz * (dstZ1 - dstZ0) / FramedMapVolume.SIZE);
                int wx = key.originX() + sx * scaleFactor;
                int wz = key.originZ() + sz * scaleFactor;
                int surfaceColor = colors[index];

                // Neighbouring maps continue the surface. For a mega-map the
                // next source column belongs to the same map, so close the
                // seam with a real wall; overlapping top faces caused
                // z-fighting and holes at the centre.
                float east = px + 1 < FramedMapVolume.SIZE ? depth[index + 1]
                        : seamDepth(source, volume, startY, div, dstX0, dstX1, dstZ0, dstZ1, rotation, px, pz, 1, 0, d, (textureMask & FramedMapConnections.TEX_RIGHT) != 0);
                if (east < d) {
                    addWallEast(walls, volume, index, scaleFactor, wx, wz,
                            px + 1, pz, east, d, surfaceColor, div);
                }
                float west = px > 0 ? depth[index - 1]
                        : seamDepth(source, volume, startY, div, dstX0, dstX1, dstZ0, dstZ1, rotation, px, pz, -1, 0, d, (textureMask & FramedMapConnections.TEX_LEFT) != 0);
                if (west < d) {
                    addWallWest(walls, volume, index, scaleFactor, wx, wz,
                            px, pz, west, d, surfaceColor, div);
                }
                float south = pz + 1 < FramedMapVolume.SIZE ? depth[index + FramedMapVolume.SIZE]
                        : seamDepth(source, volume, startY, div, dstX0, dstX1, dstZ0, dstZ1, rotation, px, pz, 0, 1, d, (textureMask & FramedMapConnections.TEX_BOTTOM) != 0);
                if (south < d) {
                    addWallSouth(walls, volume, index, scaleFactor, wx, wz,
                            pz + 1, px, south, d, surfaceColor, div);
                }
                float north = pz > 0 ? depth[index - FramedMapVolume.SIZE]
                        : seamDepth(source, volume, startY, div, dstX0, dstX1, dstZ0, dstZ1, rotation, px, pz, 0, -1, d, (textureMask & FramedMapConnections.TEX_TOP) != 0);
                if (north < d) {
                    addWallNorth(walls, volume, index, scaleFactor, wx, wz,
                            pz, px, north, d, surfaceColor, div);
                }
                if (quads.size() >= MAX_QUADS) {
                    return finish(quads, lines, icons);
                }
            }
        }
        walls.emit(quads);
        if (quads.size() >= MAX_QUADS) {
            return finish(quads, lines, icons);
        }

        if (overlay != null) {
            addOverlay(volume, startY, depth, lines, icons, overlay, scaleFactor,
                    div, dstX0, dstX1, dstZ0, dstZ1, rotation);
        }
        return finish(quads, lines, icons);
    }

    /**
     * Depth of the next source column at an internal tile seam. For normal
     * maps the old behaviour is kept (skip the wall); for a mega-map the
     * source volume contains the neighbour tile and we can close the gap.
     */
    private static float seamDepth(FramedMapVolume source, FramedMapVolume tile, int startY, int div,
                                   int dstX0, int dstX1, int dstZ0, int dstZ1, int rotation,
                                   int px, int pz, int dx, int dz, float own, boolean internal) {
        if (!internal) {
            return 0;
        }
        if (div <= 1) {
            return own;
        }
        int rx = dstX0 + px * (dstX1 - dstX0) / FramedMapVolume.SIZE;
        int rz = dstZ0 + pz * (dstZ1 - dstZ0) / FramedMapVolume.SIZE;
        int nrx = rx + dx;
        int nrz = rz + dz;
        if (nrx < 0 || nrz < 0 || nrx >= FramedMapVolume.SIZE || nrz >= FramedMapVolume.SIZE) {
            return own;
        }
        int sx = FramedMapConnections.unrotateDisplayX(nrx, nrz, rotation);
        int sz = FramedMapConnections.unrotateDisplayZ(nrx, nrz, rotation);
        int ni = sz * FramedMapVolume.SIZE + sx;
        if (!source.valid[ni]) {
            return own;
        }
        float neighbor = Math.max(0, source.surfaceY[ni] - startY) * div;
        return Math.min(MAX_DEPTH * div, neighbor) + BASE_OFFSET;
    }

    private static void addWallEast(WallCollector walls, FramedMapVolume volume, int index, int scaleFactor,
                                    int wx, int wz, float plane, int spanStart, float from, float to,
                                    int surfaceColor, int div) {
        addWall(walls, WALL_EAST, plane, spanStart, volume, index, scaleFactor, wx, wz,
                from, to, surfaceColor, SIDE_SHADE, div);
    }

    private static void addWallWest(WallCollector walls, FramedMapVolume volume, int index, int scaleFactor,
                                    int wx, int wz, float plane, int spanStart, float from, float to,
                                    int surfaceColor, int div) {
        addWall(walls, WALL_WEST, plane, spanStart, volume, index, scaleFactor, wx, wz,
                from, to, surfaceColor, SIDE_SHADE, div);
    }

    private static void addWallSouth(WallCollector walls, FramedMapVolume volume, int index, int scaleFactor,
                                     int wx, int wz, float plane, int spanStart, float from, float to,
                                     int surfaceColor, int div) {
        addWall(walls, WALL_SOUTH, plane, spanStart, volume, index, scaleFactor, wx, wz,
                from, to, surfaceColor, EDGE_SHADE, div);
    }

    private static void addWallNorth(WallCollector walls, FramedMapVolume volume, int index, int scaleFactor,
                                     int wx, int wz, float plane, int spanStart, float from, float to,
                                     int surfaceColor, int div) {
        addWall(walls, WALL_NORTH, plane, spanStart, volume, index, scaleFactor, wx, wz,
                from, to, surfaceColor, EDGE_SHADE, div);
    }

    private static void addWall(WallCollector walls, int dir, float plane, int spanStart,
                                FramedMapVolume volume, int index, int scaleFactor,
                                int wx, int wz, float from, float to, int surfaceColor,
                                float factor, int div) {
        float a = from;
        while (a < to) {
            float b = Math.min(to, a + div);
            int color = wallColor(volume, index, scaleFactor, wx, wz, to, (a + b) * 0.5f,
                    surfaceColor, factor, div);
            while (b < to) {
                float c = Math.min(to, b + div);
                int next = wallColor(volume, index, scaleFactor, wx, wz, to, (b + c) * 0.5f,
                        surfaceColor, factor, div);
                if (next != color) {
                    break;
                }
                b = c;
            }
            walls.add(dir, plane, spanStart, spanStart + 1, a, b, color);
            a = b;
        }
    }

    /** Picks the sampled block colour for the wall segment, falling back to the surface colour. */
    private static int wallColor(FramedMapVolume volume, int index, int scaleFactor,
                                 int wx, int wz, float topDepth, float midDepth,
                                 int surfaceColor, float factor, int div) {
        // One terrain block is {@code div} mesh units, so a wall slice must
        // step by div to show the real strata instead of compressed stripes.
        float belowTop = topDepth - midDepth;
        if (belowTop < 0.5f * div) {
            // The thin partial slice at the very top of an outer wall.
            return shade(surfaceColor, factor);
        }
        int layer = Math.min(FramedMapVolume.LAYERS - 1, (int) Math.floor(belowTop / div));
        int packed = volume.layerColor[index * FramedMapVolume.LAYERS + layer] & 0xFF;
        if (packed == 0) {
            return shade(surfaceColor, factor);
        }
        int rgb = MapColor.byId(packed >> 2).col;
        return shade(ChunkScanner.papyrus(rgb, 0, 0, 0, 0, wx, wz), factor);
    }

    private static void addOverlay(FramedMapVolume volume, int startY, float[] depth,
                                   List<Line> lines, List<FramedMapMesh.VoxelIcon> icons,
                                   FramedMapOverlay overlay, int scaleFactor,
                                   int div, int dstX0, int dstX1, int dstZ0, int dstZ1, int rotation) {
        for (Marker marker : overlay.markers) {
            int fullX = pixelX(overlay, scaleFactor, marker.x);
            int fullZ = pixelZ(overlay, scaleFactor, marker.z);
            int displayX = FramedMapConnections.rotateSourceX(fullX, fullZ, rotation);
            int displayZ = FramedMapConnections.rotateSourceZ(fullX, fullZ, rotation);
            if (displayX < dstX0 + 4 || displayZ < dstZ0 + 4
                    || displayX >= dstX1 - 4 || displayZ >= dstZ1 - 4) {
                continue;
            }
            int px = tilePixel(displayX, dstX0, dstX1);
            int pz = tilePixel(displayZ, dstZ0, dstZ1);
            if (px < 0 || pz < 0 || px >= FramedMapVolume.SIZE || pz >= FramedMapVolume.SIZE) {
                continue;
            }
            int index = pz * FramedMapVolume.SIZE + px;
            if (!volume.valid[index]) {
                continue;
            }
            float base = Math.max(0.5f, depth[index]);
            String iconName = marker.icon == null || marker.icon.isBlank() ? "pin" : marker.icon;
            icons.add(new FramedMapMesh.VoxelIcon(iconName, marker.color, px, pz,
                    base + ICON_HOVER * div));
        }

        for (Region region : overlay.regions) {
            double[] pts = region.pts;
            for (int i = 0; i + 3 < pts.length; i += 2) {
                addLine(volume, overlay, scaleFactor, startY, lines,
                        pts[i], pts[i + 1], pts[i + 2], pts[i + 3], region.color,
                        dstX0, dstX1, dstZ0, dstZ1, div, rotation);
            }
            if (pts.length >= 4) {
                addLine(volume, overlay, scaleFactor, startY, lines,
                        pts[pts.length - 2], pts[pts.length - 1], pts[0], pts[1], region.color,
                        dstX0, dstX1, dstZ0, dstZ1, div, rotation);
            }
        }

        for (Stroke stroke : overlay.strokes) {
            for (int i = 0; i + 3 < stroke.pts.length; i += 2) {
                addLine(volume, overlay, scaleFactor, startY, lines,
                        stroke.pts[i], stroke.pts[i + 1], stroke.pts[i + 2], stroke.pts[i + 3],
                        stroke.color, dstX0, dstX1, dstZ0, dstZ1, div, rotation);
            }
        }
    }

    private static int tilePixel(int full, int from, int to) {
        if (full < from || full >= to) {
            return -1;
        }
        return (full - from) * FramedMapVolume.SIZE / Math.max(1, to - from);
    }

    private static void addLine(FramedMapVolume volume, FramedMapOverlay overlay, int scaleFactor,
                                int startY, List<Line> lines,
                                double x0, double z0, double x1, double z1, int color,
                                int dstX0, int dstX1, int dstZ0, int dstZ1, int div, int rotation) {
        int fullX0 = FramedMapConnections.rotateSourceX(
                pixelX(overlay, scaleFactor, x0), pixelZ(overlay, scaleFactor, z0), rotation);
        int fullZ0 = FramedMapConnections.rotateSourceZ(
                pixelX(overlay, scaleFactor, x0), pixelZ(overlay, scaleFactor, z0), rotation);
        int fullX1 = FramedMapConnections.rotateSourceX(
                pixelX(overlay, scaleFactor, x1), pixelZ(overlay, scaleFactor, z1), rotation);
        int fullZ1 = FramedMapConnections.rotateSourceZ(
                pixelX(overlay, scaleFactor, x1), pixelZ(overlay, scaleFactor, z1), rotation);
        int px0 = tilePixel(fullX0, dstX0, dstX1);
        int pz0 = tilePixel(fullZ0, dstZ0, dstZ1);
        int px1 = tilePixel(fullX1, dstX0, dstX1);
        int pz1 = tilePixel(fullZ1, dstZ0, dstZ1);
        if (px0 < 0 || pz0 < 0 || px1 < 0 || pz1 < 0) {
            return;
        }
        if (!inside(px0, pz0) || !inside(px1, pz1)) {
            return;
        }
        int i0 = pz0 * FramedMapVolume.SIZE + px0;
        int i1 = pz1 * FramedMapVolume.SIZE + px1;
        if (!volume.valid[i0] || !volume.valid[i1]) {
            return;
        }
        float maxDepth = MAX_DEPTH * div;
        float d0 = Math.max(0.3f, (volume.surfaceY[i0] - startY) * div) + 1.5f;
        float d1 = Math.max(0.3f, (volume.surfaceY[i1] - startY) * div) + 1.5f;
        lines.add(new Line(px0, pz0, Math.min(maxDepth, d0),
                px1, pz1, Math.min(maxDepth, d1), color));
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

    private static FramedMapMesh finish(List<Quad> quads, List<Line> lines,
                                        List<FramedMapMesh.VoxelIcon> icons) {
        float[] positions = new float[quads.size() * 12];
        int[] colors = new int[quads.size()];
        for (int i = 0; i < quads.size(); i++) {
            Quad quad = quads.get(i);
            System.arraycopy(quad.p, 0, positions, i * 12, 12);
            colors[i] = quad.color;
        }
        float[] linePositions = new float[lines.size() * 6];
        int[] lineColors = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            System.arraycopy(line.p, 0, linePositions, i * 6, 6);
            lineColors[i] = line.color;
        }
        return new FramedMapMesh(positions, colors, quads.size(), linePositions, lineColors,
                lines.size(), icons);
    }

    /** Blends the region fill into the terrain colour, like the 2D map's 0x55 fill. */
    private static int blendFill(int argb, int fill) {
        int a = REGION_FILL_ALPHA;
        int inv = 255 - a;
        int r = (((argb >> 16) & 0xFF) * inv + ((fill >> 16) & 0xFF) * a) / 255;
        int g = (((argb >> 8) & 0xFF) * inv + ((fill >> 8) & 0xFF) * a) / 255;
        int b = ((argb & 0xFF) * inv + (fill & 0xFF) * a) / 255;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
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
