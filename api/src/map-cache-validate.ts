import * as fs from "fs";
import { readSpawn, readSpawnProtectionRadius } from "./world-reader.js";
import { MAP_GEN_VERSION, cacheFilePath, type DiskBiomeCache, type MapDimension } from "./map-cache-types.js";

export type ValidateResult =
  | { ok: true; disk: DiskBiomeCache }
  | { ok: false; reason: string };

const DEFAULT_WORLD_RADIUS_BLOCKS = 10000;

function getExpectedWorldRadiusBlocks(): number {
  const raw = process.env.WORLD_RADIUS_BLOCKS?.trim();
  if (!raw) return DEFAULT_WORLD_RADIUS_BLOCKS;
  if (!/^\d+$/.test(raw)) return DEFAULT_WORLD_RADIUS_BLOCKS;
  const parsed = Number(raw);
  if (!Number.isSafeInteger(parsed) || parsed < 0) return DEFAULT_WORLD_RADIUS_BLOCKS;
  return parsed;
}

/**
 * Same acceptance rules as map-service load — version + spawn fingerprint.
 * Used by PM2 `ensure_map_cache` and `validate-map-cache` CLI.
 */
export function validateMapCache(apiDir: string, dimension: MapDimension): ValidateResult {
  const fp = cacheFilePath(apiDir, dimension);
  if (!fs.existsSync(fp)) {
    return { ok: false, reason: `map cache file missing for ${dimension}` };
  }

  let disk: DiskBiomeCache;
  try {
    disk = JSON.parse(fs.readFileSync(fp, "utf-8")) as DiskBiomeCache;
  } catch {
    return { ok: false, reason: "map cache is not valid JSON" };
  }

  if (disk.mapGenVersion !== MAP_GEN_VERSION) {
    return {
      ok: false,
      reason: `map cache version ${disk.mapGenVersion} !== ${MAP_GEN_VERSION}`,
    };
  }
  if (disk.dimension !== dimension) {
    return { ok: false, reason: `map cache dimension ${disk.dimension} !== ${dimension}` };
  }

  const spawn = readSpawn();
  const protection = readSpawnProtectionRadius();
  const expectedWorldRadiusBlocks = getExpectedWorldRadiusBlocks();
  if (
    disk.spawnX !== spawn.x ||
    disk.spawnY !== spawn.y ||
    disk.spawnZ !== spawn.z ||
    disk.spawnProtection !== protection
  ) {
    return { ok: false, reason: "map cache spawn or spawn-protection does not match world" };
  }
  if (disk.worldRadiusBlocks !== expectedWorldRadiusBlocks) {
    return {
      ok: false,
      reason: `map cache radius ${String(disk.worldRadiusBlocks)} !== ${String(expectedWorldRadiusBlocks)}`,
    };
  }

  return { ok: true, disk };
}
