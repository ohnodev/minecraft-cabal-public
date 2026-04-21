#!/usr/bin/env bash
# Cabal SMP — scheduled maintenance: in-game countdown and graceful restart.
#
# Requirements on the host:
#   - mcrcon (https://github.com/Tiiffi/mcrcon) — e.g. apt install mcrcon (if packaged) or build from source.
#   - RCON enabled in server.properties: enable-rcon=true, rcon.password=<secret>, rcon.port=25575 (default).
#   - Harden RCON with a strong rcon.password and network policy: restrict who can reach rcon.port (host firewall,
#     iptables/nftables, security groups) or put RCON behind a proxy.
#   - Cabal production runs behind nginx stream proxy, so Minecraft binds to loopback (server-ip=127.0.0.1,
#     server-port=25566) and nginx owns public :25565. Keep that architecture unless you intentionally remove nginx.
#   - Password in env RCON_PASSWORD or one-line file at RCON_PASSWORD_FILE (default: server/.rcon-password).
#   - systemd: run with sudo for systemctl (e.g. sudo -E ./scripts/mc-maintenance-restart.sh).
#
# Typical usage (after RCON is configured):
#   sudo -E RCON_PASSWORD='your-secret' ./scripts/mc-maintenance-restart.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_PASSWORD_FILE="${REPO_ROOT}/server/.rcon-password"

MC_SERVICE="${MC_SERVICE:-minecraft-cabal}"
RCON_HOST="${RCON_HOST:-127.0.0.1}"
RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD_FILE="${RCON_PASSWORD_FILE:-$DEFAULT_PASSWORD_FILE}"

usage() {
  cat <<'EOF'
Cabal SMP — scheduled maintenance: in-game countdown and graceful restart.

Requirements on the host:
  - mcrcon (https://github.com/Tiiffi/mcrcon)
  - RCON enabled in server.properties (enable-rcon=true, rcon.password, rcon.port=25575)
  - Password in env RCON_PASSWORD or one-line file at RCON_PASSWORD_FILE
    (default: server/.rcon-password)
  - systemd: run with sudo (e.g. sudo -E ./scripts/mc-maintenance-restart.sh)

Typical usage:
  sudo -E RCON_PASSWORD='your-secret' ./scripts/mc-maintenance-restart.sh

Options:
  --service NAME              systemd unit (default: minecraft-cabal)
  -h, --help                  This help

Environment:
  RCON_PASSWORD, RCON_HOST, RCON_PORT, RCON_PASSWORD_FILE
EOF
}

# Reject another flag or empty where a real value is required (e.g. --service --help).
reject_flag_like_arg() {
  local flag="$1"
  local val="${2:-}"
  if [[ -z "${val}" ]] || [[ "${val}" == -* ]]; then
    echo "error: ${flag} requires a non-flag argument (got: ${val:-empty})" >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --service)
      reject_flag_like_arg "--service" "${2:-}"
      MC_SERVICE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v mcrcon >/dev/null 2>&1; then
  echo "error: mcrcon not found. Install it (e.g. https://github.com/Tiiffi/mcrcon) and ensure it is on PATH." >&2
  exit 1
fi

if [[ -z "${RCON_PASSWORD:-}" ]]; then
  if [[ -f "${RCON_PASSWORD_FILE}" ]]; then
    RCON_PASSWORD="$(tr -d '\r\n' < "${RCON_PASSWORD_FILE}")"
  fi
fi

if [[ -z "${RCON_PASSWORD:-}" ]]; then
  echo "error: set RCON_PASSWORD or create ${RCON_PASSWORD_FILE} with one line (see server/.rcon-password.example)." >&2
  exit 1
fi

if [[ "${EUID}" -ne 0 ]]; then
  echo "error: run with sudo so systemctl can start/stop ${MC_SERVICE}." >&2
  exit 1
fi

mcr() {
  mcrcon -H "${RCON_HOST}" -P "${RCON_PORT}" -p "${RCON_PASSWORD}" "$@"
}

broadcast() {
  local msg="$1"
  # Visible to all online players (non-fatal so a degraded RCON path can still run stop/start)
  mcr "say §6[Cabal SMP]§r ${msg}" || echo "[warn] RCON broadcast failed (say)." >&2
}

wait_for_rcon() {
  local max_attempts="${1:-90}"
  local i=0
  echo "[info] Waiting for server to accept RCON (${RCON_HOST}:${RCON_PORT})..."
  while [[ "${i}" -lt "${max_attempts}" ]]; do
    if mcr "list" >/dev/null 2>&1; then
      echo "[ok] RCON accepted."
      return 0
    fi
    i=$((i + 1))
    sleep 2
  done
  echo "error: RCON not accepting connections after ~$((max_attempts * 2))s." >&2
  return 1
}

# True while systemd still considers the unit up or mid-shutdown (avoids racing is-active vs deactivating).
service_stop_in_progress() {
  local active sub
  active="$(systemctl show -p ActiveState --value "${MC_SERVICE}" 2>/dev/null || true)"
  sub="$(systemctl show -p SubState --value "${MC_SERVICE}" 2>/dev/null || true)"
  [[ "${active}" == "active" || "${sub}" == "deactivating" ]]
}

# Wait until ActiveState is inactive/failed or timeout (seconds). Uses ActiveState/SubState so we do not treat
# SubState=deactivating as "already stopped" (is-active can be false while stop is still in flight).
wait_until_unit_stopped() {
  local max_waits="${1:?}"
  local reason="${2:-stop}"
  local w=0
  while [[ "${w}" -lt "${max_waits}" ]]; do
    local active
    active="$(systemctl show -p ActiveState --value "${MC_SERVICE}" 2>/dev/null || true)"
    if [[ "${active}" == "inactive" || "${active}" == "failed" ]]; then
      return 0
    fi
    sleep 1
    w=$((w + 1))
  done
  active="$(systemctl show -p ActiveState --value "${MC_SERVICE}" 2>/dev/null || true)"
  if [[ "${active}" == "active" ]] || service_stop_in_progress; then
    echo "[warn] Timeout (${max_waits}s) waiting for ${MC_SERVICE} to become inactive (${reason}); continuing." >&2
  fi
  return 0
}

phase_countdown_main() {
  broadcast "§cScheduled maintenance: server restart in 2 minutes. Please reach a safe place."
  sleep 60
  broadcast "§cRestart in 1 minute."
  sleep 30
  broadcast "§cRestart in 30 seconds."
  sleep 20
  broadcast "§cRestart in 10 seconds."
  sleep 10
}

graceful_stop_and_start() {
  echo "[info] Saving worlds..."
  mcr "save-all flush" || mcr "save-all" || true
  sleep 3
  echo "[info] Stopping Minecraft (RCON stop)..."
  mcr "stop" || true
  echo "[info] Waiting for process to exit..."
  wait_until_unit_stopped 120 "after RCON stop"
  local active_after_wait
  active_after_wait="$(systemctl show -p ActiveState --value "${MC_SERVICE}" 2>/dev/null || true)"
  if [[ "${active_after_wait}" == "active" ]]; then
    echo "[warn] Service still active after wait; issuing systemctl stop."
    systemctl stop "${MC_SERVICE}" || true
    wait_until_unit_stopped 60 "after systemctl stop"
    active_after_wait="$(systemctl show -p ActiveState --value "${MC_SERVICE}" 2>/dev/null || true)"
    if [[ "${active_after_wait}" == "active" ]] || service_stop_in_progress; then
      echo "[warn] Service still stopping or active after systemctl stop; proceeding with start anyway." >&2
    fi
  fi
  echo "[info] Starting ${MC_SERVICE}..."
  systemctl start "${MC_SERVICE}"
  if ! wait_for_rcon 120; then
    echo "[warn] RCON not accepting after ${MC_SERVICE} start (within timeout); continuing." >&2
  fi
}

# --- main ---
echo "[info] Maintenance window: ${MC_SERVICE} (RCON ${RCON_HOST}:${RCON_PORT})"
wait_for_rcon 30

phase_countdown_main
graceful_stop_and_start

broadcast "§aMaintenance complete. Thank you for your patience."
echo "[ok] Done."
