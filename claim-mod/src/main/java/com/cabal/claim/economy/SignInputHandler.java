package com.cabal.claim.economy;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SignInputHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/SignInputHandler");
    private static final long PENDING_TIMEOUT_MS = 12_000L;

    private record PendingInput(ResourceKey<Level> dimension, BlockPos pos, Consumer<String> callback, long createdAtMs) {}

    private static final Map<UUID, PendingInput> PENDING = new ConcurrentHashMap<>();

    public static void requestInput(ServerPlayer player, Consumer<String> callback) {
        UUID playerId = player.getUUID();
        PendingInput existing = PENDING.remove(playerId);
        if (existing != null) {
            restoreClientBlock(player, existing);
        }

        ServerLevel level = player.level();
        BlockPos pos = player.blockPosition().above(2);
        BlockState signState = Blocks.OAK_SIGN.defaultBlockState();
        SignBlockEntity sign = new SignBlockEntity(pos, signState);
        sign.setLevel(level);

        sign.setAllowedPlayerEditor(playerId);
        SignText text = new SignText();
        // Line 1 is intentionally left blank for user input.
        text = text.setMessage(0, Component.literal(""), Component.literal(""));
        text = text.setMessage(1, Component.literal("^^^^^^^^^^^^^"), Component.literal("^^^^^^^^^^^^^"));
        text = text.setMessage(2, Component.literal("Search"), Component.literal("Search"));
        text = text.setMessage(3, Component.literal(""), Component.literal(""));
        sign.setText(text, true);

        player.connection.send(new ClientboundBlockUpdatePacket(pos, signState));
        ClientboundBlockEntityDataPacket bePacket = sign.getUpdatePacket();
        if (bePacket != null) {
            player.connection.send(bePacket);
        }
        PendingInput pending = new PendingInput(level.dimension(), pos, callback, System.currentTimeMillis());
        PENDING.put(playerId, pending);

        // Defer editor open by two server tasks so the client reliably applies block/be packets first.
        player.level().getServer().execute(() ->
            player.level().getServer().execute(() -> {
                player.connection.send(new ClientboundBlockUpdatePacket(pos, signState));
                ClientboundBlockEntityDataPacket delayedBePacket = sign.getUpdatePacket();
                if (delayedBePacket != null) {
                    player.connection.send(delayedBePacket);
                }
                player.connection.send(new ClientboundOpenSignEditorPacket(pos, true));
            })
        );
    }

    public static boolean shouldHandleSignUpdate(ServerPlayer player, BlockPos packetPos) {
        PendingInput pending = PENDING.get(player.getUUID());
        if (pending == null) return false;
        if ((System.currentTimeMillis() - pending.createdAtMs()) > PENDING_TIMEOUT_MS) {
            if (PENDING.remove(player.getUUID(), pending)) {
                restoreClientBlock(player, pending);
            }
            return false;
        }
        if (!player.level().dimension().equals(pending.dimension())) {
            return false;
        }
        if (!pending.pos().equals(packetPos)) {
            return false;
        }
        return true;
    }

    public static void complete(ServerPlayer player, BlockPos packetPos, String line0) {
        PendingInput pending = PENDING.get(player.getUUID());
        if (pending == null) return;
        if (!pending.pos().equals(packetPos)) return;
        String value = line0 != null ? line0.trim() : "";
        // Ignore a very early empty submit caused by client focus race.
        if (value.isEmpty() && (System.currentTimeMillis() - pending.createdAtMs()) < 350L) {
            return;
        }
        if (!PENDING.remove(player.getUUID(), pending)) {
            return;
        }
        restoreClientBlock(player, pending);
        try {
            pending.callback.accept(value);
        } catch (Throwable t) {
            LOGGER.error("sign input callback failed player={} dimension={} pos={}",
                player.getUUID(), pending.dimension(), pending.pos(), t);
        }
    }

    public static void cancelForPlayer(ServerPlayer player) {
        PendingInput pending = PENDING.remove(player.getUUID());
        if (pending == null) return;
        restoreClientBlock(player, pending);
    }

    private static void restoreClientBlock(ServerPlayer player, PendingInput pending) {
        if (player.level().getServer() == null) return;
        if (!player.level().dimension().equals(pending.dimension())) return;
        ServerLevel level = player.level();
        player.connection.send(new ClientboundBlockUpdatePacket(level, pending.pos()));
        BlockEntity current = level.getBlockEntity(pending.pos());
        if (current != null) {
            Packet<?> updatePacket = current.getUpdatePacket();
            if (updatePacket != null) {
                player.connection.send(updatePacket);
            }
        }
    }

}
