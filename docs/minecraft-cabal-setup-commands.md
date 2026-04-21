# Minecraft Cabal Setup Commands (Polished)

This is a cleaned, reproducible command sequence for server setup and networking.

## 1) Cleanup and clone

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
- `python3 (download latest fabric installer to /root/minecraft-cabal/server/fabric-installer.jar)`
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
- `edit /etc/nginx/nginx.conf (add: stream { include /etc/nginx/streams-enabled/*.conf; })`
- `create /etc/nginx/streams-enabled/minecraft-cabal.conf (listen 25565 -> proxy_pass 127.0.0.1:25566)`
- `sudo nginx -t`
- `sudo systemctl reload nginx`

## 7) Via + Grim + Geyser/Floodgate compatibility

- `edit /root/minecraft-cabal/server/config/Geyser-Fabric/config.yml`
- `set advanced.java.use-direct-connection: false`
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
