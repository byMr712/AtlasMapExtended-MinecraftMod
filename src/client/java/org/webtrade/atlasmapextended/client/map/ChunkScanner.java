package org.webtrade.atlasmapextended.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** Turns loaded chunks into papyrus-tinted map pixels. */
public final class ChunkScanner {
    private static final int PARCH_R = 234;
    private static final int PARCH_G = 216;
    private static final int PARCH_B = 174;

    private ChunkScanner() {
    }

    public static void scan(ClientLevel level, LevelChunk chunk, MapLayer layer) {
        ChunkPos cp = chunk.getPos();
        int bx = cp.getMinBlockX();
        int bz = cp.getMinBlockZ();
        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinY();
        int ceilingStart = minY + level.dimensionType().logicalHeight() - 10;

        int[] heights = new int[256];
        int[] cols = new int[256];
        int[] depths = new int[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = bx + lx;
                int wz = bz + lz;
                int y;
                if (ceiling) {
                    y = ceilingStart;
                    // skip the solid roof until we reach an open space, then fall to its floor
                    while (y > minY && colorAt(chunk, level, pos, wx, y, wz) != MapColor.NONE) {
                        y--;
                    }
                    if (y <= minY) {
                        y = ceilingStart;
                    }
                } else {
                    // ChunkAccess#getHeight already returns the topmost block
                    y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                }
                MapColor mc = colorAt(chunk, level, pos, wx, y, wz);
                while (y > minY && mc == MapColor.NONE) {
                    y--;
                    mc = colorAt(chunk, level, pos, wx, y, wz);
                }
                int depth = 0;
                int surface = y;
                if (mc == MapColor.WATER) {
                    int yy = y;
                    MapColor below = mc;
                    while (yy > minY && below == MapColor.WATER && depth < 32) {
                        yy--;
                        depth++;
                        below = colorAt(chunk, level, pos, wx, yy, wz);
                    }
                }
                int i = lz * 16 + lx;
                heights[i] = surface;
                depths[i] = mc == MapColor.WATER ? Math.max(1, depth) : 0;
                cols[i] = mc == MapColor.NONE ? -1 : mc.col;
            }
        }

        MapTile tile = layer.getOrCreate(bx >> MapTile.SHIFT, bz >> MapTile.SHIFT);
        int ox = bx & (MapTile.SIZE - 1);
        int oz = bz & (MapTile.SIZE - 1);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lz * 16 + lx;
                if (cols[i] < 0) {
                    continue;
                }
                int wx = bx + lx;
                int wz = bz + lz;
                int h = heights[i];
                int hn = lz > 0 ? heights[i - 16] : neighbourHeight(level, wx, wz - 1, h, ceiling);
                int hw = lx > 0 ? heights[i - 1] : neighbourHeight(level, wx - 1, wz, h, ceiling);
                tile.setPixel(ox + lx, oz + lz, papyrus(cols[i], h, hn, hw, depths[i], wx, wz));
            }
        }
    }

    private static MapColor colorAt(LevelChunk chunk, ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        pos.set(x, y, z);
        BlockState state = chunk.getBlockState(pos);
        return state.getMapColor(level, pos);
    }

    private static int neighbourHeight(ClientLevel level, int x, int z, int fallback, boolean ceiling) {
        if (ceiling || !level.hasChunk(x >> 4, z >> 4)) {
            return fallback;
        }
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
    }

    /** Converts a vanilla map colour to the muted, inked papyrus palette. */
    public static int papyrus(int rgb, int h, int hn, int hw, int waterDepth, int wx, int wz) {
        float r;
        float g;
        float b;
        if (waterDepth > 0) {
            float t = Math.min(waterDepth, 14) / 14f;
            r = lerp(120, 62, t);
            g = lerp(160, 98, t);
            b = lerp(164, 114, t);
            // engraved wave hatching
            if ((wz & 3) == 0 && ((wx + (wz >> 2) * 5) & 7) < 3) {
                r *= 0.88f;
                g *= 0.88f;
                b *= 0.9f;
            }
        } else {
            r = (rgb >> 16) & 0xFF;
            g = (rgb >> 8) & 0xFF;
            b = rgb & 0xFF;
            float shade = 1f + Math.max(-4, Math.min(4, h - hn)) * 0.045f;
            if (h == hn) {
                shade = 0.97f;
            }
            r *= shade;
            g *= shade;
            b *= shade;
            // sepia toning
            float l = 0.3f * r + 0.59f * g + 0.11f * b;
            r = lerp(r, l * 1.02f + 28, 0.42f);
            g = lerp(g, l * 0.9f + 16, 0.42f);
            b = lerp(b, l * 0.68f + 4, 0.42f);
            // contour lines every 8 blocks
            if (Math.floorDiv(h, 8) != Math.floorDiv(hn, 8) || Math.floorDiv(h, 8) != Math.floorDiv(hw, 8)) {
                r *= 0.84f;
                g *= 0.82f;
                b *= 0.8f;
            }
        }
        // soak into the parchment
        r = lerp(r, PARCH_R, 0.14f);
        g = lerp(g, PARCH_G, 0.14f);
        b = lerp(b, PARCH_B, 0.14f);
        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
