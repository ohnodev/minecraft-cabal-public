package com.cabal.claim.economy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class BackpackService {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/Backpack");
    public static final int BACKPACK_SLOTS = 9;

    private final EconomyDatabase db;
    private final EconomyDbWriter dbWriter;
    private final EconomyConfig config;

    public record LoadResult(ItemStack[] slots, String hash) {}

    public BackpackService(EconomyDatabase db, EconomyDbWriter dbWriter, EconomyConfig config) {
        this.db = db;
        this.dbWriter = dbWriter;
        this.config = config;
    }

    public LoadResult load(UUID playerId) {
        ItemStack[] slots = emptySlots();
        String sql = "SELECT bag_blob FROM player_backpacks WHERE player_uuid = ?";
        try (Connection conn = db.open("backpack");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("bag_blob");
                    if (blob != null && blob.length > 0) {
                        slots = deserialize(blob);
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.error("[BACKPACK] Failed to load backpack for {}: {}", playerId, e.getMessage(), e);
        }
        byte[] nbt = serialize(slots);
        String hash = nbt != null ? md5Hex(nbt) : "";
        return new LoadResult(slots, hash);
    }

    public CompletableFuture<Boolean> saveAsync(UUID playerId, ItemStack[] slots, String reason, String originalHash) {
        byte[] nbtPayload = serialize(slots);
        if (nbtPayload == null) return CompletableFuture.completedFuture(false);
        String hash = md5Hex(nbtPayload);

        if (hash.equals(originalHash)) return CompletableFuture.completedFuture(true);

        byte[] compressed = gzipCompress(nbtPayload);
        if (compressed == null) return CompletableFuture.completedFuture(false);

        long ts = Instant.now().getEpochSecond();
        int maxSnapshots = config.backpackSnapshotMaxPerPlayer;

        try {
            return dbWriter.supplyAsync(() -> savePrepared(playerId, compressed, hash, reason, ts, maxSnapshots));
        } catch (RejectedExecutionException | IllegalStateException e) {
            LOGGER.warn("[BACKPACK] Save rejected for {} reason={} hash={}", playerId, reason, hash);
            return CompletableFuture.completedFuture(false);
        }
    }

    private boolean savePrepared(UUID playerId, byte[] compressed, String hash, String reason, long ts, int maxSnapshots) {
        try (Connection conn = db.open("backpack")) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement psUpsert = conn.prepareStatement("""
                        INSERT INTO player_backpacks (player_uuid, bag_blob, bag_hash, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(player_uuid) DO UPDATE SET
                          bag_blob = excluded.bag_blob,
                          bag_hash = excluded.bag_hash,
                          updated_at = excluded.updated_at
                        """)) {
                    psUpsert.setString(1, playerId.toString());
                    psUpsert.setBytes(2, compressed);
                    psUpsert.setString(3, hash);
                    psUpsert.setLong(4, ts);
                    psUpsert.executeUpdate();
                }

                try (PreparedStatement psSnap = conn.prepareStatement(
                        "INSERT INTO backpack_snapshots (player_uuid, reason, bag_hash, snapshot_blob, ts) VALUES (?, ?, ?, ?, ?)")) {
                    psSnap.setString(1, playerId.toString());
                    psSnap.setString(2, reason);
                    psSnap.setString(3, hash);
                    psSnap.setBytes(4, compressed);
                    psSnap.setLong(5, ts);
                    psSnap.executeUpdate();
                }

                if (maxSnapshots > 0) {
                    try (PreparedStatement psPrune = conn.prepareStatement("""
                            DELETE FROM backpack_snapshots WHERE player_uuid = ? AND id NOT IN (
                              SELECT id FROM backpack_snapshots WHERE player_uuid = ? ORDER BY id DESC LIMIT ?
                            )
                            """)) {
                        psPrune.setString(1, playerId.toString());
                        psPrune.setString(2, playerId.toString());
                        psPrune.setInt(3, maxSnapshots);
                        psPrune.executeUpdate();
                    }
                } else {
                    LOGGER.warn("[BACKPACK] Snapshot pruning skipped for {} because maxSnapshots={} (must be >= 1)", playerId, maxSnapshots);
                }

                conn.commit();
                LOGGER.info("[BACKPACK] Saved backpack for {} reason={} hash={}", playerId, reason, hash);
                return true;
            } catch (SQLException ex) {
                try {
                    conn.rollback();
                } catch (SQLException rbEx) {
                    LOGGER.error("[BACKPACK] Rollback failed for {}: {}", playerId, rbEx.getMessage(), rbEx);
                }
                throw ex;
            }
        } catch (SQLException e) {
            LOGGER.error("[BACKPACK] Failed to save backpack for {}: {}", playerId, e.getMessage(), e);
            return false;
        }
    }

    public static byte[] serialize(ItemStack[] slots) {
        try {
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (int i = 0; i < slots.length; i++) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("Slot", i);
                ItemStack stack = slots[i];
                if (stack == null || stack.isEmpty()) {
                    entry.putBoolean("Empty", true);
                } else {
                    Tag encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack).result().orElse(null);
                    if (encoded instanceof CompoundTag ct) {
                        entry.put("Item", ct);
                    } else {
                        entry.putBoolean("Empty", true);
                    }
                }
                list.add(entry);
            }
            root.put("Slots", list);
            root.putInt("SlotCount", slots.length);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.write(root, new DataOutputStream(bos));
            return bos.toByteArray();
        } catch (Exception e) {
            LOGGER.error("[BACKPACK] Serialization failed: {}", e.getMessage(), e);
            return null;
        }
    }

    static ItemStack[] deserialize(byte[] compressedBlob) {
        ItemStack[] slots = emptySlots();
        try {
            byte[] raw = gzipDecompress(compressedBlob);
            CompoundTag root = NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)));
            ListTag list = root.getListOrEmpty("Slots");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i).orElse(null);
                if (entry == null) continue;
                int slot = entry.getInt("Slot").orElse(-1);
                if (slot < 0 || slot >= BACKPACK_SLOTS) continue;
                if (entry.getBoolean("Empty").orElse(false)) continue;
                CompoundTag itemTag = entry.getCompound("Item").orElse(null);
                if (itemTag == null) continue;
                ItemStack stack = ItemStack.CODEC.parse(NbtOps.INSTANCE, itemTag).result().orElse(ItemStack.EMPTY);
                slots[slot] = stack;
            }
        } catch (Exception e) {
            LOGGER.error("[BACKPACK] Deserialization failed: {}", e.getMessage(), e);
        }
        return slots;
    }

    static ItemStack[] emptySlots() {
        ItemStack[] slots = new ItemStack[BACKPACK_SLOTS];
        for (int i = 0; i < BACKPACK_SLOTS; i++) slots[i] = ItemStack.EMPTY;
        return slots;
    }

    public static String md5Hex(byte[] data) {
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
            LOGGER.error("[BACKPACK] GZIP compression failed: {}", e.getMessage(), e);
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
