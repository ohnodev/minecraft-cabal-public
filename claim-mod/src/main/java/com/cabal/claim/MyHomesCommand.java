package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class MyHomesCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("myhomes")
                .executes(MyHomesCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /myhomes"));
            return 0;
        }

        ClaimManager manager = CabalClaimMod.getClaimManager();
        List<ClaimManager.IndexedHome> homes = manager.getUnifiedHomes(player.getUUID());

        if (homes.isEmpty()) {
            source.sendFailure(Component.literal(
                "\u00a7cYou don't have any homes. Use \u00a7b/claim\u00a7c to claim land."));
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\u00a76Your Homes (").append(homes.size()).append("):\n");

        for (ClaimManager.IndexedHome home : homes) {
            sb.append("\u00a7a").append(home.index()).append(". ");
            if (home.isOwner()) {
                sb.append("\u00a7fYour Claim ");
            } else {
                sb.append("\u00a7f").append(home.claimOwnerName()).append("'s Claim ");
            }
            sb.append("\u00a77[").append((int) home.homeX()).append(", ")
                .append((int) home.homeZ()).append("] ");
            String dimShort = DimensionUtils.shortDimension(home.dimension());
            sb.append("\u00a78(").append(dimShort).append(") ");
            if (home.isOwner()) {
                sb.append(home.homeSet() ? "\u00a7a(Home set)" : "\u00a77(Claim center)");
            } else {
                sb.append("\u00a7e(Trusted)");
            }
            if (home.index() < homes.size()) sb.append("\n");
        }

        sb.append("\n\u00a77Use \u00a7b/home <number>\u00a77 to teleport.");

        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

}
