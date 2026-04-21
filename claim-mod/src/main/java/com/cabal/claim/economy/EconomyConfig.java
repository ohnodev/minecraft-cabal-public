package com.cabal.claim.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EconomyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public boolean phase1HudEnabled = true;
    public int hudUpdateTicks = 20;
    public boolean hudShowTps = true;
    public boolean hudDebugLogs = false;
    public boolean phase2EconomyEnabled = true;
    public boolean phase4AuctionEnabled = true;
    public double blockBreakReward = 0;
    public double killReward = 0;
    public boolean timeRewardEnabled = true;
    public double timeRewardPerMinute = 0.10;
    public int timeRewardIntervalSeconds = 60;
    public int timeRewardProcessIntervalSeconds = 30;
    public double timeRewardDailyCap = 60.0;
    public boolean timeRewardRequireActivity = true;
    public int timeRewardAfkWindowSeconds = 180;
    public boolean serverBuyEnabled = true;
    public boolean serverSellEnabled = true;
    public double serverSellMultiplier = 20.0;
    public double serverBuyDiamond = 6.0;
    public double serverBuyRawIron = 1.0;
    public double serverBuyRawGold = 2.0;
    public double serverBuyNetheriteScrap = 12.0;
    public double serverBuyFireworksStack = 2.0;
    public double serverBuyExpansionSlot = 100.0;
    public double serverSellFireworksStack = 50.0;
    public double serverSellExpansionSlot = 1000.0;
    public boolean ramLoggingEnabled = true;
    public int ramLoggingIntervalSeconds = 60;
    public boolean inventoryHistoryEnabled = true;
    public int inventorySnapshotIntervalSeconds = 60;
    public int inventoryMaxSnapshotsPerPlayer = 60;
    public boolean snapshotOnJoin = true;
    public boolean snapshotOnLeave = true;
    public boolean snapshotOnDeath = true;
    public boolean backpackEnabled = true;
    public int backpackSnapshotMaxPerPlayer = 100;
    public boolean backpackAuditEnabled = true;
    public int backpackAuditRetentionDays = 14;

    public static EconomyConfig loadOrCreate(Path serverDir) {
        Path path = serverDir.resolve("economy-config.json");
        if (!Files.exists(path)) {
            EconomyConfig cfg = defaults();
            cfg.save(path);
            return cfg;
        }
        try {
            EconomyConfig cfg = GSON.fromJson(Files.readString(path), EconomyConfig.class);
            if (cfg == null) cfg = defaults();
            cfg.normalize();
            cfg.save(path);
            return cfg;
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("[CabalEconomy] Failed to load economy config from " + path + ", using defaults: " + e.getMessage());
            EconomyConfig cfg = defaults();
            cfg.save(path);
            return cfg;
        }
    }

    private static EconomyConfig defaults() {
        EconomyConfig cfg = new EconomyConfig();
        cfg.normalize();
        return cfg;
    }

    private void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[CabalEconomy] Failed to save economy config: " + e.getMessage());
        }
    }

    private void normalize() {
        if (hudUpdateTicks < 20) hudUpdateTicks = 20;
        if (hudUpdateTicks > 100) hudUpdateTicks = 100;
        if (timeRewardPerMinute < 0) timeRewardPerMinute = 0;
        if (timeRewardIntervalSeconds < 10) timeRewardIntervalSeconds = 10;
        if (timeRewardProcessIntervalSeconds < 5) timeRewardProcessIntervalSeconds = 5;
        if (timeRewardDailyCap < 0) timeRewardDailyCap = 0;
        if (timeRewardAfkWindowSeconds < 30) timeRewardAfkWindowSeconds = 30;
        if (serverSellMultiplier < 1.0) serverSellMultiplier = 1.0;
        if (serverBuyDiamond < 0) serverBuyDiamond = 0;
        if (serverBuyRawIron < 0) serverBuyRawIron = 0;
        if (serverBuyRawGold < 0) serverBuyRawGold = 0;
        if (serverBuyNetheriteScrap < 0) serverBuyNetheriteScrap = 0;
        if (serverBuyFireworksStack < 0) serverBuyFireworksStack = 0;
        if (serverBuyExpansionSlot < 0) serverBuyExpansionSlot = 0;
        if (serverSellFireworksStack < 0) serverSellFireworksStack = 0;
        if (serverSellExpansionSlot < 0) serverSellExpansionSlot = 0;
        // Prevent no-risk arbitrage from misconfigured stack pricing.
        if (serverSellFireworksStack < serverBuyFireworksStack) {
            serverSellFireworksStack = serverBuyFireworksStack;
        }
        // Prevent no-risk arbitrage from misconfigured expansion slot pricing.
        if (serverSellExpansionSlot < serverBuyExpansionSlot) {
            serverSellExpansionSlot = serverBuyExpansionSlot;
        }
        if (ramLoggingIntervalSeconds < 10) ramLoggingIntervalSeconds = 10;
        if (inventorySnapshotIntervalSeconds < 10) inventorySnapshotIntervalSeconds = 10;
        if (inventoryMaxSnapshotsPerPlayer < 5) inventoryMaxSnapshotsPerPlayer = 5;
        if (inventoryMaxSnapshotsPerPlayer > 500) inventoryMaxSnapshotsPerPlayer = 500;
        if (backpackSnapshotMaxPerPlayer < 5) backpackSnapshotMaxPerPlayer = 5;
        if (backpackSnapshotMaxPerPlayer > 500) backpackSnapshotMaxPerPlayer = 500;
        if (backpackAuditRetentionDays < 1) backpackAuditRetentionDays = 1;
        if (backpackAuditRetentionDays > 90) backpackAuditRetentionDays = 90;
    }

}
