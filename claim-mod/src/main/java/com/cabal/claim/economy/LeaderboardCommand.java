package com.cabal.claim.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LeaderboardCommand {

    private static final int ROWS = 6;
    private static final int SIZE = 9 * ROWS;
    private static final int TAB_ROW_START = 0;
    private static final int TAB_ROW_END = 9;
    private static final int ENTRIES_START = 9;
    private static final int ENTRIES_END = 45;
    private static final int ENTRIES_PER_PAGE = ENTRIES_END - ENTRIES_START;
    private static final int NAV_ROW_START = 45;
    private static final int PREV_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int REFRESH_SLOT = 47;
    private static final int PAGE_INFO_SLOT = 51;
    private static final int NAME_CACHE_MAX_ENTRIES = 4096;
    private static final long NAME_CACHE_TTL_MILLIS = 60L * 60L * 1000L;
    private static final long NEGATIVE_NAME_CACHE_TTL_MILLIS = 10L * 60L * 1000L;
    private static final long PERSISTED_NAME_MAX_AGE_SECONDS = 7L * 24L * 60L * 60L;

    private final EconomyDatabase db;
    private final BoundedNameCache playerNameCache = new BoundedNameCache(
        NAME_CACHE_MAX_ENTRIES, NAME_CACHE_TTL_MILLIS);

    public LeaderboardCommand(EconomyDatabase db) {
        this.db = db;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("leaderboard")
                .executes(this::openLeaderboard)
        );
        dispatcher.register(
            Commands.literal("leaderboards")
                .executes(this::openLeaderboard)
        );
    }

    private int openLeaderboard(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        openMenu(player, Category.RICHEST, 0);
        return 1;
    }

    private void openMenu(ServerPlayer player, Category category, int page) {
        MinecraftServer server = player.level().getServer();
        MenuState state = buildState(server, category, page);
        player.openMenu(new SimpleMenuProvider(
            (syncId, inventory, p) -> new LeaderboardMenu(syncId, inventory, state, server),
            Component.literal("Leaderboards")
        ));
    }

    // --- Categories ---

    enum Category {
        RICHEST("Richest", Items.GOLD_INGOT),
        PLAYTIME("Playtime", Items.CLOCK),
        KILLS("Most Kills", Items.DIAMOND_SWORD),
        KD("Best K/D", Items.BOW),
        DEATHS("Most Deaths", Items.SKELETON_SKULL),
        BEST_PING("Best Ping (Online)", Items.ENDER_PEARL),
        WORST_PING("Worst Ping (Online)", Items.SPIDER_EYE);

        final String label;
        final net.minecraft.world.item.Item icon;

        Category(String label, net.minecraft.world.item.Item icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    // --- State building ---

    private record EntryData(String name, String valueLine) {}

    private static final class MenuState {
        final SimpleContainer container;
        Category category;
        int page;
        int totalPages;

        MenuState(SimpleContainer container, Category category, int page, int totalPages) {
            this.container = container;
            this.category = category;
            this.page = page;
            this.totalPages = totalPages;
        }
    }

    private MenuState buildState(MinecraftServer server, Category category, int page) {
        warmNameCacheFromOnlinePlayers(server);
        List<EntryData> allEntries = fetchEntries(server, category);
        int totalPages = Math.max(1, (allEntries.size() + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        int offset = page * ENTRIES_PER_PAGE;
        List<EntryData> pageEntries = allEntries.subList(offset, Math.min(offset + ENTRIES_PER_PAGE, allEntries.size()));

        SimpleContainer container = new SimpleContainer(SIZE);

        for (int i = 0; i < Category.values().length && i < 9; i++) {
            Category cat = Category.values()[i];
            ItemStack tab = new ItemStack(cat.icon);
            if (cat == category) {
                tab.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l" + cat.label + " §7(selected)"));
                tab.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            } else {
                tab.set(DataComponents.CUSTOM_NAME, Component.literal("§e" + cat.label));
            }
            container.setItem(TAB_ROW_START + i, tab);
        }

        for (int i = 0; i < pageEntries.size(); i++) {
            EntryData entry = pageEntries.get(i);
            int rank = offset + i + 1;
            ItemStack head = new ItemStack(rankIcon(rank));
            head.set(DataComponents.CUSTOM_NAME, Component.literal(rankColor(rank) + "#" + rank + " §f" + entry.name));
            head.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7" + category.label + ": " + entry.valueLine)
            )));
            container.setItem(ENTRIES_START + i, head);
        }

        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = NAV_ROW_START; i < SIZE; i++) {
            container.setItem(i, filler.copy());
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("§ePrevious Page"));
            container.setItem(PREV_SLOT, prev);
        }
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("§eNext Page"));
            container.setItem(NEXT_SLOT, next);
        }

        ItemStack refresh = new ItemStack(Items.SUNFLOWER);
        refresh.set(DataComponents.CUSTOM_NAME, Component.literal("§bRefresh"));
        container.setItem(REFRESH_SLOT, refresh);

        ItemStack pageInfo = new ItemStack(Items.PAPER);
        pageInfo.set(DataComponents.CUSTOM_NAME, Component.literal("§7Page " + (page + 1) + "/" + totalPages));
        container.setItem(PAGE_INFO_SLOT, pageInfo);

        ItemStack close = new ItemStack(Items.BARRIER);
        close.set(DataComponents.CUSTOM_NAME, Component.literal("§cClose"));
        container.setItem(CLOSE_SLOT, close);

        return new MenuState(container, category, page, totalPages);
    }

    private List<EntryData> fetchEntries(MinecraftServer server, Category category) {
        return switch (category) {
            case RICHEST -> {
                List<EconomyDatabase.TopBalance> rows = db.topBalances(100);
                preloadPersistentNames(rows.stream().map(EconomyDatabase.TopBalance::uuid).toList());
                yield rows.stream().map(r -> new EntryData(
                    resolveNameCached(server, r.uuid()),
                    "§a$" + String.format("%.2f", r.balance())
                )).toList();
            }
            case PLAYTIME -> {
                List<EconomyDatabase.LeaderboardRow> rows = db.topPlaytime(100);
                preloadPersistentNames(rows.stream().map(EconomyDatabase.LeaderboardRow::uuid).toList());
                yield rows.stream().map(r -> new EntryData(
                    resolveNameCached(server, r.uuid()),
                    "§b" + formatPlaytime(r.value())
                )).toList();
            }
            case KILLS -> {
                List<EconomyDatabase.LeaderboardRow> rows = db.topKills(100);
                preloadPersistentNames(rows.stream().map(EconomyDatabase.LeaderboardRow::uuid).toList());
                yield rows.stream().map(r -> new EntryData(
                    resolveNameCached(server, r.uuid()),
                    "§c" + r.value() + " kills"
                )).toList();
            }
            case DEATHS -> {
                List<EconomyDatabase.LeaderboardRow> rows = db.topDeaths(100);
                preloadPersistentNames(rows.stream().map(EconomyDatabase.LeaderboardRow::uuid).toList());
                yield rows.stream().map(r -> new EntryData(
                    resolveNameCached(server, r.uuid()),
                    "§4" + r.value() + " deaths"
                )).toList();
            }
            case KD -> {
                List<EconomyDatabase.KdRow> rows = db.topKd(100);
                preloadPersistentNames(rows.stream().map(EconomyDatabase.KdRow::uuid).toList());
                yield rows.stream().map(r -> new EntryData(
                    resolveNameCached(server, r.uuid()),
                    "§6" + String.format("%.2f", r.kd()) + " §7(" + r.kills() + "K/" + r.deaths() + "D)"
                )).toList();
            }
            case BEST_PING -> {
                List<EntryData> list = new ArrayList<>();
                List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
                players.sort(Comparator.comparingInt(p -> p.connection.latency()));
                for (ServerPlayer p : players) {
                    list.add(new EntryData(p.getGameProfile().name(), "§a" + p.connection.latency() + "ms"));
                }
                yield list;
            }
            case WORST_PING -> {
                List<EntryData> list = new ArrayList<>();
                List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
                players.sort(Comparator.comparingInt((ServerPlayer p) -> p.connection.latency()).reversed());
                for (ServerPlayer p : players) {
                    list.add(new EntryData(p.getGameProfile().name(), "§c" + p.connection.latency() + "ms"));
                }
                yield list;
            }
        };
    }

    // --- Helpers ---

    private String resolveNameCached(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            String name = online.getGameProfile().name();
            if (name != null && !name.isBlank()) {
                playerNameCache.put(uuid, name);
                return name;
            }
        }
        String cached = playerNameCache.get(uuid);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return uuid.toString().substring(0, 8) + "...";
    }

    private void preloadPersistentNames(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) return;
        List<UUID> missing = uuids.stream()
            .filter(id -> !playerNameCache.containsFresh(id))
            .distinct()
            .toList();
        if (missing.isEmpty()) return;

        Map<UUID, String> persisted = db.playerNamesByIds(missing, PERSISTED_NAME_MAX_AGE_SECONDS);
        for (UUID id : missing) {
            String name = persisted.get(id);
            if (name != null && !name.isBlank()) {
                playerNameCache.put(id, name);
            } else if (playerNameCache.get(id) == null) {
                // Memoize misses briefly so we don't requery DB on every refresh/page change.
                playerNameCache.putNegative(id, NEGATIVE_NAME_CACHE_TTL_MILLIS);
            }
        }
    }

    private void warmNameCacheFromOnlinePlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String name = player.getGameProfile().name();
            if (name != null && !name.isBlank()) {
                playerNameCache.put(player.getUUID(), name);
            }
        }
    }

    private static final class BoundedNameCache {
        private final int maxEntries;
        private final long positiveTtlMillis;
        private final LinkedHashMap<UUID, CacheEntry> map;

        private BoundedNameCache(int maxEntries, long positiveTtlMillis) {
            this.maxEntries = Math.max(1, maxEntries);
            this.positiveTtlMillis = Math.max(1L, positiveTtlMillis);
            this.map = new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CacheEntry> eldest) {
                    return size() > BoundedNameCache.this.maxEntries;
                }
            };
        }

        synchronized String get(UUID id) {
            CacheEntry entry = map.get(id);
            if (entry == null) return null;
            if (entry.expiresAtMillis < System.currentTimeMillis()) {
                map.remove(id);
                return null;
            }
            if (entry.negative) {
                return null;
            }
            return entry.name;
        }

        synchronized boolean containsFresh(UUID id) {
            CacheEntry entry = map.get(id);
            if (entry == null) return false;
            if (entry.expiresAtMillis < System.currentTimeMillis()) {
                map.remove(id);
                return false;
            }
            return true;
        }

        synchronized void put(UUID id, String name) {
            map.put(id, new CacheEntry(name, System.currentTimeMillis() + positiveTtlMillis, false));
        }

        synchronized void putNegative(UUID id, long ttlMillis) {
            map.put(id, new CacheEntry("", System.currentTimeMillis() + Math.max(1L, ttlMillis), true));
        }

        private record CacheEntry(String name, long expiresAtMillis, boolean negative) {}
    }

    private static String formatPlaytime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long mins = (seconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h " + mins + "m";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }

    private static net.minecraft.world.item.Item rankIcon(int rank) {
        return switch (rank) {
            case 1 -> Items.GOLD_BLOCK;
            case 2 -> Items.IRON_BLOCK;
            case 3 -> Items.COPPER_BLOCK;
            default -> Items.PAPER;
        };
    }

    private static String rankColor(int rank) {
        return switch (rank) {
            case 1 -> "§6";
            case 2 -> "§7";
            case 3 -> "§c";
            default -> "§f";
        };
    }

    // --- Menu ---

    private final class LeaderboardMenu extends ChestMenu {
        private final MenuState state;
        private final MinecraftServer server;

        LeaderboardMenu(int syncId, Inventory playerInventory, MenuState state, MinecraftServer server) {
            super(MenuType.GENERIC_9x6, syncId, playerInventory, state.container, ROWS);
            this.state = state;
            this.server = server;
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    int idx = row * 9 + col;
                    this.slots.set(idx, new ReadOnlySlot(state.container, idx, 8 + col * 18, 18 + row * 18));
                }
            }
        }

        private void openAt(Category category, int page, ServerPlayer player) {
            openMenu(player, category, page);
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer)) {
                super.clicked(slotId, button, clickType, player);
                return;
            }
            if (slotId == CLOSE_SLOT) {
                ((ServerPlayer) player).closeContainer();
                return;
            }
            if (slotId >= TAB_ROW_START && slotId < TAB_ROW_END) {
                int tabIndex = slotId - TAB_ROW_START;
                if (tabIndex < Category.values().length) {
                    Category selected = Category.values()[tabIndex];
                    openAt(selected, 0, (ServerPlayer) player);
                }
                return;
            }
            if (slotId == PREV_SLOT && state.page > 0) {
                openAt(state.category, state.page - 1, (ServerPlayer) player);
                return;
            }
            if (slotId == NEXT_SLOT && state.page < state.totalPages - 1) {
                openAt(state.category, state.page + 1, (ServerPlayer) player);
                return;
            }
            if (slotId == REFRESH_SLOT) {
                openAt(state.category, state.page, (ServerPlayer) player);
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
