package com.cabal.elytra.mixin;

import com.cabal.elytra.wing.EvokersWingCraftingHandler;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingMenu.class)
public abstract class CraftingResultMixin {

    @Inject(
            method = "slotChangedCraftingGrid",
            at = @At("TAIL")
    )
    private static void cabal$injectWingRecipe(
            AbstractContainerMenu menu,
            ServerLevel level,
            Player player,
            CraftingContainer craftSlots,
            ResultContainer resultSlots,
            @Nullable RecipeHolder<CraftingRecipe> recipeHint,
            CallbackInfo ci
    ) {
        ItemStack existing = resultSlots.getItem(0);
        if (!existing.isEmpty()) return;

        ItemStack wingResult = EvokersWingCraftingHandler.tryMatch(craftSlots);
        if (!wingResult.isEmpty()) {
            resultSlots.setItem(0, wingResult);
            menu.setRemoteSlot(0, wingResult);
            if (player instanceof ServerPlayer sp) {
                sp.connection.send(new ClientboundContainerSetSlotPacket(
                        menu.containerId, menu.incrementStateId(), 0, wingResult));
            }
            return;
        }
    }
}
