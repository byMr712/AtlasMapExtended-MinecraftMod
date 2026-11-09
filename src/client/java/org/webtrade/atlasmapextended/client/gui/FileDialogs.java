package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.Minecraft;
import org.webtrade.atlasmapextended.client.data.Storage;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Native "open file" dialog via AWT. It runs on its own thread, so the game does not freeze. */
public final class FileDialogs {
    private static volatile boolean open;

    private FileDialogs() {
    }

    public static void openImages(String title, Path startDir, Consumer<List<Path>> onChosen) {
        if (open) {
            return;
        }
        open = true;
        Thread thread = new Thread(() -> {
            List<Path> paths = new ArrayList<>();
            try {
                if (java.awt.GraphicsEnvironment.isHeadless()) {
                    return;
                }
                FileDialog dialog = new FileDialog((Frame) null, title, FileDialog.LOAD);
                dialog.setMultipleMode(true);
                dialog.setFilenameFilter((dir, name) -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
                });
                Path dir = Files.isDirectory(startDir) ? startDir : startDir.getParent();
                if (dir != null) {
                    dialog.setDirectory(dir.toAbsolutePath().toString());
                }
                dialog.setVisible(true);
                for (File file : dialog.getFiles()) {
                    paths.add(file.toPath());
                }
                dialog.dispose();
            } catch (Throwable e) {
                Storage.LOG.warn("File dialog failed", e);
            } finally {
                if (!paths.isEmpty()) {
                    Minecraft.getInstance().execute(() -> onChosen.accept(paths));
                }
                open = false;
            }
        }, "AtlasMapExtended file dialog");
        thread.setDaemon(true);
        thread.start();
    }
}
