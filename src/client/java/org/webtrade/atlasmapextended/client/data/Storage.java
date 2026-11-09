package org.webtrade.atlasmapextended.client.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/** File layout and JSON helpers. Everything lives in {@code .minecraft/atlasmapextended/}. */
public final class Storage {
    public static final Logger LOG = LoggerFactory.getLogger("AtlasMapExtended");
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Single background thread for disk writes and image decoding, keeps ordering of writes. */
    public static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AtlasMapExtended-IO");
        t.setDaemon(true);
        return t;
    });

    private Storage() {
    }

    public static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("atlasmapextended");
    }

    public static Path worldsDir() {
        return root().resolve("worlds");
    }

    public static Path worldDir(String id) {
        Path root = worldsDir().toAbsolutePath().normalize();
        Path dir = root.resolve(id).toAbsolutePath().normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            return root.resolve(sanitize(id));
        }
        return dir;
    }

    /** Makes a file name out of any string; non-latin letters are kept, a hash avoids collisions. */
    public static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        boolean changed = false;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                b.append(c);
            } else {
                b.append('_');
                changed = changed || c != ' ';
            }
        }
        String r = b.toString();
        if (changed || r.isEmpty() || r.chars().allMatch(ch -> ch == '_')) {
            r += "_" + Integer.toHexString(s.hashCode());
        }
        return r;
    }

    /** Lower-case ascii only, safe for resource identifiers. */
    public static String asciiId(String s) {
        String r = s.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        return r + "_" + Integer.toHexString(s.hashCode());
    }

    public static String dimFolder(String dim) {
        return sanitize(dim.replace(':', '_'));
    }

    public static <T> T readJson(Path file, Type type, T fallback) {
        if (!Files.isRegularFile(file)) {
            return fallback;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T v = GSON.fromJson(r, type);
            return v == null ? fallback : v;
        } catch (Exception e) {
            LOG.warn("Failed to read {}", file, e);
            return fallback;
        }
    }

    public static void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Failed to write {}", file, e);
        }
    }

    /** Serializes now (on the calling thread) and writes the file on the IO thread. */
    public static void writeJsonAsync(Path file, Object value) {
        String json = GSON.toJson(value);
        IO.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.warn("Failed to write {}", file, e);
            }
        });
    }

    public static final Type MARKER_LIST = new TypeToken<List<Marker>>() {
    }.getType();
    public static final Type STROKE_MAP = new TypeToken<java.util.Map<String, List<Stroke>>>() {
    }.getType();
    public static final Type REGION_MAP = new TypeToken<java.util.Map<String, List<Region>>>() {
    }.getType();

    /** Lists all stored worlds, most recently played first. */
    public static List<WorldMeta> listWorlds() {
        List<WorldMeta> out = new ArrayList<>();
        Path dir = worldsDir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory).forEach(p -> {
                WorldMeta m = readJson(p.resolve("meta.json"), WorldMeta.class, null);
                if (m != null) {
                    m.id = p.getFileName().toString();
                    out.add(m);
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to list worlds", e);
        }
        out.sort(Comparator.comparingLong((WorldMeta m) -> m.lastPlayed).reversed());
        return out;
    }

    public static int countMarkers(String worldId) {
        List<Marker> l = readJson(worldDir(worldId).resolve("markers.json"), MARKER_LIST, List.of());
        return l.size();
    }

    public static void deleteRecursive(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    LOG.warn("Failed to delete {}", f, e);
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to delete {}", p, e);
        }
    }
}
