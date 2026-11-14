package org.webtrade.atlasmapextended.client.gui;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A small parchment plaque above the hotbar shown when the player enters or leaves a region. */
public final class RegionToast {
    private static final int TOTAL = 80;
    private static final int MAX_QUEUE = 4;
    /** Pending messages, so leaving one nested region and entering another are both shown. */
    private static final Deque<String[]> queue = new ArrayDeque<>();
    private static String title = "";
    private static String name = "";
    private static int ticks;

    private RegionToast() {
    }

    public static void show(String heading, String regionName) {
        if (heading == null || regionName == null) {
            return;
        }
        queue.removeIf(item -> regionName.equals(item[1]));
        if (ticks > 0 && heading.equals(title) && !regionName.equals(name)) {
            if (queue.size() < MAX_QUEUE) {
                queue.addLast(new String[]{heading, regionName});
            }
            return;
        }
        queue.clear();
        title = heading;
        name = regionName;
        ticks = TOTAL;
    }

    public static void tick() {
        if (ticks > 0) {
            ticks--;
            if (ticks == 0 && !queue.isEmpty()) {
                String[] next = queue.removeFirst();
                title = next[0];
                name = next[1];
                ticks = TOTAL;
            }
        }
    }

    public static void render(GuiGraphicsExtractor g, Font font) {
        if (ticks <= 0 || title.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int tw = Math.max(font.width(title), font.width(name));
        int bw = tw + 24;
        int bh = 26;
        int x = (w - bw) / 2;
        int y = h - 78;
        Paper.panel(g, x, y, bw, bh);
        g.text(font, title, x + (bw - font.width(title)) / 2, y + 5, Paper.INK_SOFT, false);
        g.text(font, name, x + (bw - font.width(name)) / 2, y + 14, Paper.INK, false);
    }
}
