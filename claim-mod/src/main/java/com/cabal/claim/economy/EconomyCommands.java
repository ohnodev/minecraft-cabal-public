package com.cabal.claim.economy;

import com.cabal.claim.CabalClaimMod;
import com.cabal.claim.ClaimManager;
import com.cabal.claim.LandTicketHelper;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EconomyCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/EconomyCommands");
    private final EconomyService economyService;
    private final EconomyDatabase db;
    private final HudService hudService;
    private final TimeRewardService timeRewardService;
    private final AuctionService auctionService;
    private final EconomyConfig config;

    public EconomyCommands(EconomyService economyService, EconomyDatabase db, HudService hudService,
                           TimeRewardService timeRewardService, AuctionService auctionService,
                           EconomyConfig config) {
        this.economyService = economyService;
        this.db = db;
        this.hudService = hudService;
        this.timeRewardService = timeRewardService;
        this.auctionService = auctionService;
        this.config = config;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("balance")
                .executes(this::balance)
        );
        dispatcher.register(
            Commands.literal("pay")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(this::pay)))
        );
        dispatcher.register(
            Commands.literal("baltop")
                .executes(this::baltop)
        );
        dispatcher.register(
            Commands.literal("eco")
                .requires(EconomyCommands::hasEcoAdminPermission)
                .then(Commands.literal("set")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> adminSet(ctx, "set")))))
                .then(Commands.literal("add")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> adminSet(ctx, "add")))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> adminSet(ctx, "remove")))))
        );
        dispatcher.register(
            Commands.literal("togglehud")
                .executes(this::toggleHud)
        );
        dispatcher.register(
            Commands.literal("timereward")
                .requires(EconomyCommands::hasEcoAdminPermission)
                .executes(this::timeRewardStatus)
        );
        dispatcher.register(
            Commands.literal("sellserver")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                    .executes(this::sellToServer))
        );
        dispatcher.register(
            Commands.literal("foreclose")
                .then(Commands.argument("claimId", IntegerArgumentType.integer(1))
                    .executes(this::foreclose))
        );
    }

    private int balance(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;
        double balance = economyService.getBalance(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("§aBalance: §f$" + String.format("%.2f", balance)), false);
        return 1;
    }

    private int pay(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer from = getPlayerOrFail(ctx.getSource());
        if (from == null) return 0;
        String targetName = StringArgumentType.getString(ctx, "player");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        Optional<GameProfile> targetProfile = ctx.getSource().getServer().services().profileResolver().fetchByName(targetName);
        if (targetProfile.isEmpty() || targetProfile.get().id() == null) {
            ctx.getSource().sendFailure(Component.literal("§cCould not resolve player: " + targetName));
            return 0;
        }
        UUID to = targetProfile.get().id();
        if (to.equals(from.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§cYou cannot pay yourself."));
            return 0;
        }
        var source = ctx.getSource();
        var server = source.getServer();
        economyService.transferAsync(from.getUUID(), to, amount, "player_pay")
            .whenComplete((ok, err) -> server.execute(() -> {
                if (err != null || Boolean.FALSE.equals(ok)) {
                    source.sendFailure(Component.literal("§cPayment failed (insufficient balance or DB error)."));
                    return;
                }
                source.sendSuccess(() -> Component.literal("§aPaid $" + String.format("%.2f", amount) + " to " + targetProfile.get().name()), false);
            }));
        return 1;
    }

    private int baltop(CommandContext<CommandSourceStack> ctx) {
        List<EconomyDatabase.TopBalance> top = db.topBalances(10);
        if (top.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§eNo balances yet."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§6Top Balances:\n");
        int rank = 1;
        for (EconomyDatabase.TopBalance entry : top) {
            String playerName = resolveDisplayName(ctx.getSource(), entry.uuid());
            sb.append("§e").append(rank).append(". §f")
                .append(playerName)
                .append(" §a$")
                .append(String.format("%.2f", entry.balance()))
                .append("\n");
            rank++;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString().stripTrailing()), false);
        return 1;
    }

    private int adminSet(CommandContext<CommandSourceStack> ctx, String mode) {
        String targetName = StringArgumentType.getString(ctx, "player");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        Optional<GameProfile> targetProfile = ctx.getSource().getServer().services().profileResolver().fetchByName(targetName);
        if (targetProfile.isEmpty() || targetProfile.get().id() == null) {
            ctx.getSource().sendFailure(Component.literal("§cCould not resolve player: " + targetName));
            return 0;
        }
        UUID id = targetProfile.get().id();
        Map<String, Object> meta = new HashMap<>();
        meta.put("admin", ctx.getSource().getTextName());
        CompletableFuture<Boolean> op = switch (mode) {
            case "set" -> economyService.setBalanceAsync(id, amount, "admin_set", meta);
            case "add" -> economyService.addBalanceAsync(id, amount, "admin_add", meta);
            case "remove" -> economyService.addBalanceAsync(id, -amount, "admin_remove", meta);
            default -> CompletableFuture.completedFuture(false);
        };
        var source = ctx.getSource();
        var server = source.getServer();
        op.whenComplete((ok, err) -> server.execute(() -> {
            if (err != null || Boolean.FALSE.equals(ok)) {
                source.sendFailure(Component.literal("§cOperation failed."));
                return;
            }
            double updated = economyService.getBalance(id);
            source.sendSuccess(() -> Component.literal("§aUpdated " + targetName + " balance to $" + String.format("%.2f", updated)), true);
        }));
        return 1;
    }

    private int toggleHud(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;
        boolean enabled = hudService.toggleForPlayer(player);
        if (enabled) {
            ctx.getSource().sendSuccess(() -> Component.literal("§aHUD enabled."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("§eHUD hidden. Run /togglehud again to show it."), false);
        }
        return 1;
    }

    private int timeRewardStatus(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§6§lTime Reward Status\n");
        sb.append("§7Enabled: §f").append(config.timeRewardEnabled).append("\n");
        sb.append("§7Rate: §f$").append(String.format("%.2f", config.timeRewardPerMinute))
            .append(" / ").append(config.timeRewardIntervalSeconds).append("s\n");
        sb.append("§7Processing cadence: §f").append(config.timeRewardProcessIntervalSeconds).append("s\n");
        sb.append("§7Daily cap: §f$").append(String.format("%.2f", config.timeRewardDailyCap)).append("\n");
        sb.append("§7Activity gate: §f").append(config.timeRewardRequireActivity)
            .append(" (").append(config.timeRewardAfkWindowSeconds).append("s AFK window)\n");
        sb.append("§7Minted this hour: §a$").append(String.format("%.2f", timeRewardService.getMintedThisHour())).append("\n");
        sb.append("§7Minted today: §a$").append(String.format("%.2f", timeRewardService.getMintedThisDay()));
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private int foreclose(CommandContext<CommandSourceStack> ctx) {
        if (!config.serverBuyEnabled) {
            ctx.getSource().sendFailure(Component.literal("§cServer buy is disabled."));
            return 0;
        }
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;

        int claimId = IntegerArgumentType.getInteger(ctx, "claimId");
        ItemStack held = player.getMainHandItem();

        if (!LandTicketHelper.isClaimTransferTicket(held)) {
            ctx.getSource().sendFailure(Component.literal(
                "§cHold the Land Deed for claim #" + claimId + " in your main hand to foreclose."));
            return 0;
        }

        int deedClaimId = LandTicketHelper.getClaimId(held);
        if (deedClaimId != claimId) {
            ctx.getSource().sendFailure(Component.literal(
                "§cThe deed in your hand is for claim #" + deedClaimId
                    + ", but you specified claim #" + claimId + "."));
            return 0;
        }

        ClaimManager manager = CabalClaimMod.getClaimManager();
        ClaimManager.ClaimEntry claim = manager.getClaimById(claimId);
        if (claim == null) {
            ctx.getSource().sendFailure(Component.literal("§cClaim #" + claimId + " no longer exists."));
            return 0;
        }
        if (!claim.ownerUuid().equals(player.getUUID().toString())) {
            ctx.getSource().sendFailure(Component.literal("§cYou do not own claim #" + claimId + "."));
            return 0;
        }

        ItemStack sold = held.copy();
        sold.setCount(1);
        double payout = auctionService.serverBuyPayout(sold);
        if (payout < 0) {
            ctx.getSource().sendFailure(Component.literal("§cCould not determine deed payout value."));
            return 0;
        }

        held.shrink(1);

        Map<String, Object> meta = new HashMap<>();
        meta.put("event", "foreclose");
        meta.put("claim_id", claimId);
        meta.put("claim_coords", "[" + claim.x() + ", " + claim.z() + "]");
        meta.put("dimension", claim.dimensionOrDefault());

        var source = ctx.getSource();
        var server = source.getServer();

        economyService.addBalanceAsync(player.getUUID(), payout, "foreclose", meta)
            .whenComplete((credited, err) -> server.execute(() -> {
                if (err != null || !Boolean.TRUE.equals(credited)) {
                    restoreItemToPlayer(player, sold);
                    source.sendFailure(Component.literal("§cForeclosure failed while crediting balance. Deed returned."));
                    return;
                }

                ClaimManager.ForecloseResult result = manager.forecloseClaim(claimId, player.getUUID());
                if (result != ClaimManager.ForecloseResult.SUCCESS) {
                    Map<String, Object> reversalMeta = new HashMap<>(meta);
                    reversalMeta.put("event", "foreclose_reversal");
                    reversalMeta.put("foreclose_result", result.name());
                    reversalMeta.put("foreclose_reversal_reason", "claim_remove_failed");
                    economyService.addBalanceAsync(player.getUUID(), -payout, "foreclose-reversal", reversalMeta)
                        .whenComplete((reversed, reversalErr) -> server.execute(() -> {
                            if (reversalErr == null && Boolean.TRUE.equals(reversed)) {
                                restoreItemToPlayer(player, sold);
                                LOGGER.warn("Foreclose claim removal failed; payout reversed: claimId={} player={} result={} payout={}",
                                    claimId, player.getUUID(), result, payout);
                                source.sendFailure(Component.literal(
                                    "§cClaim liquidation failed (result: " + result
                                        + "), payout was reversed, and your deed was returned."
                                        + staleDeedHintForForecloseResult(result)));
                                return;
                            }
                            LOGGER.error("CRITICAL foreclose failure: claimId={} player={} result={} payout={} reversed={} reversalErr={}",
                                claimId, player.getUUID(), result, payout, reversed, reversalErr);
                            source.sendFailure(Component.literal(
                                "§cClaim liquidation failed (result: " + result
                                    + ") and automatic payout reversal also failed. Please contact an admin immediately."
                                    + staleDeedHintForForecloseResult(result)));
                        }));
                    return;
                }

                int remaining = manager.getClaimsByOwner(player.getUUID()).size();
                int slots = manager.getClaimSlots(player.getUUID());
                source.sendSuccess(() -> Component.literal(
                    "§aClaim #" + claimId + " foreclosed for $" + String.format("%.2f", payout)
                        + ". §7Land is now open for claiming. ("
                        + remaining + "/" + slots + " slots used)"), false);
            }));
        return 1;
    }

    private int sellToServer(CommandContext<CommandSourceStack> ctx) {
        if (!config.serverBuyEnabled) {
            ctx.getSource().sendFailure(Component.literal("§cServer buy is disabled."));
            return 0;
        }
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        if (amount <= 0) {
            ctx.getSource().sendFailure(Component.literal("§cAmount must be at least 1."));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || held.getCount() < amount) {
            ctx.getSource().sendFailure(Component.literal("§cHold enough items in your main hand."));
            return 0;
        }
        if (LandTicketHelper.isClaimTransferTicket(held)) {
            int deedClaimId = LandTicketHelper.getClaimId(held);
            ctx.getSource().sendFailure(Component.literal(
                "§cLand Deeds must be foreclosed with §b/foreclose " + deedClaimId
                    + "§c to properly release the land."));
            return 0;
        }

        ItemStack sold = held.copy();
        sold.setCount(amount);
        Item item = held.getItem();
        double unitPrice = auctionService.serverBuyPriceWithDailyBoost(sold);
        if (unitPrice < 0) {
            ctx.getSource().sendFailure(Component.literal(
                "§cServer only buys diamond, raw iron, raw gold, netherite scrap, fireworks, Land Tickets, and Claim Expansion Slots."));
            return 0;
        }
        int requiredStack = auctionService.serverSellQuantity(sold);
        if (auctionService.isStackOnlyServerTrade(sold) && amount != requiredStack) {
            if (LandTicketHelper.isSlotTicket(sold)) {
                ctx.getSource().sendFailure(Component.literal("§cLand Tickets are non-stackable and must be sold one at a time."));
            } else if (LandTicketHelper.isClaimExpansionSlotTicket(sold)) {
                ctx.getSource().sendFailure(Component.literal("§cClaim Expansion Slots are non-stackable and must be sold one at a time."));
            } else {
                ctx.getSource().sendFailure(Component.literal("§cFireworks can only be sold as a full stack of " + requiredStack + "."));
            }
            return 0;
        }

        double payout = auctionService.serverBuyPayout(sold);
        if (payout < 0) {
            ctx.getSource().sendFailure(Component.literal("§cInvalid amount for server buy."));
            return 0;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("event", "server_buy");
        meta.put("item", AuctionService.marketItemName(item));
        meta.put("amount", amount);
        meta.put("unit_price", auctionService.isStackOnlyServerTrade(sold) ? "stack-only" : unitPrice);

        held.shrink(amount);

        var source = ctx.getSource();
        var server = source.getServer();
        economyService.addBalanceAsync(player.getUUID(), payout, "server_buy", meta)
            .whenComplete((credited, err) -> server.execute(() -> {
                if (err != null || !Boolean.TRUE.equals(credited)) {
                    restoreItemToPlayer(player, sold);
                    source.sendFailure(Component.literal("§cSale failed while crediting balance. Items returned."));
                    return;
                }
                if (auctionService.isStackOnlyServerTrade(sold)) {
                    if (LandTicketHelper.isSlotTicket(sold)) {
                        source.sendSuccess(() -> Component.literal("§aSold 1x Land Ticket for $" + String.format("%.2f", payout) + "."), false);
                    } else if (LandTicketHelper.isClaimExpansionSlotTicket(sold)) {
                        source.sendSuccess(() -> Component.literal("§aSold 1x Claim Expansion Slot for $" + String.format("%.2f", payout) + "."), false);
                    } else {
                        source.sendSuccess(() -> Component.literal("§aSold " + requiredStack + "x for $" + String.format("%.2f", payout) +
                            " (§7full stack fireworks§a)."), false);
                    }
                } else {
                    source.sendSuccess(() -> Component.literal("§aSold " + amount + "x for $" + String.format("%.2f", payout) +
                        " (§7$" + String.format("%.2f", unitPrice) + " each§a)."), false);
                }
            }));
        return 1;
    }

    private static ServerPlayer getPlayerOrFail(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return null;
        }
    }

    private static boolean hasEcoAdminPermission(CommandSourceStack source) {
        if (source.permissions() instanceof LevelBasedPermissionSet levelSet) {
            return levelSet.level().isEqualOrHigherThan(PermissionLevel.ADMINS);
        }
        return false;
    }

    private static String resolveDisplayName(CommandSourceStack source, UUID playerId) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().name();
        }
        return playerId.toString();
    }

    private static void restoreItemToPlayer(ServerPlayer player, ItemStack item) {
        ItemStack refund = item.copy();
        boolean inserted = player.getInventory().add(refund);
        if (!inserted || !refund.isEmpty()) {
            player.drop(refund, false);
        }
    }

    private static String staleDeedHintForForecloseResult(ClaimManager.ForecloseResult result) {
        if (result != ClaimManager.ForecloseResult.NOT_OWNER) return "";
        return " §eThe restored deed may now reference a claim owned by someone else, so /foreclose may keep failing.";
    }
}
