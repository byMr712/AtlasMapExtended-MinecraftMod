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
    /** "3D framed maps": extruded terrain for maps in item frames. Enabled by default. */
    public boolean framedMaps3D = true;
    /** Main world height for the framed relief; 20 blocks below it still render as the map base. */
    public int framedMapsStartY = 60;
    /** 0 = off, 1 = 1, 2 = 5, 3 = 10, 4 = unlimited. */
    public int deathMarkers = 2;
    /** New death markers created since the limit was last applied. */
    public int deathMarkersCreated = 0;
    /** Death-marker generation; increasing the limit re-tags the active markers. */
    public int deathEpoch = 0;
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
    public java.util.Map<String, String> serverMapOverrides = new java.util.HashMap<>();

    private static ModConfig instance;
    private static int firstRunSizeDelay = -1;

    public static ModConfig get() {
        if (instance == null) {
            ModConfig fallback = new ModConfig();
            fallback.minimapSizesVersion = 1;
            boolean existed = java.nio.file.Files.isRegularFile(file());
            instance = Storage.readJson(file(), ModConfig.class, fallback);
            firstRunSizeDelay = existed ? -1 : 60;
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

    /** Resolves the first-run minimap size after the window had time to settle. */
    public static void tickFirstRunGuiScale() {
        ModConfig cfg = get();
        if (firstRunSizeDelay < 0) {
            return;
        }
        if (firstRunSizeDelay > 0) {
            firstRunSizeDelay--;
            return;
        }
        firstRunSizeDelay = -1;
        int scale = -1;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                scale = mc.getWindow().calculateScale(
                        mc.options.guiScale().get(),
                        mc.options.forceUnicodeFont().get());
            }
        } catch (Throwable ignored) {
        }
        cfg.minimapSizeIndex = scale == 1 ? 8 : scale == 2 ? 4 : scale == 3 ? 3 : 2;
        cfg.save();
    }
    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("atlasmapextended.json");
    }

    private void clamp() {
        minimapSizeIndex = Math.floorMod(minimapSizeIndex, MINIMAP_SIZES.length);
        minimapZoomIndex = Math.max(0, Math.min(MINIMAP_ZOOMS.length - 1, minimapZoomIndex));
        framedMapsStartY = Math.max(-64, Math.min(320, framedMapsStartY));
        deathMarkers = Math.max(0, Math.min(4, deathMarkers));
        deathMarkersCreated = Math.max(0, deathMarkersCreated);
        minimapCorner = Math.floorMod(minimapCorner, 4);
        if (serverMapOverrides == null) {
            serverMapOverrides = new java.util.HashMap<>();
        }
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
