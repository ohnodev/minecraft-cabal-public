package com.cabal.claim;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized teleport service shared by /home and /tp.
 * Manages a single use-cooldown timer, combat-cooldown delegation,
 * and pending teleport requests with automatic expiry.
 */
public class TeleportService {
    private static final long USE_COOLDOWN_TICKS = 60 * 20; // 60 seconds at 20 tps
    private static final long REQUEST_EXPIRY_TICKS = 60 * 20; // 60 seconds at 20 tps
    private static final long PRUNE_INTERVAL_TICKS = 10 * 20; // prune every 10 seconds

    private final PlayerCombatState combatState;

    private final Map<UUID, Long> lastUseTick = new ConcurrentHashMap<>();

    /** Keyed by target player UUID -> (requester UUID -> pending request). */
    private final Map<UUID, Map<UUID, TpRequest>> pendingRequests = new ConcurrentHashMap<>();
    private volatile long lastPruneTick = Long.MIN_VALUE;

    public TeleportService(PlayerCombatState combatState) {
        this.combatState = combatState;
    }

    // ── shared cooldowns ────────────────────────────────────────────

    /**
     * Returns remaining combat-cooldown ticks, or 0 if the player may teleport.
     */
    public long remainingCombatCooldown(UUID playerId, long currentTick) {
        return combatState.remainingCooldownTicks(playerId, currentTick);
    }

    /** Returns remaining use-cooldown ticks, or 0 if the player may teleport. */
    public long remainingUseCooldown(UUID playerId, long currentTick) {
        Long last = lastUseTick.get(playerId);
        if (last == null) return 0;
        long elapsed = currentTick - last;
        if (elapsed >= USE_COOLDOWN_TICKS) {
            lastUseTick.remove(playerId);
            return 0;
        }
        return USE_COOLDOWN_TICKS - elapsed;
    }

    public void recordUse(UUID playerId, long currentTick) {
        lastUseTick.put(playerId, currentTick);
    }

    // ── pending TP requests ─────────────────────────────────────────

    /**
     * Adds a request from requester -> target unless an unexpired duplicate already exists.
     *
     * @return true if a new request was added; false if an unexpired duplicate prevented insertion
     */
    public boolean addRequest(UUID requester, UUID target, long currentTick) {
        Map<UUID, TpRequest> byRequester = pendingRequests.computeIfAbsent(
            target, ignored -> new ConcurrentHashMap<>());
        TpRequest existing = byRequester.get(requester);
        if (existing != null && currentTick - existing.createdTick() <= REQUEST_EXPIRY_TICKS) {
            return false;
        }
        byRequester.put(requester, new TpRequest(requester, currentTick));
        return true;
    }

    /**
     * Returns the pending request targeting {@code target} from {@code requester},
     * or {@code null} if none exists or it has expired.
     */
    public TpRequest getRequest(UUID target, UUID requester, long currentTick) {
        Map<UUID, TpRequest> byRequester = pendingRequests.get(target);
        if (byRequester == null) return null;
        TpRequest req = byRequester.get(requester);
        if (req == null) return null;
        if (currentTick - req.createdTick() > REQUEST_EXPIRY_TICKS) {
            byRequester.remove(requester);
            if (byRequester.isEmpty()) {
                pendingRequests.remove(target);
            }
            return null;
        }
        return req;
    }

    public void removeRequest(UUID target, UUID requester) {
        Map<UUID, TpRequest> byRequester = pendingRequests.get(target);
        if (byRequester == null) return;
        byRequester.remove(requester);
        if (byRequester.isEmpty()) {
            pendingRequests.remove(target);
        }
    }

    // ── cleanup ─────────────────────────────────────────────────────

    /** Clears all teleport state for a disconnecting player. */
    public void clearPlayer(UUID playerId) {
        lastUseTick.remove(playerId);
        pendingRequests.remove(playerId);
        pendingRequests.values().forEach(byRequester -> byRequester.remove(playerId));
        pendingRequests.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /** Periodic cleanup entrypoint, intended to be called from a server tick hook. */
    public void onServerTick(long currentTick) {
        if (lastPruneTick != Long.MIN_VALUE && currentTick - lastPruneTick < PRUNE_INTERVAL_TICKS) {
            return;
        }
        pruneStale(currentTick);
        lastPruneTick = currentTick;
    }

    private void pruneStale(long currentTick) {
        long useCutoff = currentTick - USE_COOLDOWN_TICKS;
        lastUseTick.entrySet().removeIf(e -> e.getValue() <= useCutoff);

        long reqCutoff = currentTick - REQUEST_EXPIRY_TICKS;
        pendingRequests.values().forEach(byRequester ->
            byRequester.entrySet().removeIf(e -> e.getValue().createdTick() < reqCutoff));
        pendingRequests.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    // ── inner record ────────────────────────────────────────────────

    public record TpRequest(UUID requester, long createdTick) {}
}
