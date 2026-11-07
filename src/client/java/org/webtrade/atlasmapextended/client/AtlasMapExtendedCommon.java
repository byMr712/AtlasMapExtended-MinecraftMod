package org.webtrade.atlasmapextended.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.gui.MapScreen;
import org.webtrade.atlasmapextended.client.gui.MinimapHud;
import org.webtrade.atlasmapextended.client.gui.Paper;
import org.webtrade.atlasmapextended.client.gui.RegionToast;
import org.webtrade.atlasmapextended.client.gui.SettingsScreen;
import org.webtrade.atlasmapextended.client.gui.T;
import org.webtrade.atlasmapextended.client.gui.Theme;
import org.webtrade.atlasmapextended.client.gui.TitleButton;
import org.webtrade.atlasmapextended.client.gui.WorldsScreen;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;
import org.webtrade.atlasmapextended.mixin.client.ScreenAccessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Loader-agnostic client logic. Both the Fabric entrypoint and the NeoForge entrypoint
 * call {@link #init()}; all game hooks are implemented as vanilla mixins, so no Fabric API
 * or NeoForge event bus is required here.
 */
public final class AtlasMapExtendedCommon {

    private static final MinimapHud MINIMAP = new MinimapHud();
    private static boolean themeLoaded;
    private static boolean deathMarkerHandled;

    private AtlasMapExtendedCommon() {
    }

    public static void init() {
        // Intentionally empty: the mixins do all hooking on both loaders.
    }

    public static void onTick(Minecraft mc) {
        ModConfig.tickFirstRunGuiScale();
        loadTheme();
        MapManager.tick(mc);
        tickDeathMarker(mc);
        RegionToast.tick();
        if (ModKeys.OPEN_MAP == null) {
            return;
        }
        ModConfig cfg = ModConfig.get();
        while (ModKeys.OPEN_MAP.consumeClick()) {
            if (mc.screen == null) {
                MapScreen.openLive(mc);
            }
        }
        while (ModKeys.MARKER_LIST.consumeClick()) {
            if (mc.screen == null) {
                MapScreen s = MapScreen.openLive(mc);
                if (s != null) {
                    s.openMarkerList();
                }
            }
        }
        while (ModKeys.NEW_MARKER.consumeClick()) {
            if (mc.screen == null) {
                MapScreen s = MapScreen.openLive(mc);
                if (s != null) {
                    s.newMarkerAtPlayer();
                }
            }
        }
        while (ModKeys.TOGGLE_MINIMAP.consumeClick()) {
            cfg.minimapEnabled = !cfg.minimapEnabled;
            cfg.save();
        }
        while (ModKeys.MINIMAP_ZOOM_IN.consumeClick()) {
            cfg.minimapZoomIndex = Math.min(ModConfig.MINIMAP_ZOOMS.length - 1, cfg.minimapZoomIndex + 1);
            cfg.save();
        }
        while (ModKeys.MINIMAP_ZOOM_OUT.consumeClick()) {
            cfg.minimapZoomIndex = Math.max(0, cfg.minimapZoomIndex - 1);
            cfg.save();
        }
        while (ModKeys.MINIMAP_ZOOM_TOGGLE.consumeClick()) {
            cfg.minimapZoomIndex = (cfg.minimapZoomIndex + 1) % ModConfig.MINIMAP_ZOOMS.length;
            cfg.save();
        }
        // in-map shortcuts are handled by the map screen itself
        ModKeys.RULER.consumeClick();
        ModKeys.EDITOR.consumeClick();
        ModKeys.TOGGLE_DRAWINGS.consumeClick();
    }

    /** Creates a red skull marker on the player's death, honouring the death-marker limit. */
    private static void tickDeathMarker(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isDeadOrDying()) {
            deathMarkerHandled = false;
            return;
        }
        if (deathMarkerHandled) {
            return;
        }
        deathMarkerHandled = true;
        ModConfig cfg = ModConfig.get();
        int limit = cfg.deathMarkers;
        if (limit == 0) {
            return;
        }
        int max = switch (limit) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 10;
            default -> Integer.MAX_VALUE;
        };
        if (cfg.deathMarkersCreated >= max) {
            return;
        }
        MapSession session = MapManager.live();
        if (session == null) {
            return;
        }
        Marker m = new Marker();
        m.icon = "skull";
        m.death = true;
        m.color = 0xFFC62828;
        m.name = T.s("marker.death");
        m.description = T.s("marker.death_desc", p.getBlockX(), p.getBlockY(), p.getBlockZ());
        m.dim = mc.level.dimension().identifier().toString();
        m.x = p.getBlockX();
        m.y = p.getBlockY();
        m.z = p.getBlockZ();
        m.created = System.currentTimeMillis();
        session.markers.add(m);
        session.saveMarkers();
        cfg.deathMarkersCreated++;
        cfg.save();
    }
    /** Applies the saved UI design once the game directory (and so the config) is available. */
    public static void loadTheme() {
        if (!themeLoaded) {
            themeLoaded = true;
            Paper.apply(Theme.byId(ModConfig.get().theme));
        }
    }

    public static void renderMinimap(GuiGraphics g, DeltaTracker delta) {
        MINIMAP.render(g, delta);
        RegionToast.render(g, Minecraft.getInstance().font);
    }

    public static void addAtlasButton(Screen screen) {
        loadTheme();
        List<AbstractWidget> widgets = new ArrayList<>();
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof AbstractWidget widget) {
                widgets.add(widget);
            }
        }
        AbstractWidget anchor = null;
        for (AbstractWidget w : widgets) {
            if (w.getWidth() >= 150 && (anchor == null || w.getY() < anchor.getY())) {
                anchor = w;
            }
        }
        int x = anchor != null ? anchor.getX() + anchor.getWidth() + 4 : screen.width / 2 + 104;
        int y = anchor != null ? anchor.getY() : 100;
        int step = 24;
        if (overlaps(widgets, x, y) || overlaps(widgets, x + step, y)) {
            x = anchor != null ? anchor.getX() - 24 : screen.width / 2 - 124;
            step = -24;
        }
        Minecraft mc = Minecraft.getInstance();
        ScreenAccessor accessor = (ScreenAccessor) screen;
        accessor.atlasmapextended$addRenderableWidget(new TitleButton(x, y, "map", T.tr("atlas.button"),
                () -> mc.setScreen(new WorldsScreen(screen))));
        accessor.atlasmapextended$addRenderableWidget(new TitleButton(x + step, y, "gear", T.tr("tool.settings"),
                () -> mc.setScreen(new SettingsScreen(screen))));
    }

    private static boolean overlaps(List<AbstractWidget> widgets, int x, int y) {
        for (AbstractWidget w : widgets) {
            if (x < w.getX() + w.getWidth() && x + 20 > w.getX() && y < w.getY() + w.getHeight() && y + 20 > w.getY()) {
                return true;
            }
        }
        return false;
    }
}
