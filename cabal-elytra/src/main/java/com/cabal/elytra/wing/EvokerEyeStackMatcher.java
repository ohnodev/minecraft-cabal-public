package com.cabal.elytra.wing;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Matches Evoker Eyes produced by {@code cabal-mobs} (echo shard + custom tag).
 * Duplicated here so {@code cabal-elytra} does not depend on {@code cabal-mobs}.
 */
public final class EvokerEyeStackMatcher {
    private static final String TAG_KEY = "cabal_evoker_eye";

    private EvokerEyeStackMatcher() {}

    public static boolean isEvokerEye(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.ECHO_SHARD) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getBooleanOr(TAG_KEY, false);
    }
}
