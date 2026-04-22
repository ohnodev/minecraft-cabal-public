#!/usr/bin/env bash
# Immediate graceful restart: save now, stop now, bring container up.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_PASSWORD_FILE="${REPO_ROOT}/server/.rcon-password"
DEFAULT_SERVICE="minecraft"

RCON_HOST="${RCON_HOST:-127.0.0.1}"
RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD_FILE="${RCON_PASSWORD_FILE:-$DEFAULT_PASSWORD_FILE}"
MC_COMPOSE_SERVICE="${MC_COMPOSE_SERVICE:-$DEFAULT_SERVICE}"

if ! command -v mcrcon >/dev/null 2>&1; then
  echo "error: mcrcon not found. Install it and retry." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "error: docker not found." >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "error: docker compose not available; please install Docker Compose or enable the Docker Compose plugin." >&2
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

echo "[info] Saving world..."
mcr "save-all flush"

echo "[info] Stopping server..."
mcr "stop" || true
sleep 3

echo "[info] Starting docker service ${MC_COMPOSE_SERVICE}..."
(cd "${REPO_ROOT}" && docker compose up -d "${MC_COMPOSE_SERVICE}")

echo "[ok] Restart complete."
