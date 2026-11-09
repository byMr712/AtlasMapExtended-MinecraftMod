package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Geometry key of a filled map shown in an item frame. */
public record FramedMapKey(
        ResourceKey<Level> dimension,
        int mapId,
        int centerX,
        int centerZ,
        int scale,
        int minY,
        int height
) {
    public int scaleFactor() {
        return 1 << scale;
    }

    public int originX() {
        return centerX - 64 * scaleFactor();
    }

    public int originZ() {
        return centerZ - 64 * scaleFactor();
    }

    /** Same map area/size; used to detect a moved or rescaled map. */
    public boolean sameGeometry(FramedMapKey other) {
        return other != null
                && dimension.equals(other.dimension)
                && centerX == other.centerX
                && centerZ == other.centerZ
                && scale == other.scale
                && minY == other.minY
                && height == other.height;
    }
}
