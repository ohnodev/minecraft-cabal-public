package com.cabal.claim;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportServiceTest {

    @Test
    void addRequestRejectsUnexpiredDuplicateAndAllowsAfterExpiry() {
        TeleportService service = new TeleportService(new PlayerCombatState());
        UUID requester = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        assertTrue(service.addRequest(requester, target, 100));
        assertFalse(service.addRequest(requester, target, 110));
        assertNull(service.getRequest(target, requester, 1301));
        assertTrue(service.addRequest(requester, target, 1301));
    }

    @Test
    void supportsMultipleRequestersPerTarget() {
        TeleportService service = new TeleportService(new PlayerCombatState());
        UUID target = UUID.randomUUID();
        UUID requesterA = UUID.randomUUID();
        UUID requesterB = UUID.randomUUID();

        assertTrue(service.addRequest(requesterA, target, 200));
        assertTrue(service.addRequest(requesterB, target, 200));

        assertNotNull(service.getRequest(target, requesterA, 300));
        assertNotNull(service.getRequest(target, requesterB, 300));
    }

    @Test
    void removeRequestOnlyRemovesSpecifiedRequester() {
        TeleportService service = new TeleportService(new PlayerCombatState());
        UUID target = UUID.randomUUID();
        UUID requesterA = UUID.randomUUID();
        UUID requesterB = UUID.randomUUID();

        service.addRequest(requesterA, target, 10);
        service.addRequest(requesterB, target, 10);

        service.removeRequest(target, requesterA);
        assertNull(service.getRequest(target, requesterA, 20));
        assertNotNull(service.getRequest(target, requesterB, 20));
    }

    @Test
    void pruneKeepsBoundaryRequestAndRemovesStrictlyOlderOnes() {
        TeleportService service = new TeleportService(new PlayerCombatState());
        UUID target = UUID.randomUUID();
        UUID boundary = UUID.randomUUID();
        UUID stale = UUID.randomUUID();

        service.addRequest(boundary, target, 1200);
        service.addRequest(stale, target, 1199);
        service.onServerTick(2400);

        assertNotNull(service.getRequest(target, boundary, 2400));
        assertNull(service.getRequest(target, stale, 2400));
    }

    @Test
    void clearPlayerRemovesCooldownAndAllRequestReferences() {
        TeleportService service = new TeleportService(new PlayerCombatState());
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        service.recordUse(player, 50);
        service.addRequest(player, other, 60);
        service.addRequest(other, player, 60);

        service.clearPlayer(player);

        assertEquals(0, service.remainingUseCooldown(player, 70));
        assertNull(service.getRequest(other, player, 70));
        assertNull(service.getRequest(player, other, 70));
    }

    @Test
    void useAndCombatCooldownsReturnRemainingTicks() {
        PlayerCombatState combatState = new PlayerCombatState();
        TeleportService service = new TeleportService(combatState);
        UUID player = UUID.randomUUID();

        combatState.recordDamage(player, 100);
        service.recordUse(player, 100);

        assertEquals(300, service.remainingCombatCooldown(player, 200));
        assertEquals(900, service.remainingUseCooldown(player, 400));
    }
}
