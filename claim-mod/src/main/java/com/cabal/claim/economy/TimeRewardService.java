package com.cabal.claim.economy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TimeRewardService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimeRewardService.class);
    private final EconomyService economyService;
    private final EconomyConfig config;

    private final Map<UUID, Long> lastActivityTick = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> lastPosition = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> activeSecondsAccum = new ConcurrentHashMap<>();
    private final Map<UUID, Double> mintedToday = new ConcurrentHashMap<>();
    private final Map<UUID, Double> pendingMintedToday = new ConcurrentHashMap<>();
    private final Map<UUID, Long> dailyResetDay = new ConcurrentHashMap<>();

    private long lastTickCheck = 0;

    private double mintedThisHour = 0;
    private double mintedThisDay = 0;
    private long currentHourEpoch = currentHourBucket();
    private long currentDayEpoch = currentDayBucket();

    public TimeRewardService(EconomyService economyService, EconomyConfig config) {
        this.economyService = economyService;
        this.config = config;
    }

    public void tick(MinecraftServer server) {
        if (!config.timeRewardEnabled) return;

        long gameTick = server.getTickCount();
        if (lastTickCheck == 0) {
            lastTickCheck = gameTick;
            return;
        }
        long processTicks = Math.max(5, config.timeRewardProcessIntervalSeconds) * 20L;
        if (gameTick - lastTickCheck < processTicks) return;
        int elapsedSeconds = Math.max(1, (int) ((gameTick - lastTickCheck) / 20L));
        lastTickCheck = gameTick;

        var players = server.getPlayerList().getPlayers();
        Map<UUID, Double> rewardsToCredit = new HashMap<>();
        Map<UUID, Map<String, Object>> rewardMetaByPlayer = new HashMap<>();
        for (ServerPlayer player : players) {
            UUID uuid = player.getUUID();
            detectMovement(player, gameTick);

            if (config.timeRewardRequireActivity && isAfk(uuid, gameTick)) {
                continue;
            }

            resetDailyIfNeeded(uuid);
            double todayMinted = mintedToday.getOrDefault(uuid, 0.0) + pendingMintedToday.getOrDefault(uuid, 0.0);
            if (config.timeRewardDailyCap > 0 && todayMinted >= config.timeRewardDailyCap) {
                continue;
            }

            int seconds = activeSecondsAccum.merge(uuid, elapsedSeconds, Integer::sum);
            if (seconds >= config.timeRewardIntervalSeconds) {
                int remainder = seconds % config.timeRewardIntervalSeconds;
                int completedWindows = seconds / config.timeRewardIntervalSeconds;
                activeSecondsAccum.put(uuid, remainder);
                double reward = (config.timeRewardPerMinute * (config.timeRewardIntervalSeconds / 60.0)) * completedWindows;
                if (config.timeRewardDailyCap > 0) {
                    reward = Math.min(reward, config.timeRewardDailyCap - todayMinted);
                }
                if (reward > 0) {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("event", "time_play_reward");
                    meta.put("interval_seconds", config.timeRewardIntervalSeconds);
                    meta.put("completed_windows", completedWindows);
                    rewardsToCredit.put(uuid, reward);
                    rewardMetaByPlayer.put(uuid, Map.copyOf(meta));
                }
            }
        }

        if (!rewardsToCredit.isEmpty()) {
            for (Map.Entry<UUID, Double> entry : rewardsToCredit.entrySet()) {
                pendingMintedToday.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
            final Map<UUID, Double> rewardsFinal = Map.copyOf(rewardsToCredit);
            final Map<UUID, Map<String, Object>> rewardMetaFinal = Map.copyOf(rewardMetaByPlayer);
            economyService.addBalancesBatchAsync(rewardsFinal, "time_play_reward", rewardMetaFinal)
                .whenComplete((outcomes, err) -> server.execute(() -> {
                    for (Map.Entry<UUID, Double> entry : rewardsFinal.entrySet()) {
                        UUID uuid = entry.getKey();
                        double reward = entry.getValue();
                        releasePendingMint(uuid, reward);
                        boolean ok = err == null && outcomes != null && Boolean.TRUE.equals(outcomes.get(uuid));
                        if (!ok) {
                            LOGGER.warn("[CabalEconomy] time reward credit failed uuid={} reward={} meta={}",
                                uuid, String.format("%.2f", reward), rewardMetaFinal.get(uuid));
                            continue;
                        }
                        mintedToday.merge(uuid, reward, Double::sum);
                        mintedThisHour += reward;
                        mintedThisDay += reward;
                    }
                }));
        }

        logIfBucketRolled();
    }

    public void recordActivity(UUID playerId, long gameTick) {
        lastActivityTick.put(playerId, gameTick);
    }

    public void onPlayerJoin(UUID playerId, long gameTick) {
        lastActivityTick.put(playerId, gameTick);
        activeSecondsAccum.putIfAbsent(playerId, 0);
    }

    public void onPlayerLeave(UUID playerId) {
        lastActivityTick.remove(playerId);
        lastPosition.remove(playerId);
        activeSecondsAccum.remove(playerId);
    }

    public double getMintedThisHour() {
        return mintedThisHour;
    }

    public double getMintedThisDay() {
        return mintedThisDay;
    }

    public double getPlayerMintedToday(UUID playerId) {
        resetDailyIfNeeded(playerId);
        return mintedToday.getOrDefault(playerId, 0.0) + pendingMintedToday.getOrDefault(playerId, 0.0);
    }

    private void detectMovement(ServerPlayer player, long gameTick) {
        UUID uuid = player.getUUID();
        long bx = (long) (player.getX() * 10);
        long by = (long) (player.getY() * 10);
        long bz = (long) (player.getZ() * 10);

        long[] prev = lastPosition.get(uuid);
        if (prev == null || prev[0] != bx || prev[1] != by || prev[2] != bz) {
            lastActivityTick.put(uuid, gameTick);
            lastPosition.put(uuid, new long[]{bx, by, bz});
        }
    }

    private boolean isAfk(UUID playerId, long gameTick) {
        Long lastActive = lastActivityTick.get(playerId);
        if (lastActive == null) return true;
        long afkThresholdTicks = config.timeRewardAfkWindowSeconds * 20L;
        return gameTick - lastActive > afkThresholdTicks;
    }

    private void resetDailyIfNeeded(UUID playerId) {
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        Long playerDay = dailyResetDay.get(playerId);
        if (playerDay == null || playerDay != today) {
            dailyResetDay.put(playerId, today);
            mintedToday.put(playerId, 0.0);
            pendingMintedToday.put(playerId, 0.0);
        }
    }

    private void logIfBucketRolled() {
        long nowHour = currentHourBucket();
        long nowDay = currentDayBucket();

        if (nowHour != currentHourEpoch) {
            if (mintedThisHour > 0) {
                LOGGER.info("[CabalEconomy] Time rewards minted last hour: ${}",
                    String.format("%.2f", mintedThisHour));
            }
            mintedThisHour = 0;
            currentHourEpoch = nowHour;
        }

        if (nowDay != currentDayEpoch) {
            if (mintedThisDay > 0) {
                LOGGER.info("[CabalEconomy] Time rewards minted last day: ${}",
                    String.format("%.2f", mintedThisDay));
            }
            mintedThisDay = 0;
            mintedToday.clear();
            pendingMintedToday.clear();
            dailyResetDay.clear();
            currentDayEpoch = nowDay;
        }
    }

    private void releasePendingMint(UUID playerId, double amount) {
        pendingMintedToday.compute(playerId, (id, old) -> {
            double current = old != null ? old : 0.0;
            double updated = current - amount;
            if (updated <= 0.000001D) return null;
            return updated;
        });
    }

    private static long currentHourBucket() {
        return System.currentTimeMillis() / (3600_000L);
    }

    private static long currentDayBucket() {
        return System.currentTimeMillis() / (86_400_000L);
    }
}
