# Minecraft Cabal Agent Playbook

Use this file to drive one-shot server bootstraps with an agent.

## Copy/Paste Prompt (Full Setup)

```text
You are setting up a fresh Debian/Ubuntu server for minecraft-cabal-public.

Goal:
- Clone the repo to /root/minecraft-cabal
- Install required host packages (git, curl, ca-certificates, openssl, ufw, docker + docker compose plugin)
- Install/verify Java 21+ (Java 25 preferred if available)
- Run Minecraft via Docker Compose only (never systemd)
- Ensure Bedrock + Java connectivity is open and working
- Keep anti-xray config behavior as-is (if anti-xray jar is present, do not retune config)
- Log each executed command to /root/minecraft-cabal-setup-commands.md

Requirements:
1) Clone repo
   - git clone https://github.com/ohnodev/minecraft-cabal-public /root/minecraft-cabal

2) Install packages
   - apt-get update
   - apt-get install -y git curl ca-certificates openssl ufw python3 docker.io docker-compose-plugin

3) Java
   - Verify java -version (must be 21+)
   - If missing, install a JDK >= 21

4) Minecraft runtime files
   - Ensure /root/minecraft-cabal/server/.rcon-password exists (create secure random value if missing, chmod 600)
   - Ensure /root/minecraft-cabal/server/ops.json exists from env-specific values (do not commit runtime ops.json):
     - [ -f /root/minecraft-cabal/server/ops.json ] || cp /root/minecraft-cabal/server/ops.example.json /root/minecraft-cabal/server/ops.json
     - export MC_OP_NAME="your-op-name"
     - export MC_OP_UUID="your-op-uuid"
     - python3 - <<'PY'
       import json, os, pathlib, tempfile
       op_name = os.environ.get("MC_OP_NAME", "").strip()
       op_uuid = os.environ.get("MC_OP_UUID", "").strip()
       if not op_name or not op_uuid:
           raise SystemExit("MC_OP_NAME and MC_OP_UUID must be set and non-empty")
       p = pathlib.Path("/root/minecraft-cabal/server/ops.json")
       ops = []
       if p.exists():
           try:
               loaded = json.loads(p.read_text())
               if isinstance(loaded, list):
                   ops = loaded
           except json.JSONDecodeError:
               ops = []
       already_exists = any(
           isinstance(op, dict) and (
               op.get("uuid") == op_uuid or op.get("name") == op_name
           )
           for op in ops
       )
       if not already_exists:
           ops.append({
               "uuid": op_uuid,
               "name": op_name,
               "level": 4,
               "bypassesPlayerLimit": False
           })
       with tempfile.NamedTemporaryFile("w", delete=False, dir=str(p.parent), encoding="utf-8") as tmp:
           json.dump(ops, tmp, indent=2)
           tmp.write("\n")
           tmp_path = pathlib.Path(tmp.name)
       tmp_path.replace(p)
       print("ops.json updated:", p)
       PY
   - Ensure Fabric server runtime exists in /root/minecraft-cabal/server (fabric-server-launch.jar/server.jar/libraries/.fabric)
   - If missing, download Fabric installer and run:
     java -jar /root/minecraft-cabal/server/fabric-installer.jar server -mcversion 1.21.11 -loader 0.19.2 -downloadMinecraft -dir /root/minecraft-cabal/server

5) Start Minecraft (Docker only)
   - cd /root/minecraft-cabal
   - docker compose up -d --build minecraft
   - docker compose ps minecraft
   - docker compose logs --tail=120 minecraft

6) Network + firewall
   - ufw allow OpenSSH
   - ufw allow 25565/tcp
   - ufw allow 19132/udp
   - ufw --force enable

7) Docker runtime policy
   - Ensure no systemd runtime is used for Minecraft:
     - systemctl disable --now minecraft-cabal || true
   - Use only:
     - docker compose up -d minecraft
     - docker compose restart minecraft

8) Via + Grim + Geyser/Floodgate compatibility guard
   - In server/config/Geyser-Fabric/config.yml set:
     advanced.java.use-direct-connection: false
   - Restart container after this change:
     docker compose restart minecraft

9) Verification
   - docker compose ps minecraft should be Up
   - ss -lnt should show 0.0.0.0:25565 (published by Docker)
   - ss -lnu should show *:19132 (bedrock/geyser)
   - ufw status verbose should include 22/tcp, 25565/tcp, 19132/udp
   - docker compose logs --tail=200 minecraft should show normal startup and no persistent bedrock pipeline exceptions

10) Output
   - Print a final summary:
     - Java version
     - Container status
     - Open/listening ports
     - Any warnings requiring manual follow-up
   - Keep everything idempotent and safe to rerun.
```

## Notes

- Java clients connect on TCP `25565` (published directly by Docker).
- Bedrock clients connect on UDP `19132`.
- Keep `server/config/antixray.toml` tuned values unchanged unless explicitly requested.
