package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.webtrade.atlasmapextended.client.map.MapManager;

@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin {
    @Shadow
    public abstract String getLevelId();

    @Inject(method = "deleteLevel", at = @At("HEAD"))
    private void atlasmapextended$onDeleteLevel(CallbackInfo ci) {
        MapManager.onDeleteSingleplayerWorld(getLevelId());
    }
}
