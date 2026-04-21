# Docker (local or small hosts)

Run the same Fabric tree as production: the container only supplies **Java 25** and **`scripts/start.sh`**; everything else comes from the bind-mounted **`./server`** directory.

## Prerequisites

- Docker Engine with Compose v2 (`docker compose`) and the **Docker daemon running** (`docker info` should succeed).
- Under **`server/`**: `fabric-server-launch.jar`, `server.jar`, `libraries/`, `.fabric/` (from the Fabric installer), plus your **`mods/`** and config — same layout as a normal host install.
- **RCON**: either create **`server/.rcon-password`** (one line, gitignored) or set **`RCON_PASSWORD`** on the host before `docker compose up` (Compose passes it through; see **`docker/env.example`**). `start.sh` prefers **`RCON_PASSWORD`**, then the file. **Ports:** publish **`MC_SERVER_PORT`** / **`MC_RCON_PORT`** (defaults 25565 / 25575); set the same vars on the host so the `ports:` mapping targets the listener `start.sh` configures.

## Typical workflow

```bash
git pull origin main
docker compose up -d --build
docker compose logs -f minecraft
```

Stop gracefully (SIGINT so the world saves):

```bash
docker compose stop -t 90 minecraft
```

## Ports and memory

| Host variable          | Default | Purpose                                                          |
|------------------------|---------|------------------------------------------------------------------|
| `MC_HOST_PORT`         | `25565` | Published Java game port                                         |
| `MC_RCON_HOST_PORT`    | `25575` | Published RCON (optional tooling)                                |
| `MC_BEDROCK_HOST_PORT` | `19132` | Published Bedrock listener (UDP) served by Geyser-Fabric         |
| `MC_MIN_MEM`           | `4G`    | JVM `-Xms` (raise for production-like load)                      |
| `MC_MAX_MEM`           | `4G`    | JVM `-Xmx`                                                       |

Bedrock clients connect to **`MC_BEDROCK_HOST_PORT`** (UDP). Geyser-Fabric
(`server/mods/geyser-fabric-*.jar`) provides the Bedrock listener, and
Floodgate-Fabric (`server/mods/Floodgate-Fabric-*.jar`) authenticates Bedrock
players against the Java backend so they don't need Java accounts. Both
generate their default configs under `server/config/Geyser-Fabric/` and
`server/config/floodgate/` on first boot — edit those to change the bind
address, MOTD, or auth mode.

Example:

```bash
export MC_MIN_MEM=8G MC_MAX_MEM=8G MC_HOST_PORT=25565
docker compose up -d --build
```

Inside the container the game listens on **`MC_SERVER_PORT`** (default **25565**) on all interfaces; **`scripts/start.sh`** uses **`MC_BIND_MODE=public`** (set by the image / compose) instead of the production nginx loopback layout.

## File ownership

By default the process runs as **root** in the container, which may create root-owned files under **`server/`** on Linux. To match your UID/GID, add to **`docker-compose.yml`** under `minecraft:`:

```yaml
user: "${UID}:${GID}"
```

Then `export UID GID` before `docker compose up` (values from `id -u` / `id -g`).
