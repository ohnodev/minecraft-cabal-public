package com.cabal.elytra.command;

import com.cabal.elytra.ElytraBalanceConfig;
import com.cabal.elytra.wing.EvokersWingHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;

public final class ElytraAdminCommands {
    private ElytraAdminCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerAll(dispatcher));
    }

    private static void registerAll(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("givevokerswing")
                        .requires(ElytraAdminCommands::isAdmin)
                        .executes(ctx -> giveItem(ctx, EvokersWingHelper.create(1), "Evoker's Wing Lv.1"))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, ElytraBalanceConfig.EVOKERS_WING_MAX_LEVEL))
                                .executes(ctx -> {
                                    int lvl = IntegerArgumentType.getInteger(ctx, "level");
                                    return giveItem(ctx, EvokersWingHelper.create(1, lvl),
                                            "Evoker's Wing Lv." + lvl);
                                }))
        );
    }

    private static int giveItem(CommandContext<CommandSourceStack> ctx, ItemStack stack, String name) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }

        int count = stack.getCount();
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }

        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aGave " + count + "x " + name + "\u00a7a."),
                true);
        return 1;
    }

    private static boolean isAdmin(CommandSourceStack source) {
        if (source.permissions() instanceof LevelBasedPermissionSet levelSet) {
            return levelSet.level().isEqualOrHigherThan(PermissionLevel.ADMINS);
        }
        return false;
    }
}
