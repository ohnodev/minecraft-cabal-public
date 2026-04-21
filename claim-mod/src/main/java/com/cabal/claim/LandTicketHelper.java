package com.cabal.claim;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.regex.Pattern;

public final class LandTicketHelper {
    private LandTicketHelper() {}
    private static final Pattern MC_HEX_SECTION_FORMATTING = Pattern.compile("§x(?:§[0-9A-Fa-f]){6}");
    private static final Pattern MC_SECTION_FORMATTING = Pattern.compile("§[0-9A-FK-ORa-fk-or]");

    private static final String TAG_KEY = "cabal_land_ticket";
    private static final String TAG_TYPE = "ticket_type";
    private static final String TAG_TICKET_ID = "ticket_id";
    private static final String TAG_CLAIM_ID = "claim_id";
    private static final String TAG_CLAIM_X = "claim_x";
    private static final String TAG_CLAIM_Z = "claim_z";
    private static final String TAG_CLAIM_DIM = "claim_dim";
    private static final String TYPE_SLOT = "slot";
    private static final String TYPE_CLAIM_TRANSFER = "claim_transfer";
    private static final String TYPE_EXPANSION = "claim_expansion_slot";
    private static final int SLOT_TICKET_MODEL_ID = 910001;
    private static final int CLAIM_TICKET_MODEL_ID = 910002;
    private static final int EXPANSION_TICKET_MODEL_ID = 910003;

    public static ItemStack createSlotTicket() {
        ItemStack ticket = new ItemStack(Items.MAP, 1);
        ticket.set(DataComponents.CUSTOM_NAME,
            Component.literal("\u00a76\u00a7lLand Ticket"));
        ticket.set(DataComponents.RARITY, Rarity.RARE);
        ticket.set(DataComponents.CUSTOM_MODEL_DATA,
            new CustomModelData(List.of((float) SLOT_TICKET_MODEL_ID), List.of(), List.of(), List.of()));
        ticket.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        ticket.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("\u00a77Consumed to create one land claim."),
            Component.literal("\u00a77Hold in main hand, then run \u00a7b/claim\u00a77."),
            Component.literal("\u00a7eSellable on the auction house.")
        )));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_KEY, true);
        tag.putString(TAG_TYPE, TYPE_SLOT);
        tag.putString(TAG_TICKET_ID, UUID.randomUUID().toString());
        ticket.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return ticket;
    }

    public static ItemStack createClaimTransferTicket(int claimId, String ownerName, int claimX, int claimZ, String dimension) {
        ItemStack ticket = new ItemStack(Items.WRITABLE_BOOK, 1);
        ticket.set(DataComponents.CUSTOM_NAME,
            Component.literal("\u00a76\u00a7lLand Deed \u00a77#"+ claimId));
        ticket.set(DataComponents.CUSTOM_MODEL_DATA,
            new CustomModelData(List.of((float) CLAIM_TICKET_MODEL_ID), List.of(), List.of(), List.of()));
        ticket.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        ticket.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("\u00a77Represents ownership transfer of claim #" + claimId + "."),
            Component.literal("\u00a77Current owner: \u00a7f" + ownerName),
            Component.literal("\u00a77Coords: \u00a7f[" + claimX + ", " + claimZ + "]"),
            Component.literal("\u00a77Dimension: \u00a7f" + dimension),
            Component.literal("\u00a77When sold in auction, ownership transfers to buyer."),
            Component.literal("\u00a7eTrusted members are preserved.")
        )));
        // Optional player-authored listing notes are persisted with the item stack.
        ticket.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(
            Filterable.passThrough("Title:")
        )));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_KEY, true);
        tag.putString(TAG_TYPE, TYPE_CLAIM_TRANSFER);
        tag.putString(TAG_TICKET_ID, UUID.randomUUID().toString());
        tag.putInt(TAG_CLAIM_ID, claimId);
        tag.putInt(TAG_CLAIM_X, claimX);
        tag.putInt(TAG_CLAIM_Z, claimZ);
        tag.putString(TAG_CLAIM_DIM, dimension);
        ticket.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return ticket;
    }

    /**
     * Creates a non-tradable visual-only Land Deed item for server market showcase UI.
     */
    public static ItemStack createClaimDeedShowcaseItem() {
        ItemStack display = new ItemStack(Items.WRITABLE_BOOK, 1);
        display.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a76\u00a7lLand Deed"));
        display.set(DataComponents.RARITY, Rarity.RARE);
        display.set(DataComponents.CUSTOM_MODEL_DATA,
            new CustomModelData(List.of((float) CLAIM_TICKET_MODEL_ID), List.of(), List.of(), List.of()));
        display.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return display;
    }

    public static ItemStack createClaimExpansionSlotTicket() {
        ItemStack ticket = new ItemStack(Items.PAPER, 1);
        ticket.set(DataComponents.CUSTOM_NAME,
            Component.literal("\u00a7a\u00a7lClaim Expansion Slot"));
        ticket.set(DataComponents.RARITY, Rarity.RARE);
        ticket.set(DataComponents.CUSTOM_MODEL_DATA,
            new CustomModelData(List.of((float) EXPANSION_TICKET_MODEL_ID), List.of(), List.of(), List.of()));
        ticket.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        ticket.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("\u00a77Right-click to unlock +1 claim slot."),
            Component.literal("\u00a77Max " + ClaimManager.MAX_CLAIM_SLOTS + " claim slots per player."),
            Component.literal("\u00a7eTradeable on the auction house.")
        )));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_KEY, true);
        tag.putString(TAG_TYPE, TYPE_EXPANSION);
        tag.putString(TAG_TICKET_ID, UUID.randomUUID().toString());
        ticket.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return ticket;
    }

    /**
     * Creates a non-tradable visual-only Claim Expansion Slot item for server market showcase UI.
     */
    public static ItemStack createExpansionSlotShowcaseItem() {
        ItemStack display = new ItemStack(Items.PAPER, 1);
        display.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7a\u00a7lClaim Expansion Slot"));
        display.set(DataComponents.RARITY, Rarity.RARE);
        display.set(DataComponents.CUSTOM_MODEL_DATA,
            new CustomModelData(List.of((float) EXPANSION_TICKET_MODEL_ID), List.of(), List.of(), List.of()));
        display.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return display;
    }

    public static boolean isLandTicket(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.MAP
            && stack.getItem() != Items.WRITABLE_BOOK
            && stack.getItem() != Items.WRITTEN_BOOK
            && stack.getItem() != Items.PAPER) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAG_KEY) || !tag.getBooleanOr(TAG_KEY, false)) return false;
        String type = tag.getStringOr(TAG_TYPE, "");
        if (TYPE_SLOT.equals(type)) {
            return stack.getItem() == Items.MAP;
        }
        if (TYPE_CLAIM_TRANSFER.equals(type)) {
            return stack.getItem() == Items.WRITABLE_BOOK || stack.getItem() == Items.WRITTEN_BOOK;
        }
        if (TYPE_EXPANSION.equals(type)) {
            return stack.getItem() == Items.PAPER;
        }
        return false;
    }

    public static boolean isSlotTicket(ItemStack stack) {
        if (!isLandTicket(stack)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return TYPE_SLOT.equals(data.copyTag().getStringOr(TAG_TYPE, ""));
    }

    public static boolean isClaimTransferTicket(ItemStack stack) {
        if (!isLandTicket(stack)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return TYPE_CLAIM_TRANSFER.equals(data.copyTag().getStringOr(TAG_TYPE, ""))
            && data.copyTag().contains(TAG_CLAIM_ID);
    }

    public static String claimDeedTitle(ItemStack stack) {
        if (!isClaimTransferTicket(stack)) return null;
        WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written == null) return null;
        String title = sanitizeFormatting(written.title().get(false));
        if (title == null) return null;
        title = title.trim();
        return title.isEmpty() ? null : title;
    }

    public static List<String> claimDeedPreviewLines(ItemStack stack, int maxLines) {
        if (!isClaimTransferTicket(stack) || maxLines <= 0) return List.of();
        String plain = claimDeedDescriptionPlain(stack);
        if (plain == null || plain.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : plain.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            out.add(trimmed);
            if (out.size() >= maxLines) break;
        }
        return out;
    }

    public static String claimDeedDescriptionPlain(ItemStack stack) {
        if (!isClaimTransferTicket(stack)) return null;
        List<String> pages = new ArrayList<>();

        WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            for (Object pageObj : written.getPages(false)) {
                String cleaned = sanitizeDeedPage(pageObj);
                if (cleaned != null && !cleaned.isBlank()) pages.add(cleaned);
            }
            return pages.isEmpty() ? null : String.join("\n", pages);
        }

        WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable != null) {
            writable.getPages(false).forEach(pageObj -> {
                String cleaned = sanitizeDeedPage(pageObj);
                if (cleaned != null && !cleaned.isBlank()) pages.add(cleaned);
            });
        }
        return pages.isEmpty() ? null : String.join("\n", pages);
    }

    private static String pageToText(Object pageObj) {
        if (pageObj == null) return null;
        if (pageObj instanceof String s) return s;
        if (pageObj instanceof Component c) return c.getString();
        if (pageObj instanceof Filterable<?> f) {
            Object raw = f.get(false);
            if (raw instanceof String s) return s;
            if (raw instanceof Component c) return c.getString();
            return raw != null ? raw.toString() : null;
        }
        return pageObj.toString();
    }

    private static String sanitizeDeedPage(Object pageObj) {
        String text = pageToText(pageObj);
        if (text == null) return null;
        // Strip raw section-sign formatting so pasted formatting cannot spoof lore.
        text = sanitizeFormatting(text);
        // Normalize whitespace and remove untouched template marker.
        text = text.replace("\r", "").trim();
        if (text.equals("Tile:")) return null;
        if (text.equalsIgnoreCase("describe your land below:")) return null;
        return text.isBlank() ? null : text;
    }

    private static String sanitizeFormatting(String text) {
        if (text == null) return null;
        String strippedHex = MC_HEX_SECTION_FORMATTING.matcher(text).replaceAll("");
        return MC_SECTION_FORMATTING.matcher(strippedHex).replaceAll("");
    }

    public static int getClaimId(ItemStack stack) {
        if (!isClaimTransferTicket(stack)) return -1;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return -1;
        return data.copyTag().getIntOr(TAG_CLAIM_ID, -1);
    }

    public static String claimCoordinatesLabel(ItemStack stack) {
        if (!isClaimTransferTicket(stack)) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        int x = tag.getIntOr(TAG_CLAIM_X, Integer.MIN_VALUE);
        int z = tag.getIntOr(TAG_CLAIM_Z, Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            return null;
        }
        String dim = tag.getStringOr(TAG_CLAIM_DIM, "minecraft:overworld");
        return "[" + x + ", " + z + "] (" + dim + ")";
    }

    /**
     * Finds and removes one slot Land Ticket from the player's inventory.
     * @return true if a ticket was consumed
     */
    public static boolean consumeOneSlotTicket(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (isSlotTicket(slot)) {
                slot.shrink(1);
                // Write back to force inventory sync/update to client.
                player.getInventory().setItem(i, slot);
                player.getInventory().setChanged();
                player.containerMenu.broadcastChanges();
                return true;
            }
        }
        return false;
    }

    public static boolean hasSlotTicket(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isSlotTicket(player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasSlotTicketInMainHand(ServerPlayer player) {
        return isSlotTicket(player.getMainHandItem());
    }

    public static boolean consumeOneSlotTicketFromMainHand(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (!isSlotTicket(main)) return false;
        main.shrink(1);
        player.setItemInHand(InteractionHand.MAIN_HAND, main);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }

    public static boolean consumeOneSlotTicketFromHand(ServerPlayer player, InteractionHand hand) {
        ItemStack used = player.getItemInHand(hand);
        if (!isSlotTicket(used)) return false;
        used.shrink(1);
        player.setItemInHand(hand, used);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }

    public static boolean isClaimExpansionSlotTicket(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.PAPER) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAG_KEY) || !tag.getBooleanOr(TAG_KEY, false)) return false;
        return TYPE_EXPANSION.equals(tag.getStringOr(TAG_TYPE, ""));
    }

    public static boolean consumeOneExpansionSlotFromMainHand(ServerPlayer player) {
        return consumeOneExpansionSlotFromHand(player, InteractionHand.MAIN_HAND);
    }

    public static boolean consumeOneExpansionSlotFromHand(ServerPlayer player, InteractionHand hand) {
        ItemStack used = player.getItemInHand(hand);
        if (!isClaimExpansionSlotTicket(used)) return false;
        used.shrink(1);
        player.setItemInHand(hand, used);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }
}
