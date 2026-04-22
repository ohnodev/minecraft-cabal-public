import Fastify from "fastify";
import cors from "@fastify/cors";
import * as fs from "fs";
import * as fsp from "fs/promises";
import * as path from "path";
import { fileURLToPath } from "url";
import { status } from "minecraft-server-util";
import fastifyStatic from "@fastify/static";
import { getServedMap, startMapBackground, stopMapBackground } from "./map-service.js";
import { fetchActiveListings, closeAuctionDb } from "./auction-db.js";
import { fetchLiveEvokerBoss, closeEvokerBossDb } from "./evoker-boss-db.js";
import type { MapDimension } from "./map-cache-types.js";

const __apiDir = path.dirname(fileURLToPath(import.meta.url));
const API_ROOT = path.resolve(__apiDir, "..");
const MC_HOST = process.env.MC_HOST ?? "127.0.0.1";
const MC_PORT = Number(process.env.MC_PORT ?? "25565");
const API_PORT = Number(process.env.API_PORT ?? "4866");
const API_HOST = process.env.API_HOST ?? "127.0.0.1";
const CONTENT_PATH = path.resolve(new URL(".", import.meta.url).pathname, "../content.json");
const STATUS_RETRY_ATTEMPTS = 3;
const STATUS_RETRY_DELAY_MS = 300;
const PLAYER_POSITIONS_PATH = path.resolve(process.env.MC_SERVER_DIR ?? path.resolve(API_ROOT, "..", "server"), "player-positions.json");

const app = Fastify({ logger: true });
let lastServerStatus: {
  name: string;
  version: string;
  onlinePlayers: number;
  maxPlayers: number;
  sampleNames: string[];
  error?: string;
  stale?: boolean;
} | null = null;

const DEFAULT_CORS = [
  "http://localhost:5173",
  "http://localhost:4173",
  "https://smp.thecabal.app",
  "http://smp.thecabal.app",
];
const corsOrigins = (() => {
  const raw = process.env.CORS_ORIGINS?.trim();
  if (!raw) return DEFAULT_CORS;
  const list = raw.split(",").map((o) => o.trim()).filter(Boolean);
  return list.length > 0 ? list : DEFAULT_CORS;
})();
const localhostOriginRe = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?$/i;
await app.register(cors, {
  origin: (origin, cb) => {
    // Allow non-browser requests (no Origin header), configured origins,
    // and localhost loopback origins on any dev port.
    if (!origin || corsOrigins.includes(origin) || localhostOriginRe.test(origin)) {
      cb(null, true);
      return;
    }
    cb(null, false);
  },
});

await app.register(fastifyStatic, {
  root: path.resolve(API_ROOT, "public"),
  prefix: "/static/",
  decorateReply: false,
});

// ── /health ─────────────────────────────────────────────────────────

app.get("/health", async () => ({ ok: true }));

// ── /api/server ─────────────────────────────────────────────────────

app.get("/api/server", async (req, reply) => {
  let lastError: unknown = null;
  for (let attempt = 1; attempt <= STATUS_RETRY_ATTEMPTS; attempt++) {
    try {
      const res = await status(MC_HOST, MC_PORT, { timeout: 5000 });
      const payload = {
        name: res.motd?.clean ?? "Cabal SMP",
        version: res.version?.name ?? "26.1",
        onlinePlayers: res.players?.online ?? 0,
        maxPlayers: res.players?.max ?? 20,
        sampleNames: res.players?.sample?.map((p) => p.name) ?? [],
      };
      lastServerStatus = payload;
      return payload;
    } catch (err) {
      lastError = err;
      if (attempt < STATUS_RETRY_ATTEMPTS) {
        await sleep(STATUS_RETRY_DELAY_MS);
      }
    }
  }

  req.log.error({ err: lastError }, "Minecraft server status probe failed");
  if (lastServerStatus) {
    reply.header("X-Status-Stale", "1");
    return { ...lastServerStatus, stale: true, error: "Temporary probe failure; showing last known status." };
  }

  reply.code(200);
  return {
    name: "Cabal SMP",
    version: "26.1",
    onlinePlayers: 0,
    maxPlayers: 20,
    sampleNames: [],
    error: "Server status temporarily unavailable",
    stale: true,
  };
});

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

// ── /api/content ────────────────────────────────────────────────────

app.get("/api/content", async () => {
  try {
    return JSON.parse(fs.readFileSync(CONTENT_PATH, "utf-8"));
  } catch {
    return {
      serverName: "Cabal SMP",
      description: "",
      connectAddress: "minecraft.thecabal.app",
      updates: [],
    };
  }
});

// ── /api/auction ────────────────────────────────────────────────────

app.get("/api/auction", async () => {
  const listings = fetchActiveListings();
  return { listings };
});

// ── /api/map ────────────────────────────────────────────────────────
// Biome grid is pre-built by scripts (generate-map-cache); see map-service.ts — no region I/O here.

app.get("/api/map", async (_req, reply) => {
  const q = (_req.query ?? {}) as { dimension?: string };
  const dimension = parseDimension(q.dimension);
  const data = getServedMap(dimension);
  if (!data) {
    reply.header("X-Map-Ready", "0");
    reply.header("Retry-After", "5");
    reply.code(503);
    return {
      error: "Map not ready yet",
      detail:
        "Biome grid is pre-generated offline. On the server: cd api && npm run build && npm run generate-map-cache:all",
    };
  }
  reply.header("X-Map-Ready", "1");
  reply.header("X-Cache", "MEM");
  return data;
});

// ── /api/players/positions ──────────────────────────────────────────

type PlayerPosition = {
  uuid: string;
  name: string;
  x: number;
  y: number;
  z: number;
  dimension: string;
  sampledAtMs: number;
};

app.get("/api/players/positions", async (_req, reply) => {
  try {
    const raw = await fsp.readFile(PLAYER_POSITIONS_PATH, "utf-8");
    const parsed = JSON.parse(raw) as { generatedAtMs?: number; players?: PlayerPosition[] };
    const players = Array.isArray(parsed.players) ? parsed.players : [];
    return {
      generatedAtMs: Number(parsed.generatedAtMs ?? Date.now()),
      players: players.map((p) => ({
        uuid: String(p.uuid ?? ""),
        name: String(p.name ?? "Unknown"),
        x: Number(p.x ?? 0),
        y: Number(p.y ?? 0),
        z: Number(p.z ?? 0),
        dimension: String(p.dimension ?? "minecraft:overworld"),
        sampledAtMs: Number(p.sampledAtMs ?? 0),
      })),
    };
  } catch {
    reply.code(200);
    return { generatedAtMs: Date.now(), players: [] };
  }
});

// ── /api/boss/evoker ────────────────────────────────────────────────

app.get("/api/boss/evoker", async () => fetchLiveEvokerBoss());

function parseDimension(raw: string | undefined): MapDimension {
  const v = (raw ?? "overworld").trim().toLowerCase();
  if (v === "nether" || v === "minecraft:the_nether") return "nether";
  if (v === "end" || v === "minecraft:the_end") return "end";
  return "overworld";
}

startMapBackground({ apiDir: API_ROOT, log: app.log });

let shutdownStarted = false;
function gracefulShutdown(signal: string): void {
  if (shutdownStarted) return;
  shutdownStarted = true;
  stopMapBackground();
  closeAuctionDb();
  closeEvokerBossDb();
  void app
    .close()
    .then(() => {
      app.log.info({ signal }, "server: closed");
      process.exit(0);
    })
    .catch((err: unknown) => {
      app.log.error({ err }, `server: close failed after ${signal}`);
      process.exit(1);
    });
}

for (const sig of ["SIGINT", "SIGTERM"] as const) {
  process.on(sig, () => gracefulShutdown(sig));
}

// ── Start ───────────────────────────────────────────────────────────

try {
  await app.listen({ port: API_PORT, host: API_HOST });
} catch (err) {
  app.log.error(err);
  process.exit(1);
}
