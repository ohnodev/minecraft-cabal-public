# Minecraft Cabal Setup Commands (Polished)

This is a cleaned, reproducible command sequence for server setup and networking.

## 1) Cleanup and clone

> **Safety warning:** `pkill -9` sends immediate `SIGKILL` and can corrupt in-flight writes (including world data).  
> `userdel -r` deletes the user home/data permanently.  
> Only run these on fresh machines or after confirmed backups. Prefer graceful shutdown first:
> `cd /root/minecraft-cabal && docker compose stop minecraft`

- `sudo pkill -9 -u minecraft`
- `sudo userdel -r minecraft`
- `getent passwd minecraft`
- `git clone https://github.com/ohnodev/minecraft-cabal-public /root/minecraft-cabal`

## 2) Base packages

- `sudo apt-get update`
- `sudo apt-get install -y git curl ca-certificates openssl ufw mcrcon python3 docker.io docker-compose-plugin`
- `command -v mcrcon`

## 3) Java and Fabric runtime

- `java -version` (must be Java 21+)
- `openssl rand -base64 32 > /root/minecraft-cabal/server/.rcon-password`
- `chmod 600 /root/minecraft-cabal/server/.rcon-password`
- `[ -f /root/minecraft-cabal/server/ops.json ] || cp /root/minecraft-cabal/server/ops.example.json /root/minecraft-cabal/server/ops.json`
- (optional env-driven op bootstrap)
  ```bash
  export MC_OP_NAME="your-op-name"
  export MC_OP_UUID="your-op-uuid"
  python3 - <<'PY'
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
  ```
- `curl -fL "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.1/fabric-installer-1.1.1.jar" -o /root/minecraft-cabal/server/fabric-installer.jar` (use current installer version, 1.1.1+)
- `ls -lh /root/minecraft-cabal/server/fabric-installer.jar` (verify file exists at exact path before install)
- `java -jar /root/minecraft-cabal/server/fabric-installer.jar server -mcversion 1.21.11 -loader 0.19.2 -downloadMinecraft -dir /root/minecraft-cabal/server`

## 4) Docker runtime (required)

- `cd /root/minecraft-cabal`
- `sudo systemctl disable --now minecraft-cabal || true` (explicitly avoid systemd runtime)
- `sudo docker compose up -d --build minecraft`
- `sudo docker compose ps minecraft`
- `sudo docker compose logs --tail=120 minecraft`

## 5) Firewall (Java + Bedrock)

- `sudo ufw allow OpenSSH`
- `sudo ufw allow 25565/tcp`
- `sudo ufw allow 19132/udp`
- `sudo ufw --force enable`
- `sudo ufw status verbose`

## 6) Docker networking checks

- `sudo docker compose ps minecraft`
- `sudo ss -lnt` (expect `0.0.0.0:25565`)
- `sudo ss -lnu` (expect `*:19132`)

## 7) Via + Grim + Geyser/Floodgate compatibility

- `sudo sed -i 's/^\([[:space:]]*use-direct-connection:\).*/\1 false/' /root/minecraft-cabal/server/config/Geyser-Fabric/config.yml`
- `rg -n "use-direct-connection" /root/minecraft-cabal/server/config/Geyser-Fabric/config.yml`
- `sudo docker compose restart minecraft`

## 8) Verify final state

- `sudo docker compose ps minecraft`
- `sudo ss -lnt` (expect `0.0.0.0:25565`)
- `sudo ss -lnu` (expect `*:19132`)
- `sudo docker compose logs --tail=200 minecraft`

## 9) Optional AntiXray install (1.21.11)

- Download `antixray-fabric-1.4.13+1.21.11.jar` to `/root/minecraft-cabal/server/mods/`
- `sudo docker compose restart minecraft`
- Verify log line: `Successfully initialized antixray for fabric`

---

Keep this file as a practical command log/runbook for videos and repeatable provisioning.
