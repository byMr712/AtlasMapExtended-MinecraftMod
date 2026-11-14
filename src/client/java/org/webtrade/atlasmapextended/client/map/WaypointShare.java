package org.webtrade.atlasmapextended.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.gui.MapScreen;
import org.webtrade.atlasmapextended.client.gui.T;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles marker sharing compatible with VoxelMap (and Xaero's Minimap).
 * Generates and parses VoxelMap waypoint format strings in chat,
 * intercepts waypoint commands, and creates interactive chat components.
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

        boolean isAdd = cmd.startsWith("voxelmapAddWaypoint") || cmd.startsWith("atlasmapAddWaypoint");
        boolean isView = cmd.startsWith("atlasmapViewWaypoint") || cmd.startsWith("voxelmapHighlightWaypoint") || cmd.startsWith("atlasmapHighlightWaypoint");

        if (!isAdd && !isView) {
            return false;
        }

        int space = cmd.indexOf(' ');
        String rawData = space != -1 ? cmd.substring(space + 1).trim() : "";
        ParsedWaypoint wp = parse(rawData);
        if (wp == null) return true;

        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (isAdd) {
                addWaypoint(wp);
            } else {
                viewWaypoint(wp);
            }
        });
        return true;
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
            mc.player.sendSystemMessage(Component.literal("§6[Atlas] §r" + msg));
        }
    }
}
