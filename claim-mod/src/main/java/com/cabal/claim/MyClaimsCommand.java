package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class MyClaimsCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("myclaims")
                .executes(MyClaimsCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /myclaims"));
            return 0;
        }

        ClaimManager manager = CabalClaimMod.getClaimManager();
        List<ClaimManager.ClaimEntry> claims = manager.getClaimsByOwner(player.getUUID());
        int slots = manager.getClaimSlots(player.getUUID());

        if (claims.isEmpty()) {
            source.sendFailure(Component.literal(
                "\u00a7cYou don't have any claims. Use \u00a7b/claim\u00a7c to claim land."));
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\u00a76Your Claims (\u00a7f").append(claims.size())
            .append("/").append(slots).append(" slots\u00a76):\n");

        for (int i = 0; i < claims.size(); i++) {
            ClaimManager.ClaimEntry claim = claims.get(i);
            int trustedCount = claim.trustedOrEmpty().size();
            String dimShort = DimensionUtils.shortDimension(claim.dimensionOrDefault());
            sb.append("\u00a7a").append(i + 1).append(". ")
                .append("\u00a76#").append(claim.id()).append(" ")
                .append("\u00a7f[").append(claim.x()).append(", ").append(claim.z()).append("] ")
                .append("\u00a77(").append(dimShort).append(") ")
                .append("\u00a7a- ").append(trustedCount).append(" trusted");
            if (i < claims.size() - 1) sb.append("\n");
        }

        if (claims.size() < slots) {
            sb.append("\n\u00a77You have ").append(slots - claims.size())
                .append(" unused slot(s). Use \u00a7b/claim\u00a77 to claim more land.");
        } else {
            sb.append("\n\u00a77You are at the maximum claim slots for this server.");
        }

        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

}
