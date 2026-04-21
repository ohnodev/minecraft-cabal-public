package com.cabal.mobs.evokerboss;

import com.cabal.mobs.CabalMobsMod;
import com.cabal.mobs.items.EvokerEyeHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jetbrains.annotations.Nullable;

public final class EvokerBossLootHelper {
    private EvokerBossLootHelper() {}

    public static void dropBossLoot(LivingEntity evoker, @Nullable DamageSource source) {
        if (!(evoker.level() instanceof ServerLevel level)) return;
        RandomSource random = level.getRandom();
        int lootingLevel = getLootingLevel(level, source);

        int eyeCount = random.nextFloat() < EvokerBossConfig.LOOT_EVOKER_EYE_DROP_CHANCE ? 1 : 0;
        if (eyeCount > 0) {
            drop(level, evoker, EvokerEyeHelper.create(eyeCount));
        }

        int arrowCount = randomRange(random,
                EvokerBossConfig.LOOT_ARROWS_MIN,
                EvokerBossConfig.LOOT_ARROWS_MAX)
                + lootingLevel * EvokerBossConfig.LOOT_ARROWS_LOOTING_EXTRA;
        drop(level, evoker, new ItemStack(Items.ARROW, arrowCount));

        int fireworkCount = randomRange(random,
                EvokerBossConfig.LOOT_FIREWORKS_MIN,
                EvokerBossConfig.LOOT_FIREWORKS_MAX)
                + lootingLevel * EvokerBossConfig.LOOT_FIREWORKS_LOOTING_EXTRA;
        drop(level, evoker, new ItemStack(Items.FIREWORK_ROCKET, fireworkCount));

        CabalMobsMod.LOGGER.info(
                "[CabalMobs][BossLoot] Dropped loot: eyes={}, arrows={}, fireworks={} (looting={})",
                eyeCount, arrowCount, fireworkCount, lootingLevel);
    }

    private static void drop(ServerLevel level, LivingEntity evoker, ItemStack stack) {
        ItemEntity item = new ItemEntity(level,
                evoker.getX(), evoker.getY() + 0.5, evoker.getZ(),
                stack);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }

    private static int randomRange(RandomSource random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static int getLootingLevel(ServerLevel level, @Nullable DamageSource source) {
        if (source == null) return 0;
        Holder<Enchantment> lootingHolder = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.LOOTING);
        int looting = 0;
        looting = Math.max(looting, getLootingFromEntity(source.getEntity(), lootingHolder));
        looting = Math.max(looting, getLootingFromEntity(source.getDirectEntity(), lootingHolder));
        if (source.getDirectEntity() instanceof Projectile projectile) {
            looting = Math.max(looting, getLootingFromEntity(projectile.getOwner(), lootingHolder));
        }
        return looting;
    }

    private static int getLootingFromEntity(@Nullable Entity entity, Holder<Enchantment> lootingHolder) {
        if (!(entity instanceof ServerPlayer player)) return 0;
        ItemStack weapon = player.getMainHandItem();
        if (weapon.isEmpty()) return 0;
        ItemEnchantments enchantments = weapon.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        return enchantments.getLevel(lootingHolder);
    }
}
