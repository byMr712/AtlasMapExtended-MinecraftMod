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

import java.util.ArrayDeque;
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
    private static final int GROUP_TICKS = 10;
    /** Large unfinished same-map clusters render flat until the mega forms. */
    private static final int INCOMPLETE_3D_LIMIT = 6;
    private static final int MAX_CACHE = 8192;
    private static final Map<Key, Cached> CACHE = new HashMap<>();
    private static final Set<Key> LOGGED_KEYS = new HashSet<>();
    private static final Set<Integer> RIM_LOGGED = new HashSet<>();
    private static final Map<SingleKey, SingleState> SINGLE = new HashMap<>();
    private static final Map<GroupKey, GroupInfo> GROUP_INFO = new HashMap<>();
    private static final Map<Long, GroupKey> POSITION_GROUP = new HashMap<>();
    /** Positions of large unfinished clusters, valid for a few ticks. */
    private static final Map<Long, Long> SUPPRESS_SCAN = new HashMap<>();
    /** One 3D group at a time keeps the cost equal to a single map. */
    private static final double MAX_3D_DISTANCE_SQ = 64.0 * 64.0;
    private static long active3DTick = Long.MIN_VALUE;
    private static long active3DGroup = Long.MIN_VALUE;
    private static double active3DDistance = Double.MAX_VALUE;
    private static Context current;

    private FramedMapConnections() {
    }

    public record Context(int frameMask, int textureMask, int worldMask,
                          int tileDiv, int tileCol, int tileRow, int tileRotation,
                          boolean glow, int light, boolean hideFrame,
                          boolean suppress3D, boolean active3D) {
    }

    private record Key(long pos, int direction, int mapId, int rotation, ResourceKey<Level> dimension) {
    }

    private record Cached(long tick, int frameMask, int textureMask, int worldMask,
                          int tileDiv, int tileCol, int tileRow, int singleRotation,
                          boolean suppress3D, GroupKey group) {
    }

    private record SingleKey(long pos, int direction, int mapId, ResourceKey<Level> dimension) {
    }

    private static final class SingleState {
        int rotation;
        int entityId = Integer.MIN_VALUE;
    }

    private record TileInfo(int div, int col, int row, GroupKey group, boolean suppress3D) {
    }

    private record GroupKey(long anchor, int div, int direction, int mapId,
                            ResourceKey<Level> dimension) {
    }

    /** Cached geometry of one complete mega group, shared by all its tiles. */
    private static final class GroupInfo {
        final GroupKey key;
        final int div;
        final int minR;
        final int minD;
        final Direction imgRight;
        final Direction imgDown;
        final Set<Long> members = new HashSet<>();
        long scanTick;

        GroupInfo(GroupKey key, int div, int minR, int minD,
                  Direction imgRight, Direction imgDown) {
            this.key = key;
            this.div = div;
            this.minR = minR;
            this.minD = minD;
            this.imgRight = imgRight;
            this.imgDown = imgDown;
        }

        TileInfo tileAt(BlockPos pos) {
            return new TileInfo(div, dot(pos, imgRight) - minR,
                    dot(pos, imgDown) - minD, key, false);
        }
    }

    private static final class GroupState {
        int rotation;
        final Map<Long, Integer> last = new HashMap<>();
        /** Entity ids of the frames that formed this group. */
        final Set<Integer> entities = new HashSet<>();
    }

    private static final Map<GroupKey, GroupState> GROUPS = new HashMap<>();

    public static Context context() {
        return current;
    }

    /** Texture-side mask for the frame currently being submitted. */
    public static int currentTextureMask() {
        return current == null ? 0 : current.textureMask();
    }

    public static int currentTileDiv() {
        return current == null ? 1 : current.tileDiv();
    }

    public static int currentTileCol() {
        return current == null ? 0 : current.tileCol();
    }

    public static int currentTileRow() {
        return current == null ? 0 : current.tileRow();
    }

    public static int currentTileRotation() {
        return current == null ? 0 : current.tileRotation();
    }

    /** Large unfinished clusters are shown flat until the square is complete. */
    public static boolean currentSuppress3D() {
        return current != null && current.suppress3D();
    }

    /** Only one map/group gets the 3D relief per tick. */
    public static boolean currentActive3D() {
        return current != null && current.active3D();
    }

    /**
     * Rotation of a floor map whose top points away from the player, so the
     * map reads like the held preview from the player's side.
     * 0 = north, 1 = east, 2 = south, 3 = west.
     */
    public static int floorPlayerRotation(double centerX, double centerZ, Vec3 eye) {
        if (eye == null) {
            return 0;
        }
        double dx = eye.x - centerX;
        double dz = eye.z - centerZ;
        int playerDirection;
        if (Math.abs(dx) > Math.abs(dz)) {
            playerDirection = dx > 0 ? 1 : 3; // east / west
        } else {
            playerDirection = dz > 0 ? 2 : 0; // south / north
        }
        return (playerDirection + 2) & 3;
    }

    /** Rotates an unrotated source pixel into the displayed mega-map image. */
    public static int rotateSourceX(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> 127 - z;
            case 2 -> 127 - x;
            case 3 -> z;
            default -> x;
        };
    }

    public static int rotateSourceZ(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> x;
            case 2 -> 127 - z;
            case 3 -> 127 - x;
            default -> z;
        };
    }

    /** Inverse of {@link #rotateSourceX}/{@link #rotateSourceZ}. */
    public static int unrotateDisplayX(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> z;
            case 2 -> 127 - x;
            case 3 -> 127 - z;
            default -> x;
        };
    }

    public static int unrotateDisplayZ(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> 127 - x;
            case 2 -> 127 - z;
            case 3 -> x;
            default -> z;
        };
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
            TileInfo tile = computeTile(level, pos, state, data, camera);
            boolean mega = tile.div() > 1;
            int frameRotation = state.rotation & 3;
            GroupInfo info = mega ? GROUP_INFO.get(tile.group()) : null;
            if (mega) {
                updateGroupRotation(tile.group(), pos, frameRotation);
                // The whole mega-map has one orientation; a rotation of any
                // frame changes the common rotation instead of one tile.
                state.rotation = 0;
            }
            int frameMask = mega && info != null
                    ? frameMaskFromInfo(info, pos, state)
                    : computeFrameMask(level, pos, state, data, state.mapId, mega);
            int worldMask = mega && info != null
                    ? worldMaskFromInfo(info, pos, state)
                    : computeWorldMask(level, pos, state, data, state.mapId, mega);
            int textureMask = state.direction.getAxis() == Direction.Axis.Y
                    ? worldTextureMask(worldMask)
                    : textureMask(frameMask, state.rotation);
            int singleRotation = 0;
            if (!mega && state.direction == Direction.UP) {
                singleRotation = singleRotation(level, pos, state, data, camera);
            }
            cached = new Cached(tick, frameMask, textureMask, worldMask,
                    tile.div(), tile.col(), tile.row(), singleRotation,
                    tile.suppress3D(), tile.group());
            if (CACHE.size() > MAX_CACHE) {
                CACHE.clear();
            }
            CACHE.put(key, cached);
            if (previous == null || previous.frameMask() != frameMask
                    || previous.worldMask() != worldMask || !LOGGED_KEYS.contains(key)) {
                logSummary(level, pos, state, data, frameMask, textureMask, worldMask,
                        tile.div(), tile.col(), tile.row(),
                        tile.group() == null ? singleRotation : groupRotation(tile.group()));
                LOGGED_KEYS.add(key);
            }
        }

        // The mega-map is one shared image: the custom texture and mesh
        // already carry the group rotation. Zero the frame's own item
        // rotation on every submit, not only when the ten-tick cache is
        // refreshed, or the vanilla map pose would add it a second time.
        if (cached.tileDiv() > 1) {
            state.rotation = 0;
        }

        boolean front = camera == null || camera.pos == null || frontSide(camera.pos, pos, state.direction);
        int frameMask = front ? cached.frameMask() : 0;
        int textureMask = front ? cached.textureMask() : 0;
        int worldMask = front ? cached.worldMask() : 0;
        int tileRotation = cached.group() != null
                ? groupRotation(cached.group()) : cached.singleRotation();
        int light = state.isGlowFrame ? 15728880 : state.lightCoords;
        long groupId = cached.group() != null ? cached.group().hashCode() : pos.asLong();
        double distanceSq = camera != null && camera.pos != null
                ? camera.pos.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                : Double.MAX_VALUE;
        if (tick != active3DTick) {
            active3DTick = tick;
            active3DGroup = Long.MIN_VALUE;
            active3DDistance = Double.MAX_VALUE;
        }
        boolean suppress = cached.suppress3D();
        if (front && !suppress && distanceSq <= MAX_3D_DISTANCE_SQ
                && (active3DGroup == Long.MIN_VALUE
                || (groupId != active3DGroup && distanceSq + 4.0 < active3DDistance))) {
            active3DGroup = groupId;
            active3DDistance = distanceSq;
        }
        boolean active3D = front && !suppress && groupId == active3DGroup;
        current = new Context(frameMask, textureMask, worldMask,
                cached.tileDiv(), cached.tileCol(), cached.tileRow(), tileRotation,
                state.isGlowFrame, light, worldMask != 0, cached.suppress3D(), active3D);
    }

    public static void end() {
        current = null;
    }

    public static void clear() {
        CACHE.clear();
        SINGLE.clear();
        GROUPS.clear();
        GROUP_INFO.clear();
        POSITION_GROUP.clear();
        SUPPRESS_SCAN.clear();
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
                                        MapItemSavedData data, MapId ownId, boolean mega) {
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
        if (hasNeighbour(level, pos.relative(right), dir, rotation, data, ownId, mega)) {
            mask |= SIDE_POS_X;
        }
        if (hasNeighbour(level, pos.relative(right.getOpposite()), dir, rotation, data, ownId, mega)) {
            mask |= SIDE_NEG_X;
        }
        if (hasNeighbour(level, pos.relative(up), dir, rotation, data, ownId, mega)) {
            mask |= SIDE_POS_Y;
        }
        if (hasNeighbour(level, pos.relative(up.getOpposite()), dir, rotation, data, ownId, mega)) {
            mask |= SIDE_NEG_Y;
        }
        return mask;
    }

    private static boolean hasNeighbour(ClientLevel level, BlockPos pos, Direction direction, int rotation,
                                        MapItemSavedData ownData, MapId ownId, boolean mega) {
        return frameAt(level, pos, direction, rotation, ownData, ownId, mega) != null;
    }

    /** The item frame that occupies exactly this block, or null. */
    private static ItemFrame frameAt(ClientLevel level, BlockPos pos, Direction direction, int rotation,
                                     MapItemSavedData ownData, MapId ownId, boolean mega) {
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0).inflate(0.01);
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {
            // The frame box is a full block, so the inflated query also finds
            // the current frame and diagonal neighbours. Only the frame that
            // actually occupies the requested block is a side neighbour.
            if (!frame.blockPosition().equals(pos)) {
                continue;
            }
            ItemStack stack = frame.getItem();
            MapId id = stack.get(DataComponents.MAP_ID);
            if (id == null) {
                continue;
            }
            if (frame.getDirection() != direction) {
                continue;
            }
            boolean sameMap = id.equals(ownId);
            if (!(mega && sameMap) && (frame.getRotation() & 3) != rotation) {
                continue;
            }
            MapItemSavedData other = level.getMapData(id);
            if (other == null || other.scale != ownData.scale
                    || !other.dimension.equals(ownData.dimension)) {
                continue;
            }
            return frame;
        }
        return null;
    }

    /** Same map, same plane, same scale, rotation intentionally ignored. */
    private static ItemFrame sameMapFrameAt(ClientLevel level, BlockPos pos, Direction direction,
                                            MapId ownId, MapItemSavedData ownData) {
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0).inflate(0.01);
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {
            if (!frame.blockPosition().equals(pos) || frame.getDirection() != direction) {
                continue;
            }
            ItemStack stack = frame.getItem();
            MapId id = stack.get(DataComponents.MAP_ID);
            if (id == null || !id.equals(ownId)) {
                continue;
            }
            MapItemSavedData other = level.getMapData(id);
            if (other != null && other.scale == ownData.scale
                    && other.dimension.equals(ownData.dimension)) {
                return frame;
            }
        }
        return null;
    }

    /**
     * A complete N×N group of identical maps becomes one big picture: each
     * frame shows one sub-rectangle of the same map, scaled to the frame.
     * Rectangles and incomplete groups do not become a mega-map.
     */
    private static TileInfo computeTile(ClientLevel level, BlockPos start, ItemFrameRenderState state,
                                        MapItemSavedData data, CameraRenderState camera) {
        TileInfo none = new TileInfo(1, 0, 0, null, false);
        if (state.mapId == null) {
            return none;
        }
        long startLong = start.asLong();
        Long suppressTick = SUPPRESS_SCAN.get(startLong);
        if (suppressTick != null) {
            long tick = level.getGameTime();
            if (tick - suppressTick < GROUP_TICKS) {
                return new TileInfo(1, 0, 0, null, true);
            }
            SUPPRESS_SCAN.remove(startLong);
        }
        GroupKey known = POSITION_GROUP.get(startLong);
        if (known != null) {
            GroupInfo info = GROUP_INFO.get(known);
            long tick = level.getGameTime();
            if (info != null && tick - info.scanTick < GROUP_TICKS
                    && known.mapId() == state.mapId.id()
                    && known.direction() == state.direction.ordinal()
                    && known.dimension().equals(level.dimension())
                    && info.members.contains(startLong)) {
                return info.tileAt(start);
            }
        }
        Direction localX;
        Direction localY;
        switch (state.direction) {
            case SOUTH -> {
                localX = Direction.WEST;
                localY = Direction.UP;
            }
            case NORTH -> {
                localX = Direction.EAST;
                localY = Direction.UP;
            }
            case EAST -> {
                localX = Direction.SOUTH;
                localY = Direction.UP;
            }
            case WEST -> {
                localX = Direction.NORTH;
                localY = Direction.UP;
            }
            case UP -> {
                localX = Direction.WEST;
                localY = Direction.NORTH;
            }
            default -> {
                localX = Direction.WEST;
                localY = Direction.SOUTH;
            }
        }
        Direction imgRight = localX.getOpposite();
        Direction imgDown = localY.getOpposite();

        Map<BlockPos, ItemFrame> members = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        ItemFrame startFrame = sameMapFrameAt(level, start, state.direction, state.mapId, data);
        if (startFrame == null) {
            return none;
        }
        members.put(start, startFrame);
        queue.add(start);
        int minR = Integer.MAX_VALUE;
        int maxR = Integer.MIN_VALUE;
        int minD = Integer.MAX_VALUE;
        int maxD = Integer.MIN_VALUE;
        int count = 0;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (++count > 256) {
                return none;
            }
            int r = dot(pos, imgRight);
            int d = dot(pos, imgDown);
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            minD = Math.min(minD, d);
            maxD = Math.max(maxD, d);
            for (Direction side : planeSides(state.direction)) {
                BlockPos next = pos.relative(side);
                if (members.containsKey(next)) {
                    continue;
                }
                ItemFrame neighbor = sameMapFrameAt(level, next, state.direction, state.mapId, data);
                if (neighbor != null) {
                    members.put(next, neighbor);
                    queue.add(next);
                }
            }
        }

        int cols = maxR - minR + 1;
        int rows = maxD - minD + 1;
        if (cols <= 1 || cols != rows || count != cols * rows) {
            // The group broke: drop its cached geometry so the remaining
            // frames do not keep rendering stale mega tiles.
            GroupKey stale = POSITION_GROUP.remove(startLong);
            if (stale != null) {
                GroupInfo old = GROUP_INFO.remove(stale);
                if (old != null) {
                    for (long member : old.members) {
                        POSITION_GROUP.remove(member, stale);
                    }
                    invalidate(old.members);
                }
            }
            // Several identical maps that do not form a full square yet:
            // render them flat until the mega-map is complete, otherwise
            // every frame submits a full 128x128 relief.
            if (count >= INCOMPLETE_3D_LIMIT && cols >= 2 && rows >= 2) {
                long tick = level.getGameTime();
                for (BlockPos member : members.keySet()) {
                    SUPPRESS_SCAN.put(member.asLong(), tick);
                }
                return new TileInfo(1, 0, 0, null, true);
            }
            return none;
        }
        int col = dot(start, imgRight) - minR;
        int row = dot(start, imgDown) - minD;

        long anchor = Long.MAX_VALUE;
        for (BlockPos member : members.keySet()) {
            anchor = Math.min(anchor, member.asLong());
        }
        GroupKey group = new GroupKey(anchor, cols, state.direction.ordinal(),
                state.mapId.id(), level.dimension());
        GroupState groupState = GROUPS.computeIfAbsent(group, g -> new GroupState());
        Set<Integer> currentEntities = new HashSet<>();
        for (ItemFrame frame : members.values()) {
            currentEntities.add(frame.getId());
        }
        Set<Long> currentMembers = new HashSet<>();
        for (BlockPos member : members.keySet()) {
            currentMembers.add(member.asLong());
        }
        double centerX = 0.0;
        double centerZ = 0.0;
        for (BlockPos member : members.keySet()) {
            centerX += member.getX() + 0.5;
            centerZ += member.getZ() + 0.5;
        }
        centerX /= members.size();
        centerZ /= members.size();
        if (!groupState.entities.equals(currentEntities)) {
            // The physical set of frames changed. If some of the same frames
            // are still here, this is the same group growing or shrinking:
            // keep its established orientation. Only a genuinely new/rebuilt
            // group takes a fresh orientation from the player's view.
            int initialRotation;
            if (intersects(groupState.entities, currentEntities)) {
                initialRotation = groupState.rotation;
            } else {
                GroupKey inherited = overlappingGroup(group, currentEntities);
                if (inherited != null) {
                    initialRotation = GROUPS.get(inherited).rotation;
                    GROUPS.remove(inherited);
                } else {
                    initialRotation = state.direction == Direction.UP && camera != null
                            ? floorPlayerRotation(centerX, centerZ, camera.pos) : 0;
                }
            }
            groupState.entities.clear();
            groupState.entities.addAll(currentEntities);
            groupState.last.clear();
            groupState.rotation = initialRotation;
            for (Map.Entry<BlockPos, ItemFrame> entry : members.entrySet()) {
                groupState.last.put(entry.getKey().asLong(), entry.getValue().getRotation() & 3);
            }
            // Let every tile pick up the fresh orientation immediately.
            invalidate(currentMembers);
            for (long member : currentMembers) {
                SUPPRESS_SCAN.remove(member);
            }
            FramedMapDebug.log("group formed map={} tile={}x{} members={} rot={}",
                    state.mapId.id(), cols, cols, members.size(), initialRotation);
        } else {
            // The same group: a click on any frame rotates the whole picture.
            for (Map.Entry<BlockPos, ItemFrame> entry : members.entrySet()) {
                long id = entry.getKey().asLong();
                int current = entry.getValue().getRotation() & 3;
                Integer previous = groupState.last.get(id);
                if (previous == null) {
                    groupState.last.put(id, current);
                } else if (current != previous) {
                    groupState.rotation = (groupState.rotation + current - previous) & 3;
                    groupState.last.put(id, current);
                    FramedMapDebug.log("group rotate map={} tile={}x{} at={} rot={}",
                            state.mapId.id(), cols, cols, entry.getKey(), groupState.rotation);
                }
            }
        }
        GroupInfo info = new GroupInfo(group, cols, minR, minD, imgRight, imgDown);
        info.members.addAll(currentMembers);
        info.scanTick = level.getGameTime();
        GroupInfo old = GROUP_INFO.put(group, info);
        if (old != null) {
            for (long member : old.members) {
                POSITION_GROUP.remove(member, group);
            }
        }
        for (long member : currentMembers) {
            POSITION_GROUP.put(member, group);
        }
        // Drop geometries of a previous shape that shared these frames.
        GROUP_INFO.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(group)) {
                return false;
            }
            if (intersectsLong(entry.getValue().members, currentMembers)) {
                for (long member : entry.getValue().members) {
                    POSITION_GROUP.remove(member, entry.getKey());
                }
                return true;
            }
            return false;
        });
        return info.tileAt(start);
    }

    private static int groupRotation(GroupKey group) {
        GroupState state = GROUPS.get(group);
        return state == null ? 0 : state.rotation & 3;
    }

    /** Cheap per-frame rotation check for a mega group, without a BFS. */
    private static void updateGroupRotation(GroupKey group, BlockPos pos, int current) {
        GroupState state = GROUPS.get(group);
        if (state == null) {
            return;
        }
        long id = pos.asLong();
        Integer previous = state.last.get(id);
        if (previous == null) {
            state.last.put(id, current);
        } else if (current != previous) {
            state.rotation = (state.rotation + current - previous) & 3;
            state.last.put(id, current);
            FramedMapDebug.log("group rotate map={} tile={}x{} at={} rot={}",
                    group.mapId(), group.div(), group.div(), pos, state.rotation);
        }
    }

    private static int frameMaskFromInfo(GroupInfo info, BlockPos pos, ItemFrameRenderState state) {
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
        if (info.members.contains(pos.relative(right).asLong())) {
            mask |= SIDE_POS_X;
        }
        if (info.members.contains(pos.relative(right.getOpposite()).asLong())) {
            mask |= SIDE_NEG_X;
        }
        if (info.members.contains(pos.relative(up).asLong())) {
            mask |= SIDE_POS_Y;
        }
        if (info.members.contains(pos.relative(up.getOpposite()).asLong())) {
            mask |= SIDE_NEG_Y;
        }
        return mask;
    }

    private static int worldMaskFromInfo(GroupInfo info, BlockPos pos, ItemFrameRenderState state) {
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
        if (info.members.contains(pos.relative(a).asLong())) {
            mask |= 1 << a.ordinal();
        }
        if (info.members.contains(pos.relative(a.getOpposite()).asLong())) {
            mask |= 1 << a.getOpposite().ordinal();
        }
        if (info.members.contains(pos.relative(b).asLong())) {
            mask |= 1 << b.ordinal();
        }
        if (info.members.contains(pos.relative(b.getOpposite()).asLong())) {
            mask |= 1 << b.getOpposite().ordinal();
        }
        return mask;
    }

    /**
     * Orientation of a lone floor map. It is chosen once, when the map is
     * first placed, from the direction the player is looking, and then stays
     * fixed no matter where the player walks.
     */
    private static int singleRotation(ClientLevel level, BlockPos pos, ItemFrameRenderState state,
                                      MapItemSavedData data, CameraRenderState camera) {
        SingleKey key = new SingleKey(pos.asLong(), state.direction.ordinal(),
                state.mapId.id(), level.dimension());
        SingleState stored = SINGLE.computeIfAbsent(key, k -> new SingleState());
        ItemFrame frame = frameAt(level, pos, state.direction, state.rotation & 3,
                data, state.mapId, false);
        if (frame != null && stored.entityId != frame.getId()) {
            stored.entityId = frame.getId();
            stored.rotation = floorPlayerRotation(pos.getX() + 0.5, pos.getZ() + 0.5,
                    camera != null ? camera.pos : null);
            FramedMapDebug.log("single map={} at={} placed rot={}",
                    state.mapId.id(), pos, stored.rotation);
        }
        return stored.rotation & 3;
    }

    private static boolean intersects(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Set<Integer> small = a.size() <= b.size() ? a : b;
        Set<Integer> large = small == a ? b : a;
        for (Integer id : small) {
            if (large.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsLong(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Set<Long> small = a.size() <= b.size() ? a : b;
        Set<Long> large = small == a ? b : a;
        for (Long id : small) {
            if (large.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** Drop only the affected frames from the per-frame context cache. */
    private static void invalidate(Set<Long> positions) {
        if (!positions.isEmpty()) {
            CACHE.entrySet().removeIf(entry -> positions.contains(entry.getKey().pos()));
        }
    }

    /** An existing group of the same map/plane that shares at least one frame. */
    private static GroupKey overlappingGroup(GroupKey group, Set<Integer> entities) {
        for (Map.Entry<GroupKey, GroupState> entry : GROUPS.entrySet()) {
            GroupKey key = entry.getKey();
            if (key.equals(group)
                    || key.mapId() != group.mapId()
                    || key.direction() != group.direction()
                    || !key.dimension().equals(group.dimension())) {
                continue;
            }
            if (intersects(entry.getValue().entities, entities)) {
                return key;
            }
        }
        return null;
    }

    private static Direction[] planeSides(Direction direction) {
        return switch (direction.getAxis()) {
            case Y -> new Direction[]{Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
            case Z -> new Direction[]{Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};
            default -> new Direction[]{Direction.SOUTH, Direction.NORTH, Direction.UP, Direction.DOWN};
        };
    }

    private static Direction directionOf(Direction a, int ca, Direction b, int cb) {
        int x = ca * a.getStepX() + cb * b.getStepX();
        int y = ca * a.getStepY() + cb * b.getStepY();
        int z = ca * a.getStepZ() + cb * b.getStepZ();
        int ax = Math.abs(x);
        int ay = Math.abs(y);
        int az = Math.abs(z);
        if (ax >= ay && ax >= az) {
            return x >= 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return y >= 0 ? Direction.UP : Direction.DOWN;
        }
        return z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int dot(BlockPos pos, Direction direction) {
        return pos.getX() * direction.getStepX()
                + pos.getY() * direction.getStepY()
                + pos.getZ() * direction.getStepZ();
    }

    /**
     * Neighbour bits in world directions. Unlike the frame-local mask this is
     * independent of how the frame model is rotated.
     */
    private static int computeWorldMask(ClientLevel level, BlockPos pos, ItemFrameRenderState state,
                                        MapItemSavedData data, MapId ownId, boolean mega) {
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
        if (hasNeighbour(level, pos.relative(a), state.direction, state.rotation & 3, data, ownId, mega)) {
            mask |= 1 << a.ordinal();
        }
        if (hasNeighbour(level, pos.relative(a.getOpposite()), state.direction, state.rotation & 3, data, ownId, mega)) {
            mask |= 1 << a.getOpposite().ordinal();
        }
        if (hasNeighbour(level, pos.relative(b), state.direction, state.rotation & 3, data, ownId, mega)) {
            mask |= 1 << b.ordinal();
        }
        if (hasNeighbour(level, pos.relative(b.getOpposite()), state.direction, state.rotation & 3, data, ownId, mega)) {
            mask |= 1 << b.getOpposite().ordinal();
        }
        return mask;
    }

    /** Diagnostic: writes one line per frame and one line per checked side. */
    private static void logSummary(ClientLevel level, BlockPos pos, ItemFrameRenderState state,
                                   MapItemSavedData data, int frameMask, int textureMask, int worldMask,
                                   int tileDiv, int tileCol, int tileRow, int tileRotation) {
        if (!FramedMapDebug.ENABLED || !FramedMapDebug.DETAILED) {
            return;
        }
        FramedMapDebug.log("frame {} map={} center={},{} tile={}x{} col={} row={} scale={} dim={} dir={} rot={} frameRot={} mask={} tex={} world={}",
                pos, state.mapId == null ? "null" : state.mapId.id(),
                data.centerX, data.centerZ, tileDiv, tileDiv, tileCol, tileRow, data.scale,
                data.dimension.identifier(), state.direction, tileRotation, state.rotation & 3,
                frameMask, textureMask, worldMask);
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
