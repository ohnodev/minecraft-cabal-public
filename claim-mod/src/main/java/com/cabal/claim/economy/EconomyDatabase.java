package com.cabal.claim.economy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class EconomyDatabase {
    private final String jdbcUrl;
    private final LongAdder openAttempts = new LongAdder();
    private final LongAdder openSuccess = new LongAdder();
    private final LongAdder openFailure = new LongAdder();
    private final Map<String, LongAdder> openByLabel = new ConcurrentHashMap<>();

    public EconomyDatabase(Path serverDir) {
        this.jdbcUrl = "jdbc:sqlite:" + serverDir.resolve("economy.db").toAbsolutePath();
    }

    public void migrate() {
        try (Connection conn = open();
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                  uuid TEXT PRIMARY KEY,
                  balance REAL NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uuid TEXT NOT NULL,
                  type TEXT NOT NULL,
                  amount REAL NOT NULL,
                  source TEXT NOT NULL,
                  meta_json TEXT NOT NULL,
                  ts INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_stats (
                  uuid TEXT PRIMARY KEY,
                  kills INTEGER NOT NULL DEFAULT 0,
                  deaths INTEGER NOT NULL DEFAULT 0,
                  playtime_seconds INTEGER NOT NULL DEFAULT 0,
                  updated_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_names (
                  uuid TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS auction_listings (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  seller TEXT NOT NULL,
                  seller_name TEXT NOT NULL,
                  item_blob TEXT NOT NULL,
                  price REAL NOT NULL,
                  status TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_auction_active_price ON auction_listings(status, expires_at, price, created_at, id)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS inventory_snapshots (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  player_uuid TEXT NOT NULL,
                  reason TEXT NOT NULL,
                  inv_hash TEXT NOT NULL,
                  snapshot_blob BLOB NOT NULL,
                  ts INTEGER NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_inv_snapshots_player_ts ON inventory_snapshots(player_uuid, ts DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_inv_snapshots_player_id ON inventory_snapshots(player_uuid, id DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_backpacks (
                  player_uuid TEXT PRIMARY KEY,
                  bag_blob BLOB NOT NULL,
                  bag_hash TEXT NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS backpack_snapshots (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  player_uuid TEXT NOT NULL,
                  reason TEXT NOT NULL,
                  bag_hash TEXT NOT NULL,
                  snapshot_blob BLOB NOT NULL,
                  ts INTEGER NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_bp_snapshots_player_ts ON backpack_snapshots(player_uuid, ts DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bp_snapshots_player_id ON backpack_snapshots(player_uuid, id DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS backpack_audit_log (
                  id            INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id    TEXT NOT NULL,
                  player_uuid   TEXT NOT NULL,
                  player_name   TEXT NOT NULL,
                  action        TEXT NOT NULL,
                  slot_from     INTEGER,
                  slot_to       INTEGER,
                  item_id       TEXT,
                  item_count    INTEGER,
                  nbt_hash      TEXT,
                  before_hash   TEXT,
                  after_hash    TEXT,
                  delta_summary TEXT,
                  ts_ms         INTEGER NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_bp_audit_player_ts ON backpack_audit_log(player_uuid, ts_ms DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bp_audit_session ON backpack_audit_log(session_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bp_audit_ts_ms ON backpack_audit_log(ts_ms)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to migrate economy database", e);
        }
    }

    public Connection open() throws SQLException {
        return open("unknown");
    }

    public Connection open(String label) throws SQLException {
        openAttempts.increment();
        openByLabel.computeIfAbsent(sanitizeLabel(label), k -> new LongAdder()).increment();
        try {
            Connection conn = DriverManager.getConnection(jdbcUrl);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
            }
            openSuccess.increment();
            return conn;
        } catch (SQLException e) {
            openFailure.increment();
            throw e;
        }
    }

    public record DbMetrics(long openAttempts, long openSuccess, long openFailure, Map<String, Long> openByLabel) {}

    public DbMetrics snapshotAndResetMetrics() {
        Map<String, Long> byLabel = new HashMap<>();
        for (Map.Entry<String, LongAdder> entry : openByLabel.entrySet()) {
            long count = entry.getValue().sumThenReset();
            if (count > 0) {
                byLabel.put(entry.getKey(), count);
            } else {
                openByLabel.remove(entry.getKey(), entry.getValue());
            }
        }
        return new DbMetrics(
            openAttempts.sumThenReset(),
            openSuccess.sumThenReset(),
            openFailure.sumThenReset(),
            byLabel
        );
    }

    public record TopBalance(UUID uuid, double balance) {}

    public List<TopBalance> topBalances(int limit) {
        List<TopBalance> out = new ArrayList<>();
        String sql = "SELECT uuid, balance FROM accounts ORDER BY balance DESC LIMIT ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TopBalance(UUID.fromString(rs.getString(1)), rs.getDouble(2)));
                }
            }
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] topBalances failed: " + e.getMessage());
        }
        return out;
    }

    public record LeaderboardRow(UUID uuid, long value) {}

    public record KdRow(UUID uuid, int kills, int deaths, double kd) {}

    public void upsertPlayerName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        String sql = """
            INSERT INTO player_names(uuid, name, updated_at)
            VALUES(?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
              name = excluded.name,
              updated_at = excluded.updated_at
            """;
        try (Connection conn = open("upsertPlayerName");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, nowTs());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] upsertPlayerName failed: " + e.getMessage());
        }
    }

    public Map<UUID, String> playerNamesByIds(List<UUID> ids, long maxAgeSeconds) {
        Map<UUID, String> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) return out;

        List<UUID> deduped = ids.stream().distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(deduped.size(), "?"));
        long cutoffTs = nowTs() - Math.max(0, maxAgeSeconds);
        String sql = "SELECT uuid, name FROM player_names WHERE updated_at >= ? AND uuid IN (" + placeholders + ")";
        try (Connection conn = open("playerNamesByIds");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoffTs);
            for (int i = 0; i < deduped.size(); i++) {
                ps.setString(i + 2, deduped.get(i).toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] playerNamesByIds failed: " + e.getMessage());
        }
        return out;
    }

    public void purgeStalePlayerNames(long maxAgeSeconds) {
        long cutoffTs = nowTs() - Math.max(0, maxAgeSeconds);
        String sql = "DELETE FROM player_names WHERE updated_at < ?";
        try (Connection conn = open("purgeStalePlayerNames");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoffTs);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] purgeStalePlayerNames failed: " + e.getMessage());
        }
    }

    public List<LeaderboardRow> topPlaytime(int limit) {
        return queryLeaderboard("SELECT uuid, playtime_seconds FROM player_stats ORDER BY playtime_seconds DESC LIMIT ?", limit, "topPlaytime");
    }

    public List<LeaderboardRow> topKills(int limit) {
        return queryLeaderboard("SELECT uuid, kills FROM player_stats ORDER BY kills DESC LIMIT ?", limit, "topKills");
    }

    public List<LeaderboardRow> topDeaths(int limit) {
        return queryLeaderboard("SELECT uuid, deaths FROM player_stats ORDER BY deaths DESC LIMIT ?", limit, "topDeaths");
    }

    public List<KdRow> topKd(int limit) {
        List<KdRow> out = new ArrayList<>();
        String sql = "SELECT uuid, kills, deaths FROM player_stats WHERE kills > 0 ORDER BY CASE WHEN deaths = 0 THEN kills * 1.0 ELSE CAST(kills AS REAL) / deaths END DESC LIMIT ?";
        try (Connection conn = open("topKd");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int kills = rs.getInt(2);
                    int deaths = rs.getInt(3);
                    double kd = deaths == 0 ? kills : (double) kills / deaths;
                    out.add(new KdRow(UUID.fromString(rs.getString(1)), kills, deaths, kd));
                }
            }
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] topKd failed: " + e.getMessage());
        }
        return out;
    }

    private List<LeaderboardRow> queryLeaderboard(String sql, int limit, String label) {
        List<LeaderboardRow> out = new ArrayList<>();
        try (Connection conn = open(label);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LeaderboardRow(UUID.fromString(rs.getString(1)), rs.getLong(2)));
                }
            }
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] " + label + " failed: " + e.getMessage());
        }
        return out;
    }

    public static long nowTs() {
        return Instant.now().getEpochSecond();
    }

    private static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) return "unknown";
        return label.replaceAll("[,:]+", "_");
    }
}
