package org.webtrade.atlasmapextended.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.webtrade.atlasmapextended.client.data.Storage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Translation shortcuts and the marker icon catalog (assets/atlasmapextended/icons.json). */
public final class T {
    /** Marker icons shown in the icon picker, in display order. */
    public static final List<String> MARKER_ICONS = new ArrayList<>();
    /** Old coloured icon ids, mapped to the icon and tint that replace them. */
    public static final Map<String, Alias> ALIASES = new HashMap<>();
    private static final Map<String, String[]> ICON_NAMES = new HashMap<>();
    private static final Map<String, String> LANG_EN = loadLang("en_us");
    private static final Map<String, String> LANG_RU = loadLang("ru_ru");

    private static Map<String, String> loadLang(String code) {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = T.class.getResourceAsStream("/assets/atlasmapextended/lang/" + code + ".json")) {
            if (in == null) {
                return map;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : root.entrySet()) {
                map.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            Storage.LOG.error("Failed to load translations " + code, e);
        }
        return map;
    }

    public record Alias(String icon, int color) {
    }

    static {
        try (InputStream in = T.class.getResourceAsStream("/assets/atlasmapextended/icons.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement ie : root.getAsJsonArray("icons")) {
                MARKER_ICONS.add(readIcon(ie.getAsJsonObject()));
            }
            for (JsonElement ie : root.getAsJsonArray("hidden")) {
                readIcon(ie.getAsJsonObject());
            }
            for (var e : root.getAsJsonObject("aliases").entrySet()) {
                JsonObject a = e.getValue().getAsJsonObject();
                int color = 0xFF000000 | Integer.parseInt(a.get("color").getAsString().substring(1), 16);
                ALIASES.put(e.getKey(), new Alias(a.get("icon").getAsString(), color));
            }
            ALIASES.put("arrow", new Alias("arrow_n", 0));
        } catch (Exception e) {
            Storage.LOG.error("Failed to load the marker icon catalog", e);
            if (MARKER_ICONS.isEmpty()) {
                MARKER_ICONS.add("pin");
            }
        }
    }

    private static String readIcon(JsonObject i) {
        String id = i.get("id").getAsString();
        ICON_NAMES.put(id, new String[]{i.get("ru").getAsString(), i.get("en").getAsString()});
        return id;
    }

    private T() {
    }

    public static MutableComponent tr(String key, Object... args) {
        String full = "atlasmapextended." + key;
        String pattern = (langIndex() == 0 ? LANG_RU : LANG_EN).get(full);
        if (pattern == null) {
            return Component.translatable(full, args);
        }
        if (args.length == 0) {
            return Component.literal(pattern);
        }
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = args[i] instanceof Component c ? c.getString() : args[i];
        }
        try {
            return Component.literal(String.format(pattern, values));
        } catch (RuntimeException e) {
            return Component.literal(pattern);
        }
    }

    public static String s(String key, Object... args) {
        return tr(key, args).getString();
    }

    /** Translations for the selected client language, injected into vanilla so key bindings are translated. */
    public static Map<String, String> clientTranslations(List<String> languageCodes) {
        boolean russian = false;
        for (String code : languageCodes) {
            if (code.startsWith("ru") || code.startsWith("uk") || code.startsWith("be")) {
                russian = true;
                break;
            }
        }
        return russian ? LANG_RU : LANG_EN;
    }

    public static String dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> s("dim.overworld");
            case "minecraft:the_nether" -> s("dim.nether");
            case "minecraft:the_end" -> s("dim.end");
            default -> {
                int i = dim.indexOf(':');
                yield i >= 0 ? dim.substring(i + 1) : dim;
            }
        };
    }

    private static int langIndex() {
        String lang = Minecraft.getInstance().options.languageCode;
        return lang != null && (lang.startsWith("ru") || lang.startsWith("uk") || lang.startsWith("be")) ? 0 : 1;
    }

    public static String iconName(String icon) {
        String[] n = ICON_NAMES.get(icon);
        return n == null ? icon : n[langIndex()];
    }

    /** Icons whose name (in any language) or id contains every word of the query. Russian endings are ignored. */
    public static List<String> searchIcons(String query) {
        List<String> words = new ArrayList<>();
        for (String w : query.toLowerCase(Locale.ROOT).trim().split("\s+")) {
            if (w.length() >= 4 && w.matches(".*[а-яё](ий|ый|ой|ая|яя|ое|ее|ые|ие)$")) {
                w = w.substring(0, w.length() - 2);
            }
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        List<String> out = new ArrayList<>();
        for (String id : MARKER_ICONS) {
            String[] n = ICON_NAMES.get(id);
            String hay = id + " " + (n == null ? "" : n[0].toLowerCase(Locale.ROOT) + " " + n[1].toLowerCase(Locale.ROOT));
            List<String> hayWords = List.of(hay.split("[\\s_-]+"));
            if (words.stream().allMatch(w -> hayWords.stream().anyMatch(h -> h.startsWith(w)))) {
                out.add(id);
            }
        }
        return out;
    }
}
