package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Samples the top block of one map pixel. Surface only: nothing below the
 * heightmap is read, so caves and overhangs are intentionally ignored.
 */
public final class FramedMapSampler {

    private FramedMapSampler() {
    }

    public static void sampleColumn(
            ClientLevel level,
            FramedMapVolume volume,
            FramedMapKey key,
            byte[] explored,
            int px, int pz
    ) {
        int index = pz * FramedMapVolume.SIZE + px;
        if (explored[index] == 0) {
            return;
        }

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
        if (state.isAir()) {
            return;
        }

        boolean water = !state.getFluidState().isEmpty();
        MapColor mapColor = state.getMapColor(level, pos);
        if (!water && mapColor == MapColor.NONE) {
            return;
        }

        volume.valid[index] = true;
        volume.surfaceY[index] = surface;
        volume.water[index] = water;
        volume.rgb[index] = water ? MapColor.WATER.col : mapColor.col;
    }
}
