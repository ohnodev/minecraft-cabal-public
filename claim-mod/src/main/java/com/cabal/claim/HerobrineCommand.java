package com.cabal.claim;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HerobrineCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalChatAudit");
    private static final String HEROBRINE_NAME = "Herobrine";

    private HerobrineCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("whisperherobrine")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(HerobrineCommand::whisperHerobrine))
        );
        dispatcher.register(
            Commands.literal("wh")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(HerobrineCommand::whisperHerobrine))
        );
        dispatcher.register(
            Commands.literal("report")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(HerobrineCommand::reportPlayer)))
        );
    }

    private static int whisperHerobrine(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;

        String message = sanitize(StringArgumentType.getString(ctx, "message"));
        if (message.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("§cUsage: /wh <message>"));
            return 0;
        }

        JsonObject event = new JsonObject();
        event.addProperty("event", "CHAT_PM");
        event.addProperty("from", player.getGameProfile().name());
        event.addProperty("from_uuid", player.getUUID().toString());
        event.addProperty("cmd", "wh");
        event.addProperty("to", HEROBRINE_NAME);
        event.addProperty("message", message);
        LOGGER.info(event.toString());

        ctx.getSource().sendSuccess(
            () -> Component.literal("§7You whisper to " + HEROBRINE_NAME + ": §f" + message),
            false
        );

        return 1;
    }

    private static int reportPlayer(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer reporter = getPlayerOrFail(ctx.getSource());
        if (reporter == null) return 0;

        String target;
        try {
            target = sanitizePlayer(StringArgumentType.getString(ctx, "player"));
        } catch (IllegalArgumentException ex) {
            ctx.getSource().sendFailure(Component.literal("§c" + ex.getMessage()));
            return 0;
        }
        String reason = sanitize(StringArgumentType.getString(ctx, "reason"));
        if (target.isBlank() || reason.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("§cUsage: /report <player> <reason>"));
            return 0;
        }
        if (target.equalsIgnoreCase(reporter.getGameProfile().name())) {
            ctx.getSource().sendFailure(Component.literal("§cYou cannot report yourself."));
            return 0;
        }

        JsonObject event = new JsonObject();
        event.addProperty("event", "CHAT_REPORT");
        event.addProperty("from", reporter.getGameProfile().name());
        event.addProperty("from_uuid", reporter.getUUID().toString());
        event.addProperty("target", target);
        event.addProperty("reason", reason);
        LOGGER.info(event.toString());

        ctx.getSource().sendSuccess(
            () -> Component.literal("§aReport sent for §f" + target + "§a. Herobrine is reviewing recent chat."),
            false
        );
        return 1;
    }

    private static ServerPlayer getPlayerOrFail(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return null;
        }
    }

    private static String sanitize(String input) {
        return String.valueOf(input == null ? "" : input).replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String sanitizePlayer(String input) {
        String cleaned = sanitize(input).replaceFirst("^@", "");
        if (cleaned.length() > 16) {
            throw new IllegalArgumentException("player name exceeds maximum length");
        }
        return cleaned;
    }
}
