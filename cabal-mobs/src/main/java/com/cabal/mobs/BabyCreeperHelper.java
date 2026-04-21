package com.cabal.mobs;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;

public final class BabyCreeperHelper {

    static final Identifier SPEED_MODIFIER_ID =
            Identifier.fromNamespaceAndPath("cabal_mobs", "baby_creeper_speed");

    private BabyCreeperHelper() {}

    /**
     * Applies baby creeper attributes: half scale, double movement speed.
     * Safe to call multiple times — uses hasModifier guard for speed.
     */
    public static void applyBabyAttributes(Creeper creeper) {
        AttributeInstance scale = creeper.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(CabalMobsMod.BABY_CREEPER_SCALE);
        }

        AttributeInstance speed = creeper.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && !speed.hasModifier(SPEED_MODIFIER_ID)) {
            speed.addPermanentModifier(new AttributeModifier(
                    SPEED_MODIFIER_ID,
                    CabalMobsMod.BABY_CREEPER_SPEED_MULTIPLIER,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE
            ));
        }
    }

    public static boolean isBabyCreeper(Creeper creeper) {
        AttributeInstance scale = creeper.getAttribute(Attributes.SCALE);
        return scale != null && scale.getBaseValue() < 1.0;
    }
}
