package com.cabal.mobs.blazeboss;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;

public final class BlazeBossHelper {
    private BlazeBossHelper() {}

    public static void applyBossAttributes(Blaze blaze) {
        AttributeInstance scale = blaze.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(BlazeBossConfig.BOSS_SCALE);
            blaze.refreshDimensions();
        }

        AttributeInstance maxHealth = blaze.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null && !blaze.getTags().contains(BlazeBossConfig.BOSS_HEALTH_SCALED_TAG)) {
            maxHealth.setBaseValue(maxHealth.getBaseValue() * BlazeBossConfig.BOSS_HEALTH_MULTIPLIER);
            blaze.setHealth((float) maxHealth.getValue());
            blaze.addTag(BlazeBossConfig.BOSS_HEALTH_SCALED_TAG);
        }

        setAttributeBase(blaze.getAttribute(Attributes.FOLLOW_RANGE), BlazeBossConfig.BOSS_FOLLOW_RANGE);
        setAttributeBase(blaze.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER), BlazeBossConfig.BOSS_FALL_DAMAGE_MULTIPLIER);
        setAttributeBase(blaze.getAttribute(Attributes.SAFE_FALL_DISTANCE), BlazeBossConfig.BOSS_SAFE_FALL_DISTANCE);

        blaze.setPersistenceRequired();
    }

    private static void setAttributeBase(AttributeInstance attribute, double value) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(value);
    }
}
