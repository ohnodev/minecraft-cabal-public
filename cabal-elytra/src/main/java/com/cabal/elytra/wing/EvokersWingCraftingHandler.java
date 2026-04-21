package com.cabal.elytra.wing;

import com.cabal.elytra.CabalElytraMod;
import com.cabal.elytra.ElytraBalanceConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Programmatic crafting handler for Evoker's Wing level progression.
 *
 * <p>L1: 8 Evoker Eyes + 1 Elytra. L2+: 8 Evoker Eyes + 1 Wing of previous level.
 */
public final class EvokersWingCraftingHandler {
    private EvokersWingCraftingHandler() {}

    public static ItemStack tryMatch(CraftingContainer container) {
        int eyeCount = 0;
        ItemStack elytraSlot = ItemStack.EMPTY;
        ItemStack wingSlot = ItemStack.EMPTY;
        int filledSlots = 0;

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) continue;
            filledSlots++;

            if (EvokerEyeStackMatcher.isEvokerEye(slot)) {
                eyeCount++;
            } else if (EvokersWingHelper.isEvokersWing(slot)) {
                if (!wingSlot.isEmpty()) return ItemStack.EMPTY;
                wingSlot = slot;
            } else if (slot.getItem() == Items.ELYTRA) {
                if (!elytraSlot.isEmpty()) return ItemStack.EMPTY;
                elytraSlot = slot;
            } else {
                return ItemStack.EMPTY;
            }
        }

        if (eyeCount != 8) return ItemStack.EMPTY;

        if (!wingSlot.isEmpty() && elytraSlot.isEmpty() && filledSlots == 9) {
            int currentLevel = EvokersWingHelper.getLevel(wingSlot);
            int nextLevel = currentLevel + 1;
            if (nextLevel > ElytraBalanceConfig.EVOKERS_WING_MAX_LEVEL) return ItemStack.EMPTY;
            return EvokersWingHelper.create(1, nextLevel);
        }

        if (!elytraSlot.isEmpty() && wingSlot.isEmpty() && filledSlots == 9) {
            return EvokersWingHelper.create(1, 1);
        }

        return ItemStack.EMPTY;
    }

    public static void onCraft(ServerPlayer player, ItemStack result, CraftingContainer container) {
        if (result.isEmpty()) return;
        if (!EvokersWingHelper.isEvokersWing(result)) return;
        int level = EvokersWingHelper.getLevel(result);
        CabalElytraMod.LOGGER.info("[CabalElytra] {} crafted Evoker's Wing Lv.{}", player.getGameProfile().name(), level);
    }
}
