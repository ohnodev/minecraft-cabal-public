package com.cabal.claim.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class BackpackCommand {
    private static final int BACKPACK_ROWS = 1;
    private static final String DEPRECATION_WARNING =
        "§eBackpack is deprecated and now withdraw-only. Please migrate your items within 14 days.";

    private final BackpackService backpackService;
    private final BackpackAuditService auditService;
    private final EconomyConfig config;

    public BackpackCommand(BackpackService backpackService, BackpackAuditService auditService, EconomyConfig config) {
        this.backpackService = backpackService;
        this.auditService = auditService;
        this.config = config;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("backpack")
                .executes(this::openBackpack)
        );
        dispatcher.register(
            Commands.literal("bp")
                .executes(this::openBackpack)
        );
    }

    private int openBackpack(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;

        if (!config.backpackEnabled) {
            ctx.getSource().sendFailure(Component.literal("§cBackpacks are currently disabled."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(DEPRECATION_WARNING), false);

        BackpackService.LoadResult result;
        try {
            result = backpackService.load(player.getUUID());
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cFailed to load your backpack. Please try again."));
            return 0;
        }

        SimpleContainer container = new SimpleContainer(BackpackService.BACKPACK_SLOTS);
        ItemStack[] loaded = result.slots();
        for (int i = 0; i < loaded.length; i++) {
            if (!loaded[i].isEmpty()) {
                container.setItem(i, loaded[i]);
            }
        }

        UUID playerId = player.getUUID();
        String playerName = player.getGameProfile().name();
        String originalHash = result.hash();
        String sessionId = UUID.randomUUID().toString();

        player.openMenu(new SimpleMenuProvider(
            (syncId, inventory, p) -> new BackpackMenu(
                syncId,
                inventory,
                container,
                playerId,
                playerName,
                backpackService,
                auditService,
                originalHash,
                sessionId
            ),
            Component.literal("§5Backpack §8(Withdraw-Only)")
        ));

        auditService.logEvent(new BackpackAuditService.AuditEvent(
            sessionId, playerId.toString(), playerName,
            "open", -1, -1, null, 0, null,
            originalHash, originalHash, null,
            System.currentTimeMillis()
        ));

        return 1;
    }

    private static ServerPlayer getPlayerOrFail(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return null;
        }
    }

    static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    static String actionName(ClickType type) {
        return switch (type) {
            case QUICK_MOVE -> "shift_click";
            case SWAP -> "swap";
            case THROW -> "drop";
            case QUICK_CRAFT -> "drag";
            default -> "click";
        };
    }

    static final class BackpackMenu extends ChestMenu {
        private final SimpleContainer backpackContainer;
        private final UUID ownerId;
        private final String playerName;
        private final BackpackService backpackService;
        private final BackpackAuditService auditService;
        private final String sessionId;
        private final String sessionOpenHash;

        private String acknowledgedHash;
        private String lastRequestedHash;
        private CompletableFuture<Boolean> latestSaveFuture = CompletableFuture.completedFuture(true);
        private int changeCount = 0;
        private boolean removed = false;

        BackpackMenu(
            int syncId,
            Inventory playerInventory,
            SimpleContainer container,
            UUID ownerId,
            String playerName,
            BackpackService backpackService,
            BackpackAuditService auditService,
            String originalHash,
            String sessionId
        ) {
            super(MenuType.GENERIC_9x1, syncId, playerInventory, container, BACKPACK_ROWS);
            this.backpackContainer = container;
            this.ownerId = ownerId;
            this.playerName = playerName;
            this.backpackService = backpackService;
            this.auditService = auditService;
            this.sessionId = sessionId;
            this.sessionOpenHash = originalHash;
            this.acknowledgedHash = originalHash;
            this.lastRequestedHash = originalHash;

            for (int i = 0; i < BackpackService.BACKPACK_SLOTS; i++) {
                Slot slot = this.slots.get(i);
                this.slots.set(i, new BackpackStorageSlot(backpackContainer, i, slot.x, slot.y));
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return player != null && player.isAlive() && ownerId.equals(player.getUUID());
        }

        @Override
        public void clicked(int slotIndex, int button, ClickType clickType, Player player) {
            ItemStack[] beforeSlots = snapshotBackpackSlots();
            String beforeHash = hashSlots(beforeSlots);
            ItemStack beforeCursor = getCarried().copy();

            super.clicked(slotIndex, button, clickType, player);

            ItemStack[] afterSlots = snapshotBackpackSlots();
            String afterHash = hashSlots(afterSlots);

            int changedSlotIndex = -1;
            for (int i = 0; i < BackpackService.BACKPACK_SLOTS; i++) {
                if (!ItemStack.matches(beforeSlots[i], afterSlots[i])) {
                    changedSlotIndex = i;
                    break;
                }
            }
            if (changedSlotIndex < 0) return;

            changeCount++;
            ItemStack changedBefore = beforeSlots[changedSlotIndex];
            ItemStack changedAfter = afterSlots[changedSlotIndex];
            ItemStack movedItem = !changedBefore.isEmpty() ? changedBefore : beforeCursor;

            String action = actionName(clickType);
            persistIfChanged(afterSlots, action);

            auditService.logEvent(new BackpackAuditService.AuditEvent(
                sessionId, ownerId.toString(), playerName,
                action, changedSlotIndex, -1,
                itemId(movedItem), movedItem.getCount(), null,
                beforeHash, afterHash,
                describeDelta(changedBefore, changedAfter),
                System.currentTimeMillis()
            ));
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) {
                return ItemStack.EMPTY;
            }

            ItemStack source = slot.getItem();
            ItemStack original = source.copy();

            // Withdraw-only policy: allow moving backpack -> player inventory, block reverse direction.
            if (index < BackpackService.BACKPACK_SLOTS) {
                if (!this.moveItemStackTo(source, BackpackService.BACKPACK_SLOTS, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                return ItemStack.EMPTY;
            }

            if (source.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            return original;
        }

        @Override
        public void removed(Player player) {
            if (!removed) {
                removed = true;
                ItemStack[] slots = snapshotBackpackSlots();
                String closeHash = hashSlots(slots);
                persistIfChanged(slots, "close");
                ensureFinalCloseSave(slots, closeHash);

                auditService.logEvent(new BackpackAuditService.AuditEvent(
                    sessionId, ownerId.toString(), playerName,
                    "close", -1, -1, null, 0, null,
                    sessionOpenHash, closeHash,
                    "changes=" + changeCount,
                    System.currentTimeMillis()
                ));
            }
            super.removed(player);
        }

        private void persistIfChanged(ItemStack[] slots, String reason) {
            String currentHash = hashSlots(slots);
            if (currentHash.isEmpty() || currentHash.equals(lastRequestedHash)) {
                return;
            }
            String originalHash = acknowledgedHash;
            latestSaveFuture = backpackService.saveAsync(ownerId, slots, reason, originalHash);
            lastRequestedHash = currentHash;
            latestSaveFuture.thenAccept(saved -> {
                if (Boolean.TRUE.equals(saved)) {
                    acknowledgedHash = currentHash;
                    lastRequestedHash = currentHash;
                } else if (currentHash.equals(lastRequestedHash)) {
                    // Save failed/rejected; allow a retry on the next interaction.
                    lastRequestedHash = acknowledgedHash;
                }
            });
        }

        private void ensureFinalCloseSave(ItemStack[] slots, String closeHash) {
            waitForSaveCompletion(latestSaveFuture, null);
            if (closeHash.isEmpty() || closeHash.equals(acknowledgedHash)) {
                return;
            }

            CompletableFuture<Boolean> closeFuture = backpackService.saveAsync(ownerId, slots, "close", acknowledgedHash);
            latestSaveFuture = closeFuture;
            lastRequestedHash = closeHash;
            waitForSaveCompletion(closeFuture, closeHash);
        }

        private void waitForSaveCompletion(CompletableFuture<Boolean> future, String successHash) {
            try {
                Boolean saved = future.get(3, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(saved) && successHash != null) {
                    acknowledgedHash = successHash;
                    lastRequestedHash = successHash;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // Best-effort close flush; menu close continues even if save confirmation times out/fails.
            }
        }

        private ItemStack[] snapshotBackpackSlots() {
            ItemStack[] slots = new ItemStack[BackpackService.BACKPACK_SLOTS];
            for (int i = 0; i < BackpackService.BACKPACK_SLOTS; i++) {
                slots[i] = backpackContainer.getItem(i).copy();
            }
            return slots;
        }

        private static String hashSlots(ItemStack[] slots) {
            byte[] nbt = BackpackService.serialize(slots);
            return nbt != null ? BackpackService.md5Hex(nbt) : "";
        }

        private String describeDelta(ItemStack before, ItemStack after) {
            if (before.isEmpty() && after.isEmpty()) return "no_change";
            if (before.isEmpty()) return "+" + after.getCount() + " " + itemId(after);
            if (after.isEmpty()) return "-" + before.getCount() + " " + itemId(before);
            if (ItemStack.isSameItemSameComponents(before, after)) {
                int diff = after.getCount() - before.getCount();
                String sign = diff >= 0 ? "+" : "";
                return sign + diff + " " + itemId(before);
            }
            return "-" + before.getCount() + " " + itemId(before) + ", +" + after.getCount() + " " + itemId(after);
        }
    }

    private static final class BackpackStorageSlot extends Slot {
        BackpackStorageSlot(SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
