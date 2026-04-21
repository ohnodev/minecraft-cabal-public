package com.cabal.elytra.wing;

import com.cabal.elytra.ElytraBalanceConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

public final class EvokersWingHelper {
    private EvokersWingHelper() {}

    public static final String TAG_KEY = "cabal_evokers_wing";
    public static final String TAG_LEVEL = "cabal_evokers_wing_level";

    public static ItemStack create(int count, int level) {
        level = Math.clamp(level, 1, ElytraBalanceConfig.EVOKERS_WING_MAX_LEVEL);
        int speedPercent = (int) Math.round(level * ElytraBalanceConfig.EVOKERS_WING_SPEED_PER_LEVEL * 100.0);

        ItemStack stack = new ItemStack(Items.ELYTRA, count);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("\u00a76\u00a7lEvoker's Wing Lv." + level));
        stack.set(DataComponents.RARITY, Rarity.EPIC);
        stack.set(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of((float) ElytraBalanceConfig.EVOKERS_WING_MODEL_ID),
                        List.of(), List.of(), List.of()));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("\u00a7eAn upgraded elytra forged from the Evoker Boss."),
                Component.literal("\u00a7aImmune to fly-into-wall damage while gliding."),
                Component.literal("\u00a7b+" + speedPercent + "% glide speed bonus.")
        )));

        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_KEY, true);
        tag.putInt(TAG_LEVEL, level);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static ItemStack create(int count) {
        return create(count, 1);
    }

    private static CompoundTag evokersWingTagOrNull(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != Items.ELYTRA) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.getBooleanOr(TAG_KEY, false)) {
            return null;
        }
        return tag;
    }

    public static boolean isEvokersWing(ItemStack stack) {
        return evokersWingTagOrNull(stack) != null;
    }

    public static int getLevel(ItemStack stack) {
        CompoundTag tag = evokersWingTagOrNull(stack);
        if (tag == null) {
            return 0;
        }
        int level = tag.getIntOr(TAG_LEVEL, 1);
        return Math.clamp(level, 1, ElytraBalanceConfig.EVOKERS_WING_MAX_LEVEL);
    }

    public static double getSpeedMultiplier(int level) {
        if (level <= 0) return 0.0;
        level = Math.min(level, ElytraBalanceConfig.EVOKERS_WING_MAX_LEVEL);
        return level * ElytraBalanceConfig.EVOKERS_WING_SPEED_PER_LEVEL;
    }
}
