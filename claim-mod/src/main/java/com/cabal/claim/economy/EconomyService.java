package com.cabal.claim.economy;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class EconomyService {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/EconomyService");
    private final EconomyDatabase db;
    private final EconomyDbWriter dbWriter;
    private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();

    public enum BalanceChangeResult {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        STORAGE_ERROR
    }

    public EconomyService(EconomyDatabase db, EconomyDbWriter dbWriter) {
        this.db = db;
        this.dbWriter = dbWriter;
    }

    public double getBalance(UUID playerId) {
        return balanceCache.computeIfAbsent(playerId, this::loadBalanceFromDb);
    }

    public void ensureAccount(UUID playerId) {
        try (Connection conn = db.open("economy")) {
            conn.setAutoCommit(false);
            double existing = getBalanceForUpdate(conn, playerId);
            conn.commit();
            balanceCache.put(playerId, existing);
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] ensureAccount failed: " + e.getMessage());
        }
    }

    public boolean setBalance(UUID playerId, double amount, String source, Map<String, Object> meta) {
        assertNotWriterThread("setBalance");
        return setBalanceAsync(playerId, amount, source, meta).join();
    }

    public CompletableFuture<Boolean> setBalanceAsync(UUID playerId, double amount, String source, Map<String, Object> meta) {
        return dbWriter.supplyAsync(() -> setBalanceInternal(playerId, amount, source, meta));
    }

    private boolean setBalanceInternal(UUID playerId, double amount, String source, Map<String, Object> meta) {
        if (amount < 0) return false;
        Connection conn = null;
        try {
            conn = db.open("economy");
            conn.setAutoCommit(false);
            upsertBalance(conn, playerId, amount);
            insertTransaction(conn, playerId, "SET", amount, source, toMetaJson(meta));
            conn.commit();
            balanceCache.put(playerId, amount);
            LOGGER.info("[ECO] type=SET player={} amount={} source={} balance={}",
                playerId, String.format("%.2f", amount), source, String.format("%.2f", amount));
            return true;
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    System.err.println("[CabalEconomy] setBalance rollback failed: " + rollbackError.getMessage());
                }
            }
            System.err.println("[CabalEconomy] setBalance failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException closeError) {
                    System.err.println("[CabalEconomy] setBalance close failed: " + closeError.getMessage());
                }
            }
        }
    }

    public boolean addBalance(UUID playerId, double amount, String source, Map<String, Object> meta) {
        assertNotWriterThread("addBalance");
        return addBalanceAsync(playerId, amount, source, meta).join();
    }

    /**
     * Richer synchronous balance update result that distinguishes user-facing
     * insufficient funds from backend/storage failures.
     */
    public BalanceChangeResult addBalanceDetailed(UUID playerId, double amount, String source, Map<String, Object> meta) {
        assertNotWriterThread("addBalanceDetailed");
        return addBalanceDetailedAsync(playerId, amount, source, meta).join();
    }

    public CompletableFuture<Boolean> addBalanceAsync(UUID playerId, double amount, String source, Map<String, Object> meta) {
        return dbWriter.supplyAsync(() -> addBalanceInternal(playerId, amount, source, meta));
    }

    public CompletableFuture<BalanceChangeResult> addBalanceDetailedAsync(
        UUID playerId, double amount, String source, Map<String, Object> meta
    ) {
        return dbWriter.supplyAsync(() -> addBalanceInternalDetailed(playerId, amount, source, meta));
    }

    public CompletableFuture<Map<UUID, Boolean>> addBalancesBatchAsync(
        Map<UUID, Double> amounts,
        String source,
        Map<UUID, Map<String, Object>> metaByPlayer
    ) {
        if (amounts == null || amounts.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }
        Map<UUID, Double> amountsCopy = new LinkedHashMap<>(amounts);
        Map<UUID, Map<String, Object>> metaByPlayerCopy;
        if (metaByPlayer == null || metaByPlayer.isEmpty()) {
            metaByPlayerCopy = Collections.emptyMap();
        } else {
            metaByPlayerCopy = new HashMap<>();
            for (Map.Entry<UUID, Map<String, Object>> entry : metaByPlayer.entrySet()) {
                UUID playerId = entry.getKey();
                if (playerId == null) continue;
                Map<String, Object> meta = entry.getValue();
                metaByPlayerCopy.put(playerId, meta == null ? Collections.emptyMap() : new HashMap<>(meta));
            }
        }
        return dbWriter.supplyAsync(() -> addBalancesBatchInternal(amountsCopy, source, metaByPlayerCopy));
    }

    private boolean addBalanceInternal(UUID playerId, double amount, String source, Map<String, Object> meta) {
        BalanceChangeResult detailed = addBalanceInternalDetailed(playerId, amount, source, meta);
        return detailed == BalanceChangeResult.SUCCESS;
    }

    private BalanceChangeResult addBalanceInternalDetailed(UUID playerId, double amount, String source, Map<String, Object> meta) {
        if (amount == 0) return BalanceChangeResult.SUCCESS;
        try (Connection conn = db.open("economy")) {
            conn.setAutoCommit(false);
            double current = getBalanceForUpdate(conn, playerId);
            double updated = current + amount;
            if (updated < 0) {
                conn.rollback();
                return BalanceChangeResult.INSUFFICIENT_FUNDS;
            }
            upsertBalance(conn, playerId, updated);
            insertTransaction(conn, playerId, amount >= 0 ? "CREDIT" : "DEBIT", amount, source, toMetaJson(meta));
            conn.commit();
            balanceCache.put(playerId, updated);
            LOGGER.info("[ECO] type={} player={} amount={} source={} balance={}",
                amount >= 0 ? "CREDIT" : "DEBIT",
                playerId,
                String.format("%.2f", amount),
                source,
                String.format("%.2f", updated));
            return BalanceChangeResult.SUCCESS;
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] addBalance failed: " + e.getMessage());
            LOGGER.error("addBalance storage error for player={} source={} amount={}",
                playerId, source, amount, e);
            return BalanceChangeResult.STORAGE_ERROR;
        }
    }

    private Map<UUID, Boolean> addBalancesBatchInternal(
        Map<UUID, Double> amounts,
        String source,
        Map<UUID, Map<String, Object>> metaByPlayer
    ) {
        Map<UUID, Boolean> outcomes = new LinkedHashMap<>();
        Map<UUID, Double> updatedBalances = new HashMap<>();
        Connection conn = null;
        try {
            conn = db.open("economy");
            conn.setAutoCommit(false);
            for (Map.Entry<UUID, Double> entry : amounts.entrySet()) {
                UUID playerId = entry.getKey();
                double amount = entry.getValue() != null ? entry.getValue() : 0.0D;
                if (playerId == null) {
                    outcomes.put(playerId, false);
                    continue;
                }
                if (amount == 0.0D) {
                    outcomes.put(playerId, true);
                    continue;
                }

                Savepoint savepoint = null;
                try {
                    savepoint = conn.setSavepoint();
                    double current = getBalanceForUpdate(conn, playerId);
                    double updated = current + amount;
                    if (updated < 0) {
                        rollbackToSavepoint(conn, savepoint);
                        outcomes.put(playerId, false);
                        continue;
                    }

                    upsertBalance(conn, playerId, updated);
                    Map<String, Object> meta = metaByPlayer != null ? metaByPlayer.get(playerId) : null;
                    insertTransaction(conn, playerId, amount >= 0 ? "CREDIT" : "DEBIT", amount, source, toMetaJson(meta));
                    try {
                        conn.releaseSavepoint(savepoint);
                    } catch (SQLException ignored) {
                        // best effort; some drivers may auto-release after commit/rollback.
                    }
                    updatedBalances.put(playerId, updated);
                    outcomes.put(playerId, true);
                } catch (Exception entryError) {
                    try {
                        rollbackToSavepoint(conn, savepoint);
                    } catch (SQLException rollbackError) {
                        throw new RuntimeException(
                            "[CabalEconomy] addBalancesBatch rollback failed for " + playerId,
                            rollbackError
                        );
                    }
                    outcomes.put(playerId, false);
                    System.err.println("[CabalEconomy] addBalancesBatch entry failed for " + playerId + ": " + entryError.getMessage());
                }
            }
            conn.commit();

            for (Map.Entry<UUID, Double> updated : updatedBalances.entrySet()) {
                UUID playerId = updated.getKey();
                double finalBalance = updated.getValue();
                double amount = amounts.getOrDefault(playerId, 0.0D);
                balanceCache.put(playerId, finalBalance);
                LOGGER.info("[ECO] type={} player={} amount={} source={} balance={}",
                    amount >= 0 ? "CREDIT" : "DEBIT",
                    playerId,
                    String.format("%.2f", amount),
                    source,
                    String.format("%.2f", finalBalance));
            }
            return outcomes;
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    System.err.println("[CabalEconomy] addBalancesBatch rollback failed: " + rollbackError.getMessage());
                }
            }
            System.err.println("[CabalEconomy] addBalancesBatch failed: " + e.getMessage());
            for (UUID playerId : amounts.keySet()) {
                outcomes.put(playerId, false);
            }
            return outcomes;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException closeError) {
                    System.err.println("[CabalEconomy] addBalancesBatch close failed: " + closeError.getMessage());
                }
            }
        }
    }

    private static void rollbackToSavepoint(Connection conn, Savepoint savepoint) throws SQLException {
        if (savepoint == null) return;
        try {
            conn.rollback(savepoint);
        } catch (SQLException rollbackError) {
            System.err.println("[CabalEconomy] addBalancesBatch savepoint rollback failed: " + rollbackError.getMessage());
            throw rollbackError;
        }
    }

    public boolean transfer(UUID from, UUID to, double amount, String source) {
        assertNotWriterThread("transfer");
        return transferAsync(from, to, amount, source).join();
    }

    public CompletableFuture<Boolean> transferAsync(UUID from, UUID to, double amount, String source) {
        return dbWriter.supplyAsync(() -> transferInternal(from, to, amount, source));
    }

    private boolean transferInternal(UUID from, UUID to, double amount, String source) {
        if (amount <= 0) return false;
        try (Connection conn = db.open("economy")) {
            conn.setAutoCommit(false);
            if (!transfer(conn, from, to, amount, source)) {
                conn.rollback();
                return false;
            }
            conn.commit();
            balanceCache.compute(from, (id, old) -> {
                double base = old != null ? old : loadBalanceFromDb(id);
                return old != null ? base - amount : base;
            });
            balanceCache.compute(to, (id, old) -> {
                double base = old != null ? old : loadBalanceFromDb(id);
                return old != null ? base + amount : base;
            });
            double fromBalance = getBalance(from);
            double toBalance = getBalance(to);
            LOGGER.info("[ECO] type=TRANSFER from={} to={} amount={} source={} from_balance={} to_balance={}",
                from,
                to,
                String.format("%.2f", amount),
                source,
                String.format("%.2f", fromBalance),
                String.format("%.2f", toBalance));
            return true;
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] transfer failed: " + e.getMessage());
            return false;
        }
    }

    public boolean transfer(Connection conn, UUID from, UUID to, double amount, String source) throws SQLException {
        if (amount <= 0) return false;
        if (from.equals(to)) return false;
        double fromBalance = getBalanceForUpdate(conn, from);
        if (fromBalance < amount) {
            return false;
        }
        double toBalance = getBalanceForUpdate(conn, to);
        upsertBalance(conn, from, fromBalance - amount);
        upsertBalance(conn, to, toBalance + amount);
        Map<String, Object> fromMeta = new HashMap<>();
        fromMeta.put("to", to.toString());
        Map<String, Object> toMeta = new HashMap<>();
        toMeta.put("from", from.toString());
        insertTransaction(conn, from, "TRANSFER_OUT", -amount, source, toMetaJson(fromMeta));
        insertTransaction(conn, to, "TRANSFER_IN", amount, source, toMetaJson(toMeta));
        return true;
    }

    public void invalidateBalanceCache(UUID playerId) {
        balanceCache.remove(playerId);
    }

    private static void upsertBalance(Connection conn, UUID playerId, double amount) throws SQLException {
        String sql = """
            INSERT INTO accounts(uuid, balance, updated_at)
            VALUES(?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET balance=excluded.balance, updated_at=excluded.updated_at
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setDouble(2, amount);
            ps.setLong(3, EconomyDatabase.nowTs());
            ps.executeUpdate();
        }
    }

    private static double getBalanceForUpdate(Connection conn, UUID playerId) throws SQLException {
        String sql = "SELECT balance FROM accounts WHERE uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        }
        upsertBalance(conn, playerId, 0.0D);
        return 0.0D;
    }

    private static void insertTransaction(Connection conn, UUID playerId, String type, double amount, String source, String metaJson)
        throws SQLException {
        String sql = "INSERT INTO transactions(uuid, type, amount, source, meta_json, ts) VALUES(?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, type);
            ps.setDouble(3, amount);
            ps.setString(4, source);
            ps.setString(5, metaJson);
            ps.setLong(6, EconomyDatabase.nowTs());
            ps.executeUpdate();
        }
    }

    private static String toMetaJson(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return "{}";
        return GSON.toJson(meta);
    }

    private double loadBalanceFromDb(UUID playerId) {
        String sql = "SELECT balance FROM accounts WHERE uuid = ?";
        try (Connection conn = db.open("economy");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (SQLException e) {
            System.err.println("[CabalEconomy] getBalance failed: " + e.getMessage());
        }
        return 0.0D;
    }

    private void assertNotWriterThread(String methodName) {
        if (dbWriter.isWriterThread()) {
            throw new IllegalStateException(methodName + " must not be called on EconomyDbWriter thread; use async API");
        }
    }
}
