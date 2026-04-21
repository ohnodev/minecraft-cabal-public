package com.cabal.claim;

import com.cabal.claim.economy.EconomyModule;
import com.cabal.claim.economy.EconomyService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuyLandTicketCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalClaim/BuyLandTicketCommand");
    private static final double LAND_TICKET_PRICE = 250.0;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("buylandticket")
                .executes(BuyLandTicketCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /buylandticket"));
            return 0;
        }

        EconomyModule eco = CabalClaimMod.getEconomyModule();
        if (eco == null) {
            source.sendFailure(Component.literal(
                "\u00a7cEconomy system is not available. Contact an admin."));
            return 0;
        }

        EconomyService economyService = eco.economyService();
        EconomyService.BalanceChangeResult debitResult = economyService.addBalanceDetailed(
            player.getUUID(), -LAND_TICKET_PRICE, "buy_land_ticket", null);
        switch (debitResult) {
            case SUCCESS -> {
                // continue
            }
            case INSUFFICIENT_FUNDS -> {
                source.sendFailure(Component.literal(
                    "\u00a7cInsufficient funds. Land Tickets cost \u00a7e$"
                        + String.format("%.0f", LAND_TICKET_PRICE) + "\u00a7c."));
                return 0;
            }
            case STORAGE_ERROR -> {
                LOGGER.error("Failed to debit land ticket purchase for player={}", player.getUUID());
                source.sendFailure(Component.literal(
                    "\u00a7cPurchase failed due to a backend storage error. Please try again or contact staff."));
                return 0;
            }
        }

        economyService.invalidateBalanceCache(player.getUUID());

        ItemStack ticket = LandTicketHelper.createSlotTicket();
        boolean inserted = player.getInventory().add(ticket);
        if (!inserted || !ticket.isEmpty()) {
            if (!ticket.isEmpty()) {
                player.drop(ticket, false);
            }
        }

        ClaimManager manager = CabalClaimMod.getClaimManager();
        int usedClaims = manager.getClaimsByOwner(player.getUUID()).size();
        int maxClaims = manager.getClaimSlots(player.getUUID());

        source.sendSuccess(
            () -> Component.literal(
                "\u00a7aPurchased a \u00a76Land Ticket\u00a7a for \u00a7e$"
                    + String.format("%.0f", LAND_TICKET_PRICE)
                    + "\u00a7a! Hold it in your main hand and use \u00a7b/claim\u00a7a. "
                    + "\u00a77(" + usedClaims + "/" + maxClaims + " claims used)"),
            false);
        return 1;
    }
}
