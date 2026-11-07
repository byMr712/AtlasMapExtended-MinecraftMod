package org.webtrade.atlasmapextended.client.framed3d;

import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Persists the sampled surface volume next to the world data. A map that was
 * once seen in a loaded area can therefore keep its relief when it is later
 * placed in a frame far away (or after a restart), without rescanning chunks.
 */
public final class FramedMapStorage {
    private static final int MAGIC = 0x41334D31; // A3M1
    /** Bumped when the surface sampling changes, so old stored volumes are resampled. */
    private static final int VERSION = 2;

    private FramedMapStorage() {
    }

    private static Path file(FramedMapKey key) {
        MapSession session = MapManager.live();
        if (session == null) {
            return null;
        }
        String dim = Storage.dimFolder(key.dimension().identifier().toString());
        return session.dir.resolve("framed3d").resolve(dim + "_" + key.mapId() + ".bin");
    }

    public static void save(FramedMapKey key, FramedMapVolume volume) {
        Path path = file(key);
        if (path == null) {
            return;
        }
        FramedMapVolume copy = volume.copy();
        Storage.IO.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(
                        Files.newOutputStream(path), new Deflater(1)))) {
                    out.writeInt(MAGIC);
                    out.writeInt(VERSION);
                    out.writeInt(key.centerX());
                    out.writeInt(key.centerZ());
                    out.writeInt(key.scale());
                    out.writeInt(key.minY());
                    out.writeInt(key.height());
                    for (int i = 0; i < FramedMapVolume.COLUMNS; i++) {
                        int flags = (copy.valid[i] ? 1 : 0) | (copy.water[i] ? 2 : 0);
                        out.writeByte(flags);
                        out.writeInt(copy.surfaceY[i]);
                        out.writeInt(copy.rgb[i]);
                    }
                }
            } catch (IOException e) {
                Storage.LOG.warn("[AtlasMap3D] could not save {}", path, e);
            }
        });
    }

    public static FramedMapVolume load(FramedMapKey key) {
        Path path = file(key);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return null;
            }
            int centerX = in.readInt();
            int centerZ = in.readInt();
            int scale = in.readInt();
            int minY = in.readInt();
            int height = in.readInt();
            if (centerX != key.centerX() || centerZ != key.centerZ()
                    || scale != key.scale() || minY != key.minY() || height != key.height()) {
                return null;
            }
            FramedMapVolume volume = new FramedMapVolume();
            for (int i = 0; i < FramedMapVolume.COLUMNS; i++) {
                int flags = in.readUnsignedByte();
                volume.valid[i] = (flags & 1) != 0;
                volume.water[i] = (flags & 2) != 0;
                volume.surfaceY[i] = in.readInt();
                volume.rgb[i] = in.readInt();
            }
            return volume;
        } catch (IOException e) {
            Storage.LOG.warn("[AtlasMap3D] could not load {}", path, e);
            return null;
        }
    }
}
