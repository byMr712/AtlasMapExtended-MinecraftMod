package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor to set the map's center coordinates on the client. */
@Mixin(MapItemSavedData.class)
public interface MapItemSavedDataAccessor {

    @Accessor("centerX")
    @Mutable
    void atlasmapextended$setCenterX(int centerX);

    @Accessor("centerZ")
    @Mutable
    void atlasmapextended$setCenterZ(int centerZ);
}
