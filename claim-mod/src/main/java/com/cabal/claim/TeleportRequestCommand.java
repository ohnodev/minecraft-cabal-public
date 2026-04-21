package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Teleport-request flow for players.
 * Usage: /tpr &lt;player&gt;
 * Sends a request to &lt;player&gt;; they accept with /tpa &lt;requester&gt;.
 */
public class TeleportRequestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("tpr")
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(TeleportRequestCommand::execute))
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer requester;
        try {
            requester = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /tpr"));
            return 0;
        }

        ServerPlayer target;
        try {
            target = EntityArgument.getPlayer(ctx, "target");
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cPlayer not found."));
            return 0;
        }

        if (requester.getUUID().equals(target.getUUID())) {
            source.sendFailure(Component.literal("\u00a7cYou can't teleport to yourself."));
            return 0;
        }

        TeleportService svc = CabalClaimMod.getTeleportService();
        long currentTick = requester.level().getGameTime();

        long combatRemaining = svc.remainingCombatCooldown(requester.getUUID(), currentTick);
        if (combatRemaining > 0) {
            long seconds = (combatRemaining + 19) / 20;
            source.sendFailure(Component.literal(
                "\u00a7cYou took damage recently. Wait " + seconds + "s before teleporting."));
            return 0;
        }

        long useRemaining = svc.remainingUseCooldown(requester.getUUID(), currentTick);
        if (useRemaining > 0) {
            long seconds = (useRemaining + 19) / 20;
            source.sendFailure(Component.literal(
                "\u00a7cTeleport is on cooldown. Wait " + seconds + "s."));
            return 0;
        }

        boolean added = svc.addRequest(requester.getUUID(), target.getUUID(), currentTick);
        if (!added) {
            source.sendFailure(Component.literal(
                "\u00a7eYou already have an active teleport request to "
                    + target.getName().getString() + "."));
            return 0;
        }

        source.sendSuccess(
            () -> Component.literal(
                "\u00a7aTeleport request sent to \u00a7f" + target.getName().getString()
                    + "\u00a7a. They have 60s to accept with \u00a7e/tpa " + requester.getName().getString()),
            false);

        target.sendSystemMessage(Component.literal(
            "\u00a7e" + requester.getName().getString()
                + " \u00a7awants to teleport to you. Type \u00a7e/tpa "
                + requester.getName().getString() + " \u00a7ato accept."));

        return 1;
    }
}
