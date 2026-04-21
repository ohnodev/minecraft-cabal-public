# Minecraft Cabal Agent Playbook

Use this file to drive one-shot server bootstraps with an agent.

## Copy/Paste Prompt (Full Setup)

```text
You are setting up a fresh Debian/Ubuntu server for minecraft-cabal-public.

Goal:
- Clone the repo to /root/minecraft-cabal
- Install required host packages (git, curl, ca-certificates, openssl, ufw, nginx, libnginx-mod-stream, docker + docker compose plugin)
- Install/verify Java 21+ (Java 25 preferred if available)
- Install and start the Minecraft service via systemd
- Ensure Bedrock + Java connectivity is open and working
- Keep anti-xray config behavior as-is (if anti-xray jar is present, do not retune config)
- Log each executed command to /root/minecraft-cabal-setup-commands.md

Requirements:
1) Clone repo
   - git clone https://github.com/ohnodev/minecraft-cabal-public /root/minecraft-cabal

2) Install packages
   - apt-get update
   - apt-get install -y git curl ca-certificates openssl ufw nginx libnginx-mod-stream docker.io docker-compose-plugin

3) Java
   - Verify java -version (must be 21+)
   - If missing, install a JDK >= 21

4) Minecraft runtime files
   - Ensure /root/minecraft-cabal/server/.rcon-password exists (create secure random value if missing, chmod 600)
   - Ensure Fabric server runtime exists in /root/minecraft-cabal/server (fabric-server-launch.jar/server.jar/libraries/.fabric)
   - If missing, download Fabric installer and run:
     java -jar /root/minecraft-cabal/server/fabric-installer.jar server -mcversion 1.21.11 -loader 0.19.2 -downloadMinecraft -dir /root/minecraft-cabal/server

5) Install/enable service
   - cd /root/minecraft-cabal
   - ./deploy/install-systemd-minecraft.sh
   - systemctl enable --now minecraft-cabal

6) Network + firewall
   - ufw allow OpenSSH
   - ufw allow 25565/tcp
   - ufw allow 19132/udp
   - ufw --force enable

7) Nginx Java stream proxy
   - Ensure nginx.conf contains:
     stream { include /etc/nginx/streams-enabled/*.conf; }
   - Create /etc/nginx/streams-enabled/minecraft-cabal.conf mapping:
     listen 25565;
     proxy_pass 127.0.0.1:25566;
   - nginx -t && systemctl reload nginx

8) Via + Grim + Geyser/Floodgate compatibility guard
   - In server/config/Geyser-Fabric/config.yml set:
     advanced.java.use-direct-connection: false
   - Restart minecraft-cabal after this change

9) Verification
   - systemctl is-active minecraft-cabal (must be active)
   - ss -lnt should show 0.0.0.0:25565 (nginx) and 127.0.0.1:25566 (minecraft)
   - ss -lnu should show *:19132 (bedrock/geyser)
   - ufw status verbose should include 22/tcp, 25565/tcp, 19132/udp
   - journalctl -u minecraft-cabal -n 200 --no-pager should show normal startup and no persistent bedrock pipeline exceptions

10) Output
   - Print a final summary:
     - Java version
     - Service status
     - Open/listening ports
     - Any warnings requiring manual follow-up
   - Keep everything idempotent and safe to rerun.
```

## Notes

- Java clients connect on TCP `25565` (nginx stream -> local `25566`).
- Bedrock clients connect on UDP `19132`.
- Keep `server/config/antixray.toml` tuned values unchanged unless explicitly requested.
