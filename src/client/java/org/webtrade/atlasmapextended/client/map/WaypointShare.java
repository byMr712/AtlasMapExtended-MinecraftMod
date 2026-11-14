package org.webtrade.atlasmapextended.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.gui.MapScreen;
import org.webtrade.atlasmapextended.client.gui.T;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles marker and region sharing compatible with VoxelMap (and Xaero's Minimap).
 * Generates and parses VoxelMap waypoint and region format strings in chat,
 * intercepts waypoint/region commands, and creates interactive chat components.
 */
public final class WaypointShare {

    public static final Pattern VOXEL_PATTERN = Pattern.compile(
            "\\[(\\w+\\s*:\\s*[-#]?[^\\[\\]]+)(,\\s*\\w+\\s*:\\s*[-#]?[^\\[\\]]+)+\\]",
            Pattern.CASE_INSENSITIVE
    );

    public static final Pattern XAERO_PATTERN = Pattern.compile(
            "xaero-waypoint:(.*?):([^:]*):(-?\\d+):(-?\\d+):(-?\\d+):(\\d+):(true|false):(\\d+)((?::[^:\\s]*)*)"
    );

    public static final class ParsedWaypoint {
        public String name = "";
        public int x;
        public int y = 64;
        public int z;
        public String dim = "minecraft:overworld";
        public int color;
        public String icon = "pin";
        public String sharedBy;
        public String sharedWith;

        // Region support
        public boolean isRegion;
        public boolean filled = true;
        public String description = "";
        public double[] pts;
    }

    private WaypointShare() {
    }

    public static String toShareString(Marker m, String senderName, String targetPlayer) {
        StringBuilder sb = new StringBuilder();
        String safeName = (m.name == null || m.name.isBlank()) ? "Marker" : m.name.replace(',', ' ').replace(':', ' ').trim();
        sb.append("[name:").append(safeName);
        sb.append(", x:").append(m.x);
        sb.append(", y:").append(m.y);
        sb.append(", z:").append(m.z);
        sb.append(", dim:").append(m.dim != null && !m.dim.isBlank() ? m.dim : "minecraft:overworld");
        if (m.color != 0) {
            sb.append(String.format(Locale.ROOT, ", color:#%06X", m.color & 0xFFFFFF));
        }
        if (m.icon != null && !m.icon.isBlank() && !m.icon.equals("pin")) {
            sb.append(", icon:").append(m.icon);
        }
        if (senderName != null && !senderName.isBlank()) {
            sb.append(", sharedBy:").append(senderName);
        }
        sb.append(", sharedWith:").append(targetPlayer != null && !targetPlayer.isBlank() ? targetPlayer : "*");
        sb.append("]");
        return sb.toString();
    }

    public static String toRegionShareString(Region r, String senderName, String targetPlayer) {
        StringBuilder sb = new StringBuilder();
        String safeName = (r.name == null || r.name.isBlank()) ? "Region" : r.name.replace(',', ' ').replace(':', ' ').trim();
        int cx = 0;
        int cz = 0;
        int n = r.pointCount();
        if (n > 0) {
            double sx = 0;
            double sz = 0;
            for (int i = 0; i < n; i++) {
                sx += r.pts[i * 2];
                sz += r.pts[i * 2 + 1];
            }
            cx = (int) Math.round(sx / n);
            cz = (int) Math.round(sz / n);
        }
        sb.append("[name:").append(safeName);
        sb.append(", type:region");
        sb.append(", x:").append(cx);
        sb.append(", y:64");
        sb.append(", z:").append(cz);
        sb.append(", dim:").append(r.dim != null && !r.dim.isBlank() ? r.dim : "minecraft:overworld");
        if (r.color != 0) {
            sb.append(String.format(Locale.ROOT, ", color:#%06X", r.color & 0xFFFFFF));
        }
        sb.append(", fill:").append(r.filled ? "1" : "0");
        if (r.description != null && !r.description.isBlank()) {
            String safeDesc = r.description.replace(',', ' ').replace(':', ' ').trim();
            if (safeDesc.length() > 32) safeDesc = safeDesc.substring(0, 32);
            sb.append(", desc:").append(safeDesc);
        }
        if (r.pts != null && r.pts.length >= 6) {
            double[] pts = simplifyPointsForShare(r.pts, 12);
            sb.append(", pts:").append(encodePoints(pts));
        }
        if (senderName != null && !senderName.isBlank()) {
            sb.append(", sharedBy:").append(senderName);
        }
        sb.append(", sharedWith:").append(targetPlayer != null && !targetPlayer.isBlank() ? targetPlayer : "*");
        sb.append("]");
        return sb.toString();
    }

    public static double[] simplifyPointsForShare(double[] pts, int maxPoints) {
        int n = pts.length / 2;
        if (n <= maxPoints) return pts;
        double[] out = new double[maxPoints * 2];
        for (int i = 0; i < maxPoints; i++) {
            int src = (int) Math.round((double) i * (n - 1) / (maxPoints - 1));
            out[i * 2] = pts[src * 2];
            out[i * 2 + 1] = pts[src * 2 + 1];
        }
        return out;
    }

    public static String encodePoints(double[] pts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pts.length; i += 2) {
            if (i > 0) sb.append(';');
            sb.append((int) Math.round(pts[i])).append('_').append((int) Math.round(pts[i + 1]));
        }
        return sb.toString();
    }

    public static double[] parsePoints(String s) {
        if (s == null || s.isBlank()) return new double[0];
        try {
            String[] pairs = s.split(";");
            double[] pts = new double[pairs.length * 2];
            for (int i = 0; i < pairs.length; i++) {
                String[] xy = pairs[i].split("_");
                pts[i * 2] = Double.parseDouble(xy[0]);
                pts[i * 2 + 1] = Double.parseDouble(xy[1]);
            }
            return pts;
        } catch (Exception e) {
            return new double[0];
        }
    }

    private static double[] defaultBox(double cx, double cz) {
        return new double[]{cx - 16, cz - 16, cx + 16, cz - 16, cx + 16, cz + 16, cx - 16, cz + 16};
    }

    public static ParsedWaypoint parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("xaero-waypoint:")) {
            return parseXaero(s);
        }
        return parseVoxel(s);
    }

    private static ParsedWaypoint parseVoxel(String s) {
        ParsedWaypoint wp = new ParsedWaypoint();
        boolean hasX = false;
        boolean hasZ = false;
        String[] parts = s.split(",");
        for (String part : parts) {
            int colon = part.indexOf(':');
            if (colon == -1) continue;
            String k = part.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String v = part.substring(colon + 1).trim();
            try {
                switch (k) {
                    case "name" -> wp.name = v;
                    case "x" -> { wp.x = Integer.parseInt(v); hasX = true; }
                    case "y" -> wp.y = Integer.parseInt(v);
                    case "z" -> { wp.z = Integer.parseInt(v); hasZ = true; }
                    case "dim", "dimension", "dimensions" -> wp.dim = normalizeDim(v);
                    case "color" -> wp.color = Integer.decode(v) & 0xFFFFFF;
                    case "icon", "suffix" -> wp.icon = v;
                    case "sharedby" -> wp.sharedBy = v;
                    case "sharedwith" -> wp.sharedWith = v;
                    case "type" -> {
                        if ("region".equalsIgnoreCase(v)) wp.isRegion = true;
                    }
                    case "desc", "description" -> wp.description = v;
                    case "fill", "filled" -> wp.filled = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                    case "pts", "points" -> {
                        wp.isRegion = true;
                        wp.pts = parsePoints(v);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (!hasX || !hasZ) {
            return null;
        }
        return wp;
    }

    private static ParsedWaypoint parseXaero(String s) {
        Matcher m = XAERO_PATTERN.matcher(s);
        if (!m.find()) return null;
        try {
            ParsedWaypoint wp = new ParsedWaypoint();
            wp.name = m.group(1).trim();
            wp.x = Integer.parseInt(m.group(3));
            wp.y = Integer.parseInt(m.group(4));
            wp.z = Integer.parseInt(m.group(5));
            String extra = m.group(9);
            if (extra != null && !extra.isBlank()) {
                wp.dim = normalizeXaeroDim(extra);
            }
            return wp;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String normalizeDim(String raw) {
        if (raw == null || raw.isBlank()) {
            return "minecraft:overworld";
        }
        String d = raw.trim().toLowerCase(Locale.ROOT);
        if (d.contains(":")) {
            return d;
        }
        if (d.contains("nether") || d.equals("-1")) return "minecraft:the_nether";
        if (d.contains("end") || d.equals("1")) return "minecraft:the_end";
        return "minecraft:overworld";
    }

    private static String normalizeXaeroDim(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("the_nether") || s.contains("nether")) return "minecraft:the_nether";
        if (s.contains("the_end") || s.contains("end")) return "minecraft:the_end";
        if (s.contains("overworld")) return "minecraft:overworld";
        return "minecraft:overworld";
    }

    public static Component modifyChatMessage(Component message) {
        if (message == null) return null;
        String text = message.getString();
        if (!text.contains("[") && !text.contains("xaero-waypoint:")) {
            return message;
        }
        Matcher vm = VOXEL_PATTERN.matcher(text);
        Matcher xm = XAERO_PATTERN.matcher(text);
        boolean hasVm = vm.find();
        boolean hasXm = !hasVm && xm.find();
        if (!hasVm && !hasXm) {
            return message;
        }

        MutableComponent result = Component.empty();
        int lastIdx = 0;
        Matcher m = hasVm ? vm : xm;
        m.reset();
        while (m.find()) {
            if (m.start() > lastIdx) {
                result.append(Component.literal(text.substring(lastIdx, m.start())));
            }
            String matched = m.group();
            ParsedWaypoint wp = parse(matched);
            if (wp != null) {
                result.append(buildWaypointComponent(wp, matched));
            } else {
                result.append(Component.literal(matched));
            }
            lastIdx = m.end();
        }
        if (lastIdx < text.length()) {
            result.append(Component.literal(text.substring(lastIdx)));
        }
        return result;
    }

    private static Component buildWaypointComponent(ParsedWaypoint wp, String raw) {
        MutableComponent comp = Component.empty();

        if (wp.isRegion) {
            String label = wp.name != null && !wp.name.isBlank() ? wp.name : T.s("region.title");
            String coords = wp.x + ", " + wp.z;
            MutableComponent tag = Component.literal("⬡ " + label + " [" + coords + "]");
            int tagColor = wp.color != 0 ? wp.color : 0xFFE08A2E;
            StringBuilder hb = new StringBuilder(label).append("\n").append(coords).append("\n").append(wp.dim);
            if (wp.pts != null && wp.pts.length >= 6) {
                hb.append("\n").append(T.s("region.points", wp.pts.length / 2));
            }
            if (wp.description != null && !wp.description.isBlank()) {
                hb.append("\n").append(wp.description);
            }
            if (wp.sharedBy != null && !wp.sharedBy.isBlank()) {
                hb.append("\n").append(T.s("marker.share.by", wp.sharedBy));
            }
            tag.withStyle(style -> style
                    .withColor(tagColor)
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(hb.toString())))
                    .withClickEvent(new ClickEvent.RunCommand("/atlasmapViewRegion " + raw))
            );
            comp.append(tag);
            comp.append(Component.literal(" "));

            MutableComponent addBtn = Component.literal("[" + T.s("marker.share.add") + "]");
            addBtn.withStyle(style -> style
                    .withColor(0x55FF55)
                    .withHoverEvent(new HoverEvent.ShowText(T.tr("region.share.add_tip")))
                    .withClickEvent(new ClickEvent.RunCommand("/atlasmapAddRegion " + raw))
            );
            comp.append(addBtn);
            comp.append(Component.literal(" "));

            MutableComponent viewBtn = Component.literal("[" + T.s("marker.share.view") + "]");
            viewBtn.withStyle(style -> style
                    .withColor(0x55FFFF)
                    .withHoverEvent(new HoverEvent.ShowText(T.tr("region.share.view_tip")))
                    .withClickEvent(new ClickEvent.RunCommand("/atlasmapViewRegion " + raw))
            );
            comp.append(viewBtn);

            return comp;
        }

        String label = wp.name != null && !wp.name.isBlank() ? wp.name : T.s("marker.unnamed");
        String coords = wp.x + ", " + wp.y + ", " + wp.z;

        MutableComponent tag = Component.literal("📍 " + label + " [" + coords + "]");
        int tagColor = wp.color != 0 ? wp.color : 0xFFD700;
        String baseHover = label + "\n" + coords + "\n" + wp.dim;
        String hoverText = (wp.sharedBy != null && !wp.sharedBy.isBlank())
                ? baseHover + "\n" + T.s("marker.share.by", wp.sharedBy)
                : baseHover;
        tag.withStyle(style -> style
                .withColor(tagColor)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText)))
                .withClickEvent(new ClickEvent.RunCommand("/atlasmapViewWaypoint " + raw))
        );
        comp.append(tag);
        comp.append(Component.literal(" "));

        MutableComponent addBtn = Component.literal("[" + T.s("marker.share.add") + "]");
        addBtn.withStyle(style -> style
                .withColor(0x55FF55)
                .withHoverEvent(new HoverEvent.ShowText(T.tr("marker.share.add_tip")))
                .withClickEvent(new ClickEvent.RunCommand("/voxelmapAddWaypoint " + raw))
        );
        comp.append(addBtn);
        comp.append(Component.literal(" "));

        MutableComponent viewBtn = Component.literal("[" + T.s("marker.share.view") + "]");
        viewBtn.withStyle(style -> style
                .withColor(0x55FFFF)
                .withHoverEvent(new HoverEvent.ShowText(T.tr("marker.share.view_tip")))
                .withClickEvent(new ClickEvent.RunCommand("/atlasmapViewWaypoint " + raw))
        );
        comp.append(viewBtn);

        return comp;
    }

    public static boolean handleCommand(String command) {
        if (command == null) return false;
        String cmd = command.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);

        boolean isAddRegion = cmd.startsWith("atlasmapAddRegion");
        boolean isViewRegion = cmd.startsWith("atlasmapViewRegion");
        boolean isAddMarker = cmd.startsWith("voxelmapAddWaypoint") || cmd.startsWith("atlasmapAddWaypoint");
        boolean isViewMarker = cmd.startsWith("atlasmapViewWaypoint") || cmd.startsWith("voxelmapHighlightWaypoint") || cmd.startsWith("atlasmapHighlightWaypoint");

        if (!isAddRegion && !isViewRegion && !isAddMarker && !isViewMarker) {
            return false;
        }

        int space = cmd.indexOf(' ');
        String rawData = space != -1 ? cmd.substring(space + 1).trim() : "";
        ParsedWaypoint wp = parse(rawData);
        if (wp == null) return true;

        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (isAddRegion || (isAddMarker && wp.isRegion)) {
                addRegion(wp);
            } else if (isViewRegion || (isViewMarker && wp.isRegion)) {
                viewRegion(wp);
            } else if (isAddMarker) {
                addWaypoint(wp);
            } else {
                viewWaypoint(wp);
            }
        });
        return true;
    }

    public static void addRegion(ParsedWaypoint wp) {
        MapSession session = MapManager.live();
        if (session == null) return;

        String dim = wp.dim != null && !wp.dim.isBlank() ? wp.dim : (MapManager.liveDim() != null ? MapManager.liveDim() : "minecraft:overworld");
        List<Region> regions = session.regions(dim);

        Region existing = null;
        for (Region r : regions) {
            if (r.dim.equals(dim) && r.name.equalsIgnoreCase(wp.name)) {
                existing = r;
                break;
            }
        }

        double[] pts = (wp.pts != null && wp.pts.length >= 6) ? wp.pts : defaultBox(wp.x, wp.z);

        if (existing != null) {
            if (wp.color != 0) existing.color = wp.color;
            existing.filled = wp.filled;
            existing.pts = pts;
            if (wp.description != null && !wp.description.isBlank()) existing.description = wp.description;
            session.saveRegions();
            notifyPlayer(T.s("region.share.updated", existing.name));
        } else {
            Region r = new Region();
            r.name = wp.name != null && !wp.name.isBlank() ? wp.name : T.s("region.title");
            r.color = wp.color != 0 ? wp.color : 0xFFE08A2E;
            r.dim = dim;
            r.filled = wp.filled;
            r.description = wp.description != null ? wp.description : "";
            r.pts = pts;
            regions.add(r);
            session.saveRegions();
            notifyPlayer(T.s("region.share.added", r.name));
        }
    }

    public static void viewRegion(ParsedWaypoint wp) {
        Minecraft mc = Minecraft.getInstance();
        MapSession session = MapManager.live();
        if (session == null) return;

        String dim = wp.dim != null && !wp.dim.isBlank() ? wp.dim : (MapManager.liveDim() != null ? MapManager.liveDim() : "minecraft:overworld");
        List<Region> regions = session.regions(dim);

        Region target = null;
        for (Region r : regions) {
            if (r.dim.equals(dim) && r.name.equalsIgnoreCase(wp.name)) {
                target = r;
                break;
            }
        }

        if (target == null) {
            target = new Region();
            target.name = wp.name != null && !wp.name.isBlank() ? wp.name : T.s("region.title");
            target.dim = dim;
            target.color = wp.color != 0 ? wp.color : 0xFFE08A2E;
            target.filled = wp.filled;
            target.description = wp.description != null ? wp.description : "";
            target.pts = (wp.pts != null && wp.pts.length >= 6) ? wp.pts : defaultBox(wp.x, wp.z);
        }

        MapScreen screen = MapScreen.openLive(mc);
        if (screen != null) {
            screen.showDimension(dim);
            screen.showRegion(target);
            notifyPlayer(T.s("region.share.showing", target.name));
        }
    }

    public static void addWaypoint(ParsedWaypoint wp) {
        MapSession session = MapManager.live();
        if (session == null) return;

        String dim = wp.dim != null && !wp.dim.isBlank() ? wp.dim : (MapManager.liveDim() != null ? MapManager.liveDim() : "minecraft:overworld");

        Marker existing = null;
        for (Marker m : session.markers) {
            if (m.dim.equals(dim) && m.x == wp.x && m.z == wp.z) {
                existing = m;
                break;
            }
        }

        if (existing != null) {
            if (wp.name != null && !wp.name.isBlank()) existing.name = wp.name;
            if (wp.color != 0) existing.color = wp.color;
            existing.y = wp.y;
            session.saveMarkers();
            notifyPlayer(T.s("marker.share.updated", existing.name));
        } else {
            Marker m = new Marker();
            m.name = wp.name != null && !wp.name.isBlank() ? wp.name : T.s("marker.unnamed");
            m.x = wp.x;
            m.y = wp.y;
            m.z = wp.z;
            m.dim = dim;
            m.color = wp.color;
            if (wp.icon != null && !wp.icon.isBlank()) {
                m.icon = wp.icon;
            }
            session.markers.add(m);
            session.saveMarkers();
            notifyPlayer(T.s("marker.share.added", m.name));
        }
    }

    public static void viewWaypoint(ParsedWaypoint wp) {
        Minecraft mc = Minecraft.getInstance();
        MapSession session = MapManager.live();
        if (session == null) return;

        String dim = wp.dim != null && !wp.dim.isBlank() ? wp.dim : (MapManager.liveDim() != null ? MapManager.liveDim() : "minecraft:overworld");

        Marker target = null;
        for (Marker m : session.markers) {
            if (m.dim.equals(dim) && m.x == wp.x && m.z == wp.z) {
                target = m;
                break;
            }
        }
        if (target == null) {
            target = new Marker();
            target.name = wp.name != null && !wp.name.isBlank() ? wp.name : T.s("marker.unnamed");
            target.x = wp.x;
            target.y = wp.y;
            target.z = wp.z;
            target.dim = dim;
            target.color = wp.color;
            if (wp.icon != null && !wp.icon.isBlank()) {
                target.icon = wp.icon;
            }
        }

        MapScreen screen = MapScreen.openLive(mc);
        if (screen != null) {
            screen.showDimension(dim);
            screen.showMarker(target);
        }
    }

    public static void notifyPlayer(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§6[AtlasMapExtended] §r" + msg));
        }
    }
}