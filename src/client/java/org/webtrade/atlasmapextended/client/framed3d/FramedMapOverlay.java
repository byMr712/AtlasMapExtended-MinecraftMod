package org.webtrade.atlasmapextended.client.framed3d;

import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Stroke;

import java.util.ArrayList;
import java.util.List;

/**
 * Frozen snapshot of AtlasMap objects that were inside a map's area when the
 * map was first seen. Later marker/drawing/region edits do not change it.
 */
public class FramedMapOverlay {
    public String dim = "";
    public int centerX;
    public int centerZ;
    public int scale;
    public List<Marker> markers = new ArrayList<>();
    public List<Region> regions = new ArrayList<>();
    public List<Stroke> strokes = new ArrayList<>();

    public boolean matches(FramedMapKey key) {
        return dim.equals(key.dimension().identifier().toString())
                && centerX == key.centerX()
                && centerZ == key.centerZ()
                && scale == key.scale();
    }
}
