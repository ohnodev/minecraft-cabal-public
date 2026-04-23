# Cabal SMP — Minecraft Java server

**Game version:** 1.21.11 (Fabric server runtime, vanilla-client compatible)  
**Fabric Loader:** 0.19.2  
**Java:** OpenJDK **25** (`/usr/lib/jvm/java-25-openjdk-amd64/bin/java`, pinned in `scripts/start.sh`; 1.21.11 requires **Java 21+** — the Java 25 runtime is backwards-compatible)  
**Connect (Java):** `minecraft.thecabal.app` or `157.230.189.203` — port **25565** (default).  
**Connect (Bedrock):** same hostname, UDP port **19132** — served in-process by Geyser-Fabric; Floodgate-Fabric lets Bedrock players skip a Java account.

**How traffic flows:** By default, Compose publishes the Java listener on **`127.0.0.1:25566/tcp`** only and **RCON on `127.0.0.1:25575/tcp`** only; use **nginx `stream`** on the host to expose **25565/tcp** to players (see `deploy/nginx/03-mc-stream-rate-limit.conf`). Bedrock is on **`0.0.0.0:19132/udp`** unless you change it. Geyser-Fabric and Floodgate run inside the same JVM/mod stack as Java. Open **25565/tcp** (or your nginx front port) and **19132/udp** at the firewall / cloud security group, or clients will time out.

Players connect with a **normal vanilla 1.21.11 client** (Java) — no mods needed on the client side. Modern **1.21.x clients** (1.21.x ≠ 1.21.11) are accepted through the Via translation stack (ViaFabric + ViaVersion + ViaBackwards). **Bedrock** clients connect through the Geyser + Floodgate mod pair running inside the Fabric server.

## Quick commands

```bash
cd /root/minecraft-cabal
docker compose up -d minecraft
docker compose restart minecraft
docker compose stop minecraft
docker compose ps minecraft
docker compose logs -f minecraft
docker compose logs --tail=200 minecraft
```

## Docker runtime (required)

```bash
cd /root/minecraft-cabal
git pull origin main
docker compose up -d --build minecraft
docker compose logs -f minecraft
```

Compose bind-mounts **`./server`** and uses the same **`scripts/start.sh`** entrypoint. The server listens on **all interfaces** on **`MC_SERVER_PORT`** (default **25565**). Heap defaults to **4G** in Compose; override with **`MC_MIN_MEM`** / **`MC_MAX_MEM`**. Full options: **[`docker/README.md`](docker/README.md)**.

`scripts/start.sh` enforces runtime properties (`enable-rcon=true`, `rcon.port`, `server-port`) and injects `rcon.password` from **`RCON_PASSWORD`** if set, otherwise from **`server/.rcon-password`**. It renders runtime `server/server.properties` from tracked `server/server.properties.template`, so secrets and live overrides do not modify tracked files.

> Runtime policy: **Docker Compose only** for Minecraft on this repo. Do not run `minecraft-cabal.service`.

## Paths

| Item | Path |
|------|------|
| Server root | `/root/minecraft-cabal/server/` |
| World | `/root/minecraft-cabal/server/world/` (includes Nether/End) |
| Tracked properties template | `/root/minecraft-cabal/server/server.properties.template` |
| Runtime server.properties (generated at start) | `/root/minecraft-cabal/server/server.properties` |
| Server jar (Fabric) | `/root/minecraft-cabal/server/fabric-server-launch.jar` |
| Vanilla jar (bundled) | `/root/minecraft-cabal/server/server.jar` |
| Server mods | `/root/minecraft-cabal/server/mods/` |
| Claim mod source | `/root/minecraft-cabal/claim-mod/` |
| Claims data | `/root/minecraft-cabal/server/claims.json` |
| Start script | `/root/minecraft-cabal/scripts/start.sh` |
| Backups | `/root/minecraft-cabal/backups/` |
| Compose runtime | `/root/minecraft-cabal/docker-compose.yml` |

## Server tuning (distances, heap)

JVM heap is **8 GB** (`scripts/start.sh`). `view-distance` and `simulation-distance` are both **8** (`server/server.properties.template`, rendered to runtime on start).

### Scheduled restart with in-game warnings (RCON)

Use [`scripts/mc-maintenance-restart.sh`](scripts/mc-maintenance-restart.sh) for scheduled Docker maintenance restarts with in-game countdown + graceful stop/start. Requires **[mcrcon](https://github.com/Tiiffi/mcrcon)** on the host. Direct `docker compose restart minecraft` remains a valid manual alternative when you do not need countdown broadcasts.

**RCON in `server/server.properties.template`:** keep **`enable-rcon=true`** and **`rcon.port=25575`**. The runtime `rcon.password` is written by **`scripts/start.sh`** at boot: it prefers **`RCON_PASSWORD`**, else reads **`server/.rcon-password`** (one line, gitignored). If you use the file, create it on the host **before** starting or restarting Minecraft, owned by the user that runs the service (e.g. the same UID as the container’s `user:` if you set one in Compose), and set restrictive permissions with **`chmod 0600 server/.rcon-password`** so only that user can read the secret. Otherwise **`start.sh`** has no valid password to inject. Compose publishes RCON on **`127.0.0.1:25575`** by default (host-only — use **`mcrcon -H 127.0.0.1`**). **Restart Minecraft** after changing `enable-rcon`, `rcon.port`, the password, or the password file so the server reloads the listener and `rcon.password`.

**For the script:** put the **same** secret in **`server/.rcon-password`**, e.g. copy the value from `rcon.password=`, or export **`RCON_PASSWORD`**. See **`server/.rcon-password.example`**.

```bash
# Restart only (countdown + graceful cycle)
sudo -E ./scripts/mc-maintenance-restart.sh
```

### Instant clean restart (no countdown)

Use this for an immediate operator restart that still saves world state first:

```bash
cd /root/minecraft-cabal
sudo -E ./scripts/mc-restart-now.sh
```

This script performs: `save-all flush` -> `stop` -> `docker compose up -d minecraft`.
Keep `scripts/start.sh` — Docker uses it as the container entrypoint and runtime property renderer.

### Manual graceful restart (copy/paste)

Use this when you want an explicit, operator-visible restart flow without relying on the helper script:

```bash
cd /root/minecraft-cabal
RCON_PASS="$(tr -d '\r\n' < server/.rcon-password)"
if [ -z "$RCON_PASS" ]; then
  echo "error: missing or empty server/.rcon-password" >&2
  exit 1
fi

# Optional player notice
mcrcon -H 127.0.0.1 -P 25575 -p "$RCON_PASS" "say [Cabal SMP] Restart in 30 seconds for maintenance."
sleep 30

# Graceful save + stop
mcrcon -H 127.0.0.1 -P 25575 -p "$RCON_PASS" "save-all flush"
mcrcon -H 127.0.0.1 -P 25575 -p "$RCON_PASS" "stop"

# Wait until the minecraft compose service is fully stopped (max ~90s)
for i in $(seq 1 90); do
  if ! docker compose ps --status running --services minecraft | grep -q '^minecraft$'; then
    break
  fi
  sleep 1
done
if docker compose ps --status running --services minecraft | grep -q '^minecraft$'; then
  echo "error: minecraft service did not stop within 90s" >&2
  docker compose ps minecraft
  exit 1
fi

# Bring service back up after confirmed stop
docker compose up -d minecraft
docker compose ps minecraft
docker compose logs --tail=120 minecraft
```

## World border

Vanilla Minecraft has a built-in **world border** (no mods). It is controlled with **`/worldborder`** and applies **immediately** — **no server restart** is required. Run commands from the **server console** or as an **op** in-game (pick a quiet moment so players are not standing past the new edge).

**Cabal SMP policy (overworld):** **20,000,000 block diameter** (vanilla allows up to **59,999,968**). That is intentionally huge for exploration; pair it with **[Chunky](https://modrinth.com/mod/chunky)** (Fabric jar in `server/mods/`) to **pre-generate** chunks so joins do not pay full generation cost on the main thread. Copy-paste border commands: `scripts/world-border-commands.txt`.

**The End:** unchanged — **default vanilla** (stronghold portals, dragon, gateways). This repo does not lock or schedule the End.

**Dimensions:** Overworld, Nether, and End each have their **own** border. In the **Nether**, one block equals **⅛** of an overworld block horizontally, so the border should be **scaled**: **Nether diameter = Overworld diameter ÷ 8**, and **Nether center (X, Z) = (Overworld spawn X ÷ 8, Overworld spawn Z ÷ 8)** so portals and coordinates line up. **Optionally,** the End can be set to **mirror** the Overworld border (same block scale, center on **0, 0**); that is **not** the default here — follow **Cabal policy and vanilla** for the End (unchanged, see above) unless you **deliberately** choose to align it. Example values for a **20,000,000** overworld diameter are in `scripts/world-border-commands.txt` (Nether scaling and optional End mirroring; run as op when players are not standing past the new edge).

## Land Claims, Trust, and Home Teleport

The server runs a custom Fabric mod (`cabal-claim`) with the following commands. On **join**, players see a short **welcome** in chat (explore, `/claim`, **smp.thecabal.app**).

| Command | Description |
|---------|-------------|
| `/claim` | Claim a 100-block radius around your current position |
| `/home` | Teleport back to your claim center |
| `/landtrust <player>` | Grant a player permission to build and interact on your land |
| `/landuntrust <player>` | Revoke a player's trust on your land |
| `/landlist` | List all players trusted on your land |

### How claims work

- Stand where you want your base and type `/claim`.
- You get a **100-block radius** around that point where only you (and trusted players) can build and interact.
- One claim per player. No `/unclaim` — once set, it's permanent (for now).
- Type `/home` to teleport back to your claim center from anywhere.

### Land trust

Claim owners can trust other players to collaborate on their land. Trusted players can:
- **Break and place blocks**
- **Use interactive blocks** (doors, buttons, levers, beds, crafting tables, etc.)
- **Open containers** (chests, barrels, hoppers, furnaces, etc.)
- **Interact with entities** (item frames, armor stands, animals, minecarts, boats)

Trust commands require the caller to have a claim, and the target player must be **online**.

### /home rules

| Rule | Detail |
|------|--------|
| Requires a claim | Cannot use `/home` without first using `/claim` |
| 20-second damage cooldown | If you took damage in the last 20 seconds, `/home` is blocked (prevents combat escape) |
| 60-second use cooldown | After a successful `/home`, you must wait 60 seconds before using it again |
| Safe landing | The mod scans vertically for two air blocks above solid ground near the claim center |
| Cross-dimension | Works from any dimension; teleports to the dimension where you `/claim`ed |

### Claim restrictions

| Rule | Detail |
|------|--------|
| Minimum spawn distance | 100 blocks from world spawn |
| Overlap protection | New claims cannot overlap any existing claim (200-block minimum center-to-center) |
| One claim per player | Second `/claim` is rejected with a message showing your existing claim location |

### Claims data file

Claims are persisted to `server/claims.json` as a JSON map keyed by player UUID. Back this up alongside world data. Example entry:

```json
{
  "550e8400-e29b-41d4-a716-446655440000": {
    "ownerUuid": "550e8400-e29b-41d4-a716-446655440000",
    "ownerName": "PlayerName",
    "x": 500,
    "y": 64,
    "z": -200,
    "dimension": "minecraft:overworld",
    "trusted": [
      { "uuid": "a1b2c3d4-...", "name": "FriendName" }
    ]
  }
}
```

The `dimension` and `trusted` fields are optional for backward compatibility; legacy entries default to `minecraft:overworld` and an empty trust list.

## Cabal config (`server/cabal-config.json`)

A single, user-friendly JSON file at `server/cabal-config.json` lets server owners tweak the most-requested knobs without touching Java code. The file is created with sensible defaults on first server start by the `cabal-mobs` mod; edit it and restart the server to pick up changes.

```json
{
  "babyCreeper": {
    "spawnChance": 0.30
  },
  "evoker": {
    "enabled": true
  },
  "bundle_safeguard": {
    "disable_crafting": true,
    "block_right_click_open": true
  },
  "server": {
    "name": "Cabal SMP",
    "colorCodes": "&b&l"
  },
  "hud": {
    "titleOverride": "&b&lC&3&lA&9&lB&b&lA&3&lL &9&lS&3&lM&b&lP",
    "icons": {
      "money": "$",
      "kills": "\u2726",
      "deaths": "\u2620",
      "playtime": "\u231B",
      "ping": "\u26A1"
    },
    "colors": {
      "money": "a",
      "kills": "c",
      "deaths": "4",
      "playtime": "b",
      "ping": "e"
    }
  }
}
```

| Field | Effect |
|-------|--------|
| `babyCreeper.spawnChance` | Probability a creeper becomes a baby when spawned through `NATURAL`, `SPAWN_ITEM_USE`, or `SPAWN_EGG` (range `0.0`–`1.0`). Set to `0.0` to disable baby creepers entirely. |
| `evoker.enabled` | Master switch for the evoker boss system. `false` disables boss scheduling/loot, the elemental fire/lightning arrows (and their `/givearrow`/`/giveevokereye` commands), and Evoker's Wing crafting/speed/kinetic-immunity hooks. |
| `server.name` | Plain server name used in chat and as the default HUD title. |
| `server.colorCodes` | Formatting codes prefixed to `server.name`. Supports `&`-style shortcodes (e.g. `&b&l` for bold aqua) or raw `§` codes. |
| `hud.titleOverride` | When non-empty, replaces the computed `colorCodes + name` title with a custom string (useful for multi-color titles). Set to `""` or `null` to fall back to the computed title. |
| `hud.icons.*` | Glyph shown before each HUD line label. Any printable character (e.g. `$`, `✦`, `♥`). |
| `hud.colors.*` | Single-character Minecraft color code applied to the icon/value on each HUD line (`a` green, `c` light red, `4` dark red, `b` aqua, `e` yellow, etc.). |
| `bundle_safeguard.disable_crafting` | **(cabal-claim)** When `true` (default), the server removes the `minecraft:bundle` result from the crafting output (2x2 and 3x3), so players cannot craft new bundles. |
| `bundle_safeguard.block_right_click_open` | **(cabal-claim)** When `true` (default), right-clicking a bundle in-hand to open it is blocked (throttled chat message). Set to `false` if you only want to allow opening existing bundles while keeping crafting disabled. |

**Notes**
- Missing or malformed keys fall back to the defaults shown above; the loader never silently deletes user keys it does not recognize, so adding future Cabal fields is non-destructive.
- `server/cabal-config.json` is tracked in the repo as the default template (same pattern as `server/economy-config.json`). If the file is ever deleted at runtime, the `cabal-mobs` mod rewrites defaults on next start.
- Disabling the evoker also stops spawning Evoker's Eyes, so any in-world eyes become cosmetic — a deliberate consequence of disabling the full evoker system.
- **Bundle safeguard (optional workaround):** Some clients behind Via can disconnect with `Packet Type: CUSTOM_PAYLOAD` when interacting with bundles. The **cabal-claim** mod can block bundle **crafting** and **right-click open** (see `bundle_safeguard` above). If issues persist for a player who still has bundles in their inventory, remove or store those items (e.g. creative op or playerdata edit) or set `block_right_click_open` to `false` and test. **Verification:** set `disable_crafting` / `block_right_click_open` as needed, restart, then craft string+leather (result should stay empty) and right-click a bundle (should be blocked when `true`); watch `logs/latest.log` for the prior Via disconnect pattern.

## Building the claim mod

The claim mod pulls Fabric API **0.141.3+1.21.11** straight from the Fabric Maven (no local `publishToMavenLocal` step is required — the dependency is fully public). Gradle uses the **official Mojang mappings** (`loom.officialMojangMappings()`) so source references match the normal `net.minecraft.*` names in decompiled 1.21.11.

```bash
cd /root/minecraft-cabal/claim-mod
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew build
cp build/libs/cabal-claim-1.3.4.jar ../server/mods/
cd /root/minecraft-cabal
docker compose restart minecraft
```

## Building the mobs mod

```bash
cd /root/minecraft-cabal/cabal-mobs
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew build
cp build/libs/cabal-mobs-1.0.1.jar ../server/mods/
cd /root/minecraft-cabal
docker compose restart minecraft
```

### How baby creepers work

The mod hooks into `Mob.finalizeSpawn()` via Mixin. When a creeper spawn reason is `NATURAL`, `SPAWN_EGG`, or `SPAWN_ITEM_USE`, there is a **30% chance** it becomes a baby creeper:

- **Scale:** 0.5 (half size) via `Attributes.SCALE` base value
- **Speed:** 2x (permanent `ADD_MULTIPLIED_BASE` modifier on `Attributes.MOVEMENT_SPEED`)
- **Persistence:** Vanilla attribute serialization handles save/load automatically; the speed modifier uses a stable ID (`cabal_mobs:baby_creeper_speed`) so duplicates are impossible.
- **Scope:** `NATURAL`, `SPAWN_EGG`, and `SPAWN_ITEM_USE` can produce baby creepers; `/summon` and spawners still produce normal creepers.

### Evoker boss

A giant evoker boss is managed by `EvokerBossScheduler` and tracked in `economy.db` (`cabal_evoker_boss_events`) for live API/map visibility. Spawns are minute-gated from `ServerTickEvents.END_SERVER_TICK`, with one active encounter at a time and startup/runtime reconciliation to avoid duplicate active rows.

- **Scale + stats:** 10x evoker scale (`Attributes.SCALE`) with boosted max health/follow range and forced persistence.
- **Spawn cadence:** Default at most once per real-world hour (`AUTOMATIC_SPAWN_INTERVAL_SECONDS=3600`); can be overridden for testing with `-Dcabal.evoker.spawnIntervalSeconds=<seconds>` or `CABAL_EVOKER_SPAWN_INTERVAL_SECONDS`.
- **Spawn position:** Fixed overworld column (`X=0`, `Z=0`) at near-world-top Y.
- **Despawn window:** 15 minutes (`DESPAWN_TICKS=18000`) unless defeated/purged first.
- **Lifecycle tracking:** Spawn/position/end-state writes are persisted and exposed through the API boss endpoint used by the web map marker.

### v2 roadmap: true custom entity type

A future iteration could register a dedicated `cabal:baby_creeper` `EntityType` with its own spawn placement, loot table, and advancement entries. This would require either a client-side Fabric mod or a protocol-level strategy (e.g., remapping to creeper on the wire) and is tracked as a separate milestone.

## Fabric runtime

The server uses **Fabric Loader 0.19.2** with **Fabric API 0.141.3+1.21.11**. This is a server-side modding framework — clients do not need any mods. The Fabric launcher (`fabric-server-launch.jar`) wraps the vanilla `server.jar` and loads mods from the `mods/` directory.

### Installed mods

| Mod | Version | Purpose |
|-----|---------|---------|
| Fabric API | 0.141.3+1.21.11 | Core library for Fabric mods |
| C2ME | 0.3.7+alpha.0.9 (1.21.11) | Concurrent chunk generation and I/O |
| Spark | 1.10.170-fabric | Server profiler (`/spark`) for MSPT / CPU investigation |
| Grim Anticheat | 2.3.74 (Fabric) | Upstream [Grim](https://github.com/GrimAnticheat/Grim) packet-level anticheat |
| ViaFabric | 0.4.21+159-1.14-1.21 | Fabric platform adapter for the Via translation stack |
| ViaVersion | 5.9.0 | Core protocol translation engine |
| ViaBackwards | 5.9.0 | Downgrade support so older clients can join the newer 1.21.11 server |
| Geyser-Fabric | 2.9.5-b1119 | Bedrock client listener on UDP **19132** |
| Floodgate-Fabric | 2.2.6-b60 | Allows Bedrock players to join without a Java account |
| cabal-claim | 1.3.4 | Land claims (`/claim`), `/home` teleport, land trust, auction/economy, optional bundle safeguard |
| cabal-mobs | 1.0.1 | Baby creepers and the minute-gated evoker boss |
| cabal-elytra | 1.0.0 | Evoker's Wing progression, crafting hooks, and admin commands |

**Protocol policy:**
- Native **1.21.11** Java clients join without any translation.
- Older 1.21.x Java clients are translated by the Via stack (ViaFabric bundles the server-side integration; ViaBackwards carries the 1.21.x → 1.21.11 mappings).
- Pre-1.21 clients are intentionally unsupported; ViaRewind may be installed but is not required for the 1.21.x profile.
- **Bedrock** clients connect on UDP **19132** via Geyser-Fabric; Floodgate-Fabric authenticates them against the Java backend so a linked Microsoft account is optional.
- When running Via + Grim + Geyser/Floodgate together, set `advanced.java.use-direct-connection: false` in `server/config/Geyser-Fabric/config.yml` to avoid Netty handler-order conflicts on Bedrock login.

**Anticheat:** The server runs upstream [**Grim Anticheat**](https://github.com/GrimAnticheat/Grim) (`grimac-fabric-*.jar`) directly from Modrinth/GitHub releases. The old in-repo `reaper-ac/` fork is no longer deployed. Logs are routed to `logs/anticheat.log` via `server/log4j2.xml` (logger name `ac.grim.grimac`).

**Bedrock (Geyser + Floodgate):**
- Geyser generates `server/config/Geyser-Fabric/config.yml` on first boot — edit `bedrock.address` / `bedrock.port` there if you need to bind somewhere other than `0.0.0.0:19132`.
- Floodgate generates `server/config/floodgate/config.yml` and a `key.pem` secret (treat as sensitive; the key is used to sign auth tokens and should be backed up out of band, not committed).
- To let Bedrock players keep the same profile across sessions without a Microsoft-linked Java account, keep Floodgate's `auth-type: floodgate` default; switch to `online` if you want to require Java-linked accounts only.

## Backups

```bash
/root/minecraft-cabal/scripts/backup.sh
```

Keeps the 7 newest `world-backup-*.tar.gz` files. Backs up `world/` and, if present, `world_nether/` and `world_the_end/`.

## Update to a newer Minecraft release

1. `cd /root/minecraft-cabal && docker compose stop minecraft`
2. From the [version manifest](https://launchermeta.mojang.com/mc/game/version_manifest_v2.json), open your version's JSON and copy the `downloads.server.url` for `server.jar`.
3. Replace `/root/minecraft-cabal/server/server.jar` with that file.
4. Re-run the Fabric installer for the new version: `java -jar fabric-installer.jar server -mcversion <version> -dir /root/minecraft-cabal/server`
5. Rebuild the Cabal mods (`claim`, `mobs`, `elytra`) if the Minecraft version changes (update each mod's `build.gradle` dependency version).
6. Check the version JSON `javaVersion.majorVersion` — if it changes, install that JDK and update `JAVA=` in `scripts/start.sh`.
7. `cd /root/minecraft-cabal && docker compose up -d minecraft`

## Rollback to pre-Fabric vanilla

A snapshot of the pre-Fabric runtime is stored in `backups/pre-fabric-snapshot-*`. To revert:

1. Stop the server.
2. Copy `start.sh` and `server.jar` back from the snapshot.
3. Remove `fabric-server-launch.jar`, `libraries/`, `.fabric/`, and `mods/` from the server directory.
4. `cd /root/minecraft-cabal && docker compose up -d minecraft`

## Operator (admin)

`server/ops.json` is runtime-only and not committed. Start from the checked-in template and populate with environment-specific values:

```bash
cp /root/minecraft-cabal/server/ops.example.json /root/minecraft-cabal/server/ops.json
```

Or generate from env/secrets during provisioning:

```bash
export MC_OP_NAME="your-op-name"
export MC_OP_UUID="your-op-uuid"
python3 - <<'PY'
import json, os, pathlib
p = pathlib.Path("/root/minecraft-cabal/server/ops.json")
p.write_text(json.dumps([{
    "uuid": os.environ["MC_OP_UUID"],
    "name": os.environ["MC_OP_NAME"],
    "level": 4,
    "bypassesPlayerLimit": False
}], indent=2) + "\n")
PY
```

## Server Code of Conduct

The in-game code of conduct prompt is enabled (`enable-code-of-conduct=true`).
Edit `/root/minecraft-cabal/server/codeofconduct/en_us.txt` to change the text shown to players.

## Whitelist

`white-list=false` by default. To enable: set `white-list=true` in `server/server.properties.template`, restart, then `/whitelist add PlayerName` as an op.

## SMP Dashboard (website + API)

### At a glance (how it all fits together)

- **Minecraft server** writes live state to disk (`server/`): world data, claims, spawn/protection, and economy tables.
- **API (`api/`)** reads that state and serves JSON on `:4866` (server status, content, map payload).
- **Web frontend (`web/`)** is a Vite + React SPA that calls the API (via `VITE_API_BASE_URL`) and renders the public dashboard/map.
- **Map generation** is a two-step model: biome cache is precomputed offline (`npm run generate-map-cache`), while claims/spawn are merged live by the API.

### Quick start / restart commands (humans first)

```bash
# Minecraft game server
cd /root/minecraft-cabal
docker compose up -d minecraft
docker compose restart minecraft
docker compose ps minecraft
docker compose logs -f minecraft

# PM2 helper (includes API-focused workflows)
cd /root/minecraft-cabal
./pm2-manager.sh start
./pm2-manager.sh restart
./pm2-manager.sh status
```

The web app lives in `web/` and is included in this repo so anyone can run it locally or deploy it directly.

### API (`api/`)

A Fastify TypeScript service on **port 4866** that reads game state from disk and exposes it as JSON:

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Liveness check |
| `GET /api/server` | Online players, version, MOTD via Minecraft status ping |
| `GET /api/content` | Server description and latest updates (edit `api/content.json`) |
| `GET /api/map` | **Biome cells** come from pre-built **`api/cache/map-biomes.json`**; **claims/spawn** are merged live from the server dir. Returns **503** if the cache file is missing or stale vs `level.dat` / `server.properties`. |

**Biomes are not generated inside the API process** (that blocked the event loop and caused OOM). Generate offline with **`npm run generate-map-cache`** (uses an 8GB Node heap). The cache is **compact** (~10–20 KiB JSON): step **32** blocks, **palette + integer grid** instead of per-cell objects. Re-run after **moving spawn**, changing **spawn-protection**, or when you need a larger sampled area (new claims far out). The API does **not** invalidate the cache when `level.dat` is merely saved—only when spawn coordinates or protection no longer match the values stored in the cache file. **`pm2-manager.sh start`** runs generation automatically **only if** the cache file is absent. Claims update every ~30s without regenerating biomes. Redeploy the **web** app when the map JSON shape changes.

```bash
# Dev
cd api && npm run dev

# Production (managed by PM2)
cd /root/minecraft-cabal
./pm2-manager.sh start
./pm2-manager.sh status
./pm2-manager.sh logs 100
```

CORS allows localhost dev and **`https://smp.thecabal.app`** by default. Override with `CORS_ORIGINS` (comma-separated) in `ecosystem.config.js` / PM2.

### Nginx + TLS (this server — API only)

| Host | Purpose |
|------|---------|
| `minecraftapi.thecabal.app` | Reverse proxy → `127.0.0.1:4866` (Let’s Encrypt on the VPS) |
| `smp.thecabal.app` | **Cloudflare Pages** (Wrangler) — not served by nginx here |

DNS: **A** (or CNAME per CF) for `minecraftapi` → this machine. Port **80** must reach the server for HTTP-01 renewal unless you use DNS validation.

```bash
sudo mkdir -p /var/www/certbot
./deploy/install-nginx.sh bootstrap

sudo certbot certonly --webroot -w /var/www/certbot \
  -d minecraftapi.thecabal.app \
  --email contact@thecabal.app --agree-tos --non-interactive

./deploy/install-nginx.sh api-tls
```

### Frontend (`web/`) — Cloudflare Pages

Vite + React SPA. **Deploy = one command** from `web/` (builds `dist/` in-repo, uploads with Wrangler — nothing under `/var/www`):

```bash
cd web && npm install   # once, includes wrangler
npx wrangler login      # once
cd web && npm run deploy
```

- Config: `web/wrangler.toml` (`name` = Pages project slug; change if your CF project name differs).
- Production env for the build is in `web/.env.production` (`VITE_API_BASE_URL=https://minecraftapi.thecabal.app`).
- In **Cloudflare Pages** → project → **Custom domains**, attach `smp.thecabal.app` (and set the same env vars there if you build in CI instead of locally).

```bash
# Dev (needs API running)
cd web && npm run dev
```

### PM2 manager (API only)

The repo includes a dedicated manager for the API process only:

```bash
cd /root/minecraft-cabal
./pm2-manager.sh init
./pm2-manager.sh start
./pm2-manager.sh restart
./pm2-manager.sh status
./pm2-manager.sh logs 100
```

- PM2 process name: `cabal-smp-api`
- Ecosystem file: `/root/minecraft-cabal/ecosystem.config.js`
- Log folder: `/root/minecraft-cabal/logs`
- `restart` clears API logs, rebuilds `api/`, and starts clean
- `init` ensures ecosystem config exists and configures `pm2-logrotate`
- Minecraft control is intentionally out of scope for PM2 manager. Use Docker directly:
  - `cd /root/minecraft-cabal && docker compose restart minecraft`

## Economy + Marketplace mod mode

The Fabric mod now includes an economy/marketplace mode with four phased capabilities:

- `Phase 1`: `Caba SMP` HUD refresh loop with money, K/D, KDR, playtime, ping, and optional TPS line.
- `Phase 2`: SQLite-backed accounts + transactions with server-authoritative money mutation paths.
- `Phase 3`: Protected-item provenance records + ownership events with soft/hard enforcement toggles.
- `Phase 4`: `/auction` flow (`list`, `sell`, `buy`) with atomic money settlement.

Runtime files created in `server/`:

- `economy-config.json` (phase flags + reward tuning)
- `economy.db` (accounts, transactions, player_stats, item_provenance, ownership_events, auction_listings)

Commands:

- Player: `/balance`, `/pay <player> <amount>`, `/baltop`
- Admin economy: `/eco set|add|remove <player> <amount>`
- Auction: `/auction`, `/auction list`, `/auction sell <price>`, `/auction buy <id>`
- Provenance audit: `/provenance check` (main-hand protected items)

**API runtime policy:** use PM2 only for `cabal-smp-api` to avoid process-manager conflicts on `:4866`.
