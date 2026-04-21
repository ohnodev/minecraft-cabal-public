package com.cabal.claim.mixin;

import com.mojang.brigadier.ParseResults;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.Set;

@Mixin(Commands.class)
public abstract class WhisperCommandLogMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalChatAudit");
    private static final Set<String> PRIVATE_MESSAGE_COMMANDS = Set.of("msg", "tell", "w", "whisper");
    private static final String HEROBRINE_ALIAS = "@herobrine";
    private static final String HEROBRINE_PLAYER_NAME = "Herobrine";
    private static final int IDX_COMMAND_TOKEN = 0;
    private static final int IDX_ROOT = 1;
    private static final int IDX_TARGET = 2;
    private static final int IDX_MESSAGE = 3;
    private static final int IDX_ALIAS_SUBSTITUTED = 4;

    private static String[] parseAndValidatePmCommand(String commandString, boolean substituteHerobrineAlias) {
        if (commandString == null || commandString.isBlank()) {
            return null;
        }
        String trimmed = commandString.trim();
        String[] parts = trimmed.split("\\s+", 3);
        if (parts.length < 2) {
            return null;
        }

        String commandToken = parts[0];
        String root = commandToken.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
        if (!PRIVATE_MESSAGE_COMMANDS.contains(root)) {
            return null;
        }

        String rawTarget = parts[1].trim();
        boolean aliasSubstituted = substituteHerobrineAlias && rawTarget.equalsIgnoreCase(HEROBRINE_ALIAS);
        String target = aliasSubstituted ? HEROBRINE_PLAYER_NAME : rawTarget;
        target = target.replace('\n', ' ').replace('\r', ' ').trim();

        String message = parts.length >= 3 ? parts[2] : "";
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return new String[] { commandToken, root, target, message, String.valueOf(aliasSubstituted) };
    }

    private static String rewriteFromParsed(String[] parsed) {
        StringBuilder rewritten = new StringBuilder(parsed[IDX_COMMAND_TOKEN]).append(' ').append(parsed[IDX_TARGET]);
        if (!parsed[IDX_MESSAGE].isBlank()) {
            rewritten.append(' ').append(parsed[IDX_MESSAGE]);
        }
        return rewritten.toString();
    }

    private static String rewriteHerobrineAliasIfNeeded(String commandString) {
        String[] parsed = parseAndValidatePmCommand(commandString, true);
        if (parsed == null || !Boolean.parseBoolean(parsed[IDX_ALIAS_SUBSTITUTED])) {
            return commandString;
        }
        return rewriteFromParsed(parsed);
    }

    @ModifyVariable(method = "performPrefixedCommand", at = @At("HEAD"), argsOnly = true)
    private String cabalRewriteHerobrineWhisperAliasBeforeParse(String command) {
        return rewriteHerobrineAliasIfNeeded(command);
    }

    @Inject(method = "performCommand", at = @At("HEAD"))
    private void cabalLogPrivateMessageCommands(
        ParseResults<CommandSourceStack> command,
        String commandString,
        CallbackInfo ci
    ) {
        CommandSourceStack source = command.getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        String[] parsed = parseAndValidatePmCommand(commandString, true);
        if (parsed == null) {
            return;
        }
        if (parsed[IDX_MESSAGE].isEmpty()) {
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("event", "CHAT_PM");
        event.addProperty("from", player.getGameProfile().name());
        event.addProperty("from_uuid", player.getUUID().toString());
        event.addProperty("cmd", parsed[IDX_ROOT]);
        event.addProperty("to", parsed[IDX_TARGET]);
        event.addProperty("message", parsed[IDX_MESSAGE]);
        LOGGER.info(event.toString());
    }
}
