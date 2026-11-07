package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.webtrade.atlasmapextended.client.data.ModConfig;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-map cache. Sampling is spread over client ticks so a frame never causes
 * a long freeze; the mesh is built once when all explored columns are ready.
 */
public final class FramedMapCache {
    private static final int COLUMNS_PER_TICK = 512;
    private static final Map<Integer, Entry> ENTRIES = new HashMap<>();
    private static ResourceKey<Level> currentDimension;

    private FramedMapCache() {
    }

    private static final class Entry {
        FramedMapKey key;
        FramedMapVolume volume = new FramedMapVolume();
        byte[] explored;
        long exploredHash;
        int cursor;
        boolean sampling;
        FramedMapMesh mesh;
        long builtHash;

        Entry(FramedMapKey key) {
            this.key = key;
        }
    }

    /** Called from the item frame renderer. Returns null until the mesh is ready. */
    public static FramedMapMesh getOrRequest(ClientLevel level, MapId mapId, MapItemSavedData data) {
        if (!ModConfig.get().framedMaps3D) {
            return null;
        }
        if (currentDimension != null && !currentDimension.equals(level.dimension())) {
            clear();
        }
        currentDimension = level.dimension();

        int id = mapId.id();
        FramedMapKey key = new FramedMapKey(
                level.dimension(), id,
                data.centerX, data.centerZ, data.scale,
                level.getMinY(), level.getHeight());

        long hash = Arrays.hashCode(data.colors);
        Entry entry = ENTRIES.get(id);
        if (entry == null || !entry.key.sameGeometry(key)) {
            entry = new Entry(key);
            ENTRIES.put(id, entry);
        } else if (entry.mesh != null && entry.builtHash != hash) {
            // the map was explored further or rescaled: rebuild
            entry = new Entry(key);
            ENTRIES.put(id, entry);
        }

        if (entry.mesh != null) {
            return entry.mesh;
        }
        if (!entry.sampling) {
            entry.sampling = true;
            entry.explored = data.colors.clone();
            entry.exploredHash = hash;
            entry.cursor = 0;
            entry.volume.clear();
        }
        return null;
    }

    /** Called once per client tick. */
    public static void tick(ClientLevel level) {
        if (level == null || !ModConfig.get().framedMaps3D) {
            return;
        }
        for (Entry entry : ENTRIES.values()) {
            if (!entry.sampling || !entry.key.dimension().equals(level.dimension())) {
                continue;
            }
            int end = Math.min(FramedMapVolume.COLUMNS, entry.cursor + COLUMNS_PER_TICK);
            for (; entry.cursor < end; entry.cursor++) {
                int px = entry.cursor % FramedMapVolume.SIZE;
                int pz = entry.cursor / FramedMapVolume.SIZE;
                FramedMapSampler.sampleColumn(level, entry.volume, entry.key, entry.explored, px, pz);
            }
            if (entry.cursor >= FramedMapVolume.COLUMNS) {
                entry.mesh = FramedMapMesher.build(entry.volume, entry.key, ModConfig.get().framedMapsStartY);
                entry.builtHash = entry.exploredHash;
                entry.sampling = false;
            }
        }
    }

    public static void clear() {
        ENTRIES.clear();
        currentDimension = null;
    }
}
