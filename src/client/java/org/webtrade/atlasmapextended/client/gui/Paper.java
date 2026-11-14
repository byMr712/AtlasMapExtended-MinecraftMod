package org.webtrade.atlasmapextended.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

/** Drawing helpers for the papyrus look: parchment panels, inked borders, buttons and lines. */
public final class Paper {
    public static final ResourceLocation COMPASS = id("textures/gui/compass.png");
    public static final int RED = 0xFF9C3B2A;
    public static final int SHADOW = 0x55000000;

    // current theme; reassigned by apply()
    public static ResourceLocation PARCHMENT;
    public static ResourceLocation PARCHMENT_DARK;
    public static int INK;
    public static int INK_SOFT;
    public static int INK_FAINT;
    public static int GOLD;
    public static int GOLD_LIGHT;
    public static int CREAM;
    public static int DISABLED;
    public static int HINT;
    public static int DIM;
    public static int VIGNETTE;
    private static Theme theme;

    static {
        apply(Theme.PAPYRUS);
    }

    private Paper() {
    }

    public static Theme theme() {
        return theme;
    }

    public static void apply(Theme t) {
        theme = t;
        PARCHMENT = t.panel();
        PARCHMENT_DARK = t.panelDark();
        INK = t.ink();
        INK_SOFT = t.inkSoft();
        INK_FAINT = a(t.ink(), 0x66);
        GOLD = t.accent();
        GOLD_LIGHT = t.accentLight();
        CREAM = t.cream();
        DISABLED = t.disabled();
        HINT = t.hint();
        DIM = t.darkBg() ? 0x90000000 : 0x60100A05;
        VIGNETTE = t.vignette();
    }

    /** The colour with its alpha replaced. */
    public static int a(int color, int alpha) {
        return alpha << 24 | color & 0xFFFFFF;
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("atlasmapextended", path);
    }
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("AtlasMapExtended/Textures");
    private static final java.util.Set<ResourceLocation> MISSING = new java.util.HashSet<>();
    private static final java.util.Map<ResourceLocation, net.minecraft.client.renderer.texture.DynamicTexture> LOADED = new java.util.HashMap<>();

    /**
     * Loads a texture straight from the mod jar. Fabric Loader does not mount mod resources on its
     * own (that is Fabric API's resource-loader), so the mod stays dependency-free by registering
     * its PNGs with the texture manager itself.
     */
    public static void ensure(ResourceLocation texture) {
        ensure(texture, "/assets/" + texture.getNamespace() + "/" + texture.getPath());
    }

    /** Loads the image of {@code source} but registers it under {@code texture}. */
    public static void ensureFrom(ResourceLocation texture, ResourceLocation source) {
        ensure(texture, "/assets/" + source.getNamespace() + "/" + source.getPath());
    }

    private static void ensure(ResourceLocation texture, String path) {
        net.minecraft.client.renderer.texture.TextureManager manager = Minecraft.getInstance().getTextureManager();
        net.minecraft.client.renderer.texture.DynamicTexture loaded = LOADED.get(texture);
        if (loaded != null && manager.getTexture(texture) == loaded) {
            return;
        }
        if (MISSING.contains(texture)) {
            return;
        }
        try (java.io.InputStream in = Paper.class.getResourceAsStream(path)) {
            if (in == null) {
                MISSING.add(texture);
                LOG.warn("Missing texture {}", path);
                return;
            }
            net.minecraft.client.renderer.texture.DynamicTexture created =
                    new net.minecraft.client.renderer.texture.DynamicTexture(com.mojang.blaze3d.platform.NativeImage.read(in));
            manager.register(texture, created);
            LOADED.put(texture, created);
        } catch (java.io.IOException e) {
            MISSING.add(texture);
            LOG.warn("Failed to load texture {}", path, e);
        }
    }

    /** Frees a texture registered by this mod, so it can be loaded again later. */
    public static void release(ResourceLocation texture) {
        if (LOADED.remove(texture) != null) {
            Minecraft.getInstance().getTextureManager().release(texture);
        }
    }


    /** 1px border drawn with fills, so it follows the normal GUI batch order. */
    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static ResourceLocation marker(String icon) {
        String name = icon == null || icon.isBlank() ? "pin" : ("arrow".equals(icon) ? "arrow_n" : icon);
        T.Alias alias = T.ALIASES.get(name);
        if (alias != null) {
            name = alias.icon();
        }
        return id("textures/gui/markers/" + name + ".png");
    }

    public static ResourceLocation tool(String icon) {
        return id((theme.darkBg() ? "textures/gui/tools_light/" : "textures/gui/tools/") + icon + ".png");
    }

    /** Tiles a 256x256 texture over a rectangle. */
    public static void tiled(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h) {
        tiled(g, tex, x, y, w, h, 0, 0);
    }

    public static void tiled(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, int offX, int offY) {
        ensure(tex);
        int u0 = Math.floorMod(offX, 256);
        int v0 = Math.floorMod(offY, 256);
        int yy = 0;
        int v = v0;
        while (yy < h) {
            int ch = Math.min(256 - v, h - yy);
            int xx = 0;
            int u = u0;
            while (xx < w) {
                int cw = Math.min(256 - u, w - xx);
                g.blit(RenderType::guiTextured, tex, x + xx, y + yy, u, v, cw, ch, 256, 256);
                xx += cw;
                u = 0;
            }
            yy += ch;
            v = 0;
        }
    }

    /** A parchment sheet with a double inked border and gilded corners. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, SHADOW);
        tiled(g, PARCHMENT, x, y, w, h, x * 3, y * 7);
        border(g, x, y, w, h);
    }

    public static void border(GuiGraphics g, int x, int y, int w, int h) {
        if (!theme.ornate()) {
            Paper.outline(g, x, y, w, h, a(INK, 0x50));
            return;
        }
        Paper.outline(g, x, y, w, h, INK);
        Paper.outline(g, x + 2, y + 2, w - 4, h - 4, INK_FAINT);
        corner(g, x - 1, y - 1);
        corner(g, x + w - 4, y - 1);
        corner(g, x - 1, y + h - 4);
        corner(g, x + w - 4, y + h - 4);
    }

    private static void corner(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 5, y + 5, INK);
        g.fill(x + 1, y + 1, x + 4, y + 4, GOLD);
        g.fill(x + 2, y + 2, x + 3, y + 3, GOLD_LIGHT);
    }

    /** A sunken area used behind text fields and image slots. */
    public static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x30FFFFFF);
        g.fill(x, y, x + w, y + 1, INK_SOFT);
        g.fill(x, y, x + 1, y + h, INK_SOFT);
        g.fill(x, y + h - 1, x + w, y + h, 0x50FFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0x50FFFFFF);
    }

    /** A decorative separator line with a small diamond in the middle. */
    public static void separator(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, INK_FAINT);
        int cx = x + w / 2;
        g.fill(cx - 1, y - 1, cx + 2, y + 2, INK_SOFT);
    }

    /** A marker icon; a non-zero colour repaints it using its grey copy. Alpha (0-255) fades it. */
    public static void markerIcon(GuiGraphics g, String icon, int color, int x, int y, int size, int alpha) {
        String name = icon == null || icon.isBlank() ? "pin" : ("arrow".equals(icon) ? "arrow_n" : icon);
        int c = color;
        T.Alias alias = T.ALIASES.get(name);
        if (alias != null) {
            name = alias.icon();
            if (c == 0) {
                c = alias.color();
            }
        }
        if (c == 0) {
            icon(g, marker(name), x, y, size, alpha << 24 | 0xFFFFFF);
        } else {
            icon(g, id("textures/gui/markers_tint/" + name + ".png"), x, y, size, a(c, alpha));
        }
    }

    public static void icon(GuiGraphics g, ResourceLocation tex, int x, int y, int size) {
        ensure(tex);
        g.blit(RenderType::guiTextured, tex, x, y, 0f, 0f, size, size, 16, 16, 16, 16);
    }

    public static void icon(GuiGraphics g, ResourceLocation tex, int x, int y, int size, int color) {
        ensure(tex);
        g.blit(RenderType::guiTextured, tex, x, y, 0f, 0f, size, size, 16, 16, 16, 16, color);
    }

    public static void iconMirroredH(GuiGraphics g, ResourceLocation tex, int x, int y, int size, int color) {
        ensure(tex);
        g.blit(RenderType::guiTextured, tex, x, y, 16f, 0f, size, size, -16, 16, 16, 16, color);
    }

    /** Square icon button. */
    public static void iconButton(GuiGraphics g, ResourceLocation icon, int x, int y, int size, boolean hovered, boolean selected) {
        g.fill(x + 1, y + 1, x + size + 1, y + size + 1, SHADOW);
        tiled(g, selected ? PARCHMENT_DARK : PARCHMENT, x, y, size, size, x * 5, y * 3);
        if (selected) {
            g.fill(x, y, x + size, y + size, a(GOLD, 0x30));
        }
        if (hovered) {
            g.fill(x, y, x + size, y + size, 0x28FFFFFF);
        }
        Paper.outline(g, x, y, size, size, selected ? GOLD : INK);
        if (hovered || selected) {
            Paper.outline(g, x + 1, y + 1, size - 2, size - 2, selected ? GOLD_LIGHT : INK_FAINT);
        }
        int is = size >= 20 ? 16 : size - 4;
        icon(g, icon, x + (size - is) / 2, y + (size - is) / 2, is);
    }

    /** Text button in parchment style. */
    public static void button(GuiGraphics g, Font font, Component label, int x, int y, int w, int h, boolean hovered, boolean enabled) {
        g.fill(x + 1, y + 1, x + w + 1, y + h + 1, SHADOW);
        tiled(g, PARCHMENT_DARK, x, y, w, h, x * 5, y * 3);
        if (hovered && enabled) {
            g.fill(x, y, x + w, y + h, 0x30FFFFFF);
        }
        Paper.outline(g, x, y, w, h, hovered && enabled ? GOLD : INK);
        int color = enabled ? INK : DISABLED;
        String s = font.plainSubstrByWidth(label.getString(), w - 6);
        g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2 + 1, color, false);
    }

    /** Button with an icon and a label. */
    public static void iconTextButton(GuiGraphics g, Font font, ResourceLocation icon, Component label, int x, int y, int w, int h,
                                      boolean hovered, boolean enabled) {
        g.fill(x + 1, y + 1, x + w + 1, y + h + 1, SHADOW);
        tiled(g, PARCHMENT_DARK, x, y, w, h, x * 5, y * 3);
        if (hovered && enabled) {
            g.fill(x, y, x + w, y + h, 0x30FFFFFF);
        }
        Paper.outline(g, x, y, w, h, hovered && enabled ? GOLD : INK);
        int is = Math.min(16, h - 4);
        String s = font.plainSubstrByWidth(label.getString(), w - is - 10);
        int total = is + 3 + font.width(s);
        int sx = x + (w - total) / 2;
        icon(g, icon, sx, y + (h - is) / 2, is, enabled ? 0xFFFFFFFF : 0x80FFFFFF);
        g.drawString(font, s, sx + is + 3, y + (h - 8) / 2 + 1, enabled ? INK : DISABLED, false);
    }

    public static void title(GuiGraphics g, Font font, String text, int cx, int y) {
        int w = font.width(text);
        g.drawString(font, text, cx - w / 2, y, INK, false);
        g.fill(cx - w / 2 - 12, y + 4, cx - w / 2 - 4, y + 5, INK_SOFT);
        g.fill(cx + w / 2 + 4, y + 4, cx + w / 2 + 12, y + 5, INK_SOFT);
    }

    /** Draws a straight line of the given thickness using a rotated, scaled quad. */
    public static void line(GuiGraphics g, float x1, float y1, float x2, float y2, float width, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) {
            return;
        }
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x1, y1, 0);
        pose.mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        pose.translate(0, -width / 2f, 0);
        pose.scale(len, width, 1f);
        g.fill(0, 0, 1, 1, color);
        pose.popPose();
    }

    public static void dot(GuiGraphics g, float x, float y, float size, int color) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x - size / 2f, y - size / 2f, 0);
        pose.scale(size, size, 1f);
        g.fill(0, 0, 1, 1, color);
        pose.popPose();
    }

    public static void dashedLine(GuiGraphics g, float x1, float y1, float x2, float y2, float width, int color, float dash) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) {
            return;
        }
        float ux = dx / len;
        float uy = dy / len;
        for (float t = 0; t < len; t += dash * 2) {
            float e = Math.min(len, t + dash);
            line(g, x1 + ux * t, y1 + uy * t, x1 + ux * e, y1 + uy * e, width, color);
        }
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    public static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    public static void pageTurn() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0f));
    }
}
