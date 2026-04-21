import * as path from "path";

/** Bump when on-disk biome cache shape or sampling logic changes. */
export const MAP_GEN_VERSION = 10;

export type MapDimension = "overworld" | "nether" | "end";

export type MapClaim = { ownerName: string; x: number; z: number; radius: number };

/** Compact biome grid: indices row-major wx outer, wz inner (matches sampleBiomes order). */
export type MapPayload = {
  format: "compact";
  dimension: MapDimension;
  spawn: { x: number; z: number; protectionRadius: number };
  claimRadius: number;
  claims: MapClaim[];
  bounds: { minX: number; maxX: number; minZ: number; maxZ: number };
  step: number;
  gridWidth: number;
  gridHeight: number;
  palette: string[];
  paletteColors: string[];
  grid: number[];
};

/**
 * On-disk cache. We intentionally do NOT key off level.dat mtime — it changes on every
 * world save and would reject a valid biome grid. Match on spawn + spawn-protection only.
 */
export type DiskBiomeCache = {
  mapGenVersion: number;
  dimension: MapDimension;
  spawnX: number;
  spawnY: number;
  spawnZ: number;
  spawnProtection: number;
  worldRadiusBlocks: number;
  bounds: MapPayload["bounds"];
  step: number;
  gridWidth: number;
  gridHeight: number;
  palette: string[];
  paletteColors: string[];
  grid: number[];
};

export function cacheFilePath(apiDir: string, dimension: MapDimension): string {
  return path.join(apiDir, "cache", `map-biomes-${dimension}.json`);
}
