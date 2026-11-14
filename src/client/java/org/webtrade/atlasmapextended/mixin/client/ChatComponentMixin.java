package org.webtrade.atlasmapextended.mixin.client;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.webtrade.atlasmapextended.client.map.WaypointShare;

/** Intercepts incoming chat messages to format shared waypoints with clickable actions. */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @ModifyVariable(method = "addMessage", at = @At("HEAD"), ordinal = 0)
    private Component atlasmapextended$modifyChatMessage(Component message) {
        return WaypointShare.modifyChatMessage(message);
    }
}
