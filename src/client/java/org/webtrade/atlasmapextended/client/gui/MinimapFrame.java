package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.webtrade.atlasmapextended.client.AtlasMapExtendedCommon;

/** Draws the minimap as the last HUD layer, so it covers the scoreboard and stays under screens. */
public final class MinimapFrame {
    private static final boolean FABRIC = hasClass("net.fabricmc.loader.api.FabricLoader");
    private static boolean drawnThisFrame;

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

    /** Fabric builds the HUD from a layer list; NeoForge gets a direct draw at the end of Gui.render. */
    public static boolean useLayer() {
        return FABRIC;
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