package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.webtrade.atlasmapextended.client.AtlasMapExtendedCommon;

/** Draws the minimap above the scoreboard, and behind any open screen. */
public final class MinimapFrame {
    private MinimapFrame() {
    }

    /** HUD pass: below open screens. */
    public static void draw(GuiGraphics g, DeltaTracker delta) {
        if (g == null || delta == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) {
            return;
        }
        g.nextStratum();
        AtlasMapExtendedCommon.renderMinimap(g, delta);
        g.nextStratum();
    }

    /** Late pass: only while no screen is open, so it can sit above the scoreboard. */
    public static void drawAboveHud(GuiGraphics g, DeltaTracker delta) {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        draw(g, delta);
    }
}