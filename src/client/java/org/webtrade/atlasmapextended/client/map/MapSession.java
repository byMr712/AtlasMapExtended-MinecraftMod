package org.webtrade.atlasmapextended.client.map;

import org.webtrade.atlasmapextended.client.gui.T;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.client.data.Stroke;
import org.webtrade.atlasmapextended.client.data.WorldMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.stream.Stream;

/** Everything stored for one world: map tiles per dimension, markers, drawings and screenshots. */
public class MapSession {
    public final WorldMeta meta;
    public final Path dir;
    public final List<Marker> markers;
    private final Map<String, List<Stroke>> strokes;
    private final Map<String, List<Region>> regions;
    private final Map<String, MapLayer> layers = new ConcurrentHashMap<>();
    boolean live;

    private MapSession(WorldMeta meta) {
        this.meta = meta;
        this.dir = Storage.worldDir(meta.id);
        this.markers = new ArrayList<>(Storage.readJson(dir.resolve("markers.json"), Storage.MARKER_LIST, List.<Marker>of()));
        this.strokes = new HashMap<>(Storage.readJson(dir.resolve("drawings.json"), Storage.STROKE_MAP, Map.<String, List<Stroke>>of()));
        this.strokes.replaceAll((k, v) -> new ArrayList<>(v));
        this.regions = new HashMap<>(Storage.readJson(dir.resolve("regions.json"), Storage.REGION_MAP, Map.<String, List<Region>>of()));
        this.regions.replaceAll((k, v) -> new ArrayList<>(v));
        for (Marker m : markers) {
            if (m.screenshots == null) {
                m.screenshots = new ArrayList<>();
            }
            if (m.shotDrawings == null) {
                m.shotDrawings = new java.util.HashMap<>();
            }
            if (m.icon == null) {
                m.icon = "pin";
            }
            // coloured copies of icons were merged into one tintable icon
            T.Alias alias = T.ALIASES.get(m.icon);
            if (alias != null) {
                m.icon = alias.icon();
                if (m.color == 0) {
                    m.color = alias.color();
                }
            }
        }
        long now = System.currentTimeMillis();
        for (List<Region> list : regions.values()) {
            for (Region r : list) {
                if (r.screenshots == null) {
                    r.screenshots = new ArrayList<>();
                }
                if (r.shotDrawings == null) {
                    r.shotDrawings = new java.util.HashMap<>();
                }
                if (r.created == 0) {
                    r.created = now;
                }
            }
        }
    }

    public static MapSession open(WorldMeta meta) {
        return new MapSession(meta);
    }

    public boolean isLive() {
        return live;
    }

    public MapLayer layer(String dim) {
        return layers.computeIfAbsent(dim, d -> new MapLayer(meta.id, d, dir.resolve("tiles").resolve(Storage.dimFolder(d))));
    }

    public List<Stroke> strokes(String dim) {
        return strokes.computeIfAbsent(dim, d -> new ArrayList<>());
    }

    public List<Region> regions(String dim) {
        return regions.computeIfAbsent(dim, d -> new ArrayList<>());
    }

    /** Every region of every dimension, for the object list. */
    public List<Region> allRegions() {
        List<Region> out = new ArrayList<>();
        regions.values().forEach(out::addAll);
        return out;
    }

    public Path screenshotsDir() {
        return dir.resolve("screenshots");
    }

    public Path screenshot(String file) {
        return screenshotsDir().resolve(file);
    }

    /** Dimensions that have anything stored, plus the vanilla ones in a stable order. */
    public List<String> knownDims() {
        Set<String> out = new LinkedHashSet<>();
        Path tiles = dir.resolve("tiles");
        Map<String, String> folderToDim = new HashMap<>();
        for (String d : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
            folderToDim.put(Storage.dimFolder(d), d);
        }
        for (Marker m : markers) {
            folderToDim.put(Storage.dimFolder(m.dim), m.dim);
        }
        folderToDim.put(Storage.dimFolder(meta.lastDim), meta.lastDim);
        List<String> found = new ArrayList<>();
        if (Files.isDirectory(tiles)) {
            try (Stream<Path> s = Files.list(tiles)) {
                s.forEach(p -> {
                    String f = p.getFileName().toString();
                    found.add(folderToDim.getOrDefault(f, f.replaceFirst("_", ":")));
                });
            } catch (IOException ignored) {
            }
        }
        for (String d : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
            if (found.contains(d) || d.equals(meta.lastDim)) {
                out.add(d);
            }
        }
        out.addAll(found);
        for (Marker m : markers) {
            out.add(m.dim);
        }
        layers.keySet().forEach(out::add);
        regions.keySet().forEach(out::add);
        if (out.isEmpty()) {
            out.add("minecraft:overworld");
        }
        return new ArrayList<>(out);
    }

    public Marker marker(String id) {
        for (Marker m : markers) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public void saveMarkers() {
        Storage.writeJsonAsync(dir.resolve("markers.json"), new ArrayList<>(markers));
    }

    public void saveStrokes() {
        Map<String, List<Stroke>> copy = new HashMap<>();
        strokes.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        Storage.writeJsonAsync(dir.resolve("drawings.json"), copy);
    }

    public void saveRegions() {
        Map<String, List<Region>> copy = new HashMap<>();
        regions.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        Storage.writeJsonAsync(dir.resolve("regions.json"), copy);
    }

    public void saveMeta() {
        Storage.writeJsonAsync(dir.resolve("meta.json"), meta);
    }

    public void saveTiles() {
        layers.values().forEach(MapLayer::saveDirty);
    }

    public void unloadUnused() {
        layers.values().forEach(MapLayer::unloadUnused);
    }

    public void close() {
        layers.values().forEach(MapLayer::close);
        layers.clear();
    }
}
