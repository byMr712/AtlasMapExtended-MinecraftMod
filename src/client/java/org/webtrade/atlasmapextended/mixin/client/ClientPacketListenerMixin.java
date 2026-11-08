package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapCache;
import org.webtrade.atlasmapextended.client.framed3d.FramedMapConnections;
import org.webtrade.atlasmapextended.client.framed3d.FramedOverlayStorage;
import org.webtrade.atlasmapextended.client.map.HeldMapTextures;
import org.webtrade.atlasmapextended.client.map.MapManager;

/** Replaces Fabric API's join and disconnect events. */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void atlasmapextended$handleLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        FramedMapConnections.clear();
        MapManager.onJoin(Minecraft.getInstance());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void atlasmapextended$close(CallbackInfo ci) {
        FramedMapCache.clear();
        FramedMapConnections.clear();
        FramedOverlayStorage.clear();
        HeldMapTextures.clear();
        MapManager.onDisconnect();
    }
}
