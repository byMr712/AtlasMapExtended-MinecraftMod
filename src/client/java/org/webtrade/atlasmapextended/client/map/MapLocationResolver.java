package org.webtrade.atlasmapextended.client.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.mixin.client.MapItemSavedDataAccessor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and fixes map center coordinates on the client.
 * Vanilla packet ClientboundMapItemDataPacket omits centerX and centerZ,
 * leaving client maps hardcoded to (0, 0).
 */
public final class MapLocationResolver {

    private static final Map<String, CenterEntry> KNOWN_CENTERS = new ConcurrentHashMap<>();
    private static Path loadedPath = null;
    private static boolean dirty = false;

    /**
     * Precision of the source. A more precise source is allowed to overwrite
     * a cached approximate center (for example a player marker over a frame
     * guess), while an approximate source never replaces a precise one.
     */
    private enum Source {
        FRAME(1),
        HELD(2),
        // Old files have no source; do not let a frame/held guess replace
        // them, but a real player marker or server value may.
        LEGACY(2),
        DECORATION(3),
        SERVER(4);

        private final int precision;

        Source(int precision) {
            this.precision = precision;
        }

        int precision() {
            return precision;
        }

        static Source from(String value) {
            if (value != null) {
                for (Source source : values()) {
                    if (source.name().equalsIgnoreCase(value)) {
                        return source;
                    }
                }
            }
            return LEGACY;
        }
    }

    /** Public fields so Gson can persist the source together with the center. */
    private static final class CenterEntry {
        public int x;
        public int z;
        public Source source;

        CenterEntry() {
        }

        CenterEntry(int x, int z, Source source) {
            this.x = x;
            this.z = z;
            this.source = source;
        }
    }

    private MapLocationResolver() {
    }

    private static String key(MapId mapId, String dim) {
        return dim + "#" + mapId.id();
    }

    private static void ensureLoaded() {
        MapSession session = MapManager.live();
        if (session == null) {
            return;
        }
        Path path = session.dir.resolve("map_centers.json");
        if (path.equals(loadedPath)) {
            return;
        }
        loadedPath = path;
        KNOWN_CENTERS.clear();
        JsonObject root = Storage.readJson(path, JsonObject.class, null);
        if (root == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonElement value = entry.getValue();
            int x;
            int z;
            Source source = Source.LEGACY;
            if (value.isJsonArray()) {
                // Old format: [x, z]
                var array = value.getAsJsonArray();
                if (array.size() < 2) {
                    continue;
                }
                x = array.get(0).getAsInt();
                z = array.get(1).getAsInt();
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                x = object.has("x") ? object.get("x").getAsInt() : 0;
                z = object.has("z") ? object.get("z").getAsInt() : 0;
                if (object.has("source")) {
                    source = Source.from(object.get("source").getAsString());
                }
            } else {
                continue;
            }
            KNOWN_CENTERS.put(entry.getKey(), new CenterEntry(x, z, source));
        }
    }

    private static void save() {
        if (!dirty) {
            return;
        }
        MapSession session = MapManager.live();
        if (session == null) {
            return;
        }
        dirty = false;
        Path path = session.dir.resolve("map_centers.json");
        Storage.writeJsonAsync(path, new HashMap<>(KNOWN_CENTERS));
    }

    public static void resolve(Level level, MapId mapId, MapItemSavedData data, BlockPos framePos) {
        if (mapId == null || data == null || level == null) {
            return;
        }
        ensureLoaded();

        String dim = data.dimension.location().toString();
        String mapKey = key(mapId, dim);
        CenterEntry cached = KNOWN_CENTERS.get(mapKey);

        // 1. Singleplayer authoritative server data
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            ServerLevel serverLevel = server.getLevel(data.dimension);
            if (serverLevel != null) {
                MapItemSavedData serverData = serverLevel.getMapData(mapId);
                if (serverData != null) {
                    apply(data, mapKey, serverData.centerX, serverData.centerZ, cached, Source.SERVER);
                    return;
                }
            }
        }

        LocalPlayer player = Minecraft.getInstance().player;
        int scaleFactor = 1 << data.scale;
        if (player != null && data.dimension.equals(player.level().dimension())) {
            // 2. A player marker is a precise estimate and may replace a
            //    cached frame/held guess.
            for (MapDecoration decoration : data.getDecorations()) {
                if (decoration.type().equals(MapDecorationTypes.PLAYER)) {
                    if (decoration.x() > -63 && decoration.x() < 63
                            && decoration.y() > -63 && decoration.y() < 63) {
                        double approxX = player.getX() - (decoration.x() / 2.0) * scaleFactor;
                        double approxZ = player.getZ() - (decoration.y() / 2.0) * scaleFactor;
                        int cx = align(approxX, data.scale);
                        int cz = align(approxZ, data.scale);
                        if (cached == null || Source.DECORATION.precision() > cached.source.precision()) {
                            apply(data, mapKey, cx, cz, cached, Source.DECORATION);
                            return;
                        }
                        break;
                    }
                }
            }

            // 3. Held map: the player is probably on/near it, but this stays
            //    a fallback below a real marker.
            if (isHeld(player, mapId)
                    && (cached == null || Source.HELD.precision() > cached.source.precision())) {
                int cx = align(player.getX(), data.scale);
                int cz = align(player.getZ(), data.scale);
                apply(data, mapKey, cx, cz, cached, Source.HELD);
                return;
            }
        }

        // 4. Previously cached center (already the best available).
        if (cached != null) {
            apply(data, mapKey, cached.x, cached.z, cached, cached.source);
            return;
        }

        // 5. Frame position: the last resort when nothing anchors the map.
        if (framePos != null) {
            int cx = align(framePos.getX(), data.scale);
            int cz = align(framePos.getZ(), data.scale);
            apply(data, mapKey, cx, cz, null, Source.FRAME);
            return;
        }

        // 6. Keep whatever is already in data (or default)
        if (data.centerX != 0 || data.centerZ != 0) {
            KNOWN_CENTERS.put(mapKey, new CenterEntry(data.centerX, data.centerZ, Source.LEGACY));
            dirty = true;
            save();
        }
    }

    private static int align(double coord, byte scale) {
        int i = 128 * (1 << scale);
        int j = Mth.floor((coord + 64.0) / (double) i);
        return j * i + i / 2 - 64;
    }

    private static void apply(MapItemSavedData data, String mapKey, int cx, int cz,
                              CenterEntry cached, Source source) {
        if (cached != null && cached.source.precision() > source.precision()) {
            // Never replace a more precise cached position with a guess.
            cx = cached.x;
            cz = cached.z;
            source = cached.source;
        }
        if (data.centerX != cx || data.centerZ != cz) {
            ((MapItemSavedDataAccessor) data).atlasmapextended$setCenterX(cx);
            ((MapItemSavedDataAccessor) data).atlasmapextended$setCenterZ(cz);
        }
        if (cached == null || cached.x != cx || cached.z != cz || cached.source != source) {
            KNOWN_CENTERS.put(mapKey, new CenterEntry(cx, cz, source));
            dirty = true;
            save();
        }
    }

    private static boolean isHeld(LocalPlayer player, MapId mapId) {
        return isMap(player.getMainHandItem(), mapId) || isMap(player.getOffhandItem(), mapId);
    }

    private static boolean isMap(ItemStack stack, MapId mapId) {
        if (stack.isEmpty()) {
            return false;
        }
        MapId id = stack.get(DataComponents.MAP_ID);
        return id != null && id.equals(mapId);
    }

    public static void clear() {
        save();
        KNOWN_CENTERS.clear();
        loadedPath = null;
        dirty = false;
    }
}
