package com.cabal.mobs.evokerboss;

import com.cabal.mobs.CabalMobsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.illager.Evoker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EvokerBossStateStore {
    private static final String DB_URL = "jdbc:sqlite:economy.db";
    private static final long POSITION_UPDATE_INTERVAL_MS = 5_000L;
    private static final Object DB_LOCK = new Object();
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cabal-evoker-boss-db");
        thread.setDaemon(true);
        return thread;
    });

    private static Connection connection;
    private static boolean schemaReady;
    private static long lastPositionWriteAtMs;
    private static UUID lastPositionBossId;

    private EvokerBossStateStore() {}

    static void initialize() {
        synchronized (DB_LOCK) {
            ensureSchemaLocked();
        }
    }

    static void close() {
        DB_EXECUTOR.shutdownNow();
        synchronized (DB_LOCK) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // best effort close only
                } finally {
                    connection = null;
                    schemaReady = false;
                }
            }
        }
    }

    static void recordSpawn(Evoker evoker, BlockPos spawnPos) {
        UUID bossId = evoker.getUUID();
        String dimensionId = dimensionId(evoker);
        double spawnX = spawnPos.getX() + 0.5;
        double spawnY = spawnPos.getY();
        double spawnZ = spawnPos.getZ() + 0.5;
        double lastX = evoker.getX();
        double lastY = evoker.getY();
        double lastZ = evoker.getZ();
        long nowMs = System.currentTimeMillis();

        final String sql = """
                INSERT INTO cabal_evoker_boss_events (
                    boss_uuid, status, spawned_at_ms, spawn_x, spawn_y, spawn_z, spawn_dimension,
                    last_x, last_y, last_z, last_dimension, last_update_ms,
                    despawned_at_ms, defeated_at_ms, defeated_by_uuid, defeated_by_name, end_reason
                ) VALUES (?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL)
                ON CONFLICT(boss_uuid) DO UPDATE SET
                    status='ACTIVE',
                    spawned_at_ms=excluded.spawned_at_ms,
                    spawn_x=excluded.spawn_x,
                    spawn_y=excluded.spawn_y,
                    spawn_z=excluded.spawn_z,
                    spawn_dimension=excluded.spawn_dimension,
                    last_x=excluded.last_x,
                    last_y=excluded.last_y,
                    last_z=excluded.last_z,
                    last_dimension=excluded.last_dimension,
                    last_update_ms=excluded.last_update_ms,
                    despawned_at_ms=NULL,
                    defeated_at_ms=NULL,
                    defeated_by_uuid=NULL,
                    defeated_by_name=NULL,
                    end_reason=NULL
                """;
        queueWrite(() -> {
            synchronized (DB_LOCK) {
                if (!ensureSchemaLocked()) return;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, bossId.toString());
                    ps.setLong(2, nowMs);
                    ps.setDouble(3, spawnX);
                    ps.setDouble(4, spawnY);
                    ps.setDouble(5, spawnZ);
                    ps.setString(6, dimensionId);
                    ps.setDouble(7, lastX);
                    ps.setDouble(8, lastY);
                    ps.setDouble(9, lastZ);
                    ps.setString(10, dimensionId);
                    ps.setLong(11, nowMs);
                    ps.executeUpdate();
                    lastPositionBossId = bossId;
                    lastPositionWriteAtMs = nowMs;
                } catch (SQLException e) {
                    CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to persist evoker boss spawn state", e);
                }
            }
        });
    }

    static void updateActivePosition(Evoker evoker, boolean force) {
        long nowMs = System.currentTimeMillis();
        UUID bossId = evoker.getUUID();
        synchronized (DB_LOCK) {
            if (!force && lastPositionBossId != null && lastPositionBossId.equals(bossId)
                    && nowMs - lastPositionWriteAtMs < POSITION_UPDATE_INTERVAL_MS) {
                return;
            }
        }
        String dimensionId = dimensionId(evoker);
        double posX = evoker.getX();
        double posY = evoker.getY();
        double posZ = evoker.getZ();

        final String sql = """
                UPDATE cabal_evoker_boss_events
                SET last_x=?, last_y=?, last_z=?, last_dimension=?, last_update_ms=?
                WHERE boss_uuid=? AND status='ACTIVE'
                """;
        queueWrite(() -> {
            synchronized (DB_LOCK) {
                if (!ensureSchemaLocked()) return;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setDouble(1, posX);
                    ps.setDouble(2, posY);
                    ps.setDouble(3, posZ);
                    ps.setString(4, dimensionId);
                    ps.setLong(5, nowMs);
                    ps.setString(6, bossId.toString());
                    ps.executeUpdate();
                    lastPositionBossId = bossId;
                    lastPositionWriteAtMs = nowMs;
                } catch (SQLException e) {
                    CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to persist evoker boss position", e);
                }
            }
        });
    }

    static void markDespawned(UUID bossId, String reason) {
        finishEvent(bossId, "DESPAWNED", reason, null, null);
    }

    static void markPurged(UUID bossId, String reason) {
        finishEvent(bossId, "PURGED", reason, null, null);
    }

    static void markDefeated(UUID bossId, ServerPlayer killer) {
        String killerUuid = killer == null ? null : killer.getUUID().toString();
        String killerName = killer == null ? null : killer.getGameProfile().name();
        finishEvent(bossId, "DEFEATED", "killed", killerUuid, killerName);
    }

    /**
     * Last persisted position for an {@code ACTIVE} boss row: {@code last_*} when present, otherwise spawn column.
     * Used when the entity is not yet loaded (e.g. {@code /boss} while {@code pendingDbBossId} is set).
     */
    static BlockPos getLastKnownBlockPos(UUID bossId) {
        if (bossId == null) {
            return null;
        }
        synchronized (DB_LOCK) {
            if (!ensureSchemaLocked()) {
                return null;
            }
            final String sql = """
                    SELECT last_x, last_y, last_z, spawn_x, spawn_y, spawn_z
                    FROM cabal_evoker_boss_events
                    WHERE boss_uuid=? AND status='ACTIVE'
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, bossId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    double x = rs.getDouble("last_x");
                    if (rs.wasNull()) {
                        x = rs.getDouble("spawn_x");
                    }
                    double y = rs.getDouble("last_y");
                    if (rs.wasNull()) {
                        y = rs.getDouble("spawn_y");
                    }
                    double z = rs.getDouble("last_z");
                    if (rs.wasNull()) {
                        z = rs.getDouble("spawn_z");
                    }
                    return BlockPos.containing(x, y, z);
                }
            } catch (Exception e) {
                CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to read last-known evoker boss position for {}", bossId, e);
                return null;
            }
        }
    }

    static UUID getMostRecentActiveBossId(long maxAgeMs) {
        synchronized (DB_LOCK) {
            if (!ensureSchemaLocked()) return null;
        final String sql = """
                SELECT boss_uuid, last_update_ms, spawned_at_ms
                FROM cabal_evoker_boss_events
                WHERE status='ACTIVE'
                ORDER BY COALESCE(last_update_ms, spawned_at_ms) DESC
                LIMIT 1
                """;
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            long lastUpdate = rs.getLong("last_update_ms");
            if (rs.wasNull()) {
                lastUpdate = rs.getLong("spawned_at_ms");
            }
            if (maxAgeMs > 0L && nowMs - lastUpdate > maxAgeMs) {
                return null;
            }
            return UUID.fromString(rs.getString("boss_uuid"));
        } catch (Exception e) {
            CabalMobsMod.LOGGER.warn("[CabalMobs] Failed reading active evoker boss state", e);
            return null;
        }
        }
    }

    static void markAllActivePurgedExcept(UUID keepBossId, String reason) {
        long nowMs = System.currentTimeMillis();
        final String sql = """
                UPDATE cabal_evoker_boss_events
                SET status='PURGED',
                    last_update_ms=?,
                    despawned_at_ms=COALESCE(despawned_at_ms, ?),
                    end_reason=?
                WHERE status='ACTIVE' AND (? IS NULL OR boss_uuid <> ?)
                """;
        String keepId = keepBossId == null ? null : keepBossId.toString();
        queueWrite(() -> {
            synchronized (DB_LOCK) {
                if (!ensureSchemaLocked()) return;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, nowMs);
                    ps.setLong(2, nowMs);
                    ps.setString(3, reason);
                    ps.setString(4, keepId);
                    ps.setString(5, keepId);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to reconcile active evoker boss rows", e);
                }
            }
        });
    }

    private static void finishEvent(UUID bossId, String status, String reason, String killerUuid, String killerName) {
        if (bossId == null) return;
        long nowMs = System.currentTimeMillis();

        final String sql = """
                UPDATE cabal_evoker_boss_events
                SET status=?,
                    last_update_ms=?,
                    despawned_at_ms = CASE WHEN ?='DESPAWNED' OR ?='PURGED' THEN ? ELSE despawned_at_ms END,
                    defeated_at_ms = CASE WHEN ?='DEFEATED' THEN ? ELSE defeated_at_ms END,
                    defeated_by_uuid = COALESCE(?, defeated_by_uuid),
                    defeated_by_name = COALESCE(?, defeated_by_name),
                    end_reason = ?
                WHERE boss_uuid=? AND status='ACTIVE'
                """;
        queueWrite(() -> {
            synchronized (DB_LOCK) {
                if (!ensureSchemaLocked()) return;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, status);
                    ps.setLong(2, nowMs);
                    ps.setString(3, status);
                    ps.setString(4, status);
                    ps.setLong(5, nowMs);
                    ps.setString(6, status);
                    ps.setLong(7, nowMs);
                    ps.setString(8, killerUuid);
                    ps.setString(9, killerName);
                    ps.setString(10, reason);
                    ps.setString(11, bossId.toString());
                    ps.executeUpdate();
                    if (lastPositionBossId != null && lastPositionBossId.equals(bossId)) {
                        lastPositionBossId = null;
                        lastPositionWriteAtMs = 0L;
                    }
                } catch (SQLException e) {
                    CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to persist evoker boss end-state {}", status, e);
                }
            }
        });
    }

    private static boolean ensureSchemaLocked() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(DB_URL);
                try (Statement s = connection.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA busy_timeout=3000");
                }
            }
            if (!schemaReady) {
                try (Statement s = connection.createStatement()) {
                    s.execute("""
                            CREATE TABLE IF NOT EXISTS cabal_evoker_boss_events (
                                boss_uuid TEXT PRIMARY KEY,
                                status TEXT NOT NULL,
                                spawned_at_ms INTEGER NOT NULL,
                                spawn_x REAL NOT NULL,
                                spawn_y REAL NOT NULL,
                                spawn_z REAL NOT NULL,
                                spawn_dimension TEXT NOT NULL,
                                last_x REAL,
                                last_y REAL,
                                last_z REAL,
                                last_dimension TEXT,
                                last_update_ms INTEGER,
                                despawned_at_ms INTEGER,
                                defeated_at_ms INTEGER,
                                defeated_by_uuid TEXT,
                                defeated_by_name TEXT,
                                end_reason TEXT
                            )
                            """);
                    s.execute("CREATE INDEX IF NOT EXISTS idx_cabal_evoker_boss_status ON cabal_evoker_boss_events(status)");
                    s.execute("CREATE INDEX IF NOT EXISTS idx_cabal_evoker_boss_update ON cabal_evoker_boss_events(last_update_ms)");
                }
                schemaReady = true;
            }
            return true;
        } catch (SQLException e) {
            CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to initialize evoker boss state store", e);
            return false;
        }
    }

    private static void queueWrite(Runnable task) {
        try {
            DB_EXECUTOR.execute(task);
        } catch (Exception e) {
            CabalMobsMod.LOGGER.warn("[CabalMobs] Failed to queue evoker boss DB write", e);
        }
    }

    private static String dimensionId(Evoker evoker) {
        try {
            return evoker.level().dimension().identifier().toString();
        } catch (Exception ignored) {
            return "minecraft:overworld";
        }
    }
}
