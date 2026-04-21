package com.cabal.elytra.mixin;

import com.cabal.elytra.wing.EvokersWingCraftingHandler;
import com.cabal.elytra.wing.EvokersWingHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires Evoker's Wing craft logging when the player actually takes the result stack
 * (mouse or shift-click), matching vanilla crafting completion semantics.
 */
@Mixin(ResultSlot.class)
public abstract class EvokersWingResultSlotMixin {

    @Shadow
    @Final
    private CraftingContainer craftSlots;

    @Shadow
    @Final
    private Player player;

    @Inject(method = "checkTakeAchievements", at = @At("TAIL"))
    private void cabal$onEvokersWingCrafted(ItemStack carried, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        if (EvokersWingHelper.isEvokersWing(carried)) {
            EvokersWingCraftingHandler.onCraft(sp, carried, craftSlots);
        }
    }
}
