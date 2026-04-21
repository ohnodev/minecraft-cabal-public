import Database from "better-sqlite3";
import * as path from "path";
import { fileURLToPath } from "url";

const API_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const DB_PATH = path.resolve(
  process.env.MC_SERVER_DIR ?? path.resolve(API_ROOT, "..", "server"),
  "economy.db"
);
const ACTIVE_STALE_MS = 15_000;

export type LiveEvokerBoss = {
  present: boolean;
  bossUuid: string | null;
  x: number | null;
  y: number | null;
  z: number | null;
  dimension: string | null;
  updatedAtMs: number | null;
  markerRadius: number;
};

let cachedDb: Database.Database | null = null;

function getDb(): Database.Database {
  if (cachedDb) return cachedDb;
  cachedDb = new Database(DB_PATH, { readonly: true, fileMustExist: true });
  cachedDb.pragma("busy_timeout = 3000");
  return cachedDb;
}

export function fetchLiveEvokerBoss(): LiveEvokerBoss {
  try {
    const db = getDb();
    const row = db
      .prepare(
        `SELECT boss_uuid, status, last_x, last_y, last_z, last_dimension, last_update_ms
         FROM cabal_evoker_boss_events
         WHERE status = 'ACTIVE'
         ORDER BY spawned_at_ms DESC
         LIMIT 1`
      )
      .get() as
      | {
          boss_uuid: string;
          status: string;
          last_x: number | null;
          last_y: number | null;
          last_z: number | null;
          last_dimension: string | null;
          last_update_ms: number | null;
        }
      | undefined;

    if (!row) {
      return emptyBossPayload();
    }
    const updatedAtMs = Number(row.last_update_ms ?? 0);
    if (!Number.isFinite(updatedAtMs) || updatedAtMs <= 0 || Date.now() - updatedAtMs > ACTIVE_STALE_MS) {
      return emptyBossPayload();
    }

    return {
      present: true,
      bossUuid: row.boss_uuid,
      x: row.last_x == null ? null : Number(row.last_x),
      y: row.last_y == null ? null : Number(row.last_y),
      z: row.last_z == null ? null : Number(row.last_z),
      dimension: row.last_dimension == null ? "minecraft:overworld" : String(row.last_dimension),
      updatedAtMs,
      markerRadius: 48,
    };
  } catch (err) {
    if (cachedDb) {
      try {
        cachedDb.close();
      } catch {
        // ignore
      }
      cachedDb = null;
    }
    console.error("[evoker-boss-db] fetchLiveEvokerBoss failed:", err);
    return emptyBossPayload();
  }
}

export function closeEvokerBossDb(): void {
  if (!cachedDb) return;
  try {
    cachedDb.close();
  } catch {
    // ignore
  }
  cachedDb = null;
}

function emptyBossPayload(): LiveEvokerBoss {
  return {
    present: false,
    bossUuid: null,
    x: null,
    y: null,
    z: null,
    dimension: null,
    updatedAtMs: null,
    markerRadius: 48,
  };
}
