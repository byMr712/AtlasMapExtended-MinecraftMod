package org.webtrade.atlasmapextended.client.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A user placed marker (waypoint) on the map. Serialized with Gson. */
public class Marker {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String description = "";
    public String icon = "pin";
    /** Icon tint (0xRRGGBB with full alpha), or 0 for the icon's own colours. */
    public int color;
    public String dim = "minecraft:overworld";
    public int x;
    public int y = 64;
    public int z;
    public boolean hidden;
    /** True for markers created automatically on death. */
    public boolean death;
    /** Death-marker generation used to rotate only the active limit. */
    public int deathEpoch;
    public long created = System.currentTimeMillis();
    public List<String> screenshots = new ArrayList<>();
    /** Drawings on top of screenshots, keyed by screenshot file. Points are in image pixels. */
    public Map<String, List<Stroke>> shotDrawings = new HashMap<>();

    public Marker copy() {
        Marker m = new Marker();
        m.id = id;
        m.name = name;
        m.description = description;
        m.icon = icon;
        m.color = color;
        m.dim = dim;
        m.x = x;
        m.y = y;
        m.z = z;
        m.hidden = hidden;
        m.death = death;
        m.deathEpoch = deathEpoch;
        m.created = created;
        m.screenshots = new ArrayList<>(screenshots);
        m.shotDrawings = copyDrawings(shotDrawings);
        return m;
    }

    public void apply(Marker from) {
        name = from.name;
        description = from.description;
        icon = from.icon;
        color = from.color;
        dim = from.dim;
        x = from.x;
        y = from.y;
        z = from.z;
        hidden = from.hidden;
        death = from.death;
        deathEpoch = from.deathEpoch;
        screenshots = new ArrayList<>(from.screenshots);
        shotDrawings = copyDrawings(from.shotDrawings);
    }

    public List<Stroke> drawingsFor(String shot) {
        if (shotDrawings == null) {
            shotDrawings = new HashMap<>();
        }
        return shotDrawings.computeIfAbsent(shot, k -> new ArrayList<>());
    }

    private static Map<String, List<Stroke>> copyDrawings(Map<String, List<Stroke>> src) {
        Map<String, List<Stroke>> out = new HashMap<>();
        if (src != null) {
            src.forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    out.put(k, new ArrayList<>(v));
                }
            });
        }
        return out;
    }
}
