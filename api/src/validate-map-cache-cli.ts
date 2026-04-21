/**
 * CLI: node dist/validate-map-cache-cli.js [apiDir]
 * Exit 0 if map-biomes.json is valid for current world; else 1.
 */
import * as path from "path";
import { fileURLToPath } from "url";
import { validateMapCache } from "./map-cache-validate.js";
import type { MapDimension } from "./map-cache-types.js";

const __dir = path.dirname(fileURLToPath(import.meta.url));
const API_ROOT = path.resolve(__dir, "..");
const REPO_ROOT = path.resolve(API_ROOT, "..");

if (!process.env.MC_SERVER_DIR) {
  process.env.MC_SERVER_DIR = path.join(REPO_ROOT, "server");
}

const apiDir = process.argv[2] ? path.resolve(process.argv[2]) : API_ROOT;
const dimension = ((process.env.MAP_DIMENSION ?? process.env.MC_DIMENSION ?? "overworld").trim().toLowerCase()) as MapDimension;
const r = validateMapCache(apiDir, dimension);
if (!r.ok) {
  console.error(`[validate-map-cache] ${r.reason}`);
  process.exit(1);
}
process.exit(0);
