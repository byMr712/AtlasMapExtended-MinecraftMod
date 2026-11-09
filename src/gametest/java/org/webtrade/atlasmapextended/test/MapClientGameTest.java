package org.webtrade.atlasmapextended.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import com.mojang.blaze3d.platform.InputConstants;
import org.webtrade.atlasmapextended.client.ModKeys;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.client.data.WorldMeta;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.gui.MapScreen;
import org.webtrade.atlasmapextended.client.gui.Paper;
import org.webtrade.atlasmapextended.client.gui.SettingsScreen;
import org.webtrade.atlasmapextended.client.gui.Theme;
import org.webtrade.atlasmapextended.client.gui.WorldsScreen;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.MapSession;

import java.util.List;

/** Drives the mod through its main features and takes screenshots for visual review. */
public class MapClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        TestInput input = ctx.getInput();
        input.resizeWindow(1600, 900);
        ctx.runOnClient(mc -> {
            mc.options.languageCode = "ru_ru";
            mc.getLanguageManager().setSelected("ru_ru");
            mc.options.guiScale().set(3);
            mc.resizeGui();
        });
        ctx.runOnClient(mc -> mc.reloadResourcePacks());
        ctx.waitTicks(60);
        ctx.takeScreenshot("01_title");

        try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setAllowCommands(true)).create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("difficulty peaceful");
            sp.getServer().runCommand("gamemode creative @a");
            ctx.waitTicks(400);
            ctx.takeScreenshot("02_ingame_minimap");

            ctx.runOnClient(mc -> {
                MapSession s = MapManager.live();
                if (s == null) {
                    throw new AssertionError("no live map session");
                }
                int px = mc.player.getBlockX();
                int pz = mc.player.getBlockZ();
                String[] icons = {"house", "castle", "cave", "portal", "chest", "star", "farm", "skull"};
                String[] names = {"Мой дом", "Крепость", "Пещера с алмазами", "Портал в Незер", "Клад", "Точка сбора", "Ферма пшеницы", "Опасно: крипер"};
                for (int i = 0; i < icons.length; i++) {
                    Marker m = new Marker();
                    m.icon = icons[i];
                    m.name = names[i];
                    m.x = px + (int) (Math.cos(i * 0.8) * (20 + i * 9));
                    m.z = pz + (int) (Math.sin(i * 0.8) * (20 + i * 9));
                    m.y = 70;
                    m.dim = mc.level.dimension().location().toString();
                    m.description = "Описание метки номер " + (i + 1) + ". Здесь можно писать заметки о месте, ресурсах и планах.";
                    m.created = System.currentTimeMillis() - i * 1000L;
                    s.markers.add(m);
                }
            });

            input.pressKey(ModKeys.OPEN_MAP);
            ctx.waitForScreen(MapScreen.class);
            ctx.waitTicks(10);
            ctx.takeScreenshot("03_map");
            // the biome plaque appears after the cursor rests over explored terrain
            moveGui(ctx, input, mc -> new int[]{mc.screen.width / 2, mc.screen.height / 2});
            ctx.waitTicks(70);
            ctx.runOnClient(mc -> {
                String biome = ((MapScreen) mc.screen).hoveredBiome();
                if (biome == null || biome.isEmpty()) {
                    throw new AssertionError("biome hint did not appear over explored terrain");
                }
                System.out.println("[AtlasMapExtended test] hovered biome: " + biome);
            });
            ctx.takeScreenshot("03b_biome_hint");

            // and stays hidden over unscanned (fog) terrain
            ctx.runOnClient(mc -> ((MapScreen) mc.screen).setView(30_000_000, 30_000_000, 1f));
            moveGui(ctx, input, mc -> new int[]{mc.screen.width / 2, mc.screen.height / 2});
            ctx.waitTicks(70);
            ctx.runOnClient(mc -> {
                if (((MapScreen) mc.screen).hoveredBiome() != null) {
                    throw new AssertionError("biome hint appeared over unscanned map");
                }
            });
            ctx.takeScreenshot("03c_biome_hidden");
            ctx.runOnClient(mc -> ((MapScreen) mc.screen).setView(mc.player.getX(), mc.player.getZ(), 2f));
            ctx.waitTicks(5);


            input.scroll(3);
            ctx.waitTicks(5);
            ctx.takeScreenshot("04_map_zoomed_in");
            input.scroll(-7);
            ctx.waitTicks(5);
            ctx.takeScreenshot("05_map_zoomed_out");
            input.scroll(4);

            input.pressKey(ModKeys.MARKER_LIST);
            ctx.waitTicks(5);
            ctx.takeScreenshot("06_marker_list");

            ctx.runOnClient(mc -> ((MapScreen) mc.screen).showMarker(MapManager.live().markers.get(2)));
            ctx.waitTicks(5);
            ctx.takeScreenshot("07_marker_view");

            ctx.runOnClient(mc -> ((MapScreen) mc.screen).editMarker(MapManager.live().markers.get(2)));
            ctx.waitTicks(5);
            input.typeChars(" (изменено)");
            ctx.waitTicks(3);
            ctx.takeScreenshot("08_marker_edit");

            // open the icon picker by clicking the icon selector in the right panel
            clickGui(ctx, input, mc -> {
                int w = mc.screen.width;
                int pw = Math.max(150, Math.min(190, w / 3));
                return new int[]{w - pw - 6 + 30, 6 + 22 + 10 + 20 + 11};
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("09_icon_picker");
            input.scroll(-12);
            ctx.waitTicks(3);
            ctx.takeScreenshot("09a_icon_picker_scrolled");
            input.typeChars("дом");
            ctx.waitTicks(3);
            ctx.takeScreenshot("09b_icon_search");
            // right click on the first found icon opens the colour chooser
            clickGui(ctx, input, MapClientGameTest::firstIconCell, InputConstants.MOUSE_BUTTON_RIGHT);
            ctx.waitTicks(3);
            moveGui(ctx, input, mc -> swatch(mc, 33));
            ctx.waitTicks(2);
            ctx.takeScreenshot("09c_color_picker");
            clickGui(ctx, input, mc -> swatch(mc, 33));
            ctx.waitTicks(3);
            ctx.takeScreenshot("09d_recoloured_icon");
            ctx.runOnClient(mc -> {
                Marker w = MapManager.live().markers.get(2);
                if (w.color != 0) {
                    throw new AssertionError("colour must apply to the edited copy only");
                }
            });

            // "+" tile opens the screenshot picker
            clickGui(ctx, input, mc -> {
                int w = mc.screen.width;
                int h = mc.screen.height;
                int pw = Math.max(150, Math.min(190, w / 3));
                int ph = h - 12;
                int descH = Math.max(40, Math.min(90, (int) (ph * 0.22)));
                int y = 6 + 22 + 10 + 20 + 26 + 10 + 21 + 10 + descH + 6 + 11;
                return new int[]{w - pw - 6 + 9 + 20, y + 10};
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("10_screenshot_picker");
            // attach the first screenshot and press "Done"
            clickGui(ctx, input, mc -> {
                int w = mc.screen.width;
                int ow = Math.min(w - 30, 440);
                return new int[]{(w - ow) / 2 + 30, 15 + 22 + 20 + 4 + 15};
            });
            ctx.waitTicks(30);
            clickGui(ctx, input, mc -> {
                int w = mc.screen.width;
                int h = mc.screen.height;
                int ow = Math.min(w - 30, 440);
                return new int[]{(w - ow) / 2 + ow - 60, 15 + (h - 30) - 22 + 8};
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("10b_edit_with_shot");
            // open the attached screenshot (first tile) and draw on it
            clickGui(ctx, input, mc -> {
                int w = mc.screen.width;
                int h = mc.screen.height;
                int pw = Math.max(150, Math.min(190, w / 3));
                int ph = h - 12;
                int descH = Math.max(40, Math.min(90, (int) (ph * 0.22)));
                int y = 6 + 22 + 10 + 20 + 26 + 10 + 21 + 10 + descH + 6 + 11;
                return new int[]{w - pw - 6 + 9 + 20, y + 10};
            });
            ctx.waitTicks(30);
            clickGui(ctx, input, mc -> new int[]{mc.screen.width - 46 + 9, 6 + 9});
            ctx.waitTicks(5);
            clickGui(ctx, input, mc -> new int[]{(mc.screen.width - 235) / 2 + 8 + 2 * 11 + 4, 6 + 28 + 11 + 4});
            drag(ctx, input, mc -> {
                int cx = mc.screen.width / 2;
                int cy = mc.screen.height / 2 + 30;
                int[] pts = new int[40];
                for (int i = 0; i < 20; i++) {
                    pts[i * 2] = cx + (int) (Math.cos(i * 0.33) * 40);
                    pts[i * 2 + 1] = cy + (int) (Math.sin(i * 0.33) * 25);
                }
                return pts;
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("10c_draw_on_screenshot");
            input.pressKey(InputConstants.KEY_ESCAPE);
            input.pressKey(InputConstants.KEY_ESCAPE);
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> ((MapScreen) mc.screen).showMarker(MapManager.live().markers.get(2)));
            ctx.waitTicks(10);
            ctx.takeScreenshot("10d_view_with_drawn_shot");

            // ruler
            input.pressKey(ModKeys.RULER);
            clickGui(ctx, input, mc -> new int[]{mc.screen.width / 2 - 80, mc.screen.height / 2 + 40});
            clickGui(ctx, input, mc -> new int[]{mc.screen.width / 2 - 10, mc.screen.height / 2 - 30});
            clickGui(ctx, input, mc -> new int[]{mc.screen.width / 2 + 60, mc.screen.height / 2 + 10});
            moveGui(ctx, input, mc -> new int[]{mc.screen.width / 2 + 90, mc.screen.height / 2 + 60});
            ctx.waitTicks(3);
            ctx.takeScreenshot("11_ruler");
            input.pressKey(ModKeys.RULER);

            // drawing editor: freehand circle-ish stroke and a rectangle
            input.pressKey(ModKeys.EDITOR);
            ctx.waitTicks(2);
            drag(ctx, input, mc -> {
                int cx = mc.screen.width / 2 - 40;
                int cy = mc.screen.height / 2;
                int[] pts = new int[40];
                for (int i = 0; i < 20; i++) {
                    pts[i * 2] = cx + (int) (Math.cos(i * 0.33) * 30);
                    pts[i * 2 + 1] = cy + (int) (Math.sin(i * 0.33) * 22);
                }
                return pts;
            });
            ctx.waitTicks(2);
            ctx.takeScreenshot("12_drawing");
            input.pressKey(ModKeys.EDITOR);
            ctx.waitTicks(2);
            ctx.takeScreenshot("13_drawing_done");

            // settings: gear in the toolbar, then pick a design by clicking its tile
            clickGui(ctx, input, mc -> new int[]{16, 6 + 10 * 23 + 10});
            ctx.waitForScreen(SettingsScreen.class);
            ctx.waitTicks(5);
            ctx.takeScreenshot("13a_settings");
            clickGui(ctx, input, MapClientGameTest::chooseDesignButton);
            ctx.waitTicks(3);
            clickGui(ctx, input, mc -> popupThemeTile(mc, 4));
            ctx.waitTicks(3);
            ctx.takeScreenshot("13b_settings_modern_dark");
            ctx.runOnClient(mc -> {
                if (!ModConfig.get().theme.equals("modern_dark")) {
                    throw new AssertionError("theme tile click did not apply, theme=" + ModConfig.get().theme);
                }
                ModConfig.get().teleport = true;
            });
            input.pressKey(InputConstants.KEY_ESCAPE);
            ctx.waitForScreen(MapScreen.class);
            for (Theme theme : Theme.ALL) {
                ctx.runOnClient(mc -> {
                    Paper.apply(theme);
                    MapScreen ms = (MapScreen) mc.screen;
                    ms.openMarkerList();
                    ms.showMarker(MapManager.live().markers.get(0));
                    mc.resizeGui();
                });
                ctx.waitTicks(5);
                ctx.takeScreenshot("theme_" + theme.id());
            }
            ctx.runOnClient(mc -> {
                Paper.apply(Theme.PAPYRUS);
                ModConfig.get().theme = "papyrus";
                ModConfig.get().save();
                mc.resizeGui();
            });
            // teleport button at the bottom of the marker card
            clickGui(ctx, input, mc -> {
                int w = mc.screen.width;
                int h = mc.screen.height;
                int pw = Math.max(150, Math.min(190, w / 3));
                int buttonsY = 6 + (h - 12) - 8 - 16 * 3 - 4 * 2;
                return new int[]{w - pw - 6 + pw / 2, buttonsY + 40 + 8};
            });
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> {
                Marker m = MapManager.live().markers.get(0);
                double d = Math.hypot(mc.player.getX() - m.x - 0.5, mc.player.getZ() - m.z - 0.5);
                System.out.println("[AtlasMapExtended test] teleport distance to marker: " + d + ", screen=" + mc.screen);
                if (d > 2) {
                    throw new AssertionError("teleport did not move the player, distance " + d);
                }
            });
            ctx.takeScreenshot("13c_after_teleport");
            input.pressKey(ModKeys.OPEN_MAP);
            ctx.waitForScreen(MapScreen.class);

            input.pressKey(InputConstants.KEY_ESCAPE);
            ctx.waitTicks(5);
            ctx.takeScreenshot("14_back_ingame");
        }

        ctx.waitForScreen(TitleScreen.class);
        ctx.setScreen(() -> new WorldsScreen(new TitleScreen()));
        ctx.waitTicks(5);
        ctx.takeScreenshot("15_atlas");

        ctx.runOnClient(mc -> {
            List<WorldMeta> worlds = Storage.listWorlds();
            if (worlds.isEmpty()) {
                throw new AssertionError("world was not stored");
            }
            WorldMeta meta = worlds.get(0);
            MapSession s = MapManager.openForViewing(meta);
            if (s.markers.size() != 8) {
                throw new AssertionError("expected 8 markers, got " + s.markers.size());
            }
            mc.setScreen(new MapScreen(new WorldsScreen(new TitleScreen()), s, true));
        });
        ctx.waitTicks(20);
        ctx.takeScreenshot("16_offline_map");
        ctx.setScreen(TitleScreen::new);
        ctx.waitTicks(5);
    }

    /** Centre of the first cell in the icon picker grid. */
    private static int[] firstIconCell(Minecraft mc) {
        int w = mc.screen.width;
        int ow = Math.min(w - 16, 18 * 22 + 24);
        int cols = Math.max(4, (ow - 20) / 22);
        int gx = (w - ow) / 2 + (ow - cols * 22) / 2 + 1;
        return new int[]{gx + 10, 8 + 20 + 31 + 11};
    }

    /** Centre of palette swatch i in the colour chooser. */
    private static int[] swatch(Minecraft mc, int i) {
        int ox = (mc.screen.width - (20 * 11 + 16)) / 2;
        int oy = (mc.screen.height - 110) / 2;
        return new int[]{ox + 8 + (i % 20) * 11 + 4, oy + 68 + (i / 20) * 11 + 4};
    }

    private static int[] chooseDesignButton(Minecraft mc) {
        int w = mc.screen.width;
        int h = mc.screen.height;
        int pw = Math.min(w - 16, 430);
        int px = (w - pw) / 2;
        boolean twoCols = pw >= 380;
        int colW = twoCols ? (pw - 30) / 2 : pw - 20;
        int designH = 12 + 16 + 4 + 16 + 6
                + mc.font.split(org.webtrade.atlasmapextended.client.gui.T.tr("settings.death_markers_note"), colW).size() * 10 + 4 + 4 + 19;
        int optionsH = 12 + 4 * 19 + 4 + 12 + 3 * 19 + 24;
        int contentH = twoCols ? Math.max(designH, optionsH) : designH + 8 + optionsH;
        int ph = Math.min(h - 16, contentH + 54);
        int py = (h - ph) / 2;
        int top = py + 22;
        int lx = px + 10;
        return new int[]{lx + colW / 2, top + 12 + 8};
    }

    private static int[] popupThemeTile(Minecraft mc, int i) {
        int w = mc.screen.width;
        int h = mc.screen.height;
        int pw = Math.min(w - 16, 430);
        int px = (w - pw) / 2;
        int rows = (Theme.ALL.size() + 1) / 2;
        int ph = Math.min(h - 16, 12 + rows * (32 + 4) + 40);
        int py = (h - ph) / 2;
        int x = px + 10;
        int tw = (pw - 20 - 4) / 2;
        int top = py + 24;
        int tx = x + (i % 2) * (tw + 4);
        int ty = top + (i / 2) * (32 + 4);
        return new int[]{tx + tw / 2, ty + 16};
    }

    private interface Pos {
        int[] get(Minecraft mc);
    }

    private static void clickGui(ClientGameTestContext ctx, TestInput input, Pos pos) {
        clickGui(ctx, input, pos, InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static void clickGui(ClientGameTestContext ctx, TestInput input, Pos pos, int button) {
        int[] p = ctx.computeOnClient(mc -> {
            int[] g = pos.get(mc);
            int s = mc.getWindow().getGuiScale();
            return new int[]{g[0] * s, g[1] * s};
        });
        input.setCursorPos(p[0], p[1]);
        ctx.waitTick();
        input.pressMouse(button);
        ctx.waitTick();
    }

    private static void moveGui(ClientGameTestContext ctx, TestInput input, Pos pos) {
        int[] p = ctx.computeOnClient(mc -> {
            int[] g = pos.get(mc);
            int s = mc.getWindow().getGuiScale();
            return new int[]{g[0] * s, g[1] * s};
        });
        input.setCursorPos(p[0], p[1]);
        ctx.waitTick();
    }

    private static void drag(ClientGameTestContext ctx, TestInput input, Pos pos) {
        int[] pts = ctx.computeOnClient(mc -> {
            int[] g = pos.get(mc);
            int s = mc.getWindow().getGuiScale();
            int[] out = new int[g.length];
            for (int i = 0; i < g.length; i++) {
                out[i] = g[i] * s;
            }
            return out;
        });
        input.setCursorPos(pts[0], pts[1]);
        ctx.waitTick();
        input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTick();
        for (int i = 2; i < pts.length; i += 2) {
            input.setCursorPos(pts[i], pts[i + 1]);
            ctx.waitTick();
        }
        input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ctx.waitTick();
    }
}
