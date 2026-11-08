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
    private static final int VERSION = 4;

    private FramedMapStorage() {
    }

    private static Path file(FramedMapKey key) {
        MapSession session = MapManager.live();
        if (session == null) {
            return null;
        }
        String dim = Storage.dimFolder(key.dimension().identifier().toString());
        return session.dir.resolve("framed3d").resolve(dim + "_" + key.mapId()
                + "_" + key.centerX() + "_" + key.centerZ() + "_" + key.scale() + ".bin");
    }

    /** Old single-map volumes were stored without the centre in the name. */
    private static Path legacyFile(FramedMapKey key) {
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
        boolean any = false;
        for (int i = 0; i < FramedMapVolume.COLUMNS; i++) {
            if (volume.valid[i]) {
                any = true;
                break;
            }
        }
        // Never overwrite a usable volume with an empty pass made while the
        // map's chunks were not loaded.
        if (!any) {
            FramedMapDebug.log("storage map={} not saved: empty volume", key.mapId());
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
                        out.writeByte(copy.waterDepth[i]);
                        out.write(copy.layerColor, i * FramedMapVolume.LAYERS, FramedMapVolume.LAYERS);
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
            path = legacyFile(key);
        }
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
            int valid = 0;
            for (int i = 0; i < FramedMapVolume.COLUMNS; i++) {
                int flags = in.readUnsignedByte();
                volume.valid[i] = (flags & 1) != 0;
                volume.water[i] = (flags & 2) != 0;
                if (volume.valid[i]) {
                    valid++;
                }
                volume.surfaceY[i] = in.readInt();
                volume.rgb[i] = in.readInt();
                volume.waterDepth[i] = in.readByte();
                in.readFully(volume.layerColor, i * FramedMapVolume.LAYERS, FramedMapVolume.LAYERS);
            }
            if (valid == 0) {
                FramedMapDebug.log("storage map={} ignored empty stored volume", key.mapId());
                return null;
            }
            FramedMapDebug.detail("storage map={} loaded valid={}", key.mapId(), valid);
            return volume;
        } catch (IOException e) {
            Storage.LOG.warn("[AtlasMap3D] could not load {}", path, e);
            return null;
        }
    }
}
