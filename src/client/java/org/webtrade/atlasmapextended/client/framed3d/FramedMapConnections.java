package org.webtrade.atlasmapextended.client.framed3d;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.webtrade.atlasmapextended.client.gui.Paper;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds map frames that touch this one and turns the shared edges into one
 * continuous picture. The frame model is replaced by a custom rim that is
 * drawn only on the outer edges of the group.
 */
public final class FramedMapConnections {
    /** Frame-local sides. */
    public static final int SIDE_POS_X = 1;
    public static final int SIDE_NEG_X = 2;
    public static final int SIDE_POS_Y = 4;
    public static final int SIDE_NEG_Y = 8;
    /** Map-texture sides (the map texture is rotated inside the frame). */
    public static final int TEX_RIGHT = 1;
    public static final int TEX_LEFT = 2;
    public static final int TEX_BOTTOM = 4;
    public static final int TEX_TOP = 8;

    private static final Identifier WHITE = Paper.id("textures/gui/white.png");
    private static final int CACHE_TICKS = 10;
    private static final int MAX_CACHE = 8192;
    private static final Map<Key, Cached> CACHE = new HashMap<>();
    private static Context current;

    private FramedMapConnections() {
    }

    public record Context(int frameMask, int textureMask, boolean glow, int light, boolean hideFrame) {
    }

    private record Key(long pos, int direction, int mapId, int rotation, ResourceKey<Level> dimension) {
    }

    private record Cached(long tick, int frameMask, int textureMask) {
    }

    public static Context context() {
        return current;
    }

    /** Texture-side mask for the frame currently being submitted. */
    public static int currentTextureMask() {
        return current == null ? 0 : current.textureMask();
    }

    /** Call at the start of {@code ItemFrameRenderer.submit}. */
    public static void begin(ClientLevel level, ItemFrameRenderState state, CameraRenderState camera) {
        current = null;
        if (level == null || state.mapId == null || state.direction == null) {
            return;
        }
        MapItemSavedData data = level.getMapData(state.mapId);
        if (data == null) {
            return;
        }
        BlockPos pos = BlockPos.containing(state.x, state.y, state.z);
        Key key = new Key(pos.asLong(), state.direction.ordinal(), state.mapId.id(),
                state.rotation & 3, level.dimension());
        long tick = level.getGameTime();
        Cached cached = CACHE.get(key);
        if (cached == null || tick - cached.tick() >= CACHE_TICKS) {
            int frameMask = computeFrameMask(level, pos, state, data);
            int textureMask = textureMask(frameMask, state.rotation);
            cached = new Cached(tick, frameMask, textureMask);
            if (CACHE.size() > MAX_CACHE) {
                CACHE.clear();
            }
            CACHE.put(key, cached);
        }

        boolean front = camera == null || camera.pos == null || frontSide(camera.pos, pos, state.direction);
        int frameMask = front ? cached.frameMask() : 0;
        int textureMask = front ? cached.textureMask() : 0;
        int light = state.isGlowFrame ? 15728880 : state.lightCoords;
        current = new Context(frameMask, textureMask, state.isGlowFrame, light, frameMask != 0);
    }

    public static void end() {
        current = null;
    }

    public static void clear() {
        CACHE.clear();
        current = null;
    }

    private static boolean frontSide(Vec3 camera, BlockPos pos, Direction direction) {
        double dx = camera.x - (pos.getX() + 0.5);
        double dy = camera.y - (pos.getY() + 0.5);
        double dz = camera.z - (pos.getZ() + 0.5);
        return dx * direction.getStepX() + dy * direction.getStepY() + dz * direction.getStepZ() >= 0.0;
    }

    private static int computeFrameMask(ClientLevel level, BlockPos pos, ItemFrameRenderState state,
                                        MapItemSavedData data) {
        Direction right;
        Direction up;
        Direction dir = state.direction;
        if (dir.getAxis() == Direction.Axis.Y) {
            right = Direction.WEST;
            up = dir == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        } else {
            up = Direction.UP;
            right = switch (dir) {
                case SOUTH -> Direction.WEST;
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case WEST -> Direction.NORTH;
                default -> Direction.EAST;
            };
        }

        int mask = 0;
        int rotation = state.rotation & 3;
        if (hasNeighbour(level, pos.relative(right), dir, rotation, state.mapId, data)) {
            mask |= SIDE_POS_X;
        }
        if (hasNeighbour(level, pos.relative(right.getOpposite()), dir, rotation, state.mapId, data)) {
            mask |= SIDE_NEG_X;
        }
        if (hasNeighbour(level, pos.relative(up), dir, rotation, state.mapId, data)) {
            mask |= SIDE_POS_Y;
        }
        if (hasNeighbour(level, pos.relative(up.getOpposite()), dir, rotation, state.mapId, data)) {
            mask |= SIDE_NEG_Y;
        }
        return mask;
    }

    private static boolean hasNeighbour(ClientLevel level, BlockPos pos, Direction direction, int rotation,
                                        MapId ownId, MapItemSavedData ownData) {
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0).inflate(0.01);
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {
            if (frame.getDirection() != direction || (frame.getRotation() & 3) != rotation) {
                continue;
            }
            ItemStack stack = frame.getItem();
            MapId id = stack.get(DataComponents.MAP_ID);
            if (id == null || id.equals(ownId)) {
                continue;
            }
            MapItemSavedData other = level.getMapData(id);
            if (other == null || other.scale != ownData.scale
                    || !other.dimension.equals(ownData.dimension)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Converts a frame-side mask into the map-image edges that touch the
     * neighbouring frames. The image is rotated by 180° + rotation * 90° in
     * the frame, so the same rotation is used here.
     */
    public static int textureMask(int frameMask, int rotation) {
        if (frameMask == 0) {
            return 0;
        }
        double angle = Math.toRadians(180.0 + (rotation & 3) * 90.0);
        int tx = (int) Math.round(Math.cos(angle));
        int ty = (int) Math.round(Math.sin(angle));
        int mask = 0;
        if ((frameMask & SIDE_POS_X) != 0) {
            mask |= edgeForSide(1, 0, tx, ty);
        }
        if ((frameMask & SIDE_NEG_X) != 0) {
            mask |= edgeForSide(-1, 0, tx, ty);
        }
        if ((frameMask & SIDE_POS_Y) != 0) {
            mask |= edgeForSide(0, 1, tx, ty);
        }
        if ((frameMask & SIDE_NEG_Y) != 0) {
            mask |= edgeForSide(0, -1, tx, ty);
        }
        return mask;
    }

    private static int edgeForSide(int fx, int fy, int tx, int ty) {
        int alongX = fx * tx + fy * ty;
        int alongY = fx * (-ty) + fy * tx;
        if (alongX > 0) {
            return TEX_RIGHT;
        }
        if (alongX < 0) {
            return TEX_LEFT;
        }
        if (alongY > 0) {
            return TEX_BOTTOM;
        }
        if (alongY < 0) {
            return TEX_TOP;
        }
        return 0;
    }

    /**
     * Draws the wooden rim only on the outer edges of a connected map group.
     * Called in the item-frame model pose (block units, -0.5..0.5).
     */
    public static void renderOuterFrame(PoseStack pose, SubmitNodeCollector collector,
                                        int frameMask, boolean glow, int light) {
        int sides = (~frameMask) & 0x0F;
        if (sides == 0) {
            return;
        }
        Paper.ensureFrom(WHITE, WHITE);
        RenderType type = RenderTypes.entityCutout(WHITE, false);
        int color = glow ? 0xFFF0DCA8 : 0xFFC8AE78;
        collector.submitCustomGeometry(pose, type, (p, consumer) -> {
            Matrix4fc matrix = p.pose();
            float z = 0.5005f;
            float w = 1.0f / 16.0f;
            if ((sides & SIDE_POS_Y) != 0) {
                quad(consumer, matrix, -0.5f, 0.5f - w, 0.5f, 0.5f, z, color, light);
            }
            if ((sides & SIDE_NEG_Y) != 0) {
                quad(consumer, matrix, -0.5f, -0.5f, 0.5f, -0.5f + w, z, color, light);
            }
            if ((sides & SIDE_POS_X) != 0) {
                quad(consumer, matrix, 0.5f - w, -0.5f + w, 0.5f, 0.5f - w, z, color, light);
            }
            if ((sides & SIDE_NEG_X) != 0) {
                quad(consumer, matrix, -0.5f, -0.5f + w, -0.5f + w, 0.5f - w, z, color, light);
            }
        });
    }

    private static void quad(VertexConsumer consumer, Matrix4fc matrix,
                             float x0, float y0, float x1, float y1,
                             float z, int color, int light) {
        consumer.addVertex(matrix, x0, y0, z)
                .setColor(color).setUv(0.0f, 0.0f).setLight(light)
                .setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0f, 0.0f, 1.0f);
        consumer.addVertex(matrix, x1, y0, z)
                .setColor(color).setUv(1.0f, 0.0f).setLight(light)
                .setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0f, 0.0f, 1.0f);
        consumer.addVertex(matrix, x1, y1, z)
                .setColor(color).setUv(1.0f, 1.0f).setLight(light)
                .setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0f, 0.0f, 1.0f);
        consumer.addVertex(matrix, x0, y1, z)
                .setColor(color).setUv(0.0f, 1.0f).setLight(light)
                .setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0f, 0.0f, 1.0f);
    }
}
