package org.webtrade.atlasmapextended.client.map;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.client.gui.RegionToast;
import org.webtrade.atlasmapextended.client.gui.T;
import org.webtrade.atlasmapextended.client.data.WorldMeta;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Owns the map session of the world the player is currently in and keeps it up to date. */
public final class MapManager {
    private static final long SCAN_BUDGET_NS = 1_500_000L;

    private static MapSession live;
    private static String liveDim;
    /** Player position on the previous tick, so the exit point of a dimension is exact. */
    private static double curX;
    private static double curZ;
    /** Pan/zoom of dimensions opened remotely, valid only while in the current dimension. */
    private static final Map<String, double[]> remoteView = new HashMap<>();
    private static final Map<String, Float> remoteZoom = new HashMap<>();
    /** Regions the player is currently inside (id -> display name). */
    private static final Map<String, String> insideRegions = new HashMap<>();
    private static final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
    private static int ticks;

    private MapManager() {
    }

    public static MapSession live() {
        return live;
    }

    public static String liveDim() {
        return liveDim;
    }
    public static double[] remoteView(String dim) {
        return remoteView.get(dim);
    }

    public static Float remoteZoom(String dim) {
        return remoteZoom.get(dim);
    }

    public static void rememberView(String dim, double x, double z, float zoom) {
        remoteView.put(dim, new double[]{x, z});
        remoteZoom.put(dim, zoom);
    }


    public static void onJoin(Minecraft mc) {
        closeLive();
        WorldMeta meta = resolveMeta(mc);
        live = MapSession.open(meta);
        live.live = true;
        liveDim = null;
        remoteView.clear();
        remoteZoom.clear();
        insideRegions.clear();
        queue.clear();
        live.saveMeta();
    }

    public static void onDisconnect() {
        closeLive();
    }

    private static void closeLive() {
        if (live != null) {
            if (liveDim != null) {
                live.meta.setPos(liveDim, curX, curZ);
            }
            live.meta.lastPlayed = System.currentTimeMillis();
            live.saveMeta();
            live.saveMarkers();
            live.saveStrokes();
            live.saveRegions();
            live.close();
            live = null;
        }
        liveDim = null;
        insideRegions.clear();
        queue.clear();
    }

    /** Opens a stored world for viewing. Reuses the live session if it is the same world. */
    public static MapSession openForViewing(WorldMeta meta) {
        if (live != null && live.meta.id.equals(meta.id)) {
            return live;
        }
        return MapSession.open(meta);
    }

    public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        if (live != null && level == Minecraft.getInstance().level) {
            queue.add(MapLayer.pack(chunk.getPos().x, chunk.getPos().z));
        }
    }

    public static void tick(Minecraft mc) {
        if (live == null || mc.level == null || mc.player == null) {
            return;
        }
        ClientLevel level = mc.level;
        String dim = level.dimension().location().toString();
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        if (!dim.equals(liveDim)) {
            if (liveDim != null) {
                live.meta.setPos(liveDim, curX, curZ);
                live.saveMeta();
            }
            liveDim = dim;
            // Panning a remote dimension only lasts while the player stays in this one.
            remoteView.clear();
            remoteZoom.clear();
            insideRegions.clear();
            queue.clear();
            int r = mc.options.getEffectiveRenderDistance();
            enqueueAround(pcx, pcz, r);
        }
        ticks++;
        if (ticks % 40 == 0) {
            enqueueAround(pcx, pcz, 2);
        }
        if (ticks % 10 == 0) {
            // track the whole set, not just one region: with nested regions the player can be
            // inside several at once, and only the actual boundary crossings must be announced
            Map<String, String> now = new HashMap<>();
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            for (Region r : live.regions(dim)) {
                if (!r.hidden && r.dim.equals(dim) && r.mayContain(px, pz) && r.contains(px, pz)) {
                    now.put(r.id, r.name == null || r.name.isBlank() ? "Region" : r.name);
                }
            }
            for (Map.Entry<String, String> e : insideRegions.entrySet()) {
                if (!now.containsKey(e.getKey())) {
                    RegionToast.show(T.s("region.exit_title"), e.getValue());
                }
            }
            for (Map.Entry<String, String> e : now.entrySet()) {
                if (!insideRegions.containsKey(e.getKey())) {
                    RegionToast.show(T.s("region.enter_title"), e.getValue());
                }
            }
            insideRegions.clear();
            insideRegions.putAll(now);
        }

        MapLayer layer = live.layer(dim);
        long start = System.nanoTime();
        while (!queue.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NS) {
            long k = queue.removeFirstLong();
            int cx = (int) (k >> 32);
            int cz = (int) k;
            if (level.hasChunk(cx, cz)) {
                LevelChunk chunk = level.getChunk(cx, cz);
                if (!chunk.isEmpty()) {
                    ChunkScanner.scan(level, chunk, layer);
                }
            }
        }
        layer.uploadDirty();

        if (ticks % 100 == 0) {
            live.meta.lastDim = dim;
            live.meta.lastX = mc.player.getX();
            live.meta.lastZ = mc.player.getZ();
            live.meta.lastPlayed = System.currentTimeMillis();
        }
        if (ticks % 1200 == 0) {
            live.saveTiles();
            live.saveMeta();
            live.unloadUnused();
        }
        curX = mc.player.getX();
        curZ = mc.player.getZ();
    }

    private static void enqueueAround(int cx, int cz, int r) {
        // spiral-ish order: closest chunks first
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                for (int z = -d; z <= d; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == d) {
                        queue.add(MapLayer.pack(cx + x, cz + z));
                    }
                }
            }
        }
    }

    private static WorldMeta resolveMeta(Minecraft mc) {
        WorldMeta meta = new WorldMeta();
        IntegratedServer sp = mc.getSingleplayerServer();
        ServerData sd = mc.getCurrentServer();
        if (sp != null) {
            Path root = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            String folder = root.getFileName() == null ? "world" : root.getFileName().toString();
            meta.id = "local_" + Storage.sanitize(folder);
            meta.name = sp.getWorldData().getLevelName();
            meta.address = folder;
            meta.type = WorldMeta.LOCAL;
        } else if (sd != null) {
            meta.id = "server_" + Storage.sanitize(sd.ip);
            meta.name = sd.name;
            meta.address = sd.ip;
            meta.type = WorldMeta.SERVER;
        } else {
            meta.id = "unknown";
            meta.name = "Unknown";
            meta.type = WorldMeta.SERVER;
        }
        WorldMeta old = Storage.readJson(Storage.worldDir(meta.id).resolve("meta.json"), WorldMeta.class, null);
        if (old != null) {
            meta.lastDim = old.lastDim;
            meta.lastX = old.lastX;
            meta.lastZ = old.lastZ;
        }
        meta.lastPlayed = System.currentTimeMillis();
        return meta;
    }
}
