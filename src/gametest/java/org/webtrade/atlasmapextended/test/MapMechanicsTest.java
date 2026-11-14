package org.webtrade.atlasmapextended.test;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.client.data.Stroke;
import org.webtrade.atlasmapextended.client.data.WorldMeta;
import org.webtrade.atlasmapextended.client.gui.ImageCache;
import org.webtrade.atlasmapextended.client.gui.MapView;
import org.webtrade.atlasmapextended.client.gui.Paper;
import org.webtrade.atlasmapextended.client.gui.T;
import org.webtrade.atlasmapextended.client.gui.Theme;
import org.webtrade.atlasmapextended.client.map.MapLayer;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;
import org.webtrade.atlasmapextended.client.map.MapTile;
import org.webtrade.atlasmapextended.client.map.WaypointShare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Functional checks for the mod's data layer, storage, map session and image pipeline. */
public class MapMechanicsTest implements FabricClientGameTest {
    private int checks;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            storage();
            markerCopy();
            strokePoints();
            regions();
            themes();
            translations();
            viewTransforms();
            configPersistence();
            waypointShare();
        });

        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(s -> s.setAllowCommands(true))
                .create()) {
            ctx.waitTicks(400);
            ctx.runOnClient(mc -> liveMap());
            ctx.runOnClient(mc -> persistence());
            ctx.runOnClient(mc -> screenshots());
        }

        System.out.println("[AtlasMapExtended mechanics] " + checks + " checks passed");
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("[AtlasMapExtended mechanics] " + message);
        }
    }

    private static void flushIo() {
        try {
            Storage.IO.submit(() -> {
            }).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("IO flush failed", e);
        }
    }

    private void storage() {
        check("my_world".equals(Storage.sanitize("My World")), "sanitize(My World)");
        check(Storage.sanitize("Привет").contains("привет"), "sanitize keeps Cyrillic");
        check(Storage.asciiId("minecraft:overworld").matches("[a-z0-9._-]+_[0-9a-f]+"), "asciiId format");
        check(Storage.dimFolder("minecraft:overworld").equals(Storage.sanitize("minecraft_overworld")), "dimFolder");

        Path dir = Storage.worldDir("test_mechanics");
        Path file = dir.resolve("roundtrip.json");
        Marker m = new Marker();
        m.name = "Roundtrip";
        m.x = 12;
        m.y = 70;
        m.z = -34;
        m.icon = "house";
        m.description = "hello";
        Storage.writeJson(file, m);
        Marker read = Storage.readJson(file, Marker.class, null);
        check(read != null, "json read back");
        check("Roundtrip".equals(read.name), "json name");
        check(read.x == 12 && read.y == 70 && read.z == -34, "json coordinates");
        check("house".equals(read.icon), "json icon");
        Storage.deleteRecursive(dir);
        check(!Files.exists(dir), "test world cleanup");
    }

    private void markerCopy() {
        Marker a = new Marker();
        a.name = "A";
        a.screenshots.add("1.png");
        a.drawingsFor("1.png").add(new Stroke(Stroke.LINE, 0xFF112233, 2, new double[]{1, 2, 3, 4}));
        Marker b = a.copy();
        check(a.id.equals(b.id), "copy id");
        check(!(a.screenshots == b.screenshots), "copy screenshots list");
        check(b.screenshots.contains("1.png"), "copy screenshot value");
        check(b.drawingsFor("1.png").size() == 1, "copy drawings");
        b.screenshots.add("2.png");
        check(!a.screenshots.contains("2.png"), "copy is deep");
        a.apply(b);
        check(a.screenshots.contains("2.png"), "apply screenshots");
    }

    private void strokePoints() {
        Stroke s = new Stroke();
        check(s.pointCount() == 0, "stroke starts empty");
        s.addPoint(1, 2);
        s.addPoint(3, 4);
        check(s.pointCount() == 2, "stroke point count");
        check(s.pts[0] == 1 && s.pts[1] == 2 && s.pts[2] == 3 && s.pts[3] == 4, "stroke points");
        check(Stroke.PEN.equals("pen") && Stroke.LINE.equals("line") && Stroke.RECT.equals("rect") && Stroke.CIRCLE.equals("circle"), "stroke types");
    }

    private void themes() {
        check(Theme.ALL.size() == 8, "eight themes");
        Set<String> ids = new HashSet<>();
        for (Theme t : Theme.ALL) {
            check(ids.add(t.id()), "unique theme id " + t.id());
        }
        check(Theme.byId("modern_dark").id().equals("modern_dark"), "theme by id");
        check(Theme.byId("missing").id().equals("papyrus"), "unknown theme fallback");
        check(Theme.PAPYRUS.ink() == 0xFF3A2616, "papyrus ink");
    }

    private void translations() {
        check(T.MARKER_ICONS.size() >= 100, "icon catalog");
        check(!T.searchIcons("cave").isEmpty(), "search cave");
        check(!T.searchIcons("дом").isEmpty(), "search дом");
        check(!T.iconName("house").isEmpty(), "icon name");
        check(!T.dimName("minecraft:overworld").isEmpty(), "dimension name");
        String key = T.tr("atlas.button").getString();
        check(!key.isEmpty() && !key.equals("atlasmapextended.atlas.button"), "translation loaded");
    }

    private void viewTransforms() {
        MapView v = new MapView();
        v.setRect(0, 0, 100, 100);
        v.cx = 0;
        v.cz = 0;
        v.zoom = 1;
        check(v.sx(0) == 50f && v.sy(0) == 50f, "view centre");
        check(v.wx(50) == 0 && v.wz(50) == 0, "view world to screen");
        v.zoomAt(50, 50, 2f);
        check(v.zoom == 2f, "view zoomAt");
        check(Math.abs(v.wx(50)) < 0.0001 && Math.abs(v.wz(50)) < 0.0001, "view zoom anchor");
        check(v.contains(50, 50) && !v.contains(-1, -1), "view contains");
    }

    private void configPersistence() {
        ModConfig cfg = ModConfig.get();
        String theme = cfg.theme;
        boolean minimap = cfg.minimapEnabled;
        int size = cfg.minimapSizeIndex;
        int zoom = cfg.minimapZoomIndex;
        int corner = cfg.minimapCorner;
        boolean teleport = cfg.teleport;
        int sort = cfg.markerSort;

        cfg.theme = "stone";
        cfg.minimapEnabled = false;
        cfg.minimapSizeIndex = 99;
        cfg.minimapZoomIndex = -1;
        cfg.minimapCorner = 9;
        cfg.teleport = true;
        cfg.markerSort = 2;
        cfg.save();

        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("atlasmapextended.json");
        ModConfig loaded = Storage.readJson(file, ModConfig.class, null);
        check(loaded != null, "config read back");
        check("stone".equals(loaded.theme), "config theme");
        check(!loaded.minimapEnabled, "config minimap flag");
        check(loaded.minimapSizeIndex >= 0 && loaded.minimapSizeIndex < ModConfig.MINIMAP_SIZES.length, "config size clamped");
        check(loaded.minimapZoomIndex == 0, "config zoom clamped");
        check(loaded.minimapCorner == 1, "config corner clamped");
        check(loaded.teleport, "config teleport");
        check(loaded.markerSort == 2, "config sort");

        cfg.theme = theme;
        cfg.minimapEnabled = minimap;
        cfg.minimapSizeIndex = size;
        cfg.minimapZoomIndex = zoom;
        cfg.minimapCorner = corner;
        cfg.teleport = teleport;
        cfg.markerSort = sort;
        cfg.save();
    }

    private void liveMap() {
        MapSession s = MapManager.live();
        check(s != null, "live session");
        String dim = MapManager.liveDim();
        check(dim != null, "live dimension");
        check(s.meta.id != null && !s.meta.id.isEmpty(), "world meta id");
        MapLayer layer = s.layer(dim);
        check(layer != null, "map layer");
        check(!layer.isEmpty(), "map tiles were scanned");

        MapTile tile = layer.getOrCreate(1000, 1000);
        tile.setPixel(0, 0, 0xFF112233);
        check(tile.getPixel(0, 0) == 0xFF112233, "tile pixel roundtrip");
        check(layer.hasData(256000, 256000), "hasData sees a scanned pixel");
        check(!layer.hasData(256001, 256000), "hasData ignores empty pixels");
        check(!layer.hasData(30_000_000, 30_000_000), "hasData false without a tile");

        long key = MapLayer.pack(-5, 7);
        check((int) (key >> 32) == -5, "pack x");
        check((int) key == 7, "pack z");
    }

    private void regions() {
        Region r = new Region();
        r.name = "Area";
        r.dim = "minecraft:overworld";
        r.pts = new double[]{0, 0, 20, 0, 20, 20, 0, 20};
        check(r.pointCount() == 4, "region point count");
        check(r.contains(10, 10), "region contains centre");
        check(!r.contains(-1, 10), "region outside left");
        check(!r.contains(10, -1), "region outside top");
        Region copy = r.copy();
        check(copy.id.equals(r.id) && copy.contains(5, 5), "region copy");
        r.pts = new double[]{0, 0, 20, 0, 10, 20};
        check(r.contains(10, 10), "triangle contains");
        check(!r.contains(1, 18), "triangle outside");

        Region big = new Region();
        big.name = "Big";
        big.dim = "minecraft:overworld";
        big.pts = new double[]{-100, -100, 100, -100, 100, 100, -100, 100};
        Region small = new Region();
        small.name = "Small";
        small.dim = "minecraft:overworld";
        small.pts = new double[]{-10, -10, 10, -10, 10, 10, -10, 10};
        check(Math.abs(big.area() - 40_000) < 0.001, "region area big");
        check(Math.abs(small.area() - 400) < 0.001, "region area small");
        List<Region> outerFirst = List.of(big, small);
        List<Region> innerFirst = List.of(small, big);
        check(Region.innermostAt(outerFirst, "minecraft:overworld", 0, 0) == small, "innermost: outer created first");
        check(Region.innermostAt(innerFirst, "minecraft:overworld", 0, 0) == small, "innermost: inner created first");
        check(Region.innermostAt(outerFirst, "minecraft:overworld", 90, 90) == big, "innermost: only the big one");
        check(Region.innermostAt(outerFirst, "minecraft:overworld", 500, 500) == null, "innermost: outside");
        check(Region.innermostAt(outerFirst, "minecraft:the_nether", 0, 0) == null, "innermost: other dimension");
        small.hidden = true;
        check(Region.innermostAt(outerFirst, "minecraft:overworld", 0, 0) == big, "innermost: hidden is skipped");

        // the same rule must apply to map clicks: a click inside the nested region picks it
        small.hidden = false;
        MapView view = new MapView();
        view.setRect(0, 0, 100, 100);
        view.cx = 0;
        view.cz = 0;
        view.zoom = 1;
        check(view.regionAt(outerFirst, "minecraft:overworld", 50, 50) == small, "regionAt: nested wins");
        check(view.regionAt(outerFirst, "minecraft:overworld", 90, 50) == big, "regionAt: outer area");
        check(view.regionAt(innerFirst, "minecraft:overworld", 50, 50) == small, "regionAt: nested wins (other order)");

        // a click on a name must select that region even when the point lies inside a smaller one
        Region offset = new Region();
        offset.name = "Offset";
        offset.dim = "minecraft:overworld";
        offset.pts = new double[]{-20, -20, 80, -20, 80, 80, -20, 80};
        MapView labels = new MapView();
        labels.setRect(0, 0, 200, 200);
        labels.cx = 0;
        labels.cz = 0;
        labels.zoom = 1;
        Font font = Minecraft.getInstance().font;
        List<Region> both = List.of(big, offset);
        // world (0, 0) is the centre of the big region and inside the offset one
        check(labels.regionAt(both, "minecraft:overworld", 100, 100) == offset, "label test: area picks the smaller");
        check(labels.regionLabelAt(font, both, "minecraft:overworld", null, 100, 100) == big,
                "label test: the name wins over a nested area");
        check(labels.regionLabelAt(font, both, "minecraft:overworld", null, 130, 130) == offset,
                "label test: the offset name is clickable");
        // zoomed out only the selected region keeps its label, so only that one is clickable
        labels.zoom = 0.4f;
        check(labels.regionLabelAt(font, both, "minecraft:overworld", null, 100, 100) == null,
                "label test: hidden names are not clickable");
        check(labels.regionLabelAt(font, both, "minecraft:overworld", big.id, 100, 100) == big,
                "label test: selected name stays clickable");
    }

    private void persistence() {
        MapSession s = MapManager.live();
        String dim = MapManager.liveDim();

        Marker m = new Marker();
        m.name = "Mechanics";
        m.description = "persist";
        m.icon = "house";
        m.dim = dim;
        m.x = 10;
        m.y = 70;
        m.z = -20;
        s.markers.add(m);
        s.saveMarkers();

        Stroke stroke = new Stroke(Stroke.RECT, 0xFF445566, 3, new double[]{0, 0, 8, 8});
        int strokesBefore = s.strokes(dim).size();
        s.strokes(dim).add(stroke);
        s.saveStrokes();

        Region region = new Region();
        region.name = "Mechanics area";
        region.dim = dim;
        region.color = 0xFF336699;
        region.pts = new double[]{0, 0, 8, 0, 8, 8, 0, 8};
        int regionsBefore = s.regions(dim).size();
        s.regions(dim).add(region);
        s.saveRegions();
        flushIo();

        MapSession reloaded = MapSession.open(s.meta);
        Marker loaded = reloaded.marker(m.id);
        check(loaded != null, "marker reloaded");
        check("Mechanics".equals(loaded.name), "marker name reloaded");
        check(loaded.x == 10 && loaded.y == 70 && loaded.z == -20, "marker coordinates reloaded");
        check("persist".equals(loaded.description), "marker description reloaded");
        check(reloaded.strokes(dim).size() == strokesBefore + 1, "drawing reloaded");
        check(reloaded.regions(dim).size() == regionsBefore + 1, "region reloaded");
        Region loadedRegion = reloaded.regions(dim).stream()
                .filter(x -> x.id.equals(region.id)).findFirst().orElse(null);
        check(loadedRegion != null && "Mechanics area".equals(loadedRegion.name), "region name reloaded");
        check(loadedRegion != null && loadedRegion.contains(4, 4), "region shape reloaded");
        check(reloaded.knownDims().contains(dim), "known dimensions");

        s.markers.removeIf(x -> x.id.equals(m.id));
        s.strokes(dim).removeIf(x -> x == stroke);
        s.regions(dim).removeIf(x -> x.id.equals(region.id));
        s.saveMarkers();
        s.saveStrokes();
        s.saveRegions();
        flushIo();
    }

    private void screenshots() {
        MapSession s = MapManager.live();
        Marker m = new Marker();
        m.name = "Shot";
        m.dim = MapManager.liveDim();
        s.markers.add(m);
        s.saveMarkers();

        Path src = Storage.root().resolve("mechanics_src.png");
        try {
            Files.createDirectories(src.getParent());
            NativeImage img = new NativeImage(2, 2, false);
            img.setPixel(0, 0, 0xFFFF0000);
            img.writeToFile(src);
            img.close();

            String name = ImageCache.importImage(src, s.screenshotsDir()).get(15, TimeUnit.SECONDS);
            check(name != null, "image imported");
            check(Files.exists(s.screenshot(name)), "image file exists");
            m.screenshots.add(name);
            s.saveMarkers();
            flushIo();

            MapSession reloaded = MapSession.open(s.meta);
            Marker loaded = reloaded.marker(m.id);
            check(loaded != null && loaded.screenshots.contains(name), "screenshot persisted");

            m.screenshots.remove(name);
            s.saveMarkers();
            Files.deleteIfExists(s.screenshot(name));
            Files.deleteIfExists(src);
        } catch (Exception e) {
            throw new AssertionError("[AtlasMapExtended mechanics] screenshot pipeline", e);
        } finally {
            s.markers.removeIf(x -> x.id.equals(m.id));
            s.saveMarkers();
            ImageCache.clear();
        }
    }

    private void waypointShare() {
        Marker m = new Marker();
        m.name = "Base Camp";
        m.x = 120;
        m.y = 65;
        m.z = -300;
        m.dim = "minecraft:overworld";
        m.color = 0xFF5500;
        m.icon = "pin";

        String shareStr = WaypointShare.toShareString(m, "Player1", "*");
        check(shareStr.startsWith("[name:Base Camp, x:120, y:65, z:-300"), "waypoint serialize base");
        check(shareStr.contains("sharedBy:Player1"), "waypoint serialize sender");

        WaypointShare.ParsedWaypoint wp = WaypointShare.parse(shareStr);
        check(wp != null, "waypoint parse non-null");
        check(Paper.marker("arrow").getPath().endsWith("arrow_n.png"), "arrow marker alias resolves to arrow_n");
        check(Paper.marker("arrow_n_blue").getPath().endsWith("arrow_n.png"), "arrow_n_blue marker alias resolves to arrow_n");
        check("Base Camp".equals(wp.name), "waypoint parse name");
        check(wp.x == 120 && wp.y == 65 && wp.z == -300, "waypoint parse coords");
        check("minecraft:overworld".equals(wp.dim), "waypoint parse dim");
        check(wp.color == 0xFF5500, "waypoint parse color");
        check("Player1".equals(wp.sharedBy), "waypoint parse sender");

        String xaeroStr = "xaero-waypoint:Outpost:O:-45:70:890:3:false:0:Internal-overworld-waypoints";
        WaypointShare.ParsedWaypoint xaeroWp = WaypointShare.parse(xaeroStr);
        check(xaeroWp != null, "xaero waypoint parse non-null");
        check("Outpost".equals(xaeroWp.name), "xaero waypoint name");
        check(xaeroWp.x == -45 && xaeroWp.y == 70 && xaeroWp.z == 890, "xaero waypoint coords");

        net.minecraft.network.chat.Component original = net.minecraft.network.chat.Component.literal("Found this: " + shareStr + " see you there!");
        net.minecraft.network.chat.Component transformed = WaypointShare.modifyChatMessage(original);
        check(transformed != null, "chat message transformed");
        check(!transformed.equals(original), "chat message modified");

        Region r = new Region();
        r.name = "Northern Outpost";
        r.dim = "minecraft:overworld";
        r.color = 0x00FF88;
        r.description = "Main fortress";
        r.filled = true;
        r.pts = new double[]{100, 100, 200, 100, 200, 200, 100, 200};

        String regionShare = WaypointShare.toRegionShareString(r, "Player1", "*");
        check(regionShare.contains("name:Northern Outpost") && regionShare.contains("type:region"), "region serialize type");
        check(regionShare.contains("pts:100_100;200_100;200_200;100_200"), "region serialize points");

        WaypointShare.ParsedWaypoint rWp = WaypointShare.parse(regionShare);
        check(rWp != null && rWp.isRegion, "region parsed as region");
        check("Northern Outpost".equals(rWp.name), "region parse name");
        check(rWp.pts != null && rWp.pts.length == 8, "region parse points length");
        check(rWp.pts[0] == 100 && rWp.pts[1] == 100, "region parse first point");
        check(rWp.color == 0x00FF88, "region parse color");
        check("Main fortress".equals(rWp.description), "region parse description");

        net.minecraft.network.chat.Component regChat = WaypointShare.modifyChatMessage(net.minecraft.network.chat.Component.literal("Check " + regionShare));
        check(regChat != null && regChat.getString().contains("⬡ Northern Outpost"), "region chat component format");
    }
}
