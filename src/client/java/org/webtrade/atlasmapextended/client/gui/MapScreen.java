package org.webtrade.atlasmapextended.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import org.webtrade.atlasmapextended.client.ModKeys;
import org.webtrade.atlasmapextended.client.data.Marker;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.data.Region;
import org.webtrade.atlasmapextended.client.data.Storage;
import org.webtrade.atlasmapextended.client.data.WorldMeta;
import org.webtrade.atlasmapextended.client.data.Stroke;
import org.webtrade.atlasmapextended.client.map.MapLayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.webtrade.atlasmapextended.client.map.MapManager;
import org.webtrade.atlasmapextended.client.map.WaypointShare;
import org.webtrade.atlasmapextended.client.map.MapSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

/** The full screen papyrus map with markers, the marker list, the ruler and the drawing editor. */
public class MapScreen extends Screen {
    private static final int TB = 20;
    private static final int PAL_COLS = 20;
    private static final int[] COLORS = buildPalette();
    private static final int[] WIDTHS = {1, 2, 3, 5};
    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 16f;
    private static final String[] MARKER_SORTS = {"newest", "oldest", "name", "distance", "icon"};
    private static final String[] REGION_SORTS = {"newest", "oldest", "name", "distance"};

    private static float lastZoom = 2f;

    /** 20 hues in three shades (light, normal, dark): 60 inks in total. */
    private static int[] buildPalette() {
        int[] base = {0x3A2616, 0x7A4E2A, 0xB03A2E, 0xD35A2A, 0xE08A2E, 0xE0B03A, 0xE8D44A, 0x9DBB3A, 0x4E8A34, 0x2F5E2A,
                0x2E8A7E, 0x3AA0C0, 0x5A8FD0, 0x2F5AA8, 0x45408F, 0x7A3E9A, 0xB0408A, 0xD9708A, 0x8A8A8A, 0xF2E6C8};
        int[] out = new int[base.length * 3];
        for (int i = 0; i < base.length; i++) {
            int c = base[i];
            int r = c >> 16 & 0xFF;
            int g = c >> 8 & 0xFF;
            int b = c & 0xFF;
            out[i] = 0xFF000000 | mix(r, 255, 0.45f) << 16 | mix(g, 255, 0.45f) << 8 | mix(b, 255, 0.45f);
            out[base.length + i] = 0xFF000000 | c;
            out[base.length * 2 + i] = 0xFF000000 | (int) (r * 0.58f) << 16 | (int) (g * 0.58f) << 8 | (int) (b * 0.58f);
        }
        out[base.length - 1] = 0xFFFFFFFF;
        out[base.length * 3 - 1] = 0xFF000000;
        return out;
    }

    private static int mix(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    private enum Tool { NONE, RULER, DRAW, REGION }

    private enum DrawTool { PEN, LINE, ARROW, RECT, CIRCLE, ERASER }

    private enum RegionShape { FREEHAND, RECT, CIRCLE }

    private enum Panel { NONE, VIEW, EDIT }

    private enum Overlay { NONE, ICONS, COLOR, PICKER, VIEWER, CONFIRM, DIMS, MAPS, REGION, REGION_COLOR, SHARE }

    private record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {
    }

    private record ScrollArea(int x, int y, int w, int h, DoubleConsumer onScroll) {
    }

    private final Screen parent;
    private MapSession session;
    private final boolean ownsSession;
    private final MapView view = new MapView();
    private final ModConfig cfg = ModConfig.get();
    private String dim;

    // tools
    private Tool tool = Tool.NONE;
    private DrawTool drawTool = DrawTool.PEN;
    private int colorIdx = PAL_COLS;
    private int regionColorIdx = PAL_COLS + 4;
    private boolean regionFill = true;
    private int widthIdx = 1;
    private Stroke current;
    private List<Stroke> erased;
    private final Deque<Runnable> undo = new ArrayDeque<>();
    private final List<double[]> ruler = new ArrayList<>();

    // regions
    private final List<double[]> regionPoints = new ArrayList<>();
    private boolean regionDrawing;
    private RegionShape regionShape = RegionShape.FREEHAND;
    private double regionPressX;
    private double regionPressY;
    private double regionStartWX;
    private double regionStartWZ;
    private double regionCurrentWX;
    private double regionCurrentWZ;
    private Region editingRegion;
    private boolean regionIsNew;
    private EditBox regionNameBox;
    private String selectedRegionId;

    // marker panels
    private boolean listOpen;
    private boolean markersOpen;
    private boolean regionsOpen;
    private String search = "";
    private String markerSearch = "";
    private String regionSearch = "";
    private double listScroll;
    private Marker selected;
    private Panel panel = Panel.NONE;
    private Marker working;
    private boolean workingIsNew;
    private Region selectedRegion;
    private Region workingRegion;
    private boolean regionMode;
    private Region redrawTarget;
    private boolean markerDrag;
    private double markerDragOffX;
    private double markerDragOffZ;
    private final List<String> addedFiles = new ArrayList<>();
    private final List<String> removedFiles = new ArrayList<>();
    private double descScroll;
    private double shotsScroll;

    // overlays
    private Overlay overlay = Overlay.NONE;
    private List<Path> pickerFiles = new ArrayList<>();
    private final List<Path> pickerChosen = new ArrayList<>();
    private double pickerScroll;
    private List<Path> viewerFiles = new ArrayList<>();
    private List<String> viewerNames = new ArrayList<>();
    private Marker viewerMarker;
    private boolean annotating;
    private boolean annotPressed;
    private Stroke annotCurrent;
    private int[] viewerRect;
    private final MapView annotView = new MapView();
    private int viewerIndex;
    private Component confirmText = Component.empty();
    private Runnable confirmAction;
    private int iconPickerY;
    private double iconScroll;
    private Marker colorTarget;
    private String colorIcon = "pin";
    private Marker colorMarker;
    private Marker shareMarker;
    private Region shareRegion;
    private EditBox shareCommentBox;
    private double sharePlayerScroll;
    private Region colorRegion;
    private EditBox iconSearch;

    // widgets
    private EditBox nameBox;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private EditBox searchBox;
    private EditBox markerSearchBox;
    private EditBox regionSearchBox;
    private MultiLineEditBox descBox;

    // input / hit testing
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> toolHits = new ArrayList<>();
    private final List<ScrollArea> scrolls = new ArrayList<>();
    private final List<int[]> uiRects = new ArrayList<>();
    private List<int[]> lastUiRects = new ArrayList<>();
    private int modalStart = -1;
    private Component tooltip;
    private boolean pressed;
    private int pressButton;
    private double pressX;
    private double pressY;
    private double pressCX;
    private double pressCZ;
    private boolean dragging;
    private double mouseX;
    private double mouseY;
    private int biomeCellX = Integer.MIN_VALUE;
    private int biomeCellZ = Integer.MIN_VALUE;
    private long biomeHoverSince;
    private boolean biomeResolved;
    private String biomeName;
    private int biomeX;
    private int biomeZ;

    private final WorldMeta previewMeta;
    private WorldMeta confirmBindTarget;
    private EditBox mapSearchBox;
    private EditBox localMapSearchBox;
    private EditBox serverMapSearchBox;
    private EditBox realmMapSearchBox;
    private String mapSearch = "";
    private String localMapSearch = "";
    private String serverMapSearch = "";
    private String realmMapSearch = "";
    private boolean localsOpen = true;
    private boolean serversOpen = true;
    private boolean realmsOpen = true;
    private int localSort = 0;
    private int serverSort = 0;
    private int realmSort = 0;
    private static final String[] MAP_SORTS = {"newest", "name", "markers"};
    private final Map<String, Integer> markerCounts = new HashMap<>();

    public MapScreen(Screen parent, MapSession session, boolean ownsSession) {
        this(parent, session, ownsSession, null);
    }

    public MapScreen(Screen parent, MapSession session, boolean ownsSession, WorldMeta previewMeta) {
        super(T.tr("map.title"));
        this.parent = parent;
        this.session = session;
        this.ownsSession = ownsSession;
        this.previewMeta = previewMeta;
        this.dim = session.meta.lastDim;
        this.view.cx = session.meta.lastX;
        this.view.cz = session.meta.lastZ;
        this.view.zoom = lastZoom;
        Minecraft mc = Minecraft.getInstance();
        if (previewMeta == null && session.isLive() && mc.player != null && mc.level != null) {
            dim = mc.level.dimension().location().toString();
            view.cx = mc.player.getX();
            view.cz = mc.player.getZ();
        }
    }

    /** Opens the map of the world the player is in. */
    public static MapScreen openLive(Minecraft mc) {
        MapSession s = MapManager.live();
        if (s == null) {
            return null;
        }
        MapScreen screen = new MapScreen(null, s, false);
        mc.setScreen(screen);
        Paper.pageTurn();
        return screen;
    }

    public void openMarkerList() {
        setListOpen(true);
    }

    /** Switches the shown dimension (also used by the client gametest). */
    public void showDimension(String d) {
        switchDim(d);
    }

    /** View centre and zoom (used by the client gametest). */
    public double[] viewCenter() {
        return new double[]{view.cx, view.cz, view.zoom};
    }


    /** Dimension currently shown on the map and in the object list (used by the client gametest). */
    public String shownDimension() {
        return dim;
    }


    private void setListOpen(boolean open) {
        listOpen = open;
        if (open) {
            markersOpen = false;
            regionsOpen = false;
            markerSearch = "";
            regionSearch = "";
            search = "";
            listScroll = 0;
            if (markerSearchBox != null) {
                searchBox.setValue("");
                markerSearchBox.setValue("");
                regionSearchBox.setValue("");
            }
        }
    }

    private void toggleList() {
        setListOpen(!listOpen);
    }

    /** Selects a marker, centers the map on it and shows its info panel. */
    public void showMarker(Marker m) {
        openView(m, true);
    }

    public void showRegion(Region r) {
        if (r == null) return;
        openRegionView(r);
        centerOn(selected);
    }

    private boolean clean;

    /** Shows only the map itself (no toolbars or panels), e.g. for screenshots. */
    public void setCleanMode(boolean clean) {
        this.clean = clean;
    }

    /** Moves the map view. */
    public void setView(double x, double z, float zoom) {
        view.cx = x;
        view.cz = z;
        view.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    public void closePanels() {
        closePanel();
        listOpen = false;
    }

    /** Opens the editor panel for a marker. */
    public void editMarker(Marker m) {
        openEdit(m, false);
    }

    /** Creates a marker at the player's feet and opens its editor. */
    public void newMarkerAtPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            createMarker(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void init() {
        nameBox = new EditBox(font, 0, 0, 100, 12, T.tr("field.name"));
        nameBox.setBordered(false);
        nameBox.setTextColor(Paper.INK);
        nameBox.setTextShadow(false);
        nameBox.setMaxLength(64);
        nameBox.setResponder(s -> {
            if (working != null) {
                working.name = s;
            }
        });
        xBox = coordBox();
        yBox = coordBox();
        zBox = coordBox();
        descBox = MultiLineEditBox.builder()
                .setTextColor(Paper.INK)
                .setCursorColor(Paper.INK)
                .setTextShadow(false)
                .setShowBackground(false)
                .setShowDecorations(false)
                .setPlaceholder(T.tr("field.desc_hint"))
                .build(font, 100, 60, T.tr("field.desc"));
        descBox.setCharacterLimit(4000);
        descBox.setValueListener(s -> {
            if (working != null) {
                working.description = s;
            }
        });
        searchBox = new EditBox(font, 0, 0, 100, 12, T.tr("list.search"));
        searchBox.setBordered(false);
        searchBox.setTextColor(Paper.INK);
        searchBox.setTextShadow(false);
        searchBox.setValue(search);
        searchBox.setResponder(s -> {
            search = s;
            listScroll = 0;
        });
        markerSearchBox = new EditBox(font, 0, 0, 100, 12, T.tr("list.search_markers"));
        markerSearchBox.setBordered(false);
        markerSearchBox.setTextColor(Paper.INK);
        markerSearchBox.setTextShadow(false);
        markerSearchBox.setValue(markerSearch);
        markerSearchBox.setResponder(s -> {
            markerSearch = s;
            listScroll = 0;
        });
        regionSearchBox = new EditBox(font, 0, 0, 100, 12, T.tr("list.search_regions"));
        regionSearchBox.setBordered(false);
        regionSearchBox.setTextColor(Paper.INK);
        regionSearchBox.setTextShadow(false);
        regionSearchBox.setValue(regionSearch);
        regionSearchBox.setResponder(s -> {
            regionSearch = s;
            listScroll = 0;
        });
        addRenderableWidget(nameBox);
        addRenderableWidget(xBox);
        addRenderableWidget(yBox);
        addRenderableWidget(zBox);
        addRenderableWidget(descBox);
        addRenderableWidget(searchBox);
        addRenderableWidget(markerSearchBox);
        addRenderableWidget(regionSearchBox);
        iconSearch = new EditBox(font, 0, 0, 100, 12, T.tr("icons.search_hint"));
        iconSearch.setBordered(false);
        iconSearch.setTextColor(Paper.INK);
        iconSearch.setTextShadow(false);
        iconSearch.setResponder(v -> iconScroll = 0);
        addWidget(iconSearch);
        regionNameBox = new EditBox(font, 0, 0, 100, 12, T.tr("region.name"));
        regionNameBox.setBordered(false);
        regionNameBox.setTextColor(Paper.INK);
        regionNameBox.setTextShadow(false);
        regionNameBox.setMaxLength(48);
        addWidget(regionNameBox);
        shareCommentBox = new EditBox(font, 0, 0, 100, 12, T.tr("marker.share.comment_hint"));
        shareCommentBox.setBordered(false);
        shareCommentBox.setTextColor(Paper.INK);
        shareCommentBox.setTextShadow(false);
        shareCommentBox.setMaxLength(128);
        addWidget(shareCommentBox);

        mapSearchBox = new EditBox(font, 0, 0, 100, 12, T.tr("map.search_all"));
        mapSearchBox.setBordered(false);
        mapSearchBox.setTextColor(Paper.INK);
        mapSearchBox.setTextShadow(false);
        mapSearchBox.setValue(mapSearch);
        mapSearchBox.setResponder(s -> {
            mapSearch = s.toLowerCase(Locale.ROOT).trim();
            scrollMaps = 0;
        });
        addWidget(mapSearchBox);

        localMapSearchBox = new EditBox(font, 0, 0, 100, 12, T.tr("map.search_locals"));
        localMapSearchBox.setBordered(false);
        localMapSearchBox.setTextColor(Paper.INK);
        localMapSearchBox.setTextShadow(false);
        localMapSearchBox.setValue(localMapSearch);
        localMapSearchBox.setResponder(s -> {
            localMapSearch = s.toLowerCase(Locale.ROOT).trim();
            scrollMaps = 0;
        });
        addWidget(localMapSearchBox);

        serverMapSearchBox = new EditBox(font, 0, 0, 100, 12, T.tr("map.search_servers"));
        serverMapSearchBox.setBordered(false);
        serverMapSearchBox.setTextColor(Paper.INK);
        serverMapSearchBox.setTextShadow(false);
        serverMapSearchBox.setValue(serverMapSearch);
        serverMapSearchBox.setResponder(s -> {
            serverMapSearch = s.toLowerCase(Locale.ROOT).trim();
            scrollMaps = 0;
        });
        addWidget(serverMapSearchBox);

        realmMapSearchBox = new EditBox(font, 0, 0, 100, 12, T.tr("map.search_realms"));
        realmMapSearchBox.setBordered(false);
        realmMapSearchBox.setTextColor(Paper.INK);
        realmMapSearchBox.setTextShadow(false);
        realmMapSearchBox.setValue(realmMapSearch);
        realmMapSearchBox.setResponder(s -> {
            realmMapSearch = s.toLowerCase(Locale.ROOT).trim();
            scrollMaps = 0;
        });
        addWidget(realmMapSearchBox);
        if (working != null) {
            fillWidgets();
        }
        updateWidgetVisibility();
    }

    private EditBox coordBox() {
        EditBox b = new EditBox(font, 0, 0, 30, 12, Component.empty());
        b.setBordered(false);
        b.setTextColor(Paper.INK);
        b.setTextShadow(false);
        b.setMaxLength(9);
        return b;
    }

    private void fillWidgets() {
        nameBox.setValue(working.name);
        xBox.setValue(Integer.toString(working.x));
        yBox.setValue(Integer.toString(working.y));
        zBox.setValue(Integer.toString(working.z));
        descBox.setValue(working.description);
    }

    /** True while the coordinates in the fields differ from the saved marker position. */
    private boolean coordsChanged() {
        if (working == null || selected == null) {
            return false;
        }
        return parse(xBox.getValue(), working.x) != selected.x
                || parse(yBox.getValue(), working.y) != selected.y
                || parse(zBox.getValue(), working.z) != selected.z;
    }

    /** Restores the saved coordinates and updates the fields. */
    private void revertCoords() {
        if (working == null || selected == null) {
            return;
        }
        working.x = selected.x;
        working.y = selected.y;
        working.z = selected.z;
        xBox.setValue(Integer.toString(working.x));
        yBox.setValue(Integer.toString(working.y));
        zBox.setValue(Integer.toString(working.z));
        Paper.click();
    }

    /** Set while the settings screen is shown on top, so the session stays open. */
    private boolean suspended;

    private void openSettings() {
        commitEdit(false);
        suspended = true;
        minecraft.setScreen(new SettingsScreen(this));
    }

    @Override
    public void removed() {
        if (suspended) {
            suspended = false;
            return;
        }
        commitEdit(false);
        session.meta.lastDim = session.isLive() ? session.meta.lastDim : dim;
        if (!session.isLive()) {
            session.meta.lastX = view.cx;
            session.meta.lastZ = view.cz;
            session.saveMeta();
        }
        lastZoom = view.zoom;
        session.saveMarkers();
        session.saveStrokes();
        session.saveRegions();
        if (ownsSession) {
            session.close();
        }
        ImageCache.clear();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.mouseX = mx;
        this.mouseY = my;
        hits.clear();
        toolHits.clear();
        scrolls.clear();
        uiRects.clear();
        modalStart = -1;
        tooltip = null;
        MapView.newFrame();

        drawMap(g, mx, my);
        updateBiomeHover(mx, my);
        if (clean) {
            lastUiRects = new ArrayList<>();
            return;
        }
        if (previewMeta != null) {
            drawPreviewHud(g, mx, my);
            if (confirmBindTarget != null) {
                drawConfirmBind(g, mx, my);
            }
            if (tooltip != null) {
                String tip = tooltip.getString();
                if (tip.indexOf('\n') >= 0) {
                    List<Component> lines = new ArrayList<>();
                    for (String line : tip.split("\n", -1)) {
                        lines.add(Component.literal(line));
                    }
                    g.setComponentTooltipForNextFrame(font, lines, mx, my);
                } else {
                    g.setTooltipForNextFrame(font, tooltip, mx, my);
                }
                tooltip = null;
            }
            lastUiRects = new ArrayList<>(uiRects);
            return;
        }
        g.nextStratum();

        drawToolbar(g, mx, my);
        if (tool == Tool.DRAW) {
            drawDrawBar(g, mx, my);
        } else if (tool == Tool.RULER) {
            drawRulerBar(g);
        } else if (tool == Tool.REGION) {
            drawRegionBar(g, mx, my);
        } else {
            drawTitle(g);
        }
        drawStatus(g, mx, my);
        if (listOpen) {
            drawList(g, mx, my);
        }
        updateWidgetVisibility();
        if (panel == Panel.VIEW) {
            drawViewPanel(g, mx, my);
        } else if (panel == Panel.EDIT) {
            drawEditPanel(g, mx, my);
        }
        super.render(g, overlay == Overlay.NONE ? mx : -1, overlay == Overlay.NONE ? my : -1, pt);

        if (overlay != Overlay.NONE) {
            g.nextStratum();
            tooltip = null;
            modalStart = hits.size();
            switch (overlay) {
                case ICONS -> drawIconPicker(g, mx, my);
                case COLOR -> drawColorPicker(g, mx, my);
                case REGION_COLOR -> drawRegionColorPicker(g, mx, my);
                case PICKER -> drawShotPicker(g, mx, my);
                case VIEWER -> drawViewer(g, mx, my);
                case CONFIRM -> drawConfirm(g, mx, my);
                case DIMS -> drawDims(g, mx, my);
                case MAPS -> drawMapSelect(g, mx, my);
                case REGION -> drawRegionEditor(g, mx, my);
                default -> {
                }
            }
        }
        if (tooltip == null) {
            drawBiomeHint(g, mx, my);
        }
        if (tooltip != null) {
            String tip = tooltip.getString();
            if (tip.indexOf('\n') >= 0) {
                List<Component> lines = new ArrayList<>();
                for (String line : tip.split("\n", -1)) {
                    lines.add(Component.literal(line));
                }
                g.setComponentTooltipForNextFrame(font, lines, mx, my);
            } else {
                g.setTooltipForNextFrame(font, tooltip, mx, my);
            }
        }
        lastUiRects = new ArrayList<>(uiRects);
    }

    // ------------------------------------------------------------- biome hover

    /** Shows the biome plaque after the cursor rests on the same spot for two seconds. */
    private void updateBiomeHover(double mx, double my) {
        if (overlay != Overlay.NONE || regionDrawing || listOpen
                || !mouseOverMap(mx, my)) {
            resetBiomeHover();
            return;
        }
        double cell = Math.max(1.0, 4.0 / view.zoom);
        int cellX = (int) Math.floor(view.wx(mx) / cell);
        int cellZ = (int) Math.floor(view.wz(my) / cell);
        if (cellX != biomeCellX || cellZ != biomeCellZ) {
            biomeCellX = cellX;
            biomeCellZ = cellZ;
            biomeHoverSince = System.currentTimeMillis();
            biomeResolved = false;
            biomeName = null;
            return;
        }
        if (!biomeResolved && System.currentTimeMillis() - biomeHoverSince >= 2000L) {
            biomeResolved = true;
            biomeX = (int) Math.floor(view.wx(mx));
            biomeZ = (int) Math.floor(view.wz(my));
            biomeName = biomeAt(biomeX, biomeZ);
        }
    }

    private void resetBiomeHover() {
        biomeCellX = Integer.MIN_VALUE;
        biomeCellZ = Integer.MIN_VALUE;
        biomeResolved = false;
        biomeName = null;
    }

    /** Localized biome name, or null when that spot has no scanned map data. */
    private String biomeAt(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !dim.equals(mc.level.dimension().location().toString())
                || !session.layer(dim).hasData(x, z)
                || !mc.level.hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        Holder<Biome> holder = mc.level.getBiome(new BlockPos(x, mc.level.getSeaLevel(), z));
        return holder.unwrapKey()
                .map(key -> key.location().toString().replace(':', '.'))
                .map(id -> Component.translatable("biome." + id).getString())
                .orElse(null);
    }

    /** Test hook: the biome shown by the hover plaque, or null when hidden. */
    public String hoveredBiome() {
        return biomeName;
    }

    private void drawBiomeHint(GuiGraphics g, int mx, int my) {
        if (biomeName == null) {
            return;
        }
        String title = T.s("biome.title");
        String coords = "x " + biomeX + "   z " + biomeZ;
        int inner = Math.max(font.width(biomeName), Math.max(font.width(title), font.width(coords)));
        int w = inner + 16;
        int h = 40;
        int px = mx + 16;
        int py = my + 12;
        if (px + w > width - 4) {
            px = mx - w - 12;
        }
        if (py + h > height - 4) {
            py = height - h - 4;
        }
        px = Math.max(4, px);
        py = Math.max(4, py);
        Paper.panel(g, px, py, w, h);
        g.drawString(font, title, px + 8, py + 5, Paper.INK_SOFT, false);
        Paper.separator(g, px + 6, py + 16, w - 12);
        g.drawString(font, biomeName, px + 8, py + 19, Paper.INK, false);
        g.drawString(font, coords, px + 8, py + 29, Paper.a(Paper.INK, 0x90), false);
    }

    private void drawMap(GuiGraphics g, int mx, int my) {
        view.setRect(0, 0, width, height);
        MapLayer layer = session.layer(dim);
        view.drawBackground(g);
        view.drawTiles(g, layer);
        view.drawGrid(g);
        view.drawRegions(g, font, regionsForDisplay(), dim, false, regionMode ? selectedRegionId : null);
        if (regionDrawing) {
            Region preview = previewRegion();
            if (preview != null) {
                view.drawRegion(g, font, preview, false, true);
            }
        }
        vignette(g);
        if (cfg.drawingsVisible || tool == Tool.DRAW) {
            view.drawStrokes(g, session.strokes(dim), 1f);
        }
        if (current != null) {
            view.drawStroke(g, current, 1f);
        }
        view.drawRegionLabels(g, font, regionsForDisplay(), dim,
                regionMode ? selectedRegionId : null);
        if (tool == Tool.DRAW && drawTool == DrawTool.ERASER && mouseOverMap(mx, my)) {
            Paper.outline(g, mx - 6, my - 6, 12, 12, Paper.RED);
        }
        drawRuler(g, mx, my);

        Marker hovered = hoveredMarker(mx, my);
        List<Marker> shown = session.markers;
        if (panel == Panel.EDIT && working != null) {
            shown = new ArrayList<>(session.markers);
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i) == selected) {
                    shown.set(i, working);
                    break;
                }
            }
        }
        if (cfg.markersVisible || panel == Panel.EDIT) {
            view.drawMarkers(g, font, shown, dim, 16, cfg.markerLabels,
                    selected == null ? null : selected.id, hovered == null ? null : hovered.id, false);
        } else if (selected != null && selected.dim.equals(dim) && !regionMode) {
            view.drawMarkers(g, font, List.of(selected), dim, 16, true, selected.id, null, false);
        }
        Minecraft mc = Minecraft.getInstance();
        if (session.isLive() && mc.player != null && mc.level != null && dim.equals(mc.level.dimension().location().toString())) {
            view.drawPlayer(g, mc.player.getX(), mc.player.getZ(), mc.player.getYRot(), 16);
        }
        if (hovered != null && overlay == Overlay.NONE) {
            tooltip = Component.literal(hovered.name.isEmpty() ? T.s("marker.unnamed") : hovered.name)
                    .append(Component.literal("  " + hovered.x + ", " + hovered.y + ", " + hovered.z).withColor(0xFFAAAAAA));
        }
        if (session.isLive() && mc.level != null
                && !dim.equals(mc.level.dimension().location().toString())) {
            double[] last = session.meta.posFor(dim);
            if (last != null && overlay == Overlay.NONE
                    && view.drawLastSeen(g, last[0], last[1], 16, mx, my)) {
                tooltip = T.tr("map.last_seen");
            }
            MapManager.rememberView(dim, view.cx, view.cz, view.zoom);
        }
    }

    /**
     * Regions as drawn while editing. The edited region previews the picked colour without touching
     * the saved data until the user presses Save.
     */
    private List<Region> regionsForDisplay() {
        List<Region> saved = session.regions(dim);
        if (!regionMode || panel != Panel.EDIT || working == null || selectedRegion == null) {
            return saved;
        }
        List<Region> out = new ArrayList<>(saved.size());
        for (Region r : saved) {
            if (!r.id.equals(selectedRegion.id)) {
                out.add(r);
                continue;
            }
            Region preview = new Region();
            preview.id = r.id;
            preview.name = r.name;
            preview.description = r.description;
            preview.color = working.color;
            preview.filled = workingRegion != null ? workingRegion.filled : r.filled;
            preview.dim = r.dim;
            preview.pts = r.pts;
            preview.hidden = r.hidden;
            out.add(preview);
        }
        return out;
    }

    private void vignette(GuiGraphics g) {
        for (int i = 0; i < 10; i++) {
            int a = (10 - i) * 7 << 24 | Paper.VIGNETTE;
            g.fill(0, i * 2, width, i * 2 + 2, a);
            g.fill(0, height - i * 2 - 2, width, height - i * 2, a);
            g.fill(i * 2, 0, i * 2 + 2, height, a);
            g.fill(width - i * 2 - 2, 0, width - i * 2, height, a);
        }
    }

    private void drawRuler(GuiGraphics g, int mx, int my) {
        if (ruler.isEmpty()) {
            return;
        }
        int ink = 0xFF9C3B2A;
        for (int i = 0; i + 1 < ruler.size(); i++) {
            double[] a = ruler.get(i);
            double[] b = ruler.get(i + 1);
            Paper.dashedLine(g, view.sx(a[0]), view.sy(a[1]), view.sx(b[0]), view.sy(b[1]), 2f, ink, 5f);
        }
        boolean live = tool == Tool.RULER && overlay == Overlay.NONE && mouseOverMap(mx, my);
        if (live) {
            double[] a = ruler.get(ruler.size() - 1);
            Paper.dashedLine(g, view.sx(a[0]), view.sy(a[1]), mx, my, 1.5f, 0xB09C3B2A, 4f);
        }
        for (double[] p : ruler) {
            float x = view.sx(p[0]);
            float y = view.sy(p[1]);
            Paper.dot(g, x, y, 7, Paper.INK);
            Paper.dot(g, x, y, 4, Paper.GOLD_LIGHT);
        }
        for (int i = 0; i + 1 < ruler.size(); i++) {
            double[] a = ruler.get(i);
            double[] b = ruler.get(i + 1);
            label(g, fmt(dist(a, b)) + " " + T.s("unit.blocks"), (view.sx(a[0]) + view.sx(b[0])) / 2, (view.sy(a[1]) + view.sy(b[1])) / 2);
        }
        if (live) {
            double[] a = ruler.get(ruler.size() - 1);
            double d = Math.hypot(view.wx(mx) - a[0], view.wz(my) - a[1]);
            label(g, fmt(d), mx + 12, my + 10);
        }
    }

    private void label(GuiGraphics g, String s, float cx, float cy) {
        int w = font.width(s);
        int x = Math.round(cx - w / 2f);
        int y = Math.round(cy - 5);
        g.fill(x - 3, y - 2, x + w + 3, y + 10, Paper.a(Paper.CREAM, 0xE0));
        Paper.outline(g, x - 3, y - 2, w + 6, 12, 0xFF9C3B2A);
        g.drawString(font, s, x, y, Paper.INK, false);
    }

    private void drawTitle(GuiGraphics g) {
        int avail = freeRight() - freeLeft();
        String t = font.plainSubstrByWidth(session.meta.name + "  ·  " + T.dimName(dim), Math.max(20, avail - 40));
        int w = font.width(t) + 30;
        if (w > avail) {
            return;
        }
        int cx = (freeLeft() + freeRight()) / 2;
        int x = cx - w / 2;
        Paper.panel(g, x, 6, w, 18);
        ui(x, 6, w, 18);
        Paper.title(g, font, t, cx, 11);
    }

    private void drawRulerBar(GuiGraphics g) {
        double total = 0;
        for (int i = 0; i + 1 < ruler.size(); i++) {
            total += dist(ruler.get(i), ruler.get(i + 1));
        }
        String l1 = T.s("ruler.total", fmt(total), ruler.size());
        String l2 = T.s("ruler.hint");
        if (ruler.size() >= 2) {
            double[] a = ruler.get(0);
            double[] b = ruler.get(ruler.size() - 1);
            l1 += "   " + T.s("ruler.direct", fmt(dist(a, b)), (int) Math.round(b[0] - a[0]), (int) Math.round(b[1] - a[1]));
        }
        int avail = freeRight() - freeLeft();
        int w = Math.min(avail, Math.max(font.width(l1), font.width(l2)) + 44);
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.literal(l1), w - 40));
        int firstCount = lines.size();
        lines.addAll(font.split(Component.literal(l2), w - 40));
        int h = lines.size() * 10 + 10;
        int x = (freeLeft() + freeRight()) / 2 - w / 2;
        Paper.panel(g, x, 6, w, h);
        ui(x, 6, w, h);
        Paper.icon(g, Paper.tool("ruler"), x + 8, 6 + (h - 16) / 2, 16);
        int ty = 11;
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + 30, ty, i < firstCount ? Paper.INK : Paper.INK_SOFT, false);
            ty += 10;
        }
    }

    private void drawToolbar(GuiGraphics g, int mx, int my) {
        int x = 6;
        int y = 6;
        List<Object[]> items = new ArrayList<>();
        items.add(new Object[]{"list", T.tr("tool.list", keyName(ModKeys.MARKER_LIST)), listOpen, (Runnable) this::toggleList});
        items.add(new Object[]{"ruler", T.tr("tool.ruler", keyName(ModKeys.RULER)), tool == Tool.RULER, (Runnable) () -> setTool(tool == Tool.RULER ? Tool.NONE : Tool.RULER)});
        items.add(new Object[]{"quill", T.tr("tool.editor", keyName(ModKeys.EDITOR)), tool == Tool.DRAW, (Runnable) () -> setTool(tool == Tool.DRAW ? Tool.NONE : Tool.DRAW)});
        items.add(new Object[]{"region", regionTooltip(), tool == Tool.REGION, (Runnable) () -> setTool(tool == Tool.REGION ? Tool.NONE : Tool.REGION)});
        items.add(new Object[]{cfg.drawingsVisible ? "eye" : "eye_off", T.tr(cfg.drawingsVisible ? "tool.hide_drawings" : "tool.show_drawings", keyName(ModKeys.TOGGLE_DRAWINGS)), !cfg.drawingsVisible, (Runnable) this::toggleDrawings});
        items.add(new Object[]{"marker:pin", T.tr(cfg.markersVisible ? "tool.hide_markers" : "tool.show_markers"), !cfg.markersVisible, (Runnable) () -> {
            cfg.markersVisible = !cfg.markersVisible;
            cfg.save();
        }});
        if (session.isLive()) {
            items.add(new Object[]{"target", T.tr("tool.center"), false, (Runnable) this::centerOnPlayer});
        }
        items.add(new Object[]{"zoom_in", T.tr("tool.zoom_in"), false, (Runnable) () -> zoomBy(1.5f, width / 2.0, height / 2.0)});
        items.add(new Object[]{"zoom_out", T.tr("tool.zoom_out"), false, (Runnable) () -> zoomBy(1 / 1.5f, width / 2.0, height / 2.0)});
        items.add(new Object[]{"globe", T.tr("tool.dims"), overlay == Overlay.DIMS, (Runnable) () -> overlay = Overlay.DIMS});
        items.add(new Object[]{"folder", T.tr("tool.switch_map"), overlay == Overlay.MAPS, (Runnable) () -> {
            overlay = overlay == Overlay.MAPS ? Overlay.NONE : Overlay.MAPS;
            scrollMaps = 0;
        }});
        items.add(new Object[]{"gear", T.tr("tool.settings"), false, (Runnable) this::openSettings});
        items.add(new Object[]{"close", T.tr("tool.close", keyName(ModKeys.OPEN_MAP)), false, (Runnable) this::onClose});

        int count = items.size();
        int step = TB + 3;
        int iconSize = TB;
        int available = height - 12;
        if (count * step + 5 > available) {
            step = Math.max(14, (available - 5) / count);
            iconSize = Math.max(10, Math.min(TB, step - 2));
        }
        int h = count * step + 5;
        if (Paper.inside(mx, my, x - 3, y - 3, TB + 6, h)) {
            tooltip = null;
        }
        g.fill(x - 3, y - 3, x + TB + 3, y + h - 3, 0x40000000);
        ui(x - 3, y - 3, TB + 6, h);
        for (Object[] it : items) {
            boolean hover = overlay == Overlay.NONE && Paper.inside(mx, my, x, y, TB, step);
            String ic = (String) it[0];
            int ix = x + (TB - iconSize) / 2;
            int iy = y + (step - iconSize) / 2;
            Paper.iconButton(g, ic.startsWith("marker:") ? Paper.marker(ic.substring(7)) : Paper.tool(ic), ix, iy, iconSize, hover, (Boolean) it[2]);
            if (hover) {
                tooltip = (Component) it[1];
            }
            toolHit(ix - 2, iy - 2, iconSize + 4, iconSize + 4, (Runnable) it[3]);
            y += step;
        }
    }

    /** The region tooltip: a title plus a one-sentence explanation. */
    private static Component regionTooltip() {
        return Component.literal(T.s("tool.regions"))
                .append(Component.literal("\n" + T.s("tool.regions.tip")).withColor(0xFF9A9A9A));
    }

    private static String onOff(boolean value) {
        return T.s(value ? "on" : "off");
    }

    private static final int DRAW_BAR_W = PAL_COLS * 11 + 15;
    private static final int DRAW_BAR_H = 64;

    private void drawDrawBar(GuiGraphics g, int mx, int my) {
        int x = (freeLeft() + freeRight()) / 2 - DRAW_BAR_W / 2;
        drawToolBar(g, mx, my, x, 6, overlay == Overlay.NONE, this::undo,
                () -> confirm(T.tr("confirm.clear_drawings"), this::clearDrawings), T.tr("draw.clear"));
    }

    /** The region tool's top bar: freehand (default), rectangle and circle presets. */
    private void drawRegionBar(GuiGraphics g, int mx, int my) {
        int w = DRAW_BAR_W;
        int h = DRAW_BAR_H + 22;
        int x = (freeLeft() + freeRight()) / 2 - w / 2;
        int y = 6;
        Paper.panel(g, x, y, w, h);
        ui(x, y, w, h);
        hit(x, y, w, h, () -> {
        });
        RegionShape[] shapes = {RegionShape.FREEHAND, RegionShape.RECT, RegionShape.CIRCLE};
        String[] icons = {"pen", "rect", "circle"};
        String[] tips = {"region.freehand", "region.rect", "region.circle"};
        for (int i = 0; i < shapes.length; i++) {
            int bx = x + 7 + i * 22;
            boolean hover = overlay == Overlay.NONE && Paper.inside(mx, my, bx, y + 5, 18, 18);
            Paper.iconButton(g, Paper.tool(icons[i]), bx, y + 5, 18, hover, regionShape == shapes[i]);
            if (hover) {
                tooltip = T.tr(tips[i]);
            }
            RegionShape shape = shapes[i];
            hit(bx, y + 5, 18, 18, () -> regionShape = shape);
        }
        int py = y + 28;
        for (int i = 0; i < COLORS.length; i++) {
            int idx = i;
            int sx = x + 8 + (i % PAL_COLS) * 11;
            int sy = py + (i / PAL_COLS) * 11;
            g.fill(sx - 1, sy - 1, sx + 10, sy + 10, Paper.INK);
            g.fill(sx, sy, sx + 9, sy + 9, COLORS[i]);
            if (regionColorIdx == i) {
                Paper.outline(g, sx - 2, sy - 2, 13, 13, Paper.GOLD_LIGHT);
                Paper.outline(g, sx - 1, sy - 1, 11, 11, Paper.GOLD);
            }
            hit(sx - 1, sy - 1, 11, 11, () -> regionColorIdx = idx);
        }
        int bw = PAL_COLS * 11;
        int by = py + 3 * 11 + 3;
        boolean hf = overlay == Overlay.NONE && Paper.inside(mx, my, x + 8, by, bw, 16);
        Paper.button(g, font, T.tr("region.fill", onOff(regionFill)), x + 8, by, bw, 16, hf, true);
        hit(x + 8, by, bw, 16, () -> regionFill = !regionFill);
    }

    /** Tool selector, line widths and the 60 colour palette. Shared by the map editor and the screenshot editor. */
    private void drawToolBar(GuiGraphics g, int mx, int my, int x, int y, boolean active, Runnable onUndo,
                             Runnable onClear, Component clearTip) {
        DrawTool[] tools = DrawTool.values();
        String[] icons = {"pen", "line", "arrow_draw", "rect", "circle", "eraser"};
        Paper.panel(g, x, y, DRAW_BAR_W, DRAW_BAR_H);
        ui(x, y, DRAW_BAR_W, DRAW_BAR_H);
        hit(x, y, DRAW_BAR_W, DRAW_BAR_H, () -> {
        });
        int cx = x + 7;
        for (int i = 0; i < tools.length; i++) {
            DrawTool t = tools[i];
            boolean hover = active && Paper.inside(mx, my, cx, y + 5, 18, 18);
            Paper.iconButton(g, Paper.tool(icons[i]), cx, y + 5, 18, hover, drawTool == t);
            if (hover) {
                tooltip = T.tr("draw." + icons[i]);
            }
            hit(cx, y + 5, 18, 18, () -> drawTool = t);
            cx += 20;
        }
        cx += 4;
        int dotColor = COLORS[colorIdx] == 0xFFFFFFFF || COLORS[colorIdx] == 0xFFF2E6C8 ? Paper.INK : COLORS[colorIdx];
        for (int i = 0; i < WIDTHS.length; i++) {
            int idx = i;
            if (widthIdx == i) {
                g.fill(cx, y + 5, cx + 13, y + 23, Paper.a(Paper.GOLD, 0x40));
                Paper.outline(g, cx, y + 5, 13, 18, Paper.GOLD);
            }
            Paper.dot(g, cx + 6.5f, y + 14, WIDTHS[i] + 1, dotColor);
            hit(cx, y + 5, 13, 18, () -> widthIdx = idx);
            cx += 14;
        }
        int ux = x + DRAW_BAR_W - 7 - 38;
        boolean hu = active && Paper.inside(mx, my, ux, y + 5, 18, 18);
        Paper.iconButton(g, Paper.tool("undo"), ux, y + 5, 18, hu, false);
        if (hu) {
            tooltip = T.tr("draw.undo");
        }
        hit(ux, y + 5, 18, 18, onUndo);
        boolean hc = active && Paper.inside(mx, my, ux + 20, y + 5, 18, 18);
        Paper.iconButton(g, Paper.tool("trash"), ux + 20, y + 5, 18, hc, false);
        if (hc) {
            tooltip = clearTip;
        }
        hit(ux + 20, y + 5, 18, 18, onClear);

        int py = y + 28;
        for (int i = 0; i < COLORS.length; i++) {
            int idx = i;
            int sx = x + 8 + (i % PAL_COLS) * 11;
            int sy = py + (i / PAL_COLS) * 11;
            g.fill(sx - 1, sy - 1, sx + 10, sy + 10, Paper.INK);
            g.fill(sx, sy, sx + 9, sy + 9, COLORS[i]);
            if (colorIdx == i) {
                Paper.outline(g, sx - 2, sy - 2, 13, 13, Paper.GOLD_LIGHT);
                Paper.outline(g, sx - 1, sy - 1, 11, 11, Paper.GOLD);
            }
            hit(sx - 1, sy - 1, 11, 11, () -> colorIdx = idx);
        }
    }

    private void drawStatus(GuiGraphics g, int mx, int my) {
        int x = 6 + TB + 10 + (listOpen ? listWidth() + 4 : 0);
        int y = height - 22;
        String coords;
        if (mouseOverMap(mx, my)) {
            coords = "X " + (int) Math.floor(view.wx(mx)) + "   Z " + (int) Math.floor(view.wz(my));
        } else {
            coords = "X " + (int) Math.floor(view.cx) + "   Z " + (int) Math.floor(view.cz);
        }
        // scale bar
        int[] nice = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};
        int len = nice[nice.length - 1];
        for (int n : nice) {
            if (n * view.zoom >= 40) {
                len = n;
                break;
            }
        }
        int px = Math.round(len * view.zoom);
        String scale = len + " " + T.s("unit.blocks");
        int w = font.width(coords) + 16 + px + 8 + font.width(scale) + 12;
        Paper.panel(g, x, y, w, 16);
        ui(x, y, w, 16);
        g.drawString(font, coords, x + 6, y + 5, Paper.INK, false);
        int bx = x + 6 + font.width(coords) + 12;
        g.fill(bx, y + 9, bx + px, y + 11, Paper.INK);
        g.fill(bx, y + 5, bx + 1, y + 12, Paper.INK);
        g.fill(bx + px - 1, y + 5, bx + px, y + 12, Paper.INK);
        g.fill(bx + px / 2, y + 7, bx + px / 2 + 1, y + 11, Paper.INK);
        g.drawString(font, scale, bx + px + 5, y + 5, Paper.INK_SOFT, false);

        int right = panel != Panel.NONE ? width - panelWidth() - 12 : width - 6;
        Paper.ensure(Paper.COMPASS);
        g.blit(RenderPipelines.GUI_TEXTURED, Paper.COMPASS, right - 52, height - 56, 0f, 0f, 48, 48, 64, 64, 64, 64);
    }

    // ------------------------------------------------------------------ marker list

    private int freeLeft() {
        return 6 + TB + 8 + (listOpen ? listWidth() + 4 : 0);
    }

    private int freeRight() {
        return panel != Panel.NONE ? width - panelWidth() - 10 : width - 6;
    }

    private int listWidth() {
        return Math.max(130, Math.min(180, width / 4));
    }

    private int panelWidth() {
        return Math.max(150, Math.min(190, width / 3));
    }

    private List<Marker> sortedMarkers(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Marker> list = new ArrayList<>();
        for (Marker m : session.markers) {
            if (m.dim.equals(dim) && (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q)
                    || m.description.toLowerCase(Locale.ROOT).contains(q))) {
                list.add(m);
            }
        }
        double[] ref = referencePoint();
        Comparator<Marker> byName = Comparator.comparing(m -> m.name.toLowerCase(Locale.ROOT));
        int sort = Math.floorMod(cfg.markerSort, MARKER_SORTS.length);
        switch (sort) {
            case 1 -> list.sort(Comparator.comparingLong((Marker m) -> m.created));
            case 2 -> list.sort(byName);
            case 3 -> list.sort(Comparator.comparingInt((Marker m) -> m.dim.equals(dim) ? 0 : 1)
                    .thenComparingDouble(m -> Math.hypot(m.x - ref[0], m.z - ref[1])));
            case 4 -> list.sort(Comparator.comparingInt((Marker m) -> T.MARKER_ICONS.indexOf(m.icon)).thenComparing(byName));
            default -> list.sort(Comparator.comparingLong((Marker m) -> m.created).reversed());
        }
        return list;
    }

    private List<Region> sortedRegions(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Region> list = new ArrayList<>();
        for (Region r : session.allRegions()) {
            if (r.dim.equals(dim) && (q.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(q)
                    || r.description.toLowerCase(Locale.ROOT).contains(q))) {
                list.add(r);
            }
        }
        double[] ref = referencePoint();
        Comparator<Region> byName = Comparator.comparing(r -> r.name.toLowerCase(Locale.ROOT));
        int sort = Math.floorMod(cfg.regionSort, REGION_SORTS.length);
        switch (sort) {
            case 1 -> list.sort(Comparator.comparingLong((Region r) -> r.created));
            case 2 -> list.sort(byName);
            case 3 -> {
                // distance is O(points): compute it once per region, not inside every comparison
                Map<Region, Double> distances = new HashMap<>();
                for (Region r : list) {
                    distances.put(r, regionDistance(r, ref[0], ref[1]));
                }
                list.sort(Comparator.comparingInt((Region r) -> r.dim.equals(dim) ? 0 : 1)
                        .thenComparingDouble(distances::get));
            }
            default -> list.sort(Comparator.comparingLong((Region r) -> r.created).reversed());
        }
        return list;
    }

    /** Shortest distance from a point to the region border. */
    private static double regionDistance(Region r, double px, double pz) {
        int n = r.pointCount();
        if (n == 0) {
            return 0;
        }
        if (n == 1) {
            return Math.hypot(r.pts[0] - px, r.pts[1] - pz);
        }
        double best = Double.MAX_VALUE;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            best = Math.min(best, segmentDistance(px, pz,
                    r.pts[j * 2], r.pts[j * 2 + 1], r.pts[i * 2], r.pts[i * 2 + 1]));
        }
        return best;
    }

    private static double segmentDistance(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (pz - z1) * dz) / len));
        return Math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz));
    }

    private double[] referencePoint() {
        Minecraft mc = Minecraft.getInstance();
        if (session.isLive() && mc.player != null && mc.level != null && dim.equals(mc.level.dimension().location().toString())) {
            return new double[]{mc.player.getX(), mc.player.getZ()};
        }
        return new double[]{view.cx, view.cz};
    }

    private static int listSectionHeight(boolean open, int count) {
        return 18 + (open ? 20 + Math.max(18, count * 22) : 0);
    }

    private void drawListSearch(GuiGraphics g, EditBox box, int x, int y, int w, String hintKey) {
        Paper.inset(g, x, y, w, 14);
        Paper.icon(g, Paper.tool("zoom_in"), x + 2, y + 2, 10);
        box.setX(x + 14);
        box.setY(y + 3);
        box.setWidth(w - 18);
        hint(g, box, T.s(hintKey));
    }

    private void drawListHeader(GuiGraphics g, int mx, int my, int x, int y, int w, int top, int bottom,
                                String titleKey, int count, boolean open, String[] sorts, int sortIndex,
                                Runnable toggle, Runnable next, Runnable prev, Runnable eye, boolean anyVisible) {
        boolean hover = overlay == Overlay.NONE && Paper.inside(mx, my, x, y, w, 18);
        g.fill(x, y, x + w, y + 18, hover ? 0x24FFFFFF : 0x12FFFFFF);
        Paper.outline(g, x, y, w, 18, Paper.a(Paper.INK, 0x40));
        g.drawString(font, open ? "▾" : "▸", x + 4, y + 5, Paper.INK, false);
        g.drawString(font, T.s(titleKey) + " (" + count + ")", x + 14, y + 5, Paper.INK, false);
        int hy = Math.max(y, top);
        int hh = Math.min(y + 18, bottom) - hy;
        if (hh > 0) {
            hit(x, hy, w, hh, toggle);
        }
        int sx = x + w - 48;
        boolean hs = overlay == Overlay.NONE && Paper.inside(mx, my, sx, y + 1, 16, 16);
        Paper.iconButton(g, Paper.tool("sort"), sx, y + 1, 16, hs, false);
        if (hs) {
            tooltip = T.tr("sort." + sorts[Math.floorMod(sortIndex, sorts.length)]);
        }
        hit(sx, y + 1, 16, 16, next, prev);
        int ex = x + w - 26;
        boolean he = overlay == Overlay.NONE && Paper.inside(mx, my, ex, y + 1, 16, 16);
        Paper.iconButton(g, Paper.tool(anyVisible ? "eye" : "eye_off"), ex, y + 1, 16, he, false);
        if (he) {
            tooltip = T.tr(anyVisible ? "list.hide_all" : "list.show_all");
        }
        hit(ex, y + 1, 16, 16, eye);
    }

    private void drawMarkerRow(GuiGraphics g, int mx, int my, int lx, int lw,
                               int top, int bottom, int y, int rowH, Marker m, double[] ref) {
        int rx = lx + 8;
        int rw = lw - 16;
        boolean hover = overlay == Overlay.NONE && Paper.inside(mx, my, rx, y, rw, rowH - 2)
                && my >= top && my < bottom;
        boolean sel = !regionMode && selected != null && selected.id.equals(m.id);
        if (sel) {
            g.fill(rx, y, rx + rw, y + rowH - 2, Paper.a(Paper.GOLD, 0x50));
            Paper.outline(g, rx, y, rw, rowH - 2, Paper.GOLD);
        } else if (hover) {
            g.fill(rx, y, rx + rw, y + rowH - 2, 0x28FFFFFF);
        }
        Paper.markerIcon(g, m.icon, m.color, rx + 2, y + 2, 16, m.hidden ? 0x70 : 0xFF);
        String dist = m.dim.equals(dim)
                ? fmtInt(Math.hypot(m.x - ref[0], m.z - ref[1])) + " " + T.s("unit.blocks_short")
                : T.dimName(m.dim);
        int dw = font.width(dist);
        int nameW = rw - 22 - dw - 20;
        String name = font.plainSubstrByWidth(m.name.isEmpty() ? T.s("marker.unnamed") : m.name, nameW);
        g.drawString(font, name, rx + 21, y + 6, m.hidden ? Paper.DISABLED : Paper.INK, false);
        g.drawString(font, dist, rx + rw - 16 - dw, y + 6, Paper.INK_SOFT, false);
        int ex = rx + rw - 14;
        boolean eh = hover && Paper.inside(mx, my, ex, y + 4, 12, 12);
        Paper.icon(g, Paper.tool(m.hidden ? "eye_off" : "eye"), ex, y + 4, 12, eh ? 0xFFFFFFFF : 0xB0FFFFFF);
        if (eh) {
            tooltip = T.tr(m.hidden ? "marker.show" : "marker.hide");
        }
        int clipY = Math.max(y, top);
        int clipH = Math.min(y + rowH - 2, bottom) - clipY;
        if (clipH > 0) {
            hit(rx, clipY, rw, clipH, () -> openView(m, true), () -> openView(m, true));
            if (y + 4 >= top && y + 16 <= bottom) {
                hit(ex, y + 4, 12, 12, () -> {
                    m.hidden = !m.hidden;
                    session.saveMarkers();
                });
            }
        }
    }

    private void drawRegionRow(GuiGraphics g, int mx, int my, int lx, int lw,
                               int top, int bottom, int y, int rowH, Region r, double[] ref) {
        int rx = lx + 8;
        int rw = lw - 16;
        boolean hover = overlay == Overlay.NONE && Paper.inside(mx, my, rx, y, rw, rowH - 2)
                && my >= top && my < bottom;
        boolean sel = regionMode && selectedRegion != null && selectedRegion.id.equals(r.id);
        if (sel) {
            g.fill(rx, y, rx + rw, y + rowH - 2, Paper.a(Paper.GOLD, 0x50));
            Paper.outline(g, rx, y, rw, rowH - 2, Paper.GOLD);
        } else if (hover) {
            g.fill(rx, y, rx + rw, y + rowH - 2, 0x28FFFFFF);
        }
        int color = regionMode && panel == Panel.EDIT && working != null
                && selectedRegion != null && selectedRegion.id.equals(r.id) ? working.color : r.color;
        g.fill(rx + 2, y + 3, rx + 18, y + 19, color);
        Paper.outline(g, rx + 2, y + 3, 16, 16, Paper.INK);
        String dist = r.dim.equals(dim)
                ? fmtInt(regionDistance(r, ref[0], ref[1])) + " " + T.s("unit.blocks_short")
                : T.dimName(r.dim);
        int dw = font.width(dist);
        int nameW = rw - 22 - dw - 20;
        String name = font.plainSubstrByWidth(r.name.isEmpty() ? T.s("region.title") : r.name, nameW);
        g.drawString(font, name, rx + 21, y + 6, r.hidden ? Paper.DISABLED : Paper.INK, false);
        g.drawString(font, dist, rx + rw - 16 - dw, y + 6, Paper.INK_SOFT, false);
        int ex = rx + rw - 14;
        boolean eh = hover && Paper.inside(mx, my, ex, y + 4, 12, 12);
        Paper.icon(g, Paper.tool(r.hidden ? "eye_off" : "eye"), ex, y + 4, 12, eh ? 0xFFFFFFFF : 0xB0FFFFFF);
        if (eh) {
            tooltip = T.tr(r.hidden ? "marker.show" : "marker.hide");
        }
        int clipY = Math.max(y, top);
        int clipH = Math.min(y + rowH - 2, bottom) - clipY;
        if (clipH > 0) {
            hit(rx, clipY, rw, clipH, () -> openRegionView(r));
            if (y + 4 >= top && y + 16 <= bottom) {
                hit(ex, y + 4, 12, 12, () -> {
                    r.hidden = !r.hidden;
                    session.saveRegions();
                });
            }
        }
    }

    private void drawList(GuiGraphics g, int mx, int my) {
        int lx = 6 + TB + 8;
        int ly = 6;
        int lw = listWidth();
        int lh = height - 12;
        if (Paper.inside(mx, my, lx, ly, lw, lh)) {
            tooltip = null;
        }
        Paper.panel(g, lx, ly, lw, lh);
        ui(lx, ly, lw, lh);
        hit(lx, ly, lw, lh, () -> {
        });
        int total = session.regions(dim).size();
        for (Marker m : session.markers) {
            if (m.dim.equals(dim)) {
                total++;
            }
        }
        Paper.title(g, font, T.s("list.title", total), lx + lw / 2, ly + 8);

        // one global search over markers and regions
        int sy = ly + 20;
        drawListSearch(g, searchBox, lx + 7, sy, lw - 14, "list.search_objects");

        boolean globalSearch = !search.isBlank();
        List<Marker> markers = sortedMarkers(globalSearch ? search : markerSearch);
        List<Region> regions = sortedRegions(globalSearch ? search : regionSearch);
        int top = sy + 20;
        int bottom = ly + lh - 8;
        int areaH = bottom - top;
        int rowH = 22;
        int contentH;
        if (globalSearch) {
            int found = markers.size() + regions.size();
            contentH = found == 0 ? 18 : found * rowH;
        } else {
            contentH = listSectionHeight(markersOpen, markers.size()) + 4
                    + listSectionHeight(regionsOpen, regions.size());
        }
        double maxScroll = Math.max(0, contentH - areaH);
        listScroll = Math.max(0, Math.min(maxScroll, listScroll));
        scroll(lx, top, lw, areaH, d -> listScroll -= d * 22);
        g.enableScissor(lx + 3, top, lx + lw - 3, bottom);
        double[] ref = referencePoint();
        int y = top - (int) listScroll;

        if (globalSearch) {
            // no section headers while searching: show every match in one flat list
            if (markers.isEmpty() && regions.isEmpty()) {
                g.drawString(font, T.tr("list.nothing_found"), lx + 12, y + 4, Paper.DISABLED, false);
            } else {
                for (Marker m : markers) {
                    if (y + rowH >= top && y <= bottom) {
                        drawMarkerRow(g, mx, my, lx, lw, top, bottom, y, rowH, m, ref);
                    }
                    y += rowH;
                }
                for (Region r : regions) {
                    if (y + rowH >= top && y <= bottom) {
                        drawRegionRow(g, mx, my, lx, lw, top, bottom, y, rowH, r, ref);
                    }
                    y += rowH;
                }
            }
        } else {
            boolean anyMarker = session.markers.stream().anyMatch(m -> m.dim.equals(dim) && !m.hidden);
            drawListHeader(g, mx, my, lx + 6, y, lw - 12, top, bottom, "list.section.markers", markers.size(),
                    markersOpen, MARKER_SORTS, cfg.markerSort,
                    this::toggleMarkersSection,
                    () -> {
                        cfg.markerSort = (cfg.markerSort + 1) % MARKER_SORTS.length;
                        cfg.save();
                    },
                    () -> {
                        cfg.markerSort = (cfg.markerSort + MARKER_SORTS.length - 1) % MARKER_SORTS.length;
                        cfg.save();
                    },
                    () -> {
                        boolean hide = session.markers.stream().anyMatch(m -> m.dim.equals(dim) && !m.hidden);
                        session.markers.forEach(m -> {
                            if (m.dim.equals(dim)) {
                                m.hidden = hide;
                            }
                        });
                        session.saveMarkers();
                    }, anyMarker);
            y += 18;
            if (markersOpen) {
                drawListSearch(g, markerSearchBox, lx + 12, y, lw - 24, "list.search_markers");
                y += 20;
                if (markers.isEmpty()) {
                    g.drawString(font, T.tr(session.markers.stream().anyMatch(m -> m.dim.equals(dim))
                                    ? "list.nothing_found" : "list.empty"),
                            lx + 12, y + 4, Paper.DISABLED, false);
                    y += 18;
                } else {
                    for (Marker m : markers) {
                        if (y + rowH >= top && y <= bottom) {
                            drawMarkerRow(g, mx, my, lx, lw, top, bottom, y, rowH, m, ref);
                        }
                        y += rowH;
                    }
                }
            }
            y += 4;

            boolean anyRegion = session.regions(dim).stream().anyMatch(r -> !r.hidden);
            drawListHeader(g, mx, my, lx + 6, y, lw - 12, top, bottom, "list.section.regions", regions.size(),
                    regionsOpen, REGION_SORTS, cfg.regionSort,
                    this::toggleRegionsSection,
                    () -> {
                        cfg.regionSort = (cfg.regionSort + 1) % REGION_SORTS.length;
                        cfg.save();
                    },
                    () -> {
                        cfg.regionSort = (cfg.regionSort + REGION_SORTS.length - 1) % REGION_SORTS.length;
                        cfg.save();
                    },
                    () -> {
                        boolean hide = session.regions(dim).stream().anyMatch(r -> !r.hidden);
                        session.regions(dim).forEach(r -> r.hidden = hide);
                        session.saveRegions();
                    }, anyRegion);
            y += 18;
            if (regionsOpen) {
                drawListSearch(g, regionSearchBox, lx + 12, y, lw - 24, "list.search_regions");
                y += 20;
                if (regions.isEmpty()) {
                    g.drawString(font, T.tr(session.regions(dim).isEmpty() ? "list.empty_regions" : "list.nothing_found"),
                            lx + 12, y + 4, Paper.DISABLED, false);
                    y += 18;
                } else {
                    for (Region r : regions) {
                        if (y + rowH >= top && y <= bottom) {
                            drawRegionRow(g, mx, my, lx, lw, top, bottom, y, rowH, r, ref);
                        }
                        y += rowH;
                    }
                }
            }
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int trackH = areaH;
            int thumbH = Math.max(12, (int) (trackH * (areaH / (double) contentH)));
            int thumbY = top + (int) ((trackH - thumbH) * (listScroll / maxScroll));
            g.fill(lx + lw - 5, top, lx + lw - 4, bottom, Paper.INK_FAINT);
            g.fill(lx + lw - 6, thumbY, lx + lw - 3, thumbY + thumbH, Paper.INK_SOFT);
        }
    }

    private void toggleMarkersSection() {
        markersOpen = !markersOpen;
        if (!markersOpen) {
            setFocused(null);
        }
        listScroll = 0;
    }

    private void toggleRegionsSection() {
        regionsOpen = !regionsOpen;
        if (!regionsOpen) {
            setFocused(null);
        }
        listScroll = 0;
    }

    // ------------------------------------------------------------------ right panel


    private int[] panelRect() {
        int pw = panelWidth();
        return new int[]{width - pw - 6, 6, pw, height - 12};
    }

    private void drawViewPanel(GuiGraphics g, int mx, int my) {
        if (regionMode) {
            drawRegionViewPanel(g, mx, my);
            return;
        }
        if (selected == null) {
            panel = Panel.NONE;
            return;
        }
        Marker m = selected;
        int[] r = panelRect();
        int px = r[0];
        int py = r[1];
        int pw = r[2];
        int ph = r[3];
        if (Paper.inside(mx, my, px, py, pw, ph)) {
            tooltip = null;
        }
        Paper.panel(g, px, py, pw, ph);
        ui(px, py, pw, ph);
        hit(px, py, pw, ph, () -> {
        });
        closeButton(g, mx, my, px + pw - 18, py + 5, this::closePanel);

        int cx = px + 9;
        int cw = pw - 18;
        int y = py + 10;
        Paper.inset(g, cx, y, 28, 28);
        Paper.markerIcon(g, m.icon, m.color, cx + 2, y + 2, 24, 255);
        if (overlay == Overlay.NONE && Paper.inside(mx, my, cx, y, 28, 28)) {
            tooltip = T.tr("color.tip");
        }
        hit(cx, y, 28, 28, () -> {
        }, () -> openColors(m, m.icon));
        List<FormattedCharSequence> nameLines = font.split(Component.literal(m.name.isEmpty() ? T.s("marker.unnamed") : m.name), cw - 36 - 10);
        int ny = y + (nameLines.size() > 1 ? 1 : 5);
        for (int i = 0; i < Math.min(2, nameLines.size()); i++) {
            g.drawString(font, nameLines.get(i), cx + 34, ny + i * 10, Paper.INK, false);
        }
        if (nameLines.size() < 2) {
            g.drawString(font, T.iconName(m.icon), cx + 34, y + 17, Paper.INK_SOFT, false);
        }
        y += 34;
        g.drawString(font, "X " + m.x + "   Y " + m.y + "   Z " + m.z, cx, y, Paper.INK, false);
        y += 11;
        String where = T.dimName(m.dim);
        double[] ref = referencePoint();
        if (m.dim.equals(dim)) {
            where += "  ·  " + fmtInt(Math.hypot(m.x - ref[0], m.z - ref[1])) + " " + T.s("unit.blocks");
        }
        g.drawString(font, where, cx, y, Paper.INK_SOFT, false);
        y += 13;
        Paper.separator(g, cx, y, cw);
        y += 5;

        g.drawString(font, T.tr("field.desc"), cx, y, Paper.INK_SOFT, false);
        y += 11;
        List<FormattedCharSequence> desc = m.description.isBlank()
                ? List.of(T.tr("marker.no_desc").getVisualOrderText())
                : font.split(Component.literal(m.description), cw - 6);
        int descH = Math.min(desc.size() * 10 + 6, Math.max(30, (int) (ph * 0.28)));
        Paper.inset(g, cx, y, cw, descH);
        double maxDesc = Math.max(0, desc.size() * 10 + 6 - descH);
        descScroll = Math.max(0, Math.min(maxDesc, descScroll));
        scroll(cx, y, cw, descH, d -> descScroll -= d * 10);
        g.enableScissor(cx + 1, y + 1, cx + cw - 1, y + descH - 1);
        int ty = y + 4 - (int) descScroll;
        for (FormattedCharSequence l : desc) {
            g.drawString(font, l, cx + 3, ty, m.description.isBlank() ? Paper.DISABLED : Paper.INK, false);
            ty += 10;
        }
        g.disableScissor();
        y += descH + 6;
        Paper.separator(g, cx, y, cw);
        y += 5;

        boolean tp = cfg.teleport && session.isLive();
        int rows = tp ? 3 : 2;
        int buttonsY = py + ph - 8 - 16 * rows - 4 * (rows - 1);
        g.drawString(font, T.tr("field.shots", m.screenshots.size()), cx, y, Paper.INK_SOFT, false);
        y += 11;
        drawShotGrid(g, mx, my, m, cx, y, cw, buttonsY - 6 - y, false);

        int bw = (cw - 4) / 2;
        int y2 = buttonsY + 20;
        boolean h2 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("trash"), T.tr("button.delete"), cx, y2, bw, 16, h2, true);
        hit(cx, y2, bw, 16, () -> confirmDelete(m));
        boolean h1 = overlay == Overlay.NONE && Paper.inside(mx, my, cx + bw + 4, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("gear"), T.tr("button.configure"), cx + bw + 4, y2, bw, 16, h1, true);
        if (h1) {
            tooltip = T.tr("button.configure_tip");
        }
        hit(cx + bw + 4, y2, bw, 16, () -> openEdit(m, false));
        y2 = buttonsY;
        boolean h3 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool(m.hidden ? "eye" : "eye_off"), T.tr(m.hidden ? "marker.show" : "marker.hide"), cx, y2, bw, 16, h3, true);
        hit(cx, y2, bw, 16, () -> {
            m.hidden = !m.hidden;
            session.saveMarkers();
        });
        boolean h4 = overlay == Overlay.NONE && Paper.inside(mx, my, cx + bw + 4, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("target"), T.tr("button.goto"), cx + bw + 4, y2, bw, 16, h4, true);
        hit(cx + bw + 4, y2, bw, 16, () -> centerOn(m));
        if (tp) {
            int y3 = buttonsY + 40;
            boolean h5 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y3, cw, 16);
            Paper.iconTextButton(g, font, Paper.tool("arrow"), T.tr("button.teleport"), cx, y3, cw, 16, h5, true);
            if (h5) {
                tooltip = T.tr("button.teleport_tip");
            }
            hit(cx, y3, cw, 16, () -> teleportTo(m));
        }
    }

    /** Sends a /tp command; it works where the player may use commands (cheats on or operator). */
    private void teleportTo(Marker m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        net.minecraft.resources.ResourceLocation dim = net.minecraft.resources.ResourceLocation.tryParse(m.dim);
        if (dim == null || !dim.toString().equals(m.dim)) {
            return;
        }
        mc.player.connection.sendCommand("execute in " + dim + " run tp @s " + m.x + ".5 " + m.y + " " + m.z + ".5");
        onClose();
    }

    private void drawEditPanel(GuiGraphics g, int mx, int my) {
        if (regionMode) {
            drawRegionEditPanel(g, mx, my);
            return;
        }
        if (working == null) {
            panel = Panel.NONE;
            return;
        }
        int[] r = panelRect();
        int px = r[0];
        int py = r[1];
        int pw = r[2];
        int ph = r[3];
        if (Paper.inside(mx, my, px, py, pw, ph)) {
            tooltip = null;
        }
        Paper.panel(g, px, py, pw, ph);
        ui(px, py, pw, ph);
        hit(px, py, pw, ph, () -> setFocused(null));
        Paper.title(g, font, T.s(workingIsNew ? "edit.new" : "edit.title"), px + pw / 2, py + 8);
        closeButton(g, mx, my, px + pw - 18, py + 5, this::closePanel);
        if (!workingIsNew) {
            boolean ht = overlay == Overlay.NONE && Paper.inside(mx, my, px + 5, py + 5, 13, 13);
            Paper.iconButton(g, Paper.tool("trash"), px + 5, py + 5, 13, ht, false);
            if (ht) {
                tooltip = T.tr("button.delete");
            }
            hit(px + 5, py + 5, 13, 13, () -> confirmDelete(selected));
        }

        int cx = px + 9;
        int cw = pw - 18;
        int y = py + 22;
        g.drawString(font, T.tr("field.name"), cx, y, Paper.INK_SOFT, false);
        y += 10;
        Paper.inset(g, cx, y, cw, 16);
        nameBox.setX(cx + 4);
        nameBox.setY(y + 4);
        nameBox.setWidth(cw - 8);
        hint(g, nameBox, T.s("field.name_hint"));
        y += 20;

        // icon selector
        boolean hi = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y, cw, 22);
        g.fill(cx + 1, y + 1, cx + cw + 1, y + 23, Paper.SHADOW);
        Paper.tiled(g, Paper.PARCHMENT_DARK, cx, y, cw, 22, cx, y);
        if (hi) {
            g.fill(cx, y, cx + cw, y + 22, 0x30FFFFFF);
        }
        Paper.outline(g, cx, y, cw, 22, hi ? Paper.GOLD : Paper.INK);
        Paper.markerIcon(g, working.icon, working.color, cx + 3, y + 3, 16, 255);
        if (hi) {
            tooltip = T.tr("edit.icon_tip");
        }
        g.drawString(font, font.plainSubstrByWidth(T.s("edit.icon", T.iconName(working.icon)), cw - 34), cx + 23, y + 7, Paper.INK, false);
        g.drawString(font, "▼", cx + cw - 10, y + 7, Paper.INK_SOFT, false);
        int iconY = y;
        hit(cx, y, cw, 22, () -> {
            iconPickerY = iconY + 24;
            iconScroll = 0;
            iconSearch.setValue("");
            overlay = Overlay.ICONS;
            iconSearch.setVisible(true);
            setFocused(iconSearch);
        }, () -> openColors(working, working.icon));
        y += 26;

        // coordinates
        g.drawString(font, T.tr("field.coords"), cx, y, Paper.INK_SOFT, false);
        Minecraft mc = Minecraft.getInstance();
        if (session.isLive() && mc.player != null) {
            int bx = cx + cw - 12;
            boolean hp = overlay == Overlay.NONE && Paper.inside(mx, my, bx, y - 2, 12, 12);
            Paper.icon(g, Paper.tool("target"), bx, y - 2, 12, hp ? 0xFFFFFFFF : 0xB0FFFFFF);
            if (hp) {
                tooltip = T.tr("edit.here");
            }
            hit(bx, y - 2, 12, 12, () -> {
                working.x = mc.player.getBlockX();
                working.y = mc.player.getBlockY();
                working.z = mc.player.getBlockZ();
                xBox.setValue(Integer.toString(working.x));
                yBox.setValue(Integer.toString(working.y));
                zBox.setValue(Integer.toString(working.z));
                working.dim = mc.level.dimension().location().toString();
            });
        }
        y += 10;
        int bw = (cw - 8) / 3;
        EditBox[] boxes = {xBox, yBox, zBox};
        String[] labels = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            int bx = cx + i * (bw + 4);
            Paper.inset(g, bx, y, bw, 16);
            g.drawString(font, labels[i], bx + 3, y + 4, Paper.INK_SOFT, false);
            boxes[i].setX(bx + 11);
            boxes[i].setY(y + 4);
            boxes[i].setWidth(bw - 13);
        }
        y += 21;
        if (coordsChanged()) {
            boolean hr = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y, cw, 12);
            Paper.button(g, font, T.tr("button.revert"), cx, y, cw, 12, hr, true);
            if (hr) {
                tooltip = T.tr("edit.revert_tip");
            }
            hit(cx, y, cw, 12, this::revertCoords);
            y += 15;
        }

        g.drawString(font, T.tr("field.desc"), cx, y, Paper.INK_SOFT, false);
        y += 10;
        int descH = Math.max(40, Math.min(90, (int) (ph * 0.22)));
        Paper.inset(g, cx, y, cw, descH);
        descBox.setX(cx + 1);
        descBox.setY(y + 1);
        descBox.setWidth(cw - 2);
        descBox.setHeight(descH - 2);
        y += descH + 6;

        int buttonsY = py + ph - 8 - 16;
        g.drawString(font, T.tr("field.shots", working.screenshots.size()), cx, y, Paper.INK_SOFT, false);
        y += 11;
        drawShotGrid(g, mx, my, working, cx, y, cw, buttonsY - 6 - y, true);

        int b2 = (cw - 4) / 2;
        boolean h1 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, buttonsY, b2, 16);
        Paper.iconTextButton(g, font, Paper.tool("check"), T.tr("button.save"), cx, buttonsY, b2, 16, h1, true);
        hit(cx, buttonsY, b2, 16, this::saveAndView);
        boolean h2 = overlay == Overlay.NONE && Paper.inside(mx, my, cx + b2 + 4, buttonsY, b2, 16);
        Paper.iconTextButton(g, font, Paper.tool("close"), T.tr("button.cancel"), cx + b2 + 4, buttonsY, b2, 16, h2, true);
        hit(cx + b2 + 4, buttonsY, b2, 16, this::cancelEdit);
    }

    /** Screenshot thumbnails, two per row. In edit mode a "+" tile and delete buttons are shown. */
    private void drawShotGrid(GuiGraphics g, int mx, int my, Marker marker, int x, int y, int w, int h, boolean edit) {
        List<String> shots = marker.screenshots;
        if (h < 20) {
            return;
        }
        int gap = 4;
        int tw = (w - gap) / 2;
        int th = Math.max(20, tw * 9 / 16);
        int count = shots.size() + (edit ? 1 : 0);
        int rows = (count + 1) / 2;
        double max = Math.max(0, rows * (th + gap) - gap - h);
        shotsScroll = Math.max(0, Math.min(max, shotsScroll));
        scroll(x, y, w, h, d -> shotsScroll -= d * (th + gap) / 2.0);
        if (count == 0) {
            g.drawString(font, T.tr("marker.no_shots"), x + 2, y + 2, Paper.DISABLED, false);
            return;
        }
        g.enableScissor(x - 1, y, x + w + 1, y + h);
        for (int i = 0; i < count; i++) {
            int col = i % 2;
            int row = i / 2;
            int tx = x + col * (tw + gap);
            int ty = y + row * (th + gap) - (int) shotsScroll;
            if (ty + th < y || ty > y + h) {
                continue;
            }
            boolean hover = overlay == Overlay.NONE && Paper.inside(mx, my, tx, ty, tw, th) && my >= y && my < y + h;
            int clipY = Math.max(ty, y);
            int clipH = Math.min(ty + th, y + h) - clipY;
            if (edit && i == shots.size()) {
                g.fill(tx, ty, tx + tw, ty + th, hover ? 0x40FFFFFF : 0x20FFFFFF);
                Paper.dashedLine(g, tx, ty + 0.5f, tx + tw, ty + 0.5f, 1, Paper.INK_SOFT, 3);
                Paper.dashedLine(g, tx, ty + th - 0.5f, tx + tw, ty + th - 0.5f, 1, Paper.INK_SOFT, 3);
                Paper.dashedLine(g, tx + 0.5f, ty, tx + 0.5f, ty + th, 1, Paper.INK_SOFT, 3);
                Paper.dashedLine(g, tx + tw - 0.5f, ty, tx + tw - 0.5f, ty + th, 1, Paper.INK_SOFT, 3);
                Paper.icon(g, Paper.tool("plus"), tx + (tw - 16) / 2, ty + (th - 16) / 2, 16);
                if (hover) {
                    tooltip = T.tr("shots.add_tip");
                }
                if (clipH > 0) {
                    hit(tx, clipY, tw, clipH, this::openPicker);
                }
                continue;
            }
            String file = shots.get(i);
            Paper.inset(g, tx, ty, tw, th);
            ImageCache.Entry e = ImageCache.get(session.screenshot(file), true);
            if (e.ready()) {
                ImageCache.drawCover(g, e, tx + 1, ty + 1, tw - 2, th - 2);
                List<Stroke> ann = marker.shotDrawings == null ? null : marker.shotDrawings.get(file);
                if (ann != null && !ann.isEmpty() && e.origWidth > 0) {
                    float sc = Math.max((tw - 2) / (float) e.origWidth, (th - 2) / (float) e.origHeight);
                    annotView.setRect(tx + 1, ty + 1, tw - 2, th - 2);
                    annotView.zoom = sc;
                    annotView.cx = e.origWidth / 2.0;
                    annotView.cz = e.origHeight / 2.0;
                    g.enableScissor(tx + 1, Math.max(ty + 1, y), tx + tw - 1, Math.min(ty + th - 1, y + h));
                    annotView.drawStrokes(g, ann, sc);
                    g.disableScissor();
                }
            } else {
                String s = e.failed ? "✖" : "…";
                g.drawString(font, s, tx + (tw - font.width(s)) / 2, ty + (th - 8) / 2, Paper.INK_SOFT, false);
            }
            Paper.outline(g, tx, ty, tw, th, hover ? Paper.GOLD : Paper.INK);
            int idx = i;
            if (clipH > 0) {
                hit(tx, clipY, tw, clipH, () -> openViewer(marker, idx));
            }
            if (edit) {
                int dx = tx + tw - 12;
                int dy = ty + 2;
                boolean dh = hover && Paper.inside(mx, my, dx, dy, 10, 10);
                g.fill(dx, dy, dx + 10, dy + 10, dh ? Paper.a(Paper.CREAM, 0xE0) : Paper.a(Paper.CREAM, 0xB0));
                Paper.outline(g, dx, dy, 10, 10, Paper.INK);
                Paper.icon(g, Paper.tool("close"), dx + 1, dy + 1, 8);
                if (dh) {
                    tooltip = T.tr("shots.remove");
                }
                if (dy >= y && dy + 10 <= y + h) {
                    hit(dx, dy, 10, 10, () -> {
                        String f = working.screenshots.remove(idx);
                        working.shotDrawings.remove(f);
                        if (addedFiles.remove(f)) {
                            deleteShot(f);
                        } else {
                            removedFiles.add(f);
                        }
                    });
                }
            }
        }
        g.disableScissor();
    }

    private void hint(GuiGraphics g, EditBox box, String text) {
        if (box.getValue().isEmpty() && !box.isFocused()) {
            g.drawString(font, font.plainSubstrByWidth(text, box.getWidth()), box.getX(), box.getY(), Paper.HINT, false);
        }
    }

    private void closeButton(GuiGraphics g, int mx, int my, int x, int y, Runnable action) {
        boolean h = overlay == Overlay.NONE && Paper.inside(mx, my, x, y, 13, 13);
        Paper.iconButton(g, Paper.tool("close"), x, y, 13, h, false);
        hit(x, y, 13, 13, action);
    }

    // ------------------------------------------------------------------ overlays

    private void blocker(Runnable onOutside) {
        hit(0, 0, width, height, onOutside, onOutside);
    }

    private void drawIconPicker(GuiGraphics g, int mx, int my) {
        blocker(() -> overlay = Overlay.NONE);
        g.fill(0, 0, width, height, Paper.DIM);
        int cell = 22;
        int ow = Math.min(width - 16, 18 * cell + 24);
        int cols = Math.max(4, (ow - 20) / cell);
        int oh = height - 16;
        int ox = (width - ow) / 2;
        int oy = 8;
        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {
        });
        Paper.title(g, font, T.s("edit.choose_icon") + "  (" + T.MARKER_ICONS.size() + ")", ox + ow / 2, oy + 7);
        boolean hc = Paper.inside(mx, my, ox + ow - 18, oy + 5, 13, 13);
        Paper.iconButton(g, Paper.tool("close"), ox + ow - 18, oy + 5, 13, hc, false);
        hit(ox + ow - 18, oy + 5, 13, 13, () -> overlay = Overlay.NONE);

        int gx = ox + (ow - cols * cell) / 2 + 1;
        int gridW = cols * cell - 2;
        // search field
        int sy = oy + 20;
        Paper.inset(g, gx, sy, gridW, 15);
        Paper.icon(g, Paper.tool("zoom_in"), gx + 2, sy + 2, 11);
        iconSearch.setX(gx + 16);
        iconSearch.setY(sy + 4);
        iconSearch.setWidth(gridW - 20);
        iconSearch.render(g, mx, my, 0);
        if (iconSearch.getValue().isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(T.s("icons.search_hint"), gridW - 26), gx + 20, sy + 4, Paper.HINT, false);
        }
        g.drawString(font, font.plainSubstrByWidth(T.s("icons.color_hint"), gridW), gx, sy + 19, Paper.INK_SOFT, false);

        int top = sy + 31;
        int bottom = oy + oh - 8;
        String query = iconSearch.getValue().trim();
        List<String> icons = query.isEmpty() ? T.MARKER_ICONS : T.searchIcons(query);
        int contentH = (icons.size() + cols - 1) / cols * cell;
        double max = Math.max(0, contentH - (bottom - top));
        iconScroll = Math.max(0, Math.min(max, iconScroll));
        scroll(gx, top, gridW, bottom - top, d -> iconScroll -= d * cell * 2);
        Paper.separator(g, gx, top - 3, gridW);
        g.enableScissor(gx - 2, top, gx + gridW + 2, bottom);
        int y = top + 1 - (int) iconScroll;
        for (int i = 0; i < icons.size(); i++) {
            String icon = icons.get(i);
            int cx = gx + (i % cols) * cell;
            int cy = y + (i / cols) * cell;
            if (cy + cell < top || cy > bottom) {
                continue;
            }
            boolean hover = Paper.inside(mx, my, cx, cy, cell - 2, cell - 2) && my >= top && my < bottom;
            boolean sel = working != null && icon.equals(working.icon);
            if (sel) {
                g.fill(cx, cy, cx + cell - 2, cy + cell - 2, Paper.a(Paper.GOLD, 0x50));
                Paper.outline(g, cx, cy, cell - 2, cell - 2, Paper.GOLD);
            } else if (hover) {
                g.fill(cx, cy, cx + cell - 2, cy + cell - 2, 0x30FFFFFF);
                Paper.outline(g, cx, cy, cell - 2, cell - 2, Paper.INK_SOFT);
            }
            Paper.markerIcon(g, icon, sel ? working.color : 0, cx + (cell - 2 - 16) / 2, cy + (cell - 2 - 16) / 2, 16, 255);
            if (hover) {
                tooltip = Component.literal(T.iconName(icon));
            }
            int clipY = Math.max(cy, top);
            int clipH = Math.min(cy + cell - 2, bottom) - clipY;
            if (clipH > 0) {
                hit(cx, clipY, cell - 2, clipH, () -> {
                    if (working != null) {
                        working.icon = icon;
                    }
                    overlay = Overlay.NONE;
                    Paper.click();
                }, () -> openColors(working, icon));
            }
        }
        if (icons.isEmpty()) {
            g.drawString(font, T.s("list.nothing_found"), gx, top + 8, Paper.DISABLED, false);
        }
        g.disableScissor();
        if (max > 0) {
            int trackH = bottom - top;
            int thumbH = Math.max(12, (int) (trackH * (trackH / (double) contentH)));
            int thumbY = top + (int) ((trackH - thumbH) * (iconScroll / max));
            g.fill(ox + ow - 6, top, ox + ow - 5, bottom, Paper.INK_FAINT);
            g.fill(ox + ow - 7, thumbY, ox + ow - 4, thumbY + thumbH, Paper.INK_SOFT);
        }
    }

    /** Opens the colour chooser for an icon of a marker (the edited copy or a saved marker). */
    private void openColors(Marker target, String icon) {
        if (target == null) {
            return;
        }
        colorTarget = target;
        colorIcon = icon;
        overlay = Overlay.COLOR;
        Paper.click();
    }

    private void drawColorPicker(GuiGraphics g, int mx, int my) {
        blocker(() -> overlay = Overlay.NONE);
        g.fill(0, 0, width, height, Paper.DIM);
        int ow = PAL_COLS * 11 + 16;
        int oh = 110;
        int ox = (width - ow) / 2;
        int oy = (height - oh) / 2;
        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {
        });
        Paper.title(g, font, T.s("color.title"), ox + ow / 2, oy + 7);
        boolean hc = Paper.inside(mx, my, ox + ow - 18, oy + 5, 13, 13);
        Paper.iconButton(g, Paper.tool("close"), ox + ow - 18, oy + 5, 13, hc, false);
        hit(ox + ow - 18, oy + 5, 13, 13, () -> overlay = Overlay.NONE);

        int py = oy + 68;
        int hovered = -1;
        for (int i = 0; i < COLORS.length; i++) {
            int sx = ox + 8 + (i % PAL_COLS) * 11;
            int sy = py + (i / PAL_COLS) * 11;
            if (Paper.inside(mx, my, sx - 1, sy - 1, 11, 11)) {
                hovered = i;
            }
        }
        int current = colorIcon.equals(colorTarget.icon) ? colorTarget.color : 0;
        int shown = hovered >= 0 ? COLORS[hovered] : current;
        Paper.inset(g, ox + 8, oy + 22, 40, 40);
        Paper.markerIcon(g, colorIcon, shown, ox + 12, oy + 26, 32, 255);
        g.drawString(font, font.plainSubstrByWidth(T.iconName(colorIcon), ow - 64), ox + 54, oy + 25, Paper.INK, false);
        int bx = ox + 54;
        int bw = ow - 62;
        boolean hr = Paper.inside(mx, my, bx, oy + 42, bw, 16);
        Paper.button(g, font, T.tr("color.original"), bx, oy + 42, bw, 16, hr, true);
        hit(bx, oy + 42, bw, 16, () -> applyColor(0));
        for (int i = 0; i < COLORS.length; i++) {
            int c = COLORS[i];
            int sx = ox + 8 + (i % PAL_COLS) * 11;
            int sy = py + (i / PAL_COLS) * 11;
            g.fill(sx - 1, sy - 1, sx + 10, sy + 10, Paper.INK);
            g.fill(sx, sy, sx + 9, sy + 9, c);
            if (c == current || i == hovered) {
                Paper.outline(g, sx - 2, sy - 2, 13, 13, Paper.GOLD_LIGHT);
                Paper.outline(g, sx - 1, sy - 1, 11, 11, Paper.GOLD);
            }
            hit(sx - 1, sy - 1, 11, 11, () -> applyColor(c));
        }
    }

    private void applyColor(int color) {
        Marker m = colorTarget;
        if (m != null) {
            m.icon = colorIcon;
            m.color = color;
            if (m != working) {
                session.saveMarkers();
            }
        }
        overlay = Overlay.NONE;
        Paper.click();
    }

    /** Opens the compact colour chooser used by the region settings. */
    private void openRegionColors(Marker target, Region region) {
        colorMarker = target;
        colorRegion = region;
        overlay = Overlay.REGION_COLOR;
        Paper.click();
    }

    private void applyRegionColor(int color) {
        if (colorMarker != null) {
            colorMarker.color = color;
        }
        overlay = Overlay.NONE;
        Paper.click();
    }

    /** Small centred palette without the drawing controls. */
    private void drawRegionColorPicker(GuiGraphics g, int mx, int my) {
        blocker(() -> overlay = Overlay.NONE);
        g.fill(0, 0, width, height, Paper.DIM);
        int ow = PAL_COLS * 11 + 16;
        int oh = 80;
        int ox = (width - ow) / 2;
        int oy = (height - oh) / 2;
        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {
        });
        Paper.title(g, font, T.s("region.color"), ox + ow / 2, oy + 7);
        boolean hc = Paper.inside(mx, my, ox + ow - 18, oy + 5, 13, 13);
        Paper.iconButton(g, Paper.tool("close"), ox + ow - 18, oy + 5, 13, hc, false);
        hit(ox + ow - 18, oy + 5, 13, 13, () -> overlay = Overlay.NONE);

        int current = colorMarker == null ? 0 : colorMarker.color;
        int py = oy + 22;
        for (int i = 0; i < COLORS.length; i++) {
            int sx = ox + 8 + (i % PAL_COLS) * 11;
            int sy = py + (i / PAL_COLS) * 11;
            g.fill(sx - 1, sy - 1, sx + 10, sy + 10, Paper.INK);
            g.fill(sx, sy, sx + 9, sy + 9, COLORS[i]);
            if ((COLORS[i] & 0xFFFFFF) == (current & 0xFFFFFF)) {
                Paper.outline(g, sx - 2, sy - 2, 13, 13, Paper.GOLD_LIGHT);
                Paper.outline(g, sx - 1, sy - 1, 11, 11, Paper.GOLD);
            }
            int c = COLORS[i];
            hit(sx - 1, sy - 1, 11, 11, () -> applyRegionColor(c));
        }
        if (colorRegion != null) {
            int bw = PAL_COLS * 11;
            int by = py + 3 * 11 + 3;
            boolean hf = Paper.inside(mx, my, ox + 8, by, bw, 16);
            Paper.button(g, font, T.tr("region.fill", onOff(colorRegion.filled)),
                    ox + 8, by, bw, 16, hf, true);
            hit(ox + 8, by, bw, 16, () -> colorRegion.filled = !colorRegion.filled);
        }
    }

    private void openPicker() {
        pickerChosen.clear();
        pickerScroll = 0;
        pickerFiles = new ArrayList<>();
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                pickerFiles = s.filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                        })
                        .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                        .limit(300)
                        .toList();
            } catch (IOException e) {
                Storage.LOG.warn("Failed to list screenshots", e);
            }
        }
        overlay = Overlay.PICKER;
    }

    private void drawShotPicker(GuiGraphics g, int mx, int my) {
        blocker(() -> {
        });
        g.fill(0, 0, width, height, Paper.DIM);
        int ow = Math.min(width - 30, 440);
        int oh = height - 30;
        int ox = (width - ow) / 2;
        int oy = 15;
        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {
        });
        Paper.title(g, font, T.s("shots.title"), ox + ow / 2, oy + 8);
        List<FormattedCharSequence> hint = font.split(T.tr("shots.hint"), ow - 24);
        int y = oy + 22;
        for (FormattedCharSequence l : hint) {
            g.drawString(font, l, ox + 12, y, Paper.INK_SOFT, false);
            y += 10;
        }
        y += 4;
        int bottom = oy + oh - 28;
        int gx = ox + 10;
        int gw = ow - 20;
        int cols = gw >= 330 ? 4 : 3;
        int gap = 5;
        int tw = (gw - gap * (cols - 1)) / cols;
        int th = tw * 9 / 16;
        int rows = (pickerFiles.size() + cols - 1) / cols;
        int areaH = bottom - y;
        double max = Math.max(0, rows * (th + gap) - areaH);
        pickerScroll = Math.max(0, Math.min(max, pickerScroll));
        scroll(gx, y, gw, areaH, d -> pickerScroll -= d * (th + gap) / 2.0);
        if (pickerFiles.isEmpty()) {
            for (FormattedCharSequence l : font.split(T.tr("shots.empty"), gw - 10)) {
                g.drawString(font, l, gx + 4, y + 6, Paper.DISABLED, false);
                y += 10;
            }
        } else {
            g.enableScissor(gx - 1, y, gx + gw + 1, bottom);
            for (int i = 0; i < pickerFiles.size(); i++) {
                int tx = gx + (i % cols) * (tw + gap);
                int ty = y + (i / cols) * (th + gap) - (int) pickerScroll;
                if (ty + th < y || ty > bottom) {
                    continue;
                }
                Path p = pickerFiles.get(i);
                boolean chosen = pickerChosen.contains(p);
                boolean hover = Paper.inside(mx, my, tx, ty, tw, th) && my >= y && my < bottom;
                Paper.inset(g, tx, ty, tw, th);
                ImageCache.Entry e = ImageCache.get(p, true);
                if (e.ready()) {
                    ImageCache.drawCover(g, e, tx + 1, ty + 1, tw - 2, th - 2);
                }
                if (chosen) {
                    g.fill(tx, ty, tx + tw, ty + th, Paper.a(Paper.GOLD_LIGHT, 0x40));
                    Paper.icon(g, Paper.tool("check"), tx + tw - 18, ty + 2, 16);
                }
                Paper.outline(g, tx, ty, tw, th, chosen ? Paper.GOLD : hover ? Paper.GOLD_LIGHT : Paper.INK);
                if (hover) {
                    tooltip = Component.literal(p.getFileName().toString());
                }
                int clipY = Math.max(ty, y);
                int clipH = Math.min(ty + th, bottom) - clipY;
                if (clipH > 0 && !chosen) {
                    hit(tx, clipY, tw, clipH, () -> {
                        pickerChosen.add(p);
                        importShot(p);
                        Paper.click();
                    });
                }
            }
            g.disableScissor();
        }
        int bw = 110;
        int by = oy + oh - 22;
        boolean h1 = Paper.inside(mx, my, ox + 10, by, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("folder"), T.tr("shots.from_file"), ox + 10, by, bw, 16, h1, true);
        hit(ox + 10, by, bw, 16, this::openFileDialog);
        boolean h2 = Paper.inside(mx, my, ox + ow - bw - 10, by, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("check"), T.tr("button.done"), ox + ow - bw - 10, by, bw, 16, h2, true);
        hit(ox + ow - bw - 10, by, bw, 16, () -> overlay = Overlay.NONE);
    }

    private void openViewer(Marker marker, int index) {
        viewerMarker = marker;
        viewerNames = new ArrayList<>(marker.screenshots);
        viewerFiles = viewerNames.stream().map(session::screenshot).toList();
        viewerIndex = index;
        annotating = false;
        annotCurrent = null;
        overlay = Overlay.VIEWER;
    }

    private void drawViewer(GuiGraphics g, int mx, int my) {
        blocker(() -> {
            if (!annotating) {
                overlay = Overlay.NONE;
            }
        });
        g.fill(0, 0, width, height, 0xD0140C06);
        if (viewerFiles.isEmpty()) {
            overlay = Overlay.NONE;
            return;
        }
        viewerIndex = Math.floorMod(viewerIndex, viewerFiles.size());
        Path p = viewerFiles.get(viewerIndex);
        String name = viewerNames.get(viewerIndex);
        ImageCache.Entry e = ImageCache.get(p, false);
        ImageCache.Entry thumb = ImageCache.get(p, true);
        int top = annotating ? 6 + DRAW_BAR_H + 10 : 14;
        int maxW = width - 80;
        int maxH = height - top - 32;
        ImageCache.Entry show = e.ready() ? e : thumb;
        int iw = 160;
        int ih = 90;
        if (show.ready()) {
            float s = Math.min(maxW / (float) show.width, maxH / (float) show.height);
            iw = Math.round(show.width * s);
            ih = Math.round(show.height * s);
        }
        int ix = (width - iw) / 2;
        int iy = top + (maxH - ih) / 2;
        viewerRect = new int[]{ix, iy, iw, ih};
        Paper.panel(g, ix - 6, iy - 6, iw + 12, ih + 12);
        hit(ix - 6, iy - 6, iw + 12, ih + 12, () -> {
        });
        if (show.ready()) {
            ImageCache.drawFit(g, show, ix, iy, iw, ih);
            int ow = show.origWidth > 0 ? show.origWidth : show.width;
            int oh = show.origHeight > 0 ? show.origHeight : show.height;
            annotView.setRect(ix, iy, iw, ih);
            annotView.zoom = iw / (float) ow;
            annotView.cx = ow / 2.0;
            annotView.cz = oh / 2.0;
            g.enableScissor(ix, iy, ix + iw, iy + ih);
            annotView.drawStrokes(g, viewerMarker.drawingsFor(name), annotView.zoom);
            if (annotCurrent != null) {
                annotView.drawStroke(g, annotCurrent, annotView.zoom);
            }
            g.disableScissor();
            if (annotating && drawTool == DrawTool.ERASER && Paper.inside(mx, my, ix, iy, iw, ih)) {
                Paper.outline(g, mx - 6, my - 6, 12, 12, Paper.RED);
            }
        } else {
            String s = show.failed ? T.s("shots.failed") : T.s("shots.loading");
            g.drawString(font, s, ix + (iw - font.width(s)) / 2, iy + ih / 2 - 4, Paper.INK, false);
        }
        if (viewerFiles.size() > 1) {
            boolean hl = Paper.inside(mx, my, 10, height / 2 - 12, 24, 24);
            Paper.iconButton(g, Paper.tool("left"), 10, height / 2 - 12, 24, hl, false);
            hit(10, height / 2 - 12, 24, 24, () -> switchShot(-1));
            boolean hr = Paper.inside(mx, my, width - 34, height / 2 - 12, 24, 24);
            Paper.iconButton(g, Paper.tool("right"), width - 34, height / 2 - 12, 24, hr, false);
            hit(width - 34, height / 2 - 12, 24, 24, () -> switchShot(1));
        }
        String cap = T.s(annotating ? "shots.draw_hint" : "shots.counter", viewerIndex + 1, viewerFiles.size());
        int cw = font.width(cap) + 16;
        Paper.panel(g, (width - cw) / 2, height - 22, cw, 16);
        g.drawString(font, cap, (width - cw) / 2 + 8, height - 18, Paper.INK, false);
        boolean hc = Paper.inside(mx, my, width - 24, 6, 18, 18);
        Paper.iconButton(g, Paper.tool("close"), width - 24, 6, 18, hc, false);
        hit(width - 24, 6, 18, 18, () -> {
            annotating = false;
            overlay = Overlay.NONE;
        });
        boolean hd = Paper.inside(mx, my, width - 46, 6, 18, 18);
        Paper.iconButton(g, Paper.tool("quill"), width - 46, 6, 18, hd, annotating);
        if (hd) {
            tooltip = T.tr(annotating ? "shots.draw_stop" : "shots.draw");
        }
        hit(width - 46, 6, 18, 18, () -> {
            annotating = !annotating;
            annotCurrent = null;
            Paper.click();
        });
        if (annotating) {
            List<Stroke> list = viewerMarker.drawingsFor(name);
            drawToolBar(g, mx, my, (width - DRAW_BAR_W) / 2, 6, true, this::undo, () -> {
                if (!list.isEmpty()) {
                    List<Stroke> copy = new ArrayList<>(list);
                    list.clear();
                    undo.push(() -> list.addAll(copy));
                    annotationsChanged();
                }
            }, T.tr("shots.clear_drawings"));
        }
    }

    private void switchShot(int d) {
        viewerIndex += d;
        annotCurrent = null;
    }

    /** Drawings of the marker being edited are stored on "Save"; otherwise they are written right away. */
    private void annotationsChanged() {
        if (viewerMarker != working) {
            session.saveMarkers();
        }
    }

    private String currentShot() {
        return viewerNames.get(Math.floorMod(viewerIndex, viewerNames.size()));
    }

    private boolean annotationPress(double mx, double my, int button) {
        if (!annotating || button != InputConstants.MOUSE_BUTTON_LEFT || viewerRect == null
                || !Paper.inside(mx, my, viewerRect[0], viewerRect[1], viewerRect[2], viewerRect[3])) {
            return false;
        }
        annotPressed = true;
        double wx = annotView.wx(mx);
        double wz = annotView.wz(my);
        int c = COLORS[colorIdx];
        int w = Math.max(1, Math.round(WIDTHS[widthIdx] / annotView.zoom));
        switch (drawTool) {
            case ERASER -> {
                erased = null;
                eraseShotAt(mx, my);
            }
            case PEN -> annotCurrent = new Stroke(Stroke.PEN, c, w, new double[]{wx, wz});
            case LINE -> annotCurrent = new Stroke(Stroke.LINE, c, w, new double[]{wx, wz, wx, wz});
            case ARROW -> annotCurrent = new Stroke(Stroke.ARROW, c, w, new double[]{wx, wz, wx, wz});
            case RECT -> annotCurrent = new Stroke(Stroke.RECT, c, w, new double[]{wx, wz, wx, wz});
            case CIRCLE -> annotCurrent = new Stroke(Stroke.CIRCLE, c, w, new double[]{wx, wz, wx, wz});
        }
        return true;
    }

    private void annotationDrag(double mx, double my) {
        if (drawTool == DrawTool.ERASER) {
            eraseShotAt(mx, my);
            return;
        }
        if (annotCurrent == null) {
            return;
        }
        double wx = annotView.wx(mx);
        double wz = annotView.wz(my);
        if (drawTool == DrawTool.PEN) {
            double[] p = annotCurrent.pts;
            if (Math.hypot(mx - annotView.sx(p[p.length - 2]), my - annotView.sy(p[p.length - 1])) >= 2) {
                annotCurrent.addPoint(wx, wz);
            }
        } else {
            annotCurrent.pts[2] = wx;
            annotCurrent.pts[3] = wz;
        }
    }

    private void annotationRelease() {
        annotPressed = false;
        List<Stroke> list = viewerMarker.drawingsFor(currentShot());
        if (drawTool == DrawTool.ERASER) {
            if (erased != null && !erased.isEmpty()) {
                List<Stroke> removed = erased;
                undo.push(() -> list.addAll(removed));
                annotationsChanged();
            }
            erased = null;
            return;
        }
        Stroke s = annotCurrent;
        annotCurrent = null;
        if (s == null) {
            return;
        }
        boolean valid = s.type.equals(Stroke.PEN)
                || Math.hypot(annotView.sx(s.pts[2]) - annotView.sx(s.pts[0]), annotView.sy(s.pts[3]) - annotView.sy(s.pts[1])) > 2;
        if (valid) {
            list.add(s);
            undo.push(() -> list.remove(s));
            annotationsChanged();
        }
    }

    private void eraseShotAt(double mx, double my) {
        List<Stroke> list = viewerMarker.drawingsFor(currentShot());
        for (int i = list.size() - 1; i >= 0; i--) {
            Stroke st = list.get(i);
            if (annotView.distanceTo(st, mx, my) <= 6 + st.width * annotView.zoom / 2.0) {
                list.remove(i);
                if (erased == null) {
                    erased = new ArrayList<>();
                }
                erased.add(st);
            }
        }
    }

    private void confirm(Component text, Runnable action) {
        confirmText = text;
        confirmAction = action;
        overlay = Overlay.CONFIRM;
    }

    private void drawConfirm(GuiGraphics g, int mx, int my) {
        blocker(() -> overlay = Overlay.NONE);
        g.fill(0, 0, width, height, Paper.DIM);
        int ow = Math.min(240, width - 20);
        List<FormattedCharSequence> lines = font.split(confirmText, ow - 24);
        int oh = lines.size() * 10 + 50;
        int ox = (width - ow) / 2;
        int oy = (height - oh) / 2;
        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {
        });
        int y = oy + 12;
        for (FormattedCharSequence l : lines) {
            g.drawString(font, l, ox + (ow - font.width(l)) / 2, y, Paper.INK, false);
            y += 10;
        }
        int bw = 80;
        int by = oy + oh - 24;
        int bx1 = ox + ow / 2 - bw - 4;
        int bx2 = ox + ow / 2 + 4;
        boolean h1 = Paper.inside(mx, my, bx1, by, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("check"), T.tr("button.yes"), bx1, by, bw, 16, h1, true);
        hit(bx1, by, bw, 16, () -> {
            overlay = Overlay.NONE;
            if (confirmAction != null) {
                confirmAction.run();
            }
        });
        boolean h2 = Paper.inside(mx, my, bx2, by, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("close"), T.tr("button.no"), bx2, by, bw, 16, h2, true);
        hit(bx2, by, bw, 16, () -> overlay = Overlay.NONE);
    }

    private void openShare(Marker m) {
        shareMarker = m;
        shareRegion = null;
        sharePlayerScroll = 0;
        overlay = Overlay.SHARE;
        if (shareCommentBox != null) {
            shareCommentBox.setValue("");
            setFocused(shareCommentBox);
        }
    }

    private void openShareRegion(Region r) {
        shareRegion = r;
        shareMarker = regionProxy(r);
        sharePlayerScroll = 0;
        overlay = Overlay.SHARE;
        if (shareCommentBox != null) {
            shareCommentBox.setValue("");
            setFocused(shareCommentBox);
        }
    }

    private void drawShareOverlay(GuiGraphics g, int mx, int my) {
        if (shareMarker == null && shareRegion == null) {
            overlay = Overlay.NONE;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        blocker(() -> overlay = Overlay.NONE);
        g.fill(0, 0, width, height, Paper.DIM);

        int ow = Math.min(260, width - 20);
        int oh = Math.min(230, height - 20);
        int ox = (width - ow) / 2;
        int oy = (height - oh) / 2;

        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {});
        closeButton(g, mx, my, ox + ow - 18, oy + 5, () -> overlay = Overlay.NONE);

        boolean isReg = shareRegion != null;
        Paper.title(g, font, T.s(isReg ? "region.share.title" : "marker.share.title"), ox + ow / 2, oy + 8);

        int cx = ox + 12;
        int cw = ow - 24;
        int y = oy + 22;

        String icon = isReg ? "⬡ " : "📍 ";
        String name = isReg
                ? (shareRegion.name.isBlank() ? T.s("region.title") : shareRegion.name)
                : (shareMarker.name.isBlank() ? T.s("marker.unnamed") : shareMarker.name);
        String coords = isReg
                ? Math.round(regionCenterX(shareRegion)) + ", " + Math.round(regionCenterZ(shareRegion))
                : shareMarker.x + ", " + shareMarker.y + ", " + shareMarker.z;
        String preview = icon + name + " (" + coords + ")";
        g.drawString(font, font.plainSubstrByWidth(preview, cw), cx, y, Paper.INK, false);
        y += 12;

        g.drawString(font, T.tr("marker.share.comment"), cx, y, Paper.INK_SOFT, false);
        y += 10;
        Paper.inset(g, cx, y, cw, 14);
        if (shareCommentBox != null) {
            shareCommentBox.setX(cx + 2);
            shareCommentBox.setY(y + 2);
            shareCommentBox.setWidth(cw - 4);
            shareCommentBox.setHeight(10);
            shareCommentBox.visible = true;
            shareCommentBox.render(g, mx, my, 0);
        }
        y += 18;

        // Button: Everyone in chat
        boolean hAll = overlay == Overlay.SHARE && Paper.inside(mx, my, cx, y, cw, 16);
        Paper.iconTextButton(g, font, Paper.tool("globe"), T.tr("marker.share.everyone"), cx, y, cw, 16, hAll, true);
        hit(cx, y, cw, 16, () -> {
            sendShare("*", false);
            overlay = Overlay.NONE;
        });
        y += 20;

        // Button: Copy to clipboard
        int hw = (cw - 4) / 2;
        boolean hCopy = overlay == Overlay.SHARE && Paper.inside(mx, my, cx, y, hw, 16);
        Paper.iconTextButton(g, font, Paper.tool("quill"), T.tr("marker.share.copy"), cx, y, hw, 16, hCopy, true);
        hit(cx, y, hw, 16, () -> {
            copyShare();
            overlay = Overlay.NONE;
        });

        // Button: Cancel
        boolean hCancel = overlay == Overlay.SHARE && Paper.inside(mx, my, cx + hw + 4, y, hw, 16);
        Paper.iconTextButton(g, font, Paper.tool("close"), T.tr("button.cancel"), cx + hw + 4, y, hw, 16, hCancel, true);
        hit(cx + hw + 4, y, hw, 16, () -> overlay = Overlay.NONE);
        y += 22;

        // Online players list
        ClientPacketListener conn = mc.getConnection();
        List<PlayerInfo> players = new ArrayList<>();
        if (conn != null) {
            String selfName = mc.player != null ? mc.player.getGameProfile().getName() : "";
            for (PlayerInfo p : conn.getOnlinePlayers()) {
                String pname = p.getProfile().getName();
                if (!pname.equalsIgnoreCase(selfName)) {
                    players.add(p);
                }
            }
        }

        if (!players.isEmpty()) {
            g.drawString(font, T.tr("marker.share.players"), cx, y, Paper.INK_SOFT, false);
            y += 10;
            int listH = oy + oh - 8 - y;
            if (listH > 20) {
                Paper.inset(g, cx, y, cw, listH);
                int rowH = 15;
                int totalH = players.size() * rowH;
                double maxScroll = Math.max(0, totalH - listH + 2);
                sharePlayerScroll = Math.max(0, Math.min(maxScroll, sharePlayerScroll));
                scroll(cx, y, cw, listH, d -> sharePlayerScroll -= d * 15);

                g.enableScissor(cx + 1, y + 1, cx + cw - 1, y + listH - 1);
                int ry = y + 2 - (int) sharePlayerScroll;
                for (PlayerInfo p : players) {
                    if (ry + rowH >= y && ry <= y + listH) {
                        String pname = p.getProfile().getName();
                        boolean hr = overlay == Overlay.SHARE && Paper.inside(mx, my, cx + 2, ry, cw - 4, rowH - 1)
                                && my >= y && my <= y + listH;
                        if (hr) {
                            g.fill(cx + 2, ry, cx + cw - 2, ry + rowH - 1, 0x20000000);
                        }
                        g.drawString(font, pname, cx + 6, ry + 3, hr ? Paper.GOLD : Paper.INK, false);
                        hit(cx + 2, ry, cw - 4, rowH - 1, () -> {
                            sendShare(pname, true);
                            overlay = Overlay.NONE;
                        });
                    }
                    ry += rowH;
                }
                g.disableScissor();
            }
        }
    }

    private void sendShare(String target, boolean whisper) {
        Minecraft mc = Minecraft.getInstance();
        String sender = mc.player != null ? mc.player.getGameProfile().getName() : "";
        boolean isReg = shareRegion != null;
        String shareStr = isReg
                ? WaypointShare.toRegionShareString(shareRegion, sender, target)
                : WaypointShare.toShareString(shareMarker, sender, target);
        String comment = shareCommentBox != null ? shareCommentBox.getValue().trim() : "";
        String fullMsg = comment.isEmpty() ? shareStr : comment + " " + shareStr;
        if (mc.player != null && mc.player.connection != null) {
            if (whisper) {
                mc.player.connection.sendCommand("msg " + target + " " + fullMsg);
                WaypointShare.notifyPlayer(T.s(isReg ? "region.share.sent_to" : "marker.share.sent_to", target));
            } else {
                mc.player.connection.sendChat(fullMsg);
                WaypointShare.notifyPlayer(T.s(isReg ? "region.share.sent" : "marker.share.sent"));
            }
        }
    }

    private void copyShare() {
        Minecraft mc = Minecraft.getInstance();
        String sender = mc.player != null ? mc.player.getGameProfile().getName() : "";
        boolean isReg = shareRegion != null;
        String shareStr = isReg
                ? WaypointShare.toRegionShareString(shareRegion, sender, "*")
                : WaypointShare.toShareString(shareMarker, sender, "*");
        String comment = shareCommentBox != null ? shareCommentBox.getValue().trim() : "";
        String fullMsg = comment.isEmpty() ? shareStr : comment + " " + shareStr;
        mc.keyboardHandler.setClipboard(fullMsg);
        WaypointShare.notifyPlayer(T.s(isReg ? "region.share.copied" : "marker.share.copied"));
    }

    private void drawDims(GuiGraphics g, int mx, int my) {
        blocker(() -> overlay = Overlay.NONE);
        List<String> dims = session.knownDims();
        if (!dims.contains(dim)) {
            dims.add(dim);
        }
        int ow = 150;
        int oh = dims.size() * 19 + 28;
        int ox = 6 + TB + 8;
        int oy = Math.max(6, Math.min(height - oh - 6, 6 + 8 * (TB + 3)));
        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {
        });
        Paper.title(g, font, T.s("dims.title"), ox + ow / 2, oy + 8);
        int y = oy + 22;
        for (String d : dims) {
            boolean h = Paper.inside(mx, my, ox + 8, y, ow - 16, 16);
            boolean cur = d.equals(dim);
            Paper.button(g, font, Component.literal((cur ? "» " : "") + T.dimName(d) + (cur ? " «" : "")), ox + 8, y, ow - 16, 16, h, true);
            hit(ox + 8, y, ow - 16, 16, () -> {
                switchDim(d);
                overlay = Overlay.NONE;
            });
            y += 19;
        }
    }

    // ------------------------------------------------------------------ actions

    private void setTool(Tool t) {
        tool = t;
        if (t != Tool.REGION) {
            regionDrawing = false;
            regionPoints.clear();
            redrawTarget = null;
        }
        if (t != Tool.NONE && freeRight() - freeLeft() < 230) {
            listOpen = false;
        }
        current = null;
        if (t != Tool.RULER) {
            ruler.clear();
        }
        Paper.click();
    }

    private void toggleDrawings() {
        cfg.drawingsVisible = !cfg.drawingsVisible;
        cfg.save();
    }

    private void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) {
            String d = mc.level.dimension().location().toString();
            if (!d.equals(dim)) {
                switchDim(d);
            }
            view.cx = mc.player.getX();
            view.cz = mc.player.getZ();
        }
    }

    private void centerOn(Marker m) {
        if (!m.dim.equals(dim)) {
            switchDim(m.dim);
        }
        view.cx = m.x + 0.5;
        view.cz = m.z + 0.5;
        if (view.zoom < 1f) {
            view.zoom = 2f;
        }
    }

    private void switchDim(String d) {
        if (d.equals(dim)) {
            return;
        }
        dim = d;
        if (listOpen) {
            listScroll = 0;
        }
        ruler.clear();
        current = null;
        Minecraft mc = Minecraft.getInstance();
        if (session.isLive() && mc.player != null && mc.level != null && d.equals(mc.level.dimension().location().toString())) {
            view.cx = mc.player.getX();
            view.cz = mc.player.getZ();
        } else {
            double sx = 0;
            double sz = 0;
            int n = 0;
            for (Marker m : session.markers) {
                if (m.dim.equals(d)) {
                    sx += m.x;
                    sz += m.z;
                    n++;
                }
            }
            double[] remembered = MapManager.remoteView(d);
            double[] pos = remembered != null ? remembered : session.meta.posFor(d);
            if (pos != null) {
                view.cx = pos[0];
                view.cz = pos[1];
                if (remembered != null) {
                    Float z = MapManager.remoteZoom(d);
                    if (z != null) {
                        view.zoom = z;
                    }
                }
            } else if (n > 0) {
                view.cx = sx / n;
                view.cz = sz / n;
            } else {
                // No markers: jump to where this dimension was actually explored.
                double[] c = session.layer(d).center();
                view.cx = c != null ? c[0] : 0;
                view.cz = c != null ? c[1] : 0;
            }
        }
    }

    private void zoomBy(float factor, double mx, double my) {
        float z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, view.zoom * factor));
        view.zoomAt(mx, my, z);
    }

    private void createMarker(int x, int y, int z) {
        Marker m = new Marker();
        m.x = x;
        m.y = y;
        m.z = z;
        m.dim = dim;
        m.name = T.s("marker.default_name", session.markers.size() + 1);
        session.markers.add(m);
        openEdit(m, true);
        Paper.pageTurn();
    }

    /**
     * Marker Y: two blocks above the surface when the chunk is really loaded,
     * otherwise 70. hasChunk() answers true for every coordinate (the client chunk
     * cache hands out a shared empty chunk outside the view), and an uninitialised
     * heightmap reports minY - 1, so the chunk is looked up with getChunkNow() and
     * the height is trusted only when it is above minY.
     */
    private int surfaceY(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (session.isLive() && mc.level != null && dim.equals(mc.level.dimension().location().toString())
                && !mc.level.dimensionType().hasCeiling()
                && mc.level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
            int h = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (h > mc.level.getMinY()) {
                return h + 2;
            }
        }
        return 70;
    }

    private void openView(Marker m, boolean center) {
        commitEdit(false);
        selectedRegion = null;
        workingRegion = null;
        regionMode = false;
        selectedRegionId = null;
        selected = m;
        panel = Panel.VIEW;
        descScroll = 0;
        shotsScroll = 0;
        if (center) {
            centerOn(m);
        }
    }

    private void openEdit(Marker m, boolean isNew) {
        if (panel == Panel.EDIT && working != null && selected == m) {
            return;
        }
        commitEdit(false);
        selectedRegion = null;
        workingRegion = null;
        regionMode = false;
        selected = m;
        working = m.copy();
        workingIsNew = isNew;
        addedFiles.clear();
        removedFiles.clear();
        shotsScroll = 0;
        panel = Panel.EDIT;
        if (nameBox != null) {
            fillWidgets();
            setFocused(nameBox);
            nameBox.moveCursorToEnd(false);
        }
    }

    /** Applies the working copy to the marker (if the edit panel is open). */
    private void commitEdit() {
        commitEdit(true);
    }

    /** Applies the working copy; the new position is stored only when Save was pressed. */
    private void commitEdit(boolean applyLocation) {
        if (regionMode) {
            if (working != null && workingRegion != null && selectedRegion != null) {
                workingRegion.name = working.name;
                workingRegion.description = working.description;
                workingRegion.color = working.color;
                workingRegion.screenshots = new ArrayList<>(working.screenshots);
                workingRegion.shotDrawings = new HashMap<>();
                working.shotDrawings.forEach((k, v) -> workingRegion.shotDrawings.put(k, new ArrayList<>(v)));
                selectedRegion.apply(workingRegion);
                for (String f : removedFiles) {
                    deleteShot(f);
                }
                removedFiles.clear();
                addedFiles.clear();
                session.saveRegions();
            }
            working = null;
            workingRegion = null;
            regionMode = false;
            panel = Panel.NONE;
            return;
        }
        if (panel != Panel.EDIT || working == null || selected == null) {
            return;
        }
        if (applyLocation) {
            working.x = parse(xBox.getValue(), working.x);
            working.y = parse(yBox.getValue(), working.y);
            working.z = parse(zBox.getValue(), working.z);
        } else {
            working.x = selected.x;
            working.y = selected.y;
            working.z = selected.z;
        }
        selected.apply(working);
        for (String f : removedFiles) {
            deleteShot(f);
        }
        removedFiles.clear();
        addedFiles.clear();
        working = null;
        workingIsNew = false;
        panel = Panel.NONE;
        session.saveMarkers();
    }

    private void saveAndView() {
        if (regionMode) {
            Region r = selectedRegion;
            commitEdit();
            if (r != null) {
                openRegionView(r);
            }
            Paper.click();
            return;
        }
        Marker m = selected;
        commitEdit();
        if (m != null) {
            selected = m;
            panel = Panel.VIEW;
        }
        Paper.click();
    }

    private void cancelEdit() {
        if (regionMode) {
            for (String f : addedFiles) {
                deleteShot(f);
            }
            addedFiles.clear();
            removedFiles.clear();
            Region r = selectedRegion;
            working = null;
            workingRegion = null;
            regionMode = false;
            if (r != null) {
                openRegionView(r);
            } else {
                panel = Panel.NONE;
            }
            return;
        }
        for (String f : addedFiles) {
            deleteShot(f);
        }
        addedFiles.clear();
        removedFiles.clear();
        if (workingIsNew && selected != null) {
            session.markers.remove(selected);
            selected = null;
            panel = Panel.NONE;
        } else {
            panel = selected != null ? Panel.VIEW : Panel.NONE;
        }
        working = null;
        workingIsNew = false;
        setFocused(null);
    }

    private void closePanel() {
        if (regionMode) {
            cancelEdit();
            panel = Panel.NONE;
            selected = null;
            selectedRegion = null;
            selectedRegionId = null;
            regionMode = false;
            setFocused(null);
            return;
        }
        commitEdit(false);
        panel = Panel.NONE;
        selected = null;
        setFocused(null);
    }

    private void confirmDelete(Marker m) {
        if (m == null) {
            return;
        }
        confirm(T.tr("confirm.delete_marker", m.name.isEmpty() ? T.s("marker.unnamed") : m.name), () -> {
            session.markers.remove(m);
            working = null;
            workingIsNew = false;
            selected = null;
            panel = Panel.NONE;
            setFocused(null);
            session.saveMarkers();
        });
    }

    private void deleteShot(String file) {
        Path p = session.screenshot(file);
        Storage.IO.execute(() -> {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                Storage.LOG.warn("Failed to delete {}", p, e);
            }
        });
    }

    private void importShot(Path source) {
        Marker target = working;
        if (target == null) {
            return;
        }
        ImageCache.importImage(source, session.screenshotsDir()).thenAcceptAsync(name -> {
            if (name == null) {
                return;
            }
            if (working == target) {
                target.screenshots.add(name);
                addedFiles.add(name);
            } else {
                deleteShot(name);
            }
        }, Minecraft.getInstance());
    }

    private void openFileDialog() {
        Path start = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        FileDialogs.openImages(T.s("shots.dialog_title"), start, paths -> {
            for (Path p : paths) {
                importShot(p);
            }
        });
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (panel != Panel.EDIT || working == null) {
            return;
        }
        for (Path p : files) {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) {
                importShot(p);
            }
        }
    }

    private void undo() {
        Runnable r = undo.poll();
        if (r != null) {
            r.run();
            session.saveStrokes();
            session.saveMarkers();
        }
    }

    private void clearDrawings() {
        List<Stroke> list = session.strokes(dim);
        List<Stroke> copy = new ArrayList<>(list);
        list.clear();
        undo.push(() -> list.addAll(copy));
        session.saveStrokes();
    }

    private void eraseAt(double mx, double my) {
        List<Stroke> list = session.strokes(dim);
        for (int i = list.size() - 1; i >= 0; i--) {
            Stroke s = list.get(i);
            if (view.distanceTo(s, mx, my) <= 6 + s.width / 2.0) {
                list.remove(i);
                if (erased == null) {
                    erased = new ArrayList<>();
                }
                erased.add(s);
            }
        }
    }

    // ------------------------------------------------------------------ input

    private void hit(int x, int y, int w, int h, Runnable left) {
        hits.add(new Hit(x, y, w, h, left, null));
    }

    private void hit(int x, int y, int w, int h, Runnable left, Runnable right) {
        hits.add(new Hit(x, y, w, h, left, right));
    }

    private void toolHit(int x, int y, int w, int h, Runnable left) {
        toolHits.add(new Hit(x, y, w, h, left, null));
    }

    private boolean runToolHits(double mx, double my, int button) {
        for (int i = toolHits.size() - 1; i >= 0; i--) {
            Hit h = toolHits.get(i);
            if (Paper.inside(mx, my, h.x, h.y, h.w, h.h)) {
                Runnable r = button == InputConstants.MOUSE_BUTTON_RIGHT ? h.right : h.left;
                if (r != null) {
                    r.run();
                }
                return true;
            }
        }
        return false;
    }

    private void scroll(int x, int y, int w, int h, DoubleConsumer c) {
        scrolls.add(new ScrollArea(x, y, w, h, c));
    }

    private void ui(int x, int y, int w, int h) {
        uiRects.add(new int[]{x, y, w, h});
    }

    private boolean mouseOverMap(double mx, double my) {
        if (overlay != Overlay.NONE) {
            return false;
        }
        for (int[] r : lastUiRects) {
            if (Paper.inside(mx, my, r[0], r[1], r[2], r[3])) {
                return false;
            }
        }
        return true;
    }

    private Marker hoveredMarker(double mx, double my) {
        if (!mouseOverMap(mx, my) || pressed && dragging) {
            return null;
        }
        return view.markerAt(session.markers, dim, mx, my, 16);
    }

    private boolean typing() {
        GuiEventListener f = getFocused();
        return f instanceof EditBox e && e.isFocused() && e.isVisible()
                || f instanceof MultiLineEditBox b && b.isFocused() && b.visible;
    }

    private void updateWidgetVisibility() {
        boolean edit = panel == Panel.EDIT && working != null;
        nameBox.setVisible(edit);
        xBox.setVisible(edit && !regionMode);
        yBox.setVisible(edit && !regionMode);
        zBox.setVisible(edit && !regionMode);
        descBox.visible = edit;
        boolean globalSearch = !search.isBlank();
        searchBox.setVisible(listOpen);
        markerSearchBox.setVisible(listOpen && !globalSearch && markersOpen);
        regionSearchBox.setVisible(listOpen && !globalSearch && regionsOpen);
        boolean inMaps = overlay == Overlay.MAPS && confirmBindTarget == null && previewMeta == null;
        boolean globalMaps = !mapSearch.isBlank();
        mapSearchBox.setVisible(inMaps);
        localMapSearchBox.setVisible(inMaps && !globalMaps && localsOpen);
        serverMapSearchBox.setVisible(inMaps && !globalMaps && serversOpen);
        realmMapSearchBox.setVisible(inMaps && !globalMaps && realmsOpen);
        iconSearch.setVisible(overlay == Overlay.ICONS);
        regionNameBox.setVisible(overlay == Overlay.REGION);
        if (shareCommentBox != null) shareCommentBox.setVisible(overlay == Overlay.SHARE);
        GuiEventListener f = getFocused();
        if (f instanceof EditBox e && !e.isVisible() || f instanceof MultiLineEditBox b && !b.visible) {
            setFocused(null);
        }
    }

    private boolean runHits(double mx, double my, int button) {
        int from = modalStart >= 0 ? modalStart : 0;
        for (int i = hits.size() - 1; i >= from; i--) {
            Hit h = hits.get(i);
            if (Paper.inside(mx, my, h.x, h.y, h.w, h.h)) {
                Runnable r = button == InputConstants.MOUSE_BUTTON_RIGHT ? h.right : h.left;
                if (r != null) {
                    r.run();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (confirmBindTarget != null) {
            runHits(mx, my, button);
            return true;
        }
        if (overlay == Overlay.VIEWER && annotationPress(mx, my, button)) {
            return true;
        }
        if (overlay == Overlay.ICONS && iconSearch.isMouseOver(mx, my)) {
            setFocused(iconSearch);
            iconSearch.mouseClicked(mx, my, button);
            return true;
        }
        if (overlay == Overlay.REGION) {
            if (regionNameBox.isMouseOver(mx, my)) {
                setFocused(regionNameBox);
                regionNameBox.mouseClicked(mx, my, button);
            } else {
                runHits(mx, my, button);
            }
            return true;
        }
        if (overlay == Overlay.MAPS) {
            if (mapSearchBox.isMouseOver(mx, my)) {
                setFocused(mapSearchBox);
                mapSearchBox.mouseClicked(mx, my, button);
                return true;
            }
            if (localMapSearchBox.isVisible() && localMapSearchBox.isMouseOver(mx, my)) {
                setFocused(localMapSearchBox);
                localMapSearchBox.mouseClicked(mx, my, button);
                return true;
            }
            if (serverMapSearchBox.isVisible() && serverMapSearchBox.isMouseOver(mx, my)) {
                setFocused(serverMapSearchBox);
                serverMapSearchBox.mouseClicked(mx, my, button);
                return true;
            }
            if (realmMapSearchBox.isVisible() && realmMapSearchBox.isMouseOver(mx, my)) {
                setFocused(realmMapSearchBox);
                realmMapSearchBox.mouseClicked(mx, my, button);
                return true;
            }
            runHits(mx, my, button);
            return true;
        }
        if (overlay != Overlay.NONE) {
            runHits(mx, my, button);
            return true;
        }
        if (runToolHits(mx, my, button)) {
            return true;
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        setFocused(null);
        if (runHits(mx, my, button)) {
            return true;
        }
        if (tool == Tool.REGION
                && button == InputConstants.MOUSE_BUTTON_LEFT && mouseOverMap(mx, my)) {
            regionDrawing = true;
            regionPoints.clear();
            regionPressX = mx;
            regionPressY = my;
            regionStartWX = view.wx(mx);
            regionStartWZ = view.wz(my);
            regionCurrentWX = regionStartWX;
            regionCurrentWZ = regionStartWZ;
            if (regionShape == RegionShape.FREEHAND) {
                addRegionPoint(mx, my);
            }
            return true;
        }
        if (panel == Panel.EDIT && working != null && !regionMode && tool == Tool.NONE
                && button == InputConstants.MOUSE_BUTTON_LEFT && mouseOverMap(mx, my)) {
            Marker hit = view.markerAt(List.of(working), dim, mx, my, 16);
            if (hit != null) {
                markerDrag = true;
                markerDragOffX = working.x - view.wx(mx);
                markerDragOffZ = working.z - view.wz(my);
                return true;
            }
        }
        pressed = true;
        pressButton = button;
        pressX = mx;
        pressY = my;
        pressCX = view.cx;
        pressCZ = view.cz;
        dragging = false;
        if (tool == Tool.DRAW && button == InputConstants.MOUSE_BUTTON_LEFT) {
            double wx = view.wx(mx);
            double wz = view.wz(my);
            int c = COLORS[colorIdx];
            int w = WIDTHS[widthIdx];
            switch (drawTool) {
                case ERASER -> {
                    erased = null;
                    eraseAt(mx, my);
                }
                case PEN -> current = new Stroke(Stroke.PEN, c, w, new double[]{wx, wz});
                case LINE -> current = new Stroke(Stroke.LINE, c, w, new double[]{wx, wz, wx, wz});
            case ARROW -> current = new Stroke(Stroke.ARROW, c, w, new double[]{wx, wz, wx, wz});
                case RECT -> current = new Stroke(Stroke.RECT, c, w, new double[]{wx, wz, wx, wz});
                case CIRCLE -> current = new Stroke(Stroke.CIRCLE, c, w, new double[]{wx, wz, wx, wz});
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (confirmBindTarget != null) {
            return true;
        }
        if (annotPressed) {
            annotationDrag(mx, my);
            return true;
        }
        if (regionDrawing) {
            regionCurrentWX = view.wx(mx);
            regionCurrentWZ = view.wz(my);
            if (regionShape == RegionShape.FREEHAND) {
                addRegionPoint(mx, my);
            }
            return true;
        }
        if (markerDrag && working != null) {
            working.x = (int) Math.floor(view.wx(mx) + markerDragOffX);
            working.z = (int) Math.floor(view.wz(my) + markerDragOffZ);
            xBox.setValue(Integer.toString(working.x));
            zBox.setValue(Integer.toString(working.z));
            return true;
        }
        if (!pressed) {
            return super.mouseDragged(mx, my, button, dx, dy);
        }
        if (tool == Tool.DRAW && pressButton == 0) {
            if (drawTool == DrawTool.ERASER) {
                eraseAt(mx, my);
            } else if (current != null) {
                double wx = view.wx(mx);
                double wz = view.wz(my);
                if (drawTool == DrawTool.PEN) {
                    double[] p = current.pts;
                    double lx = view.sx(p[p.length - 2]);
                    double ly = view.sy(p[p.length - 1]);
                    if (Math.hypot(mx - lx, my - ly) >= 2) {
                        current.addPoint(wx, wz);
                    }
                } else {
                    current.pts[2] = wx;
                    current.pts[3] = wz;
                }
            }
            return true;
        }
        if (!dragging && Math.hypot(mx - pressX, my - pressY) > 3) {
            dragging = true;
        }
        if (dragging) {
            view.cx = pressCX - (mx - pressX) / view.zoom;
            view.cz = pressCZ - (my - pressY) / view.zoom;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (annotPressed) {
            annotationRelease();
            return true;
        }
        if (regionDrawing) {
            regionDrawing = false;
            finishRegion(mx, my);
            return true;
        }
        if (markerDrag) {
            markerDrag = false;
            // recompute Y once, when the drag ends: the moved marker sits on the surface
            // of the new column, or at 70 when that chunk is not loaded
            if (working != null && selected != null
                    && (working.x != selected.x || working.z != selected.z)) {
                working.y = surfaceY(working.x, working.z);
                yBox.setValue(Integer.toString(working.y));
            }
            return true;
        }
        if (!pressed) {
            return super.mouseReleased(mx, my, button);
        }
        pressed = false;
        if (tool == Tool.DRAW && pressButton == 0) {
            if (drawTool == DrawTool.ERASER) {
                if (erased != null && !erased.isEmpty()) {
                    List<Stroke> list = session.strokes(dim);
                    List<Stroke> removed = erased;
                    undo.push(() -> list.addAll(removed));
                    session.saveStrokes();
                }
                erased = null;
            } else if (current != null) {
                Stroke s = current;
                current = null;
                boolean valid = s.type.equals(Stroke.PEN) || Math.hypot(view.sx(s.pts[2]) - view.sx(s.pts[0]), view.sy(s.pts[3]) - view.sy(s.pts[1])) > 2;
                if (valid) {
                    List<Stroke> list = session.strokes(dim);
                    list.add(s);
                    undo.push(() -> list.remove(s));
                    session.saveStrokes();
                    if (!cfg.drawingsVisible) {
                        cfg.drawingsVisible = true;
                        cfg.save();
                    }
                }
            }
            return true;
        }
        if (!dragging) {
            onMapClick(mx, my, pressButton);
        }
        dragging = false;
        return true;
    }

    // ------------------------------------------------------------------ regions

    private void addRegionPoint(double mx, double my) {
        double wx = view.wx(mx);
        double wz = view.wz(my);
        if (!regionPoints.isEmpty()) {
            double[] last = regionPoints.get(regionPoints.size() - 1);
            if (Math.hypot(view.sx(last[0]) - mx, view.sy(last[1]) - my) < 3) {
                return;
            }
        }
        regionPoints.add(new double[]{wx, wz});
    }

    private void finishRegion(double mx, double my) {
        double travelled = Math.hypot(mx - regionPressX, my - regionPressY);
        if (travelled < 3) {
            regionPoints.clear();
            // a click on a name selects that region even if another one is nested underneath
            Region hit = view.regionLabelAt(font, session.regions(dim), dim,
                    regionMode ? selectedRegionId : null, mx, my);
            if (hit == null) {
                hit = view.regionAt(session.regions(dim), dim, mx, my);
            }
            if (hit != null) {
                openRegionView(hit);
            }
            return;
        }
        double[] pts;
        if (regionShape == RegionShape.FREEHAND) {
            List<double[]> points = new ArrayList<>(regionPoints);
            double[] first = points.get(0);
            double[] last = points.get(points.size() - 1);
            if (Math.hypot(last[0] - first[0], last[1] - first[1]) < 2.5) {
                points.remove(points.size() - 1);
            }
            pts = points.size() >= 3 ? flatten(points) : new double[0];
        } else {
            regionCurrentWX = view.wx(mx);
            regionCurrentWZ = view.wz(my);
            pts = shapePoints();
        }
        if (regionShape == RegionShape.FREEHAND && Region.selfIntersects(pts)) {
            pts = Region.convexHull(pts);
        }
        regionPoints.clear();
        if (pts.length < 6) {
            return;
        }
        if (redrawTarget != null) {
            redrawTarget.pts = pts;
            session.saveRegions();
            Region target = redrawTarget;
            redrawTarget = null;
            openRegionView(target);
            return;
        }
        Region r = new Region();
        r.name = T.s("region.default_name", session.regions(dim).size() + 1);
        r.color = COLORS[regionColorIdx];
        r.filled = regionFill;
        r.dim = dim;
        r.pts = pts;
        session.regions(dim).add(r);
        session.saveRegions();
        selectedRegionId = r.id;
        Paper.pageTurn();
        openRegionView(r);
    }

    /** Builds the polygon of the selected region shape for preview and for the final region. */
    private double[] shapePoints() {
        if (regionShape == RegionShape.RECT) {
            double x1 = Math.min(regionStartWX, regionCurrentWX);
            double x2 = Math.max(regionStartWX, regionCurrentWX);
            double z1 = Math.min(regionStartWZ, regionCurrentWZ);
            double z2 = Math.max(regionStartWZ, regionCurrentWZ);
            if (x2 - x1 < 1 || z2 - z1 < 1) {
                return new double[0];
            }
            return new double[]{x1, z1, x2, z1, x2, z2, x1, z2};
        }
        if (regionShape == RegionShape.CIRCLE) {
            double radius = Math.hypot(regionCurrentWX - regionStartWX, regionCurrentWZ - regionStartWZ);
            if (radius < 1) {
                return new double[0];
            }
            int n = 24;
            double[] p = new double[n * 2];
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                p[i * 2] = regionStartWX + Math.cos(a) * radius;
                p[i * 2 + 1] = regionStartWZ + Math.sin(a) * radius;
            }
            return p;
        }
        return flatten(regionPoints);
    }

    private Region previewRegion() {
        double[] pts = shapePoints();
        if (pts.length < 6) {
            return null;
        }
        Region r = new Region();
        r.color = COLORS[colorIdx];
        r.dim = dim;
        r.pts = pts;
        return r;
    }

    private static double[] flatten(List<double[]> points) {
        double[] out = new double[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            out[i * 2] = points.get(i)[0];
            out[i * 2 + 1] = points.get(i)[1];
        }
        return out;
    }

    private void openRegionView(Region r) {
        if (panel == Panel.EDIT) {
            commitEdit(false);
        }
        selectedRegion = r;
        regionMode = true;
        selectedRegionId = r.id;
        selected = regionProxy(r);
        working = null;
        workingRegion = null;
        panel = Panel.VIEW;
        descScroll = 0;
        shotsScroll = 0;
    }

    private void openRegionEdit(Region r) {
        if (panel == Panel.EDIT) {
            commitEdit(false);
        }
        selectedRegion = r;
        regionMode = true;
        selectedRegionId = r.id;
        workingRegion = r.copy();
        selected = regionProxy(r);
        working = regionProxy(r);
        workingIsNew = false;
        panel = Panel.EDIT;
        descScroll = 0;
        shotsScroll = 0;
        if (nameBox != null) {
            fillWidgets();
            setFocused(nameBox);
            nameBox.moveCursorToEnd(false);
        }
    }

    private Marker regionProxy(Region r) {
        Marker m = new Marker();
        m.id = r.id;
        m.name = r.name.isBlank() ? T.s("region.title") : r.name;
        m.description = r.description;
        m.color = r.color;
        m.dim = r.dim;
        m.hidden = r.hidden;
        m.icon = "pin";
        m.x = (int) Math.round(regionCenterX(r));
        m.z = (int) Math.round(regionCenterZ(r));
        m.y = surfaceY(m.x, m.z);
        m.screenshots = new ArrayList<>(r.screenshots);
        m.shotDrawings = new HashMap<>();
        r.shotDrawings.forEach((k, v) -> m.shotDrawings.put(k, new ArrayList<>(v)));
        return m;
    }

    private static double regionCenterX(Region r) {
        int n = r.pointCount();
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += r.pts[i * 2];
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double regionCenterZ(Region r) {
        int n = r.pointCount();
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += r.pts[i * 2 + 1];
        }
        return n == 0 ? 0 : sum / n;
    }

    private void confirmDeleteRegion() {
        Region r = selectedRegion;
        if (r == null) {
            return;
        }
        String name = r.name.isBlank() ? T.s("region.default_name", 1) : r.name;
        confirm(T.tr("confirm.delete_region", name), () -> {
            session.regions(dim).removeIf(x -> x.id.equals(r.id));
            session.saveRegions();
            selectedRegion = null;
            working = null;
            workingRegion = null;
            regionMode = false;
            selectedRegionId = null;
            selected = null;
            panel = Panel.NONE;
        });
    }

    private void startRedraw() {
        if (panel == Panel.EDIT) {
            commitEdit(false);
        }
        if (selectedRegion == null) {
            return;
        }
        redrawTarget = selectedRegion;
        working = null;
        workingRegion = null;
        regionMode = false;
        panel = Panel.NONE;
        setTool(Tool.REGION);
        Paper.pageTurn();
    }

    private void drawRegionViewPanel(GuiGraphics g, int mx, int my) {
        if (selectedRegion == null) {
            panel = Panel.NONE;
            regionMode = false;
            return;
        }
        Region r = selectedRegion;
        int[] pr = panelRect();
        int px = pr[0];
        int py = pr[1];
        int pw = pr[2];
        int ph = pr[3];
        Paper.panel(g, px, py, pw, ph);
        ui(px, py, pw, ph);
        hit(px, py, pw, ph, () -> {
        });
        closeButton(g, mx, my, px + pw - 18, py + 5, this::closePanel);

        int cx = px + 9;
        int cw = pw - 18;
        int y = py + 10;
        Paper.inset(g, cx, y, 20, 20);
        g.fill(cx + 2, y + 2, cx + 18, y + 18, r.color);
        Paper.outline(g, cx, y, 20, 20, Paper.INK);
        List<FormattedCharSequence> nameLines = font.split(
                Component.literal(r.name.isEmpty() ? T.s("region.title") : r.name), cw - 28);
        int ny = y + (nameLines.size() > 1 ? 1 : 6);
        for (int i = 0; i < Math.min(2, nameLines.size()); i++) {
            g.drawString(font, nameLines.get(i), cx + 26, ny + i * 10, Paper.INK, false);
        }
        y += 26;

        g.drawString(font, T.s("region.center", Math.round(regionCenterX(r)), Math.round(regionCenterZ(r))),
                cx, y, Paper.INK, false);
        y += 12;
        Paper.separator(g, cx, y, cw);
        y += 5;

        g.drawString(font, T.tr("field.desc"), cx, y, Paper.INK_SOFT, false);
        y += 11;
        List<FormattedCharSequence> desc = r.description.isBlank()
                ? List.of(T.tr("marker.no_desc").getVisualOrderText())
                : font.split(Component.literal(r.description), cw - 6);
        int descH = Math.min(desc.size() * 10 + 6, Math.max(30, (int) (ph * 0.25)));
        Paper.inset(g, cx, y, cw, descH);
        double maxDesc = Math.max(0, desc.size() * 10 + 6 - descH);
        descScroll = Math.max(0, Math.min(maxDesc, descScroll));
        scroll(cx, y, cw, descH, d -> descScroll -= d * 10);
        g.enableScissor(cx + 1, y + 1, cx + cw - 1, y + descH - 1);
        int ty = y + 4 - (int) descScroll;
        for (FormattedCharSequence l : desc) {
            g.drawString(font, l, cx + 3, ty, r.description.isBlank() ? Paper.DISABLED : Paper.INK, false);
            ty += 10;
        }
        g.disableScissor();
        y += descH + 6;
        Paper.separator(g, cx, y, cw);
        y += 5;

        boolean tp = cfg.teleport && session.isLive();
        int rows = tp ? 3 : 2;
        int buttonsY = py + ph - 8 - 16 * rows - 4 * (rows - 1);
        g.drawString(font, T.tr("field.shots", r.screenshots.size()), cx, y, Paper.INK_SOFT, false);
        y += 11;
        drawShotGrid(g, mx, my, selected, cx, y, cw, buttonsY - 6 - y, false);

        int bw = (cw - 4) / 2;
        int y2 = buttonsY + 20;
        boolean h2 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("trash"), T.tr("button.delete"), cx, y2, bw, 16, h2, true);
        hit(cx, y2, bw, 16, this::confirmDeleteRegion);
        boolean h1 = overlay == Overlay.NONE && Paper.inside(mx, my, cx + bw + 4, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("gear"), T.tr("button.configure"), cx + bw + 4, y2, bw, 16, h1, true);
        hit(cx + bw + 4, y2, bw, 16, () -> openRegionEdit(r));
        y2 = buttonsY;
        boolean h3 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool(r.hidden ? "eye" : "eye_off"),
                T.tr(r.hidden ? "marker.show" : "marker.hide"), cx, y2, bw, 16, h3, true);
        hit(cx, y2, bw, 16, () -> {
            r.hidden = !r.hidden;
            selected.hidden = r.hidden;
            session.saveRegions();
        });
        boolean h4 = overlay == Overlay.NONE && Paper.inside(mx, my, cx + bw + 4, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("target"), T.tr("button.goto"), cx + bw + 4, y2, bw, 16, h4, true);
        hit(cx + bw + 4, y2, bw, 16, () -> centerOn(selected));
        if (tp) {
            int y3 = buttonsY + 40;
            boolean h5 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y3, cw, 16);
            Paper.iconTextButton(g, font, Paper.tool("arrow"), T.tr("button.teleport"), cx, y3, cw, 16, h5, true);
            hit(cx, y3, cw, 16, () -> teleportTo(selected));
        }
    }

    private void drawRegionEditPanel(GuiGraphics g, int mx, int my) {
        if (working == null) {
            panel = Panel.NONE;
            regionMode = false;
            return;
        }
        int[] pr = panelRect();
        int px = pr[0];
        int py = pr[1];
        int pw = pr[2];
        int ph = pr[3];
        Paper.panel(g, px, py, pw, ph);
        ui(px, py, pw, ph);
        hit(px, py, pw, ph, () -> setFocused(null));
        Paper.title(g, font, T.s("region.edit"), px + pw / 2, py + 8);
        closeButton(g, mx, my, px + pw - 18, py + 5, this::cancelEdit);

        int cx = px + 9;
        int cw = pw - 18;
        int y = py + 22;
        g.drawString(font, T.tr("region.name"), cx, y, Paper.INK_SOFT, false);
        y += 10;
        Paper.inset(g, cx, y, cw, 16);
        nameBox.setX(cx + 4);
        nameBox.setY(y + 4);
        nameBox.setWidth(cw - 8);
        y += 22;

        g.drawString(font, T.tr("region.color"), cx, y, Paper.INK_SOFT, false);
        int by = y + 9;
        boolean hcol = overlay == Overlay.NONE && Paper.inside(mx, my, cx, by, cw, 16);
        Paper.button(g, font, T.tr("region.set_color"), cx, by, cw, 16, hcol, true);
        g.fill(cx + cw - 18, by + 2, cx + cw - 6, by + 14, working.color);
        Paper.outline(g, cx + cw - 18, by + 2, 12, 12, Paper.INK);
        hit(cx, by, cw, 16, () -> openRegionColors(working, workingRegion));
        y = by + 16 + 8;

        g.drawString(font, T.tr("field.desc"), cx, y, Paper.INK_SOFT, false);
        y += 10;
        int descH = Math.max(36, Math.min(80, (int) (ph * 0.2)));
        Paper.inset(g, cx, y, cw, descH);
        descBox.setX(cx + 1);
        descBox.setY(y + 1);
        descBox.setWidth(cw - 2);
        descBox.setHeight(descH - 2);
        y += descH + 6;

        int buttonsY = py + ph - 8 - 16 * 2 - 4;
        g.drawString(font, T.tr("field.shots", working.screenshots.size()), cx, y, Paper.INK_SOFT, false);
        y += 11;
        drawShotGrid(g, mx, my, working, cx, y, cw, buttonsY - 6 - y, true);

        int bw = (cw - 4) / 2;
        int y2 = buttonsY + 20;
        boolean h1 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("check"), T.tr("button.save"), cx, y2, bw, 16, h1, true);
        hit(cx, y2, bw, 16, this::saveAndView);
        boolean h2 = overlay == Overlay.NONE && Paper.inside(mx, my, cx + bw + 4, y2, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("close"), T.tr("button.cancel"), cx + bw + 4, y2, bw, 16, h2, true);
        hit(cx + bw + 4, y2, bw, 16, this::cancelEdit);
        boolean h3 = overlay == Overlay.NONE && Paper.inside(mx, my, cx, buttonsY, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("trash"), T.tr("button.delete"), cx, buttonsY, bw, 16, h3, true);
        hit(cx, buttonsY, bw, 16, this::confirmDeleteRegion);
        boolean h4 = overlay == Overlay.NONE && Paper.inside(mx, my, cx + bw + 4, buttonsY, bw, 16);
        Paper.iconTextButton(g, font, Paper.tool("region"), T.tr("region.redraw"), cx + bw + 4, buttonsY, bw, 16, h4, true);
        if (h4) {
            tooltip = T.tr("region.redraw_tip");
        }
        hit(cx + bw + 4, buttonsY, bw, 16, this::startRedraw);
    }

    private void openRegionEditor(Region r, boolean isNew) {
        editingRegion = isNew ? r : r.copy();
        regionIsNew = isNew;
        selectedRegionId = r.id;
        overlay = Overlay.REGION;
        regionNameBox.setValue(editingRegion.name);
        regionNameBox.moveCursorToEnd(false);
        setFocused(regionNameBox);
    }

    private void saveRegion() {
        if (editingRegion == null) {
            overlay = Overlay.NONE;
            return;
        }
        String name = regionNameBox.getValue().trim();
        editingRegion.name = name.isEmpty()
                ? T.s("region.default_name", session.regions(dim).size() + 1) : name;
        if (!regionIsNew) {
            for (Region r : session.regions(dim)) {
                if (r.id.equals(editingRegion.id)) {
                    r.apply(editingRegion);
                    break;
                }
            }
        }
        selectedRegionId = editingRegion.id;
        session.saveRegions();
        overlay = Overlay.NONE;
        editingRegion = null;
        regionIsNew = false;
        setFocused(null);
        Paper.click();
    }

    private void cancelRegion() {
        if (regionIsNew && editingRegion != null) {
            session.regions(dim).removeIf(r -> r.id.equals(editingRegion.id));
            session.saveRegions();
        }
        overlay = Overlay.NONE;
        editingRegion = null;
        regionIsNew = false;
        setFocused(null);
    }

    private void deleteRegion() {
        if (editingRegion != null) {
            session.regions(dim).removeIf(r -> r.id.equals(editingRegion.id));
            session.saveRegions();
        }
        overlay = Overlay.NONE;
        editingRegion = null;
        regionIsNew = false;
        setFocused(null);
        Paper.pageTurn();
    }

    private void drawRegionEditor(GuiGraphics g, int mx, int my) {
        blocker(this::cancelRegion);
        g.fill(0, 0, width, height, Paper.DIM);
        int ow = Math.min(262, width - 20);
        int oh = 134;
        int ox = (width - ow) / 2;
        int oy = (height - oh) / 2;
        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {
        });
        Paper.title(g, font, T.s("region.title"), ox + ow / 2, oy + 8);
        int cx = ox + 10;
        int cw = ow - 20;
        int y = oy + 24;
        g.drawString(font, T.tr("region.name"), cx, y, Paper.INK_SOFT, false);
        y += 10;
        Paper.inset(g, cx, y, cw, 16);
        regionNameBox.setX(cx + 4);
        regionNameBox.setY(y + 4);
        regionNameBox.setWidth(cw - 8);
        regionNameBox.render(g, mx, my, 0);
        y += 22;

        int bw = (cw - 8) / 3;
        boolean hs = Paper.inside(mx, my, cx, y, bw, 14);
        Paper.button(g, font, T.tr("button.save"), cx, y, bw, 14, hs, true);
        hit(cx, y, bw, 14, this::saveRegion);
        boolean hc = Paper.inside(mx, my, cx + bw + 4, y, bw, 14);
        Paper.button(g, font, T.tr("button.cancel"), cx + bw + 4, y, bw, 14, hc, true);
        hit(cx + bw + 4, y, bw, 14, this::cancelRegion);
        boolean hd = Paper.inside(mx, my, cx + 2 * (bw + 4), y, bw, 14);
        Paper.button(g, font, T.tr("button.delete"), cx + 2 * (bw + 4), y, bw, 14, hd, true);
        hit(cx + 2 * (bw + 4), y, bw, 14, this::deleteRegion);

        y += 14 + 8;
        g.drawString(font, T.tr("region.color"), cx, y, Paper.INK_SOFT, false);
        int sy = y + 10;
        for (int i = 0; i < COLORS.length; i++) {
            int idx = i;
            int sx = cx + (i % PAL_COLS) * 11;
            int py2 = sy + (i / PAL_COLS) * 11;
            g.fill(sx - 1, py2 - 1, sx + 10, py2 + 10, Paper.INK);
            g.fill(sx, py2, sx + 9, py2 + 9, COLORS[i]);
            boolean sel = (editingRegion.color & 0xFFFFFF) == (COLORS[i] & 0xFFFFFF);
            if (sel) {
                Paper.outline(g, sx - 2, py2 - 2, 13, 13, Paper.GOLD_LIGHT);
                Paper.outline(g, sx - 1, py2 - 1, 11, 11, Paper.GOLD);
            }
            hit(sx - 1, py2 - 1, 11, 11, () -> {
                regionColorIdx = idx;
                editingRegion.color = COLORS[idx];
            });
        }
    }

    private void onMapClick(double mx, double my, int button) {
        if (previewMeta != null) return;        Marker hit = cfg.markersVisible ? view.markerAt(session.markers, dim, mx, my, 16) : null;
        if (tool == Tool.RULER) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                double[] p = hit != null ? new double[]{hit.x + 0.5, hit.z + 0.5} : new double[]{view.wx(mx), view.wz(my)};
                ruler.add(p);
            } else if (button == InputConstants.MOUSE_BUTTON_RIGHT && !ruler.isEmpty()) {
                ruler.remove(ruler.size() - 1);
            }
            return;
        }
        if (tool == Tool.DRAW) {
            return;
        }
        if (tool == Tool.REGION) {
            return;
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (hit != null) {
                openView(hit, false);
                Paper.click();
            } else {
                Region region = view.regionLabelAt(font, session.regions(dim), dim,
                        regionMode ? selectedRegionId : null, mx, my);
                if (region == null) {
                    region = view.regionAt(session.regions(dim), dim, mx, my);
                }
                if (region != null) {
                    openRegionView(region);
                    Paper.click();
                } else if (panel != Panel.NONE) {
                    closePanel();
                }
            }
        } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (hit != null) {
                openView(hit, false);
                Paper.click();
            } else {
                int x = (int) Math.floor(view.wx(mx));
                int z = (int) Math.floor(view.wz(my));
                createMarker(x, surfaceY(x, z), z);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (confirmBindTarget != null) {
            return true;
        }
        if (overlay != Overlay.NONE && overlay != Overlay.PICKER && overlay != Overlay.ICONS && overlay != Overlay.MAPS) {
            return true;
        }
        for (int i = scrolls.size() - 1; i >= 0; i--) {
            ScrollArea a = scrolls.get(i);
            if (Paper.inside(mx, my, a.x, a.y, a.w, a.h)) {
                if (overlay == Overlay.PICKER || overlay == Overlay.ICONS || !(getChildAt(mx, my).isPresent() && getChildAt(mx, my).get() == descBox && descBox.visible)) {
                    a.onScroll.accept(sy);
                    return true;
                }
            }
        }
        if (overlay != Overlay.NONE) {
            return true;
        }
        if (super.mouseScrolled(mx, my, sx, sy)) {
            return true;
        }
        if (mouseOverMap(mx, my)) {
            zoomBy((float) Math.pow(1.2, sy), mx, my);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int key = keyCode;
        if (overlay == Overlay.SHARE) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                sendShare("*", false);
                overlay = Overlay.NONE;
            } else if (key == InputConstants.KEY_ESCAPE) {
                overlay = Overlay.NONE;
            } else if (shareCommentBox != null) {
                shareCommentBox.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        if (overlay == Overlay.CONFIRM) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                overlay = Overlay.NONE;
                if (confirmAction != null) {
                    confirmAction.run();
                }
            } else if (key == InputConstants.KEY_ESCAPE) {
                overlay = Overlay.NONE;
            }
            return true;
        }
        if (overlay == Overlay.REGION) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                saveRegion();
            } else if (key == InputConstants.KEY_ESCAPE) {
                cancelRegion();
            } else {
                regionNameBox.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        if (overlay == Overlay.VIEWER) {
            if (key == InputConstants.KEY_LEFT) {
                switchShot(-1);
            } else if (key == InputConstants.KEY_RIGHT) {
                switchShot(1);
            } else if (key == InputConstants.KEY_Z && hasControlDown()) {
                undo();
            } else if (key == InputConstants.KEY_ESCAPE && annotating) {
                annotating = false;
                annotCurrent = null;
            } else if (key == InputConstants.KEY_ESCAPE || ModKeys.OPEN_MAP.matches(keyCode, scanCode)) {
                overlay = Overlay.NONE;
            }
            return true;
        }
        if (overlay == Overlay.ICONS && key != InputConstants.KEY_ESCAPE) {
            setFocused(iconSearch);
            iconSearch.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (confirmBindTarget != null) {
            if (key == InputConstants.KEY_ESCAPE) {
                confirmBindTarget = null;
            }
            return true;
        }
        if (previewMeta != null) {
            if (key == InputConstants.KEY_ESCAPE || ModKeys.OPEN_MAP.matches(keyCode, scanCode)) {
                this.onClose();
            }
            return true;
        }
        if (overlay != Overlay.NONE) {
            if (key == InputConstants.KEY_ESCAPE) {
                if (overlay == Overlay.ICONS && !iconSearch.getValue().isEmpty()) {
                    iconSearch.setValue("");
                } else {
                    overlay = Overlay.NONE;
                    setFocused(null);
                }
            }
            return true;
        }
        if (typing()) {
            if (key == InputConstants.KEY_ESCAPE) {
                setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (key == InputConstants.KEY_ESCAPE) {
            if (tool != Tool.NONE) {
                setTool(Tool.NONE);
                return true;
            }
            onClose();
            return true;
        }
        if (ModKeys.OPEN_MAP.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (ModKeys.MARKER_LIST.matches(keyCode, scanCode)) {
            toggleList();
            return true;
        }
        if (ModKeys.RULER.matches(keyCode, scanCode)) {
            setTool(tool == Tool.RULER ? Tool.NONE : Tool.RULER);
            return true;
        }
        if (ModKeys.EDITOR.matches(keyCode, scanCode)) {
            setTool(tool == Tool.DRAW ? Tool.NONE : Tool.DRAW);
            return true;
        }
        if (ModKeys.TOGGLE_DRAWINGS.matches(keyCode, scanCode)) {
            toggleDrawings();
            return true;
        }
        if (ModKeys.NEW_MARKER.matches(keyCode, scanCode)) {
            newMarkerAtPlayer();
            return true;
        }
        if (key == InputConstants.KEY_Z && hasControlDown()) {
            undo();
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE && tool == Tool.RULER) {
            ruler.clear();
            return true;
        }
        if (key == InputConstants.KEY_DELETE) {
            if (selectedRegion != null) {
                confirmDeleteRegion();
                return true;
            }
            if (selected != null) {
                confirmDelete(selected);
                return true;
            }
        }
        if (key == InputConstants.KEY_C) {
            centerOnPlayer();
            return true;
        }
        if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
            zoomBy(1.5f, width / 2.0, height / 2.0);
            return true;
        }
        if (key == InputConstants.KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
            zoomBy(1 / 1.5f, width / 2.0, height / 2.0);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (overlay == Overlay.SHARE) {
            if (shareCommentBox != null) {
                setFocused(shareCommentBox);
                return shareCommentBox.charTyped(chr, modifiers);
            }
            return true;
        }
        if (overlay == Overlay.ICONS) {
            setFocused(iconSearch);
            return iconSearch.charTyped(chr, modifiers);
        }
        if (overlay != Overlay.NONE) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    // ------------------------------------------------------------------ utils

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private static String fmt(double d) {
        return d >= 100 ? Long.toString(Math.round(d)) : String.format(Locale.ROOT, "%.1f", d);
    }

    private static String fmtInt(double d) {
        return Long.toString(Math.round(d));
    }

    private static Component keyName(net.minecraft.client.KeyMapping k) {
        return k.getTranslatedKeyMessage();
    }

    private double scrollMaps;

    private List<WorldMeta> filterAndSortMaps(List<WorldMeta> in, String filter, int sort) {
        List<WorldMeta> out = new ArrayList<>();
        for (WorldMeta w : in) {
            if (filter.isEmpty()) {
                out.add(w);
                continue;
            }
            String n = (w.name != null ? w.name : "").toLowerCase(Locale.ROOT);
            String a = (w.address != null ? w.address : "").toLowerCase(Locale.ROOT);
            String id = (w.id != null ? w.id : "").toLowerCase(Locale.ROOT);
            if (n.contains(filter) || a.contains(filter) || id.contains(filter)) {
                out.add(w);
            }
        }
        String activeId = session != null && session.meta != null ? session.meta.id : "";
        out.sort((a, b) -> {
            boolean aAct = a.id.equals(activeId);
            boolean bAct = b.id.equals(activeId);
            if (aAct != bAct) return aAct ? -1 : 1;
            return switch (sort) {
                case 1 -> {
                    String na = a.name != null && !a.name.isBlank() ? a.name : a.id;
                    String nb = b.name != null && !b.name.isBlank() ? b.name : b.id;
                    yield String.CASE_INSENSITIVE_ORDER.compare(na, nb);
                }
                case 2 -> {
                    int ca = markerCounts.computeIfAbsent(a.id, Storage::countMarkers);
                    int cb = markerCounts.computeIfAbsent(b.id, Storage::countMarkers);
                    yield Integer.compare(cb, ca);
                }
                default -> Long.compare(b.lastPlayed, a.lastPlayed);
            };
        });
        return out;
    }

    private void drawMapRow(GuiGraphics g, int mx, int my, int ox, int ow, int top, int bottom, int y, int rowH, WorldMeta w, String activeId) {
        boolean active = w.id.equals(activeId);
        boolean hover = Paper.inside(mx, my, ox + 8, y, ow - 16, rowH - 2) && my >= top && my < bottom;

        if (active) {
            g.fill(ox + 8, y, ox + ow - 8, y + rowH - 2, Paper.a(Paper.GOLD, 0x40));
            Paper.outline(g, ox + 8, y, ow - 16, rowH - 2, Paper.GOLD);
        } else if (hover) {
            g.fill(ox + 8, y, ox + ow - 8, y + rowH - 2, 0x25FFFFFF);
            Paper.outline(g, ox + 8, y, ow - 16, rowH - 2, Paper.INK_SOFT);
        }

        String icon = w.isRealm() ? "star" : (w.isServer() ? "globe" : "map");
        Paper.icon(g, Paper.tool(icon), ox + 12, y + 5, 16);

        String name = (w.name != null && !w.name.isBlank()) ? w.name : w.id;
        name = font.plainSubstrByWidth(name, ow - 110);
        g.drawString(font, name, ox + 32, y + 4, active ? Paper.GOLD : Paper.INK, false);

        int count = markerCounts.computeIfAbsent(w.id, Storage::countMarkers);
        String sub = (w.address != null && !w.address.isBlank()) ? w.address : w.id;
        sub = sub + " (" + count + ")";
        sub = font.plainSubstrByWidth(sub, ow - 110);
        g.drawString(font, sub, ox + 32, y + 14, Paper.INK_SOFT, false);

        if (active) {
            String actStr = T.s("map.active");
            g.drawString(font, actStr, ox + ow - 36 - font.width(actStr), y + 8, 0xFF44AA44, false);
        }

        int ex = ox + ow - 28;
        int ey = y + 5;
        boolean eh = hover && Paper.inside(mx, my, ex, ey, 16, 16);
        Paper.iconButton(g, Paper.tool("eye"), ex, ey, 16, eh, false);
        if (eh) {
            tooltip = T.tr("map.preview_tooltip");
        }

        int clipY = Math.max(y, top);
        int clipH = Math.min(y + rowH - 2, bottom) - clipY;
        if (clipH > 0) {
            if (ey >= top && ey + 16 <= bottom) {
                hit(ex, ey, 16, 16, () -> openPreviewMap(w));
            }
            if (!active) {
                hit(ox + 8, clipY, ow - 38, clipH, () -> openConfirmBind(w));
            }
        }
    }

    private void drawMapSelect(GuiGraphics g, int mx, int my) {
        blocker(() -> overlay = Overlay.NONE);
        int ow = Math.min(320, width - 20);
        int oh = Math.min(height - 24, 380);
        int ox = (width - ow) / 2;
        int oy = (height - oh) / 2;
        Paper.panel(g, ox, oy, ow, oh);
        ui(ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {
        });

        Paper.title(g, font, T.s("map.select_title"), ox + ow / 2, oy + 8);
        closeButton(g, mx, my, ox + ow - 18, oy + 5, () -> overlay = Overlay.NONE);

        Minecraft mc = Minecraft.getInstance();
        String defaultKey = MapManager.resolveDefaultKey(mc);
        boolean hasOverride = defaultKey != null && ModConfig.get().serverMapOverrides.containsKey(defaultKey);

        int btnY = oy + oh - 24;
        int btnH = 16;
        if (session.isLive() && defaultKey != null) {
            int bW = hasOverride ? (ow - 20) / 2 : (ow - 16);
            boolean hNew = Paper.inside(mx, my, ox + 8, btnY, bW, btnH);
            Paper.iconTextButton(g, font, Paper.tool("plus"), T.tr("map.new_server_map"), ox + 8, btnY, bW, btnH, hNew, true);
            hit(ox + 8, btnY, bW, btnH, this::createNewServerMap);

            if (hasOverride) {
                int rx = ox + 8 + bW + 4;
                boolean hReset = Paper.inside(mx, my, rx, btnY, bW, btnH);
                Paper.iconTextButton(g, font, Paper.tool("undo"), T.tr("map.reset_override"), rx, btnY, bW, btnH, hReset, true);
                hit(rx, btnY, bW, btnH, () -> switchMapTo(null));
            }
        }

        String curName = session.meta.name != null && !session.meta.name.isBlank() ? session.meta.name : session.meta.id;
        String curInfo = T.s("map.current_map", font.plainSubstrByWidth(curName, ow - 40));
        g.drawString(font, curInfo, ox + 10, oy + 22, Paper.INK_SOFT, false);

        int sy = oy + 34;
        drawListSearch(g, mapSearchBox, ox + 8, sy, ow - 16, "map.search_all");

        int top = sy + 18;
        int bottom = (session.isLive() && defaultKey != null) ? btnY - 6 : oy + oh - 8;
        int areaH = bottom - top;

        List<WorldMeta> allWorlds = Storage.listWorlds();
        List<WorldMeta> rawLocals = new ArrayList<>();
        List<WorldMeta> rawServers = new ArrayList<>();
        List<WorldMeta> rawRealms = new ArrayList<>();
        for (WorldMeta w : allWorlds) {
            if (w.isRealm()) {
                rawRealms.add(w);
            } else if (w.isServer()) {
                rawServers.add(w);
            } else {
                rawLocals.add(w);
            }
        }

        boolean globalSearch = !mapSearch.isBlank();
        List<WorldMeta> locals = filterAndSortMaps(rawLocals, globalSearch ? mapSearch : localMapSearch, localSort);
        List<WorldMeta> servers = filterAndSortMaps(rawServers, globalSearch ? mapSearch : serverMapSearch, serverSort);
        List<WorldMeta> realms = filterAndSortMaps(rawRealms, globalSearch ? mapSearch : realmMapSearch, realmSort);

        int rowH = 26;
        int totalH = 0;
        if (globalSearch) {
            int totalMatches = locals.size() + servers.size() + realms.size();
            totalH = totalMatches == 0 ? 18 : totalMatches * rowH;
        } else {
            if (!rawLocals.isEmpty()) {
                totalH += 18;
                if (localsOpen) totalH += 18 + locals.size() * rowH;
            }
            if (!rawServers.isEmpty()) {
                totalH += 18;
                if (serversOpen) totalH += 18 + servers.size() * rowH;
            }
            if (!rawRealms.isEmpty()) {
                totalH += 18;
                if (realmsOpen) totalH += 18 + realms.size() * rowH;
            }
        }

        double maxScroll = Math.max(0, totalH - areaH);
        scrollMaps = Math.max(0, Math.min(maxScroll, scrollMaps));
        scroll(ox + 8, top, ow - 16, areaH, d -> scrollMaps -= d * rowH * 1.5);

        String activeId = session.meta.id;
        g.enableScissor(ox + 6, top, ox + ow - 6, bottom);
        int y = top + 2 - (int) scrollMaps;

        if (globalSearch) {
            if (locals.isEmpty() && servers.isEmpty() && realms.isEmpty()) {
                g.drawString(font, T.tr("list.nothing_found"), ox + 12, y + 4, Paper.DISABLED, false);
            } else {
                for (WorldMeta w : locals) {
                    if (y + rowH >= top && y <= bottom) drawMapRow(g, mx, my, ox, ow, top, bottom, y, rowH, w, activeId);
                    y += rowH;
                }
                for (WorldMeta w : servers) {
                    if (y + rowH >= top && y <= bottom) drawMapRow(g, mx, my, ox, ow, top, bottom, y, rowH, w, activeId);
                    y += rowH;
                }
                for (WorldMeta w : realms) {
                    if (y + rowH >= top && y <= bottom) drawMapRow(g, mx, my, ox, ow, top, bottom, y, rowH, w, activeId);
                    y += rowH;
                }
            }
        } else {
            if (!rawLocals.isEmpty()) {
                drawListHeader(g, mx, my, ox + 8, y, ow - 16, top, bottom, "map.section.locals", locals.size(),
                        localsOpen, MAP_SORTS, localSort,
                        () -> localsOpen = !localsOpen,
                        () -> localSort = (localSort + 1) % MAP_SORTS.length,
                        () -> localSort = (localSort + MAP_SORTS.length - 1) % MAP_SORTS.length,
                        null, false);
                y += 18;
                if (localsOpen) {
                    drawListSearch(g, localMapSearchBox, ox + 8, y + 2, ow - 16, "map.search_locals");
                    y += 18;
                    for (WorldMeta w : locals) {
                        if (y + rowH >= top && y <= bottom) drawMapRow(g, mx, my, ox, ow, top, bottom, y, rowH, w, activeId);
                        y += rowH;
                    }
                }
            }
            if (!rawServers.isEmpty()) {
                drawListHeader(g, mx, my, ox + 8, y, ow - 16, top, bottom, "map.section.servers", servers.size(),
                        serversOpen, MAP_SORTS, serverSort,
                        () -> serversOpen = !serversOpen,
                        () -> serverSort = (serverSort + 1) % MAP_SORTS.length,
                        () -> serverSort = (serverSort + MAP_SORTS.length - 1) % MAP_SORTS.length,
                        null, false);
                y += 18;
                if (serversOpen) {
                    drawListSearch(g, serverMapSearchBox, ox + 8, y + 2, ow - 16, "map.search_servers");
                    y += 18;
                    for (WorldMeta w : servers) {
                        if (y + rowH >= top && y <= bottom) drawMapRow(g, mx, my, ox, ow, top, bottom, y, rowH, w, activeId);
                        y += rowH;
                    }
                }
            }
            if (!rawRealms.isEmpty()) {
                drawListHeader(g, mx, my, ox + 8, y, ow - 16, top, bottom, "map.section.realms", realms.size(),
                        realmsOpen, MAP_SORTS, realmSort,
                        () -> realmsOpen = !realmsOpen,
                        () -> realmSort = (realmSort + 1) % MAP_SORTS.length,
                        () -> realmSort = (realmSort + MAP_SORTS.length - 1) % MAP_SORTS.length,
                        null, false);
                y += 18;
                if (realmsOpen) {
                    drawListSearch(g, realmMapSearchBox, ox + 8, y + 2, ow - 16, "map.search_realms");
                    y += 18;
                    for (WorldMeta w : realms) {
                        if (y + rowH >= top && y <= bottom) drawMapRow(g, mx, my, ox, ow, top, bottom, y, rowH, w, activeId);
                        y += rowH;
                    }
                }
            }
        }
        g.disableScissor();

        if (confirmBindTarget != null) {
            drawConfirmBind(g, mx, my);
        }
    }

    private void openPreviewMap(WorldMeta w) {
        MapSession s = MapManager.openForViewing(w);
        if (s != null) {
            suspended = true;
            minecraft.setScreen(new MapScreen(this, s, true, w));
            Paper.pageTurn();
        }
    }

    private void drawPreviewHud(GuiGraphics g, int mx, int my) {
        int pw = Math.min(360, width - 40);
        int px = (width - pw) / 2;
        Paper.panel(g, px, 6, pw, 22);
        ui(px, 6, pw, 22);
        hit(px, 6, pw, 22, () -> {});
        String pTitle = T.s("map.preview_title", previewMeta.name != null && !previewMeta.name.isBlank() ? previewMeta.name : previewMeta.id);
        Paper.title(g, font, font.plainSubstrByWidth(pTitle, pw - 40), px + pw / 2, 11);
        Paper.icon(g, Paper.tool(previewMeta.isRealm() ? "star" : (previewMeta.isServer() ? "globe" : "map")), px + 6, 9, 16);

        List<String> dims = session.knownDims();
        if (!dims.contains(dim)) {
            dims.add(dim);
        }
        int dw = 130;
        int dh = dims.size() * 19 + 26;
        int dx = 10;
        int dy = Math.max(10, (height - dh) / 2);
        Paper.panel(g, dx, dy, dw, dh);
        ui(dx, dy, dw, dh);
        hit(dx, dy, dw, dh, () -> {});
        Paper.title(g, font, T.s("dims.title"), dx + dw / 2, dy + 8);
        int y = dy + 22;
        for (String d : dims) {
            boolean cur = d.equals(dim);
            boolean h = Paper.inside(mx, my, dx + 6, y, dw - 12, 16);
            Paper.button(g, font, Component.literal((cur ? "» " : "") + T.dimName(d) + (cur ? " «" : "")), dx + 6, y, dw - 12, 16, h, true);
            hit(dx + 6, y, dw - 12, 16, () -> switchDim(d));
            y += 19;
        }

        int bw = 150;
        int bh = 20;
        int gap = 16;
        int totalW = bw * 2 + gap;
        int bx = (width - totalW) / 2;
        int by = height - 30;

        boolean canBind = MapManager.live() != null;
        boolean hBind = canBind && Paper.inside(mx, my, bx, by, bw, bh);
        Paper.iconTextButton(g, font, Paper.tool("check"), T.tr("map.preview.bind"), bx, by, bw, bh, hBind, canBind);
        if (canBind) {
            hit(bx, by, bw, bh, () -> openConfirmBind(previewMeta));
        }

        boolean hBack = Paper.inside(mx, my, bx + bw + gap, by, bw, bh);
        Paper.iconTextButton(g, font, Paper.tool("left"), T.tr("map.preview.back"), bx + bw + gap, by, bw, bh, hBack, true);
        hit(bx + bw + gap, by, bw, bh, this::onClose);
    }

    private void openConfirmBind(WorldMeta target) {
        this.confirmBindTarget = target;
        Paper.click();
    }

    private void drawConfirmBind(GuiGraphics g, int mx, int my) {
        blocker(() -> confirmBindTarget = null);
        g.fill(0, 0, width, height, Paper.DIM);
        modalStart = hits.size();

        int ow = Math.min(360, width - 20);
        List<FormattedCharSequence> titleLines = font.split(T.tr("map.confirm_bind.title"), ow - 24);
        int lineH = titleLines.size() * 10;
        int btnH = 20;
        int btnGap = 5;
        int totalBtnH = 3 * btnH + 2 * btnGap;
        int oh = 24 + lineH + 12 + totalBtnH + 16;
        int ox = (width - ow) / 2;
        int oy = (height - oh) / 2;

        Paper.panel(g, ox, oy, ow, oh);
        hit(ox, oy, ow, oh, () -> {});

        int ty = oy + 12;
        for (FormattedCharSequence l : titleLines) {
            g.drawString(font, l, ox + (ow - font.width(l)) / 2, ty, Paper.INK, false);
            ty += 10;
        }
        ty += 8;

        int bw = ow - 24;
        int bx = ox + 12;

        boolean h1 = Paper.inside(mx, my, bx, ty, bw, btnH);
        Paper.button(g, font, T.tr("map.confirm_bind.full"), bx, ty, bw, btnH, h1, true);
        if (h1) {
            tooltip = T.tr("map.confirm_bind.full.tooltip");
        }
        hit(bx, ty, bw, btnH, () -> {
            WorldMeta target = confirmBindTarget;
            confirmBindTarget = null;
            executeBindMap(target, true);
        });
        ty += btnH + btnGap;

        boolean h2 = Paper.inside(mx, my, bx, ty, bw, btnH);
        Paper.button(g, font, T.tr("map.confirm_bind.markers_only"), bx, ty, bw, btnH, h2, true);
        if (h2) {
            tooltip = T.tr("map.confirm_bind.markers_only.tooltip");
        }
        hit(bx, ty, bw, btnH, () -> {
            WorldMeta target = confirmBindTarget;
            confirmBindTarget = null;
            executeBindMap(target, false);
        });
        ty += btnH + btnGap;

        boolean h3 = Paper.inside(mx, my, bx, ty, bw, btnH);
        Paper.button(g, font, T.tr("map.confirm_bind.cancel"), bx, ty, bw, btnH, h3, true);
        if (h3) {
            tooltip = T.tr("map.confirm_bind.cancel.tooltip");
        }
        hit(bx, ty, bw, btnH, () -> confirmBindTarget = null);
    }

    private void executeBindMap(WorldMeta source, boolean fullCopy) {
        if (source == null) return;
        Minecraft mc = Minecraft.getInstance();
        String currentKey = MapManager.resolveDefaultKey(mc);
        if (currentKey == null) {
            if (session != null && session.meta != null) {
                currentKey = session.meta.id;
            } else {
                return;
            }
        }

        final String targetId = currentKey;
        final String sourceId = source.id;

        if (targetId.equals(sourceId)) {
            return;
        }

        try {
            Path srcDir = Storage.worldDir(sourceId);
            Path dstDir = Storage.worldDir(targetId);

            if (!Files.isDirectory(srcDir)) {
                return;
            }

            if (fullCopy) {
                if (Files.isDirectory(dstDir)) {
                    MapManager.archiveWorld(targetId, "Replaced by " + sourceId);
                }
                Files.createDirectories(dstDir);
                Storage.copyRecursive(srcDir, dstDir);

                WorldMeta meta = Storage.readJson(dstDir.resolve("meta.json"), WorldMeta.class, null);
                if (meta == null) {
                    meta = new WorldMeta();
                }
                meta.id = targetId;
                WorldMeta liveMeta = MapManager.live() != null ? MapManager.live().meta : null;
                if (liveMeta != null) {
                    meta.name = liveMeta.name;
                    meta.address = liveMeta.address;
                    meta.type = liveMeta.type;
                    meta.seed = liveMeta.seed;
                    meta.uid = liveMeta.uid;
                }
                meta.lastPlayed = System.currentTimeMillis();
                Storage.writeJson(dstDir.resolve("meta.json"), meta);

                ModConfig cfg = ModConfig.get();
                if (cfg != null && cfg.serverMapOverrides != null) {
                    cfg.serverMapOverrides.remove(targetId);
                    cfg.save();
                }

                if (session.isLive()) {
                    MapManager.switchLive(targetId);
                    session = MapManager.live();
                } else {
                    session.close();
                    session = MapSession.open(meta);
                }
                WaypointShare.notifyPlayer(T.s("map.bind.success", source.name != null && !source.name.isBlank() ? source.name : source.id));
            } else {
                Files.createDirectories(dstDir);
                if (Files.exists(srcDir.resolve("markers.json"))) {
                    Files.copy(srcDir.resolve("markers.json"), dstDir.resolve("markers.json"), StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(srcDir.resolve("drawings.json"))) {
                    Files.copy(srcDir.resolve("drawings.json"), dstDir.resolve("drawings.json"), StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(srcDir.resolve("regions.json"))) {
                    Files.copy(srcDir.resolve("regions.json"), dstDir.resolve("regions.json"), StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.isDirectory(srcDir.resolve("screenshots"))) {
                    Storage.copyRecursive(srcDir.resolve("screenshots"), dstDir.resolve("screenshots"));
                }

                if (session.isLive()) {
                    MapManager.switchLive(targetId);
                    session = MapManager.live();
                } else {
                    WorldMeta meta = Storage.readJson(dstDir.resolve("meta.json"), WorldMeta.class, null);
                    session.close();
                    session = MapSession.open(meta != null ? meta : source);
                }
                WaypointShare.notifyPlayer(T.s("map.bind.markers_success", source.name != null && !source.name.isBlank() ? source.name : source.id));
            }

            if (previewMeta != null) {
                if (parent instanceof MapScreen pMap) {
                    pMap.overlay = Overlay.NONE;
                    pMap.session = MapManager.live();
                }
                minecraft.setScreen(parent);
            } else {
                overlay = Overlay.NONE;
            }
            Paper.pageTurn();
        } catch (Exception e) {
            Storage.LOG.error("Failed to bind map " + sourceId + " to " + targetId, e);
        }
    }

    private void switchMapTo(String targetId) {
        if (session.isLive()) {
            MapManager.switchLive(targetId);
            session = MapManager.live();
        } else if (targetId != null) {
            WorldMeta meta = Storage.readJson(Storage.worldDir(targetId).resolve("meta.json"), WorldMeta.class, null);
            if (meta != null) {
                session.close();
                session = MapSession.open(meta);
            }
        }
        if (session != null) {
            selected = null;
            working = null;
            panel = Panel.NONE;
            selectedRegion = null;
            editingRegion = null;
            dim = session.meta.lastDim;
            view.cx = session.meta.lastX;
            view.cz = session.meta.lastZ;
            if (session.isLive()) {
                centerOnPlayer();
            }
            WaypointShare.notifyPlayer(T.s("map.switched", session.meta.name != null && !session.meta.name.isBlank() ? session.meta.name : session.meta.id));
        }
    }

    private void createNewServerMap() {
        Minecraft mc = Minecraft.getInstance();
        String defaultKey = MapManager.resolveDefaultKey(mc);
        if (defaultKey == null) {
            return;
        }
        int idx = 2;
        String newId = defaultKey + "_" + idx;
        while (Files.exists(Storage.worldDir(newId))) {
            idx++;
            newId = defaultKey + "_" + idx;
        }
        WorldMeta meta = new WorldMeta();
        meta.id = newId;
        if (mc.getSingleplayerServer() != null) {
            meta.type = WorldMeta.LOCAL;
            String levelName = mc.getSingleplayerServer().getWorldData().getLevelName();
            meta.name = (levelName != null && !levelName.isBlank()) ? levelName + " #" + idx : "World #" + idx;
            meta.address = defaultKey.startsWith("local_") ? defaultKey.substring(6) : defaultKey;
        } else {
            meta.type = WorldMeta.SERVER;
            meta.address = defaultKey.startsWith("server_") ? defaultKey.substring(7) : defaultKey;
            meta.name = (mc.getCurrentServer() != null && mc.getCurrentServer().name != null && !mc.getCurrentServer().name.isBlank())
                    ? mc.getCurrentServer().name + " #" + idx
                    : meta.address + " #" + idx;
        }
        meta.lastPlayed = System.currentTimeMillis();
        Storage.writeJson(Storage.worldDir(newId).resolve("meta.json"), meta);

        switchMapTo(newId);
        overlay = Overlay.NONE;
        Paper.click();
    }
}