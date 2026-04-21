package com.cabal.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClaimManager {
    public static final int CLAIM_RADIUS = 100;
    public static final int MIN_SPAWN_DISTANCE = 100;
    public static final long SETHOME_COOLDOWN_TICKS = 24L * 60L * 60L * 20L; // 24 hours
    public static final int DEFAULT_CLAIM_SLOTS = 2;
    public static final int MAX_CLAIM_SLOTS = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LEGACY_CLAIMS_TYPE = new TypeToken<Map<String, ClaimEntry>>() {}.getType();

    private final Path dataFile;
    private final Object claimMutationLock = new Object();
    private final Map<Integer, ClaimEntry> claimsById = new ConcurrentHashMap<>();
    private final Map<String, Integer> claimSlots = new ConcurrentHashMap<>();
    private volatile int nextClaimId = 1;
    private volatile boolean claimsAvailable = true;

    public ClaimManager(Path serverDir) {
        this.dataFile = serverDir.resolve("claims.json");
        load();
    }

    // ── Records ──────────────────────────────────────────────────────

    public record TrustedPlayer(String uuid, String name) {}

    public record HomeAnchor(double x, double y, double z, long setTick) {}

    public record ClaimEntry(int id, String ownerUuid, String ownerName, int x, int y, int z,
                             String dimension, List<TrustedPlayer> trusted,
                             Map<String, HomeAnchor> homes,
                             boolean claimTransferTicketIssued) {
        public String dimensionOrDefault() {
            return dimension != null ? dimension : "minecraft:overworld";
        }
        public List<TrustedPlayer> trustedOrEmpty() {
            return trusted != null ? trusted : List.of();
        }
        public Map<String, HomeAnchor> homesOrEmpty() {
            return homes != null ? homes : Map.of();
        }
        public ClaimEntry withTrusted(List<TrustedPlayer> newTrusted) {
            return new ClaimEntry(id, ownerUuid, ownerName, x, y, z, dimension, newTrusted, homes, claimTransferTicketIssued);
        }
        public ClaimEntry withHomes(Map<String, HomeAnchor> newHomes) {
            return new ClaimEntry(id, ownerUuid, ownerName, x, y, z, dimension, trusted, newHomes, claimTransferTicketIssued);
        }
        public ClaimEntry withClaimTransferTicketIssued(boolean issued) {
            return new ClaimEntry(id, ownerUuid, ownerName, x, y, z, dimension, trusted, homes, issued);
        }
    }

    /** Unified home entry visible to a player via /myhomes and /home <index>. */
    public record IndexedHome(int index, int claimId, String claimOwnerName,
                              double homeX, double homeY, double homeZ,
                              String dimension, boolean isOwner, boolean homeSet) {}

    public enum ClaimResult {
        SUCCESS,
        TOO_CLOSE_TO_SPAWN,
        NO_CLAIM_SLOTS,
        OVERLAPS_EXISTING,
        SAVE_FAILED
    }

    public enum TrustResult {
        SUCCESS,
        NO_CLAIM,
        ALREADY_TRUSTED,
        NOT_TRUSTED,
        CANNOT_TRUST_SELF,
        SAVE_FAILED
    }

    public enum SetHomeResult {
        SUCCESS,
        NOT_ON_CLAIM,
        NOT_ALLOWED,
        ON_COOLDOWN,
        SAVE_FAILED
    }

    public enum ClaimTransferTicketResult {
        SUCCESS,
        NO_CLAIM,
        NOT_OWNER,
        ALREADY_ISSUED,
        SAVE_FAILED
    }

    public enum TransferClaimResult {
        SUCCESS,
        NO_CLAIM,
        NOT_OWNER,
        RECIPIENT_NO_CLAIM_SLOTS,
        SAVE_FAILED
    }

    public enum ForecloseResult {
        SUCCESS,
        NO_CLAIM,
        NOT_OWNER,
        SAVE_FAILED
    }

    // ── Claim creation ──────────────────────────────────────────────

    public ClaimResult tryClaim(UUID playerUuid, String playerName, int x, int y, int z,
                               int spawnX, int spawnZ, String dimension) {
        synchronized (claimMutationLock) {
            String uid = playerUuid.toString();

            int owned = (int) claimsById.values().stream()
                .filter(c -> c.ownerUuid().equals(uid)).count();
            int slots = getClaimSlots(playerUuid);
            if (owned >= slots) {
                return ClaimResult.NO_CLAIM_SLOTS;
            }

            ClaimResult locationCheck = validateClaimLocation(x, z, spawnX, spawnZ, dimension);
            if (locationCheck != ClaimResult.SUCCESS) return locationCheck;

            int id = nextClaimId++;
            ClaimEntry entry = new ClaimEntry(id, uid, playerName, x, y, z, dimension, null, null, false);
            claimsById.put(id, entry);
            if (!save()) {
                claimsById.remove(id);
                nextClaimId--;
                return ClaimResult.SAVE_FAILED;
            }
            return ClaimResult.SUCCESS;
        }
    }

    /**
     * Validates whether a claim center is legal in the given dimension.
     * This check is non-mutating and ignores slot ownership/capacity.
     */
    public ClaimResult validateClaimLocation(int x, int z, int spawnX, int spawnZ, String dimension) {
        double spawnDist = Math.sqrt(Math.pow(x - spawnX, 2) + Math.pow(z - spawnZ, 2));
        if (spawnDist < MIN_SPAWN_DISTANCE) {
            return ClaimResult.TOO_CLOSE_TO_SPAWN;
        }

        for (ClaimEntry existing : claimsById.values()) {
            if (!existing.dimensionOrDefault().equals(dimension)) continue;
            double dist = Math.sqrt(Math.pow(x - existing.x, 2) + Math.pow(z - existing.z, 2));
            if (dist < CLAIM_RADIUS * 2) {
                return ClaimResult.OVERLAPS_EXISTING;
            }
        }
        return ClaimResult.SUCCESS;
    }

    // ── Queries ──────────────────────────────────────────────────────

    /** Returns the first (lowest-id) claim for a player, or null. Backward-compat helper. */
    public ClaimEntry getClaim(UUID playerUuid) {
        String uid = playerUuid.toString();
        return claimsById.values().stream()
            .filter(c -> c.ownerUuid().equals(uid))
            .min(Comparator.comparingInt(ClaimEntry::id))
            .orElse(null);
    }

    public List<ClaimEntry> getClaimsByOwner(UUID ownerUuid) {
        String uid = ownerUuid.toString();
        return claimsById.values().stream()
            .filter(c -> c.ownerUuid().equals(uid))
            .sorted(Comparator.comparingInt(ClaimEntry::id))
            .toList();
    }

    public ClaimEntry getClaimById(int id) {
        return claimsById.get(id);
    }

    /** Returns the claim that contains the given block position, or null. */
    public ClaimEntry getClaimAt(String dimensionKey, int blockX, int blockZ) {
        for (ClaimEntry entry : claimsById.values()) {
            if (!entry.dimensionOrDefault().equals(dimensionKey)) continue;
            double dist = Math.sqrt(Math.pow(blockX - entry.x, 2) + Math.pow(blockZ - entry.z, 2));
            if (dist <= CLAIM_RADIUS) {
                return entry;
            }
        }
        return null;
    }

    public UUID getClaimOwnerAt(String dimensionKey, int blockX, int blockZ) {
        ClaimEntry entry = getClaimAt(dimensionKey, blockX, blockZ);
        return entry != null ? UUID.fromString(entry.ownerUuid()) : null;
    }

    public boolean isAllowedAt(String dimensionKey, int blockX, int blockZ, UUID actorUuid) {
        for (ClaimEntry entry : claimsById.values()) {
            if (!entry.dimensionOrDefault().equals(dimensionKey)) continue;
            double dist = Math.sqrt(Math.pow(blockX - entry.x, 2) + Math.pow(blockZ - entry.z, 2));
            if (dist <= CLAIM_RADIUS) {
                if (entry.ownerUuid().equals(actorUuid.toString())) return true;
                String actorStr = actorUuid.toString();
                return entry.trustedOrEmpty().stream().anyMatch(t -> t.uuid().equals(actorStr));
            }
        }
        return true; // unclaimed
    }

    // ── Trust ────────────────────────────────────────────────────────

    public TrustResult addTrusted(int claimId, UUID callerUuid, UUID targetUuid, String targetName) {
        if (callerUuid.equals(targetUuid)) return TrustResult.CANNOT_TRUST_SELF;
        String targetStr = targetUuid.toString();
        ClaimEntry previous = claimsById.get(claimId);
        if (previous == null || !previous.ownerUuid().equals(callerUuid.toString())) {
            return TrustResult.NO_CLAIM;
        }
        List<TrustedPlayer> current = new ArrayList<>(previous.trustedOrEmpty());
        if (current.stream().anyMatch(t -> t.uuid().equals(targetStr))) {
            return TrustResult.ALREADY_TRUSTED;
        }
        current.add(new TrustedPlayer(targetStr, targetName));
        ClaimEntry updated = previous.withTrusted(current);
        claimsById.put(claimId, updated);
        if (save()) return TrustResult.SUCCESS;
        claimsById.put(claimId, previous);
        return TrustResult.SAVE_FAILED;
    }

    /**
     * Removes trust AND deletes the target's home anchor on this claim.
     */
    public TrustResult removeTrusted(int claimId, UUID callerUuid, UUID targetUuid) {
        String targetStr = targetUuid.toString();
        ClaimEntry previous = claimsById.get(claimId);
        if (previous == null || !previous.ownerUuid().equals(callerUuid.toString())) {
            return TrustResult.NO_CLAIM;
        }
        List<TrustedPlayer> current = new ArrayList<>(previous.trustedOrEmpty());
        boolean removed = current.removeIf(t -> t.uuid().equals(targetStr));
        if (!removed) return TrustResult.NOT_TRUSTED;

        Map<String, HomeAnchor> updatedHomes = new HashMap<>(previous.homesOrEmpty());
        updatedHomes.remove(targetStr);

        ClaimEntry updated = new ClaimEntry(previous.id(), previous.ownerUuid(), previous.ownerName(),
            previous.x(), previous.y(), previous.z(), previous.dimension(),
            current, updatedHomes.isEmpty() ? null : updatedHomes, previous.claimTransferTicketIssued());
        claimsById.put(claimId, updated);
        if (save()) return TrustResult.SUCCESS;
        claimsById.put(claimId, previous);
        return TrustResult.SAVE_FAILED;
    }

    /**
     * Adds trust on first (lowest-id) claim owned by {@code ownerUuid}.
     */
    public TrustResult addTrustedOnFirstClaim(UUID ownerUuid, UUID targetUuid, String targetName) {
        ClaimEntry first = getClaim(ownerUuid);
        if (first == null) return TrustResult.NO_CLAIM;
        return addTrusted(first.id(), ownerUuid, targetUuid, targetName);
    }

    /**
     * @deprecated use {@link #addTrusted(int, UUID, UUID, String)} for explicit claims,
     * or {@link #addTrustedOnFirstClaim(UUID, UUID, String)} when first-claim fallback
     * is intentionally desired.
     */
    @Deprecated
    public TrustResult addTrusted(UUID ownerUuid, UUID targetUuid, String targetName) {
        return addTrustedOnFirstClaim(ownerUuid, targetUuid, targetName);
    }

    /**
     * Removes trust from first (lowest-id) claim owned by {@code ownerUuid}.
     */
    public TrustResult removeTrustedOnFirstClaim(UUID ownerUuid, UUID targetUuid) {
        ClaimEntry first = getClaim(ownerUuid);
        if (first == null) return TrustResult.NO_CLAIM;
        return removeTrusted(first.id(), ownerUuid, targetUuid);
    }

    /**
     * @deprecated use {@link #removeTrusted(int, UUID, UUID)} for explicit claims,
     * or {@link #removeTrustedOnFirstClaim(UUID, UUID)} when first-claim fallback
     * is intentionally desired.
     */
    @Deprecated
    public TrustResult removeTrusted(UUID ownerUuid, UUID targetUuid) {
        return removeTrustedOnFirstClaim(ownerUuid, targetUuid);
    }

    public List<TrustedPlayer> listTrusted(int claimId) {
        ClaimEntry entry = claimsById.get(claimId);
        if (entry == null) return Collections.emptyList();
        return entry.trustedOrEmpty();
    }

    /** Backward-compat: list trusted for first owned claim. */
    public List<TrustedPlayer> listTrusted(UUID ownerUuid) {
        ClaimEntry entry = getClaim(ownerUuid);
        if (entry == null) return Collections.emptyList();
        return entry.trustedOrEmpty();
    }

    // ── Homes ────────────────────────────────────────────────────────

    public SetHomeResult setHome(UUID playerUuid, int claimId, double hx, double hy, double hz, long currentTick) {
        String uid = playerUuid.toString();
        ClaimEntry claim = claimsById.get(claimId);
        if (claim == null) return SetHomeResult.NOT_ON_CLAIM;

        boolean isOwner = claim.ownerUuid().equals(uid);
        boolean isTrusted = claim.trustedOrEmpty().stream().anyMatch(t -> t.uuid().equals(uid));
        if (!isOwner && !isTrusted) return SetHomeResult.NOT_ALLOWED;

        HomeAnchor existing = claim.homesOrEmpty().get(uid);
        if (existing != null && currentTick - existing.setTick() < SETHOME_COOLDOWN_TICKS) {
            return SetHomeResult.ON_COOLDOWN;
        }

        Map<String, HomeAnchor> updatedHomes = new HashMap<>(claim.homesOrEmpty());
        updatedHomes.put(uid, new HomeAnchor(hx, hy, hz, currentTick));
        ClaimEntry previous = claim;
        ClaimEntry updated = claim.withHomes(updatedHomes);
        claimsById.put(claimId, updated);
        if (save()) return SetHomeResult.SUCCESS;
        claimsById.put(claimId, previous);
        return SetHomeResult.SAVE_FAILED;
    }

    public long remainingSethomeCooldown(UUID playerUuid, int claimId, long currentTick) {
        ClaimEntry claim = claimsById.get(claimId);
        if (claim == null) return 0;
        HomeAnchor existing = claim.homesOrEmpty().get(playerUuid.toString());
        if (existing == null) return 0;
        long elapsed = currentTick - existing.setTick();
        return elapsed >= SETHOME_COOLDOWN_TICKS ? 0 : SETHOME_COOLDOWN_TICKS - elapsed;
    }

    /**
     * Builds a unified, deterministically-ordered home list for a player.
     * Owned claims always appear (claim center as fallback), then trusted claims
     * where the player has explicitly set a home.
     */
    public List<IndexedHome> getUnifiedHomes(UUID playerUuid) {
        String uid = playerUuid.toString();
        List<IndexedHome> result = new ArrayList<>();

        List<ClaimEntry> owned = getClaimsByOwner(playerUuid);
        for (ClaimEntry claim : owned) {
            HomeAnchor home = claim.homesOrEmpty().get(uid);
            double hx = home != null ? home.x() : claim.x() + 0.5;
            double hy = home != null ? home.y() : claim.y();
            double hz = home != null ? home.z() : claim.z() + 0.5;
            result.add(new IndexedHome(0, claim.id(), claim.ownerName(),
                hx, hy, hz, claim.dimensionOrDefault(), true, home != null));
        }

        List<ClaimEntry> trustedClaims = new ArrayList<>();
        for (ClaimEntry claim : claimsById.values()) {
            if (claim.ownerUuid().equals(uid)) continue;
            boolean trusted = claim.trustedOrEmpty().stream().anyMatch(t -> t.uuid().equals(uid));
            if (!trusted) continue;
            HomeAnchor home = claim.homesOrEmpty().get(uid);
            if (home == null) continue;
            trustedClaims.add(claim);
        }
        trustedClaims.sort(Comparator.comparingInt(ClaimEntry::id));
        for (ClaimEntry claim : trustedClaims) {
            HomeAnchor home = claim.homesOrEmpty().get(uid);
            result.add(new IndexedHome(0, claim.id(), claim.ownerName(),
                home.x(), home.y(), home.z(), claim.dimensionOrDefault(), false, true));
        }

        List<IndexedHome> indexed = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            IndexedHome h = result.get(i);
            indexed.add(new IndexedHome(i + 1, h.claimId(), h.claimOwnerName(),
                h.homeX(), h.homeY(), h.homeZ(), h.dimension(), h.isOwner(), h.homeSet()));
        }
        return indexed;
    }

    // ── Claim slots (land tickets) ──────────────────────────────────

    public int getClaimSlots(UUID playerUuid) {
        int stored = claimSlots.getOrDefault(playerUuid.toString(), DEFAULT_CLAIM_SLOTS);
        return Math.min(MAX_CLAIM_SLOTS, Math.max(stored, DEFAULT_CLAIM_SLOTS));
    }

    /**
     * Increments the player's claim slot count by 1, up to MAX_CLAIM_SLOTS.
     * @return true if the slot was added, false if already at max or save failed.
     */
    public boolean addClaimSlot(UUID playerUuid) {
        synchronized (claimMutationLock) {
            int current = getClaimSlots(playerUuid);
            if (current >= MAX_CLAIM_SLOTS) return false;
            String key = playerUuid.toString();
            claimSlots.put(key, current + 1);
            if (save()) return true;
            claimSlots.put(key, current);
            return false;
        }
    }

    public ClaimTransferTicketResult issueClaimTransferTicket(int claimId, UUID ownerUuid) {
        ClaimEntry previous = claimsById.get(claimId);
        if (previous == null) return ClaimTransferTicketResult.NO_CLAIM;
        if (!previous.ownerUuid().equals(ownerUuid.toString())) return ClaimTransferTicketResult.NOT_OWNER;
        if (previous.claimTransferTicketIssued()) return ClaimTransferTicketResult.ALREADY_ISSUED;
        ClaimEntry updated = previous.withClaimTransferTicketIssued(true);
        claimsById.put(claimId, updated);
        if (save()) return ClaimTransferTicketResult.SUCCESS;
        claimsById.put(claimId, previous);
        return ClaimTransferTicketResult.SAVE_FAILED;
    }

    public TransferClaimResult transferClaimOwnership(int claimId, UUID fromOwner, UUID toOwner, String toOwnerName) {
        ClaimEntry previous = claimsById.get(claimId);
        if (previous == null) return TransferClaimResult.NO_CLAIM;
        if (!previous.ownerUuid().equals(fromOwner.toString())) return TransferClaimResult.NOT_OWNER;
        String toOwnerStr = toOwner.toString();
        if (!previous.ownerUuid().equals(toOwnerStr)) {
            int recipientOwned = (int) claimsById.values().stream()
                .filter(c -> c.ownerUuid().equals(toOwnerStr))
                .count();
            int recipientSlots = getClaimSlots(toOwner);
            if (recipientOwned >= recipientSlots) {
                return TransferClaimResult.RECIPIENT_NO_CLAIM_SLOTS;
            }
        }
        Map<String, HomeAnchor> updatedHomes = new HashMap<>(previous.homesOrEmpty());
        HomeAnchor previousOwnerHome = updatedHomes.remove(fromOwner.toString());
        List<TrustedPlayer> updatedTrusted = new ArrayList<>(previous.trustedOrEmpty());
        updatedTrusted.removeIf(t -> t.uuid().equals(toOwnerStr));
        // QoL: transfer seller's sethome position to the new owner for this claim.
        if (previousOwnerHome != null && !updatedHomes.containsKey(toOwnerStr)) {
            // Transfer coordinates only; buyer should not inherit seller's /sethome cooldown.
            updatedHomes.put(toOwnerStr, new HomeAnchor(
                previousOwnerHome.x(),
                previousOwnerHome.y(),
                previousOwnerHome.z(),
                0L
            ));
        }
        ClaimEntry updated = new ClaimEntry(
            previous.id(),
            toOwnerStr,
            toOwnerName,
            previous.x(), previous.y(), previous.z(),
            previous.dimension(),
            updatedTrusted.isEmpty() ? null : updatedTrusted,
            updatedHomes.isEmpty() ? null : updatedHomes,
            true
        );
        claimsById.put(claimId, updated);
        if (save()) return TransferClaimResult.SUCCESS;
        claimsById.put(claimId, previous);
        return TransferClaimResult.SAVE_FAILED;
    }

    /**
     * Permanently removes a claim, freeing the land for future claiming.
     * The owner's used-slot count drops naturally since it is derived from owned claim count.
     */
    public ForecloseResult forecloseClaim(int claimId, UUID ownerUuid) {
        synchronized (claimMutationLock) {
            ClaimEntry previous = claimsById.get(claimId);
            if (previous == null) return ForecloseResult.NO_CLAIM;
            if (!previous.ownerUuid().equals(ownerUuid.toString())) return ForecloseResult.NOT_OWNER;
            claimsById.remove(claimId);
            if (save()) return ForecloseResult.SUCCESS;
            claimsById.put(claimId, previous);
            return ForecloseResult.SAVE_FAILED;
        }
    }

    // ── Persistence ──────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(dataFile)) return;
        try {
            String json = Files.readString(dataFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("version")) {
                loadV2(root);
            } else {
                loadV1(root);
            }
        } catch (Exception e) {
            claimsAvailable = false;
            throw new IllegalStateException("Failed to load claims; refusing startup to avoid fail-open land protection", e);
        }
    }

    /** Migrate V1 format (keyed by owner UUID, no id/homes) to V2. */
    private void loadV1(JsonObject root) {
        Map<String, ClaimEntry> legacy = GSON.fromJson(root, LEGACY_CLAIMS_TYPE);
        if (legacy == null) return;
        Map<Integer, ClaimEntry> previousClaims = new HashMap<>(claimsById);
        int previousNextClaimId = nextClaimId;
        claimsById.clear();
        int id = 1;
        for (ClaimEntry old : legacy.values()) {
            ClaimEntry migrated = new ClaimEntry(id, old.ownerUuid(), old.ownerName(),
                old.x(), old.y(), old.z(), old.dimension(), old.trusted(), null, false);
            claimsById.put(id, migrated);
            id++;
        }
        nextClaimId = id;
        boolean persisted = save();
        if (!persisted) {
            claimsById.clear();
            claimsById.putAll(previousClaims);
            nextClaimId = previousNextClaimId;
            throw new IllegalStateException("Failed to persist V1->V2 claims migration");
        }
        System.out.println("[CabalClaim] Migrated " + claimsById.size() + " claims from V1 to V2 format");
    }

    private void loadV2(JsonObject root) {
        int loadedNextClaimId = 1;
        if (root.has("nextClaimId")) {
            loadedNextClaimId = root.get("nextClaimId").getAsInt();
        }
        if (root.has("claims") && root.get("claims").isJsonObject()) {
            JsonObject claimsObj = root.getAsJsonObject("claims");
            for (String key : claimsObj.keySet()) {
                ClaimEntry entry = GSON.fromJson(claimsObj.get(key), ClaimEntry.class);
                if (entry != null) {
                    ClaimEntry normalized = new ClaimEntry(
                        entry.id(),
                        entry.ownerUuid(),
                        entry.ownerName(),
                        entry.x(),
                        entry.y(),
                        entry.z(),
                        entry.dimension(),
                        entry.trusted(),
                        entry.homes(),
                        entry.claimTransferTicketIssued()
                    );
                    claimsById.put(normalized.id(), normalized);
                }
            }
        }
        int maxExistingId = claimsById.values().stream()
            .mapToInt(ClaimEntry::id)
            .max()
            .orElse(0);
        nextClaimId = Math.max(loadedNextClaimId, maxExistingId + 1);
        if (root.has("claimSlots") && root.get("claimSlots").isJsonObject()) {
            JsonObject slotsObj = root.getAsJsonObject("claimSlots");
            for (String key : slotsObj.keySet()) {
                claimSlots.put(key, slotsObj.get(key).getAsInt());
            }
        }
    }

    private boolean save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 2);
            root.addProperty("nextClaimId", nextClaimId);

            JsonObject claimsObj = new JsonObject();
            for (Map.Entry<Integer, ClaimEntry> entry : claimsById.entrySet()) {
                claimsObj.add(String.valueOf(entry.getKey()), GSON.toJsonTree(entry.getValue()));
            }
            root.add("claims", claimsObj);

            JsonObject slotsObj = new JsonObject();
            for (Map.Entry<String, Integer> entry : claimSlots.entrySet()) {
                slotsObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("claimSlots", slotsObj);

            Files.createDirectories(dataFile.getParent());
            Files.writeString(dataFile, GSON.toJson(root));
            return true;
        } catch (IOException e) {
            System.err.println("[CabalClaim] Failed to save claims: " + e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return claimsAvailable;
    }
}
