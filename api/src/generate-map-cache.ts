/**
 * Offline biome grid generator — run separately from the API (heavy memory / CPU).
 *   cd api && npm run build && npm run generate-map-cache
 */
import * as fs from "fs";
import * as path from "path";
import { fileURLToPath } from "url";
import { readSpawn, readSpawnProtectionRadius, sampleBiomes } from "./world-reader.js";
import { biomeColor } from "./biome-colors.js";
import { MAP_GEN_VERSION, cacheFilePath, type DiskBiomeCache, type MapDimension } from "./map-cache-types.js";
import { cellsToCompact } from "./map-compact.js";

const __dir = path.dirname(fileURLToPath(import.meta.url));
const API_ROOT = path.resolve(__dir, "..");
const REPO_ROOT = path.resolve(API_ROOT, "..");

if (!process.env.MC_SERVER_DIR) {
  process.env.MC_SERVER_DIR = path.join(REPO_ROOT, "server");
}

const MAP_STEP = 32;
const DEFAULT_WORLD_RADIUS_BLOCKS = 10000;
const WORLD_RADIUS_BLOCKS = (() => {
  const raw = process.env.WORLD_RADIUS_BLOCKS?.trim();
  if (!raw) return DEFAULT_WORLD_RADIUS_BLOCKS;
  if (!/^\d+$/.test(raw)) return DEFAULT_WORLD_RADIUS_BLOCKS;
  const parsed = Number(raw);
  if (!Number.isSafeInteger(parsed) || parsed < 0) return DEFAULT_WORLD_RADIUS_BLOCKS;
  return parsed;
})();
const MAP_DIMENSION = ((process.env.MAP_DIMENSION ?? process.env.MC_DIMENSION ?? "overworld").trim().toLowerCase()) as MapDimension;

function writeAtomicJson(fp: string, json: string): void {
  const dir = path.dirname(fp);
  const base = path.basename(fp);
  const tmp = path.join(dir, `.${base}.tmp.${process.pid}`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(tmp, json, "utf-8");
  fs.renameSync(tmp, fp);
}

function main(): void {
  console.error(`[generate-map-cache] MC_SERVER_DIR=${process.env.MC_SERVER_DIR} MAP_DIMENSION=${MAP_DIMENSION}`);

  const spawn = readSpawn();
  const protection = readSpawnProtectionRadius();
  // Always generate the full square world map for the configured border policy:
  // 20,000 blocks edge-to-edge (10,000 from spawn each axis).
  const minX = spawn.x - WORLD_RADIUS_BLOCKS;
  const maxX = spawn.x + WORLD_RADIUS_BLOCKS;
  const minZ = spawn.z - WORLD_RADIUS_BLOCKS;
  const maxZ = spawn.z + WORLD_RADIUS_BLOCKS;
  const circles = [
    // Two corners force sampleBiomes to include the full rectangular extent.
    { x: minX, z: minZ, radius: 0 },
    { x: maxX, z: maxZ, radius: 0 },
    // Keep spawn ring included explicitly.
    { x: spawn.x, z: spawn.z, radius: Math.max(protection, 32) },
  ];

  const t0 = Date.now();
  const { step, cells } = sampleBiomes(circles, MAP_STEP, 0, false, false);
  const known = cells.filter((c) => c.biome !== "unknown");
  const { gridWidth, gridHeight, palette, paletteColors, grid } = cellsToCompact(cells, biomeColor);
  // Align bounds to the actual sampled grid extents so viewport and grid stay in lockstep.
  const activeBounds = {
    minX,
    maxX: minX + Math.max(0, gridWidth - 1) * step,
    minZ,
    maxZ: minZ + Math.max(0, gridHeight - 1) * step,
  };
  console.error(
    `[generate-map-cache] sampled ${cells.length} bounded cells (known=${known.length}, step=${step}) grid ${gridWidth}x${gridHeight} bounds ${activeBounds.minX},${activeBounds.minZ}..${activeBounds.maxX},${activeBounds.maxZ} in ${Date.now() - t0}ms`
  );

  const disk: DiskBiomeCache = {
    mapGenVersion: MAP_GEN_VERSION,
    dimension: MAP_DIMENSION,
    spawnX: spawn.x,
    spawnY: spawn.y,
    spawnZ: spawn.z,
    spawnProtection: protection,
    worldRadiusBlocks: WORLD_RADIUS_BLOCKS,
    bounds: activeBounds,
    step,
    gridWidth,
    gridHeight,
    palette,
    paletteColors,
    grid,
  };

  const fp = cacheFilePath(API_ROOT, MAP_DIMENSION);
  const payload = JSON.stringify(disk);
  writeAtomicJson(fp, payload);
  const bytes = fs.statSync(fp).size;
  console.error(`[generate-map-cache] wrote ${fp} (${(bytes / 1024).toFixed(1)} KiB)`);
}

main();
