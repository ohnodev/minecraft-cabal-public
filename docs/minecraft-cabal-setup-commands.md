# Minecraft Cabal Setup Commands (Polished)

This is a cleaned, reproducible command sequence for server setup and networking.

## 1) Cleanup and clone

> **Safety warning:** `pkill -9` sends immediate `SIGKILL` and can corrupt in-flight writes (including world data).  
> `userdel -r` deletes the user home/data permanently.  
> Only run these on fresh machines or after confirmed backups. Prefer graceful shutdown first:
> `sudo systemctl stop minecraft-cabal`

- `sudo pkill -9 -u minecraft`
- `sudo userdel -r minecraft`
- `getent passwd minecraft`
- `git clone https://github.com/ohnodev/minecraft-cabal-public /root/minecraft-cabal`

## 2) Base packages

- `sudo apt-get update`
- `sudo apt-get install -y git curl ca-certificates openssl ufw nginx libnginx-mod-stream docker.io docker-compose-plugin`

## 3) Java and Fabric runtime

- `java -version` (must be Java 21+)
- `openssl rand -base64 32 > /root/minecraft-cabal/server/.rcon-password`
- `chmod 600 /root/minecraft-cabal/server/.rcon-password`
- `curl -fL "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.1/fabric-installer-1.1.1.jar" -o /root/minecraft-cabal/server/fabric-installer.jar` (use current installer version, 1.1.1+)
- `ls -lh /root/minecraft-cabal/server/fabric-installer.jar` (verify file exists at exact path before install)
- `java -jar /root/minecraft-cabal/server/fabric-installer.jar server -mcversion 1.21.11 -loader 0.19.2 -downloadMinecraft -dir /root/minecraft-cabal/server`

## 4) systemd service

- `cd /root/minecraft-cabal`
- `sudo ./deploy/install-systemd-minecraft.sh`
- `sudo systemctl enable --now minecraft-cabal`
- `sudo systemctl status minecraft-cabal --no-pager`
- `sudo journalctl -u minecraft-cabal -n 120 --no-pager`

## 5) Firewall (Java + Bedrock)

- `sudo ufw allow OpenSSH`
- `sudo ufw allow 25565/tcp`
- `sudo ufw allow 19132/udp`
- `sudo ufw --force enable`
- `sudo ufw status verbose`

## 6) Nginx stream (Java public port)

- `sudo mkdir -p /etc/nginx/streams-enabled`
- `sudo cp /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak-minecraft-stream-$(date +%Y%m%d_%H%M%S)`
- `sudo grep -qE '^\s*stream\s*\{' /etc/nginx/nginx.conf || printf '\nstream {\n    include /etc/nginx/streams-enabled/*.conf;\n}\n' | sudo tee -a /etc/nginx/nginx.conf > /dev/null`
- Create `/etc/nginx/streams-enabled/minecraft-cabal.conf`:
  ```bash
  sudo tee /etc/nginx/streams-enabled/minecraft-cabal.conf > /dev/null <<'EOF'
  server {
      listen 25565;
      proxy_pass 127.0.0.1:25566;
      proxy_connect_timeout 5s;
      proxy_timeout 1h;
  }
  EOF
  ```
- `sudo nginx -t`
- `sudo systemctl reload nginx`

## 7) Via + Grim + Geyser/Floodgate compatibility

- `sudo sed -i 's/^\([[:space:]]*use-direct-connection:\).*/\1 false/' /root/minecraft-cabal/server/config/Geyser-Fabric/config.yml`
- `rg -n "use-direct-connection" /root/minecraft-cabal/server/config/Geyser-Fabric/config.yml`
- `sudo systemctl restart minecraft-cabal`

## 8) Verify final state

- `sudo systemctl is-active minecraft-cabal`
- `sudo ss -lnt` (expect `0.0.0.0:25565` and `127.0.0.1:25566`)
- `sudo ss -lnu` (expect `*:19132`)
- `sudo journalctl -u minecraft-cabal -n 200 --no-pager`

## 9) Optional AntiXray install (1.21.11)

- Download `antixray-fabric-1.4.13+1.21.11.jar` to `/root/minecraft-cabal/server/mods/`
- `sudo systemctl restart minecraft-cabal`
- Verify log line: `Successfully initialized antixray for fabric`

---

Keep this file as a practical command log/runbook for videos and repeatable provisioning.
