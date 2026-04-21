package com.cabal.elytra.mixin;

import com.cabal.elytra.wing.EvokersWingHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class EvokersWingGlideMixin {

    @Unique
    private static final Identifier WING_SPEED_ID =
            Identifier.fromNamespaceAndPath("cabal_elytra", "evokers_wing_speed");

    @Unique
    private int cabal$lastAppliedWingLevel = 0;

    @Inject(
            method = "hurtServer",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cabal$evokersWingKineticImmunity(
            ServerLevel level,
            DamageSource source,
            float damage,
            CallbackInfoReturnable<Boolean> cir
    ) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!source.is(DamageTypes.FLY_INTO_WALL)) return;
        if (!self.isFallFlying()) return;
        ItemStack chestItem = self.getItemBySlot(EquipmentSlot.CHEST);
        if (EvokersWingHelper.isEvokersWing(chestItem)) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private void cabal$clearWingSpeed(LivingEntity self) {
        AttributeInstance speed = self.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(WING_SPEED_ID);
        }
        cabal$lastAppliedWingLevel = 0;
    }

    @Inject(
            method = "tick",
            at = @At("TAIL")
    )
    private void cabal$evokersWingSpeedBonus(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;

        if (!self.isFallFlying()) {
            cabal$clearWingSpeed(self);
            return;
        }

        ItemStack chestItem = self.getItemBySlot(EquipmentSlot.CHEST);
        if (!EvokersWingHelper.isEvokersWing(chestItem)) {
            cabal$clearWingSpeed(self);
            return;
        }

        int wingLevel = EvokersWingHelper.getLevel(chestItem);

        if (wingLevel == cabal$lastAppliedWingLevel) return;
        cabal$lastAppliedWingLevel = wingLevel;

        AttributeInstance speed = self.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;

        speed.removeModifier(WING_SPEED_ID);

        if (wingLevel > 0) {
            double bonus = EvokersWingHelper.getSpeedMultiplier(wingLevel);
            speed.addTransientModifier(new AttributeModifier(
                    WING_SPEED_ID, bonus,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
            ));
        }
    }
}
