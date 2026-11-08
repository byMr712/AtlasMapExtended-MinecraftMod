package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.data.Storage;

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
    /** Narrow unsampled border strips up to this many pixels get extrapolated for the mesh. */
    private static final int BORDER_FILL = 16;
    private static final int COLUMNS_PER_TICK = 512;
    /** Columns re-checked per tick to fill in areas that were not loaded during the first pass. */
    private static final int RETRY_COLUMNS_PER_TICK = 512;
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    /** Loaded/sampled volumes shared by every rotation/tile of the same map. */
    private static final Map<FramedMapKey, FramedMapVolume> STORED = new HashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger MESH_THREAD =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ExecutorService MESH_POOL = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "AtlasMap-3D-Mesh-" + MESH_THREAD.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private FramedMapCache() {
    }

    private static final class Entry {
        final FramedMapKey key;
        final int mask;
        final int tileDiv;
        final int tileCol;
        final int tileRow;
        final int tileRotation;
        final int reliefDiv;
        final int lodStep;
        final String id;
        FramedMapVolume volume = new FramedMapVolume();
        long exploredHash;
        int cursor;
        int retryCursor;
        boolean sampling;
        boolean building;
        boolean dirty;
        boolean complete;
        int buildGeneration = -1;
        int generation;
        FramedMapMesh mesh;
        FramedMapOverlay overlay;
        long builtHash;
        long lastBuildTick;

        Entry(FramedMapKey key, int mask, int tileDiv, int tileCol, int tileRow, int tileRotation,
              int reliefDiv, int lodStep, boolean group) {
            this.key = key;
            this.mask = mask & 0x0F;
            this.tileDiv = tileDiv;
            this.tileCol = tileCol;
            this.tileRow = tileRow;
            this.tileRotation = tileRotation & 3;
            this.reliefDiv = reliefDiv;
            this.lodStep = lodStep;
            this.id = group
                    ? key.dimension().identifier() + "#" + key.mapId() + "@group:"
                    + reliefDiv + ":" + this.tileRotation + ":" + lodStep
                    : key.dimension().identifier() + "#" + key.mapId() + "#" + this.mask
                    + "@" + tileDiv + ":" + tileCol + ":" + tileRow + ":" + this.tileRotation
                    + ":" + lodStep;
        }
    }

    /** Called from the item frame renderer. Returns null until the mesh is ready. */
    public static FramedMapMesh getOrRequest(ClientLevel level, MapId mapId, MapItemSavedData data,
                                             int textureMask, int tileDiv, int tileCol, int tileRow,
                                             int tileRotation, int lodStep) {
        if (!ModConfig.get().framedMaps3D || data.colors == null) {
            return null;
        }

        FramedMapKey key = new FramedMapKey(
                data.dimension, mapId.id(),
                data.centerX, data.centerZ, data.scale,
                level.getMinY(), level.getHeight());
        int mask = textureMask & 0x0F;
        int div = Math.max(1, tileDiv);
        int rotation = div > 1 ? tileRotation & 3 : 0;
        int lod = Math.max(1, lodStep);
        String id = key.dimension().identifier() + "#" + key.mapId() + "#" + mask
                + "@" + div + ":" + tileCol + ":" + tileRow + ":" + rotation + ":" + lod;

        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            long hash = Arrays.hashCode(data.colors);
            entry = new Entry(key, mask, div, tileCol, tileRow, rotation, div, lod, false);
            entry.overlay = FramedOverlayStorage.getOrCapture(key);
            FramedMapVolume stored = STORED.get(key);
            if (stored == null) {
                stored = FramedMapStorage.load(key);
                if (stored != null) {
                    STORED.put(key, stored);
                }
            }
            if (stored != null) {
                boolean complete = validCount(stored) == FramedMapVolume.COLUMNS;
                entry.volume = complete ? stored : stored.copy();
                entry.complete = complete;
                entry.exploredHash = hash;
                entry.builtHash = hash;
                FramedMapDebug.detail("cache map={} mask={} center={},{} tile={}x{} col={} row={} scale={} loaded stored volume, valid={}",
                        mapId.id(), mask, data.centerX, data.centerZ,
                        div, div, tileCol, tileRow, data.scale, validCount(entry.volume));
                startBuild(entry);
            } else {
                FramedMapDebug.detail("cache map={} mask={} center={},{} tile={}x{} col={} row={} scale={} start sampling",
                        mapId.id(), mask, data.centerX, data.centerZ,
                        div, div, tileCol, tileRow, data.scale);
                startSampling(entry, hash);
            }
            ENTRIES.put(id, entry);
        } else if (!entry.key.sameGeometry(key)) {
            long hash = Arrays.hashCode(data.colors);
            entry = new Entry(key, mask, div, tileCol, tileRow, rotation, div, lod, false);
            entry.overlay = FramedOverlayStorage.getOrCapture(key);
            startSampling(entry, hash);
            ENTRIES.put(id, entry);
        } else if (!entry.sampling && !entry.building && entry.mesh != null
                && level.getGameTime() - entry.lastBuildTick > 100) {
            // the map was explored further: rebuild in the background, keep the old mesh visible
            long hash = Arrays.hashCode(data.colors);
            if (entry.builtHash != hash) {
                startSampling(entry, hash);
            }
        }

        return entry.mesh;
    }

    /**
     * One relief mesh for a whole mega group: the full 128x128 map stretched
     * over the N×N tiles. Shared by every group of the same map and rotation.
     */
    public static FramedMapMesh getGroup(ClientLevel level, MapId mapId, MapItemSavedData data,
                                         int div, int rotation, int lodStep) {
        if (!ModConfig.get().framedMaps3D || data.colors == null) {
            return null;
        }
        FramedMapKey key = new FramedMapKey(
                data.dimension, mapId.id(),
                data.centerX, data.centerZ, data.scale,
                level.getMinY(), level.getHeight());
        int d = Math.max(2, div);
        int rot = rotation & 3;
        int lod = Math.max(1, lodStep);
        String id = key.dimension().identifier() + "#" + key.mapId() + "@group:" + d + ":" + rot
                + ":" + lod;

        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            long hash = Arrays.hashCode(data.colors);
            entry = new Entry(key, 0, 1, 0, 0, rot, d, lod, true);
            entry.overlay = FramedOverlayStorage.getOrCapture(key);
            FramedMapVolume stored = STORED.get(key);
            if (stored == null) {
                stored = FramedMapStorage.load(key);
                if (stored != null) {
                    STORED.put(key, stored);
                }
            }
            if (stored != null) {
                boolean complete = validCount(stored) == FramedMapVolume.COLUMNS;
                entry.volume = complete ? stored : stored.copy();
                entry.complete = complete;
                entry.exploredHash = hash;
                entry.builtHash = hash;
                startBuild(entry);
            } else {
                startSampling(entry, hash);
            }
            ENTRIES.put(id, entry);
        } else if (!entry.key.sameGeometry(key)) {
            long hash = Arrays.hashCode(data.colors);
            entry = new Entry(key, 0, 1, 0, 0, rot, d, lod, true);
            entry.overlay = FramedOverlayStorage.getOrCapture(key);
            startSampling(entry, hash);
            ENTRIES.put(id, entry);
        } else if (!entry.sampling && !entry.building && entry.mesh != null
                && level.getGameTime() - entry.lastBuildTick > 100) {
            long hash = Arrays.hashCode(data.colors);
            if (entry.builtHash != hash) {
                startSampling(entry, hash);
            }
        }
        return entry.mesh;
    }

    /**
     * Warms the stored relief for a map the player is carrying. It is sampled
     * while the map's chunks are loaded, so the same map can show 3D in a
     * frame far away later.
     */
    public static void prepareHeld(ClientLevel level, MapId mapId, MapItemSavedData data) {
        if (!ModConfig.get().framedMaps3D || data.colors == null) {
            return;
        }
        getOrRequest(level, mapId, data, 0, 1, 0, 0, 0, 1);
    }

    private static void startSampling(Entry entry, long hash) {
        entry.volume = new FramedMapVolume();
        entry.complete = false;
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
                    entry.complete = validCount(entry.volume) == FramedMapVolume.COLUMNS;
                    STORED.put(entry.key, entry.volume);
                    FramedMapDebug.detail("sampled map={} mask={} valid={}", entry.key.mapId(), entry.mask,
                            validCount(entry.volume));
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
        entry.building = true;
        entry.buildGeneration = entry.generation;
        int generation = entry.generation;
        FramedMapOverlay overlay = entry.overlay;
        if (overlay == null) {
            overlay = FramedOverlayStorage.getOrCapture(entry.key);
            entry.overlay = overlay;
        }
        FramedMapKey key = entry.key;
        long hash = entry.exploredHash;
        FramedMapOverlay snapshotOverlay = overlay;
        // A complete volume is never mutated while the entry is building, so
        // the worker can read the shared arrays directly. Incomplete volumes
        // are rare; copy them before leaving the client thread.
        FramedMapVolume source = entry.complete ? entry.volume : entry.volume.copy();
        boolean fillBorders = !entry.complete;
        int mask = entry.mask;
        int div = entry.tileDiv;
        int col = entry.tileCol;
        int row = entry.tileRow;
        int rotation = entry.tileRotation;
        MESH_POOL.execute(() -> {
            if (fillBorders) {
                source.fillMissingBorders(BORDER_FILL);
            }
            int minSurface = Integer.MAX_VALUE;
            int maxSurface = Integer.MIN_VALUE;
            int valid = 0;
            for (int i = 0; i < FramedMapVolume.COLUMNS; i++) {
                if (source.valid[i]) {
                    valid++;
                    minSurface = Math.min(minSurface, source.surfaceY[i]);
                    maxSurface = Math.max(maxSurface, source.surfaceY[i]);
                }
            }
            if (valid == 0) {
                FramedMapDebug.detail("build map={} mask={} skipped: no valid columns", key.mapId(), mask);
                Minecraft.getInstance().execute(() -> {
                    if (ENTRIES.get(entry.id) == entry) {
                        entry.building = false;
                    }
                });
                return;
            }
            int mainY = chooseStartY(ModConfig.get().framedMapsStartY, minSurface, maxSurface);
            // The configured level is the "main" height; terrain up to 20
            // blocks below it is still part of the relief and forms the base.
            int startY = Math.max(key.minY(), mainY - BELOW_BASELINE);
            FramedMapDebug.detail("build map={} mask={} valid={} startY={} surface={}..{}",
                    key.mapId(), mask, valid, startY, minSurface, maxSurface);
            FramedMapMesh mesh;
            try {
                FramedMapDebug.detail("meshing start map={} mask={} tile={}x{} col={} row={}",
                        key.mapId(), mask, div, div, col, row);
                mesh = FramedMapMesher.build(source, key, startY, snapshotOverlay, mask, div, col, row, rotation, entry.reliefDiv, entry.lodStep);
                FramedMapDebug.detail("meshing done map={} mask={} tile={}x{} col={} row={} quads={}",
                        key.mapId(), mask, div, div, col, row, mesh.quadCount());
            } catch (Throwable t) {
                Storage.LOG.error("[AtlasMap3D] mesh build failed map={} mask={} tile={}x{} col={} row={}",
                        key.mapId(), mask, div, div, col, row, t);
                Minecraft.getInstance().execute(() -> {
                    if (ENTRIES.get(entry.id) == entry) {
                        entry.building = false;
                    }
                });
                return;
            }
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
                FramedMapDebug.detail("built map={} mask={} quads={} lines={} icons={}",
                        entry.key.mapId(), entry.mask, mesh.quadCount(), mesh.lineCount(),
                        mesh.voxelIcons().size());
            });
        });
    }

    private static int validCount(FramedMapVolume volume) {
        int valid = 0;
        for (int i = 0; i < FramedMapVolume.COLUMNS; i++) {
            if (volume.valid[i]) {
                valid++;
            }
        }
        return valid;
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
        STORED.clear();
    }
}
