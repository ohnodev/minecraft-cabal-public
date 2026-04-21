package com.cabal.mobs.elemental;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Server-item helpers for elemental arrows.
 * Follows the same CustomData-tag pattern used by LandTicketHelper in cabal-claim.
 */
public final class ElementalArrowHelper {
    private ElementalArrowHelper() {}

    private static final String TAG_KEY = "cabal_elemental_arrow";
    private static final String TAG_ELEMENT = "element";

    private static final int FIRE_ARROW_MODEL_ID = 920001;
    private static final int LIGHTNING_ARROW_MODEL_ID = 920002;

    public static ItemStack createElementalArrow(ElementType element, int count) {
        ItemStack stack = new ItemStack(Items.ARROW, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(element.displayName()));
        stack.set(DataComponents.RARITY, Rarity.UNCOMMON);
        int modelId = switch (element) {
            case FIRE -> FIRE_ARROW_MODEL_ID;
            case LIGHTNING -> LIGHTNING_ARROW_MODEL_ID;
        };
        stack.set(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of((float) modelId), List.of(), List.of(), List.of()));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(element.loreText())
        )));

        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_KEY, true);
        tag.putString(TAG_ELEMENT, element.id());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static boolean isElementalArrow(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.ARROW) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.copyTag();
        return tag.getBooleanOr(TAG_KEY, false);
    }

    public static @Nullable ElementType getArrowElement(ItemStack stack) {
        if (!isElementalArrow(stack)) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String elementId = data.copyTag().getStringOr(TAG_ELEMENT, "");
        return ElementType.fromId(elementId);
    }
}
