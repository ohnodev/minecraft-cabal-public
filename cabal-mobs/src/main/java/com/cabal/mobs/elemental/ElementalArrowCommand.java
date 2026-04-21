package com.cabal.mobs.elemental;

import com.cabal.mobs.items.EvokerEyeHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;

public final class ElementalArrowCommand {
    private ElementalArrowCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (ElementType element : ElementType.values()) {
            dispatcher.register(
                    Commands.literal("givearrow")
                            .requires(ElementalArrowCommand::isAdmin)
                            .then(Commands.literal(element.id())
                                    .executes(ctx -> give(ctx, element, 16))
                                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                            .executes(ctx -> give(ctx, element,
                                                    IntegerArgumentType.getInteger(ctx, "count")))))
            );
        }

        dispatcher.register(
                Commands.literal("giveevokereye")
                        .requires(ElementalArrowCommand::isAdmin)
                        .executes(ctx -> giveItem(ctx, EvokerEyeHelper.create(1), "Evoker Eye"))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> giveItem(ctx,
                                        EvokerEyeHelper.create(IntegerArgumentType.getInteger(ctx, "count")),
                                        "Evoker Eye")))
        );

    }

    private static int give(CommandContext<CommandSourceStack> ctx, ElementType element, int count) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }

        ItemStack arrows = ElementalArrowHelper.createElementalArrow(element, count);
        boolean inserted = player.getInventory().add(arrows);
        if (!inserted && !arrows.isEmpty()) {
            player.drop(arrows, false);
        }

        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aGave " + count + "x " + element.displayName() + "\u00a7a."),
                true);
        return 1;
    }

    private static int giveItem(CommandContext<CommandSourceStack> ctx, ItemStack stack, String name) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }

        int count = stack.getCount();
        boolean inserted = player.getInventory().add(stack);
        if (!inserted && !stack.isEmpty()) {
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
