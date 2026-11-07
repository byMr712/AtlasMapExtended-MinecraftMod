package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.webtrade.atlasmapextended.client.AtlasMapExtendedCommon;

/** Draws the minimap above the scoreboard, and behind any open screen. */
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
        if (mc.level == null || mc.options.hideGui) {
            return;
        }
        g.nextStratum();
        AtlasMapExtendedCommon.renderMinimap(g, delta);
        g.nextStratum();
    }

    /** Called from the screen render hook: above the scoreboard, below the screen. */
    public static void drawBehindScreen(GuiGraphics g) {
        if (g == null || pendingDelta == null) {
            return;
        }
        draw(g, pendingDelta);
    }

    public static void drawAboveHud() {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        if (pendingGraphics == null || pendingDelta == null) {
            return;
        }
        draw(pendingGraphics, pendingDelta);
    }
}