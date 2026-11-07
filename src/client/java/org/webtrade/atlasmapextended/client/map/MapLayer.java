package org.webtrade.atlasmapextended.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import org.webtrade.atlasmapextended.client.data.Storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** All map tiles of one dimension of one world. Tiles are loaded lazily and unloaded when unused. */
public class MapLayer {
    private static final int LOADS_PER_FRAME = 6;
    private static final long UNLOAD_AFTER_MS = 90_000;
    private static int frameLoads;

    public final String dim;
    private final String key;
    private final Path dir;
    private final Map<Long, MapTile> tiles = new HashMap<>();
    private final Set<Long> onDisk = new HashSet<>();
    private final Set<Long> broken = new HashSet<>();

    MapLayer(String worldId, String dim, Path dir) {
        this.dim = dim;
        this.dir = dir;
        this.key = Storage.asciiId(worldId) + "/" + Storage.asciiId(dim);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.forEach(p -> {
                    String n = p.getFileName().toString();
                    if (n.endsWith(".png")) {
                        String[] parts = n.substring(0, n.length() - 4).split("_");
                        if (parts.length == 2) {
                            try {
                                onDisk.add(pack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                });
            } catch (IOException e) {
                Storage.LOG.warn("Failed to list tiles in {}", dir, e);
            }
        }
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Called once per rendered frame to limit how many tiles may be decoded from disk. */
    public static void newFrame() {
        frameLoads = 0;
    }

    public boolean isEmpty() {
        return onDisk.isEmpty() && tiles.isEmpty();
    }

    /** True when this layer already has scanned pixels at the given world block. */
    public boolean hasData(int wx, int wz) {
        int rx = Math.floorDiv(wx, MapTile.SIZE);
        int rz = Math.floorDiv(wz, MapTile.SIZE);
        MapTile t = getForRender(rx, rz);
        if (t == null) {
            return false;
        }
        int px = Math.floorMod(wx, MapTile.SIZE);
        int pz = Math.floorMod(wz, MapTile.SIZE);
        return (t.getPixel(px, pz) >>> 24) != 0;
    }

    /** Centre of the most recently explored area, or null when this dimension has no tiles. */
    public double[] center() {
        Set<Long> keys = new HashSet<>(onDisk);
        for (MapTile t : tiles.values()) {
            keys.add(pack(t.rx, t.rz));
        }
        if (keys.isEmpty()) {
            return null;
        }
        // Anchor on the freshest tile: that is where the player was last.
        long anchor = keys.iterator().next();
        long newest = Long.MIN_VALUE;
        for (long k : keys) {
            long stamp = 0L;
            if (onDisk.contains(k)) {
                try {
                    stamp = Files.getLastModifiedTime(tileFile(k)).toMillis();
                } catch (IOException ignored) {
                }
            }
            if (stamp > newest) {
                newest = stamp;
                anchor = k;
            }
        }
        int ax = (int) (anchor >> 32);
        int az = (int) anchor;
        // Average the tiles around the anchor so the view lands in the middle of that area,
        // not on the corner tile. Far-away clusters are ignored on purpose.
        long sx = 0;
        long sz = 0;
        long n = 0;
        for (long k : keys) {
            int rx = (int) (k >> 32);
            int rz = (int) k;
            if (Math.abs(rx - ax) <= 4 && Math.abs(rz - az) <= 4) {
                sx += (long) rx * MapTile.SIZE + MapTile.SIZE / 2L;
                sz += (long) rz * MapTile.SIZE + MapTile.SIZE / 2L;
                n++;
            }
        }
        if (n == 0) {
            return new double[]{(long) ax * MapTile.SIZE + MapTile.SIZE / 2.0,
                    (long) az * MapTile.SIZE + MapTile.SIZE / 2.0};
        }
        return new double[]{(double) sx / n, (double) sz / n};
    }

    private Path tileFile(long k) {
        return dir.resolve((int) (k >> 32) + "_" + (int) k + ".png");
    }

    /** Loads a tile without the per-frame budget; used when baking a one-off texture. */
    public MapTile getForRead(int rx, int rz) {
        long k = pack(rx, rz);
        MapTile t = tiles.get(k);
        if (t != null) {
            t.touch();
            return t;
        }
        if (onDisk.contains(k) && !broken.contains(k)) {
            return load(rx, rz);
        }
        return null;
    }

    /** Returns the tile for rendering, loading it from disk if the per-frame budget allows. */
    public MapTile getForRender(int rx, int rz) {
        long k = pack(rx, rz);
        MapTile t = tiles.get(k);
        if (t != null) {
            t.touch();
            return t;
        }
        if (onDisk.contains(k) && !broken.contains(k) && frameLoads < LOADS_PER_FRAME) {
            frameLoads++;
            return load(rx, rz);
        }
        return null;
    }

    /** Returns the tile for writing, creating it (or loading it) if needed. */
    public MapTile getOrCreate(int rx, int rz) {
        long k = pack(rx, rz);
        MapTile t = tiles.get(k);
        if (t == null && onDisk.contains(k) && !broken.contains(k)) {
            t = load(rx, rz);
        }
        if (t == null) {
            t = new MapTile(key, rx, rz, new NativeImage(MapTile.SIZE, MapTile.SIZE, true));
            tiles.put(k, t);
        }
        t.touch();
        return t;
    }

    private MapTile load(int rx, int rz) {
        long k = pack(rx, rz);
        Path file = dir.resolve(rx + "_" + rz + ".png");
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage img = NativeImage.read(in);
            if (img.getWidth() != MapTile.SIZE || img.getHeight() != MapTile.SIZE || img.format() != NativeImage.Format.RGBA) {
                img.close();
                broken.add(k);
                return null;
            }
            MapTile t = new MapTile(key, rx, rz, img);
            tiles.put(k, t);
            return t;
        } catch (IOException e) {
            Storage.LOG.warn("Failed to load map tile {}", file, e);
            broken.add(k);
            return null;
        }
    }

    /** Pushes changed pixels to the GPU. */
    public void uploadDirty() {
        for (MapTile t : tiles.values()) {
            t.upload();
        }
    }

    public void saveDirty() {
        for (MapTile t : tiles.values()) {
            if (t.diskDirty) {
                t.diskDirty = false;
                NativeImage copy = t.snapshot();
                Path file = dir.resolve(t.rx + "_" + t.rz + ".png");
                onDisk.add(pack(t.rx, t.rz));
                Storage.IO.execute(() -> {
                    try {
                        Files.createDirectories(dir);
                        copy.writeToFile(file);
                    } catch (IOException e) {
                        Storage.LOG.warn("Failed to save map tile {}", file, e);
                    } finally {
                        copy.close();
                    }
                });
            }
        }
    }

    /** Frees GPU memory of tiles that were not looked at for a while. */
    public void unloadUnused() {
        long now = System.currentTimeMillis();
        Iterator<MapTile> it = tiles.values().iterator();
        List<MapTile> removed = new ArrayList<>();
        while (it.hasNext()) {
            MapTile t = it.next();
            if (!t.diskDirty && now - t.lastUsed > UNLOAD_AFTER_MS) {
                it.remove();
                removed.add(t);
            }
        }
        removed.forEach(MapTile::release);
    }

    public void close() {
        saveDirty();
        tiles.values().forEach(MapTile::release);
        tiles.clear();
    }
}
