package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.webtrade.atlasmapextended.client.data.ModConfig;
import org.webtrade.atlasmapextended.client.gui.Paper;

import java.util.HashSet;
import java.util.Set;

/**
 * Draws the 3D relief right after the vanilla map quad, using the same
 * map-pixel pose (128 px = 1 block).
 */
public final class FramedMapRenderer {
    private static final Identifier WHITE = Paper.id("textures/gui/white.png");
    private static final float ICON_SCALE = 1.0f;
    /** Glow item frames make the relief and its objects look sunlit. */
    private static final float GLOW_MESH_BRIGHTNESS = 1.45f;
    private static final float GLOW_ICON_BRIGHTNESS = 1.5f;
    /** One full eased turn, then a short rest before the next one. */
    private static final int SPIN_TICKS = 80;
    private static final int SPIN_PAUSE = 5;
    private static final Set<String> LOGGED = new HashSet<>();

    private FramedMapRenderer() {
    }

    public static void render(ItemFrameRenderState state, PoseStack pose, SubmitNodeCollector collector,
                              int textureMask, int tileDiv, int tileCol, int tileRow, int tileRotation) {
        if (state.mapId == null) {
            return;
        }
        if (!ModConfig.get().framedMaps3D) {
            logOnce("disabled:" + state.mapId.id(), "render map={} skipped: framedMaps3D=false",
                    state.mapId.id());
            return;
        }
        Paper.ensureFrom(WHITE, WHITE);
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        MapItemSavedData data = level.getMapData(state.mapId);
        if (data == null || data.colors == null) {
            logOnce("nodata:" + state.mapId.id(), "render map={} skipped: data={} colors={}",
                    state.mapId.id(), data != null, data != null && data.colors != null);
            return;
        }

        int div = Math.max(1, tileDiv);
        if (tooSmallOnScreen(state, div, mc)) {
            return;
        }
        int rotation = megatileRotation(div, tileRotation);
        FramedMapMesh mesh = div > 1
                ? FramedMapCache.getGroup(level, state.mapId, data, div, rotation)
                : FramedMapCache.getOrRequest(
                        level, state.mapId, data, textureMask, div, tileCol, tileRow, rotation);
        if (mesh == null) {
            logOnce("nomesh:" + state.mapId.id() + ":" + textureMask + ":" + div + ":" + tileCol + ":" + tileRow + ":" + rotation,
                    "render map={} mask={} center={},{} tile={}x{} col={} row={} rot={} scale={} -> mesh not ready",
                    state.mapId.id(), textureMask, data.centerX, data.centerZ,
                    div, div, tileCol, tileRow, rotation, data.scale);
            return;
        }
        logOnce("mesh:" + state.mapId.id() + ":" + textureMask + ":" + div + ":" + tileCol + ":" + tileRow + ":" + rotation
                        + ":" + System.identityHashCode(mesh),
                "render map={} mask={} center={},{} tile={}x{} col={} row={} rot={} scale={} glow={} quads={} lines={} icons={}",
                state.mapId.id(), textureMask, data.centerX, data.centerZ,
                div, div, tileCol, tileRow, rotation, data.scale,
                state.isGlowFrame, mesh.quadCount(), mesh.lineCount(), mesh.voxelIcons().size());
        long gameTime = level.getGameTime();
        float meshBrightness = state.isGlowFrame ? GLOW_MESH_BRIGHTNESS : 1.0f;
        float iconBrightness = state.isGlowFrame ? GLOW_ICON_BRIGHTNESS : 1.0f;
        // A normal frame is lit by the room; a glowing frame is self-lit.
        int light = state.isGlowFrame ? 15728880 : state.lightCoords;
        pose.pushPose();
        if (div > 1) {
            // The whole mega-map is one mesh. Map its full 128x128 image into
            // the leader tile's pose: scale the image by N and shift it so the
            // tile's sub-rectangle lands exactly in this frame.
            pose.translate(-tileCol * (float) FramedMapVolume.SIZE,
                    -tileRow * (float) FramedMapVolume.SIZE, 0.0f);
            pose.scale(div, div, -1.0f);
        } else {
            pose.scale(1.0f, 1.0f, -1.0f);
        }
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(WHITE, false),
                (p, consumer) -> mesh.emit(p.pose(), consumer, meshBrightness, light));
        if (mesh.lineCount() > 0) {
            collector.submitCustomGeometry(pose, RenderTypes.lines(),
                    (p, consumer) -> mesh.emitLines(p.pose(), consumer, meshBrightness));
        }
        float angle = spinAngle(gameTime);
        boolean verticalFrame = state.direction != null && state.direction.getAxis().isVertical();
        float standUp = (float) Math.toRadians(state.direction == Direction.DOWN ? 90.0 : -90.0);
        if (!mesh.voxelIcons().isEmpty()) {
            collector.submitCustomGeometry(pose, RenderTypes.entityCutout(WHITE, false), (p, consumer) -> {
                Matrix4f base = p.pose();
                // A mega group's pose already scales the whole map by div;
                // the icon geometry must stay in map pixels, not be scaled twice.
                float iconScale = ICON_SCALE;
                for (FramedMapMesh.VoxelIcon icon : mesh.voxelIcons()) {
                    IconVoxelShapes.Shape shape = IconVoxelShapes.get(icon.icon(), icon.tint());
                    if (shape == null) {
                        continue;
                    }
                    boolean arrowIcon = icon.icon().startsWith("arrow");
                    Matrix4f matrix = new Matrix4f(base).translate(icon.x(), icon.y(), icon.z());
                    if (arrowIcon) {
                        // Bullet spin around the arrow's own line; opposite
                        // directions are normalised to the same side.
                        rotateAroundArrowAxis(matrix, icon.icon(), angle);
                    } else if (verticalFrame) {
                        // Map lies flat: stand the icon up out of the map and spin it
                        // around that vertical axis, like a floating plumbob.
                        matrix.rotateZ(angle).rotateX(standUp);
                    } else {
                        matrix.rotateY(angle);
                    }
                    float yOffset = arrowIcon ? -8.0f : -16.0f;
                    matrix.scale(iconScale, iconScale, iconScale)
                            .translate(-8.0f, yOffset, -shape.depth / 2.0f);
                    shape.emit(matrix, consumer, iconBrightness, light);
                }
            });
        }
        pose.popPose();
    }

    private static int megatileRotation(int div, int rotation) {
        return div > 1 ? rotation & 3 : 0;
    }

    /**
     * Far/small maps keep their 2D base but skip the relief mesh. At less
     * than a few pixels the relief cannot be seen anyway.
     */
    private static boolean tooSmallOnScreen(ItemFrameRenderState state, int div, Minecraft mc) {
        if (mc.player == null) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        double dx = state.x - eye.x;
        double dy = state.y - eye.y;
        double dz = state.z - eye.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0) {
            return false;
        }
        double fov = Math.toRadians(mc.options.fov().get());
        double pixels = mc.getWindow().getHeight() * div
                / (2.0 * distance * Math.tan(fov * 0.5));
        return pixels < 8.0;
    }

    private static void logOnce(String key, String message, Object... args) {
        if (!FramedMapDebug.DETAILED) {
            return;
        }
        if (LOGGED.size() > 4096) {
            LOGGED.clear();
        }
        if (LOGGED.add(key)) {
            FramedMapDebug.log(message, args);
        }
    }

    public static void clearDiagnostics() {
        LOGGED.clear();
    }

    /**
     * Rolls a flat arrow around its own line, with opposite directions matched.
     * This is the agreed "bullet" behaviour: do NOT replace it with a spin
     * around the map normal or the vertical axis — both were tried and looked
     * wrong. See {@code docs/framed-map-3d.md}.
     */
    private static void rotateAroundArrowAxis(Matrix4f matrix, String icon, float angle) {
        String suffix = icon.length() > 5 ? icon.substring(5) : "";
        if (suffix.startsWith("_")) {
            suffix = suffix.substring(1);
        }
        float x = suffix.contains("e") ? 1.0f : suffix.contains("w") ? -1.0f : 0.0f;
        float y = suffix.contains("n") ? -1.0f : suffix.contains("s") ? 1.0f : 0.0f;
        if (x == 0.0f && y == 0.0f) {
            x = 1.0f;
        }
        // Canonical half-plane: E/W share the east axis, N/S share the north
        // axis, diagonals share the same diagonal. Both members of an opposite
        // pair therefore rotate the same way.
        if (x < 0.0f || (x == 0.0f && y > 0.0f)) {
            x = -x;
            y = -y;
        }
        float len = (float) Math.sqrt(x * x + y * y);
        matrix.rotate(angle, x / len, y / len, 0.0f);
    }

    /** Smooth full turn with a pause at the end of each revolution. */
    private static float spinAngle(long gameTime) {
        long cycle = SPIN_TICKS + SPIN_PAUSE;
        long t = Math.floorMod(gameTime, cycle);
        if (t >= SPIN_TICKS) {
            return (float) (Math.PI * 2.0);
        }
        float p = t / (float) SPIN_TICKS;
        float eased = p * p * p * (p * (p * 6.0f - 15.0f) + 10.0f);
        return (float) (Math.PI * 2.0 * eased);
    }
}
