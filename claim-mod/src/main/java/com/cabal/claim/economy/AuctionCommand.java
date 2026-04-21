package com.cabal.claim.economy;

import com.cabal.claim.CabalClaimMod;
import com.cabal.claim.ClaimManager;
import com.cabal.claim.LandTicketHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AuctionCommand {
    private static final int MARKET_ROWS = 6;
    private static final int MARKET_SIZE = 9 * MARKET_ROWS;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int NAV_ROW_START = 45;

    private static final int NAV_PREV = 45;
    private static final int NAV_SORT = 46;
    private static final int NAV_FILTER = 47;
    private static final int NAV_SEARCH = 48;
    private static final int NAV_REFRESH = 49;
    private static final int NAV_MY_LISTINGS = 50;
    private static final int NAV_NEXT = 53;

    private static final int CONFIRM_ROWS = 1;
    private static final int CONFIRM_SIZE = 9;
    private static final int CONFIRM_CANCEL_SLOT = 2;
    private static final int CONFIRM_PREVIEW_SLOT = 4;
    private static final int CONFIRM_BUY_SLOT = 6;

    private static final int MY_LISTINGS_ROWS = 6;
    private static final int MY_LISTINGS_SIZE = 9 * MY_LISTINGS_ROWS;
    private static final int MY_LISTINGS_NAV_ROW_START = 45;
    private static final int MY_LISTINGS_ITEMS_PER_PAGE = MY_LISTINGS_NAV_ROW_START;
    private static final int MY_LISTINGS_PREV_SLOT = 45;
    private static final int MY_LISTINGS_NEXT_SLOT = 53;
    private static final int MY_LISTINGS_BACK_SLOT = 49;

    private static final DateTimeFormatter LISTING_DATE_FMT =
        DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneOffset.UTC);
    private static final ParsedTemplate SEARCH_TEMPLATE =
        ParsedTemplate.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("/auction search $(query)")).result().orElse(null);

    private final AuctionService auctionService;

    public AuctionCommand(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("auction")
                .then(Commands.literal("open")
                    .executes(this::openMenu))
                .then(Commands.literal("sell")
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .then(Commands.argument("price", IntegerArgumentType.integer(1))
                            .executes(this::sellWithAmount)))
                    .then(Commands.argument("price", IntegerArgumentType.integer(1))
                        .executes(this::sell)))
                .then(Commands.literal("buy")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(this::buy)))
                .then(Commands.literal("list")
                    .executes(this::list))
                .then(Commands.literal("mylistings")
                    .executes(this::openMyListings))
                .then(Commands.literal("search")
                    .executes(this::clearSearch)
                    .then(Commands.argument("query", StringArgumentType.greedyString())
                        .executes(this::search)))
                .executes(this::openMenu)
        );
        dispatcher.register(
            Commands.literal("sell")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                    .then(Commands.argument("price", IntegerArgumentType.integer(1))
                        .executes(this::sellWithAmount)))
        );
        dispatcher.register(
            Commands.literal("mylistings")
                .executes(this::openMyListings)
        );
    }

    // ── State records ────────────────────────────────────────────────

    private record BrowseState(UUID viewerId, int page, AuctionService.SortMode sort, AuctionService.FilterMode filter, String searchText) {
        BrowseState withPage(int p) { return new BrowseState(viewerId, p, sort, filter, searchText); }
        BrowseState withSort(AuctionService.SortMode s) { return new BrowseState(viewerId, 0, s, filter, searchText); }
        BrowseState withFilter(AuctionService.FilterMode f) { return new BrowseState(viewerId, 0, sort, f, searchText); }
        BrowseState withSearch(String s) { return new BrowseState(viewerId, 0, sort, filter, s); }
    }

    private static final class MarketMenuData {
        final SimpleContainer container;
        final Map<Integer, Item> slotServerItems;
        final Map<Integer, AuctionService.Listing> slotListings;
        final java.util.Set<Integer> expansionShowcaseSlots;
        final Map<Integer, AuctionService.PurchaseQuote> expansionShowcaseQuotes;
        BrowseState state;
        boolean hasNext;
        int totalListings;
        int totalPages;

        MarketMenuData(
            SimpleContainer container,
            Map<Integer, Item> slotServerItems,
            Map<Integer, AuctionService.Listing> slotListings,
            java.util.Set<Integer> expansionShowcaseSlots,
            Map<Integer, AuctionService.PurchaseQuote> expansionShowcaseQuotes,
            BrowseState state,
            boolean hasNext,
            int totalListings,
            int totalPages
        ) {
            this.container = container;
            this.slotServerItems = slotServerItems;
            this.slotListings = slotListings;
            this.expansionShowcaseSlots = expansionShowcaseSlots;
            this.expansionShowcaseQuotes = expansionShowcaseQuotes;
            this.state = state;
            this.hasNext = hasNext;
            this.totalListings = totalListings;
            this.totalPages = totalPages;
        }
    }

    private record BuyConfirmData(SimpleContainer container, long listingId, UUID viewerId) {}

    private static final class MyListingsData {
        final SimpleContainer container;
        final Map<Integer, Long> slotListingIds;
        final UUID seller;
        int page;
        boolean hasNext;
        final BrowseState originatingState;

        MyListingsData(
            SimpleContainer container,
            Map<Integer, Long> slotListingIds,
            UUID seller,
            int page,
            boolean hasNext,
            BrowseState originatingState
        ) {
            this.container = container;
            this.slotListingIds = slotListingIds;
            this.seller = seller;
            this.page = page;
            this.hasNext = hasNext;
            this.originatingState = originatingState;
        }
    }

    // ── Command handlers ─────────────────────────────────────────────

    private int openMenu(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;
        BrowseState state = new BrowseState(player.getUUID(), 0, AuctionService.SortMode.PRICE_ASC, AuctionService.FilterMode.ALL, "");
        openMarketMenu(player, state);
        return 1;
    }

    private int sell(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer seller = getPlayerOrFail(ctx.getSource());
        if (seller == null) return 0;
        int price = IntegerArgumentType.getInteger(ctx, "price");
        return sendResult(ctx, auctionService.listFromMainHand(seller, price));
    }

    private int sellWithAmount(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer seller = getPlayerOrFail(ctx.getSource());
        if (seller == null) return 0;
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        int price = IntegerArgumentType.getInteger(ctx, "price");
        return sendResult(ctx, auctionService.listFromMainHand(seller, amount, price));
    }

    private int buy(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer buyer = getPlayerOrFail(ctx.getSource());
        if (buyer == null) return 0;
        long id = IntegerArgumentType.getInteger(ctx, "id");
        AuctionService.Result result = auctionService.buy(buyer, id);
        if (result.success()) ctx.getSource().sendSuccess(() -> Component.literal("§a" + result.message()), false);
        else ctx.getSource().sendFailure(Component.literal("§c" + result.message()));
        return result.success() ? 1 : 0;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        List<AuctionService.Listing> listings = auctionService.listActive(10, 0);
        if (listings.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§eNo active listings."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§6Auction Listings:\n");
        for (AuctionService.Listing l : listings) {
            sb.append("§e#").append(l.id())
                .append(" §f").append(auctionService.listingSummary(l))
                .append(" §7by ").append(l.sellerName())
                .append(" §a$").append(String.format("%.2f", l.price()))
                .append("\n");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString().stripTrailing()), false);
        return 1;
    }

    private int openMyListings(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;
        openMyListingsPage(player, 0, new BrowseState(player.getUUID(), 0, AuctionService.SortMode.PRICE_ASC, AuctionService.FilterMode.ALL, ""));
        return 1;
    }

    private int clearSearch(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;
        BrowseState state = new BrowseState(player.getUUID(), 0, AuctionService.SortMode.PRICE_ASC, AuctionService.FilterMode.ALL, "");
        openMarketMenu(player, state);
        return 1;
    }

    private int search(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = getPlayerOrFail(ctx.getSource());
        if (player == null) return 0;
        String query = normalizeSearchQuery(StringArgumentType.getString(ctx, "query"));
        BrowseState state = new BrowseState(player.getUUID(), 0, AuctionService.SortMode.PRICE_ASC, AuctionService.FilterMode.ALL, query);
        openMarketMenu(player, state);
        return 1;
    }

    private static String normalizeSearchQuery(String raw) {
        if (raw == null) return "";
        String q = raw.trim();
        // Dialog templates may escape quote characters (e.g. \"\").
        q = q.replace("\\\"", "\"").replace("\\'", "'");
        // Greedy-string + command templates may pass an empty quoted literal ("" or '').
        while (q.length() >= 2) {
            boolean wrappedDouble = q.startsWith("\"") && q.endsWith("\"");
            boolean wrappedSingle = q.startsWith("'") && q.endsWith("'");
            if (!wrappedDouble && !wrappedSingle) break;
            q = q.substring(1, q.length() - 1).trim();
        }
        // Keep search input strictly alphanumeric plus spaces.
        q = q.replaceAll("[^A-Za-z0-9 ]", " ");
        q = q.replaceAll("\\s+", " ").trim();
        return q;
    }

    private int sendResult(CommandContext<CommandSourceStack> ctx, AuctionService.Result result) {
        if (result.success()) ctx.getSource().sendSuccess(() -> Component.literal("§a" + result.message()), false);
        else ctx.getSource().sendFailure(Component.literal("§c" + result.message()));
        return result.success() ? 1 : 0;
    }

    private static ServerPlayer getPlayerOrFail(CommandSourceStack source) {
        try { return source.getPlayerOrException(); }
        catch (Exception e) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return null;
        }
    }

    // ── Market browse menu ───────────────────────────────────────────

    private void openMarketMenu(ServerPlayer player, BrowseState state) {
        MarketMenuData data = buildMarketData(state);
        String title = "Auction House";
        if (state.searchText() != null && !state.searchText().isBlank()) {
            String q = state.searchText().length() > 16 ? state.searchText().substring(0, 16) + "…" : state.searchText();
            title = "Auction: \"" + q + "\"";
        }
        if (data.totalPages > 1) {
            title += " (" + (state.page() + 1) + "/" + data.totalPages + ")";
        }
        final String menuTitle = title;
        player.openMenu(new SimpleMenuProvider(
            (syncId, inv, p) -> new MarketBrowseMenu(syncId, inv, data),
            Component.literal(menuTitle)
        ));
    }

    private MarketMenuData buildMarketData(BrowseState state) {
        List<Item> serverShowcaseItems = auctionService.serverMarketItems();
        int serverShowcaseSlots = serverShowcaseItems.size();
        int listingsPerPage = Math.max(1, ITEMS_PER_PAGE - serverShowcaseSlots);
        int offset = state.page() * listingsPerPage;
        AuctionService.BrowseResult browse = auctionService.browseAndCountListings(
            listingsPerPage + 1, offset, state.sort(), state.searchText(), state.filter());
        List<AuctionService.Listing> listings = browse.page();
        int total = browse.totalMatches();
        int totalPages = Math.max(1, (total + listingsPerPage - 1) / listingsPerPage);

        boolean hasNext = listings.size() > listingsPerPage;
        if (hasNext) listings = listings.subList(0, listingsPerPage);

        SimpleContainer container = new SimpleContainer(MARKET_SIZE);
        Map<Integer, Item> slotServerItems = new HashMap<>();
        Map<Integer, AuctionService.Listing> slotListings = new HashMap<>();
        java.util.Set<Integer> expansionShowcaseSlots = new java.util.HashSet<>();
        Map<Integer, AuctionService.PurchaseQuote> expansionShowcaseQuotes = new HashMap<>();

        Map<String, AuctionService.MarketEntry> market = auctionService.lowestActiveByAllowedItem();
        int expansionShowcaseIdx = AuctionService.EXPANSION_SLOT_SHOWCASE_INDEX;
        for (int i = 0; i < serverShowcaseItems.size(); i++) {
            Item item = serverShowcaseItems.get(i);
            boolean isExpansionShowcase = (i == expansionShowcaseIdx);
            ItemStack display;
            if (isExpansionShowcase) {
                display = LandTicketHelper.createExpansionSlotShowcaseItem();
            } else if (item == Items.MAP) {
                display = LandTicketHelper.createSlotTicket();
            } else if (item == Items.WRITABLE_BOOK) {
                display = LandTicketHelper.createClaimDeedShowcaseItem();
            } else {
                display = new ItemStack(item, 1);
            }

            ArrayList<Component> lore = new ArrayList<>();

            if (isExpansionShowcase) {
                String expansionKey = auctionService.identifyMarketKeyForItem(Items.PAPER);
                AuctionService.MarketEntry entry = market.get(expansionKey);
                AuctionService.PurchaseQuote quote = auctionService.quoteBestOffer(Items.PAPER, state.viewerId());
                if (entry != null) {
                    lore.add(Component.literal("§7Lowest player price: §a$" + String.format("%.2f", entry.lowestPrice())));
                    lore.add(Component.literal("§7Active listings: §f" + entry.listingCount()));
                } else {
                    lore.add(Component.literal("§7No active player listings"));
                }
                if (auctionService.serverBuyEnabled()) {
                    lore.add(Component.literal("§7Server buys: §a$" + String.format("%.2f", auctionService.expansionSlotServerBuyPrice()) + " each"));
                }
                if (auctionService.serverSellEnabled()) {
                    lore.add(Component.literal("§7Server sells: §c$" + String.format("%.2f", auctionService.expansionSlotServerSellPrice()) + " each"));
                }
                if (quote != null) {
                    String source = quote.source() == AuctionService.OfferSource.SERVER_LISTING ? "Server" : "Player listing";
                    lore.add(Component.literal("§7Best buy offer: §a$" + String.format("%.2f", quote.price()) + " §8(" + source + ")"));
                    lore.add(Component.literal("§eClick to buy best offer"));
                    expansionShowcaseQuotes.put(i, quote);
                } else if (auctionService.serverSellEnabled()) {
                    lore.add(Component.literal("§eClick to buy from server"));
                }
                lore.add(Component.literal(""));
                lore.add(Component.literal("§8Right-click item to unlock +1 claim slot"));
            } else {
                AuctionService.MarketEntry entry = market.get(auctionService.identifyMarketKeyForItem(item));
                AuctionService.PurchaseQuote quote = auctionService.quoteBestOffer(item, state.viewerId());
                if (entry != null) {
                    lore.add(Component.literal("§7Lowest player price: §a$" + String.format("%.2f", entry.lowestPrice())));
                    lore.add(Component.literal("§7Active listings: §f" + entry.listingCount()));
                } else {
                    lore.add(Component.literal("§7No active player listings"));
                }
                double serverBuyPrice = auctionService.serverBuyPriceWithDailyBoost(item);
                if (auctionService.serverBuyEnabled() && serverBuyPrice >= 0) {
                    int quantity = auctionService.serverSellQuantity(item);
                    boolean boosted = auctionService.isDailyBoostedServerBuyItem(item);
                    if (auctionService.isStackOnlyServerTrade(item)) {
                        lore.add(Component.literal("§7Server buys: §a$" + String.format("%.2f", serverBuyPrice) + " per stack (" + quantity + "x)" + (boosted ? " §6(+25% today)" : "")));
                    } else {
                        lore.add(Component.literal("§7Server buys: §a$" + String.format("%.2f", serverBuyPrice) + " each" + (boosted ? " §6(+25% today)" : "")));
                    }
                }
                double serverSellPrice = auctionService.serverSellPrice(item);
                if (auctionService.serverSellEnabled() && serverSellPrice >= 0) {
                    int quantity = auctionService.serverSellQuantity(item);
                    if (auctionService.isStackOnlyServerTrade(item)) {
                        lore.add(Component.literal("§7Server sells: §c$" + String.format("%.2f", serverSellPrice) + " per stack (" + quantity + "x)"));
                    } else {
                        lore.add(Component.literal("§7Server sells: §c$" + String.format("%.2f", serverSellPrice) + " each"));
                    }
                }
                if (quote != null) {
                    String source = quote.source() == AuctionService.OfferSource.SERVER_LISTING ? "Server" : "Player listing";
                    lore.add(Component.literal("§7Best buy offer: §a$" + String.format("%.2f", quote.price()) + " §8(" + source + ")"));
                    lore.add(Component.literal("§eClick to buy best offer"));
                }
                lore.add(Component.literal(""));
                if (LandTicketHelper.isClaimTransferTicket(display)) {
                    lore.add(Component.literal("§8Use /foreclose <claimId> to sell deed & release land"));
                } else {
                    lore.add(Component.literal("§8Use /sellserver <amount> to sell"));
                }
            }
            display.set(DataComponents.CUSTOM_NAME, Component.literal("§6Server Market • §f" + display.getHoverName().getString()));
            display.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(i, display);
            slotServerItems.put(i, item);
            if (isExpansionShowcase) {
                expansionShowcaseSlots.add(i);
            }
        }

        for (int i = 0; i < listings.size(); i++) {
            AuctionService.Listing listing = listings.get(i);
            ItemStack display = AuctionService.deserializeItem(listing.itemBlob());
            boolean isClaimDeed = LandTicketHelper.isClaimTransferTicket(display);
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(Component.literal("§7Price: §a$" + String.format("%.2f", listing.price())));
            lore.add(Component.literal("§7Seller: §f" + listing.sellerName()));
            if (!isClaimDeed) {
                lore.add(Component.literal("§7Qty: §f" + display.getCount()));
            }
            String coords = LandTicketHelper.claimCoordinatesLabel(display);
            if (coords != null) {
                lore.add(Component.literal("§7Claim: §f" + coords));
            }
            if (isClaimDeed) {
                String deedTitle = LandTicketHelper.claimDeedTitle(display);
                if (deedTitle != null) {
                    lore.add(Component.literal("§7Deed title: §f" + deedTitle));
                }
                List<String> previewLines = LandTicketHelper.claimDeedPreviewLines(display, 2);
                if (!previewLines.isEmpty()) {
                    lore.add(Component.literal("§7Description:"));
                    for (String line : previewLines) {
                        lore.add(Component.literal("§f- " + line));
                    }
                }
                int claimId = LandTicketHelper.getClaimId(display);
                if (claimId > 0) {
                    ClaimManager cm = CabalClaimMod.getClaimManager();
                    if (cm != null) {
                        ClaimManager.ClaimEntry ce = cm.getClaimById(claimId);
                        int trustedCount = ce != null ? ce.trustedOrEmpty().size() : 0;
                        lore.add(Component.literal("§7Trusted members: §f" + trustedCount));
                    }
                }
            }
            lore.add(Component.literal("§7Listed: §f" + LISTING_DATE_FMT.format(Instant.ofEpochSecond(listing.createdAt())) + " UTC"));
            if (!isClaimDeed) {
                lore.add(Component.literal("§7Listing #§f" + listing.id()));
            }
            lore.add(Component.literal(""));
            if (listing.seller().equals(state.viewerId())) {
                lore.add(Component.literal("§8Your listing"));
            } else {
                lore.add(Component.literal("§eClick to purchase"));
            }
            display.set(DataComponents.LORE, new ItemLore(lore));
            int slot = serverShowcaseSlots + i;
            container.setItem(slot, display);
            slotListings.put(slot, listing);
        }

        buildNavRow(container, state, hasNext, total, totalPages);
        return new MarketMenuData(container, slotServerItems, slotListings, expansionShowcaseSlots, expansionShowcaseQuotes, state, hasNext, total, totalPages);
    }

    private void buildNavRow(SimpleContainer container, BrowseState state, boolean hasNext, int total, int totalPages) {
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = NAV_ROW_START; i < MARKET_SIZE; i++) {
            container.setItem(i, filler.copy());
        }

        if (state.page() > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("§ePrevious Page"));
            prev.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Page " + state.page() + "/" + totalPages)
            )));
            container.setItem(NAV_PREV, prev);
        }

        ItemStack sort = new ItemStack(Items.COMPARATOR);
        sort.set(DataComponents.CUSTOM_NAME, Component.literal("§bSort"));
        ArrayList<Component> sortLore = new ArrayList<>();
        sortLore.add(Component.literal("§7Click to cycle sort mode"));
        sortLore.add(Component.literal(""));
        for (AuctionService.SortMode mode : AuctionService.SortMode.values()) {
            boolean active = mode == state.sort();
            sortLore.add(Component.literal((active ? "§a" : "§9") + (active ? "• " : "  ") + mode.displayName()));
        }
        sort.set(DataComponents.LORE, new ItemLore(sortLore));
        container.setItem(NAV_SORT, sort);

        ItemStack filter = new ItemStack(Items.HOPPER);
        filter.set(DataComponents.CUSTOM_NAME, Component.literal("§bFilter: " + state.filter().displayName()));
        ArrayList<Component> filterLore = new ArrayList<>();
        filterLore.add(Component.literal("§7Click to cycle filter category"));
        filterLore.add(Component.literal(""));
        for (AuctionService.FilterMode mode : AuctionService.FilterMode.values()) {
            boolean active = mode == state.filter();
            filterLore.add(Component.literal((active ? "§a" : "§9") + (active ? "• " : "  ") + mode.displayName()));
        }
        filter.set(DataComponents.LORE, new ItemLore(filterLore));
        container.setItem(NAV_FILTER, filter);

        ItemStack search = new ItemStack(Items.NAME_TAG);
        String searchLabel = (state.searchText() != null && !state.searchText().isBlank())
            ? "§bSearch: §f" + state.searchText()
            : "§bSearch";
        search.set(DataComponents.CUSTOM_NAME, Component.literal(searchLabel));
        ArrayList<Component> searchLore = new ArrayList<>();
        searchLore.add(Component.literal("§7Click to search by item name"));
        if (state.searchText() != null && !state.searchText().isBlank()) {
            searchLore.add(Component.literal("§7Shift-click to clear search"));
        }
        search.set(DataComponents.LORE, new ItemLore(searchLore));
        container.setItem(NAV_SEARCH, search);

        ItemStack refresh = new ItemStack(Items.SUNFLOWER);
        refresh.set(DataComponents.CUSTOM_NAME, Component.literal("§aRefresh"));
        refresh.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7" + total + " active listing" + (total != 1 ? "s" : ""))
        )));
        container.setItem(NAV_REFRESH, refresh);

        ItemStack myListings = new ItemStack(Items.CHEST);
        myListings.set(DataComponents.CUSTOM_NAME, Component.literal("§6My Listings"));
        myListings.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7View and manage your listings")
        )));
        container.setItem(NAV_MY_LISTINGS, myListings);

        if (hasNext) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("§eNext Page"));
            next.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Page " + (state.page() + 2) + "/" + totalPages)
            )));
            container.setItem(NAV_NEXT, next);
        }
    }

    // ── Buy confirmation menu ────────────────────────────────────────

    private void openBuyConfirm(ServerPlayer player, AuctionService.Listing listing, BrowseState returnState) {
        SimpleContainer container = new SimpleContainer(CONFIRM_SIZE);

        ItemStack cancel = new ItemStack(Items.BARRIER);
        cancel.set(DataComponents.CUSTOM_NAME, Component.literal("§cCancel"));
        container.setItem(CONFIRM_CANCEL_SLOT, cancel);

        ItemStack preview = AuctionService.deserializeItem(listing.itemBlob());
        preview.set(DataComponents.CUSTOM_NAME, Component.literal("§eConfirm Purchase"));
        preview.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Item: §f" + preview.getCount() + "x " + preview.getHoverName().getString()),
            Component.literal("§7Seller: §f" + listing.sellerName()),
            Component.literal("§7Price: §c$" + String.format("%.2f", listing.price())),
            Component.literal("§7Listing #§f" + listing.id())
        )));
        container.setItem(CONFIRM_PREVIEW_SLOT, preview);

        ItemStack confirm = new ItemStack(Items.LIME_DYE);
        confirm.set(DataComponents.CUSTOM_NAME, Component.literal("§aConfirm Purchase"));
        container.setItem(CONFIRM_BUY_SLOT, confirm);

        BuyConfirmData data = new BuyConfirmData(container, listing.id(), returnState.viewerId());
        player.openMenu(new SimpleMenuProvider(
            (syncId, inv, p) -> new BuyConfirmMenu(syncId, inv, data, returnState),
            Component.literal("Confirm Purchase")
        ));
    }

    // ── My Listings menu ─────────────────────────────────────────────

    private void openMyListingsPage(ServerPlayer player, int page, BrowseState originatingState) {
        MyListingsData data = buildMyListingsData(player.getUUID(), page, originatingState);
        player.openMenu(new SimpleMenuProvider(
            (syncId, inv, p) -> new MyListingsMenu(syncId, inv, data),
            Component.literal("My Listings" + (page > 0 ? " (page " + (page + 1) + ")" : ""))
        ));
    }

    private MyListingsData buildMyListingsData(UUID seller, int page, BrowseState originatingState) {
        int offset = page * MY_LISTINGS_ITEMS_PER_PAGE;
        List<AuctionService.Listing> listings;
        try {
            listings = auctionService.listActiveBySeller(seller, MY_LISTINGS_ITEMS_PER_PAGE + 1, offset);
        } catch (RuntimeException e) {
            SimpleContainer errorContainer = new SimpleContainer(MY_LISTINGS_SIZE);
            ItemStack error = new ItemStack(Items.BARRIER);
            error.set(DataComponents.CUSTOM_NAME, Component.literal("§cFailed to load listings"));
            error.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Database error. Try again shortly.")
            )));
            errorContainer.setItem(22, error);
            return new MyListingsData(errorContainer, Map.of(), seller, page, false, originatingState);
        }
        boolean hasNext = listings.size() > MY_LISTINGS_ITEMS_PER_PAGE;
        if (hasNext) listings = listings.subList(0, MY_LISTINGS_ITEMS_PER_PAGE);

        SimpleContainer container = new SimpleContainer(MY_LISTINGS_SIZE);
        Map<Integer, Long> slotIds = new HashMap<>();

        for (int i = 0; i < listings.size(); i++) {
            AuctionService.Listing listing = listings.get(i);
            ItemStack display = AuctionService.deserializeItem(listing.itemBlob());
            ArrayList<Component> lore = new ArrayList<>();
            lore.add(Component.literal("§7Price: §a$" + String.format("%.2f", listing.price())));
            lore.add(Component.literal("§7Qty: §f" + display.getCount()));
            lore.add(Component.literal("§7Listed: §f" + LISTING_DATE_FMT.format(Instant.ofEpochSecond(listing.createdAt())) + " UTC"));
            lore.add(Component.literal("§7Listing #§f" + listing.id()));
            lore.add(Component.literal(""));
            lore.add(Component.literal("§cClick to unlist and return to inventory"));
            display.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(i, display);
            slotIds.put(i, listing.id());
        }

        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = MY_LISTINGS_NAV_ROW_START; i < MY_LISTINGS_SIZE; i++) {
            container.setItem(i, filler.copy());
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("§ePrevious Page"));
            container.setItem(MY_LISTINGS_PREV_SLOT, prev);
        }
        if (hasNext) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("§eNext Page"));
            container.setItem(MY_LISTINGS_NEXT_SLOT, next);
        }
        ItemStack back = new ItemStack(Items.DARK_OAK_DOOR);
        back.set(DataComponents.CUSTOM_NAME, Component.literal("§eBack to Auction House"));
        container.setItem(MY_LISTINGS_BACK_SLOT, back);

        return new MyListingsData(container, slotIds, seller, page, hasNext, originatingState);
    }

    // ── Inner menu classes ───────────────────────────────────────────

    private final class MarketBrowseMenu extends ChestMenu {
        private final MarketMenuData data;

        MarketBrowseMenu(int syncId, Inventory playerInventory, MarketMenuData data) {
            super(MenuType.GENERIC_9x6, syncId, playerInventory, data.container, MARKET_ROWS);
            this.data = data;
            for (int row = 0; row < MARKET_ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    int idx = row * 9 + col;
                    this.slots.set(idx, new ReadOnlySlot(data.container, idx, 8 + col * 18, 18 + row * 18));
                }
            }
        }

        private void updateInPlace(BrowseState nextState) {
            MarketMenuData next = buildMarketData(nextState);
            for (int i = 0; i < MARKET_SIZE; i++) {
                this.data.container.setItem(i, next.container.getItem(i).copy());
            }
            this.data.slotServerItems.clear();
            this.data.slotServerItems.putAll(next.slotServerItems);
            this.data.slotListings.clear();
            this.data.slotListings.putAll(next.slotListings);
            this.data.expansionShowcaseSlots.clear();
            this.data.expansionShowcaseSlots.addAll(next.expansionShowcaseSlots);
            this.data.expansionShowcaseQuotes.clear();
            this.data.expansionShowcaseQuotes.putAll(next.expansionShowcaseQuotes);
            this.data.state = next.state;
            this.data.hasNext = next.hasNext;
            this.data.totalListings = next.totalListings;
            this.data.totalPages = next.totalPages;
            this.broadcastChanges();
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer sp)) {
                super.clicked(slotId, button, clickType, player);
                return;
            }
            BrowseState state = data.state;

            if (slotId == NAV_PREV && state.page() > 0) {
                updateInPlace(state.withPage(state.page() - 1));
                return;
            }
            if (slotId == NAV_NEXT && data.hasNext) {
                updateInPlace(state.withPage(state.page() + 1));
                return;
            }
            if (slotId == NAV_SORT) {
                updateInPlace(state.withSort(state.sort().next()));
                return;
            }
            if (slotId == NAV_FILTER) {
                updateInPlace(state.withFilter(state.filter().next()));
                return;
            }
            if (slotId == NAV_SEARCH) {
                boolean shiftClick = clickType == ClickType.QUICK_MOVE;
                if (shiftClick && state.searchText() != null && !state.searchText().isBlank()) {
                    updateInPlace(state.withSearch(""));
                    return;
                }
                sp.closeContainer();
                openSearchDialog(sp, state);
                return;
            }
            if (slotId == NAV_REFRESH) {
                updateInPlace(state);
                return;
            }
            if (slotId == NAV_MY_LISTINGS) {
                openMyListingsPage(sp, 0, state);
                return;
            }

            if (data.expansionShowcaseSlots.contains(slotId)) {
                AuctionService.PurchaseQuote quote = data.expansionShowcaseQuotes.get(slotId);
                AuctionService.Result result = (quote != null)
                    ? auctionService.executeQuotePurchase(sp, quote)
                    : auctionService.buyExpansionSlotFromServer(sp);
                if (result.success()) {
                    sp.sendSystemMessage(Component.literal("§a" + result.message()));
                } else {
                    sp.sendSystemMessage(Component.literal("§c" + result.message()));
                }
                updateInPlace(state);
                return;
            }

            Item serverItem = data.slotServerItems.get(slotId);
            if (serverItem != null) {
                AuctionService.Result result = auctionService.buyLowestForItem(sp, serverItem);
                if (result.success()) {
                    sp.sendSystemMessage(Component.literal("§a" + result.message()));
                } else {
                    sp.sendSystemMessage(Component.literal("§c" + result.message()));
                }
                updateInPlace(state);
                return;
            }

            AuctionService.Listing listing = data.slotListings.get(slotId);
            if (listing != null) {
                if (listing.seller().equals(sp.getUUID())) {
                    sp.sendSystemMessage(Component.literal("§7This is your own listing. Manage it in My Listings."));
                    return;
                }
                openBuyConfirm(sp, listing, state);
                return;
            }

            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    private final class BuyConfirmMenu extends ChestMenu {
        private final BuyConfirmData data;
        private final BrowseState returnState;

        BuyConfirmMenu(int syncId, Inventory playerInventory, BuyConfirmData data, BrowseState returnState) {
            super(MenuType.GENERIC_9x1, syncId, playerInventory, data.container(), CONFIRM_ROWS);
            this.data = data;
            this.returnState = returnState;
            for (int col = 0; col < 9; col++) {
                this.slots.set(col, new ReadOnlySlot(data.container(), col, 8 + col * 18, 18));
            }
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer sp)) {
                super.clicked(slotId, button, clickType, player);
                return;
            }
            if (slotId == CONFIRM_CANCEL_SLOT) {
                openMarketMenu(sp, returnState);
                return;
            }
            if (slotId == CONFIRM_BUY_SLOT) {
                AuctionService.Result result = auctionService.buy(sp, data.listingId());
                if (result.success()) {
                    sp.sendSystemMessage(Component.literal("§a" + result.message()));
                } else {
                    sp.sendSystemMessage(Component.literal("§c" + result.message()));
                }
                openMarketMenu(sp, returnState);
                return;
            }
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    private final class MyListingsMenu extends ChestMenu {
        private final MyListingsData data;

        MyListingsMenu(int syncId, Inventory playerInventory, MyListingsData data) {
            super(MenuType.GENERIC_9x6, syncId, playerInventory, data.container, MY_LISTINGS_ROWS);
            this.data = data;
            for (int row = 0; row < MY_LISTINGS_ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    int idx = row * 9 + col;
                    this.slots.set(idx, new ReadOnlySlot(data.container, idx, 8 + col * 18, 18 + row * 18));
                }
            }
        }

        private void updateInPlace(int nextPage) {
            MyListingsData next = buildMyListingsData(this.data.seller, nextPage, this.data.originatingState);
            for (int i = 0; i < MY_LISTINGS_SIZE; i++) {
                this.data.container.setItem(i, next.container.getItem(i).copy());
            }
            this.data.slotListingIds.clear();
            this.data.slotListingIds.putAll(next.slotListingIds);
            this.data.page = next.page;
            this.data.hasNext = next.hasNext;
            this.broadcastChanges();
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer sp)) {
                super.clicked(slotId, button, clickType, player);
                return;
            }
            if (slotId == MY_LISTINGS_BACK_SLOT) {
                openMarketMenu(sp, data.originatingState);
                return;
            }
            if (slotId == MY_LISTINGS_PREV_SLOT && data.page > 0) {
                updateInPlace(data.page - 1);
                return;
            }
            if (slotId == MY_LISTINGS_NEXT_SLOT && data.hasNext) {
                updateInPlace(data.page + 1);
                return;
            }
            Long listingId = data.slotListingIds.get(slotId);
            if (listingId != null) {
                if (!sp.getUUID().equals(data.seller)) {
                    sp.sendSystemMessage(Component.literal("§cYou can only unlist your own items."));
                    updateInPlace(data.page);
                    return;
                }
                AuctionService.UnlistResult result = auctionService.unlist(data.seller, listingId);
                if (result.success() && !result.returnedItem().isEmpty()) {
                    boolean inserted = sp.getInventory().add(result.returnedItem());
                    if (!inserted && !result.returnedItem().isEmpty()) {
                        sp.drop(result.returnedItem(), false);
                    }
                    sp.sendSystemMessage(Component.literal("§aListing #" + listingId + " unlisted. Items returned."));
                } else {
                    sp.sendSystemMessage(Component.literal("§c" + result.message()));
                }
                updateInPlace(data.page);
                return;
            }
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    // ── Slot helper ──────────────────────────────────────────────────

    private static final class ReadOnlySlot extends Slot {
        ReadOnlySlot(SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }

    private void openSearchDialog(ServerPlayer player, BrowseState state) {
        if (SEARCH_TEMPLATE == null) {
            // Fallback if template parsing ever fails.
            player.sendSystemMessage(Component.literal("§eSearch via command: §b/auction search <text>"));
            return;
        }
        String initial = state.searchText() == null ? "" : state.searchText();
        TextInput textInput = new TextInput(
            310,
            Component.literal("Search listings"),
            true,
            initial,
            64,
            Optional.empty()
        );
        Input queryInput = new Input("query", textInput);

        CommonDialogData common = new CommonDialogData(
            Component.literal("Auction Search"),
            Optional.of(Component.literal("Auction Search")),
            true,
            false,
            DialogAction.CLOSE,
            List.of(new PlainMessage(Component.literal("Type an item name or id. Leave empty to show all listings."), 310)),
            List.of(queryInput)
        );
        ActionButton action = new ActionButton(
            new CommonButtonData(Component.literal("Search"), 200),
            Optional.of(new CommandTemplate(SEARCH_TEMPLATE))
        );
        Dialog dialog = new NoticeDialog(common, action);
        player.openDialog(Holder.direct(dialog));
    }
}
