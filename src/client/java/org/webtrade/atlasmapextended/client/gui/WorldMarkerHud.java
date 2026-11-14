package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Draws marker icons over the game world while playing. */
public final class WorldMarkerHud {
    private static final int ICON_SIZE = 20;
    private static final double FADE_RADIUS_SQ = 5.0 * 5.0;
    private static final double HOVER_RADIUS_SQ = (ICON_SIZE / 2.0 + 3) * (ICON_SIZE / 2.0 + 3);
    private static final long TEXTURE_TTL_TICKS = 100;

    // Reused every frame so rendering does not allocate per marker.
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Vector3f POINT = new Vector3f();
    private static final Map<String, IconEntry> ICONS = new HashMap<>();

    private static boolean screenshotPending;
    private static boolean screenshotPanorama;
    private static boolean visibleLastFrame;

    private WorldMarkerHud() {
    }

    /** Whether any marker icon was drawn in the latest frame. */
    public static boolean hasVisibleMarkers() {
        return visibleLastFrame;
    }

    /** Defers a screenshot by one frame so marker icons are not captured in it. */
    public static void requestScreenshot(boolean panorama) {
        screenshotPending = true;
        screenshotPanorama = panorama;
    }

    /** Takes the screenshot queued by {@link #requestScreenshot(boolean)} after a marker-free frame. */
    public static void finishScreenshot() {
        if (!screenshotPending) {
            return;
        }
        screenshotPending = false;
        Screenshot.grab(Minecraft.getInstance(), screenshotPanorama);
    }

    public static void render(GuiGraphicsExtractor g) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.worldMarkers || !cfg.markersVisible) {
            releaseAll();
            visibleLastFrame = false;
            return;
        }
        if (screenshotPending) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        MapSession session = MapManager.live();
        if (p == null || mc.level == null || session == null || mc.gui.screen() != null || session.markers.isEmpty()) {
            visibleLastFrame = false;
            return;
        }
        Camera camera = mc.gameRenderer.mainCamera();
        if (camera == null || !camera.isInitialized()) {
            visibleLastFrame = false;
            return;
        }
        String dim = mc.level.dimension().identifier().toString();
        Vec3 eye = camera.position();
        Vector3fc forward = camera.forwardVector();
        camera.getViewRotationProjectionMatrix(PROJECTION);

        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int crossX = sw / 2;
        int crossY = sh / 2;
        long now = mc.level.getGameTime();
        int renderDistance = Math.max(16, mc.options.getEffectiveRenderDistance() * 16);
        double renderDistanceSq = (double) renderDistance * renderDistance;
        int drawn = 0;
        for (IconEntry entry : ICONS.values()) {
            entry.uses = 0;
        }

        Marker hovered = null;
        double hoverDistance = Double.MAX_VALUE;
        int hoverX = 0;
        int hoverY = 0;
        double hoveredDist = 0;
        for (Marker m : session.markers) {
            if (m.hidden || !m.dim.equals(dim)) {
                continue;
            }
            double dx = m.x + 0.5 - eye.x;
            double dy = m.y + 1.0 - eye.y;
            double dz = m.z + 0.5 - eye.z;
            if (dx * dx + dz * dz > renderDistanceSq || !mc.level.hasChunk(m.x >> 4, m.z >> 4)) {
                continue;
            }
            if (dx * forward.x() + dy * forward.y() + dz * forward.z() <= 0) {
                continue;
            }
            POINT.set((float) dx, (float) dy, (float) dz);
            PROJECTION.transformProject(POINT);
            if (POINT.z < -1 || POINT.z > 1) {
                continue;
            }
            int sx = Math.round((POINT.x + 1) * 0.5f * sw);
            int sy = Math.round((1 - POINT.y) * 0.5f * sh);
            if (sx < -ICON_SIZE || sx > sw + ICON_SIZE || sy < -ICON_SIZE || sy > sh + ICON_SIZE) {
                continue;
            }
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int hx = sx - crossX;
            int hy = sy - crossY;
            double hoverDist = hx * hx + hy * hy;
            int alpha = 178;
            if (hoverDist <= HOVER_RADIUS_SQ) {
                alpha = 128;
                if (hoverDist < hoverDistance) {
                    hoverDistance = hoverDist;
                    hovered = m;
                    hoverX = sx;
                    hoverY = sy;
                    hoveredDist = dist;
                }
            }
            if (dist < 5.0) {
                double t = Math.max(0, Math.min(1, (dist - 1.0) / 4.0));
                int near = (int) Math.round(102 + (178 - 102) * t);
                if (dist < 1.0) {
                    near = (int) Math.round(20 + (near - 20) * Math.max(0, dist));
                }
                alpha = Math.min(alpha, near);
            }
            double scale;
            if (dist <= 10.0) {
                scale = 0.65;
            } else if (dist >= 40.0) {
                scale = 1.0;
            } else {
                scale = 0.65 + 0.35 * (dist - 10.0) / 30.0;
            }
            int iconSize = (int) Math.round(ICON_SIZE * scale);
            IconEntry icon = iconEntry(m.icon, m.color != 0, now);
            icon.uses++;
            icon.lastUsed = now;
            int tint = m.color == 0 ? alpha << 24 | 0xFFFFFF : Paper.a(m.color, alpha);
            boolean mirrorH = m.icon != null && m.icon.startsWith("arrow");
            if (mirrorH) {
                Matrix3x2fStack pose = g.pose();
                pose.pushMatrix();
                pose.translate((float) sx, (float) sy);
                pose.scale(-1.0f, 1.0f);
                pose.translate((float) -sx, (float) -sy);
                Paper.icon(g, icon.texture, sx - iconSize / 2, sy - iconSize / 2, iconSize, tint);
                pose.popMatrix();
            } else {
                Paper.icon(g, icon.texture, sx - iconSize / 2, sy - iconSize / 2, iconSize, tint);
            }
            drawn++;
        }
        if (hovered != null) {
            drawName(g, mc.font, hovered, hoveredDist, hoverX, hoverY, sw, sh);
        }
        visibleLastFrame = drawn > 0;
        releaseUnused(now);
    }

    private static IconEntry iconEntry(String icon, boolean tinted, long now) {
        String name = icon == null ? "pin" : icon;
        String key = (tinted ? "t:" : "p:") + name;
        IconEntry entry = ICONS.get(key);
        if (entry == null) {
            Identifier source = tinted
                    ? Paper.id("textures/gui/markers_tint/" + name + ".png")
                    : Paper.marker(name);
            Identifier texture = Paper.id("textures/gui/markers_world/"
                    + (tinted ? "tint/" : "") + name + ".png");
            Paper.ensureFrom(texture, source);
            entry = new IconEntry(texture, now);
            ICONS.put(key, entry);
        }
        return entry;
    }

    private static void drawName(GuiGraphicsExtractor g, Font font, Marker m, double distance, int sx, int sy, int sw, int sh) {
        String name = m.name == null || m.name.isBlank() ? T.s("marker.unnamed") : m.name;
        name = font.plainSubstrByWidth(name, Math.min(180, sw - 12));
        String dist = T.s("marker.distance", Math.round(distance));
        int nameW = font.width(name);
        int distW = font.width(dist);
        int tw = Math.max(nameW, distW);
        int tx = Math.max(4, Math.min(sw - tw - 4, sx - tw / 2));
        int ty = sy + ICON_SIZE / 2 + 4;
        if (ty + 21 > sh - 4) {
            ty = sy - ICON_SIZE / 2 - 23;
        }
        ty = Math.max(4, ty);
        g.fill(tx - 4, ty - 2, tx + tw + 4, ty + 19, Paper.a(Paper.CREAM, 0xE8));
        g.outline(tx - 4, ty - 2, tw + 8, 21, Paper.a(Paper.INK, 0xA0));
        g.text(font, name, tx, ty, Paper.INK, false);
        g.text(font, dist, tx + (nameW - distW) / 2, ty + 10, Paper.a(Paper.INK, 0xB0), false);
    }
    /** Frees icon textures that have been out of view for a while, so they load again on return. */
    private static void releaseUnused(long now) {
        Iterator<Map.Entry<String, IconEntry>> it = ICONS.entrySet().iterator();
        while (it.hasNext()) {
            IconEntry entry = it.next().getValue();
            if (entry.uses == 0 && now - entry.lastUsed > TEXTURE_TTL_TICKS) {
                Paper.release(entry.texture);
                it.remove();
            }
        }
    }

    private static void releaseAll() {
        if (ICONS.isEmpty()) {
            return;
        }
        for (IconEntry entry : ICONS.values()) {
            Paper.release(entry.texture);
        }
        ICONS.clear();
    }

    private static final class IconEntry {
        final Identifier texture;
        int uses;
        long lastUsed;

        IconEntry(Identifier texture, long now) {
            this.texture = texture;
            this.lastUsed = now;
        }
    }
}
