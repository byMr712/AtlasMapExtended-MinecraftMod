package org.webtrade.atlasmapextended.client.map;

import com.google.gson.reflect.TypeToken;
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

import java.lang.reflect.Type;
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

    private static final Map<String, int[]> KNOWN_CENTERS = new ConcurrentHashMap<>();
    private static final Type MAP_TYPE = new TypeToken<Map<String, int[]>>() {}.getType();
    private static Path loadedPath = null;
    private static boolean dirty = false;

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
        Map<String, int[]> loaded = Storage.readJson(path, MAP_TYPE, null);
        if (loaded != null) {
            KNOWN_CENTERS.putAll(loaded);
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

        String dim = data.dimension.identifier().toString();
        String mapKey = key(mapId, dim);
        int[] cached = KNOWN_CENTERS.get(mapKey);

        int cx;
        int cz;

        // 1. Singleplayer authoritative server data
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            ServerLevel serverLevel = server.getLevel(data.dimension);
            if (serverLevel != null) {
                MapItemSavedData serverData = serverLevel.getMapData(mapId);
                if (serverData != null) {
                    cx = serverData.centerX;
                    cz = serverData.centerZ;
                    apply(data, mapKey, cx, cz, cached);
                    return;
                }
            }
        }

        // 2. Previously cached center
        if (cached != null) {
            apply(data, mapKey, cached[0], cached[1], cached);
            return;
        }

        // 3. Estimate from player decoration if player is on this map
        LocalPlayer player = Minecraft.getInstance().player;
        int scaleFactor = 1 << data.scale;
        int gridSize = 128 * scaleFactor;

        if (player != null && data.dimension.equals(player.level().dimension())) {
            for (MapDecoration dec : data.getDecorations()) {
                if (dec.type().equals(MapDecorationTypes.PLAYER)) {
                    if (dec.x() > -63 && dec.x() < 63 && dec.y() > -63 && dec.y() < 63) {
                        double approxX = player.getX() - (dec.x() / 2.0) * scaleFactor;
                        double approxZ = player.getZ() - (dec.y() / 2.0) * scaleFactor;
                        cx = align(approxX, data.scale);
                        cz = align(approxZ, data.scale);
                        apply(data, mapKey, cx, cz, cached);
                        return;
                    }
                }
            }

            // 4. If player holds this map right now, center around player
            if (isHeld(player, mapId)) {
                cx = align(player.getX(), data.scale);
                cz = align(player.getZ(), data.scale);
                apply(data, mapKey, cx, cz, cached);
                return;
            }
        }

        // 5. If rendered in an item frame, center around the frame
        if (framePos != null) {
            cx = align(framePos.getX(), data.scale);
            cz = align(framePos.getZ(), data.scale);
            apply(data, mapKey, cx, cz, cached);
            return;
        }

        // 6. Keep whatever is already in data (or default)
        if (data.centerX != 0 || data.centerZ != 0) {
            KNOWN_CENTERS.put(mapKey, new int[]{data.centerX, data.centerZ});
            dirty = true;
            save();
        }
    }

    private static int align(double coord, byte scale) {
        int i = 128 * (1 << scale);
        int j = Mth.floor((coord + 64.0) / (double) i);
        return j * i + i / 2 - 64;
    }

    private static void apply(MapItemSavedData data, String mapKey, int cx, int cz, int[] cached) {
        if (data.centerX != cx || data.centerZ != cz) {
            ((MapItemSavedDataAccessor) data).atlasmapextended$setCenterX(cx);
            ((MapItemSavedDataAccessor) data).atlasmapextended$setCenterZ(cz);
        }
        if (cached == null || cached[0] != cx || cached[1] != cz) {
            KNOWN_CENTERS.put(mapKey, new int[]{cx, cz});
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
