package com.cabal.mobs.mixin;

import com.cabal.mobs.evokerboss.EvokerBossConfig;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.zombie.Husk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class EvokerBossDimensionsMixin {

    @Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
    private void cabal$inflateEvokerBossDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if ((Object) this instanceof Husk husk) {
            if (husk.getTags().contains(EvokerBossConfig.BOSS_PROXY_HEAD_TAG)) {
                cir.setReturnValue(EntityDimensions.scalable(
                        EvokerBossConfig.PROXY_HEAD_WIDTH,
                        EvokerBossConfig.PROXY_HEAD_HEIGHT
                ));
                return;
            }
            if (husk.getTags().contains(EvokerBossConfig.BOSS_PROXY_TORSO_TAG)) {
                cir.setReturnValue(EntityDimensions.scalable(
                        EvokerBossConfig.PROXY_TORSO_WIDTH,
                        EvokerBossConfig.PROXY_TORSO_HEIGHT
                ));
                return;
            }
        }

        if (!((Object) this instanceof Evoker evoker)) return;
        if (!evoker.getTags().contains(EvokerBossConfig.BOSS_TAG)) return;

        EntityDimensions original = cir.getReturnValue();
        cir.setReturnValue(original.scale(
                (float) EvokerBossConfig.HITBOX_WIDTH_MULTIPLIER,
                (float) EvokerBossConfig.HITBOX_HEIGHT_MULTIPLIER
        ));
    }
}
