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
    private static final float SPLIT_EPS = 1e-4f;
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
        return build(volume, key, startY, overlay, textureMask,
                tileDiv, tileCol, tileRow, tileRotation, tileDiv);
    }

    public static FramedMapMesh build(FramedMapVolume volume, FramedMapKey key, int startY,
                                      FramedMapOverlay overlay, int textureMask,
                                      int tileDiv, int tileCol, int tileRow, int tileRotation,
                                      int reliefDiv) {
        return build(volume, key, startY, overlay, textureMask,
                tileDiv, tileCol, tileRow, tileRotation, reliefDiv, 1);
    }

    /**
     * {@code lodStep} greater than one builds a cheap far-distance variant:
     * one heightfield cell per {@code lodStep} source pixels, with shared
     * corner heights so the surface stays continuous. Interior cliffs need no
     * side quads because neighbouring cells share their corners; only the
     * outer rim gets a skirt. This cuts a map from tens of thousands of quads
     * to roughly {@code (128/lodStep)^2}.
     */
    public static FramedMapMesh build(FramedMapVolume volume, FramedMapKey key, int startY,
                                      FramedMapOverlay overlay, int textureMask,
                                      int tileDiv, int tileCol, int tileRow, int tileRotation,
                                      int reliefDiv, int lodStep) {
        int div = Math.max(1, tileDiv);
        int relief = Math.max(1, reliefDiv);
        int step = Math.max(1, Math.min(FramedMapVolume.SIZE, lodStep));
        // A completed mega group is one 128x128 image spread over N frames.
        // Quads get a tile index so their glow can follow the physical frame.
        int groupTiles = div == 1 && relief > 1 ? relief : 0;
        int rotation = relief > 1 ? tileRotation & 3 : 0;
        int dstX0 = div == 1 ? 0 : tileCol * FramedMapVolume.SIZE / div;
        int dstX1 = div == 1 ? FramedMapVolume.SIZE : (tileCol + 1) * FramedMapVolume.SIZE / div;
        int dstZ0 = div == 1 ? 0 : tileRow * FramedMapVolume.SIZE / div;
        int dstZ1 = div == 1 ? FramedMapVolume.SIZE : (tileRow + 1) * FramedMapVolume.SIZE / div;
        FramedMapVolume source = volume;
        // A mega group has no tile sub-rectangle (div = 1) but still needs
        // its whole image rotated into the group orientation. Only the
        // per-tile path used to do this, which left the shared group mesh
        // unrotated while its icons rotated.
        if (div > 1 || rotation != 0) {
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
        // A mega-map stretches one source block over {@code relief} mesh units
        // in both axes, so the relief cap must stretch with it. Capping at the
        // single-map 96 units flattened every tile bigger than ~2x2.
        float maxDepth = MAX_DEPTH * relief;

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
                float height = Math.max(0, volume.surfaceY[index] - startY) * relief;
                depth[index] = Math.min(maxDepth, height) + BASE_OFFSET;
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

        if (step <= 1) {
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
                        return finish(quads, lines, icons, groupTiles);
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
                            : seamDepth(source, volume, startY, relief, dstX0, dstX1, dstZ0, dstZ1, rotation, px, pz, 1, 0, d, (textureMask & FramedMapConnections.TEX_RIGHT) != 0);
                    if (east < d) {
                        addWallEast(walls, volume, index, scaleFactor, wx, wz,
                                px + 1, pz, east, d, surfaceColor, relief);
                    }
                    float west = px > 0 ? depth[index - 1]
                            : seamDepth(source, volume, startY, relief, dstX0, dstX1, dstZ0, dstZ1, rotation, px, pz, -1, 0, d, (textureMask & FramedMapConnections.TEX_LEFT) != 0);
                    if (west < d) {
                        addWallWest(walls, volume, index, scaleFactor, wx, wz,
                                px, pz, west, d, surfaceColor, relief);
                    }
                    float south = pz + 1 < FramedMapVolume.SIZE ? depth[index + FramedMapVolume.SIZE]
                            : seamDepth(source, volume, startY, relief, dstX0, dstX1, dstZ0, dstZ1, rotation, px, pz, 0, 1, d, (textureMask & FramedMapConnections.TEX_BOTTOM) != 0);
                    if (south < d) {
                        addWallSouth(walls, volume, index, scaleFactor, wx, wz,
                                pz + 1, px, south, d, surfaceColor, relief);
                    }
                    float north = pz > 0 ? depth[index - FramedMapVolume.SIZE]
                            : seamDepth(source, volume, startY, relief, dstX0, dstX1, dstZ0, dstZ1, rotation, px, pz, 0, -1, d, (textureMask & FramedMapConnections.TEX_TOP) != 0);
                    if (north < d) {
                        addWallNorth(walls, volume, index, scaleFactor, wx, wz,
                                pz, px, north, d, surfaceColor, relief);
                    }
                    if (quads.size() >= MAX_QUADS) {
                        return finish(quads, lines, icons, groupTiles);
                    }
                }
            }
            walls.emit(quads);
            if (quads.size() >= MAX_QUADS) {
                return finish(quads, lines, icons, groupTiles);
            }
        } else {
            int cells = FramedMapVolume.SIZE / step;
            int stride = cells + 1;
            // Shared corner heights keep the coarse surface continuous, so no
            // interior walls are needed at all.
            float[] heights = new float[stride * stride];
            for (int cz = 0; cz <= cells; cz++) {
                for (int cx = 0; cx <= cells; cx++) {
                    float sum = 0.0f;
                    int count = 0;
                    for (int dz = -1; dz <= 0; dz++) {
                        for (int dx = -1; dx <= 0; dx++) {
                            int cellX = cx + dx;
                            int cellZ = cz + dz;
                            if (cellX < 0 || cellZ < 0 || cellX >= cells || cellZ >= cells) {
                                continue;
                            }
                            int repZ = cellZ * step + step / 2;
                            int repX = cellX * step + step / 2;
                            float d = depth[repZ * FramedMapVolume.SIZE + repX];
                            if (d > 0.0f) {
                                sum += d;
                                count++;
                            }
                        }
                    }
                    heights[cz * stride + cx] = count == 0 ? 0.0f : sum / count;
                }
            }

            for (int cz = 0; cz < cells; cz++) {
                for (int cx = 0; cx < cells; cx++) {
                    int rep = (cz * step + step / 2) * FramedMapVolume.SIZE
                            + cx * step + step / 2;
                    float d = depth[rep];
                    if (d <= 0.0f) {
                        continue;
                    }
                    float x0 = cx == 0 && (textureMask & FramedMapConnections.TEX_LEFT) != 0
                            ? -SEAM_OVERLAP : cx * step;
                    float x1 = cx + 1 == cells && (textureMask & FramedMapConnections.TEX_RIGHT) != 0
                            ? FramedMapVolume.SIZE + SEAM_OVERLAP : (cx + 1) * step;
                    float z0 = cz == 0 && (textureMask & FramedMapConnections.TEX_TOP) != 0
                            ? -SEAM_OVERLAP : cz * step;
                    float z1 = cz + 1 == cells && (textureMask & FramedMapConnections.TEX_BOTTOM) != 0
                            ? FramedMapVolume.SIZE + SEAM_OVERLAP : (cz + 1) * step;
                    quads.add(new Quad(
                            x0, z0, heights[cz * stride + cx],
                            x0, z1, heights[(cz + 1) * stride + cx],
                            x1, z1, heights[(cz + 1) * stride + cx + 1],
                            x1, z0, heights[cz * stride + cx + 1],
                            colors[rep]));
                    if (quads.size() >= MAX_QUADS) {
                        return finish(quads, lines, icons, groupTiles);
                    }
                }
            }

            // The skirt is the only place that needs a side quad: the rim of
            // the whole map. Adjacent maps continue the surface, so their
            // shared edge stays open, exactly like the full-detail path.
            for (int cz = 0; cz < cells; cz++) {
                for (int cx = 0; cx < cells; cx++) {
                    int rep = (cz * step + step / 2) * FramedMapVolume.SIZE
                            + cx * step + step / 2;
                    if (depth[rep] <= 0.0f) {
                        continue;
                    }
                    float x0 = cx * step;
                    float x1 = (cx + 1) * step;
                    float z0 = cz * step;
                    float z1 = (cz + 1) * step;
                    float h00 = heights[cz * stride + cx];
                    float h10 = heights[cz * stride + cx + 1];
                    float h01 = heights[(cz + 1) * stride + cx];
                    float h11 = heights[(cz + 1) * stride + cx + 1];
                    int color = colors[rep];
                    if (cx == 0 && (textureMask & FramedMapConnections.TEX_LEFT) == 0) {
                        quads.add(new Quad(
                                x0, z1, 0.0f,
                                x0, z0, 0.0f,
                                x0, z0, h00,
                                x0, z1, h01,
                                shade(color, SIDE_SHADE)));
                    }
                    if (cx + 1 == cells && (textureMask & FramedMapConnections.TEX_RIGHT) == 0) {
                        quads.add(new Quad(
                                x1, z0, 0.0f,
                                x1, z1, 0.0f,
                                x1, z1, h11,
                                x1, z0, h10,
                                shade(color, SIDE_SHADE)));
                    }
                    if (cz == 0 && (textureMask & FramedMapConnections.TEX_TOP) == 0) {
                        quads.add(new Quad(
                                x0, z0, 0.0f,
                                x1, z0, 0.0f,
                                x1, z0, h10,
                                x0, z0, h00,
                                shade(color, EDGE_SHADE)));
                    }
                    if (cz + 1 == cells && (textureMask & FramedMapConnections.TEX_BOTTOM) == 0) {
                        quads.add(new Quad(
                                x1, z1, 0.0f,
                                x0, z1, 0.0f,
                                x0, z1, h01,
                                x1, z1, h11,
                                shade(color, EDGE_SHADE)));
                    }
                    if (quads.size() >= MAX_QUADS) {
                        return finish(quads, lines, icons, groupTiles);
                    }
                }
            }
        }

        if (overlay != null) {
            addOverlay(volume, startY, depth, lines, icons, overlay, scaleFactor,
                    relief, dstX0, dstX1, dstZ0, dstZ1, rotation, step <= 2);
        }
        return finish(quads, lines, icons, groupTiles);
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
                                   int div, int dstX0, int dstX1, int dstZ0, int dstZ1, int rotation,
                                   boolean withIcons) {
        if (withIcons) {
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
                                        List<FramedMapMesh.VoxelIcon> icons, int tilesPerSide) {
        List<Quad> finalQuads = quads;
        int[] quadTiles = null;
        if (tilesPerSide > 1) {
            float tileSize = (float) FramedMapVolume.SIZE / tilesPerSide;
            finalQuads = new ArrayList<>(quads.size());
            List<Integer> tiles = new ArrayList<>(quads.size());
            for (Quad quad : quads) {
                splitByTiles(quad, tilesPerSide, tileSize, finalQuads, tiles);
                if (finalQuads.size() >= MAX_QUADS) {
                    break;
                }
            }
            quadTiles = new int[finalQuads.size()];
            for (int i = 0; i < quadTiles.length; i++) {
                quadTiles[i] = tiles.get(i);
            }
        }
        float[] positions = new float[finalQuads.size() * 12];
        int[] colors = new int[finalQuads.size()];
        for (int i = 0; i < finalQuads.size(); i++) {
            Quad quad = finalQuads.get(i);
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
        return new FramedMapMesh(positions, colors, finalQuads.size(), linePositions, lineColors,
                lines.size(), icons, quadTiles);
    }

    /**
     * Splits a quad at the mega-group tile boundaries and records which tile
     * it belongs to. Without this a large merged ocean rectangle could span
     * several frames and glow as one whole piece.
     */
    private static void splitByTiles(Quad quad, int tiles, float tileSize,
                                     List<Quad> out, List<Integer> tileOut) {
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            float x = quad.p[v * 3];
            float y = quad.p[v * 3 + 1];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        float xSpan = maxX - minX;
        float ySpan = maxY - minY;
        if (xSpan <= SPLIT_EPS && ySpan <= SPLIT_EPS) {
            out.add(quad);
            tileOut.add(tileAt((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, tiles, tileSize));
        } else if (xSpan > SPLIT_EPS && ySpan > SPLIT_EPS) {
            splitTop(quad, minX, maxX, minY, maxY, tiles, tileSize, out, tileOut);
        } else if (xSpan > SPLIT_EPS) {
            splitWallX(quad, minX, maxX, tiles, tileSize, out, tileOut);
        } else {
            splitWallY(quad, minY, maxY, tiles, tileSize, out, tileOut);
        }
    }

    /** Horizontal top face; depth is bilinear between its four corners. */
    private static void splitTop(Quad quad, float minX, float maxX, float minY, float maxY,
                                 int tiles, float tileSize, List<Quad> out, List<Integer> tileOut) {
        float xSpan = maxX - minX;
        float ySpan = maxY - minY;
        if (!crosses(minX, maxX, tileSize) && !crosses(minY, maxY, tileSize)) {
            out.add(quad);
            tileOut.add(tileAt((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, tiles, tileSize));
            return;
        }
        float z00 = cornerDepth(quad, minX, minY);
        float z10 = cornerDepth(quad, maxX, minY);
        float z01 = cornerDepth(quad, minX, maxY);
        float z11 = cornerDepth(quad, maxX, maxY);
        float[] xs = breaks(minX, maxX, tileSize);
        float[] ys = breaks(minY, maxY, tileSize);
        for (int xi = 0; xi + 1 < xs.length; xi++) {
            float xa = xs[xi];
            float xb = xs[xi + 1];
            for (int yi = 0; yi + 1 < ys.length; yi++) {
                float ya = ys[yi];
                float yb = ys[yi + 1];
                float zA = bilinear(z00, z10, z01, z11, minX, xa, xSpan, minY, ya, ySpan);
                float zB = bilinear(z00, z10, z01, z11, minX, xa, xSpan, minY, yb, ySpan);
                float zC = bilinear(z00, z10, z01, z11, minX, xb, xSpan, minY, yb, ySpan);
                float zD = bilinear(z00, z10, z01, z11, minX, xb, xSpan, minY, ya, ySpan);
                out.add(new Quad(xa, ya, zA, xa, yb, zB, xb, yb, zC, xb, ya, zD, quad.color));
                tileOut.add(tileAt((xa + xb) * 0.5f, (ya + yb) * 0.5f, tiles, tileSize));
            }
        }
    }

    /** Vertical wall whose span runs along X (south/north). */
    private static void splitWallX(Quad quad, float minX, float maxX,
                                   int tiles, float tileSize, List<Quad> out, List<Integer> tileOut) {
        float x0 = quad.p[0];
        float x1 = quad.p[3];
        float y = quad.p[1];
        float bottom0 = quad.p[2];
        float bottom1 = quad.p[5];
        float top0 = quad.p[11];
        float top1 = quad.p[8];
        if (!crosses(minX, maxX, tileSize)) {
            out.add(quad);
            tileOut.add(tileAt((minX + maxX) * 0.5f, y, tiles, tileSize));
            return;
        }
        float span = x1 - x0;
        float[] xs = breaks(minX, maxX, tileSize);
        boolean forward = x1 > x0;
        for (int i = 0; i + 1 < xs.length; i++) {
            float xa = xs[i];
            float xb = xs[i + 1];
            float ta = (xa - x0) / span;
            float tb = (xb - x0) / span;
            float bottomA = lerp(bottom0, bottom1, ta);
            float bottomB = lerp(bottom0, bottom1, tb);
            float topA = lerp(top0, top1, ta);
            float topB = lerp(top0, top1, tb);
            if (forward) {
                out.add(new Quad(xa, y, bottomA, xb, y, bottomB,
                        xb, y, topB, xa, y, topA, quad.color));
            } else {
                out.add(new Quad(xb, y, bottomB, xa, y, bottomA,
                        xa, y, topA, xb, y, topB, quad.color));
            }
            tileOut.add(tileAt((xa + xb) * 0.5f, y, tiles, tileSize));
        }
    }

    /** Vertical wall whose span runs along Y (east/west). */
    private static void splitWallY(Quad quad, float minY, float maxY,
                                   int tiles, float tileSize, List<Quad> out, List<Integer> tileOut) {
        float y0 = quad.p[1];
        float y1 = quad.p[4];
        float x = quad.p[0];
        float bottom0 = quad.p[2];
        float bottom1 = quad.p[5];
        float top0 = quad.p[11];
        float top1 = quad.p[8];
        if (!crosses(minY, maxY, tileSize)) {
            out.add(quad);
            tileOut.add(tileAt(x, (minY + maxY) * 0.5f, tiles, tileSize));
            return;
        }
        float span = y1 - y0;
        float[] ys = breaks(minY, maxY, tileSize);
        boolean forward = y1 > y0;
        for (int i = 0; i + 1 < ys.length; i++) {
            float ya = ys[i];
            float yb = ys[i + 1];
            float ta = (ya - y0) / span;
            float tb = (yb - y0) / span;
            float bottomA = lerp(bottom0, bottom1, ta);
            float bottomB = lerp(bottom0, bottom1, tb);
            float topA = lerp(top0, top1, ta);
            float topB = lerp(top0, top1, tb);
            if (forward) {
                out.add(new Quad(x, ya, bottomA, x, yb, bottomB,
                        x, yb, topB, x, ya, topA, quad.color));
            } else {
                out.add(new Quad(x, yb, bottomB, x, ya, bottomA,
                        x, ya, topA, x, yb, topB, quad.color));
            }
            tileOut.add(tileAt(x, (ya + yb) * 0.5f, tiles, tileSize));
        }
    }

    private static boolean crosses(float min, float max, float tileSize) {
        int first = (int) Math.ceil((min + SPLIT_EPS) / tileSize);
        return first * tileSize < max - SPLIT_EPS;
    }

    private static float[] breaks(float min, float max, float tileSize) {
        List<Float> list = new ArrayList<>();
        list.add(min);
        int first = (int) Math.ceil((min + SPLIT_EPS) / tileSize);
        for (int i = first; ; i++) {
            float boundary = i * tileSize;
            if (boundary >= max - SPLIT_EPS) {
                break;
            }
            if (boundary > min + SPLIT_EPS) {
                list.add(boundary);
            }
            if (list.size() > 64) {
                break;
            }
        }
        if (list.get(list.size() - 1) < max) {
            list.add(max);
        }
        float[] result = new float[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    private static float cornerDepth(Quad quad, float x, float y) {
        for (int v = 0; v < 4; v++) {
            if (Math.abs(quad.p[v * 3] - x) <= SPLIT_EPS
                    && Math.abs(quad.p[v * 3 + 1] - y) <= SPLIT_EPS) {
                return quad.p[v * 3 + 2];
            }
        }
        return quad.p[2];
    }

    private static float bilinear(float z00, float z10, float z01, float z11,
                                  float minX, float x, float xSpan,
                                  float minY, float y, float ySpan) {
        float u = xSpan <= SPLIT_EPS ? 0.0f : (x - minX) / xSpan;
        float v = ySpan <= SPLIT_EPS ? 0.0f : (y - minY) / ySpan;
        float north = z00 + (z10 - z00) * u;
        float south = z01 + (z11 - z01) * u;
        return north + (south - north) * v;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int tileAt(float x, float y, int tiles, float tileSize) {
        int col = (int) Math.floor(x / tileSize);
        int row = (int) Math.floor(y / tileSize);
        col = Math.max(0, Math.min(tiles - 1, col));
        row = Math.max(0, Math.min(tiles - 1, row));
        return row * tiles + col;
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
