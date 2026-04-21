package com.cabal.mobs.mixin;

import com.cabal.mobs.CabalMobsMod;
import com.cabal.mobs.evokerboss.EvokerBossConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mixin(AbstractArrow.class)
public abstract class EvokerBossArrowCollisionMixin {

    @Inject(method = "findHitEntities", at = @At("RETURN"))
    private void cabal$traceEvokerBossCandidates(
            Vec3 from,
            Vec3 to,
            CallbackInfoReturnable<Collection<EntityHitResult>> cir
    ) {
        if (!EvokerBossConfig.DEBUG_HIT_TRACE) return;
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (arrow.level().isClientSide()) return;
        if (EvokerBossConfig.HIT_TRACE_LOG_EVERY_TICKS <= 0) return;
        if (arrow.level().getGameTime() % EvokerBossConfig.HIT_TRACE_LOG_EVERY_TICKS != 0L) return;

        Collection<EntityHitResult> hits = cir.getReturnValue();
        Set<Integer> hitEntityIds = new HashSet<>();
        for (EntityHitResult hit : hits) {
            hitEntityIds.add(hit.getEntity().getId());
        }

        AABB traceBox = arrow.getBoundingBox().expandTowards(arrow.getDeltaMovement()).inflate(2.0);
        List<Evoker> bossCandidates = arrow.level().getEntitiesOfClass(
                Evoker.class,
                traceBox,
                evoker -> evoker.isAlive() && evoker.getTags().contains(EvokerBossConfig.BOSS_TAG)
        );
        if (bossCandidates.isEmpty()) return;

        for (Evoker evoker : bossCandidates) {
            boolean selected = hitEntityIds.contains(evoker.getId());
            CabalMobsMod.LOGGER.info(
                    "[CabalMobs][ArrowPathTrace] selected={} arrow_pos=({}, {}, {}) arrow_vel=({}, {}, {}) from=({}, {}, {}) to=({}, {}, {}) trace_bb={} boss_pos=({}, {}, {}) boss_bb=({}, {})",
                    selected,
                    String.format("%.2f", arrow.getX()),
                    String.format("%.2f", arrow.getY()),
                    String.format("%.2f", arrow.getZ()),
                    String.format("%.2f", arrow.getDeltaMovement().x),
                    String.format("%.2f", arrow.getDeltaMovement().y),
                    String.format("%.2f", arrow.getDeltaMovement().z),
                    String.format("%.2f", from.x),
                    String.format("%.2f", from.y),
                    String.format("%.2f", from.z),
                    String.format("%.2f", to.x),
                    String.format("%.2f", to.y),
                    String.format("%.2f", to.z),
                    traceBox,
                    String.format("%.2f", evoker.getX()),
                    String.format("%.2f", evoker.getY()),
                    String.format("%.2f", evoker.getZ()),
                    String.format("%.2f", evoker.getBbWidth()),
                    String.format("%.2f", evoker.getBbHeight())
            );
        }
    }

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void cabal$traceArrowEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        if (!EvokerBossConfig.DEBUG_HIT_TRACE) return;
        Entity target = hitResult.getEntity();
        if (!(target instanceof Evoker evoker)) return;
        if (!evoker.getTags().contains(EvokerBossConfig.BOSS_TAG)) return;

        AbstractArrow arrow = (AbstractArrow) (Object) this;
        CabalMobsMod.LOGGER.info(
                "[CabalMobs][ArrowHitPath] arrow_id={} target_id={} arrow_pos=({}, {}, {}) target_pos=({}, {}, {}) target_bb=({}, {})",
                arrow.getId(),
                evoker.getId(),
                String.format("%.2f", arrow.getX()),
                String.format("%.2f", arrow.getY()),
                String.format("%.2f", arrow.getZ()),
                String.format("%.2f", evoker.getX()),
                String.format("%.2f", evoker.getY()),
                String.format("%.2f", evoker.getZ()),
                String.format("%.2f", evoker.getBbWidth()),
                String.format("%.2f", evoker.getBbHeight())
        );
    }
}
