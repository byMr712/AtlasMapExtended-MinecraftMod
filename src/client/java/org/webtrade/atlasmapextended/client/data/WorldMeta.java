package org.webtrade.atlasmapextended.client.data;

/** Describes one stored world (a server or a singleplayer save). */
public class WorldMeta {
    public static final String SERVER = "server";
    public static final String LOCAL = "local";

    public String id = "";
    public String name = "";
    public String type = LOCAL;
    public String address = "";
    public long lastPlayed;
    public String lastDim = "minecraft:overworld";
    public double lastX;
    public double lastZ;
    public long seed;
    public String uid = "";

    /** Player position when this dimension was last left, keyed by dimension id. */
    public java.util.Map<String, double[]> dimPos = new java.util.LinkedHashMap<>();

    public double[] posFor(String dim) {
        return dimPos == null ? null : dimPos.get(dim);
    }

    public void setPos(String dim, double x, double z) {
        if (dimPos == null) {
            dimPos = new java.util.LinkedHashMap<>();
        }
        dimPos.put(dim, new double[]{x, z});
    }

    public boolean isServer() {
        return SERVER.equals(type);
    }
}
