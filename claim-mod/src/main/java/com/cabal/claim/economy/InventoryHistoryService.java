package com.cabal.claim.economy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class InventoryHistoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/InvHistory");

    private final EconomyDatabase db;
    private final EconomyDbWriter dbWriter;
    private final EconomyConfig config;
    private static final int PLAYERS_PER_TICK_BATCH = 5;

    private final Map<UUID, String> lastHashByPlayer = new ConcurrentHashMap<>();
    private long lastTickTs = 0;
    private int playerCursor = 0;

    public InventoryHistoryService(EconomyDatabase db, EconomyDbWriter dbWriter, EconomyConfig config) {
        this.db = db;
        this.dbWriter = dbWriter;
        this.config = config;
    }

    public void tickPeriodic(MinecraftServer server) {
        if (!config.inventoryHistoryEnabled) return;
        long now = System.currentTimeMillis() / 1000;
        if (now - lastTickTs < config.inventorySnapshotIntervalSeconds) return;
        lastTickTs = now;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        int toProcess = Math.min(PLAYERS_PER_TICK_BATCH, players.size());
        for (int i = 0; i < toProcess; i++) {
            ServerPlayer player = players.get(playerCursor % players.size());
            playerCursor++;
            captureSnapshot(player, "interval");
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!config.inventoryHistoryEnabled || !config.snapshotOnJoin) return;
        lastHashByPlayer.remove(player.getUUID());
        captureSnapshot(player, "join");
    }

    public void onPlayerLeave(ServerPlayer player) {
        if (!config.inventoryHistoryEnabled || !config.snapshotOnLeave) return;
        captureSnapshot(player, "leave");
        lastHashByPlayer.remove(player.getUUID());
    }

    public void onPlayerDeath(ServerPlayer player) {
        if (!config.inventoryHistoryEnabled || !config.snapshotOnDeath) return;
        captureSnapshot(player, "death");
    }

    private void captureSnapshot(ServerPlayer player, String reason) {
        byte[] nbtPayload = serializeInventory(player);
        if (nbtPayload == null) return;
        String hash = md5Hex(nbtPayload);
        String prevHash = lastHashByPlayer.get(player.getUUID());
        if (hash.equals(prevHash)) return;

        byte[] compressed = gzipCompress(nbtPayload);
        if (compressed == null) return;

        UUID playerId = player.getUUID();
        long ts = Instant.now().getEpochSecond();
        int maxSnapshots = config.inventoryMaxSnapshotsPerPlayer;

        dbWriter.runAsync(() -> {
            try (Connection conn = db.open("inv_history")) {
                conn.setAutoCommit(false);
                try (PreparedStatement psInsert = conn.prepareStatement(
                        "INSERT INTO inventory_snapshots (player_uuid, reason, inv_hash, snapshot_blob, ts) VALUES (?, ?, ?, ?, ?)")) {
                    psInsert.setString(1, playerId.toString());
                    psInsert.setString(2, reason);
                    psInsert.setString(3, hash);
                    psInsert.setBytes(4, compressed);
                    psInsert.setLong(5, ts);
                    psInsert.executeUpdate();
                }
                try (PreparedStatement psPrune = conn.prepareStatement("""
                        DELETE FROM inventory_snapshots WHERE player_uuid = ? AND id NOT IN (
                          SELECT id FROM inventory_snapshots WHERE player_uuid = ? ORDER BY id DESC LIMIT ?
                        )
                        """)) {
                    psPrune.setString(1, playerId.toString());
                    psPrune.setString(2, playerId.toString());
                    psPrune.setInt(3, maxSnapshots);
                    psPrune.executeUpdate();
                }
                conn.commit();
                lastHashByPlayer.put(playerId, hash);
            } catch (SQLException e) {
                LOGGER.error("[INVHIST] Failed to store snapshot for {}: {}", playerId, e.getMessage(), e);
            }
        });
    }

    static byte[] serializeInventory(ServerPlayer player) {
        try {
            Inventory inv = player.getInventory();
            CompoundTag root = new CompoundTag();
            ListTag slots = new ListTag();
            int containerSize = inv.getContainerSize();
            for (int i = 0; i < containerSize; i++) {
                ItemStack stack = inv.getItem(i);
                CompoundTag entry = new CompoundTag();
                entry.putInt("Slot", i);
                if (stack.isEmpty()) {
                    entry.putBoolean("Empty", true);
                } else {
                    Tag encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack).result().orElse(null);
                    if (encoded instanceof CompoundTag ct) {
                        entry.put("Item", ct);
                    } else {
                        entry.putBoolean("Empty", true);
                    }
                }
                slots.add(entry);
            }
            root.put("Slots", slots);
            root.putInt("ContainerSize", containerSize);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.write(root, new DataOutputStream(bos));
            return bos.toByteArray();
        } catch (Exception e) {
            LOGGER.error("[INVHIST] Failed to serialize inventory for {}: {}", player.getUUID(), e.getMessage(), e);
            return null;
        }
    }

    static CompoundTag deserializeInventoryNbt(byte[] compressedBlob) throws IOException {
        byte[] raw = gzipDecompress(compressedBlob);
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)));
    }

    public record SnapshotMeta(long id, String playerUuid, String reason, long ts) {}

    public List<SnapshotMeta> listSnapshots(UUID playerId, int limit, int offset) {
        List<SnapshotMeta> out = new ArrayList<>();
        String sql = "SELECT id, player_uuid, reason, ts FROM inventory_snapshots WHERE player_uuid = ? ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?";
        try (Connection conn = db.open("inv_history");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SnapshotMeta(
                        rs.getLong("id"),
                        rs.getString("player_uuid"),
                        rs.getString("reason"),
                        rs.getLong("ts")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("[INVHIST] Failed to list snapshots for {}: {}", playerId, e.getMessage(), e);
        }
        return out;
    }

    public byte[] getSnapshotBlob(long snapshotId) {
        String sql = "SELECT snapshot_blob FROM inventory_snapshots WHERE id = ?";
        try (Connection conn = db.open("inv_history");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes("snapshot_blob");
                }
            }
        } catch (SQLException e) {
            LOGGER.error("[INVHIST] Failed to load snapshot blob id={}: {}", snapshotId, e.getMessage(), e);
        }
        return null;
    }

    public SnapshotMeta getSnapshotMeta(long snapshotId) {
        String sql = "SELECT id, player_uuid, reason, ts FROM inventory_snapshots WHERE id = ?";
        try (Connection conn = db.open("inv_history");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SnapshotMeta(
                        rs.getLong("id"),
                        rs.getString("player_uuid"),
                        rs.getString("reason"),
                        rs.getLong("ts")
                    );
                }
            }
        } catch (SQLException e) {
            LOGGER.error("[INVHIST] Failed to load snapshot meta id={}: {}", snapshotId, e.getMessage(), e);
        }
        return null;
    }

    public record RestoreResult(boolean success, String message) {}

    public RestoreResult restoreSnapshot(ServerPlayer target, long snapshotId, String adminName) {
        byte[] blob = getSnapshotBlob(snapshotId);
        if (blob == null) return new RestoreResult(false, "Snapshot not found.");
        SnapshotMeta meta = getSnapshotMeta(snapshotId);
        if (meta == null) return new RestoreResult(false, "Snapshot metadata not found.");
        if (!meta.playerUuid().equals(target.getUUID().toString())) {
            return new RestoreResult(false, "Snapshot does not belong to target player.");
        }

        CompoundTag nbt;
        try {
            nbt = deserializeInventoryNbt(blob);
        } catch (Exception e) {
            LOGGER.error("[INVHIST] Corrupted snapshot blob id={}: {}", snapshotId, e.getMessage(), e);
            return new RestoreResult(false, "Corrupted snapshot data.");
        }

        Inventory inv = target.getInventory();
        int containerSize = inv.getContainerSize();
        ItemStack[] parsed = new ItemStack[containerSize];
        for (int i = 0; i < containerSize; i++) parsed[i] = ItemStack.EMPTY;

        ListTag slots = nbt.getListOrEmpty("Slots");
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag entry = slots.getCompound(i).orElse(null);
            if (entry == null) continue;
            int slot = entry.getInt("Slot").orElse(-1);
            if (slot < 0 || slot >= containerSize) continue;
            if (entry.getBoolean("Empty").orElse(false)) continue;
            CompoundTag itemTag = entry.getCompound("Item").orElse(null);
            if (itemTag == null) continue;
            ItemStack stack = ItemStack.CODEC.parse(NbtOps.INSTANCE, itemTag).result().orElse(ItemStack.EMPTY);
            parsed[slot] = stack;
        }

        inv.clearContent();
        for (int i = 0; i < containerSize; i++) {
            if (!parsed[i].isEmpty()) {
                inv.setItem(i, parsed[i]);
            }
        }

        LOGGER.info("[INVHIST] RESTORE admin={} target={} snapshotId={} reason={} snapshotTs={}",
            adminName, target.getUUID(), snapshotId, meta.reason(), meta.ts());

        captureSnapshot(target, "manual_restore");

        return new RestoreResult(true, "Inventory restored from snapshot #" + snapshotId + ".");
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    private static byte[] gzipCompress(byte[] data) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(data);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            LOGGER.error("[INVHIST] GZIP compression failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private static byte[] gzipDecompress(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 2);
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }
}
