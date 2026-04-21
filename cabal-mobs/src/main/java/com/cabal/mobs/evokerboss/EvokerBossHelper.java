package com.cabal.mobs.evokerboss;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Evoker;

public final class EvokerBossHelper {
    private static final double VANILLA_EVOKER_BASE_HEALTH = 24.0;

    private EvokerBossHelper() {}

    public static void applyBossAttributes(Evoker evoker) {
        AttributeInstance scale = evoker.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(EvokerBossConfig.BOSS_SCALE);
            evoker.refreshDimensions();
        }

        AttributeInstance maxHealth = evoker.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            double oldBase = maxHealth.getBaseValue();
            double sourceBase = oldBase > 0.0 ? oldBase : VANILLA_EVOKER_BASE_HEALTH;
            double targetHealth = VANILLA_EVOKER_BASE_HEALTH * EvokerBossConfig.BOSS_HEALTH_MULTIPLIER;
            maxHealth.setBaseValue(targetHealth);
            float scaledHealth = sourceBase > 0.0
                    ? (float) Math.min(targetHealth, (evoker.getHealth() / (float) sourceBase) * targetHealth)
                    : (float) targetHealth;
            evoker.setHealth(Math.max(1.0f, scaledHealth));
        }

        AttributeInstance followRange = evoker.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(EvokerBossConfig.BOSS_FOLLOW_RANGE);
        }

        AttributeInstance fallDamageMultiplier = evoker.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER);
        if (fallDamageMultiplier != null) {
            fallDamageMultiplier.setBaseValue(EvokerBossConfig.BOSS_FALL_DAMAGE_MULTIPLIER);
        }

        AttributeInstance safeFallDistance = evoker.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (safeFallDistance != null) {
            safeFallDistance.setBaseValue(EvokerBossConfig.BOSS_SAFE_FALL_DISTANCE);
        }

        AttributeInstance stepHeight = evoker.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(EvokerBossConfig.BOSS_STEP_HEIGHT);
        }

        evoker.setPersistenceRequired();
    }
}
