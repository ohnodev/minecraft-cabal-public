package com.cabal.mobs.items;

import com.cabal.mobs.evokerboss.EvokerBossConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

public final class EvokerEyeHelper {
    private EvokerEyeHelper() {}

    private static final String TAG_KEY = "cabal_evoker_eye";
    private static final Item BASE_ITEM = Items.ECHO_SHARD;

    public static ItemStack create(int count) {
        ItemStack stack = new ItemStack(BASE_ITEM, count);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7d\u00a7lEvoker Eye"));
        stack.set(DataComponents.RARITY, Rarity.RARE);
        stack.set(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of((float) EvokerBossConfig.EVOKER_EYE_MODEL_ID),
                        List.of(), List.of(), List.of()));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("\u00a75A sinister eye torn from the Evoker Boss."),
                Component.literal("\u00a78Used to craft Evoker's Wing.")
        )));

        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static boolean isEvokerEye(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != BASE_ITEM) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getBooleanOr(TAG_KEY, false);
    }
}
