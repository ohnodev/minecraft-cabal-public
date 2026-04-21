#!/usr/bin/env bash
# Cabal SMP — scheduled maintenance: in-game countdown and graceful Docker restart.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_PASSWORD_FILE="${REPO_ROOT}/server/.rcon-password"
DEFAULT_SERVICE="minecraft"

RCON_HOST="${RCON_HOST:-127.0.0.1}"
RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD_FILE="${RCON_PASSWORD_FILE:-$DEFAULT_PASSWORD_FILE}"
MC_COMPOSE_SERVICE="${MC_COMPOSE_SERVICE:-$DEFAULT_SERVICE}"

usage() {
  cat <<'EOF'
Cabal SMP — scheduled maintenance: in-game countdown and graceful Docker restart.

Requirements:
  - mcrcon (https://github.com/Tiiffi/mcrcon)
  - docker + docker compose plugin
  - RCON enabled in server.properties (enable-rcon=true, rcon.password, rcon.port=25575)
  - Password in env RCON_PASSWORD or one-line file at RCON_PASSWORD_FILE

Usage:
  sudo -E RCON_PASSWORD='your-secret' ./scripts/mc-maintenance-restart.sh

Options:
  --service NAME    docker compose service (default: minecraft)
  -h, --help        This help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --service)
      MC_COMPOSE_SERVICE="${2:-}"
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
  echo "error: mcrcon not found. Install it and retry." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "error: docker not found." >&2
  exit 1
fi

if [[ -z "${RCON_PASSWORD:-}" ]] && [[ -f "${RCON_PASSWORD_FILE}" ]]; then
  RCON_PASSWORD="$(tr -d '\r\n' < "${RCON_PASSWORD_FILE}")"
fi
if [[ -z "${RCON_PASSWORD:-}" ]]; then
  echo "error: set RCON_PASSWORD or create ${RCON_PASSWORD_FILE}." >&2
  exit 1
fi

mcr() {
  mcrcon -H "${RCON_HOST}" -P "${RCON_PORT}" -p "${RCON_PASSWORD}" "$@"
}

broadcast() {
  local msg="$1"
  mcr "say §6[Cabal SMP]§r ${msg}" || echo "[warn] RCON broadcast failed." >&2
}

wait_for_rcon() {
  local max_attempts="${1:-90}"
  local i=0
  while [[ "${i}" -lt "${max_attempts}" ]]; do
    if mcr "list" >/dev/null 2>&1; then
      return 0
    fi
    i=$((i + 1))
    sleep 2
  done
  return 1
}

echo "[info] Maintenance window (docker service: ${MC_COMPOSE_SERVICE})"
wait_for_rcon 30

broadcast "§cScheduled maintenance: server restart in 2 minutes. Please reach a safe place."
sleep 60
broadcast "§cRestart in 1 minute."
sleep 30
broadcast "§cRestart in 30 seconds."
sleep 20
broadcast "§cRestart in 10 seconds."
sleep 10

echo "[info] Saving worlds..."
mcr "save-all flush" || mcr "save-all" || true
sleep 2
echo "[info] Stopping via RCON..."
mcr "stop" || true
sleep 6

echo "[info] Starting Docker service..."
(cd "${REPO_ROOT}" && docker compose up -d "${MC_COMPOSE_SERVICE}")

if ! wait_for_rcon 120; then
  echo "[warn] RCON not reachable after restart window." >&2
fi

broadcast "§aMaintenance complete. Thank you for your patience."
echo "[ok] Done."
