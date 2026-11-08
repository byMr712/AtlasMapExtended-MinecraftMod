package org.webtrade.atlasmapextended.client.framed3d;

import org.webtrade.atlasmapextended.client.data.Storage;

/** Shared debug switch for the framed-map diagnostics. */
public final class FramedMapDebug {
    /** The diagnostic build keeps this on; turn it off to silence the per-frame log. */
    public static final boolean ENABLED = true;

    private FramedMapDebug() {
    }

    public static void log(String message, Object... args) {
        if (ENABLED) {
            Storage.LOG.info("[AtlasMap3D] " + message, args);
        }
    }
}
