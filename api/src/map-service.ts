import * as fs from "fs";
import { readSpawn, readClaims, readSpawnProtectionRadius } from "./world-reader.js";
import { cacheFilePath, type DiskBiomeCache, type MapClaim, type MapPayload, type MapDimension } from "./map-cache-types.js";
import { validateMapCache } from "./map-cache-validate.js";

const CLAIM_RADIUS = 100;
const MAP_RELOAD_INTERVAL_MS = 30_000;
const MAP_DIMENSIONS: MapDimension[] = ["overworld", "nether", "end"];

export type { MapClaim, MapPayload };

export interface MapBgLogger {
  info(obj: object, msg?: string): void;
  warn(obj: object, msg?: string): void;
  error(obj: object, msg?: string): void;
}

let served: Record<MapDimension, MapPayload | null> = {
  overworld: null,
  nether: null,
  end: null,
};
let cacheFingerprint: Record<MapDimension, { spawnX: number; spawnY: number; spawnZ: number; spawnProtection: number } | null> = {
  overworld: null,
  nether: null,
  end: null,
};
let lastCacheFileMtime: Record<MapDimension, number> = {
  overworld: 0,
  nether: 0,
  end: 0,
};
let mapBackgroundIntervalId: ReturnType<typeof setInterval> | undefined;
let mapBackgroundStarted = false;

function claimDimension(raw: string | undefined): MapDimension {
  const v = (raw ?? "").trim().toLowerCase();
  if (v === "minecraft:the_nether" || v === "the_nether" || v === "nether") return "nether";
  if (v === "minecraft:the_end" || v === "the_end" || v === "end") return "end";
  return "overworld";
}

function claimsList(dimension: MapDimension): MapClaim[] {
  const claimsMap = readClaims();
  return Object.values(claimsMap)
    .filter((c) => typeof c.ownerName === "string" && c.ownerName.trim().length > 0)
    .filter((c) => claimDimension(c.dimension) === dimension)
    .map((c) => ({
      ownerName: c.ownerName.trim(),
      x: c.x,
      z: c.z,
      radius: CLAIM_RADIUS,
    }));
}

function composeFromDisk(
  spawn: ReturnType<typeof readSpawn>,
  protection: number,
  claims: MapClaim[],
  disk: DiskBiomeCache
): MapPayload {
  return {
    format: "compact",
    dimension: disk.dimension,
    spawn: { x: spawn.x, z: spawn.z, protectionRadius: protection },
    claimRadius: CLAIM_RADIUS,
    claims,
    bounds: disk.bounds,
    step: disk.step,
    gridWidth: disk.gridWidth,
    gridHeight: disk.gridHeight,
    palette: disk.palette,
    paletteColors: disk.paletteColors,
    grid: disk.grid,
  };
}

function loadDiskIntoServed(apiDir: string, dimension: MapDimension, log: MapBgLogger): boolean {
  const fp = cacheFilePath(apiDir, dimension);

  const validated = validateMapCache(apiDir, dimension);
  if (!validated.ok) {
    log.warn({}, `map(${dimension}): ${validated.reason}`);
    return false;
  }
  const disk = validated.disk;

  const spawn = readSpawn();
  const protection = readSpawnProtectionRadius();
  const claims = claimsList(dimension);

  let kb = 0;
  let mtimeMs = 0;
  try {
    const stat = fs.statSync(fp);
    mtimeMs = stat.mtimeMs;
    kb = Math.round(stat.size / 1024);
  } catch {
    lastCacheFileMtime[dimension] = 0;
    return false;
  }
  lastCacheFileMtime[dimension] = mtimeMs;

  served[dimension] = composeFromDisk(spawn, protection, claims, disk);
  cacheFingerprint[dimension] = {
    spawnX: disk.spawnX,
    spawnY: disk.spawnY,
    spawnZ: disk.spawnZ,
    spawnProtection: disk.spawnProtection,
  };

  log.info({ dimension, gridCells: disk.grid.length, kb }, "map: loaded compact biome grid + live claims");
  return true;
}

function refreshClaimsOnly(dimension: MapDimension): void {
  const current = served[dimension];
  if (!current || !cacheFingerprint[dimension]) return;
  const spawn = readSpawn();
  const protection = readSpawnProtectionRadius();
  const fp = cacheFingerprint[dimension];
  if (
    spawn.x !== fp.spawnX ||
    spawn.y !== fp.spawnY ||
    spawn.z !== fp.spawnZ ||
    protection !== fp.spawnProtection
  ) {
    served[dimension] = null;
    cacheFingerprint[dimension] = null;
    return;
  }
  served[dimension] = {
    ...current,
    spawn: { x: spawn.x, z: spawn.z, protectionRadius: protection },
    claims: claimsList(dimension),
  };
}

export function getServedMap(dimension: MapDimension): MapPayload | null {
  return served[dimension];
}

export function stopMapBackground(): void {
  if (mapBackgroundIntervalId !== undefined) {
    clearInterval(mapBackgroundIntervalId);
    mapBackgroundIntervalId = undefined;
  }
  mapBackgroundStarted = false;
  served = { overworld: null, nether: null, end: null };
  cacheFingerprint = { overworld: null, nether: null, end: null };
  lastCacheFileMtime = { overworld: 0, nether: 0, end: 0 };
}

export function startMapBackground(opts: { apiDir: string; log: MapBgLogger }): void {
  const { apiDir, log } = opts;

  if (mapBackgroundStarted) {
    if (mapBackgroundIntervalId !== undefined) {
      clearInterval(mapBackgroundIntervalId);
      mapBackgroundIntervalId = undefined;
    }
  }
  mapBackgroundStarted = true;

  for (const dimension of MAP_DIMENSIONS) {
    if (!loadDiskIntoServed(apiDir, dimension, log)) {
      served[dimension] = null;
      cacheFingerprint[dimension] = null;
      log.warn(
        { dimension },
        "map: missing/invalid cache — run: cd api && npm run build && npm run generate-map-cache:all"
      );
    }
  }

  mapBackgroundIntervalId = setInterval(() => {
    for (const dimension of MAP_DIMENSIONS) {
      const fp = cacheFilePath(apiDir, dimension);
      let cacheMtime = 0;
      try {
        cacheMtime = fs.statSync(fp).mtimeMs;
      } catch {
        cacheMtime = 0;
      }

      if (cacheMtime !== lastCacheFileMtime[dimension]) {
        if (!loadDiskIntoServed(apiDir, dimension, log)) {
          served[dimension] = null;
          cacheFingerprint[dimension] = null;
        }
        continue;
      }
      refreshClaimsOnly(dimension);
    }
  }, MAP_RELOAD_INTERVAL_MS);
}
