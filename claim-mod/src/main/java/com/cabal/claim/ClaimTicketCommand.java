package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public class ClaimTicketCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("getdeed")
                .then(Commands.argument("claimId", IntegerArgumentType.integer(1))
                    .executes(ClaimTicketCommand::execute))
        );
        dispatcher.register(
            Commands.literal("landdeed")
                .then(Commands.argument("claimId", IntegerArgumentType.integer(1))
                    .executes(ClaimTicketCommand::execute))
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /getdeed"));
            return 0;
        }

        int claimId = IntegerArgumentType.getInteger(ctx, "claimId");
        ClaimManager manager = CabalClaimMod.getClaimManager();
        ClaimManager.ClaimEntry claim = manager.getClaimById(claimId);
        if (claim == null) {
            source.sendFailure(Component.literal("\u00a7cClaim #" + claimId + " was not found."));
            return 0;
        }
        if (!claim.ownerUuid().equals(player.getUUID().toString())) {
            source.sendFailure(Component.literal("\u00a7cYou do not own claim #" + claimId + "."));
            return 0;
        }

        ClaimManager.ClaimTransferTicketResult result = manager.issueClaimTransferTicket(claimId, player.getUUID());
        return switch (result) {
            case SUCCESS -> {
                ItemStack ticket = LandTicketHelper.createClaimTransferTicket(
                    claimId, claim.ownerName(), claim.x(), claim.z(), claim.dimensionOrDefault());
                boolean inserted = player.getInventory().add(ticket);
                if (!inserted || !ticket.isEmpty()) {
                    if (!ticket.isEmpty()) {
                        player.drop(ticket, false);
                    }
                }
                source.sendSuccess(() -> Component.literal(
                    "\u00a7aIssued Land Deed for claim #" + claimId
                        + ". List this deed on auction to transfer ownership on purchase."), false);
                yield 1;
            }
            case ALREADY_ISSUED -> {
                source.sendFailure(Component.literal(
                    "\u00a7cA Land Deed has already been issued for claim #" + claimId + "."));
                yield 0;
            }
            case NO_CLAIM, NOT_OWNER -> {
                source.sendFailure(Component.literal("\u00a7cUnable to issue ticket for claim #" + claimId + "."));
                yield 0;
            }
            case SAVE_FAILED -> {
                source.sendFailure(Component.literal(
                    "\u00a7cCould not save claim ticket state. Please try again."));
                yield 0;
            }
        };
    }
}
