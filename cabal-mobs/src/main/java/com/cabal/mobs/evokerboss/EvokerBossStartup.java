package com.cabal.mobs.evokerboss;

import com.cabal.mobs.CabalMobsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.zombie.Husk;

import java.util.List;
import java.util.UUID;

/**
 * JVM-session / server boot reconciliation for the evoker boss: clearing leftover entities from disk,
 * adopting DB or world candidates, and duplicate loaded-entity purges after restarts.
 * <p>
 * Runtime paths (minute purge, {@code /boss} resync) also call {@link #purgeLoadedDuplicateBosses} and
 * {@link #syncActiveBossFromWorld}; they live here so all reconciliation is in one place, separate from
 * per-tick scheduling in {@link EvokerBossScheduler}.
 */
public final class EvokerBossStartup {

    private EvokerBossStartup() {}

    /**
     * Assigns a new session tag, reconciles DB/world state, then purges duplicate loaded bosses/proxies/vexes.
     * Invoked once from the first overworld server tick after JVM start.
     */
    public static int runJvmSessionBegin(ServerLevel overworld, long gameTime) {
        EvokerBossScheduler.currentSessionTag = EvokerBossScheduler.SESSION_TAG_PREFIX + UUID.randomUUID();
        reconcileStartupState(overworld, gameTime);
        return purgeLoadedDuplicateBosses(overworld, gameTime);
    }

    public static int syncActiveBossFromWorld(ServerLevel level, long gameTime, boolean startup) {
        List<Evoker> candidates = EvokerBossScheduler.findBossCandidates(level);
        if (candidates.isEmpty()) {
            return 0;
        }
        UUID preferredId = EvokerBossStateStore.getMostRecentActiveBossId(EvokerBossScheduler.activeLockMaxAgeMs());
        Evoker chosen = preferredId == null
                ? candidates.getFirst()
                : candidates.stream()
                        .filter(evoker -> preferredId.equals(evoker.getUUID()))
                        .findFirst()
                        .orElse(candidates.getFirst());
        EvokerBossScheduler.relinkBossToCurrentSession(level, chosen, gameTime, startup ? "startup_world_candidates" : "runtime_world_candidates");

        if (candidates.size() > 1) {
            for (Evoker extra : candidates) {
                if (extra.getUUID().equals(chosen.getUUID())) {
                    continue;
                }
                EvokerBossStateStore.markPurged(extra.getUUID(), startup ? "startup_dedupe" : "minute_dedupe");
                extra.discard();
            }
        }
        EvokerBossStateStore.markAllActivePurgedExcept(chosen.getUUID(), startup ? "startup_reconcile" : "runtime_reconcile");
        return candidates.size();
    }

    static void reconcileStartupState(ServerLevel level, long gameTime) {
        int removed = cleanupExistingBosses(level);
        if (removed > 0) {
            CabalMobsMod.LOGGER.info("[CabalMobs] Startup cleanup removed {} loaded leftover boss entities", removed);
        }

        int discovered = syncActiveBossFromWorld(level, gameTime, true);
        if (discovered > 0) {
            CabalMobsMod.LOGGER.info("[CabalMobs] Startup adopted active evoker boss from world (candidates={})", discovered);
            return;
        }

        UUID dbActiveBossId = EvokerBossStateStore.getMostRecentActiveBossId(EvokerBossScheduler.activeLockMaxAgeMs());
        if (dbActiveBossId != null) {
            Entity dbEntity = level.getEntity(dbActiveBossId);
            if (dbEntity instanceof Evoker evoker && evoker.isAlive()) {
                EvokerBossScheduler.relinkBossToCurrentSession(level, evoker, gameTime, "startup_db_entity_present");
                CabalMobsMod.LOGGER.info("[CabalMobs] Startup adopted DB-linked evoker boss (uuid={})", dbActiveBossId);
                return;
            }
            EvokerBossScheduler.pendingDbBossId = dbActiveBossId;
            EvokerBossScheduler.activeBossId = null;
            BlockPos persistedArena = EvokerBossStateStore.getLastKnownBlockPos(dbActiveBossId);
            EvokerBossScheduler.activeBossSpawnPos = persistedArena;
            EvokerBossScheduler.despawnAtGameTime = gameTime + EvokerBossConfig.DESPAWN_TICKS;
            EvokerBossScheduler.enragedActive = false;
            CabalMobsMod.LOGGER.info(
                    "[CabalMobs] Startup holding DB active boss lock for {} (entity not loaded yet — will repair when chunk loads)",
                    dbActiveBossId);
            return;
        }

        EvokerBossStateStore.markAllActivePurgedExcept(null, "startup_no_recent_active");
    }

    public static int purgeLoadedDuplicateBosses(ServerLevel level, long gameTime) {
        int purged = 0;

        List<Evoker> loadedEvokers = level.getEntitiesOfClass(
                Evoker.class,
                EvokerBossScheduler.GLOBAL_ENTITY_SEARCH_VOLUME,
                evoker -> evoker.isAlive() && evoker.getTags().contains(EvokerBossConfig.BOSS_TAG)
        );
        for (Evoker evoker : loadedEvokers) {
            boolean currentSession = EvokerBossScheduler.currentSessionTag != null
                    && evoker.getTags().contains(EvokerBossScheduler.currentSessionTag);
            if (!currentSession) {
                if ((EvokerBossScheduler.activeBossId != null && EvokerBossScheduler.activeBossId.equals(evoker.getUUID()))
                        || (EvokerBossScheduler.pendingDbBossId != null
                        && EvokerBossScheduler.pendingDbBossId.equals(evoker.getUUID()))) {
                    EvokerBossScheduler.relinkBossToCurrentSession(level, evoker, gameTime, "restart_purge_db_uuid_match");
                    continue;
                }
                EvokerBossStateStore.markPurged(evoker.getUUID(), "restart_flush_loaded");
                evoker.discard();
                purged++;
                continue;
            }

            if (EvokerBossScheduler.activeBossId == null) {
                if (EvokerBossScheduler.pendingDbBossId != null) {
                    if (EvokerBossScheduler.pendingDbBossId.equals(evoker.getUUID())) {
                        EvokerBossScheduler.relinkBossToCurrentSession(level, evoker, gameTime, "restart_flush_pending_db_match");
                    } else {
                        EvokerBossStateStore.markPurged(evoker.getUUID(), "restart_flush_duplicate_session_pending_other");
                        evoker.discard();
                        purged++;
                    }
                } else {
                    EvokerBossScheduler.relinkBossToCurrentSession(level, evoker, gameTime, "restart_flush_first_session_evoker");
                }
                continue;
            }

            if (!EvokerBossScheduler.activeBossId.equals(evoker.getUUID())) {
                EvokerBossStateStore.markPurged(evoker.getUUID(), "restart_flush_duplicate_session");
                evoker.discard();
                purged++;
            }
        }

        List<Husk> proxies = level.getEntitiesOfClass(
                Husk.class,
                EvokerBossScheduler.GLOBAL_ENTITY_SEARCH_VOLUME,
                husk -> husk.isAlive() && husk.getTags().contains(EvokerBossConfig.BOSS_PROXY_TAG)
        );
        for (Husk proxy : proxies) {
            UUID proxyId = proxy.getUUID();
            if ((EvokerBossScheduler.headProxyId != null && EvokerBossScheduler.headProxyId.equals(proxyId))
                    || (EvokerBossScheduler.torsoProxyId != null && EvokerBossScheduler.torsoProxyId.equals(proxyId))) {
                continue;
            }
            proxy.discard();
            purged++;
        }

        List<Vex> bossVexes = level.getEntitiesOfClass(
                Vex.class,
                EvokerBossScheduler.GLOBAL_ENTITY_SEARCH_VOLUME,
                vex -> vex.isAlive()
                        && vex.getTags().contains(EvokerBossConfig.BOSS_VEX_TAG)
                        && EvokerBossScheduler.currentSessionTag != null
                        && !vex.getTags().contains(EvokerBossScheduler.currentSessionTag)
        );
        for (Vex vex : bossVexes) {
            vex.discard();
            purged++;
        }

        if (EvokerBossScheduler.activeBossId == null && EvokerBossScheduler.pendingDbBossId == null) {
            EvokerBossStateStore.markAllActivePurgedExcept(null, "restart_flush_no_live_session_boss");
        } else {
            UUID keeper = EvokerBossScheduler.activeBossId != null
                    ? EvokerBossScheduler.activeBossId
                    : EvokerBossScheduler.pendingDbBossId;
            EvokerBossStateStore.markAllActivePurgedExcept(keeper, "restart_flush_reconcile");
        }
        return purged;
    }

    static int cleanupExistingBosses(ServerLevel level) {
        List<Entity> existingBosses = level.getEntitiesOfClass(
                Entity.class,
                EvokerBossScheduler.GLOBAL_ENTITY_SEARCH_VOLUME,
                entity -> entity.getTags().contains(EvokerBossConfig.BOSS_PROXY_TAG)
                        || entity.getTags().contains(EvokerBossConfig.BOSS_VEX_TAG)
        );
        for (Entity entity : existingBosses) {
            if (entity instanceof Evoker evoker) {
                EvokerBossStateStore.markPurged(evoker.getUUID(), "startup_cleanup");
            }
            entity.discard();
        }

        EvokerBossScheduler.activeBossId = null;
        EvokerBossScheduler.pendingDbBossId = null;
        EvokerBossScheduler.activeBossSpawnPos = null;
        EvokerBossScheduler.pendingScaleSyncBossId = null;
        EvokerBossScheduler.headProxyId = null;
        EvokerBossScheduler.torsoProxyId = null;
        EvokerBossScheduler.clearForcedBossChunk(level);
        if (EvokerBossScheduler.bossBar != null) {
            EvokerBossScheduler.bossBar.removeAllPlayers();
            EvokerBossScheduler.bossBar.setVisible(false);
            EvokerBossScheduler.bossBar = null;
        }
        EvokerBossScheduler.encounterActive = false;
        EvokerBossScheduler.lastEncounterActive = false;
        EvokerBossScheduler.bossBarPlayerIds.clear();
        EvokerBossScheduler.completedHealthPhases = 0;
        EvokerBossScheduler.phaseIntermissionActive = false;
        EvokerBossScheduler.phaseIntermissionEndsAt = 0L;
        EvokerBossScheduler.witherAuraActive = false;
        EvokerBossScheduler.phaseWitherAuraEscalationActive = false;
        EvokerBossScheduler.phaseWitherAuraEscalationEndsAt = 0L;
        EvokerBossScheduler.enragedActive = false;
        EvokerBossScheduler.lastWitherAuraApplyAt = 0L;
        EvokerBossScheduler.lastKnownBossHealth = -1.0f;
        return existingBosses.size();
    }
}
