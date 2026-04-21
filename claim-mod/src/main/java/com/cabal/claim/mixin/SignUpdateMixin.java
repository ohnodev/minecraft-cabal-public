package com.cabal.claim.mixin;

import com.cabal.claim.economy.SignInputHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SignUpdateMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
    private void cabalInterceptSignInput(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        if (SignInputHandler.shouldHandleSignUpdate(this.player, packet.getPos())) {
            String[] lines = packet.getLines();
            String line0 = (lines != null && lines.length > 0) ? lines[0] : "";
            if (line0 != null) {
                line0 = ChatFormatting.stripFormatting(line0);
            }
            if (line0 == null) line0 = "";
            final String sanitizedLine0 = line0;
            this.player.level().getServer().execute(() -> SignInputHandler.complete(this.player, packet.getPos(), sanitizedLine0));
            ci.cancel();
        }
    }
}
