package org.webtrade.atlasmapextended.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Stroke;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapConnections;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapKey;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapOverlay;
import org.webtrade.atlasmapextended.client.framed3d.FramedOverlayStorage;
import org.webtrade.atlasmapextended.client.gui.Paper;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Papyrus version of a held filled map: the same pixels as the vanilla map,
 * but inked, sepia-toned, framed with a paper margin and with the frozen
 * marker/region/drawing snapshot baked in.
 */
public final class HeldMapTextures {
    private static final int SIZE = 128;
    private static final int BORDER = 3;
    private static final int PAPER = 0xFFEAD8AE;
    private static final int PAPER_LIGHT = 0xFFF2E4C2;
    private static final int INK = 0xFF4A3B2A;
    private static final int MARKER_SIZE = 8;

    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final Map<String, NativeImage> ICONS = new HashMap<>();

    private HeldMapTextures() {
    }

    private static final class Entry {
        final Identifier id;
        final DynamicTexture texture;
        long hash = Long.MIN_VALUE;

        Entry(Identifier id, DynamicTexture texture) {
            this.id = id;
            this.texture = texture;
        }
    }

    public static Identifier get(MapId mapId, MapItemSavedData data) {
        return get(mapId, data, true, 0, true, data.centerX, data.centerZ);
    }

    /** Frame base: with 3D disabled it also gets the 2D snapshot overlay. */
    public static Identifier getFramed(MapId mapId, MapItemSavedData data) {
        return getFramed(mapId, data, 0, data.centerX, data.centerZ);
    }

    public static Identifier getFramed(MapId mapId, MapItemSavedData data, int textureMask) {
        return getFramed(mapId, data, textureMask, data.centerX, data.centerZ);
    }

    public static Identifier getFramed(MapId mapId, MapItemSavedData data, int textureMask,
                                       int tileCenterX, int tileCenterZ) {
        // A framed map has no paper margin: its content must reach the edge so
        // neighbouring frames form one picture. A held map keeps the margin.
        return get(mapId, data, !ModConfig.get().framedMaps3D, textureMask, false,
                tileCenterX, tileCenterZ);
    }

    private static Identifier get(MapId mapId, MapItemSavedData data, boolean withOverlay,
                                  int textureMask, boolean border, int tileCenterX, int tileCenterZ) {
        if (data.colors == null) {
            return null;
        }
        int id = mapId.id();
        int mask = textureMask & 0x0F;
        boolean tile = tileCenterX != data.centerX || tileCenterZ != data.centerZ;
        FramedMapOverlay overlay = withOverlay ? overlayFor(mapId, data, tileCenterX, tileCenterZ) : null;
        long hash = Arrays.hashCode(data.colors) * 31L + (overlay == null ? 0 : 1)
                + (withOverlay ? 1 : 0) + mask * 17L + (border ? 1 : 0)
                + tileCenterX * 31L + tileCenterZ;
        String cacheKey = id + (withOverlay ? "h" : "f") + (border ? "b" : "n")
                + "_" + mask + "_" + tileCenterX + "_" + tileCenterZ;
        Entry entry = ENTRIES.get(cacheKey);
        if (entry == null) {
            Identifier textureId = Paper.id((withOverlay ? "held_map/" : "framed_map/")
                    + id + (border ? "b" : "n") + "_" + mask + "_" + tileCenterX + "_" + tileCenterZ);
            DynamicTexture texture = new DynamicTexture(
                    () -> "atlasmap-held-" + id, SIZE, SIZE, false);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            entry = new Entry(textureId, texture);
            ENTRIES.put(cacheKey, entry);
        }
        if (entry.hash != hash) {
            entry.hash = hash;
            paint(entry.texture.getPixels(), data.colors, overlay, data, mask, border,
                    tile, tileCenterX, tileCenterZ);
            entry.texture.upload();
        }
        return entry.id;
    }

    /** Vanilla water brightness levels (LOW..HIGH) mapped to the mod's 1..14 depth palette. */
    private static int waterDepth(int brightness) {
        return switch (brightness) {
            case 2 -> 1;   // HIGH: shallow
            case 1 -> 5;   // NORMAL
            case 0 -> 10;  // LOW: deep
            default -> 14; // LOWEST: deepest
        };
    }

    private static FramedMapOverlay overlayFor(MapId mapId, MapItemSavedData data,
                                               int centerX, int centerZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        FramedMapKey key = new FramedMapKey(
                data.dimension, mapId.id(),
                centerX, centerZ, data.scale,
                mc.level.getMinY(), mc.level.getHeight());
        return FramedOverlayStorage.getOrCapture(key);
    }

    private static void paint(NativeImage image, byte[] colors, FramedMapOverlay overlay,
                              MapItemSavedData data, int textureMask, boolean border,
                              boolean tile, int tileCenterX, int tileCenterZ) {
        // Prefer the mod's own tiles: they already carry the height shading and
        // water-depth palette used by the minimap and the world map.
        MapSession session = MapManager.live();
        MapLayer layer = session == null ? null : session.layer(data.dimension.identifier().toString());
        int scaleFactor = 1 << data.scale;
        int centerX = tile ? tileCenterX : data.centerX;
        int centerZ = tile ? tileCenterZ : data.centerZ;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int wx = centerX + (x - 64) * scaleFactor;
                int wz = centerZ + (y - 64) * scaleFactor;
                int packed = 0;
                int mx = 64 + Math.floorDiv(wx - data.centerX, scaleFactor);
                int mz = 64 + Math.floorDiv(wz - data.centerZ, scaleFactor);
                if (mx >= 0 && mz >= 0 && mx < SIZE && mz < SIZE) {
                    packed = colors[mz * SIZE + mx] & 0xFF;
                }
                int argb = 0;
                if (layer != null) {
                    MapTile mapTile = layer.getForRead(
                            Math.floorDiv(wx, MapTile.SIZE), Math.floorDiv(wz, MapTile.SIZE));
                    if (mapTile != null) {
                        argb = mapTile.getPixel(Math.floorMod(wx, MapTile.SIZE),
                                Math.floorMod(wz, MapTile.SIZE));
                    }
                }
                if (argb == 0) {
                    if (packed == 0) {
                        argb = PAPER_LIGHT;
                    } else {
                        // Fallback for areas the mod has not scanned yet: vanilla
                        // colours still keep the same papyrus palette.
                        MapColor color = MapColor.byId(packed >> 2);
                        int waterDepth = color == MapColor.WATER ? waterDepth(packed & 3) : 0;
                        argb = ChunkScanner.papyrus(color.col, 0, 0, 0, waterDepth, wx, wz);
                    }
                }
                image.setPixelABGR(x, y, abgr(argb));
            }
        }

        if (overlay != null) {
            paintOverlay(image, overlay);
        }

        if (!border) {
            return;
        }
        boolean leftJoined = (textureMask & FramedMapConnections.TEX_LEFT) != 0;
        boolean rightJoined = (textureMask & FramedMapConnections.TEX_RIGHT) != 0;
        boolean topJoined = (textureMask & FramedMapConnections.TEX_TOP) != 0;
        boolean bottomJoined = (textureMask & FramedMapConnections.TEX_BOTTOM) != 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean left = x < BORDER && !leftJoined;
                boolean right = x >= SIZE - BORDER && !rightJoined;
                boolean top = y < BORDER && !topJoined;
                boolean bottom = y >= SIZE - BORDER && !bottomJoined;
                if (left || right || top || bottom) {
                    boolean line = (left && x == 1) || (right && x == SIZE - 2)
                            || (top && y == 1) || (bottom && y == SIZE - 2);
                    image.setPixelABGR(x, y, abgr(line ? INK : PAPER));
                }
            }
        }
    }

    private static void paintOverlay(NativeImage image, FramedMapOverlay overlay) {
        int scaleFactor = 1 << overlay.scale;
        for (Region region : overlay.regions) {
            double[] pts = region.pts;
            for (int i = 0; i + 3 < pts.length; i += 2) {
                line(image, px(overlay, scaleFactor, pts[i]), pz(overlay, scaleFactor, pts[i + 1]),
                        px(overlay, scaleFactor, pts[i + 2]), pz(overlay, scaleFactor, pts[i + 3]), 1, region.color);
            }
            if (pts.length >= 4) {
                line(image, px(overlay, scaleFactor, pts[pts.length - 2]), pz(overlay, scaleFactor, pts[pts.length - 1]),
                        px(overlay, scaleFactor, pts[0]), pz(overlay, scaleFactor, pts[1]), 1, region.color);
            }
        }

        for (Stroke stroke : overlay.strokes) {
            int width = Math.max(1, Math.min(3, Math.round(stroke.width * 0.6f)));
            for (int i = 0; i + 3 < stroke.pts.length; i += 2) {
                line(image, px(overlay, scaleFactor, stroke.pts[i]), pz(overlay, scaleFactor, stroke.pts[i + 1]),
                        px(overlay, scaleFactor, stroke.pts[i + 2]), pz(overlay, scaleFactor, stroke.pts[i + 3]), width, stroke.color);
            }
        }

        for (Marker marker : overlay.markers) {
            drawMarker(image, px(overlay, scaleFactor, marker.x), pz(overlay, scaleFactor, marker.z), marker);
        }
    }

    private static int px(FramedMapOverlay overlay, int scaleFactor, double worldX) {
        return (int) Math.round(64.0 + (worldX - overlay.centerX) / scaleFactor);
    }

    private static int pz(FramedMapOverlay overlay, int scaleFactor, double worldZ) {
        return (int) Math.round(64.0 + (worldZ - overlay.centerZ) / scaleFactor);
    }

    private static void line(NativeImage image, int x0, int y0, int x1, int y1, int width, int argb) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int steps = Math.max(dx, dy);
        if (steps == 0) {
            dot(image, x0, y0, width, argb);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (x1 - x0) * i / steps;
            int y = y0 + (y1 - y0) * i / steps;
            dot(image, x, y, width, argb);
        }
    }

    private static void dot(NativeImage image, int cx, int cy, int width, int argb) {
        int half = width / 2;
        for (int y = cy - half; y <= cy + half; y++) {
            for (int x = cx - half; x <= cx + half; x++) {
                if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
                    image.setPixelABGR(x, y, abgr(argb));
                }
            }
        }
    }

    private static void drawMarker(NativeImage image, int cx, int cy, Marker marker) {
        String icon = marker.icon == null || marker.icon.isBlank() ? "pin" : marker.icon;
        NativeImage source = icon(icon, marker.color != 0);
        int tint = marker.color == 0 ? 0xFFFFFFFF : marker.color;

        int ox = cx - MARKER_SIZE / 2;
        int oy = cy - MARKER_SIZE / 2;
        if (source == null) {
            dot(image, cx, cy, 3, tint);
            return;
        }
        for (int y = 0; y < MARKER_SIZE; y++) {
            int sy = y * source.getHeight() / MARKER_SIZE;
            for (int x = 0; x < MARKER_SIZE; x++) {
                int sx = x * source.getWidth() / MARKER_SIZE;
                int pixel = source.getPixel(sx, sy);
                int a = (pixel >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                if (marker.color != 0) {
                    r = r * ((tint >> 16) & 0xFF) / 255;
                    g = g * ((tint >> 8) & 0xFF) / 255;
                    b = b * (tint & 0xFF) / 255;
                }
                int px = ox + x;
                int py = oy + y;
                if (px >= 0 && py >= 0 && px < SIZE && py < SIZE) {
                    image.setPixel(px, py, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
        }
    }

    private static NativeImage icon(String name, boolean tinted) {
        String key = (tinted ? "t:" : "p:") + name;
        NativeImage cached = ICONS.get(key);
        if (cached != null) {
            return cached;
        }
        String path = "/assets/atlasmapextended/textures/gui/markers"
                + (tinted ? "_tint" : "") + "/" + name + ".png";
        try (InputStream in = HeldMapTextures.class.getResourceAsStream(path)) {
            if (in == null) {
                ICONS.put(key, null);
                return null;
            }
            NativeImage image = NativeImage.read(in);
            ICONS.put(key, image);
            return image;
        } catch (Exception e) {
            ICONS.put(key, null);
            return null;
        }
    }

    private static int abgr(int argb) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Entry entry : ENTRIES.values()) {
            entry.texture.close();
            mc.getTextureManager().release(entry.id);
        }
        ENTRIES.clear();
        for (NativeImage image : ICONS.values()) {
            if (image != null) {
                image.close();
            }
        }
        ICONS.clear();
    }
}
