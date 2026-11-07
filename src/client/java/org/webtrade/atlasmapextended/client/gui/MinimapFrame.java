package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.webtrade.atlasmapextended.client.AtlasMapExtendedCommon;

/** Draws the minimap as the last HUD layer, so it covers the scoreboard and stays under screens. */
public final class MinimapFrame {
    private static boolean drawnThisFrame;

    private MinimapFrame() {
    }

    public static void beginFrame() {
        drawnThisFrame = false;
    }

    public static void draw(GuiGraphics g, DeltaTracker delta) {
        if (drawnThisFrame) {
            return;
        }
        drawnThisFrame = true;
        if (g == null || delta == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.level == null || mc.options.hideGui) {
            return;
        }
        g.flush();
        AtlasMapExtendedCommon.renderMinimap(g, delta);
        g.flush();
    }
}
