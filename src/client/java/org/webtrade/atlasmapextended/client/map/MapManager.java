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
import org.webtrade.atlasmapextended.client.data.ModConfig;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

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

    public static String normalizeServerAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return "server";
        }
        String s = ip.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(":25565")) {
            s = s.substring(0, s.length() - 6);
        }
        return s;
    }

    public static String resolveDefaultServerId(ServerData sd) {
        if (sd == null) {
            return "unknown";
        }
        if (sd.isLan()) {
            String raw = sd.ip != null ? sd.ip.trim().toLowerCase(Locale.ROOT) : "";
            String host = raw.contains(":") ? raw.substring(0, raw.lastIndexOf(':')) : raw;
            String name = sd.name != null && !sd.name.isBlank() ? sd.name : "world";
            return "server_lan_" + Storage.sanitize(host + "_" + name);
        }
        if (sd.isRealm()) {
            String name = sd.name != null && !sd.name.isBlank() ? sd.name : "realm";
            return "server_realm_" + Storage.sanitize(name);
        }
        String norm = normalizeServerAddress(sd.ip);
        return "server_" + Storage.sanitize(norm);
    }

    public static String resolveDefaultServerKey(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            return null;
        }
        ServerData sd = mc.getCurrentServer();
        if (sd == null && mc.getConnection() != null) {
            sd = mc.getConnection().getServerData();
        }
        if (sd == null && mc.getConnection() != null && mc.getConnection().getConnection() != null) {
            SocketAddress sa = mc.getConnection().getConnection().getRemoteAddress();
            if (sa instanceof InetSocketAddress isa) {
                String host = isa.getHostString().toLowerCase(Locale.ROOT);
                int port = isa.getPort();
                String norm = (port == 25565 || port <= 0) ? host : host + ":" + port;
                sd = new ServerData(norm, norm, ServerData.Type.OTHER);
            }
        }
        return resolveDefaultServerId(sd);
    }

    
    public static void onDeleteSingleplayerWorld(String folder) {
        if (folder == null || folder.isBlank()) {
            return;
        }
        String worldId = "local_" + Storage.sanitize(folder);
        archiveWorld(worldId, "Deleted");
    }

    public static void archiveWorld(String worldId, String reasonTag) {
        Path worldDir = Storage.worldDir(worldId);
        if (!Files.isDirectory(worldDir)) {
            return;
        }
        try {
            String archiveId = worldId + "_old_" + System.currentTimeMillis();
            Path archiveDir = Storage.worldDir(archiveId);
            Files.move(worldDir, archiveDir);
            WorldMeta old = Storage.readJson(archiveDir.resolve("meta.json"), WorldMeta.class, null);
            if (old != null) {
                old.id = archiveId;
                if (old.name != null && !old.name.isBlank()) {
                    old.name = old.name + " (" + (reasonTag != null ? reasonTag : "Old") + ")";
                }
                Storage.writeJson(archiveDir.resolve("meta.json"), old);
            }
            Storage.LOG.info("Archived world map " + worldId + " to " + archiveId + " (" + reasonTag + ")");
        } catch (Exception e) {
            Storage.LOG.error("Failed to archive world map " + worldId, e);
        }
    }

    public static void switchLive(String newWorldId) {
        Minecraft mc = Minecraft.getInstance();
        if (live == null || !live.isLive()) {
            return;
        }
        String defaultKey = resolveDefaultServerKey(mc);
        if (defaultKey != null) {
            ModConfig cfg = ModConfig.get();
            if (newWorldId == null || newWorldId.equals(defaultKey)) {
                cfg.serverMapOverrides.remove(defaultKey);
            } else {
                cfg.serverMapOverrides.put(defaultKey, newWorldId);
            }
            cfg.save();
        }
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

            long currentSeed = 0L;
            try {
                if (sp.overworld() != null) {
                    currentSeed = sp.overworld().getSeed();
                }
            } catch (Exception ignored) {
            }
            meta.seed = currentSeed;

            Path uidFile = root.resolve(".atlasmap_uid");
            String currentUid = null;
            if (Files.exists(uidFile)) {
                try {
                    currentUid = Files.readString(uidFile).trim();
                } catch (Exception ignored) {
                }
            }

            Path worldDir = Storage.worldDir(meta.id);
            WorldMeta old = Storage.readJson(worldDir.resolve("meta.json"), WorldMeta.class, null);
            boolean recreated = false;
            if (old != null) {
                if (old.seed != 0L && currentSeed != 0L && old.seed != currentSeed) {
                    recreated = true;
                } else if (old.uid != null && !old.uid.isBlank() && (currentUid == null || !currentUid.equals(old.uid))) {
                    recreated = true;
                }
            }

            if (recreated) {
                archiveWorld(meta.id, "Old Seed");
                if (Files.isDirectory(worldDir)) {
                    meta.id = meta.id + "_" + (currentSeed != 0 ? Math.abs(currentSeed % 100000) : System.currentTimeMillis() % 100000);
                }
            }

            if (currentUid == null || currentUid.isBlank()) {
                currentUid = java.util.UUID.randomUUID().toString();
                try {
                    Files.writeString(uidFile, currentUid);
                } catch (Exception ignored) {
                }
            }
            meta.uid = currentUid;
        } else {
            if (sd == null && mc.getConnection() != null) {
                sd = mc.getConnection().getServerData();
            }
            if (sd == null && mc.getConnection() != null && mc.getConnection().getConnection() != null) {
                SocketAddress sa = mc.getConnection().getConnection().getRemoteAddress();
                if (sa instanceof InetSocketAddress isa) {
                    String host = isa.getHostString().toLowerCase(Locale.ROOT);
                    int port = isa.getPort();
                    String norm = (port == 25565 || port <= 0) ? host : host + ":" + port;
                    sd = new ServerData(norm, norm, ServerData.Type.OTHER);
                }
            }
            String defaultId = resolveDefaultServerId(sd);
            String targetId = defaultId;

            ModConfig cfg = ModConfig.get();
            if (cfg != null && cfg.serverMapOverrides != null && cfg.serverMapOverrides.containsKey(defaultId)) {
                String override = cfg.serverMapOverrides.get(defaultId);
                if (override != null && !override.isBlank() && Files.isDirectory(Storage.worldDir(override))) {
                    targetId = override;
                }
            }

            if (targetId.equals(defaultId) && !Files.isDirectory(Storage.worldDir(targetId)) && sd != null) {
                String norm = normalizeServerAddress(sd.ip);
                String legacyPrefix = "server_" + Storage.sanitize(norm) + "_25565_";
                Path worlds = Storage.worldsDir();
                if (Files.isDirectory(worlds)) {
                    try (Stream<Path> s = Files.list(worlds)) {
                        Optional<Path> found = s.filter(Files::isDirectory)
                                .filter(p -> p.getFileName().toString().startsWith(legacyPrefix))
                                .findFirst();
                        if (found.isPresent()) {
                            targetId = found.get().getFileName().toString();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            meta.id = targetId;
            meta.type = WorldMeta.SERVER;
            if (sd != null) {
                meta.name = sd.name != null && !sd.name.isBlank() ? sd.name : (sd.ip != null ? normalizeServerAddress(sd.ip) : "Server");
                meta.address = sd.ip != null ? normalizeServerAddress(sd.ip) : "server";
            } else {
                meta.name = "Server";
                meta.address = "server";
            }
        }
        WorldMeta old = Storage.readJson(Storage.worldDir(meta.id).resolve("meta.json"), WorldMeta.class, null);
        if (old != null) {
            meta.lastDim = old.lastDim;
            meta.lastX = old.lastX;
            meta.lastZ = old.lastZ;
            if (old.name != null && !old.name.isBlank()) {
                meta.name = old.name;
            }
        }
        meta.lastPlayed = System.currentTimeMillis();
        return meta;
    }
}
