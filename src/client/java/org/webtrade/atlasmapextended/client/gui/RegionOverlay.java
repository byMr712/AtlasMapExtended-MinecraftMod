package org.webtrade.atlasmapextended.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Arrays;

/**
 * A screen-sized ARGB layer for region fills and outlines. Everything is rasterised on the CPU
 * once per view change and drawn with a single blit, so thousands of regions cost one draw call
 * per frame instead of one per edge.
 */
final class RegionOverlay {
    private static int counter;
    private final Identifier id;
    private DynamicTexture texture;
    private int[] pixels = new int[0];
    private int width;
    private int height;
    private boolean dirty = true;

    RegionOverlay() {
        id = Identifier.fromNamespaceAndPath("atlasmapextended", "region_overlay/" + (counter++));
    }

    /** Clears the buffer for the given size and returns it for rasterisation. */
    int[] begin(int w, int h) {
        if (texture == null || w != width || h != height) {
            release();
            width = w;
            height = h;
            NativeImage image = new NativeImage(w, h, true);
            texture = new DynamicTexture(() -> "atlasmapextended region overlay", image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            pixels = new int[w * h];
        }
        Arrays.fill(pixels, 0);
        dirty = true;
        return pixels;
    }

    /** Uploads the pixels (only after a rebuild) and draws the layer at the given screen position. */
    void draw(GuiGraphicsExtractor g, int x, int y) {
        if (texture == null || width <= 0 || height <= 0) {
            return;
        }
        if (dirty) {
            NativeImage image = texture.getPixels();
            if (image != null) {
                java.nio.ByteBuffer buf = image.getPixelBytes();
                int total = width * height;
                for (int i = 0; i < total; i++) {
                    buf.putInt(i * 4, net.minecraft.util.ARGB.toABGR(pixels[i]));
                }
                texture.upload();
            }
            dirty = false;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, width, height, width, height);
    }

    void release() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(id);
            texture = null;
        }
    }
}
