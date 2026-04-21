package com.cabal.claim.mixin;

import com.cabal.claim.CabalClaimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.server.dedicated.DedicatedServer;

@Mixin(DedicatedServer.class)
public class SpawnProtectionToggleMixin {
    @Inject(method = "isUnderSpawnProtection", at = @At("HEAD"), cancellable = true)
    private void cabalBypassSpawnProtectionWhenDisabled(ServerLevel level, BlockPos pos, Player player, CallbackInfoReturnable<Boolean> cir) {
        var manager = CabalClaimMod.getSpawnProtectionToggleManager();
        if (manager != null && manager.isDisabled()) {
            cir.setReturnValue(false);
        }
    }
}
