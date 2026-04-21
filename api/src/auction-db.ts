import Database from "better-sqlite3";
import * as path from "path";
import * as fs from "fs";

const API_ROOT = path.resolve(new URL(".", import.meta.url).pathname, "..");
const DB_PATH = path.resolve(
  process.env.MC_SERVER_DIR ?? path.resolve(API_ROOT, "..", "server"),
  "economy.db"
);

export interface AuctionRow {
  id: number;
  sellerName: string;
  itemId: string;
  spriteItemId: string;
  itemName: string;
  itemCount: number;
  price: number;
  createdAt: number;
  expiresAt: number;
}

/**
 * Decode an item_blob produced by AuctionService.serializeItem() into
 * a canonical item id and count.
 *
 * Formats:
 *   codecjson:<base64 JSON>  – full Mojang codec; JSON has {"id":"minecraft:diamond","count":3,...}
 *   legacy:<item_id>|<count> – simple fallback
 */
function decodeItemBlob(blob: string): { itemId: string; count: number } {
  if (blob.startsWith("codecjson:")) {
    try {
      const json = Buffer.from(blob.slice("codecjson:".length), "base64").toString("utf-8");
      const obj = JSON.parse(json);
      const id: string = obj?.id ?? "minecraft:stone";
      const count: number =
        typeof obj?.count === "number" ? Math.max(1, obj.count) : 1;
      return { itemId: id, count };
    } catch {
      return { itemId: "minecraft:stone", count: 1 };
    }
  }

  const normalized = blob.startsWith("legacy:") ? blob.slice("legacy:".length) : blob;
  const parts = normalized.split("|", 2);
  const itemId = parts[0] || "minecraft:stone";
  let count = 1;
  if (parts.length > 1) {
    const n = parseInt(parts[1], 10);
    if (Number.isFinite(n) && n > 0) count = n;
  }
  return { itemId, count };
}

const ITEM_DISPLAY_NAMES: Record<string, string> = {
  "minecraft:diamond": "Diamond",
  "minecraft:raw_iron": "Raw Iron",
  "minecraft:raw_gold": "Raw Gold",
  "minecraft:netherite_scrap": "Netherite Scrap",
  "minecraft:firework_rocket": "Firework Rocket",
  "minecraft:stone": "Stone",
  "minecraft:cobblestone": "Cobblestone",
  "minecraft:iron_ingot": "Iron Ingot",
  "minecraft:gold_ingot": "Gold Ingot",
  "minecraft:emerald": "Emerald",
  "minecraft:lapis_lazuli": "Lapis Lazuli",
  "minecraft:redstone": "Redstone",
  "minecraft:coal": "Coal",
  "minecraft:copper_ingot": "Copper Ingot",
  "minecraft:netherite_ingot": "Netherite Ingot",
  "minecraft:oak_log": "Oak Log",
  "minecraft:spruce_log": "Spruce Log",
  "minecraft:birch_log": "Birch Log",
};

function displayName(itemId: string): string {
  if (ITEM_DISPLAY_NAMES[itemId]) return ITEM_DISPLAY_NAMES[itemId];
  const stripped = itemId.replace(/^minecraft:/, "");
  return stripped
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

let cachedDb: Database.Database | null = null;
let cachedSpriteNames: Set<string> | null = null;

const SPRITES_DIR = path.resolve(API_ROOT, "public", "item-sprites");
const SPRITE_PLACEHOLDER = "barrier";

function loadSpriteNames(): Set<string> {
  if (cachedSpriteNames) return cachedSpriteNames;
  try {
    const names = fs
      .readdirSync(SPRITES_DIR)
      .filter((f) => f.endsWith(".png"))
      .map((f) => f.replace(/\.png$/i, ""));
    cachedSpriteNames = new Set(names);
  } catch (err) {
    console.warn("[auction-db] loadSpriteNames failed for directory:", SPRITES_DIR, err);
    return new Set();
  }
  return cachedSpriteNames;
}

function resolveSpriteItemId(itemId: string): string {
  const id = itemId.replace(/^minecraft:/, "");
  const available = loadSpriteNames();

  const candidates = [
    id,
    // Common families that may not have direct item sprites.
    id.startsWith("infested_") ? id.slice("infested_".length) : "",
    id.startsWith("waxed_") ? id.slice("waxed_".length) : "",
    id.endsWith("_wall_hanging_sign") ? id.replace(/_wall_hanging_sign$/, "_hanging_sign") : "",
    id.endsWith("_wall_sign") ? id.replace(/_wall_sign$/, "_sign") : "",
    id.endsWith("_wall_banner") ? id.replace(/_wall_banner$/, "_banner") : "",
    id.endsWith("_shulker_box") ? "shulker_box" : "",
  ].filter(Boolean);

  for (const candidate of candidates) {
    if (available.has(candidate)) return `minecraft:${candidate}`;
  }
  return `minecraft:${SPRITE_PLACEHOLDER}`;
}

function getDb(): Database.Database {
  if (cachedDb) return cachedDb;
  cachedDb = new Database(DB_PATH, { readonly: true, fileMustExist: true });
  cachedDb.pragma("journal_mode = WAL");
  cachedDb.pragma("busy_timeout = 3000");
  return cachedDb;
}

export function fetchActiveListings(limit = 200): AuctionRow[] {
  try {
    const db = getDb();
    const nowEpochSec = Math.floor(Date.now() / 1000);
    const rows = db
      .prepare(
        `SELECT id, seller_name, item_blob, price, created_at, expires_at
         FROM auction_listings
         WHERE status = 'ACTIVE' AND expires_at > ?
         ORDER BY price ASC, created_at ASC, id ASC
         LIMIT ?`
      )
      .all(nowEpochSec, limit) as {
      id: number;
      seller_name: string;
      item_blob: string;
      price: number;
      created_at: number;
      expires_at: number;
    }[];

    return rows.map((r) => {
      const { itemId, count } = decodeItemBlob(r.item_blob);
      return {
        id: r.id,
        sellerName: r.seller_name,
        itemId,
        spriteItemId: resolveSpriteItemId(itemId),
        itemName: displayName(itemId),
        itemCount: count,
        price: r.price,
        createdAt: r.created_at,
        expiresAt: r.expires_at,
      };
    });
  } catch (err) {
    if (cachedDb) {
      try { cachedDb.close(); } catch { /* ignore */ }
      cachedDb = null;
    }
    console.error("[auction-db] fetchActiveListings failed:", err);
    return [];
  }
}

export function closeAuctionDb(): void {
  if (cachedDb) {
    try { cachedDb.close(); } catch { /* ignore */ }
    cachedDb = null;
  }
}
