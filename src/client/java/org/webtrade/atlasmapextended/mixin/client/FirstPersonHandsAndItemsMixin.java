package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;
import org.webtrade.atlasmapextended.client.map.MapLocationResolver;

/** Swaps the held filled-map texture for the AtlasMap papyrus version. */
@Mixin(FirstPersonHandsAndItems.class)
public class FirstPersonHandsAndItemsMixin {

    @Inject(method = "extractMapRenderState", at = @At("RETURN"))
    private void atlasmapextended$papyrusHeldMap(
            LocalPlayer player,
            ItemStack stack,
            MapRenderState state,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValue()) {
            return;
        }
        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) {
            return;
        }
        MapItemSavedData data = MapItem.getSavedData(mapId, player.level());
        if (data == null) {
            return;
        }
        MapLocationResolver.resolve(player.level(), mapId, data, null);
        var texture = HeldMapTextures.get(mapId, data);
        if (texture != null) {
            state.texture = texture;
        }
    }
}
