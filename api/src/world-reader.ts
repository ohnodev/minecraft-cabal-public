import * as fs from "fs";
import * as path from "path";
import * as zlib from "zlib";
import * as nbt from "prismarine-nbt";
import { fileURLToPath } from "url";

/** `api/` directory (sibling of repo `server/`, same as `API_ROOT` in `index.ts`). */
const API_PACKAGE_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
/** `<repo>/server` when `MC_SERVER_DIR` is unset (portable; not a host-specific absolute path). */
const DEFAULT_MC_SERVER_DIR = path.resolve(API_PACKAGE_ROOT, "..", "server");
/** Trim env and treat empty/whitespace as unset so fallback is reliable. */
const NORMALIZED_MC_SERVER_DIR = (() => {
  const raw = process.env.MC_SERVER_DIR;
  if (!raw) return DEFAULT_MC_SERVER_DIR;
  const trimmed = raw.trim();
  return trimmed.length > 0 ? trimmed : DEFAULT_MC_SERVER_DIR;
})();

const DIMENSION_KEY = (process.env.MAP_DIMENSION ?? process.env.MC_DIMENSION ?? "overworld").trim().toLowerCase();
/**
 * Vanilla on-disk layout (Java Edition): main overworld is {@code world/region},
 * Nether is {@code world/DIM-1/region}, End is {@code world/DIM1/region}.
 * Some tools use {@code world/dimensions/minecraft/.../region}; if present, prefer
 * that only when the vanilla path is missing.
 */
const REGION_SUBPATH = (() => {
  const serverDir = NORMALIZED_MC_SERVER_DIR;
  const tryDim = (vanilla: string, alt: string): string => {
    const v = path.join(serverDir, vanilla);
    const a = path.join(serverDir, alt);
    if (fs.existsSync(v)) return vanilla;
    if (fs.existsSync(a)) return alt;
    return vanilla;
  };
  switch (DIMENSION_KEY) {
    case "overworld":
    case "minecraft:overworld":
      return tryDim("world/region", "world/dimensions/minecraft/overworld/region");
    case "nether":
    case "minecraft:the_nether":
      return tryDim("world/DIM-1/region", "world/dimensions/minecraft/the_nether/region");
    case "end":
    case "minecraft:the_end":
      return tryDim("world/DIM1/region", "world/dimensions/minecraft/the_end/region");
    default:
      throw new Error(`Unsupported map dimension: ${DIMENSION_KEY}`);
  }
})();

const REGION_DIR = path.resolve(
  NORMALIZED_MC_SERVER_DIR,
  REGION_SUBPATH
);

const LEVEL_DAT = path.resolve(
  NORMALIZED_MC_SERVER_DIR,
  "world/level.dat"
);

const CLAIMS_PATH = path.resolve(
  NORMALIZED_MC_SERVER_DIR,
  "claims.json"
);
const SERVER_PROPERTIES_PATH = path.resolve(
  NORMALIZED_MC_SERVER_DIR,
  "server.properties"
);

export interface SpawnData {
  x: number;
  y: number;
  z: number;
}

/** Mirrors {@code ClaimManager.TrustedPlayer} in claim-mod (uuid + last-known name). */
export interface TrustedPlayer {
  uuid: string;
  name: string;
}

export interface ClaimEntry {
  ownerUuid: string;
  ownerName: string;
  x: number;
  y: number;
  z: number;
  dimension?: string; // e.g. "minecraft:overworld"; absent in legacy entries
  trusted?: TrustedPlayer[];
}

export interface BiomeCell {
  x: number;
  z: number;
  biome: string;
}

export interface LoadedWorldBounds {
  minX: number;
  maxX: number;
  minZ: number;
  maxZ: number;
}

const DEFAULT_SPAWN: SpawnData = { x: 0, y: 64, z: 0 };

function finiteOr(n: unknown, fallback: number): number {
  const v = Number(n);
  return Number.isFinite(v) ? v : fallback;
}

export function readSpawn(): SpawnData {
  try {
    if (!fs.existsSync(LEVEL_DAT)) return { ...DEFAULT_SPAWN };
    const buf = fs.readFileSync(LEVEL_DAT);
    const decomp = zlib.gunzipSync(buf);
    const parsed = nbt.parseUncompressed(decomp) as any;
    const data = parsed.value?.Data?.value ?? parsed.value;
    if (!data) return { ...DEFAULT_SPAWN };
    const spawn = data.spawn?.value;
    if (spawn?.pos?.value && Array.isArray(spawn.pos.value) && spawn.pos.value.length >= 3) {
      const [xa, ya, za] = spawn.pos.value;
      return {
        x: finiteOr(xa, 0),
        y: finiteOr(ya, 64),
        z: finiteOr(za, 0),
      };
    }
    return {
      x: finiteOr(data.SpawnX?.value, 0),
      y: finiteOr(data.SpawnY?.value, 64),
      z: finiteOr(data.SpawnZ?.value, 0),
    };
  } catch {
    return { ...DEFAULT_SPAWN };
  }
}

export function readClaims(): Record<string, ClaimEntry> {
  if (!fs.existsSync(CLAIMS_PATH)) return {};
  try {
    const parsed = JSON.parse(fs.readFileSync(CLAIMS_PATH, "utf-8")) as unknown;
    if (!parsed || typeof parsed !== "object") return {};

    // Support both legacy claim files ({ "<uuid>": claim }) and v2 schema
    // ({ version, nextClaimId, claims: { "<id>": claim }, ... }).
    const root = parsed as Record<string, unknown>;
    const claimsNode =
      root.claims && typeof root.claims === "object"
        ? (root.claims as Record<string, unknown>)
        : root;

    const out: Record<string, ClaimEntry> = {};
    for (const [id, rawEntry] of Object.entries(claimsNode)) {
      if (!rawEntry || typeof rawEntry !== "object") continue;
      const entry = rawEntry as Record<string, unknown>;
      const ownerName = typeof entry.ownerName === "string" ? entry.ownerName.trim() : "";
      if (!ownerName) continue;

      const trusted = Array.isArray(entry.trusted)
        ? entry.trusted
            .filter((t): t is Record<string, unknown> => !!t && typeof t === "object")
            .map((t) => {
              const trimmedUuid = typeof t.uuid === "string" ? t.uuid.trim() : "";
              const trimmedName = typeof t.name === "string" ? t.name.trim() : "";
              return { uuid: trimmedUuid, name: trimmedName };
            })
            .filter((t) => t.uuid.length > 0 && t.name.length > 0)
        : undefined;

      out[id] = {
        ownerUuid: typeof entry.ownerUuid === "string" ? entry.ownerUuid : "",
        ownerName,
        x: finiteOr(entry.x, 0),
        y: finiteOr(entry.y, 64),
        z: finiteOr(entry.z, 0),
        dimension: typeof entry.dimension === "string" ? entry.dimension : undefined,
        trusted,
      };
    }
    return out;
  } catch {
    return {};
  }
}

export function readSpawnProtectionRadius(): number {
  if (!fs.existsSync(SERVER_PROPERTIES_PATH)) return 16;
  try {
    const text = fs.readFileSync(SERVER_PROPERTIES_PATH, "utf-8");
    const match = text.match(/^spawn-protection=(-?\d+)/m);
    if (!match) return 16;
    const value = Number(match[1]);
    return Number.isFinite(value) ? Math.max(0, value) : 16;
  } catch {
    return 16;
  }
}

// ── Region / chunk parsing ──────────────────────────────────────────

/** Parsed chunk NBT or `null` for empty/missing/corrupt (cached to avoid repeat work). */
const chunkCache = new Map<string, any | null>();
/** Region file bytes, or `null` if the .mca file is missing (avoids repeated stat/read). */
const regionCache = new Map<string, Buffer | null>();

/** Cap resident region buffers during one sample pass to limit peak RSS (FIFO eviction). */
const MAX_CACHED_REGIONS = 14;

function evictRegion(regionKey: string): void {
  regionCache.delete(regionKey);
  const [rxS, rzS] = regionKey.split(",");
  const rx = Number(rxS);
  const rz = Number(rzS);
  if (!Number.isFinite(rx) || !Number.isFinite(rz)) return;
  for (const ck of chunkCache.keys()) {
    const parts = ck.split(",");
    const cx = Number(parts[0]);
    const cz = Number(parts[1]);
    if (!Number.isFinite(cx) || !Number.isFinite(cz)) continue;
    if ((cx >> 5) === rx && (cz >> 5) === rz) chunkCache.delete(ck);
  }
}

function trimRegionCacheIfNeeded(): void {
  while (regionCache.size > MAX_CACHED_REGIONS) {
    const oldest = regionCache.keys().next().value;
    if (oldest === undefined) break;
    evictRegion(oldest);
  }
}

function readChunkNBT(cx: number, cz: number): any | null {
  const rx = cx >> 5;
  const rz = cz >> 5;
  const key = `${cx},${cz}`;
  if (chunkCache.has(key)) {
    return chunkCache.get(key)!;
  }

  const regionKey = `${rx},${rz}`;
  let buf: Buffer | null;
  if (regionCache.has(regionKey)) {
    buf = regionCache.get(regionKey)!;
  } else {
    const regionFile = path.join(REGION_DIR, `r.${rx}.${rz}.mca`);
    if (!fs.existsSync(regionFile)) {
      regionCache.set(regionKey, null);
      chunkCache.set(key, null);
      return null;
    }
    try {
      buf = fs.readFileSync(regionFile);
      regionCache.set(regionKey, buf);
      trimRegionCacheIfNeeded();
    } catch {
      regionCache.set(regionKey, null);
      chunkCache.set(key, null);
      return null;
    }
  }

  if (buf === null) {
    chunkCache.set(key, null);
    return null;
  }

  try {
    const idx = (cx & 31) + (cz & 31) * 32;
    const off = buf.readUInt32BE(idx * 4);
    const sectorOffset = (off >> 8) & 0xffffff;
    if (sectorOffset === 0) {
      chunkCache.set(key, null);
      return null;
    }

    const chunkStart = sectorOffset * 4096;
    const length = buf.readUInt32BE(chunkStart);
    const comp = buf[chunkStart + 4];
    const compressed = buf.subarray(chunkStart + 5, chunkStart + 4 + length);

    let decomp: Buffer;
    if (comp === 2) decomp = zlib.inflateSync(compressed);
    else if (comp === 1) decomp = zlib.gunzipSync(compressed);
    else {
      chunkCache.set(key, null);
      return null;
    }

    const parsed = nbt.parseUncompressed(decomp);
    chunkCache.set(key, parsed);
    return parsed;
  } catch {
    chunkCache.set(key, null);
    return null;
  }
}

function getSurfaceBiome(chunk: any, worldX: number, worldZ: number): string {
  const sections = chunk.value?.sections?.value?.value;
  if (!sections) return "unknown";

  let section = sections.find((s: any) => s.Y?.value === 4);
  if (!section) section = sections.find((s: any) => s.Y?.value === 3);
  if (!section) section = sections[sections.length - 1];

  const biomes = section?.biomes;
  if (!biomes) return "unknown";

  const palette: string[] = biomes.value.palette?.value?.value ?? [];
  if (palette.length === 0) return "unknown";
  if (palette.length === 1) return palette[0].replace("minecraft:", "");

  const data: bigint[] | number[] = biomes.value.data?.value;
  if (!data || data.length === 0) return palette[0].replace("minecraft:", "");

  const bx = Math.floor((((worldX % 16) + 16) % 16) / 4);
  const bz = Math.floor((((worldZ % 16) + 16) % 16) / 4);
  const by = 2;
  const biomeIdx = bx + bz * 4 + by * 16;

  const bitsPerEntry = Math.max(1, Math.ceil(Math.log2(palette.length)));
  const entriesPerLong = Math.floor(64 / bitsPerEntry);
  const longIdx = Math.floor(biomeIdx / entriesPerLong);
  const bitOffset = (biomeIdx % entriesPerLong) * bitsPerEntry;
  const mask = (1n << BigInt(bitsPerEntry)) - 1n;

  if (longIdx >= data.length) return palette[0].replace("minecraft:", "");
  const longVal = BigInt(data[longIdx].toString());
  const paletteIdx = Number((longVal >> BigInt(bitOffset)) & mask);

  return (palette[paletteIdx] ?? palette[0]).replace("minecraft:", "");
}

export function sampleBiomes(
  circles: { x: number; z: number; radius: number }[],
  step: number = 8,
  outerPadding: number = 320,
  onlyInsideCircles: boolean = false,
  keepViewportAspect: boolean = true
): { bounds: { minX: number; maxX: number; minZ: number; maxZ: number }; step: number; cells: BiomeCell[] } {
  if (!Number.isFinite(step) || step <= 0) {
    throw new RangeError(`sampleBiomes: step must be a finite number > 0, got ${String(step)}`);
  }

  chunkCache.clear();
  regionCache.clear();

  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (const c of circles) {
    minX = Math.min(minX, c.x - c.radius);
    maxX = Math.max(maxX, c.x + c.radius);
    minZ = Math.min(minZ, c.z - c.radius);
    maxZ = Math.max(maxZ, c.z + c.radius);
  }
  const pad = outerPadding;
  minX -= pad; maxX += pad; minZ -= pad; maxZ += pad;

  if (keepViewportAspect) {
    // Match the dashboard’s 16:9 map frame: expand the sampled rectangle so every
    // pixel in that viewport has biome data.
    let w = maxX - minX;
    let h = maxZ - minZ;
    const minSpan = step * 6;
    if (w < minSpan) {
      const e = (minSpan - w) / 2;
      minX -= e;
      maxX += e;
      w = maxX - minX;
    }
    if (h < minSpan) {
      const e = (minSpan - h) / 2;
      minZ -= e;
      maxZ += e;
      h = maxZ - minZ;
    }
    const VIEW_ASPECT = 16 / 9;
    const currentAspect = w / h;
    if (currentAspect > VIEW_ASPECT) {
      const targetH = w / VIEW_ASPECT;
      const extra = (targetH - h) / 2;
      minZ -= extra;
      maxZ += extra;
    } else {
      const targetW = h * VIEW_ASPECT;
      const extra = (targetW - w) / 2;
      minX -= extra;
      maxX += extra;
    }
  }

  const cells: BiomeCell[] = [];
  for (let wx = minX; wx <= maxX; wx += step) {
    for (let wz = minZ; wz <= maxZ; wz += step) {
      let inside = false;
      if (onlyInsideCircles) {
        for (const c of circles) {
          const dx = wx - c.x;
          const dz = wz - c.z;
          if (dx * dx + dz * dz <= c.radius * c.radius) {
            inside = true;
            break;
          }
        }
      }
      if (onlyInsideCircles && !inside) continue;

      const cx = Math.floor(wx / 16);
      const cz = Math.floor(wz / 16);
      const chunk = readChunkNBT(cx, cz);
      const biome = chunk ? getSurfaceBiome(chunk, wx, wz) : "unknown";
      cells.push({ x: wx, z: wz, biome });
    }
  }

  return { bounds: { minX, maxX, minZ, maxZ }, step, cells };
}

export function readLoadedWorldBounds(): LoadedWorldBounds | null {
  if (!fs.existsSync(REGION_DIR)) return null;
  let minChunkX = Infinity;
  let maxChunkX = -Infinity;
  let minChunkZ = Infinity;
  let maxChunkZ = -Infinity;
  let foundAny = false;

  let regionFiles: string[] = [];
  try {
    regionFiles = fs.readdirSync(REGION_DIR);
  } catch {
    return null;
  }

  const regionRe = /^r\.(-?\d+)\.(-?\d+)\.mca$/;
  for (const file of regionFiles) {
    const m = file.match(regionRe);
    if (!m) continue;
    const rx = Number(m[1]);
    const rz = Number(m[2]);
    if (!Number.isFinite(rx) || !Number.isFinite(rz)) continue;
    const fp = path.join(REGION_DIR, file);
    let header: Buffer;
    try {
      const region = fs.readFileSync(fp);
      if (region.length < 4096) continue;
      header = region.subarray(0, 4096);
    } catch {
      continue;
    }
    for (let idx = 0; idx < 1024; idx++) {
      const off = header.readUInt32BE(idx * 4);
      const sectorOffset = (off >> 8) & 0xffffff;
      if (sectorOffset === 0) continue;
      const localX = idx % 32;
      const localZ = Math.floor(idx / 32);
      const chunkX = rx * 32 + localX;
      const chunkZ = rz * 32 + localZ;
      foundAny = true;
      minChunkX = Math.min(minChunkX, chunkX);
      maxChunkX = Math.max(maxChunkX, chunkX);
      minChunkZ = Math.min(minChunkZ, chunkZ);
      maxChunkZ = Math.max(maxChunkZ, chunkZ);
    }
  }

  if (!foundAny) return null;
  return {
    minX: minChunkX * 16,
    maxX: (maxChunkX + 1) * 16,
    minZ: minChunkZ * 16,
    maxZ: (maxChunkZ + 1) * 16,
  };
}
