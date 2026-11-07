package org.webtrade.atlasmapextended.client.framed3d;

import java.util.Arrays;

/**
 * 128×128 surface columns. Only the top block of every column is stored:
 * no underground layers, caves or overhangs.
 */
public final class FramedMapVolume {
    public static final int SIZE = 128;
    public static final int COLUMNS = SIZE * SIZE;

    /** World Y of the surface block; MIN_VALUE = no data. */
    public final int[] surfaceY = new int[COLUMNS];
    /** Vanilla map colour (0xRRGGBB) of the top block. */
    public final int[] rgb = new int[COLUMNS];
    /** true when the top block is a fluid (water/lava). */
    public final boolean[] water = new boolean[COLUMNS];
    /** Column has data and is inside the explored part of the map. */
    public final boolean[] valid = new boolean[COLUMNS];

    public void clear() {
        Arrays.fill(surfaceY, Integer.MIN_VALUE);
        Arrays.fill(rgb, 0);
        Arrays.fill(water, false);
        Arrays.fill(valid, false);
    }

    public FramedMapVolume copy() {
        FramedMapVolume copy = new FramedMapVolume();
        System.arraycopy(surfaceY, 0, copy.surfaceY, 0, COLUMNS);
        System.arraycopy(rgb, 0, copy.rgb, 0, COLUMNS);
        System.arraycopy(water, 0, copy.water, 0, COLUMNS);
        System.arraycopy(valid, 0, copy.valid, 0, COLUMNS);
        return copy;
    }
}
