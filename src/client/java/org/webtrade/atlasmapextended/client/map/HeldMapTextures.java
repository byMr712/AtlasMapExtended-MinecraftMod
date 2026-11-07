package org.webtrade.atlasmapextended.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.webtrade.atlasmapextended.client.gui.Paper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Papyrus version of a held filled map: the same pixels as the vanilla map,
 * but inked, sepia-toned and framed with a paper margin.
 */
public final class HeldMapTextures {
    private static final int SIZE = 128;
    private static final int BORDER = 3;
    private static final int PAPER = 0xFFEAD8AE;
    private static final int PAPER_LIGHT = 0xFFF2E4C2;
    private static final int INK = 0xFF4A3B2A;

    private static final Map<Integer, Entry> ENTRIES = new HashMap<>();

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
        if (data.colors == null) {
            return null;
        }
        int id = mapId.id();
        long hash = Arrays.hashCode(data.colors);
        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            Identifier textureId = Paper.id("held_map/" + id);
            DynamicTexture texture = new DynamicTexture(
                    () -> "atlasmap-held-" + id, SIZE, SIZE, false);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            entry = new Entry(textureId, texture);
            ENTRIES.put(id, entry);
        }
        if (entry.hash != hash) {
            entry.hash = hash;
            paint(entry.texture.getPixels(), data.colors);
            entry.texture.upload();
        }
        return entry.id;
    }

    private static void paint(NativeImage image, byte[] colors) {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int argb;
                if (x < BORDER || y < BORDER || x >= SIZE - BORDER || y >= SIZE - BORDER) {
                    boolean line = x == 1 || y == 1 || x == SIZE - 2 || y == SIZE - 2;
                    argb = line ? INK : (x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1 ? INK : PAPER);
                } else {
                    int packed = colors[y * SIZE + x] & 0xFF;
                    if (packed == 0) {
                        argb = PAPER_LIGHT;
                    } else {
                        int rgb = MapColor.getColorFromPackedId(packed);
                        boolean water = rgb == MapColor.WATER.col;
                        argb = ChunkScanner.papyrus(rgb, 0, 0, 0, water ? 1 : 0, x, y);
                    }
                }
                image.setPixelABGR(x, y, abgr(argb));
            }
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
    }
}
