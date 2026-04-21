#!/usr/bin/env node
/**
 * Extracts all Minecraft item textures from the merged JAR into
 * api/public/item-sprites/ as individual PNG files keyed by item id
 * (e.g. diamond.png, raw_iron.png).
 *
 * Also copies select block textures that commonly appear as auction items
 * but lack a dedicated item texture.
 *
 * Usage:  npx tsx src/extract-item-sprites.ts
 */

import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import { execSync } from "child_process";

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const API_ROOT = path.resolve(SCRIPT_DIR, "..");
const OUT_DIR = path.resolve(API_ROOT, "public", "item-sprites");

const JAR_CANDIDATES = [
  path.resolve(API_ROOT, "..", "claim-mod", ".gradle", "loom-cache", "minecraftMaven", "net", "minecraft"),
  path.resolve(process.env.HOME ?? "/root", ".gradle", "caches", "fabric-loom"),
];

function findMergedJar(): string {
  for (const base of JAR_CANDIDATES) {
    const direct = path.join(base, "26.1.1", "minecraft-merged.jar");
    if (fs.existsSync(direct)) return direct;
  }
  for (const base of JAR_CANDIDATES) {
    if (!fs.existsSync(base)) continue;
    const result = execSync(`find "${base}" -name "minecraft-merged*.jar" -path "*26.1*" 2>/dev/null || true`, {
      encoding: "utf-8",
    }).trim();
    const lines = result.split("\n").filter(Boolean);
    if (lines.length > 0) return lines[0];
  }
  throw new Error("Could not find minecraft-merged JAR. Run gradle build in claim-mod first.");
}

const PY_EXTRACT = `
import zipfile, os, sys, json

jar_path = sys.argv[1]
out_dir = sys.argv[2]

zf = zipfile.ZipFile(jar_path)

item_prefix = "assets/minecraft/textures/item/"
block_prefix = "assets/minecraft/textures/block/"

extracted_items = set()
count = 0

for name in zf.namelist():
    if name.startswith(item_prefix) and name.endswith(".png"):
        basename = name[len(item_prefix):]
        out_path = os.path.join(out_dir, basename)
        with open(out_path, "wb") as f:
            f.write(zf.read(name))
        extracted_items.add(basename.replace(".png", ""))
        count += 1

block_fallbacks = [
    "cobblestone", "stone", "oak_log", "spruce_log", "birch_log",
    "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log",
    "cherry_log", "sand", "gravel", "dirt", "grass_block_side",
    "oak_planks", "spruce_planks", "birch_planks",
    "iron_ore", "gold_ore", "diamond_ore", "coal_ore",
    "copper_ore", "lapis_ore", "redstone_ore", "emerald_ore",
    "deepslate", "netherrack", "end_stone", "obsidian",
    "glass", "bookshelf", "crafting_table_front",
]

for block in block_fallbacks:
    if block in extracted_items:
        continue
    block_path = block_prefix + block + ".png"
    if block_path in zf.namelist():
        out_path = os.path.join(out_dir, block + ".png")
        with open(out_path, "wb") as f:
            f.write(zf.read(block_path))
        count += 1
        extracted_items.add(block)

# Extra aliases for items that do not have dedicated item textures.
# Keep these keyed by item id so the frontend can resolve exact API ids.
alias_sources = {
    # In modern assets this is only a block texture.
    "shulker_box": "assets/minecraft/textures/block/shulker_box.png",
}

for alias_name, source_path in alias_sources.items():
    if alias_name in extracted_items:
        continue
    if source_path in zf.namelist():
        out_path = os.path.join(out_dir, alias_name + ".png")
        with open(out_path, "wb") as f:
            f.write(zf.read(source_path))
        count += 1
        extracted_items.add(alias_name)

# Alias one id to an already extracted sprite file.
alias_from_existing = {
    "infested_cobblestone": "cobblestone",
}

for alias_name, source_name in alias_from_existing.items():
    if alias_name in extracted_items:
        continue
    source_path = os.path.join(out_dir, source_name + ".png")
    alias_path = os.path.join(out_dir, alias_name + ".png")
    if os.path.exists(source_path):
        with open(source_path, "rb") as src, open(alias_path, "wb") as dst:
            dst.write(src.read())
        count += 1
        extracted_items.add(alias_name)

# Many infested variants share the same base texture (e.g. infested_cobblestone -> cobblestone).
for model_name in zf.namelist():
    if not model_name.startswith("assets/minecraft/models/item/") or not model_name.endswith(".json"):
        continue
    item_id = os.path.basename(model_name).replace(".json", "")
    if not item_id.startswith("infested_"):
        continue
    if item_id in extracted_items:
        continue
    base_id = item_id[len("infested_"):]
    base_path = os.path.join(out_dir, base_id + ".png")
    alias_path = os.path.join(out_dir, item_id + ".png")
    if os.path.exists(base_path):
        with open(base_path, "rb") as src, open(alias_path, "wb") as dst:
            dst.write(src.read())
        count += 1
        extracted_items.add(item_id)

print(json.dumps({"extracted": count}))
`;

async function main() {
  const jar = findMergedJar();
  console.log(`Source JAR: ${jar}`);

  fs.mkdirSync(OUT_DIR, { recursive: true });

  const tmpPy = path.join(os.tmpdir(), "extract_sprites.py");
  fs.writeFileSync(tmpPy, PY_EXTRACT, "utf-8");

  try {
    const result = execSync(
      `python3 ${JSON.stringify(tmpPy)} ${JSON.stringify(jar)} ${JSON.stringify(OUT_DIR)}`,
      { encoding: "utf-8" }
    ).trim();
    const parsed = JSON.parse(result);
    console.log(`Extracted ${parsed.extracted} sprite PNGs to ${OUT_DIR}`);
  } finally {
    try { fs.unlinkSync(tmpPy); } catch { /* ignore */ }
  }
}

main().catch((err) => {
  console.error("extract-item-sprites failed:", err);
  process.exit(1);
});
