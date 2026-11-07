package org.webtrade.atlasmapextended.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.webtrade.atlasmapextended.client.data.Storage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Loads screenshots asynchronously into GPU textures, either as thumbnails or in full size. */
public final class ImageCache {
    private static final int THUMB_WIDTH = 240;
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "AtlasMapExtended-Images");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    public static final class Entry {
        public Identifier id;
        public int width;
        public int height;
        /** Size of the source image (before thumbnail scaling). */
        public int origWidth;
        public int origHeight;
        public boolean failed;
        boolean closed;

        public boolean ready() {
            return id != null;
        }
    }

    /** A dynamic texture sampled linearly so that downscaled photos look smooth. */
    private static final class SmoothTexture extends DynamicTexture {
        SmoothTexture(String label, NativeImage image) {
            super(() -> label, image);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    private ImageCache() {
    }

    public static Entry get(Path file, boolean thumb) {
        String key = (thumb ? "t:" : "f:") + file.toAbsolutePath();
        Entry e = ENTRIES.get(key);
        if (e != null) {
            return e;
        }
        Entry entry = new Entry();
        ENTRIES.put(key, entry);
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage img = NativeImage.read(in);
                entry.origWidth = img.getWidth();
                entry.origHeight = img.getHeight();
                if (thumb && img.getWidth() > THUMB_WIDTH) {
                    int h = Math.max(1, img.getHeight() * THUMB_WIDTH / img.getWidth());
                    NativeImage small = new NativeImage(THUMB_WIDTH, h, false);
                    img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), small);
                    img.close();
                    return small;
                }
                return img;
            } catch (Exception ex) {
                Storage.LOG.warn("Failed to load image {}", file, ex);
                return null;
            }
        }, DECODER).thenAcceptAsync(img -> {
            if (img == null) {
                entry.failed = true;
                return;
            }
            if (entry.closed) {
                img.close();
                return;
            }
            Identifier id = Identifier.fromNamespaceAndPath("atlasmapextended", "image/" + COUNTER.incrementAndGet());
            entry.width = img.getWidth();
            entry.height = img.getHeight();
            mc.getTextureManager().register(id, new SmoothTexture("atlasmapextended image", img));
            entry.id = id;
        }, mc);
        return entry;
    }

    /** Draws the image fitted into the box, keeping its aspect ratio. */
    public static void drawFit(GuiGraphicsExtractor g, Entry e, int x, int y, int w, int h) {
        if (e == null || !e.ready()) {
            return;
        }
        float s = Math.min(w / (float) e.width, h / (float) e.height);
        int dw = Math.max(1, Math.round(e.width * s));
        int dh = Math.max(1, Math.round(e.height * s));
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, e.id, dx, dy, 0, 0, dw, dh, e.width, e.height, e.width, e.height);
    }

    /** Draws the image covering the box (cropping the overflow). */
    public static void drawCover(GuiGraphicsExtractor g, Entry e, int x, int y, int w, int h) {
        if (e == null || !e.ready()) {
            return;
        }
        float s = Math.max(w / (float) e.width, h / (float) e.height);
        int uw = Math.max(1, Math.round(w / s));
        int vh = Math.max(1, Math.round(h / s));
        int u = (e.width - uw) / 2;
        int v = (e.height - vh) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, e.id, x, y, u, v, w, h, uw, vh, e.width, e.height);
    }

    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Entry e : ENTRIES.values()) {
            e.closed = true;
            if (e.id != null) {
                mc.getTextureManager().release(e.id);
            }
        }
        ENTRIES.clear();
    }

    /** Copies an image file into a world's screenshot folder as PNG. Runs on the IO thread. */
    public static CompletableFuture<String> importImage(Path source, Path targetDir) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = Files.newInputStream(source)) {
                NativeImage img = NativeImage.read(in);
                Files.createDirectories(targetDir);
                String name = java.util.UUID.randomUUID() + ".png";
                try {
                    img.writeToFile(targetDir.resolve(name));
                } finally {
                    img.close();
                }
                return name;
            } catch (Exception ex) {
                Storage.LOG.warn("Failed to import image {}", source, ex);
                return null;
            }
        }, Storage.IO);
    }
    /** Imports a death screenshot, giving both the source and the stored copy a death_ name. */
    public static CompletableFuture<String> importDeathImage(Path source, Path targetDir) {
        return CompletableFuture.supplyAsync(() -> {
            Path named = source;
            try {
                String name = source.getFileName().toString();
                if (!name.startsWith("death_")) {
                    Path target = source.resolveSibling("death_" + name);
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    named = target;
                }
            } catch (Exception ex) {
                // fall back to the original file; the stored copy still gets the prefix
            }
            String baseName = named.getFileName().toString();
            if (!baseName.startsWith("death_")) {
                baseName = "death_" + baseName;
            }
            try (InputStream in = Files.newInputStream(named)) {
                NativeImage img = NativeImage.read(in);
                Files.createDirectories(targetDir);
                String fileName = baseName;
                Path target = targetDir.resolve(fileName);
                if (Files.exists(target)) {
                    fileName = java.util.UUID.randomUUID() + "_" + baseName;
                    target = targetDir.resolve(fileName);
                }
                try {
                    img.writeToFile(target);
                } finally {
                    img.close();
                }
                return fileName;
            } catch (Exception ex) {
                Storage.LOG.warn("Failed to import death image {}", named, ex);
                return null;
            }
        }, Storage.IO);
    }
}
