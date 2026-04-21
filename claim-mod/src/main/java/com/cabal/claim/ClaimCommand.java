package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClaimCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalClaim/ClaimCommand");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("claim")
                .executes(ClaimCommand::execute)
        );
    }

    public record ClaimTicketUseResult(boolean success, Component message) {}

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /claim"));
            return 0;
        }

        ClaimTicketUseResult result = tryClaimWithHeldSlotTicket(player, InteractionHand.MAIN_HAND);
        if (result.success()) {
            source.sendSuccess(() -> result.message(), true);
            return 1;
        }
        source.sendFailure(result.message());
        return 0;
    }

    public static ClaimTicketUseResult tryClaimWithHeldSlotTicket(ServerPlayer player, InteractionHand hand) {
        BlockPos pos = player.blockPosition();
        ServerLevel level = player.level();
        BlockPos spawnPos = level.getRespawnData().pos();

        ResourceKey<Level> worldKey = level.dimension();
        String dimension = worldKey.identifier().toString();

        ClaimManager manager = CabalClaimMod.getClaimManager();

        if (!LandTicketHelper.isSlotTicket(player.getItemInHand(hand))) {
            String handLabel = hand == InteractionHand.MAIN_HAND ? "main hand" : "off hand";
            return new ClaimTicketUseResult(false, Component.literal(
                "\u00a7cHold a \u00a76Land Ticket\u00a7c in your " + handLabel + " to use \u00a7b/claim\u00a7c."));
        }

        int used = manager.getClaimsByOwner(player.getUUID()).size();
        int max = manager.getClaimSlots(player.getUUID());
        if (used > max) {
            LOGGER.error("Claim slot invariant violated for player={} used={} max={}",
                player.getUUID(), used, max);
            return new ClaimTicketUseResult(false, Component.literal(
                "\u00a7cClaim slot data appears inconsistent (" + used + "/" + max + "). "
                    + "\u00a77Please contact an admin."
            ));
        }

        boolean consumed = LandTicketHelper.consumeOneSlotTicketFromHand(player, hand);
        if (!consumed) {
            return new ClaimTicketUseResult(false, Component.literal(
                "\u00a7cCould not consume your held Land Ticket. Try again."));
        }

        ClaimManager.ClaimResult result = manager.tryClaim(
            player.getUUID(),
            player.getGameProfile().name(),
            pos.getX(), pos.getY(), pos.getZ(),
            spawnPos.getX(), spawnPos.getZ(),
            dimension
        );

        switch (result) {
            case SUCCESS -> {
                int owned = manager.getClaimsByOwner(player.getUUID()).size();
                int slots = manager.getClaimSlots(player.getUUID());
                return new ClaimTicketUseResult(true, Component.literal(
                    "\u00a7aClaim created! You own a " + ClaimManager.CLAIM_RADIUS +
                    "-block radius around [" + pos.getX() + ", " + pos.getZ() + "]. "
                    + "\u00a77(" + owned + "/" + slots + " slots used)"
                ));
            }
            case TOO_CLOSE_TO_SPAWN -> {
                refundSlotTicket(player, hand);
                return new ClaimTicketUseResult(false, Component.literal(
                    "\u00a7cToo close to spawn! Land Ticket refunded."));
            }
            case NO_CLAIM_SLOTS -> {
                int usedNow = manager.getClaimsByOwner(player.getUUID()).size();
                int maxNow = manager.getClaimSlots(player.getUUID());
                String hint = maxNow < ClaimManager.MAX_CLAIM_SLOTS
                    ? "Buy a \u00a7aClaim Expansion Slot\u00a77 from the auction house to unlock more, "
                        + "or use \u00a7b/foreclose <claimId>\u00a77 to sell a deed and free a slot."
                    : "Use \u00a7b/getdeed <claimId>\u00a77 to list land on auction, "
                        + "or \u00a7b/foreclose <claimId>\u00a77 to sell to server and free a slot.";
                Component msg = Component.literal(
                    "\u00a7cYou've used up " + usedNow + "/" + maxNow + " claim slots. "
                        + "\u00a77" + hint + " Land Ticket refunded.");
                refundSlotTicket(player, hand);
                return new ClaimTicketUseResult(false, msg);
            }
            case OVERLAPS_EXISTING -> {
                refundSlotTicket(player, hand);
                return new ClaimTicketUseResult(false, Component.literal(
                    "\u00a7cThis area overlaps another player's claim. Land Ticket refunded."));
            }
            case SAVE_FAILED -> {
                refundSlotTicket(player, hand);
                return new ClaimTicketUseResult(false, Component.literal(
                    "\u00a7cCould not save your claim to disk. Land Ticket refunded."));
            }
        }
        return new ClaimTicketUseResult(false, Component.literal("\u00a7cClaim failed."));
    }

    private static void refundSlotTicket(ServerPlayer player, InteractionHand hand) {
        ItemStack refund = LandTicketHelper.createSlotTicket();
        ItemStack handStack = player.getItemInHand(hand);
        if (handStack.isEmpty()) {
            player.setItemInHand(hand, refund);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return;
        }
        if (ItemStack.isSameItemSameComponents(handStack, refund) && handStack.getCount() < handStack.getMaxStackSize()) {
            handStack.grow(1);
            player.setItemInHand(hand, handStack);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return;
        }
        boolean inserted = player.getInventory().add(refund);
        if (!inserted && !refund.isEmpty()) {
            player.drop(refund, false);
        }
    }
}
