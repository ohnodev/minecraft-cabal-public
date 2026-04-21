package com.cabal.claim.economy;

import com.cabal.claim.CabalClaimMod;
import com.cabal.claim.ClaimManager;
import com.cabal.claim.LandTicketHelper;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TypedEntityData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;

public final class AuctionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("CabalEconomy/AuctionService");
    public record Listing(long id, UUID seller, String sellerName, String itemBlob, double price, String status, long expiresAt, long createdAt) {}
    public record Result(boolean success, String message) {}
    public record UnlistResult(boolean success, String message, ItemStack returnedItem) {}
    public record MarketEntry(String marketKey, Item displayItem, double lowestPrice, long listingId, int listingCount) {}
    public record PurchaseQuote(Item item, double price, long listingId, OfferSource source) {}
    public enum OfferSource { PLAYER_LISTING, SERVER_LISTING }
    public record BrowseResult(List<Listing> page, int totalMatches) {}
    public enum FilterMode {
        ALL,
        BLOCKS,
        TOOLS,
        COMBAT,
        ARMOR,
        CONSUMABLES,
        MATERIALS;

        public FilterMode next() {
            FilterMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public String displayName() {
            return switch (this) {
                case ALL -> "All";
                case BLOCKS -> "Blocks";
                case TOOLS -> "Tools";
                case COMBAT -> "Combat";
                case ARMOR -> "Armor";
                case CONSUMABLES -> "Consumables";
                case MATERIALS -> "Materials";
            };
        }
    }
    public enum SortMode {
        PRICE_ASC, PRICE_DESC, NEWEST, OLDEST;
        public SortMode next() {
            SortMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
        public String displayName() {
            return switch (this) {
                case PRICE_ASC -> "Price: Low to High";
                case PRICE_DESC -> "Price: High to Low";
                case NEWEST -> "Newest First";
                case OLDEST -> "Oldest First";
            };
        }
    }

    private static final Set<Item> DENY_LIST = Set.of(
        Items.BARRIER, Items.COMMAND_BLOCK, Items.CHAIN_COMMAND_BLOCK,
        Items.REPEATING_COMMAND_BLOCK, Items.COMMAND_BLOCK_MINECART,
        Items.STRUCTURE_BLOCK, Items.STRUCTURE_VOID, Items.JIGSAW,
        Items.DEBUG_STICK, Items.KNOWLEDGE_BOOK, Items.LIGHT,
        Items.BEDROCK, Items.SPAWNER, Items.REINFORCED_DEEPSLATE, Items.END_PORTAL_FRAME
    );
    private static final List<Item> DAILY_SERVER_BUY_BOOST_ITEMS = List.of(
        Items.DIAMOND,
        Items.RAW_IRON,
        Items.RAW_GOLD,
        Items.NETHERITE_SCRAP,
        Items.FIREWORK_ROCKET
    );
    /**
     * Server market showcase items. Land Deed and Claim Expansion Slot use
     * distinct base items, while display rendering still resolves the exact
     * custom ItemStack by position index.
     */
    private static final List<Item> SERVER_MARKET_ITEMS = List.of(
        Items.DIAMOND,
        Items.RAW_IRON,
        Items.RAW_GOLD,
        Items.NETHERITE_SCRAP,
        Items.FIREWORK_ROCKET,
        Items.MAP,
        Items.WRITABLE_BOOK,   // Land Deed showcase (index 6)
        Items.PAPER            // Claim Expansion Slot showcase (index 7)
    );
    static final int EXPANSION_SLOT_SHOWCASE_INDEX = 7;
    private static final String MARKET_KEY_SLOT_TICKET = "cabal:ticket_slot";
    private static final String MARKET_KEY_CLAIM_DEED = "cabal:ticket_claim_deed";
    private static final String MARKET_KEY_EXPANSION_SLOT = "cabal:ticket_expansion_slot";
    private static final double DAILY_SERVER_BUY_BOOST_MULTIPLIER = 1.25d;
    private static final int MAX_DENY_DEPTH = 16;

    private final EconomyDatabase db;
    private final EconomyService economyService;
    private final EconomyConfig config;
    private final Set<Identifier> allowedItemIds = new LinkedHashSet<>();
    private static final long ACTIVE_LISTINGS_CACHE_TTL_MS = 1000;
    private volatile long activeListingsCacheAtMs = 0;
    private volatile List<Listing> activeListingsCache = List.of();

    public AuctionService(EconomyDatabase db, EconomyService economyService, EconomyConfig config) {
        this.db = db;
        this.economyService = economyService;
        this.config = config;
        seedAllowedItems();
    }

    public Result listFromMainHand(ServerPlayer seller, int amount, double price) {
        if (price <= 0) return new Result(false, "Price must be greater than 0.");
        if (amount <= 0) return new Result(false, "Amount must be at least 1.");
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty()) return new Result(false, "Hold an item in your main hand first.");
        if (held.getCount() < amount) return new Result(false, "You are not holding enough items.");
        if ((held.getItem() == Items.MAP || held.getItem() == Items.WRITABLE_BOOK) && !LandTicketHelper.isLandTicket(held)) {
            return new Result(false, "Only Land Ticket / Land Deed / Expansion Slot items are tradable.");
        }
        if (LandTicketHelper.isLandTicket(held) && amount != 1) {
            return new Result(false, "These items are non-stackable and must be listed one at a time.");
        }
        if (LandTicketHelper.isClaimTransferTicket(held)) {
            if (amount != 1) {
                return new Result(false, "Claim transfer tickets must be listed one at a time.");
            }
            int claimId = LandTicketHelper.getClaimId(held);
            if (claimId <= 0) {
                return new Result(false, "Invalid claim transfer ticket data.");
            }
            ClaimManager claimManager = CabalClaimMod.getClaimManager();
            if (claimManager == null) {
                return new Result(false, "Claim system unavailable.");
            }
            ClaimManager.ClaimEntry claim = claimManager.getClaimById(claimId);
            if (claim == null) {
                return new Result(false, "Claim #" + claimId + " no longer exists.");
            }
            if (!claim.ownerUuid().equals(seller.getUUID().toString())) {
                return new Result(false, "You no longer own claim #" + claimId + ".");
            }
        }
        if (isDenied(held)) return new Result(false, "That item cannot be listed in auction.");
        ItemStack toList = held.copy();
        toList.setCount(amount);

        String itemBlob = serializeItem(toList);
        long now = EconomyDatabase.nowTs();
        long expires = now + 7 * 24 * 3600;
        String sql = "INSERT INTO auction_listings(seller, seller_name, item_blob, price, status, created_at, expires_at) VALUES(?, ?, ?, ?, 'ACTIVE', ?, ?)";
        try (Connection conn = db.open("auction");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seller.getUUID().toString());
            ps.setString(2, seller.getGameProfile().name());
            ps.setString(3, itemBlob);
            ps.setDouble(4, price);
            ps.setLong(5, now);
            ps.setLong(6, expires);
            ps.executeUpdate();
            held.shrink(amount);
            invalidateActiveListingsCache();
            return new Result(true, "Listed " + amount + "x for $" + String.format("%.2f", price) + ".");
        } catch (SQLException e) {
            return new Result(false, "Failed to create listing: " + e.getMessage());
        }
    }

    public Result listFromMainHand(ServerPlayer seller, double price) {
        ItemStack held = seller.getMainHandItem();
        int amount = held.isEmpty() ? 0 : held.getCount();
        return listFromMainHand(seller, amount, price);
    }

    public List<Listing> listActive(int limit, int offset) {
        List<Listing> snapshot = getActiveListingsSnapshot();
        if (offset >= snapshot.size()) {
            return List.of();
        }
        int end = Math.min(snapshot.size(), offset + Math.max(0, limit));
        if (end <= offset) return List.of();
        return new ArrayList<>(snapshot.subList(offset, end));
    }

    public BrowseResult browseAndCountListings(int limit, int offset, SortMode sort, String searchText) {
        return browseAndCountListings(limit, offset, sort, searchText, FilterMode.ALL);
    }

    public BrowseResult browseAndCountListings(int limit, int offset, SortMode sort, String searchText, FilterMode filterMode) {
        List<Listing> snapshot = getActiveListingsSnapshot();
        String query = searchText == null ? "" : searchText.trim().toLowerCase();
        FilterMode filter = filterMode == null ? FilterMode.ALL : filterMode;
        List<Listing> filtered = new ArrayList<>();
        for (Listing listing : snapshot) {
            if (matchesSearch(listing, query, filter)) {
                filtered.add(listing);
            }
        }
        filtered.sort(listingComparator(sort));
        int totalMatches = filtered.size();
        if (offset >= totalMatches) return new BrowseResult(List.of(), totalMatches);
        int end = Math.min(totalMatches, offset + Math.max(0, limit));
        if (end <= offset) return new BrowseResult(List.of(), totalMatches);
        return new BrowseResult(new ArrayList<>(filtered.subList(offset, end)), totalMatches);
    }

    public List<Listing> browseListings(int limit, int offset, SortMode sort, String searchText) {
        return browseAndCountListings(limit, offset, sort, searchText).page();
    }

    public List<Listing> browseListings(int limit, int offset, SortMode sort, String searchText, FilterMode filterMode) {
        return browseAndCountListings(limit, offset, sort, searchText, filterMode).page();
    }

    public int countActiveListings(String searchText) {
        return browseAndCountListings(0, 0, SortMode.PRICE_ASC, searchText).totalMatches();
    }

    public int countActiveListings(String searchText, FilterMode filterMode) {
        return browseAndCountListings(0, 0, SortMode.PRICE_ASC, searchText, filterMode).totalMatches();
    }

    private static Comparator<Listing> listingComparator(SortMode sort) {
        return switch (sort) {
            case PRICE_ASC -> Comparator.comparingDouble(Listing::price);
            case PRICE_DESC -> Comparator.comparingDouble(Listing::price).reversed();
            case NEWEST -> Comparator.comparingLong(Listing::createdAt).reversed();
            case OLDEST -> Comparator.comparingLong(Listing::createdAt);
        };
    }

    private static boolean matchesSearch(Listing listing, String query, FilterMode filterMode) {
        ItemStack stack = deserializeItem(listing.itemBlob());
        if (!matchesFilter(stack, filterMode)) return false;
        if (query == null || query.isBlank()) return true;
        String name = stack.getHoverName().getString().toLowerCase();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String idStr = itemId != null ? itemId.toString().toLowerCase() : "";
        return name.contains(query) || idStr.contains(query);
    }

    private static boolean matchesFilter(ItemStack stack, FilterMode filterMode) {
        if (filterMode == null || filterMode == FilterMode.ALL) return true;
        Item item = stack.getItem();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
        String path = itemId != null ? itemId.getPath() : "";
        boolean armorByName = path.endsWith("_helmet")
            || path.endsWith("_chestplate")
            || path.endsWith("_leggings")
            || path.endsWith("_boots")
            || path.equals("elytra");
        boolean toolByName = path.endsWith("_pickaxe")
            || path.endsWith("_axe")
            || path.endsWith("_shovel")
            || path.endsWith("_hoe")
            || path.endsWith("_shears")
            || path.endsWith("_fishing_rod")
            || path.endsWith("_flint_and_steel");
        boolean combatByName = path.endsWith("_sword")
            || path.endsWith("_bow")
            || path.endsWith("_crossbow")
            || path.endsWith("_trident")
            || path.endsWith("_shield");
        return switch (filterMode) {
            case ALL -> true;
            case BLOCKS -> item instanceof BlockItem;
            case TOOLS -> toolByName
                || item instanceof FishingRodItem
                || item instanceof ShearsItem
                || item instanceof FlintAndSteelItem;
            case COMBAT -> combatByName
                || item instanceof BowItem
                || item instanceof CrossbowItem
                || item instanceof TridentItem
                || item instanceof ShieldItem;
            case ARMOR -> armorByName;
            case CONSUMABLES -> stack.get(DataComponents.FOOD) != null
                || path.contains("potion")
                || path.contains("stew")
                || path.contains("soup")
                || path.contains("golden_apple");
            case MATERIALS -> !(item instanceof BlockItem)
                && !armorByName
                && !toolByName
                && !combatByName
                && !(item instanceof BowItem)
                && !(item instanceof CrossbowItem)
                && !(item instanceof TridentItem)
                && !(item instanceof ShieldItem)
                && stack.get(DataComponents.FOOD) == null;
        };
    }

    public List<Listing> listActiveBySeller(UUID seller, int limit, int offset) {
        List<Listing> out = new ArrayList<>();
        String sql = """
            SELECT id, seller, seller_name, item_blob, price, status, expires_at, created_at
            FROM auction_listings
            WHERE seller = ? AND status = 'ACTIVE' AND expires_at > ?
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;
        try (Connection conn = db.open("auction");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seller.toString());
            ps.setLong(2, EconomyDatabase.nowTs());
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Listing(
                        rs.getLong(1),
                        UUID.fromString(rs.getString(2)),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getDouble(5),
                        rs.getString(6),
                        rs.getLong(7),
                        rs.getLong(8)
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("listActiveBySeller failed seller={}", seller, e);
            throw new RuntimeException("Failed to load seller listings for " + seller, e);
        }
        return out;
    }

    public UnlistResult unlist(UUID seller, long listingId) {
        String select = "SELECT seller, item_blob, status FROM auction_listings WHERE id = ?";
        String cancel = "UPDATE auction_listings SET status='CANCELLED' WHERE id = ? AND seller = ? AND status='ACTIVE'";
        try (Connection conn = db.open("auction");
             PreparedStatement psSelect = conn.prepareStatement(select);
             PreparedStatement psCancel = conn.prepareStatement(cancel)) {
            psSelect.setLong(1, listingId);
            String dbSeller;
            String itemBlob;
            String status;
            try (ResultSet rs = psSelect.executeQuery()) {
                if (!rs.next()) {
                    return new UnlistResult(false, "Listing not found.", ItemStack.EMPTY);
                }
                dbSeller = rs.getString(1);
                itemBlob = rs.getString(2);
                status = rs.getString(3);
            }
            if (!seller.toString().equals(dbSeller)) {
                return new UnlistResult(false, "That listing does not belong to you.", ItemStack.EMPTY);
            }
            if (!"ACTIVE".equals(status)) {
                return new UnlistResult(false, "Listing is no longer active (already sold or cancelled).", ItemStack.EMPTY);
            }
            ItemStack item = deserializeItemStrict(itemBlob);
            if (item == null || item.isEmpty()) {
                LOGGER.warn("unlist blocked due to invalid item blob seller={} listingId={}", seller, listingId);
                return new UnlistResult(false, "Unlist failed due to invalid listing data. Please contact staff.", ItemStack.EMPTY);
            }
            psCancel.setLong(1, listingId);
            psCancel.setString(2, seller.toString());
            int changed = psCancel.executeUpdate();
            if (changed == 0) {
                return new UnlistResult(false, "Listing was already sold or cancelled by another action.", ItemStack.EMPTY);
            }
            invalidateActiveListingsCache();
            LOGGER.info("[AUCTION] event=unlist seller={} listingId={} item={} count={}",
                seller, listingId, itemName(item.getItem()), item.getCount());
            return new UnlistResult(true, "Unlisted successfully.", item);
        } catch (SQLException e) {
            LOGGER.error("unlist failed seller={} listingId={}", seller, listingId, e);
            return new UnlistResult(false, "Unlist failed due to a server error. Please try again.", ItemStack.EMPTY);
        }
    }

    public Result buy(ServerPlayer buyer, long listingId) {
        String select = "SELECT seller, seller_name, item_blob, price, status, expires_at FROM auction_listings WHERE id = ?";
        String complete = "UPDATE auction_listings SET status='SOLD' WHERE id = ? AND status='ACTIVE'";
        try (Connection conn = db.open("auction");
             PreparedStatement psSelect = conn.prepareStatement(select);
             PreparedStatement psComplete = conn.prepareStatement(complete)) {
            conn.setAutoCommit(false);
            psSelect.setLong(1, listingId);
            UUID seller;
            String itemBlob;
            double price;
            String status;
            long expires;
            try (ResultSet rs = psSelect.executeQuery()) {
                if (!rs.next()) {
                    conn.rollback();
                    return new Result(false, "Listing not found.");
                }
                seller = UUID.fromString(rs.getString(1));
                itemBlob = rs.getString(3);
                price = rs.getDouble(4);
                status = rs.getString(5);
                expires = rs.getLong(6);
            }
            if (!"ACTIVE".equals(status) || expires <= EconomyDatabase.nowTs()) {
                conn.rollback();
                return new Result(false, "Listing is no longer active.");
            }
            if (buyer.getUUID().equals(seller)) {
                conn.rollback();
                return new Result(false, "You cannot buy your own listing.");
            }
            ItemStack item = deserializeItemStrict(itemBlob);
            if (item == null || item.isEmpty()) {
                conn.rollback();
                LOGGER.warn("buy blocked due to invalid item blob seller={} listingId={} buyer={}",
                    seller, listingId, buyer.getUUID());
                return new Result(false, "Listing data is invalid. Please contact staff.");
            }
            if (!canFullyInsert(buyer, item.copy())) {
                if (!LandTicketHelper.isClaimTransferTicket(item)) {
                    conn.rollback();
                    return new Result(false, "Not enough inventory space. Free up slots and try again.");
                }
            }
            psComplete.setLong(1, listingId);
            int changed = psComplete.executeUpdate();
            if (changed == 0) {
                conn.rollback();
                return new Result(false, "Listing was purchased by another player.");
            }
            if (!economyService.transfer(conn, buyer.getUUID(), seller, price, "auction_purchase")) {
                conn.rollback();
                return new Result(false, "Unable to settle payment.");
            }
            if (LandTicketHelper.isClaimTransferTicket(item)) {
                int claimId = LandTicketHelper.getClaimId(item);
                if (claimId <= 0) {
                    conn.rollback();
                    return new Result(false, "Invalid claim transfer ticket payload.");
                }
                ClaimManager claimManager = CabalClaimMod.getClaimManager();
                if (claimManager == null) {
                    conn.rollback();
                    return new Result(false, "Claim system unavailable; purchase aborted.");
                }
                int recipientOwned = claimManager.getClaimsByOwner(buyer.getUUID()).size();
                int recipientSlots = claimManager.getClaimSlots(buyer.getUUID());
                if (recipientOwned >= recipientSlots) {
                    conn.rollback();
                    return new Result(false,
                        "You do not have a free claim slot for claim #" + claimId
                            + ". You are at your claim-slot limit.");
                }
                conn.commit();
                ClaimManager.TransferClaimResult transfer = claimManager.transferClaimOwnership(
                    claimId, seller, buyer.getUUID(), buyer.getGameProfile().name());
                if (transfer != ClaimManager.TransferClaimResult.SUCCESS) {
                    boolean compensated = compensateClaimTransferFailure(
                        listingId, seller, buyer.getUUID(), price);
                    LOGGER.error("Claim transfer failed after SQL commit listingId={} claimId={} transferResult={} compensated={}",
                        listingId, claimId, transfer, compensated);
                    economyService.invalidateBalanceCache(buyer.getUUID());
                    economyService.invalidateBalanceCache(seller);
                    invalidateActiveListingsCache();
                    if (transfer == ClaimManager.TransferClaimResult.RECIPIENT_NO_CLAIM_SLOTS) {
                        return new Result(false,
                            "Claim transfer could not complete because you have no free claim slots. "
                                + "Transaction was reverted.");
                    }
                    if (compensated) {
                        return new Result(false, "Claim transfer failed after purchase; transaction was reverted. Please try again.");
                    }
                    return new Result(false, "Claim transfer failed after purchase and automatic revert failed. Contact staff immediately.");
                }
                economyService.invalidateBalanceCache(buyer.getUUID());
                economyService.invalidateBalanceCache(seller);
                invalidateActiveListingsCache();
                return new Result(true, "Purchased claim #" + claimId + " for $" + String.format("%.2f", price) + ". Ownership transferred.");
            } else {
                ItemStack delivery = item.copy();
                boolean inserted = buyer.getInventory().add(delivery);
                if (!inserted || !delivery.isEmpty()) {
                    if (!delivery.isEmpty()) {
                        buyer.drop(delivery, false);
                    }
                    LOGGER.warn("buy delivery fallback listingId={} buyer={} item={} dropped_leftover={}",
                        listingId, buyer.getUUID(), itemName(item.getItem()), !delivery.isEmpty());
                }
            }
            conn.commit();
            economyService.invalidateBalanceCache(buyer.getUUID());
            economyService.invalidateBalanceCache(seller);
            invalidateActiveListingsCache();
            return new Result(true, "Purchased listing #" + listingId + " for $" + String.format("%.2f", price) + ".");
        } catch (SQLException e) {
            return new Result(false, "Purchase failed: " + e.getMessage());
        }
    }

    private boolean compensateClaimTransferFailure(long listingId, UUID seller, UUID buyer, double price) {
        String restoreListing = "UPDATE auction_listings SET status='ACTIVE' WHERE id = ? AND status='SOLD'";
        try (Connection conn = db.open("auction");
             PreparedStatement psRestore = conn.prepareStatement(restoreListing)) {
            conn.setAutoCommit(false);
            psRestore.setLong(1, listingId);
            int restored = psRestore.executeUpdate();
            if (restored == 0) {
                conn.rollback();
                LOGGER.error("Compensation failed: could not restore listing status listingId={}", listingId);
                return false;
            }
            if (!economyService.transfer(conn, seller, buyer, price, "auction_claim_transfer_compensate")) {
                conn.rollback();
                LOGGER.error("Compensation failed: could not reverse payment listingId={} seller={} buyer={} price={}",
                    listingId, seller, buyer, price);
                return false;
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            LOGGER.error("Compensation failed with SQL error listingId={}", listingId, e);
            return false;
        }
    }

    public Result buyLowestForItem(ServerPlayer buyer, Item item) {
        PurchaseQuote quote = quoteBestOffer(item, buyer.getUUID());
        if (quote == null) {
            return new Result(false, "No valid listings available for " + marketItemName(item) + ".");
        }
        return executeQuotePurchase(buyer, quote);
    }

    static String marketItemName(Item item) {
        if (item == Items.MAP) return "Land Ticket";
        if (item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK) return "Land Deed";
        if (item == Items.PAPER) return "Claim Expansion Slot";
        return itemName(item);
    }

    public PurchaseQuote quoteBestBuyOffer(Item item, UUID buyerId) {
        PurchaseQuote best = null;
        Listing playerListing = findLowestActiveListing(item, buyerId);
        if (playerListing != null) {
            best = new PurchaseQuote(item, playerListing.price(), playerListing.id(), OfferSource.PLAYER_LISTING);
        }
        if (config.serverSellEnabled) {
            double sellPrice = serverSellPrice(item);
            if (sellPrice >= 0) {
                if (best == null || sellPrice < best.price()) {
                    best = new PurchaseQuote(item, sellPrice, -1, OfferSource.SERVER_LISTING);
                }
            }
        }
        return best;
    }

    public PurchaseQuote quoteBestOffer(Item item, UUID buyerId) {
        return quoteBestBuyOffer(item, buyerId);
    }

    public String identifyMarketKeyForItem(Item item) {
        return identifyMarketKey(item);
    }

    public Result executeQuotePurchase(ServerPlayer buyer, PurchaseQuote quote) {
        if (quote == null) return new Result(false, "Missing purchase quote.");
        if (quote.source() == OfferSource.PLAYER_LISTING) {
            return buy(buyer, quote.listingId());
        }
        return buyFromServerListing(buyer, quote.item(), quote.price());
    }

    public List<Item> allowedItems() {
        List<Item> out = new ArrayList<>();
        for (Identifier id : allowedItemIds) {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item != null && item != Items.AIR) out.add(item);
        }
        return out;
    }

    /**
     * Base-item level buy price. For WRITABLE_BOOK, returns the deed price as default;
     * stack-level overloads disambiguate deed vs expansion.
     */
    public double serverBuyPrice(Item item) {
        if (item == Items.DIAMOND) return config.serverBuyDiamond;
        if (item == Items.RAW_IRON) return config.serverBuyRawIron;
        if (item == Items.RAW_GOLD) return config.serverBuyRawGold;
        if (item == Items.NETHERITE_SCRAP) return config.serverBuyNetheriteScrap;
        if (item == Items.FIREWORK_ROCKET) return config.serverBuyFireworksStack;
        if (item == Items.MAP) return 50.0;
        if (item == Items.WRITABLE_BOOK) return 30.0;
        if (item == Items.WRITTEN_BOOK) return 30.0;
        return -1;
    }

    public double serverBuyPrice(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (stack.getItem() == Items.FIREWORK_ROCKET && !isDefaultFireworkRocketStack(stack)) return -1;
        if (stack.getItem() == Items.MAP && !LandTicketHelper.isSlotTicket(stack)) return -1;
        if (stack.getItem() == Items.PAPER) {
            if (LandTicketHelper.isClaimExpansionSlotTicket(stack)) return expansionSlotServerBuyPrice();
            return -1;
        }
        if (stack.getItem() == Items.WRITABLE_BOOK || stack.getItem() == Items.WRITTEN_BOOK) {
            if (LandTicketHelper.isClaimTransferTicket(stack)) return 30.0;
            return -1;
        }
        return serverBuyPrice(stack.getItem());
    }

    public double serverBuyPriceWithDailyBoost(Item item) {
        double base = serverBuyPrice(item);
        if (base < 0) return -1;
        if (isDailyBoostedServerBuyItem(item)) {
            return base * DAILY_SERVER_BUY_BOOST_MULTIPLIER;
        }
        return base;
    }

    public double serverBuyPriceWithDailyBoost(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (stack.getItem() == Items.FIREWORK_ROCKET && !isDefaultFireworkRocketStack(stack)) return -1;
        if (stack.getItem() == Items.MAP && !LandTicketHelper.isSlotTicket(stack)) return -1;
        if (stack.getItem() == Items.PAPER) {
            if (LandTicketHelper.isClaimExpansionSlotTicket(stack)) return expansionSlotServerBuyPrice();
            return -1;
        }
        if (stack.getItem() == Items.WRITABLE_BOOK || stack.getItem() == Items.WRITTEN_BOOK) {
            if (LandTicketHelper.isClaimTransferTicket(stack)) return 30.0;
            return -1;
        }
        return serverBuyPriceWithDailyBoost(stack.getItem());
    }

    public Item dailyBoostedServerBuyItem() {
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        int index = Math.floorMod(day, DAILY_SERVER_BUY_BOOST_ITEMS.size());
        return DAILY_SERVER_BUY_BOOST_ITEMS.get(index);
    }

    public List<Item> dailyServerBuyBoostItems() {
        return DAILY_SERVER_BUY_BOOST_ITEMS;
    }

    public List<Item> serverMarketItems() {
        return SERVER_MARKET_ITEMS;
    }

    public boolean isDailyBoostedServerBuyItem(Item item) {
        return item != null && item == dailyBoostedServerBuyItem();
    }

    /**
     * Item-level sell price. WRITABLE_BOOK returns -1 at this level because
     * deed and expansion must be distinguished via stack-level overload.
     */
    public double serverSellPrice(Item item) {
        if (item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK) return -1;
        if (item == Items.PAPER) return expansionSlotServerSellPrice();
        if (item == Items.MAP) return 250.0;
        if (item == Items.FIREWORK_ROCKET) return config.serverSellFireworksStack;
        double buyPrice = serverBuyPrice(item);
        if (buyPrice < 0) return -1;
        return buyPrice * config.serverSellMultiplier;
    }

    public double serverSellPrice(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (stack.getItem() == Items.FIREWORK_ROCKET && !isDefaultFireworkRocketStack(stack)) return -1;
        if (stack.getItem() == Items.MAP && !LandTicketHelper.isSlotTicket(stack)) return -1;
        if (stack.getItem() == Items.PAPER) {
            if (LandTicketHelper.isClaimExpansionSlotTicket(stack)) return expansionSlotServerSellPrice();
            return -1;
        }
        if (stack.getItem() == Items.WRITABLE_BOOK || stack.getItem() == Items.WRITTEN_BOOK) {
            return -1;
        }
        return serverSellPrice(stack.getItem());
    }

    public int serverSellQuantity(Item item) {
        if (item == Items.FIREWORK_ROCKET) return 64;
        return 1;
    }

    public int serverSellQuantity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 1;
        if (stack.getItem() == Items.FIREWORK_ROCKET && !isDefaultFireworkRocketStack(stack)) return 1;
        if (stack.getItem() == Items.MAP && !LandTicketHelper.isSlotTicket(stack)) return 1;
        if (stack.getItem() == Items.PAPER && LandTicketHelper.isClaimExpansionSlotTicket(stack)) return 1;
        if (stack.getItem() == Items.WRITABLE_BOOK || stack.getItem() == Items.WRITTEN_BOOK) return 1;
        return serverSellQuantity(stack.getItem());
    }

    public boolean isStackOnlyServerTrade(Item item) {
        return item == Items.FIREWORK_ROCKET || item == Items.MAP
            || item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK || item == Items.PAPER;
    }

    public boolean isStackOnlyServerTrade(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() == Items.FIREWORK_ROCKET) {
            return isDefaultFireworkRocketStack(stack);
        }
        if (stack.getItem() == Items.MAP) {
            return LandTicketHelper.isSlotTicket(stack);
        }
        if (stack.getItem() == Items.PAPER) {
            return LandTicketHelper.isClaimExpansionSlotTicket(stack);
        }
        if (stack.getItem() == Items.WRITABLE_BOOK || stack.getItem() == Items.WRITTEN_BOOK) {
            return LandTicketHelper.isClaimTransferTicket(stack);
        }
        return false;
    }

    public double serverBuyPayout(Item item, int amount) {
        double priceForUnitOrStack = serverBuyPriceWithDailyBoost(item);
        if (item == Items.FIREWORK_ROCKET) {
            return amount == 64 ? priceForUnitOrStack : -1;
        }
        if (item == Items.MAP) {
            return amount == 1 ? priceForUnitOrStack : -1;
        }
        if (item == Items.WRITABLE_BOOK) {
            return amount == 1 ? priceForUnitOrStack : -1;
        }
        if (priceForUnitOrStack < 0 || amount <= 0) return -1;
        return priceForUnitOrStack * amount;
    }

    public double serverBuyPayout(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (stack.getItem() == Items.FIREWORK_ROCKET && !isDefaultFireworkRocketStack(stack)) return -1;
        if (stack.getItem() == Items.MAP && !LandTicketHelper.isSlotTicket(stack)) return -1;
        if (stack.getItem() == Items.PAPER) {
            if (LandTicketHelper.isClaimExpansionSlotTicket(stack)) {
                return stack.getCount() == 1 ? expansionSlotServerBuyPrice() : -1;
            }
            return -1;
        }
        if (stack.getItem() == Items.WRITABLE_BOOK || stack.getItem() == Items.WRITTEN_BOOK) {
            if (!LandTicketHelper.isClaimTransferTicket(stack)) return -1;
        }
        return serverBuyPayout(stack.getItem(), stack.getCount());
    }

    public boolean serverBuyEnabled() {
        return config.serverBuyEnabled;
    }

    public boolean serverSellEnabled() {
        return config.serverSellEnabled;
    }

    public Result buyFromServerListing(ServerPlayer buyer, Item item, double price) {
        if (!config.serverSellEnabled) {
            return new Result(false, "Server sales are currently disabled.");
        }
        double sellPrice = serverSellPrice(item);
        if (sellPrice < 0) {
            return new Result(false, "This item is not sold by the server.");
        }
        if (Math.abs(sellPrice - price) > 0.01) {
            LOGGER.warn("server_sell price drift: quoted={} current={} buyer={} item={}", price, sellPrice, buyer.getUUID(), marketItemName(item));
            return new Result(false, "Quote changed, please retry.");
        }
        int quantity = serverSellQuantity(item);
        ItemStack preview = createServerSellDeliveryItem(item, quantity);
        if (!canFullyInsert(buyer, preview.copy())) {
            return new Result(false, "Not enough inventory space. Free up slots and try again.");
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("event", "server_sell_to_player");
        meta.put("item", marketItemName(item));
        meta.put("price", sellPrice);
        boolean debited;
        try {
            debited = economyService.addBalance(buyer.getUUID(), -sellPrice, "server_sell_to_player", meta);
        } catch (Exception e) {
            LOGGER.error("server_sell debit failed buyer={} item={} price={}", buyer.getUUID(), marketItemName(item), sellPrice, e);
            return new Result(false, "Failed to process payment.");
        }
        if (!debited) {
            return new Result(false, "Failed to process payment. Please try again later.");
        }
        ItemStack delivery = createServerSellDeliveryItem(item, quantity);
        boolean inserted = buyer.getInventory().add(delivery);
        if (!inserted || !delivery.isEmpty()) {
            boolean refunded = false;
            try {
                refunded = economyService.addBalance(buyer.getUUID(), sellPrice, "server_sell_refund_no_space", Map.of(
                    "event", "server_sell_refund_no_space",
                    "item", marketItemName(item),
                    "price", sellPrice
                ));
            } catch (Exception e) {
                LOGGER.error("server_sell refund failed buyer={} item={} price={}", buyer.getUUID(), marketItemName(item), sellPrice, e);
            }
            if (!delivery.isEmpty()) {
                buyer.drop(delivery, false);
            }
            if (refunded) {
                economyService.invalidateBalanceCache(buyer.getUUID());
            }
            return new Result(false, "Inventory changed and item could not be delivered. " + (refunded ? "Payment refunded." : "Please contact staff."));
        }
        economyService.invalidateBalanceCache(buyer.getUUID());
        LOGGER.info("[AUCTION] event=server_sell buyer={} item={} qty={} price={}", buyer.getUUID(), marketItemName(item), quantity, sellPrice);
        return new Result(true, "Bought " + quantity + "x " + marketItemName(item) + " from server for $" + String.format("%.2f", sellPrice) + ".");
    }

    private static ItemStack createServerSellDeliveryItem(Item item, int quantity) {
        if (item == Items.MAP) return LandTicketHelper.createSlotTicket();
        if (item == Items.PAPER) return LandTicketHelper.createClaimExpansionSlotTicket();
        return new ItemStack(item, quantity);
    }

    public double expansionSlotServerSellPrice() {
        return config.serverSellExpansionSlot;
    }

    public double expansionSlotServerBuyPrice() {
        return config.serverBuyExpansionSlot;
    }

    public Result buyExpansionSlotFromServer(ServerPlayer buyer) {
        if (!config.serverSellEnabled) {
            return new Result(false, "Server sales are currently disabled.");
        }
        double price = expansionSlotServerSellPrice();
        ItemStack preview = LandTicketHelper.createClaimExpansionSlotTicket();
        if (!canFullyInsert(buyer, preview.copy())) {
            return new Result(false, "Not enough inventory space. Free up slots and try again.");
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("event", "server_sell_to_player");
        meta.put("item", "Claim Expansion Slot");
        meta.put("price", price);
        boolean debited;
        try {
            debited = economyService.addBalance(buyer.getUUID(), -price, "server_sell_to_player", meta);
        } catch (Exception e) {
            LOGGER.error("expansion_slot server sell debit failed buyer={} price={}", buyer.getUUID(), price, e);
            return new Result(false, "Failed to process payment.");
        }
        if (!debited) {
            return new Result(false, "Failed to process payment. Please try again later.");
        }
        ItemStack delivery = LandTicketHelper.createClaimExpansionSlotTicket();
        boolean inserted = buyer.getInventory().add(delivery);
        if (!inserted || !delivery.isEmpty()) {
            boolean refunded = false;
            try {
                refunded = economyService.addBalance(buyer.getUUID(), price, "server_sell_refund_no_space", Map.of(
                    "event", "server_sell_refund_no_space",
                    "item", "Claim Expansion Slot",
                    "price", price
                ));
            } catch (Exception e) {
                LOGGER.error("expansion_slot refund failed buyer={} price={}", buyer.getUUID(), price, e);
            }
            if (!delivery.isEmpty()) {
                buyer.drop(delivery, false);
            }
            if (refunded) {
                economyService.invalidateBalanceCache(buyer.getUUID());
            }
            return new Result(false, "Inventory changed and item could not be delivered. " + (refunded ? "Payment refunded." : "Please contact staff."));
        }
        economyService.invalidateBalanceCache(buyer.getUUID());
        LOGGER.info("[AUCTION] event=server_sell_expansion buyer={} price={}", buyer.getUUID(), price);
        return new Result(true, "Bought 1x Claim Expansion Slot from server for $" + String.format("%.2f", price) + ".");
    }

    private static boolean canFullyInsert(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return true;
        int remaining = stack.getCount();
        int invMax = player.getInventory().getMaxStackSize();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty()) {
                int perSlot = Math.min(invMax, stack.getMaxStackSize());
                remaining -= Math.min(perSlot, remaining);
            } else if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int slotLimit = Math.min(invMax, slot.getMaxStackSize());
                int space = Math.max(0, slotLimit - slot.getCount());
                if (space > 0) {
                    remaining -= Math.min(space, remaining);
                }
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    public Result sellToMarket(ServerPlayer seller, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new Result(false, "Amount must be at least 1.");
        Item item = stack.getItem();
        int amount = stack.getCount();
        if (amount <= 0) return new Result(false, "Amount must be at least 1.");
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null || !allowedItemIds.contains(id)) {
            return new Result(false, "That item is not tradable in this market.");
        }
        if (item == Items.MAP && !LandTicketHelper.isSlotTicket(stack)) {
            return new Result(false, "Only Land Ticket map items are tradable.");
        }
        if ((item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK)
                && !LandTicketHelper.isClaimTransferTicket(stack)) {
            return new Result(false, "Only valid Land Deed items are tradable.");
        }
        if (item == Items.PAPER && !LandTicketHelper.isClaimExpansionSlotTicket(stack)) {
            return new Result(false, "Only valid Claim Expansion Slot items are tradable.");
        }
        if (item == Items.FIREWORK_ROCKET && !isDefaultFireworkRocketStack(stack)) {
            return new Result(false, "Only default Firework Rockets are tradable on this market.");
        }
        if (!serverBuyEnabled()) {
            return new Result(false, "No server buy price available for this item.");
        }
        int requiredStack = serverSellQuantity(stack);
        if (isStackOnlyServerTrade(stack) && amount != requiredStack) {
            return new Result(false, "This item can only be sold as a full stack of " + requiredStack + ".");
        }
        double payout = serverBuyPayout(stack);
        if (payout < 0) {
            return new Result(false, "No server buy price available for this item.");
        }
        boolean stackOnlyTrade = isStackOnlyServerTrade(stack);
        double unitPriceFromPayout = stackOnlyTrade
            ? (requiredStack > 0 ? (payout / requiredStack) : -1)
            : (amount > 0 ? (payout / amount) : -1);
        Map<String, Object> meta = new HashMap<>();
        meta.put("event", "auction_market_sell");
        meta.put("item", itemName(item));
        meta.put("amount", amount);
        if (stackOnlyTrade) {
            meta.put("unit_price", "stack-only");
            if (unitPriceFromPayout >= 0) {
                meta.put("unit_price_each", unitPriceFromPayout);
            }
        } else {
            meta.put("unit_price", unitPriceFromPayout);
        }
        meta.put("source", "SERVER_BUY");
        boolean credited;
        try {
            credited = economyService.addBalance(seller.getUUID(), payout, "auction_market_sell", meta);
        } catch (Exception e) {
            credited = false;
            LOGGER.error("auction_market_sell credit threw seller={} item={} amount={} error={}",
                seller.getUUID(), itemName(item), amount, e.getMessage());
        }
        if (!credited) {
            return new Result(false, "Failed to settle sale payout.");
        }
        if (stackOnlyTrade) {
            if (unitPriceFromPayout >= 0) {
                return new Result(true, "Sold " + requiredStack + "x " + itemName(item) + " for $" + String.format("%.2f", payout)
                    + " (per full stack, $" + String.format("%.2f", unitPriceFromPayout) + " each).");
            }
            return new Result(true, "Sold " + requiredStack + "x " + itemName(item) + " for $" + String.format("%.2f", payout) + " (per full stack).");
        }
        return new Result(true, "Sold " + amount + "x " + itemName(item) + " for $" + String.format("%.2f", payout) +
            " ($" + String.format("%.2f", unitPriceFromPayout) + " each).");
    }

    public Map<String, MarketEntry> lowestActiveByAllowedItem() {
        Map<String, MarketEntry> lowest = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Listing listing : getActiveListingsSnapshot()) {
            ItemStack stack = deserializeItem(listing.itemBlob());
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null || !allowedItemIds.contains(id)) continue;
            String marketKey = identifyMarketKey(stack);
            counts.merge(marketKey, 1, Integer::sum);
            MarketEntry existing = lowest.get(marketKey);
            if (existing == null || listing.price() < existing.lowestPrice()) {
                lowest.put(marketKey, new MarketEntry(marketKey, stack.getItem(), listing.price(), listing.id(), counts.get(marketKey)));
            }
        }
        for (Map.Entry<String, MarketEntry> entry : lowest.entrySet()) {
            int count = counts.getOrDefault(entry.getKey(), 1);
            MarketEntry value = entry.getValue();
            lowest.put(entry.getKey(), new MarketEntry(value.marketKey(), value.displayItem(), value.lowestPrice(), value.listingId(), count));
        }
        return lowest;
    }

    public String listingSummary(Listing listing) {
        ItemStack stack = deserializeItem(listing.itemBlob());
        return stack.getCount() + "x " + itemName(stack.getItem());
    }

    private Listing findLowestActiveListing(Item item, UUID buyerId) {
        Listing best = null;
        String targetKey = identifyMarketKey(item);
        if (targetKey.isBlank()) return null;
        for (Listing listing : getActiveListingsSnapshot()) {
            if (listing.seller().equals(buyerId)) continue;
            ItemStack listed = deserializeItem(listing.itemBlob());
            if (!targetKey.equals(identifyMarketKey(listed))) continue;
            if (best == null || listing.price() < best.price()) {
                best = listing;
            }
        }
        return best;
    }

    private static String identifyMarketKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        if (LandTicketHelper.isSlotTicket(stack)) return MARKET_KEY_SLOT_TICKET;
        if (LandTicketHelper.isClaimTransferTicket(stack)) return MARKET_KEY_CLAIM_DEED;
        if (LandTicketHelper.isClaimExpansionSlotTicket(stack)) return MARKET_KEY_EXPANSION_SLOT;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null ? id.toString() : "";
    }

    private static String identifyMarketKey(Item item) {
        if (item == null || item == Items.AIR) return "";
        if (item == Items.MAP) return MARKET_KEY_SLOT_TICKET;
        if (item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK) return MARKET_KEY_CLAIM_DEED;
        if (item == Items.PAPER) return MARKET_KEY_EXPANSION_SLOT;
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id != null ? id.toString() : "";
    }

    private boolean isAllowedItem(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && allowedItemIds.contains(id);
    }

    private static boolean isDenied(ItemStack stack) {
        return isDenied(stack, 0);
    }

    private static boolean isDenied(ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty()) return true;
        if (depth > MAX_DENY_DEPTH) return true;
        if (DENY_LIST.contains(stack.getItem())) return true;

        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            for (ItemStack nested : container.nonEmptyItemsCopy()) {
                if (isDenied(nested, depth + 1)) return true;
            }
        }

        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack nested : (Iterable<ItemStack>) bundle.itemCopyStream()::iterator) {
                if (isDenied(nested, depth + 1)) return true;
            }
        }

        TypedEntityData<?> blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntityData != null && containsDeniedInTag(blockEntityData.copyTagWithoutId(), depth + 1)) {
            return true;
        }

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && containsDeniedInTag(customData.copyTag(), depth + 1)) {
            return true;
        }

        return false;
    }

    private static boolean containsDeniedInTag(CompoundTag tag, int depth) {
        if (tag == null || tag.isEmpty()) return false;
        if (depth > MAX_DENY_DEPTH) return true;

        if (tag.contains("id")) {
            String idValue = tag.getStringOr("id", "");
            if (isDeniedItemId(idValue)) return true;
            ItemStack decoded = decodeNestedItemTag(tag);
            if (!decoded.isEmpty() && isDenied(decoded, depth + 1)) return true;
        }

        for (Map.Entry<String, Tag> entry : tag.entrySet()) {
            Tag value = entry.getValue();
            if (value instanceof CompoundTag nestedTag) {
                if (containsDeniedInTag(nestedTag, depth + 1)) return true;
            } else if (value instanceof ListTag listTag) {
                if (containsDeniedInList(listTag, depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean containsDeniedInList(ListTag list, int depth) {
        if (depth > MAX_DENY_DEPTH) return true;
        for (int i = 0; i < list.size(); i++) {
            Tag value = list.get(i);
            if (value instanceof CompoundTag nestedTag) {
                if (containsDeniedInTag(nestedTag, depth + 1)) return true;
            } else if (value instanceof ListTag nestedList) {
                if (containsDeniedInList(nestedList, depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean isDeniedItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return false;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item != null && item != Items.AIR && DENY_LIST.contains(item);
    }

    private static ItemStack decodeNestedItemTag(CompoundTag itemTag) {
        if (itemTag == null || itemTag.isEmpty()) return ItemStack.EMPTY;
        try {
            ItemStack decoded = ItemStack.CODEC.parse(NbtOps.INSTANCE, itemTag).result().orElse(ItemStack.EMPTY);
            if (!decoded.isEmpty() && decoded.getCount() <= 0) decoded.setCount(1);
            return decoded;
        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean isDefaultFireworkRocketStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != Items.FIREWORK_ROCKET) {
            return false;
        }
        ItemStack normalized = stack.copy();
        normalized.setCount(1);
        return ItemStack.isSameItemSameComponents(normalized, new ItemStack(Items.FIREWORK_ROCKET, 1));
    }

    private List<Listing> getActiveListingsSnapshot() {
        long nowMs = System.currentTimeMillis();
        List<Listing> cached = activeListingsCache;
        if (nowMs - activeListingsCacheAtMs < ACTIVE_LISTINGS_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (this) {
            long refreshedNowMs = System.currentTimeMillis();
            if (refreshedNowMs - activeListingsCacheAtMs < ACTIVE_LISTINGS_CACHE_TTL_MS) {
                return activeListingsCache;
            }
            List<Listing> refreshed = fetchActiveListingsFromDb();
            activeListingsCache = refreshed;
            activeListingsCacheAtMs = refreshedNowMs;
            return refreshed;
        }
    }

    private List<Listing> fetchActiveListingsFromDb() {
        List<Listing> out = new ArrayList<>();
        String sql = """
            SELECT id, seller, seller_name, item_blob, price, status, expires_at, created_at
            FROM auction_listings
            WHERE status = 'ACTIVE' AND expires_at > ?
            ORDER BY price ASC, created_at ASC, id ASC
            """;
        try (Connection conn = db.open("auction");
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, EconomyDatabase.nowTs());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Listing(
                        rs.getLong(1),
                        UUID.fromString(rs.getString(2)),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getDouble(5),
                        rs.getString(6),
                        rs.getLong(7),
                        rs.getLong(8)
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("listActive failed", e);
        }
        return out;
    }

    private void invalidateActiveListingsCache() {
        activeListingsCacheAtMs = 0;
    }

    private void seedAllowedItems() {
        // Keep the auction scope intentionally minimal: only the four tracked economic materials.
        addAllowed("minecraft:diamond");
        addAllowed("minecraft:raw_iron");
        addAllowed("minecraft:raw_gold");
        addAllowed("minecraft:netherite_scrap");
        addAllowed("minecraft:firework_rocket");
        addAllowed("minecraft:map");
        addAllowed("minecraft:writable_book");
        addAllowed("minecraft:written_book");
        addAllowed("minecraft:paper");
    }

    static String itemName(Item item) {
        return item.getName(new ItemStack(item, 1)).getString();
    }

    private void addAllowed(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id != null) {
            allowedItemIds.add(id);
        }
    }

    static String serializeItem(ItemStack item) {
        JsonElement encoded = ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, item).result().orElse(null);
        if (encoded != null) {
            String json = encoded.toString();
            String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return "codecjson:" + b64;
        }
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem());
        return "legacy:" + id + "|" + item.getCount();
    }

    static ItemStack deserializeItem(String blob) {
        if (blob == null || blob.isBlank()) {
            return new ItemStack(Items.STONE, 1);
        }
        if (blob != null && blob.startsWith("codecjson:")) {
            try {
                String b64 = blob.substring("codecjson:".length());
                String json = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                JsonElement element = JsonParser.parseString(json);
                ItemStack decoded = ItemStack.CODEC.parse(JsonOps.INSTANCE, element).result().orElse(ItemStack.EMPTY);
                if (!decoded.isEmpty() && decoded.getCount() <= 0) {
                    decoded.setCount(1);
                }
                if (!decoded.isEmpty()) {
                    return decoded;
                }
            } catch (Exception ignored) {
                // Fallback to legacy format below.
            }
        }

        if (blob != null && blob.startsWith("legacy:")) {
            blob = blob.substring("legacy:".length());
        }
        String[] parts = blob.split("\\|", 2);
        String itemId = parts.length > 0 ? parts[0] : "minecraft:stone";
        int count = 1;
        if (parts.length > 1) {
            try {
                count = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }
        Item item = Items.STONE;
        Identifier id = Identifier.tryParse(itemId);
        if (id != null) {
            Item resolved = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
            if (resolved != null) item = resolved;
        }
        return new ItemStack(item, Math.max(1, count));
    }

    private static ItemStack deserializeItemStrict(String blob) {
        if (blob == null || blob.isBlank()) return ItemStack.EMPTY;
        if (blob.startsWith("codecjson:")) {
            try {
                String b64 = blob.substring("codecjson:".length());
                String json = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                JsonElement element = JsonParser.parseString(json);
                ItemStack decoded = ItemStack.CODEC.parse(JsonOps.INSTANCE, element).result().orElse(ItemStack.EMPTY);
                if (decoded.isEmpty()) return ItemStack.EMPTY;
                if (decoded.getCount() <= 0) decoded.setCount(1);
                return decoded;
            } catch (Exception ignored) {
                return ItemStack.EMPTY;
            }
        }
        String normalized = blob.startsWith("legacy:") ? blob.substring("legacy:".length()) : blob;
        String[] parts = normalized.split("\\|", 2);
        if (parts.length == 0 || parts[0].isBlank()) return ItemStack.EMPTY;
        Identifier id = Identifier.tryParse(parts[0]);
        if (id == null) return ItemStack.EMPTY;
        Item resolved = BuiltInRegistries.ITEM.getValue(id);
        if (resolved == null || resolved == Items.AIR) return ItemStack.EMPTY;
        int count = 1;
        if (parts.length > 1) {
            try {
                count = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }
        return new ItemStack(resolved, Math.max(1, count));
    }
}
