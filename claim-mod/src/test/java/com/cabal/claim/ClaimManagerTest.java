package com.cabal.claim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClaimManagerTest {
    @TempDir
    Path tempDir;
    private ClaimManager manager;

    @BeforeEach
    void setUp() {
        manager = new ClaimManager(tempDir);
    }

    // ── Multi-claim slot system ─────────────────────────────────────

    @Test
    void firstClaimSucceedsWithDefaultSlot() {
        UUID owner = UUID.randomUUID();
        var result = manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        assertEquals(ClaimManager.ClaimResult.SUCCESS, result);
        assertNotNull(manager.getClaim(owner));
    }

    @Test
    void rejectThirdClaimWhenAtMaxSlots() {
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            int x = 500 + (i * 250);
            int z = 500 + (i * 250);
            assertEquals(ClaimManager.ClaimResult.SUCCESS,
                manager.tryClaim(owner, "Owner", x, 64, z, 0, 0, "minecraft:overworld"));
        }
        var result = manager.tryClaim(owner, "Owner", 3500, 64, 3500, 0, 0, "minecraft:overworld");
        assertEquals(ClaimManager.ClaimResult.NO_CLAIM_SLOTS, result);
    }

    @Test
    void secondClaimAllowedWithinDefaultLimit() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        var result = manager.tryClaim(owner, "Owner", 2000, 64, 2000, 0, 0, "minecraft:overworld");
        assertEquals(ClaimManager.ClaimResult.SUCCESS, result);
        assertEquals(2, manager.getClaimsByOwner(owner).size());
    }

    @Test
    void claimSlotDefaultsToTwo() {
        assertEquals(2, manager.getClaimSlots(UUID.randomUUID()));
    }

    // ── Query methods ───────────────────────────────────────────────

    @Test
    void getClaimsByOwnerReturnsSortedById() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        manager.tryClaim(owner, "Owner", 2000, 64, 2000, 0, 0, "minecraft:overworld");

        List<ClaimManager.ClaimEntry> claims = manager.getClaimsByOwner(owner);
        assertEquals(2, claims.size());
        assertTrue(claims.get(0).id() < claims.get(1).id());
    }

    @Test
    void getClaimAtReturnsCorrectClaim() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry found = manager.getClaimAt("minecraft:overworld", 510, 510);
        assertNotNull(found);
        assertEquals(owner.toString(), found.ownerUuid());
    }

    @Test
    void getClaimAtReturnsNullForUnclaimed() {
        assertNull(manager.getClaimAt("minecraft:overworld", 9999, 9999));
    }

    // ── Sethome and unified homes ───────────────────────────────────

    @Test
    void setHomeOnOwnedClaimSucceeds() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);
        var result = manager.setHome(owner, claim.id(), 510.5, 65.0, 510.5, 100);
        assertEquals(ClaimManager.SetHomeResult.SUCCESS, result);
    }

    @Test
    void setHomeCooldownPreventsReset() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);
        manager.setHome(owner, claim.id(), 510.5, 65.0, 510.5, 100);
        var result = manager.setHome(owner, claim.id(), 520.5, 65.0, 520.5, 200);
        assertEquals(ClaimManager.SetHomeResult.ON_COOLDOWN, result);
    }

    @Test
    void setHomeCooldownExpiresAfter24h() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);
        long tick = 100;
        manager.setHome(owner, claim.id(), 510.5, 65.0, 510.5, tick);
        long after24h = tick + ClaimManager.SETHOME_COOLDOWN_TICKS;
        var result = manager.setHome(owner, claim.id(), 520.5, 65.0, 520.5, after24h);
        assertEquals(ClaimManager.SetHomeResult.SUCCESS, result);
    }

    @Test
    void trustedPlayerCanSetHomeOnOwnerClaim() {
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);
        manager.addTrusted(claim.id(), owner, trusted, "Trusted");
        var result = manager.setHome(trusted, claim.id(), 505.5, 65.0, 505.5, 100);
        assertEquals(ClaimManager.SetHomeResult.SUCCESS, result);
    }

    @Test
    void untrustedPlayerCannotSetHome() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);
        var result = manager.setHome(stranger, claim.id(), 505.5, 65.0, 505.5, 100);
        assertEquals(ClaimManager.SetHomeResult.NOT_ALLOWED, result);
    }

    @Test
    void unifiedHomesIncludesOwnedClaimWithDefaultCenter() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        List<ClaimManager.IndexedHome> homes = manager.getUnifiedHomes(owner);
        assertEquals(1, homes.size());
        assertEquals(1, homes.get(0).index());
        assertTrue(homes.get(0).isOwner());
        assertFalse(homes.get(0).homeSet());
        assertEquals(500.5, homes.get(0).homeX(), 0.01);
    }

    @Test
    void unifiedHomesReflectsSetHome() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);
        manager.setHome(owner, claim.id(), 510.5, 70.0, 520.5, 100);
        List<ClaimManager.IndexedHome> homes = manager.getUnifiedHomes(owner);
        assertEquals(1, homes.size());
        assertTrue(homes.get(0).homeSet());
        assertEquals(510.5, homes.get(0).homeX(), 0.01);
        assertEquals(70.0, homes.get(0).homeY(), 0.01);
    }

    @Test
    void unifiedHomesIncludesTrustedClaimOnlyWhenHomeSet() {
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        manager.tryClaim(trusted, "Trusted", 2000, 64, 2000, 0, 0, "minecraft:overworld");

        ClaimManager.ClaimEntry ownerClaim = manager.getClaim(owner);
        manager.addTrusted(ownerClaim.id(), owner, trusted, "Trusted");

        // Trusted has no home set on owner's claim yet -> not in unified list
        List<ClaimManager.IndexedHome> homesBefore = manager.getUnifiedHomes(trusted);
        assertEquals(1, homesBefore.size()); // only their own claim

        // Set home on owner's claim
        manager.setHome(trusted, ownerClaim.id(), 505.5, 65.0, 505.5, 100);
        List<ClaimManager.IndexedHome> homesAfter = manager.getUnifiedHomes(trusted);
        assertEquals(2, homesAfter.size());
        assertTrue(homesAfter.get(0).isOwner());
        assertFalse(homesAfter.get(1).isOwner());
    }

    // ── Trust + revocation ──────────────────────────────────────────

    @Test
    void untrustRemovesTrustedPlayersHome() {
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);
        manager.addTrusted(claim.id(), owner, trusted, "Trusted");
        manager.setHome(trusted, claim.id(), 505.5, 65.0, 505.5, 100);

        // Verify home exists before untrust
        ClaimManager.ClaimEntry beforeUntrust = manager.getClaimById(claim.id());
        assertNotNull(beforeUntrust.homesOrEmpty().get(trusted.toString()));

        // Untrust should remove the home
        manager.removeTrusted(claim.id(), owner, trusted);
        ClaimManager.ClaimEntry afterUntrust = manager.getClaimById(claim.id());
        assertNull(afterUntrust.homesOrEmpty().get(trusted.toString()));

        // Trusted player should no longer see that home
        List<ClaimManager.IndexedHome> homes = manager.getUnifiedHomes(trusted);
        assertTrue(homes.stream().noneMatch(h -> h.claimId() == claim.id()));
    }

    @Test
    void trustOnMultipleClaimsUsesClaimId() {
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        manager.tryClaim(owner, "Owner", 2000, 64, 2000, 0, 0, "minecraft:overworld");

        List<ClaimManager.ClaimEntry> claims = manager.getClaimsByOwner(owner);
        assertEquals(2, claims.size());

        manager.addTrusted(claims.get(0).id(), owner, trusted, "Trusted");
        assertFalse(manager.listTrusted(claims.get(0).id()).isEmpty());
        assertTrue(manager.listTrusted(claims.get(1).id()).isEmpty());
    }

    // ── Overlap / spawn checks ──────────────────────────────────────

    @Test
    void overlapRejected() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        manager.tryClaim(a, "A", 500, 64, 500, 0, 0, "minecraft:overworld");
        var result = manager.tryClaim(b, "B", 550, 64, 550, 0, 0, "minecraft:overworld");
        assertEquals(ClaimManager.ClaimResult.OVERLAPS_EXISTING, result);
    }

    @Test
    void tooCloseToSpawnRejected() {
        UUID owner = UUID.randomUUID();
        var result = manager.tryClaim(owner, "Owner", 50, 64, 50, 0, 0, "minecraft:overworld");
        assertEquals(ClaimManager.ClaimResult.TOO_CLOSE_TO_SPAWN, result);
    }

    @Test
    void validateClaimLocationRejectsOverlapWithOwnExistingClaim() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");

        var result = manager.validateClaimLocation(550, 550, 0, 0, "minecraft:overworld");
        assertEquals(ClaimManager.ClaimResult.OVERLAPS_EXISTING, result);
    }

    @Test
    void validateClaimLocationRejectsTooCloseToSpawn() {
        var result = manager.validateClaimLocation(50, 50, 0, 0, "minecraft:overworld");
        assertEquals(ClaimManager.ClaimResult.TOO_CLOSE_TO_SPAWN, result);
    }

    // ── V1 migration ────────────────────────────────────────────────

    @Test
    void migratesV1FormatToV2OnLoad() throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String ownerUuid = UUID.randomUUID().toString();
        String v1Json = gson.toJson(Map.of(
            ownerUuid, Map.of(
                "ownerUuid", ownerUuid,
                "ownerName", "LegacyPlayer",
                "x", 300,
                "y", 70,
                "z", 400,
                "dimension", "minecraft:overworld"
            )
        ));
        Files.writeString(tempDir.resolve("claims.json"), v1Json);

        ClaimManager migrated = new ClaimManager(tempDir);
        ClaimManager.ClaimEntry claim = migrated.getClaim(UUID.fromString(ownerUuid));
        assertNotNull(claim);
        assertTrue(claim.id() > 0);
        assertEquals("LegacyPlayer", claim.ownerName());

        // File should now be V2 format
        String saved = Files.readString(tempDir.resolve("claims.json"));
        assertTrue(saved.contains("\"version\": 2"));
    }

    @Test
    void v2FormatPersistsAndReloads() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);
        manager.setHome(owner, claim.id(), 510.5, 65.0, 510.5, 100);

        ClaimManager reloaded = new ClaimManager(tempDir);
        ClaimManager.ClaimEntry reloadedClaim = reloaded.getClaim(owner);
        assertNotNull(reloadedClaim);
        assertEquals(claim.id(), reloadedClaim.id());
        assertNotNull(reloadedClaim.homesOrEmpty().get(owner.toString()));
        assertEquals(2, reloaded.getClaimSlots(owner));
    }

    @Test
    void legacyStoredSlotsBelowBaselineAreClampedToTwo() throws IOException {
        String ownerUuid = UUID.randomUUID().toString();
        String v2Json = """
            {
              "version": 2,
              "nextClaimId": 1,
              "claims": {},
              "claimSlots": {
                "%s": 1
              }
            }
            """.formatted(ownerUuid);
        Files.writeString(tempDir.resolve("claims.json"), v2Json);

        ClaimManager reloaded = new ClaimManager(tempDir);
        assertEquals(2, reloaded.getClaimSlots(UUID.fromString(ownerUuid)));
    }

    @Test
    void issueClaimTransferTicketIsOneTimeUntilOwnershipChanges() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);

        assertEquals(ClaimManager.ClaimTransferTicketResult.SUCCESS,
            manager.issueClaimTransferTicket(claim.id(), owner));
        assertEquals(ClaimManager.ClaimTransferTicketResult.ALREADY_ISSUED,
            manager.issueClaimTransferTicket(claim.id(), owner));
    }

    @Test
    void transferClaimOwnershipPreservesTrustedMembersAndKeepsTicketIssuedLocked() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        manager.tryClaim(seller, "Seller", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(seller);
        manager.addTrusted(claim.id(), seller, trusted, "Trusted");
        manager.issueClaimTransferTicket(claim.id(), seller);

        assertEquals(ClaimManager.TransferClaimResult.SUCCESS,
            manager.transferClaimOwnership(claim.id(), seller, buyer, "Buyer"));

        ClaimManager.ClaimEntry transferred = manager.getClaimById(claim.id());
        assertEquals(buyer.toString(), transferred.ownerUuid());
        assertEquals("Buyer", transferred.ownerName());
        assertFalse(transferred.trustedOrEmpty().isEmpty());
        assertTrue(transferred.claimTransferTicketIssued());
    }

    @Test
    void transferClaimOwnershipBlockedWhenRecipientAtSlotCapacity() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        manager.tryClaim(seller, "Seller", 500, 64, 500, 0, 0, "minecraft:overworld");
        for (int i = 0; i < 2; i++) {
            int x = 2000 + (i * 250);
            int z = 2000 + (i * 250);
            assertEquals(ClaimManager.ClaimResult.SUCCESS,
                manager.tryClaim(buyer, "Buyer", x, 64, z, 0, 0, "minecraft:overworld"));
        }
        ClaimManager.ClaimEntry sellerClaim = manager.getClaim(seller);

        assertEquals(ClaimManager.TransferClaimResult.RECIPIENT_NO_CLAIM_SLOTS,
            manager.transferClaimOwnership(sellerClaim.id(), seller, buyer, "Buyer"));

        ClaimManager.ClaimEntry unchanged = manager.getClaimById(sellerClaim.id());
        assertEquals(seller.toString(), unchanged.ownerUuid());
    }

    @Test
    void transferClaimOwnershipFreesSellerUsedSlot() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            int x = 500 + (i * 250);
            int z = 500 + (i * 250);
            assertEquals(ClaimManager.ClaimResult.SUCCESS,
                manager.tryClaim(seller, "Seller", x, 64, z, 0, 0, "minecraft:overworld"));
        }
        ClaimManager.ClaimEntry toSell = manager.getClaimsByOwner(seller).get(0);

        assertEquals(ClaimManager.TransferClaimResult.SUCCESS,
            manager.transferClaimOwnership(toSell.id(), seller, buyer, "Buyer"));

        // Seller should now have one free slot (1/2 used), so another claim can be created.
        assertEquals(ClaimManager.ClaimResult.SUCCESS,
            manager.tryClaim(seller, "Seller", 5000, 64, 5000, 0, 0, "minecraft:overworld"));
    }

    @Test
    void forecloseClaimRemovesOwnershipAndFreesSlot() {
        UUID owner = UUID.randomUUID();
        assertEquals(ClaimManager.ClaimResult.SUCCESS,
            manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld"));
        assertEquals(ClaimManager.ClaimResult.SUCCESS,
            manager.tryClaim(owner, "Owner", 2000, 64, 2000, 0, 0, "minecraft:overworld"));
        int claimIdToForeclose = manager.getClaimsByOwner(owner).get(0).id();

        assertEquals(ClaimManager.ForecloseResult.SUCCESS, manager.forecloseClaim(claimIdToForeclose, owner));
        assertNull(manager.getClaimById(claimIdToForeclose));
        assertEquals(1, manager.getClaimsByOwner(owner).size());
        assertEquals(ClaimManager.ClaimResult.SUCCESS,
            manager.tryClaim(owner, "Owner", 5000, 64, 5000, 0, 0, "minecraft:overworld"));
    }

    @Test
    void forecloseClaimRejectsWrongOwnerAndKeepsClaim() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        assertEquals(ClaimManager.ClaimResult.SUCCESS,
            manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld"));
        int claimId = manager.getClaim(owner).id();

        assertEquals(ClaimManager.ForecloseResult.NOT_OWNER, manager.forecloseClaim(claimId, stranger));
        assertNotNull(manager.getClaimById(claimId));
    }

    @Test
    void forecloseClaimMissingIdReturnsNoClaim() {
        int missingId = Integer.MAX_VALUE;
        assertNull(manager.getClaimById(missingId));
        assertEquals(ClaimManager.ForecloseResult.NO_CLAIM,
            manager.forecloseClaim(missingId, UUID.randomUUID()));
        assertNull(manager.getClaimById(missingId));
    }

    @Test
    void transferClaimOwnershipCopiesSellerSetHomeToBuyer() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        manager.tryClaim(seller, "Seller", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(seller);
        manager.setHome(seller, claim.id(), 612.25, 71.0, 644.75, 1234);
        manager.issueClaimTransferTicket(claim.id(), seller);

        assertEquals(ClaimManager.TransferClaimResult.SUCCESS,
            manager.transferClaimOwnership(claim.id(), seller, buyer, "Buyer"));

        ClaimManager.ClaimEntry transferred = manager.getClaimById(claim.id());
        assertNull(transferred.homesOrEmpty().get(seller.toString()));
        ClaimManager.HomeAnchor buyerHome = transferred.homesOrEmpty().get(buyer.toString());
        assertNotNull(buyerHome);
        assertEquals(612.25, buyerHome.x(), 0.001);
        assertEquals(71.0, buyerHome.y(), 0.001);
        assertEquals(644.75, buyerHome.z(), 0.001);
        assertEquals(0L, buyerHome.setTick());
    }

    @Test
    void transferClaimOwnershipKeepsBuyerHomeWhenAlreadySet() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        manager.tryClaim(seller, "Seller", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(seller);
        manager.addTrusted(claim.id(), seller, buyer, "Buyer");

        manager.setHome(seller, claim.id(), 612.25, 71.0, 644.75, 1234);
        manager.setHome(buyer, claim.id(), 701.5, 80.0, 702.5, 1235);
        manager.issueClaimTransferTicket(claim.id(), seller);

        assertEquals(ClaimManager.TransferClaimResult.SUCCESS,
            manager.transferClaimOwnership(claim.id(), seller, buyer, "Buyer"));

        ClaimManager.ClaimEntry transferred = manager.getClaimById(claim.id());
        assertNull(transferred.homesOrEmpty().get(seller.toString()));
        ClaimManager.HomeAnchor buyerHome = transferred.homesOrEmpty().get(buyer.toString());
        assertNotNull(buyerHome);
        assertEquals(701.5, buyerHome.x(), 0.001);
        assertEquals(80.0, buyerHome.y(), 0.001);
        assertEquals(702.5, buyerHome.z(), 0.001);
        assertTrue(transferred.trustedOrEmpty().stream().noneMatch(t -> t.uuid().equals(buyer.toString())));
    }

    // ── Claim-scoped trust/untrust flows ──────────────────────────

    @Test
    void trustWithExplicitClaimIdSucceeds() {
        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        manager.tryClaim(owner, "Owner", 2000, 64, 2000, 0, 0, "minecraft:overworld");

        List<ClaimManager.ClaimEntry> claims = manager.getClaimsByOwner(owner);
        int secondClaimId = claims.get(1).id();

        var result = manager.addTrusted(secondClaimId, owner, friend, "Friend");
        assertEquals(ClaimManager.TrustResult.SUCCESS, result);
        assertTrue(manager.listTrusted(claims.get(0).id()).isEmpty());
        assertFalse(manager.listTrusted(secondClaimId).isEmpty());
        assertEquals("Friend", manager.listTrusted(secondClaimId).get(0).name());
    }

    @Test
    void trustOnClaimYouDoNotOwnReturnsNoClaim() {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        manager.tryClaim(ownerA, "A", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claimA = manager.getClaim(ownerA);

        var result = manager.addTrusted(claimA.id(), ownerB, target, "Target");
        assertEquals(ClaimManager.TrustResult.NO_CLAIM, result);
    }

    @Test
    void untrustPlayerNotTrustedReturnsNotTrusted() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);

        var result = manager.removeTrusted(claim.id(), owner, stranger);
        assertEquals(ClaimManager.TrustResult.NOT_TRUSTED, result);
    }

    @Test
    void cannotTrustSelfReturnsTrustResult() {
        UUID owner = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);

        var result = manager.addTrusted(claim.id(), owner, owner, "Owner");
        assertEquals(ClaimManager.TrustResult.CANNOT_TRUST_SELF, result);
    }

    @Test
    void untrustWithExplicitClaimIdRemovesFromCorrectClaim() {
        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        manager.tryClaim(owner, "Owner", 2000, 64, 2000, 0, 0, "minecraft:overworld");

        List<ClaimManager.ClaimEntry> claims = manager.getClaimsByOwner(owner);
        manager.addTrusted(claims.get(0).id(), owner, friend, "Friend");
        manager.addTrusted(claims.get(1).id(), owner, friend, "Friend");

        var result = manager.removeTrusted(claims.get(1).id(), owner, friend);
        assertEquals(ClaimManager.TrustResult.SUCCESS, result);
        assertFalse(manager.listTrusted(claims.get(0).id()).isEmpty());
        assertTrue(manager.listTrusted(claims.get(1).id()).isEmpty());
    }

    @Test
    void listTrustedByClaimIdReturnsCorrectPlayers() {
        UUID owner = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();
        manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(owner);

        manager.addTrusted(claim.id(), owner, f1, "Alice");
        manager.addTrusted(claim.id(), owner, f2, "Bob");

        List<ClaimManager.TrustedPlayer> trusted = manager.listTrusted(claim.id());
        assertEquals(2, trusted.size());
        assertTrue(trusted.stream().anyMatch(t -> t.name().equals("Alice")));
        assertTrue(trusted.stream().anyMatch(t -> t.name().equals("Bob")));
    }

    // ── Trust persistence across ownership transfer ─────────────

    @Test
    void transferPreservesTrustAndNewOwnerCanUntrust() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID trustedA = UUID.randomUUID();
        UUID trustedB = UUID.randomUUID();

        manager.tryClaim(seller, "Seller", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(seller);

        manager.addTrusted(claim.id(), seller, trustedA, "Alice");
        manager.addTrusted(claim.id(), seller, trustedB, "Bob");
        assertEquals(2, manager.listTrusted(claim.id()).size());

        manager.issueClaimTransferTicket(claim.id(), seller);

        assertEquals(ClaimManager.TransferClaimResult.SUCCESS,
            manager.transferClaimOwnership(claim.id(), seller, buyer, "Buyer"));

        ClaimManager.ClaimEntry transferred = manager.getClaimById(claim.id());
        assertEquals(buyer.toString(), transferred.ownerUuid());

        List<ClaimManager.TrustedPlayer> postTransferTrust = manager.listTrusted(claim.id());
        assertEquals(2, postTransferTrust.size());
        assertTrue(postTransferTrust.stream().anyMatch(t -> t.name().equals("Alice")));
        assertTrue(postTransferTrust.stream().anyMatch(t -> t.name().equals("Bob")));

        var untrustResult = manager.removeTrusted(claim.id(), buyer, trustedA);
        assertEquals(ClaimManager.TrustResult.SUCCESS, untrustResult);

        List<ClaimManager.TrustedPlayer> afterUntrust = manager.listTrusted(claim.id());
        assertEquals(1, afterUntrust.size());
        assertEquals("Bob", afterUntrust.get(0).name());
    }

    @Test
    void transferPreservesTrustAndNewOwnerCanTrustAdditionalPlayers() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID trustedA = UUID.randomUUID();
        UUID newFriend = UUID.randomUUID();

        manager.tryClaim(seller, "Seller", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(seller);
        manager.addTrusted(claim.id(), seller, trustedA, "Alice");
        manager.issueClaimTransferTicket(claim.id(), seller);

        manager.transferClaimOwnership(claim.id(), seller, buyer, "Buyer");

        var trustResult = manager.addTrusted(claim.id(), buyer, newFriend, "Charlie");
        assertEquals(ClaimManager.TrustResult.SUCCESS, trustResult);

        List<ClaimManager.TrustedPlayer> trusted = manager.listTrusted(claim.id());
        assertEquals(2, trusted.size());
        assertTrue(trusted.stream().anyMatch(t -> t.name().equals("Alice")));
        assertTrue(trusted.stream().anyMatch(t -> t.name().equals("Charlie")));
    }

    @Test
    void transferRemovesSellerHomePreservesTrustedHomes() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID trustedA = UUID.randomUUID();

        manager.tryClaim(seller, "Seller", 500, 64, 500, 0, 0, "minecraft:overworld");
        ClaimManager.ClaimEntry claim = manager.getClaim(seller);
        manager.addTrusted(claim.id(), seller, trustedA, "Alice");
        manager.setHome(seller, claim.id(), 510.0, 65.0, 510.0, 100);
        manager.setHome(trustedA, claim.id(), 520.0, 65.0, 520.0, 100);
        manager.issueClaimTransferTicket(claim.id(), seller);

        manager.transferClaimOwnership(claim.id(), seller, buyer, "Buyer");

        ClaimManager.ClaimEntry transferred = manager.getClaimById(claim.id());
        assertNull(transferred.homesOrEmpty().get(seller.toString()));
        assertNotNull(transferred.homesOrEmpty().get(trustedA.toString()));
    }

    // ── Claim expansion slots ────────────────────────────────────────

    @Test
    void addClaimSlotIncrementsByOne() {
        UUID owner = UUID.randomUUID();
        assertEquals(2, manager.getClaimSlots(owner));
        assertTrue(manager.addClaimSlot(owner));
        assertEquals(3, manager.getClaimSlots(owner));
    }

    @Test
    void addClaimSlotPersistsAcrossReload() {
        UUID owner = UUID.randomUUID();
        manager.addClaimSlot(owner);
        manager.addClaimSlot(owner);
        assertEquals(4, manager.getClaimSlots(owner));

        ClaimManager reloaded = new ClaimManager(tempDir);
        assertEquals(4, reloaded.getClaimSlots(owner));
    }

    @Test
    void addClaimSlotRejectsAtMax() {
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 18; i++) {
            assertTrue(manager.addClaimSlot(owner), "addClaimSlot should succeed at step " + i);
        }
        assertEquals(20, manager.getClaimSlots(owner));
        assertFalse(manager.addClaimSlot(owner));
        assertEquals(20, manager.getClaimSlots(owner));
    }

    @Test
    void claimSucceedsAfterSlotExpansion() {
        UUID owner = UUID.randomUUID();
        // Fill default 2 slots
        assertEquals(ClaimManager.ClaimResult.SUCCESS,
            manager.tryClaim(owner, "Owner", 500, 64, 500, 0, 0, "minecraft:overworld"));
        assertEquals(ClaimManager.ClaimResult.SUCCESS,
            manager.tryClaim(owner, "Owner", 2000, 64, 2000, 0, 0, "minecraft:overworld"));
        // Third claim should fail (at limit)
        assertEquals(ClaimManager.ClaimResult.NO_CLAIM_SLOTS,
            manager.tryClaim(owner, "Owner", 4000, 64, 4000, 0, 0, "minecraft:overworld"));
        // Expand by one slot
        assertTrue(manager.addClaimSlot(owner));
        assertEquals(3, manager.getClaimSlots(owner));
        // Third claim should now succeed
        assertEquals(ClaimManager.ClaimResult.SUCCESS,
            manager.tryClaim(owner, "Owner", 4000, 64, 4000, 0, 0, "minecraft:overworld"));
    }

    @Test
    void transferStillWorksWithExpandedSlots() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        manager.addClaimSlot(buyer); // buyer has 3 slots now
        manager.tryClaim(seller, "Seller", 500, 64, 500, 0, 0, "minecraft:overworld");
        manager.tryClaim(buyer, "Buyer", 2000, 64, 2000, 0, 0, "minecraft:overworld");
        manager.tryClaim(buyer, "Buyer", 4000, 64, 4000, 0, 0, "minecraft:overworld");

        ClaimManager.ClaimEntry sellerClaim = manager.getClaim(seller);
        assertEquals(ClaimManager.TransferClaimResult.SUCCESS,
            manager.transferClaimOwnership(sellerClaim.id(), seller, buyer, "Buyer"));
        assertEquals(3, manager.getClaimsByOwner(buyer).size());
    }

    @Test
    void loadV2ClampsNextClaimIdToAvoidIdReuse() throws IOException {
        String ownerUuid = UUID.randomUUID().toString();
        String v2Json = """
            {
              "version": 2,
              "nextClaimId": 1,
              "claims": {
                "42": {
                  "id": 42,
                  "ownerUuid": "%s",
                  "ownerName": "Owner",
                  "x": 500,
                  "y": 64,
                  "z": 500,
                  "dimension": "minecraft:overworld",
                  "claimTransferTicketIssued": false
                }
              },
              "claimSlots": {}
            }
            """.formatted(ownerUuid);
        Files.writeString(tempDir.resolve("claims.json"), v2Json);

        ClaimManager reloaded = new ClaimManager(tempDir);
        ClaimManager.ClaimResult result = reloaded.tryClaim(
            UUID.randomUUID(), "NewOwner", 2000, 64, 2000, 0, 0, "minecraft:overworld");

        assertEquals(ClaimManager.ClaimResult.SUCCESS, result);
        assertNotNull(reloaded.getClaimById(42));
        assertNotNull(reloaded.getClaimById(43));
    }
}
