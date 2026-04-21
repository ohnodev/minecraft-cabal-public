export type BiomeCell = { x: number; z: number; biome: string };

/**
 * sampleBiomes iterates wx outer, wz inner. Flatten to palette indices for a small JSON payload.
 */
export function cellsToCompact(
  cells: BiomeCell[],
  biomeColor: (biome: string) => string
): {
  gridWidth: number;
  gridHeight: number;
  palette: string[];
  paletteColors: string[];
  grid: number[];
} {
  if (cells.length === 0) {
    return { gridWidth: 0, gridHeight: 0, palette: [], paletteColors: [], grid: [] };
  }

  const x0 = cells[0]!.x;
  let gridHeight = 0;
  for (const c of cells) {
    if (c.x === x0) gridHeight++;
    else break;
  }
  if (gridHeight === 0) gridHeight = 1;
  const gridWidth = cells.length / gridHeight;
  if (!Number.isInteger(gridWidth)) {
    throw new Error(`map-compact: cell count ${cells.length} not divisible by column height ${gridHeight}`);
  }

  const biomeToIdx = new Map<string, number>();
  const palette: string[] = [];
  const paletteColors: string[] = [];

  function idxFor(biome: string): number {
    const existing = biomeToIdx.get(biome);
    if (existing !== undefined) return existing;
    const i = palette.length;
    biomeToIdx.set(biome, i);
    palette.push(biome);
    paletteColors.push(biomeColor(biome));
    return i;
  }

  const grid = cells.map((c) => idxFor(c.biome));
  return { gridWidth, gridHeight, palette, paletteColors, grid };
}
