package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.webtrade.atlasmapextended.client.AtlasMapExtendedCommon;

/** Draws the minimap last, so it covers the scoreboard and stays under screens. */
public final class MinimapFrame {
    private static boolean drawnThisFrame;
    private static GuiGraphics pendingGraphics;
    private static DeltaTracker pendingDelta;

    private MinimapFrame() {
    }

    public static void beginFrame() {
        drawnThisFrame = false;
        pendingGraphics = null;
        pendingDelta = null;
    }

    public static void remember(GuiGraphics g, DeltaTracker delta) {
        pendingGraphics = g;
        pendingDelta = delta;
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
        g.nextStratum();
        AtlasMapExtendedCommon.renderMinimap(g, delta);
        g.nextStratum();
    }

    public static void drawLate() {
        if (drawnThisFrame || pendingGraphics == null || pendingDelta == null) {
            return;
        }
        draw(pendingGraphics, pendingDelta);
    }
}