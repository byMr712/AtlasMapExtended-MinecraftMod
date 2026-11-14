package org.webtrade.atlasmapextended.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;
import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.client.data.WorldMeta;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** "Atlas" opened from the main menu: every stored server and singleplayer world, listed separately. */
public class WorldsScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private List<WorldMeta> servers = new ArrayList<>();
    private List<WorldMeta> locals = new ArrayList<>();
    private final Map<String, Integer> markerCounts = new HashMap<>();
    private double scrollServers;
    private double scrollLocals;
    private int[] serverArea = new int[4];
    private int[] localArea = new int[4];
    private WorldMeta toDelete;
    private int modalStart = -1;

    public WorldsScreen(Screen parent) {
        super(T.tr("atlas.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        reload();
    }

    private void reload() {
        servers = new ArrayList<>();
        locals = new ArrayList<>();
        markerCounts.clear();
        for (WorldMeta m : Storage.listWorlds()) {
            (m.isServer() ? servers : locals).add(m);
            markerCounts.put(m.id, Storage.countMarkers(m.id));
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        modalStart = -1;
        Paper.tiled(g, Paper.PARCHMENT, 0, 0, width, height);
        for (int i = 0; i < 12; i++) {
            int a = (12 - i) * 7 << 24 | 0x2A1A0C;
            g.fill(0, i * 2, width, i * 2 + 2, a);
            g.fill(0, height - i * 2 - 2, width, height - i * 2, a);
            g.fill(i * 2, 0, i * 2 + 2, height, a);
            g.fill(width - i * 2 - 2, 0, width - i * 2, height, a);
        }
        Paper.ensure(Paper.COMPASS);
        g.blit(Paper.COMPASS, width - 70, height - 70, 56, 56, 0, 0, 64, 64, 64, 64);
        Paper.icon(g, Paper.tool("map"), width / 2 - font.width(title) / 2 - 24, 10, 16);
        Paper.title(g, font, title.getString(), width / 2, 14);
        List<FormattedCharSequence> sub = font.split(T.tr("atlas.subtitle"), width - 40);
        int sy = 28;
        for (FormattedCharSequence l : sub) {
            g.drawString(font, l, (width - font.width(l)) / 2, sy, Paper.INK_SOFT, false);
            sy += 10;
        }

        int top = sy + 8;
        int bottom = height - 34;
        boolean twoCols = width >= 420;
        if (twoCols) {
            int colW = Math.min(300, (width - 40) / 2);
            int gap = 12;
            int x1 = width / 2 - colW - gap / 2;
            int x2 = width / 2 + gap / 2;
            serverArea = drawColumn(g, mx, my, x1, top, colW, bottom - top, T.s("atlas.servers"), "globe", servers, true);
            localArea = drawColumn(g, mx, my, x2, top, colW, bottom - top, T.s("atlas.singleplayer"), "marker:house", locals, false);
        } else {
            int colW = width - 30;
            int half = (bottom - top - 8) / 2;
            serverArea = drawColumn(g, mx, my, 15, top, colW, half, T.s("atlas.servers"), "globe", servers, true);
            localArea = drawColumn(g, mx, my, 15, top + half + 8, colW, half, T.s("atlas.singleplayer"), "marker:house", locals, false);
        }

        int bw = 120;
        boolean hb = toDelete == null && Paper.inside(mx, my, (width - bw) / 2, height - 26, bw, 18);
        Paper.iconTextButton(g, font, Paper.tool("left"), T.tr("button.back"), (width - bw) / 2, height - 26, bw, 18, hb, true);
        hits.add(new Hit((width - bw) / 2, height - 26, bw, 18, this::onClose));

        if (toDelete != null) {
            modalStart = hits.size();
            hits.add(new Hit(0, 0, width, height, () -> toDelete = null));
            g.fill(0, 0, width, height, Paper.DIM);
            int ow = Math.min(260, width - 20);
            List<FormattedCharSequence> lines = font.split(T.tr("atlas.confirm_delete", toDelete.name), ow - 24);
            int oh = lines.size() * 10 + 50;
            int ox = (width - ow) / 2;
            int oy = (height - oh) / 2;
            Paper.panel(g, ox, oy, ow, oh);
            hits.add(new Hit(ox, oy, ow, oh, () -> {
            }));
            int y = oy + 12;
            for (FormattedCharSequence l : lines) {
                g.drawString(font, l, ox + (ow - font.width(l)) / 2, y, Paper.INK, false);
                y += 10;
            }
            int b = 80;
            int by = oy + oh - 24;
            boolean h1 = Paper.inside(mx, my, ox + ow / 2 - b - 4, by, b, 16);
            Paper.iconTextButton(g, font, Paper.tool("trash"), T.tr("button.delete"), ox + ow / 2 - b - 4, by, b, 16, h1, true);
            hits.add(new Hit(ox + ow / 2 - b - 4, by, b, 16, () -> {
                WorldMeta m = toDelete;
                toDelete = null;
                if (MapManager.live() == null || !MapManager.live().meta.id.equals(m.id)) {
                    Storage.IO.execute(() -> Storage.deleteRecursive(Storage.worldDir(m.id)));
                    servers.remove(m);
                    locals.remove(m);
                }
            }));
            boolean h2 = Paper.inside(mx, my, ox + ow / 2 + 4, by, b, 16);
            Paper.iconTextButton(g, font, Paper.tool("close"), T.tr("button.cancel"), ox + ow / 2 + 4, by, b, 16, h2, true);
            hits.add(new Hit(ox + ow / 2 + 4, by, b, 16, () -> toDelete = null));
        }
    }

    private int[] drawColumn(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String title, String icon,
                             List<WorldMeta> list, boolean isServers) {
        Paper.panel(g, x, y, w, h);
        Paper.icon(g, icon.startsWith("marker:") ? Paper.marker(icon.substring(7)) : Paper.tool(icon), x + 8, y + 5, 12);
        g.drawString(font, title + "  (" + list.size() + ")", x + 24, y + 8, Paper.INK, false);
        Paper.separator(g, x + 8, y + 21, w - 16);
        int top = y + 26;
        int bottom = y + h - 6;
        int cardH = 38;
        double scroll = isServers ? scrollServers : scrollLocals;
        double max = Math.max(0, list.size() * (cardH + 4) - (bottom - top));
        scroll = Math.max(0, Math.min(max, scroll));
        if (isServers) {
            scrollServers = scroll;
        } else {
            scrollLocals = scroll;
        }
        if (list.isEmpty()) {
            for (FormattedCharSequence l : font.split(T.tr(isServers ? "atlas.no_servers" : "atlas.no_worlds"), w - 24)) {
                g.drawString(font, l, x + 12, top + 6, Paper.DISABLED, false);
                top += 10;
            }
            return new int[]{x, y, w, h};
        }
        SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        g.enableScissor(x + 3, top, x + w - 3, bottom);
        int cy = top - (int) scroll;
        for (WorldMeta m : list) {
            if (cy + cardH >= top && cy <= bottom) {
                int cx = x + 8;
                int cw = w - 16;
                boolean hover = toDelete == null && Paper.inside(mx, my, cx, cy, cw, cardH) && my >= top && my < bottom;
                g.fill(cx, cy, cx + cw, cy + cardH, hover ? 0x40FFFFFF : 0x18FFFFFF);
                Paper.outline(g, cx, cy, cw, cardH, hover ? Paper.GOLD : Paper.INK_FAINT);
                Paper.inset(g, cx + 4, cy + 4, 30, 30);
                Paper.icon(g, Paper.tool(isServers ? "globe" : "map"), cx + 7, cy + 7, 24);
                String name = font.plainSubstrByWidth(m.name == null || m.name.isEmpty() ? m.id : m.name, cw - 64);
                g.drawString(font, name, cx + 40, cy + 5, Paper.INK, false);
                String addr = font.plainSubstrByWidth(m.address == null ? "" : m.address, cw - 64);
                g.drawString(font, addr, cx + 40, cy + 15, Paper.INK_SOFT, false);
                String info = T.s("atlas.info", markerCounts.getOrDefault(m.id, 0), df.format(new Date(m.lastPlayed)));
                g.drawString(font, font.plainSubstrByWidth(info, cw - 64), cx + 40, cy + 26, Paper.DISABLED, false);
                int tx = cx + cw - 20;
                int ty = cy + (cardH - 16) / 2;
                boolean th = hover && Paper.inside(mx, my, tx, ty, 16, 16);
                Paper.iconButton(g, Paper.tool("trash"), tx, ty, 16, th, false);
                int clipY = Math.max(cy, top);
                int clipH = Math.min(cy + cardH, bottom) - clipY;
                if (clipH > 0) {
                    hits.add(new Hit(cx, clipY, cw, clipH, () -> openWorld(m)));
                    if (ty >= top && ty + 16 <= bottom) {
                        hits.add(new Hit(tx, ty, 16, 16, () -> toDelete = m));
                    }
                }
            }
            cy += cardH + 4;
        }
        g.disableScissor();
        return new int[]{x, top, w, bottom - top};
    }

    private void openWorld(WorldMeta m) {
        MapSession s = MapManager.openForViewing(m);
        boolean owns = s != MapManager.live();
        Paper.pageTurn();
        minecraft.setScreen(new MapScreen(this, s, owns));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int from = modalStart >= 0 ? modalStart : 0;
        for (int i = hits.size() - 1; i >= from; i--) {
            Hit h = hits.get(i);
            if (Paper.inside(mouseX, mouseY, h.x, h.y, h.w, h.h)) {
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    h.action.run();
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (Paper.inside(mx, my, serverArea[0], serverArea[1], serverArea[2], serverArea[3])) {
            scrollServers -= sy * 20;
        } else if (Paper.inside(mx, my, localArea[0], localArea[1], localArea[2], localArea[3])) {
            scrollLocals -= sy * 20;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (toDelete != null && keyCode == InputConstants.KEY_ESCAPE) {
            toDelete = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
