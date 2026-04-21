package com.cabal.claim;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last game-tick at which each player took damage.
 * Used by /home to enforce a 20-second combat cooldown.
 */
public class PlayerCombatState {
    private static final long COMBAT_COOLDOWN_TICKS = 20 * 20; // 20 seconds at 20 tps

    private final Map<UUID, Long> lastDamageTick = new ConcurrentHashMap<>();

    public void recordDamage(UUID playerId, long gameTick) {
        lastDamageTick.put(playerId, gameTick);
    }

    /** Returns 0 if the player may teleport, otherwise the remaining ticks. */
    public long remainingCooldownTicks(UUID playerId, long currentTick) {
        Long last = lastDamageTick.get(playerId);
        if (last == null) return 0;
        long elapsed = currentTick - last;
        if (elapsed >= COMBAT_COOLDOWN_TICKS) {
            lastDamageTick.remove(playerId);
            return 0;
        }
        return COMBAT_COOLDOWN_TICKS - elapsed;
    }

    public void remove(UUID playerId) {
        lastDamageTick.remove(playerId);
    }
}
