package com.cabal.mobs.mixin;

import com.cabal.mobs.evokerboss.EvokerBossConfig;
import com.cabal.mobs.evokerboss.EvokerBossVexTuning;
import net.minecraft.world.entity.monster.illager.Evoker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.lang.reflect.Field;

@Mixin(targets = "net.minecraft.world.entity.monster.illager.Evoker$EvokerSummonSpellGoal")
public abstract class EvokerBossSummonSpellMixin {
    private static volatile Field cachedOwnerField;
    private static volatile boolean ownerFieldResolved;

    @Inject(method = "getCastingInterval", at = @At("RETURN"), cancellable = true)
    private void cabal$bossSummonInterval(CallbackInfoReturnable<Integer> cir) {
        Evoker evoker = cabal$resolveOwnerEvoker();
        if (evoker != null && evoker.getTags().contains(EvokerBossConfig.BOSS_TAG)) {
            cir.setReturnValue(EvokerBossConfig.BOSS_VEX_SUMMON_INTERVAL_TICKS);
        }
    }

    @Inject(method = "performSpellCasting", at = @At("TAIL"))
    private void cabal$bossExtraVex(CallbackInfo ci) {
        Evoker evoker = cabal$resolveOwnerEvoker();
        if (evoker != null) {
            EvokerBossVexTuning.spawnExtraBossVexIfConfigured(evoker);
        }
    }

    private Evoker cabal$resolveOwnerEvoker() {
        Field ownerField = cachedOwnerField;
        if (!ownerFieldResolved) {
            synchronized (EvokerBossSummonSpellMixin.class) {
                if (!ownerFieldResolved) {
                    Class<?> cursor = this.getClass();
                    while (cursor != null && ownerField == null) {
                        for (Field field : cursor.getDeclaredFields()) {
                            if (!Evoker.class.isAssignableFrom(field.getType())) {
                                continue;
                            }
                            field.setAccessible(true);
                            ownerField = field;
                            break;
                        }
                        cursor = cursor.getSuperclass();
                    }
                    cachedOwnerField = ownerField;
                    ownerFieldResolved = true;
                }
            }
        }
        ownerField = cachedOwnerField;

        if (ownerField == null) {
            throw new IllegalStateException("Evoker owner field missing for EvokerSummonSpellGoal mixin");
        }

        try {
            Object owner = ownerField.get(this);
            if (owner == null) {
                return null;
            }
            if (owner instanceof Evoker evoker) {
                return evoker;
            }
            throw new IllegalStateException("Resolved owner field is not an Evoker instance");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to access Evoker owner field", e);
        }
    }
}
