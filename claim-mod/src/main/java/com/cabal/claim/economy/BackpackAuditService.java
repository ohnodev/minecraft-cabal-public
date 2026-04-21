package com.cabal.claim.economy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;

public final class BackpackAuditService {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/BackpackAudit");

    private final EconomyDatabase db;
    private final EconomyDbWriter dbWriter;
    private final EconomyConfig config;

    public record AuditEvent(
        String sessionId, String playerUuid, String playerName,
        String action, int slotFrom, int slotTo,
        String itemId, int itemCount, String nbtHash,
        String beforeHash, String afterHash, String deltaSummary,
        long tsMs
    ) {}

    public BackpackAuditService(EconomyDatabase db, EconomyDbWriter dbWriter, EconomyConfig config) {
        this.db = db;
        this.dbWriter = dbWriter;
        this.config = config;
    }

    public void logEvent(AuditEvent event) {
        if (!config.backpackAuditEnabled) return;
        submitBestEffort(() -> {
            String sql = """
                INSERT INTO backpack_audit_log
                  (session_id, player_uuid, player_name, action, slot_from, slot_to,
                   item_id, item_count, nbt_hash, before_hash, after_hash, delta_summary, ts_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            try (Connection conn = db.open("bp_audit");
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, event.sessionId());
                ps.setString(2, event.playerUuid());
                ps.setString(3, event.playerName());
                ps.setString(4, event.action());
                ps.setInt(5, event.slotFrom());
                ps.setInt(6, event.slotTo());
                ps.setString(7, event.itemId());
                ps.setInt(8, event.itemCount());
                ps.setString(9, event.nbtHash());
                ps.setString(10, event.beforeHash());
                ps.setString(11, event.afterHash());
                ps.setString(12, event.deltaSummary());
                ps.setLong(13, event.tsMs());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("[BP_AUDIT] Failed to log event action={} player={}: {}",
                    event.action(), event.playerUuid(), e.getMessage(), e);
            }
        });
    }

    public void purgeOldEntries() {
        if (!config.backpackAuditEnabled) return;
        int days = config.backpackAuditRetentionDays;
        long cutoffMs = Instant.now().toEpochMilli() - ((long) days * 24 * 60 * 60 * 1000);
        submitBestEffort(() -> {
            String sql = "DELETE FROM backpack_audit_log WHERE ts_ms < ?";
            try (Connection conn = db.open("bp_audit_purge");
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, cutoffMs);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    LOGGER.info("[BP_AUDIT] Purged {} audit entries older than {} days", deleted, days);
                }
            } catch (SQLException e) {
                LOGGER.error("[BP_AUDIT] Failed to purge old entries: {}", e.getMessage(), e);
            }
        });
    }

    private void submitBestEffort(Runnable task) {
        try {
            dbWriter.runAsync(task);
        } catch (RejectedExecutionException | IllegalStateException ignored) {
            // Best-effort only: writer shutdown can race with late audit submissions.
        }
    }
}
