package com.cabal.mobs.mixin;

import com.cabal.mobs.BabyCreeperHelper;
import com.cabal.mobs.CabalMobsMod;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class CreeperFinalizeSpawnMixin {

    @Inject(method = "finalizeSpawn", at = @At("TAIL"))
    private void cabal$onFinalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            EntitySpawnReason spawnReason,
            SpawnGroupData groupData,
            CallbackInfoReturnable<SpawnGroupData> cir
    ) {
        if (!((Object) this instanceof Creeper creeper)) return;
        // Allow both natural spawns and spawn-egg placement to roll baby chance.
        String reason = spawnReason.name();
        if (!"NATURAL".equals(reason) && !"SPAWN_ITEM_USE".equals(reason) && !"SPAWN_EGG".equals(reason)) return;
        if (level.getRandom().nextFloat() >= CabalMobsMod.BABY_CREEPER_CHANCE) return;

        BabyCreeperHelper.applyBabyAttributes(creeper);
        CabalMobsMod.LOGGER.debug("[CabalMobs] Spawned baby creeper at [{}, {}, {}]",
                (int) creeper.getX(), (int) creeper.getY(), (int) creeper.getZ());
    }
}
