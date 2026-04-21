const envUrl = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.trim();

/** API base URL override; defaults to same-origin relative paths when unset. */
const BASE = envUrl || "";

export interface ServerStatus {
  name: string;
  version: string;
  onlinePlayers: number;
  maxPlayers: number;
  sampleNames: string[];
  error?: string;
}

export interface ContentData {
  /** Optional stable id from the API; when present, used to detect real content changes vs refetch identity. */
  id?: string;
  serverName: string;
  description: string;
  connectAddress: string;
  updates: { date: string; title: string; body: string }[];
}

export interface MapClaim {
  ownerName: string;
  x: number;
  z: number;
  radius: number;
}

export type MapDimension = "overworld" | "nether" | "end";

export interface LivePlayerPosition {
  uuid: string;
  name: string;
  x: number;
  y: number;
  z: number;
  dimension: string;
  // Optional/deprecated for frontend rendering; retained for backend compatibility.
  sampledAtMs: number;
}

export interface LiveEvokerBoss {
  present: boolean;
  bossUuid: string | null;
  x: number | null;
  y: number | null;
  z: number | null;
  dimension: string | null;
  updatedAtMs: number | null;
  markerRadius: number;
}

/** Compact biome map from API (palette + index grid — small JSON). */
export interface MapData {
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
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { signal });
  if (!res.ok) throw new Error(`API ${res.status}`);
  return res.json();
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    let t: ReturnType<typeof setTimeout> | undefined;
    const onAbort = () => {
      if (t !== undefined) clearTimeout(t);
      signal?.removeEventListener("abort", onAbort);
      reject(new DOMException("Aborted", "AbortError"));
    };
    t = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

export const fetchServer = (signal?: AbortSignal) => get<ServerStatus>("/api/server", signal);
export const fetchContent = (signal?: AbortSignal) => get<ContentData>("/api/content", signal);
export const fetchPlayerPositions = (signal?: AbortSignal) =>
  get<{ generatedAtMs: number; players: LivePlayerPosition[] }>("/api/players/positions", signal);
export const fetchEvokerBoss = (signal?: AbortSignal) =>
  get<LiveEvokerBoss>("/api/boss/evoker", signal);

export interface AuctionListing {
  id: number;
  sellerName: string;
  itemId: string;
  spriteItemId?: string;
  itemName: string;
  itemCount: number;
  price: number;
  createdAt: number;
  expiresAt: number;
}

export const fetchAuction = (signal?: AbortSignal) =>
  get<{ listings: AuctionListing[] }>("/api/auction", signal);

/** Resolve a sprite URL for an item id like "minecraft:diamond" */
export function itemSpriteUrl(itemId: string): string {
  const stripped = itemId.replace(/^minecraft:/, "");
  return `${BASE}/static/item-sprites/${encodeURIComponent(stripped)}.png`;
}

function parseRetryAfterSeconds(res: Response, fallbackSec: number): number {
  const raw = res.headers.get("Retry-After")?.trim();
  if (!raw) return fallbackSec;
  const n = parseInt(raw, 10);
  if (Number.isFinite(n) && n >= 0) return Math.min(Math.max(n, 1), 120);
  return fallbackSec;
}

/** Map may 503 until cache exists — poll until ready or caller aborts; honor Retry-After. */
export async function fetchMap(dimension: MapDimension, signal?: AbortSignal): Promise<MapData> {
  const fallbackDelayMs = 2000;
  for (;;) {
    if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
    const res = await fetch(`${BASE}/api/map?dimension=${encodeURIComponent(dimension)}`, { signal });
    if (res.status === 503) {
      if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
      const waitMs = parseRetryAfterSeconds(res, Math.ceil(fallbackDelayMs / 1000)) * 1000;
      await sleep(waitMs, signal);
      continue;
    }
    if (!res.ok) throw new Error(`API ${res.status}`);
    return res.json() as Promise<MapData>;
  }
}
