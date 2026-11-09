package org.webtrade.atlasmapextended.client.framed3d;

import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.client.data.Stroke;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Loads or creates the frozen overlay snapshot for a map. */
public final class FramedOverlayStorage {
    private static final Map<Path, FramedMapOverlay> CACHE = new HashMap<>();

    private FramedOverlayStorage() {
    }

    private static Path file(FramedMapKey key) {
        MapSession session = MapManager.live();
        if (session == null) {
            return null;
        }
        String dim = Storage.dimFolder(key.dimension().identifier().toString());
        return session.dir.resolve("framed3d").resolve(dim + "_" + key.mapId()
                + "_" + key.centerX() + "_" + key.centerZ() + "_" + key.scale() + ".overlay.json");
    }

    private static Path legacyFile(FramedMapKey key) {
        MapSession session = MapManager.live();
        if (session == null) {
            return null;
        }
        String dim = Storage.dimFolder(key.dimension().identifier().toString());
        return session.dir.resolve("framed3d").resolve(dim + "_" + key.mapId() + ".overlay.json");
    }

    public static FramedMapOverlay getOrCapture(FramedMapKey key) {
        Path path = file(key);
        if (path == null) {
            return null;
        }
        FramedMapOverlay overlay = CACHE.get(path);
        if (overlay != null && overlay.matches(key)) {
            return overlay;
        }
        overlay = Storage.readJson(path, FramedMapOverlay.class, null);
        if (overlay == null || !overlay.matches(key)) {
            Path legacy = legacyFile(key);
            if (legacy != null) {
                FramedMapOverlay old = Storage.readJson(legacy, FramedMapOverlay.class, null);
                if (old != null && old.matches(key)) {
                    overlay = old;
                }
            }
        }
        if (overlay == null || !overlay.matches(key)) {
            overlay = capture(key);
            if (overlay == null) {
                return null;
            }
            Storage.writeJsonAsync(path, overlay);
        }
        CACHE.put(path, overlay);
        return overlay;
    }

    private static FramedMapOverlay capture(FramedMapKey key) {
        MapSession session = MapManager.live();
        if (session == null) {
            return null;
        }

        FramedMapOverlay overlay = new FramedMapOverlay();
        overlay.dim = key.dimension().identifier().toString();
        overlay.centerX = key.centerX();
        overlay.centerZ = key.centerZ();
        overlay.scale = key.scale();

        int sf = key.scaleFactor();
        double minX = key.centerX() - 64.0 * sf;
        double maxX = key.centerX() + 64.0 * sf;
        double minZ = key.centerZ() - 64.0 * sf;
        double maxZ = key.centerZ() + 64.0 * sf;

        for (Marker marker : session.markers) {
            if (overlay.dim.equals(marker.dim)
                    && marker.x >= minX && marker.x < maxX
                    && marker.z >= minZ && marker.z < maxZ) {
                overlay.markers.add(marker.copy());
            }
        }

        for (Region region : session.regions(overlay.dim)) {
            if (!overlay.dim.equals(region.dim)) {
                continue;
            }
            double[] b = region.bounds();
            if (b[1] >= minX && b[0] <= maxX && b[3] >= minZ && b[2] <= maxZ) {
                overlay.regions.add(region.copy());
            }
        }

        for (Stroke stroke : session.strokes(overlay.dim)) {
            if (intersects(stroke, minX, maxX, minZ, maxZ)) {
                overlay.strokes.add(new Stroke(stroke.type, stroke.color, stroke.width, stroke.pts.clone()));
            }
        }

        return overlay;
    }

    private static boolean intersects(Stroke stroke, double minX, double maxX, double minZ, double maxZ) {
        for (int i = 0; i + 1 < stroke.pts.length; i += 2) {
            double x = stroke.pts[i];
            double z = stroke.pts[i + 1];
            if (x >= minX && x < maxX && z >= minZ && z < maxZ) {
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        CACHE.clear();
    }
}
