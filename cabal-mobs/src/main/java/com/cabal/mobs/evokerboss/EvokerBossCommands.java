package com.cabal.mobs.evokerboss;

import com.cabal.mobs.CabalMobsMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Brigadier registration and handlers for evoker boss admin and public status commands.
 * Runtime state remains on {@link EvokerBossScheduler}; this class is side-effect wiring only.
 */
public final class EvokerBossCommands {

    private EvokerBossCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerAll(dispatcher));
    }

    private static void registerAll(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("evokerboss")
                        .requires(EvokerBossCommands::hasBossAdminPermission)
                        .then(Commands.literal("purge")
                                .executes(ctx -> purgeBossCommand(ctx, 96))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                                        .executes(ctx -> purgeBossCommand(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
                        .then(Commands.literal("stressvanillaevokers")
                                .executes(ctx -> stressVanillaEvokersCommand(ctx, 10, 48))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> stressVanillaEvokersCommand(
                                                ctx, IntegerArgumentType.getInteger(ctx, "count"), 48))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, 256))
                                                .executes(ctx -> stressVanillaEvokersCommand(
                                                        ctx,
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))))
        );
        dispatcher.register(
                Commands.literal("boss")
                        .executes(EvokerBossCommands::showBossCommand)
        );
    }

    private static int purgeBossCommand(CommandContext<CommandSourceStack> ctx, int radius) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("\u00a7cCommand must be used in a server world."));
            return 0;
        }
        Evoker lookedAt = findLookTargetBoss(player, level, 192.0, 5.0);
        int purgedCount = 0;

        if (lookedAt != null) {
            boolean lookedWasActive = (EvokerBossScheduler.activeBossId != null
                    && EvokerBossScheduler.activeBossId.equals(lookedAt.getUUID()))
                    || (EvokerBossScheduler.pendingDbBossId != null
                    && EvokerBossScheduler.pendingDbBossId.equals(lookedAt.getUUID()));
            EvokerBossStateStore.markPurged(lookedAt.getUUID(), "command_purge_look");
            lookedAt.discard();
            purgedCount = 1;
            if (lookedWasActive) {
                EvokerBossScheduler.clearActiveBossState(level);
            }
            EvokerBossScheduler.sweepBossProxyHusksAfterPurge(level);
            source.sendSuccess(() -> Component.literal("\u00a7aPurged looked-at evoker boss."), true);
            return purgedCount;
        }

        AABB area = player.getBoundingBox().inflate(radius);
        List<Evoker> nearby = level.getEntitiesOfClass(
                Evoker.class,
                area,
                evoker -> evoker.isAlive() && EvokerBossScheduler.isLikelyBossEvoker(evoker)
        );
        for (Evoker evoker : nearby) {
            boolean wasActive = (EvokerBossScheduler.activeBossId != null
                    && EvokerBossScheduler.activeBossId.equals(evoker.getUUID()))
                    || (EvokerBossScheduler.pendingDbBossId != null
                    && EvokerBossScheduler.pendingDbBossId.equals(evoker.getUUID()));
            EvokerBossStateStore.markPurged(evoker.getUUID(), "command_purge_radius");
            evoker.discard();
            purgedCount++;
            if (wasActive) {
                EvokerBossScheduler.clearActiveBossState(level);
            }
        }

        EvokerBossScheduler.sweepBossProxyHusksAfterPurge(level);
        if (purgedCount > 0) {
            int finalPurgedCount = purgedCount;
            source.sendSuccess(() -> Component.literal("\u00a7aPurged " + finalPurgedCount + " evoker boss(es) within " + radius + " blocks."), true);
            return purgedCount;
        }

        source.sendFailure(Component.literal("\u00a7cNo boss-like evoker found in your sight or within " + radius + " blocks."));
        return 0;
    }

    /**
     * Spawns untagged vanilla evokers near the player for load testing (not boss scale, no proxies).
     */
    private static int stressVanillaEvokersCommand(CommandContext<CommandSourceStack> ctx, int count, int radius) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("\u00a7cCommand must be used in a server world."));
            return 0;
        }
        Vec3 center = player.position();
        var random = level.getRandom();
        int spawned = 0;
        int maxDist = Math.max(1, radius - 4);
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 4 + random.nextDouble() * maxDist;
            double x = center.x + Math.cos(angle) * dist;
            double z = center.z + Math.sin(angle) * dist;
            BlockPos column = BlockPos.containing(x, center.y, z);
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
            EvokerBossScheduler.SpawnedEntity<Evoker> evokerSpawn = EvokerBossScheduler.createEvokerEntity(level);
            if (evokerSpawn == null) {
                CabalMobsMod.LOGGER.warn("[CabalMobs] stressvanillaevokers: failed to create evoker");
                continue;
            }
            Evoker evoker = evokerSpawn.entity();
            evoker.setPos(x, surface.getY() + 1.0, z);
            evoker.finalizeSpawn(level, level.getCurrentDifficultyAt(surface), evokerSpawn.spawnReason(), null);
            if (level.addFreshEntity(evoker)) {
                spawned++;
            }
        }
        int finalSpawned = spawned;
        source.sendSuccess(
                () -> Component.literal(
                        "\u00a7aSpawned " + finalSpawned + "/" + count + " vanilla evokers (r=" + radius + "). "
                                + "\u00a77Kill with /kill @e[type=minecraft:evoker,distance=..128] \u00a77if needed."),
                true);
        CabalMobsMod.LOGGER.info(
                "[CabalMobs] stressvanillaevokers spawned={} requested={} radius={} by {}",
                spawned,
                count,
                radius,
                player.getScoreboardName());
        return spawned;
    }

    private static int showBossCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (source.getServer().overworld() == null) {
            source.sendFailure(Component.literal("\u00a7cOverworld unavailable."));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        Evoker boss = null;
        if (EvokerBossScheduler.activeBossId != null) {
            Entity active = level.getEntity(EvokerBossScheduler.activeBossId);
            if (active instanceof Evoker evoker && evoker.isAlive()) {
                boss = evoker;
            }
        }
        if (boss == null && EvokerBossScheduler.pendingDbBossId != null) {
            UUID pendingId = EvokerBossScheduler.pendingDbBossId;
            Entity pendingEntity = level.getEntity(pendingId);
            if (pendingEntity instanceof Evoker evoker && pendingEntity.isAlive()) {
                boss = evoker;
            } else if (pendingEntity == null) {
                long secondsLeftPending = EvokerBossScheduler.despawnAtGameTime > 0L
                        ? Math.max(0L, (EvokerBossScheduler.despawnAtGameTime - level.getGameTime()) / 20L)
                        : -1L;
                BlockPos last = EvokerBossStateStore.getLastKnownBlockPos(pendingId);
                if (last != null) {
                    String base = "\u00a75[Boss] Active evoker boss (loading) - last known coords: X="
                            + last.getX() + " Y=" + last.getY() + " Z=" + last.getZ();
                    String msg = secondsLeftPending >= 0L ? base + " (" + secondsLeftPending + "s remaining)" : base;
                    source.sendSuccess(() -> Component.literal(msg), false);
                } else {
                    String msg = secondsLeftPending >= 0L
                            ? "\u00a75[Boss] Active evoker boss (location pending load) (" + secondsLeftPending + "s remaining)"
                            : "\u00a75[Boss] Active evoker boss (location pending load)";
                    source.sendSuccess(() -> Component.literal(msg), false);
                }
                return 1;
            }
        }
        if (boss == null) {
            List<Evoker> candidates = EvokerBossScheduler.findBossCandidates(level);
            if (!candidates.isEmpty()) {
                UUID preferredId = EvokerBossStateStore.getMostRecentActiveBossId(EvokerBossScheduler.activeLockMaxAgeMs());
                boss = pickObservationalBossCandidate(candidates, preferredId);
            }
        }
        if (boss == null) {
            source.sendFailure(Component.literal("\u00a7cNo active evoker boss right now."));
            return 0;
        }
        long secondsLeft = EvokerBossScheduler.despawnAtGameTime > 0L
                ? Math.max(0L, (EvokerBossScheduler.despawnAtGameTime - level.getGameTime()) / 20L)
                : -1L;
        BlockPos pos = boss.blockPosition();
        String msg = secondsLeft >= 0L
                ? "\u00a75[Boss] Active Evoker at X=" + pos.getX() + " Y=" + pos.getY() + " Z=" + pos.getZ() + " (" + secondsLeft + "s remaining)"
                : "\u00a75[Boss] Active Evoker at X=" + pos.getX() + " Y=" + pos.getY() + " Z=" + pos.getZ();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static Evoker findLookTargetBoss(ServerPlayer player, ServerLevel level, double maxDistance, double maxPerpendicular) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle().normalize();
        AABB search = player.getBoundingBox().expandTowards(lookVec.scale(maxDistance)).inflate(maxPerpendicular + 4.0);
        List<Evoker> candidates = level.getEntitiesOfClass(
                Evoker.class,
                search,
                evoker -> evoker.isAlive() && EvokerBossScheduler.isLikelyBossEvoker(evoker)
        );

        Evoker best = null;
        double bestScore = Double.MAX_VALUE;

        for (Evoker candidate : candidates) {
            Vec3 toCandidate = candidate.position().subtract(eyePos);
            double along = toCandidate.dot(lookVec);
            if (along < 0.0 || along > maxDistance) continue;

            Vec3 closestPoint = eyePos.add(lookVec.scale(along));
            double perpendicular = candidate.position().distanceTo(closestPoint);
            if (perpendicular > maxPerpendicular) continue;

            double score = along + perpendicular * 8.0;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean hasBossAdminPermission(CommandSourceStack source) {
        if (source.permissions() instanceof LevelBasedPermissionSet levelSet) {
            return levelSet.level().isEqualOrHigherThan(PermissionLevel.ADMINS);
        }
        return false;
    }

    /** Read-only boss pick for {@code /boss}; does not discard, relink, or mutate DB rows. */
    private static Evoker pickObservationalBossCandidate(List<Evoker> candidates, UUID preferredId) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (preferredId != null) {
            for (Evoker candidate : candidates) {
                if (preferredId.equals(candidate.getUUID())) {
                    return candidate;
                }
            }
        }
        return candidates.getFirst();
    }
}
