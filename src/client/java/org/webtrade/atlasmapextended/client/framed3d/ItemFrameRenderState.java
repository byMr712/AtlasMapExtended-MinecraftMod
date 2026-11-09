package org.webtrade.atlasmapextended.client.framed3d;

import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.saveddata.maps.MapId;

public final class ItemFrameRenderState {
    public MapId mapId;
    public Direction direction;
    public double x;
    public double y;
    public double z;
    public int rotation;
    public boolean isGlowFrame;

    public static ItemFrameRenderState from(ItemFrame frame) {
        ItemFrameRenderState state = new ItemFrameRenderState();
        state.mapId = frame.getItem().get(DataComponents.MAP_ID);
        state.direction = frame.getDirection();
        state.x = frame.getX();
        state.y = frame.getY();
        state.z = frame.getZ();
        state.rotation = frame.getRotation();
        state.isGlowFrame = frame.getType() == EntityType.GLOW_ITEM_FRAME;
        return state;
    }
}
