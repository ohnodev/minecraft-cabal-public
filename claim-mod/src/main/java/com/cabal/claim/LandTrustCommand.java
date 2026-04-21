package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class LandTrustCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("landtrust")
                .then(Commands.argument("claimId", IntegerArgumentType.integer(1))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(LandTrustCommand::executeTrust)))
        );
        dispatcher.register(
            Commands.literal("landuntrust")
                .then(Commands.argument("claimId", IntegerArgumentType.integer(1))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(LandTrustCommand::executeUntrust)))
        );
        dispatcher.register(
            Commands.literal("landlist")
                .then(Commands.argument("claimId", IntegerArgumentType.integer(1))
                    .executes(LandTrustCommand::executeList))
        );
    }

    private static int executeTrust(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer caller = getPlayerOrFail(source);
        if (caller == null) return 0;

        ClaimManager manager = CabalClaimMod.getClaimManager();
        int claimId = IntegerArgumentType.getInteger(ctx, "claimId");
        ClaimManager.ClaimEntry targetClaim = resolveOwnedClaimById(manager, caller, source, claimId);
        if (targetClaim == null) return 0;

        String targetName = StringArgumentType.getString(ctx, "player");
        Optional<GameProfile> resolved = source.getServer().services().profileResolver().fetchByName(targetName);
        if (resolved.isEmpty()) {
            source.sendFailure(Component.literal(
                "\u00a7cCould not resolve player name \u00a7e" + targetName + "\u00a7c."));
            return 0;
        }
        GameProfile profile = resolved.get();
        UUID targetId = profile.id();
        if (targetId == null) {
            source.sendFailure(Component.literal(
                "\u00a7cCould not resolve UUID for \u00a7e" + targetName + "\u00a7c."));
            return 0;
        }
        String resolvedName = profile.name() != null ? profile.name() : targetName;

        ClaimManager.TrustResult result = manager.addTrusted(
            targetClaim.id(), caller.getUUID(), targetId, resolvedName);

        return switch (result) {
            case SUCCESS -> {
                source.sendSuccess(
                    () -> Component.literal(
                        "\u00a7a" + resolvedName + " is now trusted on your land."),
                    false);
                ServerPlayer online = source.getServer().getPlayerList().getPlayer(targetId);
                if (online != null) {
                    online.sendSystemMessage(Component.literal(
                        "\u00a7a" + caller.getGameProfile().name() + " has trusted you on their land!"));
                }
                yield 1;
            }
            case CANNOT_TRUST_SELF -> {
                source.sendFailure(Component.literal("\u00a7cYou cannot trust yourself."));
                yield 0;
            }
            case ALREADY_TRUSTED -> {
                source.sendFailure(Component.literal(
                    "\u00a7c" + resolvedName + " is already trusted."));
                yield 0;
            }
            case SAVE_FAILED -> {
                source.sendFailure(Component.literal(
                    "\u00a7cCould not save trust list to disk. Try again or contact an admin."));
                yield 0;
            }
            default -> 0;
        };
    }

    private static int executeUntrust(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer caller = getPlayerOrFail(source);
        if (caller == null) return 0;

        ClaimManager manager = CabalClaimMod.getClaimManager();
        int claimId = IntegerArgumentType.getInteger(ctx, "claimId");
        ClaimManager.ClaimEntry targetClaim = resolveOwnedClaimById(manager, caller, source, claimId);
        if (targetClaim == null) return 0;

        String targetName = StringArgumentType.getString(ctx, "player");
        Optional<GameProfile> resolved = source.getServer().services().profileResolver().fetchByName(targetName);
        if (resolved.isEmpty()) {
            source.sendFailure(Component.literal(
                "\u00a7cCould not resolve player name \u00a7e" + targetName + "\u00a7c."));
            return 0;
        }
        GameProfile profile = resolved.get();
        UUID targetId = profile.id();
        if (targetId == null) {
            source.sendFailure(Component.literal(
                "\u00a7cCould not resolve UUID for \u00a7e" + targetName + "\u00a7c."));
            return 0;
        }
        String resolvedName = profile.name() != null ? profile.name() : targetName;

        ClaimManager.TrustResult result = manager.removeTrusted(
            targetClaim.id(), caller.getUUID(), targetId);

        return switch (result) {
            case SUCCESS -> {
                source.sendSuccess(
                    () -> Component.literal(
                        "\u00a7a" + resolvedName + " is no longer trusted on your land. "
                            + "\u00a77(Their home on this claim has been removed.)"),
                    false);
                ServerPlayer online = source.getServer().getPlayerList().getPlayer(targetId);
                if (online != null) {
                    online.sendSystemMessage(Component.literal(
                        "\u00a7e" + caller.getGameProfile().name() + " has removed your trust on their land."));
                }
                yield 1;
            }
            case NOT_TRUSTED -> {
                source.sendFailure(Component.literal(
                    "\u00a7c" + resolvedName + " is not on your trust list."));
                yield 0;
            }
            case SAVE_FAILED -> {
                source.sendFailure(Component.literal(
                    "\u00a7cCould not save trust list to disk. Try again or contact an admin."));
                yield 0;
            }
            default -> 0;
        };
    }

    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer caller = getPlayerOrFail(source);
        if (caller == null) return 0;

        ClaimManager manager = CabalClaimMod.getClaimManager();
        int claimId = IntegerArgumentType.getInteger(ctx, "claimId");
        ClaimManager.ClaimEntry targetClaim = resolveOwnedClaimById(manager, caller, source, claimId);
        if (targetClaim == null) return 0;

        List<ClaimManager.TrustedPlayer> trusted = manager.listTrusted(targetClaim.id());
        if (trusted.isEmpty()) {
            source.sendSuccess(
                () -> Component.literal("\u00a7eNo players are trusted on this claim."),
                false);
            return 1;
        }

        StringBuilder sb = new StringBuilder(
            "\u00a7eTrusted players on claim [" + targetClaim.x() + ", " + targetClaim.z() + "] ("
                + trusted.size() + "):\n");
        for (ClaimManager.TrustedPlayer tp : trusted) {
            sb.append("\u00a7a - ").append(tp.name()).append("\n");
        }
        source.sendSuccess(() -> Component.literal(sb.toString().stripTrailing()), false);
        return 1;
    }

    private static ClaimManager.ClaimEntry resolveOwnedClaimById(
            ClaimManager manager, ServerPlayer caller, CommandSourceStack source, int claimId) {
        ClaimManager.ClaimEntry claim = manager.getClaimById(claimId);
        if (claim == null) {
            source.sendFailure(Component.literal("\u00a7cClaim #" + claimId + " was not found."));
            return null;
        }
        if (!claim.ownerUuid().equals(caller.getUUID().toString())) {
            source.sendFailure(Component.literal(
                "\u00a7cClaim #" + claimId + " is not owned by you."));
            return null;
        }
        return claim;
    }

    private static ServerPlayer getPlayerOrFail(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return null;
        }
    }
}
