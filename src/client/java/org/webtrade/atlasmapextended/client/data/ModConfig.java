package org.webtrade.atlasmapextended.client.data;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** Client settings, stored in {@code config/atlasmapextended.json}. */
public class ModConfig {
    public static final float[] MINIMAP_ZOOMS = {0.5f, 1f, 1.5f, 2f, 3f, 4f};
    public static final int[] MINIMAP_SIZES = {20, 40, 60, 80, 110, 140, 170, 200, 230, 260, 290};

    public boolean minimapEnabled = true;
    public int minimapSizeIndex = 2;
    /** Bumped when the size list changes, so old indices can be remapped once. */
    public int minimapSizesVersion = 0;
    public int minimapZoomIndex = 0;
    /** 0 = top right, 1 = top left, 2 = bottom right, 3 = bottom left. */
    public int minimapCorner = 0;
    public boolean minimapCoords = true;
    public boolean minimapMarkers = true;
    public boolean drawingsVisible = true;
    public boolean markersVisible = true;
    public boolean worldMarkers = false;
    public int markerSort = 0;
    public int regionSort = 0;
    public String theme = "papyrus";
    /** Shows a "Teleport" button on markers (needs command permission). */
    public boolean teleport = false;
    public boolean markerLabels = true;

    private static ModConfig instance;

    public static ModConfig get() {
        if (instance == null) {
            ModConfig fallback = new ModConfig();
            fallback.minimapSizesVersion = 1;
            fallback.minimapSizeIndex = defaultSizeIndexForGuiScale();
            instance = Storage.readJson(file(), ModConfig.class, fallback);
            if (instance.minimapSizesVersion == 0) {
                // Old config: the first four slots used to be 80/110/140/170.
                instance.minimapSizeIndex = Math.min(MINIMAP_SIZES.length - 1, instance.minimapSizeIndex + 3);
                instance.minimapSizesVersion = 1;
                instance.save();
            }
            instance.clamp();
        }
        return instance;
    }

    /** First-run default: 230 px at GUI scale 1, 110 px at scale 2, 60 px at scale 3. */
    private static int defaultSizeIndexForGuiScale() {
        int scale = 3;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                scale = mc.options.guiScale().get();
            }
        } catch (Throwable ignored) {
        }
        return scale == 1 ? 8 : scale == 2 ? 4 : 2;
    }
    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("atlasmapextended.json");
    }

    private void clamp() {
        minimapSizeIndex = Math.floorMod(minimapSizeIndex, MINIMAP_SIZES.length);
        minimapZoomIndex = Math.max(0, Math.min(MINIMAP_ZOOMS.length - 1, minimapZoomIndex));
        minimapCorner = Math.floorMod(minimapCorner, 4);
    }

    public void save() {
        clamp();
        Storage.writeJson(file(), this);
    }

    public int minimapSize() {
        return MINIMAP_SIZES[minimapSizeIndex];
    }

    public float minimapZoom() {
        return MINIMAP_ZOOMS[minimapZoomIndex];
    }
}
