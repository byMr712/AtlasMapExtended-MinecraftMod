package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapCache;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapConnections;
import org.webtrade.atlasmapextended.client.framed3d.FramedOverlayStorage;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;
import org.webtrade.atlasmapextended.client.map.MapLocationResolver;
import org.webtrade.atlasmapextended.client.map.MapManager;

/** Replaces Fabric API's join and disconnect events. */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void atlasmapextended$handleLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        FramedMapConnections.clear();
        MapManager.onJoin(Minecraft.getInstance());
    }

    @Inject(method = "handleMapItemData", at = @At("TAIL"))
    private void atlasmapextended$handleMapItemData(ClientboundMapItemDataPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) {
            return;
        }
        MapItemSavedData data = level.getMapData(packet.mapId());
        if (data == null) {
            return;
        }
        // Packet handlers run on the network thread; resolving touches files
        // and map data that the render thread reads.
        mc.execute(() -> {
            if (Minecraft.getInstance().level == level) {
                MapLocationResolver.resolve(level, packet.mapId(), data, null);
            }
        });
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void atlasmapextended$close(CallbackInfo ci) {
        FramedMapCache.clear();
        FramedMapConnections.clear();
        FramedOverlayStorage.clear();
        HeldMapTextures.clear();
        MapLocationResolver.clear();
        MapManager.onDisconnect();
    }
}
