package org.webtrade.atlasmapextended.client.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import net.minecraft.client.Camera;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/** Draws marker icons over the game world while playing. */
public final class WorldMarkerHud {
    private static final int ICON_SIZE = 20;
    private static final double FADE_RADIUS_SQ = 5.0 * 5.0;
    private static final double HOVER_RADIUS_SQ = (ICON_SIZE / 2.0 + 3) * (ICON_SIZE / 2.0 + 3);
    private static final long TEXTURE_TTL_TICKS = 100;

    // Reused every frame so rendering does not allocate per marker.
    private static final Map<String, IconEntry> ICONS = new HashMap<>();
    private static final float[] ARROW_ORIGIN = new float[2];
    private static final float[] ARROW_ALONG = new float[2];

    private static boolean screenshotPending;
    private static boolean visibleLastFrame;

    private WorldMarkerHud() {
    }

    /** Whether any marker icon was drawn in the latest frame. */
    public static boolean hasVisibleMarkers() {
        return visibleLastFrame;
    }

    /** Defers a screenshot by one frame so marker icons are not captured in it. */
    public static void requestScreenshot() {
        screenshotPending = true;
    }

    /** Takes the screenshot queued by {@link #requestScreenshot()} after a marker-free frame. */
    public static void finishScreenshot() {
        if (!screenshotPending) {
            return;
        }
        screenshotPending = false;
        captureNow();
    }

    /** Takes a screenshot with the same notification behaviour as the vanilla F2 key. */
    public static void captureNow() {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(),
                component -> mc.gui.getChat().addClientSystemMessage(component));
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
        if (p == null || mc.level == null || session == null || mc.screen != null || session.markers.isEmpty()) {
            visibleLastFrame = false;
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            visibleLastFrame = false;
            return;
        }
        String dim = mc.level.dimension().identifier().toString();
        Vec3 eye = p.getEyePosition();

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
            Vec3 screen = mc.gameRenderer.projectPointToScreen(new Vec3(m.x + 0.5, m.y + 1.0, m.z + 0.5));
            if (screen.z < -1 || screen.z > 1) {
                continue;
            }
            int sx = Math.round((float) ((screen.x + 1) * 0.5 * sw));
            int sy = Math.round((float) ((1 - screen.y) * 0.5 * sh));
            if (sx < -ICON_SIZE || sx > sw + ICON_SIZE || sy < -ICON_SIZE || sy > sh + ICON_SIZE) {
                continue;
            }
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int hx0 = sx - crossX;
            int hy0 = sy - crossY;
            int alpha = 178;
            if (hx0 * hx0 + hy0 * hy0 <= HOVER_RADIUS_SQ) {
                alpha = 128;
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
            int arrow = arrowAngle(m.icon);
            if (arrow >= 0) {
                IconEntry arrowIcon = iconEntry("arrow_n", m.color != 0, now);
                arrowIcon.uses++;
                arrowIcon.lastUsed = now;
                int arrowTint = m.color == 0 ? alpha << 24 | 0xFFFFFF : Paper.a(m.color, alpha);
                if (drawWorldArrow(g, m, eye, arrow, arrowIcon.texture, arrowTint,
                        iconSize, screenUpAngle(camera), sw, sh, camera)) {
                    drawn++;
                    continue;
                }
            }
            String iconName = adaptedArrow(m.icon, screenUpAngle(camera));
            IconEntry icon = iconEntry(iconName, m.color != 0, now);
            icon.uses++;
            icon.lastUsed = now;
            int tint = m.color == 0 ? alpha << 24 | 0xFFFFFF : Paper.a(m.color, alpha);
            Paper.icon(g, icon.texture, sx - iconSize / 2, sy - iconSize / 2, iconSize, tint);
            drawn++;

            int hx = sx - crossX;
            int hy = sy - crossY;
            double hoverDist = hx * hx + hy * hy;
            if (hoverDist <= HOVER_RADIUS_SQ && hoverDist < hoverDistance) {
                hoverDistance = hoverDist;
                hovered = m;
                hoverX = sx;
                hoverY = sy;
                hoveredDist = dist;
            }
        }
        if (hovered != null) {
            drawName(g, mc.font, hovered, hoveredDist, hoverX, hoverY, sw, sh);
        }
        visibleLastFrame = drawn > 0;
        releaseUnused(now);
    }

    private static IconEntry iconEntry(String icon, boolean tinted, long now) {
        String name = icon == null || icon.isBlank() ? "pin" : ("arrow".equals(icon) ? "arrow_n" : icon);
        T.Alias alias = T.ALIASES.get(name);
        if (alias != null) {
            name = alias.icon();
        }
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
        String name = m.name == null || m.name.isBlank() ? T.s("marker.unnamed") : m.name.trim();
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
        g.text(font, name, tx + (tw - nameW) / 2, ty, Paper.INK, false);
        g.text(font, dist, tx + (tw - distW) / 2, ty + 10, Paper.a(Paper.INK, 0xB0), false);
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

    private static final String[] ARROW_NAMES = {
            "arrow_n", "arrow_ne", "arrow_e", "arrow_se",
            "arrow_s", "arrow_sw", "arrow_w", "arrow_nw"
    };

    private static int arrowAngle(String icon) {
        if (icon == null || icon.isBlank()) return -1;
        String name = "arrow".equals(icon) ? "arrow_n" : icon;
        T.Alias alias = T.ALIASES.get(name);
        if (alias != null) {
            name = alias.icon();
        }
        return switch (name) {
            case "arrow_n" -> 0;
            case "arrow_ne" -> 45;
            case "arrow_e" -> 90;
            case "arrow_se" -> 135;
            case "arrow_s" -> 180;
            case "arrow_sw" -> 225;
            case "arrow_w" -> 270;
            case "arrow_nw" -> 315;
            default -> -1;
        };
    }

    private static boolean drawWorldArrow(GuiGraphicsExtractor g, Marker m, Vec3 eye,
                                          int arrowDeg, Identifier texture, int tint,
                                          int iconSize, double screenUp, int sw, int sh,
                                          Camera camera) {
        double rad = Math.toRadians(arrowDeg);
        double dirX = Math.sin(rad);
        double dirZ = -Math.cos(rad);
        double ax = m.x + 0.5;
        double ay = m.y + 1.0;
        double az = m.z + 0.5;
        float[] origin = ARROW_ORIGIN;
        if (!projectPoint(ax, ay, az, origin, sw, sh)) {
            return false;
        }
        float[] ahead = ARROW_ALONG;
        // Project a point further along the arrow, scaled with distance: a
        // one-block step at long range lands within a pixel or two and the
        // angle becomes noisy, which reads as shaking.
        double arrowStep = Math.min(8.0, Math.max(1.0, Math.sqrt(
                (ax - eye.x) * (ax - eye.x) + (ay - eye.y) * (ay - eye.y)
                        + (az - eye.z) * (az - eye.z)) * 0.05));
        if (!projectPoint(ax + dirX * arrowStep, ay, az + dirZ * arrowStep, ahead, sw, sh)) {
            return false;
        }
        float dx = ahead[0] - origin[0];
        float dy = ahead[1] - origin[1];
        double angle = Math.hypot(dx, dy) < 1.5
                ? Math.toRadians(arrowDeg - screenUp)
                : Math.atan2(dx, -dy);
        if (!Double.isFinite(angle)) {
            return false;
        }
        Vector3f forward = new Quaternionf(camera.rotation()).transform(new Vector3f(0.0f, 0.0f, -1.0f));
        double facing = Math.abs(dirX * forward.x + dirZ * forward.z);
        float lengthScale = (float) (0.35 + 0.65 * Math.sqrt(Math.max(0.0, 1.0 - facing * facing)));
        g.pose().pushMatrix();
        g.pose().translate(origin[0], origin[1]);
        g.pose().rotate((float) angle);
        g.pose().scale(1.0f, lengthScale);
        Paper.icon(g, texture, -iconSize / 2, -iconSize / 2, iconSize, tint);
        g.pose().popMatrix();
        return true;
    }

    private static boolean projectPoint(double x, double y, double z,
                                        float[] out, int sw, int sh) {
        Vec3 point = Minecraft.getInstance().gameRenderer.projectPointToScreen(new Vec3(x, y, z));
        if (point.z < -1 || point.z > 1) {
            return false;
        }
        out[0] = (float) ((point.x + 1.0) * 0.5 * sw);
        out[1] = (float) ((1.0 - point.y) * 0.5 * sh);
        return true;
    }

    /**
     * Map angle (0 = north, 90 = east) that points up on screen for the given
     * camera. Derived from the camera's screen-right axis projected onto the
     * ground, which includes yaw, third-person/free-camera offsets and roll.
     */
    private static double screenUpAngle(Camera camera) {
        Quaternionf q = new Quaternionf(camera.rotation());
        Vector3f right = q.transform(new Vector3f(1.0f, 0.0f, 0.0f));
        double len = Math.hypot(right.x, right.z);
        if (len > 1.0e-4) {
            double rightAngle = Math.toDegrees(Math.atan2(right.x / len, -right.z / len));
            return rightAngle - 90.0;
        }
        // Straight up/down with an exact 90 degree roll: fall back to the
        // forward direction, where the horizontal axis still has a meaning.
        Vector3f forward = q.transform(new Vector3f(0.0f, 0.0f, -1.0f));
        return Math.toDegrees(Math.atan2(forward.x, -forward.z));
    }

    private static String adaptedArrow(String icon, double screenUp) {
        int arrowAngle = arrowAngle(icon);
        if (arrowAngle < 0) {
            return icon;
        }
        double relAngle = (arrowAngle - screenUp) % 360.0;
        if (relAngle < 0) {
            relAngle += 360.0;
        }
        int octant = (int) Math.floor((relAngle + 22.5) / 45.0) & 7;
        return ARROW_NAMES[octant];
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
