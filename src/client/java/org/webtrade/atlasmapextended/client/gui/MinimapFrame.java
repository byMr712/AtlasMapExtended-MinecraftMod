package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.webtrade.atlasmapextended.client.AtlasMapExtendedCommon;

/** Draws the minimap right after the HUD, so it covers the scoreboard and stays under screens. */
public final class MinimapFrame {
    private static GuiGraphics graphics;
    private static DeltaTracker delta;

    private MinimapFrame() {
    }

    public static void remember(GuiGraphics g, DeltaTracker d) {
        graphics = g;
        delta = d;
    }

    public static void drawLate() {
        if (graphics == null || delta == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.level == null || mc.options.hideGui) {
            return;
        }
        graphics.nextStratum();
        AtlasMapExtendedCommon.renderMinimap(graphics, delta);
        graphics.nextStratum();
    }
}
