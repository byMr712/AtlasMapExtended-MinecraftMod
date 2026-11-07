package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.webtrade.atlasmapextended.client.AtlasMapExtendedCommon;

/** Draws the minimap last, so it covers the scoreboard and stays under screens. */
public final class MinimapFrame {
    private static final boolean FABRIC = hasClass("net.fabricmc.loader.api.FabricLoader");
    private static boolean drawnThisFrame;
    private static GuiGraphics pendingGraphics;
    private static DeltaTracker pendingDelta;

    private MinimapFrame() {
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, MinimapFrame.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Fabric builds the HUD from a layer list; NeoForge draws at the end of the whole frame. */
    public static boolean useLayer() {
        return FABRIC;
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
        g.flush();
        AtlasMapExtendedCommon.renderMinimap(g, delta);
        g.flush();
    }

    public static void drawLate() {
        if (drawnThisFrame || pendingGraphics == null || pendingDelta == null) {
            return;
        }
        draw(pendingGraphics, pendingDelta);
    }
}