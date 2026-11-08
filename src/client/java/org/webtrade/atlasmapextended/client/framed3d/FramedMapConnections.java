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
import org.joml.Vector3f;
import org.webtrade.atlasmapextended.client.gui.Paper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private static final Set<Key> LOGGED_KEYS = new HashSet<>();
    private static final Set<Integer> RIM_LOGGED = new HashSet<>();
    private static Context current;

    private FramedMapConnections() {
    }

    public record Context(int frameMask, int textureMask, int worldMask,
                          boolean glow, int light, boolean hideFrame) {
    }

    private record Key(long pos, int direction, int mapId, int rotation, ResourceKey<Level> dimension) {
    }

    private record Cached(long tick, int frameMask, int textureMask, int worldMask) {
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
            Cached previous = cached;
            int frameMask = computeFrameMask(level, pos, state, data);
            int worldMask = computeWorldMask(level, pos, state, data);
            int textureMask = state.direction.getAxis() == Direction.Axis.Y
                    ? worldTextureMask(worldMask)
                    : textureMask(frameMask, state.rotation);
            cached = new Cached(tick, frameMask, textureMask, worldMask);
            if (CACHE.size() > MAX_CACHE) {
                CACHE.clear();
            }
            CACHE.put(key, cached);
            if (previous == null || previous.frameMask() != frameMask
                    || previous.worldMask() != worldMask || !LOGGED_KEYS.contains(key)) {
                logSummary(level, pos, state, data, frameMask, textureMask, worldMask);
                LOGGED_KEYS.add(key);
            }
        }

        boolean front = camera == null || camera.pos == null || frontSide(camera.pos, pos, state.direction);
        int frameMask = front ? cached.frameMask() : 0;
        int textureMask = front ? cached.textureMask() : 0;
        int worldMask = front ? cached.worldMask() : 0;
        int light = state.isGlowFrame ? 15728880 : state.lightCoords;
        current = new Context(frameMask, textureMask, worldMask, state.isGlowFrame, light, worldMask != 0);
    }

    public static void end() {
        current = null;
    }

    public static void clear() {
        CACHE.clear();
        LOGGED_KEYS.clear();
        RIM_LOGGED.clear();
        FramedMapRenderer.clearDiagnostics();
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
        if (hasNeighbour(level, pos.relative(right), dir, rotation, data)) {
            mask |= SIDE_POS_X;
        }
        if (hasNeighbour(level, pos.relative(right.getOpposite()), dir, rotation, data)) {
            mask |= SIDE_NEG_X;
        }
        if (hasNeighbour(level, pos.relative(up), dir, rotation, data)) {
            mask |= SIDE_POS_Y;
        }
        if (hasNeighbour(level, pos.relative(up.getOpposite()), dir, rotation, data)) {
            mask |= SIDE_NEG_Y;
        }
        return mask;
    }

    private static boolean hasNeighbour(ClientLevel level, BlockPos pos, Direction direction, int rotation,
                                        MapItemSavedData ownData) {
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0).inflate(0.01);
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {
            // The frame box is a full block, so the inflated query also finds
            // the current frame and diagonal neighbours. Only the frame that
            // actually occupies the requested block is a side neighbour.
            if (!frame.blockPosition().equals(pos)) {
                continue;
            }
            if (frame.getDirection() != direction || (frame.getRotation() & 3) != rotation) {
                continue;
            }
            ItemStack stack = frame.getItem();
            MapId id = stack.get(DataComponents.MAP_ID);
            if (id == null) {
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
     * Neighbour bits in world directions. Unlike the frame-local mask this is
     * independent of how the frame model is rotated.
     */
    private static int computeWorldMask(ClientLevel level, BlockPos pos, ItemFrameRenderState state,
                                        MapItemSavedData data) {
        Direction a;
        Direction b;
        switch (state.direction.getAxis()) {
            case Y -> {
                a = Direction.EAST;
                b = Direction.SOUTH;
            }
            case Z -> {
                a = Direction.EAST;
                b = Direction.UP;
            }
            default -> {
                a = Direction.SOUTH;
                b = Direction.UP;
            }
        }
        int mask = 0;
        if (hasNeighbour(level, pos.relative(a), state.direction, state.rotation & 3, data)) {
            mask |= 1 << a.ordinal();
        }
        if (hasNeighbour(level, pos.relative(a.getOpposite()), state.direction, state.rotation & 3, data)) {
            mask |= 1 << a.getOpposite().ordinal();
        }
        if (hasNeighbour(level, pos.relative(b), state.direction, state.rotation & 3, data)) {
            mask |= 1 << b.ordinal();
        }
        if (hasNeighbour(level, pos.relative(b.getOpposite()), state.direction, state.rotation & 3, data)) {
            mask |= 1 << b.getOpposite().ordinal();
        }
        return mask;
    }

    /** Diagnostic: writes one line per frame and one line per checked side. */
    private static void logSummary(ClientLevel level, BlockPos pos, ItemFrameRenderState state,
                                   MapItemSavedData data, int frameMask, int textureMask, int worldMask) {
        if (!FramedMapDebug.ENABLED) {
            return;
        }
        FramedMapDebug.log("frame {} map={} center={},{} scale={} dim={} dir={} rot={} mask={} tex={} world={}",
                pos, state.mapId == null ? "null" : state.mapId.id(),
                data.centerX, data.centerZ, data.scale, data.dimension.identifier(),
                state.direction, state.rotation & 3, frameMask, textureMask, worldMask);
        Direction right;
        Direction up;
        if (state.direction.getAxis() == Direction.Axis.Y) {
            right = Direction.WEST;
            up = state.direction == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        } else {
            up = Direction.UP;
            right = switch (state.direction) {
                case SOUTH -> Direction.WEST;
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case WEST -> Direction.NORTH;
                default -> Direction.EAST;
            };
        }
        debugSide(level, pos.relative(right), "local+x", state, data);
        debugSide(level, pos.relative(right.getOpposite()), "local-x", state, data);
        debugSide(level, pos.relative(up), "local+y", state, data);
        debugSide(level, pos.relative(up.getOpposite()), "local-y", state, data);
    }

    private static void debugSide(ClientLevel level, BlockPos pos, String side,
                                  ItemFrameRenderState state, MapItemSavedData ownData) {
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0).inflate(0.01);
        var frames = level.getEntitiesOfClass(ItemFrame.class, box);
        if (frames.isEmpty()) {
            FramedMapDebug.log("  {} {}: no item frame entity", side, pos);
            return;
        }
        for (ItemFrame frame : frames) {
            if (!frame.blockPosition().equals(pos)) {
                continue;
            }
            FramedMapDebug.log("  {} candidate entity pos={} block={} box={}", side, frame.position(),
                    frame.blockPosition(), frame.getBoundingBox());
            ItemStack stack = frame.getItem();
            MapId id = stack.get(DataComponents.MAP_ID);
            if (frame.getDirection() != state.direction) {
                FramedMapDebug.log("  {} {}: frame direction {} != {}", side, pos,
                        frame.getDirection(), state.direction);
            } else if ((frame.getRotation() & 3) != (state.rotation & 3)) {
                FramedMapDebug.log("  {} {}: frame rotation {} != {}", side, pos,
                        frame.getRotation() & 3, state.rotation & 3);
            } else if (id == null) {
                FramedMapDebug.log("  {} {}: frame has no filled map", side, pos);
            } else {
                MapItemSavedData other = level.getMapData(id);
                if (other == null) {
                    FramedMapDebug.log("  {} {}: map {} data not loaded", side, pos, id.id());
                } else if (other.scale != ownData.scale) {
                    FramedMapDebug.log("  {} {}: map {} scale {} != {}", side, pos, id.id(),
                            other.scale, ownData.scale);
                } else if (!other.dimension.equals(ownData.dimension)) {
                    FramedMapDebug.log("  {} {}: map {} dim {} != {}", side, pos, id.id(),
                            other.dimension.identifier(), ownData.dimension.identifier());
                } else {
                    FramedMapDebug.log("  {} {}: OK map {} center={},{}", side, pos, id.id(),
                            other.centerX, other.centerZ);
                }
            }
        }
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

    /** Map-image edges for a floor/ceiling frame, whose sides are world X/Z. */
    private static int worldTextureMask(int worldMask) {
        int mask = 0;
        if ((worldMask & (1 << Direction.EAST.ordinal())) != 0) {
            mask |= TEX_RIGHT;
        }
        if ((worldMask & (1 << Direction.WEST.ordinal())) != 0) {
            mask |= TEX_LEFT;
        }
        if ((worldMask & (1 << Direction.SOUTH.ordinal())) != 0) {
            mask |= TEX_BOTTOM;
        }
        if ((worldMask & (1 << Direction.NORTH.ordinal())) != 0) {
            mask |= TEX_TOP;
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
                                        int worldMask, boolean glow, int light) {
        if (RIM_LOGGED.add(worldMask)) {
            Matrix4fc m = pose.last().pose();
            FramedMapDebug.log("rim worldMask={} +x={} -x={} +y={} -y={}",
                    worldMask, directionOf(m, 1, 0, 0), directionOf(m, -1, 0, 0),
                    directionOf(m, 0, 1, 0), directionOf(m, 0, -1, 0));
        }
        Paper.ensureFrom(WHITE, WHITE);
        RenderType type = RenderTypes.entityCutout(WHITE, false);
        int color = glow ? 0xFFF0DCA8 : 0xFFC8AE78;
        collector.submitCustomGeometry(pose, type, (p, consumer) -> {
            Matrix4fc matrix = p.pose();
            float z = 0.5005f;
            float w = 1.0f / 16.0f;
            if (!worldSidePresent(matrix, worldMask, 0, 1, 0)) {
                quad(consumer, matrix, -0.5f, 0.5f - w, 0.5f, 0.5f, z, color, light);
            }
            if (!worldSidePresent(matrix, worldMask, 0, -1, 0)) {
                quad(consumer, matrix, -0.5f, -0.5f, 0.5f, -0.5f + w, z, color, light);
            }
            if (!worldSidePresent(matrix, worldMask, 1, 0, 0)) {
                quad(consumer, matrix, 0.5f - w, -0.5f + w, 0.5f, 0.5f - w, z, color, light);
            }
            if (!worldSidePresent(matrix, worldMask, -1, 0, 0)) {
                quad(consumer, matrix, -0.5f, -0.5f + w, -0.5f + w, 0.5f - w, z, color, light);
            }
        });
    }

    private static Direction directionOf(Matrix4fc matrix, float x, float y, float z) {
        Vector3f v = new Vector3f(x, y, z);
        matrix.transformDirection(v);
        float ax = Math.abs(v.x);
        float ay = Math.abs(v.y);
        float az = Math.abs(v.z);
        if (ax >= ay && ax >= az) {
            return v.x >= 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return v.y >= 0 ? Direction.UP : Direction.DOWN;
        }
        return v.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** True when the frame-local direction maps to a world side that has a neighbour. */
    private static boolean worldSidePresent(Matrix4fc matrix, int worldMask, float x, float y, float z) {
        Vector3f v = new Vector3f(x, y, z);
        matrix.transformDirection(v);
        float ax = Math.abs(v.x);
        float ay = Math.abs(v.y);
        float az = Math.abs(v.z);
        Direction direction;
        if (ax >= ay && ax >= az) {
            direction = v.x >= 0 ? Direction.EAST : Direction.WEST;
        } else if (ay >= az) {
            direction = v.y >= 0 ? Direction.UP : Direction.DOWN;
        } else {
            direction = v.z >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return (worldMask & (1 << direction.ordinal())) != 0;
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
