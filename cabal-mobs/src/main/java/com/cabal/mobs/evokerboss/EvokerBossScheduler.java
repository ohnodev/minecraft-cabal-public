package com.cabal.mobs.evokerboss;

import com.cabal.mobs.CabalMobsMod;
import com.cabal.mobs.elemental.ElementalDamageHelper;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

public final class EvokerBossScheduler {
    record SpawnedEntity<T extends Entity>(T entity, EntitySpawnReason spawnReason) {}
    private static final EntitySpawnReason[] PREFERRED_SPAWN_REASONS = {
            EntitySpawnReason.EVENT,
            EntitySpawnReason.COMMAND,
            EntitySpawnReason.MOB_SUMMONED,
            EntitySpawnReason.NATURAL
    };

    private static final float CLEANUP_SCALE_THRESHOLD = 1.5f;
    /** Package-private for {@link EvokerBossStartup} duplicate purge / cleanup scans. */
    static final AABB GLOBAL_ENTITY_SEARCH_VOLUME =
            new AABB(-3.0E7, -2048.0, -3.0E7, 3.0E7, 2048.0, 3.0E7);
    static final long DB_ACTIVE_LOCK_GRACE_MS = 120_000L;
    static final String SESSION_TAG_PREFIX = "cabal_evoker_session:";

    /**
     * Wall-clock epoch second when we last consumed an automatic spawn interval ({@link EvokerBossConfig#AUTOMATIC_SPAWN_INTERVAL_SECONDS}).
     * Updated when an automatic spawn attempt begins (after gated checks and spawn position selection), even if the
     * attempt later fails.
     */
    private static long lastAutomaticSpawnCadenceEpochSecond = Long.MIN_VALUE / 4;
    /** Package-private: reconciled on startup by {@link EvokerBossStartup}. */
    static UUID activeBossId;
    /**
     * DB-reported active boss UUID while the evoker is not yet loaded in the overworld; {@link #activeBossId} stays
     * null so despawn timeout does not early-return on {@code getEntity(null)} and block the automatic spawn path.
     */
    static UUID pendingDbBossId;
    static long despawnAtGameTime;
    static BlockPos activeBossSpawnPos;
    static UUID pendingScaleSyncBossId;
    private static long pendingScaleSyncAtGameTime;
    static ServerBossEvent bossBar;
    static boolean encounterActive;
    static boolean lastEncounterActive;
    private static boolean startupCleanupDone;
    /** One extra global stray-proxy discard after chunks have had time to load post-restart. */
    private static long strayProxyExtraSweepAtGameTime = Long.MIN_VALUE;
    static UUID headProxyId;
    static UUID torsoProxyId;
    private static int traceWindowAttempts;
    private static int traceWindowBlocked;
    private static int traceWindowDamaging;
    private static long traceWindowStartTick;
    static float lastKnownBossHealth = -1.0f;
    static String currentSessionTag;
    private static Integer forcedBossChunkX;
    private static Integer forcedBossChunkZ;
    /** Number of 10% HP thresholds already processed for this active boss. */
    static int completedHealthPhases;
    /** Boss phase intermission: AI disabled while true. */
    static boolean phaseIntermissionActive;
    static long phaseIntermissionEndsAt;
    /** Wither aura toggles on after first phase transition. */
    static boolean witherAuraActive;
    /** Temporary post-intermission phase escalation for wither aura radius. */
    static boolean phaseWitherAuraEscalationActive;
    static long phaseWitherAuraEscalationEndsAt;
    /** Enrage is entered once boss health drops below the threshold. */
    static boolean enragedActive;
    static long lastWitherAuraApplyAt;
    /** Last game time we applied contact-burn melee damage per player (UUID). */
    private static final Map<UUID, Long> contactBurnLastDamageGameTime = new HashMap<>();
    static final Set<UUID> bossBarPlayerIds = new HashSet<>();
    /** Recursion guard for elemental bonus damage applied via hurtServer inside AFTER_DAMAGE. */
    private static boolean applyingElementalDamage;
    /** Periodic lightweight MSPT tracker (one timestamp sample per tick). */
    private static long tickPressureLastSampleNanos;
    private static long tickPressureWindowStartGameTime;
    private static double tickPressureAccumulatedMspt;
    private static double tickPressureMaxMspt;
    private static int tickPressureSampleCount;

    private EvokerBossScheduler() {}

    public static void register() {
        EvokerBossCommands.register();
        EvokerBossStateStore.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerLevel ow = server.overworld();
            if (ow != null) {
                EvokerBossSpawnPoints.reload(ow);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EvokerBossStateStore.close());

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof Vex vex && vex.getOwner() instanceof Evoker owner) {
                EvokerBossVexTuning.tuneVexOwnedByBossEvoker(vex, owner);
            }
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (entity instanceof Husk proxyMob && isBossProxy(proxyMob)) {
                if (activeBossId == null) return;
                if (!(proxyMob.level() instanceof ServerLevel level)) return;
                Entity bossEntity = level.getEntity(activeBossId);
                if (!(bossEntity instanceof Evoker evoker) || !evoker.isAlive()) return;

                // Forward proxy hits into the real boss health pool.
                if (damageTaken > 0.0f) {
                    evoker.invulnerableTime = 0;
                    evoker.hurtServer(level, source, damageTaken);
                }
                replenishProxy(proxyMob);
                if (source.getEntity() instanceof ServerPlayer) {
                    encounterActive = true;
                    if (bossBar != null) bossBar.setVisible(true);
                }
                return;
            }

            if (!(entity instanceof Evoker evoker) || activeBossId == null) return;
            if (!activeBossId.equals(evoker.getUUID())) return;

            if (EvokerBossConfig.DEBUG_HIT_TRACE) {
                traceWindowAttempts++;
                if (blocked) traceWindowBlocked++;
                if (damageTaken > 0.0f) traceWindowDamaging++;
                logHitTrace(evoker, source, baseDamage, damageTaken, blocked);
            }

            if (!applyingElementalDamage && damageTaken > 0.0f && evoker.isAlive()) {
                float elementalBonus = ElementalDamageHelper.computeEvokerElementalBonus(source, damageTaken);
                if (elementalBonus > 0.0f && evoker.level() instanceof ServerLevel lvl) {
                    applyingElementalDamage = true;
                    try {
                        evoker.invulnerableTime = 0;
                        evoker.hurtServer(lvl, source, elementalBonus);
                    } finally {
                        applyingElementalDamage = false;
                    }
                }
            }

            if (!(source.getEntity() instanceof ServerPlayer)) return;
            encounterActive = true;
            if (bossBar != null) bossBar.setVisible(true);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof Evoker evoker) || activeBossId == null) return;
            if (!activeBossId.equals(evoker.getUUID())) return;
            evoker.removeTag(EvokerBossConfig.BOSS_ATTRIBUTES_APPLIED_TAG);
            ServerPlayer killer = source.getEntity() instanceof ServerPlayer player ? player : null;
            EvokerBossLootHelper.dropBossLoot(evoker, source);
            EvokerBossStateStore.markDefeated(evoker.getUUID(), killer);
            if (evoker.level() instanceof ServerLevel level) {
                clearActiveBossState(level);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerLevel overworld = server.overworld();
            if (overworld == null) return;

            long gameTime = overworld.getGameTime();
            sampleTickPressure(overworld, gameTime);
            if (traceWindowStartTick == 0L) {
                traceWindowStartTick = gameTime;
            }

            if (!startupCleanupDone) {
                startupCleanupDone = true;
                int purgedAtStartup = EvokerBossStartup.runJvmSessionBegin(overworld, gameTime);
                CabalMobsMod.LOGGER.info("[CabalMobs] Restart purge initialized (session={}) purged_loaded={}", currentSessionTag, purgedAtStartup);
                strayProxyExtraSweepAtGameTime = gameTime + 100L;
            }

            if (strayProxyExtraSweepAtGameTime != Long.MIN_VALUE && gameTime >= strayProxyExtraSweepAtGameTime) {
                strayProxyExtraSweepAtGameTime = Long.MIN_VALUE;
                discardStrayBossProxyHusks(overworld.getServer());
            }

            if (pendingScaleSyncBossId != null && gameTime >= pendingScaleSyncAtGameTime) {
                var pending = overworld.getEntity(pendingScaleSyncBossId);
                if (pending instanceof Evoker evoker && pending.isAlive()) {
                    ensureBossAttributesApplied(evoker);
                }
                pendingScaleSyncBossId = null;
            }

            if (pendingDbBossId != null) {
                Entity pendingLoaded = overworld.getEntity(pendingDbBossId);
                if (pendingLoaded instanceof Evoker evoker && evoker.isAlive()) {
                    relinkBossToCurrentSession(overworld, evoker, gameTime, "pending_db_chunk_loaded");
                }
            }

            if (despawnAtGameTime > 0L && gameTime >= despawnAtGameTime && (activeBossId != null || pendingDbBossId != null)) {
                UUID timedOutBossId = activeBossId != null ? activeBossId : pendingDbBossId;
                if (activeBossId != null) {
                    var entity = overworld.getEntity(activeBossId);
                    if (entity != null && entity.isAlive()) {
                        BlockPos pos = activeBossSpawnPos != null ? activeBossSpawnPos : entity.blockPosition();
                        if (entity instanceof Evoker evoker) {
                            evoker.removeTag(EvokerBossConfig.BOSS_ATTRIBUTES_APPLIED_TAG);
                        }
                        entity.discard();
                        CabalMobsMod.LOGGER.info("[CabalMobs] Evoker boss despawned ({}s elapsed) at [{}, {}, {}]",
                                EvokerBossConfig.DESPAWN_TICKS / 20, pos.getX(), pos.getY(), pos.getZ());
                    }
                }
                if (timedOutBossId != null) {
                    EvokerBossStateStore.markDespawned(timedOutBossId, "timeout");
                }
                clearActiveBossState(overworld);
            }

            if (activeBossId != null) {
                var activeEntity = overworld.getEntity(activeBossId);
                if (activeEntity == null) {
                    // Active boss may be in unloaded chunk; keep lock.
                } else if (!activeEntity.isAlive()) {
                    if (activeBossId != null) {
                        EvokerBossStateStore.markDespawned(activeBossId, "removed");
                    }
                    clearActiveBossState(overworld);
                } else if (activeEntity instanceof Evoker evoker) {
                    repairBossRuntimeIfNeeded(overworld, evoker);
                    forceBossChunk(overworld, evoker.blockPosition());
                    tickEvokerBossPhases(overworld, evoker, gameTime);

                    if (EvokerBossConfig.ROOTED_DIAGNOSTIC) {
                        // Diagnostic mode: freeze boss movement to isolate hitbox vs desync.
                        evoker.setNoGravity(true);
                        evoker.getNavigation().stop();
                        evoker.setDeltaMovement(Vec3.ZERO);
                    } else {
                        evoker.setNoGravity(false);
                    }
                    if (EvokerBossConfig.DEBUG_HIT_TRACE) {
                        if (lastKnownBossHealth < 0.0f) {
                            lastKnownBossHealth = evoker.getHealth();
                        } else if (evoker.getHealth() < lastKnownBossHealth - 0.001f) {
                            CabalMobsMod.LOGGER.info(
                                    "[CabalMobs][HitTraceHealth] health_drop={} old={} new={} bb=({}, {}) pos=({}, {}, {})",
                                    String.format("%.2f", lastKnownBossHealth - evoker.getHealth()),
                                    String.format("%.2f", lastKnownBossHealth),
                                    String.format("%.2f", evoker.getHealth()),
                                    String.format("%.2f", evoker.getBbWidth()),
                                    String.format("%.2f", evoker.getBbHeight()),
                                    String.format("%.2f", evoker.getX()),
                                    String.format("%.2f", evoker.getY()),
                                    String.format("%.2f", evoker.getZ())
                            );
                            lastKnownBossHealth = evoker.getHealth();
                        } else {
                            lastKnownBossHealth = evoker.getHealth();
                        }
                        if (gameTime % 20L == 0L) {
                            logProjectileDiagnostics(overworld, evoker);
                        }
                    }
                    followBossProxyPositions(overworld, evoker);
                    if (gameTime % EvokerBossConfig.PROXY_REPLENISH_EVERY_TICKS == 0L) {
                        replenishBossProxies(overworld);
                    }
                    if (!phaseIntermissionActive) {
                        applyEvokerBossContactBurn(overworld, evoker, gameTime);
                    }
                    double auraRadius = resolveCurrentWitherAuraRadius();
                    if (auraRadius > 0.0) {
                        applyEvokerWitherAura(overworld, evoker, gameTime, auraRadius);
                    }
                    EvokerBossStateStore.updateActivePosition(evoker, false);
                    if (EvokerBossConfig.BOSS_INFRA_LOG_INTERVAL_TICKS > 0
                            && gameTime % EvokerBossConfig.BOSS_INFRA_LOG_INTERVAL_TICKS == 0L) {
                        logBossInfraSnapshot(overworld, evoker, gameTime);
                    }
                    if (EvokerBossConfig.DEBUG_HITBOX_PARTICLES
                            && gameTime % EvokerBossConfig.DEBUG_HITBOX_PARTICLE_EVERY_TICKS == 0L) {
                        renderHitboxDiagnostics(overworld, evoker);
                    }
                    if (EvokerBossConfig.DEBUG_HIT_TRACE
                            && gameTime - traceWindowStartTick >= EvokerBossConfig.HIT_TRACE_LOG_EVERY_TICKS
                            && traceWindowAttempts > 0) {
                        float hitRate = (traceWindowDamaging * 100.0f) / traceWindowAttempts;
                        float blockedRate = (traceWindowBlocked * 100.0f) / traceWindowAttempts;
                        CabalMobsMod.LOGGER.info(
                                "[CabalMobs][HitTraceSummary] attempts={} damaging={} blocked={} hit_rate={} blocked_rate={} bb_w={} bb_h={} scale={} health={}/{}",
                                traceWindowAttempts,
                                traceWindowDamaging,
                                traceWindowBlocked,
                                String.format("%.1f%%", hitRate),
                                String.format("%.1f%%", blockedRate),
                                String.format("%.2f", evoker.getBbWidth()),
                                String.format("%.2f", evoker.getBbHeight()),
                                String.format("%.2f", evoker.getScale()),
                                String.format("%.1f", evoker.getHealth()),
                                String.format("%.1f", evoker.getMaxHealth())
                        );
                        traceWindowAttempts = 0;
                        traceWindowBlocked = 0;
                        traceWindowDamaging = 0;
                        traceWindowStartTick = gameTime;
                        lastKnownBossHealth = -1.0f;
                    }
                    if (bossBar != null) {
                        updateBossBarVisuals();
                        float progress = Mth.clamp(evoker.getHealth() / evoker.getMaxHealth(), 0.0f, 1.0f);
                        bossBar.setProgress(progress);
                        if (encounterActive != lastEncounterActive || (encounterActive && gameTime % 20L == 0L)) {
                            syncBossBarPlayers(overworld);
                            lastEncounterActive = encounterActive;
                        }
                    }
                }
            }

            if (server.getTickCount() % 20 != 0) return;
            long epochSecond = Instant.now().getEpochSecond();
            long interval = EvokerBossConfig.AUTOMATIC_SPAWN_INTERVAL_SECONDS;
            if (epochSecond - lastAutomaticSpawnCadenceEpochSecond < interval) {
                return;
            }
            if (activeBossId != null || pendingDbBossId != null) return;

            int purgedBeforeSpawn = EvokerBossStartup.purgeLoadedDuplicateBosses(overworld, gameTime);
            if (activeBossId != null || pendingDbBossId != null) {
                CabalMobsMod.LOGGER.info("[CabalMobs] Spawn skipped: active evoker already present after purge sweep (purged={})", purgedBeforeSpawn);
                return;
            }

            BlockPos pos = EvokerBossSpawnPoints.pickRandomSpawn(overworld);
            lastAutomaticSpawnCadenceEpochSecond = epochSecond;
            SpawnedEntity<Evoker> evokerSpawn = createEvokerEntity(overworld);
            if (evokerSpawn == null) {
                CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to create evoker entity");
                return;
            }
            Evoker evoker = evokerSpawn.entity();
            evoker.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            DifficultyInstance difficulty = overworld.getCurrentDifficultyAt(pos);
            evoker.finalizeSpawn(overworld, difficulty, evokerSpawn.spawnReason(), null);

            if (!overworld.addFreshEntity(evoker)) {
                CabalMobsMod.LOGGER.warn("[CabalMobs] addFreshEntity failed for evoker boss");
                return;
            }

            evoker.addTag(EvokerBossConfig.BOSS_TAG);
            if (currentSessionTag != null) {
                evoker.addTag(currentSessionTag);
            }
            ensureBossAttributesApplied(evoker);
            pendingScaleSyncBossId = evoker.getUUID();
            pendingScaleSyncAtGameTime = gameTime + 1L;
            activeBossId = evoker.getUUID();
            activeBossSpawnPos = pos;
            despawnAtGameTime = gameTime + EvokerBossConfig.DESPAWN_TICKS;
            forceBossChunk(overworld, pos);
            encounterActive = false;
            lastEncounterActive = false;
            bossBarPlayerIds.clear();
            completedHealthPhases = 0;
            phaseIntermissionActive = false;
            phaseIntermissionEndsAt = 0L;
            witherAuraActive = false;
            phaseWitherAuraEscalationActive = false;
            phaseWitherAuraEscalationEndsAt = 0L;
            enragedActive = false;
            lastWitherAuraApplyAt = 0L;
            lastKnownBossHealth = evoker.getHealth();
            spawnBossProxies(overworld, evoker);
            ensureBossBar();
            EvokerBossStateStore.recordSpawn(evoker, pos);
            EvokerBossStateStore.updateActivePosition(evoker, true);

            if (bossBar != null) {
                bossBar.setVisible(false);
                bossBar.setProgress(1.0f);
            }

            CabalMobsMod.LOGGER.info("[CabalMobs] Evoker boss spawned (scale={}x, speed={}x, health={}x, despawn in {}s) at [{}, {}, {}]",
                    (int) EvokerBossConfig.BOSS_SCALE,
                    (int) EvokerBossConfig.BOSS_SPEED_MULTIPLIER,
                    (int) EvokerBossConfig.BOSS_HEALTH_MULTIPLIER,
                    EvokerBossConfig.DESPAWN_TICKS / 20,
                    pos.getX(), pos.getY(), pos.getZ());

            Component spawnMsg = Component.literal("§5[Evoker Boss] Spawned at §dX=" + pos.getX() + " Y=" + pos.getY() + " Z=" + pos.getZ());
            for (ServerPlayer player : overworld.players()) {
                player.sendSystemMessage(spawnMsg);
            }
        });
    }

    static boolean isLikelyBossEvoker(Evoker evoker) {
        return evoker.getTags().contains(EvokerBossConfig.BOSS_TAG)
                || evoker.getScale() >= CLEANUP_SCALE_THRESHOLD
                || evoker.getMaxHealth() > 64.0f;
    }

    static void clearActiveBossState(ServerLevel level) {
        discardProxyEntities(level);
        discardBossVexEntities(level);
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBar.setVisible(false);
            bossBar = null;
        }
        encounterActive = false;
        lastEncounterActive = false;
        bossBarPlayerIds.clear();
        activeBossSpawnPos = null;
        pendingScaleSyncBossId = null;
        activeBossId = null;
        pendingDbBossId = null;
        despawnAtGameTime = 0L;
        completedHealthPhases = 0;
        phaseIntermissionActive = false;
        phaseIntermissionEndsAt = 0L;
        witherAuraActive = false;
        phaseWitherAuraEscalationActive = false;
        phaseWitherAuraEscalationEndsAt = 0L;
        enragedActive = false;
        lastWitherAuraApplyAt = 0L;
        clearForcedBossChunk(level);
        traceWindowAttempts = 0;
        traceWindowBlocked = 0;
        traceWindowDamaging = 0;
        traceWindowStartTick = level.getGameTime();
        lastKnownBossHealth = -1.0f;
        contactBurnLastDamageGameTime.clear();
    }

    private static void ensureBossBar() {
        if (bossBar != null) return;
        bossBar = new ServerBossEvent(
                Component.literal("Evoker Boss"),
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.PROGRESS
        );
    }

    /**
     * Binds an overworld evoker to this JVM session: tags, boss bar, hitbox proxies, chunk ticket, and DB row.
     * Used after restarts so we never keep {@code activeBossId} without {@link #bossBar} / proxy UUIDs.
     */
    static void relinkBossToCurrentSession(ServerLevel level, Evoker evoker, long gameTime, String reason) {
        pendingDbBossId = null;
        evoker.addTag(EvokerBossConfig.BOSS_TAG);
        if (currentSessionTag != null) {
            evoker.addTag(currentSessionTag);
        }
        ensureBossAttributesApplied(evoker);
        activeBossId = evoker.getUUID();
        activeBossSpawnPos = evoker.blockPosition();
        despawnAtGameTime = gameTime + EvokerBossConfig.DESPAWN_TICKS;
        completedHealthPhases = computeCurrentHealthPhase(evoker);
        phaseIntermissionActive = false;
        phaseIntermissionEndsAt = 0L;
        witherAuraActive = completedHealthPhases > 0;
        phaseWitherAuraEscalationActive = false;
        phaseWitherAuraEscalationEndsAt = 0L;
        enragedActive = isEnrageThresholdReached(evoker);
        lastWitherAuraApplyAt = 0L;
        encounterActive = false;
        lastEncounterActive = false;
        bossBarPlayerIds.clear();
        forceBossChunk(level, activeBossSpawnPos);
        ensureBossBar();
        if (bossBar != null) {
            bossBar.setVisible(false);
            bossBar.setProgress(Mth.clamp(evoker.getHealth() / evoker.getMaxHealth(), 0.0f, 1.0f));
        }
        spawnBossProxies(level, evoker);
        EvokerBossStateStore.recordSpawn(evoker, evoker.blockPosition());
        EvokerBossStateStore.updateActivePosition(evoker, true);
        CabalMobsMod.LOGGER.info("[CabalMobs] Evoker boss session re-link ({}) uuid={}", reason, evoker.getUUID());
    }

    /** If the active boss is loaded but bar/proxies/session drifted (e.g. chunk loaded after startup), repair without re-recording spawn every tick. */
    private static void repairBossRuntimeIfNeeded(ServerLevel level, Evoker evoker) {
        if (activeBossId == null || !activeBossId.equals(evoker.getUUID())) {
            return;
        }
        if (currentSessionTag != null && !evoker.getTags().contains(currentSessionTag)) {
            evoker.addTag(currentSessionTag);
        }
        if (bossBar == null) {
            ensureBossBar();
            if (bossBar != null) {
                bossBar.setVisible(false);
                bossBar.setProgress(Mth.clamp(evoker.getHealth() / evoker.getMaxHealth(), 0.0f, 1.0f));
            }
        }
        if (!bossProxiesHealthy(level, evoker)) {
            discardStrayBossProxyHusks(level.getServer());
            spawnBossProxies(level, evoker);
        }
        if (activeBossSpawnPos == null) {
            activeBossSpawnPos = evoker.blockPosition();
            forceBossChunk(level, activeBossSpawnPos);
        }
    }

    static long activeLockMaxAgeMs() {
        return (EvokerBossConfig.DESPAWN_TICKS * 50L) + DB_ACTIVE_LOCK_GRACE_MS;
    }

    static List<Evoker> findBossCandidates(ServerLevel level) {
        return level.getEntitiesOfClass(
                Evoker.class,
                GLOBAL_ENTITY_SEARCH_VOLUME,
                evoker -> evoker.isAlive()
                        && isLikelyBossEvoker(evoker)
        );
    }

    private static void syncBossBarPlayers(ServerLevel level) {
        if (bossBar == null) return;
        if (!encounterActive) {
            for (UUID playerId : new HashSet<>(bossBarPlayerIds)) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
                if (player != null) {
                    bossBar.removePlayer(player);
                }
            }
            bossBarPlayerIds.clear();
            bossBar.setVisible(false);
            return;
        }

        bossBar.setVisible(true);
        Set<UUID> currentPlayers = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            currentPlayers.add(player.getUUID());
            if (!bossBarPlayerIds.contains(player.getUUID())) {
                bossBar.addPlayer(player);
            }
        }
        for (UUID previousPlayerId : new HashSet<>(bossBarPlayerIds)) {
            if (!currentPlayers.contains(previousPlayerId)) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(previousPlayerId);
                if (player != null) {
                    bossBar.removePlayer(player);
                }
            }
        }
        bossBarPlayerIds.clear();
        bossBarPlayerIds.addAll(currentPlayers);
    }

    private static void ensureBossAttributesApplied(Evoker evoker) {
        if (evoker.getTags().contains(EvokerBossConfig.BOSS_ATTRIBUTES_APPLIED_TAG)) {
            return;
        }
        EvokerBossHelper.applyBossAttributes(evoker);
        evoker.addTag(EvokerBossConfig.BOSS_ATTRIBUTES_APPLIED_TAG);
    }

    private static boolean isBossProxy(LivingEntity entity) {
        return entity.getTags().contains(EvokerBossConfig.BOSS_PROXY_TAG);
    }

    private static void forceBossChunk(ServerLevel level, BlockPos pos) {
        if (pos == null) {
            return;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        if (forcedBossChunkX != null && forcedBossChunkZ != null
                && forcedBossChunkX == chunkX && forcedBossChunkZ == chunkZ) {
            return;
        }

        if (forcedBossChunkX != null && forcedBossChunkZ != null) {
            level.setChunkForced(forcedBossChunkX, forcedBossChunkZ, false);
        }

        level.setChunkForced(chunkX, chunkZ, true);
        forcedBossChunkX = chunkX;
        forcedBossChunkZ = chunkZ;
    }

    static void clearForcedBossChunk(ServerLevel level) {
        if (forcedBossChunkX == null || forcedBossChunkZ == null) {
            return;
        }
        level.setChunkForced(forcedBossChunkX, forcedBossChunkZ, false);
        forcedBossChunkX = null;
        forcedBossChunkZ = null;
    }

    /**
     * Resolves a tracked boss hitbox proxy anywhere dimensions are loaded; {@link ServerLevel#getEntity(UUID)} only
     * consults one dimension's entity map.
     */
    private static @Nullable Husk resolveBossProxyHusk(ServerLevel overworld, UUID proxyId) {
        if (proxyId == null) {
            return null;
        }
        Entity entity = overworld.getEntityInAnyDimension(proxyId);
        return entity instanceof Husk husk && husk.isAlive() ? husk : null;
    }

    private static boolean bossProxiesHealthy(ServerLevel overworld, Evoker evoker) {
        if (headProxyId == null || torsoProxyId == null) {
            return false;
        }
        Husk head = resolveBossProxyHusk(overworld, headProxyId);
        Husk torso = resolveBossProxyHusk(overworld, torsoProxyId);
        if (head == null || torso == null) {
            return false;
        }
        ServerLevel bossLevel = (ServerLevel) evoker.level();
        return head.level() == bossLevel && torso.level() == bossLevel;
    }

    /**
     * Discards persisted or duplicate {@link EvokerBossConfig#BOSS_PROXY_TAG} husks that are not the currently tracked
     * pair (e.g. proxies from a previous session whose chunk loaded after the first startup purge).
     */
    private static void discardStrayBossProxyHusks(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel sl : server.getAllLevels()) {
            List<Husk> proxies = sl.getEntitiesOfClass(
                    Husk.class,
                    GLOBAL_ENTITY_SEARCH_VOLUME,
                    husk -> husk.isAlive() && husk.getTags().contains(EvokerBossConfig.BOSS_PROXY_TAG));
            for (Husk proxy : proxies) {
                UUID id = proxy.getUUID();
                if (headProxyId != null && headProxyId.equals(id)) {
                    continue;
                }
                if (torsoProxyId != null && torsoProxyId.equals(id)) {
                    continue;
                }
                proxy.discard();
            }
        }
    }

    private static void discardProxyEntities(ServerLevel level) {
        discardProxy(level, headProxyId);
        discardProxy(level, torsoProxyId);
        headProxyId = null;
        torsoProxyId = null;
    }

    private static void discardProxy(ServerLevel overworld, UUID proxyId) {
        if (proxyId == null) {
            return;
        }
        Entity proxy = overworld.getEntityInAnyDimension(proxyId);
        if (proxy != null && proxy.isAlive()) {
            proxy.discard();
        }
    }

    private static Vec3 bossArenaSearchCenter(ServerLevel level) {
        if (activeBossSpawnPos != null) {
            return Vec3.atBottomCenterOf(activeBossSpawnPos);
        }
        return EvokerBossSpawnPoints.fallbackArenaBottomCenter(level);
    }

    private static void discardBossVexEntities(ServerLevel level) {
        Vec3 center = bossArenaSearchCenter(level);
        double r = EvokerBossConfig.BOSS_VEX_PURGE_RADIUS_BLOCKS;
        double x = center.x;
        double z = center.z;
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        AABB box = new AABB(x - r, minY, z - r, x + r, maxY, z + r);
        List<Vex> bossVexes = level.getEntitiesOfClass(
                Vex.class,
                box,
                vex -> vex.isAlive() && vex.getTags().contains(EvokerBossConfig.BOSS_VEX_TAG));
        for (Vex vex : bossVexes) {
            vex.discard();
        }
    }

    private static void spawnBossProxies(ServerLevel level, Evoker evoker) {
        discardProxyEntities(level);
        Husk headProxy = createProxy(level, evoker, EvokerBossConfig.BOSS_PROXY_HEAD_TAG, EvokerBossConfig.PROXY_HEAD_Y_OFFSET);
        Husk torsoProxy = createProxy(level, evoker, EvokerBossConfig.BOSS_PROXY_TORSO_TAG, EvokerBossConfig.PROXY_TORSO_Y_OFFSET);
        if (headProxy != null) {
            headProxyId = headProxy.getUUID();
        }
        if (torsoProxy != null) {
            torsoProxyId = torsoProxy.getUUID();
        }
    }

    private static Husk createProxy(ServerLevel level, Evoker boss, String partTag, double yOffset) {
        SpawnedEntity<Husk> proxySpawn = createHuskEntity(level);
        if (proxySpawn == null) {
            CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to create husk boss proxy ({})", partTag);
            return null;
        }
        Husk proxy = proxySpawn.entity();
        proxy.addTag(EvokerBossConfig.BOSS_PROXY_TAG);
        proxy.addTag(partTag);
        proxy.setInvisible(!EvokerBossConfig.DEBUG_HITBOX_VISUALS);
        proxy.setGlowingTag(EvokerBossConfig.DEBUG_HITBOX_VISUALS);
        proxy.setNoAi(true);
        proxy.setNoGravity(true);
        proxy.setSilent(true);
        proxy.setPersistenceRequired();
        proxy.setCustomName(Component.literal(partTag.equals(EvokerBossConfig.BOSS_PROXY_HEAD_TAG) ? "Boss Hitbox: HEAD" : "Boss Hitbox: TORSO"));
        proxy.setCustomNameVisible(EvokerBossConfig.DEBUG_HITBOX_VISUALS);
        proxy.refreshDimensions();
        positionProxy(proxy, boss, yOffset);
        if (!level.addFreshEntity(proxy)) {
            CabalMobsMod.LOGGER.warn("[CabalMobs] addFreshEntity failed for boss proxy ({})", partTag);
            return null;
        }
        replenishProxy(proxy);
        return proxy;
    }

    static SpawnedEntity<Evoker> createEvokerEntity(ServerLevel level) {
        SpawnedEntity<? extends Entity> created = createEntityWithReasonFallback(level, EntityType.EVOKER, "evoker");
        return created != null && created.entity() instanceof Evoker evoker
                ? new SpawnedEntity<>(evoker, created.spawnReason())
                : null;
    }

    private static SpawnedEntity<Husk> createHuskEntity(ServerLevel level) {
        SpawnedEntity<? extends Entity> created = createEntityWithReasonFallback(level, EntityType.HUSK, "husk");
        return created != null && created.entity() instanceof Husk husk
                ? new SpawnedEntity<>(husk, created.spawnReason())
                : null;
    }

    private static SpawnedEntity<? extends Entity> createEntityWithReasonFallback(
            ServerLevel level,
            EntityType<?> type,
            String debugTypeName
    ) {
        for (EntitySpawnReason reason : PREFERRED_SPAWN_REASONS) {
            Entity created = type.create(level, reason);
            if (created != null) {
                return new SpawnedEntity<>(created, reason);
            }
        }
        CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to instantiate {} via EntityType#create after trying spawn reasons EVENT/COMMAND/MOB_SUMMONED/NATURAL", debugTypeName);
        return null;
    }

    private static void followBossProxyPositions(ServerLevel level, Evoker boss) {
        followProxyPosition(level, headProxyId, boss, EvokerBossConfig.PROXY_HEAD_Y_OFFSET);
        followProxyPosition(level, torsoProxyId, boss, EvokerBossConfig.PROXY_TORSO_Y_OFFSET);
    }

    private static void followProxyPosition(ServerLevel overworld, UUID proxyId, Evoker boss, double yOffset) {
        if (proxyId == null) {
            return;
        }
        Husk proxy = resolveBossProxyHusk(overworld, proxyId);
        if (proxy == null) {
            return;
        }
        ServerLevel bossLevel = (ServerLevel) boss.level();
        if (proxy.level() != bossLevel) {
            double x = boss.getX();
            double y = boss.getY() + yOffset;
            double z = boss.getZ();
            proxy.teleportTo(bossLevel, x, y, z, Set.of(), boss.getYRot(), boss.getXRot(), false);
        }
        positionProxy(proxy, boss, yOffset);
    }

    private static void replenishBossProxies(ServerLevel level) {
        replenishProxyIfPresent(level, headProxyId);
        replenishProxyIfPresent(level, torsoProxyId);
    }

    private static void replenishProxyIfPresent(ServerLevel overworld, UUID proxyId) {
        if (proxyId == null) {
            return;
        }
        Husk proxy = resolveBossProxyHusk(overworld, proxyId);
        if (proxy == null) {
            return;
        }
        replenishProxy(proxy);
    }

    /**
     * Discards boss hitbox proxy husks in {@code level}'s dimension, then re-attaches proxies if the tracked active
     * boss still exists in the overworld (boss logic is overworld-centric). If there is no live overworld boss,
     * clears scheduler state using the overworld when available so proxy/vex discard resolves entities.
     */
    static void sweepBossProxyHusksAfterPurge(ServerLevel level) {
        List<Husk> proxies = level.getEntitiesOfClass(
                Husk.class,
                GLOBAL_ENTITY_SEARCH_VOLUME,
                husk -> husk.isAlive() && husk.getTags().contains(EvokerBossConfig.BOSS_PROXY_TAG));
        for (Husk husk : proxies) {
            husk.discard();
        }
        // Keep headProxyId / torsoProxyId until spawnBossProxies or clearActiveBossState so discardProxyEntities
        // can still resolve and discard overworld proxies when this purge runs from another dimension.
        MinecraftServer server = level.getServer();
        UUID trackedBoss = activeBossId != null ? activeBossId : pendingDbBossId;
        if (trackedBoss == null) {
            ServerLevel proxyDiscardLevel = server != null && server.overworld() != null ? server.overworld() : level;
            discardProxyEntities(proxyDiscardLevel);
            return;
        }
        ServerLevel overworld = server == null ? null : server.overworld();
        if (overworld != null) {
            Entity entity = overworld.getEntity(trackedBoss);
            if (entity instanceof Evoker evoker && evoker.isAlive()) {
                spawnBossProxies(overworld, evoker);
                return;
            }
            if (entity == null
                    && ((pendingDbBossId != null && pendingDbBossId.equals(trackedBoss))
                    || (activeBossId != null && activeBossId.equals(trackedBoss)))) {
                // Tracked boss not in entity map yet (unloaded chunk) — keep scheduler/DB lock; do not clear session.
                return;
            }
        }
        ServerLevel clearLevel = overworld != null ? overworld : level;
        clearActiveBossState(clearLevel);
    }

    private static void positionProxy(Husk proxy, Evoker boss, double yOffset) {
        double x = boss.getX();
        double y = boss.getY() + yOffset;
        double z = boss.getZ();
        // snapTo resets previous-position for clients; setPos alone lets proxies interpolate away from the boss.
        proxy.snapTo(x, y, z, boss.getYRot(), boss.getXRot());
        proxy.setYHeadRot(boss.getYHeadRot());
        proxy.setDeltaMovement(Vec3.ZERO);
    }

    private static void replenishProxy(LivingEntity proxy) {
        AttributeInstance maxHealth = proxy.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(EvokerBossConfig.PROXY_MAX_HEALTH);
        }
        proxy.setHealth((float) EvokerBossConfig.PROXY_MAX_HEALTH);
        proxy.invulnerableTime = 0;
    }

    private static void renderHitboxDiagnostics(ServerLevel level, Evoker evoker) {
        drawBoxWireframe(level, evoker.getBoundingBox(), ParticleTypes.END_ROD);
        renderProxyBox(level, headProxyId, ParticleTypes.FLAME);
        renderProxyBox(level, torsoProxyId, ParticleTypes.SOUL_FIRE_FLAME);
    }

    private static void renderProxyBox(ServerLevel level, UUID proxyId, net.minecraft.core.particles.SimpleParticleType particle) {
        if (proxyId == null) return;
        Husk proxy = resolveBossProxyHusk(level, proxyId);
        if (proxy == null) {
            return;
        }
        drawBoxWireframe(level, proxy.getBoundingBox(), particle);
    }

    private static void drawBoxWireframe(ServerLevel level, AABB box, net.minecraft.core.particles.SimpleParticleType particle) {
        Vec3 p000 = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 p001 = new Vec3(box.minX, box.minY, box.maxZ);
        Vec3 p010 = new Vec3(box.minX, box.maxY, box.minZ);
        Vec3 p011 = new Vec3(box.minX, box.maxY, box.maxZ);
        Vec3 p100 = new Vec3(box.maxX, box.minY, box.minZ);
        Vec3 p101 = new Vec3(box.maxX, box.minY, box.maxZ);
        Vec3 p110 = new Vec3(box.maxX, box.maxY, box.minZ);
        Vec3 p111 = new Vec3(box.maxX, box.maxY, box.maxZ);

        drawEdge(level, particle, p000, p001);
        drawEdge(level, particle, p001, p101);
        drawEdge(level, particle, p101, p100);
        drawEdge(level, particle, p100, p000);

        drawEdge(level, particle, p010, p011);
        drawEdge(level, particle, p011, p111);
        drawEdge(level, particle, p111, p110);
        drawEdge(level, particle, p110, p010);

        drawEdge(level, particle, p000, p010);
        drawEdge(level, particle, p001, p011);
        drawEdge(level, particle, p101, p111);
        drawEdge(level, particle, p100, p110);
    }

    private static void drawEdge(ServerLevel level, net.minecraft.core.particles.SimpleParticleType particle, Vec3 start, Vec3 end) {
        double length = start.distanceTo(end);
        int steps = Math.max(2, (int) Math.ceil(length / 1.25));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3 point = start.lerp(end, t);
            level.sendParticles(particle, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static AABB unionAabb(AABB a, AABB b) {
        return new AABB(
                Math.min(a.minX, b.minX),
                Math.min(a.minY, b.minY),
                Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX),
                Math.max(a.maxY, b.maxY),
                Math.max(a.maxZ, b.maxZ));
    }

    private static AABB contactBurnPlayerQueryBounds(ServerLevel level, Evoker evoker) {
        AABB bounds = evoker.getBoundingBox();
        if (headProxyId != null) {
            Husk head = resolveBossProxyHusk(level, headProxyId);
            if (head != null) {
                bounds = unionAabb(bounds, head.getBoundingBox());
            }
        }
        if (torsoProxyId != null) {
            Husk torso = resolveBossProxyHusk(level, torsoProxyId);
            if (torso != null) {
                bounds = unionAabb(bounds, torso.getBoundingBox());
            }
        }
        return bounds.inflate(EvokerBossConfig.CONTACT_BURN_PLAYER_SEARCH_INFLATE_BLOCKS);
    }

    private static void tickEvokerBossPhases(ServerLevel level, Evoker evoker, long gameTime) {
        // Phase logic should never disable combat AI.
        evoker.setNoAi(false);
        updateEnrageState(evoker, gameTime);

        if (phaseWitherAuraEscalationActive && gameTime >= phaseWitherAuraEscalationEndsAt) {
            phaseWitherAuraEscalationActive = false;
            phaseWitherAuraEscalationEndsAt = 0L;
        }

        if (phaseIntermissionActive) {
            if (gameTime >= phaseIntermissionEndsAt) {
                phaseIntermissionActive = false;
                phaseWitherAuraEscalationActive = witherAuraActive;
                phaseWitherAuraEscalationEndsAt = witherAuraActive
                        ? gameTime + EvokerBossConfig.PHASE_WITHER_AURA_ESCALATION_DURATION_TICKS
                        : 0L;
                CabalMobsMod.LOGGER.info(
                        "[CabalMobs] Phase intermission ended at health={}%; phase aura escalation {} for {}s (AI remained enabled).",
                        String.format("%.1f", evoker.getHealth() * 100.0f / Math.max(1.0f, evoker.getMaxHealth())),
                        phaseWitherAuraEscalationActive ? "enabled" : "disabled",
                        EvokerBossConfig.PHASE_WITHER_AURA_ESCALATION_DURATION_TICKS / 20
                );
            } else if (gameTime % 5L == 0L) {
                level.sendParticles(
                        ParticleTypes.SMOKE,
                        evoker.getX(),
                        evoker.getY() + evoker.getBbHeight() * 0.5,
                        evoker.getZ(),
                        14,
                        0.8,
                        1.1,
                        0.8,
                        0.0
                );
            }
            return;
        }

        int currentPhase = computeCurrentHealthPhase(evoker);
        if (currentPhase > completedHealthPhases) {
            completedHealthPhases++;
            phaseIntermissionActive = true;
            phaseIntermissionEndsAt = gameTime + EvokerBossConfig.PHASE_INTERMISSION_DURATION_TICKS;
            witherAuraActive = true;
            phaseWitherAuraEscalationActive = false;
            phaseWitherAuraEscalationEndsAt = 0L;
            CabalMobsMod.LOGGER.info(
                    "[CabalMobs] Evoker boss entered phase {} (health={}%), aura delay {}s (AI still active).",
                    completedHealthPhases,
                    String.format("%.1f", evoker.getHealth() * 100.0f / Math.max(1.0f, evoker.getMaxHealth())),
                    EvokerBossConfig.PHASE_INTERMISSION_DURATION_TICKS / 20
            );
        }
    }

    private static void updateEnrageState(Evoker evoker, long gameTime) {
        int checkInterval = Math.max(1, EvokerBossConfig.ENRAGE_CHECK_INTERVAL_TICKS);
        if (gameTime % checkInterval != 0L) {
            return;
        }
        if (enragedActive || !isEnrageThresholdReached(evoker)) {
            return;
        }
        enragedActive = true;
        lastWitherAuraApplyAt = 0L;
        CabalMobsMod.LOGGER.info(
                "[CabalMobs] Evoker boss ENRAGED at health={}%. Constant wither aura radius set to {} blocks.",
                String.format("%.1f", evoker.getHealth() * 100.0f / Math.max(1.0f, evoker.getMaxHealth())),
                String.format("%.1f", EvokerBossConfig.ENRAGED_WITHER_AURA_RADIUS_BLOCKS)
        );
    }

    private static boolean isEnrageThresholdReached(Evoker evoker) {
        float ratio = evoker.getHealth() / Math.max(1.0f, evoker.getMaxHealth());
        return ratio <= EvokerBossConfig.ENRAGE_HEALTH_THRESHOLD_RATIO;
    }

    private static double resolveCurrentWitherAuraRadius() {
        if (!witherAuraActive) {
            return 0.0;
        }
        if (enragedActive) {
            return phaseWitherAuraEscalationActive
                    ? EvokerBossConfig.ENRAGED_PHASE_WITHER_AURA_RADIUS_BLOCKS
                    : EvokerBossConfig.ENRAGED_WITHER_AURA_RADIUS_BLOCKS;
        }
        return EvokerBossConfig.WITHER_AURA_RADIUS_BLOCKS;
    }

    private static void updateBossBarVisuals() {
        if (bossBar == null) {
            return;
        }
        if (enragedActive) {
            bossBar.setColor(BossEvent.BossBarColor.RED);
            bossBar.setName(Component.literal("§4§lEvoker Boss [ENRAGED]"));
            bossBar.setOverlay(BossEvent.BossBarOverlay.NOTCHED_20);
            return;
        }
        bossBar.setColor(BossEvent.BossBarColor.PURPLE);
        bossBar.setName(Component.literal("Evoker Boss"));
        bossBar.setOverlay(BossEvent.BossBarOverlay.PROGRESS);
    }

    private static int computeCurrentHealthPhase(Evoker evoker) {
        float healthRatio = Mth.clamp(evoker.getHealth() / Math.max(1.0f, evoker.getMaxHealth()), 0.0f, 1.0f);
        int maxPhases = Math.max(0, (100 / EvokerBossConfig.PHASE_HEALTH_STEP_PERCENT) - 1);
        int reached = (int) Math.floor((1.0f - healthRatio) * (100.0f / EvokerBossConfig.PHASE_HEALTH_STEP_PERCENT));
        return Mth.clamp(reached, 0, maxPhases);
    }

    private static void applyEvokerWitherAura(ServerLevel level, Evoker evoker, long gameTime, double radius) {
        double radiusSq = radius * radius;

        if (gameTime - lastWitherAuraApplyAt >= EvokerBossConfig.WITHER_AURA_APPLY_INTERVAL_TICKS) {
            lastWitherAuraApplyAt = gameTime;
            for (ServerPlayer player : level.players()) {
                if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
                if (player.distanceToSqr(evoker) > radiusSq) continue;
                player.addEffect(
                        new MobEffectInstance(
                                MobEffects.WITHER,
                                EvokerBossConfig.WITHER_AURA_EFFECT_DURATION_TICKS,
                                EvokerBossConfig.WITHER_AURA_EFFECT_AMPLIFIER,
                                true,
                                true,
                                true
                        ),
                        evoker
                );
                player.igniteForTicks(EvokerBossConfig.WITHER_AURA_BURN_FIRE_TICKS);
                player.hurtServer(
                        level,
                        evoker.damageSources().mobAttack(evoker),
                        EvokerBossConfig.WITHER_AURA_BURN_DAMAGE
                );
            }
        }

        if (gameTime % EvokerBossConfig.WITHER_AURA_PARTICLE_EVERY_TICKS != 0L) return;

        int points = EvokerBossConfig.WITHER_AURA_RING_POINTS;
        double centerX = evoker.getX();
        double centerY = evoker.getY() + evoker.getBbHeight() * 0.55;
        double centerZ = evoker.getZ();
        double phase = (gameTime % 200L) / 200.0 * Math.PI * 2.0;

        // Draw an animated sphere shell (latitude rings) so aura is obvious in 3D.
        int latitudeBands = 6;
        for (int latBand = 0; latBand <= latitudeBands; latBand++) {
            double t = latBand / (double) latitudeBands;
            double latitude = -Math.PI / 2.0 + Math.PI * t;
            double ringRadius = radius * Math.cos(latitude);
            double y = centerY + radius * Math.sin(latitude);
            int ringPoints = Math.max(12, (int) Math.round(points * Math.max(0.25, Math.cos(latitude))));
            for (int i = 0; i < ringPoints; i++) {
                double angle = phase + (Math.PI * 2.0 * i) / ringPoints;
                double x = centerX + Math.cos(angle) * ringRadius;
                double z = centerZ + Math.sin(angle) * ringRadius;
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 1, 0.0, 0.01, 0.0, 0.0);
                level.sendParticles(ParticleTypes.SMOKE, x, y, z, 1, 0.0, 0.01, 0.0, 0.0);
            }
        }

        // Add a vertical inner swirl to make the aura easier to perceive in motion.
        int swirlPoints = 24;
        double swirlRadius = radius * 0.35;
        for (int i = 0; i < swirlPoints; i++) {
            double t = i / (double) (swirlPoints - 1);
            double angle = phase * 1.8 + (Math.PI * 2.0 * i) / swirlPoints;
            double y = centerY - radius + (radius * 2.0 * t);
            double x = centerX + Math.cos(angle) * swirlRadius;
            double z = centerZ + Math.sin(angle) * swirlRadius;
            level.sendParticles(ParticleTypes.SOUL, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static void logHitTrace(
            Evoker evoker,
            net.minecraft.world.damagesource.DamageSource source,
            float baseDamage,
            float damageTaken,
            boolean blocked
    ) {
        Vec3 sourcePos = source.getSourcePosition();
        Entity direct = source.getDirectEntity();
        Entity attacker = source.getEntity();

        String sourceType = source.type().msgId();
        String directType = direct == null ? "none" : direct.getType().toString();
        String attackerType = attacker == null ? "none" : attacker.getType().toString();
        String sourceDistance = sourcePos == null ? "n/a" : String.format("%.2f", sourcePos.distanceTo(evoker.position()));

        CabalMobsMod.LOGGER.info(
                "[CabalMobs][HitTrace] source={} direct={} attacker={} base={} taken={} blocked={} src_dist={} boss_pos=({}, {}, {}) bb=({}, {}) scale={} pose={}",
                sourceType,
                directType,
                attackerType,
                String.format("%.2f", baseDamage),
                String.format("%.2f", damageTaken),
                blocked,
                sourceDistance,
                String.format("%.2f", evoker.getX()),
                String.format("%.2f", evoker.getY()),
                String.format("%.2f", evoker.getZ()),
                String.format("%.2f", evoker.getBbWidth()),
                String.format("%.2f", evoker.getBbHeight()),
                String.format("%.2f", evoker.getScale()),
                evoker.getPose()
        );
    }

    private static void logProjectileDiagnostics(ServerLevel level, Evoker evoker) {
        AABB bossBox = evoker.getBoundingBox();
        AABB search = bossBox.inflate(6.0);
        List<AbstractArrow> arrows = level.getEntitiesOfClass(
                AbstractArrow.class,
                search,
                arrow -> arrow.isAlive() && !arrow.isRemoved()
        );
        if (arrows.isEmpty()) return;

        int intersects = 0;
        int contains = 0;
        double nearestArrowDist = Double.MAX_VALUE;

        for (AbstractArrow arrow : arrows) {
            AABB arrowBox = arrow.getBoundingBox();
            if (arrowBox.intersects(bossBox)) intersects++;
            if (bossBox.contains(arrow.position())) contains++;
            nearestArrowDist = Math.min(nearestArrowDist, arrow.position().distanceTo(evoker.position()));
        }

        CabalMobsMod.LOGGER.info(
                "[CabalMobs][HitTraceProj] arrows_near={} intersects={} contains={} nearest_dist={} boss_bb=({}, {}) boss_pos=({}, {}, {})",
                arrows.size(),
                intersects,
                contains,
                String.format("%.2f", nearestArrowDist),
                String.format("%.2f", evoker.getBbWidth()),
                String.format("%.2f", evoker.getBbHeight()),
                String.format("%.2f", evoker.getX()),
                String.format("%.2f", evoker.getY()),
                String.format("%.2f", evoker.getZ())
        );
    }

    private static void applyEvokerBossContactBurn(ServerLevel level, Evoker evoker, long gameTime) {
        AABB query = contactBurnPlayerQueryBounds(level, evoker);
        List<ServerPlayer> candidates = level.getEntitiesOfClass(
                ServerPlayer.class,
                query,
                p -> p.isAlive() && !p.isSpectator() && !p.isCreative());
        for (ServerPlayer player : candidates) {
            if (!playerTouchesBossHitbox(level, player, evoker)) {
                continue;
            }
            player.igniteForTicks(EvokerBossConfig.CONTACT_BURN_FIRE_TICKS);
            long last = contactBurnLastDamageGameTime.getOrDefault(player.getUUID(), Long.MIN_VALUE);
            if (gameTime - last >= EvokerBossConfig.CONTACT_BURN_DAMAGE_INTERVAL_TICKS) {
                player.hurtServer(level, evoker.damageSources().mobAttack(evoker), EvokerBossConfig.CONTACT_BURN_EXTRA_DAMAGE);
                contactBurnLastDamageGameTime.put(player.getUUID(), gameTime);
            }
        }
    }

    private static boolean playerTouchesBossHitbox(ServerLevel level, ServerPlayer player, Evoker evoker) {
        AABB playerBox = player.getBoundingBox();
        if (playerBox.intersects(evoker.getBoundingBox())) {
            return true;
        }
        Husk head = headProxyId == null ? null : resolveBossProxyHusk(level, headProxyId);
        if (head != null && playerBox.intersects(head.getBoundingBox())) {
            return true;
        }
        Husk torso = torsoProxyId == null ? null : resolveBossProxyHusk(level, torsoProxyId);
        return torso != null && playerBox.intersects(torso.getBoundingBox());
    }

    /** Periodic context for operators; use Spark (server/SPARK.md) for stack-level attribution. */
    private static void logBossInfraSnapshot(ServerLevel level, Evoker evoker, long gameTime) {
        double x = evoker.getX();
        double z = evoker.getZ();
        double colR = EvokerBossConfig.BOSS_VEX_PURGE_RADIUS_BLOCKS;
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        AABB column = new AABB(x - colR, minY, z - colR, x + colR, maxY, z + colR);
        int bossVexes = level.getEntitiesOfClass(
                Vex.class,
                column,
                v -> v.isAlive() && v.getTags().contains(EvokerBossConfig.BOSS_VEX_TAG)
        ).size();

        double nearR = EvokerBossConfig.BOSS_INFRA_PLAYER_NEAR_RADIUS_BLOCKS;
        AABB nearPlayers = evoker.getBoundingBox().inflate(nearR, 0.0, nearR);
        int playersNear = level.getEntitiesOfClass(
                ServerPlayer.class,
                nearPlayers,
                p -> p.isAlive() && !p.isSpectator() && !p.isCreative()
        ).size();

        Husk head = headProxyId == null ? null : resolveBossProxyHusk(level, headProxyId);
        Husk torso = torsoProxyId == null ? null : resolveBossProxyHusk(level, torsoProxyId);
        boolean headOk = head != null;
        boolean torsoOk = torso != null;

        double auraR = resolveCurrentWitherAuraRadius();
        MinecraftServer server = level.getServer();
        int playersOnline = server == null ? 0 : server.getPlayerList().getPlayers().size();

        CabalMobsMod.LOGGER.info(
                "[CabalMobs][BossInfra] gameTime={} pos=({}, {}, {}) health={}/{} health_pct={} phases_done={} "
                        + "intermission={} wither_aura={} aura_escalation={} enraged={} aura_radius_blocks={} "
                        + "boss_vex_column={} players_near={} proxies_head={} proxies_torso={} encounter={} "
                        + "players_online={} chunk_forced=({}, {})",
                gameTime,
                String.format("%.1f", evoker.getX()),
                String.format("%.1f", evoker.getY()),
                String.format("%.1f", evoker.getZ()),
                String.format("%.1f", evoker.getHealth()),
                String.format("%.1f", evoker.getMaxHealth()),
                String.format("%.1f", evoker.getHealth() * 100.0f / Math.max(1.0f, evoker.getMaxHealth())),
                completedHealthPhases,
                phaseIntermissionActive,
                witherAuraActive,
                phaseWitherAuraEscalationActive,
                enragedActive,
                String.format("%.1f", auraR),
                bossVexes,
                playersNear,
                headOk,
                torsoOk,
                encounterActive,
                playersOnline,
                forcedBossChunkX == null ? "none" : String.valueOf(forcedBossChunkX),
                forcedBossChunkZ == null ? "none" : String.valueOf(forcedBossChunkZ)
        );
    }

    private static void sampleTickPressure(ServerLevel level, long gameTime) {
        long now = System.nanoTime();
        if (tickPressureLastSampleNanos == 0L) {
            tickPressureLastSampleNanos = now;
            tickPressureWindowStartGameTime = gameTime;
            return;
        }

        double mspt = (now - tickPressureLastSampleNanos) / 1_000_000.0;
        tickPressureLastSampleNanos = now;
        if (mspt < 0.0 || mspt > 5_000.0) {
            return;
        }

        tickPressureAccumulatedMspt += mspt;
        tickPressureMaxMspt = Math.max(tickPressureMaxMspt, mspt);
        tickPressureSampleCount++;

        if (tickPressureWindowStartGameTime == 0L) {
            tickPressureWindowStartGameTime = gameTime;
        }
        long windowTicks = gameTime - tickPressureWindowStartGameTime;
        if (windowTicks < EvokerBossConfig.TICK_PRESSURE_LOG_INTERVAL_TICKS) {
            return;
        }

        double avgMspt = tickPressureSampleCount == 0 ? 0.0 : tickPressureAccumulatedMspt / tickPressureSampleCount;
        double warnThreshold = EvokerBossConfig.TICK_PRESSURE_WARN_MSPT;
        boolean elevated = avgMspt >= warnThreshold || tickPressureMaxMspt >= (warnThreshold + 5.0);
        String template = elevated
                ? "[CabalMobs][TickPressure] avg_mspt={} max_mspt={} samples={} players_online={} boss_active={}"
                : "[CabalMobs][TickPressure] avg_mspt={} max_mspt={} samples={} players_online={} boss_active={}";
        if (elevated) {
            CabalMobsMod.LOGGER.warn(
                    template,
                    String.format("%.2f", avgMspt),
                    String.format("%.2f", tickPressureMaxMspt),
                    tickPressureSampleCount,
                    level.players().size(),
                    activeBossId != null || pendingDbBossId != null
            );
        } else {
            CabalMobsMod.LOGGER.info(
                    template,
                    String.format("%.2f", avgMspt),
                    String.format("%.2f", tickPressureMaxMspt),
                    tickPressureSampleCount,
                    level.players().size(),
                    activeBossId != null || pendingDbBossId != null
            );
        }

        tickPressureWindowStartGameTime = gameTime;
        tickPressureAccumulatedMspt = 0.0;
        tickPressureMaxMspt = 0.0;
        tickPressureSampleCount = 0;
    }
}
