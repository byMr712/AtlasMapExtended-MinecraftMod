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
        final String id;
        FramedMapVolume volume = new FramedMapVolume();
        byte[] explored;
        long exploredHash;
        int cursor;
        boolean sampling;
        boolean building;
        int buildGeneration = -1;
        int generation;
        FramedMapMesh mesh;
        FramedMapOverlay overlay;
        long builtHash;
        long lastBuildTick;

        Entry(FramedMapKey key) {
            this.key = key;
            this.id = key.dimension().identifier() + "#" + key.mapId();
        }
    }

    /** Called from the item frame renderer. Returns null until the mesh is ready. */
    public static FramedMapMesh getOrRequest(ClientLevel level, MapId mapId, MapItemSavedData data) {
        if (!ModConfig.get().framedMaps3D || data.colors == null) {
            return null;
        }

        FramedMapKey key = new FramedMapKey(
                data.dimension, mapId.id(),
                data.centerX, data.centerZ, data.scale,
                level.getMinY(), level.getHeight());
        String id = key.dimension().identifier() + "#" + key.mapId();
        long hash = Arrays.hashCode(data.colors);

        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            entry = new Entry(key);
            entry.overlay = FramedOverlayStorage.getOrCapture(key);
            FramedMapVolume stored = FramedMapStorage.load(key);
            if (stored != null) {
                entry.volume = stored;
                entry.exploredHash = hash;
                entry.builtHash = hash;
                startBuild(entry);
            } else {
                startSampling(entry, data, hash);
            }
            ENTRIES.put(id, entry);
        } else if (!entry.key.sameGeometry(key)) {
            entry = new Entry(key);
            entry.overlay = FramedOverlayStorage.getOrCapture(key);
            startSampling(entry, data, hash);
            ENTRIES.put(id, entry);
        } else if (!entry.sampling && !entry.building && entry.mesh != null
                && entry.builtHash != hash
                && level.getGameTime() - entry.lastBuildTick > 100) {
            // the map was explored further: rebuild in the background, keep the old mesh visible
            startSampling(entry, data, hash);
        }

        return entry.mesh;
    }

    private static void startSampling(Entry entry, MapItemSavedData data, long hash) {
        entry.volume.clear();
        entry.explored = data.colors.clone();
        entry.exploredHash = hash;
        entry.cursor = 0;
        entry.sampling = true;
        entry.generation++;
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
                entry.sampling = false;
                FramedMapStorage.save(entry.key, entry.volume);
                startBuild(entry);
            }
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
            FramedMapMesh mesh = FramedMapMesher.build(snapshot, key, startY, snapshotOverlay);
            Minecraft.getInstance().execute(() -> {
                Entry current = ENTRIES.get(entry.id);
                if (current != entry || entry.generation != generation) {
                    return;
                }
                long now = Minecraft.getInstance().level != null
                        ? Minecraft.getInstance().level.getGameTime() : 0L;
                boolean firstAppearance = entry.mesh == null || entry.mesh.revealStart() < 0;
                mesh.setRevealStart(firstAppearance ? now : entry.mesh.revealStart());
                entry.mesh = mesh;
                entry.builtHash = hash;
                entry.building = false;
                entry.lastBuildTick = now;
            });
        });
    }

    /**
     * Y=60 (or the configured value) is used while it is near the map's own
     * surface range. Maps from deep caves, sky islands or high plateaus get a
     * local baseline instead, so every map shows its own relief.
     */
    private static int chooseStartY(int preferred, int minSurface, int maxSurface) {
        if (minSurface == Integer.MAX_VALUE || maxSurface == Integer.MIN_VALUE) {
            return preferred;
        }
        int range = maxSurface - minSurface;
        if (range < 4) {
            return maxSurface;
        }
        if (preferred >= minSurface - 16 && preferred <= maxSurface) {
            return preferred;
        }
        return minSurface + range / 8;
    }

    public static void clear() {
        ENTRIES.clear();
    }
}
