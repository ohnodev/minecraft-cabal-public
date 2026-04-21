package com.cabal.mobs.evokerboss;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.scores.PlayerTeam;

public final class EvokerBossVexTuning {
    private static final String SPEED_TUNED_KEY = "cabal_boss_vex_speed_tuned";

    private EvokerBossVexTuning() {}

    public static void tuneVexOwnedByBossEvoker(Vex vex, Evoker owner) {
        if (!owner.getTags().contains(EvokerBossConfig.BOSS_TAG)) {
            return;
        }
        vex.addTag(EvokerBossConfig.BOSS_VEX_TAG);
        CustomData existing = vex.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = existing != null ? existing.copyTag() : new CompoundTag();
        if (tag.getBooleanOr(SPEED_TUNED_KEY, false)) {
            return;
        }
        tag.putBoolean(SPEED_TUNED_KEY, true);
        vex.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        var movement = vex.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement != null) {
            movement.setBaseValue(movement.getBaseValue() * EvokerBossConfig.BOSS_VEX_SPEED_MULTIPLIER);
        }
    }

    /**
     * One additional vex per summon cast for the tagged boss only (vanilla casts three).
     */
    public static void spawnExtraBossVexIfConfigured(Evoker evoker) {
        if (!evoker.getTags().contains(EvokerBossConfig.BOSS_TAG)) {
            return;
        }
        if (!EvokerBossConfig.BOSS_VEX_SPAWN_ONE_EXTRA_PER_CAST) {
            return;
        }
        if (!(evoker.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        PlayerTeam evokerTeam = evoker.getTeam();
        BlockPos pos = evoker.blockPosition().offset(-2 + evoker.getRandom().nextInt(5), 1, -2 + evoker.getRandom().nextInt(5));
        Vex vex = EntityType.VEX.create(evoker.level(), EntitySpawnReason.MOB_SUMMONED);
        if (vex == null) {
            return;
        }
        vex.snapTo(pos, 0.0f, 0.0f);
        vex.finalizeSpawn(serverLevel, serverLevel.getCurrentDifficultyAt(pos), EntitySpawnReason.MOB_SUMMONED, null);
        vex.setOwner(evoker);
        vex.setBoundOrigin(pos);
        vex.setLimitedLife(20 * (30 + evoker.getRandom().nextInt(90)));
        if (evokerTeam != null) {
            serverLevel.getScoreboard().addPlayerToTeam(vex.getScoreboardName(), evokerTeam);
        }
        serverLevel.addFreshEntityWithPassengers(vex);
        serverLevel.gameEvent(GameEvent.ENTITY_PLACE, pos, GameEvent.Context.of(evoker));
        tuneVexOwnedByBossEvoker(vex, evoker);
    }
}
