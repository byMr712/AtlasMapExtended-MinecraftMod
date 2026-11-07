package org.webtrade.atlasmapextended.client.data;

/**
 * A single drawing on the map. Points are stored in world block coordinates (x, z pairs),
 * the width is in screen pixels so drawings stay readable at every zoom level.
 */
public class Stroke {
    public static final String PEN = "pen";
    public static final String LINE = "line";
    public static final String ARROW = "arrow";
    public static final String RECT = "rect";
    public static final String CIRCLE = "circle";

    public String type = PEN;
    public int color = 0xFF3A2616;
    public int width = 2;
    public double[] pts = new double[0];

    public Stroke() {
    }

    public Stroke(String type, int color, int width, double[] pts) {
        this.type = type;
        this.color = color;
        this.width = width;
        this.pts = pts;
    }

    public int pointCount() {
        return pts.length / 2;
    }

    public void addPoint(double x, double z) {
        double[] n = new double[pts.length + 2];
        System.arraycopy(pts, 0, n, 0, pts.length);
        n[pts.length] = x;
        n[pts.length + 1] = z;
        pts = n;
    }
}
