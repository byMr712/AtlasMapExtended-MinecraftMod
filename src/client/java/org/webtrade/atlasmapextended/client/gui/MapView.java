package org.webtrade.atlasmapextended.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Stroke;
import org.webtrade.atlasmapextended.client.map.MapLayer;
import org.webtrade.atlasmapextended.client.map.MapTile;

import java.util.Arrays;
import java.util.List;

/** A viewport onto the world map. Shared by the full screen map and the minimap. */
public class MapView {
    public int x;
    public int y;
    public int w;
    public int h;
    public double cx;
    public double cz;
    /** GUI pixels per block. */
    public float zoom = 2f;

    /** Diagnostics: time spent in {@link #drawRegions} during the last frame. */
    public static long lastRegionsNanos;
    public static long lastBakeNanos;
    public static int lastRegionsDrawn;
    public static int lastRegionsPoints;

    private final RegionOverlay regionOverlay = new RegionOverlay();
    private long regionCacheKey = Long.MIN_VALUE;

    // scratch buffers, reused between regions and frames to keep the GC quiet
    private float[] rawX = new float[0];
    private float[] rawY = new float[0];
    private float[] simpleX = new float[0];
    private float[] simpleY = new float[0];
    private boolean[] keep = new boolean[0];
    private int[] stack = new int[0];
    private float[] cross = new float[0];
    private int[] dir = new int[0];
    private int[] spanX1 = new int[0];
    private int[] spanX2 = new int[0];

    private static float[] grow(float[] a, int n) {
        return a.length >= n ? a : new float[Math.max(n, a.length * 2)];
    }

    private static int[] grow(int[] a, int n) {
        return a.length >= n ? a : new int[Math.max(n, a.length * 2)];
    }

    private static boolean[] grow(boolean[] a, int n) {
        return a.length >= n ? a : new boolean[Math.max(n, a.length * 2)];
    }


    public void setRect(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public float sx(double wx) {
        return (float) (x + w / 2.0 + (wx - cx) * zoom);
    }

    public float sy(double wz) {
        return (float) (y + h / 2.0 + (wz - cz) * zoom);
    }

    public double wx(double sx) {
        return cx + (sx - x - w / 2.0) / zoom;
    }

    public double wz(double sy) {
        return cz + (sy - y - h / 2.0) / zoom;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    /** Zooms keeping the world point under (mx, my) in place. */
    public void zoomAt(double mx, double my, float newZoom) {
        double wx = wx(mx);
        double wz = wz(my);
        zoom = newZoom;
        cx = wx - (mx - x - w / 2.0) / zoom;
        cz = wz - (my - y - h / 2.0) / zoom;
    }

    public void drawBackground(GuiGraphics g) {
        Paper.tiled(g, Paper.PARCHMENT, x, y, w, h, (int) Math.floor(cx * zoom), (int) Math.floor(cz * zoom));
    }

    public void drawTiles(GuiGraphics g, MapLayer layer) {
        int rx0 = Math.floorDiv((int) Math.floor(wx(x)), MapTile.SIZE);
        int rx1 = Math.floorDiv((int) Math.floor(wx(x + w)), MapTile.SIZE);
        int rz0 = Math.floorDiv((int) Math.floor(wz(y)), MapTile.SIZE);
        int rz1 = Math.floorDiv((int) Math.floor(wz(y + h)), MapTile.SIZE);
        PoseStack pose = g.pose();
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                MapTile t = layer.getForRender(rx, rz);
                if (t == null) {
                    continue;
                }
                pose.pushPose();
                pose.translate(sx(rx * (double) MapTile.SIZE), sy(rz * (double) MapTile.SIZE), 0);
                pose.scale(zoom, zoom, 1f);
                g.blit(RenderType::guiTextured, t.id, 0, 0, 0f, 0f, MapTile.SIZE, MapTile.SIZE, MapTile.SIZE, MapTile.SIZE);
                pose.popPose();
            }
        }
    }

    /** Faint chunk grid when zoomed in far enough. */
    public void drawGrid(GuiGraphics g) {
        if (zoom < 3f) {
            return;
        }
        int color = zoom >= 6 ? Paper.a(Paper.INK, 0x22) : Paper.a(Paper.INK, 0x16);
        int bx0 = Math.floorDiv((int) Math.floor(wx(x)), 16) * 16;
        int bz0 = Math.floorDiv((int) Math.floor(wz(y)), 16) * 16;
        for (int bx = bx0; sx(bx) < x + w; bx += 16) {
            int px = Math.round(sx(bx));
            if (px >= x) {
                g.fill(px, y, px + 1, y + h, color);
            }
        }
        for (int bz = bz0; sy(bz) < y + h; bz += 16) {
            int py = Math.round(sy(bz));
            if (py >= y) {
                g.fill(x, py, x + w, py + 1, color);
            }
        }
    }

    public void drawStrokes(GuiGraphics g, List<Stroke> strokes, float widthScale) {
        for (Stroke s : strokes) {
            drawStroke(g, s, widthScale);
        }
    }

    public void drawStroke(GuiGraphics g, Stroke s, float widthScale) {
        double[] p = s.pts;
        if (p.length < 2) {
            return;
        }
        float wpx = Math.max(1f, s.width * widthScale);
        int c = s.color;
        switch (s.type) {
            case Stroke.LINE -> {
                if (p.length >= 4) {
                    seg(g, p[0], p[1], p[2], p[3], wpx, c);
                }
            }
            case Stroke.ARROW -> {
                if (p.length >= 4) {
                    seg(g, p[0], p[1], p[2], p[3], wpx, c);
                    float ax = sx(p[0]);
                    float ay = sy(p[1]);
                    float bx = sx(p[2]);
                    float by = sy(p[3]);
                    double ang = Math.atan2(by - ay, bx - ax);
                    double len = Math.max(7.0, wpx * 4.0);
                    double spread = Math.toRadians(26);
                    Paper.line(g, bx, by,
                            (float) (bx - Math.cos(ang - spread) * len),
                            (float) (by - Math.sin(ang - spread) * len), wpx, c);
                    Paper.line(g, bx, by,
                            (float) (bx - Math.cos(ang + spread) * len),
                            (float) (by - Math.sin(ang + spread) * len), wpx, c);
                }
            }
            case Stroke.RECT -> {
                if (p.length >= 4) {
                    seg(g, p[0], p[1], p[2], p[1], wpx, c);
                    seg(g, p[2], p[1], p[2], p[3], wpx, c);
                    seg(g, p[2], p[3], p[0], p[3], wpx, c);
                    seg(g, p[0], p[3], p[0], p[1], wpx, c);
                    if (wpx >= 2) {
                        Paper.dot(g, sx(p[0]), sy(p[1]), wpx, c);
                        Paper.dot(g, sx(p[2]), sy(p[1]), wpx, c);
                        Paper.dot(g, sx(p[2]), sy(p[3]), wpx, c);
                        Paper.dot(g, sx(p[0]), sy(p[3]), wpx, c);
                    }
                }
            }
            case Stroke.CIRCLE -> {
                if (p.length >= 4) {
                    double r = Math.hypot(p[2] - p[0], p[3] - p[1]);
                    float rs = (float) (r * zoom);
                    int segs = Math.max(16, Math.min(128, (int) (rs / 2)));
                    double px = p[0] + r;
                    double pz = p[1];
                    for (int i = 1; i <= segs; i++) {
                        double a = i * Math.PI * 2 / segs;
                        double nx = p[0] + Math.cos(a) * r;
                        double nz = p[1] + Math.sin(a) * r;
                        seg(g, px, pz, nx, nz, wpx, c);
                        px = nx;
                        pz = nz;
                    }
                }
            }
            default -> {
                if (p.length == 2) {
                    Paper.dot(g, sx(p[0]), sy(p[1]), wpx, c);
                }
                for (int i = 0; i + 3 < p.length; i += 2) {
                    seg(g, p[i], p[i + 1], p[i + 2], p[i + 3], wpx, c);
                    if (wpx >= 2) {
                        Paper.dot(g, sx(p[i + 2]), sy(p[i + 3]), wpx, c);
                    }
                }
            }
        }
    }

    private void seg(GuiGraphics g, double x1, double z1, double x2, double z2, float w, int c) {
        float ax = sx(x1);
        float ay = sy(z1);
        float bx = sx(x2);
        float by = sy(z2);
        // cheap culling
        float minX = Math.min(ax, bx) - w;
        float maxX = Math.max(ax, bx) + w;
        float minY = Math.min(ay, by) - w;
        float maxY = Math.max(ay, by) + w;
        if (maxX < x || minX > x + w() || maxY < y || minY > y + h) {
            return;
        }
        Paper.line(g, ax, ay, bx, by, w, c);
    }

    private int w() {
        return w;
    }

    /** Distance (in screen pixels) from a point to a stroke, used by the eraser. */
    public double distanceTo(Stroke s, double mx, double my) {
        double[] p = s.pts;
        double best = Double.MAX_VALUE;
        switch (s.type) {
            case Stroke.RECT -> {
                if (p.length >= 4) {
                    best = Math.min(best, segDist(mx, my, p[0], p[1], p[2], p[1]));
                    best = Math.min(best, segDist(mx, my, p[2], p[1], p[2], p[3]));
                    best = Math.min(best, segDist(mx, my, p[2], p[3], p[0], p[3]));
                    best = Math.min(best, segDist(mx, my, p[0], p[3], p[0], p[1]));
                }
            }
            case Stroke.CIRCLE -> {
                if (p.length >= 4) {
                    double r = Math.hypot(p[2] - p[0], p[3] - p[1]) * zoom;
                    double d = Math.hypot(mx - sx(p[0]), my - sy(p[1]));
                    best = Math.abs(d - r);
                }
            }
            default -> {
                if (p.length == 2) {
                    best = Math.hypot(mx - sx(p[0]), my - sy(p[1]));
                }
                for (int i = 0; i + 3 < p.length; i += 2) {
                    best = Math.min(best, segDist(mx, my, p[i], p[i + 1], p[i + 2], p[i + 3]));
                }
            }
        }
        return best;
    }

    private double segDist(double mx, double my, double x1, double z1, double x2, double z2) {
        double ax = sx(x1);
        double ay = sy(z1);
        double bx = sx(x2);
        double by = sy(z2);
        double dx = bx - ax;
        double dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((mx - ax) * dx + (my - ay) * dy) / l2));
        return Math.hypot(mx - (ax + t * dx), my - (ay + t * dy));
    }

    /**
     * Draws markers of the given dimension.
     *
     * @param clampToEdge if true, markers outside the view are drawn small at the border (minimap)
     */
    public void drawMarkers(GuiGraphics g, Font font, List<Marker> markers, String dim, int size, boolean labels,
                            String selectedId, String hoveredId, boolean clampToEdge) {
        for (Marker m : markers) {
            if (m.hidden || !m.dim.equals(dim)) {
                continue;
            }
            float px = sx(m.x + 0.5);
            float py = sy(m.z + 0.5);
            int s = size;
            boolean outside = px < x || py < y || px >= x + w || py >= y + h;
            if (outside) {
                if (!clampToEdge) {
                    if (px < x - size || py < y - size || px > x + w + size || py > y + h + size) {
                        continue;
                    }
                } else {
                    s = Math.max(6, size * 2 / 3);
                    px = Math.max(x + s / 2f, Math.min(x + w - s / 2f, px));
                    py = Math.max(y + s / 2f, Math.min(y + h - s / 2f, py));
                }
            }
            int ix = Math.round(px - s / 2f);
            int iy = Math.round(py - s / 2f);
            boolean selected = m.id.equals(selectedId);
            boolean hovered = m.id.equals(hoveredId);
            if (selected || hovered) {
                int pad = 2;
                g.fill(ix - pad, iy - pad, ix + s + pad, iy + s + pad, selected ? Paper.a(Paper.GOLD_LIGHT, 0x60) : 0x40FFFFFF);
                Paper.outline(g, ix - pad, iy - pad, s + pad * 2, s + pad * 2, selected ? Paper.GOLD : Paper.INK_SOFT);
            }
            Paper.markerIcon(g, m.icon, m.color, ix, iy, s, outside ? 0xB0 : 0xFF);
            if (labels && !outside && (zoom >= 1.5f || selected || hovered) && !m.name.isEmpty()) {
                String name = font.plainSubstrByWidth(m.name, 120);
                int tw = font.width(name);
                int tx = Math.round(px - tw / 2f);
                int ty = iy + s + 2;
                g.fill(tx - 3, ty - 2, tx + tw + 3, ty + 9, Paper.a(Paper.CREAM, 0xD0));
                Paper.outline(g, tx - 3, ty - 2, tw + 6, 11, Paper.a(Paper.INK, 0x80));
                g.drawString(font, name, tx, ty, Paper.INK, false);
            }
        }
    }

    public Marker markerAt(List<Marker> markers, String dim, double mx, double my, int size) {
        Marker best = null;
        double bestD = size * 0.75;
        for (Marker m : markers) {
            if (m.hidden || !m.dim.equals(dim)) {
                continue;
            }
            double d = Math.max(Math.abs(mx - sx(m.x + 0.5)), Math.abs(my - sy(m.z + 0.5)));
            if (d <= bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    /**
     * Draws all named areas of one dimension. The whole layer is rasterised into one ARGB
     * texture and blitted once: the cost no longer depends on the number of regions.
     */
    public void drawRegions(GuiGraphics g, Font font, List<Region> regions, String dim, boolean labels, String selectedId) {
        if (w <= 0 || h <= 0) {
            return;
        }
        long start = System.nanoTime();
        long key = regionKey(regions, dim, selectedId);
        if (key != regionCacheKey) {
            long bakeStart = System.nanoTime();
            int[] pixels = regionOverlay.begin(w, h);
            int[] stats = rasterize(pixels, regions, dim, selectedId);
            lastRegionsDrawn = stats[0];
            lastRegionsPoints = stats[1];
            lastBakeNanos = System.nanoTime() - bakeStart;
            regionCacheKey = key;
        }
        regionOverlay.draw(g, x, y);
        lastRegionsNanos = System.nanoTime() - start;
    }

    /** Changes whenever the region layer has to be rasterised again. */
    private long regionKey(List<Region> regions, String dim, String selectedId) {
        long h = 1125899906842597L;
        h = 31 * h + Double.doubleToLongBits(cx);
        h = 31 * h + Double.doubleToLongBits(cz);
        h = 31 * h + Float.floatToIntBits(zoom);
        h = 31 * h + this.x * 1000003L + this.y;
        h = 31 * h + this.w * 31L + this.h;
        h = 31 * h + (selectedId == null ? 0 : selectedId.hashCode());
        for (Region r : regions) {
            if (!r.dim.equals(dim)) {
                continue;
            }
            h = 31 * h + r.id.hashCode();
            h = 31 * h + System.identityHashCode(r.pts);
            h = 31 * h + (r.hidden ? 1 : 0) + (r.filled ? 2 : 0) + r.color;
        }
        return h;
    }

    private int[] rasterize(int[] pixels, List<Region> regions, String dim, String selectedId) {
        int drawn = 0;
        int points = 0;
        for (Region r : regions) {
            if (r.hidden || !r.dim.equals(dim)) {
                continue;
            }
            int n = r.pointCount();
            if (n < 3) {
                continue;
            }
            // world bounds first: an off-screen region costs nothing, not even the point loop
            double[] b = r.bounds();
            float minX = Math.min(sx(b[0]), sx(b[1]));
            float maxX = Math.max(sx(b[0]), sx(b[1]));
            float minY = Math.min(sy(b[2]), sy(b[3]));
            float maxY = Math.max(sy(b[2]), sy(b[3]));
            if (maxX < x || minX > x + w || maxY < y || minY > y + h) {
                continue;
            }
            rawX = grow(rawX, n);
            rawY = grow(rawY, n);
            for (int i = 0; i < n; i++) {
                rawX[i] = sx(r.pts[i * 2]) - x;
                rawY[i] = sy(r.pts[i * 2 + 1]) - y;
            }
            // simplify below a pixel of screen space; the picture stays the same
            int m = simplify(rawX, rawY, n, 0.75f);
            points += m;
            drawn++;
            int rgb = r.color & 0xFFFFFF;
            if (r.filled) {
                fillSpans(pixels, simpleX, simpleY, m, rgb | 0x55000000, minY - y, maxY - y);
            }
            boolean selected = r.id.equals(selectedId);
            int outline = selected ? Paper.GOLD : rgb | 0xFF000000;
            float lineWidth = selected ? 3f : 1.4f;
            for (int i = 0, j = m - 1; i < m; j = i++) {
                line(pixels, simpleX[i], simpleY[i], simpleX[j], simpleY[j], lineWidth, outline);
            }
            for (int i = 0; i < m; i++) {
                dot(pixels, simpleX[i], simpleY[i], lineWidth, outline);
            }
        }
        return new int[]{drawn, points};
    }

    /** Non-zero winding scanline fill straight into the overlay pixels. */
    private void fillSpans(int[] pixels, float[] xs, float[] ys, int m, int color, float top, float bottom) {
        int y0 = Math.max(0, (int) Math.floor(top));
        int y1 = Math.min(h - 1, (int) Math.ceil(bottom));
        cross = grow(cross, m);
        dir = grow(dir, m);
        for (int yy = y0; yy <= y1; yy++) {
            float cy = yy + 0.5f;
            int count = 0;
            for (int i = 0, j = m - 1; i < m; j = i++) {
                float yi = ys[i];
                float yj = ys[j];
                if ((yi > cy) != (yj > cy)) {
                    cross[count] = xs[i] + (cy - yi) / (yj - yi) * (xs[j] - xs[i]);
                    dir[count] = yj > yi ? 1 : -1;
                    count++;
                }
            }
            if (count < 2) {
                continue;
            }
            sortCrossings(count);
            int wind = 0;
            int spanStart = 0;
            for (int k = 0; k < count; k++) {
                int before = wind;
                wind += dir[k];
                if (before == 0 && wind != 0) {
                    spanStart = (int) Math.ceil(cross[k]);
                } else if (before != 0 && wind == 0) {
                    int x1 = Math.max(0, spanStart);
                    int x2 = Math.min(w, (int) Math.ceil(cross[k]));
                    if (x2 > x1) {
                        blendSpan(pixels, yy, x1, x2, color);
                    }
                }
            }
        }
    }

    /** Source-over blending of one horizontal run into the overlay. */
    private void blendSpan(int[] pixels, int yy, int x1, int x2, int color) {
        int sa = color >>> 24;
        if (sa == 0) {
            return;
        }
        int row = yy * w;
        if (sa >= 255) {
            java.util.Arrays.fill(pixels, row + x1, row + x2, color);
            return;
        }
        int sr = (color >> 16) & 0xFF;
        int sg = (color >> 8) & 0xFF;
        int sb = color & 0xFF;
        int inv = 255 - sa;
        for (int xx = x1; xx < x2; xx++) {
            int dst = pixels[row + xx];
            int da = dst >>> 24;
            if (da == 0) {
                pixels[row + xx] = color;
                continue;
            }
            int outA = sa + da * inv / 255;
            if (outA <= 0) {
                pixels[row + xx] = 0;
                continue;
            }
            int outR = (sr * sa + ((dst >> 16) & 0xFF) * da * inv / 255) / outA;
            int outG = (sg * sa + ((dst >> 8) & 0xFF) * da * inv / 255) / outA;
            int outB = (sb * sa + (dst & 0xFF) * da * inv / 255) / outA;
            pixels[row + xx] = outA << 24 | outR << 16 | outG << 8 | outB;
        }
    }

    /**
     * Anti-aliased line: every pixel inside the segment's bounding box gets coverage from its
     * distance to the segment, so thin outlines are smooth on small views such as the minimap.
     */
    private void line(int[] pixels, float x1, float y1, float x2, float y2, float width, int color) {
        float half = Math.max(0.55f, width / 2f);
        float pad = half + 1f;
        int bx0 = Math.max(0, (int) Math.floor(Math.min(x1, x2) - pad));
        int bx1 = Math.min(w - 1, (int) Math.ceil(Math.max(x1, x2) + pad));
        int by0 = Math.max(0, (int) Math.floor(Math.min(y1, y2) - pad));
        int by1 = Math.min(h - 1, (int) Math.ceil(Math.max(y1, y2) + pad));
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len2 = dx * dx + dy * dy;
        int alpha = color >>> 24;
        for (int py = by0; py <= by1; py++) {
            float cy = py + 0.5f;
            for (int px = bx0; px <= bx1; px++) {
                float cx = px + 0.5f;
                float t = len2 < 0.0001f ? 0f
                        : ((cx - x1) * dx + (cy - y1) * dy) / len2;
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                float ddx = cx - (x1 + t * dx);
                float ddy = cy - (y1 + t * dy);
                float coverage = half + 0.5f - (float) Math.sqrt(ddx * ddx + ddy * ddy);
                if (coverage <= 0) {
                    continue;
                }
                if (coverage > 1) {
                    coverage = 1;
                }
                blendPixel(pixels, px, py, ((int) (alpha * coverage) << 24) | (color & 0xFFFFFF));
            }
        }
    }

    /** Round joint so thin segments do not leave dark gaps at polygon vertices. */
    private void dot(int[] pixels, float cx, float cy, float width, int color) {
        float r = Math.max(0.55f, width / 2f);
        int bx0 = Math.max(0, (int) Math.floor(cx - r - 1));
        int bx1 = Math.min(w - 1, (int) Math.ceil(cx + r + 1));
        int by0 = Math.max(0, (int) Math.floor(cy - r - 1));
        int by1 = Math.min(h - 1, (int) Math.ceil(cy + r + 1));
        int alpha = color >>> 24;
        for (int py = by0; py <= by1; py++) {
            float dy = py + 0.5f - cy;
            for (int px = bx0; px <= bx1; px++) {
                float dx = px + 0.5f - cx;
                float coverage = r + 0.5f - (float) Math.sqrt(dx * dx + dy * dy);
                if (coverage <= 0) {
                    continue;
                }
                if (coverage > 1) {
                    coverage = 1;
                }
                blendPixel(pixels, px, py, ((int) (alpha * coverage) << 24) | (color & 0xFFFFFF));
            }
        }
    }

    private void blendPixel(int[] pixels, int px, int py, int color) {
        int sa = color >>> 24;
        if (sa == 0) {
            return;
        }
        int index = py * w + px;
        int dst = pixels[index];
        int da = dst >>> 24;
        if (da == 0) {
            pixels[index] = color;
            return;
        }
        int inv = 255 - sa;
        int outA = sa + da * inv / 255;
        if (outA <= 0) {
            pixels[index] = 0;
            return;
        }
        int outR = (((color >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * inv / 255) / outA;
        int outG = (((color >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * inv / 255) / outA;
        int outB = ((color & 0xFF) * sa + (dst & 0xFF) * da * inv / 255) / outA;
        pixels[index] = outA << 24 | outR << 16 | outG << 8 | outB;
    }

    /** Direct single-region drawing, used for the region that is being drawn right now. */
    public void drawRegion(GuiGraphics g, Font font, Region r, boolean labels, boolean selected) {
        int n = r.pointCount();
        if (n < 3) {
            return;
        }
        double[] b = r.bounds();
        float minX = Math.min(sx(b[0]), sx(b[1]));
        float maxX = Math.max(sx(b[0]), sx(b[1]));
        float minY = Math.min(sy(b[2]), sy(b[3]));
        float maxY = Math.max(sy(b[2]), sy(b[3]));
        if (maxX < x || minX > x + w || maxY < y || minY > y + h) {
            return;
        }
        rawX = grow(rawX, n);
        rawY = grow(rawY, n);
        for (int i = 0; i < n; i++) {
            rawX[i] = sx(r.pts[i * 2]);
            rawY[i] = sy(r.pts[i * 2 + 1]);
        }
        int m = simplify(rawX, rawY, n, 0.75f);
        int rgb = r.color & 0xFFFFFF;
        int fill = rgb | 0x55000000;
        int outline = rgb | 0xFF000000;
        if (r.filled) {
            int y0 = Math.max(y, (int) Math.floor(minY));
            int y1 = Math.min(y + h - 1, (int) Math.ceil(maxY));
            cross = grow(cross, m);
            dir = grow(dir, m);
            spanX1 = grow(spanX1, m);
            spanX2 = grow(spanX2, m);
            int bandX1 = 0;
            int bandX2 = 0;
            int bandTop = 0;
            int bandBottom = 0;
            boolean band = false;
            for (int yy = y0; yy <= y1; yy++) {
                float cy = yy + 0.5f;
                int count = 0;
                for (int i = 0, j = m - 1; i < m; j = i++) {
                    float yi = simpleY[i];
                    float yj = simpleY[j];
                    if ((yi > cy) != (yj > cy)) {
                        cross[count] = simpleX[i] + (cy - yi) / (yj - yi) * (simpleX[j] - simpleX[i]);
                        dir[count] = yj > yi ? 1 : -1;
                        count++;
                    }
                }
                if (count < 2) {
                    continue;
                }
                sortCrossings(count);
                int wind = 0;
                int spanStart = 0;
                int spans = 0;
                for (int k = 0; k < count; k++) {
                    int before = wind;
                    wind += dir[k];
                    if (before == 0 && wind != 0) {
                        spanStart = (int) Math.ceil(cross[k]);
                    } else if (before != 0 && wind == 0) {
                        int x1 = Math.max(x, spanStart);
                        int x2 = Math.min(x + w, (int) Math.ceil(cross[k]));
                        if (x2 > x1) {
                            spanX1[spans] = x1;
                            spanX2[spans] = x2;
                            spans++;
                        }
                    }
                }
                if (spans == 1) {
                    int x1 = spanX1[0];
                    int x2 = spanX2[0];
                    if (band && x1 == bandX1 && x2 == bandX2 && yy == bandBottom) {
                        bandBottom = yy + 1;
                    } else {
                        if (band) {
                            g.fill(bandX1, bandTop, bandX2, bandBottom, fill);
                        }
                        band = true;
                        bandX1 = x1;
                        bandX2 = x2;
                        bandTop = yy;
                        bandBottom = yy + 1;
                    }
                } else if (spans > 1) {
                    if (band) {
                        g.fill(bandX1, bandTop, bandX2, bandBottom, fill);
                        band = false;
                    }
                    for (int s = 0; s < spans; s++) {
                        g.fill(spanX1[s], yy, spanX2[s], yy + 1, fill);
                    }
                }
            }
            if (band) {
                g.fill(bandX1, bandTop, bandX2, bandBottom, fill);
            }
        }
        for (int i = 0, j = m - 1; i < m; j = i++) {
            Paper.line(g, simpleX[i], simpleY[i], simpleX[j], simpleY[j], selected ? 2.4f : 1.4f,
                    selected ? Paper.GOLD : outline);
        }
        for (int i = 0; i < m; i++) {
            Paper.dot(g, simpleX[i], simpleY[i], selected ? 2.4f : 1.4f,
                    selected ? Paper.GOLD : outline);
        }
        if (labels && !r.name.isBlank() && maxX - minX >= 24) {
            drawRegionName(g, font, r, minX, maxX, minY, maxY);
        }
    }

    /** Ramer-Douglas-Peucker in screen space; writes into {@link #simpleX}/{@link #simpleY}. */
    private int simplify(float[] xs, float[] ys, int n, float tolerance) {
        simpleX = grow(simpleX, n);
        simpleY = grow(simpleY, n);
        if (n <= 4) {
            System.arraycopy(xs, 0, simpleX, 0, n);
            System.arraycopy(ys, 0, simpleY, 0, n);
            return n;
        }
        keep = grow(keep, n);
        stack = grow(stack, n * 4);
        java.util.Arrays.fill(keep, 0, n, false);
        keep[0] = true;
        keep[n - 1] = true;
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        float tol2 = tolerance * tolerance;
        while (sp > 0) {
            int b = stack[--sp];
            int a = stack[--sp];
            float dx = xs[b] - xs[a];
            float dy = ys[b] - ys[a];
            float len2 = dx * dx + dy * dy;
            float best = -1;
            int idx = -1;
            for (int i = a + 1; i < b; i++) {
                float d;
                if (len2 < 0.0001f) {
                    float ex = xs[i] - xs[a];
                    float ey = ys[i] - ys[a];
                    d = ex * ex + ey * ey;
                } else {
                    float t = ((xs[i] - xs[a]) * dx + (ys[i] - ys[a]) * dy) / len2;
                    t = t < 0 ? 0 : (t > 1 ? 1 : t);
                    float ex = xs[i] - (xs[a] + t * dx);
                    float ey = ys[i] - (ys[a] + t * dy);
                    d = ex * ex + ey * ey;
                }
                if (d > best) {
                    best = d;
                    idx = i;
                }
            }
            if (idx > a && best > tol2) {
                keep[idx] = true;
                stack[sp++] = a;
                stack[sp++] = idx;
                stack[sp++] = idx;
                stack[sp++] = b;
            }
        }
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                simpleX[m] = xs[i];
                simpleY[m] = ys[i];
                m++;
            }
        }
        return m;
    }

    private void sortCrossings(int count) {
        for (int a = 1; a < count; a++) {
            float keyX = cross[a];
            int keyDir = dir[a];
            int b = a - 1;
            while (b >= 0 && cross[b] > keyX) {
                cross[b + 1] = cross[b];
                dir[b + 1] = dir[b];
                b--;
            }
            cross[b + 1] = keyX;
            dir[b + 1] = keyDir;
        }
    }

    /** Draws only the region names, so drawings never cover them. */
    public void drawRegionLabels(GuiGraphics g, Font font, List<Region> regions, String dim, String selectedId) {
        if (zoom < 0.5f) {
            for (Region r : regions) {
                if (r.hidden || !r.dim.equals(dim) || r.name.isBlank() || r.pointCount() < 3
                        || selectedId == null || !r.id.equals(selectedId)) {
                    continue;
                }
                drawRegionLabel(g, font, r);
            }
            return;
        }
        for (Region r : regions) {
            if (r.hidden || !r.dim.equals(dim) || r.name.isBlank() || r.pointCount() < 3) {
                continue;
            }
            drawRegionLabel(g, font, r);
        }
    }

    private void drawRegionLabel(GuiGraphics g, Font font, Region r) {
        double[] b = r.bounds();
        float minX = Math.min(sx(b[0]), sx(b[1]));
        float maxX = Math.max(sx(b[0]), sx(b[1]));
        float minY = Math.min(sy(b[2]), sy(b[3]));
        float maxY = Math.max(sy(b[2]), sy(b[3]));
        if (maxX < x || minX > x + w || maxY < y || minY > y + h) {
            return;
        }
        // a name wider than its region would only clutter the map with overlapping labels
        if (maxX - minX < 24) {
            return;
        }
        drawRegionName(g, font, r, minX, maxX, minY, maxY);
    }

    private void drawRegionName(GuiGraphics g, Font font, Region r,
                                float minX, float maxX, float minY, float maxY) {
        float cx = (minX + maxX) / 2f;
        float cy = (minY + maxY) / 2f;
        String name = font.plainSubstrByWidth(r.name, 140);
        int tw = font.width(name);
        int tx = Math.round(cx - tw / 2f);
        int ty = Math.round(cy - 4);
        g.fill(tx - 3, ty - 2, tx + tw + 3, ty + 9, Paper.a(Paper.CREAM, 0xD8));
        Paper.outline(g, tx - 3, ty - 2, tw + 6, 11, Paper.a(Paper.INK, 0x90));
        g.drawString(font, name, tx, ty, Paper.INK, false);
    }

    /** The innermost area under the cursor, if any (nested regions: the smallest wins). */
    public Region regionAt(List<Region> regions, String dim, double mx, double my) {
        return Region.innermostAt(regions, dim, wx(mx), wz(my));
    }

    /** The region whose map label is under the cursor. */
    public Region regionLabelAt(Font font, List<Region> regions, String dim, String selectedId,
                                double mx, double my) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (r.hidden || !r.dim.equals(dim) || r.name.isBlank() || r.pointCount() < 3) {
                continue;
            }
            // when zoomed out only the selected region keeps its name on screen
            if (zoom < 0.5f && (selectedId == null || !r.id.equals(selectedId))) {
                continue;
            }
            double[] b = r.bounds();
            float minX = Math.min(sx(b[0]), sx(b[1]));
            float maxX = Math.max(sx(b[0]), sx(b[1]));
            float minY = Math.min(sy(b[2]), sy(b[3]));
            float maxY = Math.max(sy(b[2]), sy(b[3]));
            String name = font.plainSubstrByWidth(r.name, 140);
            int tw = font.width(name);
            int tx = Math.round((minX + maxX) / 2f - tw / 2f);
            int ty = Math.round((minY + maxY) / 2f - 4);
            if (mx >= tx - 3 && mx <= tx + tw + 3 && my >= ty - 2 && my <= ty + 9) {
                return r;
            }
        }
        return null;
    }

    /** Non-clickable "you were here" pin. Returns true when the cursor is over it. */
    public boolean drawLastSeen(GuiGraphics g, double px, double pz, int size, double mx, double my) {
        int x = Math.round(sx(px)) - size / 2;
        int y = Math.round(sy(pz)) - size / 2;
        Paper.icon(g, Paper.tool("target"), x, y, size);
        return Paper.inside(mx, my, x, y, size, size);
    }

    public void drawPlayer(GuiGraphics g, double px, double pz, float yaw, int size) {
        float sx = sx(px);
        float sy = sy(pz);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(sx, sy, 0);
        pose.mulPose(Axis.ZP.rotation((float) Math.toRadians(yaw + 180)));
        Paper.ensure(Paper.tool("arrow"));
        g.blit(RenderType::guiTextured, Paper.tool("arrow"), -size / 2, -size / 2, 0f, 0f, size, size, 16, 16, 16, 16);
        pose.popPose();
    }

    public static void newFrame() {
        MapLayer.newFrame();
    }
}
