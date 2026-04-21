package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * /tpa &lt;player&gt; — accept a pending teleport request.
 * Teleports the requester to the accepter's current location.
 */
public class TeleportAcceptCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("tpa")
                .then(Commands.argument("requester", EntityArgument.player())
                    .executes(TeleportAcceptCommand::execute))
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer accepter;
        try {
            accepter = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /tpa"));
            return 0;
        }

        ServerPlayer requester;
        try {
            requester = EntityArgument.getPlayer(ctx, "requester");
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cPlayer not found."));
            return 0;
        }

        TeleportService svc = CabalClaimMod.getTeleportService();
        long currentTick = accepter.level().getGameTime();

        TeleportService.TpRequest req = svc.getRequest(
            accepter.getUUID(), requester.getUUID(), currentTick);
        if (req == null) {
            source.sendFailure(Component.literal(
                "\u00a7cNo pending teleport request from " + requester.getName().getString() + "."));
            return 0;
        }

        long combatRemaining = svc.remainingCombatCooldown(requester.getUUID(), currentTick);
        if (combatRemaining > 0) {
            long seconds = (combatRemaining + 19) / 20;
            source.sendFailure(Component.literal(
                "\u00a7c" + requester.getName().getString()
                    + " took damage recently. They must wait " + seconds + "s before teleporting."));
            return 0;
        }

        long useRemaining = svc.remainingUseCooldown(requester.getUUID(), currentTick);
        if (useRemaining > 0) {
            long seconds = (useRemaining + 19) / 20;
            source.sendFailure(Component.literal(
                "\u00a7c" + requester.getName().getString()
                    + " is on teleport cooldown for " + seconds + "s."));
            return 0;
        }

        ServerLevel targetLevel = (ServerLevel) accepter.level();
        requester.teleportTo(targetLevel,
            accepter.getX(), accepter.getY(), accepter.getZ(),
            Set.of(), accepter.getYRot(), accepter.getXRot(), false);
        requester.resetFallDistance();

        svc.removeRequest(accepter.getUUID(), requester.getUUID());
        svc.recordUse(requester.getUUID(), currentTick);

        requester.sendSystemMessage(Component.literal(
            "\u00a7aTeleported to \u00a7f" + accepter.getName().getString() + "\u00a7a!"));
        source.sendSuccess(
            () -> Component.literal(
                "\u00a7aAccepted teleport request from \u00a7f" + requester.getName().getString() + "\u00a7a."),
            false);

        return 1;
    }
}
