package org.webtrade.atlasmapextended.client.framed3d;

import java.util.Arrays;

/**
 * 128×128 surface columns plus a shallow block profile for the side walls.
 * Only the top block determines the relief; the profile is used to paint the
 * visible cut faces as real rock layers.
 */
public final class FramedMapVolume {
    public static final int SIZE = 128;
    public static final int COLUMNS = SIZE * SIZE;
    /** Blocks below the surface kept for the side-wall colour profile. */
    public static final int LAYERS = 20;

    /** World Y of the surface block; MIN_VALUE = no data. */
    public final int[] surfaceY = new int[COLUMNS];
    /** Vanilla map colour (0xRRGGBB) of the top block. */
    public final int[] rgb = new int[COLUMNS];
    /** true when the top block is a fluid (water/lava). */
    public final boolean[] water = new boolean[COLUMNS];
    /** Water column depth in blocks (0 for land). */
    public final byte[] waterDepth = new byte[COLUMNS];
    /** Column has data and is inside the explored part of the map. */
    public final boolean[] valid = new boolean[COLUMNS];
    /** Packed map-colour ids of the first {@link #LAYERS} blocks below the surface. */
    public final byte[] layerColor = new byte[COLUMNS * LAYERS];

    public void clear() {
        Arrays.fill(surfaceY, Integer.MIN_VALUE);
        Arrays.fill(rgb, 0);
        Arrays.fill(water, false);
        Arrays.fill(waterDepth, (byte) 0);
        Arrays.fill(valid, false);
        Arrays.fill(layerColor, (byte) 0);
    }

    public FramedMapVolume copy() {
        FramedMapVolume copy = new FramedMapVolume();
        System.arraycopy(surfaceY, 0, copy.surfaceY, 0, COLUMNS);
        System.arraycopy(rgb, 0, copy.rgb, 0, COLUMNS);
        System.arraycopy(water, 0, copy.water, 0, COLUMNS);
        System.arraycopy(waterDepth, 0, copy.waterDepth, 0, COLUMNS);
        System.arraycopy(valid, 0, copy.valid, 0, COLUMNS);
        System.arraycopy(layerColor, 0, copy.layerColor, 0, layerColor.length);
        return copy;
    }

    /**
     * Cosmetic fallback for narrow unsampled strips touching the map border:
     * extend the nearest valid column/row into the strip so the relief does
     * not end in a flat 2D hole. Only a copy used for meshing is modified;
     * real sampling still replaces the approximation once the chunks load.
     */
    public void fillMissingBorders(int limit) {
        for (int z = 0; z < SIZE; z++) {
            int first = -1;
            int last = -1;
            for (int x = 0; x < SIZE; x++) {
                if (valid[z * SIZE + x]) {
                    if (first < 0) {
                        first = x;
                    }
                    last = x;
                }
            }
            if (first > 0 && first <= limit) {
                for (int x = 0; x < first; x++) {
                    copyCell(first, z, x, z);
                }
            }
            if (last >= 0 && last < SIZE - 1 && SIZE - 1 - last <= limit) {
                for (int x = last + 1; x < SIZE; x++) {
                    copyCell(last, z, x, z);
                }
            }
        }
        for (int x = 0; x < SIZE; x++) {
            int first = -1;
            int last = -1;
            for (int z = 0; z < SIZE; z++) {
                if (valid[z * SIZE + x]) {
                    if (first < 0) {
                        first = z;
                    }
                    last = z;
                }
            }
            if (first > 0 && first <= limit) {
                for (int z = 0; z < first; z++) {
                    copyCell(x, first, x, z);
                }
            }
            if (last >= 0 && last < SIZE - 1 && SIZE - 1 - last <= limit) {
                for (int z = last + 1; z < SIZE; z++) {
                    copyCell(x, last, x, z);
                }
            }
        }
    }

    private void copyCell(int srcX, int srcZ, int dstX, int dstZ) {
        int from = srcZ * SIZE + srcX;
        int to = dstZ * SIZE + dstX;
        valid[to] = valid[from];
        surfaceY[to] = surfaceY[from];
        rgb[to] = rgb[from];
        water[to] = water[from];
        waterDepth[to] = waterDepth[from];
        System.arraycopy(layerColor, from * LAYERS, layerColor, to * LAYERS, LAYERS);
    }
}
