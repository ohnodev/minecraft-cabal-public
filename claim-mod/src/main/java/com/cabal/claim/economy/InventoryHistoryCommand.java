package com.cabal.claim.economy;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
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
import net.minecraft.world.item.component.ItemLore;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InventoryHistoryCommand {
    private static final int BROWSER_ROWS = 6;
    private static final int BROWSER_SIZE = 9 * BROWSER_ROWS;
    private static final int NAV_ROW_START = 45;
    private static final int ITEMS_PER_PAGE = NAV_ROW_START;
    private static final int PREV_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int CLOSE_SLOT = 49;

    private static final int PREVIEW_ROWS = 6;
    private static final int PREVIEW_SIZE = 9 * PREVIEW_ROWS;
    private static final int CONFIRM_SLOT = 45;
    private static final int CANCEL_RESTORE_SLOT = 53;
    private static final int BACK_SLOT = 49;
    private static final int CONFIRM_MENU_CANCEL_SLOT = 2;
    private static final int CONFIRM_MENU_CONFIRM_SLOT = 6;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final InventoryHistoryService historyService;

    public InventoryHistoryCommand(InventoryHistoryService historyService) {
        this.historyService = historyService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("invhistory")
                .requires(InventoryHistoryCommand::hasAdminPermission)
                .then(Commands.literal("see")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(this::openBrowser)))
                .then(Commands.literal("restore")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("snapshotId", LongArgumentType.longArg(1))
                            .executes(this::cliRestore))))
        );
    }

    private int openBrowser(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = getPlayerOrFail(ctx.getSource());
        if (admin == null) return 0;
        String targetName = StringArgumentType.getString(ctx, "player");
        Optional<GameProfile> targetProfile = ctx.getSource().getServer().services().profileResolver().fetchByName(targetName);
        if (targetProfile.isEmpty() || targetProfile.get().id() == null) {
            ctx.getSource().sendFailure(Component.literal("§cCould not resolve player: " + targetName));
            return 0;
        }
        UUID targetId = targetProfile.get().id();
        openBrowserPage(admin, targetId, targetName, 0);
        return 1;
    }

    private void openBrowserPage(ServerPlayer admin, UUID targetId, String targetName, int page) {
        BrowserState state = buildBrowserState(targetId, targetName, page);
        admin.openMenu(new SimpleMenuProvider(
            (syncId, inventory, p) -> new SnapshotBrowserMenu(syncId, inventory, state),
            Component.literal("Inventory History: " + targetName + (page > 0 ? " (p" + (page + 1) + ")" : ""))
        ));
    }

    private BrowserState buildBrowserState(UUID targetId, String targetName, int page) {
        int offset = page * ITEMS_PER_PAGE;
        List<InventoryHistoryService.SnapshotMeta> snapshots = historyService.listSnapshots(targetId, ITEMS_PER_PAGE + 1, offset);
        boolean hasNext = snapshots.size() > ITEMS_PER_PAGE;
        if (hasNext) snapshots = snapshots.subList(0, ITEMS_PER_PAGE);

        SimpleContainer container = new SimpleContainer(BROWSER_SIZE);
        Map<Integer, Long> slotSnapshotIds = new HashMap<>();

        for (int i = 0; i < snapshots.size(); i++) {
            InventoryHistoryService.SnapshotMeta snap = snapshots.get(i);
            ItemStack icon = iconForReason(snap.reason());
            icon.set(DataComponents.CUSTOM_NAME, Component.literal(
                "§e" + reasonLabel(snap.reason()) + " §7— #" + snap.id()
            ));
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Time: §f" + DATE_FMT.format(Instant.ofEpochSecond(snap.ts())) + " UTC"),
                Component.literal("§7Reason: §f" + snap.reason()),
                Component.literal("§7Snapshot ID: §f" + snap.id()),
                Component.literal(""),
                Component.literal("§aClick to preview & restore")
            )));
            container.setItem(i, icon);
            slotSnapshotIds.put(i, snap.id());
        }

        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = NAV_ROW_START; i < BROWSER_SIZE; i++) {
            container.setItem(i, filler.copy());
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("§ePrevious Page"));
            container.setItem(PREV_SLOT, prev);
        }
        if (hasNext) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("§eNext Page"));
            container.setItem(NEXT_SLOT, next);
        }
        ItemStack close = new ItemStack(Items.BARRIER);
        close.set(DataComponents.CUSTOM_NAME, Component.literal("§cClose"));
        container.setItem(CLOSE_SLOT, close);

        return new BrowserState(container, slotSnapshotIds, targetId, targetName, page, hasNext);
    }

    private void openPreview(ServerPlayer admin, UUID targetId, String targetName, long snapshotId, int returnPage) {
        byte[] blob = historyService.getSnapshotBlob(snapshotId);
        InventoryHistoryService.SnapshotMeta meta = historyService.getSnapshotMeta(snapshotId);
        if (blob == null || meta == null) {
            admin.sendSystemMessage(Component.literal("§cSnapshot not found."));
            return;
        }

        CompoundTag nbt;
        try {
            nbt = InventoryHistoryService.deserializeInventoryNbt(blob);
        } catch (Exception e) {
            admin.sendSystemMessage(Component.literal("§cCorrupted snapshot data."));
            return;
        }

        SimpleContainer container = new SimpleContainer(PREVIEW_SIZE);

        ListTag slots = nbt.getListOrEmpty("Slots");
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag entry = slots.getCompound(i).orElse(null);
            if (entry == null) continue;
            int slot = entry.getInt("Slot").orElse(-1);
            if (slot < 0 || slot >= PREVIEW_SIZE) continue;
            if (entry.getBoolean("Empty").orElse(false)) continue;
            CompoundTag itemTag = entry.getCompound("Item").orElse(null);
            if (itemTag == null) continue;
            ItemStack stack = ItemStack.CODEC.parse(NbtOps.INSTANCE, itemTag).result().orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) {
                container.setItem(slot, stack);
            }
        }

        ItemStack confirmBtn = new ItemStack(Items.LIME_DYE);
        confirmBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lRESTORE this snapshot"));
        confirmBtn.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Snapshot #" + snapshotId),
            Component.literal("§7Time: §f" + DATE_FMT.format(Instant.ofEpochSecond(meta.ts())) + " UTC"),
            Component.literal("§7Reason: §f" + meta.reason()),
            Component.literal(""),
            Component.literal("§c§lThis will REPLACE the player's current inventory!")
        )));
        container.setItem(CONFIRM_SLOT, confirmBtn);

        ItemStack backBtn = new ItemStack(Items.ARROW);
        backBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§eBack to list"));
        container.setItem(BACK_SLOT, backBtn);

        ItemStack cancelBtn = new ItemStack(Items.BARRIER);
        cancelBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§cCancel"));
        container.setItem(CANCEL_RESTORE_SLOT, cancelBtn);

        PreviewState previewState = new PreviewState(container, targetId, targetName, snapshotId, returnPage);
        admin.openMenu(new SimpleMenuProvider(
            (syncId, inventory, p) -> new SnapshotPreviewMenu(syncId, inventory, previewState),
            Component.literal("Preview: #" + snapshotId + " (" + meta.reason() + ")")
        ));
    }

    private void openConfirm(ServerPlayer admin, UUID targetId, String targetName, long snapshotId, int returnPage) {
        InventoryHistoryService.SnapshotMeta meta = historyService.getSnapshotMeta(snapshotId);
        if (meta == null) {
            admin.sendSystemMessage(Component.literal("§cSnapshot not found."));
            return;
        }

        SimpleContainer container = new SimpleContainer(9);

        ItemStack cancel = new ItemStack(Items.RED_WOOL);
        cancel.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lCANCEL — Do NOT restore"));
        container.setItem(CONFIRM_MENU_CANCEL_SLOT, cancel);

        ItemStack info = new ItemStack(Items.PAPER);
        info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lConfirm Restore?"));
        info.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Snapshot #" + snapshotId),
            Component.literal("§7Player: §f" + targetName),
            Component.literal("§7Time: §f" + DATE_FMT.format(Instant.ofEpochSecond(meta.ts())) + " UTC"),
            Component.literal("§7Reason: §f" + meta.reason()),
            Component.literal(""),
            Component.literal("§c§lThis will WIPE current inventory"),
            Component.literal("§c§land replace with snapshot contents!")
        )));
        container.setItem(4, info);

        ItemStack confirm = new ItemStack(Items.LIME_WOOL);
        confirm.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lCONFIRM RESTORE"));
        container.setItem(CONFIRM_MENU_CONFIRM_SLOT, confirm);

        ConfirmState confirmState = new ConfirmState(container, targetId, targetName, snapshotId, returnPage);
        admin.openMenu(new SimpleMenuProvider(
            (syncId, inventory, p) -> new ConfirmRestoreMenu(syncId, inventory, confirmState),
            Component.literal("§c§lConfirm Restore #" + snapshotId)
        ));
    }

    private int cliRestore(CommandContext<CommandSourceStack> ctx) {
        String targetName = StringArgumentType.getString(ctx, "player");
        long snapshotId = LongArgumentType.getLong(ctx, "snapshotId");
        Optional<GameProfile> targetProfile = ctx.getSource().getServer().services().profileResolver().fetchByName(targetName);
        if (targetProfile.isEmpty() || targetProfile.get().id() == null) {
            ctx.getSource().sendFailure(Component.literal("§cCould not resolve player: " + targetName));
            return 0;
        }
        UUID targetId = targetProfile.get().id();
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayer(targetId);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer " + targetName + " is not online."));
            return 0;
        }
        String adminName = ctx.getSource().getTextName();
        InventoryHistoryService.RestoreResult result = historyService.restoreSnapshot(target, snapshotId, adminName);
        if (result.success()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + result.message()), true);
        } else {
            ctx.getSource().sendFailure(Component.literal("§c" + result.message()));
        }
        return result.success() ? 1 : 0;
    }

    private static ItemStack iconForReason(String reason) {
        return switch (reason) {
            case "join" -> new ItemStack(Items.OAK_DOOR);
            case "leave" -> new ItemStack(Items.IRON_DOOR);
            case "death" -> new ItemStack(Items.SKELETON_SKULL);
            case "interval" -> new ItemStack(Items.CLOCK);
            case "manual_restore" -> new ItemStack(Items.ENDER_CHEST);
            default -> new ItemStack(Items.PAPER);
        };
    }

    private static String reasonLabel(String reason) {
        return switch (reason) {
            case "join" -> "Join";
            case "leave" -> "Leave";
            case "death" -> "Death";
            case "interval" -> "Periodic";
            case "manual_restore" -> "Post-Restore";
            default -> reason;
        };
    }

    private static boolean hasAdminPermission(CommandSourceStack source) {
        if (source.permissions() instanceof LevelBasedPermissionSet levelSet) {
            return levelSet.level().isEqualOrHigherThan(PermissionLevel.ADMINS);
        }
        return false;
    }

    private static ServerPlayer getPlayerOrFail(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return null;
        }
    }

    private static final class BrowserState {
        final SimpleContainer container;
        final Map<Integer, Long> slotSnapshotIds;
        final UUID targetId;
        final String targetName;
        int page;
        boolean hasNext;

        BrowserState(SimpleContainer container, Map<Integer, Long> slotSnapshotIds,
                     UUID targetId, String targetName, int page, boolean hasNext) {
            this.container = container;
            this.slotSnapshotIds = slotSnapshotIds;
            this.targetId = targetId;
            this.targetName = targetName;
            this.page = page;
            this.hasNext = hasNext;
        }
    }
    private record PreviewState(SimpleContainer container, UUID targetId, String targetName,
                                long snapshotId, int returnPage) {}
    private record ConfirmState(SimpleContainer container, UUID targetId, String targetName,
                                long snapshotId, int returnPage) {}

    private final class SnapshotBrowserMenu extends ChestMenu {
        private final BrowserState state;

        SnapshotBrowserMenu(int syncId, Inventory playerInventory, BrowserState state) {
            super(MenuType.GENERIC_9x6, syncId, playerInventory, state.container, BROWSER_ROWS);
            this.state = state;
            for (int row = 0; row < BROWSER_ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    int idx = row * 9 + col;
                    this.slots.set(idx, new ReadOnlySlot(state.container, idx, 8 + col * 18, 18 + row * 18));
                }
            }
        }

        private void updateInPlace(int nextPage) {
            BrowserState next = buildBrowserState(this.state.targetId, this.state.targetName, nextPage);
            for (int i = 0; i < BROWSER_SIZE; i++) {
                this.state.container.setItem(i, next.container.getItem(i).copy());
            }
            this.state.slotSnapshotIds.clear();
            this.state.slotSnapshotIds.putAll(next.slotSnapshotIds);
            this.state.page = next.page;
            this.state.hasNext = next.hasNext;
            this.broadcastChanges();
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer admin)) {
                super.clicked(slotId, button, clickType, player);
                return;
            }
            if (slotId == CLOSE_SLOT) {
                admin.closeContainer();
                return;
            }
            if (slotId == PREV_SLOT && state.page > 0) {
                updateInPlace(state.page - 1);
                return;
            }
            if (slotId == NEXT_SLOT && state.hasNext) {
                updateInPlace(state.page + 1);
                return;
            }
            Long snapId = state.slotSnapshotIds.get(slotId);
            if (snapId != null) {
                openPreview(admin, state.targetId, state.targetName, snapId, state.page);
                return;
            }
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }
    }

    private final class SnapshotPreviewMenu extends ChestMenu {
        private final PreviewState state;

        SnapshotPreviewMenu(int syncId, Inventory playerInventory, PreviewState state) {
            super(MenuType.GENERIC_9x6, syncId, playerInventory, state.container(), PREVIEW_ROWS);
            this.state = state;
            for (int row = 0; row < PREVIEW_ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    int idx = row * 9 + col;
                    this.slots.set(idx, new ReadOnlySlot(state.container(), idx, 8 + col * 18, 18 + row * 18));
                }
            }
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer admin)) {
                super.clicked(slotId, button, clickType, player);
                return;
            }
            if (slotId == CANCEL_RESTORE_SLOT) {
                admin.closeContainer();
                return;
            }
            if (slotId == BACK_SLOT) {
                openBrowserPage(admin, state.targetId(), state.targetName(), state.returnPage());
                return;
            }
            if (slotId == CONFIRM_SLOT) {
                openConfirm(admin, state.targetId(), state.targetName(), state.snapshotId(), state.returnPage());
                return;
            }
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }
    }

    private final class ConfirmRestoreMenu extends ChestMenu {
        private final ConfirmState state;

        ConfirmRestoreMenu(int syncId, Inventory playerInventory, ConfirmState state) {
            super(MenuType.GENERIC_9x1, syncId, playerInventory, state.container(), 1);
            this.state = state;
            for (int col = 0; col < 9; col++) {
                this.slots.set(col, new ReadOnlySlot(state.container(), col, 8 + col * 18, 18));
            }
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer admin)) {
                super.clicked(slotId, button, clickType, player);
                return;
            }
            if (slotId == CONFIRM_MENU_CANCEL_SLOT) {
                openPreview(admin, state.targetId(), state.targetName(), state.snapshotId(), state.returnPage());
                return;
            }
            if (slotId == CONFIRM_MENU_CONFIRM_SLOT) {
                ServerPlayer target = admin.level().getServer().getPlayerList().getPlayer(state.targetId());
                if (target == null) {
                    admin.sendSystemMessage(Component.literal("§cPlayer " + state.targetName() + " is not online. Cannot restore."));
                    admin.closeContainer();
                    return;
                }
                String adminName = admin.getName().getString();
                InventoryHistoryService.RestoreResult result = historyService.restoreSnapshot(target, state.snapshotId(), adminName);
                if (result.success()) {
                    admin.sendSystemMessage(Component.literal("§a" + result.message()));
                    target.sendSystemMessage(Component.literal("§eYour inventory was restored by an admin."));
                } else {
                    admin.sendSystemMessage(Component.literal("§c" + result.message()));
                }
                admin.closeContainer();
                return;
            }
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }
    }

    private static final class ReadOnlySlot extends Slot {
        ReadOnlySlot(SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
