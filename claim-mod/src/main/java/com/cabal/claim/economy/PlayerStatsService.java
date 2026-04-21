package com.cabal.claim.economy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStatsService {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/PlayerStats");
    private static final long PLAYTIME_UPDATE_INTERVAL_TICKS = 20;
    private static final long PERSIST_INTERVAL_TICKS = 20 * 60;

    public record Snapshot(long playtimeSeconds, int kills, int deaths) {
        public double kdr() {
            return deaths == 0 ? kills : (double) kills / (double) deaths;
        }
    }

    private final EconomyDatabase db;
    private final EconomyDbWriter dbWriter;
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    private long lastPlaytimeTick = 0;
    private long lastPersistTick = 0;

    public PlayerStatsService(EconomyDatabase db, EconomyDbWriter dbWriter) {
        this.db = db;
        this.dbWriter = dbWriter;
    }

    public Snapshot get(UUID playerId) {
        return cache.computeIfAbsent(playerId, this::loadFromDb);
    }

    public Snapshot ensurePlayer(UUID playerId) {
        Snapshot s = get(playerId);
        save(playerId, s);
        return s;
    }

    public void incrementKill(UUID playerId) {
        Snapshot updated = cache.compute(playerId, (id, old) -> {
            Snapshot current = old != null ? old : loadFromDb(id);
            return new Snapshot(current.playtimeSeconds(), current.kills() + 1, current.deaths());
        });
        if (updated != null) {
            dirtyPlayers.add(playerId);
        }
    }

    public void incrementDeath(UUID playerId) {
        Snapshot updated = cache.compute(playerId, (id, old) -> {
            Snapshot current = old != null ? old : loadFromDb(id);
            return new Snapshot(current.playtimeSeconds(), current.kills(), current.deaths() + 1);
        });
        if (updated != null) {
            dirtyPlayers.add(playerId);
        }
    }

    public void tickPlaytime(Iterable<UUID> onlinePlayers, long gameTick) {
        if (gameTick - lastPlaytimeTick < PLAYTIME_UPDATE_INTERVAL_TICKS) return;
        lastPlaytimeTick = gameTick;
        for (UUID playerId : onlinePlayers) {
            Snapshot s = get(playerId);
            Snapshot updated = new Snapshot(s.playtimeSeconds() + 1, s.kills(), s.deaths());
            cache.put(playerId, updated);
            dirtyPlayers.add(playerId);
        }
        if (gameTick - lastPersistTick >= PERSIST_INTERVAL_TICKS) {
            flushDirtyAsync();
            lastPersistTick = gameTick;
        }
    }

    public void flushPlayerAsync(UUID playerId) {
        Snapshot snapshot = cache.get(playerId);
        if (snapshot == null) return;
        try {
            dbWriter.runAsync(() -> saveToDb(playerId, snapshot));
            dirtyPlayers.remove(playerId);
        } catch (RuntimeException e) {
            dirtyPlayers.add(playerId);
        }
    }

    public void flushAllSync() {
        for (UUID playerId : Set.copyOf(dirtyPlayers)) {
            Snapshot snapshot = cache.get(playerId);
            if (snapshot != null) {
                saveToDb(playerId, snapshot);
            }
        }
        dirtyPlayers.clear();
    }

    private Snapshot loadFromDb(UUID playerId) {
        String sql = "SELECT playtime_seconds, kills, deaths FROM player_stats WHERE uuid = ?";
        try (Connection conn = db.open("stats");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Snapshot(rs.getLong(1), rs.getInt(2), rs.getInt(3));
                }
            }
            return new Snapshot(0, 0, 0);
        } catch (SQLException e) {
            LOGGER.error("Failed to load player stats for {}", playerId, e);
            throw new RuntimeException("Failed to load player stats for " + playerId, e);
        }
    }

    private void save(UUID playerId, Snapshot s) {
        cache.put(playerId, s);
        try {
            dbWriter.runAsync(() -> saveToDb(playerId, s));
            dirtyPlayers.remove(playerId);
        } catch (RuntimeException e) {
            dirtyPlayers.add(playerId);
        }
    }

    private void flushDirtyAsync() {
        if (dirtyPlayers.isEmpty()) return;
        for (UUID playerId : Set.copyOf(dirtyPlayers)) {
            Snapshot snapshot = cache.get(playerId);
            if (snapshot == null) {
                dirtyPlayers.remove(playerId);
                continue;
            }
            try {
                dbWriter.runAsync(() -> saveToDb(playerId, snapshot));
                dirtyPlayers.remove(playerId);
            } catch (RuntimeException e) {
                dirtyPlayers.add(playerId);
            }
        }
    }

    private void saveToDb(UUID playerId, Snapshot s) {
        String sql = """
                INSERT INTO player_stats(uuid, kills, deaths, playtime_seconds, updated_at)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  kills=excluded.kills,
                  deaths=excluded.deaths,
                  playtime_seconds=excluded.playtime_seconds,
                  updated_at=excluded.updated_at
                """;
        try (Connection conn = db.open("stats");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, s.kills());
            ps.setInt(3, s.deaths());
            ps.setLong(4, s.playtimeSeconds());
            ps.setLong(5, EconomyDatabase.nowTs());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to save player stats for {}", playerId, e);
            throw new RuntimeException("Failed to save player stats for " + playerId, e);
        }
    }
}
