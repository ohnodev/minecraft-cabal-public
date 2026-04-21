package com.cabal.claim;

import com.cabal.claim.economy.EconomyModule;
import com.cabal.claim.economy.SignInputHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.CodeSource;

public class CabalClaimMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/Bootstrap");
    private static final String[] WELCOME_LINES = {
        "\u00a76\u00a7lWelcome to Cabal SMP\u00a7r",
        "\u00a77Feel free to explore the world.",
        "\u00a7eClaim your land with \u00a7b/claim \u00a7e— a \u00a7f100-block radius \u00a7earound where you stand.",
        "\u00a77More server info: \u00a7bsmp.thecabal.app"
    };
    private static final int ENTITY_CRAMMING_LIMIT = 30;

    private static ClaimManager claimManager;
    private static PlayerCombatState combatState;
    private static TeleportService teleportService;
    private static EconomyModule economyModule;
    private static SpawnProtectionToggleManager spawnProtectionToggleManager;

    public static ClaimManager getClaimManager() {
        return claimManager;
    }

    public static PlayerCombatState getCombatState() {
        return combatState;
    }

    public static EconomyModule getEconomyModule() {
        return economyModule;
    }

    public static TeleportService getTeleportService() {
        return teleportService;
    }

    public static SpawnProtectionToggleManager getSpawnProtectionToggleManager() {
        return spawnProtectionToggleManager;
    }

    @Override
    public void onInitialize() {
        logDeployedJarFingerprint();
        claimManager = new ClaimManager(Path.of(".")); // server working directory
        combatState = new PlayerCombatState();
        teleportService = new TeleportService(combatState);
        economyModule = new EconomyModule(Path.of("."));
        spawnProtectionToggleManager = new SpawnProtectionToggleManager(Path.of("."));

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            ClaimCommand.register(dispatcher);
            HomeCommand.register(dispatcher);
            SetHomeCommand.register(dispatcher);
            MyClaimsCommand.register(dispatcher);
            MyHomesCommand.register(dispatcher);
            BuyLandTicketCommand.register(dispatcher);
            ClaimTicketCommand.register(dispatcher);
            TeleportRequestCommand.register(dispatcher);
            TeleportAcceptCommand.register(dispatcher);
            LandTrustCommand.register(dispatcher);
            HerobrineCommand.register(dispatcher);
            SpawnProtectionCommand.register(dispatcher);
            economyModule.economyCommands().register(dispatcher);
            economyModule.auctionCommand().register(dispatcher);
            economyModule.inventoryHistoryCommand().register(dispatcher);
            economyModule.backpackCommand().register(dispatcher);
            economyModule.leaderboardCommand().register(dispatcher);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Defer one tick so login / secure chat / Via are settled (same idea as protocol checks after join).
            server.execute(() -> {
                ServerPlayer player = handler.getPlayer();
                if (player == null) {
                    return;
                }
                for (String line : WELCOME_LINES) {
                    player.sendSystemMessage(Component.literal(line));
                }
                economyModule.onPlayerJoin(player);
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.getPlayer() != null) {
                ServerPlayer leaving = handler.getPlayer();
                var id = leaving.getUUID();
                teleportService.clearPlayer(id);
                combatState.remove(id);
                SignInputHandler.cancelForPlayer(leaving);
                economyModule.onPlayerLeave(leaving);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (economyModule != null) {
                economyModule.purgeStaleScoreboard(server);
            }
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "gamerule maxEntityCramming " + ENTITY_CRAMMING_LIMIT
            );
            LOGGER.info("[BOOT] Set gamerule maxEntityCramming={}", ENTITY_CRAMMING_LIMIT);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (economyModule != null) {
                economyModule.shutdown();
            }
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (entity instanceof ServerPlayer sp && damageTaken > 0) {
                combatState.recordDamage(sp.getUUID(), sp.level().getGameTime());
            }
            if (damageTaken > 0) {
                economyModule.onAfterDamage(entity, source);
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer sp) {
                economyModule.onPlayerDeath(sp, source);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            economyModule.onServerTick(server);
            if (server.overworld() != null) {
                teleportService.onServerTick(server.overworld().getGameTime());
            }
        });

        // Block breaking — owner or trusted only
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide()) return true;
            String dim = level.dimension().identifier().toString();
            if (claimManager.isAllowedAt(dim, pos.getX(), pos.getZ(), player.getUUID())) return true;
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("\u00a7cThis area is claimed. You cannot break blocks here."));
            }
            return false;
        });

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                economyModule.onBlockBreakReward(sp);
            }
        });

        // Block interaction (place, open containers, use doors/buttons/levers) — owner or trusted only
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            if (!(hitResult instanceof BlockHitResult blockHit)) return InteractionResult.PASS;
            String dim = level.dimension().identifier().toString();
            ItemStack held = player.getItemInHand(hand);
            BlockPlaceContext placeContext = new BlockPlaceContext(level, player, hand, held, blockHit);
            BlockPos target = placeContext.canPlace() ? placeContext.getClickedPos() : blockHit.getBlockPos();
            if (claimManager.isAllowedAt(dim, target.getX(), target.getZ(), player.getUUID())) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("\u00a7cThis area is claimed. You cannot interact here."));
            }
            return InteractionResult.FAIL;
        });

        // Entity interaction (right-click: item frames, armor stands, animals, vehicles) — owner or trusted only
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            if (hostileMobBypassesEntityClaims(entity)) {
                return InteractionResult.PASS;
            }
            String dim = level.dimension().identifier().toString();
            BlockPos epos = entity.blockPosition();
            if (claimManager.isAllowedAt(dim, epos.getX(), epos.getZ(), player.getUUID())) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("\u00a7cThis area is claimed. You cannot interact with this entity."));
            }
            return InteractionResult.FAIL;
        });

        // Claim Expansion Slot: right-click to consume and add +1 claim slot.
        // Other land tickets are blocked from vanilla right-click behavior.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!LandTicketHelper.isLandTicket(held)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.FAIL;

            if (LandTicketHelper.isClaimExpansionSlotTicket(held)) {
                int currentSlots = claimManager.getClaimSlots(sp.getUUID());
                if (currentSlots >= ClaimManager.MAX_CLAIM_SLOTS) {
                    sp.sendSystemMessage(Component.literal(
                        "\u00a7cYou already have the maximum " + ClaimManager.MAX_CLAIM_SLOTS
                            + " claim slots. This item was not consumed."));
                    return InteractionResult.FAIL;
                }
                boolean consumed = LandTicketHelper.consumeOneExpansionSlotFromHand(sp, hand);
                if (!consumed) {
                    sp.sendSystemMessage(Component.literal(
                        "\u00a7cCould not consume your Claim Expansion Slot item. Please try again."));
                    return InteractionResult.FAIL;
                }
                boolean added = claimManager.addClaimSlot(sp.getUUID());
                if (!added) {
                    // Roll back consumed item on add failure to avoid item loss.
                    ItemStack refund = LandTicketHelper.createClaimExpansionSlotTicket();
                    boolean inserted = sp.getInventory().add(refund);
                    if (!inserted && !refund.isEmpty()) {
                        sp.drop(refund, false);
                    }
                    sp.sendSystemMessage(Component.literal(
                        "\u00a7cFailed to expand claim slots; your claim ticket was refunded (or dropped). Please try again."));
                    return InteractionResult.FAIL;
                }
                int newSlots = claimManager.getClaimSlots(sp.getUUID());
                sp.sendSystemMessage(Component.literal(
                    "\u00a7a+1 claim slot unlocked! You now have \u00a7f"
                        + newSlots + "/" + ClaimManager.MAX_CLAIM_SLOTS + "\u00a7a claim slots."));
                return InteractionResult.SUCCESS;
            }

            if (LandTicketHelper.isSlotTicket(held)) {
                ClaimCommand.ClaimTicketUseResult claimResult = ClaimCommand.tryClaimWithHeldSlotTicket(sp, hand);
                sp.sendSystemMessage(claimResult.message());
                return claimResult.success() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }

            // Allow Land Deed writable-book interaction so owners can sign and label deeds.
            if (LandTicketHelper.isClaimTransferTicket(held)) {
                return InteractionResult.PASS;
            }

            sp.sendSystemMessage(Component.literal(
                "\u00a7eLand Tickets cannot be used directly. "
                    + "Use \u00a7b/claim\u00a7e (slot tickets) or \u00a7b/auction sell\u00a7e (Land Deed)."));
            return InteractionResult.FAIL;
        });

        // Entity attack (left-click: item frames, armor stands, animals) — owner or trusted only
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            if (hostileMobBypassesEntityClaims(entity)) {
                return InteractionResult.PASS;
            }
            String dim = level.dimension().identifier().toString();
            BlockPos epos = entity.blockPosition();
            if (claimManager.isAllowedAt(dim, epos.getX(), epos.getZ(), player.getUUID())) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("\u00a7cThis area is claimed. You cannot attack this entity."));
            }
            return InteractionResult.FAIL;
        });
    }

    /**
     * {@link MobCategory#MONSTER} mobs (illagers, zombies, creepers, etc.) ignore claim checks for
     * attack and use-entity so anyone can fight bosses or strays that appear on someone else's land.
     * Passive animals, villagers, golems, and vehicles remain protected.
     */
    private static boolean hostileMobBypassesEntityClaims(Entity entity) {
        return entity.getType().getCategory() == MobCategory.MONSTER;
    }

    private void logDeployedJarFingerprint() {
        try {
            CodeSource codeSource = CabalClaimMod.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                LOGGER.info("[BOOT] cabal-claim source=unknown hash=unavailable");
                return;
            }
            Path artifactPath = Path.of(codeSource.getLocation().toURI());
            String fileName = artifactPath.getFileName() != null ? artifactPath.getFileName().toString() : artifactPath.toString();
            String sha = sha256Hex(artifactPath);
            LOGGER.info("[BOOT] cabal-claim artifact={} sha256_12={}", fileName, sha.substring(0, Math.min(12, sha.length())));
        } catch (Exception e) {
            LOGGER.warn("[BOOT] cabal-claim fingerprint unavailable", e);
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

}
