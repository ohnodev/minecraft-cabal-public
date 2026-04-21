package com.cabal.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

public class HomeCommand {
    private static final int SAFE_Y_SCAN_RANGE = 32;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("home")
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> execute(ctx, IntegerArgumentType.getInteger(ctx, "index"))))
                .executes(ctx -> execute(ctx, 1))
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, int index) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use /home"));
            return 0;
        }

        ClaimManager manager = CabalClaimMod.getClaimManager();
        List<ClaimManager.IndexedHome> homes = manager.getUnifiedHomes(player.getUUID());

        if (homes.isEmpty()) {
            source.sendFailure(Component.literal(
                "\u00a7cYou don't have any homes. Use /claim first to claim land."));
            return 0;
        }

        if (index < 1 || index > homes.size()) {
            source.sendFailure(Component.literal(
                "\u00a7cInvalid home index. You have " + homes.size()
                    + " home(s). Use /myhomes to see the list."));
            return 0;
        }

        ClaimManager.IndexedHome home = homes.get(index - 1);

        TeleportService svc = CabalClaimMod.getTeleportService();
        long currentTick = player.level().getGameTime();

        long combatRemaining = svc.remainingCombatCooldown(player.getUUID(), currentTick);
        if (combatRemaining > 0) {
            long seconds = (combatRemaining + 19) / 20;
            source.sendFailure(Component.literal(
                "\u00a7cYou took damage recently. Wait " + seconds + "s before using /home."));
            return 0;
        }

        long useRemaining = svc.remainingUseCooldown(player.getUUID(), currentTick);
        if (useRemaining > 0) {
            long secs = (useRemaining + 19) / 20;
            source.sendFailure(Component.literal(
                "\u00a7c/home is on cooldown. Wait " + secs + "s."));
            return 0;
        }

        String dimStr = home.dimension();
        Identifier dimId = Identifier.tryParse(dimStr);
        if (dimId == null) {
            source.sendFailure(Component.literal(
                "\u00a7cInvalid dimension in home data. Ask an admin to check claims.json."));
            return 0;
        }
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimId);
        MinecraftServer server = source.getServer();
        ServerLevel targetLevel = server.getLevel(dimKey);
        if (targetLevel == null) {
            source.sendFailure(Component.literal(
                "\u00a7cCould not find dimension: " + dimStr));
            return 0;
        }

        int safeY = findSafeY(targetLevel, (int) home.homeX(), (int) home.homeY(), (int) home.homeZ());
        if (safeY == Integer.MIN_VALUE) {
            source.sendFailure(Component.literal(
                "\u00a7cCould not find a safe spot at your home. Try /home again later."));
            return 0;
        }

        player.teleportTo(targetLevel,
            home.homeX(), safeY, home.homeZ(),
            Set.of(), player.getYRot(), player.getXRot(), false);
        player.resetFallDistance();

        svc.recordUse(player.getUUID(), currentTick);

        String label = home.isOwner() ? "your claim" : (home.claimOwnerName() + "'s claim");
        source.sendSuccess(
            () -> Component.literal(
                "\u00a7aTeleported to " + label + " at ["
                    + (int) home.homeX() + ", " + (int) home.homeZ() + "]!"),
            false);
        return 1;
    }

    /**
     * Scans vertically around startY at (x, z) for two air blocks above a solid block.
     * Returns the Y of the lower air block, or Integer.MIN_VALUE if none found.
     */
    private static int findSafeY(ServerLevel level, int x, int startY, int z) {
        int minY = Math.max(level.getMinY(), startY - SAFE_Y_SCAN_RANGE);
        int maxY = Math.min(level.getMaxY() - 1, startY + SAFE_Y_SCAN_RANGE);

        for (int y = startY; y <= maxY; y++) {
            if (isSafe(level, x, y, z)) return y;
        }
        for (int y = startY - 1; y >= minY; y--) {
            if (isSafe(level, x, y, z)) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isSafe(ServerLevel level, int x, int y, int z) {
        BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
        BlockState feet = level.getBlockState(new BlockPos(x, y, z));
        BlockState head = level.getBlockState(new BlockPos(x, y + 1, z));
        return !below.isAir() && below.getFluidState().isEmpty()
            && feet.isAir() && head.isAir();
    }
}
