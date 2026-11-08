package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.webtrade.atlasmapextended.client.data.ModConfig;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-map cache. A map is sampled once while its area is loaded, the surface
 * volume is stored in the world folder and the mesh is built off-thread.
 * Moving the frame, or viewing the same map in another place, reuses the same
 * baked result.
 */
public final class FramedMapCache {
    /** Extra blocks below the configured level that still get 3D relief. */
    private static final int BELOW_BASELINE = 20;
    private static final int COLUMNS_PER_TICK = 512;
    /** Columns re-checked per tick to fill in areas that were not loaded during the first pass. */
    private static final int RETRY_COLUMNS_PER_TICK = 512;
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final ExecutorService MESH_POOL = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AtlasMap-3D-Mesh");
        thread.setDaemon(true);
        return thread;
    });

    private FramedMapCache() {
    }

    private static final class Entry {
        final FramedMapKey key;
        final int mask;
        final String id;
        FramedMapVolume volume = new FramedMapVolume();
        long exploredHash;
        int cursor;
        int retryCursor;
        boolean sampling;
        boolean building;
        boolean dirty;
        int buildGeneration = -1;
        int generation;
        FramedMapMesh mesh;
        FramedMapOverlay overlay;
        long builtHash;
        long lastBuildTick;

        Entry(FramedMapKey key, int mask) {
            this.key = key;
            this.mask = mask & 0x0F;
            this.id = key.dimension().identifier() + "#" + key.mapId() + "#" + this.mask;
        }
    }

    /** Called from the item frame renderer. Returns null until the mesh is ready. */
    public static FramedMapMesh getOrRequest(ClientLevel level, MapId mapId, MapItemSavedData data,
                                             int textureMask) {
        if (!ModConfig.get().framedMaps3D || data.colors == null
                || !data.dimension.equals(level.dimension())) {
            return null;
        }

        FramedMapKey key = new FramedMapKey(
                data.dimension, mapId.id(),
                data.centerX, data.centerZ, data.scale,
                level.getMinY(), level.getHeight());
        int mask = textureMask & 0x0F;
        String id = key.dimension().identifier() + "#" + key.mapId() + "#" + mask;
        long hash = Arrays.hashCode(data.colors);

        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            entry = new Entry(key, mask);
            entry.overlay = FramedOverlayStorage.getOrCapture(key);
            FramedMapVolume stored = FramedMapStorage.load(key);
            if (stored != null) {
                entry.volume = stored;
                entry.exploredHash = hash;
                entry.builtHash = hash;
                startBuild(entry);
            } else {
                startSampling(entry, hash);
            }
            ENTRIES.put(id, entry);
        } else if (!entry.key.sameGeometry(key)) {
            entry = new Entry(key, mask);
            entry.overlay = FramedOverlayStorage.getOrCapture(key);
            startSampling(entry, hash);
            ENTRIES.put(id, entry);
        } else if (!entry.sampling && !entry.building && entry.mesh != null
                && entry.builtHash != hash
                && level.getGameTime() - entry.lastBuildTick > 100) {
            // the map was explored further: rebuild in the background, keep the old mesh visible
            startSampling(entry, hash);
        }

        return entry.mesh;
    }

    private static void startSampling(Entry entry, long hash) {
        entry.volume.clear();
        entry.exploredHash = hash;
        entry.cursor = 0;
        entry.retryCursor = 0;
        entry.sampling = true;
        entry.dirty = false;
        entry.generation++;
    }

    /** Called once per client tick. */
    public static void tick(ClientLevel level) {
        if (level == null || !ModConfig.get().framedMaps3D) {
            return;
        }
        for (Entry entry : ENTRIES.values()) {
            if (!entry.key.dimension().equals(level.dimension())) {
                continue;
            }
            if (entry.sampling) {
                int end = Math.min(FramedMapVolume.COLUMNS, entry.cursor + COLUMNS_PER_TICK);
                for (; entry.cursor < end; entry.cursor++) {
                    int px = entry.cursor % FramedMapVolume.SIZE;
                    int pz = entry.cursor / FramedMapVolume.SIZE;
                    FramedMapSampler.sampleColumn(level, entry.volume, entry.key, px, pz);
                }
                if (entry.cursor >= FramedMapVolume.COLUMNS) {
                    entry.sampling = false;
                    FramedMapStorage.save(entry.key, entry.volume);
                    startBuild(entry);
                }
                continue;
            }
            retryMissing(level, entry);
        }
    }

    /**
     * The first pass can run before every chunk of the map is loaded; those
     * columns stay empty and used to stay empty forever. Re-check them in the
     * background and rebuild once something was filled in.
     */
    private static void retryMissing(ClientLevel level, Entry entry) {
        if (entry.building) {
            return;
        }
        int filled = 0;
        int end = Math.min(FramedMapVolume.COLUMNS, entry.retryCursor + RETRY_COLUMNS_PER_TICK);
        for (; entry.retryCursor < end; entry.retryCursor++) {
            int index = entry.retryCursor;
            if (entry.volume.valid[index]) {
                continue;
            }
            int px = index % FramedMapVolume.SIZE;
            int pz = index / FramedMapVolume.SIZE;
            FramedMapSampler.sampleColumn(level, entry.volume, entry.key, px, pz);
            if (entry.volume.valid[index]) {
                filled++;
            }
        }
        if (entry.retryCursor >= FramedMapVolume.COLUMNS) {
            entry.retryCursor = 0;
        }
        if (filled > 0) {
            entry.dirty = true;
        }
        if (entry.dirty && level.getGameTime() - entry.lastBuildTick > 40) {
            entry.dirty = false;
            FramedMapStorage.save(entry.key, entry.volume);
            startBuild(entry);
        }
    }

    private static void startBuild(Entry entry) {
        if (entry.building && entry.buildGeneration == entry.generation) {
            return;
        }
        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;
        for (int i = 0; i < FramedMapVolume.COLUMNS; i++) {
            if (entry.volume.valid[i]) {
                minSurface = Math.min(minSurface, entry.volume.surfaceY[i]);
                maxSurface = Math.max(maxSurface, entry.volume.surfaceY[i]);
            }
        }
        int mainY = chooseStartY(ModConfig.get().framedMapsStartY, minSurface, maxSurface);
        // The configured level is the "main" height; terrain up to 20 blocks
        // below it is still part of the relief and forms the map's base.
        int startY = Math.max(entry.key.minY(), mainY - BELOW_BASELINE);
        entry.building = true;
        entry.buildGeneration = entry.generation;
        int generation = entry.generation;
        FramedMapOverlay overlay = entry.overlay;
        if (overlay == null) {
            overlay = FramedOverlayStorage.getOrCapture(entry.key);
            entry.overlay = overlay;
        }
        FramedMapVolume snapshot = entry.volume.copy();
        FramedMapKey key = entry.key;
        long hash = entry.exploredHash;
        FramedMapOverlay snapshotOverlay = overlay;
        MESH_POOL.execute(() -> {
            FramedMapMesh mesh = FramedMapMesher.build(snapshot, key, startY, snapshotOverlay, entry.mask);
            Minecraft.getInstance().execute(() -> {
                Entry current = ENTRIES.get(entry.id);
                if (current != entry || entry.generation != generation) {
                    return;
                }
                entry.mesh = mesh;
                entry.builtHash = hash;
                entry.building = false;
                entry.lastBuildTick = Minecraft.getInstance().level != null
                        ? Minecraft.getInstance().level.getGameTime() : 0L;
            });
        });
    }

    /**
     * The configured Y is shared by neighbouring maps, so their relief joins
     * into one landscape. Only maps far outside the normal range (deep caves,
     * sky islands) get a local baseline.
     */
    private static int chooseStartY(int preferred, int minSurface, int maxSurface) {
        if (minSurface == Integer.MAX_VALUE || maxSurface == Integer.MIN_VALUE) {
            return preferred;
        }
        if (preferred < minSurface - 64) {
            return minSurface + 8;
        }
        if (preferred > maxSurface + 24) {
            return maxSurface - 8;
        }
        return preferred;
    }

    public static void clear() {
        ENTRIES.clear();
    }
}
