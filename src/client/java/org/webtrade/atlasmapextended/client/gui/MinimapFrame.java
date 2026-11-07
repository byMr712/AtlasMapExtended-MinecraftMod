package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.webtrade.atlasmapextended.client.AtlasMapExtendedCommon;

/** Draws the minimap above the scoreboard, and behind any open screen. */
public final class MinimapFrame {
    private static final boolean FABRIC = hasClass("net.fabricmc.loader.api.FabricLoader");

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

    /** Fabric builds the HUD from a layer list; NeoForge needs an extra draw after the HUD. */
    public static boolean useLayer() {
        return FABRIC;
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
        g.flush();
        AtlasMapExtendedCommon.renderMinimap(g, delta);
        g.flush();
    }

    /** Late pass: only while no screen is open, so it can sit above the scoreboard. */
    public static void drawAboveHud(GuiGraphics g, DeltaTracker delta) {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        draw(g, delta);
    }
}