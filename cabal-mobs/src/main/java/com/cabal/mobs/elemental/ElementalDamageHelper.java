package com.cabal.mobs.elemental;

import com.cabal.mobs.CabalMobsMod;
import com.cabal.mobs.evokerboss.EvokerBossConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Computes elemental bonus damage for the evoker boss based on arrow metadata.
 * Each elemental arrow deals physical damage (vanilla) plus an equal amount of
 * elemental damage scaled by the boss's elemental multiplier.
 */
public final class ElementalDamageHelper {
    private ElementalDamageHelper() {}

    /**
     * Returns additional elemental damage to add on top of {@code physicalDamageTaken}.
     * The value is computed as {@code physicalDamageTaken * multiplier}.
     * @param source    the damage source from AFTER_DAMAGE
     * @param physicalDamageTaken  the physical damage already dealt
     * @return additional elemental damage: positive for extra damage, zero when no elemental
     * effect applies, and negative only if an elemental multiplier is explicitly negative
     */
    public static float computeEvokerElementalBonus(DamageSource source, float physicalDamageTaken) {
        ElementType element = extractElementFromSource(source);
        if (element == null) return 0.0f;

        float multiplier = switch (element) {
            case FIRE -> EvokerBossConfig.ELEMENTAL_FIRE_MULTIPLIER;
            case LIGHTNING -> EvokerBossConfig.ELEMENTAL_LIGHTNING_MULTIPLIER;
        };

        float elementalDamage = physicalDamageTaken * multiplier;

        if (EvokerBossConfig.DEBUG_ELEMENTAL_DAMAGE) {
            CabalMobsMod.LOGGER.info(
                    "[CabalMobs][Elemental] element={} physical={} multiplier={} elemental_bonus={}",
                    element.id(),
                    String.format("%.2f", physicalDamageTaken),
                    String.format("%.2f", multiplier),
                    String.format("%.2f", elementalDamage)
            );
        }

        return elementalDamage;
    }

    public static @Nullable ElementType extractElementFromSource(DamageSource source) {
        if (source.getDirectEntity() instanceof AbstractArrow arrow) {
            ItemStack pickupItem = arrow.getPickupItemStackOrigin();
            return ElementalArrowHelper.getArrowElement(pickupItem);
        }
        return null;
    }
}
