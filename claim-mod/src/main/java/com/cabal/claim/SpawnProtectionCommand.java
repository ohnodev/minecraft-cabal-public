package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;

public final class SpawnProtectionCommand {
    private SpawnProtectionCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("removespawnprotection")
                .requires(SpawnProtectionCommand::hasSpawnAdminPermission)
                .executes(SpawnProtectionCommand::disableSpawnProtection)
        );

        dispatcher.register(
            Commands.literal("addspawnprotection")
                .requires(SpawnProtectionCommand::hasSpawnAdminPermission)
                .executes(SpawnProtectionCommand::enableSpawnProtection)
        );

        // Backward-compat alias: an admin requested this exact misspelling in chat.
        // Keep it as a secondary literal so old copy/pasted usage still works.
        dispatcher.register(
            Commands.literal("addspawnproteciton")
                .requires(SpawnProtectionCommand::hasSpawnAdminPermission)
                .executes(SpawnProtectionCommand::enableSpawnProtection)
        );
    }

    private static int disableSpawnProtection(CommandContext<CommandSourceStack> ctx) {
        SpawnProtectionToggleManager manager = CabalClaimMod.getSpawnProtectionToggleManager();
        if (manager == null) {
            ctx.getSource().sendFailure(Component.literal("§cSpawn-protection manager not ready."));
            return 0;
        }
        if (!manager.setDisabled(true)) {
            ctx.getSource().sendFailure(Component.literal("§cFailed to persist spawn-protection toggle."));
            return 0;
        }
        ctx.getSource().sendSuccess(
            () -> Component.literal("§aSpawn protection disabled. Non-ops can build at spawn now."),
            true
        );
        return 1;
    }

    private static int enableSpawnProtection(CommandContext<CommandSourceStack> ctx) {
        SpawnProtectionToggleManager manager = CabalClaimMod.getSpawnProtectionToggleManager();
        if (manager == null) {
            ctx.getSource().sendFailure(Component.literal("§cSpawn-protection manager not ready."));
            return 0;
        }
        if (!manager.setDisabled(false)) {
            ctx.getSource().sendFailure(Component.literal("§cFailed to persist spawn-protection toggle."));
            return 0;
        }
        ctx.getSource().sendSuccess(
            () -> Component.literal("§aSpawn protection enabled using your server spawn-protection radius."),
            true
        );
        return 1;
    }

    private static boolean hasSpawnAdminPermission(CommandSourceStack source) {
        if (source.permissions() instanceof LevelBasedPermissionSet levelSet) {
            return levelSet.level().isEqualOrHigherThan(PermissionLevel.ADMINS);
        }
        return false;
    }
}
