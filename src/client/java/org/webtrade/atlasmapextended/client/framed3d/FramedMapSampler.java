package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Samples the top block of one map pixel. The relief itself is surface-only;
 * a shallow block profile is read as well so cut faces can show rock layers.
 * Caves and overhangs never influence the height.
 */
public final class FramedMapSampler {

    private FramedMapSampler() {
    }

    public static void sampleColumn(
            ClientLevel level,
            FramedMapVolume volume,
            FramedMapKey key,
            int px, int pz
    ) {
        int index = pz * FramedMapVolume.SIZE + px;

        int scaleFactor = key.scaleFactor();
        int wx = key.originX() + px * scaleFactor;
        int wz = key.originZ() + pz * scaleFactor;
        if (!level.hasChunk(wx >> 4, wz >> 4)) {
            return;
        }

        LevelChunk chunk = level.getChunk(wx >> 4, wz >> 4);
        // getHeight already returns the Y of the top non-air block.
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
        if (surface < level.getMinY()) {
            return;
        }

        BlockPos pos = new BlockPos(wx, surface, wz);
        BlockState state = chunk.getBlockState(pos);
        MapColor mapColor = state.getMapColor(level, pos);
        // Plants, snow layers and similar blocks can have no map colour; keep
        // descending until the ground gets one, otherwise a hole is left.
        while (surface > level.getMinY() && (state.isAir() || mapColor == MapColor.NONE)) {
            surface--;
            pos = new BlockPos(wx, surface, wz);
            state = chunk.getBlockState(pos);
            mapColor = state.getMapColor(level, pos);
        }
        if (mapColor == MapColor.NONE) {
            return;
        }

        boolean water = mapColor == MapColor.WATER;
        int depth = 0;
        if (water) {
            depth = 1;
            while (depth <= 32 && surface - depth >= level.getMinY()) {
                BlockPos below = new BlockPos(wx, surface - depth, wz);
                if (chunk.getBlockState(below).getFluidState().isEmpty()) {
                    break;
                }
                depth++;
            }
        }

        volume.valid[index] = true;
        volume.surfaceY[index] = surface;
        volume.water[index] = water;
        volume.waterDepth[index] = (byte) Math.min(32, depth);
        volume.rgb[index] = water ? MapColor.WATER.col : mapColor.col;
        sampleLayers(level, chunk, volume, index, wx, surface, wz, mapColor);
    }

    /** Records the first blocks below the surface so cut faces show real strata. */
    private static void sampleLayers(ClientLevel level, LevelChunk chunk, FramedMapVolume volume,
                                     int index, int wx, int surface, int wz, MapColor surfaceColor) {
        int packed = surfaceColor.getPackedId(MapColor.Brightness.NORMAL) & 0xFF;
        int base = index * FramedMapVolume.LAYERS;
        for (int layer = 0; layer < FramedMapVolume.LAYERS; layer++) {
            int y = surface - layer - 1;
            if (y < level.getMinY()) {
                volume.layerColor[base + layer] = (byte) packed;
                continue;
            }
            BlockPos pos = new BlockPos(wx, y, wz);
            MapColor color = chunk.getBlockState(pos).getMapColor(level, pos);
            if (color != MapColor.NONE) {
                packed = color.getPackedId(MapColor.Brightness.NORMAL) & 0xFF;
            }
            volume.layerColor[base + layer] = (byte) packed;
        }
    }
}
