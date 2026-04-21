package com.cabal.claim.economy;

import com.cabal.claim.config.CabalConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

public final class EconomyModule {
    private final EconomyConfig config;
    private final CabalConfig cabalConfig;
    private final EconomyDatabase db;
    private final EconomyDbWriter dbWriter;
    private final EconomyService economyService;
    private final PlayerStatsService playerStatsService;
    private final AuctionService auctionService;
    private final TimeRewardService timeRewardService;
    private final DiagnosticsService diagnosticsService;
    private final InventoryHistoryService inventoryHistoryService;
    private final BackpackService backpackService;
    private final BackpackAuditService backpackAuditService;
    private final PlayerLocationSampler playerLocationSampler;
    private final HudService hudService;
    private final EconomyCommands economyCommands;
    private final AuctionCommand auctionCommand;
    private final InventoryHistoryCommand inventoryHistoryCommand;
    private final BackpackCommand backpackCommand;
    private final LeaderboardCommand leaderboardCommand;

    public EconomyModule(Path serverDir) {
        this.config = EconomyConfig.loadOrCreate(serverDir);
        this.cabalConfig = CabalConfig.loadOrDefault(serverDir);
        this.db = new EconomyDatabase(serverDir);
        this.db.migrate();
        this.dbWriter = new EconomyDbWriter();
        this.economyService = new EconomyService(db, dbWriter);
        this.playerStatsService = new PlayerStatsService(db, dbWriter);
        this.diagnosticsService = new DiagnosticsService(config, db, dbWriter);
        this.inventoryHistoryService = new InventoryHistoryService(db, dbWriter, config);
        this.backpackService = new BackpackService(db, dbWriter, config);
        this.backpackAuditService = new BackpackAuditService(db, dbWriter, config);
        this.auctionService = new AuctionService(db, economyService, config);
        this.timeRewardService = new TimeRewardService(economyService, config);
        this.playerLocationSampler = new PlayerLocationSampler(serverDir);
        this.hudService = new HudService(economyService, playerStatsService, cabalConfig);
        this.economyCommands = new EconomyCommands(economyService, db, hudService, timeRewardService, auctionService, config);
        this.auctionCommand = new AuctionCommand(auctionService);
        this.inventoryHistoryCommand = new InventoryHistoryCommand(inventoryHistoryService);
        this.backpackCommand = new BackpackCommand(backpackService, backpackAuditService, config);
        this.leaderboardCommand = new LeaderboardCommand(db);
    }

    public EconomyConfig config() {
        return config;
    }

    public CabalConfig cabalConfig() {
        return cabalConfig;
    }

    public EconomyService economyService() {
        return economyService;
    }

    public EconomyCommands economyCommands() {
        return economyCommands;
    }

    public AuctionCommand auctionCommand() {
        return auctionCommand;
    }

    public InventoryHistoryCommand inventoryHistoryCommand() {
        return inventoryHistoryCommand;
    }

    public BackpackCommand backpackCommand() {
        return backpackCommand;
    }

    public LeaderboardCommand leaderboardCommand() {
        return leaderboardCommand;
    }

    public void purgeStaleScoreboard(MinecraftServer server) {
        hudService.purgeGlobalScoreboard(server);
    }

    public void shutdown() {
        playerLocationSampler.shutdown();
        dbWriter.shutdown();
        playerStatsService.flushAllSync();
    }

    private long lastAuditPurgeTick = 0;
    private long lastNamePurgeTick = 0;
    private static final long AUDIT_PURGE_INTERVAL_TICKS = 72_000; // ~1 hour at 20 TPS
    private static final long PLAYER_NAME_RETENTION_SECONDS = 7L * 24L * 60L * 60L;

    public void onServerTick(MinecraftServer server) {
        if (!config.enabled) return;
        diagnosticsService.tick(server);
        var players = server.getPlayerList().getPlayers();
        playerStatsService.tickPlaytime(players.stream().map(ServerPlayer::getUUID).collect(Collectors.toList()), server.getTickCount());
        timeRewardService.tick(server);
        inventoryHistoryService.tickPeriodic(server);
        playerLocationSampler.tick(server);
        if (config.phase1HudEnabled) {
            hudService.tick(server, Math.max(20, config.hudUpdateTicks), config.hudShowTps, config.hudDebugLogs);
        }
        long tick = server.getTickCount();
        if (tick - lastAuditPurgeTick >= AUDIT_PURGE_INTERVAL_TICKS) {
            lastAuditPurgeTick = tick;
            backpackAuditService.purgeOldEntries();
        }
        if (tick - lastNamePurgeTick >= AUDIT_PURGE_INTERVAL_TICKS) {
            lastNamePurgeTick = tick;
            try {
                dbWriter.runAsync(() -> db.purgeStalePlayerNames(PLAYER_NAME_RETENTION_SECONDS));
            } catch (RejectedExecutionException ignored) {
                // Best-effort maintenance; next interval will retry.
            }
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!config.enabled) return;
        UUID playerId = player.getUUID();
        String playerName = player.getGameProfile().name();
        economyService.ensureAccount(playerId);
        playerStatsService.ensurePlayer(playerId);
        try {
            dbWriter.runAsync(() -> db.upsertPlayerName(playerId, playerName));
        } catch (RejectedExecutionException ignored) {
            // Best effort only; leaderboard falls back to cache/UUID if this write is skipped.
        }
        timeRewardService.onPlayerJoin(playerId, player.level().getServer().getTickCount());
        inventoryHistoryService.onPlayerJoin(player);
        if (config.phase1HudEnabled) {
            hudService.onPlayerJoin(player);
        }
    }

    public void onPlayerLeave(ServerPlayer player) {
        if (config.enabled) {
            inventoryHistoryService.onPlayerLeave(player);
        }
        onPlayerLeave(player.getUUID());
    }

    public void onPlayerLeave(UUID playerId) {
        playerStatsService.flushPlayerAsync(playerId);
        timeRewardService.onPlayerLeave(playerId);
        hudService.onPlayerLeave(playerId);
    }

    public void onBlockBreakReward(ServerPlayer player) {
        if (!config.enabled) return;
        timeRewardService.recordActivity(player.getUUID(), player.level().getServer().getTickCount());
        if (!config.phase2EconomyEnabled || config.blockBreakReward <= 0) return;
        Map<String, Object> meta = new HashMap<>();
        meta.put("event", "block_break");
        economyService.addBalanceAsync(player.getUUID(), config.blockBreakReward, "block_break_reward", meta);
    }

    public void onAfterDamage(Entity victim, DamageSource source) {
        if (!config.enabled) return;
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer killer) {
            timeRewardService.recordActivity(killer.getUUID(), killer.level().getServer().getTickCount());
        }
        if (!(victim instanceof ServerPlayer hurtPlayer)) return;
        timeRewardService.recordActivity(hurtPlayer.getUUID(), hurtPlayer.level().getServer().getTickCount());
    }

    public void onPlayerDeath(ServerPlayer deadPlayer, DamageSource source) {
        if (!config.enabled) return;
        inventoryHistoryService.onPlayerDeath(deadPlayer);
        playerStatsService.incrementDeath(deadPlayer.getUUID());
        playerStatsService.flushPlayerAsync(deadPlayer.getUUID());
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer killer && !killer.getUUID().equals(deadPlayer.getUUID())) {
            playerStatsService.incrementKill(killer.getUUID());
            playerStatsService.flushPlayerAsync(killer.getUUID());
            if (config.phase2EconomyEnabled && config.killReward > 0) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("event", "kill_reward");
                meta.put("victim", deadPlayer.getUUID().toString());
                economyService.addBalanceAsync(killer.getUUID(), config.killReward, "kill_reward", meta);
            }
        }
    }

}
