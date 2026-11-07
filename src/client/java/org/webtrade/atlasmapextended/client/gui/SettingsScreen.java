package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;

import java.util.ArrayList;
import java.util.List;

/** Mod settings: the UI design (8 themes), minimap and marker options. Opened by the gear buttons. */
public class SettingsScreen extends Screen {
    private static final int TILE_H = 34;
    private static final int ROW = 19;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final Screen parent;
    private final ModConfig cfg = ModConfig.get();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> popupHits = new ArrayList<>();
    private boolean themePopup;
    private int pendingDeathMarkers;
    private Component tooltip;
    private double scroll;
    private int maxScroll;

    public SettingsScreen(Screen parent) {
        super(T.tr("settings.title"));
        this.parent = parent;
        this.pendingDeathMarkers = cfg.deathMarkers;
    }

    @Override
    public void onClose() {
        done();
    }

    @Override
    public boolean isPauseScreen() {
        return parent == null;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        // Opened from the world map: keep the world (and minimap HUD) visible behind the settings.
        if (parent instanceof MapScreen) {
            return;
        }
        if (parent != null) {
            parent.extractBackground(g, -1, -1, pt);
            parent.extractRenderState(g, -1, -1, pt);
        } else {
            super.extractBackground(g, mx, my, pt);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        hits.clear();
        tooltip = null;
        g.nextStratum();
        g.fill(0, 0, width, height, Paper.DIM);

        int pw = Math.min(width - 16, 430);
        int px = (width - pw) / 2;
        boolean twoCols = pw >= 380;
        int colW = twoCols ? (pw - 30) / 2 : pw - 20;
        int designH = 12 + 16 + 4 + 16 + 6
                + font.split(T.tr("settings.death_markers_note"), colW).size() * 10 + 4;
        int optionsH = 12 + 4 * ROW + 4 + 12 + 3 * ROW + 24;
        int contentH = twoCols ? Math.max(designH, optionsH) : designH + 8 + optionsH;
        int ph = Math.min(height - 16, contentH + 54);
        int py = (height - ph) / 2;
        Paper.panel(g, px, py, pw, ph);
        Paper.title(g, font, T.s("settings.title"), px + pw / 2, py + 8);
        boolean hc = Paper.inside(mx, my, px + pw - 18, py + 5, 13, 13);
        Paper.iconButton(g, Paper.tool("close"), px + pw - 18, py + 5, 13, hc, false);
        hit(px + pw - 18, py + 5, 13, 13, this::onClose);

        int top = py + 22;
        int bottom = py + ph - 28;
        maxScroll = Math.max(0, contentH - (bottom - top));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        g.enableScissor(px + 2, top, px + pw - 2, bottom);
        int y = top - (int) scroll;
        int lx = px + 10;
        drawDesign(g, mx, my, lx, y, colW, top, bottom);
        if (twoCols) {
            drawOptions(g, mx, my, lx + colW + 10, y, colW, top, bottom);
        } else {
            drawOptions(g, mx, my, lx, y + designH + 8, colW, top, bottom);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int trackH = bottom - top;
            int thumbH = Math.max(12, trackH * trackH / contentH);
            int thumbY = top + (int) ((trackH - thumbH) * (scroll / maxScroll));
            g.fill(px + pw - 5, top, px + pw - 4, bottom, Paper.INK_FAINT);
            g.fill(px + pw - 6, thumbY, px + pw - 3, thumbY + thumbH, Paper.INK_SOFT);
        }

        int bw = 100;
        int bx = px + (pw - bw) / 2;
        int by = py + ph - 22;
        boolean hd = Paper.inside(mx, my, bx, by, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("check"), T.tr("button.done"), bx, by, bw, 16, hd, true);
        hit(bx, by, bw, 16, this::done);

        if (tooltip != null) {
            g.setTooltipForNextFrame(font, tooltip, mx, my);
        }
        if (themePopup) {
            drawThemePopup(g, mx, my);
        }
    }

    private void drawDesign(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int top, int bottom) {
        section(g, T.s("settings.design"), x, y, w);
        y += 12;
        y = option(g, mx, my, x, y, w, top, bottom, T.tr("settings.choose_design"),
                () -> themePopup = true);
        int deathY = y;
        y = option(g, mx, my, x, y, w, top, bottom,
                T.tr("settings.death_markers", deathLabel(pendingDeathMarkers)),
                () -> pendingDeathMarkers = (pendingDeathMarkers + 1) % 5);
        if (Paper.inside(mx, my, x, deathY, w, 16) && my >= top && my < bottom) {
            tooltip = T.tr("settings.death_markers_tip");
        }
        for (FormattedCharSequence l : font.split(T.tr("settings.death_markers_note"), w)) {
            g.text(font, l, x, y + 1, Paper.HINT, false);
            y += 10;
        }
    }
    private void drawThemePopup(GuiGraphicsExtractor g, int mx, int my) {
        g.nextStratum();
        g.fill(0, 0, width, height, Paper.DIM);
        int pw = Math.min(width - 16, 430);
        int rows = (Theme.ALL.size() + 1) / 2;
        int ph = Math.min(height - 16, 12 + rows * (TILE_H + 4) + 40);
        int px = (width - pw) / 2;
        int py = (height - ph) / 2;
        Paper.panel(g, px, py, pw, ph);
        Paper.title(g, font, T.s("settings.choose_design"), px + pw / 2, py + 8);
        boolean hc = Paper.inside(mx, my, px + pw - 18, py + 5, 13, 13);
        Paper.iconButton(g, Paper.tool("close"), px + pw - 18, py + 5, 13, hc, false);
        popupHit(px + pw - 18, py + 5, 13, 13, () -> themePopup = false);

        int x = px + 10;
        int w = pw - 20;
        int tw = (w - 4) / 2;
        int top = py + 24;
        for (int i = 0; i < Theme.ALL.size(); i++) {
            Theme t = Theme.ALL.get(i);
            int tx = x + (i % 2) * (tw + 4);
            int ty = top + (i / 2) * (TILE_H + 4);
            boolean cur = t == Paper.theme();
            boolean hover = Paper.inside(mx, my, tx, ty, tw, TILE_H);
            g.fill(tx + 1, ty + 1, tx + tw + 1, ty + TILE_H + 1, Paper.SHADOW);
            Paper.tiled(g, t.panel(), tx, ty, tw, TILE_H, tx * 3, ty * 7);
            Paper.tiled(g, t.panelDark(), tx + 4, ty + TILE_H - 14, 26, 10, tx, ty);
            g.outline(tx + 4, ty + TILE_H - 14, 26, 10, t.ink());
            g.fill(tx + 34, ty + TILE_H - 10, tx + tw - 6, ty + TILE_H - 8, t.accent());
            String name = font.plainSubstrByWidth(T.s("theme." + t.id()), tw - 10);
            g.text(font, name, tx + 5, ty + 5, t.ink(), false);
            if (cur) {
                g.outline(tx - 1, ty - 1, tw + 2, TILE_H + 2, Paper.GOLD);
                g.outline(tx, ty, tw, TILE_H, Paper.GOLD_LIGHT);
                Paper.icon(g, Paper.tool("check"), tx + tw - 15, ty + 3, 12);
            } else {
                g.outline(tx, ty, tw, TILE_H, hover ? Paper.GOLD : Paper.a(Paper.INK, 0x80));
            }
            popupHit(tx, ty, tw, TILE_H, () -> {
                cfg.theme = t.id();
                Paper.apply(t);
                cfg.save();
                themePopup = false;
            });
        }
    }
    private void done() {
        if (pendingDeathMarkers != cfg.deathMarkers) {
            int oldMax = deathMarkerMax(cfg.deathMarkers);
            int newMax = deathMarkerMax(pendingDeathMarkers);
            int existing = 0;
            MapSession session = MapManager.live();
            if (session != null) {
                for (Marker m : session.markers) {
                    if (m.death) {
                        existing++;
                    }
                }
            }
            if (pendingDeathMarkers == 4) {
                cfg.deathMarkersCreated = 0;
            } else if (newMax > oldMax) {
                cfg.deathMarkersCreated = Math.min(existing, oldMax);
            } else {
                cfg.deathMarkersCreated = 0;
            }
            cfg.deathMarkers = pendingDeathMarkers;
        }
        cfg.save();
        minecraft.setScreen(parent);
    }

    private static int deathMarkerMax(int limit) {
        return switch (limit) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 10;
            case 4 -> Integer.MAX_VALUE;
            default -> 0;
        };
    }

    private Component deathLabel(int value) {
        return T.tr(switch (value) {
            case 0 -> "death.off";
            case 1 -> "death.one";
            case 2 -> "death.five";
            case 3 -> "death.ten";
            default -> "death.infinite";
        });
    }

    private void popupHit(int x, int y, int w, int h, Runnable action) {
        popupHits.add(new Hit(x, y, w, h, action));
    }
    private void drawThemes(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int top, int bottom) {
        section(g, T.s("settings.design"), x, y, w);
        y += 12;
        int tw = (w - 4) / 2;
        for (int i = 0; i < Theme.ALL.size(); i++) {
            Theme t = Theme.ALL.get(i);
            int tx = x + (i % 2) * (tw + 4);
            int ty = y + (i / 2) * (TILE_H + 4);
            boolean cur = t == Paper.theme();
            boolean hover = Paper.inside(mx, my, tx, ty, tw, TILE_H) && my >= top && my < bottom;
            g.fill(tx + 1, ty + 1, tx + tw + 1, ty + TILE_H + 1, Paper.SHADOW);
            Paper.tiled(g, t.panel(), tx, ty, tw, TILE_H, tx * 3, ty * 7);
            Paper.tiled(g, t.panelDark(), tx + 4, ty + TILE_H - 14, 26, 10, tx, ty);
            g.outline(tx + 4, ty + TILE_H - 14, 26, 10, t.ink());
            g.fill(tx + 34, ty + TILE_H - 10, tx + tw - 6, ty + TILE_H - 8, t.accent());
            String name = font.plainSubstrByWidth(T.s("theme." + t.id()), tw - 10);
            g.text(font, name, tx + 5, ty + 5, t.ink(), false);
            if (cur) {
                g.outline(tx - 1, ty - 1, tw + 2, TILE_H + 2, Paper.GOLD);
                g.outline(tx, ty, tw, TILE_H, Paper.GOLD_LIGHT);
                Paper.icon(g, Paper.tool("check"), tx + tw - 15, ty + 3, 12);
            } else {
                g.outline(tx, ty, tw, TILE_H, hover ? Paper.GOLD : Paper.a(Paper.INK, 0x80));
            }
            hit(tx, ty, tw, TILE_H, () -> {
                cfg.theme = t.id();
                Paper.apply(t);
                cfg.save();
            });
        }
    }

    private void drawOptions(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int top, int bottom) {
        String[] corners = {"corner.tr", "corner.tl", "corner.br", "corner.bl"};
        section(g, T.s("settings.minimap_section"), x, y, w);
        y += 12;
        y = option(g, mx, my, x, y, w, top, bottom, T.tr("settings.minimap", onOff(cfg.minimapEnabled)), () -> cfg.minimapEnabled = !cfg.minimapEnabled);
        y = optionPair(g, mx, my, x, y, w, top, bottom,
                T.tr("settings.size", cfg.minimapSize()), () -> cfg.minimapSizeIndex = (cfg.minimapSizeIndex + 1) % ModConfig.MINIMAP_SIZES.length,
                T.tr("settings.zoom", fmtZoom(cfg.minimapZoom())), () -> cfg.minimapZoomIndex = (cfg.minimapZoomIndex + 1) % ModConfig.MINIMAP_ZOOMS.length);
        y = option(g, mx, my, x, y, w, top, bottom, T.tr("settings.corner", T.tr(corners[cfg.minimapCorner])), () -> cfg.minimapCorner = (cfg.minimapCorner + 1) % 4);
        y = optionPair(g, mx, my, x, y, w, top, bottom,
                T.tr("settings.coords", onOff(cfg.minimapCoords)), () -> cfg.minimapCoords = !cfg.minimapCoords,
                T.tr("settings.mm_markers", onOff(cfg.minimapMarkers)), () -> cfg.minimapMarkers = !cfg.minimapMarkers);
        y += 4;
        section(g, T.s("settings.markers_section"), x, y, w);
        y += 12;
        int ty = y;
        y = option(g, mx, my, x, y, w, top, bottom, T.tr("settings.teleport", onOff(cfg.teleport)), () -> cfg.teleport = !cfg.teleport);
        if (Paper.inside(mx, my, x, ty, w, 16)) {
            tooltip = T.tr("settings.teleport_tip");
        }
        y = option(g, mx, my, x, y, w, top, bottom, T.tr("settings.labels", onOff(cfg.markerLabels)), () -> cfg.markerLabels = !cfg.markerLabels);
        y = option(g, mx, my, x, y, w, top, bottom, T.tr("settings.world_markers", onOff(cfg.worldMarkers)), () -> cfg.worldMarkers = !cfg.worldMarkers);
        for (FormattedCharSequence l : font.split(T.tr("settings.teleport_note"), w)) {
            g.text(font, l, x, y + 1, Paper.HINT, false);
            y += 10;
        }
    }

    private int optionPair(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int top, int bottom,
                           Component left, Runnable leftAction, Component right, Runnable rightAction) {
        int half = (w - 4) / 2;
        drawOption(g, mx, my, x, y, half, top, bottom, left, leftAction);
        drawOption(g, mx, my, x + half + 4, y, w - half - 4, top, bottom, right, rightAction);
        return y + ROW;
    }

    private int option(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int top, int bottom, Component label, Runnable action) {
        drawOption(g, mx, my, x, y, w, top, bottom, label, action);
        return y + ROW;
    }

    private void drawOption(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int top, int bottom,
                            Component label, Runnable action) {
        boolean h = Paper.inside(mx, my, x, y, w, 16) && my >= top && my < bottom;
        Paper.button(g, font, label, x, y, w, 16, h, true);
        if (y + 16 > top && y < bottom) {
            hit(x, Math.max(y, top), w, Math.min(y + 16, bottom) - Math.max(y, top), () -> {
                action.run();
                cfg.save();
            });
        }
    }

    private void section(GuiGraphicsExtractor g, String text, int x, int y, int w) {
        g.text(font, text, x, y, Paper.INK_SOFT, false);
        int tw = font.width(text);
        g.fill(x + tw + 5, y + 4, x + w, y + 5, Paper.INK_FAINT);
    }

    private void hit(int x, int y, int w, int h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (themePopup) {
            for (int i = popupHits.size() - 1; i >= 0; i--) {
                Hit h = popupHits.get(i);
                if (Paper.inside(e.x(), e.y(), h.x, h.y, h.w, h.h)) {
                    h.action.run();
                    Paper.click();
                    return true;
                }
            }
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Paper.inside(e.x(), e.y(), h.x, h.y, h.w, h.h)) {
                h.action.run();
                Paper.click();
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (themePopup) {
            return true;
        }
        scroll -= sy * ROW;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.isEscape()) {
            if (themePopup) {
                themePopup = false;
            } else {
                onClose();
            }
            return true;
        }
        return super.keyPressed(e);
    }

    static Component onOff(boolean b) {
        return T.tr(b ? "on" : "off");
    }

    static String fmtZoom(float z) {
        return z == Math.round(z) ? "x" + (int) z : "x" + z;
    }
}
