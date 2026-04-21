package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class SetHomeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("sethome")
                .executes(SetHomeCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /sethome"));
            return 0;
        }

        ClaimManager manager = CabalClaimMod.getClaimManager();
        BlockPos pos = player.blockPosition();
        String dim = player.level().dimension().identifier().toString();

        ClaimManager.ClaimEntry claim = manager.getClaimAt(dim, pos.getX(), pos.getZ());
        if (claim == null) {
            source.sendFailure(Component.literal(
                "\u00a7cYou must be standing on a claimed area to use /sethome."));
            return 0;
        }

        String uid = player.getUUID().toString();
        boolean isOwner = claim.ownerUuid().equals(uid);
        boolean isTrusted = claim.trustedOrEmpty().stream().anyMatch(t -> t.uuid().equals(uid));
        if (!isOwner && !isTrusted) {
            source.sendFailure(Component.literal(
                "\u00a7cYou are not the owner or a trusted member of this claim."));
            return 0;
        }

        long currentTick = player.level().getGameTime();
        long remaining = manager.remainingSethomeCooldown(player.getUUID(), claim.id(), currentTick);
        if (remaining > 0) {
            long hours = remaining / (20L * 60L * 60L);
            long minutes = (remaining / (20L * 60L)) % 60;
            source.sendFailure(Component.literal(
                "\u00a7c/sethome is on cooldown for this claim. " + hours + "h " + minutes + "m remaining."));
            return 0;
        }

        ClaimManager.SetHomeResult result = manager.setHome(
            player.getUUID(), claim.id(),
            player.getX(), player.getY(), player.getZ(), currentTick);

        return switch (result) {
            case SUCCESS -> {
                String label = isOwner ? "your claim" : (claim.ownerName() + "'s claim");
                source.sendSuccess(
                    () -> Component.literal(
                        "\u00a7aHome set on " + label + " at ["
                            + (int) player.getX() + ", " + (int) player.getZ() + "]!"),
                    false);
                yield 1;
            }
            case ON_COOLDOWN -> {
                // Defensive fallback: cooldown was pre-checked above, but we keep
                // this branch in case concurrent command execution races that check.
                source.sendFailure(Component.literal(
                    "\u00a7c/sethome is on cooldown for this claim."));
                yield 0;
            }
            case NOT_ON_CLAIM, NOT_ALLOWED -> {
                source.sendFailure(Component.literal(
                    "\u00a7cYou cannot set a home here."));
                yield 0;
            }
            case SAVE_FAILED -> {
                source.sendFailure(Component.literal(
                    "\u00a7cCould not save your home. Try again or contact an admin."));
                yield 0;
            }
        };
    }
}
